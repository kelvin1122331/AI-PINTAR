package id.omni.floatai;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

final class ReviewDeck {
    static final long DAY = 86_400_000L;
    record Card(String front, String back, long due, int days) {}

    static List<Card> read(Context context) {
        var cards = new ArrayList<Card>();
        try {
            var array = new JSONArray(StudyTools.prefs(context).getString("review_cards", "[]"));
            for (int i = 0; i < array.length(); i++) {
                var card = array.optJSONObject(i);
                if (card == null || card.optString("front").isEmpty() || card.optString("back").isEmpty()) continue;
                cards.add(new Card(card.optString("front"), card.optString("back"), card.optLong("due"), card.optInt("days")));
            }
        } catch (org.json.JSONException ignored) {}
        return cards;
    }

    static List<Card> due(Context context, long now) {
        var ready = read(context);
        ready.removeIf(card -> card.due() > now);
        ready.sort(Comparator.comparingLong(Card::due));
        return ready;
    }

    static int add(Context context, String response, long now) {
        var all = new LinkedHashMap<String, Card>();
        for (Card card : read(context)) all.put(key(card.front()), card);
        int count = 0;
        for (String text : response.split("\\r?\\n")) {
            String line = text.trim().replaceFirst("^(?:[0-9]+[.)]|[-*])\\s+", "");
            if (line.startsWith("|")) line = line.substring(1);
            if (line.endsWith("|")) line = line.substring(0, line.length() - 1);
            int divider = line.indexOf('|');
            if (divider < 1) continue;
            String front = tidy(line.substring(0, divider));
            String back = tidy(line.substring(divider + 1));
            if (front.isEmpty() || back.isEmpty() || front.length() > 600 || back.length() > 2000
                    || front.equalsIgnoreCase("Depan") || front.matches("[-:]+") || back.matches("[-:]+")
                    || back.indexOf('|') >= 0 || all.containsKey(key(front))) continue;
            if (all.size() >= 120) break;
            all.put(key(front), new Card(front, back, now, 0));
            count++;
        }
        write(context, new ArrayList<>(all.values()));
        return count;
    }

    record Stats(int total, int due, int longerInterval, long correct, long repeat) {}

    static Stats stats(Context context, long now) {
        var cards = read(context);
        int due = 0, longerInterval = 0;
        for (var card : cards) {
            if (card.due() <= now) due++;
            if (card.days() >= 7) longerInterval++;
        }
        var preferences = StudyTools.prefs(context);
        return new Stats(cards.size(), due, longerInterval, preferences.getLong("cards_correct", 0),
            preferences.getLong("cards_repeat", 0));
    }

    static String exportTsv(Context context) {
        var output = new StringBuilder("Depan\tBelakang\n");
        for (var card : read(context)) output.append(escape(card.front())).append('\t').append(escape(card.back())).append('\n');
        return output.toString();
    }

    static int importTsv(Context context, String document, long now) {
        var existing = new LinkedHashMap<String, Card>();
        for (var card : read(context)) existing.put(key(card.front()), card);
        int size = existing.size();
        for (String line : document.split("\\r?\\n")) {
            int split = line.indexOf('\t');
            if (split < 1 || line.indexOf('\t', split + 1) >= 0) continue;
            String front = unescape(line.substring(0, split)).trim();
            String back = unescape(line.substring(split + 1)).trim();
            if (front.isEmpty() || back.isEmpty() || front.length() > 600 || back.length() > 2000
                || (front.equalsIgnoreCase("Depan") && back.equalsIgnoreCase("Belakang"))) continue;
            if (existing.size() == 120) break;
            existing.putIfAbsent(key(front), new Card(front, back, now, 0));
        }
        int added = existing.size() - size;
        if (added > 0) write(context, new ArrayList<>(existing.values()));
        return added;
    }

    private static String escape(String text) {
        return text.replace("\\", "\\\\").replace("\r\n", "\n").replace("\r", "\n")
            .replace("\n", "\\n").replace("\t", "\\t");
    }

    private static String unescape(String text) {
        var output = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '\\' && i + 1 < text.length()) {
                char next = text.charAt(i + 1);
                if (next == 'n' || next == 't' || next == '\\') {
                    output.append(next == 'n' ? '\n' : next == 't' ? '\t' : next);
                    i++;
                    continue;
                }
            }
            output.append(ch);
        }
        return output.toString();
    }

    static void grade(Context context, Card shown, boolean remembered, long now) {
        var all = read(context);
        for (int i = 0; i < all.size(); i++) {
            Card card = all.get(i);
            if (!key(card.front()).equals(key(shown.front()))) continue;
            int days = remembered ? Math.max(1, Math.min(30, card.days() * 2)) : 0;
            long due = now + (remembered ? days * DAY : 600_000L);
            all.set(i, new Card(card.front(), card.back(), due, days));
            var prefs = StudyTools.prefs(context);
            String stat = remembered ? "cards_correct" : "cards_repeat";
            write(prefs.edit().putLong(stat, prefs.getLong(stat, 0) + 1), all);
            return;
        }
    }

    private static String tidy(String value) { return value.replace("**", "").trim(); }
    private static String key(String front) { return front.trim().toLowerCase(Locale.ROOT); }

    private static void write(Context context, List<Card> cards) {
        write(StudyTools.prefs(context).edit(), cards);
    }

    private static void write(android.content.SharedPreferences.Editor edit, List<Card> cards) {
        var out = new JSONArray();
        try {
            for (Card card : cards) out.put(new JSONObject().put("front", card.front()).put("back", card.back())
                .put("due", card.due()).put("days", card.days()));
            edit.putString("review_cards", out.toString()).apply();
        } catch (org.json.JSONException error) {
            throw new IllegalArgumentException("Kartu tidak valid.", error);
        }
    }

    private ReviewDeck() {}
}
