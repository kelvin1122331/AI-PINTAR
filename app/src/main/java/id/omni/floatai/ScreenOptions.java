package id.omni.floatai;

import android.content.Context;
import android.graphics.Bitmap;
import java.util.ArrayList;
import java.util.List;

final class ScreenOptions {
    static final String[] SPEEDS = {"Cepat", "Seimbang", "Stabil"};
    static final String[] AREAS = {"Seluruh layar", "Bagian atas", "Bagian tengah", "Bagian bawah"};
    static final String[] FORMATS = {"Paragraf", "Per baris"};

    record Settings(int speed, int area, int format, int opacity) {
        int quietMillis() { return speed == 0 ? 155 : speed == 2 ? 410 : 260; }
        int blankHoldMillis() { return speed == 0 ? 1350 : speed == 2 ? 3000 : 2050; }
        boolean lines() { return format == 1; }
    }

    record Crop(Bitmap bitmap, int top, int height, int fullHeight) {
        List<TranslationOverlay.Label> place(List<TranslationOverlay.Label> labels) {
            if (height == fullHeight) return labels;
            var result = new ArrayList<TranslationOverlay.Label>(labels.size());
            for (var item : labels) {
                result.add(new TranslationOverlay.Label(item.x(),
                    (top * 1000 + item.y() * height) / fullHeight, item.width(),
                    Math.max(1, item.height() * height / fullHeight), item.value(), item.original()));
            }
            return result;
        }
    }

    static Settings read(Context context) {
        var prefs = TranslationPreferences.of(context);
        return new Settings(range(prefs.getInt("screen_speed", 1), 0, 2),
            range(prefs.getInt("screen_area", 0), 0, 3),
            range(prefs.getInt("screen_format", 0), 0, 1),
            range(prefs.getInt("screen_opacity", 70), 45, 70));
    }

    static Crop crop(Bitmap bitmap, int area) {
        int fullHeight = bitmap.getHeight();
        int half = Math.max(1, fullHeight / 2);
        int top = area == 2 ? fullHeight / 4 : area == 3 ? fullHeight - half : 0;
        if (area == 0 || fullHeight < 2) return new Crop(bitmap, 0, fullHeight, fullHeight);
        Bitmap cropped = Bitmap.createBitmap(bitmap, 0, top, bitmap.getWidth(), half);
        if (cropped != bitmap) bitmap.recycle();
        return new Crop(cropped, top, half, fullHeight);
    }

    private static int range(int value, int low, int high) { return Math.min(high, Math.max(low, value)); }
    private ScreenOptions() {}
}
