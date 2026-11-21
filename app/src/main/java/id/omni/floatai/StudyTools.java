package id.omni.floatai;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;

final class StudyTools {
    static final String[] MODES = {
        "Tutor Socratic", "Ringkas materi", "Flashcard", "Kuis latihan",
        "Jelaskan sederhana", "Peta konsep", "Contoh soal", "Rencana belajar",
        "Periksa jawaban", "Glosarium"
    };
    private static final String[] RULES = {
        "Ajukan satu petunjuk atau pertanyaan pemandu. Tunggu jawaban sebelum memberikan solusi lengkap.",
        "Buat ringkasan dan poin penting dalam maksimal 250 kata.",
        "Buat 8 kartu. Format setiap baris sebagai 'Depan | Belakang'.",
        "Buat 5 soal latihan pilihan ganda. Tempatkan kunci dan penjelasan di akhir.",
        "Jelaskan dengan gagasan inti, analogi, dan satu contoh sederhana.",
        "Buat peta konsep berbentuk hierarki teks. Jelaskan hubungan antar konsep.",
        "Buat 3 soal dari tingkat mudah sampai menantang, dengan pembahasan di akhir.",
        "Buat rencana belajar tujuh hari. Sertakan target dan latihan per hari.",
        "Periksa jawaban dan langkah pengguna. Tunjukkan bagian benar, kesalahan, dan cara memperbaikinya.",
        "Buat glosarium istilah penting dengan definisi singkat dan contoh."
    };

    static JSONArray request(int mode, String input, int style) throws Exception {
        String[] lengths = {"Singkat", "Seimbang", "Mendalam"};
        return new JSONArray()
            .put(new JSONObject().put("role", "system").put("content",
                "Kamu tutor belajar. Gunakan Bahasa Indonesia. Panjang " + lengths[style] + ". " + RULES[mode]))
            .put(new JSONObject().put("role", "user").put("content", input));
    }

    static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("study", Context.MODE_PRIVATE);
    }
}
