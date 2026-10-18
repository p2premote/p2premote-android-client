package top.p2premote.android;

import org.junit.Test;
import static org.junit.Assert.*;

public class AppExceptionTest {
    @Test public void apiCausePreservesBusinessIdentityAndDiagnosticCause() {
        ApiClient.ApiException api = new ApiClient.ApiException(403, 1, "denied", ErrorCodes.ConnectionAuthorizeForbidden);
        AppException converted = AppException.from(new RuntimeException("outer", api));
        assertEquals(ErrorCodes.ConnectionAuthorizeForbidden, converted.errorCode);
        assertEquals("denied", converted.getMessage());
        assertSame(api, converted.getCause());
    }
    @Test public void causeTraversalIsBoundedAndMissingMessagesHaveFallback() {
        AppException typed = new AppException(ErrorCodes.ConnectionApprovalDenied, "typed");
        Throwable chain = typed;
        for (int i = 0; i < 63; i++) chain = new RuntimeException("wrapper", chain);
        assertSame(typed, AppException.from(chain));
        chain = new RuntimeException("too deep", chain);
        AppException fallback = AppException.from(chain);
        assertEquals(ErrorCodes.RuntimeExecutionUnclassified, fallback.errorCode);
        assertEquals("too deep", fallback.getMessage());
        assertSame(chain, fallback.getCause());
        assertEquals("建立连接失败", AppException.from(new RuntimeException()).getMessage());
    }
    @Test public void peerErrorsAcceptLegacyAndPreferCanonicalCodes() throws Exception {
        org.json.JSONObject error = new org.json.JSONObject().put("code", "hole_punch_wait_timeout").put("message", "old peer failed");
        assertEquals(ErrorCodes.TraversalExecutionExhausted, SignalTransport.peerError(error).errorCode);
        error.put("error_code", "future.stage.failure");
        assertEquals("future.stage.failure", SignalTransport.peerError(error).errorCode);
        error.put("error_code", "invalid");
        assertEquals(ErrorCodes.NativeDecodeInvalidPayload, SignalTransport.peerError(error).errorCode);
    }
    @Test public void transitionalContractsAreAcceptedAndUnsupportedContractsAreRejected() throws Exception {
        for (Object version : new Object[]{org.json.JSONObject.NULL, 2, "1", 1.5}) {
            org.json.JSONObject ready = new org.json.JSONObject().put("error_contract_version", version);
            AppException error = assertThrows(AppException.class, () -> SignalTransport.requireErrorContract(ready));
            assertEquals(ErrorCodes.ConnectionCompatibilityClientUpgradeRequired, error.errorCode);
        }
        SignalTransport.requireErrorContract(new org.json.JSONObject());
        SignalTransport.requireErrorContract(new org.json.JSONObject().put("error_contract_version", 0));
        SignalTransport.requireErrorContract(new org.json.JSONObject().put("error_contract_version", 1));
    }
    @Test public void generatedCodesMatchTheSharedRegistry() throws Exception {
        try (java.io.InputStream input = getClass().getClassLoader().getResourceAsStream("error-codes-v1.json")) {
            assertNotNull(input);
            org.json.JSONObject vectors = new org.json.JSONObject(new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            assertEquals(vectors.getString("registry_hash"), ErrorCodes.REGISTRY_HASH);
            org.json.JSONArray codes = vectors.getJSONArray("codes");
            for (int i = 0; i < codes.length(); i++) {
                org.json.JSONObject row = codes.getJSONObject(i);
                String code = (String) ErrorCodes.class.getDeclaredField(row.getString("symbol")).get(null);
                assertEquals(row.getString("code"), code);
                assertTrue(ErrorCodes.valid(code));
                assertEquals(row.getString("retry_class"), ErrorCodes.retryClass(code));
            }
        }
    }
    @Test public void wrappingAndLanguageDoNotChangeBusinessCode() {
        AppException failure = new AppException(ErrorCodes.TraversalExecutionExhausted, "任意文案");
        RuntimeException wrapped = new RuntimeException("different language", failure);
        assertSame(failure, AppException.from(wrapped));
        assertEquals(ErrorCodes.TraversalExecutionExhausted, AppException.from(wrapped).errorCode);
        assertEquals("next_attempt", ErrorCodes.retryClass(failure.errorCode));
    }
    @Test public void futureCodesAreKeptAndOldTextIsNotParsed() {
        assertEquals("future.exchange.failed", new AppException("future.exchange.failed", "any").errorCode);
        assertEquals("stop", ErrorCodes.retryClass("future.exchange.failed"));
        assertEquals(ErrorCodes.RuntimeExecutionUnclassified, AppException.from(new RuntimeException("punch_exhausted:tcp4")).errorCode);
        assertEquals(ErrorCodes.NativeDecodeInvalidPayload, new AppException("hole_punch_wait_timeout", "old").errorCode);
    }
}
