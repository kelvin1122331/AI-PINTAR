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
        hero.setBackground(OmniUi.hero(this));
        var mark = new ImageView(this);
        mark.setImageResource(R.drawable.omni);
        hero.addView(mark, new LinearLayout.LayoutParams(Chat.dp(this, 36), Chat.dp(this, 36)));
        var title = Chat.label(this, "Omni Float AI", 33, Chat.WHITE);
        title.setTypeface(android.graphics.Typeface.create("sans-serif-medium", 0));
        OmniUi.addSpace(hero, title, 7);
        hero.addView(Chat.label(this, "Terjemahkan, baca layar, dan belajar.", 15, 0xfff1e6ff));
        root.addView(hero);

        var floating = OmniUi.card(this, root, "Asisten mengambang");
        floatingStatus = OmniUi.caption(this, "");
        OmniUi.addSpace(floating, floatingStatus, 4);
        var controls = new LinearLayout(this);
        OmniUi.rowButton(controls, "Aktifkan floating", true, this::activate);
        OmniUi.rowButton(controls, "Matikan", false, () -> {
            stopService(new Intent(this, BubbleService.class));
            scheduleStatus();
        });
        OmniUi.addSpace(floating, controls, 11);

        var tools = OmniUi.card(this, root, "Ruang AI");
        OmniUi.toolRow(tools, "Terjemahan", "Teks, foto, riwayat, dan bahasa overlay", "Aa",
            () -> startActivity(new Intent(this, TextDesk.class)));
        OmniUi.toolRow(tools, "Kamera", "Terjemah langsung dari pratinjau kamera", "◉",
            () -> startActivity(new Intent(this, CameraTranslator.class)));
        OmniUi.toolRow(tools, "Belajar", "Catatan, latihan, dan kartu ulasan", "▤", () -> openTool(0));

        var api = OmniUi.card(this, root, "Pengaturan");
        apiStatus = OmniUi.caption(this, "");
        OmniUi.addSpace(api, apiStatus, 4);
        OmniUi.toolRow(api, "Masukkan API key", "Pilih penyedia dan model AI", "⚙", this::configure);
        OmniUi.toolRow(api, "Monitor koneksi", "Latensi, status, dan penggunaan token", "▥", () -> openTool(1));
        OmniUi.toolRow(api, "Atur bubble", "Ukuran, tepian, dan transparansi", "◌", () -> openTool(2));

        var platforms = OmniUi.card(this, root, "");
        var platformNames = OmniUi.caption(this, "Quizizz / Wayground · Kahoot! · Google Forms · Microsoft Forms · Socrative · Nearpod · Blooket · Wordwall. Kompatibilitas bergantung pada opsi yang dapat dibaca Chrome.");
        platformNames.setVisibility(android.view.View.GONE);
        platforms.addView(Chat.button(this, "Platform kuis", false, () -> {
            boolean open = platformNames.getVisibility() == android.view.View.VISIBLE;
            platformNames.setVisibility(open ? android.view.View.GONE : android.view.View.VISIBLE);
        }));
        OmniUi.addSpace(platforms, platformNames, 7);
        setContentView(scroll);
        updateStatus();
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
        floatingStatus.setText(BubbleService.active ? "Aktif · ketuk bubble untuk membuka alat" : "Nonaktif · nyalakan untuk memakai alat di atas layar");
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

    private void openTool(int tab) { startActivity(new Intent(this, Toolbox.class).putExtra("tab",tab)); }

    private void configure() {
        new ApiSettings(this, this::updateStatus).show();
    }
}
