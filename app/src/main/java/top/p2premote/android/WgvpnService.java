package top.p2premote.android;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.VpnService;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import libwgmobile.Keypair;
import libwgmobile.Libwgmobile;
import libwgmobile.WgResult;
import libwgmobile.WgTransferStats;
import top.p2premote.android.PunchNative.ExchangeResult;
import top.p2premote.android.PunchNative.TunnelResult;

/**
 * wgvpn 隧道服务（Foreground + VpnService）。
 *
 * 实现完整的 wgvpn active 建链/断链流程，对齐桌面端新 P2P 信令协议（commit 346a5e2）：
 *   - 生成/加载 WG 密钥对
 *   - POST /api/v1/p2p/open -> connection_id + access_grant（无 punch_token）
 *   - 客户端生成 punch_token + attempt_id
 *   - WS p2p_notify(attempt_start) -> 服务端 ack
 *   - WS 等待被动端 attempt_ready（≤30s）
 *   - gonc Exchange(punch_token) -> 双方虚拟 IP + 公钥
 *   - gonc StartUdpTunnel -> local_forward_port
 *   - VpnService establish TUN fd + WG Start/AddPeer + 等握手
 *   - POST /api/v1/p2p/end {connection_id}
 *   - 广播状态到 UI
 *
 * WS 信令通过 WsConnection 单例收发（与 PresenceService 共享同一连接）。
 *
 * fd 所有权约定：wgStart 成功后立即 detachFd()，TUN fd 由 native
 * (wireguard-go androidTun.os.File) 全权管理，WgStop 时统一关闭；
 * 仅 wgStart 之前失败时才由 cleanupNative 关闭 tunFd，杜绝 double-close。
 */
public final class WgvpnService extends VpnService {

    private static final String TAG = "WgvpnService";

    static final String ACTION_START = "top.p2premote.android.action.WGVPN_START";
    static final String ACTION_STOP = "top.p2premote.android.action.WGVPN_STOP";
    static final String ACTION_STATUS = "top.p2premote.android.action.WGVPN_STATUS";
    static final String ACTION_TEST_SPEED = "top.p2premote.android.action.WGVPN_TEST_SPEED";
    static final String ACTION_SPEED_RESULT = "top.p2premote.android.action.WGVPN_SPEED_RESULT";
    /** Activity 重建后查询当前隧道状态，服务收到后回发一次 ACTION_STATUS 广播。 */
    static final String ACTION_QUERY = "top.p2premote.android.action.WGVPN_QUERY";

    static final String EXTRA_DEVICE_ID = "device_id";
    static final String EXTRA_DEVICE_UUID = "device_uuid";
    static final String EXTRA_DEVICE_NAME = "device_name";
    static final String EXTRA_STATE = "state";
    static final String EXTRA_MESSAGE = "message";
    static final String EXTRA_TCP_RETRY = "tcp_retry_recommended";
    static final String EXTRA_NETWORK = "network";
    static final String EXTRA_VIRTUAL_IP = "virtual_ip";
    static final String EXTRA_PEER_VIRTUAL_IP = "peer_virtual_ip";
    static final String EXTRA_EXPOSED_LAN = "exposed_lan";
    /** 隧道连接时间（ms 时间戳），CONNECTED 时带出，断开时为 0。 */
    static final String EXTRA_CONNECTED_AT = "connected_at";
    /** 累计下载字节数（从对端接收），CONNECTED 时实时更新。 */
    static final String EXTRA_RX_BYTES = "rx_bytes";
    /** 累计上传字节数（发送到对端），CONNECTED 时实时更新。 */
    static final String EXTRA_TX_BYTES = "tx_bytes";
    static final String EXTRA_LATENCY_MS = "latency_ms";
    static final String EXTRA_SPEED_SUCCESS = "speed_success";
    static final String EXTRA_SPEED_LATENCY_MS = "speed_latency_ms";
    static final String EXTRA_SPEED_DOWNLOAD_MBPS = "speed_download_mbps";
    static final String EXTRA_SPEED_UPLOAD_MBPS = "speed_upload_mbps";

    private static final String CHANNEL_ID = "p2premote_wgvpn";
    private static final int NOTIFICATION_ID = 2001;

    // wgvpn 常量（对齐桌面 p2p.rs wgvpn_flow）
    private static final String WGVPN_CIDR_PREFIX = "100.99.71.";
    private static final int WGVPN_MTU = 1280;
    private static final int WG_LISTEN_PORT = 51820;
    private static final int WG_HANDSHAKE_TIMEOUT_SEC = 15;
    private static final int EXCHANGE_TIMEOUT_SEC = 60;
    /** gonc UDP 打洞总超时。对齐桌面端（gonc 已支持循环重试到超时），45s 偏小会限制重试次数。 */
    private static final int UDP_TUNNEL_TIMEOUT_SEC = 100;
    private static final int IP_RANGE_START_OFFSET = 2;   // 100.99.71.2
    private static final int IP_RANGE_END_OFFSET = 254;   // 100.99.71.254
    private static final int PERSISTENT_KEEPALIVE = 25;
    /** 等待被动端 attempt_ready 的超时（对齐桌面 30s）。 */
    private static final int PEER_READY_TIMEOUT_SEC = 30;
    /** CONNECTED 后流量统计轮询间隔。WG rekey 会清零计数器，轮询需检测 wrap。 */
    private static final long TRAFFIC_POLL_INTERVAL_MS = 2000L;
    /** 隧道健康检查 TCP 端口（对齐桌面端 health.rs HEALTH_PORT=48082）。
     *  主动端（本机）作为 client 连接被动端「peer_virtual_ip:48082」建立健康长连接，
     *  对齐桌面 p2p.rs 的 active_health_session：connect→Hello→HelloAck→Ping/Pong。 */
    private static final int HEALTH_PORT = 48082;
    /**
     * 隧道控制协议版本。报 v3（本端消息集的基线版本）：v3→v5 仅 Hello
     * 的 session_secret 增删，本端从未携带该字段，消息结构与 v5 互通。
     * 兼容策略：不设版本上限，只要求对端不低于最低兼容版本 —— 版本号
     * 升级但消息实际兼容时不应被拒；将来出现破坏性变更时提高下限即可。
     */
    private static final int TUNNEL_CONTROL_PROTOCOL_VERSION = 3;
    /** 最低可互连的协议版本，低于此值说明对端过旧，需升级后重试。 */
    private static final int TUNNEL_CONTROL_PROTOCOL_MIN_COMPAT = 3;
    /** 健康连接 / 心跳各项超时（秒），对齐桌面 p2p.rs active_health_session。 */
    private static final int HEALTH_CONNECT_TIMEOUT_SEC = 10;
    private static final int HEALTH_HANDSHAKE_TIMEOUT_SEC = 5;
    private static final int HEALTH_PING_INTERVAL_SEC = 5;
    private static final int HEALTH_PONG_TIMEOUT_SEC = 5;
    private static final int HEALTH_RECONNECT_DELAY_SEC = 5;
    /** 连续 60 秒未收到健康通道成功响应，连接已不可信，不能继续显示为 CONNECTED。 */
    private static final long HEALTH_UNHEALTHY_TIMEOUT_MS = 60_000L;
    /** 网络切换事件在此窗口内合并，避免 Wi-Fi/蜂窝抖动触发多次打洞。 */
    private static final long NETWORK_STABLE_WINDOW_MS = 8_000L;
    /** 同一网络的系统联网验证可能短暂抖动，宽限后再确认隧道失效。 */
    private static final long NETWORK_CAPABILITY_LOSS_GRACE_MS = 15_000L;
    private static final int NETWORK_RECOVERY_MAX_ATTEMPTS = 4;
    private static final long[] NETWORK_RECOVERY_BACKOFF_MS = {3_000L, 6_000L, 12_000L};
    private static final int SPEED_TEST_PORT = 48084;
    /** 对齐桌面端：上传、下载各自独立测速 6 秒。 */
    private static final int SPEED_TEST_DURATION_SEC = 6;
    private static final int SPEED_TEST_PING_COUNT = 5;
    /**
     * 测速总超时。正常一轮 ≈ 5 次 RTT + 两个方向各 SPEED_TEST_DURATION_SEC(6s)
     * + 控制消息往返 ≈ 20s（对齐桌面 speed_test.rs：单方向上限 6+15s）。
     * 取 30s = 正常时长 + 50% 余量；更长的等待本身就是异常，应尽快反馈。
     */
    private static final int SPEED_TEST_TOTAL_TIMEOUT_SEC = 30;
    /** 服务端业务错误码：设备不在线（response.CodeDeviceOffline）。 */
    private static final int CODE_DEVICE_OFFLINE = 1054;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    /** 非 native 控制任务不能堵住断链清理队列（测速最长可等待 30 秒）。 */
    private final ExecutorService controlExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService attemptCleanupExecutor = Executors.newSingleThreadExecutor();
    private ApiClient apiClient;
    private SessionStore sessionStore;

    // 当前隧道状态
    private volatile String activeState = TunnelState.IDLE;
    private volatile String activeMessage = "";
    private volatile boolean tcpRetryRecommended;
    private volatile String selectedNetwork = "";
    private volatile String myVirtualIp = "";
    private volatile String peerVirtualIp = "";
    private volatile String exposedLan = "";
    /** 首次进入 CONNECTED 的时间戳（ms），断开后清零。对齐桌面 connected_at 语义。 */
    private volatile long connectedAtMs = 0;

    // 流量统计（累计值，处理 WG rekey 清零）
    private volatile long cumulativeRx = 0;
    private volatile long cumulativeTx = 0;
    private volatile long lastSampleRx = 0;
    private volatile long lastSampleTx = 0;
    private volatile boolean trafficPolling = false;
    private final android.os.Handler trafficHandler = new android.os.Handler(android.os.Looper.getMainLooper());

    // 隧道健康检查 client：主动端连接被动端 server，维持心跳让被动端 watchdog 不误判。
    // Android VpnService 入站 TCP 到不了本地 ServerSocket（已知限制），故主动端只能做 client。
    private volatile boolean healthClientRunning = false;
    /** 最近一次 health 握手失败的人读原因（协议版本不兼容等），供测速入口透传。 */
    private volatile String healthFailReason = "";
    private volatile java.net.Socket healthSocket = null;
    private Thread healthClientThread = null;
    private volatile long lastHealthSuccessElapsedMs = 0;
    /** 主动端用单调时钟最近一次测得的隧道 RTT；-1 表示尚无有效样本。 */
    private volatile long tunnelLatencyMs = -1;
    /**
     * Foreground Service 只降低被杀概率，并不保证 Doze 时 CPU 继续调度。
     * 隧道存在时持有 partial wakelock，保证 WG keepalive 与健康心跳能实际运行。
     */
    private PowerManager.WakeLock tunnelWakeLock;
    private boolean tunnelWakeLockTimed = false;
    /** 测速复用健康控制连接，避免额外连接断开影响被动端健康会话生命周期。 */
    private final Object speedTestLock = new Object();
    private volatile SpeedTestRequest pendingSpeedTest = null;
    private volatile boolean speedTestRunning = false;

    // 底层网络变化与自动恢复状态。NetworkRecoveryPolicy 自身同步，可跨 callback/executor 使用。
    private final Handler networkHandler = new Handler(Looper.getMainLooper());
    private final NetworkRecoveryPolicy networkRecovery =
            new NetworkRecoveryPolicy(NETWORK_STABLE_WINDOW_MS);
    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;
    private Runnable pendingRecoveryRunnable;
    private Runnable pendingCapabilityLossRunnable;
    private volatile long observedNetworkHandle = -1L;
    private final Map<Long, UnderlyingNetworkState> underlyingNetworks = new ConcurrentHashMap<>();
    private volatile boolean automaticRecoveryInProgress = false;
    private volatile boolean manualStopRequested = false;
    /** 覆盖 ACTION_START 入队到 PREPARING 状态发布之间的窄窗口，避免重复启动排入第二个任务。 */
    private final AtomicBoolean manualStartQueued = new AtomicBoolean(false);
    /** 健康线程、系统回调和 UI 可能同时请求停止；只允许一个清理任务进入 native 串行队列。 */
    private final AtomicBoolean stopQueued = new AtomicBoolean(false);
    private volatile long recoveryTargetDeviceId = 0;
    private volatile String recoveryTargetDeviceUuid = "";
    private volatile String recoveryTargetDeviceName = "";
    private volatile Thread tunnelWorkerThread;

