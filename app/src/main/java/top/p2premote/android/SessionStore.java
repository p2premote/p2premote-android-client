package top.p2premote.android;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 会话持久化，基于 SharedPreferences。
 *
 * 已知限制（安全）：
 * - access_token / refresh_token / WG 私钥均为明文存储，与桌面端一致。
 *   root 设备或 ADB 备份导出可读取。
 * - 加固方案：迁到 EncryptedSharedPreferences（AndroidX Security）成本最低；
 *   AndroidKeyStore 不支持 x25519 原始密钥导出，不适用于 WG 私钥。
 * 当前作为一致性取舍，待安全需求提升时统一改造。
 */
final class SessionStore {
    /** 多个 Activity/Service 各自持有 SessionStore；所有复合更新必须跨实例串行。 */
    private static final Object STORE_LOCK = new Object();
    /** 仅登录替换或退出清理时递增；用于拒绝上一登录代际的迟到响应。 */
    private static long accountGeneration = 0;
    private static final String PREFS = "p2premote_session";
    private static final String SERVER_URL = "server_url";
    private static final String ACCESS_TOKEN = "access_token";
    private static final String REFRESH_TOKEN = "refresh_token";
    private static final String ACCESS_TOKEN_EXPIRES_AT = "access_token_expires_at";
    private static final String USERNAME = "username";
    private static final String DEVICE_ID = "device_id";
    private static final String DEVICE_UUID = "device_uuid";
    private static final String WG_PRIVATE_KEY = "wg_private_key_hex";
    private static final String WG_PUBLIC_KEY = "wg_public_key_hex";
    /** 设备页选中的目标设备 ID（0 = 未选中），持久化以便重启后保持选中。 */
    private static final String SELECTED_DEVICE_ID = "selected_device_id";

    private final SharedPreferences prefs;

    SessionStore(Context context) {
        this.prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** 读取选中的目标设备 ID，0 表示未选中。 */
    long getSelectedDeviceId() {
        return prefs.getLong(SELECTED_DEVICE_ID, 0);
    }

    /** 持久化选中的目标设备 ID。传 0 清除选中。 */
    void setSelectedDeviceId(long id) {
        prefs.edit().putLong(SELECTED_DEVICE_ID, id).apply();
    }

    Session load() {
        synchronized (STORE_LOCK) {
            return loadLocked();
        }
    }

    private Session loadLocked() {
        String accessToken = prefs.getString(ACCESS_TOKEN, "");
        String refreshToken = prefs.getString(REFRESH_TOKEN, "");
        if (accessToken == null || accessToken.isEmpty() || refreshToken == null || refreshToken.isEmpty()) {
            return null;
        }
        return new Session(
                prefs.getString(SERVER_URL, "https://cli.p2premote.top"),
                accessToken,
                refreshToken,
                prefs.getLong(ACCESS_TOKEN_EXPIRES_AT, 0),
                prefs.getString(USERNAME, ""),
                prefs.getLong(DEVICE_ID, 0),
                prefs.getString(DEVICE_UUID, ""),
                prefs.getString(WG_PRIVATE_KEY, ""),
                prefs.getString(WG_PUBLIC_KEY, "")
        );
    }

    Session require() {
        Session session = load();
        if (session == null) {
            throw new IllegalStateException("请先登录");
        }
        return session;
    }

    void save(Session session) {
        synchronized (STORE_LOCK) {
            saveLockedDurably(session);
            accountGeneration++;
        }
    }

    long accountGeneration() {
        synchronized (STORE_LOCK) {
            return accountGeneration;
        }
    }

    private void saveLocked(Session session) {
        editSession(session).apply();
    }

    /**
     * 登录和 refresh token 旋转必须在返回前落盘。服务端接受刷新后会立即废弃旧 refresh token；
     * 若使用 apply() 后进程崩溃，磁盘仍可能保留已经失效的旧令牌，导致下次启动被迫重新登录。
     * 调用点都位于 IO executor，不会阻塞主线程。
     */
    private void saveLockedDurably(Session session) {
        if (!editSession(session).commit()) {
            throw new IllegalStateException("登录凭据持久化失败");
        }
    }

    private SharedPreferences.Editor editSession(Session session) {
        return prefs.edit()
                .putString(SERVER_URL, session.serverUrl)
                .putString(ACCESS_TOKEN, session.accessToken)
                .putString(REFRESH_TOKEN, session.refreshToken)
                .putLong(ACCESS_TOKEN_EXPIRES_AT, session.accessTokenExpiresAt)
                .putString(USERNAME, session.username)
                .putLong(DEVICE_ID, session.deviceId)
                .putString(DEVICE_UUID, session.deviceUuid)
                .putString(WG_PRIVATE_KEY, session.wgPrivateKeyHex)
                .putString(WG_PUBLIC_KEY, session.wgPublicKeyHex);
    }

    Session updateDeviceIfCurrent(long expectedGeneration, DeviceItem device) {
        synchronized (STORE_LOCK) {
            Session current = loadLocked();
            if (current == null || accountGeneration != expectedGeneration) {
                throw new IllegalStateException("登录态已变化，丢弃迟到的设备注册结果");
            }
            Session updated = current.withDevice(device);
            saveLocked(updated);
            return updated;
        }
    }

    Session updateTokensIfCurrent(Session expected, String accessToken, String refreshToken,
                                  long expiresAt, String username) {
        synchronized (STORE_LOCK) {
            Session current = loadLocked();
            if (current == null
                    || !current.accessToken.equals(expected.accessToken)
                    || !current.refreshToken.equals(expected.refreshToken)) {
                throw new IllegalStateException("登录态已变化，丢弃迟到的刷新结果");
            }
            Session updated = current.withTokens(accessToken, refreshToken, expiresAt, username);
            saveLockedDurably(updated);
            return updated;
        }
    }

    /**
     * 只更新 WG 字段，避免用较早读取的 Session 快照覆盖另一线程刚旋转的登录令牌。
     * SharedPreferences.apply 会在返回前同步更新进程内视图，因此紧接着 load 可见新值。
     */
    Session updateWgKeypairIfCurrent(long expectedGeneration,
                                     String privateKeyHex, String publicKeyHex) {
        synchronized (STORE_LOCK) {
            Session current = loadLocked();
            if (current == null || accountGeneration != expectedGeneration) {
                throw new IllegalStateException("登录态已变化，丢弃迟到的 WG 密钥结果");
            }
            prefs.edit()
                    .putString(WG_PRIVATE_KEY, privateKeyHex)
                    .putString(WG_PUBLIC_KEY, publicKeyHex)
                    .apply();
            return loadLocked();
        }
    }

    void clear() {
        synchronized (STORE_LOCK) {
            accountGeneration++;
            prefs.edit().clear().apply();
        }
    }
}
