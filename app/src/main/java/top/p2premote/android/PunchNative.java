package top.p2premote.android;

import org.json.JSONObject;

/**
 * Rust 版打洞库（punch-native，libp2premote_punch_jni.so）的 JNI 绑定，
 * 替代原 gomobile 的 wgvpnmobile。WireGuard 数据面仍在 Libwgmobile。
 *
 * 结果 JSON 契约与 Rust 侧 p2premote-punch 的 types.rs 保持一致
 * （snake_case 字段）。
 */
public final class PunchNative {
    static {
        System.loadLibrary("p2premote_punch_jni");
    }

    private PunchNative() {
    }

    /** gonc 创建的 socket 必须经 VpnService.protect(fd)，否则流量被 TUN 回环吞掉。 */
    public interface ProtectCallback {
        boolean protect(int fd);
    }

    /** 在任何 exchange / 打洞调用之前注册；传 null 清除。 */
    public static native void nativeSetProtectCallback(ProtectCallback callback);

    static native String nativeExchange(
            String token, String sendData, String roleHint, int exmode, int timeoutSecs);

    static native String nativeStartUdpTunnel(String requestJson);

    static native boolean nativeStopUdpTunnel(String handleId);

    /** 对应原 wgvpnmobile.Exchange 的返回值。 */
    public static final class ExchangeResult {
        private final boolean ok;
        private final String recvData;
        private final String error;

        private ExchangeResult(boolean ok, String recvData, String error) {
            this.ok = ok;
            this.recvData = recvData;
            this.error = error;
        }

        public boolean getOK() {
            return ok;
        }

        public String getRecvData() {
            return recvData;
        }

        public String getError() {
            return error;
        }
    }

    /** 对应原 wgvpnmobile.TunnelResult 的返回值。 */
    public static final class TunnelResult {
        private final boolean ok;
        private final String handleId;
        private final long localForwardPort;
        private final String peerEndpoint;
        private final String localNATType;
        private final String remoteNATType;
        private final String selectedTraversal;
        private final String transportMode;
        private final String error;

        private TunnelResult(JSONObject json) {
            this.ok = json.optBoolean("ok", false);
            this.handleId = json.optString("handle_id", "");
            this.localForwardPort = json.optLong("local_forward_port", 0);
            this.peerEndpoint = json.optString("peer_endpoint", "");
            this.localNATType = json.optString("local_nat_type", "");
            this.remoteNATType = json.optString("remote_nat_type", "");
            this.selectedTraversal = json.optString("selected_traversal", "");
            this.transportMode = json.optString("transport_mode", "");
            this.error = json.optString("error", "");
        }

        public boolean getOK() {
            return ok;
        }

        public String getHandleID() {
            return handleId;
        }

        public long getLocalForwardPort() {
            return localForwardPort;
        }

        public String getPeerEndpoint() {
            return peerEndpoint;
        }

        public String getLocalNATType() {
            return localNATType;
        }

        public String getRemoteNATType() {
            return remoteNATType;
        }

        public String getSelectedTraversal() {
            return selectedTraversal;
        }

        public String getTransportMode() {
            return transportMode;
        }

        public String getError() {
            return error;
        }
    }

    public static ExchangeResult exchange(
            String token, String sendData, String roleHint, int exmode, int timeoutSecs) {
        JSONObject json;
        try {
            json = new JSONObject(nativeExchange(token, sendData, roleHint, exmode, timeoutSecs));
        } catch (Exception e) {
            return new ExchangeResult(false, "", "nativeExchange failed: " + e.getMessage());
        }
        return new ExchangeResult(
                json.optBoolean("ok", false),
                json.optString("recv_data", ""),
                json.optString("error", ""));
    }

    public static TunnelResult startUdpTunnel(String requestJson) {
        JSONObject json;
        try {
            json = new JSONObject(nativeStartUdpTunnel(requestJson));
        } catch (Exception e) {
            JSONObject fallback = new JSONObject();
            try {
                fallback.put("error", "nativeStartUdpTunnel failed: " + e.getMessage());
            } catch (Exception ignored) {
            }
            json = fallback;
        }
        return new TunnelResult(json);
    }

    public static void stopUdpTunnel(String handleId) {
        nativeStopUdpTunnel(handleId);
    }
}
