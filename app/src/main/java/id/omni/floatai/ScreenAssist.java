package id.omni.floatai;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.WindowManager;
import java.nio.ByteBuffer;

final class ScreenAssist {
    private static final int COLUMNS = 48;
    private static final int ROWS = 80;
    private final BubbleService service;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final DeviceTranslator translator = new DeviceTranslator();
    private final Runnable recognize = this::recognizeLatest;
    private MediaProjection projection;
    private ImageReader reader;
    private VirtualDisplay display;
    private Bitmap latest;
    private int[] signature;
    private long lastSample;
    private long lastChange;
    private boolean active;
    private boolean busy;
    private int run;
    private int revision;

    ScreenAssist(BubbleService service) { this.service = service; }

    boolean ready() { return projection != null; }
    boolean running() { return active; }

    void connect(Intent consent) {
        if (consent == null || ready()) return;
        try {
            projection = service.getSystemService(MediaProjectionManager.class)
                .getMediaProjection(Activity.RESULT_OK, consent);
            MediaProjection session = projection;
            projection.registerCallback(new MediaProjection.Callback() {
                @Override public void onStop() {
                    if (projection != session) return;
                    release(false);
                    service.screenStatus("Izin akses layar berakhir.");
                }
            }, ui);
            WindowManager windows = service.getSystemService(WindowManager.class);
            var metrics = new android.util.DisplayMetrics();
            windows.getDefaultDisplay().getRealMetrics(metrics);
            int longest = Math.max(metrics.widthPixels, metrics.heightPixels);
            float scale = Math.min(1f, 1920f / longest);
            int width = Math.max(1, Math.round(metrics.widthPixels * scale));
            int height = Math.max(1, Math.round(metrics.heightPixels * scale));
            reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
            reader.setOnImageAvailableListener(this::onFrame, ui);
            display = projection.createVirtualDisplay("Omni Float AI", width, height, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.getSurface(), null, ui);
            service.screenStatus("Akses layar aktif.");
        } catch (RuntimeException error) {
            release(true);
            service.screenStatus("Akses layar tidak bisa dimulai.");
        }
    }

    void start() {
        if (!ready() || active) return;
        active = true;
        run++;
        revision++;
        service.screenStatus("Terjemah aktif. Membaca layar…");
        service.showBubbleForTranslation();
        service.clearTranslation();
        lastChange = SystemClock.uptimeMillis();
        askForFrame();
    }

    private void askForFrame() {
        // Changing a tiny element in the overlay asks the display compositor for a fresh frame.
        service.refreshCapture();
    }

    private void onFrame(ImageReader source) {
        if (source != reader) return;
        try (Image image = source.acquireLatestImage()) {
            if (!active || image == null || service.translationPaused()) return;
            long now = SystemClock.uptimeMillis();
            if (signature != null && now - lastSample < 125) return;
            lastSample = now;
            Bitmap imageBitmap = copy(image);
            int[] current = signature(imageBitmap);
            if (service.hasTranslation()) {
                if (changed(signature, current, true)) {
                    revision++;
                    signature = null;
                    service.clearTranslation();
                }
                imageBitmap.recycle();
                return;
            }

            if (signature != null && !changed(signature, current, false)) {
                imageBitmap.recycle();
                return;
            }
            signature = current;
            latest(imageBitmap);
            revision++;
            lastChange = now;
            ui.removeCallbacks(recognize);
            ui.postDelayed(recognize, 240);
        } catch (RuntimeException error) {
            cancel();
            service.screenStatus("Tangkapan layar tidak bisa dibaca.");
        }
    }

    private void recognizeLatest() {
        if (!active || busy || latest == null || service.translationPaused()) return;
        long remaining = 240 - (SystemClock.uptimeMillis() - lastChange);
        if (remaining > 0) { ui.postDelayed(recognize, remaining); return; }
        Bitmap frame = latest;
        latest = null;
        int session = run;
        int frameRevision = revision;
        busy = true;
        translator.translate(frame, status -> {
            if (session == run && active) service.screenStatus(status);
        }, result -> {
            busy = false;
            if (session == run && active && frameRevision == revision && !service.translationPaused()) {
                if (result.error() != null) {
                    service.screenStatus(result.error());
                    ui.postDelayed(() -> {
                        if (session != run || !active || service.hasTranslation()) return;
                        signature = null;
                        askForFrame();
                    }, 4000);
                } else if (!result.labels().isEmpty()) {
                    service.showTranslation(result.labels());
                    service.screenStatus("Terjemah aktif / " + result.labels().size() + " bagian.");
                } else {
                    service.screenStatus("Terjemah aktif. Belum ada teks yang perlu diterjemahkan.");
                }
            }
            recognizeLatest();
        });
    }

    private boolean changed(int[] before, int[] after, boolean masked) {
        if (before == null) return true;
        int count = 0;
        for (int i = 0; i < after.length; i++) {
            if (before[i] == after[i]) continue;
            if (masked && service.translationCovers((i % COLUMNS + 0.5f) / COLUMNS,
                                                    (i / COLUMNS + 0.5f) / ROWS)) continue;
            if (++count >= 9) return true;
        }
        return false;
    }

    private static int[] signature(Bitmap bitmap) {
        Bitmap sample = Bitmap.createScaledBitmap(bitmap, COLUMNS, ROWS, true);
        int[] values = new int[COLUMNS * ROWS];
        sample.getPixels(values, 0, COLUMNS, 0, 0, COLUMNS, ROWS);
        sample.recycle();
        for (int i = 0; i < values.length; i++) values[i] &= 0x00e0e0e0;
        return values;
    }

    private static Bitmap copy(Image image) {
        var plane = image.getPlanes()[0];
        int width = image.getWidth();
        int height = image.getHeight();
        int stride = plane.getRowStride() / plane.getPixelStride();
        var padded = Bitmap.createBitmap(stride, height, Bitmap.Config.ARGB_8888);
        ByteBuffer bytes = plane.getBuffer();
        if (bytes.remaining() < padded.getByteCount()) {
            var full = ByteBuffer.allocate(padded.getByteCount());
            full.put(bytes).rewind();
            bytes = full;
        }
        padded.copyPixelsFromBuffer(bytes);
        Bitmap cropped = Bitmap.createBitmap(padded, 0, 0, width, height);
        if (padded != cropped) padded.recycle();
        return cropped;
    }

    void refresh() {
        if (!active) return;
        revision++;
        signature = null;
        latest(null);
        service.clearTranslation();
        askForFrame();
    }

    private void latest(Bitmap frame) {
        if (latest != null) latest.recycle();
        latest = frame;
    }

    void cancel() {
        active = false;
        run++;
        ui.removeCallbacks(recognize);
        latest(null);
        signature = null;
        service.clearTranslation();
    }

    void stop() { cancel(); service.screenStatus("Sesi layar berhenti."); }

    void release(boolean stopProjection) {
        cancel();
        MediaProjection old = projection;
        projection = null;
        if (display != null) { display.release(); display = null; }
        if (reader != null) { reader.close(); reader = null; }
        if (stopProjection && old != null) old.stop();
    }

    void destroy() { release(true); translator.close(); }
}
