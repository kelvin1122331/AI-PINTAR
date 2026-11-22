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
    private static final long SAMPLE_GAP = 140;
    private final BubbleService service;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final DeviceTranslator translator;
    private final StableTranslation overlay = new StableTranslation();
    private final Runnable recognize = this::recognizeLatest;
    private final Runnable pulse = this::askForFrame;
    private final Runnable snapshotTimeout = this::expireSnapshot;
    private MediaProjection projection;
    private ImageReader reader;
    private VirtualDisplay display;
    private ScreenOptions.Crop latest;
    private java.util.function.Consumer<Bitmap> snapshot;
    private int[] signature;
    private int[] visibleSignature;
    private long settleUntil;
    private long captureAfter;
    private long lastSample;
    private long lastChange;
    private boolean forceSample;
    private boolean paused;
    private boolean locked;
    private boolean active;
    private boolean busy;
    private int displayWidth;
    private int displayHeight;
    private int run;
    private int revision;

    ScreenAssist(BubbleService service) {
        this.service = service;
        translator = new DeviceTranslator(service);
    }

    boolean ready() { return projection != null && display != null; }
    boolean running() { return active; }
    boolean paused() { return paused; }
    boolean locked() { return locked; }

    void connect(Intent consent) {
        if (consent == null || ready()) return;
        try {
            projection = service.getSystemService(MediaProjectionManager.class)
                .getMediaProjection(Activity.RESULT_OK, consent);
            MediaProjection session = projection;
            session.registerCallback(new MediaProjection.Callback() {
                @Override public void onStop() {
                    if (projection != session) return;
                    release(false);
                    service.screenStatus("Izin akses layar berakhir.");
                }

                @Override public void onCapturedContentResize(int width, int height) {
                    if (projection == session && ready()) resize(width, height, metrics().densityDpi);
                }
            }, ui);
            var metrics = metrics();
            setupReader(metrics.widthPixels, metrics.heightPixels, metrics.densityDpi);
            service.screenStatus("Akses layar aktif.");
        } catch (RuntimeException error) {
            release(true);
            service.screenStatus("Akses layar tidak bisa dimulai.");
        }
    }

    private android.util.DisplayMetrics metrics() {
        var dimensions = new android.util.DisplayMetrics();
        service.getSystemService(WindowManager.class).getDefaultDisplay().getRealMetrics(dimensions);
        return dimensions;
    }

    void displayChanged() {
        if (!ready()) return;
        var dimensions = metrics();
        resize(dimensions.widthPixels, dimensions.heightPixels, dimensions.densityDpi);
    }

    private void resize(int width, int height, int density) {
        if (width < 1 || height < 1 || (width == displayWidth && height == displayHeight)) return;
        try {
            setupReader(width, height, density);
            overlay.reset();
            service.clearTranslation();
            resetSamples();
        } catch (RuntimeException error) {
            release(true);
            service.screenStatus("Ukuran layar berubah. Aktifkan kembali izin layar.");
        }
    }

    private void setupReader(int width, int height, int density) {
        float scale = Math.min(1f, 1920f / Math.max(width, height));
        int scaledWidth = Math.max(1, Math.round(width * scale));
        int scaledHeight = Math.max(1, Math.round(height * scale));
        ImageReader next = ImageReader.newInstance(scaledWidth, scaledHeight, PixelFormat.RGBA_8888, 2);
        try {
            next.setOnImageAvailableListener(this::onFrame, ui);
            if (display == null) {
                display = projection.createVirtualDisplay("Omni Float AI", scaledWidth, scaledHeight, density,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, next.getSurface(), null, ui);
                if (display == null) throw new IllegalStateException("Display unavailable");
            } else {
                display.resize(scaledWidth, scaledHeight, density);
                display.setSurface(next.getSurface());
            }
        } catch (RuntimeException error) {
            next.close();
            throw error;
        }
        if (reader != null) reader.close();
        reader = next;
        displayWidth = width;
        displayHeight = height;
    }

    void start() {
        if (!ready() || active) return;
        active = true;
        run++;
        service.screenStatus("Membaca teks di layar…");
        service.showBubbleForTranslation();
        overlay.reset();
        service.clearTranslation();
        resetSamples();
        service.refreshNotification();
    }

    private void askForFrame() {
        service.refreshCapture();
    }

    private boolean canTranslate() {
        return active && !paused && !locked && !service.translationPaused();
    }

    private void onFrame(ImageReader source) {
        if (source != reader) return;
        Bitmap working = null;
        try (Image image = source.acquireLatestImage()) {
            if (image == null) return;
            long now = SystemClock.uptimeMillis();
            if (snapshot != null && now >= captureAfter) {
                deliverSnapshot(copy(image));
                return;
            }
            if (!canTranslate() || now < settleUntil) return;
            if (lastSample > 0 && now - lastSample < SAMPLE_GAP) {
                ui.removeCallbacks(pulse);
                ui.postAtTime(pulse, lastSample + SAMPLE_GAP + 8);
                return;
            }
            lastSample = now;
            working = copy(image);
            int[] visible = ScreenScene.signature(working, COLUMNS, ROWS);
            if (!forceSample && !ScreenScene.changed(visibleSignature, visible)) return;
            visibleSignature = visible;
            service.restoreTranslationBackground(working, displayWidth, displayHeight);
            var crop = ScreenOptions.crop(working, ScreenOptions.read(service).area());
            working = crop.bitmap();
            int[] current = ScreenScene.signature(working, COLUMNS, ROWS);
            boolean moved = ScreenScene.changed(signature, current);
            if (!forceSample && !moved) return;
            forceSample = false;
            if (moved) overlay.moved();
            signature = current;
            setLatest(crop);
            working = null;
            revision++;
            lastChange = now;
            ui.removeCallbacks(recognize);
            ui.postDelayed(recognize, ScreenOptions.read(service).quietMillis());
        } catch (RuntimeException error) {
            if (snapshot != null) expireSnapshot();
            if (active) {
                service.screenStatus("Satu tangkapan gagal dibaca. Terjemahan terakhir tetap ditampilkan.");
                scheduleRetry(run, 2000);
            }
        } finally {
            if (working != null && !working.isRecycled()) working.recycle();
        }
    }

    private void recognizeLatest() {
        if (!canTranslate() || busy || latest == null) return;
        var settings = ScreenOptions.read(service);
        long remaining = settings.quietMillis() - (SystemClock.uptimeMillis() - lastChange);
        if (remaining > 0) { ui.postDelayed(recognize, remaining); return; }
        var crop = latest;
        latest = null;
        int session = run;
        int frameRevision = revision;
        int[] scene = signature;
        busy = true;
        translator.translate(crop.bitmap(), TranslationPreferences.source(service), TranslationPreferences.target(service),
            settings.lines(), status -> {
                if (session == run && active) service.screenStatus(status);
            }, result -> {
                busy = false;
                if (session == run && canTranslate() && frameRevision == revision) {
                    handleResult(crop, scene, settings, result, session);
                }
                recognizeLatest();
            });
    }

    private void handleResult(ScreenOptions.Crop crop, int[] scene, ScreenOptions.Settings settings,
                              DeviceTranslator.Result result, int session) {
        if (result.error() != null) {
            service.screenStatus(result.error());
            scheduleRetry(session, 4000);
            return;
        }
        if (!result.labels().isEmpty()) {
            var labels = overlay.present(scene, crop.place(result.labels()));
            if (service.showTranslation(labels)) pauseForRender();
            service.screenStatus("Terjemah aktif · " + labels.size() + " bagian.");
            return;
        }
        if (!service.hasTranslation()) {
            service.screenStatus("Belum ada teks yang perlu diterjemahkan.");
            return;
        }
        if (overlay.confirmEmpty(scene, SystemClock.uptimeMillis(), settings.blankHoldMillis())) {
            overlay.reset();
            service.clearTranslation();
            pauseForRender();
            service.screenStatus("Belum ada teks yang perlu diterjemahkan.");
        } else if (overlay.needsConfirmation(scene)) {
            scheduleRetry(session, 680);
        }
    }

    private void scheduleRetry(int session, int delay) {
        int requested = revision;
        ui.postDelayed(() -> {
            if (session != run || requested != revision || !canTranslate()) return;
            forceSample = true;
            askForFrame();
        }, delay);
    }

    private void pauseForRender() {
        settleUntil = SystemClock.uptimeMillis() + 185;
        visibleSignature = null;
        ui.removeCallbacks(pulse);
        ui.postAtTime(pulse, settleUntil + 15);
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

    private void expireSnapshot() { deliverSnapshot(null); }

    private void deliverSnapshot(Bitmap frame) {
        var callback = snapshot;
        snapshot = null;
        ui.removeCallbacks(snapshotTimeout);
        if (callback != null) callback.accept(frame);
    }

    void snapshot(java.util.function.Consumer<Bitmap> callback) {
        cancel();
        ui.postDelayed(() -> {
            if (snapshot == callback) askForFrame();
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
        if (!canTranslate()) return;
        resetSamples();
    }

    private void resetSamples() {
        revision++;
        overlay.moved();
        signature = null;
        forceSample = true;
        setLatest(null);
        pauseForRender();
    }

    private void setLatest(ScreenOptions.Crop frame) {
        if (latest != null) latest.bitmap().recycle();
        latest = frame;
    }

    void cancel() {
        active = false;
        paused = false;
        locked = false;
        forceSample = false;
        snapshot = null;
        ui.removeCallbacks(snapshotTimeout);
        run++;
        ui.removeCallbacks(recognize);
        ui.removeCallbacks(pulse);
        setLatest(null);
        overlay.reset();
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

    void destroy() { release(true); translator.close(); ui.removeCallbacksAndMessages(null); }
}
