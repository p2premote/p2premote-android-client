package top.p2premote.android;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Active-side coordinator; select WS once, retain MQTT for transition peers. */
final class TraversalClient implements AutoCloseable {
    private static final String TAG = "TraversalClient";
    interface Check { void run() throws Exception; }
    private final WsConnection ws;
    private final ApiClient.OpenResult opened;
    private final long peer;
    private final String attempt;
    private final ConnectionPreferences preferences;
    private final Check check;
    private final java.util.function.Supplier<Boolean> ipv6Availability;
    private final java.util.function.LongSupplier clock;
    private final ArrayBlockingQueue<JSONObject> inbox = new ArrayBlockingQueue<>(64);
    private volatile JSONObject ready;
    private volatile AppException signalError;
    private volatile PunchSignalClient signal;
    private volatile boolean wsSignalSelected;
    // p2p/end 上报用：主动端发出的提议与被动端响应确认的选择（逗号连接，空=未走新协商）。
    private volatile String proposedNetworks = "";
    private volatile String acknowledgedNetworks = "";
    // NAT 先记协商证据（udp4 预探测），轮次失败诊断到达后按字段覆盖；lastNetwork 记最后一次实际调用。
    private volatile String localNatType = "";
    private volatile String remoteNatType = "";
    private volatile String lastNetwork = "";
    private final List<String> attemptedInternetNetworks = new ArrayList<>();
    List<String> attemptedNetworks() { return new ArrayList<>(attemptedInternetNetworks); }

