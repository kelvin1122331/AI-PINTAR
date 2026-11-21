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
    private java.util.function.Consumer<Bitmap> snapshot;
    private final Runnable snapshotTimeout = () -> {
        var callback = snapshot;
        snapshot = null;
        if (callback != null) callback.accept(null);
    };
    private int[] signature;
    private int[] visibleSignature;
    private long settleUntil;
    private long captureAfter;
    private boolean paused;
    private boolean locked;
    private int emptyReads;
    private int displayWidth;
    private int displayHeight;
    private final Runnable pulse = () -> askForFrame();
    private long lastSample;
    private long lastChange;
    private boolean active;
    private boolean busy;
    private int run;
    private int revision;

    ScreenAssist(BubbleService service) { this.service = service; }

    boolean ready() { return projection != null; }
    boolean running() { return active; }
    boolean paused() { return paused; }
    boolean locked() { return locked; }

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
            displayWidth = metrics.widthPixels;
            displayHeight = metrics.heightPixels;
            int longest = Math.max(displayWidth, displayHeight);
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
        service.screenStatus("Terjemah aktif. Membaca layar.");
        service.showBubbleForTranslation();
        service.clearTranslation();
        resetSamples();
        service.refreshNotification();
    }

    private void askForFrame() {
        // Changing a tiny element in the overlay asks the display compositor for a fresh frame.
        service.refreshCapture();
    }

    private void onFrame(ImageReader source) {
        if (source != reader) return;
        try (Image image = source.acquireLatestImage()) {
            if (image == null) return;
            if (snapshot != null && SystemClock.uptimeMillis() >= captureAfter) {
                Bitmap frame = copy(image);
                var callback = snapshot;
                snapshot = null;
                ui.removeCallbacks(snapshotTimeout);
                callback.accept(frame);
                return;
            }
            if (!active || paused || locked || service.translationPaused()) return;
            long now = SystemClock.uptimeMillis();
            if (now < settleUntil) return;
            if (visibleSignature != null && now - lastSample < 144) {
                ui.removeCallbacks(pulse);
                ui.postAtTime(pulse, lastSample + 150);
                return;
            }
            lastSample = now;
            Bitmap imageBitmap = copy(image);
            int[] visible = signature(imageBitmap);
            if (!ScreenScene.changed(visibleSignature, visible)) {
                imageBitmap.recycle();
                return;
            }
            visibleSignature = visible;
            service.restoreTranslationBackground(imageBitmap, displayWidth, displayHeight);
            int[] current = signature(imageBitmap);
            if (!ScreenScene.changed(signature, current)) {
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
        if (!active || paused || locked || busy || latest == null || service.translationPaused()) return;
        long remaining = 240 - (SystemClock.uptimeMillis() - lastChange);
        if (remaining > 0) { ui.postDelayed(recognize, remaining); return; }
        Bitmap frame = latest;
        latest = null;
        int session = run;
        int frameRevision = revision;
        busy = true;
        translator.translate(frame, TranslationPreferences.source(service), TranslationPreferences.target(service), status -> {
            if (session == run && active) service.screenStatus(status);
        }, result -> {
            busy = false;
            if (session == run && active && !paused && !locked && frameRevision == revision && !service.translationPaused()) {
                if (result.error() != null) {
                    service.screenStatus(result.error());
                    scheduleRetry(session, 4000);
                } else if (!result.labels().isEmpty()) {
                    emptyReads = 0;
                    if (service.showTranslation(result.labels())) pauseForRender();
                    service.screenStatus("Terjemah aktif / " + result.labels().size() + " bagian.");
                } else if (!service.hasTranslation() || ++emptyReads >= 3) {
                    service.clearTranslation();
                    pauseForRender();
                    service.screenStatus("Terjemah aktif. Belum ada teks baru yang perlu diterjemahkan.");
                } else {
                    scheduleRetry(session, 500);
                }
            }
            recognizeLatest();
        });
    }

    private void scheduleRetry(int session, int delay) {
        int requested = revision;
        ui.postDelayed(() -> {
            if (session != run || requested != revision || !active || paused || locked) return;
            signature = null;
            visibleSignature = null;
            askForFrame();
        }, delay);
    }

    private void pauseForRender() {
        settleUntil = SystemClock.uptimeMillis() + 165;
        visibleSignature = null;
        ui.removeCallbacks(pulse);
        ui.postAtTime(pulse, settleUntil + 10);
    }

    private static int[] signature(Bitmap bitmap) {
        Bitmap sample = Bitmap.createScaledBitmap(bitmap, COLUMNS, ROWS, true);
        int[] values = new int[COLUMNS * ROWS];
        sample.getPixels(values, 0, COLUMNS, 0, 0, COLUMNS, ROWS);
        if (sample != bitmap) sample.recycle();
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
        if (cropped.isMutable()) return cropped;
        Bitmap writable = cropped.copy(Bitmap.Config.ARGB_8888, true);
        cropped.recycle();
        return writable;
    }

    void snapshot(java.util.function.Consumer<Bitmap> callback) {
        cancel();
        ui.postDelayed(() -> {
            if (snapshot == callback) service.refreshCapture();
        }, 280);
        captureAfter = SystemClock.uptimeMillis() + 220;
        snapshot = callback;
        ui.postDelayed(snapshotTimeout, 3500);
    }

    void setPaused(boolean value) {
        if (!active || paused == value) return;
        paused = value;
        if (!paused) refresh();
        service.screenStatus(paused ? "Terjemahan dijeda." : "Terjemahan dilanjutkan.");
        service.refreshNotification();
    }

    void setLocked(boolean value) {
        if (!active || locked == value) return;
        locked = value;
        if (!locked) refresh();
        service.screenStatus(locked ? "Hasil terjemahan dikunci." : "Hasil bisa diperbarui.");
    }

    void refresh() {
        if (!active || paused || locked) return;
        resetSamples();
    }

    private void resetSamples() {
        revision++;
        emptyReads = 0;
        signature = null;
        latest(null);
        pauseForRender();
    }

    private void latest(Bitmap frame) {
        if (latest != null) latest.recycle();
        latest = frame;
    }

    void cancel() {
        active = false;
        paused = false;
        locked = false;
        snapshot = null;
        ui.removeCallbacks(snapshotTimeout);
        run++;
        ui.removeCallbacks(recognize);
        ui.removeCallbacks(pulse);
        latest(null);
        signature = null;
        visibleSignature = null;
        service.clearTranslation();
        service.refreshNotification();
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
