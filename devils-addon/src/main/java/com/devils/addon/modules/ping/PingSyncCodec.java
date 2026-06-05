package com.devils.addon.modules.ping;

import com.devils.addon.shared.sync.SyncCrypto;
import com.devils.addon.shared.sync.SyncJsonUtils;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.InputStream;
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

public final class PingSyncCodec {
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public SyncPullResult sendPullRequest(String baseUrl, String deviceId, String token, String signingKey, int timeoutSec, String encryptionKey, long knownRevision, int waitMs) throws Exception {
        JsonObject payload = new JsonObject();
        payload.addProperty("deviceId", deviceId);
        payload.addProperty("knownRevision", knownRevision);
        if (waitMs > 0) payload.addProperty("waitMs", waitMs);
        payload.addProperty("module", PingConstants.MODULE_NAMESPACE);

        HttpRequest request = buildSyncRequest(baseUrl, PingConstants.SYNC_PULL_PATH, payload.toString(), token, signingKey, timeoutSec);
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
        long baseRevision,
        List<SyncPingData> snapshot
    ) throws Exception {
        JsonObject payload = new JsonObject();
        payload.addProperty("deviceId", deviceId);
        payload.addProperty("baseRevision", baseRevision);
        payload.addProperty("module", PingConstants.MODULE_NAMESPACE);
        payload.add("profiles", SyncCrypto.encryptProfiles(toJsonArray(snapshot), encryptionKey, PingConstants.MODULE_NAMESPACE));

        HttpRequest request = buildSyncRequest(baseUrl, PingConstants.SYNC_PUSH_PATH, payload.toString(), token, signingKey, timeoutSec);
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        return parsePushResponse(response, encryptionKey);
    }

    public HttpResponse<InputStream> sendSyncStreamRequest(
        String baseUrl,
        String deviceId,
        String token,
        String signingKey,
        int timeoutSec,
        long knownRevision,
        int waitMs,
        boolean useLegacyPath
    ) throws Exception {
        HttpRequest request = buildSyncStreamRequest(baseUrl, deviceId, token, signingKey, timeoutSec, knownRevision, waitMs, useLegacyPath);
        return httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
    }

