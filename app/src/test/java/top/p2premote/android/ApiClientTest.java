package top.p2premote.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public final class ApiClientTest {

    @Test
    public void readFullyPreservesUtf8Response() throws Exception {
        String json = "{\"msg\":\"连接成功\"}";

        String result = ApiClient.readFully(
                new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)),
                System.nanoTime());

        assertEquals(json, result);
    }

    @Test
    public void readFullyRejectsOversizedResponse() throws Exception {
        byte[] response = new byte[ApiClient.MAX_RESPONSE_BYTES + 1];

        try {
            ApiClient.readFully(new ByteArrayInputStream(response), System.nanoTime());
            fail("oversized response should be rejected");
        } catch (IOException error) {
            assertTrue(error.getMessage().contains("响应过大"));
        }
    }
}
