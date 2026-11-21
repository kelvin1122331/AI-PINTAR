package id.omni.floatai;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.View;
import java.util.ArrayList;
import org.json.JSONArray;
import org.json.JSONObject;

final class TranslationOverlay extends View {
    private record Label(int x, int y, int width, int height, String value) {}
    private final ArrayList<Label> labels = new ArrayList<>();
    private final Paint background = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final int padding;

    TranslationOverlay(Context context, String response) throws Exception {
        super(context);
        padding = Chat.dp(context, 3);
        background.setColor(0xff101923);
        text.setColor(0xffffffff);
        int start = response.indexOf('{');
        int end = response.lastIndexOf('}');
        if (start < 0 || end <= start) return;
        JSONArray items = new JSONObject(response.substring(start, end + 1)).getJSONArray("teks");
        for (int i = 0; i < Math.min(items.length(), 28); i++) {
            JSONObject row = items.optJSONObject(i);
            if (row == null) continue;
            int x = row.optInt("x", -1), y = row.optInt("y", -1);
            int w = row.optInt("w", 0), h = row.optInt("h", 0);
            String value = row.optString("id").trim();
            if (x < 0 || y < 0 || x >= 1000 || y >= 1000 || w < 10 || h < 8 || value.isEmpty()) continue;
            labels.add(new Label(x, y, Math.min(w, 1000 - x), Math.min(h, 1000 - y), value.substring(0, Math.min(value.length(), 600))));
        }
    }

    boolean isEmpty() { return labels.isEmpty(); }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        for (Label label : labels) {
            float x = label.x() * getWidth() / 1000f;
            float y = label.y() * getHeight() / 1000f;
            int width = Math.round(label.width() * getWidth() / 1000f);
            int height = Math.round(label.height() * getHeight() / 1000f);
            if (width <= 2 * padding || height <= 2 * padding) continue;
            float size = Math.max(Chat.dp(getContext(), 9), Math.min(Chat.dp(getContext(), 16), (height - 2 * padding) * 0.72f));
            text.setTextSize(size);
            int lines = Math.max(1, (int) ((height - 2 * padding) / (size * 1.15f)));
            StaticLayout layout = StaticLayout.Builder.obtain(label.value(), 0, label.value().length(), text, width - 2 * padding)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).setMaxLines(lines)
                .setEllipsize(TextUtils.TruncateAt.END).setIncludePad(false).build();
            canvas.save();
            canvas.clipRect(x, y, x + width, y + height);
            canvas.drawRoundRect(new RectF(x, y, x + width, y + height), padding, padding, background);
            canvas.translate(x + padding, y + padding);
            layout.draw(canvas);
            canvas.restore();
        }
    }
}
