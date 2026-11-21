package id.omni.floatai;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.WindowInsets;
import android.widget.*;

public final class MainActivity extends Activity {
    private TextView apiStatus;
    private TextView floatingStatus;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        var scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Chat.BG);
        var root = Chat.column(this);
        int pad = Chat.dp(this, 20);
        if (Build.VERSION.SDK_INT >= 30) {
            scroll.setOnApplyWindowInsetsListener((view, insets) -> {
                var bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                root.setPadding(bars.left + pad, bars.top + pad, bars.right + pad, bars.bottom + pad);
                return insets;
            });
        }
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root);

        var hero = Chat.column(this);
        hero.setPadding(pad, pad, pad, pad);
        var fill = new android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
            new int[]{0xff442079, 0xff7a40dd});
        fill.setCornerRadius(Chat.dp(this, 26));
        hero.setBackground(fill);
        var mark = new ImageView(this);
        mark.setImageResource(R.drawable.omni);
        hero.addView(mark, new LinearLayout.LayoutParams(Chat.dp(this, 40), Chat.dp(this, 40)));
        var title = Chat.label(this, "Omni Float AI", 33, Chat.WHITE);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        hero.addView(title);
        hero.addView(Chat.label(this, "Asisten AI mengambang untuk layar Android.", 15, 0xfff1e6ff));
        root.addView(hero);

        var floating = section(root, "Floating");
        floatingStatus = Chat.label(this, "", 14, Chat.MUTED);
        floating.addView(floatingStatus);
        var controls = new LinearLayout(this);
        addControl(controls, "Aktifkan floating", true, this::activate);
        addControl(controls, "Matikan", false, () -> {
            stopService(new Intent(this, BubbleService.class));
            scheduleStatus();
        });
        floating.addView(controls);

        var api = section(root, "Pengaturan API");
        apiStatus = Chat.label(this, "", 14, Chat.MUTED);
        api.addView(apiStatus);
        var apiControls = new LinearLayout(this);
        addControl(apiControls, "Masukkan API key", false, this::configure);
        api.addView(apiControls);

        var platforms = section(root, "Platform kuis");
        platforms.addView(Chat.label(this, "Quizizz / Wayground · Kahoot! · Google Forms · Microsoft Forms · Socrative · Nearpod · Blooket · Wordwall", 14, Chat.INK));
        var note = Chat.label(this, "Mode soal di Chrome untuk opsi pilihan tunggal yang dapat dibaca Aksesibilitas. Kompatibilitas tiap kuis dapat berbeda.", 13, Chat.MUTED);
        note.setPadding(0, Chat.dp(this, 8), 0, 0);
        platforms.addView(note);

        setContentView(scroll);
        updateStatus();
    }

    private LinearLayout section(LinearLayout root, String title) {
        var card = Chat.column(this);
        int pad = Chat.dp(this, 16);
        card.setPadding(pad, pad, pad, pad);
        var surface = Chat.shape(this, Chat.WHITE, 20);
        surface.setStroke(Chat.dp(this, 1), 0xffe9def8);
        card.setBackground(surface);
        var header = Chat.label(this, title, 19, Chat.INK);
        header.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        card.addView(header);
        var params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = Chat.dp(this, 14);
        root.addView(card, params);
        return card;
    }

    private void addControl(LinearLayout row, String title, boolean primary, Runnable action) {
        var params = new LinearLayout.LayoutParams(0, Chat.dp(this, 48), primary ? 1.5f : 1f);
        params.topMargin = Chat.dp(this, 10);
        if (row.getChildCount() > 0) params.leftMargin = Chat.dp(this, 8);
        row.addView(Chat.button(this, title, primary, action), params);
    }

    @Override protected void onResume() {
        super.onResume();
        updateStatus();
    }

    private void scheduleStatus() { floatingStatus.postDelayed(this::updateStatus, 350); }

    private void updateStatus() {
        if (apiStatus == null) return;
        int provider = Providers.active(this);
        String model = Providers.prefs(this).getString("model" + provider, "");
        apiStatus.setText(model.isEmpty() ? "Pilih provider dan model untuk mulai chat."
            : Providers.NAMES[provider] + " / " + (Providers.AUTO.equals(model) ? "Otomatis" : model));
        floatingStatus.setText(BubbleService.active ? "Bubble aktif" : "Bubble nonaktif");
    }

    private void activate() {
        if (!Settings.canDrawOverlays(this)) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()));
            startActivityForResult(intent, 1);
            return;
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                && !getPreferences(MODE_PRIVATE).getBoolean("asked", false)) {
            getPreferences(MODE_PRIVATE).edit().putBoolean("asked", true).apply();
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 2);
            return;
        }
        startForegroundService(new Intent(this, BubbleService.class));
        scheduleStatus();
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == 1 && Settings.canDrawOverlays(this)) activate();
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request == 2 && Settings.canDrawOverlays(this)) activate();
    }

    private void configure() {
        new ApiSettings(this, this::updateStatus).show();
    }
}

