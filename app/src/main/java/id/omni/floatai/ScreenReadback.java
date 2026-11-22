package id.omni.floatai;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class ScreenReadback {
    private static List<TranslationOverlay.Label> last = java.util.Collections.emptyList();

    static void remember(List<TranslationOverlay.Label> labels) {
        var ordered = new ArrayList<>(labels);
        ordered.sort(Comparator.comparingInt(TranslationOverlay.Label::y).thenComparingInt(TranslationOverlay.Label::x));
        last = ordered;
    }

    static List<TranslationOverlay.Label> latest() { return new ArrayList<>(last); }

    static String text(List<TranslationOverlay.Label> labels, boolean bilingual) {
        var result = new StringBuilder();
        for (var label : labels) {
            if (result.length() > 0) result.append(bilingual ? "\n\n" : "\n");
            if (bilingual && !label.original().isEmpty()) result.append(label.original()).append("\n");
            result.append(label.value());
        }
        return result.toString();
    }

    static void clear() { last = java.util.Collections.emptyList(); }
    private ScreenReadback() {}
}
