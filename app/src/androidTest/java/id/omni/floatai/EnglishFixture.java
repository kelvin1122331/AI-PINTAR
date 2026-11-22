package id.omni.floatai;

import android.app.Activity;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public final class EnglishFixture extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if ("image".equals(getIntent().getStringExtra("fixture"))) {
            shareImage();
            finish();
            return;
        }
        var scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xffffffff);
        var list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(25, 110, 25, 80);
        for (int i = 1; i <= 32; i++) {
            TextView row = new TextView(this);
            row.setText(i + ". " + (i % 2 == 0 ? "Thank you for reading this message." : "Good morning, how are you today?"));
            row.setTextSize(23);
            row.setTextColor(0xff0a0a0a);
            row.setPadding(0, 30, 0, 100);
            list.addView(row);
        }
        scroll.addView(list);
        setContentView(scroll);
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
                .setClassName("id.omni.floatai", "id.omni.floatai.ShareTranslation").setType("image/png")
                .putExtra(android.content.Intent.EXTRA_STREAM, uri).addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
            send.setClipData(android.content.ClipData.newRawUri("English", uri));
            startActivity(send);
        } catch (Exception error) {
            android.util.Log.e("OmniSmoke", "Fixture image could not be shared", error);
        }
    }
}
