package id.omni.floatai;

import android.content.Context;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONException;

final class StudyNotes {
    record Note(String text, boolean pinned) {}
    private static final String NOTES = "notes";
    private static final String PINNED = "pinned_study_notes";

    static List<Note> find(Context context, String search) {
        var notes = load(context);
        String query = search.trim().toLowerCase(Locale.ROOT);
        notes.removeIf(note -> !note.text().toLowerCase(Locale.ROOT).contains(query));
        notes.sort(Comparator.comparing(Note::pinned).reversed());
        return notes;
    }

    static void save(Context context, String text) {
        var notes = load(context);
        boolean pinned = notes.stream().anyMatch(note -> note.pinned() && note.text().equals(text));
        notes.removeIf(note -> note.text().equals(text));
        notes.add(0, new Note(text, pinned));
        while (notes.size() > 30) {
            int oldest = notes.size() - 1;
            while (oldest > 0 && notes.get(oldest).pinned()) oldest--;
            notes.remove(oldest);
        }
        persist(context, notes);
    }

    static void pin(Context context, String text, boolean pinned) {
        var notes = load(context);
        for (int i = 0; i < notes.size(); i++) {
            Note note = notes.get(i);
            if (note.text().equals(text)) notes.set(i, new Note(text, pinned));
        }
        persist(context, notes);
    }

    private static List<Note> load(Context context) {
        var preferences = StudyTools.prefs(context);
        var pinned = preferences.getStringSet(PINNED, java.util.Collections.emptySet());
        var result = new ArrayList<Note>();
        try {
            var stored = new JSONArray(preferences.getString(NOTES, "[]"));
            for (int i = 0; i < stored.length(); i++) {
                String text = stored.optString(i, "");
                if (!text.isEmpty()) result.add(new Note(text, pinned.contains(id(text))));
            }
        } catch (JSONException ignored) {}
        return result;
    }

    private static void persist(Context context, List<Note> notes) {
        var stored = new JSONArray();
        var pinned = new HashSet<String>();
        for (var note : notes) {
            stored.put(note.text());
            if (note.pinned()) pinned.add(id(note.text()));
        }
        StudyTools.prefs(context).edit().putString(NOTES, stored.toString()).putStringSet(PINNED, pinned).apply();
    }

    private static String id(String text) {
        try {
            var sha = MessageDigest.getInstance("SHA-256");
            return Base64.encodeToString(sha.digest(text.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);
        } catch (NoSuchAlgorithmException unreachable) {
            throw new IllegalStateException(unreachable);
        }
    }

    private StudyNotes() {}
}
