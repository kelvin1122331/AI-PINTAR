package id.omni.floatai;

import android.content.Context;
import android.content.SharedPreferences;

final class TranslationPreferences {
    static final String[] CODES = {"id", "en", "es", "fr", "de", "pt", "it", "nl", "vi", "tr", "ja", "ko", "zh", "ar"};
    static final String[] NAMES = {"Indonesia", "Inggris", "Spanyol", "Prancis", "Jerman", "Portugis", "Italia",
        "Belanda", "Vietnam", "Turki", "Jepang", "Korea", "Mandarin", "Arab"};

    static SharedPreferences of(Context context) {
        return context.getSharedPreferences("translation_options", Context.MODE_PRIVATE);
    }
    static String source(Context context) { return of(context).getString("source", "auto"); }
    static String target(Context context) { return of(context).getString("target", "id"); }

    static int index(String code) {
        for (int i = 0; i < CODES.length; i++) if (CODES[i].equals(code)) return i;
        return -1;
    }
    static String name(String code) {
        int index = index(code);
        return index < 0 ? "Otomatis" : NAMES[index];
    }

    private TranslationPreferences() {}
}
