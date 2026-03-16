package com.example.addon.modules.chesttracker;

import com.example.addon.modules.chesttracker.ChestTrackerSupport.Row;
import com.example.addon.modules.chesttracker.ChestTrackerSupport.SyncPull;
import com.example.addon.modules.chesttracker.ChestTrackerSupport.SyncPush;
import com.example.addon.modules.chesttracker.ChestTrackerSupport.SyncRuntimeConfig;
import com.example.addon.modules.sync.SyncJsonUtils;
import com.example.addon.util.SyncCrypto;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public final class ChestTrackerSyncApi {
    private final HttpClient http;
    private final String moduleName;
    private final String pullPath;
    private final String pushPath;

    public ChestTrackerSyncApi(HttpClient http, String moduleName, String pullPath, String pushPath) {
        this.http = http;
        this.moduleName = moduleName;
        this.pullPath = pullPath;
        this.pushPath = pushPath;
    }

    public SyncPull pull(String baseUrl, SyncRuntimeConfig cfg, String namespace, long knownRevision) {
        try {
            JsonObject payload = new JsonObject();
            payload.addProperty("deviceId", cfg.deviceId());
            payload.addProperty("knownRevision", knownRevision);
            payload.addProperty("module", moduleName);
            payload.addProperty("namespace", namespace);
            HttpResponse<String> response = http.send(
                buildRequest(baseUrl + pullPath, cfg, payload.toString()),
                HttpResponse.BodyHandlers.ofString()
            );
            return parsePull(response, cfg.encryptionKey());
        } catch (Throwable t) {
            return new SyncPull(false, -1, List.of(), t.getClass().getSimpleName());
        }
    }

    public SyncPush push(String baseUrl, SyncRuntimeConfig cfg, String namespace, long baseRevision, List<Row> rows) {
        try {
            JsonObject payload = new JsonObject();
            payload.addProperty("deviceId", cfg.deviceId());
            payload.addProperty("baseRevision", baseRevision);
            payload.addProperty("module", moduleName);
            payload.addProperty("namespace", namespace);
            payload.add("profiles", SyncCrypto.encryptProfiles(toJsonArray(rows), cfg.encryptionKey(), moduleName));
            HttpResponse<String> response = http.send(
                buildRequest(baseUrl + pushPath, cfg, payload.toString()),
                HttpResponse.BodyHandlers.ofString()
            );
            return parsePush(response);
        } catch (Throwable t) {
            return new SyncPush(false, false, -1, t.getClass().getSimpleName());
        }
    }

    private SyncPull parsePull(HttpResponse<String> response, String encryptionKey) {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            return new SyncPull(false, -1, List.of(), "http-" + response.statusCode());
        }

        JsonObject json = SyncJsonUtils.parseJsonObject(response.body());
        if (json == null) return new SyncPull(false, -1, List.of(), "bad-json");

        boolean ok = SyncJsonUtils.readBoolean(json, "ok", true);
        long revision = SyncJsonUtils.readLong(json, "revision", -1);
        try {
            return new SyncPull(ok, revision, readRows(json, encryptionKey), SyncJsonUtils.readString(json, "error", ""));
        } catch (Exception decryptError) {
            return new SyncPull(false, revision, List.of(), "decrypt:" + decryptError.getClass().getSimpleName());
        }
    }

    private SyncPush parsePush(HttpResponse<String> response) {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            return new SyncPush(false, false, -1, "http-" + response.statusCode());
        }

        JsonObject json = SyncJsonUtils.parseJsonObject(response.body());
        if (json == null) return new SyncPush(false, false, -1, "bad-json");

        boolean ok = SyncJsonUtils.readBoolean(json, "ok", true);
        boolean applied = SyncJsonUtils.readBoolean(json, "applied", ok);
        long revision = SyncJsonUtils.readLong(json, "revision", -1);
        return new SyncPush(ok, applied, revision, SyncJsonUtils.readString(json, "error", ""));
    }

    private List<Row> readRows(JsonObject json, String encryptionKey) throws Exception {
        JsonArray array = SyncJsonUtils.readArray(json, "profiles");
        if (array == null) return List.of();
        array = SyncCrypto.decryptProfiles(array, encryptionKey, moduleName);

        ArrayList<Row> rows = new ArrayList<>();
        for (int i = 0; i < array.size(); i++) {
            if (!array.get(i).isJsonObject()) continue;
            JsonObject profile = array.get(i).getAsJsonObject();
            rows.add(new Row(
                SyncJsonUtils.readBoolean(profile, "enabled", true),
                SyncJsonUtils.readString(profile, "username", ""),
                SyncJsonUtils.readString(profile, "server", ""),
                SyncJsonUtils.readString(profile, "password", ""),
                SyncJsonUtils.readInt(profile, "delay", 0)
            ));
        }
        return rows;
    }

    private JsonArray toJsonArray(List<Row> rows) {
        JsonArray array = new JsonArray();
        for (Row row : rows) {
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

    private HttpRequest buildRequest(String url, SyncRuntimeConfig cfg, String body) {
        URI uri = URI.create(url);
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(Math.max(3, cfg.timeoutSec())))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json");
        if (!cfg.token().isBlank()) builder.header("Authorization", "Bearer " + cfg.token().trim());
        SyncJsonUtils.applySignedHeaders(builder, uri, "POST", body, cfg.signingKey());
        return builder.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
    }
}


