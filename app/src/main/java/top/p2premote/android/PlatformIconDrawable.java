package top.p2premote.android;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

import androidx.core.graphics.PathParser;

final class PlatformIconDrawable extends Drawable {
    private final DevicePlatform.Kind platform;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    PlatformIconDrawable(DevicePlatform.Kind platform, int color) {
        this.platform = platform;
        paint.setColor(color);
        paint.setStyle(Paint.Style.FILL);
    }

    @Override public void draw(Canvas canvas) {
        Rect b = getBounds();
        float viewportSize = platform == DevicePlatform.Kind.KYLIN ? 16.7f : 24f;
        float scale = Math.min(b.width(), b.height()) / viewportSize;
        int save = canvas.save();
        canvas.clipRect(b);
        canvas.translate(b.left + (b.width() - viewportSize * scale) / 2f,
                b.top + (b.height() - viewportSize * scale) / 2f);
        canvas.scale(scale, scale);
        String data = pathData(platform);
        if (data != null) {
            Path path = PathParser.createPathFromPathData(data);
            canvas.drawPath(path, paint);
        } else {
            drawUnknown(canvas);
        }
        canvas.restoreToCount(save);
    }

    private void drawUnknown(Canvas canvas) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2f);
        canvas.drawRoundRect(3, 4, 21, 17, 2, 2, paint);
        canvas.drawLine(8, 21, 16, 21, paint);
        canvas.drawLine(12, 17, 12, 21, paint);
        paint.setStyle(Paint.Style.FILL);
    }

    private static String pathData(DevicePlatform.Kind kind) {
        switch (kind) {
            case WINDOWS_11:
                return "M3,3 H11 V11 H3 Z M13,3 H21 V11 H13 Z M3,13 H11 V21 H3 Z M13,13 H21 V21 H13 Z";
            case WINDOWS_10:
                return "M3,5.2 L10.6,4 V11.1 H3 Z M12,3.8 L21,2.5 V11.1 H12 Z M3,12.5 H10.6 V19.7 L3,18.5 Z M12,12.5 H21 V21 L12,19.7 Z";
            case WINDOWS_7:
                return "M2.8,5.8 C5.4,6.3 8,5 10.6,4.5 V11.1 C8,11.6 5.4,12.9 2.8,12.4 Z M11.9,4.2 C14.9,3.7 17.9,2.2 21,3 V9.5 C18,8.7 15,10.2 11.9,10.7 Z M2.8,13.7 C5.4,14.2 8,12.9 10.6,12.4 V19 C8,19.5 5.4,20.8 2.8,20.3 Z M11.9,12.1 C14.9,11.6 17.9,10.1 21,10.9 V17.4 C18,16.6 15,18.1 11.9,18.6 Z";
            case WINDOWS:
                return "M3,4.5 L10.7,3 V11 H3 Z M12.2,2.8 L21,1.5 V11 H12.2 Z M3,12.5 H10.7 V20.5 L3,19 Z M12.2,12.5 H21 V21.9 L12.2,20.7 Z";
            case ANDROID:
                return "M17.6,9.48 L19.44,6.3 C19.6,5.99 19.48,5.61 19.18,5.45 C18.88,5.29 18.52,5.39 18.35,5.67 L16.47,8.91 C13.63,7.72 10.37,7.72 7.53,8.91 L5.65,5.67 C5.47,5.38 5.09,5.29 4.78,5.47 C4.5,5.65 4.41,6.01 4.56,6.3 L6.4,9.48 C3.08,11.37 1,14.64 1,18 H23 C23,14.64 20.92,11.37 17.6,9.48 M7,15.25 A1.25,1.25 0,1 1,7,12.75 A1.25,1.25 0,1 1,7,15.25 M17,15.25 A1.25,1.25 0,1 1,17,12.75 A1.25,1.25 0,1 1,17,15.25";
            case UBUNTU:
                return "M12,5.4 A6.6,6.6 0,1 0,12,18.6 A6.6,6.6 0,1 0,12,5.4 M12,8.2 A3.8,3.8 0,1 1,12,15.8 A3.8,3.8 0,1 1,12,8.2 M4.2,9.8 A2.2,2.2 0,1 0,4.2,14.2 A2.2,2.2 0,1 0,4.2,9.8 M17.6,3.2 A2.2,2.2 0,1 0,17.6,7.6 A2.2,2.2 0,1 0,17.6,3.2 M17.6,16.4 A2.2,2.2 0,1 0,17.6,20.8 A2.2,2.2 0,1 0,17.6,16.4";
            case MACOS:
                return "M12.15,6.9 C11.2,6.9 9.74,5.82 8.19,5.86 C6.15,5.89 4.28,7.04 3.23,8.87 C1.11,12.55 2.68,17.97 4.75,20.96 C5.76,22.41 6.96,24.05 8.54,24 C10.06,23.94 10.63,23.01 12.48,23.01 C14.31,23.01 14.83,24 16.44,23.96 C18.08,23.93 19.12,22.48 20.12,21.01 C21.27,19.33 21.75,17.69 21.78,17.6 C18.6,16.38 18.56,12.74 21.15,8.18 C19.72,6.09 17.53,5.86 16.76,5.8 C14.76,5.65 13.09,6.9 12.15,6.9 M15.53,3.83 C16.37,2.82 16.93,1.4 16.77,0 C15.57,0.05 14.11,0.81 13.24,1.82 C12.46,2.71 11.79,4.16 11.97,5.53 C13.31,5.64 14.69,4.84 15.53,3.83";
            case UOS:
                return "M12,2 A10,10 0,1 0,22,12 H18.4 A6.4,6.4 0,1 1,12,5.6 Z M12,7.2 A4.8,4.8 0,1 0,16.8,12 H13.6 A1.6,1.6 0,1 1,12,10.4 Z";
            case DEEPIN:
                return "M12,2 A10,10 0,1 0,22,12 C22,7 18,3 13,3 C8,3 5,6 5,10 C5,14 8,17 12,17 C15,17 17,15 17,12 C17,10 16,9 15,9 C15,13 13,15 10,14 C7,13 7,9 9,6 C10,4 11,3 12,2 Z";
            case KYLIN:
                // Keep this path and its 16.7 viewport in sync with the desktop client.
                return "M7.94.07 5.81 2.25l-.54-.06-.44 1.77 2.22-1.17-.49-2.03.89-2.72"
                        + "M4.83 3.96l1.35 1.56 1.36-.69L5.81 2.25 3.89 2.96l.42 3.75L2.14 4.03l-.89 1.82L.03 7.93l1.15 2.84.37 2.24-.77.92-.81.98h2.21l.28-1.99.89-1.36 1.48.25 1.64-.65 1.21-.62 1.18.79-1.42 1.69-1.45 1.37 2.28.03.01-.93 1.73-1.37.63.8-1.82 2.59 2.32.01-.08-1.3 1.66-1.57-.85-1.72-.1-2.12 1.7-1 .96.2 1.31-.49.37-2.63-2.04.6-1.24 1.86-2.09 1.11-2.92-1.18-2.13-1.58 1.39-1.95-.49-2.04-2.71.87-2.72"
                        + "M2.05 6.32v2.13l3.13.63-.87-2.37-2.26-.39m3.13 2.76-.37 1.83 3.48-.73-.01-1.82-3.1.72m3.1 1.1 2.3.94.61-1.57-2.91.63m2.91-.63 1.02.35 1.07-1.46-2.09 1.11m2.09-1.11.63.46.61-2.32-1.24 1.86";
            case LINUX:
                return "M12,2 C9.9,2 8.2,4 8.2,6.4 C6.7,8 5.8,10.8 5.8,14.2 C5.8,18.2 8.6,21.4 12,21.4 C15.4,21.4 18.2,18.2 18.2,14.2 C18.2,10.8 17.3,8 15.8,6.4 C15.8,4 14.1,2 12,2 Z";
            default: return null;
        }
    }

    @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); invalidateSelf(); }
    @Override public void setColorFilter(ColorFilter colorFilter) { paint.setColorFilter(colorFilter); invalidateSelf(); }
    @Override @SuppressWarnings("deprecation") public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    @Override public int getIntrinsicWidth() { return 24; }
    @Override public int getIntrinsicHeight() { return 24; }
}
