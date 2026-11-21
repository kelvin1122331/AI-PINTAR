package id.omni.floatai;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.View;
import android.view.WindowManager;
import android.widget.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;

final class ApiSettings {
    private final Activity activity;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final java.util.concurrent.ExecutorService worker = Executors.newFixedThreadPool(2);
    private final Runnable scheduled = this::fetch;
    private Spinner provider;
    private EditText endpoint;
    private EditText key;
    private TextView hint;
    private Button pick;
    private AlertDialog dialog;
    private int index = -1;
    private int revision;
    private boolean loadingProfile;
    private List<String> models = java.util.Collections.emptyList();
    private String selected = Providers.AUTO;

    ApiSettings(Activity activity) { this.activity = activity; }

    void show() {
        var form = Chat.column(activity);
        int pad = Chat.dp(activity, 20);
        form.setPadding(pad, pad, pad, pad);
        provider = new Spinner(activity);
        provider.setAdapter(new ArrayAdapter<>(activity, android.R.layout.simple_spinner_dropdown_item, Providers.NAMES));
        form.addView(provider);
        endpoint = field(form, "URL chat completions", InputType.TYPE_TEXT_VARIATION_URI);
        key = field(form, "API key", InputType.TYPE_TEXT_VARIATION_PASSWORD);
        form.addView(Chat.button(activity, "Muat ulang model", false, this::fetch));
        hint = Chat.label(activity, "", 13, Chat.MUTED);
        hint.setPadding(0, pad / 2, 0, pad / 2);
        form.addView(hint);
        pick = Chat.button(activity, "Pilih model", false, this::chooseModel);
        form.addView(pick);
        var scroll = new ScrollView(activity);
        scroll.addView(form);

        dialog = new AlertDialog.Builder(activity).setTitle("Pengaturan API").setView(scroll)
            .setPositiveButton("Simpan & pakai", null).setNegativeButton("Batal", null).create();
        dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        dialog.setOnDismissListener(d -> {
            revision++;
            handler.removeCallbacks(scheduled);
            worker.shutdownNow();
        });
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> save());
        watch(key, this::credentialsChanged);
        watch(endpoint, this::credentialsChanged);
        int initial = Providers.active(activity);
        provider.setSelection(initial);
        provider.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onNothingSelected(AdapterView<?> view) {}
            public void onItemSelected(AdapterView<?> view, View item, int position, long id) { loadProfile(position); }
        });
        loadProfile(initial);
    }

    private EditText field(LinearLayout form, String label, int variation) {
        form.addView(Chat.label(activity, label, 13, Chat.MUTED));
        var field = new EditText(activity);
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_TEXT | variation);
        form.addView(field);
        return field;
    }

    private static void watch(EditText field, Runnable update) {
        field.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence text, int start, int count, int after) {}
            public void onTextChanged(CharSequence text, int start, int before, int count) { update.run(); }
            public void afterTextChanged(Editable text) {}
        });
    }

    private void loadProfile(int position) {
        if (position == index) return;
        index = position;
        loadingProfile = true;
        try {
            Providers.Profile profile = Providers.load(activity, position);
            endpoint.setText(profile.endpoint());
            key.setText(profile.key());
            selected = profile.model().isEmpty() ? Providers.AUTO : profile.model();
        } catch (Exception error) {
            endpoint.setText(Providers.ENDPOINTS[position]);
            key.setText("");
            selected = Providers.AUTO;
        }
        loadingProfile = false;
        credentialsChanged();
    }

    private Providers.Profile profile() {
        return new Providers.Profile(endpoint.getText().toString().trim(),
            selected, key.getText().toString().trim());
    }

    private boolean hasCredentials(Providers.Profile profile) {
        return !profile.endpoint().isEmpty() && (index == Providers.NAMES.length - 1 || !profile.key().isEmpty());
    }

    private void credentialsChanged() {
        if (loadingProfile) return;
        revision++;
        handler.removeCallbacks(scheduled);
        models = java.util.Collections.emptyList();
        setReady(false);
        if (hasCredentials(profile())) {
            hint.setText("Daftar model akan dimuat dari API.");
            handler.postDelayed(scheduled, 900);
        } else {
            hint.setText("Masukkan URL dan API key provider untuk melihat model.");
        }
    }

    private void setReady(boolean ready) {
        pick.setEnabled(ready);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(ready);
        pick.setText(!ready ? "Pilih model" : Providers.AUTO.equals(selected) ? "Otomatis · Pilih model" : selected);
    }

    private void fetch() {
        handler.removeCallbacks(scheduled);
        Providers.Profile snapshot = profile();
        if (!hasCredentials(snapshot)) return;
        int ticket = ++revision;
        models = java.util.Collections.emptyList();
        setReady(false);
        hint.setText("Memuat model dari API.");
        worker.execute(() -> {
            try {
                List<String> available = Providers.models(snapshot);
                available.sort(String.CASE_INSENSITIVE_ORDER);
                handler.post(() -> {
                    if (ticket != revision || !dialog.isShowing()) return;
                    models = available;
                    if (!available.contains(selected)) selected = Providers.AUTO;
                    hint.setText(available.size() + " model chat. Pilih manual atau gunakan Otomatis.");
                    setReady(true);
                });
            } catch (Exception error) {
                handler.post(() -> {
                    if (ticket != revision || !dialog.isShowing()) return;
                    hint.setText(error instanceof IllegalStateException || error instanceof IllegalArgumentException
                        ? error.getMessage() : "Daftar model gagal dimuat. Cek URL, API key, dan jaringan.");
                });
            }
        });
    }

    private void chooseModel() {
        var panel = Chat.column(activity);
        var search = field(panel, "Cari ID model", InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        var items = new ListView(activity);
        var choices = new ArrayList<>(models);
        var list = new ArrayAdapter<>(activity, android.R.layout.simple_list_item_1, choices);
        items.setAdapter(list);
        panel.addView(items, new LinearLayout.LayoutParams(-1, Chat.dp(activity, 280)));
        var chooser = new AlertDialog.Builder(activity).setTitle("Pilih model AI").setView(panel)
            .setNeutralButton("Otomatis", (d, w) -> { selected = Providers.AUTO; setReady(!models.isEmpty()); })
            .setNegativeButton("Kembali", null).create();
        watch(search, () -> {
            String query = search.getText().toString().trim().toLowerCase(java.util.Locale.ROOT);
            list.setNotifyOnChange(false);
            list.clear();
            for (String model : models) {
                if (model.toLowerCase(java.util.Locale.ROOT).contains(query)) list.add(model);
            }
            list.notifyDataSetChanged();
        });
        items.setOnItemClickListener((parent, view, position, id) -> {
            String item = list.getItem(position);
            if (models.contains(item)) {
                selected = item;
                setReady(true);
            }
            chooser.dismiss();
        });
        chooser.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        chooser.show();
    }

    private void save() {
        try {
            Providers.save(activity, index, profile());
            Chat.SESSION.clear();
            dialog.dismiss();
        } catch (Exception error) {
            hint.setText("Pengaturan API belum tersimpan. Cek URL dan API key.");
        }
    }
}

