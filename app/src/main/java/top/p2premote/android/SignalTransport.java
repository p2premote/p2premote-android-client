package top.p2premote.android;

import org.json.JSONArray;
import org.json.JSONObject;

/** Strict, pre-exchange transport negotiation shared with the desktop wire format. */
final class SignalTransport {
    private SignalTransport() {}
    static void requireErrorContract(JSONObject message) {
        Object version = message.opt("error_contract_version");
        if (!(version instanceof Integer || version instanceof Long)
                || ((Number) version).longValue() != ErrorCodes.CONTRACT_VERSION) {
            throw new AppException(ErrorCodes.ConnectionCompatibilityClientUpgradeRequired,
                    "对端不支持当前错误协议，请升级客户端");
        }
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
