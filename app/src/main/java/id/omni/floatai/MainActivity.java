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
    private boolean welcomeVisible;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        boolean welcome = state == null ? !getPreferences(MODE_PRIVATE).getBoolean("intro_seen", false)
            : state.getBoolean("welcome_visible");
        if (welcome) showWelcome();
        else showHome();
    }

    private void showWelcome() {
        welcomeVisible = true;
        apiStatus = null;
        floatingStatus = null;
        styleSystemBars(true);
        setContentView(new WelcomeScreen(this, () -> {
            getPreferences(MODE_PRIVATE).edit().putBoolean("intro_seen", true).apply();
            showHome();
        }));
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putBoolean("welcome_visible", welcomeVisible);
        super.onSaveInstanceState(state);
    }

    @Override public void onBackPressed() {
        if (welcomeVisible && getPreferences(MODE_PRIVATE).getBoolean("intro_seen", false)) showHome();
        else super.onBackPressed();
    }

    private void styleSystemBars(boolean welcome) {
        int light = android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        var decor = getWindow().getDecorView();
        int flags = decor.getSystemUiVisibility();
        decor.setSystemUiVisibility(welcome ? flags & ~light : flags | light);
        getWindow().setStatusBarColor(welcome ? 0xff1e1133 : Chat.BG);
        getWindow().setNavigationBarColor(welcome ? 0xff100c22 : Chat.BG);
    }

    private void showHome() {
        welcomeVisible = false;
        styleSystemBars(false);
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

        addHomeHeader(root);

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

        var translation = OmniUi.card(this, root, "Layar dan bahasa");
        OmniUi.toolRow(translation, "Terjemahan", "Teks, foto, riwayat, dan kamus pribadi", "Aa",
            () -> startActivity(new Intent(this, TextDesk.class)));
        OmniUi.toolRow(translation, "Atur terjemah layar", "Kecepatan, area pembacaan, dan hasil terakhir", "≡",
            () -> startActivity(new Intent(this, ScreenTools.class)));
        OmniUi.toolRow(translation, "Kamera", "Baca teks, perbesar, atau bekukan gambar", "◉",
            () -> startActivity(new Intent(this, CameraTranslator.class)));

        var tools = OmniUi.card(this, root, "Ruang AI");
        OmniUi.toolRow(tools, "Belajar", "Catatan, latihan, dan kartu ulasan", "▤", () -> openTool(0));

        var api = OmniUi.card(this, root, "Pengaturan");
        apiStatus = OmniUi.caption(this, "");
        OmniUi.addSpace(api, apiStatus, 4);
        OmniUi.toolRow(api, "Masukkan API key", "Pilih penyedia dan model AI", "⚙", this::configure);
        OmniUi.toolRow(api, "Monitor koneksi", "Latensi, status, dan penggunaan token", "▥", () -> openTool(1));
        OmniUi.toolRow(api, "Atur bubble", "Ukuran, tepian, dan transparansi", "◌", () -> openTool(2));

        var platforms = OmniUi.card(this, root, "");
        var platformNames = OmniUi.caption(this, String.join(" · ", PlatformOrbitView.PLATFORMS)
            + ". Wayground sebelumnya bernama Quizizz. Dukungan mengikuti opsi yang dapat dibaca Chrome.");
        platformNames.setVisibility(android.view.View.GONE);
        platforms.addView(Chat.button(this, "Platform kuis", false, () -> {
            boolean open = platformNames.getVisibility() == android.view.View.VISIBLE;
            platformNames.setVisibility(open ? android.view.View.GONE : android.view.View.VISIBLE);
        }));
        OmniUi.addSpace(platforms, platformNames, 7);
        OmniUi.addSpace(platforms, Chat.button(this, "Lihat layar pembuka", false, this::showWelcome), 8);
        setContentView(scroll);
        updateStatus();
    }

    private void addHomeHeader(LinearLayout root) {
        var header = Chat.column(this);
        int margin = Chat.dp(this, 20);
        header.setPadding(margin, margin, margin, margin);
        header.setBackground(OmniUi.hero(this));
        var brand = new LinearLayout(this);
        brand.setGravity(android.view.Gravity.CENTER_VERTICAL);
        var mark = new ImageView(this);
        mark.setImageResource(R.drawable.omni);
        mark.setImportantForAccessibility(android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        brand.addView(mark, new LinearLayout.LayoutParams(Chat.dp(this, 40), Chat.dp(this, 40)));
        var title = OmniUi.title(this, "Omni Float AI", 26);
        title.setTextColor(0xfffff7f2);
        var titleLayout = new LinearLayout.LayoutParams(0, -2, 1);
        titleLayout.leftMargin = Chat.dp(this, 13);
        brand.addView(title, titleLayout);
        header.addView(brand, new LinearLayout.LayoutParams(-1, -2));
        OmniUi.addSpace(header, Chat.label(this, "Terjemah, baca layar, dan belajar.", 14, 0xffefe1ff), 12);
        root.addView(header, new LinearLayout.LayoutParams(-1, -2));
    }

    @Override protected void onResume() {
        super.onResume();
        updateStatus();
    }

    private void scheduleStatus() {
        if (floatingStatus != null) floatingStatus.postDelayed(this::updateStatus, 350);
    }

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