    public String computeFingerprint(List<SyncPingData> snapshot) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            List<String> rows = new ArrayList<>(snapshot.size());
            for (SyncPingData data : snapshot) {
                rows.add(
                    PingFormattingUtils.normalizeKey(data.username())
                        + "|"
                        + PingFormattingUtils.normalizeServerKey(data.server())
                        + "|"
                        + PingFormattingUtils.safe(data.payload())
                );
            }
            rows.sort(String::compareTo);
            for (String row : rows) {
                digest.update(row.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) '\n');
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception ignored) {
            return Integer.toHexString(snapshot.hashCode());
        }
    }

    public String normalizeSyncBaseUrl(String raw) {
        return PingFormattingUtils.normalizeSyncBaseUrl(raw);
    }

    public String validateSyncBaseUrl(String baseUrl) {
        return PingFormattingUtils.validateSyncBaseUrl(baseUrl, PingConstants.SYNC_ERROR_DETAIL_MAX);
    }

    private HttpRequest buildSyncRequest(String baseUrl, String path, String body, String token, String signingKey, int timeoutSec) {
        URI uri = URI.create(baseUrl + path);
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(Math.max(3, timeoutSec)))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .header("User-Agent", "Devils-PingSync/1.0");
        if (token != null && !token.isBlank()) builder.header("Authorization", "Bearer " + token.trim());
        SyncJsonUtils.applySignedHeaders(builder, uri, "POST", body, signingKey);
        return builder.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
    }

    private SyncPullResult parsePullResponse(HttpResponse<String> response, String encryptionKey) {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            return new SyncPullResult(false, -1, null, SyncJsonUtils.parseHttpError(response), "");
        }
        if (response.body() == null || response.body().isBlank()) {
            return new SyncPullResult(true, -1, null, "", "");
        }

        JsonObject json = SyncJsonUtils.parseJsonObject(response.body());
        if (json == null) return new SyncPullResult(false, -1, null, "bad-json", "");

        boolean ok = SyncJsonUtils.readBoolean(json, "ok", true);
        long revision = SyncJsonUtils.readLong(json, "revision", SyncJsonUtils.readLong(json, "rev", -1));
        List<SyncPingData> profiles;
        try {
            profiles = readProfiles(json, encryptionKey);
        } catch (Exception decryptError) {
            return new SyncPullResult(false, revision, null, "decrypt:" + decryptError.getClass().getSimpleName(), "");
        }
        String error = SyncJsonUtils.readString(json, "error", "");
        String lastWriter = SyncJsonUtils.readString(json, "lastWriter", SyncJsonUtils.readString(json, "last_writer", ""));
        return new SyncPullResult(ok, revision, profiles, error, lastWriter);
    }

    private SyncPushResult parsePushResponse(HttpResponse<String> response, String encryptionKey) {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            return new SyncPushResult(false, false, false, -1, null, SyncJsonUtils.parseHttpError(response), "");
        }
        if (response.body() == null || response.body().isBlank()) {
            return new SyncPushResult(true, true, false, -1, null, "", "");
        }

        JsonObject json = SyncJsonUtils.parseJsonObject(response.body());
        if (json == null) return new SyncPushResult(false, false, false, -1, null, "bad-json", "");

        boolean ok = SyncJsonUtils.readBoolean(json, "ok", true);
        boolean applied = SyncJsonUtils.readBoolean(json, "applied", ok);
        boolean conflict = SyncJsonUtils.readBoolean(json, "conflict", false);
        long revision = SyncJsonUtils.readLong(json, "revision", -1);
        List<SyncPingData> profiles;
        try {
            profiles = readProfiles(json, encryptionKey);
        } catch (Exception decryptError) {
            return new SyncPushResult(false, false, conflict, revision, null, "decrypt:" + decryptError.getClass().getSimpleName(), "");
        }
        String error = SyncJsonUtils.readString(json, "error", "");
        String lastWriter = SyncJsonUtils.readString(json, "lastWriter", SyncJsonUtils.readString(json, "last_writer", ""));
        return new SyncPushResult(ok, applied, conflict, revision, profiles, error, lastWriter);
    }

    private List<SyncPingData> readProfiles(JsonObject json, String encryptionKey) throws Exception {
        JsonArray array = SyncJsonUtils.readArray(json, "profiles");
        if (array == null) array = SyncJsonUtils.readArray(json, "data");
        if (array == null) return List.of();
        array = SyncCrypto.decryptProfiles(array, encryptionKey, PingConstants.MODULE_NAMESPACE, true);

        ArrayList<SyncPingData> list = new ArrayList<>();
        for (int i = 0; i < array.size(); i++) {
            if (!array.get(i).isJsonObject()) continue;
            JsonObject profile = array.get(i).getAsJsonObject();
            list.add(new SyncPingData(
                SyncJsonUtils.readBoolean(profile, "enabled", true),
                SyncJsonUtils.readString(profile, "username", ""),
                SyncJsonUtils.readString(profile, "server", ""),
                SyncJsonUtils.readString(profile, "password", ""),
                SyncJsonUtils.readInt(profile, "delay", 0)
            ));
        }
        return list;
    }

    private JsonArray toJsonArray(List<SyncPingData> data) {
        JsonArray array = new JsonArray();
        if (data == null) return array;

        for (SyncPingData row : data) {
            JsonObject profile = new JsonObject();
            profile.addProperty("enabled", row.enabled());
            profile.addProperty("username", row.username());
            profile.addProperty("server", row.server());
            profile.addProperty("mode", "LOGIN");
            profile.addProperty("password", row.payload());
            profile.addProperty("delay", row.delay());
            array.add(profile);
        }

        return array;
    }

    private HttpRequest buildSyncStreamRequest(
        String baseUrl,
        String deviceId,
        String token,
        String signingKey,
        int timeoutSec,
        long knownRevision,
        int waitMs,
        boolean useLegacyPath
    ) {
        URI uri = buildSyncStreamUri(baseUrl, deviceId, knownRevision, waitMs, useLegacyPath);
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(Math.max(10, timeoutSec)))
            .header("Accept", "text/event-stream")
            .header("Cache-Control", "no-cache")
            .header("User-Agent", "Devils-PingSync/1.0")
            .GET();
        if (token != null && !token.isBlank()) builder.header("Authorization", "Bearer " + token.trim());
        SyncJsonUtils.applySignedHeaders(builder, uri, "GET", "", signingKey);
        return builder.build();
    }

    private URI buildSyncStreamUri(String baseUrl, String deviceId, long knownRevision, int waitMs, boolean useLegacyPath) {
        String query =
            "deviceId=" + PingFormattingUtils.encodeQueryValue(deviceId)
                + "&module=" + PingConstants.MODULE_NAMESPACE
                + "&knownRevision=" + knownRevision
                + "&waitMs=" + Math.max(50, waitMs);
        String path = useLegacyPath ? PingConstants.SYNC_STREAM_PATH_LEGACY : PingConstants.SYNC_STREAM_PATH;
        return URI.create(baseUrl + path + "?" + query);
    }
}


