package com.example.addon.modules.ping;

import com.example.addon.modules.sync.SyncJsonUtils;


import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;

public final class PingFormattingUtils {
    private PingFormattingUtils() {
    }

    public static String normalizeSender(String raw) {
        String value = safe(raw).trim();
        if (value.isBlank()) return "Unknown";
        if (value.contains("|")) {
            String[] parts = value.split("\\|");
            value = safe(parts[0]).trim();
        } else if (value.contains("/") || value.contains("\\")) {
            String[] parts = value.replace('\\', '/').split("/");
            if (parts.length >= 3 && safe(parts[0]).toLowerCase(Locale.ROOT).contains("[ping]")) {
                value = safe(parts[1]).trim();
            } else {
                value = safe(parts[parts.length - 1]).trim();
            }
        }
        if (value.regionMatches(true, 0, "[PING]", 0, 6)) {
            value = safe(value.substring(6)).trim();
        }
        int lastSpace = value.lastIndexOf(' ');
        if (lastSpace > 0) {
            String tail = safe(value.substring(lastSpace + 1)).trim().toLowerCase(Locale.ROOT);
            if (tail.equals("~") || tail.equals("--") || tail.matches("\\d+(?:\\.\\d+)?(?:m|k)?")) {
                value = safe(value.substring(0, lastSpace)).trim();
            }
        }
        return value.isBlank() ? "Unknown" : value;
    }

    public static String encodeQueryValue(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    public static String normalizeSyncBaseUrl(String raw) {
        if (raw == null) return "";
        String base = raw.trim();
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return base;
    }

    public static String normalizeLocalSoundPath(String raw) {
        if (raw == null) return "";
        String normalized = raw.trim().replace('\\', '/');
        while (normalized.startsWith("/")) normalized = normalized.substring(1);
        return normalized;
    }

    public static String validateSyncBaseUrl(String baseUrl, int detailLimit) {
        if (baseUrl == null || baseUrl.isBlank()) return "base-url-empty";
        try {
            URI uri = URI.create(baseUrl);
            String scheme = uri.getScheme();
            if (scheme == null) return "undefined scheme";
            if (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https")) return "unsupported scheme: " + scheme;
            if (uri.getHost() == null || uri.getHost().isBlank()) return "uri with undefined host";
            return null;
        } catch (Exception e) {
            return SyncJsonUtils.formatSyncException("bad-base-url", e, detailLimit);
        }
    }

    public static String safe(String value) {
        return value == null ? "" : value;
    }

    public static String normalizeKey(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    public static String normalizeServerKey(String value) {
        String normalized = normalizeKey(value);
        return normalized.endsWith(":25565") ? normalized.substring(0, normalized.length() - 6) : normalized;
    }

    public static String buildMarkerId(String sender, String server) {
        String base = normalizeKey(sender) + "|" + normalizeServerKey(server);
        if (base.isBlank()) return "ping-marker";
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String hash = HexFormat.of().formatHex(digest.digest(base.getBytes(StandardCharsets.UTF_8)));
            return "ping-" + hash.substring(0, Math.min(24, hash.length()));
        } catch (Exception ignored) {
            return "ping-" + Integer.toHexString(base.hashCode());
        }
    }

    public static double clampY(double y, int minY, int maxY) {
        if (!Double.isFinite(y)) return minY;
        if (y < minY) return minY;
        if (y > maxY) return maxY;
        return y;
    }

    public static String formatCoords(double x, double y, double z) {
        return String.format(Locale.ROOT, "%d %d %d", Math.round(x), Math.round(y), Math.round(z));
    }
}



