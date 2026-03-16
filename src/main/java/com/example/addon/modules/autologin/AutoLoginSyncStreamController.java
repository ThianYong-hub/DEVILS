package com.example.addon.modules.autologin;

import com.example.addon.modules.sync.SyncJsonUtils;
import com.google.gson.JsonObject;
import net.minecraft.client.MinecraftClient;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;

public final class AutoLoginSyncStreamController {
    private static final long SYNC_AUTH_BACKOFF_MS = 30_000;
    private static final long SYNC_NETWORK_BACKOFF_MS = 10_000;
    private static final long SYNC_CONFIG_BACKOFF_MS = 30_000;
    private static final long SYNC_STREAM_RECONNECT_MS = 5_000;
    private static final long SYNC_STREAM_UNSUPPORTED_BACKOFF_MS = 300_000;
    private static final int SYNC_ERROR_DETAIL_MAX = 120;

    private final AutoLoginSyncCodec codec;
    private final BooleanSupplier verboseSupplier;
    private final Consumer<String> infoLogger;
    private final BiConsumer<String, String> problemLogger;

    private CompletableFuture<Void> syncStreamFuture;
    private volatile boolean syncStreamStopRequested;
    private volatile boolean syncStreamConnecting;
    private volatile boolean syncStreamConnected;
    private volatile boolean syncStreamUpdatePending;
    private volatile long syncStreamPendingRevision = -1;
    private volatile long syncStreamReconnectAtMs;
    private volatile String syncStreamConnectionKey = "";
    private volatile boolean syncStreamUseLegacyPath;
    private volatile boolean syncStreamUnsupported;
    private volatile long syncStreamUnsupportedUntilMs;

    public AutoLoginSyncStreamController(AutoLoginSyncCodec codec, BooleanSupplier verboseSupplier, Consumer<String> infoLogger, BiConsumer<String, String> problemLogger) {
        this.codec = codec;
        this.verboseSupplier = verboseSupplier;
        this.infoLogger = infoLogger;
        this.problemLogger = problemLogger;
    }

    public boolean isConnected() { return syncStreamConnected; }
    public boolean isConnecting() { return syncStreamConnecting; }
    public boolean isPending() { return syncStreamUpdatePending; }
    public long pendingRevision() { return syncStreamPendingRevision; }

    public boolean consumePendingPullSignal(long knownRevision) {
        if (!syncStreamUpdatePending) return false;
        syncStreamUpdatePending = false;
        return syncStreamPendingRevision < 0 || syncStreamPendingRevision > knownRevision;
    }

    public void ensureStream(String baseUrl, String deviceId, String token, String signingKey, int timeoutSec, int waitMs, long knownRevision, Function<String, String> writerFormatter) {
        long now = System.currentTimeMillis();
        if (syncStreamUnsupported && syncStreamUnsupportedUntilMs > now) return;
        if (syncStreamUnsupported && syncStreamUnsupportedUntilMs <= now) {
            syncStreamUnsupported = false;
            syncStreamUnsupportedUntilMs = 0;
        }

        String tokenValue = token == null ? "" : token.trim();
        String connectionKey = baseUrl + "|" + deviceId + "|" + timeoutSec + "|" + waitMs + "|" + (syncStreamUseLegacyPath ? "legacy" : "v1") + "|" + Integer.toHexString(tokenValue.hashCode()) + "|" + Integer.toHexString((signingKey == null ? "" : signingKey).hashCode());
        if ((syncStreamConnected || syncStreamConnecting) && !connectionKey.equals(syncStreamConnectionKey)) stop();
        if (syncStreamConnected || syncStreamConnecting || syncStreamReconnectAtMs > System.currentTimeMillis()) return;

        syncStreamStopRequested = false;
        syncStreamConnecting = true;
        syncStreamConnectionKey = connectionKey;
        int safeWaitMs = Math.max(1_000, waitMs);
        int requestTimeout = Math.max(10, timeoutSec + 30);
        syncStreamFuture = CompletableFuture.runAsync(() -> runStream(baseUrl, deviceId, tokenValue, signingKey, requestTimeout, Math.max(-1, knownRevision), safeWaitMs, writerFormatter));
    }

    public void stop() {
        syncStreamStopRequested = true;
        syncStreamConnecting = false;
        syncStreamConnected = false;
        syncStreamUpdatePending = false;
        syncStreamPendingRevision = -1;
        syncStreamReconnectAtMs = 0;
        syncStreamConnectionKey = "";
        CompletableFuture<Void> future = syncStreamFuture;
        syncStreamFuture = null;
        if (future != null) future.cancel(true);
    }

    public void reset() {
        syncStreamUseLegacyPath = false;
        syncStreamUnsupported = false;
        syncStreamUnsupportedUntilMs = 0;
        stop();
    }

