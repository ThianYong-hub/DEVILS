package com.example.addon.modules.autologin;

import com.example.addon.modules.AutoLogin;
import com.example.addon.modules.autologin.AutoLoginProfile.SyncProfileData;
import com.example.addon.modules.autologin.AutoLoginProfile.SyncPullResult;
import com.example.addon.modules.autologin.AutoLoginProfile.SyncPushResult;
import com.example.addon.modules.sync.SyncJsonUtils;
import com.example.addon.util.SyncCrypto;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.function.IntSupplier;

public final class AutoLoginSyncCodec {
    private static final String MODULE_NAME = "auto-login";
    private static final String PULL_PATH = "/pull";
    private static final String PUSH_PATH = "/push";
    private static final String STREAM_PATH = "/v1/sync/stream";
    private static final String STREAM_PATH_LEGACY = "/stream";

    private final HttpClient httpClient;
    private final IntSupplier defaultDelaySupplier;
    private final int errorDetailMax;

    public AutoLoginSyncCodec(HttpClient httpClient, IntSupplier defaultDelaySupplier, int errorDetailMax) {
        this.httpClient = httpClient;
        this.defaultDelaySupplier = defaultDelaySupplier;
        this.errorDetailMax = errorDetailMax;
    }

    public SyncPullResult sendPullRequest(String baseUrl, String deviceId, String token, String signingKey, int timeoutSec, String encryptionKey, long knownRevision, int waitMs) throws Exception {
        JsonObject payload = new JsonObject();
        payload.addProperty("deviceId", deviceId);
        payload.addProperty("knownRevision", knownRevision);
        if (waitMs > 0) payload.addProperty("waitMs", waitMs);
        payload.addProperty("module", MODULE_NAME);

        HttpRequest request = buildSyncRequest(baseUrl, PULL_PATH, payload.toString(), token, signingKey, timeoutSec);
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        return parsePullResponse(response, encryptionKey);
    }

    public SyncPushResult sendPushRequest(
        String baseUrl,
        String deviceId,
        String token,
        String signingKey,
        int timeoutSec,
        String encryptionKey,
        long knownRevision,
        List<SyncProfileData> profilesSnapshot
    ) throws Exception {
        JsonObject payload = new JsonObject();
        payload.addProperty("deviceId", deviceId);
        payload.addProperty("baseRevision", knownRevision);
        payload.addProperty("module", MODULE_NAME);
        payload.add("profiles", SyncCrypto.encryptProfiles(toJsonArray(profilesSnapshot), encryptionKey, MODULE_NAME));

        HttpRequest request = buildSyncRequest(baseUrl, PUSH_PATH, payload.toString(), token, signingKey, timeoutSec);
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        return parsePushResponse(response, encryptionKey);
    }

    public HttpRequest buildSyncStreamRequest(String baseUrl, String deviceId, String token, String signingKey, int timeoutSec, long knownRevision, int waitMs, boolean useLegacyPath) {
        URI uri = buildSyncStreamUri(baseUrl, deviceId, knownRevision, waitMs, useLegacyPath);
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(Math.max(10, timeoutSec)))
            .header("Accept", "text/event-stream")
            .header("Cache-Control", "no-cache")
            .header("User-Agent", "Devils-AutoLoginSync/1.0")
            .GET();
        if (token != null && !token.isBlank()) builder.header("Authorization", "Bearer " + token);
        SyncJsonUtils.applySignedHeaders(builder, uri, "GET", "", signingKey);
        return builder.build();
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
            if (!normalizedScheme.equals("http") && !normalizedScheme.equals("https")) {
                return "unsupported scheme: " + normalizedScheme;
            }

            if (uri.getHost() == null || uri.getHost().isBlank()) {
                return "uri with undefined host";
            }

