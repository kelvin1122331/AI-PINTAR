package id.omni.floatai;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.net.ssl.HttpsURLConnection;

final class Providers {
    static final String[] NAMES = {"OpenAI", "Gemini", "Groq", "DeepSeek", "OpenRouter", "Custom"};
    static final String[] ENDPOINTS = {
        "https://api.openai.com/v1/chat/completions",
        "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions",
        "https://api.groq.com/openai/v1/chat/completions",
        "https://api.deepseek.com/chat/completions",
        "https://openrouter.ai/api/v1/chat/completions", ""
    };
    static final String AUTO = "@auto";
    record Profile(String endpoint, String model, String key) {
        boolean automatic() { return AUTO.equals(model); }
    }
    record Answer(String text, String model) {}
    private static final class ApiException extends IllegalStateException {
        final boolean modelUnavailable;
        ApiException(String message, boolean modelUnavailable) {
            super(message);
            this.modelUnavailable = modelUnavailable;
        }
    }
    private static final String ALIAS = "omni_float_api_keys";

    static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("providers", Context.MODE_PRIVATE);
    }

    static int active(Context context) { return prefs(context).getInt("active", 0); }

    private static SecretKey secret() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (!store.containsAlias(ALIAS)) {
            KeyGenerator generator = KeyGenerator.getInstance("AES", "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
            return generator.generateKey();
        }
        return (SecretKey) store.getKey(ALIAS, null);
    }

    static Profile load(Context context, int index) throws Exception {
        SharedPreferences p = prefs(context);
        String value = p.getString("key" + index, "");
        String key = "";
        if (!value.isEmpty()) {
            byte[] bytes = Base64.decode(value, Base64.NO_WRAP);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, secret(), new GCMParameterSpec(128, Arrays.copyOf(bytes, 12)));
            key = new String(cipher.doFinal(bytes, 12, bytes.length - 12), StandardCharsets.UTF_8);
        }
        return new Profile(p.getString("url" + index, ENDPOINTS[index]), p.getString("model" + index, ""), key);
    }

    static URL validateEndpoint(String endpoint) throws Exception {
        URL url = new URL(endpoint);
        if (!url.getProtocol().equals("https") || url.getHost().isEmpty()
                || url.getUserInfo() != null || url.getRef() != null || url.getQuery() != null) {
            throw new IllegalArgumentException("Masukkan URL endpoint HTTPS lengkap.");
        }
        return url;
    }

    static void save(Context context, int index, Profile profile) throws Exception {
        validateEndpoint(profile.endpoint());
        if (profile.model().trim().isEmpty()) throw new IllegalArgumentException("Isi ID model.");
        if (index != NAMES.length - 1 && profile.key().trim().isEmpty()) {
            throw new IllegalArgumentException("Isi API key provider.");
        }
        String value = "";
        if (!profile.key().isEmpty()) {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, secret());
            byte[] encrypted = cipher.doFinal(profile.key().getBytes(StandardCharsets.UTF_8));
            byte[] bytes = Arrays.copyOf(cipher.getIV(), 12 + encrypted.length);
            System.arraycopy(encrypted, 0, bytes, 12, encrypted.length);
            value = Base64.encodeToString(bytes, Base64.NO_WRAP);
        }
        prefs(context).edit().putInt("active", index)
            .putString("url" + index, profile.endpoint()).putString("model" + index, profile.model())
            .putString("key" + index, value).apply();
    }

    private static URL modelsUrl(Profile profile) throws Exception {
        URL endpoint = validateEndpoint(profile.endpoint());
        String path = endpoint.getPath().replaceFirst("/+$", "");
        String suffix = "/chat/completions";
        if (!path.endsWith(suffix)) {
            throw new IllegalArgumentException("URL harus berakhiran /chat/completions agar daftar model bisa dimuat.");
        }
        return new URL(endpoint.getProtocol(), endpoint.getHost(), endpoint.getPort(),
            path.substring(0, path.length() - suffix.length()) + "/models");
    }

    static java.util.List<String> models(Profile profile) throws Exception {
        JSONObject payload = request(modelsUrl(profile), profile.key(), null);
        JSONArray data = payload.getJSONArray("data");
        var ids = new java.util.LinkedHashSet<String>();
        for (int i = 0; i < data.length(); i++) {
            JSONObject item = data.optJSONObject(i);
            if (item == null || !item.optBoolean("active", true)) continue;
            String id = item.optString("id").trim();
            JSONObject architecture = item.optJSONObject("architecture");
            if (!id.isEmpty() && !AUTO.equals(id) && supportsText(id, architecture)) ids.add(id);
        }
        if (ids.isEmpty()) throw new IllegalStateException("API tidak menampilkan model chat teks.");
        return new java.util.ArrayList<>(ids);
    }

    private static boolean supportsText(String id, JSONObject architecture) {
        if (architecture != null && (!hasText(architecture.optJSONArray("input_modalities"))
                || !hasText(architecture.optJSONArray("output_modalities")))) return false;
        return !id.toLowerCase(java.util.Locale.ROOT).matches(
            ".*(?:embed|rerank|moderation|whisper|transcrib|transcription|(?:^|[-/])tts(?:[-:]|$)|"
            + "(?:^|[-/])(?:audio|realtime|imagen|veo|sora|dall-e|gpt-image|live)(?:[-:]|$)|"
            + "image-generation|image-preview|(?:^|[-/])image(?:[-:]|$)|deep-research).*");
    }

    private static boolean hasText(JSONArray modalities) {
        if (modalities == null || modalities.length() == 0) return true;
        for (int i = 0; i < modalities.length(); i++) {
            if ("text".equalsIgnoreCase(modalities.optString(i))) return true;
        }
        return false;
    }

    static Answer complete(Profile profile, JSONArray messages, String lastModel) throws Exception {
        if (!profile.automatic()) return completeOne(profile, profile.model(), messages);
        var candidates = new java.util.LinkedHashSet<String>();
        if (!lastModel.isEmpty()) {
            try {
                return completeOne(profile, lastModel, messages);
            } catch (ApiException error) {
                if (!error.modelUnavailable) throw error;
            }
        }
        var listed = models(profile);
        listed.sort(java.util.Comparator.comparingInt(Providers::priority));
        candidates.addAll(listed);
        candidates.remove(lastModel);
        int attempts = 0;
        for (String model : candidates) {
            if (attempts++ == 5) break;
            try {
                return completeOne(profile, model, messages);
            } catch (ApiException error) {
                if (!error.modelUnavailable) throw error;
            }
        }
        throw new IllegalStateException("Model otomatis tidak bisa dipakai. Buka API dan pilih model lain.");
    }

    private static int priority(String id) {
        String name = id.toLowerCase(java.util.Locale.ROOT);
        int rank = name.endsWith(":free") ? 0 : 20;
        if (name.contains("flash") || name.contains("mini") || name.contains("instant") || name.contains("lite")) rank -= 5;
        if (name.contains("preview") || name.contains("experimental")) rank += 2;
        return rank;
    }

    private static Answer completeOne(Profile profile, String model, JSONArray messages) throws Exception {
        var body = new JSONObject().put("model", model).put("messages", messages).put("stream", false);
        JSONObject response = request(validateEndpoint(profile.endpoint()), profile.key(), body);
        Object content = response.getJSONArray("choices").getJSONObject(0).getJSONObject("message").get("content");
        if (!(content instanceof String answer) || answer.trim().isEmpty()) {
            throw new IllegalStateException("Provider tidak mengembalikan jawaban teks.");
        }
        return new Answer(answer, model);
    }

    private static JSONObject request(URL url, String key, JSONObject body) throws Exception {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
        HttpsURLConnection connection = (HttpsURLConnection) url.openConnection();
        try {
            connection.setConnectTimeout(12000);
            connection.setReadTimeout(body == null ? 15000 : 60000);
            connection.setInstanceFollowRedirects(false);
            if (!key.isEmpty()) connection.setRequestProperty("Authorization", "Bearer " + key);
            if (body != null) {
                connection.setRequestMethod("POST");
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                connection.setDoOutput(true);
                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                connection.setFixedLengthStreamingMode(bytes.length);
                try (var output = connection.getOutputStream()) { output.write(bytes); }
            }
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new ApiException(switch (status) {
                    case 401, 403 -> "Akses ditolak. Cek API key dan izin model.";
                    case 402 -> "Saldo atau kredit provider tidak cukup.";
                    case 404 -> "Endpoint atau model tidak ditemukan.";
                    case 429 -> "Batas permintaan atau kuota provider tercapai.";
                    default -> "Provider mengembalikan HTTP " + status + ".";
                }, body != null && unavailableModel(connection, status));
            }
            return new JSONObject(read(connection.getInputStream(), 8_000_000));
        } finally {
            connection.disconnect();
        }
    }

    private static boolean unavailableModel(HttpsURLConnection connection, int status) {
        if (status != 400 && status != 403 && status != 404) return false;
        try {
            String detail = read(connection.getErrorStream(), 16000).toLowerCase(java.util.Locale.ROOT);
            return (status == 400 && detail.contains("image") && (detail.contains("not support") || detail.contains("text only") || detail.contains("only text")))
                || detail.contains("model_not_found") || detail.contains("unsupported_model")
                || detail.contains("model_decommissioned") || detail.contains("no endpoints found")
                || (detail.contains("model") && (detail.contains("not found") || detail.contains("does not exist")
                    || detail.contains("not supported") || detail.contains("don't have access")
                    || detail.contains("do not have access") || detail.contains("not available")));
        } catch (Exception ignored) {
            return false;
        }
    }

    private static String read(InputStream input, int limit) throws Exception {
        if (input == null) return "";
        try (input; var buffer = new java.io.ByteArrayOutputStream()) {
            byte[] chunk = new byte[8192];
            int count;
            while ((count = input.read(chunk)) != -1) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                if (buffer.size() + count > limit) throw new IllegalStateException("Respons API terlalu besar.");
                buffer.write(chunk, 0, count);
            }
            return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}

