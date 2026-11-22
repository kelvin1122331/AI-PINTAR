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
    static final String SCREEN_OPTIONS = "id.omni.floatai.SCREEN_OPTIONS";
    static final String APPEARANCE = "id.omni.floatai.APPEARANCE";
    static android.content.SharedPreferences appearance(android.content.Context context) {
        return context.getSharedPreferences("bubble_appearance", MODE_PRIVATE);
    }
    static final String CAPTURE = "id.omni.floatai.CAPTURE";
    private static final String CHANNEL = "floating_chat";
    private static final String STOP = "id.omni.floatai.STOP";
    private static final String PAUSE = "id.omni.floatai.PAUSE_TRANSLATION";
    private WindowManager windows;
    private View overlay;
    private TranslationOverlay translation;
    private java.util.List<TranslationOverlay.Label> rendered = java.util.Collections.emptyList();
    private float translationOpacity;
    private WindowManager.LayoutParams translationLayout;
    private boolean shuttingDown;
    private AlertDialog activeDialog;
    private boolean quizExpanded;
    private boolean panelOpen;
    private boolean startTranslationAfterConsent;
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
    private ScreenVision vision;
    private int pendingVision = -1;
    private String visionText = "";
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
            if (intent != null && APPEARANCE.equals(intent.getAction())) showBubble();
            if (intent != null && SCREEN_OPTIONS.equals(intent.getAction())) updateTranslationOptions();
            if (intent != null && PAUSE.equals(intent.getAction()) && assist.running()) {
                assist.setPaused(!assist.paused());
                if (panelOpen && toolsTab) showPanel();
            }
            return START_NOT_STICKY;
        }
        var notifications = getSystemService(NotificationManager.class);
        notifications.createNotificationChannel(new NotificationChannel(CHANNEL, "Bubble chat AI", NotificationManager.IMPORTANCE_LOW));
        notification = currentNotification();
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else if (Build.VERSION.SDK_INT >= 29) startForeground(1, notification, 0);
        else startForeground(1, notification);
        started = true;
        active = true;
        windows = getSystemService(WindowManager.class);
        assist = new ScreenAssist(this);
        quiz = new QuizRunner(this);
        vision = new ScreenVision(this);
        bubbleX = Chat.dp(this, 16);
        bubbleY = Chat.dp(this, 125);
        showBubble();
        if (capture) connectCapture(intent);
        return START_NOT_STICKY;
    }

    private Notification currentNotification() {
        var stop = PendingIntent.getService(this, 1, new Intent(this, BubbleService.class).setAction(STOP),
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        var open = PendingIntent.getActivity(this, 2, new Intent(this, MainActivity.class),
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        var builder = new Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.omni)
            .setContentTitle("Omni Float AI").setContentText("Asisten mengambang aktif").setOngoing(true)
            .setOnlyAlertOnce(true).setContentIntent(open);
        if (assist != null && assist.running()) {
            var pause = PendingIntent.getService(this, 3, new Intent(this, BubbleService.class).setAction(PAUSE),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            String label = assist.paused() ? "Lanjutkan terjemah" : "Jeda terjemah";
            builder.setContentText(assist.paused() ? "Terjemahan dijeda" : "Menerjemahkan layar")
                .addAction(new Notification.Action.Builder(null, label, pause).build());
        }
        return builder.addAction(new Notification.Action.Builder(null, "Matikan bubble", stop).build()).build();
    }

    void refreshNotification() {
        if (!started || shuttingDown) return;
        notification = currentNotification();
        getSystemService(NotificationManager.class).notify(1, notification);
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
        if (pendingVision >= 0 && assist.ready()) {
            int selected = pendingVision;
            pendingVision = -1;
            runVision(selected);
        } else if (startTranslationAfterConsent && assist.ready()) {
            startTranslationAfterConsent = false;
            assist.start();
        } else {
            pendingVision = -1;
            startTranslationAfterConsent = false;
            restorePanel();
        }
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
        boolean wasOpen = panelOpen;
        panelOpen = false;
        if (translation != null) translation.setVisibility(View.VISIBLE);
        var prefs = appearance(this);
        int size = Chat.dp(this, prefs.getInt("size", 56));
        var screen = getResources().getDisplayMetrics();
        bubbleX = Math.max(0, Math.min(screen.widthPixels - size, prefs.getInt("x", Chat.dp(this,16))));
        bubbleY = Math.max(0, Math.min(screen.heightPixels - size, prefs.getInt("y", Chat.dp(this,125))));
        var p = params(size, size, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        p.x = bubbleX;
        p.y = bubbleY;
        Button bubble = Chat.button(this, "AI", true, () -> { discreet = false; showPanel(); });
        var bubbleFill = Chat.shape(this, Chat.ORANGE, 28);
        bubbleFill.setStroke(Chat.dp(this, 2), Chat.PURPLE);
        bubble.setBackground(bubbleFill);
        bubble.setAlpha(discreet ? 0.12f : prefs.getInt("opacity", 100) / 100f);
        bubble.setPadding(0,0,0,0);
        bubble.setContentDescription("Buka chat Omni Float AI");
        bubble.setOnLongClickListener(v -> { quickMenu(); return true; });
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
                        var bounds = getResources().getDisplayMetrics();
                        p.x = bubbleX = Math.max(0, Math.min(bounds.widthPixels - size, oldX + dx));
                        p.y = bubbleY = Math.max(0, Math.min(bounds.heightPixels - size, oldY + dy));
                        windows.updateViewLayout(view, p);
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        if (moving) {
                            if (prefs.getBoolean("snap", false)) {
                                int edge = Math.max(0, getResources().getDisplayMetrics().widthPixels - size);
                                p.x = bubbleX = bubbleX < edge / 2 ? 0 : edge;
                                windows.updateViewLayout(view, p);
                            }
                            prefs.edit().putInt("x", bubbleX).putInt("y", bubbleY).apply();
                        }
                        return moving;
                    default:
                        return false;
                }
            }
        });
        show(bubble, p);
        if (wasOpen && assist != null && assist.running()) assist.refresh();
    }

    private void showPanel() {
        panelOpen = true;
        if (translation != null) translation.setVisibility(View.INVISIBLE);
        var metrics = getResources().getDisplayMetrics();
        var p = params(Math.min(Chat.dp(this, 410), metrics.widthPixels - Chat.dp(this, 16)),
            Math.min(Chat.dp(this, 570), (int) (metrics.heightPixels * 0.78)),
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH);
        p.gravity = Gravity.CENTER;
        p.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;
        var shell = Chat.column(this);
        int pad = Chat.dp(this, 8);
        shell.setPadding(pad, pad, pad, pad);
        var panelSurface = Chat.shape(this, Chat.BG, 24);
        panelSurface.setStroke(Chat.dp(this, 2), 0xffd7bafa);
        shell.setBackground(panelSurface);
        var top = new android.widget.LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        var heading = OmniUi.title(this, "Omni Float", 20);
        heading.setPadding(Chat.dp(this, 8), 0, 0, 0);
        top.addView(heading, new android.widget.LinearLayout.LayoutParams(0, -2, 1));
        top.addView(Chat.button(this, "Minimalkan", false, this::showBubble));
        shell.addView(top);
        var nav = new android.widget.LinearLayout(this);
        nav.addView(Chat.button(this, "Chatbot", !toolsTab, () -> { toolsTab = false; showPanel(); }),
            new android.widget.LinearLayout.LayoutParams(0, -2, 1));
        nav.addView(Chat.button(this, "Mode otomatis", toolsTab, () -> { toolsTab = true; showPanel(); }),
            new android.widget.LinearLayout.LayoutParams(0, -2, 1));
        OmniUi.addSpace(shell, nav, 7);
        android.widget.TextView currentResult = null;
        if (toolsTab) {
            var scroll = new android.widget.ScrollView(this);
            var panel = Chat.column(this);
            scroll.addView(panel);
            shell.addView(scroll, new android.widget.LinearLayout.LayoutParams(-1, 0, 1));
            currentResult = Chat.label(this, result, 15, Chat.INK);
            currentResult.setTextIsSelectable(true);
            currentResult.setPadding(Chat.dp(this, 8), Chat.dp(this, 8), Chat.dp(this, 8), 0);
            currentResult.setVisibility(result.isEmpty() ? View.GONE : View.VISIBLE);
            panel.addView(currentResult);

            var capture = OmniUi.card(this, panel, "Layar & kamera");
            OmniUi.addSpace(capture, Chat.button(this, assist.running()
                ? "Nonaktifkan Terjemah Layar" : "Aktifkan Terjemah Layar", !assist.running(), this::toggleTranslation), 8);
            OmniUi.addSpace(capture, OmniUi.caption(this, assist.paused()
                ? "Sedang dijeda" : "Terjemahan di atas aplikasi aktif"), 4);
            if (assist.running()) {
                var row = new android.widget.LinearLayout(this);
                OmniUi.rowButton(row, assist.locked() ? "Buka kunci hasil" : "Kunci hasil", false, () -> {
                    assist.setLocked(!assist.locked());
                    showBubble();
                });
                OmniUi.rowButton(row, assist.paused() ? "Lanjutkan" : "Jeda", false, () -> {
                    assist.setPaused(!assist.paused());
                    showBubble();
                });
                OmniUi.addSpace(capture, row, 7);
            }
            OmniUi.toolRow(capture, "Atur terjemah layar", "Kecepatan, area, dan tampilan", "Aa", () -> openScreenTools(false));
            if (!ScreenReadback.latest().isEmpty())
                OmniUi.toolRow(capture, "Hasil layar terakhir", "Buka teks asli dan hasil terjemah", "≡", () -> openScreenTools(true));
            OmniUi.toolRow(capture, "Terjemah Kamera", "Baca tulisan di sekitar", "◉", () -> openTool(CameraTranslator.class, 0, ""));
            OmniUi.toolRow(capture, "AI Smart Screen Vision", "Kirim satu tangkapan layar untuk dianalisis", "◈", this::visionOptions);
            if (!visionText.isEmpty()) {
                var row = new android.widget.LinearLayout(this);
                OmniUi.rowButton(row, "Salin analisis", false, () -> Toolbox.copy(this, visionText));
                OmniUi.rowButton(row, "Pelajari isi layar", false, () -> openTool(Toolbox.class, 0, visionText));
                OmniUi.addSpace(capture, row, 8);
            }

            var shortcuts = OmniUi.card(this, panel, "Ruang AI");
            OmniUi.toolRow(shortcuts, "Terjemahkan teks dan foto", "", "Aa", () -> openTool(TextDesk.class, 0, ""));
            OmniUi.toolRow(shortcuts, "AI Study Mode", "", "▤", () -> openTool(Toolbox.class, 0, ""));
            OmniUi.toolRow(shortcuts, "Monitor koneksi", "", "▥", () -> openTool(Toolbox.class, 1, ""));

            var quizCard = OmniUi.card(this, panel, "Kuis Chrome");
            quizCard.addView(Chat.button(this, quizExpanded ? "Sembunyikan kontrol kuis" : "Tampilkan kontrol kuis", false,
                () -> { quizExpanded = !quizExpanded; showPanel(); }));
            if (quizExpanded) {
                quizCard.addView(Chat.button(this, "Atur aksesibilitas untuk kuis Chrome", false, () ->
                    startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))));
                quizButton(quizCard, "Jawab 1 soal", 1, 60_000);
                quizButton(quizCard, "Otomatis sampai akhir halaman", Integer.MAX_VALUE, 1_800_000);
                quizButton(quizCard, "Otomatis 30 detik", Integer.MAX_VALUE, 30_000);
                quizButton(quizCard, "Otomatis 30 menit", Integer.MAX_VALUE, 1_800_000);
            }

            var settings = OmniUi.card(this, panel, "Kontrol sesi");
            settings.addView(Chat.button(this, assist.ready() ? "Matikan akses layar" : "Izinkan akses layar", false, () -> {
                if (assist.ready()) {
                    vision.cancel();
                    assist.release(true);
                    screenStatus("Akses layar dimatikan.");
                    restorePanel();
                } else {
                    pendingVision = -1;
                    askForCapture(false);
                }
            }));
            settings.addView(Chat.button(this, fadeTransition ? "Transisi halus: aktif" : "Transisi halus: nonaktif", false,
                () -> { fadeTransition = !fadeTransition; showPanel(); }));
            settings.addView(Chat.button(this, "Stop generation", false, () -> {
                Chat.SESSION.stop();
                quiz.cancel();
                vision.cancel();
                assist.stop();
                restorePanel();
            }));
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

    private void openTool(Class<?> screen, int tab, String text) {
        showBubble();
        if (assist.running()) assist.cancel();
        startActivity(new Intent(this, screen).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra("tab", tab).putExtra("studyText", text));
    }

    private void openScreenTools(boolean readback) {
        if (assist.running()) assist.cancel();
        showBubble();
        startActivity(new Intent(this, ScreenTools.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra("readback", readback));
    }

    private void visionOptions() {
        var menu = new AlertDialog.Builder(this, R.style.OmniTheme).setTitle("AI Smart Screen Vision")
            .setItems(new String[]{"Jelaskan layar", "Ekstrak teks", "Jelaskan grafik atau tabel"}, (dialog, choice) -> {
                if (assist.ready()) runVision(choice);
                else { pendingVision = choice; askForCapture(false); }
            }).setNegativeButton("Batal", null).create();
        showDialog(menu);
    }

    private void quickMenu() {
        var menu = new AlertDialog.Builder(this, R.style.OmniTheme).setTitle("Pintasan bubble")
            .setItems(new String[]{"Terjemah Layar", "Terjemah Kamera", "Terjemah Teks", "Belajar", "Monitor koneksi",
                "Atur bubble", "Redupkan bubble", "Matikan bubble"}, (dialog, choice) -> {
                switch (choice) {
                    case 0 -> toggleTranslation();
                    case 1 -> openTool(CameraTranslator.class, 0, "");
                    case 2 -> openTool(TextDesk.class, 0, "");
                    case 3 -> openTool(Toolbox.class, 0, "");
                    case 4 -> openTool(Toolbox.class, 1, "");
                    case 5 -> openTool(Toolbox.class, 2, "");
                    case 6 -> { discreet = true; showBubble(); }
                    case 7 -> stopSelf();
                }
            }).setNegativeButton("Tutup", null).create();
        showDialog(menu);
    }

    private void showDialog(AlertDialog dialog) {
        if (activeDialog != null) activeDialog.dismiss();
        activeDialog = dialog;
        dialog.setOnDismissListener(d -> { if (activeDialog == dialog) activeDialog = null; });
        dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
        try {
            dialog.show();
        } catch (WindowManager.BadTokenException | SecurityException error) {
            activeDialog = null;
            screenStatus("Panel tidak bisa ditampilkan. Periksa izin tampil di atas aplikasi.");
        }
    }

    private void runVision(int mode) {
        quiz.cancel();
        vision.cancel();
        assist.cancel();
        showBubble();
        assist.snapshot(frame -> {
            if (frame == null) visionResult("Layar belum dapat dibaca.", false);
            else vision.analyze(frame, mode);
        });
    }

    void visionResult(String value, boolean success) {
        visionText = success ? value : "";
        screenStatus(value);
        restorePanel();
    }

    private void askForCapture(boolean startTranslation) {
        if (startTranslation) pendingVision = -1;
        startTranslationAfterConsent = startTranslation;
        try {
            startActivity(new Intent(this, CaptureConsent.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            showBubble();
        } catch (android.content.ActivityNotFoundException | SecurityException error) {
            startTranslationAfterConsent = false;
            pendingVision = -1;
            screenStatus("Dialog akses layar tidak dapat dibuka.");
            restorePanel();
        }
    }

    private void toggleTranslation() {
        quiz.cancel();
        vision.cancel();
        if (assist.running()) {
            assist.release(true);
            screenStatus("Terjemah nonaktif.");
            restorePanel();
        } else if (assist.ready()) {
            assist.start();
        } else {
            askForCapture(true);
        }
    }

    boolean translationPaused() { return panelOpen; }
    boolean hasTranslation() { return translation != null; }
    void restoreTranslationBackground(android.graphics.Bitmap frame, int displayWidth, int displayHeight) {
        if (translation != null) translation.restoreBackground(frame, displayWidth, displayHeight, translationOpacity);
    }

    void showBubbleForTranslation() { showBubble(); }

    void refreshCapture() {
        if (overlay != null && !panelOpen) overlay.setTranslationX(overlay.getTranslationX() == 0f ? 1f : 0f);
    }

    private void quizButton(android.widget.LinearLayout panel, String name, int limit, long duration) {
        panel.addView(Chat.button(this, name, false, () -> {
            vision.cancel();
            assist.cancel();
            clearTranslation();
            showBubble();
            quiz.start(limit, duration);
        }));
    }

    boolean showTranslation(java.util.List<TranslationOverlay.Label> labels) {
        if (panelOpen || labels.isEmpty()) return false;
        ScreenReadback.remember(labels);
        if (OmniUi.sameLabels(rendered, labels, 7)) return false;
        if (translation != null) {
            translation.setLabels(labels);
            rendered = labels;
            return true;
        }
        var layer = new TranslationOverlay(this, labels);
        var layout = params(-1, -1, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN);
        layout.alpha = preferredTranslationOpacity();
        layout.setTitle("Omni Float Translation");
        if (Build.VERSION.SDK_INT >= 30) layout.setFitInsetsTypes(0);
        try {
            windows.addView(layer, layout);
            translation = layer;
            translationOpacity = layout.alpha;
            translationLayout = layout;
            rendered = labels;
            showBubble();
            return true;
        } catch (WindowManager.BadTokenException | SecurityException error) {
            screenStatus("Lapisan terjemah tidak bisa ditampilkan.");
            return false;
        }
    }

    private float preferredTranslationOpacity() {
        float maximum = Build.VERSION.SDK_INT >= 31
            ? getSystemService(android.hardware.input.InputManager.class).getMaximumObscuringOpacityForTouch() : 0.8f;
        return Math.min(ScreenOptions.read(this).opacity() / 100f, Math.max(0f, maximum - .09f));
    }

    private void updateTranslationOptions() {
        if (translation != null && translationLayout != null) {
            translationLayout.alpha = preferredTranslationOpacity();
            try {
                windows.updateViewLayout(translation, translationLayout);
                translationOpacity = translationLayout.alpha;
                translation.setLabels(rendered);
            } catch (IllegalArgumentException error) { clearTranslation(); }
        }
        assist.refresh();
    }

    void clearTranslation() {
        if (translation != null) {
            windows.removeViewImmediate(translation);
            translation = null;
            translationLayout = null;
        }
        rendered = java.util.Collections.emptyList();
    }

    @Override public void onConfigurationChanged(android.content.res.Configuration config) {
        super.onConfigurationChanged(config);
        if (assist != null) assist.displayChanged();
        if (quiz != null) quiz.cancel();
    }

    void screenStatus(String text) {
        result = text;
        if (resultView != null) {
            resultView.setText(text);
            resultView.setVisibility(text.isEmpty() ? View.GONE : View.VISIBLE);
        }
    }

    void restorePanel() {
        toolsTab = true;
        showPanel();
    }

    @Override public void onDestroy() {
        active = false;
        shuttingDown = true;
        if (activeDialog != null) activeDialog.dismiss();
        if (assist != null) assist.destroy();
        ScreenReadback.clear();
        if (quiz != null) quiz.destroy();
        if (vision != null) vision.destroy();
        clearTranslation();
        removeOverlay();
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }
}
