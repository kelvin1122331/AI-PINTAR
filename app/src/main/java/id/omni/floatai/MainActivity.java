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
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        var root = Chat.column(this);
        root.setBackgroundColor(Chat.BG);
        int pad = Chat.dp(this, 12);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                var sides = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime() | WindowInsets.Type.displayCutout());
                root.setPadding(sides.left + pad, sides.top + pad, sides.right + pad, sides.bottom + pad);
            }
            return insets;
        });
        root.setPadding(pad, pad, pad, pad);
        root.addView(Chat.SESSION.panel(this, null, null, this::configure), new LinearLayout.LayoutParams(-1, 0, 1));
        var controls = new LinearLayout(this);
        controls.addView(Chat.button(this, "Aktifkan bubble", true, this::activate), new LinearLayout.LayoutParams(0, -2, 1));
        controls.addView(Chat.button(this, "Matikan", false, () -> {
            stopService(new Intent(this, BubbleService.class));
            Toast.makeText(this, "Bubble dimatikan", Toast.LENGTH_SHORT).show();
        }), new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(controls);
        setContentView(root);
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
        new ApiSettings(this).show();
    }
}

