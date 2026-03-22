package com.example.addon.modules.ping;

import com.example.addon.modules.Ping;
import com.example.addon.modules.SyncHub;
import com.example.addon.modules.sync.SyncJsonUtils;
import meteordevelopment.meteorclient.systems.modules.Modules;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public final class PingSyncController {
    private final Ping module;
    private final PingMarkerController markerController;
    private final PingSyncCodec codec = new PingSyncCodec();
    private final PingSyncProblemTracker problemTracker = new PingSyncProblemTracker();
    private final PingSyncStreamController streamController;

    private boolean syncInFlight;
    private long lastKnownSyncRevision = -1;
    private long lastSyncPullAttemptMs;
    private String lastSyncedFingerprint = "";
    private String lastSyncStatus = "idle";
    private long lastPushOkLogMs;
    private long lastConflictLogMs;
    private String runtimeSyncDeviceId = "";
    private boolean runtimePingSyncEnabled;
    private boolean syncTickQueued;

    public PingSyncController(Ping module, PingMarkerController markerController) {
        this.module = module;
        this.markerController = markerController;
        this.streamController = new PingSyncStreamController(module, codec, problemTracker);
    }

    public void onDeactivate() {
        streamController.onDeactivate();
        syncTickQueued = false;
    }

    public void onGameJoined() {
        lastSyncPullAttemptMs = 0;
        syncTickQueued = false;
        streamController.onGameJoined();
    }

    public void onGameLeft() {
        streamController.onGameLeft();
        lastSyncPullAttemptMs = 0;
        syncTickQueued = false;
    }

    public String lastSyncStatus() {
        return lastSyncStatus;
    }

    public String currentSyncDeviceId() {
        SyncRuntimeConfig config = resolveSyncRuntimeConfig();
        return config == null ? "" : config.deviceId();
    }

    public void handleSyncTick() {
        SyncRuntimeConfig sync = resolveSyncRuntimeConfig();
        if (sync == null) {
            runtimePingSyncEnabled = false;
            runtimeSyncDeviceId = "";
            streamController.stopSyncStream();
            return;
        }
        if (syncInFlight) {
            syncTickQueued = true;
            return;
        }

        runtimePingSyncEnabled = true;
        runtimeSyncDeviceId = sync.deviceId();
        String baseUrl = codec.normalizeSyncBaseUrl(sync.baseUrl());
        if (baseUrl.isBlank()) {
            lastSyncStatus = "skip:no-base-url";
            streamController.stopSyncStream();
            return;
        }

        String baseUrlValidationError = codec.validateSyncBaseUrl(baseUrl);
        if (baseUrlValidationError != null) {
            lastSyncStatus = "skip:bad-base-url";
            problemTracker.logProblem(module, "invalid base-url", baseUrlValidationError);
            streamController.stopSyncStream();
            return;
        }

        if (!sync.allowHttp() && baseUrl.startsWith("http://")) {
            lastSyncStatus = "skip:http-disabled";
            streamController.stopSyncStream();
            return;
        }
        if (module.client().player == null || module.client().world == null) {
            streamController.stopSyncStream();
            return;
        }

        if (sync.useStream()) {
            streamController.ensureSyncStream(baseUrl, sync.deviceId(), sync.token(), sync.signingKey(), sync.timeoutSec(), sync.streamWaitMs(), lastKnownSyncRevision);
        } else {
            streamController.stopSyncStream();
        }

        if (problemTracker.backoffUntilMs() > System.currentTimeMillis()) return;

        long now = System.currentTimeMillis();
        List<SyncPingData> localSnapshot = markerController.snapshotSyncData(runtimePingSyncEnabled, runtimeSyncDeviceId);
        String localFingerprint = codec.computeFingerprint(localSnapshot);
        boolean localChanged = !localFingerprint.equals(lastSyncedFingerprint);
        boolean streamTriggeredPull = streamController.consumePendingPullSignal(lastKnownSyncRevision);
        boolean shouldBootstrapPull = lastKnownSyncRevision < 0;
        boolean streamFallbackPull = sync.useStream()
            && !streamController.isConnected()
            && !streamController.isConnecting()
            && (now - lastSyncPullAttemptMs) >= PingConstants.PULL_FALLBACK_INTERVAL_MS;
        boolean periodicPull = !sync.useStream() && (now - lastSyncPullAttemptMs) >= PingConstants.PULL_FALLBACK_INTERVAL_MS;
        boolean shouldPull = streamTriggeredPull || shouldBootstrapPull || streamFallbackPull || periodicPull;
        boolean shouldRun = streamTriggeredPull || localChanged || shouldBootstrapPull || streamFallbackPull || periodicPull;
        if (!shouldRun) return;

        lastSyncPullAttemptMs = now;
        syncInFlight = true;
        int pullWaitMs = sync.useStream() ? 0 : sync.streamWaitMs();
        runSyncCycleAsync(
            baseUrl,
            sync.deviceId(),
            sync.token(),
            sync.signingKey(),
            sync.timeoutSec(),
            pullWaitMs,
            sync.encryptionKey(),
            lastKnownSyncRevision,
            localSnapshot,
            localFingerprint,
            localChanged,
            shouldPull
        );
    }

    private void runSyncCycleAsync(
        String baseUrl,
        String deviceId,
        String token,
        String signingKey,
        int timeoutSec,
        int pullWaitMs,
        String encryptionKey,
        long knownRevision,
        List<SyncPingData> localSnapshot,
        String localFingerprint,
        boolean localChanged,
        boolean doPull
    ) {
        CompletableFuture.runAsync(() -> {
            SyncPullResult pullResult = null;
            SyncPushResult pushResult = null;
            boolean remoteApplied = false;
            String error = null;
            List<SyncPingData> effectiveSnapshot = localSnapshot;
            String effectiveFingerprint = localFingerprint;
            long pushBaseRevision = knownRevision;
            boolean pushRequestedByMerge = false;
            boolean localNeedsPush = localChanged;

            if (doPull) {
                try {
                    pullResult = codec.sendPullRequest(baseUrl, deviceId, token, signingKey, timeoutSec, encryptionKey, knownRevision, pullWaitMs);
                    if (!pullResult.ok()) {
                        error = "pull-rejected:" + pullResult.error();
                    } else {
                        if (pullResult.revision() >= 0) pushBaseRevision = pullResult.revision();
                        List<SyncPingData> remoteSnapshot = pullResult.profiles() == null ? List.of() : pullResult.profiles();
                        String remoteFingerprint = codec.computeFingerprint(remoteSnapshot);
                        boolean remoteIsNewer = pullResult.revision() > knownRevision && pullResult.profiles() != null;

                        if (localChanged) {
                            List<SyncPingData> merged = markerController.mergeSnapshots(remoteSnapshot, localSnapshot);
                            String mergedFingerprint = codec.computeFingerprint(merged);
                            if (!mergedFingerprint.equals(remoteFingerprint)) {
                                effectiveSnapshot = merged;
                                effectiveFingerprint = mergedFingerprint;
                                pushRequestedByMerge = true;
                                localNeedsPush = true;
                            } else {
                                effectiveSnapshot = remoteSnapshot;
                                effectiveFingerprint = remoteFingerprint;
                                localNeedsPush = false;
                                if (remoteIsNewer) remoteApplied = true;
                            }
                        } else if (remoteIsNewer) {
                            effectiveSnapshot = remoteSnapshot;
                            effectiveFingerprint = remoteFingerprint;
                            remoteApplied = true;
                        }
                    }
                } catch (Throwable throwable) {
                    error = SyncJsonUtils.formatSyncException("pull-error", throwable, PingConstants.SYNC_ERROR_DETAIL_MAX);
                }
            }

            if (error == null && !remoteApplied && (localNeedsPush || pushRequestedByMerge)) {
                try {
                    pushResult = codec.sendPushRequest(baseUrl, deviceId, token, signingKey, timeoutSec, encryptionKey, pushBaseRevision, effectiveSnapshot);
                    if (pushResult.ok() && pushResult.conflict() && pushResult.profiles() != null && pushResult.revision() >= 0) {
                        List<SyncPingData> conflictMerged = markerController.mergeSnapshots(pushResult.profiles(), localSnapshot);
                        String conflictFingerprint = codec.computeFingerprint(conflictMerged);
                        pushResult = codec.sendPushRequest(baseUrl, deviceId, token, signingKey, timeoutSec, encryptionKey, pushResult.revision(), conflictMerged);
                        effectiveSnapshot = conflictMerged;
                        effectiveFingerprint = conflictFingerprint;
                    }
                } catch (Throwable throwable) {
                    error = SyncJsonUtils.formatSyncException("push-error", throwable, PingConstants.SYNC_ERROR_DETAIL_MAX);
                }
            }

            SyncCycleResult result = new SyncCycleResult(
                pullResult,
                pushResult,
                remoteApplied,
                localNeedsPush,
                effectiveSnapshot,
                effectiveFingerprint,
                error
            );
            module.client().execute(() -> handleSyncCycleResult(result));
        });
    }

    private void handleSyncCycleResult(SyncCycleResult result) {
        syncInFlight = false;
        try {
            if (result.error() != null) {
                lastSyncStatus = result.error();
                problemTracker.logProblem(module, "failed", result.error());
                return;
            }

            if (result.pullResult() != null && result.pullResult().ok() && result.pullResult().revision() >= 0) {
                lastKnownSyncRevision = Math.max(lastKnownSyncRevision, result.pullResult().revision());
            }

            if (result.remoteApplied()) {
                lastSyncStatus = "pull-applied";
                markerController.applySyncedSnapshot(result.snapshot(), runtimePingSyncEnabled, runtimeSyncDeviceId);
                refreshLocalOwnedFingerprint();
                problemTracker.clear();
                return;
            }

            if (result.pushResult() != null) {
                if (result.pushResult().ok() && result.pushResult().applied()) {
                    if (result.pushResult().revision() >= 0) {
                        lastKnownSyncRevision = Math.max(lastKnownSyncRevision, result.pushResult().revision());
                    }
                    lastSyncStatus = "push-ok";
                    long now = System.currentTimeMillis();
                    if (now - lastPushOkLogMs >= PingConstants.PUSH_OK_LOG_COOLDOWN_MS) {
                        module.logSyncInternal("Ping sync push ok (rev=%d).", lastKnownSyncRevision);
                        lastPushOkLogMs = now;
                    }
                    markerController.applySyncedSnapshot(result.snapshot(), runtimePingSyncEnabled, runtimeSyncDeviceId);
                    refreshLocalOwnedFingerprint();
                    problemTracker.clear();
                    return;
                }

                if (result.pushResult().ok() && result.pushResult().conflict()) {
                    lastSyncStatus = "push-conflict";
                    long now = System.currentTimeMillis();
                    if (now - lastConflictLogMs >= PingConstants.PUSH_OK_LOG_COOLDOWN_MS) {
                        module.logSyncInternal("Ping sync conflict handled by merge.");
                        lastConflictLogMs = now;
                    }
                    markerController.applySyncedSnapshot(result.snapshot(), runtimePingSyncEnabled, runtimeSyncDeviceId);
                    refreshLocalOwnedFingerprint();
                    problemTracker.clear();
                    return;
                }

                if (result.pushResult().ok()) {
                    lastSyncStatus = "push-rejected:" + result.pushResult().error();
                    problemTracker.logProblem(module, "push rejected", result.pushResult().error());
                    return;
                }
            }

            if (!result.localChanged()) {
                refreshLocalOwnedFingerprint();
                lastSyncStatus = "noop";
                problemTracker.clear();
                return;
            }

            lastSyncStatus = "local-change-pending";
        } finally {
            if (syncTickQueued) {
                syncTickQueued = false;
                handleSyncTick();
            }
        }
    }

    private void refreshLocalOwnedFingerprint() {
        List<SyncPingData> localSnapshot = markerController.snapshotSyncData(runtimePingSyncEnabled, runtimeSyncDeviceId);
        lastSyncedFingerprint = codec.computeFingerprint(localSnapshot);
    }

    private SyncRuntimeConfig resolveSyncRuntimeConfig() {
        Modules modules = Modules.get();
        if (modules == null) return null;

        SyncHub syncHub = modules.get(SyncHub.class);
        if (syncHub == null || !syncHub.isFeatureEnabled(SyncHub.SyncFeature.PING)) return null;

        String deviceId = syncHub.getOrCreateDeviceId();
        if (deviceId.isBlank()) return null;
        String encryptionKey = syncHub.getEncryptionKeyMaterial();
        if (encryptionKey.isBlank()) return null;
        String signingKey = syncHub.getRequestSigningKey();
        if (signingKey.isBlank()) return null;

        return new SyncRuntimeConfig(
            syncHub.getBaseUrl(),
            syncHub.getToken(),
            deviceId,
            true,
            syncHub.allowHttp(),
            Math.max(3, syncHub.requestTimeoutSec()),
            Math.max(50, syncHub.streamWaitMs()),
            encryptionKey,
            signingKey
        );
    }
}



