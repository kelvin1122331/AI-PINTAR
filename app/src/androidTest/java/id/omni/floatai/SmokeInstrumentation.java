package id.omni.floatai;

import android.app.Activity;
import android.app.Instrumentation;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Bundle;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import javax.net.ssl.HttpsURLConnection;
import org.json.*;

public class SmokeInstrumentation extends Instrumentation {
    private static final List<String> requests = new ArrayList<>();
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    private static void require(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
        android.util.Log.i("OmniSmoke", "PASS " + label);
    }
    @Override public void onStart() {
        Bundle summary = new Bundle();
        try {
            checkLocalFeatures();
            URL.setURLStreamHandlerFactory(protocol -> !protocol.equals("https") ? null : new URLStreamHandler() {
                @Override protected URLConnection openConnection(URL url) throws IOException {
                    if (!url.getHost().equals("omni-test.invalid")) throw new IOException("Unmocked host");
                    return new MockConnection(url);
                }
            });
            var context = getTargetContext();
            var endpoint = "https://omni-test.invalid/v1/chat/completions";
            var profile = new Providers.Profile(endpoint, Providers.AUTO, "test-key");
            int custom = Providers.NAMES.length - 1;
            Providers.save(context, custom, profile);
            var loaded = Providers.load(context, custom);
            require(profile.equals(loaded) && Providers.active(context) == custom, "encrypted profile save and load");
            var raw = Providers.prefs(context).getString("key" + custom, "");
            require(!raw.isEmpty() && !raw.contains("test-key"), "API key encrypted at rest");
            require(Providers.models(loaded).equals(List.of("mock-flash", "mock-chat")), "model list with capability filtering");
            var messages = new JSONArray().put(new JSONObject().put("role","user").put("content","hello"));
            var auto = Providers.complete(loaded, messages, "");
            require(auto.model().equals("mock-chat") && auto.text().equals("mock response"), "automatic model fallback");
            require(requests.contains("mock-flash") && requests.contains("mock-chat"), "API request used selected model IDs");
            requests.clear();
            var manual = Providers.complete(new Providers.Profile(endpoint, "mock-chat", "test-key"), messages, "");
            require(manual.model().equals("mock-chat") && requests.equals(List.of("mock-chat")), "manual selection without fallback");
            ConnectionMonitor.clear();
            for (int mode = 0; mode < StudyTools.MODES.length; mode++) {
                var study = StudyTools.request(mode, "Fotosintesis", mode % 3);
                require(study.length() == 2 && study.getJSONObject(1).getString("content").equals("Fotosintesis"),
                    "study mode sends original topic: " + StudyTools.MODES[mode]);
                var reply = Providers.complete(loaded, study, "mock-chat");
                require(!reply.text().isEmpty(), "study mode receives a result");
            }
            var events = ConnectionMonitor.history();
            var summaryStats = ConnectionMonitor.summarize(events);
            require(summaryStats.success() == 10 && summaryStats.total() == 10 && summaryStats.input() > 0,
                "connection history rolls up request counts and token estimates");
            require(ConnectionMonitor.report(events).contains("Laporan koneksi") &&
                !ConnectionMonitor.report(events).contains("test-key"), "connection export omits credentials");
            require(events.size() == 10 && events.get(0).input() > 0 && events.get(0).output() > 0,
                "monitor measures study requests and estimates text tokens");
            try {
                Providers.complete(new Providers.Profile(endpoint, "mock-flash", "test-key"), messages, "");
                throw new AssertionError("Expected model error");
            } catch (IllegalStateException expected) {
                var errors = ConnectionMonitor.history();
                require(errors.get(errors.size() - 1).error() != null, "monitor records provider failures");
            }
            summary.putString("stream", "OMNI_SMOKE_PASS\n");
            finish(Activity.RESULT_OK, summary);
        } catch (Throwable failure) {
            summary.putString("stream", android.util.Log.getStackTraceString(failure));
            finish(Activity.RESULT_CANCELED, summary);
        }
    }

