package top.p2premote.android;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import static org.junit.Assert.*;

public class TraversalRoundErrorTest {
    @Test public void sharedRoundFailuresKeepCodeAndPolicyAcrossTheWire() throws Exception {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream("traversal-round-errors-v1.json")) {
            assertNotNull(input);
            JSONArray cases = new JSONObject(new String(input.readAllBytes(), StandardCharsets.UTF_8)).getJSONArray("cases");
            for (int i = 0; i < cases.length(); i++) {
                JSONObject row = cases.getJSONObject(i);
                AppException local = row.isNull("local") ? null : new AppException(row.getString("local"), "local detail");
                AppException peer = row.isNull("peer") ? null : new AppException(row.getString("peer"), "peer detail");
                // Serialize/parse the actual result frame, including unknown future codes.
                JSONObject frame = new JSONObject(TraversalRoundError.result(3, false, peer).toString());
                AppException selected = TraversalRoundError.choose(local, TraversalRoundError.fromPeer(frame));
                if (row.isNull("selected")) { assertNull(selected); continue; }
                assertEquals(row.getString("name"), row.getString("selected"), selected.errorCode);
                assertEquals(row.getString("name"), row.getBoolean("advance"), TraversalRoundError.canAdvance(selected));
            }
        }
    }

    @Test public void malformedAndContradictoryResultsAreRejected() throws Exception {
        for (String payload : Arrays.asList(
                "{\"ok\":true,\"error\":{\"error_code\":\"traversal.punch.timeout\",\"message\":\"x\"}}",
                "{\"ok\":false,\"error\":{\"error_code\":\"old_timeout\",\"message\":\"x\"}}",
                "{\"ok\":false,\"error\":{\"error_code\":\"traversal.punch.timeout\"}}",
                "{\"ok\":false,\"error\":\"invalid\"}")) {
            AppException error = assertThrows(AppException.class, () -> TraversalRoundError.fromPeer(new JSONObject(payload)));
            assertEquals(ErrorCodes.SignalReceiveInvalidFrame, error.errorCode);
        }
        assertNull(TraversalRoundError.fromPeer(TraversalRoundError.result(1, true, null)));
        assertThrows(AppException.class, () -> TraversalRoundError.result(1, true,
                new AppException(ErrorCodes.TraversalPunchTimeout, "x")));
    }
}
