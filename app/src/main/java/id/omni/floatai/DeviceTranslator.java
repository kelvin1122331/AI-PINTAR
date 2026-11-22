package id.omni.floatai;

import android.content.Context;
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
import java.util.Map;
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
    private final Context context;
    private boolean closed;

    DeviceTranslator() { this(null); }
    DeviceTranslator(Context context) { this.context = context == null ? null : context.getApplicationContext(); }

    void translate(Bitmap frame, Consumer<String> status, Consumer<Result> result) {
        translate(frame, "auto", "id", false, status, result);
    }

    void translate(Bitmap frame, String source, String target, Consumer<String> status, Consumer<Result> result) {
        translate(frame, source, target, false, status, result);
    }

    void translate(Bitmap frame, String source, String target, boolean perLine, Consumer<String> status, Consumer<Result> result) {
        int width = frame.getWidth();
        int height = frame.getHeight();
        recognize(frame, perLine, blocks -> {
            if (closed || blocks.isEmpty()) { result.accept(empty(null)); return; }
            StringBuilder sample = new StringBuilder();
            for (Block block : blocks) {
                if (sample.length() >= 1500) break;
                sample.append(block.text()).append(' ');
            }
            resolve(sample.toString(), source, target, pair -> {
                if (closed) { result.accept(empty(null)); return; }
                if (pair == null) { result.accept(empty("Bahasa layar belum didukung.")); return; }
                if (!pair.source().equals(pair.target())) {
                    translateGroup(blocks, pair, width, height, status, result);
                } else if ("auto".equals(source) && blocks.size() > 1) {
                    translateMixed(blocks, pair.target(), width, height, status, result);
                } else {
                    result.accept(empty(null));
                }
            });
        }, error -> result.accept(empty(error)));
    }

    void translatePhoto(Bitmap frame, String source, String target, Consumer<String> status, Consumer<TextResult> result) {
        recognize(frame, false, blocks -> {
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
            var key = new Phrase(pair, text);
            String local = TranslationLibrary.overrides(context, pair.source(), pair.target()).get(TranslationLibrary.termKey(text));
            if (local == null) local = cache.get(key);
            if (local != null) { result.accept(new TextResult(pair.source(), text, local, null)); return; }
            prepare(pair, status, client -> {
                if (closed) return;
                if (client == null) {
                    result.accept(new TextResult(pair.source(), text, "", "Model terjemah belum tersedia. Periksa internet."));
                    return;
                }
                client.translate(text).addOnCompleteListener(task -> {
                    if (closed) return;
                    if (task.isSuccessful() && task.getResult() != null) {
                        save(key, task.getResult());
                        result.accept(new TextResult(pair.source(), text, task.getResult(), null));
                    } else {
                        result.accept(new TextResult(pair.source(), text, "", "Terjemahan gagal diproses."));
                    }
                });
            });
        });
    }

    private void recognize(Bitmap frame, boolean perLine, Consumer<List<Block>> success, Consumer<String> failure) {
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
                for (var block : task.getResult().getTextBlocks()) {
                    if (perLine) {
                        for (var line : block.getLines()) addBlock(blocks, line.getBoundingBox(), line.getText());
                    } else {
                        addBlock(blocks, block.getBoundingBox(), block.getText());
                    }
                    if (blocks.size() >= 70) break;
                }
                success.accept(blocks);
            });
        } catch (RuntimeException error) {
            frame.recycle();
            failure.accept("Gambar gagal dibaca.");
        }
    }

    private static void addBlock(List<Block> output, Rect box, String text) {
        if (box == null || output.size() >= 70) return;
        String compact = text.replaceAll("\\s+", " ").trim();
        if (compact.length() >= 2 && compact.matches("(?s).*\\p{L}.*")) output.add(new Block(box, compact));
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

    private void translateMixed(List<Block> blocks, String target, int width, int height,
                                Consumer<String> status, Consumer<Result> result) {
        var identified = new LinkedHashMap<String, Task<String>>();
        for (var block : blocks) {
            if (block.text().length() >= 5) identified.computeIfAbsent(block.text(), language::identifyLanguage);
        }
        Tasks.whenAllComplete(identified.values()).addOnCompleteListener(done -> {
            if (closed) { result.accept(empty(null)); return; }
            var groups = new LinkedHashMap<LanguagePair, List<Block>>();
            for (var block : blocks) {
                var task = identified.get(block.text());
                if (task == null || !task.isSuccessful()) continue;
                String detected = TranslateLanguage.fromLanguageTag(task.getResult());
                if (detected == null || detected.equals(target)) continue;
                groups.computeIfAbsent(new LanguagePair(detected, target), key -> new ArrayList<>()).add(block);
            }
            if (groups.isEmpty()) { result.accept(empty(null)); return; }
            var combined = new ArrayList<TranslationOverlay.Label>();
            int[] remaining = {groups.size()};
            String[] error = {null};
            groups.forEach((pair, group) -> translateGroup(group, pair, width, height, status, partial -> {
                combined.addAll(partial.labels());
                if (partial.error() != null) error[0] = partial.error();
                if (--remaining[0] == 0) result.accept(new Result(combined, combined.isEmpty() ? error[0] : null));
            }));
        });
    }

    private void translateGroup(List<Block> blocks, LanguagePair pair, int width, int height,
                                Consumer<String> status, Consumer<Result> result) {
        Map<String, String> glossary = TranslationLibrary.overrides(context, pair.source(), pair.target());
        boolean needsModel = false;
        for (var block : blocks) {
            if (localText(glossary, new Phrase(pair, block.text())) == null) { needsModel = true; break; }
        }
        if (needsModel) {
            prepare(pair, status, client -> translateBlocks(blocks, client, pair, glossary, width, height, result));
        } else {
            translateBlocks(blocks, null, pair, glossary, width, height, result);
        }
    }

    private String localText(Map<String, String> glossary, Phrase key) {
        String text = glossary.get(TranslationLibrary.termKey(key.text()));
        return text != null ? text : cache.get(key);
    }

    private void translateBlocks(List<Block> blocks, Translator client, LanguagePair pair,
                                 Map<String, String> glossary, int width, int height, Consumer<Result> result) {
        if (closed) { result.accept(empty(null)); return; }
        var requests = new LinkedHashMap<Phrase, Task<String>>();
        for (Block block : blocks) {
            var key = new Phrase(pair, block.text());
            if (requests.containsKey(key)) continue;
            String local = localText(glossary, key);
            if (local != null) requests.put(key, Tasks.forResult(local));
            else if (client != null) requests.put(key, client.translate(key.text()));
        }
        Tasks.whenAllComplete(requests.values()).addOnCompleteListener(done -> {
            if (closed) { result.accept(empty(null)); return; }
            var labels = new ArrayList<TranslationOverlay.Label>();
            int translated = 0;
            for (Block block : blocks) {
                var key = new Phrase(pair, block.text());
                Task<String> task = requests.get(key);
                if (task == null || !task.isSuccessful() || task.getResult() == null) continue;
                translated++;
                String text = task.getResult().trim();
                if (!glossary.containsKey(TranslationLibrary.termKey(key.text()))) save(key, text);
                if (text.isEmpty() || text.equalsIgnoreCase(block.text())) continue;
                Rect box = block.box();
                labels.add(new TranslationOverlay.Label(
                    Math.max(0, box.left * 1000 / width), Math.max(0, box.top * 1000 / height),
                    Math.max(1, box.width() * 1000 / width), Math.max(1, box.height() * 1000 / height), text, block.text()));
            }
            String error = translated > 0 ? null : client == null
                ? "Model terjemah belum tersedia. Periksa internet." : "Terjemahan gagal diproses.";
            result.accept(new Result(labels, error));
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
