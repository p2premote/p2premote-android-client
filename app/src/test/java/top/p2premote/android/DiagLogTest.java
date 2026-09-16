package top.p2premote.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** DiagLog 纯 JVM 部分的格式化测试（文件写入依赖 Android Context，不在单测范围）。 */
public class DiagLogTest {

    @Test
    public void formatLine_matchesLogcatTimeStyle() {
        long t = System.currentTimeMillis();
        String line = DiagLog.formatLine(t, "I", "WgvpnService", "state=CONNECTED msg=隧道已建立", null);
        String stamp = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(new Date(t));
        assertTrue("should start with logcat-style timestamp: " + line, line.startsWith(stamp + " I WgvpnService: "));
        assertTrue(line.endsWith("state=CONNECTED msg=隧道已建立"));
    }

    @Test
    public void formatLine_appendsStackTrace() {
        String line = DiagLog.formatLine(0, "E", "T", "boom",
                new IllegalStateException("密钥交换失败"));
        assertTrue(line.contains("\njava.lang.IllegalStateException: 密钥交换失败"));
        assertTrue(line.contains("at top.p2premote.android.DiagLogTest.formatLine_appendsStackTrace"));
    }

    @Test
    public void formatLine_nullMessageAndThrowable() {
        String line = DiagLog.formatLine(0, "I", "T", null, null);
        assertEquals("000000毫秒时间戳正常格式化", true, line.contains(" I T: "));
    }
}
