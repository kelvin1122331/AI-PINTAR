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
    record Profile(String endpoint, String model, String key) {}
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

    static String complete(Profile profile, JSONArray messages) throws Exception {
        HttpsURLConnection connection = (HttpsURLConnection) validateEndpoint(profile.endpoint()).openConnection();
        try {
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(60000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            if (!profile.key().isEmpty()) connection.setRequestProperty("Authorization", "Bearer " + profile.key());
            connection.setDoOutput(true);
            byte[] body = new JSONObject().put("model", profile.model()).put("messages", messages)
                .put("stream", false).toString().getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(body.length);
            try (var output = connection.getOutputStream()) { output.write(body); }
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) throw new IllegalStateException(switch (status) {
                case 401, 403 -> "Akses ditolak. Cek API key dan izin model.";
                case 404 -> "Endpoint atau model tidak ditemukan.";
                case 429 -> "Batas permintaan atau kuota provider tercapai.";
                default -> "Provider mengembalikan HTTP " + status + ".";
            });
            try (InputStream input = connection.getInputStream()) {
                var buffer = new java.io.ByteArrayOutputStream();
                byte[] chunk = new byte[8192];
                int count;
                while ((count = input.read(chunk)) != -1) {
                    if (buffer.size() + count > 2_000_000) throw new IllegalStateException("Respons terlalu besar.");
                    buffer.write(chunk, 0, count);
                }
                Object content = new JSONObject(new String(buffer.toByteArray(), StandardCharsets.UTF_8))
                    .getJSONArray("choices").getJSONObject(0).getJSONObject("message").get("content");
                if (!(content instanceof String answer) || answer.trim().isEmpty()) {
                    throw new IllegalStateException("Provider tidak mengembalikan jawaban teks.");
                }
                return answer;
            }
        } finally {
            connection.disconnect();
        }
    }
}
