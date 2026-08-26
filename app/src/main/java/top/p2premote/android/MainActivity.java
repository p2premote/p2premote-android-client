package top.p2premote.android;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Typeface;
import android.graphics.Insets;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 主界面：登录页 + 底部三导航（连接/设备/我的）。
 *
 * wgvpn 架构版：建立隧道前需请求 VPN 权限，隧道成功后展示虚拟 IP。
 */
public final class MainActivity extends Activity {
    private static final int PAGE_CONNECT = 0;
    private static final int PAGE_DEVICES = 1;
    private static final int PAGE_PROFILE = 2;
    private static final int VPN_PERMISSION_REQUEST = 100;
    private static final String DEFAULT_SERVER_URL = "https://cli.p2premote.top";
    private static final String CLIENT_DOWNLOAD_PAGE_URL = "https://www.p2premote.top/#download";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final List<DeviceItem> cachedDevices = new ArrayList<>();
    /** 后台任务只可回调创建它的 Activity 代际；销毁或退出登录会使旧回调失效。 */
    private volatile int callbackGeneration = 0;
    private volatile boolean destroyed = false;

    private SessionStore sessionStore;
    private ApiClient apiClient;
    private LinearLayout contentRoot;
    private ProgressBar progress;
    private TextView statusView;
    private int currentPage = PAGE_CONNECT;

    // 隧道状态（由 WgvpnService 广播驱动）
    private String currentTunnelState = TunnelState.IDLE;
    private String currentTunnelMessage = "";
    private String peerVirtualIp = "";
    private String exposedLan = "";
    /** 隧道连接时间（ms），CONNECTED 时由广播带出，用于展示已连接时长。 */
    private long connectedAtMs = 0;
    /** 累计流量（由 WgvpnService 广播驱动），用于连接页统计展示。 */
    private long rxBytes = 0;
    private long txBytes = 0;
    /** 健康心跳最近一次测得的隧道 RTT；-1 表示尚无有效样本。 */
    private long tunnelLatencyMs = -1;
    /** 当前隧道测速是否正在执行；由测速结果广播复位。 */
    private boolean speedTesting = false;

    // 连接页局部刷新：结构变化才整页重建，否则只 setText 动态字段（时长/流量），
    // 避免每秒 removeAllViews 重建整页造成的 GC 抖动与潜在闪烁。
    private String connectPageSignature = "";
    private TextView durationView;
    private TextView trafficDownView;
    private TextView trafficUpView;
    private TextView latencyView;
    // B1：状态切换时按钮背景色平滑过渡。记录上一帧按钮色，重建后跑 ArgbEvaluator 动画。
    private int lastConnectBtnColor = 0;
    private Button connectBtn;
    private int connectBtnColor = 0;
    // B5：登录页输入框引用，失败时按错误类型高亮红框。仅登录页有效，其它页置 null。
    private EditText loginIdentifier;
    private EditText loginPassword;

    // 待建立隧道的目标（VPN 授权回来后用）
    private DeviceItem pendingTarget = null;

    /** 设备页选中的目标设备 ID，持久化到 SessionStore，0 = 未选中。 */
    private long selectedDeviceId = 0;

    // 在线保活状态（由 PresenceService 广播驱动）
    private String presenceState = PresenceService.STATE_DISCONNECTED;
    private String presenceMessage = "";
    /** 标记上次 presence 状态是否已连接，用于检测「刚连上」触发一次设备刷新。 */
    private boolean presenceWasConnected = false;

    /** 连接时长每秒刷新回调，仅在 CONNECTED 且处于连接页时运行。 */
    private final Runnable connectedDurationTicker = new Runnable() {
        @Override
        public void run() {
            if (TunnelState.CONNECTED.equals(currentTunnelState) && currentPage == PAGE_CONNECT) {
                // 每秒变化的只有时长；不要进入通用 render 流程，否则会重复触发布局并
                // 在 contentRoot 尾部不断追加 spacer，造成页面闪烁和高度持续增长。
                updateConnectDynamicTexts();
                mainHandler.postDelayed(this, 1000L);
            }
        }
    };

