package top.p2premote.android;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

final class ApiClient {
    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 30_000;
    private static final long TOTAL_REQUEST_TIMEOUT_MS = 45_000L;
    private static final int VERSION_CONNECT_TIMEOUT_MS = 3_000;
    private static final int VERSION_READ_TIMEOUT_MS = 2_000;
    private static final long VERSION_TOTAL_TIMEOUT_MS = 5_000L;
    static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;
    private static final long TOKEN_REFRESH_SKEW_MS = 60_000L;
    /** 所有 ApiClient 实例共享一次 refresh，避免旋转型 refresh token 被并发消费。 */
    private static final Object REFRESH_LOCK = new Object();
    /** HttpURLConnection 没有 call timeout；定时 disconnect 为整个请求提供硬截止时间。 */
    private static final ScheduledExecutorService REQUEST_DEADLINE_EXECUTOR =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "p2premote-http-deadline");
                thread.setDaemon(true);
                return thread;
            });

    private final SessionStore sessionStore;

    ApiClient(SessionStore sessionStore) {
        this.sessionStore = sessionStore;
    }

    ClientVersionPolicy getClientVersionPolicy(String serverUrl) throws Exception {
        JSONObject data = requestWithTimeouts(
                serverUrl,
                "GET",
                "/api/v1/client/version-policy?target=android",
                null,
                null,
                VERSION_CONNECT_TIMEOUT_MS,
                VERSION_READ_TIMEOUT_MS,
                VERSION_TOTAL_TIMEOUT_MS);
        String latestVersion = data.optString("latest_version");
        String minSupportedVersion = data.optString("min_supported_version");
        if (latestVersion.isEmpty() || minSupportedVersion.isEmpty()) {
            throw new IOException("版本策略响应缺少版本号");
        }
        return new ClientVersionPolicy(
                latestVersion,
                minSupportedVersion,
                data.optString("release_notes"));
    }

    LoginResult login(String serverUrl, String identifier, String password) throws Exception {
        JSONObject req = new JSONObject();
        req.put("identifier", identifier);
        req.put("password", password);

        JSONObject data = request(serverUrl, "POST", "/api/v1/auth/login", req, null);
        String accessToken = data.optString("access_token");
        String refreshToken = data.optString("refresh_token");
        long expiresIn = data.optLong("expires_in", 0);
        JSONObject user = data.optJSONObject("user");
        String username = user == null ? identifier : user.optString("username", identifier);

        if (accessToken.isEmpty() || refreshToken.isEmpty()) {
            throw new IOException("登录响应缺少令牌");
        }

        Session session = new Session(
                normalizeServerUrl(serverUrl),
                accessToken,
                refreshToken,
                System.currentTimeMillis() + expiresIn * 1000L,
                username,
                user == null ? "" : user.optString("member_level"),
                user == null ? "" : user.optString("member_expire_time"),
                user == null ? "" : user.optString("trial_expire_time"),
                user == null ? 0 : user.optInt("trial_remaining_days", 0),
                user != null && user.optBoolean("is_pro", false),
                0,
                "",
                "",
                ""
        );
        sessionStore.save(session);
        return new LoginResult(session);
    }

    DeviceItem registerCurrentDevice(android.content.Context context) throws Exception {
        Session session = sessionStore.require();
        long accountGeneration = sessionStore.accountGeneration();
        JSONObject req = new JSONObject();
        req.put("device_name", DeviceIdentity.deviceName());
        req.put("device_type", "android");
        req.put("system_version", DeviceIdentity.systemVersion());
        req.put("client_version", BuildConfig.VERSION_NAME);
        req.put("device_uuid", DeviceIdentity.deviceUuid(context));
        req.put("lan_ip", "");
        req.put("public_ip", "");
        req.put("service_port", 0);
        req.put("rdp_enabled", false);
        req.put("remote_access", JSONObject.NULL);
		JSONObject wol = WolSupport.capability();
		req.put("capabilities", new JSONArray());
		if (wol != null) req.put("wol_capability", wol);

        JSONObject data = authedRequest(session, "POST", "/api/v1/devices", req);
        DeviceItem device = parseDevice(data);
        // 防止退出/重新登录后，旧请求把设备信息写入新的会话。
        sessionStore.updateDeviceIfCurrent(accountGeneration, device);
        return device;
    }

    List<DeviceItem> listDevices() throws Exception {
        Session session = sessionStore.require();
        return listDevicesWithSession(session);
    }

	String wakeDevice(long deviceId) throws Exception {
		JSONObject data=authedRequest(sessionStore.require(),"POST","/api/v1/devices/"+deviceId+"/wake",null);
		return data.optString("status","send_failed");
	}

    /**
     * 发起 P2P 隧道任务鉴权（新协议 p2p/open）。
     * 接口立即返回 connection_id/access_grant；后续打洞通过 WS p2p_notify 协商。
     * punch_token 不再由服务端返回，由客户端生成后通过 WS 发给被动端。
     */
    OpenResult openP2P(String clientJobId, DeviceItem target) throws Exception {
        Session session = sessionStore.require();
        if (session.deviceId <= 0 || session.deviceUuid.isEmpty()) {
            throw new IllegalStateException("本机设备尚未注册");
        }
        if (target.uuid.isEmpty()) {
            throw new IllegalStateException("目标设备缺少 UUID");
        }
        if (!target.canAcceptP2P()) {
            throw new IllegalStateException("Android 设备不能作为连接目标");
        }

        JSONObject req = new JSONObject();
        req.put("client_job_id", clientJobId);
        req.put("source_device_id", session.deviceId);
        req.put("source_device_uuid", session.deviceUuid);
        req.put("target_device_id", target.id);
        req.put("target_device_uuid", target.uuid);

        JSONObject data = authedRequest(session, "POST", "/api/v1/p2p/open", req);
        JSONObject targetObj = data.optJSONObject("target");
        int targetRdpPort = 3389;
        String targetRemoteProtocol = "rdp";
        JSONObject remoteAccess = data.optJSONObject("target_remote_access");
        if (targetObj != null) {
            targetRdpPort = targetObj.optInt("service_port", 3389);
            if (remoteAccess == null) {
                remoteAccess = targetObj.optJSONObject("remote_access");
            }
        }
        if (remoteAccess != null) {
            targetRemoteProtocol = remoteAccess.optString("protocol", "rdp");
            targetRdpPort = remoteAccess.optInt("port", targetRdpPort);
        }
        return new OpenResult(
                data.optString("connection_id"),
                data.optLong("log_id"),
                data.optString("access_grant"),
                targetRdpPort,
                targetRemoteProtocol,
                data.optLong("source_user_id"),
                data.optString("source_username"),
                data.optString("source_email")
        );
    }

    void reportP2PEnd(OpenResult opened, DeviceItem target, boolean success,
                      String errorCode, String errorMessage) throws Exception {
        reportP2PEnd(opened, target, success, "unknown", "unknown", errorCode, errorMessage);
    }

    void reportP2PEnd(OpenResult opened, DeviceItem target, boolean success,
                      String sourceNatType, String targetNatType,
                      String errorCode, String errorMessage) throws Exception {
        Session session = sessionStore.require();
        JSONObject req = new JSONObject();
        req.put("connection_id", opened.connectionId);
        req.put("log_id", opened.logId);
        req.put("success", success);
        req.put("source_device_id", session.deviceId);
        req.put("target_device_id", target.id);
        req.put("source_nat_type", sourceNatType == null || sourceNatType.isEmpty() ? "unknown" : sourceNatType);
        req.put("target_nat_type", targetNatType == null || targetNatType.isEmpty() ? "unknown" : targetNatType);
        // 字段名必须为 error_message（对齐服务端 json tag），历史误用 error_msg 导致上报丢失。
        req.put("error_message", errorMessage == null ? "" : errorMessage);
        // 结构化错误码（≤64 字符，可选），对齐桌面 classify_tunnel_error_code 白名单。
        req.put("error_code", errorCode == null ? "" : errorCode);
        authedRequest(session, "POST", "/api/v1/p2p/end", req);
    }

    private List<DeviceItem> listDevicesWithSession(Session session) throws Exception {
        JSONObject data = authedRequest(session, "GET", "/api/v1/devices", null);
        JSONArray arr = data.optJSONArray("_array");
        List<DeviceItem> devices = new ArrayList<>();
        if (arr == null) {
            return devices;
        }
        for (int i = 0; i < arr.length(); i++) {
            JSONObject item = arr.optJSONObject(i);
            if (item == null) {
                continue;
            }
            devices.add(parseDevice(item));
        }
        return devices;
    }

    /**
     * 更新本机设备别名（PUT /api/v1/devices/{id}/alias）。
     * 对齐桌面 device.rs::update_device_alias：仅 body {"alias": "..."}，Bearer 鉴权。
     * 服务端要求 alias 非空（binding:"required"）；传空串会 400。
     */
    void updateDeviceAlias(long deviceId, String alias) throws Exception {
        Session session = sessionStore.require();
        JSONObject req = new JSONObject();
        req.put("alias", alias);
        authedRequest(session, "PUT", "/api/v1/devices/" + deviceId + "/alias", req);
    }

    /**
     * 发送邮箱验证码（注册或忘记密码前调用）。
     * 服务端 client-api 不要求图片验证码，captcha_id/captcha 字段可省略。
     */
    void sendVerificationCode(String serverUrl, String email, String codeType) throws Exception {
        JSONObject req = new JSONObject();
        req.put("email", email);
        req.put("code_type", codeType);
        request(serverUrl, "POST", "/api/v1/auth/verification-code", req, null);
    }

    /** 邮箱验证码注册新用户。inviteCode 可选，传空串表示无邀请码。 */
    void registerByEmail(String serverUrl, String username, String email,
                         String password, String verificationCode, String inviteCode) throws Exception {
        JSONObject req = new JSONObject();
        req.put("username", username);
        req.put("email", email);
        req.put("password", password);
        req.put("verification_code", verificationCode);
        if (inviteCode != null && !inviteCode.isEmpty()) {
            req.put("invite_code", inviteCode);
        }
        request(serverUrl, "POST", "/api/v1/auth/register/email", req, null);
    }

    /**
     * 用邮箱验证码重置密码。
     */
    void resetPassword(String serverUrl, String email, String verificationCode,
                       String newPassword) throws Exception {
        JSONObject req = new JSONObject();
        req.put("email", email);
        req.put("verification_code", verificationCode);
        req.put("new_password", newPassword);
        request(serverUrl, "POST", "/api/v1/auth/reset-password", req, null);
    }

    private JSONObject authedRequest(Session session, String method, String path, JSONObject body) throws Exception {
        try {
            return request(session.serverUrl, method, path, body, session.accessToken);
        } catch (ApiException e) {
            if (e.httpStatus != HttpURLConnection.HTTP_UNAUTHORIZED) {
                throw e;
            }
            Session refreshed = refreshAfterUnauthorized(session);
            return request(refreshed.serverUrl, method, path, body, refreshed.accessToken);
        }
    }

    /** WebSocket 建连前调用：始终以 Store 最新值为准，并在临近过期时单飞刷新。 */
    Session ensureFreshSession() throws Exception {
        synchronized (REFRESH_LOCK) {
            Session current = sessionStore.require();
            if (current.accessTokenExpiresAt > System.currentTimeMillis() + TOKEN_REFRESH_SKEW_MS) {
                return current;
            }
            return refresh(current);
        }
    }

    private Session refreshAfterUnauthorized(Session failedSession) throws Exception {
        synchronized (REFRESH_LOCK) {
            Session current = sessionStore.require();
            // 另一个请求可能已经刷新成功；不要再次消费旧 refresh token。
            if (!current.accessToken.equals(failedSession.accessToken)) {
                return current;
            }
            return refresh(current);
        }
    }

    private Session refresh(Session session) throws Exception {
        JSONObject req = new JSONObject();
        req.put("refresh_token", session.refreshToken);

        JSONObject data = request(session.serverUrl, "POST", "/api/v1/auth/refresh", req, null);
        String accessToken = data.optString("access_token");
        String refreshToken = data.optString("refresh_token");
        long expiresIn = data.optLong("expires_in", 0);
        JSONObject user = data.optJSONObject("user");
        String username = user == null ? session.username : user.optString("username", session.username);
        String memberLevel = user == null
                ? session.memberLevel : user.optString("member_level", session.memberLevel);
        String memberExpireTime = user == null
                ? session.memberExpireTime : user.optString("member_expire_time", session.memberExpireTime);
        String trialExpireTime = user == null
                ? session.trialExpireTime : user.optString("trial_expire_time", session.trialExpireTime);
        int trialRemainingDays = user == null
                ? session.trialRemainingDays : user.optInt("trial_remaining_days", session.trialRemainingDays);
        boolean isPro = user == null ? session.isPro : user.optBoolean("is_pro", session.isPro);

        if (accessToken.isEmpty() || refreshToken.isEmpty()) {
            throw new IOException("刷新登录态失败：响应缺少令牌");
        }

        return sessionStore.updateTokensIfCurrent(
                session,
                accessToken,
                refreshToken,
                System.currentTimeMillis() + expiresIn * 1000L,
                username,
                memberLevel,
                memberExpireTime,
                trialExpireTime,
                trialRemainingDays,
                isPro);
    }

    private DeviceItem parseDevice(JSONObject item) {
        JSONObject remoteAccess = item.optJSONObject("remote_access");
        String remoteProtocol = remoteAccess == null ? "" : remoteAccess.optString("protocol");
        boolean remoteEnabled = remoteAccess != null && remoteAccess.optBoolean("enabled", false);
        int remotePort = remoteAccess == null ? item.optInt("service_port", 3389)
                : remoteAccess.optInt("port", item.optInt("service_port", 3389));
        return new DeviceItem(
                item.optLong("device_id"),
                item.optString("device_name"),
                item.optString("device_alias"),
                item.optString("device_type"),
                item.optString("device_uuid"),
                item.optString("status"),
                item.optString("public_ip"),
                item.optString("lan_ip"),
                item.optInt("service_port", 3389),
                item.optString("public_ip_location"),
                item.optString("system_version"),
                item.optString("client_version"),
                remoteProtocol,
                remoteEnabled,
				remotePort,
				item.optBoolean("wake_available", false)
        );
    }

    /**
     * 构造 punch_token，格式对齐桌面端 build_punch_token：
     *   {source}-{target}-{秒级时间戳}-{8位随机小写字母}
     * 新协议下 punch_token 由客户端生成，双方用相同 token 派生 gonc exchange topic。
     */
    static String buildPunchToken(long sourceDeviceId, long targetDeviceId) {
        return sourceDeviceId + "-" + targetDeviceId + "-" + nowEpochSeconds() + "-" + randomSuffix(8);
    }

    /** 构造 attempt_id：attempt-{target}-{n}-{ts}-{8随机小写}。 */
    static String buildAttemptId(long targetDeviceId, int attempt) {
        return "attempt-" + targetDeviceId + "-" + attempt + "-" + nowEpochSeconds() + "-" + randomSuffix(8);
    }

    /** 构造 client_job_id：job-{target}-{ts}-{8随机小写}。 */
    static String buildClientJobId(long targetDeviceId) {
        return "job-" + targetDeviceId + "-" + nowEpochSeconds() + "-" + randomSuffix(8);
    }

    /** 构造 WS p2p_notify 的 message_id：msg-{ts}-{8随机小写}。 */
    static String buildMessageId() {
        return "msg-" + nowEpochSeconds() + "-" + randomSuffix(8);
    }

    private static long nowEpochSeconds() {
        return System.currentTimeMillis() / 1000L;
    }

    private static String randomSuffix(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append((char) ('a' + (int) (Math.random() * 26)));
        }
        return sb.toString();
    }

    private JSONObject request(String serverUrl, String method, String path, JSONObject body, String bearerToken)
            throws Exception {
        return requestWithTimeouts(serverUrl, method, path, body, bearerToken,
                CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS, TOTAL_REQUEST_TIMEOUT_MS);
    }

    private JSONObject requestWithTimeouts(String serverUrl, String method, String path, JSONObject body,
                                            String bearerToken, int connectTimeoutMs, int readTimeoutMs,
                                            long totalTimeoutMs) throws Exception {
        long startedNanos = System.nanoTime();
        URL url = new URL(normalizeServerUrl(serverUrl) + path);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        AtomicBoolean deadlineExceeded = new AtomicBoolean(false);
        ScheduledFuture<?> deadline = REQUEST_DEADLINE_EXECUTOR.schedule(() -> {
            deadlineExceeded.set(true);
            conn.disconnect();
        }, totalTimeoutMs, TimeUnit.MILLISECONDS);
        try {
            conn.setConnectTimeout(connectTimeoutMs);
            conn.setReadTimeout(readTimeoutMs);
            conn.setRequestMethod(method);
            conn.setRequestProperty("Accept", "application/json");
            if (bearerToken != null && !bearerToken.isEmpty()) {
                conn.setRequestProperty("Authorization", "Bearer " + bearerToken);
            }
            if (body != null) {
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                conn.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream out = conn.getOutputStream()) {
                    out.write(bytes);
                }
            }

            int status = conn.getResponseCode();
            long declaredLength = conn.getContentLengthLong();
            if (declaredLength > MAX_RESPONSE_BYTES) {
                throw new IOException("服务端响应过大（上限 2 MiB）");
            }
            String raw = readFully(
                    status >= 400 ? conn.getErrorStream() : conn.getInputStream(), startedNanos, totalTimeoutMs);
            JSONObject envelope;
            try {
                envelope = new JSONObject(raw);
            } catch (JSONException e) {
                throw new IOException("服务端响应不是 JSON: HTTP " + status, e);
            }

            int code = envelope.optInt("code", -1);
            String msg = envelope.optString("msg", "请求失败");
            if (status >= 400 || code != 0) {
                throw new ApiException(status, code, msg);
            }

            Object data = envelope.opt("data");
            if (data instanceof JSONObject) {
                return (JSONObject) data;
            }
            if (data instanceof JSONArray) {
                JSONObject wrapper = new JSONObject();
                wrapper.put("_array", data);
                return wrapper;
            }
            return new JSONObject();
        } catch (IOException e) {
            if (deadlineExceeded.get()) {
                SocketTimeoutException timeout = new SocketTimeoutException("请求超时");
                timeout.initCause(e);
                throw timeout;
            }
            throw e;
        } finally {
            deadline.cancel(false);
            conn.disconnect();
        }
    }

    static String readFully(InputStream stream, long startedNanos) throws IOException {
        return readFully(stream, startedNanos, TOTAL_REQUEST_TIMEOUT_MS);
    }

    private static String readFully(InputStream stream, long startedNanos, long totalTimeoutMs) throws IOException {
        if (stream == null) {
            return "{}";
        }
        try (InputStream input = stream;
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos)
                        >= totalTimeoutMs) {
                    throw new SocketTimeoutException("请求超时");
                }
                if (output.size() > MAX_RESPONSE_BYTES - read) {
                    throw new IOException("服务端响应过大（上限 2 MiB）");
                }
                output.write(buffer, 0, read);
            }
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static String normalizeServerUrl(String serverUrl) {
        String trimmed = serverUrl == null ? "" : serverUrl.trim();
        if (trimmed.endsWith("/")) {
            return trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    static final class ApiException extends Exception {
        final int httpStatus;
        final int code;

        ApiException(int httpStatus, int code, String message) {
            super(message);
            this.httpStatus = httpStatus;
            this.code = code;
        }
    }

    static final class LoginResult {
        final Session session;

        LoginResult(Session session) {
            this.session = session;
        }
    }

    /** p2p/open 返回结果。新协议不再返回 punch_token（由客户端生成）。 */
    static final class OpenResult {
        final String connectionId;
        final long logId;
        final String accessGrant;
        final int targetRdpPort;
        final String targetRemoteProtocol;
        // 主动端身份：服务端按 Bearer token 解析后回填，需在 attempt_start 中回传给被动端，
        // 否则被动端弹窗显示「未知用户」（对齐桌面 P2PAttemptMessage::AttemptStart）。
        final long sourceUserId;
        final String sourceUsername;
        final String sourceEmail;

        OpenResult(String connectionId, long logId, String accessGrant, int targetRdpPort,
                   String targetRemoteProtocol,
                   long sourceUserId, String sourceUsername, String sourceEmail) {
            this.connectionId = connectionId == null ? "" : connectionId;
            this.logId = logId;
            this.accessGrant = accessGrant == null ? "" : accessGrant;
            this.targetRdpPort = targetRdpPort;
            this.targetRemoteProtocol = targetRemoteProtocol == null ? "rdp" : targetRemoteProtocol;
            this.sourceUserId = sourceUserId;
            this.sourceUsername = sourceUsername == null ? "" : sourceUsername;
            this.sourceEmail = sourceEmail == null ? "" : sourceEmail;
        }
    }
}
