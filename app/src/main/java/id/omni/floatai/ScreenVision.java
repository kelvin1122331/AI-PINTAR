package id.omni.floatai;

import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.Executors;

final class ScreenVision {
    private final BubbleService service;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final java.util.concurrent.ExecutorService worker = Executors.newCachedThreadPool();
    private Providers.Cancellation call;
    private final Runnable deadline;
    private volatile int revision;

    ScreenVision(BubbleService service) {
        this.service = service;
        deadline = () -> {
            cancel();
            service.visionResult("Analisis layar melewati batas 55 detik.", false);
        };
    }

    void analyze(Bitmap frame, int mode) {
        cancel();
        int ticket = revision;
        var cancellation = new Providers.Cancellation();
        call = cancellation;
        ui.postDelayed(deadline, 55_000);
        service.screenStatus("Menganalisis satu tangkapan layar…");
        worker.execute(() -> {
            try {
                if (ticket != revision) return;
                var profile = Providers.load(service, Providers.active(service));
                if (profile.model().isEmpty()) throw new IllegalStateException("Isi API key dan pilih model yang menerima gambar.");
                var bytes = new ByteArrayOutputStream();
                frame.compress(Bitmap.CompressFormat.JPEG, 87, bytes);
                String image = "data:image/jpeg;base64," + Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP);
                String[] modes = {"Jelaskan isi layar dan konteksnya.", "Ekstrak hanya teks yang bisa dibaca, sesuai urutan.",
                    "Jelaskan grafik, tabel, atau diagram yang terlihat. Sebutkan jika detail tidak terbaca."};
                var parts = new JSONArray()
                    .put(new JSONObject().put("type","text").put("text","Jawab dalam Bahasa Indonesia kecuali ketika menyalin teks. " + modes[mode]))
                    .put(new JSONObject().put("type","image_url").put("image_url",new JSONObject().put("url",image)));
                var input = new JSONArray().put(new JSONObject().put("role","user").put("content",parts));
                var answer = Providers.complete(profile, input, "", cancellation);
                ui.post(() -> finish(ticket, answer.text(), true));
            } catch (Exception error) {
                ui.post(() -> finish(ticket, ConnectionMonitor.describe(error) + " Pastikan model menerima gambar.", false));
            } finally { frame.recycle(); }
        });
    }

    private void finish(int ticket, String value, boolean success) {
        if (ticket != revision) return;
        ui.removeCallbacks(deadline);
        call = null;
        service.visionResult(value, success);
    }

    void cancel() {
        revision++;
        ui.removeCallbacks(deadline);
        if (call != null) call.cancel();
        call = null;
    }

    void destroy() { cancel(); worker.shutdown(); }
}
