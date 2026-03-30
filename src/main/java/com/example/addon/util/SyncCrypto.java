package com.example.addon.util;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Locale;

public final class SyncCrypto {
    private static final String ENVELOPE_USERNAME = "__devils_e2e__";
    private static final String ENVELOPE_SERVER = "*";
    private static final String ENVELOPE_MODE = "LOGIN";
    private static final String ENVELOPE_PREFIX_V1 = "devils-e2e:v1:";
    private static final String ENVELOPE_PREFIX_V2 = "devils-e2e:v2:";
    private static final int NONCE_BYTES = 12;
    private static final int SALT_BYTES = 16;
    private static final int TAG_BITS = 128;
    private static final int PBKDF2_ITERATIONS = 310_000;
    private static final int KEY_BYTES = 32;
    private static final SecureRandom RNG = new SecureRandom();

    private SyncCrypto() {
    }

    public static JsonArray encryptProfiles(JsonArray plainProfiles, String keyMaterial, String module) throws Exception {
        if (plainProfiles == null) plainProfiles = new JsonArray();
        String key = safe(keyMaterial).trim();
        if (key.isBlank()) throw new IllegalArgumentException("missing-key");

        String normalizedModule = normalizeModule(module);
        byte[] salt = new byte[SALT_BYTES];
        byte[] nonce = new byte[NONCE_BYTES];
        RNG.nextBytes(salt);
        RNG.nextBytes(nonce);

        byte[] plaintext = plainProfiles.toString().getBytes(StandardCharsets.UTF_8);
        byte[] keyBytes = deriveKeyV2(key, salt);
        byte[] ciphertext = crypt(Cipher.ENCRYPT_MODE, plaintext, nonce, keyBytes, normalizedModule, 2);

        JsonObject envelope = new JsonObject();
        envelope.addProperty("v", 2);
        envelope.addProperty("m", normalizedModule);
        envelope.addProperty("s", b64Url(salt));
        envelope.addProperty("n", b64Url(nonce));
        envelope.addProperty("ct", b64Url(ciphertext));
        envelope.addProperty("kid", keyId(key, 2));

        JsonObject row = new JsonObject();
        row.addProperty("enabled", true);
        row.addProperty("username", ENVELOPE_USERNAME);
        row.addProperty("server", ENVELOPE_SERVER);
        row.addProperty("mode", ENVELOPE_MODE);
        row.addProperty("password", ENVELOPE_PREFIX_V2 + b64Url(envelope.toString().getBytes(StandardCharsets.UTF_8)));
        row.addProperty("delay", 0);

        JsonArray wrapped = new JsonArray();
        wrapped.add(row);
        return wrapped;
    }

    public static JsonArray decryptProfiles(JsonArray wireProfiles, String keyMaterial, String module) throws Exception {
        return decryptProfiles(wireProfiles, keyMaterial, module, false);
    }

    public static JsonArray decryptProfiles(JsonArray wireProfiles, String keyMaterial, String module, boolean requireEncryptedEnvelope) throws Exception {
        if (wireProfiles == null) return new JsonArray();
        if (!isEncryptedEnvelope(wireProfiles)) {
            if (requireEncryptedEnvelope && !wireProfiles.isEmpty()) {
                throw new IllegalArgumentException("unencrypted-profiles");
            }
            return deepCopyArray(wireProfiles);
        }

        String key = safe(keyMaterial).trim();
        if (key.isBlank()) throw new IllegalArgumentException("missing-key");
        String normalizedModule = normalizeModule(module);

        JsonArray merged = new JsonArray();
        int failures = 0;
        for (JsonElement element : wireProfiles) {
            if (!element.isJsonObject()) throw new IllegalArgumentException("bad-envelope-row");
            try {
                JsonArray plain = decryptEnvelopeRow(element.getAsJsonObject(), key, normalizedModule);
                for (JsonElement plainElement : plain) {
                    merged.add(plainElement);
                }
            } catch (Exception ignored) {
                failures++;
            }
        }
        if (!merged.isEmpty() || failures == 0) return merged;
        throw new IllegalArgumentException("all-envelopes-failed");
    }

