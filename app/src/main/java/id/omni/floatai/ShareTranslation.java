package id.omni.floatai;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

public final class ShareTranslation extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (state == null) forward(getIntent());
        finish();
    }

    private void forward(Intent inbound) {
        if (!Intent.ACTION_SEND.equals(inbound.getAction())) return;
        String type = inbound.getType();
        if (type == null) return;
        var open = new Intent(this, TextDesk.class);
        try {
            if (type.startsWith("text/")) {
                CharSequence text = inbound.getCharSequenceExtra(Intent.EXTRA_TEXT);
                if (text == null || text.length() == 0) { unavailable(); return; }
                open.putExtra("incoming_text", text.subSequence(0, Math.min(text.length(), TextDesk.LIMIT)).toString());
            } else if (type.startsWith("image/")) {
                var payload = inbound.getParcelableExtra(Intent.EXTRA_STREAM);
                Uri photo = payload instanceof Uri uri ? uri : null;
                if (photo == null && inbound.getClipData() != null && inbound.getClipData().getItemCount() > 0)
                    photo = inbound.getClipData().getItemAt(0).getUri();
                if (photo == null || !"content".equals(photo.getScheme())) { unavailable(); return; }
                open.setDataAndType(photo, type).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                open.setClipData(ClipData.newRawUri("Foto terjemahan", photo));
            } else {
                unavailable();
                return;
            }
            startActivity(open);
        } catch (RuntimeException error) { unavailable(); }
    }

    private void unavailable() { Toast.makeText(this, "Teks atau foto yang dibagikan belum dapat dibuka.", Toast.LENGTH_SHORT).show(); }
}
