package top.p2premote.android;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 连接诊断日志：把隧道建立过程的关键事件追加到应用私有文件，供
 * 「我的」页分享导出。真机上不需要 adb 即可拿到与 logcat 同等质量的
 * 线索（状态机迁移、PunchNative 结果、真实错误文案、NAT/traversal）。
 *
 * 写入在单后台线程串行执行，调用方（Service 线程/UI 线程）零阻塞。
 * 文件超过 {@link #MAX_BYTES} 时轮转：diag.log → diag-old.log（更旧删除），
 * 单文件即可覆盖最近若干次连接会话。
 */
public final class DiagLog {
    static final String FILE_NAME = "diag.log";
    static final String OLD_FILE_NAME = "diag-old.log";
    /** 256KB：约数千行，覆盖数十次连接会话。 */
    static final long MAX_BYTES = 256L * 1024;

    private static final Object INIT_LOCK = new Object();
    private static volatile ExecutorService writer;
    private static volatile File logFile;
    private static volatile File oldFile;

    private DiagLog() {
    }

    public static void i(String tag, String msg) {
        append("I", tag, msg, null);
    }

    public static void e(String tag, String msg, Throwable tr) {
        append("E", tag, msg, tr);
    }

    private static void append(String level, String tag, String msg, Throwable tr) {
        ExecutorService executor = ensureWriter();
        if (executor == null) {
            return; // 未初始化（无 Context）时静默丢弃，诊断日志不能反过来弄崩流程
        }
        String line = formatLine(System.currentTimeMillis(), level, tag, msg, tr);
        final File file = logFile;
        final File rotated = oldFile;
        executor.execute(() -> writeLine(file, rotated, line));
    }

    /** 纯 JVM 可测的行格式化：与 logcat -v time 的 MM-dd HH:mm:ss.mmm 对齐，便于跨端对照。 */
    static String formatLine(long millis, String level, String tag, String msg, Throwable tr) {
        StringBuilder sb = new StringBuilder(128);
        sb.append(new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(new Date(millis)))
                .append(' ').append(level)
                .append(' ').append(tag).append(": ")
                .append(msg == null ? "" : msg);
        if (tr != null) {
            sb.append('\n');
            java.io.StringWriter sw = new java.io.StringWriter();
            tr.printStackTrace(new java.io.PrintWriter(sw));
            sb.append(sw);
        }
        return sb.toString();
    }

    private static void writeLine(File file, File rotated, String line) {
        try {
            if (file.length() > MAX_BYTES) {
                // 尽力删除旧轮转文件；删不掉时 renameTo 同样会失败，走下面的截断兜底。
                rotated.delete();
                if (!file.renameTo(rotated)) {
                    // 轮转失败（极小概率）：截断重来，丢历史保可用。
                    new FileOutputStream(file, false).close();
                }
            }
            OutputStreamWriter out = new OutputStreamWriter(new FileOutputStream(file, true), "UTF-8");
            try {
                out.write(line);
                out.write('\n');
            } finally {
                out.close();
            }
        } catch (IOException ignored) {
            // 磁盘满等场景：诊断日志尽力而为。
        }
    }

    /** 惰性初始化；Context 由第一个调用方（WgvpnService.onCreate）提供。 */
    private static ExecutorService ensureWriter() {
        ExecutorService executor = writer;
        if (executor != null) {
            return executor;
        }
        synchronized (INIT_LOCK) {
            if (writer == null) {
                return null; // 未 attach 过：丢弃
            }
            return writer;
        }
    }

    /** 在 Application/Service 启动时调用一次；文件在 filesDir/diag/。 */
    public static void attach(Context context) {
        synchronized (INIT_LOCK) {
            if (writer != null) {
                return;
            }
            File dir = new File(context.getApplicationContext().getFilesDir(), "diag");
            if (!dir.exists() && !dir.mkdirs()) {
                return; // 目录建不出来时保持未初始化，日志全部丢弃
            }
            logFile = new File(dir, FILE_NAME);
            oldFile = new File(dir, OLD_FILE_NAME);
            writer = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "DiagLog");
                t.setDaemon(true);
                return t;
            });
        }
    }

    /** 当前日志文件（分享用）；无内容时返回 null，调用方据此提示。 */
    public static File file(Context context) {
        File dir = new File(context.getApplicationContext().getFilesDir(), "diag");
        File current = new File(dir, FILE_NAME);
        if (current.exists() && current.length() > 0) {
            return current;
        }
        File old = new File(dir, OLD_FILE_NAME);
        if (old.exists() && old.length() > 0) {
            return old;
        }
        return null;
    }

    public static void clear(Context context) {
        File dir = new File(context.getApplicationContext().getFilesDir(), "diag");
        File current = new File(dir, FILE_NAME);
        File old = new File(dir, OLD_FILE_NAME);
        ExecutorService executor = writer;
        Runnable task = () -> {
            // 删除两个日志文件后重建当前文件并写入清空标记（下一次连接会话从这里重新开始）。
            current.delete();
            old.delete();
            try {
                OutputStreamWriter out = new OutputStreamWriter(new FileOutputStream(current), "UTF-8");
                out.write(formatLine(System.currentTimeMillis(), "I", "DiagLog", "log cleared by user", null));
                out.write('\n');
                out.close();
            } catch (IOException ignored) {
            }
        };
        if (executor != null) {
            executor.execute(task);
        } else {
            task.run();
        }
    }
}
