package id.omni.floatai;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.List;

final class OmniUi {
    private OmniUi() {}

    static TextView title(Context c, String value, int size) {
        var text = Chat.label(c, value, size, Chat.INK);
        text.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return text;
    }

    static void pageHeader(LinearLayout root, String title, String description) {
        var context = root.getContext();
        var header = Chat.column(context);
        int pad = Chat.dp(context, 18);
        header.setPadding(pad, pad, pad, pad);
        header.setBackground(hero(context));
        var heading = title(context, title, 25);
        heading.setTextColor(Chat.WHITE);
        header.addView(heading);
        if (!description.isEmpty()) addSpace(header, Chat.label(context, description, 14, 0xfff1e6ff), 6);
        root.addView(header, new LinearLayout.LayoutParams(-1, -2));
    }

    static TextView caption(Context c, String value) {
        var text = Chat.label(c, value, 14, Chat.MUTED);
        text.setLineSpacing(Chat.dp(c, 2), 1f);
        return text;
    }

    static LinearLayout card(Context c, LinearLayout parent, String heading) {
        var card = Chat.column(c);
        int pad = Chat.dp(c, 16);
        card.setPadding(pad, pad, pad, pad);
        var surface = Chat.shape(c, Chat.WHITE, 20);
        surface.setStroke(Chat.dp(c, 1), 0xffe7e1f1);
        card.setBackground(surface);
        if (!heading.isEmpty()) card.addView(title(c, heading, 18));
        addSpace(parent, card, 12);
        return card;
    }

    static void addSpace(LinearLayout parent, View child, int top) {
        var params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = Chat.dp(parent.getContext(), top);
        parent.addView(child, params);
    }

    static void rowButton(LinearLayout row, String label, boolean primary, Runnable action) {
        var c = row.getContext();
        var params = new LinearLayout.LayoutParams(0, -2, 1);
        params.leftMargin = row.getChildCount() == 0 ? 0 : Chat.dp(c, 8);
        row.addView(Chat.button(c, label, primary, action), params);
    }

    static void toolRow(LinearLayout parent, String name, String description, String symbol, Runnable action) {
        Context c = parent.getContext();
        var row = new LinearLayout(c);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int pad = Chat.dp(c, 9);
        row.setPadding(pad, pad, pad, pad);
        row.setMinimumHeight(Chat.dp(c, 64));
        var mark = Chat.label(c, symbol, 15, Chat.PURPLE);
        mark.setGravity(Gravity.CENTER);
        mark.setBackground(Chat.shape(c, Chat.CARD, 10));
        row.addView(mark, new LinearLayout.LayoutParams(Chat.dp(c, 42), Chat.dp(c, 42)));
        var words = Chat.column(c);
        words.setPadding(Chat.dp(c, 12), 0, Chat.dp(c, 6), 0);
        words.addView(title(c, name, 16));
        if (!description.isEmpty()) words.addView(caption(c, description));
        row.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
        var chevron = Chat.label(c, "›", 24, Chat.PURPLE);
        chevron.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(chevron);
        var surface = Chat.shape(c, Chat.BG, 12);
        row.setBackground(new android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(0x24632fc2), surface, null));
        row.setContentDescription(name);
        row.setFocusable(true);
        row.setOnClickListener(v -> action.run());
        addSpace(parent, row, 7);
    }

    static GradientDrawable hero(Context c) {
        var fill = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
            new int[]{0xff291845, 0xff52329b, 0xff6e46bf});
        fill.setCornerRadius(Chat.dp(c, 24));
        return fill;
    }

    static boolean sameLabels(List<TranslationOverlay.Label> actual, List<TranslationOverlay.Label> next, int tolerance) {
        if (actual.size() != next.size()) return false;
        for (int i = 0; i < actual.size(); i++) {
            var a = actual.get(i);
            var b = next.get(i);
            if (!a.value().equals(b.value()) || Math.abs(a.x() - b.x()) > tolerance ||
                Math.abs(a.y() - b.y()) > tolerance || Math.abs(a.width() - b.width()) > tolerance ||
                Math.abs(a.height() - b.height()) > tolerance) return false;
        }
        return true;
    }
}
