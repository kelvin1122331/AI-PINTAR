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
    private static final int EXPORT = 521;
    static final int LIMIT = 4000;
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
    private String pendingExport;
    private android.net.Uri pendingPhoto;
    private boolean settingLanguages;
    private TextToSpeech voice;
    private boolean voiceReady;
    private boolean speakOnReady;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        settings = TranslationPreferences.of(this);
        translator = new DeviceTranslator(this);
        var scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        var root = Chat.column(this);
        int pad = Chat.dp(this, 16);
        root.setBackgroundColor(Chat.BG);
        root.setPadding(pad, pad, pad, pad);
        Toolbox.applyInsets(root, pad);
        root.setFocusableInTouchMode(true);
        OmniUi.pageHeader(root, "Terjemahan", "Teks, foto, dan kamus pribadi.");
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
        OmniUi.addSpace(workspace, Chat.button(this, "Ekspor terjemahan TXT", false, this::export), 7);
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
        OmniUi.toolRow(library, "Pasangan bahasa", "Simpan pengaturan bahasa yang sering dipakai", "⇄", this::pairs);
        OmniUi.toolRow(library, "Kamus pribadi", "Gunakan padanan untuk frasa tertentu", "≡", this::glossary);
        OmniUi.toolRow(library, "Atur tampilan layar", "Ukuran teks, kontras, dan kepekatan", "Aa", () ->
            startActivity(new Intent(this, ScreenTools.class)));
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
        if (state != null) {
            pendingExport = state.getString("export");
            if (state.containsKey("translation")) {
                latest = new TranslationJournal.Entry(state.getString("original", ""), state.getString("translation", ""),
                    state.getString("source", source()), state.getString("target", target()));
                output.setText(latest.translated());
            }
            var previousPhoto = state.getParcelable("pending_photo");
            if (previousPhoto instanceof android.net.Uri uri) translatePhoto(uri);
        } else {
            importSharedContent(getIntent());
        }
        root.requestFocus();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        importSharedContent(intent);
    }

    private void importSharedContent(Intent intent) {
        var content = SharedTranslation.read(intent);
        if (content == null) return;
        if (content.text() == null && content.image() == null) {
            status.setText("Teks atau foto yang dibagikan belum dapat dibuka.");
            return;
        }
        generation++;
        pendingPhoto = null;
        latest = null;
        output.setText("");
        if (content.image() != null) {
            input.setText("");
            translatePhoto(content.image());
        } else {
            input.setText(content.text());
            status.setText("Teks diterima. Ketuk Terjemahkan teks.");
        }
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
        pendingPhoto = null;
        int ticket = ++generation;
        String toCode = target();
        status.setText("Menerjemahkan…");
        translator.translateText(text, source(), toCode, message -> update(ticket, message), result -> show(ticket, toCode, result));
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        String document = request == EXPORT ? pendingExport : null;
        if (request == EXPORT) pendingExport = null;
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        var uri = data.getData();
        if (request == PHOTO) translatePhoto(uri);
        if (request == EXPORT && document != null) files.execute(() -> {
            try {
                LocalDocuments.writeText(getContentResolver(), uri, document);
                runOnUiThread(() -> { if (!isDestroyed()) status.setText("Terjemahan disimpan sebagai TXT."); });
            } catch (Exception error) {
                runOnUiThread(() -> { if (!isDestroyed()) status.setText("Berkas terjemahan belum dapat disimpan."); });
            }
        });
    }

    private void export() {
        if (latest == null) { status.setText("Terjemahkan teks terlebih dulu."); return; }
        pendingExport = TranslationLibrary.bilingual(latest);
        startActivityForResult(LocalDocuments.saveText("Terjemahan_Omni.txt"), EXPORT);
    }

    private void translatePhoto(android.net.Uri uri) {
        pendingPhoto = uri;
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
                            if (ticket != generation || isDestroyed()) return;
                            pendingPhoto = null;
                            if (!translated.original().isEmpty()) input.setText(translated.original());
                            show(ticket, toCode, translated);
                        });
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (ticket != generation || isDestroyed()) return;
                    pendingPhoto = null;
                    status.setText("Foto tidak dapat dibuka.");
                });
            }
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
        var panel = Chat.column(this);
        int pad = Chat.dp(this, 16);
        panel.setPadding(pad, pad, pad, 0);
        var search = new EditText(this);
        search.setHint("Cari teks asli atau terjemahan");
        panel.addView(search);
        var list = new ListView(this);
        panel.addView(list, new LinearLayout.LayoutParams(-1, Chat.dp(this, 251)));
        var matches = new java.util.ArrayList<TranslationJournal.Entry>();
        var names = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1);
        list.setAdapter(names);
        Runnable filter = () -> {
            matches.clear();
            matches.addAll(TranslationLibrary.filter(results, search.getText().toString()));
            names.setNotifyOnChange(false);
            names.clear();
            for (var entry : matches) names.add(preview(entry.translated(), 100));
            names.notifyDataSetChanged();
        };
        search.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence text, int start, int count, int after) {}
            public void onTextChanged(CharSequence text, int start, int before, int count) { filter.run(); }
            public void afterTextChanged(android.text.Editable value) {}
        });
        filter.run();
        var dialog = new AlertDialog.Builder(this).setTitle(favorites ? "Favorit terjemahan" : "Riwayat terjemahan")
            .setView(panel).setNegativeButton("Tutup", null).create();
        list.setOnItemClickListener((view, row, position, id) -> {
            var entry = matches.get(position);
            dialog.dismiss();
            new AlertDialog.Builder(this).setTitle("Terjemahan tersimpan").setMessage(TranslationLibrary.bilingual(entry))
                .setPositiveButton("Buka", (d, w) -> openEntry(entry))
                .setNeutralButton("Hapus", (d, w) -> {
                    TranslationJournal.remove(this, favorites, entry);
                    status.setText("Item dihapus.");
                }).setNegativeButton("Tutup", null).show();
        });
        dialog.show();
    }

    private static String preview(String text, int limit) {
        String row = text.replace('\n', ' ');
        return row.length() <= limit ? row : row.substring(0, limit) + "…";
    }

    private void openEntry(TranslationJournal.Entry entry) {
        generation++;
        latest = entry;
        setLanguages(entry.source(), entry.target());
        input.setText(entry.original());
        output.setText(entry.translated());
        status.setText("Terjemahan tersimpan dibuka.");
    }

    private void pairs() {
        var pairs = TranslationLibrary.pairs(this);
        String[] labels = pairs.stream().map(TranslationLibrary.Pair::name).toArray(String[]::new);
        var builder = new AlertDialog.Builder(this).setTitle("Pasangan bahasa")
            .setPositiveButton("Simpan pasangan ini", (dialog, result) -> {
                var pair = new TranslationLibrary.Pair(source(), target());
                TranslationLibrary.savePair(this, pair, false);
                status.setText(pair.name() + " disimpan.");
            }).setNegativeButton("Tutup", null);
        if (labels.length == 0) {
            builder.setMessage("Simpan bahasa sumber dan tujuan yang sedang dipilih.");
        } else {
            builder.setItems(labels, (dialog, position) -> {
                var entry = pairs.get(position);
                generation++;
                setLanguages(entry.source(), entry.target());
                status.setText(entry.name() + " dipakai.");
            });
        }
        var dialog = builder.create();
        dialog.show();
        if (labels.length > 0) dialog.getListView().setOnItemLongClickListener((parent, row, position, id) -> {
            var entry = pairs.get(position);
            dialog.dismiss();
            new AlertDialog.Builder(this).setTitle(entry.name())
                .setPositiveButton("Hapus pasangan", (confirmation, result) -> {
                    TranslationLibrary.savePair(this, entry, true);
                    status.setText("Pasangan bahasa dihapus.");
                }).setNegativeButton("Batal", null).show();
            return true;
        });
    }

    private void glossary() {
        var glossary = TranslationLibrary.glossary(this);
        var labels = glossary.stream().map(entry -> preview(entry.original(), 32) + " → " + preview(entry.translated(), 32))
            .toArray(String[]::new);
        var builder = new AlertDialog.Builder(this).setTitle("Kamus pribadi")
            .setPositiveButton("Tambah frasa", (dialog, result) -> addTerm()).setNegativeButton("Tutup", null);
        if (labels.length == 0) builder.setMessage("Simpan frasa dan padanan untuk bahasa yang sedang dipilih.");
        else builder.setItems(labels, (dialog, position) -> {
            var term = glossary.get(position);
            new AlertDialog.Builder(this).setTitle("Frasa kamus").setMessage(TranslationLibrary.bilingual(term))
                .setNeutralButton("Hapus frasa", (details, result) -> {
                    TranslationLibrary.saveTerm(this, term, true);
                    status.setText("Frasa dihapus.");
                }).setPositiveButton("Tutup", null).show();
        });
        builder.show();
    }

    private void addTerm() {
        String detected = source();
        if (detected.equals("auto") && latest != null) detected = latest.source();
        if (detected.equals("auto") || detected.equals(target())) {
            status.setText("Pilih dua bahasa yang berbeda untuk menyimpan frasa.");
            return;
        }
        String fromCode = detected, toCode = target();
        var panel = Chat.column(this);
        int pad = Chat.dp(this, 16);
        panel.setPadding(pad, pad, pad, 0);
        panel.addView(OmniUi.caption(this, TranslationPreferences.name(fromCode) + " → " + TranslationPreferences.name(toCode)));
        var original = termField("Frasa yang ingin diganti");
        var translated = termField("Padanan yang akan dipakai");
        panel.addView(original);
        panel.addView(translated);
        var dialog = new AlertDialog.Builder(this).setTitle("Tambah frasa").setView(panel)
            .setPositiveButton("Simpan", null).setNegativeButton("Batal", null).create();
        dialog.setOnShowListener(event -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            var term = new TranslationJournal.Entry(original.getText().toString().trim(),
                translated.getText().toString().trim(), fromCode, toCode);
            if (!TranslationLibrary.saveTerm(this, term, false)) {
                if (term.original().isEmpty()) original.setError("Isi frasa.");
                if (term.translated().isEmpty()) translated.setError("Isi padanan.");
                return;
            }
            status.setText("Frasa disimpan ke kamus.");
            dialog.dismiss();
        }));
        dialog.show();
    }

    private EditText termField(String hint) {
        var field = new EditText(this);
        field.setHint(hint);
        field.setSingleLine(true);
        field.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(TranslationLibrary.MAX_TERM)});
        return field;
    }

    private void speak() {
        if (latest == null) { status.setText("Belum ada terjemahan untuk dibacakan."); return; }
        if (voiceReady) { speakLatest(); return; }
        speakOnReady = true;
        status.setText("Menyiapkan suara Android…");
        if (voice == null) voice = new TextToSpeech(this, result -> input.post(() -> {
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
        state.putString("export", pendingExport);
        state.putParcelable("pending_photo", pendingPhoto);
        if (latest != null) {
            state.putString("original", latest.original());
            state.putString("translation", latest.translated());
            state.putString("source", latest.source());
            state.putString("target", latest.target());
        }
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
