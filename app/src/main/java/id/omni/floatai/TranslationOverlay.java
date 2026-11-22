package id.omni.floatai;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Bitmap;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.view.View;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class TranslationOverlay extends View {
    record Label(int x, int y, int width, int height, String value, String original) {
        Label(int x, int y, int width, int height, String value) { this(x, y, width, height, value, ""); }
    }
    private record Card(RectF bounds, StaticLayout text) {}
    private final ArrayList<Label> labels = new ArrayList<>();
    private final ArrayList<Card> cards = new ArrayList<>();
    private final Paint background = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int padding;
    private int textPercent;
    private int textColor;
    private int[] paintPixels;
    private int[] restorePixels;
    private int maskWidth;
    private int maskHeight;
    private int screenWidth;
    private int screenHeight;
    private int firstRow;
    private int lastRow;

    TranslationOverlay(Context context, List<Label> labels) {
        super(context);
        padding = Chat.dp(context, 4);
        setLabels(labels);
    }

    void setLabels(List<Label> values) {
        labels.clear();
        labels.addAll(values);
        labels.sort(Comparator.comparingInt(Label::y).thenComparingInt(Label::x));
        var preferences = TranslationPreferences.of(getContext());
        textPercent = preferences.getInt("text_size", 100);
        boolean light = preferences.getBoolean("light", false);
        background.setColor(light ? 0xfffcf7ff : 0xff25143c);
        textColor = light ? 0xff24163e : 0xffffffff;
        rebuild(getWidth(), getHeight());
        invalidate();
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        rebuild(width, height);
    }

    private void rebuild(int width, int height) {
        cards.clear();
        paintPixels = null;
        if (width <= padding * 2 || height <= padding * 2) return;
        Context context = getContext();
        for (Label label : labels) {
            int x = Math.round(label.x() * width / 1000f);
            int y = Math.round(label.y() * height / 1000f);
            int sourceWidth = Math.round(label.width() * width / 1000f);
            int sourceHeight = Math.round(label.height() * height / 1000f);
            TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
            paint.setColor(textColor);
            paint.setTextSize(Math.max(Chat.dp(context, 11), Math.min(Chat.dp(context, 16), sourceHeight * 0.68f)) * textPercent / 100f);
            int naturalWidth = (int) Math.ceil(Layout.getDesiredWidth(label.value(), paint)) + padding * 2;
            int boxWidth = Math.min(width - 2 * padding,
                Math.max(sourceWidth + 2 * padding, Math.min(naturalWidth, width * 3 / 4)));
            boxWidth = Math.max(padding * 2 + 1, boxWidth);
            x = Math.max(padding, Math.min(x - padding, width - boxWidth - padding));
            StaticLayout layout = StaticLayout.Builder.obtain(label.value(), 0, label.value().length(), paint, boxWidth - 2 * padding)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false)
                .setBreakStrategy(android.text.Layout.BREAK_STRATEGY_HIGH_QUALITY).build();
            int boxHeight = Math.max(sourceHeight + padding, layout.getHeight() + 2 * padding);
            y = Math.max(padding, Math.min(y - padding, height - boxHeight - padding));
            for (Card card : cards) {
                RectF previous = card.bounds();
                if (x >= previous.right || x + boxWidth <= previous.left) continue;
                if (y >= previous.bottom || y + boxHeight <= previous.top) continue;
                int moved = (int) previous.bottom + 2;
                if (moved + boxHeight <= height - padding) y = moved;
            }
            cards.add(new Card(new RectF(x, y, x + boxWidth, y + boxHeight), layout));
        }
    }

    void restoreBackground(Bitmap frame, int displayWidth, int displayHeight, float opacity) {
        if (getVisibility() != View.VISIBLE || cards.isEmpty() || getWidth() == 0 || opacity <= 0) return;
        int width = frame.getWidth();
        int height = frame.getHeight();
        if (paintPixels == null || maskWidth != width || maskHeight != height
                || screenWidth != displayWidth || screenHeight != displayHeight) {
            maskWidth = width;
            maskHeight = height;
            screenWidth = displayWidth;
            screenHeight = displayHeight;
            var origin = new int[2];
            getLocationOnScreen(origin);
            float sx = width / (float) Math.max(1, displayWidth);
            float sy = height / (float) Math.max(1, displayHeight);
            Bitmap mask = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            var canvas = new Canvas(mask);
            canvas.scale(sx, sy);
            canvas.translate(origin[0], origin[1]);
            paintCards(canvas);
            paintPixels = new int[width * height];
            mask.getPixels(paintPixels, 0, width, 0, 0, width, height);
            mask.recycle();
            firstRow = height;
            lastRow = 0;
            for (Card card : cards) {
                firstRow = Math.min(firstRow, Math.max(0, (int) ((card.bounds().top + origin[1]) * sy)));
                lastRow = Math.max(lastRow, Math.min(height, (int) Math.ceil((card.bounds().bottom + origin[1]) * sy)));
            }
        }
        int rows = lastRow - firstRow;
        if (rows <= 0) return;
        int length = width * rows;
        if (restorePixels == null || restorePixels.length != length) restorePixels = new int[length];
        int[] pixels = restorePixels;
        frame.getPixels(pixels, 0, width, 0, firstRow, width, rows);
        int offset = firstRow * width;
        for (int i = 0; i < pixels.length; i++) {
            int paint = paintPixels[i + offset];
            if (paint != 0) pixels[i] = ScreenScene.restore(pixels[i], paint, opacity);
        }
        frame.setPixels(pixels, 0, width, 0, firstRow, width, rows);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        paintCards(canvas);
    }

    private void paintCards(Canvas canvas) {
        for (Card card : cards) {
            RectF bounds = card.bounds();
            canvas.drawRoundRect(bounds, padding, padding, background);
            canvas.save();
            canvas.translate(bounds.left + padding, bounds.top + padding);
            card.text().draw(canvas);
            canvas.restore();
        }
    }
}
