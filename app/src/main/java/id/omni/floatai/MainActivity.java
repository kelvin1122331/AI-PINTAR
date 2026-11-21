package id.omni.floatai;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
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

    private EditText field(LinearLayout parent, String title, int type) {
        parent.addView(Chat.label(this, title, 13, Chat.MUTED));
        var edit = new EditText(this);
        edit.setSingleLine(true);
        edit.setInputType(type);
        parent.addView(edit);
        return edit;
    }

    private void configure() {
        var form = Chat.column(this);
        int pad = Chat.dp(this, 20);
        form.setPadding(pad, pad, pad, pad);
        var provider = new Spinner(this);
        var adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, Providers.NAMES);
        provider.setAdapter(adapter);
        form.addView(provider);
        var endpoint = field(form, "URL chat completions", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        var model = field(form, "ID model dari provider", InputType.TYPE_CLASS_TEXT);
        var key = field(form, "API key", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        var hint = Chat.label(this, "", 13, Chat.MUTED);
        form.addView(hint);
        int initial = Providers.active(this);
        provider.setSelection(initial);
        provider.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onNothingSelected(AdapterView<?> view) {}
            public void onItemSelected(AdapterView<?> view, android.view.View item, int position, long id) {
                try {
                    Providers.Profile profile = Providers.load(MainActivity.this, position);
                    endpoint.setText(profile.endpoint());
                    model.setText(profile.model());
                    key.setText(profile.key());
                    hint.setText("Pesan dikirim ke endpoint HTTPS yang kamu simpan.");
                } catch (Exception exception) {
                    endpoint.setText(Providers.ENDPOINTS[position]);
                    model.setText("");
                    key.setText("");
                    hint.setText("Simpan ulang API key untuk profil ini.");
                }
            }
        });
        var scroll = new ScrollView(this);
        scroll.addView(form);
        var dialog = new AlertDialog.Builder(this).setTitle("Pengaturan API").setView(scroll)
            .setPositiveButton("Simpan & pakai", null).setNegativeButton("Batal", null).create();
        dialog.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            try {
                int index = provider.getSelectedItemPosition();
                Providers.save(this, index, new Providers.Profile(endpoint.getText().toString().trim(),
                    model.getText().toString().trim(), key.getText().toString().trim()));
                Chat.SESSION.clear();
                dialog.dismiss();
            } catch (IllegalArgumentException exception) {
                hint.setText(exception.getMessage());
            } catch (Exception exception) {
                hint.setText("URL atau penyimpanan API key bermasalah. Cek kembali pengaturan.");
            }
        });
    }
}
