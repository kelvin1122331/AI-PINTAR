package id.omni.floatai;

import android.app.*;
import android.content.*;
import android.os.*;
import android.text.Editable;
import android.text.TextWatcher;
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
    private static final int IMPORT_TEXT = 621;
    private static final int EXPORT_TEXT = 622;
    private static final int IMPORT_CARDS = 623;
    private String pendingExport;
    private int monitorOutcome;
    private String monitorModel = "";
    private TextView monitorSummary;

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
        if (state != null) {
            pendingExport = state.getString("export");
            last = state.getString("result", "");
            monitorOutcome = state.getInt("outcome", 0);
            monitorModel = state.getString("modelFilter", "");
        }
        render(state != null ? state.getInt("tab", 0) : getIntent().getIntExtra("tab", 0));
    }

    private void render(int page) {
        saveDraft();
        cancel();
        ui.removeCallbacks(update);
        tab = page;
        var root = Chat.column(this);
        int pad = Chat.dp(this, 16);
        root.setPadding(pad, pad, pad, pad);
        applyInsets(root, pad);
        root.setBackgroundColor(Chat.BG);
        OmniUi.pageHeader(root, "Ruang AI", switch (page) {
            case 1 -> "Cek respons penyedia dan model.";
            case 2 -> "Sesuaikan tombol floating.";
            default -> "Olah materi, simpan catatan, dan ulas kartu.";
        });
        var tabs = new LinearLayout(this);
        OmniUi.rowButton(tabs, "Belajar", page == 0, () -> render(0));
        OmniUi.rowButton(tabs, "Koneksi", page == 1, () -> render(1));
        OmniUi.rowButton(tabs, "Bubble", page == 2, () -> render(2));
        OmniUi.addSpace(root, tabs, 8);
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
        body = OmniUi.card(this, body, "AI Study Mode");
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
        var inputActions = new LinearLayout(this);
        OmniUi.rowButton(inputActions, "Impor TXT", false, () -> startActivityForResult(LocalDocuments.open("text/*"), IMPORT_TEXT));
        OmniUi.rowButton(inputActions, "Buat", true, this::generate);
        OmniUi.addSpace(body, inputActions, 8);
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
        button("Ekspor hasil TXT", false, () -> export("Omni_Catatan.txt", last));
        button("Simpan ke catatan", false, this::saveNote);
        button("Cari dan buka catatan", false, this::notes);
        button("Jadikan kartu ulasan", false, () -> {
            int added = ReviewDeck.add(this, last, System.currentTimeMillis());
            feedback.setText(added == 0 ? "Buat flashcard dengan format Depan | Belakang. Kartu yang sudah ada tidak diduplikasi."
                : added + " kartu masuk ke jadwal ulasan.");
        });
        button("Ulas kartu hari ini", false, this::review);
        var deckActions = new LinearLayout(this);
        OmniUi.rowButton(deckActions, "Impor kartu TSV", false, () -> startActivityForResult(LocalDocuments.open("*/*"), IMPORT_CARDS));
        OmniUi.rowButton(deckActions, "Ekspor kartu TSV", false, this::exportDeck);
        OmniUi.addSpace(body, deckActions, 8);
        button("Statistik belajar", false, this::reviewStats);
        button("Belajar 25 menit", false, this::timer);
    }

    private void generate() {
        String prompt = input.getText().toString().trim();
        if (prompt.isEmpty()) { feedback.setText("Isi materi terlebih dulu."); return; }
        StudyTools.prefs(this).edit().putString("draft", prompt).apply();
        int modeIndex = mode.getSelectedItemPosition(), detail = style.getSelectedItemPosition();
        request("Menyiapkan materi.", () -> StudyTools.request(modeIndex, prompt, detail), answerText -> {
            last = answerText;
            answer.setText(last);
            feedback.setText("Materi siap.");
        });
    }

    private void saveNote() {
        if (last.isEmpty()) { feedback.setText("Buat materi terlebih dulu."); return; }
        feedback.setText(StudyNotes.save(this, last) ? "Disimpan ke catatan."
            : "Semua 30 catatan disematkan. Lepas satu sematan untuk menyimpan catatan baru.");
    }

    private void notes() {
        if (StudyNotes.find(this, "").isEmpty()) { feedback.setText("Belum ada catatan."); return; }
        var panel = Chat.column(this);
        int pad = Chat.dp(this, 16);
        panel.setPadding(pad, 0, pad, 0);
        var search = new EditText(this);
        search.setHint("Cari isi catatan");
        panel.addView(search);
        panel.addView(OmniUi.caption(this, "Tahan catatan untuk menyematkannya."));
        var list = new ListView(this);
        panel.addView(list, new LinearLayout.LayoutParams(-1, Chat.dp(this, 280)));
        var matches = new ArrayList<StudyNotes.Note>();
        var adapter = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1);
        list.setAdapter(adapter);
        Runnable filter = () -> {
            adapter.setNotifyOnChange(false);
            adapter.clear();
            matches.clear();
            matches.addAll(StudyNotes.find(this, search.getText().toString()));
            for (var note : matches) {
                String line = note.text().replace('\n', ' ');
                adapter.add((note.pinned() ? "★  " : "") + line.substring(0, Math.min(line.length(), 90)));
            }
            adapter.notifyDataSetChanged();
        };
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) { filter.run(); }
            public void afterTextChanged(Editable s) {}
        });
        filter.run();
        var dialog = new AlertDialog.Builder(this).setTitle("Catatan tersimpan").setView(panel)
            .setNegativeButton("Tutup", null).create();
        list.setOnItemClickListener((v, row, index, rowId) -> {
            last = matches.get(index).text();
            answer.setText(last);
            dialog.dismiss();
        });
        list.setOnItemLongClickListener((v, row, index, rowId) -> {
            var note = matches.get(index);
            String action = note.pinned() ? "Lepas sematan" : "Sematkan catatan";
            new AlertDialog.Builder(this).setTitle(action).setMessage(note.pinned() ? "Kembalikan catatan ke urutan biasa."
                : "Tampilkan catatan ini di atas daftar.").setPositiveButton(action, (confirm, which) -> {
                    StudyNotes.pin(this, note.text(), !note.pinned());
                    filter.run();
                }).setNegativeButton("Batal", null).show();
            return true;
        });
        dialog.show();
    }

    private void reviewStats() {
        var totals = ReviewDeck.stats(this, System.currentTimeMillis());
        String details = totals.total() + " kartu tersimpan\n"
            + totals.due() + " kartu siap diulas\n"
            + totals.longerInterval() + " kartu dengan interval ≥ 7 hari\n\n"
            + "Dinilai ingat · " + totals.correct() + "\n"
            + "Diminta ulang · " + totals.repeat();
        new AlertDialog.Builder(this).setTitle("Statistik belajar").setMessage(details)
            .setPositiveButton("Tutup", null).show();
    }

    private void exportDeck() {
        if (ReviewDeck.read(this).isEmpty()) { showFeedback("Belum ada kartu untuk diekspor."); return; }
        export("Omni_Kartu.tsv", ReviewDeck.exportTsv(this));
    }

    private void review() {
        var due = ReviewDeck.due(this, System.currentTimeMillis());
        if (due.isEmpty()) {
            feedback.setText(ReviewDeck.read(this).isEmpty() ? "Belum ada kartu. Simpan hasil flashcard terlebih dulu."
                : "Belum ada kartu yang jatuh tempo.");
            return;
        }
        var panel = Chat.column(this);
        int pad = Chat.dp(this, 16);
        panel.setPadding(pad, pad, pad, pad);
        var prompt = OmniUi.title(this, "", 20);
        var progress = OmniUi.caption(this, "");
        var solution = Chat.label(this, "", 16, Chat.INK);
        panel.addView(progress);
        OmniUi.addSpace(panel, prompt, 10);
        OmniUi.addSpace(panel, solution, 10);
        var dialog = new AlertDialog.Builder(this).setTitle("Kartu ulasan")
            .setNegativeButton("Selesai", null).create();
        int[] index = {0};
        var choices = new LinearLayout(this);
        var again = Chat.button(this, "Ulangi 10 menit", false, () -> {});
        var remember = Chat.button(this, "Ingat", true, () -> {});
        choices.addView(again, new LinearLayout.LayoutParams(0, -2, 1));
        choices.addView(remember, new LinearLayout.LayoutParams(0, -2, 1));
        var reveal = Chat.button(this, "Lihat jawaban", true, () -> {
            if (index[0] >= due.size()) return;
            solution.setText(due.get(index[0]).back());
            choices.setVisibility(View.VISIBLE);
        });
        OmniUi.addSpace(panel, reveal, 12);
        OmniUi.addSpace(panel, choices, 8);
        Runnable show = () -> {
            choices.setVisibility(View.GONE);
            solution.setText("");
            if (index[0] >= due.size()) {
                prompt.setText("Ulasan selesai.");
                progress.setText(due.size() + " kartu dinilai.");
                reveal.setVisibility(View.GONE);
                return;
            }
            progress.setText((index[0] + 1) + " / " + due.size());
            prompt.setText(due.get(index[0]).front());
        };
        again.setOnClickListener(v -> {
            if (index[0] >= due.size()) return;
            ReviewDeck.grade(this, due.get(index[0]++), false, System.currentTimeMillis());
            show.run();
        });
        remember.setOnClickListener(v -> {
            if (index[0] >= due.size()) return;
            ReviewDeck.grade(this, due.get(index[0]++), true, System.currentTimeMillis());
            show.run();
        });
        var scroller = new ScrollView(this);
        scroller.addView(panel);
        dialog.setView(scroller);
        show.run();
        dialog.show();
    }

    private void export(String fileName, String contents) {
        if (contents.isEmpty()) { feedback.setText("Belum ada hasil untuk diekspor."); return; }
        pendingExport = contents;
        startActivityForResult(LocalDocuments.saveText(fileName), EXPORT_TEXT);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        String exported = request == EXPORT_TEXT ? pendingExport : null;
        if (request == EXPORT_TEXT) pendingExport = null;
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        var uri = data.getData();
        if (request == IMPORT_TEXT) {
            worker.execute(() -> {
                try {
                    String value = LocalDocuments.readText(getContentResolver(), uri);
                    runOnUiThread(() -> {
                        if (isDestroyed() || tab != 0) return;
                        input.setText(value);
                        saveDraft();
                        feedback.setText(value.length() > 10000 ? "Materi dimuat hingga batas 10.000 karakter." : "Materi TXT dimuat.");
                    });
                } catch (Exception error) { runOnUiThread(() -> showFeedback("File TXT tidak dapat dibuka atau melebihi 128 KB.")); }
            });
        } else if (request == IMPORT_CARDS) {
            worker.execute(() -> {
                try {
                    String value = LocalDocuments.readDeck(getContentResolver(), uri);
                    runOnUiThread(() -> {
                        if (isDestroyed()) return;
                        int count = ReviewDeck.importTsv(this, value, System.currentTimeMillis());
                        showFeedback(count == 0 ? "Tidak ada kartu baru. Periksa dua kolom TSV dan kartu duplikat."
                            : count + " kartu berhasil diimpor.");
                    });
                } catch (Exception error) { runOnUiThread(() -> showFeedback("File kartu belum dapat dibaca.")); }
            });
        } else if (request == EXPORT_TEXT && exported != null) {
            worker.execute(() -> {
                try {
                    LocalDocuments.writeText(getContentResolver(), uri, exported);
                    runOnUiThread(() -> showFeedback("File berhasil disimpan."));
                } catch (Exception error) { runOnUiThread(() -> showFeedback("File belum berhasil disimpan.")); }
            });
        }
    }

    private void showFeedback(String value) {
        if (!isDestroyed() && feedback != null && tab != 2) feedback.setText(value);
    }

    private void saveDraft() {
        if (tab == 0 && input != null) StudyTools.prefs(this).edit().putString("draft", input.getText().toString()).apply();
    }

    @Override protected void onStop() {
        ui.removeCallbacks(update);
        saveDraft();
        super.onStop();
    }

    @Override protected void onResume() {
        super.onResume();
        refreshMonitor();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putInt("tab", tab);
        state.putString("export", pendingExport);
        state.putString("result", last);
        state.putInt("outcome", monitorOutcome);
        state.putString("modelFilter", monitorModel);
        super.onSaveInstanceState(state);
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
        body = OmniUi.card(this, body, "AI Connection Monitor");
        monitorSummary = OmniUi.title(this, "", 16);
        OmniUi.addSpace(body, monitorSummary, 6);
        feedback = Chat.label(this, "", 14, Chat.PURPLE);
        body.addView(feedback);
        button("Tes koneksi AI", true, () -> request("Mengirim ping ke model aktif.",
            () -> new org.json.JSONArray().put(new org.json.JSONObject().put("role", "user").put("content", "Jawab hanya: OK")),
            text -> { feedback.setText("Provider merespons."); refreshMonitor(); }));
        button("Tes katalog model", false, () -> startRequest("Memuat katalog model…",
            (profile, cancellation) -> Providers.testCatalog(profile, cancellation) + " model chat tersedia.",
            message -> { feedback.setText(message); refreshMonitor(); }));
        var filter = picker("Filter permintaan", new String[]{"Semua status", "Berhasil", "Gagal"}, monitorOutcome);
        filter.setContentDescription("Filter status koneksi");
        filter.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onNothingSelected(AdapterView<?> view) {}
            public void onItemSelected(AdapterView<?> view, View row, int index, long id) {
                monitorOutcome = index;
                refreshMonitor();
            }
        });
        var model = new EditText(this);
        model.setHint("Cari model");
        model.setSingleLine(true);
        model.setText(monitorModel);
        model.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                monitorModel = s.toString();
                refreshMonitor();
            }
            public void afterTextChanged(Editable s) {}
        });
        body.addView(model);
        button("Ekspor laporan TXT", false, () -> export("Omni_Koneksi.txt", ConnectionMonitor.report(filteredHistory())));
        button("Bersihkan riwayat", false, () -> { ConnectionMonitor.clear(); refreshMonitor(); });
        metrics = Chat.label(this, "", 14, Chat.INK);
        metrics.setTextIsSelectable(true);
        body.addView(metrics);
        refreshMonitor();
    }

    private java.util.List<ConnectionMonitor.Entry> filteredHistory() {
        return ConnectionMonitor.filtered(ConnectionMonitor.history(), monitorOutcome, monitorModel);
    }

    private void refreshMonitor() {
        ui.removeCallbacks(update);
        if (tab != 1 || metrics == null) return;
        var history = filteredHistory();
        var score = ConnectionMonitor.summarize(history);
        monitorSummary.setText(score.total() == 0 ? "Belum ada data koneksi" : score.success() * 100 / score.total()
            + "% berhasil · " + score.total() + " permintaan\nMedian " + score.medianMillis() + " ms · p95 " + score.p95Millis() + " ms");
        Collections.reverse(history);
        if (history.isEmpty()) metrics.setText(ConnectionMonitor.history().isEmpty()
            ? "Belum ada permintaan AI pada sesi ini." : "Tidak ada permintaan yang cocok dengan filter.");
        else {
            var text = new StringBuilder("Estimasi token untuk teks. Gambar tidak dihitung.\n");
            for (var event : history) {
                text.append("\n").append(event.model()).append("  ·  ").append(event.millis()).append(" ms\n")
                    .append("≈ ").append(event.input()).append(" masuk / ").append(event.output()).append(" keluar\n")
                    .append(event.error() == null ? "Berhasil" : event.error()).append("\n");
            }
            metrics.setText(text);
        }
        ui.postDelayed(update, 1200);
    }

    private void bubble() {
        var prefs = BubbleService.appearance(this);
        body = OmniUi.card(this, body, "Smart Floating Bubble");
        OmniUi.addSpace(body, OmniUi.caption(this, "Ketuk untuk membuka panel. Tekan lama untuk pintasan."), 5);
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
    private interface Request { String send(Providers.Profile profile, Providers.Cancellation cancel) throws Exception; }

    private void request(String waiting, Messages messages, java.util.function.Consumer<String> success) {
        startRequest(waiting, (profile, cancellation) -> {
            if (profile.model().isEmpty()) throw new IllegalStateException("Atur model dan API key terlebih dulu.");
            return Providers.complete(profile, messages.get(), "", cancellation).text();
        }, success);
    }

    private void startRequest(String waiting, Request action, java.util.function.Consumer<String> success) {
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
                String result = action.send(profile, cancellation);
                ui.post(() -> {
                    if (ticket != revision) return;
                    call = null;
                    success.accept(result);
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
