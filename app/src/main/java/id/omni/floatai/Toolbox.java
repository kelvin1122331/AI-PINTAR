package id.omni.floatai;

import android.app.*;
import android.content.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.Executors;

public final class Toolbox extends Activity {
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final java.util.concurrent.ExecutorService worker = Executors.newCachedThreadPool();
    private Providers.Cancellation call;
    private java.util.concurrent.Future<?> task;
    private int revision;
    private LinearLayout body;
    private TextView feedback;
    private TextView answer;
    private EditText input;
    private Spinner mode;
    private Spinner style;
    private String last = "";
    private int tab;
    private final Runnable update = this::refreshMonitor;

    static void copy(Context context, String text) {
        if (text.isEmpty()) { Toast.makeText(context, "Belum ada teks.", Toast.LENGTH_SHORT).show(); return; }
        context.getSystemService(android.content.ClipboardManager.class)
            .setPrimaryClip(ClipData.newPlainText("Omni Float AI", text));
        Toast.makeText(context, "Teks disalin.", Toast.LENGTH_SHORT).show();
    }

    static void applyInsets(View view, int pad) {
        if (Build.VERSION.SDK_INT >= 30) view.setOnApplyWindowInsetsListener((v, insets) -> {
            var b = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
            v.setPadding(b.left + pad, b.top + pad, b.right + pad, b.bottom + pad);
            return insets;
        });
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        render(getIntent().getIntExtra("tab", 0));
    }

    private void render(int page) {
        cancel();
        tab = page;
        var root = Chat.column(this);
        int pad = Chat.dp(this, 16);
        root.setPadding(pad, pad, pad, pad);
        applyInsets(root, pad);
        root.setBackgroundColor(Chat.BG);
        var title = Chat.label(this, "Ruang AI", 28, Chat.INK);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        root.addView(title);
        var tabs = new LinearLayout(this);
        tabs.addView(Chat.button(this, "Belajar", page == 0, () -> render(0)), new LinearLayout.LayoutParams(0,-2,1));
        tabs.addView(Chat.button(this, "Koneksi", page == 1, () -> render(1)), new LinearLayout.LayoutParams(0,-2,1));
        tabs.addView(Chat.button(this, "Bubble", page == 2, () -> render(2)), new LinearLayout.LayoutParams(0,-2,1));
        root.addView(tabs);
        var scroll = new ScrollView(this);
        body = Chat.column(this);
        body.setPadding(0, pad, 0, pad);
        scroll.addView(body);
        root.addView(scroll, new LinearLayout.LayoutParams(-1,0,1));
        setContentView(root);
        if (page == 0) study();
        if (page == 1) monitor();
        if (page == 2) bubble();
    }