    private final BroadcastReceiver tunnelReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (WgvpnService.ACTION_SPEED_RESULT.equals(intent.getAction())) {
                speedTesting = false;
                renderCurrentPage();
                boolean success = intent.getBooleanExtra(WgvpnService.EXTRA_SPEED_SUCCESS, false);
                if (success) {
                    double latency = intent.getDoubleExtra(WgvpnService.EXTRA_SPEED_LATENCY_MS, 0);
                    double download = intent.getDoubleExtra(WgvpnService.EXTRA_SPEED_DOWNLOAD_MBPS, 0);
                    double upload = intent.getDoubleExtra(WgvpnService.EXTRA_SPEED_UPLOAD_MBPS, 0);
                    new android.app.AlertDialog.Builder(MainActivity.this)
                            .setTitle("隧道测速结果")
                            .setMessage(String.format(java.util.Locale.US,
                                    "延迟：%.1f ms\n下载：%.2f Mbps\n上传：%.2f Mbps",
                                    latency, download, upload))
                            .setPositiveButton("确定", null)
                            .show();
                } else {
                    toast("测速失败：" + safe(intent.getStringExtra(WgvpnService.EXTRA_MESSAGE)));
                }
                return;
            }
            if (!WgvpnService.ACTION_STATUS.equals(intent.getAction())) {
                return;
            }
            currentTunnelState = safe(intent.getStringExtra(WgvpnService.EXTRA_STATE));
            currentTunnelMessage = safe(intent.getStringExtra(WgvpnService.EXTRA_MESSAGE));
            peerVirtualIp = safe(intent.getStringExtra(WgvpnService.EXTRA_PEER_VIRTUAL_IP));
            exposedLan = safe(intent.getStringExtra(WgvpnService.EXTRA_EXPOSED_LAN));
            connectedAtMs = intent.getLongExtra(WgvpnService.EXTRA_CONNECTED_AT, 0);
            rxBytes = intent.getLongExtra(WgvpnService.EXTRA_RX_BYTES, 0);
            txBytes = intent.getLongExtra(WgvpnService.EXTRA_TX_BYTES, 0);
            tunnelLatencyMs = intent.getLongExtra(WgvpnService.EXTRA_LATENCY_MS, -1);
            renderCurrentPage();
        }
    };

    private final BroadcastReceiver presenceReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!PresenceService.ACTION_STATUS.equals(intent.getAction())) {
                return;
            }
            String newState = safe(intent.getStringExtra(PresenceService.EXTRA_STATE));
            presenceMessage = safe(intent.getStringExtra(PresenceService.EXTRA_MESSAGE));
            // WS 刚从未连接变为已连接时，强制刷新一次设备列表。
            // 冷启动时 startPresenceService 与 refreshDevices 几乎同时触发，WS 建连前
            // 拉的列表可能把本机标记为离线；WS 一连上必须重拉修正（对齐桌面 b85f4086）。
            boolean justConnected = !presenceWasConnected
                    && PresenceService.STATE_CONNECTED.equals(newState);
            presenceState = newState;
            presenceWasConnected = PresenceService.STATE_CONNECTED.equals(newState);
            renderCurrentPage();
            if (justConnected) {
                refreshDevices("正在同步在线状态...");
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // minSdk 29：系统原生支持 TLS 1.3，无需 Conscrypt/TlsCompat。
        DeviceIdentity.init(this);
        sessionStore = new SessionStore(this);
        apiClient = new ApiClient(sessionStore);
        selectedDeviceId = sessionStore.getSelectedDeviceId();
        registerTunnelReceiver();
        requestNotificationPermissionIfNeeded();
        checkVersionAtStartup();
    }

    /** 冷启动先检查 Android 平台策略，强制更新时不得恢复已有登录态。 */
    private void checkVersionAtStartup() {
        LinearLayout loading = new LinearLayout(this);
        loading.setOrientation(LinearLayout.VERTICAL);
        loading.setGravity(Gravity.CENTER);
        loading.setPadding(dp(32), dp(32), dp(32), dp(32));
        ProgressBar indicator = new ProgressBar(this);
        loading.addView(indicator);
        TextView message = muted(getString(R.string.version_checking));
        message.setPadding(0, dp(16), 0, 0);
        loading.addView(message);
        setContentView(loading);

        Session existingSession = sessionStore.load();
        String serverUrl = existingSession == null ? DEFAULT_SERVER_URL : existingSession.serverUrl;
        final int generation = callbackGeneration;
        executor.execute(() -> {
            ClientVersionPolicy policy = null;
            try {
                policy = apiClient.getClientVersionPolicy(serverUrl);
            } catch (Exception error) {
                // 版本服务不可用时保持可用性；登录前仍会再次检查。
                android.util.Log.w("p2pRemote", "version policy check failed", error);
            }
            ClientVersionPolicy checkedPolicy = policy;
            mainHandler.post(() -> {
                if (!acceptsCallback(generation)) return;
                if (checkedPolicy != null && checkedPolicy.requiresForceUpdate(BuildConfig.VERSION_NAME)) {
                    showForceUpdate(checkedPolicy);
                    return;
                }
                continueAfterVersionCheck();
                if (checkedPolicy != null && checkedPolicy.hasUpdate(BuildConfig.VERSION_NAME)) {
                    showOptionalUpdate(checkedPolicy);
                }
            });
        });
    }

    private void continueAfterVersionCheck() {
        if (sessionStore.load() == null) {
            showLogin();
            return;
        }
        // 已认证冷启动：版本通过后才启动在线保活并恢复主界面。
        startPresenceService();
        showApp(PAGE_CONNECT);
        refreshDevices("正在同步设备...");
    }

    private void showOptionalUpdate(ClientVersionPolicy policy) {
        String message = getString(R.string.update_available_message,
                policy.latestVersion, BuildConfig.VERSION_NAME);
        if (!policy.releaseNotes.isEmpty()) message += "\n\n" + policy.releaseNotes;
        new android.app.AlertDialog.Builder(this)
                .setTitle(R.string.update_available_title)
                .setMessage(message)
                .setPositiveButton(R.string.open_download_page, (dialog, which) -> openClientDownloadPage())
                .setNegativeButton(R.string.update_later, null)
                .show();
    }

    /** 强制更新页不可绕过；同时停止旧版本仍可能运行的连接服务。 */
    private void showForceUpdate(ClientVersionPolicy policy) {
        callbackGeneration++;
        stopPresenceService();
        Intent stopTunnelIntent = new Intent(this, WgvpnService.class);
        stopTunnelIntent.setAction(WgvpnService.ACTION_STOP);
        startService(stopTunnelIntent);

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setGravity(Gravity.CENTER);
        page.setPadding(dp(32), dp(32), dp(32), dp(32));
        page.addView(heading(getString(R.string.force_update_title)));
        page.addView(spacer(12));
        page.addView(body(getString(R.string.force_update_message,
                BuildConfig.VERSION_NAME, policy.minSupportedVersion)));
        if (!policy.releaseNotes.isEmpty()) {
            page.addView(spacer(12));
            page.addView(helpText(policy.releaseNotes));
        }
        page.addView(spacer(24));
        Button download = primaryButton(getString(R.string.open_download_page));
        download.setOnClickListener(view -> openClientDownloadPage());
        page.addView(download, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        Button exit = outlineButton(getString(R.string.exit_client), 0xFF475569);
        exit.setOnClickListener(view -> finishAndRemoveTask());
        LinearLayout.LayoutParams exitParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        exitParams.topMargin = dp(12);
        page.addView(exit, exitParams);
        setContentView(page);
    }

    private void openClientDownloadPage() {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(CLIENT_DOWNLOAD_PAGE_URL)));
        } catch (Exception error) {
            toast(getString(R.string.open_website_failed, CLIENT_DOWNLOAD_PAGE_URL));
        }
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        callbackGeneration++;
        mainHandler.removeCallbacksAndMessages(null);
        unregisterReceiver(tunnelReceiver);
        unregisterReceiver(presenceReceiver);
        executor.shutdownNow();
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 配置变更（旋转等）已被 configChanges 拦截不重建，但进程被回收重建、
        // 或从其它页面返回时，隧道状态字段会重置为初值。这里主动查询服务最新状态，
        // 服务收到 ACTION_QUERY 会回发一次 ACTION_STATUS 广播，由 tunnelReceiver 同步 UI。
        queryTunnelStatus();
    }

    /**
     * 向 WgvpnService 查询当前隧道状态。服务未运行时 startService 会拉起 onCreate
     * 但无任务，回查静默无效；服务运行中则回发最新状态广播。
     */
    private void queryTunnelStatus() {
        Intent query = new Intent(this, WgvpnService.class);
        query.setAction(WgvpnService.ACTION_QUERY);
        try {
            startService(query);
        } catch (Exception ignored) {
            // 服务未运行或尚未注册，忽略：保持当前 UI 状态即可。
        }
    }

    /**
     * 启动在线保活服务（WebSocket 长连接）。登录成功或已认证冷启动时调用。
     */
    private void startPresenceService() {
        Intent intent = new Intent(this, PresenceService.class);
        intent.setAction(PresenceService.ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }

    /**
     * 停止在线保活服务。退出登录时调用。
     */
    private void stopPresenceService() {
        Intent intent = new Intent(this, PresenceService.class);
        intent.setAction(PresenceService.ACTION_STOP);
        startService(intent);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == VPN_PERMISSION_REQUEST) {
            if (resultCode == RESULT_OK && pendingTarget != null) {
                // 用户同意 VPN，启动 WgvpnService
                startWgvpnService(pendingTarget);
            } else {
                currentTunnelState = TunnelState.ABORTED;
                currentTunnelMessage = "需要 VPN 权限才能建立隧道";
                renderCurrentPage();
            }
            pendingTarget = null;
        }
    }

    // ============ 登录页 ============

    private void showLogin() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setGravity(Gravity.CENTER);
        page.setPadding(dp(24), dp(28), dp(24), dp(28));
        page.setBackgroundColor(0xFFF4F7FB);
        scroll.addView(page, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.MATCH_PARENT));
        setRootContent(scroll);

        LinearLayout card = card();
        card.setPadding(dp(22), dp(24), dp(22), dp(24));
        page.addView(card, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView brand = heading("p2pRemote");
        TextView subtitle = muted("手机端 P2P 连接客户端");
        loginIdentifier = input("用户名或邮箱", false);
        loginPassword = input("密码", true);
        EditText identifier = loginIdentifier;
        EditText password = loginPassword;
        Button login = primaryButton("登录");
        progress = progressBar();
        statusView = muted("");

        // 高级折叠：默认隐藏服务地址，点击展开
        final EditText[] serverUrlRef = new EditText[1];
        final int[] advancedIndex = new int[1];
        TextView advancedToggle = helpText("▸ 高级");
        advancedToggle.setTextColor(0xFF2563EB);

        card.addView(brandLogo());
        card.addView(brand);
        card.addView(subtitle);
        card.addView(spacer(10));
        card.addView(identifier);
        card.addView(password);
        advancedIndex[0] = card.getChildCount();
        card.addView(advancedToggle);
        card.addView(login);
        card.addView(progress);
        card.addView(statusView);
        card.addView(helpText("登录后会自动注册本机为 Android 设备。"));

        // 注册 / 忘记密码入口链接
        LinearLayout loginLinks = horizontal();
        loginLinks.setPadding(0, dp(12), 0, 0);
        TextView toRegister = helpText("没有账号？注册");
        toRegister.setTextColor(0xFF2563EB);
        toRegister.setPadding(0, 0, dp(16), 0);
        toRegister.setOnClickListener(v -> showRegister());
        TextView toForgot = helpText("忘记密码？");
        toForgot.setTextColor(0xFF2563EB);
        toForgot.setOnClickListener(v -> showForgotPassword());
        loginLinks.addView(toRegister);
        loginLinks.addView(toForgot);
        card.addView(loginLinks);

        advancedToggle.setOnClickListener(v -> {
            if (serverUrlRef[0] == null) {
                // 展开：创建输入框插入到高级开关之后
                serverUrlRef[0] = input("服务地址", false);
                serverUrlRef[0].setText(DEFAULT_SERVER_URL);
                card.addView(serverUrlRef[0], advancedIndex[0] + 1);
                advancedToggle.setText("▾ 高级");
            } else {
                // 收起：移除输入框
                card.removeView(serverUrlRef[0]);
                serverUrlRef[0] = null;
                advancedToggle.setText("▸ 高级");
            }
        });

        login.setOnClickListener(v -> {
            clearInputError(identifier);
            clearInputError(password);
            runAsync("正在登录并注册本机...", () -> {
                String serverUrl = (serverUrlRef[0] != null && !serverUrlRef[0].getText().toString().trim().isEmpty())
                        ? serverUrlRef[0].getText().toString()
                        : DEFAULT_SERVER_URL;
                try {
                    ClientVersionPolicy policy = apiClient.getClientVersionPolicy(serverUrl);
                    if (policy.requiresForceUpdate(BuildConfig.VERSION_NAME)) {
                        throw new ForceUpdateRequiredException(policy);
                    }
                } catch (ForceUpdateRequiredException error) {
                    throw error;
                } catch (Exception error) {
                    // 版本服务异常时不把网络故障误判成强制更新。
                    android.util.Log.w("p2pRemote", "pre-login version check failed", error);
                }
                apiClient.login(
                        serverUrl,
                        identifier.getText().toString(),
                        password.getText().toString());
                apiClient.registerCurrentDevice(this);
                return apiClient.listDevices();
            }, devices -> {
                cachedDevices.clear();
                cachedDevices.addAll(devices);
                currentPage = PAGE_CONNECT;
                // 登录注册成功后启动在线保活（device_id/device_uuid 已持久化）。
                startPresenceService();
                showApp(PAGE_CONNECT);
            });
        });
    }

    // ============ 注册页 ============

    private void showRegister() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setGravity(Gravity.CENTER);
        page.setPadding(dp(24), dp(28), dp(24), dp(28));
        page.setBackgroundColor(0xFFF4F7FB);
        scroll.addView(page, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.MATCH_PARENT));
        setRootContent(scroll);

        LinearLayout card = card();
        card.setPadding(dp(22), dp(24), dp(22), dp(24));
        page.addView(card, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView brand = heading("创建账号");
        TextView subtitle = muted("使用邮箱验证码完成注册");
        EditText regUsername = input("用户名（3-30位）", false);
        EditText regEmail = input("邮箱", false);
        Button regSendCodeBtn = outlineButton("发送验证码", 0xFF2563EB);
        EditText regVerificationCode = input("6位邮箱验证码（5分钟内有效）", false);
        regVerificationCode.setInputType(InputType.TYPE_CLASS_NUMBER);
        regVerificationCode.setFilters(new InputFilter[]{new InputFilter.LengthFilter(6)});
        EditText regPassword = input("密码（至少 6 位）", true);
        EditText regConfirmPassword = input("确认密码", true);
        EditText regInviteCode = input("邀请码（选填）", false);
        Button registerBtn = primaryButton("注册");
        progress = progressBar();
        statusView = muted("");
        TextView backToLogin = helpText("已有账号？返回登录");

        card.addView(brandLogo());
        card.addView(brand);
        card.addView(subtitle);
        card.addView(spacer(6));
        card.addView(regUsername);
        card.addView(regEmail);
        card.addView(regSendCodeBtn);
        card.addView(regVerificationCode);
        card.addView(regPassword);
        card.addView(regConfirmPassword);
        card.addView(regInviteCode);
        card.addView(registerBtn);
        card.addView(progress);
        card.addView(statusView);
        card.addView(backToLogin);

        // 服务地址高级折叠（对齐登录页写法：addView 前取 childCount）
        final EditText[] serverUrlRef = new EditText[1];
        final int[] advancedIndex = new int[1];
        TextView advancedToggle = helpText("▸ 高级");
        advancedToggle.setTextColor(0xFF2563EB);
        advancedIndex[0] = card.getChildCount();
        card.addView(advancedToggle);

        advancedToggle.setOnClickListener(v -> {
            if (serverUrlRef[0] == null) {
                serverUrlRef[0] = input("服务地址", false);
                serverUrlRef[0].setText(DEFAULT_SERVER_URL);
                card.addView(serverUrlRef[0], advancedIndex[0] + 1);
                advancedToggle.setText("▾ 高级");
            } else {
                card.removeView(serverUrlRef[0]);
                serverUrlRef[0] = null;
                advancedToggle.setText("▸ 高级");
            }
        });

        backToLogin.setOnClickListener(v -> showLogin());

        regSendCodeBtn.setOnClickListener(v -> {
            String email = regEmail.getText().toString().trim();
            if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                toast("请输入有效的邮箱地址");
                return;
            }
            String serverUrl = (serverUrlRef[0] != null && !serverUrlRef[0].getText().toString().trim().isEmpty())
                    ? serverUrlRef[0].getText().toString()
                    : DEFAULT_SERVER_URL;
            runAsync("正在发送验证码...", () -> {
                apiClient.sendVerificationCode(serverUrl, email, "register");
                return null;
            }, _unused -> toast("验证码已发送，5分钟内有效，请检查邮箱"));
        });

        registerBtn.setOnClickListener(v -> {
            String username = regUsername.getText().toString().trim();
            String email = regEmail.getText().toString().trim();
            String verificationCode = regVerificationCode.getText().toString().trim();
            String password = regPassword.getText().toString();
            String confirmPassword = regConfirmPassword.getText().toString();
            String inviteCode = regInviteCode.getText().toString().trim();

            if (username.length() < 3) { toast("用户名至少3个字符"); return; }
            if (username.length() > 30) { toast("用户名最多30个字符"); return; }
            if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                toast("请输入有效的邮箱地址"); return;
            }
            if (!VerificationCodeValidator.isValidEmailCode(verificationCode)) {
                toast("请输入6位数字邮箱验证码"); return;
            }
            if (password.length() < 6) { toast("密码至少6位"); return; }
            if (!confirmPassword.equals(password)) { toast("两次输入的密码不一致"); return; }

            String serverUrl = (serverUrlRef[0] != null && !serverUrlRef[0].getText().toString().trim().isEmpty())
                    ? serverUrlRef[0].getText().toString()
                    : DEFAULT_SERVER_URL;
            runAsync("正在注册...", () -> {
                apiClient.registerByEmail(serverUrl, username, email, password, verificationCode, inviteCode);
                return null;
            }, _unused -> {
                toast("注册成功，请登录");
                showLogin();
            });
        });
    }

    // ============ 忘记密码页 ============

    private void showForgotPassword() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setGravity(Gravity.CENTER);
        page.setPadding(dp(24), dp(28), dp(24), dp(28));
        page.setBackgroundColor(0xFFF4F7FB);
        scroll.addView(page, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.MATCH_PARENT));
        setRootContent(scroll);

        LinearLayout card = card();
        card.setPadding(dp(22), dp(24), dp(22), dp(24));
        page.addView(card, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView brand = heading("重置密码");
        TextView subtitle = muted("输入注册邮箱，发送验证码后重置密码");

        EditText forgotEmail = input("注册邮箱", false);
        EditText forgotCaptcha = input("邮箱验证码（5分钟内有效）", false);
        forgotCaptcha.setInputType(InputType.TYPE_CLASS_NUMBER);
        forgotCaptcha.setFilters(new InputFilter[]{new InputFilter.LengthFilter(6)});
        Button forgotSendBtn = outlineButton("发送验证码", 0xFF2563EB);
        EditText forgotNewPassword = input("新密码（至少 6 位）", true);
        EditText forgotConfirmPassword = input("确认新密码", true);
        Button forgotResetBtn = primaryButton("重置密码");
        progress = progressBar();
        statusView = muted("");
        TextView forgotBack = helpText("返回登录");

        card.addView(brandLogo());
        card.addView(brand);
        card.addView(subtitle);
        card.addView(spacer(6));
        card.addView(forgotEmail);
        card.addView(forgotSendBtn);
        card.addView(forgotCaptcha);
        card.addView(forgotNewPassword);
        card.addView(forgotConfirmPassword);
        card.addView(forgotResetBtn);
        card.addView(progress);
        card.addView(statusView);
        card.addView(forgotBack);

        // 初始隐藏：验证码输入框、新密码输入框、确认密码、重置按钮
        forgotCaptcha.setVisibility(View.GONE);
        forgotNewPassword.setVisibility(View.GONE);
        forgotConfirmPassword.setVisibility(View.GONE);
        forgotResetBtn.setVisibility(View.GONE);

        // 服务地址高级折叠
        final EditText[] serverUrlRef = new EditText[1];
        final int[] advancedIndex = new int[1];
        TextView advancedToggle = helpText("▸ 高级");
        advancedToggle.setTextColor(0xFF2563EB);
        advancedIndex[0] = card.indexOfChild(forgotSendBtn);
        card.addView(advancedToggle, advancedIndex[0]);

        advancedToggle.setOnClickListener(v -> {
            if (serverUrlRef[0] == null) {
                serverUrlRef[0] = input("服务地址", false);
                serverUrlRef[0].setText(DEFAULT_SERVER_URL);
                int idx = card.indexOfChild(advancedToggle);
                card.addView(serverUrlRef[0], idx + 1);
                advancedToggle.setText("▾ 高级");
            } else {
                card.removeView(serverUrlRef[0]);
                serverUrlRef[0] = null;
                advancedToggle.setText("▸ 高级");
            }
        });

        forgotBack.setOnClickListener(v -> showLogin());

        /* 步骤1：发送验证码 */
        forgotSendBtn.setOnClickListener(v -> {
            String email = forgotEmail.getText().toString().trim();
            if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                toast("请输入有效的邮箱地址");
                return;
            }
            String serverUrl = (serverUrlRef[0] != null && !serverUrlRef[0].getText().toString().trim().isEmpty())
                    ? serverUrlRef[0].getText().toString()
                    : DEFAULT_SERVER_URL;
            runAsync("正在发送验证码...", () -> {
                apiClient.sendVerificationCode(serverUrl, email, "reset_password");
                return null;
            }, _unused -> {
                // 验证码发送成功，展示验证码输入和新密码输入区域
                forgotCaptcha.setVisibility(View.VISIBLE);
                forgotNewPassword.setVisibility(View.VISIBLE);
                forgotConfirmPassword.setVisibility(View.VISIBLE);
                forgotResetBtn.setVisibility(View.VISIBLE);
                toast("如果该邮箱已注册，验证码将发送到邮箱，请注意查收");
            });
        });

        /* 步骤2：重置密码 */
        forgotResetBtn.setOnClickListener(v -> {
            String email = forgotEmail.getText().toString().trim();
            String captcha = forgotCaptcha.getText().toString().trim();
            String newPassword = forgotNewPassword.getText().toString();
            String confirmPassword = forgotConfirmPassword.getText().toString();

            if (!VerificationCodeValidator.isValidEmailCode(captcha)) { toast("请输入6位数字邮箱验证码"); return; }
            if (!PasswordValidator.isValid(newPassword)) {
                toast("新密码至少6位，且必须同时包含英文字母和数字"); return;
            }
            if (!confirmPassword.equals(newPassword)) { toast("两次输入的密码不一致"); return; }

            String serverUrl = (serverUrlRef[0] != null && !serverUrlRef[0].getText().toString().trim().isEmpty())
                    ? serverUrlRef[0].getText().toString()
                    : DEFAULT_SERVER_URL;
            runAsync("正在重置密码...", () -> {
                apiClient.resetPassword(serverUrl, email, captcha, newPassword);
                return null;
            }, _unused -> {
                toast("密码重置成功，请登录");
                showLogin();
            });
        });
    }

    // ============ 主框架 ============

    private void showApp(int page) {
        currentPage = page;
        // 离开登录页，释放登录输入框引用（B5 高亮不再需要）。
        loginIdentifier = null;
        loginPassword = null;
        LinearLayout screen = new LinearLayout(this);
        screen.setOrientation(LinearLayout.VERTICAL);
        screen.setBackgroundColor(0xFFF4F7FB);

        ScrollView scroll = new ScrollView(this);
        contentRoot = new LinearLayout(this);
        contentRoot.setOrientation(LinearLayout.VERTICAL);
        contentRoot.setPadding(dp(18), dp(20), dp(18), dp(14));
        scroll.addView(contentRoot);
        screen.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        screen.addView(bottomNav());
        setRootContent(screen);
        renderCurrentPage();
    }

    /**
     * Android 15（targetSdk 35）强制 edge-to-edge；系统栏不再自动为 View 布局留空。
     * 每个页面根布局都按真实状态栏/导航栏 inset 留白，避免内容落在刘海、状态栏
     * 或手势导航区域下方。根布局本身保留背景色，系统栏区域视觉上仍与页面连续。
     */
    private void setRootContent(View root) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            root.setOnApplyWindowInsetsListener((view, windowInsets) -> {
                Insets bars = windowInsets.getInsets(WindowInsets.Type.systemBars()
                        | WindowInsets.Type.displayCutout());
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
                return windowInsets;
            });
        }
        setContentView(root);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            root.requestApplyInsets();
        }
    }

    private void renderCurrentPage() {
        if (contentRoot == null) return;
        // 连接时长 ticker：仅在 CONNECTED + 连接页时运行，离开页面或断开时自动停止。
        mainHandler.removeCallbacks(connectedDurationTicker);
        boolean pageRebuilt = true;
        if (currentPage == PAGE_CONNECT) {
            pageRebuilt = renderConnectPage();
        } else if (currentPage == PAGE_DEVICES) {
            // 离开连接页后 contentRoot 被设备页重建，连接页的 TextView 引用失效；
            // 清空签名强制下次回到连接页时整页重建，避免局部刷新操作到野引用。
            connectPageSignature = "";
            renderDevicesPage();
        } else {
            connectPageSignature = "";
            renderProfilePage();
        }
        if (TunnelState.CONNECTED.equals(currentTunnelState) && currentPage == PAGE_CONNECT) {
            mainHandler.postDelayed(connectedDurationTicker, 1000L);
        }
        // 连接页签名未变化时只做局部文本刷新，不应再次追加 View。
        if (pageRebuilt) {
            contentRoot.addView(spacer(72));
        }
    }

    // ============ 连接页 ============

    /** @return true 表示本次重建了页面结构，需要调用方追加一次底部留白。 */
    private boolean renderConnectPage() {
        String signature = connectPageSignature();
        if (signature.equals(connectPageSignature)) {
            // 结构未变（仅时长/流量数值变化）：原地更新动态文本，不重建页面。
            updateConnectDynamicTexts();
            return false;
        }
        // 结构变化：整页重建，并把动态 TextView 引用存到字段供后续局部刷新。
        durationView = null;
        trafficDownView = null;
        trafficUpView = null;
        latencyView = null;
        int prevColor = lastConnectBtnColor;
        connectBtn = null;
        connectBtnColor = 0;
        contentRoot.removeAllViews();
        contentRoot.addView(topBar("连接", "手机端主动连接", false));
        contentRoot.addView(targetCard());
        contentRoot.addView(connectButton());
        contentRoot.addView(trafficCard());
        connectPageSignature = signature;
        // B1：状态切换导致按钮变色时，跑 160ms 颜色过渡（首帧 prevColor=0 跳过）。
        animateConnectBtnColor(prevColor);
        return true;
    }

    /**
     * 把按钮背景从 prevColor 过渡到当前 {@link #connectBtnColor}。
     * prevColor=0 表示首帧或无上一帧，直接设为目标色不动画。
     */
    private void animateConnectBtnColor(int prevColor) {
        if (connectBtn == null || connectBtnColor == 0) return;
        android.graphics.drawable.Drawable bg = connectBtn.getBackground();
        if (!(bg instanceof android.graphics.drawable.GradientDrawable)) return;
        final android.graphics.drawable.GradientDrawable gd = (android.graphics.drawable.GradientDrawable) bg;
        gd.setColor(prevColor != 0 ? prevColor : connectBtnColor);
        lastConnectBtnColor = connectBtnColor;
        if (prevColor == 0 || prevColor == connectBtnColor) return;
        android.animation.ValueAnimator anim = android.animation.ValueAnimator.ofArgb(prevColor, connectBtnColor);
        anim.setDuration(160);
        anim.setEvaluator(new android.animation.ArgbEvaluator());
        anim.addUpdateListener(a -> gd.setColor((int) a.getAnimatedValue()));
        anim.start();
    }

    /**
     * 连接页「结构签名」：任一因子变化都需要整页重建（状态机分支、选中设备、
     * 在线情况、对端虚拟 IP / 可访问网段的有无、失败/取消文案的有无）。
     * 已连接时长与 rx/tx 流量数值刻意不进签名——它们每秒变化，走局部刷新。
     */
    private String connectPageSignature() {
        DeviceItem target = selectedDevice();
        return currentTunnelState
                + "|" + (target == null ? 0 : target.id)
                + "|" + (target == null ? "" : target.status)
                + "|" + (target == null ? "" : target.publicIpLocation)
                + "|" + presenceState
                + "|" + (peerVirtualIp.isEmpty() ? 0 : 1)
                + "|" + (exposedLan.isEmpty() ? 0 : 1)
                + "|" + (speedTesting ? 1 : 0)
                + "|" + (hasFailureMessage() ? 1 : 0)
                + "|" + (TunnelState.DEGRADED.equals(currentTunnelState)
                ? currentTunnelMessage : "");
    }

    /** 失败/取消状态下是否有需要展示的文案。 */
    private boolean hasFailureMessage() {
        return !currentTunnelMessage.isEmpty()
                && (TunnelState.FAILED.equals(currentTunnelState)
                || TunnelState.ABORTED.equals(currentTunnelState));
    }

    /** 仅更新时长与流量文本，由 renderConnectPage 在签名稳定时调用。 */
    private void updateConnectDynamicTexts() {
        if (durationView != null && connectedAtMs > 0) {
            durationView.setText("已连接 " + formatDuration(System.currentTimeMillis() - connectedAtMs));
        }
        boolean connected = TunnelState.CONNECTED.equals(currentTunnelState);
        if (trafficDownView != null) {
            trafficDownView.setText("⬇ 下载  " + (connected ? formatBytes(rxBytes) : "—"));
        }
        if (trafficUpView != null) {
            trafficUpView.setText("⬆ 上传  " + (connected ? formatBytes(txBytes) : "—"));
        }
        if (latencyView != null) {
            latencyView.setText("延迟  " + (connected && tunnelLatencyMs >= 0
                    ? tunnelLatencyMs + " ms" : "—"));
        }
    }

    /** 当前选中的目标设备，null 表示未选中或设备已不在列表中。 */
    private DeviceItem selectedDevice() {
        if (selectedDeviceId <= 0) return null;
        for (DeviceItem device : cachedDevices) {
            if (device.id == selectedDeviceId && device.canAcceptP2P()) return device;
        }
        return null;
    }

    /** 连接页顶部目标设备卡片（纯展示，不可点击）。 */
    private View targetCard() {
        LinearLayout card = card();
        DeviceItem target = selectedDevice();
        if (target == null) {
            card.addView(body("未选择目标设备"));
            card.addView(helpText("请到「设备」页选择要连接的设备"));
            return card;
        }
        LinearLayout row = horizontal();
        TextView name = body(target.displayName().isEmpty() ? "未命名设备" : target.displayName());
        name.setTextSize(18);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        row.addView(name, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        row.addView(statusChip(isOnline(target) ? "在线" : "离线",
                isOnline(target) ? 0xFF16A34A : 0xFF64748B));
        card.addView(row);
        // B3：连接页只保留名称+状态+类型，详细 IP/位置信息留在「设备」页（避免重复展示）。
        card.addView(helpText(target.type.isEmpty() ? "切换设备请到「设备」页" : target.type));
        return card;
    }

    /** 连接/断开按钮，状态由当前隧道状态 + 目标设备在线情况决定。 */
    private View connectButton() {
        DeviceItem target = selectedDevice();
        boolean connected = TunnelState.CONNECTED.equals(currentTunnelState);
        boolean active = TunnelState.isActive(currentTunnelState);

        String text;
        int bgColor, txtColor;
        android.view.View.OnClickListener listener;
        boolean enabled;

        if (connected) {
            text = "断开连接";
            bgColor = 0xFFDC2626; txtColor = 0xFFFFFFFF;
            listener = v -> stopTunnel();
            enabled = true;
        } else if (TunnelState.DEGRADED.equals(currentTunnelState)) {
            text = "取消恢复";
            bgColor = 0xFFDC2626; txtColor = 0xFFFFFFFF;
            listener = v -> stopTunnel();
            enabled = true;
        } else if (active) {
            text = "建立中...";
            bgColor = 0xFFE2E8F0; txtColor = 0xFF94A3B8;
            listener = v -> stopTunnel();
            enabled = false;
        } else if (target == null) {
            text = "请先选择设备";
            bgColor = 0xFFE2E8F0; txtColor = 0xFF94A3B8;
            listener = null;
            enabled = false;
        } else if (!isOnline(target)) {
            text = "设备离线";
            bgColor = 0xFFE2E8F0; txtColor = 0xFF94A3B8;
            listener = null;
            enabled = false;
        } else {
            text = "建立连接";
            bgColor = 0xFF2563EB; txtColor = 0xFFFFFFFF;
            listener = v -> startTunnelFlow(target);
            enabled = true;
        }

        Button btn = styledButton(text, bgColor, txtColor, 14);
        btn.setEnabled(enabled);
        if (listener != null) {
            btn.setOnClickListener(listener);
        }
        // B1：暴露按钮与其目标色，供 renderConnectPage 跑颜色过渡动画。
        connectBtn = btn;
        connectBtnColor = bgColor;

        // CONNECTED 时，按钮上方展示状态摘要 + 已连接时长 + 虚拟 IP
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        if (connected) {
            LinearLayout statusRow = horizontal();
            statusRow.addView(statusChip("已连接", 0xFF16A34A),
                    new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT));
            if (connectedAtMs > 0) {
                durationView = body("已连接 " + formatDuration(System.currentTimeMillis() - connectedAtMs));
                durationView.setPadding(dp(10), 0, 0, 0);
                statusRow.addView(durationView);
            }
            latencyView = body("延迟  " + (tunnelLatencyMs >= 0
                    ? tunnelLatencyMs + " ms" : "—"));
            latencyView.setPadding(dp(10), 0, 0, 0);
            statusRow.addView(latencyView);
            wrap.addView(statusRow);
            if (!peerVirtualIp.isEmpty()) {
                TextView vip = monoAddress(peerVirtualIp);
                vip.setOnClickListener(v -> copyText("对端虚拟 IP", peerVirtualIp));
                wrap.addView(vip);
            }
            if (!exposedLan.isEmpty()) {
                wrap.addView(helpText("可访问网段：" + exposedLan));
            }
        } else if (!currentTunnelMessage.isEmpty()
                && (TunnelState.FAILED.equals(currentTunnelState)
                || TunnelState.ABORTED.equals(currentTunnelState)
                || TunnelState.DEGRADED.equals(currentTunnelState))) {
            wrap.addView(muted(currentTunnelMessage));
        }
        wrap.addView(btn);
        return wrap;
    }

    /** 流量统计卡片。CONNECTED 时显示实时下载/上传，其他状态显示占位。 */
    private View trafficCard() {
        LinearLayout card = card();
        card.addView(sectionTitle("流量统计"));
        boolean connected = TunnelState.CONNECTED.equals(currentTunnelState);
        LinearLayout row = horizontal();
        trafficDownView = body("⬇ 下载  " + (connected ? formatBytes(rxBytes) : "—"));
        trafficDownView.setTextColor(0xFF1E40AF);
        trafficUpView = body("⬆ 上传  " + (connected ? formatBytes(txBytes) : "—"));
        trafficUpView.setTextColor(0xFF1E40AF);
        trafficUpView.setPadding(dp(20), 0, 0, 0);
        row.addView(trafficDownView);
        row.addView(trafficUpView);
        card.addView(row);
        if (connected) {
            Button speed = outlineButton(speedTesting ? "测速中…" : "隧道测速", 0xFF2563EB);
            speed.setEnabled(!speedTesting);
            speed.setOnClickListener(v -> startSpeedTest());
            speed.setPadding(0, dp(8), 0, 0);
            card.addView(speed);
        }
        return card;
    }

    // ============ 设备页 ============

    private void renderDevicesPage() {
        contentRoot.removeAllViews();

        // B2：本机置顶 + 远端可连接列表分离。本机不可被选为连接目标。
        DeviceItem self = currentDevice();
        List<DeviceItem> remote = new ArrayList<>();
        int remoteOnline = 0;
        for (DeviceItem device : cachedDevices) {
            if (isSelf(device)) continue;
            remote.add(device);
            if (isOnline(device)) remoteOnline++;
        }
        int totalRemote = remote.size();
        String subtitle = "共 " + totalRemote + " 台设备，" + remoteOnline + " 台在线";
        contentRoot.addView(topBar("设备", subtitle, true));

        // 本机设备卡片（置顶、独立浅蓝底、可改别名、不可选为目标）
        if (self != null) {
            contentRoot.addView(selfDeviceCard(self));
        }

        contentRoot.addView(sectionTitle("点击选择连接目标"));

        // 远端设备：在线排前，离线排后，点击仅选中（不建链、不跳转）
        remote.sort((a, b) -> {
            int sa = isOnline(a) ? 0 : 1;
            int sb = isOnline(b) ? 0 : 1;
            return Integer.compare(sa, sb);
        });
        for (DeviceItem device : remote) {
            contentRoot.addView(selectableDeviceCard(device));
        }
        if (remote.isEmpty()) {
            contentRoot.addView(emptyCard("暂无可连接设备"));
        }
    }

    /**
     * 本机设备卡片（B2）：浅蓝底 + brand 描边置顶，展示「本机」标识、别名/名称、
     * 在线状态，并提供「改别名」入口（复用 promptForAlias）。不可被选为连接目标。
     */
    private View selfDeviceCard(DeviceItem self) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        card.setBackground(borderRect(0xFFEFF6FF, 18, 0xFF2563EB, dp(1)));
        card.setElevation(dp(1));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(12), 0, 0);
        card.setLayoutParams(lp);

        LinearLayout row = horizontal();
        TextView name = body(self.alias.isEmpty()
                ? (self.name.isEmpty() ? "本机设备" : self.name)
                : self.alias);
        name.setTextSize(17);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        row.addView(name, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        row.addView(statusChip("本机", 0xFF2563EB));
        card.addView(row);
        card.addView(helpText(self.type.isEmpty() ? "本机设备" : self.type + " · 本机"));
        if (!self.systemVersion.isEmpty()) {
            card.addView(helpText("系统版本：" + self.systemVersion));
        }
        if (!self.clientVersion.isEmpty()) {
            card.addView(helpText("客户端版本：" + self.clientVersion));
        }

        TextView editAlias = helpText("点击修改别名");
        editAlias.setTextColor(0xFF2563EB);
        editAlias.setPadding(0, dp(6), 0, 0);
        editAlias.setOnClickListener(v -> promptForAlias(self.alias));
        card.addView(editAlias);
        return card;
    }

    /**
     * 设备页可选中卡片：点击仅标记 selectedDeviceId（蓝色框线 + 浅蓝背景），
     * 不跳转、不建链。选中状态持久化到 SessionStore。
     */
    private View selectableDeviceCard(DeviceItem device) {
        boolean canAcceptP2P = device.canAcceptP2P();
        boolean selected = canAcceptP2P && device.id == selectedDeviceId;
        // 选中：蓝色框线 + 浅蓝背景；未选中：浅灰框线 + 白底
        int bgColor = selected ? 0xFFEFF6FF : 0xFFFFFFFF;
        int borderColor = selected ? 0xFF2563EB : 0xFFE2E8F0;
        int borderWidth = selected ? dp(2) : dp(1);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        card.setBackground(borderRect(bgColor, 18, borderColor, borderWidth));
        card.setElevation(dp(1));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(12), 0, 0);
        card.setLayoutParams(lp);

        LinearLayout row = horizontal();
        TextView name = body(device.displayName().isEmpty() ? "未命名设备" : device.displayName());
        name.setTextSize(17);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        row.addView(name, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        row.addView(statusChip(isOnline(device) ? "在线" : "离线",
                isOnline(device) ? 0xFF16A34A : 0xFF64748B));
        card.addView(row);
        card.addView(helpText(device.type + " · ID " + device.id));
        if (!device.systemVersion.isEmpty()) {
            card.addView(helpText("系统版本：" + device.systemVersion));
        }
        if (!device.clientVersion.isEmpty()) {
            card.addView(helpText("客户端版本：" + device.clientVersion));
        }
        if (!canAcceptP2P) {
            card.addView(helpText("该设备不能作为连接目标"));
        }
        card.addView(helpText("公网 IP：" + emptyAsUnknown(device.publicIp)));
        if (!device.publicIpLocation.isEmpty()) {
            card.addView(helpText("IP 位置：" + device.publicIpLocation));
        }

        card.setClickable(canAcceptP2P);
        if (canAcceptP2P) {
            applyPressFeedback(card);
            card.setOnClickListener(v -> {
                selectedDeviceId = device.id;
                sessionStore.setSelectedDeviceId(device.id);
                renderCurrentPage();
            });
        } else {
            card.setAlpha(0.65f);
        }
        return card;
    }

    // ============ 我的页 ============

    private void renderProfilePage() {
        contentRoot.removeAllViews();
        contentRoot.addView(topBar("我的", null, false));

        Session session = sessionStore.load();

        // 账号
        LinearLayout account = card();
        account.addView(body(session == null || session.username.isEmpty() ? "已登录" : session.username));
        account.addView(helpText("当前账号"));
        if (session != null) {
            String membership;
            if ("pro".equalsIgnoreCase(session.memberLevel)) {
                membership = "Pro 会员";
            } else if (session.isPro) {
                membership = "Pro 试用";
            } else if ("free".equalsIgnoreCase(session.memberLevel)) {
                membership = "免费会员";
            } else {
                membership = session.memberLevel;
            }
            if (membership == null || membership.trim().isEmpty()) membership = "免费会员";
            account.addView(helpText("会员状态：" + membership));
            if (session.isPro && !session.trialExpireTime.isEmpty()
                    && !"pro".equalsIgnoreCase(session.memberLevel)) {
                account.addView(helpText("试用到期：" + session.trialExpireTime
                        + (session.trialRemainingDays > 0
                        ? "（剩余 " + session.trialRemainingDays + " 天）" : "")));
            } else if (!session.memberExpireTime.isEmpty()) {
                account.addView(helpText("会员到期：" + session.memberExpireTime));
            }
        }
        contentRoot.addView(account);

        // 本机设备别名（可编辑）
        DeviceItem self = currentDevice();
        LinearLayout aliasCard = card();
        String alias = self == null ? "" : self.alias;
        TextView aliasValue = body(alias.isEmpty() ? "未设置" : alias);
        aliasValue.setTextColor(alias.isEmpty() ? 0xFF94A3B8 : 0xFF0F172A);
        aliasCard.addView(aliasValue);
        aliasCard.addView(helpText("本机设备别名"));
        TextView setAlias = helpText("点击修改");
        setAlias.setTextColor(0xFF2563EB);
        setAlias.setPadding(0, dp(6), 0, 0);
        setAlias.setOnClickListener(v -> promptForAlias(alias));
        aliasCard.addView(setAlias);
        contentRoot.addView(aliasCard);

        // 设备 UUID（原为设备 ID）
        LinearLayout devUuid = card();
        TextView uuidView = body(session == null || session.deviceUuid.isEmpty()
                ? "—" : session.deviceUuid);
        uuidView.setTypeface(Typeface.MONOSPACE);
        uuidView.setTextSize(14);
        uuidView.setTextColor(0xFF334155);
        uuidView.setOnClickListener(v -> {
            if (session != null && !session.deviceUuid.isEmpty()) {
                copyText("设备 UUID", session.deviceUuid);
            }
        });
        devUuid.addView(uuidView);
        devUuid.addView(helpText("设备 UUID（点击复制）"));
        contentRoot.addView(devUuid);

        LinearLayout versionCard = card();
        versionCard.addView(body("v" + BuildConfig.VERSION_NAME));
        versionCard.addView(helpText(getString(R.string.current_version)));
        Button checkUpdate = outlineButton(getString(R.string.check_for_updates), 0xFF2563EB);
        LinearLayout.LayoutParams checkUpdateParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        checkUpdateParams.topMargin = dp(8);
        versionCard.addView(checkUpdate, checkUpdateParams);
        contentRoot.addView(versionCard);
        checkUpdate.setOnClickListener(view -> checkVersionManually());

        Button logout = outlineButton("退出登录", 0xFFB91C1C);
        logout.setOnClickListener(v -> confirmLogout());
        contentRoot.addView(logout);
    }

    private void checkVersionManually() {
        Session session = sessionStore.load();
        if (session == null) return;
        runAsync(getString(R.string.checking_for_updates),
                () -> apiClient.getClientVersionPolicy(session.serverUrl),
                policy -> {
                    if (policy.requiresForceUpdate(BuildConfig.VERSION_NAME)) {
                        showForceUpdate(policy);
                    } else if (policy.hasUpdate(BuildConfig.VERSION_NAME)) {
                        showOptionalUpdate(policy);
                    } else {
                        toast(getString(R.string.already_latest));
                    }
                });
    }

    /**
     * 退出登录二次确认（B6）。退出会断开隧道并清除本机登录态，属不可逆操作，
     * 用确认对话框避免误点。
     */
    private void confirmLogout() {
        new android.app.AlertDialog.Builder(this)
                .setTitle("退出登录")
                .setMessage("退出登录会断开当前隧道并清除本机登录态，确定退出?")
                .setPositiveButton("退出", (d, w) -> performLogout())
                .setNegativeButton("取消", null)
                .show();
    }

    /** 执行退出登录的全部清理：停隧道/停保活/清会话与 UI 状态/回登录页。 */
    private void performLogout() {
        // 已在执行的列表刷新/别名更新不能在清空会话后再改写当前页面。
        callbackGeneration++;
        stopTunnel();
        stopPresenceService();
        cachedDevices.clear();
        sessionStore.clear();
        selectedDeviceId = 0;
        currentTunnelState = TunnelState.IDLE;
        presenceState = PresenceService.STATE_DISCONNECTED;
        presenceWasConnected = false;
        presenceMessage = "";
        peerVirtualIp = "";
        exposedLan = "";
        connectedAtMs = 0;
        rxBytes = 0;
        txBytes = 0;
        loginIdentifier = null;
        loginPassword = null;
        showLogin();
    }

    /**
     * 弹出对话框让用户输入本机设备别名，确认后调用 PUT /devices/{id}/alias
     * （对齐桌面 update_device_alias），成功后刷新设备列表同步本地缓存。
     * 输入空白视为「清除别名」——但服务端要求 alias 非空，故空白时提示并阻止提交。
     */
    private void promptForAlias(String currentAlias) {
        Session session = sessionStore.load();
        if (session == null || session.deviceId <= 0) {
            toast("设备尚未注册");
            return;
        }
        final EditText input = new EditText(this);
        input.setHint("输入设备别名");
        input.setSingleLine(true);
        input.setText(currentAlias);
        input.setSelection(currentAlias.length());
        input.setPadding(dp(16), dp(12), dp(16), dp(12));
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(dp(8), dp(8), dp(8), dp(4));
        container.addView(input);

        android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(this)
                .setTitle("设置设备别名")
                .setView(container)
                .setPositiveButton("保存", null)   // 后置拦截，避免空值直接关闭
                .setNegativeButton("取消", (d, w) -> hideKeyboard(input))
                .create();
        dialog.setOnShowListener(d -> {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
                    .setOnClickListener(b -> {
                        String value = input.getText().toString().trim();
                        if (value.isEmpty()) {
                            toast("别名不能为空");
                            return;
                        }
                        hideKeyboard(input);
                        dialog.dismiss();
                        runAsync("正在保存别名...", () -> {
                            apiClient.updateDeviceAlias(session.deviceId, value);
                            return apiClient.listDevices();
                        }, devices -> {
                            cachedDevices.clear();
                            cachedDevices.addAll(devices);
                            renderCurrentPage();
                            toast("已保存");
                        });
                    });
        });
        dialog.show();
    }

    /**
     * 显式收起软键盘。AlertDialog.dismiss() 不保证回收 IME（尤其模拟器/部分 ROM），
     * 故在关闭含输入框的对话框前主动隐藏，避免键盘滞留底部。
     */
    private void hideKeyboard(View view) {
        android.view.inputmethod.InputMethodManager imm =
                (android.view.inputmethod.InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null && view != null) {
            imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
        }
    }

    // ============ 隧道流程 ============

    /**
     * 建立隧道入口：先请求 VPN 权限，用户同意后启动 WgvpnService。
     */
    private void startTunnelFlow(DeviceItem target) {
        // 单隧道约束
        if (TunnelState.isActive(currentTunnelState)) {
            toast("请先断开当前隧道");
            return;
        }
        pendingTarget = target;
        currentTunnelState = TunnelState.REQUESTING_VPN;
        currentTunnelMessage = "正在请求 VPN 权限";
        renderCurrentPage();

        Intent vpnIntent = VpnService.prepare(this);
        if (vpnIntent != null) {
            // 需要用户授权
            startActivityForResult(vpnIntent, VPN_PERMISSION_REQUEST);
        } else {
            // 已授权，直接启动
            startWgvpnService(target);
        }
    }

    private void startWgvpnService(DeviceItem target) {
        Intent intent = new Intent(this, WgvpnService.class);
        intent.setAction(WgvpnService.ACTION_START);
        intent.putExtra(WgvpnService.EXTRA_DEVICE_ID, target.id);
        intent.putExtra(WgvpnService.EXTRA_DEVICE_UUID, target.uuid);
        intent.putExtra(WgvpnService.EXTRA_DEVICE_NAME, target.displayName());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
        currentTunnelState = TunnelState.PREPARING;
        currentTunnelMessage = "已提交隧道任务";
        currentPage = PAGE_CONNECT;
        showApp(PAGE_CONNECT);
    }

    private void stopTunnel() {
        Intent intent = new Intent(this, WgvpnService.class);
        intent.setAction(WgvpnService.ACTION_STOP);
        startService(intent);
        currentTunnelState = TunnelState.STOPPED;
        currentTunnelMessage = "已请求断开隧道";
        peerVirtualIp = "";
        exposedLan = "";
        connectedAtMs = 0;
        rxBytes = 0;
        txBytes = 0;
        speedTesting = false;
        renderCurrentPage();
    }

    private void startSpeedTest() {
        if (!TunnelState.CONNECTED.equals(currentTunnelState) || speedTesting) return;
        speedTesting = true;
        renderCurrentPage();
        Intent intent = new Intent(this, WgvpnService.class);
        intent.setAction(WgvpnService.ACTION_TEST_SPEED);
        startService(intent);
    }

    // ============ 工具方法 ============

    private void refreshDevices(String message) {
        runAsync(message, () -> {
            apiClient.registerCurrentDevice(this);
            return apiClient.listDevices();
        }, devices -> {
            cachedDevices.clear();
            cachedDevices.addAll(devices);
            renderCurrentPage();
        });
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag") // API 29-32 分支由 signature permission 隔离。
    private void registerTunnelReceiver() {
        IntentFilter tunnelFilter = new IntentFilter(WgvpnService.ACTION_STATUS);
        tunnelFilter.addAction(WgvpnService.ACTION_SPEED_RESULT);
        IntentFilter presenceFilter = new IntentFilter(PresenceService.ACTION_STATUS);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(tunnelReceiver, tunnelFilter, InternalBroadcasts.PERMISSION,
                    mainHandler, Context.RECEIVER_NOT_EXPORTED);
            registerReceiver(presenceReceiver, presenceFilter, InternalBroadcasts.PERMISSION,
                    mainHandler, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(tunnelReceiver, tunnelFilter,
                    InternalBroadcasts.PERMISSION, mainHandler);
            registerReceiver(presenceReceiver, presenceFilter,
                    InternalBroadcasts.PERMISSION, mainHandler);
        }
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 101);
        }
    }

    /** 毫秒差值格式化为「X分Y秒」/「X秒」，用于展示已连接时长。 */
    private static String formatDuration(long durationMs) {
        if (durationMs < 0) durationMs = 0;
        long totalSec = durationMs / 1000;
        long min = totalSec / 60;
        long sec = totalSec % 60;
        if (min > 0) return min + " 分 " + sec + " 秒";
        return sec + " 秒";
    }

    /** 字节数格式化为人类可读（B/KB/MB/GB），保留 1 位小数。 */
    private static String formatBytes(long bytes) {
        if (bytes < 0) bytes = 0;
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format("%.1f KB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return String.format("%.1f MB", mb);
        return String.format("%.1f GB", mb / 1024.0);
    }

    // ============ UI 组件构造 ============

    private interface Task<T> { T run() throws Exception; }
    private interface Success<T> { void accept(T value); }

    private static final class ForceUpdateRequiredException extends Exception {
        final ClientVersionPolicy policy;

        ForceUpdateRequiredException(ClientVersionPolicy policy) {
            super("force update required");
            this.policy = policy;
        }
    }

    private <T> void runAsync(String loadingText, Task<T> task, Success<T> success) {
        final int generation = callbackGeneration;
        setBusy(true, loadingText);
        executor.execute(() -> {
            try {
                T result = task.run();
                mainHandler.post(() -> {
                    if (!acceptsCallback(generation)) return;
                    setBusy(false, "");
                    success.accept(result);
                });
            } catch (final Exception e) {
                android.util.Log.e("p2pRemote", "async failed", e);
                mainHandler.post(() -> {
                    if (!acceptsCallback(generation)) return;
                    if (e instanceof ForceUpdateRequiredException) {
                        setBusy(false, "");
                        showForceUpdate(((ForceUpdateRequiredException) e).policy);
                        return;
                    }
                    String friendly = friendlyError(e);
                    setBusy(false, friendly);
                    toast(friendly);
                    onAsyncFailed(e);
                });
            }
        });
    }

    private boolean acceptsCallback(int generation) {
        return !destroyed && !isFinishing() && !isDestroyed()
                && callbackGeneration == generation;
    }

    /**
     * 异步失败时的额外 UI 反馈（B5）：登录凭证错误时高亮输入框红框。
     * runAsync 是通用入口，此处按异常分类 + 当前是否在登录页决定是否高亮。
     */
    private void onAsyncFailed(Exception e) {
        if (classifyError(e) == ERR_CREDENTIALS) {
            if (loginIdentifier != null) setInputError(loginIdentifier);
            if (loginPassword != null) setInputError(loginPassword);
        }
    }

    // 错误分类（B5）
    private static final int ERR_NETWORK = 1;
    private static final int ERR_CREDENTIALS = 2;
    private static final int ERR_OTHER = 9;

    /** 把异常归类为网络/凭证/其它，用于决定文案与是否高亮字段。 */
    private static int classifyError(Exception e) {
        // ApiClient.ApiException 携带 httpStatus：401/403 视为凭证问题。
        if (e instanceof ApiClient.ApiException) {
            int http = ((ApiClient.ApiException) e).httpStatus;
            if (http == 401 || http == 403) return ERR_CREDENTIALS;
            return ERR_OTHER;
        }
        // IOException 视为网络/IO（含 OkHttp/HttpURLConnection 连接失败、超时）。
        if (e instanceof java.io.IOException) return ERR_NETWORK;
        return ERR_OTHER;
    }

    /**
     * 把异常映射成对用户友好的中文文案（不再露 IOException/ApiException 类名）。
     * 服务端原始 msg 作为凭证/参数错误的补充信息仅在可控场景出现。
     */
    private static String friendlyError(Exception e) {
        int kind = classifyError(e);
        if (kind == ERR_NETWORK) return "网络连接失败，请检查网络后重试";
        if (kind == ERR_CREDENTIALS) {
            return "用户名或密码错误";
        }
        // 其它：优先用服务端 msg（已是业务文案），否则通用兜底。
        String msg = e.getMessage();
        if (msg == null || msg.isEmpty()) return "操作失败，请稍后重试";
        // 去掉可能的异常类名前缀（如 "IllegalStateException: ..."）。
        int colon = msg.indexOf(": ");
        if (colon > 0 && colon < 40) msg = msg.substring(colon + 2);
        return msg.isEmpty() ? "操作失败，请稍后重试" : msg;
    }

    /** 把输入框描边改为错误红，用于 B5 字段高亮。 */
    private void setInputError(EditText edit) {
        edit.setBackground(borderRect(0xFFFFFFFF, 14, 0xFFDC2626, dp(1)));
    }

    /** 恢复输入框默认描边（登录重新提交前调用）。 */
    private void clearInputError(EditText edit) {
        edit.setBackground(roundRect(0xFFFFFFFF, 14, 0xFFCBD5E1));
    }

    private void setBusy(boolean busy, String text) {
        if (progress != null) progress.setVisibility(busy ? View.VISIBLE : View.GONE);
        if (statusView != null) statusView.setText(text == null ? "" : text);
    }

    private View topBar(String title, String subtitle, boolean refreshable) {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        LinearLayout row = horizontal();
        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        text.addView(heading(title));
        text.addView(muted(subtitle));
        row.addView(text, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        if (refreshable) {
            Button refresh = iconButton("⟳");
            refresh.setOnClickListener(v -> refreshDevices("正在刷新设备..."));
            row.addView(refresh, new LinearLayout.LayoutParams(dp(48), dp(48)));
        }
        wrap.addView(row);
        progress = progressBar();
        statusView = muted("");
        wrap.addView(progress);
        wrap.addView(statusView);
        return wrap;
    }

    private LinearLayout bottomNav() {
        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setPadding(dp(10), dp(8), dp(10), dp(8));
        nav.setBackgroundColor(0xFFFFFFFF);
        nav.addView(navButton(android.R.drawable.ic_menu_send, "连接", PAGE_CONNECT), new LinearLayout.LayoutParams(0, dp(56), 1));
        nav.addView(navButton(android.R.drawable.ic_menu_sort_by_size, "设备", PAGE_DEVICES), new LinearLayout.LayoutParams(0, dp(56), 1));
        nav.addView(navButton(android.R.drawable.ic_menu_myplaces, "我的", PAGE_PROFILE), new LinearLayout.LayoutParams(0, dp(56), 1));
        return nav;
    }

    private Button navButton(int iconRes, String text, int page) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(13);
        boolean selected = (currentPage == page);
        int tint = selected ? 0xFF2563EB : 0xFF64748B;
        button.setTextColor(tint);
        // 图标置于文字上方，按选中态着色；mutate 避免共享状态串色。
        android.graphics.drawable.Drawable icon = getDrawable(iconRes).mutate();
        icon.setTint(tint);
        button.setCompoundDrawablesRelativeWithIntrinsicBounds(null, icon, null, null);
        button.setCompoundDrawablePadding(dp(2));
        button.setBackground(roundRect(selected ? 0xFFE8F0FF : 0x00FFFFFF, 14, 0));
        applyPressFeedback(button);
        button.setOnClickListener(v -> showApp(page));
        return button;
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(16), dp(18), dp(16));
        card.setBackground(roundRect(0xFFFFFFFF, 18, 0xFFE2E8F0));
        card.setElevation(dp(1));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(12), 0, 0);
        card.setLayoutParams(lp);
        return card;
    }

    private View emptyCard(String text) {
        LinearLayout card = card();
        card.addView(muted(text));
        return card;
    }

    private LinearLayout horizontal() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        return row;
    }

    private ImageView brandLogo() {
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.app_logo);
        logo.setContentDescription(null);
        logo.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(76), dp(76));
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        lp.setMargins(0, 0, 0, dp(8));
        logo.setLayoutParams(lp);
        return logo;
    }

    private TextView heading(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(26);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setTextColor(0xFF0F172A);
        view.setPadding(0, 0, 0, dp(4));
        return view;
    }

    private TextView sectionTitle(String text) {
        TextView view = body(text);
        view.setTextSize(16);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setPadding(0, dp(20), 0, dp(2));
        return view;
    }

    private TextView body(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(16);
        view.setTextColor(0xFF0F172A);
        view.setPadding(0, dp(3), 0, dp(3));
        return view;
    }

    private TextView muted(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(14);
        view.setTextColor(0xFF64748B);
        view.setPadding(0, dp(2), 0, dp(2));
        return view;
    }

    private TextView helpText(String text) {
        TextView view = muted(text);
        view.setTextSize(13);
        view.setPadding(0, dp(8), 0, 0);
        return view;
    }

    private TextView monoAddress(String text) {
        TextView view = body(text);
        view.setTextSize(15);
        view.setTypeface(Typeface.MONOSPACE);
        view.setTextColor(0xFF1E3A8A);
        view.setPadding(dp(12), dp(12), dp(12), dp(12));
        view.setBackground(roundRect(0xFFEFF6FF, 12, 0xFFBFDBFE));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(8), 0, 0);
        view.setLayoutParams(lp);
        return view;
    }

    private EditText input(String hint, boolean password) {
        EditText edit = new EditText(this);
        edit.setHint(hint);
        edit.setSingleLine(true);
        edit.setTextSize(15);
        edit.setInputType(password
                ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD
                : InputType.TYPE_CLASS_TEXT);
        edit.setPadding(dp(14), 0, dp(14), 0);
        edit.setBackground(roundRect(0xFFFFFFFF, 14, 0xFFCBD5E1));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(52));
        lp.setMargins(0, dp(12), 0, 0);
        edit.setLayoutParams(lp);
        return edit;
    }

    private Button primaryButton(String text) { return styledButton(text, 0xFF2563EB, 0xFFFFFFFF, 14); }
    private Button dangerButton(String text) { return styledButton(text, 0xFFDC2626, 0xFFFFFFFF, 14); }
    /** B6：描边次要按钮（白底 + 彩色字 + 彩色描边），降低破坏性操作的视觉权重。 */
    private Button outlineButton(String text, int color) {
        Button button = styledButton(text, 0xFFFFFFFF, color, 14);
        button.setBackground(borderRect(0xFFFFFFFF, 14, color, dp(1)));
        return button;
    }
    private Button iconButton(String text) {
        Button button = styledButton(text, 0xFFE8EEF8, 0xFF1E3A8A, 14);
        button.setTextSize(20);
        return button;
    }

    private Button styledButton(String text, int background, int color, int radius) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(15);
        button.setTextColor(color);
        button.setBackground(roundRect(background, radius, 0));
        applyPressFeedback(button);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48));
        lp.setMargins(0, dp(12), 0, 0);
        button.setLayoutParams(lp);
        return button;
    }

    private TextView statusChip(String text, int color) {
        TextView chip = new TextView(this);
        chip.setText(text);
        chip.setTextSize(12);
        chip.setTypeface(Typeface.DEFAULT_BOLD);
        chip.setTextColor(color);
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(dp(10), dp(5), dp(10), dp(5));
        chip.setBackground(roundRect(chipBackground(color), 99, 0));
        return chip;
    }

    private ProgressBar progressBar() {
        ProgressBar bar = new ProgressBar(this);
        bar.setIndeterminate(true);
        bar.setVisibility(View.GONE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        lp.setMargins(0, dp(8), 0, 0);
        bar.setLayoutParams(lp);
        return bar;
    }

    private View spacer(int heightDp) {
        View view = new View(this);
        view.setLayoutParams(new LinearLayout.LayoutParams(1, dp(heightDp)));
        return view;
    }

    private GradientDrawable roundRect(int color, int radiusDp, int strokeColor) {
        return borderRect(color, radiusDp, strokeColor, dp(1));
    }

    /** 同 roundRect，但边框宽度（像素）可自定义，用于选中态加粗框线。 */
    private GradientDrawable borderRect(int color, int radiusDp, int strokeColor, int strokeWidthPx) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        if (strokeColor != 0 && strokeWidthPx > 0) {
            drawable.setStroke(strokeWidthPx, strokeColor);
        }
        return drawable;
    }

    private int chipBackground(int color) {
        if (color == 0xFF16A34A) return 0xFFDCFCE7;
        if (color == 0xFFDC2626 || color == 0xFFB91C1C) return 0xFFFEE2E2;
        if (color == 0xFFF59E0B) return 0xFFFEF3C7;
        if (color == 0xFF2563EB) return 0xFFDBEAFE;
        return 0xFFE2E8F0;
    }

    /**
     * 统一按压反馈：StateListAnimator（按下时 translationZ 抬升，走 RenderThread 不卡主线程）
     * + 系统 ripple 前景。覆盖所有按钮与可点卡片。
     * 纯 framework 实现（本项目无 AndroidX 依赖）：从当前主题解析
     * ?attr/selectableItemBackground 取出 ripple drawable 作为前景。
     * minSdk 29：View.setForeground 任意可用。
     */
    private void applyPressFeedback(View view) {
        view.setStateListAnimator(android.animation.AnimatorInflater
                .loadStateListAnimator(this, R.animator.button_press));
        android.util.TypedValue out = new android.util.TypedValue();
        if (getTheme().resolveAttribute(android.R.attr.selectableItemBackground, out, true)
                && out.resourceId != 0) {
            view.setForeground(getDrawable(out.resourceId));
        }
        view.setClickable(true);
    }

    // ============ 设备辅助 ============

    private DeviceItem currentDevice() {
        for (DeviceItem device : cachedDevices) {
            if (isSelf(device)) return device;
        }
        return null;
    }

    private boolean isSelf(DeviceItem device) {
        Session session = sessionStore.load();
        return session != null && device.id == session.deviceId;
    }

    private boolean isOnline(DeviceItem device) {
        return "online".equalsIgnoreCase(device.status);
    }

    private String emptyAsUnknown(String value) {
        return value == null || value.isEmpty() ? "未知" : value;
    }

    private String safe(String value) { return value == null ? "" : value; }

    private void copyText(String label, String text) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText(label, text));
            toast("已复制");
        }
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
