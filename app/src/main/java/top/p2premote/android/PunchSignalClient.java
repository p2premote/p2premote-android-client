package top.p2premote.android;

import org.json.JSONObject;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** JNI callback only enqueues; the WS receive thread never waits for native work. */
final class PunchSignalClient implements AutoCloseable, PunchNative.SignalSender {
    private final WsConnection ws;
    private final ApiClient.OpenResult opened;
    private final long peer, generation;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final ThreadPoolExecutor sends = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(16), task -> {
                Thread thread = new Thread(task, "p2premote-punch-signal"); thread.setDaemon(true); return thread;
            });
    private final String handle;

    PunchSignalClient(WsConnection ws, ApiClient.OpenResult opened, long local, long peer, String attempt) throws Exception {
        this.ws = ws; this.opened = opened; this.peer = peer; this.generation = ws.socketGeneration();
        JSONObject context = new JSONObject().put("connection_id", opened.connectionId).put("attempt_id", attempt)
                .put("local_device_id", local).put("peer_device_id", peer);
        JSONObject result = checked(PunchNative.nativeCreateSignalSession(context.toString(), this));
        this.handle = result.getString("handle_id");
    }
    private static JSONObject checked(String json) throws Exception {
        JSONObject result = new JSONObject(json);
        if (!result.optBoolean("ok")) throw new IllegalStateException(result.optString("error", "signal_native_failed"));
        return result;
    }
    void register(String call, String token, long millis) throws Exception {
        if (closed.get()) throw new IllegalStateException("signal_cancelled");
        checked(PunchNative.nativeRegisterSignalCall(handle, new JSONObject().put("call_id", call)
                .put("token", token).put("timeout_ms", millis).toString()));
    }
    void receive(JSONObject data) throws Exception {
        if (closed.get() || ws.socketGeneration() != generation) throw new IllegalStateException("signal_connection_lost");
        JSONObject identity = new JSONObject().put("connection_id", opened.connectionId).put("source_device_id", peer);
        JSONObject result = new JSONObject(PunchNative.nativeDeliverSignal(handle, identity.toString(), data.toString()));
        if (!result.optBoolean("ok")) {
            String error = result.optString("error", "signal_native_failed");
            if (!"stale_signal_frame".equals(error)) { close(); throw new IllegalStateException(error); }
        }
    }
    PunchNative.ExchangeResult exchange(String token, String data, int timeout) throws Exception {
        if (closed.get()) throw new IllegalStateException("signal_cancelled");
        JSONObject request = new JSONObject().put("token", token + "-kx").put("send_data", data)
                .put("role_hint", "active").put("exmode", 0).put("timeout_secs", timeout);
        return PunchNative.exchangeWithSignal(handle, request.toString());
    }
    PunchNative.TunnelResult start(String call, JSONObject request) throws Exception {
        if (closed.get()) throw new IllegalStateException("signal_cancelled");
        return PunchNative.startWithSignal(handle, call, request.toString());
    }
    void closeCall(String call) { if (!closed.get()) PunchNative.nativeCancelSignalCall(handle, call); }

    @Override public boolean enqueue(long requestId, String data, long remainingMillis) {
        if (closed.get() || !ws.isConnected() || ws.socketGeneration() != generation) return false;
        final long deadline = android.os.SystemClock.elapsedRealtime() + remainingMillis;
        try {
            sends.execute(() -> {
                long remaining = deadline - android.os.SystemClock.elapsedRealtime();
                String error = remaining <= 0 ? "signal_delivery_timeout" : ws.sendSignalWaitAck(opened.connectionId, peer,
                        ApiClient.buildMessageId(), opened.accessGrant, data, generation, remaining);
                PunchNative.nativeCompleteSignalSend(handle, requestId, error == null ? "" : error);
            });
            return true;
        } catch (java.util.concurrent.RejectedExecutionException error) { return false; }
    }
    @Override public void close() {
        if (closed.compareAndSet(false, true)) {
            PunchNative.nativeCloseSignalSession(handle);
            sends.shutdownNow();
        }
    }
}
