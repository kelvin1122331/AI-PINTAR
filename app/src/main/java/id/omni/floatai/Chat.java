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
    static final int BG = 0xfff8f6fc, CARD = 0xffeee8f8, INK = 0xff251b35,
        MUTED = 0xff645b71, PURPLE = 0xff58349e, ORANGE = 0xffffaf72, WHITE = 0xffffffff;
    static final long DEADLINE_MS = 55_000;
    static final Chat SESSION = new Chat();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final java.util.concurrent.ExecutorService worker = Executors.newCachedThreadPool();
    private JSONArray history = new JSONArray();
    private int generation;
    private boolean busy;
    private java.util.concurrent.Future<?> task;
    private Providers.Cancellation cancellation;
    private Runnable deadline;
    private String pending = "";
    private String retryPrompt = "";
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
        view.setTextColor(INK);
        view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        view.setPadding(dp(c, 12), 0, dp(c, 12), 0);
        view.setMinHeight(dp(c, 48));
        view.setMinimumWidth(0);
        view.setBackground(new android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(0x24632fc2), shape(c, primary ? ORANGE : CARD, 14), null));
        view.setOnClickListener(v -> action.run());
        return view;
    }

    void refresh() { for (Runnable observer : observers.toArray(new Runnable[0])) observer.run(); }
    void stop() {
        cancel();
        pending = "";
        error = "Permintaan dihentikan.";
        refresh();
    }

    private void cancel() {
        generation++;
        if (deadline != null) ui.removeCallbacks(deadline);
        if (task != null) task.cancel(true);
        if (cancellation != null) cancellation.cancel();
        task = null;
        cancellation = null;
        busy = false;
    }

    void clear() {
        cancel();
        history = new JSONArray();
        pending = error = lastModel = retryPrompt = "";
        refresh();
    }

    boolean send(Context context, String prompt) {
        if (busy || prompt.isEmpty()) return false;
        try {
            Providers.Profile profile = Providers.load(context, Providers.active(context));
            if (profile.model().isEmpty()) {
                error = "Atur API dan pilih model terlebih dulu.";
                refresh();
                return false;
            }
            JSONArray request = new JSONArray();
            for (int i = Math.max(0, history.length() - 16); i < history.length(); i++) {
                request.put(history.getJSONObject(i));
            }
            request.put(new JSONObject().put("role", "user").put("content", prompt));
            int ticket = ++generation;
            String previousModel = lastModel;
            var call = new Providers.Cancellation();
            cancellation = call;
            pending = prompt;
            retryPrompt = prompt;
            busy = true;
            error = "";
            deadline = () -> fail(ticket, "API belum merespons setelah 55 detik. Coba lagi atau pilih model yang lebih cepat.");
            ui.postDelayed(deadline, DEADLINE_MS);
            refresh();
            task = worker.submit(() -> {
                try {
                    Providers.Answer answer = Providers.complete(profile, request, previousModel, call);
                    var reply = new JSONObject().put("role", "assistant").put("content", answer.text());
                    ui.post(() -> {
                        if (ticket != generation) return;
                        ui.removeCallbacks(deadline);
                        task = null;
                        cancellation = null;
                        history = request.put(reply);
                        lastModel = answer.model();
                        pending = retryPrompt = "";
                        busy = false;
                        refresh();
                    });
                } catch (Exception exception) {
                    ui.post(() -> fail(ticket, describe(exception)));
                }
            });
            return true;
        } catch (Exception exception) {
            cancel();
            pending = "";
            error = "Pengaturan API tidak bisa dibuka. Simpan ulang API key.";
            refresh();
            return false;
        }
    }

    private void fail(int ticket, String message) {
        if (ticket != generation || !busy) return;
        cancel();
        pending = "";
        error = message;
        refresh();
    }

    private static String describe(Exception error) {
        if (error instanceof java.net.SocketTimeoutException) return "Provider terlalu lama merespons. Coba lagi.";
        if (error instanceof java.net.UnknownHostException) return "Alamat API tidak ditemukan. Periksa URL dan internet.";
        if (error instanceof javax.net.ssl.SSLException) return "Koneksi HTTPS ke provider gagal.";
        if (error instanceof IllegalStateException && error.getMessage() != null) return error.getMessage();
        return "Koneksi atau respons API bermasalah. Periksa jaringan dan model.";
    }

    LinearLayout panel(Context c, String closeTitle, Runnable close, Runnable settings) {
        var root = column(c);
        int pad = dp(c, 14);
        root.setPadding(pad, pad, pad, pad);
        root.setBackground(shape(c, BG, 20));
        var title = label(c, "Omni Float AI", 23, INK);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);
        var status = label(c, "", 13, PURPLE);
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
        var retry = button(c, "Coba lagi", false, () -> send(c, retryPrompt));
        root.addView(retry);

        var scroll = new ScrollView(c);
        var transcript = column(c);
        scroll.addView(transcript);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        var feedback = label(c, "", 13, MUTED);
        root.addView(feedback);
        var composer = new LinearLayout(c);
        composer.setGravity(android.view.Gravity.CENTER_VERTICAL);
        var input = new EditText(c);
        input.setTextColor(INK);
        input.setBackground(shape(c, CARD, 14));
        input.setPadding(pad, dp(c, 10), pad, dp(c, 10));
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
            if (Providers.AUTO.equals(model)) model = lastModel.isEmpty() ? "Otomatis" : "Otomatis / " + lastModel;
            status.setText(Providers.NAMES[provider] + (model.isEmpty() ? "" : "  /  " + model));
            feedback.setText(busy ? "Menunggu respons dari " + Providers.NAMES[provider] + "." : error);
            feedback.setVisibility(busy || !error.isEmpty() ? View.VISIBLE : View.GONE);
            send.setEnabled(!busy);
            retry.setVisibility(!busy && !retryPrompt.isEmpty() ? View.VISIBLE : View.GONE);
            stop.setVisibility(busy ? View.VISIBLE : View.GONE);
            transcript.removeAllViews();
            if (history.length() == 0 && pending.isEmpty()) {
                var empty = label(c, "Apa yang mau kamu tanyakan?", 16, MUTED);
                empty.setPadding(0, pad, 0, pad);
                transcript.addView(empty);
            }
            for (int i = 0; i < history.length(); i++) {
                JSONObject entry = history.optJSONObject(i);
                boolean user = "user".equals(entry.optString("role"));
                var message = label(c, (user ? "Kamu\n" : "AI\n") + entry.optString("content"), 15, INK);
                message.setTextIsSelectable(true);
                message.setPadding(pad, pad, pad, pad);
                message.setBackground(shape(c, user ? 0xffffe3cf : CARD, 12));
                var params = new LinearLayout.LayoutParams(-1, -2);
                params.topMargin = dp(c, 8);
                transcript.addView(message, params);
            }
            if (!pending.isEmpty()) {
                var sending = label(c, "Kamu\n" + pending, 15, INK);
                sending.setPadding(pad, pad, pad, pad);
                sending.setBackground(shape(c, 0xffffe3cf, 12));
                transcript.addView(sending);
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
