package com.devils.addon.shared.sync;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class SyncJsonUtils {
    private static final SecureRandom RNG = new SecureRandom();
    private static final HexFormat HEX = HexFormat.of();

    private SyncJsonUtils() {
    }

    public static JsonObject parseJsonObject(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            if (!JsonParser.parseString(raw).isJsonObject()) return null;
            return JsonParser.parseString(raw).getAsJsonObject();
        } catch (Exception ignored) {
            return null;
        }
    }

    public static JsonArray readArray(JsonObject json, String key) {
        if (json == null || key == null || !json.has(key) || !json.get(key).isJsonArray()) return null;
        return json.getAsJsonArray(key);
    }

    public static String readString(JsonObject json, String key, String fallback) {
        if (json == null || key == null || !json.has(key) || json.get(key).isJsonNull()) return fallback;
        try {
            return json.get(key).getAsString();
        } catch (Exception ignored) {
            return fallback;
        }
    }

    public static int readInt(JsonObject json, String key, int fallback) {
        if (json == null || key == null || !json.has(key) || json.get(key).isJsonNull()) return fallback;
        try {
            return json.get(key).getAsInt();
        } catch (Exception ignored) {
            return fallback;
        }
    }

    public static long readLong(JsonObject json, String key, long fallback) {
        if (json == null || key == null || !json.has(key) || json.get(key).isJsonNull()) return fallback;
        try {
            return json.get(key).getAsLong();
        } catch (Exception ignored) {
            return fallback;
        }
    }

    public static double readDouble(JsonObject json, String key, double fallback) {
        if (json == null || key == null || !json.has(key) || json.get(key).isJsonNull()) return fallback;
        try {
            return json.get(key).getAsDouble();
        } catch (Exception ignored) {
            return fallback;
        }
    }

    public static boolean readBoolean(JsonObject json, String key, boolean fallback) {
        if (json == null || key == null || !json.has(key) || json.get(key).isJsonNull()) return fallback;
        try {
            return json.get(key).getAsBoolean();
        } catch (Exception ignored) {
            return fallback;
        }
    }

    public static String parseHttpError(HttpResponse<String> response) {
        if (response == null) return "http:error";
        return parseHttpError(response.statusCode(), response.body());
    }

    public static String parseHttpError(int statusCode, String body) {
        String safeBody = safe(body).replaceAll("\\s+", " ").trim();
        if (safeBody.length() > 120) safeBody = safeBody.substring(0, 120) + "...";
        if (!safeBody.isBlank()) return "http-" + statusCode + "-" + safeBody;
        return "http-" + statusCode;
    }

    public static String formatSyncException(String prefix, Throwable throwable, int maxDetailLen) {
        if (throwable == null) return prefix + ":unknown";

        Throwable root = throwable;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();

        String type = root.getClass().getSimpleName();
        if (type == null || type.isBlank()) type = root.getClass().getName();
        type = type.toLowerCase(Locale.ROOT);

        String detail = compactSyncErrorMessage(root.getMessage(), maxDetailLen);
        if (detail.isEmpty()) return prefix + ":" + type;
        return prefix + ":" + type + ":" + detail;
    }

    public static String compactSyncErrorMessage(String raw, int maxDetailLen) {
        if (raw == null) return "";
        String compact = raw.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
        if (compact.isEmpty()) return "";
        if (compact.length() > maxDetailLen) return compact.substring(0, maxDetailLen) + "...";
        return compact;
    }

    public static void applySignedHeaders(HttpRequest.Builder builder, URI uri, String method, String body, String signingKey) {
        if (builder == null || uri == null) return;
        String key = safe(signingKey).trim();
        if (key.isBlank()) return;

        long timestamp = Instant.now().getEpochSecond();
        String nonce = randomHex(16);
        String target = rawTarget(uri);
        String bodyHash = sha256Hex(body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8));
        String canonical = safe(method).trim().toUpperCase(Locale.ROOT)
            + "\n" + target
            + "\n" + timestamp
            + "\n" + nonce
            + "\n" + bodyHash;
        String signature = hmacSha256Hex(key.getBytes(StandardCharsets.UTF_8), canonical.getBytes(StandardCharsets.UTF_8));

        builder.header("X-Devils-Timestamp", Long.toString(timestamp));
        builder.header("X-Devils-Nonce", nonce);
        builder.header("X-Devils-Signature", signature);
        builder.header("X-Devils-Signature-Version", "v1");
    }

    private static String rawTarget(URI uri) {
        String path = safe(uri.getRawPath());
        if (path.isBlank()) path = "/";
        String query = safe(uri.getRawQuery());
        return query.isBlank() ? path : (path + "?" + query);
    }

    private static String randomHex(int size) {
        byte[] bytes = new byte[Math.max(8, size)];
        RNG.nextBytes(bytes);
        return HEX.formatHex(bytes);
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HEX.formatHex(digest.digest(bytes == null ? new byte[0] : bytes));
        } catch (Exception e) {
            throw new IllegalStateException("sha256-failed", e);
        }
    }

    private static String hmacSha256Hex(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return HEX.formatHex(mac.doFinal(data));
        } catch (Exception e) {
            throw new IllegalStateException("hmac-failed", e);
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