    private static final class RecoverySupersededException extends Exception {
        RecoverySupersededException() {
            super("network recovery superseded");
        }
    }

    private static final class UnderlyingNetworkState {
        final Network network;
        final String fingerprint;
        final boolean usable;
        final int preference;

        UnderlyingNetworkState(Network network, String fingerprint, boolean usable, int preference) {
            this.network = network;
            this.fingerprint = fingerprint;
            this.usable = usable;
            this.preference = preference;
        }
    }

    private static final class SpeedTestRequest {
        final CountDownLatch completed = new CountDownLatch(1);
        String resultJson = "";
        String error = "";
    }

    // native 资源句柄
    private volatile String goncHandleId = "";
    private volatile String peerPubkeyHex = "";
    private volatile boolean wgRunning = false;
    // tunFd 仅在 wgStart 之前短暂持有：wgStart 成功后调用 detachFd() 把 fd 所有权
    // 转移给 native（wireguard-go 的 androidTun.os.File），由 WgStop 统一关闭，
    // 避免同一 fd 被 ParcelFileDescriptor 和 os.File 双重关闭。
    private volatile ParcelFileDescriptor tunFd = null;

    @Override
    public void onCreate() {
        super.onCreate();
        // START_STICKY 进程重建不经 MainActivity，Service 侧必须自行注入应用上下文，
        // 否则重建后 deviceName() 读不到系统设备名（审计 B-13）。
        DeviceIdentity.init(getApplicationContext());
        sessionStore = new SessionStore(this);
        apiClient = new ApiClient(sessionStore);
        ensureChannel();
        // 注册 protect 回调：gonc 创建的 socket 必须 protect，否则 VPN 回环。
        PunchNative.nativeSetProtectCallback(new PunchNative.ProtectCallback() {
            @Override
            public boolean protect(int fd) {
                return protectAndBindSocket(fd);
            }
        });
        registerUnderlyingNetworkCallback();
        DiagLog.attach(this);
        Log.i(TAG, "WgvpnService created, protect callback registered");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            return START_NOT_STICKY;
        }
        String action = intent.getAction();
        if (ACTION_STOP.equals(action)) {
            manualStopRequested = true;
            cancelNetworkRecovery();
            enqueueStopTunnel("用户已断开隧道");
            return START_NOT_STICKY;
        }
        if (ACTION_QUERY.equals(action)) {
            // Activity 配置变更重建后查询当前状态：直接回放一次最新状态广播。
            emitStatusOnly();
            // startService 查询会把原本未运行的 Service 置为 started。若没有隧道任务，
            // 必须结束这次启动，否则网络 callback 与 executor 会常驻到进程被杀。
            stopIfNoTunnelWork(startId);
            return START_NOT_STICKY;
        }
        if (ACTION_TEST_SPEED.equals(action)) {
            startSpeedTest();
            stopIfNoTunnelWork(startId);
            return START_NOT_STICKY;
        }
        if (ACTION_START.equals(action)) {
            if (TunnelState.isActive(activeState) || automaticRecoveryInProgress
                    || !manualStartQueued.compareAndSet(false, true)) {
                // startService 可能因双击、Activity 重建或重试被重复调用。已有隧道时只回放状态，
                // 绝不能让第二个任务进入后把第一个活动隧道标成 FAILED 并 stopSelf。
                emitStatusOnly();
                return START_STICKY;
            }
            long deviceId = intent.getLongExtra(EXTRA_DEVICE_ID, 0);
            String deviceUuid = intent.getStringExtra(EXTRA_DEVICE_UUID);
            String deviceName = intent.getStringExtra(EXTRA_DEVICE_NAME);
            manualStopRequested = false;
            recoveryTargetDeviceId = deviceId;
            recoveryTargetDeviceUuid = deviceUuid == null ? "" : deviceUuid;
            recoveryTargetDeviceName = deviceName == null ? "" : deviceName;
            startForeground(NOTIFICATION_ID, notification("正在连接对方电脑", deviceName));
            startTunnel(deviceId, deviceUuid, deviceName);
            return START_STICKY;
        }
        return START_NOT_STICKY;
    }

    private void stopIfNoTunnelWork(int startId) {
        // ACTION_START 将 native 工作排入 executor 后，状态仍可能短暂保持 IDLE。
        // VPN 授权返回触发的 Activity.onResume 会紧接着发送 ACTION_QUERY；此时若仅
        // 检查 activeState，会误停掉已有手动启动任务，并使其随后因 generation 失效。
        if (TunnelState.isTerminal(activeState) && !automaticRecoveryInProgress
                && !manualStartQueued.get()) {
            stopSelfResult(startId);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        cancelNetworkRecovery();
        unregisterUnderlyingNetworkCallback();
        // onDestroy 可能在三种情况下触发：
        //  (a) startTunnel 的 catch 块主动 stopSelf() —— 此时 activeState 已是 FAILED，
        //      cleanupNative/emit/stopForeground 都已在 catch 中完成。这里若再 stopTunnel，
        //      会 emit(STOPPED) 把 FAILED 通知覆盖成「服务已停止」，用户永远看不到真实失败原因。
        //  (b) ACTION_STOP / onRevoke 主动 stopSelf() —— 此时已是 STOPPED，再 stopTunnel 是冗余。
        //  (c) CONNECTED 等活跃态时被系统强杀 —— 此时 native 资源仍需清理，且应回到 STOPPED。
        // 因此：仅在隧道仍活跃时才完整 stopTunnel；已是终态(FAILED/STOPPED/IDLE/ABORTED)则只清 native，
        // 不再 emit，避免覆盖 catch 已设置的真实失败状态。
        boolean alreadyTerminal = TunnelState.isTerminal(activeState);
        Log.i(TAG, "onDestroy: activeState=" + activeState
                + (alreadyTerminal ? " (terminal, cleanup native only)" : " (active, full stopTunnel)"));
        stopTrafficPolling();
        stopHealthClient();
        if (!alreadyTerminal) {
            connectedAtMs = 0;
            resetTunnelCounters();
            emit(TunnelState.STOPPED, "服务已停止", "", "", "");
            stopForeground(true);
        }
        // gomobile / WireGuard 的创建与销毁必须在同一个串行 executor 上发生。
        // 系统可能在 native 调用尚未返回时销毁 Service；将最终清理排到队尾可避免
        // wgStart/stopUdpTunnel/wgStop 并发访问 native 全局状态。shutdown() 会执行完队列。
        try {
            executor.execute(() -> {
                cleanupNative();
                PunchNative.nativeSetProtectCallback(null);
            });
        } catch (RejectedExecutionException e) {
            Log.w(TAG, "native cleanup executor already closed", e);
        }
        executor.shutdown();
        controlExecutor.shutdownNow();
        attemptCleanupExecutor.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onRevoke() {
        // 用户在系统设置中撤销了 VPN 权限
        manualStopRequested = true;
        cancelNetworkRecovery();
        enqueueStopTunnel("VPN 权限已被撤销");
        super.onRevoke();
    }

    /** 监听所有非 VPN 底层网络，避免 VpnService 建立后应用默认网络变成 VPN 而遮蔽物理网络回调。 */
    private void registerUnderlyingNetworkCallback() {
        connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (connectivityManager == null || networkCallback != null) {
            return;
        }
        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                // Android O+ 保证随后按序回调 capabilities；在那里使用回调参数建快照，
                // 避免在 onAvailable 中同步查询产生竞态。
            }

            @Override
            public void onCapabilitiesChanged(Network network, NetworkCapabilities capabilities) {
                updateUnderlyingNetwork(network, capabilities, "capabilities_changed");
            }

            @Override
            public void onLost(Network network) {
                underlyingNetworks.remove(network.getNetworkHandle());
                selectBestUnderlyingNetwork("lost");
            }
        };
        try {
            // 在注册回调前先用当前 active network 建立基线；这是回调之外的同步快照。
            Network activeNetwork = connectivityManager.getActiveNetwork();
            if (activeNetwork != null) {
                NetworkCapabilities capabilities =
                        connectivityManager.getNetworkCapabilities(activeNetwork);
                if (capabilities != null
                        && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) {
                    updateUnderlyingNetwork(activeNetwork, capabilities, "initial");
                }
            }
            NetworkRequest request = new NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                    .build();
            connectivityManager.registerNetworkCallback(request, networkCallback, networkHandler);
        } catch (RuntimeException e) {
            Log.w(TAG, "register underlying network callback failed: " + e.getMessage());
            networkCallback = null;
        }
    }

    private void unregisterUnderlyingNetworkCallback() {
        if (connectivityManager != null && networkCallback != null) {
            try {
                connectivityManager.unregisterNetworkCallback(networkCallback);
            } catch (RuntimeException e) {
                Log.w(TAG, "unregisterNetworkCallback failed: " + e.getMessage());
            }
        }
        networkCallback = null;
        underlyingNetworks.clear();
    }

    private void updateUnderlyingNetwork(Network network, NetworkCapabilities capabilities,
                                         String reason) {
        if (network == null || capabilities == null
                || !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) {
            return;
        }
        boolean usable = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED);
        underlyingNetworks.put(network.getNetworkHandle(), new UnderlyingNetworkState(
                network, buildNetworkFingerprint(network, capabilities), usable,
                networkPreference(capabilities)));
        selectBestUnderlyingNetwork(reason);
    }

    private void selectBestUnderlyingNetwork(String reason) {
        UnderlyingNetworkState best = null;
        for (UnderlyingNetworkState candidate : underlyingNetworks.values()) {
            if (best == null
                    || (candidate.usable && !best.usable)
                    || (candidate.usable == best.usable
                    && candidate.preference > best.preference)) {
                best = candidate;
            }
        }
        if (best == null) {
            observedNetworkHandle = -1L;
            handleNetworkObservation("none", false, reason);
            return;
        }
        observedNetworkHandle = best.network.getNetworkHandle();
        handleNetworkObservation(best.fingerprint, best.usable, reason);
    }

    private static int networkPreference(NetworkCapabilities capabilities) {
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return 300;
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return 200;
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) return 100;
        return 0;
    }

    private Network currentUnderlyingNetwork() {
        UnderlyingNetworkState state = underlyingNetworks.get(observedNetworkHandle);
        return state != null && state.usable ? state.network : null;
    }

    private Boolean underlyingIpv6Available() {
        try {
            Network network = currentUnderlyingNetwork();
            if (network == null || connectivityManager == null) return null;
            android.net.LinkProperties links = connectivityManager.getLinkProperties(network);
            if (links == null) return null;
            for (android.net.LinkAddress address : links.getLinkAddresses()) {
                if (address.getAddress() instanceof java.net.Inet6Address
                        && TraversalPolicy.usableIpv6(address.getAddress().getAddress())) return true;
            }
            return false;
        } catch (RuntimeException error) {
            return null;
        }
    }

    /** protect 负责绕过 VPN，bindSocket 负责确保 gonc 外层 UDP 真正走状态机选中的物理网络。 */
    private boolean protectAndBindSocket(int fd) {
        if (!protect(fd)) {
            return false;
        }
        Network network = currentUnderlyingNetwork();
        if (network == null) {
            Log.w(TAG, "socket protected without underlying network binding: fd=" + fd);
            return true;
        }
        ParcelFileDescriptor descriptor = null;
        try {
            descriptor = ParcelFileDescriptor.adoptFd(fd);
            network.bindSocket(descriptor.getFileDescriptor());
            Log.i(TAG, "socket bound to underlying network: fd=" + fd
                    + " network=" + network.getNetworkHandle());
            return true;
        } catch (Exception e) {
            Log.w(TAG, "bind socket to underlying network failed: fd=" + fd
                    + " error=" + e.getMessage());
            return false;
        } finally {
            if (descriptor != null) {
                // 仅借用 native fd，所有权必须还给 gonc，不能由 ParcelFileDescriptor 关闭。
                descriptor.detachFd();
            }
        }
    }

    private static String buildNetworkFingerprint(Network network, NetworkCapabilities capabilities) {
        String transport;
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            transport = "wifi";
        } else if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
            transport = "cellular";
        } else if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
            transport = "ethernet";
        } else {
            transport = "other";
        }
        // Network handle 在 Wi-Fi/蜂窝切换、断开重连时都会换代；不把 LinkProperties
        // 纳入指纹，避免 onAvailable 后地址逐步补齐被误判为第二次网络切换。
        return network.getNetworkHandle() + "|" + transport;
    }

    private void handleNetworkObservation(String fingerprint, boolean usable, String reason) {
        boolean active = TunnelState.isActive(activeState) || automaticRecoveryInProgress;
        NetworkRecoveryPolicy.Observation observation = networkRecovery.observe(
                fingerprint, usable, active, SystemClock.elapsedRealtime());
        Log.i(TAG, "underlying network " + reason + ": fingerprint=" + fingerprint
                + " usable=" + usable + " observation=" + observation);
        if (observation == NetworkRecoveryPolicy.Observation.USABILITY_CHANGED) {
            if (usable) {
                cancelCapabilityLossGrace();
            } else if (active && !manualStopRequested) {
                scheduleCapabilityLossGrace(reason);
            }
            return;
        }
        if (observation != NetworkRecoveryPolicy.Observation.RECOVERY_REQUIRED
                || manualStopRequested) {
            return;
        }
        cancelCapabilityLossGrace();
        beginNetworkRecovery(reason);
    }

    private void scheduleCapabilityLossGrace(String reason) {
        cancelCapabilityLossGrace();
        pendingCapabilityLossRunnable = () -> {
            boolean active = TunnelState.isActive(activeState) || automaticRecoveryInProgress;
            NetworkRecoveryPolicy.Observation confirmed = networkRecovery.confirmUnusable(
                    active, SystemClock.elapsedRealtime());
            if (confirmed == NetworkRecoveryPolicy.Observation.RECOVERY_REQUIRED
                    && !manualStopRequested) {
                beginNetworkRecovery(reason + "_grace_expired");
            }
        };
        networkHandler.postDelayed(
                pendingCapabilityLossRunnable, NETWORK_CAPABILITY_LOSS_GRACE_MS);
        Log.i(TAG, "underlying network capability loss grace started: reason=" + reason);
    }

    private void cancelCapabilityLossGrace() {
        if (pendingCapabilityLossRunnable != null) {
            networkHandler.removeCallbacks(pendingCapabilityLossRunnable);
            pendingCapabilityLossRunnable = null;
        }
    }

    /** 网络变化后旧 NAT 映射不可再信任：立即降级并串行清理，稳定后重新完整打洞。 */
    private void beginNetworkRecovery(String reason) {
        long generation = networkRecovery.generation();
        automaticRecoveryInProgress = true;
        interruptTunnelWorker();
        stopTrafficPolling();
        stopHealthClient();
        failPendingSpeedTest("网络已变化，测速已取消");
        connectedAtMs = 0;
        emit(TunnelState.DEGRADED, "网络已变化，正在等待网络稳定", "", "", "");

        executor.execute(() -> {
            cleanupNative();
            resetTunnelCounters();
            if (networkRecovery.isCurrent(generation) && !manualStopRequested) {
                // 最多持有两分钟，覆盖稳定窗口和常规重试；长期无网络时自动释放。
                acquireTunnelWakeLock(120_000L);
            }
        });
        scheduleStableNetworkRecovery(generation);
        Log.i(TAG, "network recovery scheduled: generation=" + generation + " reason=" + reason);
    }

    private void scheduleStableNetworkRecovery(long generation) {
        if (pendingRecoveryRunnable != null) {
            networkHandler.removeCallbacks(pendingRecoveryRunnable);
        }
        pendingRecoveryRunnable = () -> {
            if (manualStopRequested || !networkRecovery.isCurrent(generation)) {
                return;
            }
            long now = SystemClock.elapsedRealtime();
            if (!networkRecovery.canStart(generation, now)) {
                if (!networkRecovery.usable()) {
                    emit(TunnelState.DEGRADED, "等待可用网络", "", "", "");
                }
                return;
            }
            enqueueAutomaticRecovery(generation);
        };
        networkHandler.postDelayed(pendingRecoveryRunnable, NETWORK_STABLE_WINDOW_MS);
    }

    private void enqueueAutomaticRecovery(long generation) {
        if (recoveryTargetDeviceId <= 0 || manualStopRequested
                || !networkRecovery.isCurrent(generation)) {
            return;
        }
        int attempt = networkRecovery.nextAttempt(generation);
        if (attempt <= 0) {
            return;
        }
        emit(TunnelState.DEGRADED,
                "网络已稳定，正在重新建立隧道（" + attempt + "/"
                        + NETWORK_RECOVERY_MAX_ATTEMPTS + "）", "", "", "");
        startTunnel(recoveryTargetDeviceId, recoveryTargetDeviceUuid,
                recoveryTargetDeviceName, true, generation);
    }

    private void handleAutomaticRecoveryFailure(long generation, Exception error) {
        if (!networkRecovery.isCurrent(generation) || manualStopRequested) {
            return;
        }
        int completedAttempt = networkRecovery.currentAttempt(generation);
        if (completedAttempt >= NETWORK_RECOVERY_MAX_ATTEMPTS) {
            automaticRecoveryInProgress = false;
            networkRecovery.cancel();
            emit(TunnelState.FAILED, "网络恢复失败：" + safeErrorMessage(error), "", "", "");
            stopForeground(false);
            stopSelf();
            return;
        }
        long delay = NETWORK_RECOVERY_BACKOFF_MS[Math.min(
                Math.max(0, completedAttempt - 1), NETWORK_RECOVERY_BACKOFF_MS.length - 1)];
        acquireTunnelWakeLock(120_000L);
        emit(TunnelState.DEGRADED,
                "隧道恢复失败，" + (delay / 1000) + " 秒后重试", "", "", "");
        if (pendingRecoveryRunnable != null) {
            networkHandler.removeCallbacks(pendingRecoveryRunnable);
        }
        pendingRecoveryRunnable = () -> {
            if (networkRecovery.isCurrent(generation) && networkRecovery.usable()
                    && !manualStopRequested) {
                enqueueAutomaticRecovery(generation);
            }
        };
        networkHandler.postDelayed(pendingRecoveryRunnable, delay);
    }

    private void cancelNetworkRecovery() {
        manualStopRequested = true;
        automaticRecoveryInProgress = false;
        networkRecovery.cancel();
        interruptTunnelWorker();
        cancelCapabilityLossGrace();
        if (pendingRecoveryRunnable != null) {
            networkHandler.removeCallbacks(pendingRecoveryRunnable);
            pendingRecoveryRunnable = null;
        }
    }

    private static String safeErrorMessage(Exception error) {
        String message = error.getMessage();
        return message == null || message.isEmpty() ? "未知错误" : message;
    }

    private void interruptTunnelWorker() {
        Thread worker = tunnelWorkerThread;
        if (worker != null && worker != Thread.currentThread()) {
            worker.interrupt();
        }
    }

    private void resetTunnelCounters() {
        connectedAtMs = 0;
        cumulativeRx = 0;
        cumulativeTx = 0;
        lastSampleRx = 0;
        lastSampleTx = 0;
        tunnelLatencyMs = -1;
    }

    /**
     * 启动完整 wgvpn active 流程。
     */
    private void startTunnel(long targetDeviceId, String targetDeviceUuid, String targetName) {
        startTunnel(targetDeviceId, targetDeviceUuid, targetName,
                false, networkRecovery.generation());
    }

    private void startTunnel(long targetDeviceId, String targetDeviceUuid, String targetName,
                             boolean automaticRecovery, long expectedGeneration) {
        executor.execute(() -> {
            tunnelWorkerThread = Thread.currentThread();
            // 提到 try 外，供 catch 块失败上报使用（opened 在 p2p/open 成功后赋值）。
            ApiClient.OpenResult opened = null;
            WsConnection attemptWs = null;
            String activeAttemptId = null;
            boolean attemptStarted = false;
            TraversalClient traversal = null;
            // p2p/end 任务耗时：单调时钟，从任务开始到最终结果（成功/失败/取消）。
            final long startedAtMs = android.os.SystemClock.elapsedRealtime();
            ConnectionPreferences connectionPreferences = ConnectionPreferences.load(this);
            DeviceItem targetItem = new DeviceItem(targetDeviceId, targetName, "", "",
                    targetDeviceUuid, "online", "", "", 3389);
            try {
                tcpRetryRecommended = false;
                selectedNetwork = "";
                DiagLog.i(TAG, "==== connect session start target=" + targetName + "(" + targetDeviceId + ")"
                        + (automaticRecovery ? " mode=auto-recovery" : ""));
                Session session = sessionStore.require();
                long accountGeneration = sessionStore.accountGeneration();

                // 单隧道约束：已有活动隧道则拒绝（isActive 不含 STOPPED）。
                if (!automaticRecovery && TunnelState.isActive(activeState)) {
                    emitStatusOnly();
                    return;
                }
                ensureStartGeneration(expectedGeneration);

                // 生成/加载 WG 密钥对
                emit(TunnelState.PREPARING, "正在准备密钥和打洞请求", "", "", "");
                String privateKeyHex = session.wgPrivateKeyHex;
                String publicKeyHex = session.wgPublicKeyHex;
                if (privateKeyHex.isEmpty() || publicKeyHex.isEmpty()) {
                    Keypair kp = Libwgmobile.generateKeypair();
                    privateKeyHex = kp.getPrivateKeyHex();
                    publicKeyHex = kp.getPublicKeyHex();
                    session = sessionStore.updateWgKeypairIfCurrent(
                            accountGeneration, privateKeyHex, publicKeyHex);
                }

                // 向服务端发起 p2p/open 鉴权（新协议，返回 connection_id/access_grant）
                emit(TunnelState.PREPARING, "正在向服务端申请隧道", "", "", "");
                String clientJobId = ApiClient.buildClientJobId(targetDeviceId);
                opened = openP2PWithRetry(clientJobId, targetItem);
                ensureStartGeneration(expectedGeneration);
                if (opened.connectionId.isEmpty() || opened.accessGrant.isEmpty()) {
                    throw new IllegalStateException("p2p/open 未返回有效的 connection_id 或 access_grant");
                }
                Log.i(TAG, "p2p/open ok: connection_id=" + opened.connectionId + " log_id=" + opened.logId);
                DiagLog.i(TAG, "p2p/open ok connection_id=" + opened.connectionId + " log_id=" + opened.logId);
                // 用服务端返回的 target_remote_access 修正 targetItem（协议/端口跟随被控端，
                // macOS 为 vnc/5900，Windows/Linux 为 rdp/3389），后续上报与状态使用。
                targetItem = targetItem.withRemoteAccess(
                        opened.targetRemoteProtocol, true, opened.targetRdpPort);

                // 客户端生成 punch_token + attempt_id（新协议下 token 由客户端生成）
                String punchToken = ApiClient.buildPunchToken(session.deviceId, targetDeviceId);
                String attemptId = ApiClient.buildAttemptId(targetDeviceId, 1);
                activeAttemptId = attemptId;
                Log.i(TAG, "generated attempt_id=" + attemptId);

                // 通过 WS 发 p2p_notify(attempt_start) 给被动端，等服务端 ack
                emit(TunnelState.EXCHANGING, "正在通知对端准备隧道", "", "", "");
                WsConnection ws = WsConnection.get(this);
                attemptWs = ws;
                traversal = new TraversalClient(ws, opened, targetDeviceId, attemptId,
                        connectionPreferences, this::underlyingIpv6Available, () -> ensureStartGeneration(expectedGeneration));
                if (!ws.isConnected()) {
                    throw new IllegalStateException("WebSocket 未连接，无法发送打洞信令");
                }
                // 注册被动端回应监听（attempt_ready、审批事件、attempt_failed）。
                // approval_required 只表示“需要审批”，最终等待必须由
                // approval_granted/denied/timeout 释放。
                final java.util.concurrent.CountDownLatch readyLatch = new java.util.concurrent.CountDownLatch(1);
                final java.util.concurrent.CountDownLatch approvalDecisionLatch = new java.util.concurrent.CountDownLatch(1);
                final String[] readyResult = {"timeout", ""};
                final int[] readyRdpPort = {0};
                final AtomicBoolean approvalRequired = new AtomicBoolean(false);
                final AtomicReference<String> approvalResult = new AtomicReference<>("pending");
                ws.setPeerNotifyListener((type, aid, rdpPort, approvalRequiredFromReady, expiresAt, error) -> {
                    if (!attemptId.equals(aid)) {
                        return;  // 忽略非本次 attempt 的回应
                    }
                    if ("attempt_ready".equals(type)) {
                        readyResult[0] = type;
                        readyRdpPort[0] = rdpPort;
                        // attempt_ready 是审批要求的权威来源；OR 保留极端情况下
                        // 先收到 approval_required 再收到 ready 的 fail-safe 状态。
                        approvalRequired.compareAndSet(false, approvalRequiredFromReady);
                        readyLatch.countDown();
                    } else if ("approval_required".equals(type)) {
                        approvalRequired.set(true);
                        Log.i(TAG, "approval_required received: expiresAt=" + expiresAt);
                        // 这里只记录要求，不释放最终审批等待。
                    } else if ("approval_granted".equals(type)
                            || "approval_denied".equals(type)
                            || "approval_timeout".equals(type)) {
                        approvalResult.set(type);
                        approvalDecisionLatch.countDown();
                    } else {
                        readyResult[0] = type;
                        readyResult[1] = error != null ? error : "unknown";
                        readyLatch.countDown();
                    }
                });

                // 构造 attempt_start 消息体（嵌套 JSON 字符串，对齐桌面 P2PAttemptMessage::AttemptStart）
                // 必须带上来源用户和设备展示信息；被动桌面端按 source_device_name
                // 显示连接方设备名，缺失时只能退化为「设备 #<id>」。
                // 对齐桌面 active_jobs.rs 的 P2PAttemptMessage::AttemptStart 构造。
                JSONObject attemptData = new JSONObject();
                attemptData.put("type", "attempt_start");
                attemptData.put("protocol_version", 1);
                attemptData.put("client_job_id", clientJobId);
                attemptData.put("attempt_id", attemptId);
                attemptData.put("attempt", 1);
                attemptData.put("max_attempts", 3);
                attemptData.put("punch_token", punchToken);
                attemptData.put("source_user_id", opened.sourceUserId);
                attemptData.put("source_username", opened.sourceUsername);
                attemptData.put("source_email", opened.sourceEmail);
                attemptData.put("source_device_name", DeviceIdentity.deviceName());
                attemptData.put("source_device_alias", "");
                attemptData.put("traversal_negotiation", traversal.negotiation());

                String notifyMessageId = ApiClient.buildMessageId();
                boolean acked = ws.sendNotifyWaitAck(
                        opened.connectionId, targetDeviceId, notifyMessageId,
                        opened.accessGrant, attemptData.toString());
                ensureStartGeneration(expectedGeneration);
                if (!acked) {
                    ws.setPeerNotifyListener(null);
                    throw new IllegalStateException("打洞信令发送失败或被服务端拒绝");
                }
                attemptStarted = true;
                Log.i(TAG, "p2p_notify acked, waiting attempt_ready");

                // 等待被动端 attempt_ready（≤30 秒）
                emit(TunnelState.EXCHANGING, "正在等待对端就绪", "", "", "");
                if (!readyLatch.await(PEER_READY_TIMEOUT_SEC, TimeUnit.SECONDS)) {
                    ws.setPeerNotifyListener(null);
                    // 超时后通知被动端取消本次 attempt，避免被动端空等（对齐桌面 AttemptCancel）。
                    sendAttemptCancel(ws, opened, targetDeviceId, attemptId, "peer_prepare_timeout");
                    throw new IllegalStateException("等待对端准备超时（" + PEER_READY_TIMEOUT_SEC + " 秒）");
                }
                ensureStartGeneration(expectedGeneration);
                if (!"attempt_ready".equals(readyResult[0])) {
                    throw new IllegalStateException("对端准备失败：" + readyResult[1]);
                }
                traversal.negotiated(); // Explicit malformed versions must fail before key exchange.
                Log.i(TAG, "attempt_ready received: rdp_port=" + readyRdpPort[0] + ", starting gonc exchange");
                DiagLog.i(TAG, "attempt_ready rdp_port=" + readyRdpPort[0]);

                // gonc Exchange（密钥 + 虚拟 IP 交换，用上面生成的 punch_token）
                emit(TunnelState.EXCHANGING, "正在交换密钥和虚拟 IP", "", "", "");
                // 构造 ExchangePayload JSON，必须包含桌面端 serde 反序列化要求的所有必需字段。
                // 缺少这些字段会导致桌面端 ExchangePayload::parse 失败（attempt_failed）。
                // 注意：pubkey 必须是 base64 编码（对齐桌面端），Android 的 WG 密钥是 hex，
                // 需要转换。hex(64字符) -> 32字节 -> base64(44字符)。
                //
                // my_ip：主动端必须预计算并发送「期望的被动端虚拟 IP」作为 requested_ip，
                // 对齐桌面端 choose_passive_ip_for_peer → passive_ip_for_device。
                // 被动端 passive_ip_for_request 收到有效 requested_ip 会直接采用（不再哈希）。
                // 若发 0，被动端会用自己的 device_id 哈希分配，导致与 Wintun 实际绑定 IP 不一致。
                long ipStart = ipToU32(WGVPN_CIDR_PREFIX + IP_RANGE_START_OFFSET);
                long ipEnd = ipToU32(WGVPN_CIDR_PREFIX + IP_RANGE_END_OFFSET);
                long expectedPassiveIp = computePassiveIpForDevice(targetDeviceId, ipStart, ipEnd);
                JSONObject sendPayload = new JSONObject();
                sendPayload.put("pubkey", hexToBase64(publicKeyHex));
                sendPayload.put("device_id", session.deviceId);
                sendPayload.put("ip_range_start", ipStart);
                sendPayload.put("ip_range_end", ipEnd);
                sendPayload.put("assigned_ip", 0);
                sendPayload.put("my_ip", expectedPassiveIp);
                sendPayload.put("exposed_lan_cidrs", new JSONArray());

                ExchangeResult exResult = PunchNative.exchange(
                        punchToken, sendPayload.toString(), "active", 0, EXCHANGE_TIMEOUT_SEC);
                DiagLog.i(TAG, "exchange timeout=" + EXCHANGE_TIMEOUT_SEC
                        + " -> ok=" + exResult.getOK() + " error=" + exResult.getError()
                        + " recv=" + exResult.getRecvData());
                ensureStartGeneration(expectedGeneration);
                if (!exResult.getOK()) {
                    throw new IllegalStateException("密钥交换失败：" + exResult.getError());
                }

                JSONObject recvPayload = new JSONObject(exResult.getRecvData());
                long assignedIpU32 = recvPayload.optLong("assigned_ip", 0);
                long myIpU32 = recvPayload.optLong("my_ip", 0);
                // 被动端回传的 pubkey 是 base64（对齐桌面端），Android 的 WG 需要 hex 格式
                peerPubkeyHex = base64ToHex(recvPayload.optString("pubkey", ""));
                String assignedIp = u32ToIp(assignedIpU32);
                String peerIp = u32ToIp(myIpU32);
                if (assignedIp.equals("0.0.0.0") || peerIp.equals("0.0.0.0") || peerPubkeyHex.isEmpty()) {
                    throw new IllegalStateException("密钥交换返回的数据不完整");
                }
                myVirtualIp = assignedIp;
                peerVirtualIp = peerIp;
                exposedLan = "";
                JSONArray lanArr = recvPayload.optJSONArray("exposed_lan_cidrs");
                if (lanArr != null && lanArr.length() > 0) {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < lanArr.length(); i++) {
                        if (i > 0) sb.append(",");
                        sb.append(lanArr.optString(i));
                    }
                    exposedLan = sb.toString();
                }
                Log.i(TAG, "exchange recv: myVirtualIp=" + assignedIp + " peerVirtualIp=" + peerIp
                        + " exposedLan=" + exposedLan);
                DiagLog.i(TAG, "exchange recv myIp=" + assignedIp + " peerIp=" + peerIp + " exposedLan=" + exposedLan);

                // gonc StartUdpTunnel
                emit(TunnelState.PUNCHING, "正在进行 P2P 打洞", "", "", "");
                JSONObject tunnelReq = new JSONObject();
                tunnelReq.put("token", punchToken);
                tunnelReq.put("role_hint", "active");
                tunnelReq.put("traversal_mode", "auto");
                tunnelReq.put("network", "udp4");
                tunnelReq.put("timeout_secs", UDP_TUNNEL_TIMEOUT_SEC);
                tunnelReq.put("local_listen_ip", "127.0.0.1");
                tunnelReq.put("local_listen_port", 0);
                tunnelReq.put("remote_target_ip", "127.0.0.1");
                tunnelReq.put("remote_target_port", WG_LISTEN_PORT);
                tunnelReq.put("allow_relay", false);

                TunnelResult tunnelResult = traversal.start(tunnelReq);
                DiagLog.i(TAG, "tunnel ok=" + tunnelResult.getOK()
                        + " traversal=" + tunnelResult.getSelectedTraversal()
                        + " transport=" + tunnelResult.getTransportMode()
                        + " peerEndpoint=" + tunnelResult.getPeerEndpoint()
                        + " nat=" + tunnelResult.getLocalNATType() + "/" + tunnelResult.getRemoteNATType()
                        + " error=" + tunnelResult.getError());
                if (!tunnelResult.getOK()) {
                    throw new IllegalStateException("P2P 打洞失败：" + tunnelResult.getError());
                }
                goncHandleId = tunnelResult.getHandleID();
                selectedNetwork = tunnelResult.getNetwork();
                ensureStartGeneration(expectedGeneration);
                int localForwardPort = (int) tunnelResult.getLocalForwardPort();
                if (localForwardPort <= 0) {
                    throw new IllegalStateException("gonc 未返回有效本地转发端口");
                }
                Log.i(TAG, "gonc traversal selected=" + tunnelResult.getSelectedTraversal()
                        + " transport=" + tunnelResult.getTransportMode()
                        + " peerEndpoint=" + tunnelResult.getPeerEndpoint());
                DiagLog.i(TAG, "traversal selected=" + tunnelResult.getSelectedTraversal());

                // VpnService establish TUN fd
                emit(TunnelState.CONNECTING, "正在建立虚拟网卡", myVirtualIp, peerVirtualIp, exposedLan);
                Builder builder = new Builder();
                builder.setSession("p2pRemote");
                builder.setMtu(WGVPN_MTU);
                Network underlyingNetwork = currentUnderlyingNetwork();
                if (underlyingNetwork != null) {
                    builder.setUnderlyingNetworks(new Network[]{underlyingNetwork});
                }
                // 注意：此处用 /32 声明本机虚拟 IP，与桌面 wg0.conf 的 /24 不同。
                // VpnService 的路由完全由 addRoute 决定，/32 更精确，不会把整个
                // 100.99.71.0/24 都拉进 TUN（避免影响本机其他同网段连接）。
                builder.addAddress(myVirtualIp, 32);
                // 路由对端虚拟 IP
                builder.addRoute(peerVirtualIp, 32);
                // 路由对端暴露的 LAN 网段
                int routeAddedCount = 0;
                if (!exposedLan.isEmpty()) {
                    for (String cidr : exposedLan.split(",")) {
                        String[] parts = cidr.trim().split("/");
                        if (parts.length == 2) {
                            String routeIp = parts[0].trim();
                            int routePrefix;
                            try {
                                routePrefix = Integer.parseInt(parts[1].trim());
                            } catch (NumberFormatException nfe) {
                                Log.w(TAG, "addRoute 跳过: 非法掩码 cidr=" + cidr);
                                continue;
                            }
                            builder.addRoute(routeIp, routePrefix);
                            routeAddedCount++;
                            Log.i(TAG, "addRoute OK: " + routeIp + "/" + routePrefix);
                        } else {
                            Log.w(TAG, "addRoute 跳过: 缺少掩码 cidr=" + cidr);
                        }
                    }
                }
                Log.i(TAG, "addRoute 汇总: 对端虚拟IP/32 + LAN网段共 " + routeAddedCount + " 条 LAN 路由");
                // 已知限制：未配置 addDnsServer()。RDP/VNC 按虚拟 IP 直连不受影响；
                // 若后续要解析对端 LAN 内域名，需要额外注入 DNS。
                tunFd = builder.establish();
                if (tunFd == null) {
                    throw new IllegalStateException("VpnService 建立虚拟网卡失败");
                }

                // userspace WG Start + AddPeer
                WgResult wgStart = Libwgmobile.wgStart(tunFd.getFd(), privateKeyHex, WG_LISTEN_PORT, WGVPN_MTU);
                if (!wgStart.getOK()) {
                    throw new IllegalStateException("WireGuard 启动失败：" + wgStart.getError());
                }
                // wgStart 成功后 fd 所有权移交 native：detachFd 让 ParcelFileDescriptor 放弃
                // 对该 fd 的关闭职责，由 WgStop -> androidTun.Close 统一关闭，杜绝 double-close。
                // 失败路径仍由 cleanupNative 关闭 tunFd。
                tunFd.detachFd();
                wgRunning = true;
                ensureStartGeneration(expectedGeneration);

                String endpoint = "127.0.0.1:" + localForwardPort;
                String allowedIps = peerVirtualIp + "/32";
                if (!exposedLan.isEmpty()) {
                    allowedIps = allowedIps + "," + exposedLan;
                }
                Log.i(TAG, "wgAddPeer: endpoint=" + endpoint + " allowedIps=" + allowedIps
                        + " peerPubkey=" + peerPubkeyHex.substring(0, Math.min(16, peerPubkeyHex.length())) + "...");
                DiagLog.i(TAG, "wgAddPeer endpoint=" + endpoint + " allowedIps=" + allowedIps);
                WgResult wgPeer = Libwgmobile.wgAddPeer(peerPubkeyHex, endpoint, allowedIps, PERSISTENT_KEEPALIVE);
                ensureStartGeneration(expectedGeneration);
                if (!wgPeer.getOK()) {
                    throw new IllegalStateException("WireGuard 添加 peer 失败：" + wgPeer.getError());
                }

                // 等 WG 握手（最多 15 秒）
                emit(TunnelState.CONNECTING, "等待安全通道握手", myVirtualIp, peerVirtualIp, exposedLan);
                long deadline = System.currentTimeMillis() + WG_HANDSHAKE_TIMEOUT_SEC * 1000L;
                boolean handshakeOk = false;
                while (System.currentTimeMillis() < deadline) {
                    long hs = Libwgmobile.wgPeerLastHandshake(peerPubkeyHex);
                    if (hs > 0) {
                        handshakeOk = true;
                        break;
                    }
                    Thread.sleep(500);
                }
                if (!handshakeOk) {
                    throw new IllegalStateException("WireGuard 握手超时（" + WG_HANDSHAKE_TIMEOUT_SEC + " 秒）");
                }
                ensureStartGeneration(expectedGeneration);

                // 被动端跨账号时，WG 已完成握手但 AllowedIPs 仍为空；等待明确允许后才
                // 把主动端状态报告为 CONNECTED。审批等待不触碰数据包热路径。
                if (approvalRequired.get()) {
                    emit(TunnelState.WAITING_APPROVAL, "连接请求已发送，请等待对方同意", myVirtualIp, peerVirtualIp, exposedLan);
                    if (!approvalDecisionLatch.await(65, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("被动端审批超时");
                    }
                    if (!"approval_granted".equals(approvalResult.get())) {
                        throw new IllegalStateException("被动端未允许业务数据：" + approvalResult.get());
                    }
                }
                ws.setPeerNotifyListener(null);

                // 上报成功
                try {
                    apiClient.reportP2PEnd(
                            opened,
                            targetItem,
                            true,
                            tunnelResult.getLocalNATType(),
                            tunnelResult.getRemoteNATType(),
                            traversal.traversalPlan(),
                            traversal.traversalSelection(),
                            tunnelResult.getNetwork(),
                            (android.os.SystemClock.elapsedRealtime() - startedAtMs) / 1000,
                            "",
                            ""
                    );
                } catch (Exception reportError) {
                    Log.w(TAG, "p2p/end 上报失败（不影响隧道）: " + reportError.getMessage());
                }

                // 推送成功状态
                connectedAtMs = System.currentTimeMillis();
                Log.i(TAG, "tunnel CONNECTED: myIp=" + myVirtualIp + " peerIp=" + peerVirtualIp
                        + " health target=" + peerVirtualIp + ":" + HEALTH_PORT);
                DiagLog.i(TAG, "CONNECTED myIp=" + myVirtualIp + " peerIp=" + peerVirtualIp);
                networkRecovery.markConnected(expectedGeneration);
                automaticRecoveryInProgress = false;
                emit(TunnelState.CONNECTED, "隧道已建立 · " + selectedNetwork.toUpperCase(java.util.Locale.ROOT), myVirtualIp, peerVirtualIp, exposedLan);
                acquireTunnelWakeLock();
                startTrafficPolling();
                startHealthClient();

            } catch (Exception e) {
                String msg = e.getMessage();
                if (msg == null || msg.isEmpty()) msg = "建立隧道失败";
                Log.e(TAG, "tunnel failed", e);
                DiagLog.e(TAG, "tunnel failed", e);
                String errorCode = classifyErrorCode(msg);
                boolean superseded = e instanceof RecoverySupersededException
                        || manualStopRequested
                        || networkRecovery.generation() != expectedGeneration;
                if (superseded) {
                    if (attemptWs != null) {
                        attemptWs.setPeerNotifyListener(null);
                    }
                    cleanupNative();
                    scheduleSupersededAttemptCleanup(
                            opened, targetItem, attemptWs, activeAttemptId, attemptStarted,
                            traversal != null ? traversal.localNatType() : "",
                            traversal != null ? traversal.remoteNatType() : "",
                            traversal != null ? traversal.traversalPlan() : "",
                            traversal != null ? traversal.traversalSelection() : "",
                            traversal != null ? traversal.selectedNetwork() : "",
                            (android.os.SystemClock.elapsedRealtime() - startedAtMs) / 1000);
                    Log.i(TAG, "discarded superseded tunnel start: generation=" + expectedGeneration);
                    return;
                }
                // 失败上报（p2p/open 已成功拿到 connection_id 才有意义上报 end）
                if (opened != null) {
                    try {
                        apiClient.reportP2PEnd(opened, targetItem, false,
                                traversal != null ? traversal.localNatType() : "",
                                traversal != null ? traversal.remoteNatType() : "",
                                traversal != null ? traversal.traversalPlan() : "",
                                traversal != null ? traversal.traversalSelection() : "",
                                traversal != null ? traversal.selectedNetwork() : "",
                                (android.os.SystemClock.elapsedRealtime() - startedAtMs) / 1000,
                                errorCode, msg);
                    } catch (Exception reportError) {
                        Log.w(TAG, "p2p/end 失败上报异常: " + reportError.getMessage());
                    }
                }
                cleanupNative();
                if (automaticRecovery) {
                    handleAutomaticRecoveryFailure(expectedGeneration, e);
                    return;
                }
                tcpRetryRecommended = TraversalPolicy.tcpRetryRecommended(connectionPreferences.tcp, msg);
                if (tcpRetryRecommended) msg += "\n已开启 TCP 优先，建议关闭后重试。";
                emit(TunnelState.FAILED, msg, "", "", "");
                // stopForeground(false)：退下前台服务，但保留 FAILED 通知，让用户看到真实失败原因。
                // （此前传 true 会移除通知，随后 onDestroy 又 emit(STOPPED) 覆盖成「服务已停止」，
                //   导致用户永远只看到无信息量的「服务已停止」。）
                // onDestroy 检测到 FAILED 终态后只清理 native、不再 emit，FAILED 通知得以保留。
                stopForeground(false);
                stopSelf();
            } finally {
                if (traversal != null) traversal.close();
                if (!automaticRecovery) {
                    manualStartQueued.set(false);
                }
                if (attemptWs != null) {
                    attemptWs.setPeerNotifyListener(null);
                }
                tunnelWorkerThread = null;
                // 单线程 executor 会复用此线程；清掉取消留下的 interrupt 状态。
                Thread.interrupted();
            }
        });
    }

    private void ensureStartGeneration(long expectedGeneration)
            throws RecoverySupersededException {
        if (manualStopRequested || networkRecovery.generation() != expectedGeneration) {
            throw new RecoverySupersededException();
        }
    }

    /**
     * 迟到 generation 的远端清理不能阻塞单线程建链队列；独立执行 best-effort cancel/end。
     */
    private void scheduleSupersededAttemptCleanup(ApiClient.OpenResult opened, DeviceItem target,
                                                  WsConnection ws, String attemptId,
                                                  boolean attemptStarted,
                                                  String localNatType, String remoteNatType,
                                                  String traversalPlan, String traversalSelection,
                                                  String selectedNetwork, long durationSeconds) {
        if (opened == null) {
            return;
        }
        try {
            attemptCleanupExecutor.execute(() -> {
                if (attemptStarted && ws != null && attemptId != null && !attemptId.isEmpty()) {
                    sendAttemptCancel(ws, opened, target.id, attemptId, "network_changed");
                }
                try {
                    apiClient.reportP2PEnd(opened, target, false, localNatType, remoteNatType,
                            traversalPlan, traversalSelection, selectedNetwork, durationSeconds,
                            "network_changed", "网络变化，旧建链已取消");
                } catch (Exception e) {
                    Log.w(TAG, "superseded p2p/end cleanup failed: " + e.getMessage());
                }
            });
        } catch (RuntimeException e) {
            Log.w(TAG, "schedule superseded attempt cleanup failed: " + e.getMessage());
        }
    }

    /**
     * 带重试的 p2p/open。
     *
     * 背景：服务端「设备列表在线判断」（Redis TTL 120s）与「p2p/open 在线判断」
     * （内存 Hub 实时 WS + 心跳确认）使用两套数据源，常出现列表显示在线、但
     * p2p/open 判定「设备不在线」(code=1054) 的竞态。安卓后台 Doze/待机也会偶发
     * 掐断本机保活 WS，导致主动端自身被判离线。
     *
     * 缓解：对 code=1054 按 1.5s/3s/4.5s 退避重试，期间本机 PresenceService 通常
     * 已重连并完成首次心跳确认、或对端 WS 恢复，重试即可成功。最多 4 次（含首次），
     * 总耗时约 9s，避免长时间卡住用户。其它错误（鉴权、参数等）不重试，直接抛出。
     */
    private ApiClient.OpenResult openP2PWithRetry(String clientJobId, DeviceItem target) throws Exception {
        // 退避间隔（毫秒）：首次失败后等待 1.5s 再重试，之后 3s、4.5s。
        final long[] backoffMs = {1500L, 3000L, 4500L};
        Exception lastError = null;
        for (int attempt = 0; attempt <= backoffMs.length; attempt++) {
            try {
                return apiClient.openP2P(clientJobId, target);
            } catch (ApiClient.ApiException ae) {
                lastError = ae;
                if (ae.code != CODE_DEVICE_OFFLINE) {
                    // 非在线状态相关的业务错误，不重试
                    throw ae;
                }
                if (attempt >= backoffMs.length) {
                    // 已用完重试次数
                    break;
                }
                Log.w(TAG, "p2p/open 报「设备不在线」(code=1054)，"
                        + (attempt + 1) + "/" + backoffMs.length + " 次重试前先等待 "
                        + backoffMs[attempt] + "ms（期间确认本机 WS 在线）");
                // 始终退避：即便本机 WS 在线，对端 WS 也可能正处于短暂离线窗口，
                // 需要给它恢复时间。期间顺带等待本机保活 WS（重）连上（若它断了）。
                sleepConfirmingWs(backoffMs[attempt]);
            }
        }
        throw lastError;
    }

    /**
     * 固定睡眠 durationMs（用于重试退避），期间每 300ms 检查一次本机保活 WS：
     * 若发现 WS 已断开，记一条日志便于排障（不缩短睡眠——退避时间是为对端恢复预留的）。
     */
    private void sleepConfirmingWs(long durationMs) throws InterruptedException {
        WsConnection ws = WsConnection.get(this);
        boolean reportedDown = false;
        long deadline = System.currentTimeMillis() + durationMs;
        while (System.currentTimeMillis() < deadline) {
            if (!reportedDown && !ws.isConnected()) {
                Log.w(TAG, "退避期间本机 WS 未连接，等待 PresenceService 重连");
                reportedDown = true;
            }
            Thread.sleep(300);
        }
    }

    /**
     * 根据异常消息分类结构化 error_code，对齐桌面 classify_tunnel_error_code 白名单。
     * 用于 p2p/end 失败上报，便于管理后台按错误类型归因统计。
     *
     * 按建链阶段从后往前匹配，避免宽泛关键词吞掉后面的精确阶段。异常消息均为
     * WgvpnService 内 IllegalStateException 的中文文案（见 startTunnel 各阶段 throw）。
     */
    private static String classifyErrorCode(String msg) {
        if (msg == null) return "internal_error";
        if (msg.contains("审批超时")) return "approval_timeout";
        if (msg.contains("未允许业务数据")) return "approval_denied";
        // WireGuard 握手（最靠后，优先判断）
        if (msg.contains("握手超时") || msg.contains("握手")) return "wireguard_handshake_failed";
        if (msg.contains("添加 peer")) return "wireguard_config_failed";
        if (msg.contains("WireGuard 启动")) return "wireguard_config_failed";
        if (msg.contains("虚拟网卡") || msg.contains("VpnService")) return "wireguard_config_failed";
        // P2P 打洞 / 转发端口
        if (msg.contains("gonc 未返回")) return "hole_punch_wait_timeout";
        if (msg.contains("P2P 打洞")) return "hole_punch_wait_timeout";
        // 密钥交换（数据校验失败归 internal，仅交换失败归打洞）
        if (msg.contains("密钥交换失败")) return "hole_punch_wait_timeout";
        if (msg.contains("数据不完整")) return "internal_error";
        // 等待对端 attempt_ready
        if (msg.contains("等待对端准备超时") || msg.contains("对端准备失败")) return "peer_prepare_timeout";
        // WS 信令发送（服务端拒绝或 WS 断开）
        if (msg.contains("打洞信令发送失败") || msg.contains("WebSocket 未连接")) return "peer_offline";
        // p2p/open
        if (msg.contains("设备不在线")) return "peer_offline";
        if (msg.contains("p2p/open") || msg.contains("connection_id")) return "internal_error";
        return "internal_error";
    }

    /**
     * 启动流量统计轮询。CONNECTED 后每 {@link #TRAFFIC_POLL_INTERVAL_MS} 采样一次
     * WG peer 的 rx/tx 字节，累加为累计值并广播给 UI。
     *
     * WireGuard rekey 会把 rx/tx 计数器清零：当采样值小于上次采样值时，说明发生了
     * rekey，此时把当前采样值作为新基准（不计入 delta），避免累计值倒退。
     */
    private void startTrafficPolling() {
        trafficPolling = true;
        lastSampleRx = 0;
        lastSampleTx = 0;
        tunnelLatencyMs = -1;
        trafficHandler.postDelayed(trafficPollRunnable, TRAFFIC_POLL_INTERVAL_MS);
    }

    private void stopTrafficPolling() {
        trafficPolling = false;
        trafficHandler.removeCallbacks(trafficPollRunnable);
    }

    private final Runnable trafficPollRunnable = new Runnable() {
        @Override
        public void run() {
            if (!trafficPolling || !wgRunning || peerPubkeyHex.isEmpty()) {
                return;
            }
            try {
                executor.execute(WgvpnService.this::sampleTrafficAndReschedule);
            } catch (RejectedExecutionException e) {
                Log.w(TAG, "traffic poll stopped because native executor is closed");
            }
        }
    };

    /** 查询统计也进入 native 串行队列，避免与 wgStop/wgRemovePeer 并发。 */
    private void sampleTrafficAndReschedule() {
        if (!trafficPolling || !wgRunning || peerPubkeyHex.isEmpty()) {
            return;
        }
        try {
            WgTransferStats stats = Libwgmobile.wgPeerTransferBytes(peerPubkeyHex);
            if (stats != null && stats.getOK()) {
                long rx = stats.getRxBytes();
                long tx = stats.getTxBytes();
                // rekey 检测：计数器回绕（当前 < 上次）说明 WG 刚 rekey 清零，
                // 把当前值作为新基准；否则累加 delta。
                if (rx >= lastSampleRx) {
                    cumulativeRx += rx - lastSampleRx;
                }
                if (tx >= lastSampleTx) {
                    cumulativeTx += tx - lastSampleTx;
                }
                lastSampleRx = rx;
                lastSampleTx = tx;
                // 广播最新累计流量给 UI
                broadcastStatus(activeState, activeMessage);
            }
        } catch (Exception e) {
            Log.w(TAG, "traffic poll error: " + e.getMessage());
        }
        if (trafficPolling) {
            trafficHandler.postDelayed(trafficPollRunnable, TRAFFIC_POLL_INTERVAL_MS);
        }
    }

    /**
     * 启动隧道健康检查 TCP client（主动端角色）。
     *
     * 架构对齐桌面 p2p.rs active_health_session：
     * - 主动端（本机）作为 client 连接被动端 server「peer_virtual_ip:HEALTH_PORT」
     * - 连接成功后被动端 watchdog 取消首次连接超时判定（runtime.rs "connected" 事件）
     * - 每 5s 发 Ping / 收 Pong 维持长连接，让被动端持续判定隧道健康
     *
     * 为什么是 client 而非 server：Android VpnService TUN 入站 TCP 到不了本地 socket
     * （wireguard-go 解密后写 TUN fd，内核不路由到本地 ServerSocket）。出站方向（client
     * connect 到对端虚拟 IP）走 addRoute(peerVirtualIp) → wireguard-go 加密发出，正常工作。
     *
     * 心跳失败只重连，不主动断开隧道：Android 主动端的隧道活性由 WG WgPeerLastHandshake
     * 判断；本 client 的唯一目的是让被动端 watchdog 不误判超时。
     */
    private void startHealthClient() {
        if (healthClientRunning) {
            return;
        }
        final long sourceDeviceId;
        try {
            sourceDeviceId = sessionStore.require().deviceId;
        } catch (Exception e) {
            Log.w(TAG, "health client skipped: no device id (" + e.getMessage() + ")");
            return;
        }
        if (peerVirtualIp == null || peerVirtualIp.isEmpty()) {
            Log.w(TAG, "health client skipped: no peer virtual ip");
            return;
        }
        lastHealthSuccessElapsedMs = SystemClock.elapsedRealtime();
        healthClientRunning = true;
        healthClientThread = new Thread(() -> healthClientLoop(sourceDeviceId), "health-client");
        healthClientThread.start();
    }

    /**
     * 健康心跳主循环：connect → Hello → HelloAck → 每 5s Ping/Pong。
     * 连接失败或心跳超时则等待后重连，直到 healthClientRunning=false。
     */
    private void healthClientLoop(long sourceDeviceId) {
        while (healthClientRunning) {
            java.net.Socket socket = null;
            try {
                socket = new java.net.Socket();
                socket.connect(new java.net.InetSocketAddress(peerVirtualIp, HEALTH_PORT),
                        HEALTH_CONNECT_TIMEOUT_SEC * 1000);
                socket.setSoTimeout(HEALTH_PONG_TIMEOUT_SEC * 1000);
                healthSocket = socket;
                Log.i(TAG, "health client connected to " + peerVirtualIp + ":" + HEALTH_PORT);

                java.io.DataInputStream in = new java.io.DataInputStream(
                        new java.io.BufferedInputStream(socket.getInputStream()));
                java.io.DataOutputStream out = new java.io.DataOutputStream(
                        new java.io.BufferedOutputStream(socket.getOutputStream()));

                // 1. Hello 握手（对齐桌面 p2p.rs:340）
                String hello = "{\"t\":\"Hello\",\"c\":{\"source_device_id\":"
                        + sourceDeviceId + ",\"protocol_version\":"
                        + TUNNEL_CONTROL_PROTOCOL_VERSION + "}}";
                HealthFrameCodec.write(out, hello);
                byte[] ackBytes = HealthFrameCodec.read(in);
                JSONObject ack = new JSONObject(new String(ackBytes, java.nio.charset.StandardCharsets.UTF_8));
                if (!"HelloAck".equals(ack.optString("t", ""))) {
                    Log.w(TAG, "health unexpected hello ack: " + ack);
                    break;
                }
                JSONObject ackC = ack.optJSONObject("c");
                // 兼容性握手：只要求对端明确 ok 且版本不低于下限；不设上限
                // （本端消息集为 v3 基线，向上兼容新增字段/消息；未知消息忽略）。
                if (ackC == null || !ackC.optBoolean("ok", false)) {
                    healthFailReason = "健康通道握手被拒：" + ack;
                    Log.w(TAG, "health hello ack rejected: " + ack);
                    break;
                }
                int remoteVersion = ackC.optInt("protocol_version", 0);
                if (remoteVersion > 0 && remoteVersion < TUNNEL_CONTROL_PROTOCOL_MIN_COMPAT) {
                    healthFailReason = "对端客户端协议版本过旧（对端 v" + remoteVersion
                            + "，本端要求 ≥ v" + TUNNEL_CONTROL_PROTOCOL_MIN_COMPAT
                            + "），请升级对端客户端后重试";
                    Log.w(TAG, "health hello ack protocol too old: " + ack);
                    break;
                }
                if (remoteVersion > TUNNEL_CONTROL_PROTOCOL_VERSION) {
                    Log.i(TAG, "health handshake ok with peer protocol v" + remoteVersion
                            + " (local v" + TUNNEL_CONTROL_PROTOCOL_VERSION + ", compatible)");
                }
                healthFailReason = "";
                Log.i(TAG, "health handshake ok, starting heartbeat");
                tunnelLatencyMs = -1;
                emitStatusOnly();

                // 2. 心跳循环：每 5s 发 Ping，等 Pong（对齐桌面 p2p.rs:398-419）
                while (healthClientRunning && !socket.isClosed()) {
                    SpeedTestRequest speedRequest = takePendingSpeedTest();
                    if (speedRequest != null) {
                        runSpeedTest(in, out, speedRequest);
                        continue;
                    }
                    long ts = System.currentTimeMillis();
                    long reportedRttMs = tunnelLatencyMs;
                    String ping = "{\"t\":\"Ping\",\"c\":{\"ts\":" + ts
                            + (reportedRttMs >= 0
                            ? ",\"reported_rtt_ms\":" + reportedRttMs : "")
                            + "}}";
                    long startedNanos = SystemClock.elapsedRealtimeNanos();
                    HealthFrameCodec.write(out, ping);
                    Long remoteSpeedRequestId = null;
                    while (true) {
                        byte[] pongBytes = HealthFrameCodec.read(in);
                        JSONObject pong = new JSONObject(new String(
                                pongBytes, java.nio.charset.StandardCharsets.UTF_8));
                        String messageType = pong.optString("t", "");
                        if ("SpeedTestRequest".equals(messageType)) {
                            JSONObject requestContent = pong.optJSONObject("c");
                            long requestId = requestContent == null
                                    ? 0 : requestContent.optLong("request_id", 0);
                            if (requestId <= 0 || remoteSpeedRequestId != null || speedTestRunning) {
                                sendSpeedTestError(out, requestId, "测速正在进行");
                            } else {
                                // 请求可能先于本次 Pong 到达。先保存请求并继续读取 Pong，
                                // 避免随后测速读取到遗留的心跳响应而发生控制帧错位。
                                remoteSpeedRequestId = requestId;
                            }
                            continue;
                        }
                        JSONObject pongC = pong.optJSONObject("c");
                        if (!"Pong".equals(messageType)
                                || pongC == null || pongC.optLong("ts", 0) != ts) {
                            Log.w(TAG, "health unexpected pong: " + pong);
                            throw new java.io.IOException("健康检查响应无效：" + pong);
                        }
                        break;
                    }
                    long rawRttMs = Math.max(0L, Math.round(
                            (SystemClock.elapsedRealtimeNanos() - startedNanos) / 1_000_000.0));
                    long previousLatencyMs = tunnelLatencyMs;
                    tunnelLatencyMs = rawRttMs;
                    lastHealthSuccessElapsedMs = SystemClock.elapsedRealtime();
                    if (tunnelLatencyMs != previousLatencyMs) {
                        emitStatusOnly();
                    }
                    if (remoteSpeedRequestId != null) {
                        handleRemoteSpeedTest(in, out, remoteSpeedRequestId);
                    }
                    // 等待下一个心跳周期（sleep 5s，可被 stop 唤醒）
                    long deadline = System.currentTimeMillis() + HEALTH_PING_INTERVAL_SEC * 1000L;
                    while (healthClientRunning && System.currentTimeMillis() < deadline) {
                        try {
                            Thread.sleep(Math.min(500L, deadline - System.currentTimeMillis()));
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
            } catch (Exception e) {
                if (healthClientRunning) {
                    // 连接/心跳失败：记录原因，测速入口能在等待 60s 超时前给出具体解释。
                    healthFailReason = "健康通道连接失败（将自动重试）：" + e.getMessage();
                    Log.w(TAG, "health client error (will reconnect): " + e.getMessage());
                }
            } finally {
                if (socket != null) {
                    try { socket.close(); } catch (Exception ignored) {}
                }
                healthSocket = null;
            }
            recordHealthSessionEnded();
            // 重连前等待（对齐桌面 wgvpn_health_monitor_loop 5s 退避）
            if (healthClientRunning) {
                long deadline = System.currentTimeMillis() + HEALTH_RECONNECT_DELAY_SEC * 1000L;
                while (healthClientRunning && System.currentTimeMillis() < deadline) {
                    try {
                        Thread.sleep(Math.min(500L, deadline - System.currentTimeMillis()));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        Log.i(TAG, "health client loop exited");
    }

    /**
     * 健康连接断开后允许短暂重连，但不能无限保持 UI 的 CONNECTED 假象。
     * elapsedRealtime 不受系统时间校准影响，适合判断真实失联时长。
     */
    private void recordHealthSessionEnded() {
        if (!healthClientRunning || !TunnelState.CONNECTED.equals(activeState)) {
            return;
        }
        long elapsedMs = SystemClock.elapsedRealtime() - lastHealthSuccessElapsedMs;
        if (elapsedMs < HEALTH_UNHEALTHY_TIMEOUT_MS) {
            return;
        }
        Log.w(TAG, "health unavailable for " + elapsedMs + "ms; stopping stale tunnel");
        manualStopRequested = true;
        cancelNetworkRecovery();
        enqueueStopTunnel("隧道健康检查超时，已断开");
    }

    /** 从 UI 收到测速请求后，交给 health 线程串行执行，避免并发读写控制连接。 */
    private void startSpeedTest() {
        // healthSocket==null 说明心跳尚未握手成功（重连退避中）：直接报具体
        // 原因，避免测速请求挂在 pending 里等满 60s 才超时。
        if (!TunnelState.CONNECTED.equals(activeState) || peerVirtualIp.isEmpty()
                || !healthClientRunning || healthSocket == null) {
            String reason = healthFailReason == null || healthFailReason.isEmpty()
                    ? "隧道未连接或健康通道尚未就绪"
                    : healthFailReason;
            broadcastSpeedResult(false, 0, 0, 0, reason);
            return;
        }
        synchronized (speedTestLock) {
            if (speedTestRunning) {
                broadcastSpeedResult(false, 0, 0, 0, "测速正在进行");
                return;
            }
            speedTestRunning = true;
        }
        controlExecutor.execute(() -> {
            SpeedTestRequest request = new SpeedTestRequest();
            synchronized (speedTestLock) {
                pendingSpeedTest = request;
            }
            try {
                if (!request.completed.await(SPEED_TEST_TOTAL_TIMEOUT_SEC, TimeUnit.SECONDS)) {
                    request.error = "等待测速任务超时";
                }
                if (!request.error.isEmpty()) {
                    broadcastSpeedResult(false, 0, 0, 0, request.error);
                    return;
                }
                JSONObject result = new JSONObject(request.resultJson);
                broadcastSpeedResult(true,
                        result.optDouble("latency_ms", 0),
                        result.optDouble("download_mbps", 0),
                        result.optDouble("upload_mbps", 0), "");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                broadcastSpeedResult(false, 0, 0, 0, "测速已取消");
            } catch (Exception e) {
                broadcastSpeedResult(false, 0, 0, 0, "测速结果解析失败：" + e.getMessage());
            } finally {
                synchronized (speedTestLock) {
                    if (pendingSpeedTest == request) pendingSpeedTest = null;
                    speedTestRunning = false;
                }
            }
        });
    }

    private SpeedTestRequest takePendingSpeedTest() {
        synchronized (speedTestLock) {
            SpeedTestRequest request = pendingSpeedTest;
            pendingSpeedTest = null;
            return request;
        }
    }

    /** 桌面端同款流程：5 次 RTT → 上传、下载各自 SpeedStart/Ready + 单向 TCP 测速。 */
    private void runSpeedTest(java.io.DataInputStream in, java.io.DataOutputStream out, SpeedTestRequest request) {
        try {
            request.resultJson = performSpeedTest(in, out).toString();
        } catch (Exception e) {
            request.error = e.getMessage() == null ? "测速失败" : e.getMessage();
        } finally {
            request.completed.countDown();
        }
    }

    /** 被动端下发请求后仍由本主动端作为 client 执行，并将主动端视角结果回传。 */
    private void handleRemoteSpeedTest(java.io.DataInputStream in, java.io.DataOutputStream out,
                                       long requestId) throws Exception {
        synchronized (speedTestLock) {
            if (speedTestRunning) {
                sendSpeedTestError(out, requestId, "测速正在进行");
                return;
            }
            speedTestRunning = true;
        }
        try {
            JSONObject content = performSpeedTest(in, out).put("request_id", requestId);
            HealthFrameCodec.write(out, new JSONObject()
                    .put("t", "SpeedTestResult")
                    .put("c", content)
                    .toString());
        } catch (Exception e) {
            sendSpeedTestError(out, requestId,
                    e.getMessage() == null ? "测速失败" : e.getMessage());
        } finally {
            synchronized (speedTestLock) {
                speedTestRunning = false;
            }
        }
    }

    private JSONObject performSpeedTest(java.io.DataInputStream in,
                                        java.io.DataOutputStream out) throws Exception {
        long totalLatencyMs = 0;
        for (int i = 0; i < SPEED_TEST_PING_COUNT; i++) {
            long nonce = System.nanoTime();
            long startedAt = System.nanoTime();
            HealthFrameCodec.write(out,
                    "{\"t\":\"SpeedPing\",\"c\":{\"nonce\":" + nonce + "}}");
            JSONObject pong = readSpeedControlMessage(in, out);
            JSONObject content = pong.optJSONObject("c");
            if (!"SpeedPong".equals(pong.optString("t", "")) || content == null
                    || content.optLong("nonce", Long.MIN_VALUE) != nonce) {
                throw new java.io.IOException("测速延迟响应无效：" + pong);
            }
            totalLatencyMs += TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
        }
        double uploadMbps = runSpeedTestDirection(in, out, false);
        double downloadMbps = runSpeedTestDirection(in, out, true);
        return new JSONObject()
                .put("latency_ms", totalLatencyMs / (double) SPEED_TEST_PING_COUNT)
                .put("upload_mbps", uploadMbps)
                .put("download_mbps", downloadMbps);
    }

    /** 单方向测速前都重新启动一次对端 one-off riperf3 server，对齐桌面端。 */
    private double runSpeedTestDirection(java.io.DataInputStream in, java.io.DataOutputStream out,
                                         boolean reverse) throws Exception {
        HealthFrameCodec.write(out, "{\"t\":\"SpeedStart\"}");
        JSONObject ready = readSpeedControlMessage(in, out);
        if (!"SpeedReady".equals(ready.optString("t", ""))) {
            throw new java.io.IOException("对端未准备测速：" + ready);
        }
        JSONObject nativeResult = new JSONObject(Riperf3Native.runClient(
                peerVirtualIp, SPEED_TEST_PORT, SPEED_TEST_DURATION_SEC, reverse));
        double mbps = nativeResult.optDouble("mbps", -1);
        if (mbps < 0) throw new java.io.IOException("测速结果缺少吞吐数据");
        return mbps;
    }

    private static JSONObject readSpeedControlMessage(java.io.DataInputStream in,
                                                      java.io.DataOutputStream out) throws Exception {
        while (true) {
            JSONObject message = new JSONObject(new String(
                    HealthFrameCodec.read(in), java.nio.charset.StandardCharsets.UTF_8));
            if (!"SpeedTestRequest".equals(message.optString("t", ""))) {
                return message;
            }
            JSONObject content = message.optJSONObject("c");
            long requestId = content == null ? 0 : content.optLong("request_id", 0);
            sendSpeedTestError(out, requestId, "测速正在进行");
        }
    }

    private static void sendSpeedTestError(java.io.DataOutputStream out, long requestId,
                                           String error) throws Exception {
        HealthFrameCodec.write(out, new JSONObject()
                .put("t", "SpeedTestError")
                .put("c", new JSONObject()
                        .put("request_id", requestId)
                        .put("message", error))
                .toString());
    }

    private void broadcastSpeedResult(boolean success, double latencyMs, double downloadMbps,
                                      double uploadMbps, String message) {
        Intent intent = new Intent(ACTION_SPEED_RESULT);
        intent.setPackage(getPackageName());
        intent.putExtra(EXTRA_SPEED_SUCCESS, success);
        intent.putExtra(EXTRA_SPEED_LATENCY_MS, latencyMs);
        intent.putExtra(EXTRA_SPEED_DOWNLOAD_MBPS, downloadMbps);
        intent.putExtra(EXTRA_SPEED_UPLOAD_MBPS, uploadMbps);
        intent.putExtra(EXTRA_MESSAGE, message);
        sendBroadcast(intent, InternalBroadcasts.PERMISSION);
    }

    /**
     * 停止健康 client：尽力发 Stop 通知让被动端即时清理（对齐桌面 notify_remote_tunnel_stop），
     * 然后关闭 socket。发 Stop 失败静默忽略（被动端有 60s 心跳超时兜底）。
     */
    private void stopHealthClient() {
        healthClientRunning = false;
        failPendingSpeedTest("健康通道已断开，测速已取消");
        java.net.Socket socket = healthSocket;
        if (socket != null && !socket.isClosed()) {
            try {
                java.io.DataOutputStream out = new java.io.DataOutputStream(
                        new java.io.BufferedOutputStream(socket.getOutputStream()));
                HealthFrameCodec.write(out, "{\"t\":\"Stop\",\"c\":{\"reason\":\"initiator_closed\"}}");
                out.flush();
            } catch (Exception e) {
                Log.w(TAG, "health send Stop failed: " + e.getMessage());
            }
            try { socket.close(); } catch (Exception ignored) {}
        }
        healthSocket = null;
        // 唤醒可能正在 sleep 的心跳线程
        if (healthClientThread != null) {
            healthClientThread.interrupt();
            healthClientThread = null;
        }
        Log.i(TAG, "health client stopped");
    }

    private void failPendingSpeedTest(String error) {
        synchronized (speedTestLock) {
            if (pendingSpeedTest != null) {
                pendingSpeedTest.error = error;
                pendingSpeedTest.completed.countDown();
                pendingSpeedTest = null;
            }
        }
    }

    private void acquireTunnelWakeLock() {
        acquireTunnelWakeLock(0L);
    }

    private void acquireTunnelWakeLock(long timeoutMs) {
        if (tunnelWakeLock == null) {
            PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (powerManager == null) {
                Log.w(TAG, "PowerManager unavailable; tunnel cannot hold wake lock");
                return;
            }
            tunnelWakeLock = powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK, getPackageName() + ":wgvpn-tunnel");
            tunnelWakeLock.setReferenceCounted(false);
        }
        if (timeoutMs <= 0 && tunnelWakeLock.isHeld() && tunnelWakeLockTimed) {
            tunnelWakeLock.release();
            tunnelWakeLockTimed = false;
        }
        if (!tunnelWakeLock.isHeld()) {
            if (timeoutMs > 0) {
                tunnelWakeLock.acquire(timeoutMs);
                tunnelWakeLockTimed = true;
            } else {
                tunnelWakeLock.acquire();
                tunnelWakeLockTimed = false;
            }
            Log.i(TAG, "tunnel wake lock acquired");
        }
    }

    private void releaseTunnelWakeLock() {
        if (tunnelWakeLock != null && tunnelWakeLock.isHeld()) {
            tunnelWakeLock.release();
            tunnelWakeLockTimed = false;
            Log.i(TAG, "tunnel wake lock released");
        }
    }

    /**
     * 主动端放弃本次 attempt 时，通过 WS 发 attempt_cancel 通知被动端停止等待。
     * 对齐桌面 P2PAttemptMessage::AttemptCancel。失败静默忽略（被动端有自身超时兜底）。
     */
    private void sendAttemptCancel(WsConnection ws, ApiClient.OpenResult opened,
                                   long targetDeviceId, String attemptId, String reason) {
        try {
            JSONObject cancelData = new JSONObject();
            cancelData.put("type", "attempt_cancel");
            cancelData.put("protocol_version", 1);
            cancelData.put("attempt_id", attemptId);
            cancelData.put("reason", reason);
            ws.sendNotifyWaitAck(opened.connectionId, targetDeviceId,
                    ApiClient.buildMessageId(), opened.accessGrant, cancelData.toString());
        } catch (Exception e) {
            Log.w(TAG, "sendAttemptCancel failed (best-effort): " + e.getMessage());
        }
    }

    /**
     * 断开隧道，对齐桌面 stop_wgvpn 清理顺序。
     */
    private void stopTunnel(String message) {
        stopTrafficPolling();
        stopHealthClient();
        String cleanupError = cleanupNative();
        connectedAtMs = 0;
        cumulativeRx = 0;
        cumulativeTx = 0;
        lastSampleRx = 0;
        lastSampleTx = 0;
        tunnelLatencyMs = -1;
        if (cleanupError.isEmpty()) {
            emit(TunnelState.STOPPED, message, "", "", "");
            stopForeground(true);
        } else {
            Log.e(TAG, "tunnel cleanup incomplete: " + cleanupError);
            emit(TunnelState.FAILED, "隧道清理失败：" + cleanupError, "", "", "");
            stopForeground(false);
        }
        stopSelf();
    }

    /** 所有 native 销毁操作与建链操作共用同一个单线程队列，调用方不得直接清理。 */
    private void enqueueStopTunnel(String message) {
        if (!stopQueued.compareAndSet(false, true)) {
            return;
        }
        try {
            executor.execute(() -> {
                try {
                    stopTunnel(message);
                } finally {
                    stopQueued.set(false);
                }
            });
        } catch (RejectedExecutionException e) {
            stopQueued.set(false);
            Log.w(TAG, "stop ignored because native executor is closed", e);
        }
    }

    /**
     * 清理 native 资源：WG -> gonc -> TUN。
     */
    private String cleanupNative() {
        StringBuilder errors = new StringBuilder();
        releaseTunnelWakeLock();
        // wgStart 成功后 TUN fd 已通过 detachFd() 转交 native。必须在修改
        // wgRunning 前保存所有权，避免 wgStop 后再次关闭同一个 fd。
        boolean tunOwnedByJava = !wgRunning;
        Exception peerRemovalError = null;
        // 移除 WG peer
        if (wgRunning && !peerPubkeyHex.isEmpty()) {
            try {
                Libwgmobile.wgRemovePeer(peerPubkeyHex);
                peerPubkeyHex = "";
            } catch (Exception e) {
                Log.w(TAG, "wgRemovePeer error: " + e.getMessage());
                // 后续 wgStop 成功会释放整个 WireGuard 实例，可覆盖单个 peer
                // 删除失败；只有 wgStop 也失败时才将其计为未完成清理。
                peerRemovalError = e;
            }
        }
        // 停止 gonc UDP tunnel
        if (!goncHandleId.isEmpty()) {
            try {
                PunchNative.stopUdpTunnel(goncHandleId);
                goncHandleId = "";
            } catch (Exception e) {
                Log.w(TAG, "stopUdpTunnel error: " + e.getMessage());
                appendCleanupError(errors, "停止 UDP tunnel", e);
            }
        }
        // 停止 userspace WG
        if (wgRunning) {
            try {
                Libwgmobile.wgStop();
                wgRunning = false;
                // wgStart 成功后 fd 已由 native 接管，wgStop 成功即代表该 fd
                // 已完成释放；这里只丢弃 Java 侧已 detach 的包装对象。
                tunFd = null;
                peerPubkeyHex = "";
            } catch (Exception e) {
                Log.w(TAG, "wgStop error: " + e.getMessage());
                if (peerRemovalError != null) {
                    appendCleanupError(errors, "移除 WireGuard peer", peerRemovalError);
                }
                appendCleanupError(errors, "停止 WireGuard", e);
            }
        }
        // 关闭 TUN fd。
        // 成功路径下 fd 已 detachFd() 转交 native（由 WgStop 关闭），这里 tunFd 不可再关；
        // 仅在 wgStart 之前失败（wgRunning=false，fd 仍归 Java）时由这里负责关闭。
        if (tunFd != null && tunOwnedByJava) {
            try {
                tunFd.close();
                tunFd = null;
            } catch (Exception e) {
                Log.w(TAG, "tunFd close error: " + e.getMessage());
                appendCleanupError(errors, "关闭 TUN", e);
            }
        }
        return errors.toString();
    }

    private static void appendCleanupError(StringBuilder errors, String operation, Exception error) {
        if (errors.length() > 0) errors.append("；");
        errors.append(operation).append("失败");
        String message = error.getMessage();
        if (message != null && !message.trim().isEmpty()) {
            errors.append("（").append(message.trim()).append("）");
        }
    }

    private void emit(String state, String message, String vIp, String peerIp, String lan) {
        DiagLog.i(TAG, "state=" + state + " msg=" + message);
        activeState = state;
        activeMessage = message;
        // 仅在传入非空值时更新对应字段；传 "" 表示「该字段本阶段无新值，保持不变」。
        // 历史 bug：PUNCHING 等中间阶段 emit(...,"","","","") 会用空串覆盖已解析的
        // exposedLan，导致后续 addRoute/AllowedIPs 丢失 LAN 网段。用 isEmpty 守卫即可。
        if (vIp != null && !vIp.isEmpty()) myVirtualIp = vIp;
        if (peerIp != null && !peerIp.isEmpty()) peerVirtualIp = peerIp;
        if (lan != null && !lan.isEmpty()) exposedLan = lan;
        broadcastStatus(state, message);
        updateNotification(state, message);
    }

    /**
     * 仅用当前最新状态向外广播一次，不改任何内部状态字段。
     * 用于响应 ACTION_QUERY（Activity 配置变更后回查），避免 emit 改状态引发递归。
     */
    private void emitStatusOnly() {
        broadcastStatus(activeState, activeMessage);
    }

    private void broadcastStatus(String state, String message) {
        Intent update = new Intent(ACTION_STATUS);
        update.setPackage(getPackageName());
        update.putExtra(EXTRA_STATE, state);
        update.putExtra(EXTRA_MESSAGE, message);
        update.putExtra(EXTRA_TCP_RETRY, tcpRetryRecommended);
        update.putExtra(EXTRA_NETWORK, selectedNetwork);
        update.putExtra(EXTRA_VIRTUAL_IP, myVirtualIp);
        update.putExtra(EXTRA_PEER_VIRTUAL_IP, peerVirtualIp);
        update.putExtra(EXTRA_EXPOSED_LAN, exposedLan);
        update.putExtra(EXTRA_CONNECTED_AT, connectedAtMs);
        update.putExtra(EXTRA_RX_BYTES, cumulativeRx);
        update.putExtra(EXTRA_TX_BYTES, cumulativeTx);
        update.putExtra(EXTRA_LATENCY_MS, tunnelLatencyMs);
        sendBroadcast(update, InternalBroadcasts.PERMISSION);
    }

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "p2pRemote wgvpn", NotificationManager.IMPORTANCE_LOW);
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.createNotificationChannel(channel);
        }
    }

    private Notification notification(String title, String text) {
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        // contentIntent：点击通知跳转到 App 主界面（MainActivity）。
        // Android 12+ (API 31+) 要求 PendingIntent 必须指定 mutability 标志，这里用 IMMUTABLE。
        Intent openApp = new Intent(this, MainActivity.class);
        openApp.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            pendingFlags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0, openApp, pendingFlags);
        return builder
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title == null ? "p2pRemote" : title)
                .setContentText(text == null || text.isEmpty() ? "p2pRemote" : text)
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .build();
    }

    private void updateNotification(String state, String message) {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager == null) return;
        String title;
        if (TunnelState.CONNECTED.equals(state)) {
            title = "隧道已建立 · " + peerVirtualIp;
        } else if (TunnelState.FAILED.equals(state)) {
            title = "隧道建立失败";
        } else if (TunnelState.STOPPED.equals(state)) {
            title = "p2pRemote 隧道";
        } else {
            title = "p2pRemote VPN";
        }
        manager.notify(NOTIFICATION_ID, notification(title, message));
    }

    /** 点分 IPv4 -> big-endian u32。 */
    private static long ipToU32(String ip) {
        String[] parts = ip.split("\\.");
        if (parts.length != 4) return 0;
        long result = 0;
        for (String p : parts) {
            result = (result << 8) | (Integer.parseInt(p) & 0xFF);
        }
        return result & 0xFFFFFFFFL;
    }

    /** big-endian u32 -> 点分 IPv4。 */
    private static String u32ToIp(long v) {
        return ((v >> 24) & 0xFF) + "." + ((v >> 16) & 0xFF) + "."
                + ((v >> 8) & 0xFF) + "." + (v & 0xFF);
    }

    /**
     * 根据 device_id 确定性计算虚拟 IP，对齐桌面端 passive_ip_for_device。
     * 算法：ip_start + (|device_id| % count)，count = ip_end - ip_start + 1。
     * 主动端用「目标设备 ID」计算被动端的期望 IP，作为 requested_ip 发送，
     * 让被动端采用同一 IP（被动端收到有效 requested_ip 会直接使用）。
     */
    private static long computePassiveIpForDevice(long deviceId, long ipStart, long ipEnd) {
        if (ipStart > ipEnd) return ipStart;
        // Math.abs(Long.MIN_VALUE) 仍为负，用位运算取绝对值避免
        long absId = (deviceId == Long.MIN_VALUE) ? Long.MAX_VALUE : Math.abs(deviceId);
        long count = ipEnd - ipStart + 1;
        if (count <= 0) return ipStart;
        return ipStart + (absId % count);
    }

    /**
     * hex 编码的 WG 公钥（64 字符）转 base64（44 字符），用于 ExchangePayload。
     * 桌面端期望 base64 格式的 pubkey。
     */
    private static String hexToBase64(String hex) {
        if (hex == null || hex.isEmpty()) return "";
        byte[] bytes = new byte[hex.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP);
    }

    /**
     * base64 编码的 WG 公钥转 hex（64 字符），用于解析被动端回传的 ExchangePayload。
     * Android 的 userspace WG 需要 hex 格式。
     */
    private static String base64ToHex(String base64) {
        if (base64 == null || base64.isEmpty()) return "";
        byte[] bytes = android.util.Base64.decode(base64, android.util.Base64.DEFAULT);
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xFF));
        }
        return sb.toString();
    }
}