    private static JsonArray decryptEnvelopeRow(JsonObject row, String key, String normalizedModule) throws Exception {
        String password = safe(readString(row, "password")).trim();
        boolean v2 = password.startsWith(ENVELOPE_PREFIX_V2);
        boolean v1 = password.startsWith(ENVELOPE_PREFIX_V1);
        if (!v1 && !v2) throw new IllegalArgumentException("bad-envelope-prefix");

        String prefix = v2 ? ENVELOPE_PREFIX_V2 : ENVELOPE_PREFIX_V1;
        byte[] packedBytes = b64UrlDecode(password.substring(prefix.length()));
        JsonObject envelope = parseObject(new String(packedBytes, StandardCharsets.UTF_8));
        if (envelope == null) throw new IllegalArgumentException("bad-envelope-json");

        int version = readInt(envelope, "v", 0);
        if (version != 1 && version != 2) throw new IllegalArgumentException("unsupported-version");

        String envelopeModule = normalizeModule(readString(envelope, "m"));
        if (!envelopeModule.isBlank() && !envelopeModule.equals(normalizedModule)) {
            throw new IllegalArgumentException("module-mismatch");
        }

        byte[] nonce = b64UrlDecode(readString(envelope, "n"));
        byte[] ciphertext = b64UrlDecode(readString(envelope, "ct"));
        byte[] plaintext;
        if (version == 2) {
            byte[] salt = b64UrlDecode(readString(envelope, "s"));
            byte[] keyBytes = deriveKeyV2(key, salt);
            plaintext = crypt(Cipher.DECRYPT_MODE, ciphertext, nonce, keyBytes, normalizedModule, 2);
        } else {
            byte[] keyBytes = deriveKeyV1(key);
            plaintext = crypt(Cipher.DECRYPT_MODE, ciphertext, nonce, keyBytes, normalizedModule, 1);
        }
        JsonElement parsed = JsonParser.parseString(new String(plaintext, StandardCharsets.UTF_8));
        if (!parsed.isJsonArray()) throw new IllegalArgumentException("bad-plain-profiles");
        return parsed.getAsJsonArray();
    }

    public static boolean isEncryptedEnvelope(JsonArray profiles) {
        if (profiles == null || profiles.isEmpty()) return false;
        for (JsonElement element : profiles) {
            if (!element.isJsonObject()) return false;
            if (!isEncryptedEnvelopeRow(element.getAsJsonObject())) return false;
        }
        return true;
    }

    private static boolean isEncryptedEnvelopeRow(JsonObject row) {
        if (row == null) return false;
        String username = safe(readString(row, "username")).trim();
        String password = safe(readString(row, "password")).trim();
        return ENVELOPE_USERNAME.equals(username)
            && (password.startsWith(ENVELOPE_PREFIX_V1) || password.startsWith(ENVELOPE_PREFIX_V2));
    }

    private static byte[] crypt(int mode, byte[] content, byte[] nonce, byte[] keyBytes, String module, int version) throws Exception {
        if (nonce == null || nonce.length != NONCE_BYTES) throw new IllegalArgumentException("bad-nonce");
        SecretKeySpec keySpec = new SecretKeySpec(keyBytes, "AES");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, keySpec, new GCMParameterSpec(TAG_BITS, nonce));
        cipher.updateAAD(aad(module, version));
        return cipher.doFinal(content);
    }

    private static byte[] deriveKeyV1(String keyMaterial) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update("devils-sync-e2e-key|".getBytes(StandardCharsets.UTF_8));
        digest.update(keyMaterial.getBytes(StandardCharsets.UTF_8));
        return digest.digest();
    }

    private static byte[] deriveKeyV2(String keyMaterial, byte[] salt) throws Exception {
        if (salt == null || salt.length < 8) throw new IllegalArgumentException("bad-salt");
        char[] chars = keyMaterial.toCharArray();
        PBEKeySpec spec = new PBEKeySpec(chars, salt, PBKDF2_ITERATIONS, KEY_BYTES * 8);
        try {
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            return factory.generateSecret(spec).getEncoded();
        } finally {
            spec.clearPassword();
            Arrays.fill(chars, '\0');
        }
    }

    private static String keyId(String keyMaterial, int version) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(("devils-sync-e2e-kid:v" + version + "|").getBytes(StandardCharsets.UTF_8));
        digest.update(keyMaterial.getBytes(StandardCharsets.UTF_8));
        byte[] bytes = digest.digest();
        return b64Url(new byte[] {bytes[0], bytes[1], bytes[2], bytes[3], bytes[4], bytes[5]});
    }

    private static byte[] aad(String module, int version) {
        String normalized = normalizeModule(module);
        return ("devils-sync-e2e:aad:v" + version + "|" + normalized).getBytes(StandardCharsets.UTF_8);
    }

    private static String normalizeModule(String module) {
        return safe(module).trim().toLowerCase(Locale.ROOT);
    }

    private static String b64Url(byte[] raw) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    private static byte[] b64UrlDecode(String value) {
        String normalized = safe(value).trim();
        if (normalized.isBlank()) throw new IllegalArgumentException("empty-b64");
        return Base64.getUrlDecoder().decode(normalized);
    }

    private static JsonArray deepCopyArray(JsonArray source) {
        if (source == null) return new JsonArray();
        JsonElement parsed = JsonParser.parseString(source.toString());
        return parsed.isJsonArray() ? parsed.getAsJsonArray() : new JsonArray();
    }

    private static JsonObject parseObject(String raw) {
        try {
            JsonElement parsed = JsonParser.parseString(raw);
            return parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String readString(JsonObject object, String key) {
        if (object == null || key == null || !object.has(key) || object.get(key).isJsonNull()) return "";
        try {
            return object.get(key).getAsString();
        } catch (Exception ignored) {
            return "";
        }
    }

    private static int readInt(JsonObject object, String key, int fallback) {
        if (object == null || key == null || !object.has(key) || object.get(key).isJsonNull()) return fallback;
        try {
            return object.get(key).getAsInt();
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}


