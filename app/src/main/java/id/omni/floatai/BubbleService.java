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
    private static final String CHANNEL = "floating_chat";
    private static final String STOP = "id.omni.floatai.STOP";
    private WindowManager windows;
    private View overlay;
    private int bubbleX;
    private int bubbleY;
    private boolean started;

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if ((intent != null && STOP.equals(intent.getAction())) || !Settings.canDrawOverlays(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (started) return START_NOT_STICKY;
        var notifications = getSystemService(NotificationManager.class);
        notifications.createNotificationChannel(new NotificationChannel(CHANNEL, "Bubble chat AI", NotificationManager.IMPORTANCE_LOW));
        var stop = PendingIntent.getService(this, 1, new Intent(this, BubbleService.class).setAction(STOP),
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        var open = PendingIntent.getActivity(this, 2, new Intent(this, MainActivity.class),
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification notification = new Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.omni)
            .setContentTitle("Omni Float AI").setContentText("Bubble chat aktif").setOngoing(true)
            .setContentIntent(open).addAction(new Notification.Action.Builder(null, "Matikan bubble", stop).build()).build();
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(1, notification);
        started = true;
        windows = getSystemService(WindowManager.class);
        bubbleX = Chat.dp(this, 16);
        bubbleY = Chat.dp(this, 125);
        showBubble();
        return START_NOT_STICKY;
    }

    private WindowManager.LayoutParams params(int width, int height, int flags) {
        var p = new WindowManager.LayoutParams(width, height, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags, PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.TOP | Gravity.LEFT;
        return p;
    }

    private void removeOverlay() {
        if (overlay != null) { windows.removeViewImmediate(overlay); overlay = null; }
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
        Button bubble = Chat.button(this, "AI", true, this::showPanel);
        bubble.setBackground(Chat.shape(this, Chat.MINT, 28));
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
        var metrics = getResources().getDisplayMetrics();
        var p = params(Math.min(Chat.dp(this, 410), metrics.widthPixels - Chat.dp(this, 16)),
            Math.min(Chat.dp(this, 470), (int) (metrics.heightPixels * 0.76)),
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH);
        p.gravity = Gravity.CENTER;
        p.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;
        var panel = Chat.SESSION.panel(this, "Minimalkan", this::showBubble, null);
        panel.setFocusableInTouchMode(true);
        panel.setOnTouchListener((view, event) -> {
            if (event.getActionMasked() != MotionEvent.ACTION_OUTSIDE) return false;
            showBubble();
            return true;
        });
        show(panel, p);
    }

    @Override public void onDestroy() {
        removeOverlay();
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }
}