    TraversalClient(WsConnection ws, ApiClient.OpenResult opened, long peer, String attempt,
                    ConnectionPreferences preferences, java.util.function.Supplier<Boolean> ipv6Availability, Check check) {
        this(ws, opened, peer, attempt, preferences, ipv6Availability, check, android.os.SystemClock::elapsedRealtime);
    }
    TraversalClient(WsConnection ws, ApiClient.OpenResult opened, long peer, String attempt,
                    ConnectionPreferences preferences, java.util.function.Supplier<Boolean> ipv6Availability, Check check,
                    java.util.function.LongSupplier clock) {
        this.ws = ws; this.opened = opened; this.peer = peer; this.attempt = attempt;
        this.preferences = preferences; this.check = check;
        this.clock = clock;
        this.ipv6Availability = ipv6Availability;
        ws.setTraversalListener(opened.connectionId, peer, attempt, this::receive, () -> {
            if (wsSignalSelected) { signalError = new AppException(ErrorCodes.SignalSendDisconnected, "signal connection lost"); cancelSignal(); }
        });
    }
    JSONObject negotiation() throws Exception {
        return new JSONObject().put("version", TraversalPolicy.VERSION).put("preferences",
                new JSONObject().put("prefer_ipv6", preferences.ipv6).put("prefer_tcp", preferences.tcp));
    }
    void receive(JSONObject data) {
        String type = data.optString("type");
        if ("attempt_ready".equals(type)) ready = data;
        else if ("attempt_failed".equals(type) || "attempt_cancel".equals(type)) {
            JSONObject error = data.optJSONObject("error");
            signalError = "attempt_cancel".equals(type)
                    ? new AppException(ErrorCodes.ConnectionExecutionPeerCancelled, "对端取消连接")
                    : SignalTransport.peerError(error);
            cancelSignal();
        }
        else if ("punch_signal".equals(type)) {
            try {
                PunchSignalClient active = signal;
                if (!wsSignalSelected || active == null) throw new AppException(ErrorCodes.SignalTransportSelectInvalidSelection, "signal_transport_mismatch");
                active.receive(data);
            } catch (Exception error) { signalError = AppException.from(error); cancelSignal(); }
        }
        else if ("traversal".equals(type)) {
            JSONObject frame = data.optJSONObject("frame");
            if (frame == null || !inbox.offer(frame)) signalError = new AppException(ErrorCodes.SignalReceiveInvalidFrame, "invalid traversal frame");
        }
    }
    boolean negotiated() throws Exception {
        if (ready == null) throw new AppException(ErrorCodes.TraversalPlanNegotiationInvalidPayload, "missing_traversal_ready");
        if (!ready.has("traversal_negotiation") || ready.isNull("traversal_negotiation")) return false;
        JSONObject accepted = ready.getJSONObject("traversal_negotiation");
        JSONObject p = accepted.getJSONObject("preferences");
        if (accepted.getInt("version") != TraversalPolicy.VERSION
                || p.getBoolean("prefer_ipv6") != preferences.ipv6
                || p.getBoolean("prefer_tcp") != preferences.tcp) {
            throw new AppException(ErrorCodes.TraversalPlanNegotiationMismatch, "traversal_preference_mismatch");
        }
        return true;
    }
    JSONObject transportOffer() throws Exception { return SignalTransport.offer(BuildConfig.SIGNAL_WS_ENABLED); }
    void configureSignal(long local, String token) throws Exception {
        check.run();
        wsSignalSelected = SignalTransport.selectedWs(ready, BuildConfig.SIGNAL_WS_ENABLED, negotiated());
        if (wsSignalSelected) {
            PunchSignalClient created = new PunchSignalClient(ws, opened, local, peer, attempt);
            signal = created;
            try {
                created.register("wg-config", token + "-kx", 120_000);
                check.run();
                if (signalError != null) throw signalError;
            } catch (Exception error) { created.close(); throw error; }
        }
    }
    PunchNative.ExchangeResult exchange(String token, String payload, int timeout) throws Exception {
        check.run();
        if (!wsSignalSelected) return PunchNative.exchange(token, payload, "active", 0, timeout);
        PunchSignalClient active = signal;
        if (active == null) throw new AppException(ErrorCodes.SignalReceiveContextMismatch, "signal_context_mismatch");
        return active.exchange(token, payload, timeout);
    }
    void cancelSignal() {
        PunchSignalClient active = signal;
        if (active != null) active.close();
    }
    private void send(JSONObject frame) throws Exception {
        check.run();
        if (!ws.sendNotifyWaitAck(opened.connectionId, peer, ApiClient.buildMessageId(), opened.accessGrant,
                new JSONObject().put("type", "traversal").put("attempt_id", attempt).put("frame", frame).toString())) {
            throw new AppException(ErrorCodes.SignalSendRejected, "traversal_signal_failed");
        }
    }
    private static int stage(String kind) {
        switch (kind) {
            case "capabilities": return 0;
            case "plan": return 1;
            case "plan_ack": return 2;
            case "prepare": return 3;
            case "ready": return 4;
            case "result": return 5;
            case "commit": case "close": return 6;
            case "committed": case "closed": return 7;
            default: return 99;
        }
    }
    private JSONObject waitFrame(int round, String kind) throws Exception {
        return waitFrame(round, kind, 12_000);
    }
    JSONObject waitFrame(int round, String kind, long timeoutMillis) throws Exception {
        long end = clock.getAsLong() + timeoutMillis;
        while (clock.getAsLong() < end) {
            check.run();
            if (signalError != null) throw signalError;
            JSONObject frame = inbox.poll(200, TimeUnit.MILLISECONDS);
            if (frame == null) continue;
            int receivedRound = frame.optInt("round", 0);
            int receivedStage = stage(frame.optString("kind"));
            if (receivedRound < round || (receivedRound == round && receivedStage < stage(kind))) continue;
            if (receivedRound != round || !kind.equals(frame.optString("kind"))) throw new AppException(ErrorCodes.TraversalRoundSyncInvalidSequence, "invalid_traversal_sequence");
            return frame;
        }
        throw new AppException("capabilities".equals(kind) ? ErrorCodes.TraversalCapabilityExchangeTimeout
                : "plan".equals(kind) || "plan_ack".equals(kind) ? ErrorCodes.TraversalPlanNegotiationTimeout
                : ErrorCodes.TraversalRoundSyncTimeout, "traversal wait timed out");
    }
    private static JSONObject frame(String kind, int round) throws Exception {
        JSONObject result = new JSONObject().put("kind", kind);
        if (round > 0) result.put("round", round);
        return result;
    }
    private static List<TraversalPolicy.Evidence> evidence(JSONArray values) throws Exception {
        if (values.length() > 32) throw new AppException(ErrorCodes.TraversalCapabilityExchangeInvalidPayload, "invalid_traversal_capabilities");
        List<TraversalPolicy.Evidence> result = new ArrayList<>();
        for (int i = 0; i < values.length(); i++) {
            JSONObject value = values.getJSONObject(i);
            result.add(new TraversalPolicy.Evidence(value.getString("network"), value.getString("nat_type")));
        }
        return result;
    }
    PunchNative.TunnelResult start(JSONObject template) throws Exception {
        if (!negotiated()) return accept(PunchNative.startUdpTunnel(template.toString()));
        check.run();
        if (wsSignalSelected) signal.closeCall("wg-config");
        JSONArray local = new JSONArray(PunchNative.nativeDetectNat());
        // Limit duplicates from multi-interface native detection.
        JSONArray bounded = new JSONArray();
        for (int i = 0; i < Math.min(local.length(), 32); i++) bounded.put(local.getJSONObject(i));
        localNatType = TraversalPolicy.udp4NatType(evidence(bounded));
        Boolean ipv6 = ipv6Availability.get();
        JSONObject capabilities = frame("capabilities", 0).put("evidence", bounded)
                .put("ipv6_gate_supported", true)
                .put("lan_probe_budget_secs", TraversalPolicy.LAN_PROBE_BUDGET_SECS);
        if (ipv6 != null) capabilities.put("ipv6_available", ipv6);
        send(capabilities);
        JSONObject remoteCapabilities = waitFrame(0, "capabilities");
        JSONArray remote = remoteCapabilities.getJSONArray("evidence");
        Object remoteValue = remoteCapabilities.opt("ipv6_available");
        if (remoteValue != null && remoteValue != JSONObject.NULL && !(remoteValue instanceof Boolean)) {
            throw new AppException(ErrorCodes.TraversalCapabilityExchangeInvalidPayload, "invalid_traversal_capabilities");
        }
        Boolean remoteIpv6 = remoteValue instanceof Boolean ? (Boolean) remoteValue : null;
        Object gateValue = remoteCapabilities.opt("ipv6_gate_supported");
        if (gateValue != null && !(gateValue instanceof Boolean)) throw new AppException(ErrorCodes.TraversalCapabilityExchangeInvalidPayload, "invalid_traversal_capabilities");
        boolean remoteGate = Boolean.TRUE.equals(gateValue);
        Object remoteLanBudgetValue = remoteCapabilities.opt("lan_probe_budget_secs");
        Integer remoteLanBudget = null;
        if (remoteLanBudgetValue != null && remoteLanBudgetValue != JSONObject.NULL) {
            if (!(remoteLanBudgetValue instanceof Number)
                    || ((Number) remoteLanBudgetValue).doubleValue()
                    != ((Number) remoteLanBudgetValue).intValue()) {
                throw new AppException(ErrorCodes.TraversalCapabilityExchangeInvalidPayload, "invalid_lan_probe_budget");
            }
            remoteLanBudget = ((Number) remoteLanBudgetValue).intValue();
        }
        int lanProbeBudgetSecs = TraversalPolicy.negotiateLanProbeBudget(
                TraversalPolicy.LAN_PROBE_BUDGET_SECS, remoteLanBudget);
        DiagLog.i(TAG, "LAN probe budget local=" + TraversalPolicy.LAN_PROBE_BUDGET_SECS
                + " remote=" + (remoteLanBudget == null
                ? TraversalPolicy.LEGACY_LAN_PROBE_BUDGET_SECS : remoteLanBudget)
                + " negotiated=" + lanProbeBudgetSecs);
        List<TraversalPolicy.Evidence> localEvidence = evidence(bounded);
        List<TraversalPolicy.Evidence> remoteEvidence = evidence(remote);
        localNatType = TraversalPolicy.udp4NatType(localEvidence);
        remoteNatType = TraversalPolicy.udp4NatType(remoteEvidence);
        List<String> plan = TraversalPolicy.plan(preferences.ipv6, preferences.tcp, localEvidence, remoteEvidence,
                remoteGate ? ipv6 : null, remoteGate ? remoteIpv6 : null);
        DiagLog.i(TAG, "traversal plan proposed: " + plan);
        proposedNetworks = String.join(",", plan);
        send(frame("plan", 0).put("networks", new JSONArray(plan)));
        JSONArray accepted = waitFrame(0, "plan_ack").getJSONArray("networks");
        DiagLog.i(TAG, "traversal plan selection acknowledged by peer: " + accepted);
        StringBuilder acknowledged = new StringBuilder();
        for (int i = 0; i < accepted.length(); i++) {
            if (i > 0) acknowledged.append(',');
            acknowledged.append(accepted.getString(i));
        }
        acknowledgedNetworks = acknowledged.toString();
        if (accepted.length() != plan.size()) throw new AppException(ErrorCodes.TraversalPlanNegotiationMismatch, "traversal_plan_mismatch");
        for (int i = 0; i < plan.size(); i++) if (!plan.get(i).equals(accepted.getString(i))) throw new AppException(ErrorCodes.TraversalPlanNegotiationMismatch, "traversal_plan_mismatch");
        List<String> rounds = new ArrayList<>();
        List<String> attemptedNetworks = new ArrayList<>();
        AppException lastError = null;
        rounds.add("udp4"); rounds.addAll(plan);
        for (int i = 0; i < rounds.size(); i++) {
            check.run();
            int round = i + 1;
            String network = rounds.get(i), mode = i == 0 ? "lan" : "internet";
            int seconds = i == 0 ? lanProbeBudgetSecs : 30;
            String token = UUID.randomUUID().toString();
            String callId = "network/" + round;
            if (wsSignalSelected) signal.register(callId, token, (seconds + 12) * 1000L);
            send(frame("prepare", round).put("network", network).put("mode", mode).put("token", token).put("timeout_secs", seconds));
            waitFrame(round, "ready");
            JSONObject request = new JSONObject(template.toString()).put("network", network).put("traversal_mode", mode)
                    .put("token", token).put("timeout_secs", seconds);
            boolean skipIpv6 = network.endsWith("6") && Boolean.FALSE.equals(ipv6);
            if ("internet".equals(mode) && !skipIpv6) { attemptedNetworks.add(network); attemptedInternetNetworks.add(network); }
            PunchNative.TunnelResult owned;
            try {
                owned = skipIpv6 ? null : wsSignalSelected ? signal.start(callId, request) : PunchNative.startUdpTunnel(request.toString());
            } finally { if (wsSignalSelected) signal.closeCall(callId); }
            if (owned != null) {
                if (!owned.getLocalNATType().isEmpty()) localNatType = owned.getLocalNATType();
                if (!owned.getRemoteNATType().isEmpty()) remoteNatType = owned.getRemoteNATType();
                if (!owned.getNetwork().isEmpty()) lastNetwork = owned.getNetwork();
            }
            if (owned != null && owned.getSignalFailed()) {
                throw new AppException(owned.getErrorCode(), owned.getError());
            }
            AppException localError = owned != null && !owned.getOK()
                    ? new AppException(owned.getErrorCode(), owned.getError()) : null;
            AppException roundError;
            boolean transferred = false;
            try {
                check.run();
                boolean localOk = owned != null && owned.getOK();
                send(TraversalRoundError.result(round, localOk, localError));
                JSONObject peerResult = waitFrame(round, "result", seconds * 1000L + 12_000);
                roundError = TraversalRoundError.choose(localError, TraversalRoundError.fromPeer(peerResult));
                boolean success = peerResult.getBoolean("ok") && localOk;
                send(frame(success ? "commit" : "close", round));
                waitFrame(round, success ? "committed" : "closed");
                if (success) { transferred = true; return owned; }
            } finally {
                if (!transferred && owned != null && owned.getOK()) PunchNative.stopUdpTunnel(owned.getHandleID());
            }
            if (roundError != null) {
                if (!TraversalRoundError.canAdvance(roundError)) throw roundError;
                lastError = roundError;
            }
        }
        if (lastError != null) throw lastError;
        throw new AppException(ErrorCodes.TraversalExecutionExhausted, "打洞轮次均未成功：" + String.join(",", attemptedNetworks));
    }
    PunchNative.TunnelResult accept(PunchNative.TunnelResult result) {
        if (!result.getLocalNATType().isEmpty()) localNatType = result.getLocalNATType();
        if (!result.getRemoteNATType().isEmpty()) remoteNatType = result.getRemoteNATType();
        if (!result.getNetwork().isEmpty()) lastNetwork = result.getNetwork();
        return result;
    }
    @Override public void close() { cancelSignal(); ws.clearTraversalListener(attempt); }
    String traversalPlan() { return proposedNetworks; }
    String traversalSelection() { return acknowledgedNetworks; }
    String localNatType() { return localNatType; }
    String remoteNatType() { return remoteNatType; }
    /** 最后一次实际调用的网络；没有调用记录时回退计划首选网络。 */
    String selectedNetwork() {
        return lastNetwork;
    }
}
