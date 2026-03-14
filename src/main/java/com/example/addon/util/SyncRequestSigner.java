package com.example.addon.util;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;

import java.net.http.HttpRequest;

public final class SyncRequestSigner {
    private static final SecureRandom RNG = new SecureRandom();
    private static final HexFormat HEX = HexFormat.of();

    private SyncRequestSigner() {
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
