package id.omni.floatai;

import android.content.Context;
import android.graphics.Canvas;
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
    record Label(int x, int y, int width, int height, String value) {}
    private record Card(RectF bounds, StaticLayout text) {}
    private final List<Label> labels;
    private final ArrayList<Card> cards = new ArrayList<>();
    private final Paint background = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int padding;

    TranslationOverlay(Context context, List<Label> labels) {
        super(context);
        this.labels = new ArrayList<>(labels);
        this.labels.sort(Comparator.comparingInt(Label::y).thenComparingInt(Label::x));
        padding = Chat.dp(context, 4);
        background.setColor(0xff25143c);
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        cards.clear();
        if (width <= padding * 2 || height <= padding * 2) return;
        Context context = getContext();
        for (Label label : labels) {
            int x = Math.round(label.x() * width / 1000f);
            int y = Math.round(label.y() * height / 1000f);
            int sourceWidth = Math.round(label.width() * width / 1000f);
            int sourceHeight = Math.round(label.height() * height / 1000f);
            TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
            paint.setColor(0xffffffff);
            paint.setTextSize(Math.max(Chat.dp(context, 11), Math.min(Chat.dp(context, 16), sourceHeight * 0.68f)));
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

    boolean covers(float x, float y) {
        float px = x * getWidth();
        float py = y * getHeight();
        int bleed = Chat.dp(getContext(), 5);
        for (Card card : cards) {
            RectF b = card.bounds();
            if (px >= b.left - bleed && px <= b.right + bleed &&
                py >= b.top - bleed && py <= b.bottom + bleed) return true;
        }
        return false;
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
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

