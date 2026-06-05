package com.devils.addon.modules.chesttracker;

import com.devils.addon.modules.chesttracker.ChestTrackerSupport.SyncRuntimeConfig;
import com.devils.addon.shared.sync.SyncJsonUtils;
import com.google.gson.JsonObject;
import net.minecraft.client.MinecraftClient;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

public final class ChestTrackerStreamController {
    private final HttpClient http;
    private final String moduleName;
    private final String streamPath;
    private final String legacyStreamPath;
    private final long reconnectMs;
    private final long unsupportedBackoffMs;
    private final Consumer<String> infoLogger;
    private final BooleanSupplier verboseSupplier;

    private CompletableFuture<Void> streamFuture;
    private volatile boolean streamStop;
    private volatile boolean streamConnected;
    private volatile boolean streamConnecting;
    private volatile boolean streamUpdatePending;
    private volatile long streamPendingRevision = -1;
    private volatile long streamReconnectAtMs;
    private volatile String streamConnectionKey = "";
    private volatile boolean streamUseLegacyPath;
    private volatile boolean streamUnsupported;
    private volatile long streamUnsupportedUntilMs;

    public ChestTrackerStreamController(
        HttpClient http,
        String moduleName,
        String streamPath,
        String legacyStreamPath,
        long reconnectMs,
        long unsupportedBackoffMs,
        Consumer<String> infoLogger,
        BooleanSupplier verboseSupplier
    ) {
        this.http = http;
        this.moduleName = moduleName;
        this.streamPath = streamPath;
        this.legacyStreamPath = legacyStreamPath;
        this.reconnectMs = reconnectMs;
        this.unsupportedBackoffMs = unsupportedBackoffMs;
        this.infoLogger = infoLogger;
        this.verboseSupplier = verboseSupplier;
    }

    public boolean isConnected() {
        return streamConnected;
    }

    public boolean isConnecting() {
        return streamConnecting;
    }

    public boolean consumeStreamSignal(long knownRevision) {
        if (!streamUpdatePending) return false;
        streamUpdatePending = false;
        return streamPendingRevision > knownRevision;
    }

    public void ensureStream(String baseUrl, SyncRuntimeConfig cfg, String namespace, long knownRevision) {
        long now = System.currentTimeMillis();
        if (streamUnsupported && streamUnsupportedUntilMs > now) return;
        if (streamUnsupported && streamUnsupportedUntilMs <= now) {
            streamUnsupported = false;
            streamUnsupportedUntilMs = 0;
        }

        String key = baseUrl
            + "|"
            + namespace
            + "|"
            + cfg.deviceId()
            + "|"
            + cfg.timeoutSec()
            + "|"
            + cfg.streamWaitMs()
            + "|"
            + (streamUseLegacyPath ? "legacy" : "v1")
            + "|"
            + Integer.toHexString(cfg.signingKey().hashCode());
        if ((streamConnected || streamConnecting) && !key.equals(streamConnectionKey)) stop();
        if (streamConnected || streamConnecting || streamReconnectAtMs > System.currentTimeMillis()) return;

        streamStop = false;
        streamConnecting = true;
        streamConnectionKey = key;
        streamFuture = CompletableFuture.runAsync(() -> runStream(baseUrl, cfg, namespace, knownRevision));
    }

    public void stop() {
        streamStop = true;
        streamConnected = false;
        streamConnecting = false;
        streamUpdatePending = false;
        streamPendingRevision = -1;
        streamReconnectAtMs = 0;
        streamConnectionKey = "";

        CompletableFuture<Void> future = streamFuture;
        streamFuture = null;
        if (future != null) future.cancel(true);
    }

    public void reset() {
        streamUseLegacyPath = false;
        streamUnsupported = false;
        streamUnsupportedUntilMs = 0;
        stop();
    }

    private void runStream(String baseUrl, SyncRuntimeConfig cfg, String namespace, long knownRevision) {
        String error = null;
        try {
            String selectedPath = streamUseLegacyPath ? legacyStreamPath : streamPath;
            URI uri = URI.create(baseUrl + selectedPath
                + "?deviceId=" + ChestTrackerSupport.encode(cfg.deviceId())
                + "&module=" + ChestTrackerSupport.encode(moduleName)
                + "&namespace=" + ChestTrackerSupport.encode(namespace)
                + "&knownRevision=" + knownRevision
                + "&waitMs=" + Math.max(1_000, cfg.streamWaitMs()));

            HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(Math.max(10, cfg.timeoutSec() + 25)))
                .header("Accept", "text/event-stream")
                .GET();
            if (!cfg.token().isBlank()) builder.header("Authorization", "Bearer " + cfg.token().trim());
            SyncJsonUtils.applySignedHeaders(builder, uri, "GET", "", cfg.signingKey());

            HttpResponse<InputStream> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                String errorBody = "";
                try (InputStream input = response.body()) {
                    if (input != null) errorBody = new String(input.readNBytes(512), StandardCharsets.UTF_8);
                }
                if (response.statusCode() == 404 && !streamUseLegacyPath) {
                    streamUseLegacyPath = true;
                    throw new IllegalStateException("stream-404-switching-to-legacy");
                }
                if (response.statusCode() == 404 && streamUseLegacyPath) {
                    streamUnsupported = true;
                    streamUnsupportedUntilMs = System.currentTimeMillis() + unsupportedBackoffMs;
                    throw new IllegalStateException("stream-unsupported:http-404");
                }
                throw new IllegalStateException(SyncJsonUtils.parseHttpError(response.statusCode(), errorBody));
            }

            MinecraftClient.getInstance().execute(() -> {
                streamConnecting = false;
                streamConnected = true;
            });

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                String line;
                StringBuilder data = new StringBuilder();
                while (!streamStop && (line = reader.readLine()) != null) {
                    if (line.startsWith("data:")) {
                        if (data.length() > 0) data.append('\n');
                        data.append(line.substring(5).stripLeading());
                        continue;
                    }

                    if (line.isBlank() && data.length() > 0) {
                        JsonObject json = SyncJsonUtils.parseJsonObject(data.toString());
                        long revision = SyncJsonUtils.readLong(json, "revision", -1);
                        if (revision > knownRevision) {
                            streamPendingRevision = revision;
                            streamUpdatePending = true;
                        }
                        data.setLength(0);
                    }
                }
            }
        } catch (Throwable t) {
            if (!streamStop) error = t.getClass().getSimpleName() + ":" + (t.getMessage() == null ? "" : t.getMessage());
        } finally {
            String finalError = error;
            MinecraftClient.getInstance().execute(() -> {
                streamConnected = false;
                streamConnecting = false;
                streamFuture = null;
                if (!streamStop) {
                    if (streamUnsupported && streamUnsupportedUntilMs > System.currentTimeMillis()) {
                        streamReconnectAtMs = streamUnsupportedUntilMs;
                    } else {
                        streamReconnectAtMs = System.currentTimeMillis() + reconnectMs;
                    }
                    if (verboseSupplier.getAsBoolean() && finalError != null) infoLogger.accept("ChestTracker stream disconnected: " + finalError);
                }
            });
        }
    }
}


