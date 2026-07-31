package top.p2premote.android;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertArrayEquals;

public final class HealthFrameCodecTest {
    @Test
    public void roundTripsControlFrame() throws Exception {
        String json = "{\"t\":\"Ping\",\"c\":{\"ts\":123}}";
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        HealthFrameCodec.write(new DataOutputStream(bytes), json);

        byte[] decoded = HealthFrameCodec.read(
                new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())));

        assertArrayEquals(json.getBytes(StandardCharsets.UTF_8), decoded);
    }

    @Test(expected = IOException.class)
    public void rejectsOversizedDeclaredFrameBeforeAllocation() throws Exception {
        int length = HealthFrameCodec.MAX_FRAME_BYTES + 1;
        int encoded = (length << 2) | 0x2;
        byte[] header = {
                (byte) encoded,
                (byte) (encoded >> 8),
                (byte) (encoded >> 16)
        };
        HealthFrameCodec.read(new DataInputStream(new ByteArrayInputStream(header)));
    }

    @Test(expected = IOException.class)
    public void rejectsOversizedOutgoingFrame() throws Exception {
        String payload = "x".repeat(HealthFrameCodec.MAX_FRAME_BYTES + 1);
        HealthFrameCodec.write(new DataOutputStream(new ByteArrayOutputStream()), payload);
    }
}