            return null;
        } catch (IllegalArgumentException e) {
            return SyncJsonUtils.formatSyncException("bad-base-url", e, errorDetailMax);
        }
    }

    public String computeFingerprint(List<SyncProfileData> snapshot) {
        try {
            ArrayList<String> lines = new ArrayList<>();
            for (SyncProfileData data : snapshot) {
                lines.add(
                    AutoLoginTextRules.normalizeKey(data.username()) + "|"
                        + AutoLoginTextRules.normalizeServerKey(data.server()) + "|"
                        + data.mode().name() + "|"
                        + data.password() + "|"
                        + data.delay() + "|"
                        + data.enabled()
                );
            }
            lines.sort(Comparator.naturalOrder());
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String line : lines) {
                digest.update(line.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) '\n');
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Throwable t) {
            return Integer.toHexString(snapshot.hashCode());
        }
    }

    public String parseHttpError(HttpResponse<String> response) {
        if (response == null) return "http-unknown";
        return parseHttpError(response.statusCode(), response.body());
    }

    public String parseHttpError(int statusCode, String body) {
        String base = "http-" + statusCode;
        if (body == null || body.isBlank()) return base;
        JsonObject json = SyncJsonUtils.parseJsonObject(body);
        if (json == null) return base;
        String error = SyncJsonUtils.readString(json, "error", "").trim();
        return error.isEmpty() ? base : (base + "-" + error);
    }

    private HttpRequest buildSyncRequest(String baseUrl, String path, String body, String token, String signingKey, int timeoutSec) {
        URI uri = URI.create(baseUrl + path);
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(Math.max(3, timeoutSec)))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .header("User-Agent", "Devils-AutoLoginSync/1.0");
        if (token != null && !token.isBlank()) builder.header("Authorization", "Bearer " + token.trim());
        SyncJsonUtils.applySignedHeaders(builder, uri, "POST", body, signingKey);
        return builder.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
    }

    private SyncPullResult parsePullResponse(HttpResponse<String> response, String encryptionKey) {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            return new SyncPullResult(false, -1, null, parseHttpError(response), "");
        }
        if (response.body() == null || response.body().isBlank()) {
            return new SyncPullResult(true, -1, null, "", "");
        }

        JsonObject json = SyncJsonUtils.parseJsonObject(response.body());
        if (json == null) return new SyncPullResult(false, -1, null, "bad-json", "");

        boolean ok = SyncJsonUtils.readBoolean(json, "ok", true);
        long revision = SyncJsonUtils.readLong(json, "revision", SyncJsonUtils.readLong(json, "rev", -1));
        List<SyncProfileData> remoteProfiles;
        try {
            remoteProfiles = readProfiles(json, encryptionKey);
        } catch (Exception decryptError) {
            return new SyncPullResult(false, revision, null, "decrypt:" + decryptError.getClass().getSimpleName(), "");
        }
        String error = SyncJsonUtils.readString(json, "error", "");
        String lastWriter = SyncJsonUtils.readString(json, "lastWriter", SyncJsonUtils.readString(json, "last_writer", ""));
        return new SyncPullResult(ok, revision, remoteProfiles, error, lastWriter);
    }

    private SyncPushResult parsePushResponse(HttpResponse<String> response, String encryptionKey) {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            return new SyncPushResult(false, false, false, -1, null, parseHttpError(response), "");
        }
        if (response.body() == null || response.body().isBlank()) {
            return new SyncPushResult(true, true, false, -1, null, "", "");
        }

        JsonObject json = SyncJsonUtils.parseJsonObject(response.body());
        if (json == null) return new SyncPushResult(false, false, false, -1, null, "bad-json", "");

        boolean ok = SyncJsonUtils.readBoolean(json, "ok", true);
        boolean conflict = SyncJsonUtils.readBoolean(json, "conflict", false);
        boolean applied = SyncJsonUtils.readBoolean(json, "applied", ok && !conflict);
        long revision = SyncJsonUtils.readLong(json, "revision", SyncJsonUtils.readLong(json, "rev", -1));
        List<SyncProfileData> remoteProfiles;
        try {
            remoteProfiles = readProfiles(json, encryptionKey);
        } catch (Exception decryptError) {
            return new SyncPushResult(false, false, conflict, revision, null, "decrypt:" + decryptError.getClass().getSimpleName(), "");
        }
        String error = SyncJsonUtils.readString(json, "error", "");
        String lastWriter = SyncJsonUtils.readString(json, "lastWriter", SyncJsonUtils.readString(json, "last_writer", ""));
        return new SyncPushResult(ok, applied, conflict, revision, remoteProfiles, error, lastWriter);
    }

    private List<SyncProfileData> readProfiles(JsonObject json, String encryptionKey) throws Exception {
        JsonArray array = null;
        if (json.has("profiles") && json.get("profiles").isJsonArray()) array = json.getAsJsonArray("profiles");
        if (array == null && json.has("entries") && json.get("entries").isJsonArray()) array = json.getAsJsonArray("entries");
        if (array == null && json.has("data") && json.get("data").isJsonArray()) array = json.getAsJsonArray("data");
        if (array == null) return null;
        array = SyncCrypto.decryptProfiles(array, encryptionKey, MODULE_NAME, true);

        ArrayList<SyncProfileData> list = new ArrayList<>();
        for (JsonElement element : array) {
            if (!element.isJsonObject()) continue;
            JsonObject item = element.getAsJsonObject();
            boolean enabled = SyncJsonUtils.readBoolean(item, "enabled", true);
            String username = SyncJsonUtils.readString(item, "username", "").trim();
            String server = SyncJsonUtils.readString(item, "server", "").trim();
            String modeRaw = SyncJsonUtils.readString(item, "mode", "LOGIN").trim();
            AutoLogin.LoginMode mode = "REGISTER".equalsIgnoreCase(modeRaw) || "REG".equalsIgnoreCase(modeRaw)
                ? AutoLogin.LoginMode.REGISTER
                : AutoLogin.LoginMode.LOGIN;
            String password = SyncJsonUtils.readString(item, "password", "");
            int delay = (int) Math.max(0, SyncJsonUtils.readLong(item, "delay", defaultDelaySupplier.getAsInt()));
            list.add(new SyncProfileData(enabled, username, server, mode, password, delay));
        }
        return list;
    }

    private JsonArray toJsonArray(List<SyncProfileData> snapshot) {
        JsonArray array = new JsonArray();
        for (SyncProfileData data : snapshot) {
            JsonObject item = new JsonObject();
            item.addProperty("enabled", data.enabled());
            item.addProperty("username", data.username());
            item.addProperty("server", data.server());
            item.addProperty("mode", data.mode().name());
            item.addProperty("password", data.password());
            item.addProperty("delay", data.delay());
            array.add(item);
        }
        return array;
    }

    private URI buildSyncStreamUri(String baseUrl, String deviceId, long knownRevision, int waitMs, boolean useLegacyPath) {
        String query =
            "deviceId=" + encodeQueryValue(deviceId)
                + "&module=" + MODULE_NAME
                + "&knownRevision=" + knownRevision
                + "&waitMs=" + Math.max(1_000, waitMs);
        return URI.create(baseUrl + (useLegacyPath ? STREAM_PATH_LEGACY : STREAM_PATH) + "?" + query);
    }

    private static String encodeQueryValue(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }
}


