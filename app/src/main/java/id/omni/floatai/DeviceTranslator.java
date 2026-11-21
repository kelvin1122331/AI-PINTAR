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
    record TextResult(String source, String original, String text, String error) {}
    private record Block(Rect box, String text) {}
    private record LanguagePair(String source, String target) {}
    private record Phrase(LanguagePair languages, String text) {}

    private final com.google.mlkit.vision.text.TextRecognizer ocr =
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
    private final com.google.mlkit.nl.languageid.LanguageIdentifier language = LanguageIdentification.getClient();
    private final HashMap<LanguagePair, Translator> clients = new HashMap<>();
    private final HashMap<LanguagePair, Task<Void>> models = new HashMap<>();
    private final LinkedHashMap<Phrase, String> cache = new LinkedHashMap<>(128, 0.75f, true);
    private boolean closed;

    void translate(Bitmap frame, Consumer<String> status, Consumer<Result> result) {
        translate(frame, "auto", "id", status, result);
    }

    void translate(Bitmap frame, String source, String target, Consumer<String> status, Consumer<Result> result) {
        int width = frame.getWidth();
        int height = frame.getHeight();
        recognize(frame, blocks -> {
            if (closed || blocks.isEmpty()) { result.accept(empty(null)); return; }
            StringBuilder sample = new StringBuilder();
            for (Block block : blocks) {
                if (sample.length() >= 1500) break;
                sample.append(block.text()).append(' ');
            }
            resolve(sample.toString(), source, target, pair -> {
                if (closed) { result.accept(empty(null)); return; }
                if (pair == null) { result.accept(empty("Bahasa layar belum didukung.")); return; }
                if (pair.source().equals(pair.target())) { result.accept(empty(null)); return; }
                prepare(pair, status, client -> {
                    if (client == null) { result.accept(empty("Model terjemah belum tersedia. Periksa internet.")); return; }
                    translateBlocks(blocks, client, pair, width, height, result);
                });
            });
        }, error -> result.accept(empty(error)));
    }

    void translatePhoto(Bitmap frame, String source, String target, Consumer<String> status, Consumer<TextResult> result) {
        recognize(frame, blocks -> {
            var lines = new ArrayList<String>();
            for (Block block : blocks) lines.add(block.text());
            if (lines.isEmpty()) { result.accept(new TextResult(source, "", "", "Tidak ada teks yang terbaca di foto.")); return; }
            translateText(String.join("\n", lines), source, target, status, result);
        }, error -> result.accept(new TextResult(source, "", "", error)));
    }

    void translateText(String text, String source, String target, Consumer<String> status, Consumer<TextResult> result) {
        resolve(text.substring(0, Math.min(1800, text.length())), source, target, pair -> {
            if (closed) return;
            if (pair == null) { result.accept(new TextResult(source, text, "", "Bahasa sumber atau tujuan belum didukung.")); return; }
            if (pair.source().equals(pair.target())) { result.accept(new TextResult(pair.source(), text, text, null)); return; }
            String cached = cache.get(new Phrase(pair, text));
            if (cached != null) { result.accept(new TextResult(pair.source(), text, cached, null)); return; }
            prepare(pair, status, client -> {
                if (closed) return;
                if (client == null) {
                    result.accept(new TextResult(pair.source(), text, "", "Model terjemah belum tersedia. Periksa internet."));
                    return;
                }
                client.translate(text).addOnCompleteListener(task -> {
                    if (closed) return;
                    if (task.isSuccessful()) {
                        save(new Phrase(pair, text), task.getResult());
                        result.accept(new TextResult(pair.source(), text, task.getResult(), null));
                    } else {
                        result.accept(new TextResult(pair.source(), text, "", "Terjemahan gagal diproses."));
                    }
                });
            });
        });
    }

    private void recognize(Bitmap frame, Consumer<List<Block>> success, Consumer<String> failure) {
        if (closed) {
            frame.recycle();
            failure.accept(null);
            return;
        }
        try {
            ocr.process(InputImage.fromBitmap(frame, 0)).addOnCompleteListener(task -> {
                frame.recycle();
                if (closed) { failure.accept(null); return; }
                if (!task.isSuccessful()) { failure.accept("Teks gagal dibaca."); return; }
                var blocks = new ArrayList<Block>();
                for (var text : task.getResult().getTextBlocks()) {
                    Rect box = text.getBoundingBox();
                    String content = text.getText().replaceAll("\\s+", " ").trim();
                    if (box != null && content.length() >= 2 && content.matches("(?s).*\\p{L}.*")) {
                        blocks.add(new Block(box, content));
                    }
                }
                success.accept(blocks);
            });
        } catch (RuntimeException error) {
            frame.recycle();
            failure.accept("Gambar gagal dibaca.");
        }
    }

    private void resolve(String sample, String source, String target, Consumer<LanguagePair> result) {
        String to = TranslateLanguage.fromLanguageTag(target);
        if (to == null || closed) { result.accept(null); return; }
        if (!"auto".equals(source)) {
            result.accept(languages(source, to));
            return;
        }
        language.identifyLanguage(sample).addOnCompleteListener(task -> {
            String tag = task.isSuccessful() ? task.getResult() : "und";
            result.accept(languages("und".equals(tag) ? "en" : tag, to));
        });
    }

    private static LanguagePair languages(String tag, String to) {
        String from = TranslateLanguage.fromLanguageTag(tag);
        return from == null ? null : new LanguagePair(from, to);
    }

    private void prepare(LanguagePair pair, Consumer<String> status, Consumer<Translator> action) {
        Translator client = clients.computeIfAbsent(pair, key -> Translation.getClient(new TranslatorOptions.Builder()
            .setSourceLanguage(key.source()).setTargetLanguage(key.target()).build()));
        Task<Void> model = models.computeIfAbsent(pair, key -> client.downloadModelIfNeeded());
        if (!model.isComplete()) status.accept("Menyiapkan model bahasa. Unduhan pertama memerlukan internet.");
        model.addOnCompleteListener(ready -> {
            if (closed) { action.accept(null); return; }
            if (!ready.isSuccessful()) {
                models.remove(pair);
                action.accept(null);
            } else {
                action.accept(client);
            }
        });
    }

    private void translateBlocks(List<Block> blocks, Translator client, LanguagePair pair, int width, int height, Consumer<Result> result) {
        var requests = new LinkedHashMap<Phrase, Task<String>>();
        for (Block block : blocks) {
            var key = new Phrase(pair, block.text());
            if (requests.containsKey(key)) continue;
            String cached = cache.get(key);
            requests.put(key, cached == null ? client.translate(key.text()) : Tasks.forResult(cached));
        }
        Tasks.whenAllComplete(requests.values()).addOnCompleteListener(done -> {
            if (closed) { result.accept(empty(null)); return; }
            var labels = new ArrayList<TranslationOverlay.Label>();
            int translated = 0;
            for (Block block : blocks) {
                var key = new Phrase(pair, block.text());
                Task<String> task = requests.get(key);
                if (!task.isSuccessful()) continue;
                translated++;
                String text = task.getResult().trim();
                save(key, text);
                if (text.isEmpty() || text.equalsIgnoreCase(block.text())) continue;
                Rect box = block.box();
                labels.add(new TranslationOverlay.Label(
                    Math.max(0, box.left * 1000 / width), Math.max(0, box.top * 1000 / height),
                    Math.max(1, box.width() * 1000 / width), Math.max(1, box.height() * 1000 / height), text));
            }
            result.accept(new Result(labels, translated == 0 ? "Terjemahan gagal diproses." : null));
        });
    }

    private void save(Phrase key, String text) {
        cache.put(key, text);
        while (cache.size() > 350) cache.remove(cache.keySet().iterator().next());
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
