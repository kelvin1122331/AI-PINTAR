package id.omni.floatai;

import android.graphics.Bitmap;
import android.graphics.Rect;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.nl.languageid.LanguageIdentification;
import com.google.mlkit.nl.translate.TranslateLanguage;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.nl.translate.TranslatorOptions;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.Consumer;

final class DeviceTranslator {
    record Result(List<TranslationOverlay.Label> labels, String error) {}
    private record Block(Rect box, String text) {}
    private record Pair(String source, String text) {}
    private final com.google.mlkit.vision.text.TextRecognizer ocr =
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
    private final com.google.mlkit.nl.languageid.LanguageIdentifier language = LanguageIdentification.getClient();
    private final HashMap<String, Translator> clients = new HashMap<>();
    private final HashMap<String, Task<Void>> models = new HashMap<>();
    private final LinkedHashMap<Pair, String> cache = new LinkedHashMap<>(128, 0.75f, true);
    private boolean closed;

    void translate(Bitmap frame, Consumer<String> status, Consumer<Result> result) {
        int width = frame.getWidth();
        int height = frame.getHeight();
        ocr.process(InputImage.fromBitmap(frame, 0)).addOnCompleteListener(task -> {
            frame.recycle();
            if (closed) { result.accept(empty(null)); return; }
            if (!task.isSuccessful()) { result.accept(empty("Teks layar gagal dibaca.")); return; }
            var blocks = new ArrayList<Block>();
            StringBuilder sample = new StringBuilder();
            for (var text : task.getResult().getTextBlocks()) {
                Rect box = text.getBoundingBox();
                String content = text.getText().replaceAll("\\s+", " ").trim();
                if (box == null || content.length() < 2 || !content.matches("(?s).*\\p{L}.*")) continue;
                if (sample.length() < 1500) sample.append(content).append(' ');
                blocks.add(new Block(box, content));
            }
            if (blocks.isEmpty()) { result.accept(empty(null)); return; }
            language.identifyLanguage(sample.toString()).addOnCompleteListener(identified -> {
                if (closed) { result.accept(empty(null)); return; }
                String tag = identified.isSuccessful() ? identified.getResult() : "und";
                String source = TranslateLanguage.fromLanguageTag("und".equals(tag) ? "en" : tag);
                if (source == null) { result.accept(empty("Bahasa layar belum didukung.")); return; }
                if (TranslateLanguage.INDONESIAN.equals(source)) { result.accept(empty(null)); return; }
                Translator client = clients.computeIfAbsent(source, this::client);
                Task<Void> model = models.get(source);
                if (model == null) {
                    model = client.downloadModelIfNeeded();
                    models.put(source, model);
                }
                if (!model.isComplete()) status.accept("Menyiapkan model terjemah. Unduhan awal butuh internet.");
                model.addOnCompleteListener(ready -> {
                    if (closed) { result.accept(empty(null)); return; }
                    if (!ready.isSuccessful()) {
                        models.remove(source);
                        result.accept(empty("Model terjemah belum tersedia. Periksa internet."));
                        return;
                    }
                    translateBlocks(blocks, client, source, width, height, result);
                });
            });
        });
    }

    private Translator client(String source) {
        return Translation.getClient(new TranslatorOptions.Builder()
            .setSourceLanguage(source).setTargetLanguage(TranslateLanguage.INDONESIAN).build());
    }

    private void translateBlocks(List<Block> blocks, Translator client, String language, int width, int height, Consumer<Result> result) {
        var translations = new LinkedHashMap<Pair, Task<String>>();
        for (Block block : blocks) {
            var pair = new Pair(language, block.text());
            if (!translations.containsKey(pair)) {
                String cached = cache.get(pair);
                translations.put(pair, cached == null ? client.translate(pair.text()) : Tasks.forResult(cached));
            }
        }
        Tasks.whenAllComplete(translations.values()).addOnCompleteListener(done -> {
            if (closed) { result.accept(empty(null)); return; }
            var labels = new ArrayList<TranslationOverlay.Label>();
            int passed = 0;
            for (Block block : blocks) {
                var key = new Pair(language, block.text());
                Task<String> task = translations.get(key);
                if (!task.isSuccessful()) continue;
                passed++;
                String text = task.getResult().trim();
                cache.put(key, text);
                if (text.isEmpty() || text.equalsIgnoreCase(block.text())) continue;
                Rect b = block.box();
                labels.add(new TranslationOverlay.Label(
                    Math.max(0, b.left * 1000 / width), Math.max(0, b.top * 1000 / height),
                    Math.max(1, b.width() * 1000 / width), Math.max(1, b.height() * 1000 / height), text));
            }
            while (cache.size() > 350) cache.remove(cache.keySet().iterator().next());
            result.accept(new Result(labels, passed == 0 ? "Terjemahan gagal diproses." : null));
        });
    }

    private static Result empty(String error) { return new Result(java.util.Collections.emptyList(), error); }

    void close() {
        closed = true;
        ocr.close();
        language.close();
        for (Translator client : clients.values()) client.close();
        clients.clear();
        models.clear();
        cache.clear();
    }
}

