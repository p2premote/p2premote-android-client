package top.p2premote.android;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public final class HealthFrameCodecTest {
    @Test public void wireHeadersMatchBoundaryGoldenValues() throws Exception {
        int[] lengths = {0, 1, 63, 64, 16383, 16384, 65536};
        byte[][] headers = {{0}, {4}, {(byte)252}, {1, 1}, {(byte)253, (byte)255},
                {2, 0, 1}, {2, 0, 4}};
        for (int i = 0; i < lengths.length; i++) {
            String payload = "x".repeat(lengths[i]);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            HealthFrameCodec.write(new DataOutputStream(output), payload);
            byte[] wire = output.toByteArray();
            assertEquals(headers[i].length + lengths[i], wire.length);
            assertArrayEquals(headers[i], java.util.Arrays.copyOf(wire, headers[i].length));
            ByteArrayInputStream input = new ByteArrayInputStream(wire);
            assertArrayEquals(payload.getBytes(StandardCharsets.UTF_8), HealthFrameCodec.read(new DataInputStream(input)));
            assertEquals(0, input.available());
        }
    }

    @Test public void fourByteHeadersAndUtf8ByteLengthAreDecoded() throws Exception {
        assertArrayEquals(new byte[]{65}, HealthFrameCodec.read(new DataInputStream(
                new ByteArrayInputStream(new byte[]{7, 0, 0, 0, 65}))));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        HealthFrameCodec.write(new DataOutputStream(output), "中");
        assertArrayEquals(new byte[]{12, (byte)0xe4, (byte)0xb8, (byte)0xad}, output.toByteArray());
    }

    @Test public void truncatedHeaderAndBodyAreNotAcceptedAsCompleteFrames() {
        for (byte[] wire : new byte[][]{{}, {1}, {2, 0}, {3, 0, 0}, {8, 65}}) {
            assertThrows(java.io.EOFException.class, () -> HealthFrameCodec.read(
                    new DataInputStream(new ByteArrayInputStream(wire))));
        }
    }
    @Test
    public void roundTripsControlFrame() throws Exception {
        String json = "{\"t\":\"Ping\",\"c\":{\"ts\":123}}";
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        HealthFrameCodec.write(new DataOutputStream(bytes), json);

        byte[] decoded = HealthFrameCodec.read(
                new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())));

        assertArrayEquals(json.getBytes(StandardCharsets.UTF_8), decoded);
    }

    @Test
    public void rejectsOversizedDeclaredFrameBeforeAllocation() throws Exception {
        int length = HealthFrameCodec.MAX_FRAME_BYTES + 1;
        int encoded = (length << 2) | 0x2;
        byte[] header = {
                (byte) encoded,
                (byte) (encoded >> 8),
                (byte) (encoded >> 16)
        };
        ByteArrayInputStream input = new ByteArrayInputStream(header) {
            @Override public synchronized int read(byte[] b, int off, int len) {
                throw new AssertionError("oversized frame must not read payload");
            }
        };
        IOException error = assertThrows(IOException.class,
                () -> HealthFrameCodec.read(new DataInputStream(input)));
        assertEquals("health frame invalid length: " + length, error.getMessage());
    }

    @Test
    public void rejectsOversizedOutgoingFrame() throws Exception {
        String payload = "x".repeat(HealthFrameCodec.MAX_FRAME_BYTES + 1);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        IOException error = assertThrows(IOException.class,
                () -> HealthFrameCodec.write(new DataOutputStream(output), payload));
        assertEquals("health frame too large: " + (HealthFrameCodec.MAX_FRAME_BYTES + 1), error.getMessage());
        assertEquals(0, output.size());
    }
}
