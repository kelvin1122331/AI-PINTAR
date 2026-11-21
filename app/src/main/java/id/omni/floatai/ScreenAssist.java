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
import android.util.Base64;
import android.view.WindowManager;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

final class ScreenAssist {
    private final BubbleService service;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final java.util.concurrent.ExecutorService worker = Executors.newCachedThreadPool();
    private MediaProjection projection;
    private ImageReader reader;
    private VirtualDisplay display;
    private Future<?> request;
    private final Runnable next = this::analyze;
    private long deadline;
    private final Runnable expire = this::stop;
    private int generation;
    private int[] previous;
    private String lastModel = "";
    private String instruction;
    private boolean active;

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
                    service.clearTranslation();
                    service.screenStatus("Izin akses layar berakhir.");
                }
            }, ui);
            WindowManager windows = service.getSystemService(WindowManager.class);
            var metrics = new android.util.DisplayMetrics();
            windows.getDefaultDisplay().getRealMetrics(metrics);
            int max = Math.max(metrics.widthPixels, metrics.heightPixels);
            int width = Math.max(1, metrics.widthPixels * Math.min(max, 1600) / max);
            int height = Math.max(1, metrics.heightPixels * Math.min(max, 1600) / max);
            reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
            reader.setOnImageAvailableListener(r -> {
                if (!active) {
                    try (Image ignored = r.acquireLatestImage()) {}
                }
            }, ui);
            display = projection.createVirtualDisplay("Omni Float AI", width, height, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.getSurface(), null, ui);
            service.screenStatus("Akses layar aktif. Pilih fungsi yang ingin dijalankan.");
        } catch (RuntimeException error) {
            release(true);
            service.screenStatus("Akses layar tidak bisa dimulai.");
        }
    }

    void start(String prompt, long duration) {
        if (!ready()) {
            service.screenStatus("Aktifkan akses layar terlebih dulu.");
            return;
        }
        cancel();
        active = true;
        instruction = prompt;
        deadline = duration == 0 ? 0 : SystemClock.elapsedRealtime() + duration;
        if (duration > 0) ui.postDelayed(expire, duration);
        service.screenStatus("Membaca layar.");
        ui.postDelayed(next, 450);
    }

    private void analyze() {
        if (!active) return;
        if (deadline > 0 && SystemClock.elapsedRealtime() >= deadline) { stop(); return; }
        Bitmap frame;
        try {
            frame = frame();
        } catch (RuntimeException error) {
            stopWith("Layar tidak bisa dibaca.");
            return;
        }
        if (frame == null) {
            ui.postDelayed(next, 300);
            return;
        }
        int[] signature = new int[1200];
        for (int i = 0; i < signature.length; i++) {
            int x = (i % 30) * frame.getWidth() / 30;
            int y = (i / 30) * frame.getHeight() / 40;
            signature[i] = frame.getPixel(x, y) & 0x00e0e0e0;
        }
        int changed = 0;
        if (previous != null) {
            for (int i = 0; i < signature.length; i++) if (signature[i] != previous[i]) changed++;
        }
        if (deadline > 0 && previous != null && changed < 12) {
            frame.recycle();
            ui.postDelayed(next, 1000);
            return;
        }
        previous = signature;
        int ticket = generation;
        String prompt = instruction;
        String preferredModel = lastModel;
        request = worker.submit(() -> {
            try {
                var jpeg = new ByteArrayOutputStream();
                frame.compress(Bitmap.CompressFormat.JPEG, 82, jpeg);
                String image = "data:image/jpeg;base64," + Base64.encodeToString(jpeg.toByteArray(), Base64.NO_WRAP);
                var content = new JSONArray()
                    .put(new JSONObject().put("type", "text").put("text", prompt))
                    .put(new JSONObject().put("type", "image_url").put("image_url", new JSONObject().put("url", image)));
                var messages = new JSONArray().put(new JSONObject().put("role", "user").put("content", content));
                Providers.Profile profile = Providers.load(service, Providers.active(service));
                if (profile.model().isEmpty()) throw new IllegalStateException("Simpan provider dan model di menu API.");
                var result = Providers.complete(profile, messages, preferredModel);
                ui.post(() -> {
                    if (ticket != generation) return;
                    active = false;
                    if (!service.showTranslation(result.text())) {
                        service.screenStatus("Tidak ada terjemahan yang bisa ditempatkan di layar.");
                        service.restorePanel();
                    }
                });
            } catch (Exception error) {
                ui.post(() -> {
                    if (ticket == generation) stopWith("Gagal membaca gambar lewat API. Coba pilih model yang mendukung input gambar.");
                });
            } finally {
                frame.recycle();
            }
        });
    }

    private Bitmap frame() {
        try (Image image = reader.acquireLatestImage()) {
            if (image == null) return null;
            var plane = image.getPlanes()[0];
            int width = image.getWidth();
            int height = image.getHeight();
            var padded = Bitmap.createBitmap(plane.getRowStride() / plane.getPixelStride(), height, Bitmap.Config.ARGB_8888);
            padded.copyPixelsFromBuffer(plane.getBuffer());
            Bitmap cropped = Bitmap.createBitmap(padded, 0, 0, width, height);
            if (padded != cropped) padded.recycle();
            return cropped;
        }
    }

    void cancel() {
        generation++;
        ui.removeCallbacks(next);
        ui.removeCallbacks(expire);
        if (request != null) request.cancel(true);
        request = null;
        active = false;
        previous = null;
        lastModel = "";
    }

    void stop() { stopWith("Sesi layar berhenti."); }

    private void stopWith(String text) {
        cancel();
        service.screenStatus(text);
        service.restorePanel();
    }

    void release(boolean stopProjection) {
        cancel();
        MediaProjection old = projection;
        projection = null;
        if (display != null) { display.release(); display = null; }
        if (reader != null) { reader.close(); reader = null; }
        if (stopProjection && old != null) old.stop();
    }

    void destroy() { release(true); worker.shutdownNow(); }
}


