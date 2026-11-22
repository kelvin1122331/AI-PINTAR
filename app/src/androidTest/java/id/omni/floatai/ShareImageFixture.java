package id.omni.floatai;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Button;

public final class ShareImageFixture extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        var share = new Button(this);
        share.setText("Bagikan contoh foto");
        share.setOnClickListener(view -> shareImage());
        setContentView(share);
    }

    private void shareImage() {
        try {
            var folder = new java.io.File(getCacheDir(), "fixture_images");
            if (!folder.isDirectory() && !folder.mkdirs()) throw new java.io.IOException("Cache directory unavailable");
            var photo = new java.io.File(folder, "welcome.png");
            var bitmap = android.graphics.Bitmap.createBitmap(900, 420, android.graphics.Bitmap.Config.ARGB_8888);
            var canvas = new android.graphics.Canvas(bitmap);
            canvas.drawColor(0xffffffff);
            var paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            paint.setTextSize(56);
            paint.setColor(0xff000000);
            canvas.drawText("Good morning and welcome.", 32, 180, paint);
            try (var output = new java.io.FileOutputStream(photo)) {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output);
            } finally { bitmap.recycle(); }
            var uri = android.net.Uri.parse("content://" + getPackageName() + ".fixture/welcome.png");
            var send = new android.content.Intent(android.content.Intent.ACTION_SEND)
                .setClassName("id.omni.floatai", "id.omni.floatai.TextDesk").setType("image/png")
                .putExtra(android.content.Intent.EXTRA_STREAM, uri).addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
            send.setClipData(android.content.ClipData.newRawUri("English", uri));
            startActivity(send);
        } catch (Exception error) {
            android.util.Log.e("OmniSmoke", "Fixture image could not be shared", error);
        }
    }
}
