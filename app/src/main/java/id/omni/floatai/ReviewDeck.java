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

    static void grade(Context context, Card shown, boolean remembered, long now) {
        var all = read(context);
        for (int i = 0; i < all.size(); i++) {
            Card card = all.get(i);
            if (!key(card.front()).equals(key(shown.front()))) continue;
            int days = remembered ? Math.max(1, Math.min(30, card.days() * 2)) : 0;
            long due = now + (remembered ? days * DAY : 600_000L);
            all.set(i, new Card(card.front(), card.back(), due, days));
            break;
        }
        write(context, all);
    }

    private static String tidy(String value) { return value.replace("**", "").trim(); }
    private static String key(String front) { return front.trim().toLowerCase(Locale.ROOT); }

    private static void write(Context context, List<Card> cards) {
        var out = new JSONArray();
        try {
            for (Card card : cards) out.put(new JSONObject().put("front", card.front()).put("back", card.back())
                .put("due", card.due()).put("days", card.days()));
            StudyTools.prefs(context).edit().putString("review_cards", out.toString()).apply();
        } catch (org.json.JSONException error) {
            throw new IllegalArgumentException("Kartu tidak valid.", error);
        }
    }

    private ReviewDeck() {}
}
