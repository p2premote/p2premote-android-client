package top.p2premote.android;

import org.json.JSONObject;
import org.json.JSONException;
import org.junit.Test;
import static org.junit.Assert.*;

/** In-memory wire compatibility tests: no accounts, broker, native or device required. */
public class SignalCompatibilityTest {
    @Test public void legacyErrorMatrixPreservesMessagesAndRetryPolicy() throws Exception {
        String[][] cases = {
            {"hole_punch_wait_timeout", "traversal.execution.exhausted"},
            {"punch_exhausted", "traversal.execution.exhausted"},
            {"traversal_signal_timeout", "traversal.execution.exhausted"},
            {"peer_prepare_timeout", "connection.peer_prepare.timeout"},
            {"peer_offline", "connection.peer_prepare.offline"},
            {"peer_cancelled", "connection.execution.peer_cancelled"},
            {"user_cancelled", "connection.execution.user_cancelled"},
            {"invalid_lan_cidr", "connection.route_validation.invalid_cidr"},
            {"config_load_failed", "runtime.config.load_failed"},
            {"wireguard_config_failed", "wireguard.configure.failed"},
            {"wireguard_handshake_failed", "wireguard.handshake.failed"},
            {"approval_denied", "connection.approval.denied"},
            {"approval_timeout", "connection.approval.timeout"},
            {"approval_request_missing", "connection.approval.request_missing"},
            {"approval_busy", "connection.approval.busy"},
            {"passive_start_failed", "runtime.execution.unclassified"},
            {"internal_error", "runtime.execution.unclassified"},
            {"unknown_old_error", "runtime.execution.unclassified"},
            {"", "runtime.execution.unclassified"},
        };
        for (String[] row : cases) {
            for (String message : new String[]{"old peer failed", "旧端失败", "timeout cancel unrelated text", ""}) {
                JSONObject wire = new JSONObject().put("code", row[0]).put("message", message)
                        .put("message_key", "old.key").put("message_params", new JSONObject().put("n", 1));
                AppException error = SignalTransport.peerError(new JSONObject(wire.toString()));
                assertEquals(row[0], row[1], error.errorCode);
                assertEquals(message, error.getMessage());
                assertEquals(("traversal.execution.exhausted".equals(row[1])
                        || "connection.peer_prepare.timeout".equals(row[1])
                        || "wireguard.handshake.failed".equals(row[1])) ? "next_attempt" : "stop",
                        ErrorCodes.retryClass(error.errorCode));
            }
        }
        assertEquals(ErrorCodes.NativeDecodeInvalidPayload, SignalTransport.peerError(null).errorCode);
        assertEquals(ErrorCodes.NativeDecodeInvalidPayload, SignalTransport.peerError(new JSONObject()).errorCode);
        JSONObject mixed = new JSONObject().put("code", "peer_offline").put("error_code", "future.stage.failure").put("message", "detail");
        assertEquals("future.stage.failure", SignalTransport.peerError(mixed).errorCode);
        for (Object invalid : new Object[]{"", "INVALID", 7, JSONObject.NULL}) {
            mixed.put("error_code", invalid);
            assertEquals(ErrorCodes.NativeDecodeInvalidPayload, SignalTransport.peerError(mixed).errorCode);
        }
    }

    @Test public void versionDeclarationTypeAndBoundaryMatrix() throws Exception {
        SignalTransport.requireErrorContract(new JSONObject());
        for (Object version : new Object[]{0, 1, 0L, 1L}) {
            SignalTransport.requireErrorContract(new JSONObject().put("error_contract_version", version));
        }
        for (Object version : new Object[]{-1, 2, 255, 256, Long.MAX_VALUE, 4294967296L, "1", true, 1.0, JSONObject.NULL}) {
            AppException error = assertThrows(AppException.class, () ->
                    SignalTransport.requireErrorContract(new JSONObject().put("error_contract_version", version)));
            assertEquals(ErrorCodes.ConnectionCompatibilityClientUpgradeRequired, error.errorCode);
        }
    }

    @Test public void transportSelectionMatrixCoversLegacyAndWsNegotiation() throws Exception {
        assertEquals("mqtt-legacy", SignalTransport.offer(false).getJSONArray("supported").getString(0));
        assertEquals(1, SignalTransport.offer(false).getJSONArray("supported").length());
        assertEquals("ws-v1", SignalTransport.offer(true).getJSONArray("supported").getString(0));
        assertEquals(2, SignalTransport.offer(true).getJSONArray("supported").length());
        for (boolean wsOffered : new boolean[]{false, true}) {
            for (boolean traversal : new boolean[]{false, true}) {
                assertFalse(SignalTransport.selectedWs(new JSONObject(), wsOffered, traversal));
                JSONObject legacy = ready(1, "mqtt-legacy");
                assertFalse(SignalTransport.selectedWs(legacy, wsOffered, traversal));
                JSONObject ws = ready(1, "ws-v1");
                if (wsOffered && traversal) assertTrue(SignalTransport.selectedWs(ws, wsOffered, traversal));
                else assertThrows(AppException.class, () -> SignalTransport.selectedWs(ws, wsOffered, traversal));
            }
        }
        assertTrue(SignalTransport.selectedWs(ready(1L, "ws-v1"), true, true));
        for (Object version : new Object[]{0, 2, "1", true, 1.0, JSONObject.NULL}) {
            assertThrows(AppException.class, () -> SignalTransport.selectedWs(ready(version, "ws-v1"), true, true));
        }
        for (Object transport : new Object[]{"future", 7, JSONObject.NULL}) {
            assertThrows(AppException.class, () -> SignalTransport.selectedWs(ready(1, transport), true, true));
        }
        assertThrows(AppException.class, () -> SignalTransport.selectedWs(
                new JSONObject().put("signal_transport", JSONObject.NULL), true, true));
        assertThrows(JSONException.class, () -> SignalTransport.selectedWs(
                new JSONObject().put("signal_transport", new JSONObject()), true, true));
        assertThrows(JSONException.class, () -> SignalTransport.selectedWs(
                new JSONObject().put("signal_transport", "invalid"), true, true));
    }

    private static JSONObject ready(Object version, Object transport) throws Exception {
        return new JSONObject().put("signal_transport",
                new JSONObject().put("version", version).put("selected", transport));
    }
}

