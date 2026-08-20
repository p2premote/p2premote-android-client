package top.p2premote.android;

/**
 * 会话数据模型：保存登录态、本机设备信息和 WireGuard 密钥对。
 */
final class Session {
    final String serverUrl;
    final String accessToken;
    final String refreshToken;
    final long accessTokenExpiresAt;
    final String username;
    final String memberLevel;
    final String memberExpireTime;
    final String trialExpireTime;
    final int trialRemainingDays;
    final boolean isPro;
    final long deviceId;
    final String deviceUuid;
    /** WireGuard 私钥（hex），wgvpn 隧道用。 */
    final String wgPrivateKeyHex;
    /** WireGuard 公钥（hex），交换给对端用。 */
    final String wgPublicKeyHex;

    Session(
            String serverUrl,
            String accessToken,
            String refreshToken,
            long accessTokenExpiresAt,
            String username,
            String memberLevel,
            String memberExpireTime,
            String trialExpireTime,
            int trialRemainingDays,
            boolean isPro,
            long deviceId,
            String deviceUuid,
            String wgPrivateKeyHex,
            String wgPublicKeyHex
    ) {
        this.serverUrl = serverUrl;
        this.accessToken = accessToken;
        this.refreshToken = refreshToken;
        this.accessTokenExpiresAt = accessTokenExpiresAt;
        this.username = username == null ? "" : username;
        this.memberLevel = memberLevel == null ? "" : memberLevel;
        this.memberExpireTime = memberExpireTime == null ? "" : memberExpireTime;
        this.trialExpireTime = trialExpireTime == null ? "" : trialExpireTime;
        this.trialRemainingDays = trialRemainingDays;
        this.isPro = isPro;
        this.deviceId = deviceId;
        this.deviceUuid = deviceUuid == null ? "" : deviceUuid;
        this.wgPrivateKeyHex = wgPrivateKeyHex == null ? "" : wgPrivateKeyHex;
        this.wgPublicKeyHex = wgPublicKeyHex == null ? "" : wgPublicKeyHex;
    }

    Session withDevice(DeviceItem device) {
        return new Session(serverUrl, accessToken, refreshToken, accessTokenExpiresAt,
                username, memberLevel, memberExpireTime, trialExpireTime, trialRemainingDays, isPro,
                device.id, device.uuid, wgPrivateKeyHex, wgPublicKeyHex);
    }

    Session withTokens(String newAccessToken, String newRefreshToken, long newExpiresAt,
                       String newUsername, String newMemberLevel, String newMemberExpireTime,
                       String newTrialExpireTime, int newTrialRemainingDays, boolean newIsPro) {
        return new Session(serverUrl, newAccessToken, newRefreshToken, newExpiresAt,
                newUsername, newMemberLevel, newMemberExpireTime, newTrialExpireTime,
                newTrialRemainingDays, newIsPro, deviceId, deviceUuid,
                wgPrivateKeyHex, wgPublicKeyHex);
    }

}
