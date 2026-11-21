package id.omni.floatai;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

final class TranslationJournal {
    record Entry(String original, String translated, String source, String target) {}

    static List<Entry> read(Context context, boolean favorites) {
        var result = new ArrayList<Entry>();
        try {
            var array = new JSONArray(TranslationPreferences.of(context).getString(key(favorites), "[]"));
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item == null) continue;
                String source = item.optString("source");
                String target = item.optString("target");
                if (source.isEmpty() || TranslationPreferences.index(target) < 0) continue;
                result.add(new Entry(item.optString("original"), item.optString("translated"), source, target));
            }
        } catch (org.json.JSONException ignored) {}
        return result;
    }

    static void save(Context context, boolean favorites, Entry newEntry) {
        var all = read(context, favorites);
        all.remove(newEntry);
        all.add(0, newEntry);
        write(context, favorites, all);
    }

    static void remove(Context context, boolean favorites, Entry entry) {
        var all = read(context, favorites);
        all.remove(entry);
        write(context, favorites, all);
    }

    private static void write(Context context, boolean favorites, List<Entry> entries) {
        var array = new JSONArray();
        try {
            for (int i = 0; i < Math.min(entries.size(), 32); i++) {
                Entry entry = entries.get(i);
                array.put(new JSONObject().put("original", entry.original()).put("translated", entry.translated())
                    .put("source", entry.source()).put("target", entry.target()));
            }
            TranslationPreferences.of(context).edit().putString(key(favorites), array.toString()).apply();
        } catch (org.json.JSONException error) {
            throw new IllegalArgumentException("Catatan terjemahan tidak valid.", error);
        }
    }

    private static String key(boolean favorite) { return favorite ? "favorites" : "history"; }
    private TranslationJournal() {}
}
