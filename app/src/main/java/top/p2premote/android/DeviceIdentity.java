package top.p2premote.android;

import android.content.Context;
import android.os.Build;
import android.provider.Settings;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

final class DeviceIdentity {
    private DeviceIdentity() {
    }

    /** 应用上下文，供读 Settings.Global.DEVICE_NAME 使用。由 Application/Activity onCreate 注入。 */
    private static volatile android.content.Context appContext;

    /** 注入应用上下文，使 deviceName() 能读取系统设备名。 */
    static void init(android.content.Context context) {
        if (context != null) {
            appContext = context.getApplicationContext();
        }
    }

    /**
     * 设备显示名：优先读 Settings.Global.DEVICE_NAME（用户在系统设置里设置的
     * 蓝牙/设备名，真机一般是「XXX 的手机」之类可读名称），其次用厂商+型号拼装。
     *
     * 仅靠 Build.MANUFACTURER/MODEL 在模拟器上会得到 "unknown Android SDK built
     * for x86_64" 这种不可读值；真机上则是型号（如 "Pixel 7"），可读但不如用户自定义名。
     * Settings.Global.DEVICE_NAME 与蓝牙名一致，是 Android 推荐的设备标识来源。
     */
    static String deviceName() {
        String global = safe(readGlobalDeviceName());
        if (!global.isEmpty() && !looksLikeBuildModel(global)) {
            return global;
        }
        String manufacturer = safe(Build.MANUFACTURER);
        String model = safe(Build.MODEL);
        if (manufacturer.isEmpty() || looksLikeBuildModel(manufacturer)) {
            return model.isEmpty() ? "Android Device" : model;
        }
        if (model.toLowerCase().contains(manufacturer.toLowerCase())) {
            return model;
        }
        return manufacturer + " " + model;
    }

    /**
     * 读取 Settings.Global.DEVICE_NAME。需要Context，但本方法在无 Context 场景
     * （如登录注册流程）也能调用，故通过默认 Application 的内容解析器取值；
     * 取不到时返回空串，由调用方回退到 Build 常量。
     */
    private static String readGlobalDeviceName() {
        // appContext 由 MainActivity 与两个 Service 的 onCreate 显式注入
        // （START_STICKY 重建场景由 Service 覆盖）；未注入时回退 Build 常量。
        android.content.Context ctx = appContext;
        if (ctx == null) return "";
        try {
            return Settings.Global.getString(ctx.getContentResolver(), Settings.Global.DEVICE_NAME);
        } catch (Throwable ignored) {
            return "";
        }
    }

    /** 不可读的占位值（厂商为 "unknown"、或 AOSP 模拟器默认型号），回退时不采用。 */
    private static boolean looksLikeBuildModel(String value) {
        if (value == null) return true;
        String v = value.trim();
        if (v.isEmpty()) return true;
        String low = v.toLowerCase();
        return low.equals("unknown")
                || low.startsWith("android sdk built for")
                || low.equals("google sdk gphone")
                || low.startsWith("aosp on x86");
    }

    static String systemVersion() {
        return "Android " + Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + ")";
    }

    static String deviceUuid(Context context) {
        String androidId = Settings.Secure.getString(
                context.getContentResolver(),
                Settings.Secure.ANDROID_ID
        );
        String raw = safe(androidId) + "|" + safe(Build.MANUFACTURER) + "|" + safe(Build.MODEL);
        return sha256Hex(raw).substring(0, 8)
                + "-"
                + sha256Hex(raw + "|p2premote").substring(0, 8)
                + "-"
                + sha256Hex("android|" + raw).substring(0, 8)
                + "-"
                + sha256Hex(raw + "|device").substring(0, 8);
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private static String sha256Hex(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("failed to generate device uuid", e);
        }
    }
}