    private void checkLocalFeatures() throws Exception {
        var context = getTargetContext();
        long now = System.currentTimeMillis();
        int added = ReviewDeck.add(context, "| Depan | Belakang |\n| --- | --- |\n1. Awan | Uap air\n2. Sungai | Aliran air", now);
        require(added == 2 && ReviewDeck.due(context, now).size() == 2, "flashcard parse and initial review date");
        require(ReviewDeck.add(context, "Awan | Uap air", now) == 0, "flashcards are deduplicated");
        var cards = ReviewDeck.due(context, now);
        ReviewDeck.grade(context, cards.get(0), true, now);
        ReviewDeck.grade(context, cards.get(1), false, now);
        require(ReviewDeck.due(context, now + 599_999).isEmpty(), "graded cards are not immediately repeated");
        require(ReviewDeck.due(context, now + 600_000).size() == 1, "forgotten card returns after ten minutes");
        require(ReviewDeck.due(context, now + ReviewDeck.DAY).size() == 2, "known card returns on the next day");

        var item = new TranslationJournal.Entry("Good morning", "Selamat pagi", "en", "id");
        TranslationJournal.save(context, false, item);
        TranslationJournal.save(context, true, item);
        TranslationJournal.save(context, true, item);
        require(TranslationJournal.read(context, true).size() == 1, "favorites persist without duplicates");
        TranslationJournal.remove(context, true, item);
        require(TranslationJournal.read(context, true).isEmpty() && TranslationJournal.read(context, false).size() == 1,
            "history and favorites are independent");

        File fixture = new File(context.getCacheDir(), "smoke.txt");
        var uri = android.net.Uri.fromFile(fixture);
        LocalDocuments.writeText(context.getContentResolver(), uri, "Catatan belajarku");
        require(LocalDocuments.readText(context.getContentResolver(), uri).equals("Catatan belajarku"), "TXT export/import roundtrip");
        android.graphics.Bitmap base = Bitmap.createBitmap(800, 400, Bitmap.Config.ARGB_8888);
        var draw = new Canvas(base);
        draw.drawColor(0xffffffff);
        var paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        paint.setTextSize(48);
        paint.setColor(0xff000000);
        draw.drawText("Good morning and welcome.", 25, 170, paint);
        File photo = new File(context.getCacheDir(), "smoke.png");
        try (var out = new FileOutputStream(photo)) { base.compress(Bitmap.CompressFormat.PNG, 100, out); }
        base.recycle();
        var flight = new java.util.concurrent.ArrayBlockingQueue<DeviceTranslator.TextResult>(1);
        DeviceTranslator[] translator = new DeviceTranslator[1];
        Bitmap decoded = LocalDocuments.readImage(context.getContentResolver(), android.net.Uri.fromFile(photo));
        runOnMainSync(() -> {
            translator[0] = new DeviceTranslator();
            translator[0].translatePhoto(decoded, "en", "id", message -> {}, flight::offer);
        });
        var translated = flight.poll(100, java.util.concurrent.TimeUnit.SECONDS);
        require(translated != null && translated.error() == null && translated.original().contains("Good morning")
            && translated.text().toLowerCase(java.util.Locale.ROOT).contains("selamat pagi"), "photo picker decoder, OCR, and Indonesian translation");
        flight.clear();
        runOnMainSync(() -> translator[0].translateText("Selamat pagi", "id", "en", message -> {}, flight::offer));
        var reverse = flight.poll(100, java.util.concurrent.TimeUnit.SECONDS);
        require(reverse != null && reverse.error() == null
            && reverse.text().toLowerCase(java.util.Locale.ROOT).contains("good morning"), "manual translation with reversed languages");
        runOnMainSync(() -> translator[0].close());

        int[] scene = new int[3840];
        java.util.Arrays.fill(scene, 0xfffafafa);
        int[] raw = java.util.Arrays.copyOf(scene, scene.length);
        int paintColor = 0xff25143c;
        int composite = 0xff65596f;
        for (int i = 800; i < 1300; i++) raw[i] = composite;
        require(ScreenScene.changed(scene, raw), "raw capture would detect visible overlay as a changed screen");
        for (int i = 800; i < 1300; i++) raw[i] = ScreenScene.restore(raw[i], paintColor, 0.70f);
        require(!ScreenScene.changed(scene, raw), "overlay compositing is removed from the scene fingerprint");
        for (int i = 2450; i < 2590; i++) raw[i] = 0xff242424;
        require(ScreenScene.changed(scene, raw), "genuine scroll changes still trigger translation");
        var drawFailure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        runOnMainSync(() -> {
            try {
                var layer = new TranslationOverlay(context, java.util.Collections.singletonList(
                    new TranslationOverlay.Label(250, 420, 500, 90, "Selamat pagi dan selamat datang")));
                layer.layout(0, 0, 800, 400);
                var frame = Bitmap.createBitmap(800, 400, Bitmap.Config.ARGB_8888);
                var canvas = new Canvas(frame);
                canvas.drawColor(0xffffffff);
                int save = canvas.saveLayerAlpha(0, 0, 800, 400, 179);
                layer.draw(canvas);
                canvas.restoreToCount(save);
                int visible = frame.getPixel(209, 175);
                require(visible != 0xffffffff, "translation draws actual overlay pixels");
                layer.restoreBackground(frame, 800, 400, .70f);
                int restored = frame.getPixel(209, 175);
                frame.recycle();
                require(((restored >> 16) & 255) >= 245 && ((restored >> 8) & 255) >= 245,
                    "translation overlay pixels are restored for the next OCR frame");
            } catch (Throwable error) {
                drawFailure.set(error);
            }
        });
        if (drawFailure.get() != null) throw new AssertionError("Offscreen overlay test", drawFailure.get());
    }

    private static final class MockConnection extends HttpsURLConnection {
        private final ByteArrayOutputStream body = new ByteArrayOutputStream();
        private String model;
        MockConnection(URL url) { super(url); }
        @Override public OutputStream getOutputStream() { return body; }
        @Override public int getResponseCode() throws IOException {
            if (!"Bearer test-key".equals(getRequestProperty("Authorization"))) return 401;
            if (url.getPath().equals("/v1/models") && method.equals("GET")) return 200;
            if (!url.getPath().equals("/v1/chat/completions") || !method.equals("POST")) return 400;
            try { model = new JSONObject(body.toString("UTF-8")).getString("model"); }
            catch (JSONException e) { throw new IOException(e); }
            requests.add(model);
            return "mock-flash".equals(model) ? 404 : 200;
        }
        private InputStream stream(String value) { return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8)); }
        @Override public InputStream getInputStream() {
            String q = String.valueOf((char)34);
            if (model == null) {
                return stream(("{'data':[{'id':'mock-flash'},{'id':'mock-chat'},{'id':'mock-embedding'},{'id':'mock-disabled','active':false}]}").replace("'", q));
            }
            return stream(("{'choices':[{'message':{'content':'mock response'}}]}").replace("'", q));
        }
        @Override public InputStream getErrorStream() { return stream("model_not_found"); }
        @Override public String getCipherSuite() { return "MOCK"; }
        @Override public java.security.cert.Certificate[] getLocalCertificates() { return null; }
        @Override public java.security.cert.Certificate[] getServerCertificates() { return null; }
        @Override public void disconnect() {}
        @Override public void connect() {}
        @Override public boolean usingProxy() { return false; }
    }
}
