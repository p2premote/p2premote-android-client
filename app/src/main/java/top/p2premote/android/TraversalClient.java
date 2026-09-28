package top.p2premote.android;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Active-side application coordinator. No changes to the punch MQTT protocol. */
final class TraversalClient implements AutoCloseable {
    interface Check { void run() throws Exception; }
    private final WsConnection ws;
    private final ApiClient.OpenResult opened;
    private final long peer;
    private final String attempt;
    private final ConnectionPreferences preferences;
    private final Check check;
    private final ArrayBlockingQueue<JSONObject> inbox = new ArrayBlockingQueue<>(64);
    private volatile JSONObject ready;
    private volatile String signalError;

    TraversalClient(WsConnection ws, ApiClient.OpenResult opened, long peer, String attempt,
                    ConnectionPreferences preferences, Check check) {
        this.ws = ws; this.opened = opened; this.peer = peer; this.attempt = attempt;
        this.preferences = preferences; this.check = check;
        ws.setTraversalListener(opened.connectionId, peer, attempt, this::receive);
    }
    JSONObject negotiation() throws Exception {
        return new JSONObject().put("version", TraversalPolicy.VERSION).put("preferences",
                new JSONObject().put("prefer_ipv6", preferences.ipv6).put("prefer_tcp", preferences.tcp));
    }
    private void receive(JSONObject data) {
        String type = data.optString("type");
        if ("attempt_ready".equals(type)) ready = data;
        else if ("attempt_failed".equals(type) || "attempt_cancel".equals(type)) signalError = "peer_traversal_failed";
        else if ("traversal".equals(type)) {
            JSONObject frame = data.optJSONObject("frame");
            if (frame == null || !inbox.offer(frame)) signalError = "invalid_traversal_frame";
        }
    }
    boolean negotiated() throws Exception {
        if (ready == null) throw new IllegalStateException("missing_traversal_ready");
        if (!ready.has("traversal_negotiation") || ready.isNull("traversal_negotiation")) return false;
        JSONObject accepted = ready.getJSONObject("traversal_negotiation");
        JSONObject p = accepted.getJSONObject("preferences");
        if (accepted.getInt("version") != TraversalPolicy.VERSION
                || p.getBoolean("prefer_ipv6") != preferences.ipv6
                || p.getBoolean("prefer_tcp") != preferences.tcp) {
            throw new IllegalStateException("traversal_preference_mismatch");
        }
        return true;
    }
    private void send(JSONObject frame) throws Exception {
        check.run();
        if (!ws.sendNotifyWaitAck(opened.connectionId, peer, ApiClient.buildMessageId(), opened.accessGrant,
                new JSONObject().put("type", "traversal").put("attempt_id", attempt).put("frame", frame).toString())) {
            throw new IllegalStateException("traversal_signal_failed");
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
    private JSONObject waitFrame(int round, String kind, long timeoutMillis) throws Exception {
        long end = android.os.SystemClock.elapsedRealtime() + timeoutMillis;
        while (android.os.SystemClock.elapsedRealtime() < end) {
            check.run();
            if (signalError != null) throw new IllegalStateException(signalError);
            JSONObject frame = inbox.poll(200, TimeUnit.MILLISECONDS);
            if (frame == null) continue;
            int receivedRound = frame.optInt("round", 0);
            int receivedStage = stage(frame.optString("kind"));
            if (receivedRound < round || (receivedRound == round && receivedStage < stage(kind))) continue;
            if (receivedRound != round || !kind.equals(frame.optString("kind"))) throw new IllegalStateException("invalid_traversal_sequence");
            return frame;
        }
        throw new IllegalStateException("traversal_signal_timeout");
    }
    private static JSONObject frame(String kind, int round) throws Exception {
        JSONObject result = new JSONObject().put("kind", kind);
        if (round > 0) result.put("round", round);
        return result;
    }
    private static List<TraversalPolicy.Evidence> evidence(JSONArray values) throws Exception {
        if (values.length() > 32) throw new IllegalStateException("invalid_traversal_capabilities");
        List<TraversalPolicy.Evidence> result = new ArrayList<>();
        for (int i = 0; i < values.length(); i++) {
            JSONObject value = values.getJSONObject(i);
            result.add(new TraversalPolicy.Evidence(value.getString("network"), value.getString("nat_type")));
        }
        return result;
    }
    PunchNative.TunnelResult start(JSONObject template) throws Exception {
        if (!negotiated()) return PunchNative.startUdpTunnel(template.toString());
        check.run();
        JSONArray local = new JSONArray(PunchNative.nativeDetectNat());
        // Limit duplicates from multi-interface native detection.
        JSONArray bounded = new JSONArray();
        for (int i = 0; i < Math.min(local.length(), 32); i++) bounded.put(local.getJSONObject(i));
        send(frame("capabilities", 0).put("evidence", bounded));
        JSONArray remote = waitFrame(0, "capabilities").getJSONArray("evidence");
        List<String> plan = TraversalPolicy.plan(preferences.ipv6, preferences.tcp, evidence(bounded), evidence(remote));
        send(frame("plan", 0).put("networks", new JSONArray(plan)));
        JSONArray accepted = waitFrame(0, "plan_ack").getJSONArray("networks");
        if (accepted.length() != plan.size()) throw new IllegalStateException("traversal_plan_mismatch");
        for (int i = 0; i < plan.size(); i++) if (!plan.get(i).equals(accepted.getString(i))) throw new IllegalStateException("traversal_plan_mismatch");
        List<String> rounds = new ArrayList<>();
        rounds.add(preferences.tcp ? "tcp4" : "udp4"); rounds.addAll(plan);
        for (int i = 0; i < rounds.size(); i++) {
            check.run();
            int round = i + 1;
            String network = rounds.get(i), mode = i == 0 ? "lan" : "internet";
            int seconds = i == 0 ? 6 : (network.startsWith("tcp") ? 10 : 30);
            String token = UUID.randomUUID().toString();
            send(frame("prepare", round).put("network", network).put("mode", mode).put("token", token).put("timeout_secs", seconds));
            waitFrame(round, "ready");
            JSONObject request = new JSONObject(template.toString()).put("network", network).put("traversal_mode", mode)
                    .put("token", token).put("timeout_secs", seconds);
            PunchNative.TunnelResult owned = PunchNative.startUdpTunnel(request.toString());
            boolean transferred = false;
            try {
                check.run();
                send(frame("result", round).put("ok", owned.getOK()));
                boolean success = waitFrame(round, "result", seconds * 1000L + 12_000).getBoolean("ok") && owned.getOK();
                send(frame(success ? "commit" : "close", round));
                waitFrame(round, success ? "committed" : "closed");
                if (success) { transferred = true; return owned; }
            } finally {
                if (!transferred && owned.getOK()) PunchNative.stopUdpTunnel(owned.getHandleID());
            }
        }
        throw new IllegalStateException("punch_exhausted");
    }
    @Override public void close() { ws.clearTraversalListener(attempt); }
}