    private void runStream(String baseUrl, String deviceId, String token, String signingKey, int timeoutSec, long knownRevision, int waitMs, Function<String, String> writerFormatter) {
        String streamError = null;
        try {
            HttpResponse<InputStream> response = HttpClient.newHttpClient().send(
                codec.buildSyncStreamRequest(baseUrl, deviceId, token, signingKey, timeoutSec, knownRevision, waitMs, syncStreamUseLegacyPath),
                HttpResponse.BodyHandlers.ofInputStream()
            );
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                String errorBody = "";
                try (InputStream input = response.body()) {
                    if (input != null) errorBody = new String(input.readNBytes(512), StandardCharsets.UTF_8);
                }
                if (response.statusCode() == 404 && !syncStreamUseLegacyPath) {
                    syncStreamUseLegacyPath = true;
                    throw new IllegalStateException("stream-404-switching-to-legacy");
                }
                if (response.statusCode() == 404 && syncStreamUseLegacyPath) {
                    syncStreamUnsupported = true;
                    syncStreamUnsupportedUntilMs = System.currentTimeMillis() + SYNC_STREAM_UNSUPPORTED_BACKOFF_MS;
                    throw new IllegalStateException("stream-unsupported:http-404");
                }
                throw new IllegalStateException(codec.parseHttpError(response.statusCode(), errorBody));
            }

            MinecraftClient.getInstance().execute(() -> {
                syncStreamConnecting = false;
                syncStreamConnected = true;
                syncStreamReconnectAtMs = 0;
                if (verboseSupplier.getAsBoolean()) infoLogger.accept("AutoLogin sync stream connected.");
            });

            try (InputStream input = response.body(); BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
                String line;
                String eventType = "";
                StringBuilder data = new StringBuilder();
                while (!syncStreamStopRequested && (line = reader.readLine()) != null) {
                    if (line.startsWith("event:")) { eventType = line.substring(6).trim(); continue; }
                    if (line.startsWith("data:")) {
                        String row = line.length() > 5 ? line.substring(5).stripLeading() : "";
                        if (data.length() > 0) data.append('\n');
                        data.append(row);
                        continue;
                    }
                    if (!line.isEmpty()) continue;
                    if (data.length() == 0) { eventType = ""; continue; }
                    JsonObject json = SyncJsonUtils.parseJsonObject(data.toString());
                    long revision = SyncJsonUtils.readLong(json, "revision", -1);
                    String writer = SyncJsonUtils.readString(json, "lastWriter", SyncJsonUtils.readString(json, "last_writer", ""));
                    if (revision > knownRevision) {
                        syncStreamPendingRevision = revision;
                        syncStreamUpdatePending = true;
                        if (verboseSupplier.getAsBoolean() && revision > knownRevision + 1) {
                            String type = eventType == null || eventType.isBlank() ? "<none>" : eventType;
                            infoLogger.accept(String.format(Locale.ROOT, "AutoLogin sync stream catch-up %s (rev=%d, by=%s).", type, revision, writerFormatter.apply(writer)));
                        }
                    }
                    eventType = "";
                    data.setLength(0);
                }
            }
        } catch (Throwable t) {
            if (!syncStreamStopRequested) streamError = SyncJsonUtils.formatSyncException("stream-error", t, SYNC_ERROR_DETAIL_MAX);
        } finally {
            String finalStreamError = streamError;
            MinecraftClient.getInstance().execute(() -> {
                syncStreamConnected = false;
                syncStreamConnecting = false;
                syncStreamFuture = null;
                if (!syncStreamStopRequested) {
                    long reconnectDelay = classifyReconnectDelay(finalStreamError);
                    long reconnectAt = System.currentTimeMillis() + reconnectDelay;
                    if (syncStreamUnsupported && syncStreamUnsupportedUntilMs > reconnectAt) reconnectAt = syncStreamUnsupportedUntilMs;
                    syncStreamReconnectAtMs = reconnectAt;
                    if (finalStreamError != null && !finalStreamError.isBlank()) problemLogger.accept("stream disconnected", finalStreamError);
                }
            });
        }
    }

    private static long classifyReconnectDelay(String error) {
        String normalized = error == null ? "" : error.toLowerCase(Locale.ROOT);
        if (normalized.contains("401") || normalized.contains("unauthorized") || normalized.contains("forbidden")) return SYNC_AUTH_BACKOFF_MS;
        if (normalized.contains("undefined scheme") || normalized.contains("bad-base-url") || normalized.contains("unsupported scheme") || normalized.contains("uri with undefined host") || normalized.contains("404") || normalized.contains("not-found") || normalized.contains("not_found")) return SYNC_CONFIG_BACKOFF_MS;
        if (normalized.contains("certificateexception") || normalized.contains("sslhandshakeexception") || normalized.contains("pkix") || normalized.contains("subject alternative names")) return SYNC_CONFIG_BACKOFF_MS;
        if (normalized.contains("timeout") || normalized.contains("connect") || normalized.contains("ioexception") || normalized.contains("eofexception") || normalized.contains("eof reached while reading") || normalized.contains("unexpected end of file") || normalized.contains("connection") || normalized.contains("refused") || normalized.contains("reset") || normalized.contains("unreachable") || normalized.contains("unknownhost") || normalized.contains("noroutetohost")) return SYNC_NETWORK_BACKOFF_MS;
        return SYNC_STREAM_RECONNECT_MS;
    }
}


