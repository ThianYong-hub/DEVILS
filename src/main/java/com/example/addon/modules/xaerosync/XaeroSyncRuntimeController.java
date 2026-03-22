package com.example.addon.modules.xaerosync;

import com.example.addon.modules.SyncHub;
import com.example.addon.modules.XaeroSync;
import meteordevelopment.meteorclient.systems.modules.Modules;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public final class XaeroSyncRuntimeController {
    private final XaeroSync module;
    private final XaeroSyncDebugController debugController;
    private final XaeroPresenceStore presenceStore;
    private final XaeroSyncCodec codec;
    private final XaeroSyncProblemTracker problemTracker;
    private final XaeroSyncStreamController streamController;

    private int syncInFlightCycles;
    private long lastKnownSyncRevision = -1;
    private long lastSyncPullAttemptMs;
    private String lastSyncedFingerprint = "";
    private String lastSyncStatus = "idle";
    private String runtimeSyncDeviceId = "";
    private boolean syncTickQueued;

    public XaeroSyncRuntimeController(XaeroSync module, XaeroSyncDebugController debugController, XaeroPresenceStore presenceStore) {
        this.module = module;
        this.debugController = debugController;
        this.presenceStore = presenceStore;
        this.codec = new XaeroSyncCodec();
        this.problemTracker = new XaeroSyncProblemTracker();
        this.streamController = new XaeroSyncStreamController(module, codec, problemTracker);
    }

    public String lastSyncStatus() {
        return lastSyncStatus;
    }

    public void clearAll() {
        streamController.clear();
        presenceStore.clear();
        problemTracker.clear();
        debugController.clear();

        syncInFlightCycles = 0;
        lastKnownSyncRevision = -1;
        lastSyncPullAttemptMs = 0;
        lastSyncedFingerprint = "";
        lastSyncStatus = "idle";
        runtimeSyncDeviceId = "";
        syncTickQueued = false;
    }

    public void handleSyncTick() {
        debugController.configureWaypointDebugBridge();

        SyncRuntimeConfig sync = resolveSyncRuntimeConfig();
        if (sync == null) {
            debugController.debugRuntimeUnavailable(explainSyncRuntimeUnavailable());
            clearRuntimeStateKeepingUi();
            presenceStore.applyXaeroPresenceSnapshot();
            return;
        }

        debugController.clearRuntimeUnavailableReason();
        if (syncInFlightCycles >= XaeroSyncConstants.MAX_PARALLEL_SYNC_CYCLES) {
            syncTickQueued = true;
            presenceStore.applyXaeroPresenceSnapshot();
            return;
        }

        runtimeSyncDeviceId = sync.deviceId();
        debugController.debugRuntimeSnapshot(
            codec.normalizeSyncBaseUrl(sync.baseUrl())
                + "|stream=" + sync.useStream()
                + "|http=" + sync.allowHttp()
                + "|timeout=" + sync.timeoutSec()
                + "|waitMs=" + sync.streamWaitMs()
        );

        String baseUrl = codec.normalizeSyncBaseUrl(sync.baseUrl());
        if (baseUrl.isBlank()) {
            lastSyncStatus = "skip:no-base-url";
            debugController.debugPipeline("sync skipped: base URL is blank.");
            streamController.stopSyncStream();
            presenceStore.applyXaeroPresenceSnapshot();
            return;
        }

        String baseUrlValidationError = codec.validateSyncBaseUrl(baseUrl);
        if (baseUrlValidationError != null) {
            lastSyncStatus = "skip:bad-base-url";
            problemTracker.logProblem(module, "invalid base-url", baseUrlValidationError);
            debugController.debugPipeline("sync skipped: invalid base URL (%s).", baseUrlValidationError);
            streamController.stopSyncStream();
            presenceStore.applyXaeroPresenceSnapshot();
            return;
        }

        if (!sync.allowHttp() && baseUrl.startsWith("http://")) {
            lastSyncStatus = "skip:http-disabled";
            debugController.debugPipeline("sync skipped: HTTP is disabled while base URL is HTTP.");
            streamController.stopSyncStream();
            presenceStore.applyXaeroPresenceSnapshot();
            return;
        }

        if (module.client().player == null || module.client().world == null) {
            debugController.debugPipeline("sync skipped: local player/world not ready.");
            streamController.stopSyncStream();
            presenceStore.applyXaeroPresenceSnapshot();
            return;
        }

        if (sync.useStream()) {
            streamController.ensureSyncStream(
                baseUrl,
                sync.deviceId(),
                sync.token(),
                sync.signingKey(),
                sync.timeoutSec(),
                sync.streamWaitMs(),
                lastKnownSyncRevision
            );
        } else {
            streamController.stopSyncStream();
        }

        if (problemTracker.backoffUntilMs() > System.currentTimeMillis()) {
            presenceStore.applyXaeroPresenceSnapshot();
            return;
        }

        long now = System.currentTimeMillis();
        List<SyncXaeroData> localSnapshot = presenceStore.snapshotSyncData(runtimeSyncDeviceId);
        String localFingerprint = codec.computeFingerprint(localSnapshot);
        boolean localChanged = !localFingerprint.equals(lastSyncedFingerprint);
        boolean streamTriggeredPull = streamController.consumePendingPullSignal(lastKnownSyncRevision);

        boolean shouldBootstrapPull = lastKnownSyncRevision < 0;
        boolean streamFallbackPull = sync.useStream()
            && !streamController.isConnected()
            && !streamController.isConnecting()
            && (now - lastSyncPullAttemptMs) >= XaeroSyncConstants.PULL_FALLBACK_INTERVAL_MS;
        boolean periodicPull = !sync.useStream() && (now - lastSyncPullAttemptMs) >= XaeroSyncConstants.PULL_FALLBACK_INTERVAL_MS;
        boolean shouldPull = streamTriggeredPull || shouldBootstrapPull || streamFallbackPull || periodicPull;
        boolean shouldRun = streamTriggeredPull || localChanged || shouldBootstrapPull || streamFallbackPull || periodicPull;
        if (!shouldRun) {
            presenceStore.applyXaeroPresenceSnapshot();
            return;
        }

        lastSyncPullAttemptMs = now;
        syncInFlightCycles++;
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

    private void clearRuntimeStateKeepingUi() {
        streamController.clear();
        presenceStore.clear();
        problemTracker.clear();
        debugController.resetRuntimeSnapshots();

        syncInFlightCycles = 0;
        lastKnownSyncRevision = -1;
        lastSyncPullAttemptMs = 0;
        lastSyncedFingerprint = "";
        runtimeSyncDeviceId = "";
        syncTickQueued = false;
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
        List<SyncXaeroData> localSnapshot,
        String localFingerprint,
        boolean localChanged,
        boolean doPull
    ) {
        CompletableFuture.runAsync(() -> {
            SyncPullResult pullResult = null;
            SyncPushResult pushResult = null;
            boolean remoteApplied = false;
            String error = null;

            List<SyncXaeroData> effectiveSnapshot = localSnapshot;
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

                        List<SyncXaeroData> remoteSnapshot = pullResult.profiles() == null ? List.of() : pullResult.profiles();
                        String remoteFingerprint = codec.computeFingerprint(remoteSnapshot);
                        boolean remoteIsNewer = pullResult.revision() > knownRevision && pullResult.profiles() != null;

                        if (localChanged) {
                            List<SyncXaeroData> merged = presenceStore.mergeSnapshots(remoteSnapshot, localSnapshot);
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
                    error = com.example.addon.modules.sync.SyncJsonUtils.formatSyncException("pull-error", throwable, XaeroSyncConstants.SYNC_ERROR_DETAIL_MAX);
                }
            }

            if (error == null && !remoteApplied && (localNeedsPush || pushRequestedByMerge)) {
                try {
                    pushResult = codec.sendPushRequest(baseUrl, deviceId, token, signingKey, timeoutSec, encryptionKey, pushBaseRevision, effectiveSnapshot);
                    if (pushResult.ok() && pushResult.conflict() && pushResult.profiles() != null && pushResult.revision() >= 0) {
                        // Fast conflict retry for presence sync: re-send only our local row at fresh revision.
                        pushResult = codec.sendPushRequest(baseUrl, deviceId, token, signingKey, timeoutSec, encryptionKey, pushResult.revision(), localSnapshot);
                        effectiveSnapshot = localSnapshot;
                        effectiveFingerprint = localFingerprint;
                    }
                } catch (Throwable throwable) {
                    error = com.example.addon.modules.sync.SyncJsonUtils.formatSyncException("push-error", throwable, XaeroSyncConstants.SYNC_ERROR_DETAIL_MAX);
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
        if (syncInFlightCycles > 0) syncInFlightCycles--;
        try {
            if (result.error() != null) {
                lastSyncStatus = result.error();
                problemTracker.logProblem(module, "failed", result.error());
                presenceStore.applyXaeroPresenceSnapshot();
                return;
            }

            if (result.pullResult() != null && result.pullResult().ok() && result.pullResult().revision() >= 0) {
                lastKnownSyncRevision = Math.max(lastKnownSyncRevision, result.pullResult().revision());
            }

            if (result.remoteApplied()) {
                lastSyncStatus = "pull-applied";
                presenceStore.applySyncedSnapshot(result.snapshot());
                lastSyncedFingerprint = result.snapshotFingerprint();
                problemTracker.clear();
                presenceStore.applyXaeroPresenceSnapshot();
                return;
            }

            if (result.pushResult() != null) {
                if (result.pushResult().ok() && result.pushResult().applied()) {
                    if (result.pushResult().revision() >= 0) {
                        lastKnownSyncRevision = Math.max(lastKnownSyncRevision, result.pushResult().revision());
                    }
                    lastSyncStatus = "push-ok";
                    presenceStore.applySyncedSnapshot(result.snapshot());
                    lastSyncedFingerprint = result.snapshotFingerprint();
                    problemTracker.clear();
                    presenceStore.applyXaeroPresenceSnapshot();
                    return;
                }

                if (result.pushResult().ok() && result.pushResult().conflict()) {
                    lastSyncStatus = "push-conflict";
                    presenceStore.applySyncedSnapshot(result.snapshot());
                    lastSyncedFingerprint = result.snapshotFingerprint();
                    problemTracker.clear();
                    presenceStore.applyXaeroPresenceSnapshot();
                    return;
                }

                if (result.pushResult().ok()) {
                    lastSyncStatus = "push-rejected:" + result.pushResult().error();
                    problemTracker.logProblem(module, "push rejected", result.pushResult().error());
                    presenceStore.applyXaeroPresenceSnapshot();
                    return;
                }
            }

            if (!result.localChanged()) {
                lastSyncedFingerprint = result.snapshotFingerprint();
                lastSyncStatus = "noop";
                problemTracker.clear();
                presenceStore.applyXaeroPresenceSnapshot();
                return;
            }

            lastSyncStatus = "local-change-pending";
            presenceStore.applyXaeroPresenceSnapshot();
        } finally {
            if (syncTickQueued) {
                syncTickQueued = false;
                handleSyncTick();
            }
        }
    }

    private String explainSyncRuntimeUnavailable() {
        Modules modules = Modules.get();
        if (modules == null) return "modules-null";

        SyncHub syncHub = modules.get(SyncHub.class);
        if (syncHub == null) return "sync-hub-missing";
        if (!syncHub.isFeatureEnabled(SyncHub.SyncFeature.XAERO_WORLD_MAP)) return "sync-hub-xaero-feature-disabled";
        if (syncHub.getOrCreateDeviceId().isBlank()) return "sync-hub-device-id-empty";
        if (syncHub.getEncryptionKeyMaterial().isBlank()) return "sync-hub-encryption-key-empty";
        if (syncHub.getRequestSigningKey().isBlank()) return "sync-hub-signing-key-empty";
        return "unknown";
    }

    private SyncRuntimeConfig resolveSyncRuntimeConfig() {
        Modules modules = Modules.get();
        if (modules == null) return null;

        SyncHub syncHub = modules.get(SyncHub.class);
        if (syncHub == null) return null;
        if (!syncHub.isFeatureEnabled(SyncHub.SyncFeature.XAERO_WORLD_MAP)) return null;

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


