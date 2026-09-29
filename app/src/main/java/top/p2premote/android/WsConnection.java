package top.p2premote.android;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;
import org.java_websocket.WebSocket;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.framing.Framedata;
import org.java_websocket.framing.PingFrame;
import org.java_websocket.handshake.ServerHandshake;

import java.net.URI;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 进程级 WebSocket 连接单例，由 PresenceService 启动/维持，WgvpnService 共享用于发收
 * p2p_notify 业务消息。
 *
 * 职责：
 *   - 维持到服务端的 WS 长连接（协议 Ping/Pong 保活 + 指数退避重连）
 *   - {@link #sendNotifyWaitAck} 发送 p2p_notify 并同步等待服务端 p2p_notify_ack
 *   - {@link #setPeerNotifyListener} 接收被动端回传的 attempt_ready、审批事件和 attempt_failed
 *
 * 线程模型：WebSocket 回调在库线程；sendNotifyWaitAck 由 WgvpnService executor 调用，
 * 用 ConcurrentHashMap(message_id→CompletableFuture) 跨线程配对 ack。
 *
 * 协议对齐桌面端 ws.rs（commit 346a5e2）：
 *   - 建连后立即发首次协议 Ping，收到匹配 Pong 才进入 CONNECTED；之后每 20s Ping
 *   - p2p_notify 发送后服务端立即回 p2p_notify_ack（≤5s）
 *   - 被动端通过 p2p_notify 回传 attempt_ready、审批事件和 attempt_failed（业务 data 是嵌套 JSON 字符串）
 */
public final class WsConnection {

    private static final String TAG = "WsConnection";

    private static final long PING_INTERVAL_SEC = 20;
    private static final long PONG_TIMEOUT_SEC = 10;
    private static final int MAX_CONSECUTIVE_HEARTBEAT_FAILURES = 3;
    private static final long RECONNECT_BASE_MS = 2000;
    private static final long RECONNECT_MAX_MS = 60_000;
    /** sendNotifyWaitAck 等待服务端 ack 的超时。 */
    private static final long NOTIFY_ACK_TIMEOUT_SEC = 5;

    private static volatile WsConnection instance;

    private final ScheduledExecutorService heartbeatExecutor = Executors.newSingleThreadScheduledExecutor();
    private final ExecutorService connectionExecutor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean connected = new AtomicBoolean(false);
    /** start/stop 代际；所有延迟重连都必须属于当前代际。 */
    private final AtomicLong lifecycleGeneration = new AtomicLong(0);
    private final SessionStore sessionStore;
    private final ApiClient apiClient;
    /** socket 替换和 stop 关闭必须原子，防止停止后迟到任务重新挂上连接。 */
    private final Object connectionLock = new Object();

    private volatile WebSocketClient webSocket;
    private final Object heartbeatLock = new Object();
    private long nextPingSequence = 0;
    private long waitingPingSequence = 0;
    private int consecutiveHeartbeatFailures = 0;
    private ScheduledFuture<?> pingFuture;
    private ScheduledFuture<?> pongTimeoutFuture;
    private ScheduledFuture<?> reconnectFuture;
    private volatile int reconnectAttempt = 0;
    private volatile String activeState = PresenceService.STATE_DISCONNECTED;
    /** 等待 p2p_notify_ack 的请求表：message_id → 结果（true=accepted）。 */
    private final ConcurrentHashMap<String, java.util.concurrent.CompletableFuture<Boolean>> pendingAcks =
            new ConcurrentHashMap<>();

    /** 被动端 p2p_notify 回调（attempt_ready/approval_result/attempt_failed）。 */
    private volatile PeerNotifyListener peerNotifyListener;
    private volatile TraversalBinding traversalBinding;

    interface TraversalListener { void onMessage(JSONObject data); }
    private static final class TraversalBinding {
        final String connection, attempt;
        final long peer;
        final TraversalListener listener;
        TraversalBinding(String connection, long peer, String attempt, TraversalListener listener) {
            this.connection = connection; this.peer = peer; this.attempt = attempt; this.listener = listener;
        }
    }
    void setTraversalListener(String connection, long peer, String attempt, TraversalListener listener) {
        traversalBinding = new TraversalBinding(connection, peer, attempt, listener);
    }
    void clearTraversalListener(String attempt) {
        TraversalBinding binding = traversalBinding;
        if (binding != null && binding.attempt.equals(attempt)) traversalBinding = null;
    }

    /** 连接状态变化回调（PresenceService 注册，用于更新通知栏 + 广播 UI）。 */
    private volatile StateListener stateListener;

    /** 连接状态监听接口。 */
    interface StateListener {
        void onStateChanged(String state, String message);
    }

    private WsConnection(Context context) {
        sessionStore = new SessionStore(context.getApplicationContext());
        apiClient = new ApiClient(sessionStore);
    }

    /** 获取进程级单例。 */
    public static WsConnection get(Context context) {
        if (instance == null) {
            synchronized (WsConnection.class) {
                if (instance == null) {
                    instance = new WsConnection(context);
                }
            }
        }
        return instance;
    }

    /** 当前是否已完成协议心跳确认（不是单纯 TCP/WS onOpen）。 */
    public boolean isConnected() {
        return connected.get();
    }

    /** 最近一次广播的状态（PresenceService 读取做通知栏展示）。 */
    String activeState() {
        return activeState;
    }

    /**
     * 启动 WS 连接。PresenceService.ACTION_START 时调用。
     */
    void start(Session session) {
        if (session == null) {
            Log.w(TAG, "start: no session");
            return;
        }
        if (!running.compareAndSet(false, true)) {
            Log.d(TAG, "start ignored: connection lifecycle already running");
            return;
        }
        cancelReconnect();
        long generation = lifecycleGeneration.incrementAndGet();
        connectionExecutor.execute(() -> connectInternal(generation));
    }

    /**
     * 停止 WS 连接。PresenceService.ACTION_STOP 时调用。
     */
    void stop() {
        running.set(false);
        lifecycleGeneration.incrementAndGet();
        cancelReconnect();
        synchronized (connectionLock) {
            disconnectSocket();
        }
        connected.set(false);
        activeState = PresenceService.STATE_DISCONNECTED;
        // 唤醒所有等待 ack 的调用方，避免 WgvpnService 永久阻塞。
        for (java.util.concurrent.CompletableFuture<Boolean> f : pendingAcks.values()) {
            f.complete(false);
        }
        pendingAcks.clear();
    }

    /**
     * 设置被动端 p2p_notify 回调。WgvpnService 建链前注册，拿到就绪和审批结果。
     * 传 null 清除。
     */
    public void setPeerNotifyListener(PeerNotifyListener listener) {
        peerNotifyListener = listener;
    }

    /**
     * 设置连接状态变化回调。PresenceService 注册，用于更新通知栏 + 广播 UI。
     */
    void setStateListener(StateListener listener) {
        stateListener = listener;
    }

    /**
     * 发送 p2p_notify 并同步等待服务端 p2p_notify_ack（≤5s）。
     * 仅确认服务端已转发给目标设备，不代表被动端已处理。
     *
     * @return true=ack accepted; false=被拒/超时/未连接
     */
    public boolean sendNotifyWaitAck(String connectionId, long targetDeviceId,
                                     String messageId, String accessGrant, String data) {
        if (!connected.get() || webSocket == null) {
            Log.w(TAG, "sendNotify: not connected");
            return false;
        }
        try {
            // 构造 p2p_notify 消息体（对齐桌面 ws.rs WsSendType::P2PNotify）
            JSONObject payload = new JSONObject();
            payload.put("connection_id", connectionId);
            payload.put("target_device_id", targetDeviceId);
            payload.put("message_id", messageId);
            payload.put("access_grant", accessGrant);
            payload.put("data", data);  // data 本身是嵌套 JSON 字符串
            JSONObject msg = new JSONObject();
            msg.put("type", "p2p_notify");
            msg.put("payload", payload);

            java.util.concurrent.CompletableFuture<Boolean> future = new java.util.concurrent.CompletableFuture<>();
            pendingAcks.put(messageId, future);

            if (!webSocket.isOpen()) {
                pendingAcks.remove(messageId);
                Log.w(TAG, "sendNotify: socket closed");
                return false;
            }
            webSocket.send(msg.toString());
            try {
                return future.get(NOTIFY_ACK_TIMEOUT_SEC, TimeUnit.SECONDS);
            } catch (Exception e) {
                Log.w(TAG, "sendNotify ack wait failed: " + e.getMessage());
                return false;
            }
        } catch (Exception e) {
            Log.e(TAG, "sendNotify error", e);
            return false;
        } finally {
            pendingAcks.remove(messageId);
        }
    }

    // ============ 内部连接逻辑 ============

    /** 每次首次连接和重连都从 SessionStore 取得最新令牌。 */
    private void connectInternal(long generation) {
        if (!isCurrent(generation)) {
            return;
        }
        final Session session;
        try {
            // 不能复用启动时 Session 快照：HTTP 层可能已经旋转 access/refresh token。
            session = apiClient.ensureFreshSession();
        } catch (Exception e) {
            if (isCurrent(generation)) {
                handleDisconnect("刷新登录态失败：" + e.getMessage());
            }
            return;
        }
        if (!isCurrent(generation)) {
            return;
        }
        String wsUrl = session.serverUrl
                .replace("https://", "wss://")
                .replace("http://", "ws://");
        String fullUrl = wsUrl + "/ws?device_id=" + session.deviceId
                + "&device_uuid=" + session.deviceUuid;

        updateState(PresenceService.STATE_CONNECTING);
        Log.i(TAG, "connecting WebSocket: device_id=" + session.deviceId);
        Map<String, String> headers = new HashMap<>();
        headers.put("Authorization", "Bearer " + session.accessToken);
        final WebSocketClient socket;
        try {
            socket = new WebSocketClient(new URI(fullUrl), headers) {
            @Override
            public void onOpen(ServerHandshake handshake) {
                if (webSocket != this || !running.get()) return;
                connected.set(false);
                updateState(PresenceService.STATE_CONNECTING);
                Log.i(TAG, "WebSocket opened, sending initial protocol ping");
                resetHeartbeat();
                sendProtocolPing(this);
                synchronized (heartbeatLock) {
                    pingFuture = heartbeatExecutor.scheduleAtFixedRate(
                            () -> sendProtocolPing(this), PING_INTERVAL_SEC,
                            PING_INTERVAL_SEC, TimeUnit.SECONDS);
                }
            }

            @Override
            public void onMessage(String text) {
                if (webSocket != this) return;
                handleMessage(text);
            }

            @Override
            public void onClose(int code, String reason, boolean remote) {
                if (webSocket != this) return;
                handleDisconnect("连接已关闭 (" + code + ")");
            }

            @Override
            public void onError(Exception error) {
                if (webSocket != this) return;
                Log.w(TAG, "WebSocket error: " + error.getMessage(), error);
            }

            @Override
            public void onWebsocketPong(WebSocket conn, Framedata frame) {
                super.onWebsocketPong(conn, frame);
                if (webSocket != this) return;
                acceptProtocolPong(frame.getPayloadData());
            }
            };
        } catch (Exception e) {
            handleDisconnect("连接失败：" + e.getMessage());
            return;
        }
        synchronized (connectionLock) {
            if (!isCurrent(generation)) {
                return;
            }
            disconnectSocket();
            webSocket = socket;
            socket.connect();
        }
    }

    private boolean isCurrent(long generation) {
        return running.get() && lifecycleGeneration.get() == generation;
    }

    /** 与桌面端 ws.rs 一致：首次立即 Ping，匹配 Pong 后才确认在线。 */
    private void sendProtocolPing(WebSocketClient socket) {
        final long sequence;
        synchronized (heartbeatLock) {
            if (webSocket != socket || !socket.isOpen() || waitingPingSequence != 0) return;
            sequence = ++nextPingSequence;
            waitingPingSequence = sequence;
            if (pongTimeoutFuture != null) pongTimeoutFuture.cancel(false);
            pongTimeoutFuture = heartbeatExecutor.schedule(
                    () -> handlePongTimeout(socket, sequence), PONG_TIMEOUT_SEC, TimeUnit.SECONDS);
        }
        ByteBuffer payload = ByteBuffer.allocate(Long.BYTES).putLong(sequence);
        payload.flip();
        PingFrame ping = new PingFrame();
        ping.setPayload(payload);
        socket.sendFrame(ping);
        Log.d(TAG, "protocol ping sent: sequence=" + sequence);
    }

    private void acceptProtocolPong(ByteBuffer payload) {
        if (payload == null || payload.remaining() != Long.BYTES) return;
        long sequence = payload.slice().getLong();
        boolean firstConfirmation;
        synchronized (heartbeatLock) {
            if (waitingPingSequence != sequence) return;
            waitingPingSequence = 0;
            consecutiveHeartbeatFailures = 0;
            if (pongTimeoutFuture != null) pongTimeoutFuture.cancel(false);
            pongTimeoutFuture = null;
            firstConfirmation = connected.compareAndSet(false, true);
            if (firstConfirmation) {
                reconnectAttempt = 0;
            }
        }
        if (firstConfirmation) {
            updateState(PresenceService.STATE_CONNECTED);
            Log.i(TAG, "first protocol pong received, connection confirmed");
        } else {
            Log.d(TAG, "protocol pong acknowledged: sequence=" + sequence);
        }
    }

    private void handlePongTimeout(WebSocketClient socket, long sequence) {
        int failures;
        synchronized (heartbeatLock) {
            if (webSocket != socket || waitingPingSequence != sequence) return;
            waitingPingSequence = 0;
            pongTimeoutFuture = null;
            failures = ++consecutiveHeartbeatFailures;
        }
        Log.w(TAG, "protocol pong missed: " + failures + "/" + MAX_CONSECUTIVE_HEARTBEAT_FAILURES);
        if (failures >= MAX_CONSECUTIVE_HEARTBEAT_FAILURES) {
            socket.close(1001, "heartbeat timeout");
        }
    }

    private void resetHeartbeat() {
        synchronized (heartbeatLock) {
            waitingPingSequence = 0;
            consecutiveHeartbeatFailures = 0;
            if (pingFuture != null) pingFuture.cancel(false);
            if (pongTimeoutFuture != null) pongTimeoutFuture.cancel(false);
            pingFuture = null;
            pongTimeoutFuture = null;
        }
    }

    /**
     * 处理服务端推送的消息：p2p_notify_ack 唤醒等待方；p2p_notify 回调 listener。
     */
    private void handleMessage(String text) {
        try {
            JSONObject msg = new JSONObject(text);
            String type = msg.optString("type", "");
            JSONObject payload = msg.optJSONObject("payload");

            if ("p2p_notify_ack".equals(type) && payload != null) {
                String messageId = payload.optString("message_id", "");
                boolean accepted = payload.optBoolean("accepted", false);
                java.util.concurrent.CompletableFuture<Boolean> future = pendingAcks.remove(messageId);
                if (future != null) {
                    future.complete(accepted);
                } else {
                    Log.d(TAG, "p2p_notify_ack unmatched: " + messageId);
                }
                return;
            }

			if ("wol_request".equals(type) && payload != null) {
				final JSONObject request = payload;
				new Thread(() -> handleWolRequest(request), "p2premote-wol").start();
				return;
			}

            if ("p2p_notify".equals(type)) {
                if (payload == null) return;
                TraversalBinding binding = traversalBinding;
                if (binding != null && binding.connection.equals(payload.optString("connection_id"))
                        && binding.peer == payload.optLong("source_device_id")) {
                    JSONObject business = new JSONObject(payload.optString("data", "{}"));
                    if (binding.attempt.equals(business.optString("attempt_id"))) binding.listener.onMessage(business);
                }
                // 被动端回传（attempt_ready / approval_* / attempt_failed）。data 是嵌套 JSON 字符串。
                PeerNotifyListener listener = peerNotifyListener;
                if (listener == null) {
                    Log.d(TAG, "p2p_notify received without an attempt listener");
                    return;
                }
                String dataStr = payload.optString("data", "");
                dispatchPeerNotify(listener, dataStr);
                return;
            }

            Log.d(TAG, "signal received: type=" + type);
        } catch (Exception e) {
            Log.d(TAG, "non-json message: " + text);
        }
    }

	private void handleWolRequest(JSONObject payload) {
		String requestId=payload.optString("request_id",""); boolean success=false; String code="send_failed";
		try { if(requestId.isEmpty())return; WolSupport.send(payload.optJSONArray("macs"),payload.optString("target_ipv4",""),payload.optInt("prefix_len",0)); success=true; code="sent"; }
		catch(Exception e){Log.w(TAG,"WOL send failed: "+e.getMessage());}
		try { WebSocketClient socket=webSocket; if(socket!=null&&socket.isOpen())socket.send(new JSONObject().put("type","wol_result").put("payload",new JSONObject().put("request_id",requestId).put("success",success).put("code",code)).toString()); }
		catch(Exception e){Log.w(TAG,"WOL result send failed: "+e.getMessage());}
	}

    /**
     * 解析被动端 p2p_notify 的 data（嵌套 JSON），按 type 回调 listener。
     */
    private void dispatchPeerNotify(PeerNotifyListener listener, String dataStr) {
        try {
            JSONObject data = new JSONObject(dataStr);
            String type = data.optString("type", "");
            String attemptId = data.optString("attempt_id", "");
            switch (type) {
                case "attempt_ready": {
                    int rdpPort = data.optInt("rdp_port", 0);
                    boolean approvalRequired = data.optBoolean("approval_required", false);
                    listener.onNotify(type, attemptId, rdpPort, approvalRequired, 0L, null);
                    break;
                }
                case "approval_required": {
                    long expiresAt = data.optLong("expires_at", 0L);
                    listener.onNotify(type, attemptId, 0, false, expiresAt, null);
                    break;
                }
                case "approval_granted":
                case "approval_denied":
                case "approval_timeout": {
                    listener.onNotify(type, attemptId, 0, false, 0L, null);
                    break;
                }
                case "attempt_failed": {
                    JSONObject err = data.optJSONObject("error");
                    String errMsg = err != null ? err.optString("message", "unknown") : "unknown";
                    listener.onNotify(type, attemptId, 0, false, 0L, errMsg);
                    break;
                }
                default:
                    Log.d(TAG, "peer notify unknown type: " + type);
            }
        } catch (Exception e) {
            Log.w(TAG, "dispatchPeerNotify parse failed: " + e.getMessage());
        }
    }

    private void handleDisconnect(String reason) {
        resetHeartbeat();
        connected.set(false);
        updateState(PresenceService.STATE_DISCONNECTED);
        Log.w(TAG, "WebSocket disconnected: " + reason);
        scheduleReconnect();
    }

    private void scheduleReconnect() {
        if (!running.get()) {
            return;
        }
        long delay = RECONNECT_BASE_MS * (1L << Math.min(reconnectAttempt, 5));
        if (delay > RECONNECT_MAX_MS) {
            delay = RECONNECT_MAX_MS;
        }
        reconnectAttempt++;
        Log.i(TAG, "schedule reconnect in " + delay + "ms (attempt " + reconnectAttempt + ")");
        long generation = lifecycleGeneration.get();
        synchronized (heartbeatLock) {
            if (reconnectFuture != null) reconnectFuture.cancel(false);
            reconnectFuture = heartbeatExecutor.schedule(
                    () -> connectionExecutor.execute(() -> connectInternal(generation)),
                    delay, TimeUnit.MILLISECONDS);
        }
    }

    private void cancelReconnect() {
        synchronized (heartbeatLock) {
            if (reconnectFuture != null) {
                reconnectFuture.cancel(false);
                reconnectFuture = null;
            }
        }
    }

    private void disconnectSocket() {
        resetHeartbeat();
        WebSocketClient socket = webSocket;
        webSocket = null;
        if (socket != null) {
            try {
                socket.close(1000, "client shutdown");
            } catch (Exception ignored) {
            }
        }
    }

    private void updateState(String state) {
        activeState = state;
        StateListener listener = stateListener;
        if (listener != null) {
            String msg;
            switch (state) {
                case PresenceService.STATE_CONNECTED: msg = "在线保活已连接"; break;
                case PresenceService.STATE_CONNECTING: msg = "正在连接服务..."; break;
                default: msg = "连接断开，重连中"; break;
            }
            listener.onStateChanged(state, msg);
        }
    }

    /** 被动端 p2p_notify 回调接口。 */
    public interface PeerNotifyListener {
        /**
         * @param type      attempt_ready / approval_* / attempt_failed
         * @param attemptId 本次尝试 ID
         * @param rdpPort   attempt_ready 时的 RDP 端口（其他类型为 0）
         * @param approvalRequired attempt_ready 携带的被动端审批要求（其他类型为 false）
         * @param expiresAt approval_required 的过期时间戳（其他类型为 0）
         * @param error     attempt_failed 时的错误信息（attempt_ready 为 null）
         */
        void onNotify(String type, String attemptId, int rdpPort, boolean approvalRequired,
                      long expiresAt, String error);
    }
}
