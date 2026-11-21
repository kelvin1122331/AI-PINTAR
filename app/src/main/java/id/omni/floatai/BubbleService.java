package id.omni.floatai;

import android.app.*;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;
import android.view.*;
import android.widget.Button;

public final class BubbleService extends Service {
    static final String CAPTURE = "id.omni.floatai.CAPTURE";
    private static final String CHANNEL = "floating_chat";
    private static final String STOP = "id.omni.floatai.STOP";
    private WindowManager windows;
    private View overlay;
    private View translation;
    private final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable dismissTranslation = this::clearTranslation;
    private int bubbleX;
    private int bubbleY;
    static boolean active;
    private boolean started;
    private boolean discreet;
    private boolean toolsTab;
    private boolean fadeTransition;
    private String result = "";
    private ScreenAssist assist;
    private QuizRunner quiz;
    private Notification notification;
    private android.widget.TextView resultView;

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if ((intent != null && STOP.equals(intent.getAction())) || !Settings.canDrawOverlays(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        boolean capture = intent != null && CAPTURE.equals(intent.getAction());
        if (started) {
            if (capture) connectCapture(intent);
            return START_NOT_STICKY;
        }
        var notifications = getSystemService(NotificationManager.class);
        notifications.createNotificationChannel(new NotificationChannel(CHANNEL, "Bubble chat AI", NotificationManager.IMPORTANCE_LOW));
        var stop = PendingIntent.getService(this, 1, new Intent(this, BubbleService.class).setAction(STOP),
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        var open = PendingIntent.getActivity(this, 2, new Intent(this, MainActivity.class),
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        notification = new Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.omni)
            .setContentTitle("Omni Float AI").setContentText("Bubble chat aktif").setOngoing(true)
            .setContentIntent(open).addAction(new Notification.Action.Builder(null, "Matikan bubble", stop).build()).build();
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else if (Build.VERSION.SDK_INT >= 29) startForeground(1, notification, 0);
        else startForeground(1, notification);
        started = true;
        active = true;
        windows = getSystemService(WindowManager.class);
        assist = new ScreenAssist(this);
        quiz = new QuizRunner(this);
        bubbleX = Chat.dp(this, 16);
        bubbleY = Chat.dp(this, 125);
        showBubble();
        if (capture) connectCapture(intent);
        return START_NOT_STICKY;
    }

    private void connectCapture(Intent intent) {
        if (assist.ready()) return;
        try {
            if (Build.VERSION.SDK_INT >= 34) startForeground(1, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE | ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            else if (Build.VERSION.SDK_INT >= 29) startForeground(1, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            assist.connect(intent.getParcelableExtra("consent"));
        } catch (RuntimeException error) {
            screenStatus("Izin tangkapan layar belum aktif.");
        }
        restorePanel();
    }

    private WindowManager.LayoutParams params(int width, int height, int flags) {
        var p = new WindowManager.LayoutParams(width, height, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags, PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.TOP | Gravity.LEFT;
        return p;
    }

    private void removeOverlay() {
        if (overlay != null) { windows.removeViewImmediate(overlay); overlay = null; }
        resultView = null;
    }

    private void show(View view, WindowManager.LayoutParams p) {
        removeOverlay();
        try {
            windows.addView(view, p);
            overlay = view;
        } catch (WindowManager.BadTokenException | SecurityException exception) {
            stopSelf();
        }
    }

    private void showBubble() {
        int size = Chat.dp(this, 56);
        var p = params(size, size, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        p.x = bubbleX;
        p.y = bubbleY;
        Button bubble = Chat.button(this, "AI", true, () -> { discreet = false; showPanel(); });
        var bubbleFill = Chat.shape(this, Chat.ORANGE, 28);
        bubbleFill.setStroke(Chat.dp(this, 2), Chat.PURPLE);
        bubble.setBackground(bubbleFill);
        bubble.setAlpha(discreet ? 0.12f : 1f);
        bubble.setContentDescription("Buka chat Omni Float AI");
        bubble.setOnLongClickListener(v -> { stopSelf(); return true; });
        int slop = ViewConfiguration.get(this).getScaledTouchSlop();
        bubble.setOnTouchListener(new View.OnTouchListener() {
            float downX, downY;
            int oldX, oldY;
            boolean moving;

            public boolean onTouch(View view, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = event.getRawX(); downY = event.getRawY();
                        oldX = p.x; oldY = p.y; moving = false;
                        return false;
                    case MotionEvent.ACTION_MOVE:
                        int dx = Math.round(event.getRawX() - downX);
                        int dy = Math.round(event.getRawY() - downY);
                        if (!moving && Math.hypot(dx, dy) <= slop) return false;
                        if (!moving) {
                            MotionEvent cancel = MotionEvent.obtain(event);
                            cancel.setAction(MotionEvent.ACTION_CANCEL);
                            view.onTouchEvent(cancel);
                            cancel.recycle();
                            moving = true;
                        }
                        var screen = getResources().getDisplayMetrics();
                        p.x = bubbleX = Math.max(0, Math.min(screen.widthPixels - size, oldX + dx));
                        p.y = bubbleY = Math.max(0, Math.min(screen.heightPixels - size, oldY + dy));
                        windows.updateViewLayout(view, p);
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        return moving;
                    default:
                        return false;
                }
            }
        });
        show(bubble, p);
    }

    private void showPanel() {
        clearTranslation();
        var metrics = getResources().getDisplayMetrics();
        var p = params(Math.min(Chat.dp(this, 410), metrics.widthPixels - Chat.dp(this, 16)),
            Math.min(Chat.dp(this, 470), (int) (metrics.heightPixels * 0.76)),
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH);
        p.gravity = Gravity.CENTER;
        p.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;
        var shell = Chat.column(this);
        int pad = Chat.dp(this, 8);
        shell.setPadding(pad, pad, pad, pad);
        var panelSurface = Chat.shape(this, Chat.BG, 24);
        panelSurface.setStroke(Chat.dp(this, 2), 0xffd7bafa);
        shell.setBackground(panelSurface);
        var nav = new android.widget.LinearLayout(this);
        nav.addView(Chat.button(this, "Chatbot", !toolsTab, () -> { toolsTab = false; showPanel(); }),
            new android.widget.LinearLayout.LayoutParams(0, -2, 1));
        nav.addView(Chat.button(this, "Mode otomatis", toolsTab, () -> { toolsTab = true; showPanel(); }),
            new android.widget.LinearLayout.LayoutParams(0, -2, 1));
        shell.addView(nav);
        var actions = new android.widget.LinearLayout(this);
        actions.addView(Chat.button(this, "Samaran", false, () -> { discreet = true; showBubble(); }),
            new android.widget.LinearLayout.LayoutParams(0, -2, 1));
        actions.addView(Chat.button(this, "Minimalkan", false, this::showBubble),
            new android.widget.LinearLayout.LayoutParams(0, -2, 1));
        shell.addView(actions);
        android.widget.TextView currentResult = null;
        if (toolsTab) {
            var scroll = new android.widget.ScrollView(this);
            var panel = Chat.column(this);
            scroll.addView(panel);
            shell.addView(scroll, new android.widget.LinearLayout.LayoutParams(-1, 0, 1));
            currentResult = Chat.label(this, result, 15, Chat.INK);
            currentResult.setTextIsSelectable(true);
            panel.addView(currentResult);
            panel.addView(Chat.label(this, "Tangkapan layar dikirim ke API aktif. Gunakan model yang menerima gambar.", 13, Chat.MUTED));
            panel.addView(Chat.button(this, assist.ready() ? "Matikan akses layar" : "Izinkan akses layar", true, () -> {
                if (assist.ready()) {
                    assist.release(true);
                    screenStatus("Akses layar dimatikan.");
                    restorePanel();
                } else {
                    startActivity(new Intent(this, CaptureConsent.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                }
            }));
            screenButton(panel, "Terjemah langsung ke Indonesia",
                "Terjemahkan tulisan non-Indonesia yang terlihat ke bahasa Indonesia. " +
                "Kembalikan hanya JSON {\"teks\":[{\"x\":0,\"y\":0,\"w\":100,\"h\":30,\"id\":\"terjemahan\"}]}. " +
                "x,y,w,h adalah kotak teks ASLI dalam koordinat 0 sampai 1000 pada gambar penuh dari pojok kiri atas. " +
                "Gabungkan baris satu paragraf bila perlu. Gunakan maksimal 28 kotak. Jangan sertakan teks asli atau penjelasan. Jika tidak ada teks, gunakan daftar kosong.", 0);
            panel.addView(Chat.button(this, "Atur aksesibilitas untuk kuis Chrome", false, () ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))));
            quizButton(panel, "Jawab 1 soal", 1, 60_000);
            quizButton(panel, "Otomatis sampai akhir halaman", Integer.MAX_VALUE, 1_800_000);
            quizButton(panel, "Otomatis 30 detik", Integer.MAX_VALUE, 30_000);
            quizButton(panel, "Otomatis 30 menit", Integer.MAX_VALUE, 1_800_000);
            panel.addView(Chat.button(this, fadeTransition ? "Transisi halus: aktif" : "Transisi halus: nonaktif", false,
                () -> { fadeTransition = !fadeTransition; showPanel(); }));
            panel.addView(Chat.button(this, "Stop generation", false, () -> { Chat.SESSION.stop(); quiz.cancel(); assist.stop(); }));
        } else {
            var panel = Chat.SESSION.panel(this, null, null, null);
            shell.addView(panel, new android.widget.LinearLayout.LayoutParams(-1, 0, 1));
        }
        shell.setFocusableInTouchMode(true);
        shell.setOnTouchListener((view, event) -> {
            if (event.getActionMasked() != MotionEvent.ACTION_OUTSIDE) return false;
            showBubble();
            return true;
        });
        show(shell, p);
        resultView = currentResult;
        if (fadeTransition) {
            shell.setAlpha(0f);
            shell.animate().alpha(1f).setDuration(180).start();
        }
    }

    private void screenButton(android.widget.LinearLayout panel, String label, String prompt, long duration) {
        panel.addView(Chat.button(this, label, false, () -> {
            quiz.cancel();
            clearTranslation();
            if (assist.ready()) showBubble();
            assist.start(prompt, duration);
        }));
    }

    private void quizButton(android.widget.LinearLayout panel, String name, int limit, long duration) {
        panel.addView(Chat.button(this, name, false, () -> {
            assist.cancel();
            clearTranslation();
            showBubble();
            quiz.start(limit, duration);
        }));
    }

    boolean showTranslation(String response) {
        clearTranslation();
        TranslationOverlay layer;
        try {
            layer = new TranslationOverlay(this, response);
            if (layer.isEmpty()) return false;
            var layout = params(-1, -1, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN);
            layout.alpha = 0.78f;
            if (Build.VERSION.SDK_INT >= 30) layout.setFitInsetsTypes(0);
            windows.addView(layer, layout);
            translation = layer;
            // Put the draggable bubble above the translation layer.
            showBubble();
            ui.postDelayed(dismissTranslation, 20_000);
            return true;
        } catch (Exception error) {
            return false;
        }
    }

    void clearTranslation() {
        ui.removeCallbacks(dismissTranslation);
        if (translation != null) {
            windows.removeViewImmediate(translation);
            translation = null;
        }
    }

    @Override public void onConfigurationChanged(android.content.res.Configuration config) {
        super.onConfigurationChanged(config);
        clearTranslation();
        if (assist != null && assist.ready()) {
            assist.release(true);
            screenStatus("Ukuran layar berubah. Aktifkan kembali akses layar.");
        }
        if (quiz != null) quiz.cancel();
    }

    void screenStatus(String text) {
        result = text;
        if (resultView != null) resultView.setText(text);
    }

    void restorePanel() {
        toolsTab = true;
        showPanel();
    }

    @Override public void onDestroy() {
        active = false;
        if (assist != null) assist.destroy();
        if (quiz != null) quiz.destroy();
        clearTranslation();
        removeOverlay();
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }
}


