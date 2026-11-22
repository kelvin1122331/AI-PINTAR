package id.omni.floatai;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import androidx.activity.ComponentActivity;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public final class CameraTranslator extends ComponentActivity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable scan = this::scan;
    private final StableTranslation stable = new StableTranslation();
    private PreviewView preview;
    private ImageView still;
    private TextView status;
    private TextView zoomText;
    private SeekBar zoom;
    private Button freeze;
    private Button switchLens;
    private Button pause;
    private Camera camera;
    private ProcessCameraProvider provider;
    private DeviceTranslator translator;
    private Bitmap frozen;
    private boolean frozenScanned;
    private boolean front;
    private boolean paused;
    private boolean torch;
    private boolean processing;
    private boolean retry;
    private int revision;
    private int[] previous;
    private TranslationOverlay layer;
    private List<TranslationOverlay.Label> last = Collections.emptyList();

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        front = state != null && state.getBoolean("front");
        var root = Chat.column(this);
        int pad = Chat.dp(this, 12);
        root.setPadding(pad, pad, pad, pad);
        Toolbox.applyInsets(root, pad);
        root.setBackgroundColor(Chat.BG);
        OmniUi.pageHeader(root, "Terjemah Kamera", "");
        status = OmniUi.caption(this, "Arahkan kamera ke teks.");
        OmniUi.addSpace(root, status, 8);
        var frame = new FrameLayout(this);
        frame.setBackgroundColor(0xff1e1429);
        preview = new PreviewView(this);
        preview.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);
        frame.addView(preview, new FrameLayout.LayoutParams(-1, -1));
        still = new ImageView(this);
        still.setScaleType(ImageView.ScaleType.FIT_XY);
        still.setContentDescription("Pratinjau kamera yang dibekukan");
        still.setVisibility(View.GONE);
        frame.addView(still, new FrameLayout.LayoutParams(-1, -1));
        layer = new TranslationOverlay(this, last);
        frame.addView(layer, new FrameLayout.LayoutParams(-1, -1));
        var space = new LinearLayout.LayoutParams(-1, 0, 1);
        space.topMargin = pad;
        root.addView(frame, space);
        var zoomRow = new LinearLayout(this);
        zoomRow.setGravity(Gravity.CENTER_VERTICAL);
        zoomText = OmniUi.caption(this, "1,0×");
        zoomRow.addView(zoomText, new LinearLayout.LayoutParams(Chat.dp(this, 58), -2));
        zoom = new SeekBar(this);
        zoom.setMax(100);
        zoom.setEnabled(false);
        zoom.setContentDescription("Zoom kamera");
        zoom.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onStartTrackingTouch(SeekBar bar) {}
            public void onStopTrackingTouch(SeekBar bar) {}
            public void onProgressChanged(SeekBar bar, int value, boolean user) {
                if (user && camera != null && frozen == null) camera.getCameraControl().setLinearZoom(value / 100f);
            }
        });
        zoomRow.addView(zoom, new LinearLayout.LayoutParams(0, -2, 1));
        OmniUi.addSpace(root, zoomRow, 6);

        var controls = new LinearLayout(this);
        pause = Chat.button(this, "Jeda", true, this::togglePause);
        controls.addView(pause, new LinearLayout.LayoutParams(0, -2, 1));
        OmniUi.rowButton(controls, "Senter", false, this::toggleTorch);
        OmniUi.rowButton(controls, "Salin", false, () -> Toolbox.copy(this, ScreenReadback.text(last, false)));
        OmniUi.addSpace(root, controls, 3);
        var cameraActions = new LinearLayout(this);
        freeze = Chat.button(this, "Bekukan gambar", false, this::toggleFreeze);
        cameraActions.addView(freeze, new LinearLayout.LayoutParams(0, -2, 1));
        switchLens = Chat.button(this, "Kamera depan", false, this::toggleLens);
        cameraActions.addView(switchLens, new LinearLayout.LayoutParams(0, -2, 1));
        OmniUi.addSpace(root, cameraActions, 5);
        setContentView(root);
        translator = new DeviceTranslator(this);
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
        var pending = ProcessCameraProvider.getInstance(this);
        pending.addListener(() -> {
            if (isDestroyed()) return;
            try {
                provider = pending.get();
                if (!provider.hasCamera(selector(front))) front = !front;
                bindCamera(front);
            } catch (Exception error) { status.setText("Kamera tidak dapat dibuka."); }
        }, command -> handler.post(command));
    }

    private CameraSelector selector(boolean useFront) {
        return useFront ? CameraSelector.DEFAULT_FRONT_CAMERA : CameraSelector.DEFAULT_BACK_CAMERA;
    }

    private void bindCamera(boolean useFront) throws Exception {
        var selection = selector(useFront);
        if (!provider.hasCamera(selection)) {
            status.setText(useFront ? "Kamera depan tidak tersedia." : "Kamera belakang tidak tersedia.");
            return;
        }
        if (camera != null) {
            camera.getCameraInfo().getZoomState().removeObservers(this);
            if (torch) camera.getCameraControl().enableTorch(false);
        }
        thaw(false);
        provider.unbindAll();
        var use = new Preview.Builder().build();
        use.setSurfaceProvider(preview.getSurfaceProvider());
        camera = provider.bindToLifecycle(this, selection, use);
        front = useFront;
        torch = false;
        switchLens.setText(front ? "Kamera belakang" : "Kamera depan");
        camera.getCameraInfo().getZoomState().observe(this, value -> {
            if (value == null) return;
            zoomText.setText(String.format(Locale.getDefault(), "%.1f×", value.getZoomRatio()));
            zoom.setProgress(Math.round(value.getLinearZoom() * 100));
            zoom.setEnabled(frozen == null && value.getMaxZoomRatio() > value.getMinZoomRatio());
        });
        status.setText("Arahkan kamera ke teks.");
        schedule(150);
    }

    private void toggleLens() {
        if (provider == null) { status.setText("Kamera masih disiapkan."); return; }
        if (frozen != null) { status.setText("Lanjutkan kamera sebelum mengganti lensa."); return; }
        boolean old = front;
        try {
            bindCamera(!front);
        } catch (Exception error) {
            try { bindCamera(old); } catch (Exception ignored) {}
            status.setText("Lensa tidak dapat diganti.");
        }
    }

    private void togglePause() {
        paused = !paused;
        revision++;
        pause.setText(paused ? "Lanjut" : "Jeda");
        status.setText(paused ? "Pembacaan dijeda." : "Membaca pratinjau…");
        if (!paused) { previous = null; frozenScanned = false; schedule(0); }
    }

    private void toggleFreeze() {
        if (frozen != null) { thaw(true); return; }
        Bitmap snapshot = preview.getBitmap();
        if (snapshot == null) { status.setText("Pratinjau kamera belum siap."); return; }
        frozen = snapshot;
        still.setImageBitmap(snapshot);
        still.setVisibility(View.VISIBLE);
        frozenScanned = false;
        paused = false;
        revision++;
        pause.setText("Jeda");
        freeze.setText("Lanjutkan kamera");
        zoom.setEnabled(false);
        status.setText("Membaca gambar yang dibekukan…");
        schedule(0);
    }

    private void thaw(boolean resume) {
        if (frozen != null) {
            still.setImageDrawable(null);
            still.setVisibility(View.GONE);
            // The view renderer may still hold the old image until the next draw.
            frozen = null;
        }
        revision++;
        frozenScanned = false;
        previous = null;
        stable.reset();
        last = Collections.emptyList();
        layer.setLabels(last);
        freeze.setText("Bekukan gambar");
        if (camera != null) {
            var value = camera.getCameraInfo().getZoomState().getValue();
            zoom.setEnabled(value != null && value.getMaxZoomRatio() > value.getMinZoomRatio());
        }
        if (resume) {
            status.setText("Kamera aktif.");
            schedule(0);
        }
    }

    private void toggleTorch() {
        if (frozen != null) { status.setText("Lanjutkan kamera untuk memakai senter."); return; }
        if (camera == null || !camera.getCameraInfo().hasFlashUnit()) {
            status.setText("Senter tidak tersedia pada kamera ini.");
            return;
        }
        boolean enabled = !torch;
        var current = camera;
        var pending = current.getCameraControl().enableTorch(enabled);
        pending.addListener(() -> {
            if (camera != current || isDestroyed()) return;
            try { pending.get(); torch = enabled; }
            catch (Exception error) { status.setText("Senter belum bisa diubah."); }
        }, command -> handler.post(command));
    }

    private void schedule(long delay) {
        handler.removeCallbacks(scan);
        handler.postDelayed(scan, delay);
    }

    private void scan() {
        if (paused || processing || isDestroyed() || !hasWindowFocus() || (frozen != null && frozenScanned)) return;
        Bitmap bitmap = frozen == null ? preview.getBitmap() : frozen.copy(Bitmap.Config.ARGB_8888, false);
        if (bitmap == null) { schedule(300); return; }
        int longest = Math.max(bitmap.getWidth(), bitmap.getHeight());
        if (longest > 1440) {
            Bitmap scaled = Bitmap.createScaledBitmap(bitmap, Math.max(1, bitmap.getWidth() * 1440 / longest),
                Math.max(1, bitmap.getHeight() * 1440 / longest), true);
            if (scaled != bitmap) bitmap.recycle();
            bitmap = scaled;
        }
        int[] scene = ScreenScene.signature(bitmap, 40, 48);
        boolean moved = ScreenScene.changed(previous, scene);
        if (!moved && !retry && frozen == null && !stable.needsConfirmation(scene)) {
            bitmap.recycle();
            schedule(680);
            return;
        }
        if (moved) stable.moved();
        previous = scene;
        retry = false;
        if (front) {
            var flip = new Matrix();
            flip.setScale(-1f, 1f);
            Bitmap corrected = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), flip, true);
            if (corrected != bitmap) bitmap.recycle();
            bitmap = corrected;
        }
        processing = true;
        int ticket = revision;
        String target = TranslationPreferences.target(this);
        if (frozen != null) frozenScanned = true;
        translator.translate(bitmap, TranslationPreferences.source(this), target, ScreenOptions.read(this).lines(),
            message -> { if (ticket == revision && !isDestroyed()) status.setText(message); }, result -> {
                processing = false;
                if (isDestroyed()) return;
                if (ticket == revision && !paused && hasWindowFocus()) showResult(scene, target, result);
                schedule(result.error() == null ? 760 : 3500);
            });
    }

    private void showResult(int[] scene, String target, DeviceTranslator.Result result) {
        if (result.error() != null) {
            status.setText(result.error());
            retry = true;
            frozenScanned = false;
            return;
        }
        if (!result.labels().isEmpty()) {
            var labels = stable.present(scene, front ? flipLabels(result.labels()) : result.labels());
            if (!OmniUi.sameLabels(last, labels, 8)) {
                last = labels;
                layer.setLabels(last);
            }
        } else if ((frozen != null && stable.needsConfirmation(scene))
                   || stable.confirmEmpty(scene, SystemClock.uptimeMillis(), 2400)) {
            stable.reset();
            last = Collections.emptyList();
            layer.setLabels(last);
        }
        status.setText(last.isEmpty() ? "Arahkan kamera ke tulisan yang jelas."
            : (frozen == null ? "Terjemahan · " : "Gambar beku · ") + TranslationPreferences.name(target));
    }

    private List<TranslationOverlay.Label> flipLabels(List<TranslationOverlay.Label> source) {
        var labels = new ArrayList<TranslationOverlay.Label>(source.size());
        for (var item : source) {
            labels.add(new TranslationOverlay.Label(Math.max(0, 1000 - item.x() - item.width()),
                item.y(), item.width(), item.height(), item.value(), item.original()));
        }
        return labels;
    }

    @Override public void onWindowFocusChanged(boolean focus) {
        super.onWindowFocusChanged(focus);
        if (focus && camera != null) schedule(0);
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putBoolean("front", front);
        super.onSaveInstanceState(state);
    }

    @Override protected void onDestroy() {
        handler.removeCallbacks(scan);
        if (translator != null) translator.close();
        if (camera != null) camera.getCameraInfo().getZoomState().removeObservers(this);
        super.onDestroy();
    }
}
