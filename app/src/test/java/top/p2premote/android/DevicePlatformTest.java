package top.p2premote.android;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class DevicePlatformTest {
    @Test public void detectsPlatformsFromTypeAndSystemVersion() {
        assertEquals(DevicePlatform.Kind.ANDROID, DevicePlatform.detect("android", "Android 15"));
        assertEquals(DevicePlatform.Kind.MACOS, DevicePlatform.detect("desktop", "macOS 15"));
        assertEquals(DevicePlatform.Kind.WINDOWS_11, DevicePlatform.detect("desktop", "Windows 11 Pro"));
        assertEquals(DevicePlatform.Kind.WINDOWS_10, DevicePlatform.detect("windows", "Windows 10"));
        assertEquals(DevicePlatform.Kind.WINDOWS_7, DevicePlatform.detect("windows", "Windows 7"));
        assertEquals(DevicePlatform.Kind.UBUNTU, DevicePlatform.detect("linux", "Ubuntu 24.04"));
        assertEquals(DevicePlatform.Kind.KYLIN, DevicePlatform.detect("linux", "银河麒麟 V10"));
        assertEquals(DevicePlatform.Kind.UOS, DevicePlatform.detect("linux", "统信 UOS"));
        assertEquals(DevicePlatform.Kind.DEEPIN, DevicePlatform.detect("linux", "Deepin 25"));
        assertEquals(DevicePlatform.Kind.LINUX, DevicePlatform.detect("linux", "Debian 13"));
        assertEquals(DevicePlatform.Kind.UNKNOWN, DevicePlatform.detect("", ""));
    }
}
