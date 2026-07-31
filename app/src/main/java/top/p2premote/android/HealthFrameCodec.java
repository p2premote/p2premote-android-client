package top.p2premote.android;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** RustDesk BytesCodec 控制帧；只允许小型 JSON，分配内存前先验证长度。 */
final class HealthFrameCodec {
    static final int MAX_FRAME_BYTES = 64 * 1024;

    private HealthFrameCodec() {
    }

    static void write(DataOutputStream out, String json) throws IOException {
        byte[] data = json.getBytes(StandardCharsets.UTF_8);
        int len = data.length;
        if (len > MAX_FRAME_BYTES) {
            throw new IOException("health frame too large: " + len);
        }
        ByteArrayOutputStream header = new ByteArrayOutputStream(4);
        if (len <= 0x3F) {
            header.write(len << 2);
        } else if (len <= 0x3FFF) {
            int h = (len << 2) | 0x1;
            header.write(h & 0xFF);
            header.write((h >> 8) & 0xFF);
        } else {
            int h = (len << 2) | 0x2;
            header.write(h & 0xFF);
            header.write((h >> 8) & 0xFF);
            header.write((h >> 16) & 0xFF);
        }
        out.write(header.toByteArray());
        out.write(data);
        out.flush();
    }

    static byte[] read(DataInputStream in) throws IOException {
        int first = in.readUnsignedByte();
        int headLen = (first & 0x3) + 1;
        int encoded = first;
        for (int i = 1; i < headLen; i++) {
            encoded |= in.readUnsignedByte() << (8 * i);
        }
        int length = encoded >>> 2;
        if (length > MAX_FRAME_BYTES) {
            throw new IOException("health frame invalid length: " + length);
        }
        byte[] data = new byte[length];
        in.readFully(data);
        return data;
    }
}
