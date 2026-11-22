package id.omni.floatai;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

final class WelcomeScreen extends ScrollView {
    WelcomeScreen(Activity activity, Runnable enter) {
        super(activity);
        setFillViewport(true);
        setVerticalScrollBarEnabled(false);
        setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
            new int[]{0xff1e1133, 0xff281544, 0xff100c22}));
        var content = Chat.column(activity);
        int pad = Chat.dp(activity, 24);
        content.setPadding(pad, pad, pad, pad);
        Toolbox.applyInsets(content, pad);
        content.setGravity(Gravity.CENTER);
        addView(content, new ScrollView.LayoutParams(-1, -2));

        var greeting = center(activity, "Selamat datang", 15, 0xffffba90);
        greeting.setLetterSpacing(.08f);
        content.addView(greeting, new LinearLayout.LayoutParams(-1, -2));
        var title = center(activity, "Omni Float AI", 40, 0xfffff9f6);
        title.setTypeface(Typeface.create("sans-serif-black", Typeface.NORMAL));
        title.setLetterSpacing(-.04f);
        OmniUi.addSpace(content, title, 6);
        var description = center(activity, "Terjemahkan layar dan belajar dari mana saja.", 15, 0xffddd0f2);
        description.setLineSpacing(Chat.dp(activity, 4), 1);
        OmniUi.addSpace(content, description, 8);

        var orbit = new PlatformOrbitView(activity);
        var orbitLayout = new LinearLayout.LayoutParams(-1, -2);
        orbitLayout.topMargin = Chat.dp(activity, 9);
        content.addView(orbit, orbitLayout);

        var supported = center(activity, "Platform kuis di Chrome", 13, 0xffe5d6ff);
        supported.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        OmniUi.addSpace(content, supported, 3);
        var compatibility = center(activity, "Dukungan mengikuti opsi yang dapat dibaca Chrome.", 12, 0xffc2b3dd);
        OmniUi.addSpace(content, compatibility, 5);

        var start = Chat.button(activity, "Mulai", true, enter);
        start.setMinHeight(Chat.dp(activity, 55));
        OmniUi.addSpace(content, start, 24);

        var owner = Chat.button(activity, "Instagram  @aetherjy", false, () -> openOwner(activity));
        var surface = Chat.shape(activity, 0xff271940, 14);
        surface.setStroke(Chat.dp(activity, 1), 0xff69518d);
        owner.setBackground(new android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(0x40ffffff), surface, null));
        owner.setTextColor(0xfff3e8ff);
        owner.setContentDescription("Instagram @aetherjy");
        OmniUi.addSpace(content, owner, 10);
    }

    private static TextView center(Activity activity, String value, int size, int color) {
        var label = Chat.label(activity, value, size, color);
        label.setGravity(Gravity.CENTER);
        return label;
    }

    private static void openOwner(Activity activity) {
        try {
            activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.instagram.com/aetherjy/")));
        } catch (ActivityNotFoundException error) {
            Toast.makeText(activity, "Buka Instagram dan cari @aetherjy.", Toast.LENGTH_SHORT).show();
        }
    }
}
