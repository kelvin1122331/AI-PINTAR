package id.omni.floatai;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import java.util.Locale;
import java.util.concurrent.Executors;

public final class TextDesk extends Activity {
    private static final int PHOTO = 520;
    private static final int LIMIT = 4000;
    private final java.util.concurrent.ExecutorService files = Executors.newSingleThreadExecutor();
    private DeviceTranslator translator;
    private EditText input;
    private TextView status;
    private TextView output;
    private Spinner from;
    private Spinner to;
    private SharedPreferences settings;
    private TranslationJournal.Entry latest;
    private int generation;
    private boolean settingLanguages;
    private TextToSpeech voice;
    private boolean voiceReady;
    private boolean speakOnReady;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        settings = TranslationPreferences.of(this);
        translator = new DeviceTranslator();
        var scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        var root = Chat.column(this);
        int pad = Chat.dp(this, 16);
        root.setBackgroundColor(Chat.BG);
        root.setPadding(pad, pad, pad, pad);
        Toolbox.applyInsets(root, pad);
        root.addView(OmniUi.title(this, "Terjemahan", 30));
        root.addView(OmniUi.caption(this, "Terjemah teks dan foto di perangkat."));
        var workspace = OmniUi.card(this, root, "Teks dan foto");
        var lang = new LinearLayout(this);
        String[] sources = new String[TranslationPreferences.NAMES.length + 1];
        sources[0] = "Otomatis";
        System.arraycopy(TranslationPreferences.NAMES, 0, sources, 1, sources.length - 1);
        from = select(lang, "Bahasa sumber", sources, TranslationPreferences.index(TranslationPreferences.source(this)) + 1);
        to = select(lang, "Bahasa tujuan", TranslationPreferences.NAMES, Math.max(0, TranslationPreferences.index(TranslationPreferences.target(this))));
        workspace.addView(lang);
        var langButtons = new LinearLayout(this);
        OmniUi.rowButton(langButtons, "Tukar bahasa", false, this::swap);
        OmniUi.rowButton(langButtons, "Pilih foto", false, () -> startActivityForResult(LocalDocuments.open("image/*"), PHOTO));
        OmniUi.addSpace(workspace, langButtons, 8);
        input = new EditText(this);
        input.setHint("Ketik atau tempel teks");
        input.setGravity(Gravity.TOP | Gravity.START);
        input.setMinLines(3);
        input.setMaxLines(7);
        input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(LIMIT)});
        if (state != null) input.setText(state.getString("text", ""));
        OmniUi.addSpace(workspace, input, 10);
        OmniUi.addSpace(workspace, Chat.button(this, "Terjemahkan teks", true, this::translate), 8);
        status = OmniUi.caption(this, "Unduhan model pertama memerlukan internet.");
        OmniUi.addSpace(workspace, status, 8);
        output = Chat.label(this, "", 17, Chat.INK);
        output.setTextIsSelectable(true);
        output.setLineSpacing(Chat.dp(this, 3), 1f);
        OmniUi.addSpace(workspace, output, 10);
        var actions = new LinearLayout(this);
        OmniUi.rowButton(actions, "Salin", false, () -> Toolbox.copy(this, output.getText().toString()));
        OmniUi.rowButton(actions, "Bacakan", false, this::speak);
        OmniUi.rowButton(actions, "Favoritkan", false, () -> {
            if (latest == null) { status.setText("Terjemahkan teks terlebih dulu."); return; }
            TranslationJournal.save(this, true, latest);
            status.setText("Disimpan ke favorit.");
        });
        OmniUi.addSpace(workspace, actions, 8);
        var library = OmniUi.card(this, root, "Tersimpan");
        var history = new Switch(this);
        history.setText("Simpan riwayat terjemahan manual");
        history.setChecked(settings.getBoolean("save_history", false));
        history.setOnCheckedChangeListener((view, checked) -> settings.edit().putBoolean("save_history", checked).apply());
        library.addView(history);
        var books = new LinearLayout(this);
        OmniUi.rowButton(books, "Riwayat", false, () -> journal(false));
        OmniUi.rowButton(books, "Favorit", false, () -> journal(true));
        OmniUi.addSpace(library, books, 7);
        var look = OmniUi.card(this, root, "Tampilan overlay");
        var scale = new TextView(this);
        scale.setTextColor(Chat.INK);
        int current = settings.getInt("text_size", 100);
        scale.setText("Ukuran teks · " + current + "%");
        look.addView(scale);
        var seek = new SeekBar(this);
        seek.setContentDescription("Ukuran teks terjemahan");
        seek.setMax(60);
        seek.setProgress(current - 80);
        look.addView(seek);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onStartTrackingTouch(SeekBar bar) {}
            public void onStopTrackingTouch(SeekBar bar) {}
            public void onProgressChanged(SeekBar bar, int progress, boolean user) {
                scale.setText("Ukuran teks · " + (80 + progress) + "%");
                if (user) settings.edit().putInt("text_size", 80 + progress).apply();
            }
        });
        var theme = new Switch(this);
        theme.setText("Kontras terang");
        theme.setChecked(settings.getBoolean("light", false));
        theme.setOnCheckedChangeListener((view, checked) -> settings.edit().putBoolean("light", checked).apply());
        look.addView(theme);
        OmniUi.addSpace(look, OmniUi.caption(this, "Bahasa dan tampilan juga dipakai oleh terjemah layar serta kamera. OCR membaca aksara Latin."), 8);
        scroll.addView(root);
        setContentView(scroll);
        var listener = new AdapterView.OnItemSelectedListener() {
            public void onNothingSelected(AdapterView<?> view) {}
            public void onItemSelected(AdapterView<?> view, View row, int index, long id) {
                if (settingLanguages) return;
                String source = source();
                String target = target();
                if (source.equals(TranslationPreferences.source(TextDesk.this)) &&
                    target.equals(TranslationPreferences.target(TextDesk.this))) return;
                updateLanguages();
                generation++;
                status.setText("Bahasa diperbarui.");
            }
        };
        from.setOnItemSelectedListener(listener);
        to.setOnItemSelectedListener(listener);
    }

    private Spinner select(LinearLayout row, String label, String[] labels, int selection) {
        var column = Chat.column(this);
        column.addView(OmniUi.caption(this, label));
        var spinner = new Spinner(this);
        spinner.setContentDescription(label);
        spinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, labels));
        spinner.setSelection(selection);
        column.addView(spinner, new LinearLayout.LayoutParams(-1, Chat.dp(this, 48)));
        row.addView(column, new LinearLayout.LayoutParams(0, -2, 1));
        return spinner;
    }

    private String source() {
        int index = from.getSelectedItemPosition() - 1;
        return index < 0 ? "auto" : TranslationPreferences.CODES[index];
    }
    private String target() { return TranslationPreferences.CODES[Math.max(0, to.getSelectedItemPosition())]; }
    private void updateLanguages() {
        settings.edit().putString("source", source()).putString("target", target()).apply();
    }

    private void setLanguages(String source, String target) {
        settingLanguages = true;
        from.setSelection(TranslationPreferences.index(source) + 1);
        to.setSelection(Math.max(0, TranslationPreferences.index(target)));
        settings.edit().putString("source", TranslationPreferences.index(source) < 0 ? "auto" : source)
            .putString("target", target).apply();
        settingLanguages = false;
    }

    private void swap() {
        String a = source();
        if (a.equals("auto") && latest != null) a = latest.source();
        if (TranslationPreferences.index(a) < 0) {
            status.setText("Pilih bahasa sumber atau terjemahkan teks sebelum menukar.");
            return;
        }
        String b = target();
        generation++;
        setLanguages(b, a);
        if (latest != null) input.setText(latest.translated());
        latest = null;
        output.setText("");
        status.setText("Bahasa ditukar.");
    }

    private void translate() {
        String text = input.getText().toString().trim();
        if (text.isEmpty()) { status.setText("Masukkan teks terlebih dulu."); return; }
        updateLanguages();
        int ticket = ++generation;
        String toCode = target();
        status.setText("Menerjemahkan…");
        translator.translateText(text, source(), toCode, message -> update(ticket, message), result -> show(ticket, toCode, result));
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != PHOTO || result != RESULT_OK || data == null || data.getData() == null) return;
        var uri = data.getData();
        int ticket = ++generation;
        String fromCode = source(), toCode = target();
        status.setText("Membaca foto…");
        files.execute(() -> {
            try {
                Bitmap bitmap = LocalDocuments.readImage(getContentResolver(), uri);
                runOnUiThread(() -> {
                    if (ticket != generation || isDestroyed()) { bitmap.recycle(); return; }
                    translator.translatePhoto(bitmap, fromCode, toCode,
                        message -> update(ticket, message), translated -> {
                            if (ticket == generation && !translated.original().isEmpty()) input.setText(translated.original());
                            show(ticket, toCode, translated);
                        });
                });
            } catch (Exception error) { runOnUiThread(() -> update(ticket, "Foto tidak dapat dibuka.")); }
        });
    }

    private void update(int ticket, String message) {
        if (ticket == generation && !isDestroyed()) status.setText(message);
    }

    private void show(int ticket, String target, DeviceTranslator.TextResult result) {
        if (ticket != generation || isDestroyed()) return;
        if (result.error() != null) { status.setText(result.error()); return; }
        latest = new TranslationJournal.Entry(result.original(), result.text(), result.source(), target);
        output.setText(result.text());
        status.setText(TranslationPreferences.name(result.source()) + " → " + TranslationPreferences.name(target));
        if (settings.getBoolean("save_history", false)) TranslationJournal.save(this, false, latest);
    }

    private void journal(boolean favorites) {
        var results = TranslationJournal.read(this, favorites);
        if (results.isEmpty()) { status.setText(favorites ? "Belum ada favorit." : "Riwayat masih kosong."); return; }
        var names = new String[results.size()];
        for (int i = 0; i < names.length; i++) {
            String preview = results.get(i).translated().replace('\n', ' ');
            names[i] = preview.substring(0, Math.min(preview.length(), 95));
        }
        new AlertDialog.Builder(this).setTitle(favorites ? "Favorit terjemahan" : "Riwayat terjemahan")
            .setItems(names, (dialog, index) -> {
                var entry = results.get(index);
                new AlertDialog.Builder(this).setTitle("Terjemahan tersimpan").setMessage(entry.translated())
                    .setPositiveButton("Buka", (d, w) -> {
                        generation++;
                        latest = entry;
                        setLanguages(entry.source(), entry.target());
                        input.setText(entry.original());
                        output.setText(entry.translated());
                        status.setText("Terjemahan tersimpan dibuka.");
                    }).setNeutralButton("Hapus", (d, w) -> {
                        TranslationJournal.remove(this, favorites, entry);
                        status.setText("Item dihapus.");
                    }).setNegativeButton("Tutup", null).show();
            }).setNegativeButton("Tutup", null).show();
    }

    private void speak() {
        if (latest == null) { status.setText("Belum ada terjemahan untuk dibacakan."); return; }
        if (voiceReady) { speakLatest(); return; }
        speakOnReady = true;
        status.setText("Menyiapkan suara Android…");
        if (voice == null) voice = new TextToSpeech(this, result -> runOnUiThread(() -> {
            if (isDestroyed()) return;
            voiceReady = result == TextToSpeech.SUCCESS;
            if (voiceReady && speakOnReady) speakLatest();
            if (!voiceReady) {
                status.setText("Mesin suara Android tidak tersedia.");
                if (voice != null) voice.shutdown();
                voice = null;
            }
        }));
    }

    private void speakLatest() {
        if (latest == null) return;
        String value = latest.translated();
        if (value.length() > TextToSpeech.getMaxSpeechInputLength()) {
            status.setText("Hasil terlalu panjang untuk dibacakan sekaligus.");
            return;
        }
        int language = voice.setLanguage(Locale.forLanguageTag(latest.target()));
        if (language < 0) { status.setText("Paket suara bahasa ini belum terpasang."); return; }
        int result = voice.speak(value, TextToSpeech.QUEUE_FLUSH, null, "translation");
        status.setText(result == TextToSpeech.SUCCESS ? "Membacakan terjemahan." : "Terjemahan tidak dapat dibacakan.");
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString("text", input.getText().toString());
        super.onSaveInstanceState(state);
    }

    @Override protected void onStop() {
        if (voice != null) voice.stop();
        super.onStop();
    }

    @Override protected void onDestroy() {
        generation++;
        files.shutdownNow();
        translator.close();
        if (voice != null) voice.shutdown();
        super.onDestroy();
    }
}
