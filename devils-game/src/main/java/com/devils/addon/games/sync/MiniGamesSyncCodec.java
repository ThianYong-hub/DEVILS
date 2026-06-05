package com.devils.addon.games.sync;

import com.devils.addon.shared.sync.SyncCrypto;
import com.devils.addon.shared.sync.SyncDomainRoutes;
import com.devils.addon.shared.sync.SyncJsonUtils;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

public final class MiniGamesSyncCodec {
    public static final String MODULE_NAME = "mini-games";
    public static final String PULL_PATH = SyncDomainRoutes.GAME_PULL_PATH;
    public static final String PUSH_PATH = SyncDomainRoutes.GAME_PUSH_PATH;

    private final HttpClient httpClient;
    private final int errorDetailMax;

    public MiniGamesSyncCodec(int errorDetailMax) {
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        this.errorDetailMax = errorDetailMax;
    }

    public PullResult sendPullRequest(
        String baseUrl,
        String deviceId,
        String token,
        String signingKey,
        int timeoutSec,
        String encryptionKey,
        long knownRevision
    ) throws Exception {
        JsonObject payload = new JsonObject();
        payload.addProperty("deviceId", deviceId);
        payload.addProperty("knownRevision", knownRevision);
        payload.addProperty("module", MODULE_NAME);

        HttpRequest request = buildSyncRequest(baseUrl, PULL_PATH, payload.toString(), token, signingKey, timeoutSec);
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        return parsePullResponse(response, encryptionKey);
    }

    public PushResult sendPushRequest(
        String baseUrl,
        String deviceId,
        String token,
        String signingKey,
        int timeoutSec,
        String encryptionKey,
        long baseRevision,
        List<SyncRow> rows
    ) throws Exception {
        JsonObject payload = new JsonObject();
        payload.addProperty("deviceId", deviceId);
        payload.addProperty("baseRevision", baseRevision);
        payload.addProperty("module", MODULE_NAME);
        payload.add("profiles", SyncCrypto.encryptProfiles(toJsonArray(rows), encryptionKey, MODULE_NAME));

        HttpRequest request = buildSyncRequest(baseUrl, PUSH_PATH, payload.toString(), token, signingKey, timeoutSec);
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        return parsePushResponse(response, encryptionKey);
    }

    public String normalizeSyncBaseUrl(String rawUrl) {
        if (rawUrl == null) return "";
        String trimmed = rawUrl.trim();
        while (trimmed.endsWith("/")) trimmed = trimmed.substring(0, trimmed.length() - 1);
        return trimmed;
    }

    public String validateSyncBaseUrl(String baseUrl) {
        try {
            URI uri = URI.create(baseUrl);
            String scheme = uri.getScheme();
            if (scheme == null || scheme.isBlank()) return "uri with undefined scheme";

            String normalizedScheme = scheme.toLowerCase(Locale.ROOT);
            if (!normalizedScheme.equals("http") && !normalizedScheme.equals("https")) return "unsupported scheme: " + normalizedScheme;
            if (uri.getHost() == null || uri.getHost().isBlank()) return "uri with undefined host";
            return null;
        } catch (IllegalArgumentException e) {
            return SyncJsonUtils.formatSyncException("bad-base-url", e, errorDetailMax);
        }
    }

