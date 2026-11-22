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

    record Summary(int total, int success, long medianMillis, long p95Millis, long input, long output) {}

    static List<Entry> filtered(List<Entry> history, int outcome, String model) {
        String term = model.trim().toLowerCase(java.util.Locale.ROOT);
        var result = new ArrayList<Entry>();
        for (Entry entry : history) {
            if ((outcome == 1 && entry.error() != null) || (outcome == 2 && entry.error() == null)) continue;
            if (entry.model().toLowerCase(java.util.Locale.ROOT).contains(term)) result.add(entry);
        }
        return result;
    }

    static Summary summarize(List<Entry> entries) {
        var durations = new ArrayList<Long>();
        long input = 0, output = 0;
        for (Entry entry : entries) {
            if (entry.error() == null) durations.add(entry.millis());
            input += entry.input();
            output += entry.output();
        }
        durations.sort(Long::compare);
        long median = durations.isEmpty() ? 0 : durations.get((durations.size() - 1) / 2);
        long p95 = durations.isEmpty() ? 0 : durations.get(Math.max(0, (int) Math.ceil(durations.size() * 0.95) - 1));
        return new Summary(entries.size(), durations.size(), median, p95, input, output);
    }

    static String report(List<Entry> history) {
        Summary summary = summarize(history);
        var text = new StringBuilder("Laporan koneksi Omni Float\n");
        text.append("Berhasil: ").append(summary.success()).append(" dari ").append(summary.total());
        text.append("\nMedian: ").append(summary.medianMillis()).append(" ms, p95: ").append(summary.p95Millis()).append(" ms");
        text.append("\nEstimasi token teks: ").append(summary.input()).append(" masuk / ").append(summary.output()).append(" keluar\n");
        var stamp = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.ROOT);
        for (Entry entry : history) {
            text.append("\n").append(stamp.format(new java.util.Date(entry.time()))).append(" · ").append(entry.model())
                .append(" · ").append(entry.millis()).append(" ms · ").append(entry.error() == null ? "Berhasil" : entry.error());
        }
        return text.toString();
    }

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
