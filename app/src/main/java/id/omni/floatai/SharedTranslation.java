package id.omni.floatai;

import android.content.Intent;
import android.net.Uri;

final class SharedTranslation {
    record Input(String text, Uri image) {}
    private static final Input EMPTY = new Input(null, null);

    static Input read(Intent intent) {
        if (intent == null || !Intent.ACTION_SEND.equals(intent.getAction())) return null;
        String type = intent.getType();
        if (type == null) return EMPTY;
        try {
            if ("text/plain".equals(type)) {
                CharSequence incoming = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
                if (incoming == null || incoming.length() == 0) return EMPTY;
                String text = incoming.subSequence(0, Math.min(incoming.length(), TextDesk.LIMIT)).toString();
                return new Input(text, null);
            }
            if (type.startsWith("image/")) {
                var payload = intent.getParcelableExtra(Intent.EXTRA_STREAM);
                Uri image = payload instanceof Uri uri ? uri : null;
                if (image == null && intent.getClipData() != null && intent.getClipData().getItemCount() > 0)
                    image = intent.getClipData().getItemAt(0).getUri();
                if (image != null && "content".equals(image.getScheme())) return new Input(null, image);
            }
        } catch (RuntimeException ignored) {}
        return EMPTY;
    }

    private SharedTranslation() {}
}
