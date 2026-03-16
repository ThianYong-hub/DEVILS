package com.example.addon.modules.xaerosync;

import com.example.addon.modules.XaeroSync;
import com.example.addon.modules.sync.SyncJsonUtils;
import com.google.gson.JsonObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;

public final class XaeroSyncStreamController {
    private final XaeroSync module;
    private final XaeroSyncCodec codec;
    private final XaeroSyncProblemTracker problemTracker;

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

    public XaeroSyncStreamController(XaeroSync module, XaeroSyncCodec codec, XaeroSyncProblemTracker problemTracker) {
        this.module = module;
        this.codec = codec;
        this.problemTracker = problemTracker;
    }

    public void clear() {
        stopSyncStream();
        syncStreamUseLegacyPath = false;
        syncStreamUnsupported = false;
        syncStreamUnsupportedUntilMs = 0;
    }

    public void onGameLeft() {
        stopSyncStream();
    }

    public boolean isConnected() {
        return syncStreamConnected;
    }

    public boolean isConnecting() {
        return syncStreamConnecting;
    }

    public void ensureSyncStream(String baseUrl, String deviceId, String token, String signingKey, int timeoutSec, int waitMs, long knownRevision) {
        long now = System.currentTimeMillis();
        if (syncStreamUnsupported && syncStreamUnsupportedUntilMs > now) return;
        if (syncStreamUnsupported && syncStreamUnsupportedUntilMs <= now) {
            syncStreamUnsupported = false;
            syncStreamUnsupportedUntilMs = 0;
        }

        String tokenValue = token == null ? "" : token.trim();
        String connectionKey = baseUrl
            + "|" + deviceId
            + "|" + timeoutSec
            + "|" + waitMs
            + "|" + (syncStreamUseLegacyPath ? "legacy" : "v1")
            + "|" + Integer.toHexString(tokenValue.hashCode())
            + "|" + Integer.toHexString((signingKey == null ? "" : signingKey).hashCode());
        if ((syncStreamConnected || syncStreamConnecting) && !connectionKey.equals(syncStreamConnectionKey)) stopSyncStream();
        if (syncStreamConnected || syncStreamConnecting) return;
        if (syncStreamReconnectAtMs > System.currentTimeMillis()) return;

        syncStreamStopRequested = false;
        syncStreamConnecting = true;
        syncStreamConnectionKey = connectionKey;

        long safeKnownRevision = Math.max(-1, knownRevision);
        int safeWaitMs = Math.max(50, waitMs);
        int requestTimeout = Math.max(10, timeoutSec + 30);
        boolean useLegacyPath = syncStreamUseLegacyPath;
        syncStreamFuture = CompletableFuture.runAsync(() -> runSyncStreamLoop(baseUrl, deviceId, tokenValue, signingKey, requestTimeout, safeKnownRevision, safeWaitMs, useLegacyPath));
    }

    public boolean consumePendingPullSignal(long lastKnownSyncRevision) {
        if (!syncStreamUpdatePending) return false;
        syncStreamUpdatePending = false;
        return syncStreamPendingRevision < 0 || syncStreamPendingRevision > lastKnownSyncRevision;
    }

    public void stopSyncStream() {
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

    private void runSyncStreamLoop(String baseUrl, String deviceId, String token, String signingKey, int timeoutSec, long knownRevision, int waitMs, boolean useLegacyPath) {
        String streamError = null;
        try {
            HttpResponse<InputStream> response = codec.sendSyncStreamRequest(baseUrl, deviceId, token, signingKey, timeoutSec, knownRevision, waitMs, useLegacyPath);
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
                    syncStreamUnsupportedUntilMs = System.currentTimeMillis() + XaeroSyncConstants.SYNC_STREAM_UNSUPPORTED_BACKOFF_MS;
                    throw new IllegalStateException("stream-unsupported:http-404");
                }
                throw new IllegalStateException(SyncJsonUtils.parseHttpError(response.statusCode(), errorBody));
            }

            module.client().execute(() -> {
                syncStreamConnecting = false;
                syncStreamConnected = true;
                syncStreamReconnectAtMs = 0;
                module.logSyncInternal("XaeroSync stream connected.");
            });

            try (InputStream input = response.body(); BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
                String line;
                StringBuilder data = new StringBuilder();
                while (!syncStreamStopRequested && (line = reader.readLine()) != null) {
                    if (line.startsWith("data:")) {
                        String row = line.length() > 5 ? line.substring(5).stripLeading() : "";
                        if (data.length() > 0) data.append('\n');
                        data.append(row);
                        continue;
                    }
                    if (!line.isBlank()) continue;
                    if (data.length() > 0) processSyncStreamEvent(data.toString());
                    data.setLength(0);
                }
            }
        } catch (Throwable throwable) {
            if (!syncStreamStopRequested) streamError = SyncJsonUtils.formatSyncException("stream-error", throwable, XaeroSyncConstants.SYNC_ERROR_DETAIL_MAX);
        } finally {
            String finalStreamError = streamError;
            module.client().execute(() -> {
                syncStreamConnected = false;
                syncStreamConnecting = false;
                syncStreamFuture = null;
                if (!syncStreamStopRequested) {
                    SyncErrorType type = problemTracker.classifySyncError(finalStreamError);
                    long reconnectDelay = switch (type) {
                        case AUTH -> XaeroSyncConstants.SYNC_AUTH_BACKOFF_MS;
                        case CONFIG -> XaeroSyncConstants.SYNC_CONFIG_BACKOFF_MS;
                        case CRYPTO -> XaeroSyncConstants.SYNC_CRYPTO_BACKOFF_MS;
                        case NETWORK -> XaeroSyncConstants.SYNC_NETWORK_BACKOFF_MS;
                        case OTHER -> XaeroSyncConstants.SYNC_STREAM_RECONNECT_MS;
                    };
                    long reconnectAt = System.currentTimeMillis() + reconnectDelay;
                    if (syncStreamUnsupported && syncStreamUnsupportedUntilMs > reconnectAt) reconnectAt = syncStreamUnsupportedUntilMs;
                    syncStreamReconnectAtMs = reconnectAt;
                    if (finalStreamError != null && !finalStreamError.isBlank()) {
                        if (type == SyncErrorType.AUTH || type == SyncErrorType.CONFIG || type == SyncErrorType.CRYPTO) {
                            problemTracker.logProblem(module, "stream disconnected", finalStreamError);
                        } else {
                            module.logSyncInternal("XaeroSync stream disconnected: %s", finalStreamError);
                        }
                    }
                } else {
                    syncStreamReconnectAtMs = 0;
                }
            });
        }
    }

    private void processSyncStreamEvent(String data) {
        if (data == null || data.isBlank()) return;
        JsonObject json = SyncJsonUtils.parseJsonObject(data);
        if (json == null) return;

        long revision = SyncJsonUtils.readLong(json, "revision", -1);
        if (revision > syncStreamPendingRevision) {
            syncStreamPendingRevision = revision;
            syncStreamUpdatePending = true;
            module.client().execute(module::handleSyncTickPublic);
        }
    }
}


