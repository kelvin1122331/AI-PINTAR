package id.omni.floatai;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.*;
import androidx.activity.ComponentActivity;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;

public final class CameraTranslator extends ComponentActivity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable scan = this::scan;
    private PreviewView preview;
    private FrameLayout frame;
    private TextView status;
    private Camera camera;
    private DeviceTranslator translator;
    private boolean paused;
    private boolean torch;
    private boolean processing;
    private int revision;
    private TranslationOverlay layer;
    private java.util.List<TranslationOverlay.Label> last = java.util.Collections.emptyList();

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        var root = Chat.column(this);
        int pad = Chat.dp(this, 14);
        root.setPadding(pad, pad, pad, pad);
        Toolbox.applyInsets(root, pad);
        root.setBackgroundColor(Chat.BG);
        root.addView(Chat.label(this, "Terjemah Kamera", 25, Chat.INK));
        status = Chat.label(this, "Arahkan kamera ke teks.", 14, Chat.MUTED);
        root.addView(status);
        frame = new FrameLayout(this);
        preview = new PreviewView(this);
        preview.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);
        frame.addView(preview, new FrameLayout.LayoutParams(-1, -1));
        root.addView(frame, new LinearLayout.LayoutParams(-1, 0, 1));
        var row = new LinearLayout(this);
        Button pause = Chat.button(this, "Jeda", true, () -> {});
        pause.setOnClickListener(v -> {
            paused = !paused;
            revision++;
            pause.setText(paused ? "Lanjut" : "Jeda");
            if (!paused) schedule(0);
        });
        row.addView(pause, new LinearLayout.LayoutParams(0, -2, 1));
        row.addView(Chat.button(this, "Senter", false, this::toggleTorch), new LinearLayout.LayoutParams(0, -2, 1));
        row.addView(Chat.button(this, "Salin", false, () -> {
            var lines = new java.util.ArrayList<String>();
            for (var item : last) lines.add(item.value());
            Toolbox.copy(this, String.join("\n", lines));
        }), new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(row);
        setContentView(root);
        translator = new DeviceTranslator();
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera();
        else requestPermissions(new String[]{Manifest.permission.CAMERA}, 31);
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request != 31) return;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) startCamera();
        else status.setText("Izin kamera diperlukan untuk membaca teks.");
    }

    private void startCamera() {
        var provider = ProcessCameraProvider.getInstance(this);
        provider.addListener(() -> {
            if (isDestroyed()) return;
            try {
                var use = new Preview.Builder().build();
                use.setSurfaceProvider(preview.getSurfaceProvider());
                camera = provider.get().bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, use);
                schedule(150);
            } catch (Exception error) { status.setText("Kamera belakang tidak dapat dibuka."); }
        }, command -> handler.post(command));
    }

    private void toggleTorch() {
        if (camera == null || !camera.getCameraInfo().hasFlashUnit()) {
            status.setText("Senter tidak tersedia pada kamera ini.");
            return;
        }
        torch = !torch;
        camera.getCameraControl().enableTorch(torch);
    }

    private void schedule(long delay) {
        handler.removeCallbacks(scan);
        handler.postDelayed(scan, delay);
    }

    private void scan() {
        if (paused || processing || isDestroyed() || !hasWindowFocus()) return;
        Bitmap bitmap = preview.getBitmap();
        if (bitmap == null) { schedule(300); return; }
        int longest = Math.max(bitmap.getWidth(), bitmap.getHeight());
        if (longest > 1440) {
            Bitmap scaled = Bitmap.createScaledBitmap(bitmap, Math.max(1, bitmap.getWidth() * 1440 / longest),
                Math.max(1, bitmap.getHeight() * 1440 / longest), true);
            if (scaled != bitmap) bitmap.recycle();
            bitmap = scaled;
        }
        processing = true;
        int ticket = revision;
        translator.translate(bitmap, status::setText, result -> {
            processing = false;
            if (isDestroyed()) return;
            if (ticket == revision && !paused && hasWindowFocus()) {
                if (layer != null) frame.removeView(layer);
                layer = null;
                last = result.labels();
                if (!last.isEmpty()) {
                    layer = new TranslationOverlay(this, last);
                    frame.addView(layer, new FrameLayout.LayoutParams(-1, -1));
                }
                status.setText(result.error() != null ? result.error() : last.isEmpty()
                    ? "Arahkan kamera ke tulisan asing yang jelas." : "Terjemahan Bahasa Indonesia");
            }
            schedule(result.error() == null ? 850 : 3500);
        });
    }

    @Override public void onWindowFocusChanged(boolean focus) {
        super.onWindowFocusChanged(focus);
        if (focus && camera != null) schedule(0);
    }

    @Override protected void onDestroy() {
        handler.removeCallbacks(scan);
        if (translator != null) translator.close();
        super.onDestroy();
    }
}
