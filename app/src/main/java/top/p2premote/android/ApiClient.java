package top.p2premote.android;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

final class ApiClient {
    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 30_000;

    private final SessionStore sessionStore;

    ApiClient(SessionStore sessionStore) {
        this.sessionStore = sessionStore;
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
        JSONObject req = new JSONObject();
        req.put("device_name", DeviceIdentity.deviceName());
        req.put("device_type", "android");
        req.put("system_version", DeviceIdentity.systemVersion());
        req.put("device_uuid", DeviceIdentity.deviceUuid(context));
        req.put("lan_ip", "");
        req.put("public_ip", "");
        req.put("service_port", 0);
        req.put("rdp_enabled", false);

        JSONObject data = authedRequest(session, "POST", "/api/v1/devices", req);
        DeviceItem device = parseDevice(data);
        sessionStore.save(sessionStore.require().withDevice(device));
        return device;
    }

    List<DeviceItem> listDevices() throws Exception {
        Session session = sessionStore.require();
        return listDevicesWithSession(session);
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
        if (targetObj != null) {
            targetRdpPort = targetObj.optInt("service_port", 3389);
        }
        return new OpenResult(
                data.optString("connection_id"),
                data.optLong("log_id"),
                data.optString("access_grant"),
                targetRdpPort,
                data.optLong("source_user_id"),
                data.optString("source_username"),
                data.optString("source_email")
        );
    }

    void reportP2PEnd(OpenResult opened, DeviceItem target, boolean success,
                      String errorCode, String errorMessage) throws Exception {
        Session session = sessionStore.require();
        JSONObject req = new JSONObject();
        req.put("connection_id", opened.connectionId);
        req.put("log_id", opened.logId);
        req.put("success", success);
        req.put("source_device_id", session.deviceId);
        req.put("target_device_id", target.id);
        req.put("source_nat_type", "unknown");
        req.put("target_nat_type", "unknown");
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

    /**
     * 邮箱注册新用户。注册后用户状态为 pending，需去邮箱点击激活链接。
     * inviteCode 可选，传空串表示无邀请码。
     */
    void registerByEmail(String serverUrl, String username, String email,
                         String password, String inviteCode) throws Exception {
        JSONObject req = new JSONObject();
        req.put("username", username);
        req.put("email", email);
        req.put("password", password);
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
            Session refreshed = refresh(session);
            return request(refreshed.serverUrl, method, path, body, refreshed.accessToken);
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

        if (accessToken.isEmpty() || refreshToken.isEmpty()) {
            throw new IOException("刷新登录态失败：响应缺少令牌");
        }

        Session refreshed = session.withTokens(
                accessToken,
                refreshToken,
                System.currentTimeMillis() + expiresIn * 1000L,
                username
        );
        sessionStore.save(refreshed);
        return refreshed;
    }

    private DeviceItem parseDevice(JSONObject item) {
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
                item.optString("public_ip_location")
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
        URL url = new URL(normalizeServerUrl(serverUrl) + path);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
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
        String raw = readFully(status >= 400 ? conn.getErrorStream() : conn.getInputStream());
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
    }

    private static String readFully(InputStream stream) throws IOException {
        if (stream == null) {
            return "{}";
        }
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
        }
        return sb.toString();
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
        // 主动端身份：服务端按 Bearer token 解析后回填，需在 attempt_start 中回传给被动端，
        // 否则被动端弹窗显示「未知用户」（对齐桌面 P2PAttemptMessage::AttemptStart）。
        final long sourceUserId;
        final String sourceUsername;
        final String sourceEmail;

        OpenResult(String connectionId, long logId, String accessGrant, int targetRdpPort,
                   long sourceUserId, String sourceUsername, String sourceEmail) {
            this.connectionId = connectionId == null ? "" : connectionId;
            this.logId = logId;
            this.accessGrant = accessGrant == null ? "" : accessGrant;
            this.targetRdpPort = targetRdpPort;
            this.sourceUserId = sourceUserId;
            this.sourceUsername = sourceUsername == null ? "" : sourceUsername;
            this.sourceEmail = sourceEmail == null ? "" : sourceEmail;
        }
    }
}
