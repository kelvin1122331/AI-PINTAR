package id.omni.floatai;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.*;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.concurrent.Executors;

final class Chat {
    static final int BG = 0xff101923, CARD = 0xff1b2935, WHITE = 0xffecf5fc, MUTED = 0xffafc1ce, MINT = 0xff52e1c3;
    static final Chat SESSION = new Chat();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final java.util.concurrent.ExecutorService worker = Executors.newCachedThreadPool();
    private JSONArray history = new JSONArray();
    private int generation;
    private boolean busy;
    private java.util.concurrent.Future<?> task;
    private String error = "";
    private String lastModel = "";
    private final java.util.Set<Runnable> observers = new java.util.HashSet<>();

    static int dp(Context c, int value) { return Math.round(value * c.getResources().getDisplayMetrics().density); }
    static GradientDrawable shape(Context c, int color, int radius) {
        var background = new GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(dp(c, radius));
        return background;
    }
    static LinearLayout column(Context c) {
        var view = new LinearLayout(c);
        view.setOrientation(LinearLayout.VERTICAL);
        return view;
    }
    static TextView label(Context c, String value, int size, int color) {
        var view = new TextView(c);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }
    static Button button(Context c, String title, boolean primary, Runnable action) {
        var view = new Button(c);
        view.setAllCaps(false);
        view.setText(title);
        view.setTextColor(primary ? BG : WHITE);
        view.setMinHeight(dp(c, 48));
        view.setMinimumWidth(0);
        view.setBackground(shape(c, primary ? MINT : CARD, 12));
        view.setOnClickListener(v -> action.run());
        return view;
    }

    void refresh() { for (Runnable observer : observers.toArray(new Runnable[0])) observer.run(); }
    void stop() {
        generation++;
        if (task != null) task.cancel(true);
        task = null;
        busy = false;
        refresh();
    }
    void clear() { history = new JSONArray(); error = ""; lastModel = ""; stop(); }

    private boolean send(Context context, String prompt) {
        if (busy || prompt.isEmpty()) return false;
        Context app = context.getApplicationContext();
        try {
            Providers.Profile profile = Providers.load(app, Providers.active(app));
            if (profile.model().trim().isEmpty()) {
                error = "Atur API dan pilih mode model terlebih dulu.";
                refresh();
                return false;
            }
            JSONArray request = new JSONArray();
            for (int i = Math.max(0, history.length() - 16); i < history.length(); i++) {
                request.put(history.getJSONObject(i));
            }
            request.put(new JSONObject().put("role", "user").put("content", prompt));
            int ticket = generation;
            String previousModel = lastModel;
            busy = true;
            error = "";
            refresh();
            task = worker.submit(() -> {
                try {
                    Providers.Answer answer = Providers.complete(profile, request, previousModel);
                    var reply = new JSONObject().put("role", "assistant").put("content", answer.text());
                    ui.post(() -> {
                        if (ticket != generation) return;
                        history = request.put(reply);
                        lastModel = answer.model();
                        busy = false;
                        refresh();
                    });
                } catch (Exception exception) {
                    String message = exception instanceof IllegalStateException ? exception.getMessage()
                        : "Koneksi atau respons API bermasalah. Cek endpoint dan jaringan.";
                    ui.post(() -> {
                        if (ticket != generation) return;
                        busy = false;
                        error = message;
                        refresh();
                    });
                }
            });
            return true;
        } catch (Exception exception) {
            error = "Pengaturan API tidak bisa dibuka. Simpan ulang API key.";
            refresh();
            return false;
        }
    }

    LinearLayout panel(Context c, String closeTitle, Runnable close, Runnable settings) {
        var root = column(c);
        int pad = dp(c, 14);
        root.setPadding(pad, pad, pad, pad);
        root.setBackground(shape(c, BG, 20));
        var title = label(c, "Omni Float AI", 23, WHITE);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);
        var status = label(c, "", 13, MINT);
        root.addView(status);

        var controls = new LinearLayout(c);
        if (settings != null) controls.addView(button(c, "API", false, settings), new LinearLayout.LayoutParams(0, dp(c, 48), 1));
        controls.addView(button(c, "Chat baru", false, this::clear), new LinearLayout.LayoutParams(0, dp(c, 48), 1));
        if (close != null) controls.addView(button(c, closeTitle, false, close), new LinearLayout.LayoutParams(0, dp(c, 48), 1));
        var spacer = new LinearLayout.LayoutParams(-1, -2);
        spacer.topMargin = dp(c, 10);
        root.addView(controls, spacer);
        var stop = button(c, "Stop generation", false, this::stop);
        root.addView(stop);

        var scroll = new ScrollView(c);
        var transcript = column(c);
        scroll.addView(transcript);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        var feedback = label(c, "", 13, MUTED);
        root.addView(feedback);
        var composer = new LinearLayout(c);
        var input = new EditText(c);
        input.setTextColor(WHITE);
        input.setHintTextColor(MUTED);
        input.setHint("Tulis pesan");
        input.setTextSize(15);
        input.setMaxLines(3);
        input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(8000)});
        composer.addView(input, new LinearLayout.LayoutParams(0, -2, 1));
        Button send = button(c, "Kirim", true, () -> {
            if (send(c, input.getText().toString().trim())) input.setText("");
        });
        composer.addView(send);
        root.addView(composer);
        Runnable render = () -> {
            int provider = Providers.active(c);
            String model = Providers.prefs(c).getString("model" + provider, "");
            if (Providers.AUTO.equals(model)) model = lastModel.isEmpty() ? "Otomatis" : "Otomatis ? " + lastModel;
            status.setText(Providers.NAMES[provider] + (model.isEmpty() ? "" : "  ?  " + model));
            feedback.setText(busy ? "Menunggu jawaban." : error);
            feedback.setVisibility(busy || !error.isEmpty() ? View.VISIBLE : View.GONE);
            send.setEnabled(!busy);
            stop.setVisibility(busy ? View.VISIBLE : View.GONE);
            transcript.removeAllViews();
            if (history.length() == 0) {
                var empty = label(c, "Apa yang mau kamu tanyakan?", 16, MUTED);
                empty.setPadding(0, pad, 0, pad);
                transcript.addView(empty);
            }
            for (int i = 0; i < history.length(); i++) {
                JSONObject entry = history.optJSONObject(i);
                boolean user = "user".equals(entry.optString("role"));
                var message = label(c, (user ? "Kamu\n" : "AI\n") + entry.optString("content"), 15, WHITE);
                message.setTextIsSelectable(true);
                message.setPadding(pad, pad, pad, pad);
                message.setBackground(shape(c, user ? 0xff174039 : CARD, 12));
                var params = new LinearLayout.LayoutParams(-1, -2);
                params.topMargin = dp(c, 8);
                transcript.addView(message, params);
            }
            scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
        };
        root.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            public void onViewAttachedToWindow(View view) { observers.add(render); render.run(); }
            public void onViewDetachedFromWindow(View view) { observers.remove(render); }
        });
        return root;
    }
}


