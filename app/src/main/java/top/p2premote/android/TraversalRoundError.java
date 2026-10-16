package top.p2premote.android;

import org.json.JSONObject;

/** A round error uses the shared two-field contract; both peers decide after Close/Closed. */
final class TraversalRoundError {
    private TraversalRoundError() {}

    static JSONObject result(int round, boolean ok, AppException error) throws Exception {
        if (ok && error != null) throw invalid("successful round contains an error");
        JSONObject frame = new JSONObject().put("kind", "result").put("round", round).put("ok", ok);
        if (error != null) frame.put("error", new JSONObject()
                .put("error_code", error.errorCode).put("message", error.getMessage()));
        return frame;
    }

    static AppException fromPeer(JSONObject result) throws Exception {
        if (!result.has("error") || result.isNull("error")) return null;
        if (result.getBoolean("ok")) throw invalid("successful peer round contains an error");
        Object value = result.get("error");
        if (!(value instanceof JSONObject)) throw invalid("invalid peer round error");
        JSONObject error = (JSONObject) value;
        Object code = error.opt("error_code"), message = error.opt("message");
        if (!(code instanceof String) || !ErrorCodes.valid((String) code) || !(message instanceof String)) {
            throw invalid("invalid peer round error");
        }
        return new AppException((String) code, (String) message);
    }

    static boolean canAdvance(AppException error) {
        return "next_network_or_attempt".equals(ErrorCodes.retryClass(error.errorCode));
    }

    static AppException choose(AppException local, AppException peer) {
        if (local == null) return peer;
        if (peer != null && canAdvance(local) && !canAdvance(peer)) return peer;
        return local;
    }

    private static AppException invalid(String message) {
        return new AppException(ErrorCodes.SignalReceiveInvalidFrame, message);
    }
}
