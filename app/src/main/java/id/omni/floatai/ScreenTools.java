package id.omni.floatai;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;

public final class ScreenTools extends Activity {
    private final java.util.concurrent.ExecutorService files = java.util.concurrent.Executors.newSingleThreadExecutor();
    private String pending;
    private android.widget.TextView status;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (state != null) pending = state.getString("export");
        boolean read = getIntent().getBooleanExtra("readback", false);
        var root = Chat.column(this);
        int pad = Chat.dp(this, 18);
        root.setBackgroundColor(Chat.BG);
        Toolbox.applyInsets(root, pad);
        root.setPadding(pad, pad, pad, pad);
        var scroll = new ScrollView(this);
        scroll.addView(root);
        if (read) showReadback(root);
        else showOptions(root);
        setContentView(scroll);
    }

    private void showOptions(LinearLayout root) {
        OmniUi.pageHeader(root, "Terjemah layar", "Atur cara teks dibaca dan ditampilkan.");
        var values = ScreenOptions.read(this);
        var reading = OmniUi.card(this, root, "Pembacaan");
        option(reading, "Kecepatan pembaruan", "screen_speed", ScreenOptions.SPEEDS, values.speed());
        option(reading, "Area pembacaan", "screen_area", ScreenOptions.AREAS, values.area());
        option(reading, "Susunan terjemahan", "screen_format", ScreenOptions.FORMATS, values.format());
        var display = OmniUi.card(this, root, "Tampilan");
        var opacity = OmniUi.caption(this, "Kepekatan teks layar · " + values.opacity() + "%");
        OmniUi.addSpace(display, opacity, 6);
        var slider = new SeekBar(this);
        slider.setMax(25);
        slider.setProgress(values.opacity() - 45);
        slider.setContentDescription("Kepekatan teks layar");
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onStartTrackingTouch(SeekBar bar) {}
            public void onStopTrackingTouch(SeekBar bar) { notifyBubble(); }
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                opacity.setText("Kepekatan teks layar · " + (45 + progress) + "%");
                if (fromUser) TranslationPreferences.of(ScreenTools.this).edit()
                    .putInt("screen_opacity", 45 + progress).apply();
            }
        });
        display.addView(slider);
        OmniUi.toolRow(display, "Hasil layar terakhir", "Lihat teks asli dan terjemahannya", "≡", () ->
            startActivity(new Intent(this, ScreenTools.class).putExtra("readback", true)));
    }

    private void option(LinearLayout card, String title, String key, String[] options, int current) {
        var summary = OmniUi.caption(this, options[current]);
        OmniUi.addSpace(card, Chat.button(this, title, false, () -> new AlertDialog.Builder(this)
            .setTitle(title).setItems(options, (dialog, selection) -> {
                TranslationPreferences.of(this).edit().putInt(key, selection).apply();
                summary.setText(options[selection]);
                notifyBubble();
            }).setNegativeButton("Tutup", null).show()), 8);
        OmniUi.addSpace(card, summary, 4);
    }

    private void notifyBubble() {
        if (BubbleService.active) startService(new Intent(this, BubbleService.class).setAction(BubbleService.SCREEN_OPTIONS));
    }

    private void showReadback(LinearLayout root) {
        var labels = ScreenReadback.latest();
        OmniUi.pageHeader(root, "Hasil layar terakhir", labels.isEmpty()
            ? "Aktifkan terjemah layar untuk melihat hasilnya di sini." : labels.size() + " bagian diterjemahkan");
        if (labels.isEmpty()) return;
        var actions = new LinearLayout(this);
        String text = ScreenReadback.text(labels, true);
        OmniUi.rowButton(actions, "Salin", true, () -> Toolbox.copy(this, text));
        OmniUi.rowButton(actions, "Ekspor TXT", false, () -> {
            pending = text;
            startActivityForResult(LocalDocuments.saveText("Terjemah_Layar.txt"), 1);
        });
        OmniUi.addSpace(root, actions, 12);
        status = OmniUi.caption(this, "");
        OmniUi.addSpace(root, status, 5);
        for (var label : labels) {
            var panel = OmniUi.card(this, root, "");
            if (!label.original().isEmpty()) {
                var original = OmniUi.caption(this, label.original());
                original.setTextIsSelectable(true);
                panel.addView(original);
            }
            var translated = Chat.label(this, label.value(), 17, Chat.INK);
            translated.setTextIsSelectable(true);
            OmniUi.addSpace(panel, translated, 6);
        }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        String outgoing = pending;
        pending = null;
        if (request != 1 || result != RESULT_OK || outgoing == null || data == null || data.getData() == null) return;
        var uri = data.getData();
        files.execute(() -> {
            try {
                LocalDocuments.writeText(getContentResolver(), uri, outgoing);
                report("Hasil layar disimpan.");
            } catch (Exception error) { report("Hasil layar belum dapat disimpan."); }
        });
    }

    private void report(String text) {
        runOnUiThread(() -> { if (!isDestroyed() && status != null) status.setText(text); });
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString("export", pending);
        super.onSaveInstanceState(state);
    }

    @Override protected void onDestroy() {
        files.shutdownNow();
        super.onDestroy();
    }
}