    private Spinner picker(String label, String[] choices, int selected) {
        body.addView(Chat.label(this, label, 13, Chat.MUTED));
        var field = new Spinner(this);
        field.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, choices));
        field.setSelection(selected);
        body.addView(field, new LinearLayout.LayoutParams(-1,Chat.dp(this,48)));
        return field;
    }

    private void study() {
        body.addView(Chat.label(this, "AI Study Mode", 21, Chat.INK));
        mode = picker("Jenis bantuan", StudyTools.MODES, 0);
        style = picker("Panjang respons", new String[]{"Singkat", "Seimbang", "Mendalam"}, 1);
        input = new EditText(this);
        input.setHint("Topik, catatan, soal, atau jawabanmu");
        input.setMinLines(3);
        input.setGravity(Gravity.TOP | Gravity.START);
        input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(10000)});
        var prefs = StudyTools.prefs(this);
        input.setText(prefs.getString("draft", ""));
        if (getIntent().hasExtra("studyText") && !getIntent().getStringExtra("studyText").isEmpty()) {
            input.setText(getIntent().getStringExtra("studyText"));
            getIntent().removeExtra("studyText");
        }
        body.addView(input);
        button("Buat", true, this::generate);
        button("Hentikan", false, () -> { cancel(); feedback.setText("Permintaan dihentikan."); });
        feedback = Chat.label(this, "", 14, Chat.PURPLE);
        body.addView(feedback);
        answer = Chat.label(this, last, 16, Chat.INK);
        answer.setTextIsSelectable(true);
        answer.setPadding(0, Chat.dp(this,12), 0, Chat.dp(this,12));
        body.addView(answer);
        button("Salin hasil", false, () -> copy(this, last));
        button("Bagikan hasil", false, () -> {
            if (last.isEmpty()) return;
            startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, last), "Bagikan materi"));
        });
        button("Simpan ke catatan", false, this::saveNote);
        button("Buka catatan", false, this::notes);
        button("Belajar 25 menit", false, this::timer);
    }

    private void generate() {
        String prompt = input.getText().toString().trim();
        if (prompt.isEmpty()) { feedback.setText("Isi materi terlebih dulu."); return; }
        StudyTools.prefs(this).edit().putString("draft", prompt).apply();
        int modeIndex = mode.getSelectedItemPosition(), detail = style.getSelectedItemPosition();
        request("Menyiapkan materi…", () -> StudyTools.request(modeIndex, prompt, detail), answerText -> {
            last = answerText;
            answer.setText(last);
            feedback.setText("Materi siap.");
        });
    }

    private void saveNote() {
        if (last.isEmpty()) { feedback.setText("Buat materi terlebih dulu."); return; }
        var prefs = StudyTools.prefs(this);
        try {
            var old = new org.json.JSONArray(prefs.getString("notes", "[]"));
            var notes = new org.json.JSONArray().put(last);
            for (int i = 0; i < Math.min(29, old.length()); i++) notes.put(old.getString(i));
            prefs.edit().putString("notes", notes.toString()).apply();
            feedback.setText("Disimpan ke catatan.");
        } catch (Exception error) { feedback.setText("Catatan belum dapat disimpan."); }
    }

    private void notes() {
        var prefs = StudyTools.prefs(this);
        try {
            var items = new org.json.JSONArray(prefs.getString("notes", "[]"));
            if (items.length() == 0) { feedback.setText("Belum ada catatan."); return; }
            var names = new String[items.length()];
            for (int i = 0; i < names.length; i++) {
                String text = items.getString(i).replace('\n', ' ');
                names[i] = text.substring(0, Math.min(text.length(), 70));
            }
            new AlertDialog.Builder(this).setTitle("Catatan tersimpan").setItems(names, (d, index) -> {
                last = items.optString(index);
                answer.setText(last);
            }).setNegativeButton("Tutup", null).show();
        } catch (Exception error) { feedback.setText("Catatan belum dapat dibuka."); }
    }

    private void timer() {
        var text = Chat.label(this, "25:00", 28, Chat.INK);
        text.setGravity(Gravity.CENTER);
        var dialog = new AlertDialog.Builder(this).setTitle("Timer fokus").setView(text)
            .setNegativeButton("Akhiri", null).create();
        var countdown = new CountDownTimer(1_500_000, 1000) {
            public void onTick(long millis) {
                long seconds = (millis + 999) / 1000;
                text.setText(String.format(java.util.Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60));
            }
            public void onFinish() { text.setText("Waktu fokus selesai."); }
        };
        dialog.setOnDismissListener(d -> countdown.cancel());
        dialog.show();
        countdown.start();
    }

    private TextView metrics;
    private void monitor() {
        body.addView(Chat.label(this, "AI Connection Monitor", 21, Chat.INK));
        feedback = Chat.label(this, "", 14, Chat.PURPLE);
        body.addView(feedback);
        button("Tes koneksi AI", true, () -> request("Mengirim ping ke model aktif…",
            () -> new org.json.JSONArray().put(new org.json.JSONObject().put("role", "user").put("content", "Jawab hanya: OK")),
            text -> { feedback.setText("Provider merespons."); refreshMonitor(); }));
        button("Bersihkan riwayat", false, () -> { ConnectionMonitor.clear(); refreshMonitor(); });
        metrics = Chat.label(this, "", 14, Chat.INK);
        metrics.setTextIsSelectable(true);
        body.addView(metrics);
        refreshMonitor();
    }

    private void refreshMonitor() {
        ui.removeCallbacks(update);
        if (tab != 1 || metrics == null) return;
        var history = ConnectionMonitor.history();
        Collections.reverse(history);
        if (history.isEmpty()) metrics.setText("Belum ada permintaan AI pada sesi ini.");
        else {
            var text = new StringBuilder("Estimasi token untuk teks. Gambar tidak dihitung.\n");
            for (var event : history) {
                text.append("\n").append(event.model()).append("  •  ").append(event.millis()).append(" ms\n")
                    .append("≈ ").append(event.input()).append(" masuk / ").append(event.output()).append(" keluar\n")
                    .append(event.error() == null ? "Berhasil" : event.error()).append("\n");
            }
            metrics.setText(text);
        }
        ui.postDelayed(update, 1200);
    }

    private void bubble() {
        var prefs = BubbleService.appearance(this);
        body.addView(Chat.label(this, "Smart Floating Bubble", 21, Chat.INK));
        slider("Ukuran", 32, 68, prefs.getInt("size", 56), " dp", v -> prefs.edit().putInt("size", v).apply());
        slider("Transparansi", 15, 100, prefs.getInt("opacity", 100), "% terlihat",
            v -> prefs.edit().putInt("opacity", v).apply());
        var snap = new Switch(this);
        snap.setText("Tempel otomatis ke tepi");
        snap.setChecked(prefs.getBoolean("snap", false));
        snap.setOnCheckedChangeListener((v, checked) -> {
            prefs.edit().putBoolean("snap", checked).apply();
            applyAppearance();
        });
        body.addView(snap);
        button("Reset posisi dan tampilan", false, () -> {
            prefs.edit().clear().apply();
            applyAppearance();
            render(2);
        });
    }

    private void slider(String name, int minimum, int maximum, int value, String unit, java.util.function.IntConsumer change) {
        var label = Chat.label(this, name + " · " + value + unit, 15, Chat.INK);
        body.addView(label);
        var range = new SeekBar(this);
        range.setMax(maximum - minimum);
        range.setProgress(value - minimum);
        range.setContentDescription(name);
        range.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onStartTrackingTouch(SeekBar bar) {}
            public void onStopTrackingTouch(SeekBar bar) { applyAppearance(); }
            public void onProgressChanged(SeekBar bar, int progress, boolean user) {
                label.setText(name + " · " + (minimum + progress) + unit);
                if (user) change.accept(minimum + progress);
            }
        });
        body.addView(range);
    }

    private void applyAppearance() {
        if (BubbleService.active) startService(new Intent(this, BubbleService.class).setAction(BubbleService.APPEARANCE));
    }

    private interface Messages { org.json.JSONArray get() throws Exception; }

    private void request(String waiting, Messages messages, java.util.function.Consumer<String> success) {
        cancel();
        int ticket = revision;
        feedback.setText(waiting);
        var cancellation = new Providers.Cancellation();
        call = cancellation;
        ui.postDelayed(() -> {
            if (ticket != revision || call != cancellation) return;
            cancel();
            feedback.setText("Provider belum merespons setelah 55 detik.");
        }, 55000);
        task = worker.submit(() -> {
            try {
                var profile = Providers.load(this, Providers.active(this));
                if (profile.model().isEmpty()) throw new IllegalStateException("Atur model dan API key terlebih dulu.");
                var result = Providers.complete(profile, messages.get(), "", cancellation);
                ui.post(() -> {
                    if (ticket != revision) return;
                    call = null;
                    success.accept(result.text());
                });
            } catch (Exception error) {
                ui.post(() -> {
                    if (ticket != revision) return;
                    call = null;
                    feedback.setText(ConnectionMonitor.describe(error));
                });
            }
        });
    }

    private void button(String label, boolean primary, Runnable action) {
        var params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = Chat.dp(this, 8);
        body.addView(Chat.button(this, label, primary, action), params);
    }

    private void cancel() {
        revision++;
        if (task != null) task.cancel(true);
        if (call != null) call.cancel();
        task = null;
        call = null;
    }

    @Override protected void onDestroy() {
        cancel();
        ui.removeCallbacksAndMessages(null);
        worker.shutdownNow();
        super.onDestroy();
    }
}