    public String computeFingerprint(List<SyncRow> rows) {
        try {
            ArrayList<String> lines = new ArrayList<>();
            for (SyncRow row : rows) {
                lines.add(
                    safe(row.username) + "|"
                        + safe(row.server) + "|"
                        + safe(row.mode) + "|"
                        + safe(row.payload) + "|"
                        + row.delay + "|"
                        + row.enabled
                );
            }
            lines.sort(String::compareTo);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String line : lines) {
                digest.update(line.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) '\n');
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Throwable ignored) {
            return Integer.toHexString(rows.hashCode());
        }
    }

    private HttpRequest buildSyncRequest(String baseUrl, String path, String body, String token, String signingKey, int timeoutSec) {
        URI uri = URI.create(baseUrl + path);
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(Math.max(3, timeoutSec)))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .header("User-Agent", "Devils-MiniGames/1.0");
        if (token != null && !token.isBlank()) builder.header("Authorization", "Bearer " + token.trim());
        SyncJsonUtils.applySignedHeaders(builder, uri, "POST", body, signingKey);
        return builder.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
    }

    private PullResult parsePullResponse(HttpResponse<String> response, String encryptionKey) {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            return new PullResult(false, -1, List.of(), SyncJsonUtils.parseHttpError(response));
        }
        if (response.body() == null || response.body().isBlank()) {
            return new PullResult(true, -1, List.of(), "");
        }

        JsonObject json = SyncJsonUtils.parseJsonObject(response.body());
        if (json == null) return new PullResult(false, -1, List.of(), "bad-json");

        boolean ok = SyncJsonUtils.readBoolean(json, "ok", true);
        long revision = SyncJsonUtils.readLong(json, "revision", SyncJsonUtils.readLong(json, "rev", -1));
        List<SyncRow> rows;
        try {
            rows = readRows(json, encryptionKey);
        } catch (Exception decryptError) {
            return new PullResult(false, revision, List.of(), "decrypt:" + decryptError.getClass().getSimpleName());
        }
        String error = SyncJsonUtils.readString(json, "error", "");
        return new PullResult(ok, revision, rows, error);
    }

    private PushResult parsePushResponse(HttpResponse<String> response, String encryptionKey) {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            return new PushResult(false, false, false, -1, List.of(), SyncJsonUtils.parseHttpError(response));
        }
        if (response.body() == null || response.body().isBlank()) {
            return new PushResult(true, true, false, -1, List.of(), "");
        }

        JsonObject json = SyncJsonUtils.parseJsonObject(response.body());
        if (json == null) return new PushResult(false, false, false, -1, List.of(), "bad-json");

        boolean ok = SyncJsonUtils.readBoolean(json, "ok", true);
        boolean conflict = SyncJsonUtils.readBoolean(json, "conflict", false);
        boolean applied = SyncJsonUtils.readBoolean(json, "applied", ok && !conflict);
        long revision = SyncJsonUtils.readLong(json, "revision", SyncJsonUtils.readLong(json, "rev", -1));
        List<SyncRow> rows;
        try {
            rows = readRows(json, encryptionKey);
        } catch (Exception decryptError) {
            return new PushResult(false, false, conflict, revision, List.of(), "decrypt:" + decryptError.getClass().getSimpleName());
        }
        String error = SyncJsonUtils.readString(json, "error", "");
        return new PushResult(ok, applied, conflict, revision, rows, error);
    }

    private List<SyncRow> readRows(JsonObject json, String encryptionKey) throws Exception {
        JsonArray array = SyncJsonUtils.readArray(json, "profiles");
        if (array == null) array = SyncJsonUtils.readArray(json, "entries");
        if (array == null) array = SyncJsonUtils.readArray(json, "data");
        if (array == null) return List.of();
        array = SyncCrypto.decryptProfiles(array, encryptionKey, MODULE_NAME, true);

        ArrayList<SyncRow> rows = new ArrayList<>();
        for (int i = 0; i < array.size(); i++) {
            if (!array.get(i).isJsonObject()) continue;
            JsonObject entry = array.get(i).getAsJsonObject();
            rows.add(new SyncRow(
                SyncJsonUtils.readBoolean(entry, "enabled", true),
                SyncJsonUtils.readString(entry, "username", ""),
                SyncJsonUtils.readString(entry, "server", ""),
                SyncJsonUtils.readString(entry, "mode", "LOGIN"),
                SyncJsonUtils.readString(entry, "password", ""),
                SyncJsonUtils.readInt(entry, "delay", 0)
            ));
        }
        return rows;
    }

    private JsonArray toJsonArray(List<SyncRow> rows) {
        JsonArray array = new JsonArray();
        if (rows == null) return array;

        for (SyncRow row : rows) {
            JsonObject entry = new JsonObject();
            entry.addProperty("enabled", row.enabled);
            entry.addProperty("username", row.username);
            entry.addProperty("server", row.server);
            entry.addProperty("mode", row.mode);
            entry.addProperty("password", row.payload);
            entry.addProperty("delay", row.delay);
            array.add(entry);
        }
        return array;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    public record SyncRow(
        boolean enabled,
        String username,
        String server,
        String mode,
        String payload,
        int delay
    ) {
    }

    public record PullResult(
        boolean ok,
        long revision,
        List<SyncRow> rows,
        String error
    ) {
    }

    public record PushResult(
        boolean ok,
        boolean applied,
        boolean conflict,
        long revision,
        List<SyncRow> rows,
        String error
    ) {
    }
}

