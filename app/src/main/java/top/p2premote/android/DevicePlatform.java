package top.p2premote.android;

import java.util.Locale;

final class DevicePlatform {
    enum Kind {
        WINDOWS_7, WINDOWS_10, WINDOWS_11, WINDOWS,
        UBUNTU, KYLIN, UOS, DEEPIN, ANDROID, MACOS, LINUX, UNKNOWN
    }

    private DevicePlatform() {}

    static Kind detect(DeviceItem device) {
        if (device == null) return Kind.UNKNOWN;
        return detect(device.type, device.systemVersion);
    }

    static Kind detect(String deviceType, String systemVersion) {
        String value = ((deviceType == null ? "" : deviceType) + " "
                + (systemVersion == null ? "" : systemVersion)).toLowerCase(Locale.ROOT);
        if (containsAny(value, "android", "安卓")) return Kind.ANDROID;
        if (containsAny(value, "mac os", "macos", "os x", "darwin")) return Kind.MACOS;
        if (containsAny(value, "kylin", "麒麟")) return Kind.KYLIN;
        if (containsAny(value, "deepin", "深度")) return Kind.DEEPIN;
        if (containsAny(value, "uos", "统信")) return Kind.UOS;
        if (value.contains("ubuntu")) return Kind.UBUNTU;
        if (containsAny(value, "windows", "win7", "win 7", "win10", "win 10", "win11", "win 11")) {
            if (containsAny(value, "windows 11", "windows11", "win 11", "win11")) return Kind.WINDOWS_11;
            if (containsAny(value, "windows 10", "windows10", "win 10", "win10")) return Kind.WINDOWS_10;
            if (containsAny(value, "windows 7", "windows7", "win 7", "win7")) return Kind.WINDOWS_7;
            return Kind.WINDOWS;
        }
        if (containsAny(value, "linux", "debian", "fedora", "centos", "red hat", "arch", "opensuse")) {
            return Kind.LINUX;
        }
        return Kind.UNKNOWN;
    }

    static String label(Kind kind) {
        switch (kind) {
            case WINDOWS_7: return "Windows 7";
            case WINDOWS_10: return "Windows 10";
            case WINDOWS_11: return "Windows 11";
            case WINDOWS: return "Windows";
            case UBUNTU: return "Ubuntu";
            case KYLIN: return "Kylin";
            case UOS: return "UOS";
            case DEEPIN: return "Deepin";
            case ANDROID: return "Android";
            case MACOS: return "macOS";
            case LINUX: return "Linux";
            default: return "未知系统";
        }
    }

    private static boolean containsAny(String value, String... needles) {
        for (String needle : needles) if (value.contains(needle)) return true;
        return false;
    }
}
