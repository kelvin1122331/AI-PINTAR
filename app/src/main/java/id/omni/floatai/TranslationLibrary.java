package id.omni.floatai;

import android.content.Context;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

final class TranslationLibrary {
    private static final String PAIRS = "saved_language_pairs";
    private static final String GLOSSARY = "personal_glossary";
    static final int MAX_TERM = 180;
    record Pair(String source, String target) {
        String name() { return TranslationPreferences.name(source) + " → " + TranslationPreferences.name(target); }
    }

    static List<Pair> pairs(Context context) {
        var pairs = new ArrayList<Pair>();
        JSONArray stored = read(context, PAIRS);
        for (int i = 0; i < stored.length(); i++) {
            JSONObject row = stored.optJSONObject(i);
            if (row == null) continue;
            String source = row.optString("source");
            String target = row.optString("target");
            if (validSource(source) && TranslationPreferences.index(target) >= 0) pairs.add(new Pair(source, target));
        }
        return pairs;
    }

    static void savePair(Context context, Pair pair, boolean remove) {
        if (!validSource(pair.source()) || TranslationPreferences.index(pair.target()) < 0) return;
        var pairs = pairs(context);
        pairs.remove(pair);
        if (!remove) pairs.add(0, pair);
        var stored = new JSONArray();
        try {
            for (Pair item : pairs.subList(0, Math.min(12, pairs.size()))) {
                stored.put(new JSONObject().put("source", item.source()).put("target", item.target()));
            }
        } catch (JSONException impossible) { throw new IllegalStateException(impossible); }
        write(context, PAIRS, stored);
    }

    static List<TranslationJournal.Entry> glossary(Context context) {
        var entries = new ArrayList<TranslationJournal.Entry>();
        JSONArray stored = read(context, GLOSSARY);
        for (int i = 0; i < stored.length(); i++) {
            JSONObject row = stored.optJSONObject(i);
            if (row == null) continue;
            var entry = new TranslationJournal.Entry(row.optString("original"), row.optString("translated"),
                row.optString("source"), row.optString("target"));
            if (valid(entry)) entries.add(entry);
        }
        return entries;
    }

    static boolean saveTerm(Context context, TranslationJournal.Entry entry, boolean remove) {
        if (!valid(entry)) return false;
        var entries = glossary(context);
        entries.removeIf(item -> item.source().equals(entry.source()) && item.target().equals(entry.target())
            && termKey(item.original()).equals(termKey(entry.original())));
        if (!remove) entries.add(0, entry);
        var stored = new JSONArray();
        try {
            for (var item : entries.subList(0, Math.min(100, entries.size()))) {
                stored.put(new JSONObject().put("original", item.original()).put("translated", item.translated())
                    .put("source", item.source()).put("target", item.target()));
            }
        } catch (JSONException impossible) { throw new IllegalStateException(impossible); }
        write(context, GLOSSARY, stored);
        return true;
    }

    static Map<String, String> overrides(Context context, String source, String target) {
        if (context == null) return Collections.emptyMap();
        var result = new LinkedHashMap<String, String>();
        for (var item : glossary(context)) {
            if (item.source().equals(source) && item.target().equals(target))
                result.put(termKey(item.original()), item.translated());
        }
        return result;
    }

    static String termKey(String text) { return text.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT); }

    static List<TranslationJournal.Entry> filter(List<TranslationJournal.Entry> entries, String query) {
        String needle = termKey(query);
        var matches = new ArrayList<TranslationJournal.Entry>();
        for (var item : entries) {
            if (termKey(item.original()).contains(needle) || termKey(item.translated()).contains(needle)) matches.add(item);
        }
        return matches;
    }

    static String bilingual(TranslationJournal.Entry entry) {
        return "Teks asli · " + TranslationPreferences.name(entry.source()) + "\n"
            + entry.original() + "\n\nTerjemahan · " + TranslationPreferences.name(entry.target()) + "\n"
            + entry.translated() + "\n";
    }

    private static boolean validSource(String code) { return "auto".equals(code) || TranslationPreferences.index(code) >= 0; }

    private static boolean valid(TranslationJournal.Entry item) {
        return TranslationPreferences.index(item.source()) >= 0 && TranslationPreferences.index(item.target()) >= 0
            && !item.source().equals(item.target()) && !item.original().trim().isEmpty() && !item.translated().trim().isEmpty()
            && item.original().length() <= MAX_TERM && item.translated().length() <= MAX_TERM;
    }

    private static JSONArray read(Context context, String key) {
        try { return new JSONArray(TranslationPreferences.of(context).getString(key, "[]")); }
        catch (JSONException ignored) { return new JSONArray(); }
    }

    private static void write(Context context, String key, JSONArray contents) {
        TranslationPreferences.of(context).edit().putString(key, contents.toString()).apply();
    }

    private TranslationLibrary() {}
}
