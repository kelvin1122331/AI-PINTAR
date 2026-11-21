package id.omni.floatai;

import android.os.SystemClock;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

final class ConnectionMonitor {
    record Entry(String model, long millis, long time, String error, int input, int output) {}
    private static final ArrayDeque<Entry> recent = new ArrayDeque<>();

    static synchronized void record(String model, long start, String error, int input, int output) {
        if (recent.size() == 30) recent.removeFirst();
        recent.addLast(new Entry(model, SystemClock.elapsedRealtime() - start, System.currentTimeMillis(), error, input, output));
    }

    static synchronized List<Entry> history() { return new ArrayList<>(recent); }
    static synchronized void clear() { recent.clear(); }

    static int estimate(String value) {
        return value.isEmpty() ? 0 : Math.max(1, (value.codePointCount(0, value.length()) + 3) / 4);
    }

    static int estimateInput(JSONArray messages) {
        int total = 0;
        for (int i = 0; i < messages.length(); i++) {
            JSONObject row = messages.optJSONObject(i);
            if (row == null) continue;
            Object content = row.opt("content");
            if (content instanceof String text) total += estimate(text);
            if (content instanceof JSONArray parts) {
                for (int j = 0; j < parts.length(); j++) {
                    JSONObject part = parts.optJSONObject(j);
                    if (part != null) total += estimate(part.optString("text"));
                }
            }
        }
        return total;
    }

    static String describe(Exception error) {
        if (error instanceof java.net.SocketTimeoutException) return "Provider kehabisan waktu.";
        if (error instanceof java.net.UnknownHostException) return "Alamat server tidak ditemukan.";
        if (error instanceof javax.net.ssl.SSLException) return "Koneksi HTTPS gagal.";
        if (error instanceof InterruptedException) return "Permintaan dihentikan.";
        if (error instanceof IllegalStateException && error.getMessage() != null) return error.getMessage();
        return "Koneksi atau format respons bermasalah.";
    }
}
