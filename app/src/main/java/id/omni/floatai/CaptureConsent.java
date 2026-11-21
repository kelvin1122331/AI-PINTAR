package id.omni.floatai;

import android.app.Activity;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;

public final class CaptureConsent extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (state == null) {
            var manager = getSystemService(MediaProjectionManager.class);
            Intent request = android.os.Build.VERSION.SDK_INT >= 34
                ? manager.createScreenCaptureIntent(android.media.projection.MediaProjectionConfig.createConfigForDefaultDisplay())
                : manager.createScreenCaptureIntent();
            startActivityForResult(request, 1);
        }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == 1 && result == RESULT_OK && data != null) {
            startForegroundService(new Intent(this, BubbleService.class)
                .setAction(BubbleService.CAPTURE).putExtra("consent", data));
        }
        finishAndRemoveTask();
    }
}



