package top.p2premote.android;

/** Android JNI bridge for the Rust riperf3 client. */
final class Riperf3Native {
    private static final String LIBRARY_NAME = "p2premote_riperf3_jni";
    private static final boolean AVAILABLE;
    private static final String LOAD_ERROR;

    static {
        boolean available = false;
        String error = "";
        try {
            System.loadLibrary(LIBRARY_NAME);
            available = true;
        } catch (UnsatisfiedLinkError e) {
            error = e.getMessage() == null ? "native library unavailable" : e.getMessage();
        }
        AVAILABLE = available;
        LOAD_ERROR = error;
    }

    private Riperf3Native() {}

    /** @param reverse false=上传（本机→对端），true=下载（对端→本机）。 */
    static String runClient(String peerVirtualIp, int port, int durationSecs, boolean reverse) {
        if (!AVAILABLE) {
            throw new IllegalStateException("测速原生库不可用：" + LOAD_ERROR);
        }
        return nativeRunClient(peerVirtualIp, port, durationSecs, reverse);
    }

    private static native String nativeRunClient(String peerVirtualIp, int port, int durationSecs,
                                                 boolean reverse);
}
