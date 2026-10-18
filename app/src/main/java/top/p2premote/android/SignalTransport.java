package top.p2premote.android;

import org.json.JSONArray;
import org.json.JSONObject;

/** Strict, pre-exchange transport negotiation shared with the desktop wire format. */
final class SignalTransport {
    private SignalTransport() {}
    static void requireErrorContract(JSONObject message) {
        // 1.14 preserves peers that predate the error contract declaration.
        if (!message.has("error_contract_version")) return;
        Object version = message.opt("error_contract_version");
        if (!(version instanceof Integer || version instanceof Long)
                || (((Number) version).longValue() != 0
                && ((Number) version).longValue() != ErrorCodes.CONTRACT_VERSION)) {
            throw new AppException(ErrorCodes.ConnectionCompatibilityClientUpgradeRequired,
                    "对端不支持当前错误协议，请升级客户端");
        }
    }
    static AppException peerError(JSONObject error) {
        if (error == null) return new AppException(ErrorCodes.NativeDecodeInvalidPayload, "对端失败消息缺少错误码");
        if (error.has("error_code")) return new AppException(error.optString("error_code"), error.optString("message"));
        String code;
        switch (error.optString("code")) {
            case "hole_punch_wait_timeout": case "punch_exhausted": case "traversal_signal_timeout": code = ErrorCodes.TraversalExecutionExhausted; break;
            case "peer_prepare_timeout": code = ErrorCodes.ConnectionPeerPrepareTimeout; break;
            case "peer_offline": code = ErrorCodes.ConnectionPeerPrepareOffline; break;
            case "peer_cancelled": code = ErrorCodes.ConnectionExecutionPeerCancelled; break;
            case "user_cancelled": code = ErrorCodes.ConnectionExecutionUserCancelled; break;
            case "approval_denied": code = ErrorCodes.ConnectionApprovalDenied; break;
            case "approval_timeout": code = ErrorCodes.ConnectionApprovalTimeout; break;
            case "approval_busy": code = ErrorCodes.ConnectionApprovalBusy; break;
            case "approval_request_missing": code = ErrorCodes.ConnectionApprovalRequestMissing; break;
            case "invalid_lan_cidr": code = ErrorCodes.ConnectionRouteValidationInvalidCidr; break;
            case "config_load_failed": code = ErrorCodes.RuntimeConfigLoadFailed; break;
            case "wireguard_config_failed": code = ErrorCodes.WireguardConfigureFailed; break;
            case "wireguard_handshake_failed": code = ErrorCodes.WireguardHandshakeFailed; break;
            default: code = error.has("code") ? ErrorCodes.RuntimeExecutionUnclassified : ErrorCodes.NativeDecodeInvalidPayload;
        }
        return new AppException(code, error.optString("message"));
    }
    static JSONObject offer(boolean ws) throws Exception {
        JSONArray supported = new JSONArray();
        if (ws) supported.put("ws-v1");
        supported.put("mqtt-legacy");
        return new JSONObject().put("version", 1).put("supported", supported);
    }
    static boolean selectedWs(JSONObject ready, boolean wsOffered, boolean traversalV2) throws Exception {
        if (!ready.has("signal_transport")) return false; // Transitional legacy peer.
        if (ready.isNull("signal_transport")) throw new AppException(ErrorCodes.SignalTransportSelectInvalidSelection, "signal protocol invalid");
        JSONObject selected = ready.getJSONObject("signal_transport");
        Object version = selected.get("version"), transport = selected.get("selected");
        if (!(version instanceof Integer || version instanceof Long) || ((Number) version).longValue() != 1
                || !(transport instanceof String)) throw new AppException(ErrorCodes.SignalTransportSelectInvalidSelection, "signal protocol invalid");
        if ("mqtt-legacy".equals(transport)) return false;
        if (!"ws-v1".equals(transport) || !wsOffered || !traversalV2) throw new AppException(ErrorCodes.SignalTransportSelectInvalidSelection, "signal protocol invalid");
        return true;
    }
}
