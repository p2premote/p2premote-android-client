package top.p2premote.android;

import android.app.Instrumentation;
import android.os.Bundle;
import org.json.JSONObject;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Real Android/JNI/crypto threads, isolated synthetic identities and no login. */
public final class SignalInstrumentation extends Instrumentation {
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            testExchange();
            testCancellation();
            testThrowingCallback();
            result.putString("stream", "JNI signal exchange, cancellation and callback failure passed\n");
            finish(-1, result);
        } catch (Throwable error) {
            result.putString("stream", "FAILED: " + error + "\n");
            finish(0, result);
        }
    }
    public static final class Bridge implements PunchNative.SignalSender, AutoCloseable {
        volatile String own, peer;
        final long source;
        final AtomicInteger messages = new AtomicInteger();
        final ExecutorService executor = Executors.newSingleThreadExecutor();
        Bridge(long source) { this.source = source; }
        @Override public boolean enqueue(long request, String data, long remaining) {
            executor.execute(() -> {
                String error = "";
                try {
                    messages.incrementAndGet();
                    JSONObject identity = new JSONObject().put("connection_id", "jni-test").put("source_device_id", source);
                    JSONObject accepted = new JSONObject(PunchNative.nativeDeliverSignal(peer, identity.toString(), data));
                    if (!accepted.getBoolean("ok")) error = accepted.optString("error", "delivery_failed");
                } catch (Exception failure) { error = failure.toString(); }
                PunchNative.nativeCompleteSignalSend(own, request, error);
            });
            return true;
        }
        @Override public void close() { if (own != null) PunchNative.nativeCloseSignalSession(own); executor.shutdownNow(); }
    }
    private static String create(Bridge bridge, long peer, String attempt) throws Exception {
        JSONObject context = new JSONObject().put("connection_id", "jni-test").put("attempt_id", attempt)
                .put("local_device_id", bridge.source).put("peer_device_id", peer);
        JSONObject result = new JSONObject(PunchNative.nativeCreateSignalSession(context.toString(), bridge));
        require(result.getBoolean("ok"), result.toString());
        return result.getString("handle_id");
    }
    private static void register(String handle, String call, String token) throws Exception {
        JSONObject result = new JSONObject(PunchNative.nativeRegisterSignalCall(handle,
                new JSONObject().put("call_id", call).put("token", token).put("timeout_ms", 20_000).toString()));
        require(result.getBoolean("ok"), result.toString());
    }
    private static JSONObject request(int mode, String data) throws Exception {
        return new JSONObject().put("token", "abcdefghijklmnop-kx").put("exmode", mode)
                .put("role_hint", mode == 0 ? "active" : "passive").put("send_data", data).put("timeout_secs", 15);
    }
    private static void require(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    private static void testExchange() throws Exception {
        ExecutorService jobs = Executors.newFixedThreadPool(2);
        try (Bridge a = new Bridge(1); Bridge b = new Bridge(2)) {
            a.own = create(a, 2, "exchange"); b.own = create(b, 1, "exchange"); a.peer = b.own; b.peer = a.own;
            register(a.own, "wg-config", "abcdefghijklmnop-kx"); register(b.own, "wg-config", "abcdefghijklmnop-kx");
            String activeRequest = request(0, "active-config").toString();
            Future<JSONObject> active = jobs.submit(() -> new JSONObject(PunchNative.nativeExchangeWithSignal(a.own, activeRequest)));
            JSONObject received = new JSONObject(PunchNative.nativeExchangeWithSignal(b.own, request(1, "").toString()));
            require(received.getBoolean("ok") && "active-config".equals(received.getString("recv_data")), "wait-only failed");
            JSONObject replied = new JSONObject(PunchNative.nativeExchangeWithSignal(b.own, request(2, "passive-config").toString()));
            JSONObject finished = active.get(20, TimeUnit.SECONDS);
            require(replied.getBoolean("ok") && finished.getBoolean("ok"), "reply failed");
            require("passive-config".equals(finished.getString("recv_data")), "bad reply payload");
            require(a.messages.get() == 1 && b.messages.get() == 1, "extra data/ACK round trip");
        } finally { jobs.shutdownNow(); }
    }
    private static void testCancellation() throws Exception {
        ExecutorService jobs = Executors.newSingleThreadExecutor();
        try (Bridge a = new Bridge(1)) {
            a.own = create(a, 2, "cancel"); register(a.own, "wg-config", "abcdefghijklmnop-kx");
            String input = request(1, "").toString();
            Future<JSONObject> waiting = jobs.submit(() -> new JSONObject(PunchNative.nativeExchangeWithSignal(a.own, input)));
            Thread.sleep(100);
            PunchNative.nativeCloseSignalSession(a.own);
            JSONObject result = waiting.get(3, TimeUnit.SECONDS);
            require(!result.getBoolean("ok"), "closed session succeeded");
        } finally { jobs.shutdownNow(); }
    }

    public static final class ThrowingSender implements PunchNative.SignalSender {
        @Override public boolean enqueue(long request, String data, long remaining) {
            throw new IllegalStateException("synthetic callback failure");
        }
    }
    private static void testThrowingCallback() throws Exception {
        JSONObject context = new JSONObject().put("connection_id", "jni-test").put("attempt_id", "exception")
                .put("local_device_id", 1).put("peer_device_id", 2);
        JSONObject created = new JSONObject(PunchNative.nativeCreateSignalSession(context.toString(), new ThrowingSender()));
        require(created.getBoolean("ok"), created.toString());
        String handle = created.getString("handle_id");
        try {
            register(handle, "wg-config", "abcdefghijklmnop-kx");
            JSONObject result = new JSONObject(PunchNative.nativeExchangeWithSignal(handle, request(0, "config").toString()));
            require(!result.getBoolean("ok") && result.toString().contains("signal_callback_failed"), result.toString());
            register(handle, "network/1", "abcdefghijklmnop");
            JSONObject tunnelRequest = new JSONObject().put("token", "abcdefghijklmnop").put("role_hint", "active")
                    .put("traversal_mode", "lan").put("network", "udp4").put("timeout_secs", 5)
                    .put("local_listen_ip", "127.0.0.1").put("remote_target_ip", "127.0.0.1").put("remote_target_port", 9);
            PunchNative.TunnelResult failed = PunchNative.startWithSignal(handle, "network/1", tunnelRequest.toString());
            require(!failed.getOK() && failed.getSignalFailed() && failed.getError().contains("signal_callback_failed"),
                    "signal failure classification was lost: " + failed.getError());
            register(handle, "network/2", "abcdefghijklmnop");
            tunnelRequest.put("network", "unsupported");
            PunchNative.TunnelResult ordinary = PunchNative.startWithSignal(handle, "network/2", tunnelRequest.toString());
            require(!ordinary.getOK() && !ordinary.getSignalFailed(), "ordinary native failure was classified as signaling");
        } finally { PunchNative.nativeCloseSignalSession(handle); }
        testExchange(); // A pending Java exception must not poison later runtime callbacks.
    }
}
