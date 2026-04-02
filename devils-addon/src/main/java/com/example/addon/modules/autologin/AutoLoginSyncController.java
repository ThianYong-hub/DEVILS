package com.example.addon.modules.autologin;

import com.example.addon.modules.SyncHub;
import com.example.addon.modules.autologin.AutoLoginProfile.SyncCycleResult;
import com.example.addon.modules.autologin.AutoLoginProfile.SyncProfileData;
import com.example.addon.modules.autologin.AutoLoginProfile.SyncPullResult;
import com.example.addon.modules.autologin.AutoLoginProfile.SyncPushResult;
import com.example.addon.modules.autologin.AutoLoginProfile.SyncRuntimeConfig;
import com.example.addon.shared.sync.SyncJsonUtils;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.client.MinecraftClient;

public final class AutoLoginSyncController {
    private static final long PULL_FALLBACK_INTERVAL_MS = 2_500;
    private static final int SYNC_ERROR_DETAIL_MAX = 120;

    private final AutoLoginProfileStore profileStore;
    private final BooleanSupplier verboseSupplier;
    private final Consumer<String> infoLogger;
    private final AutoLoginSyncCodec codec;
    private final AutoLoginSyncStreamController streamController;
    private final AutoLoginSyncDiagnostics diagnostics;

    private boolean syncInFlight;
    private long lastKnownSyncRevision = -1;
    private long lastSyncPullAttemptMs;
    private String lastSyncedFingerprint = "";
    private String lastSyncStatus = "idle";
    private boolean syncInitialSyncPending = true;

    public AutoLoginSyncController(
        AutoLoginProfileStore profileStore,
        BooleanSupplier verboseSupplier,
        IntSupplier defaultDelaySupplier,
        Consumer<String> infoLogger
    ) {
        this.profileStore = profileStore;
        this.verboseSupplier = verboseSupplier;
        this.infoLogger = infoLogger;
        this.codec = new AutoLoginSyncCodec(
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(),
            defaultDelaySupplier,
            SYNC_ERROR_DETAIL_MAX
        );
        this.diagnostics = new AutoLoginSyncDiagnostics(verboseSupplier, infoLogger);
        this.streamController = new AutoLoginSyncStreamController(codec, verboseSupplier, infoLogger, diagnostics::logProblem);
        this.lastSyncedFingerprint = codec.computeFingerprint(profileStore.snapshotProfiles());
    }

    public void onActivate() {
        resetState();
    }

    public void onDeactivate() {
        resetState();
    }

    public void onTick() {
        handleSyncTick();
        diagnostics.flushPendingDelta(false);
    }

    public boolean isEnabled() {
        return resolveSyncRuntimeConfig() != null;
    }

    public boolean isInFlight() {
        return syncInFlight;
    }

    public boolean isStreamConnected() {
        return streamController.isConnected();
    }

    public boolean isStreamConnecting() {
        return streamController.isConnecting();
    }

    public boolean isStreamPending() {
        return streamController.isPending();
    }

    public long streamPendingRevision() {
        return streamController.pendingRevision();
    }

    public String status() {
        return lastSyncStatus;
    }

    public long knownRevision() {
        return lastKnownSyncRevision;
    }

    private void handleSyncTick() {
        SyncRuntimeConfig sync = resolveSyncRuntimeConfig();
        if (sync == null) {
            streamController.stop();
            return;
        }
        if (syncInFlight) return;

        String baseUrl = codec.normalizeSyncBaseUrl(sync.baseUrl());
        if (baseUrl.isBlank()) {
            lastSyncStatus = "skip:no-base-url";
            streamController.stop();
            return;
        }

        String baseUrlValidationError = codec.validateSyncBaseUrl(baseUrl);
        if (baseUrlValidationError != null) {
            lastSyncStatus = "skip:bad-base-url";
            diagnostics.logProblem("invalid base-url", baseUrlValidationError);
            streamController.stop();
            return;
        }
        if (!sync.allowHttp() && baseUrl.startsWith("http://")) {
            lastSyncStatus = "skip:http-disabled";
            streamController.stop();
            return;
        }

        if (sync.useStream()) {
            streamController.ensureStream(
                baseUrl,
                sync.deviceId(),
                sync.token(),
                sync.signingKey(),
                sync.timeoutSec(),
                sync.streamWaitMs(),
                lastKnownSyncRevision,
                this::formatSyncWriter
            );
        } else {
            streamController.stop();
        }

        long now = System.currentTimeMillis();
        if (diagnostics.backoffUntilMs() > now) return;

        List<SyncProfileData> localSnapshot = profileStore.snapshotProfiles();
        String localFingerprint = codec.computeFingerprint(localSnapshot);
        boolean localChanged = !localFingerprint.equals(lastSyncedFingerprint);
        boolean streamTriggeredPull = streamController.consumePendingPullSignal(lastKnownSyncRevision);
        boolean shouldBootstrapPull = lastKnownSyncRevision < 0;
        boolean streamFallbackPull = sync.useStream()
            && !streamController.isConnected()
            && !streamController.isConnecting()
            && (now - lastSyncPullAttemptMs) >= PULL_FALLBACK_INTERVAL_MS;
        boolean periodicPull = !sync.useStream() && (now - lastSyncPullAttemptMs) >= PULL_FALLBACK_INTERVAL_MS;
        boolean shouldPull = streamTriggeredPull || shouldBootstrapPull || streamFallbackPull || periodicPull;
        boolean shouldRun = streamTriggeredPull || localChanged || shouldBootstrapPull || streamFallbackPull || periodicPull;
        if (!shouldRun) return;

        lastSyncPullAttemptMs = now;
        syncInFlight = true;
        runSyncCycleAsync(
            baseUrl,
            sync.deviceId(),
            sync.token(),
            sync.signingKey(),
            sync.timeoutSec(),
            sync.streamWaitMs(),
            sync.encryptionKey(),
            lastKnownSyncRevision,
            localSnapshot,
            localFingerprint,
            localChanged,
            shouldPull,
            syncInitialSyncPending
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
        List<SyncProfileData> localSnapshot,
        String localFingerprint,
        boolean localChanged,
        boolean doPull,
        boolean initialSync
    ) {
        CompletableFuture.runAsync(() -> {
            SyncPullResult pullResult = null;
            SyncPushResult pushResult = null;
            boolean remoteApplied = false;
            String error = null;
            long pushBaseRevision = knownRevision;
            String effectiveFingerprint = localFingerprint;
            List<SyncProfileData> pushSnapshot = localSnapshot;
            boolean pushRequestedByInitialMerge = false;

            if (doPull) {
                try {
                    pullResult = codec.sendPullRequest(baseUrl, deviceId, token, signingKey, timeoutSec, encryptionKey, knownRevision, pullWaitMs);
                    if (pullResult.revision() >= 0) pushBaseRevision = pullResult.revision();
                    List<SyncProfileData> remoteSnapshot = pullResult.profiles() == null ? List.of() : pullResult.profiles();
                    boolean remoteIsNewer = pullResult.ok() && pullResult.revision() > knownRevision && pullResult.profiles() != null;

                    if (initialSync && !localSnapshot.isEmpty() && pullResult.ok() && pullResult.revision() >= 0) {
                        List<SyncProfileData> merged = profileStore.mergeProfilesPreferLocal(remoteSnapshot, localSnapshot);
                        String remoteFingerprint = codec.computeFingerprint(remoteSnapshot);
                        String mergedFingerprint = codec.computeFingerprint(merged);

                        if (!mergedFingerprint.equals(remoteFingerprint)) {
                            pushSnapshot = merged;
                            effectiveFingerprint = mergedFingerprint;
                            pushBaseRevision = pullResult.revision();
                            pushRequestedByInitialMerge = true;
                            logSync("AutoLogin sync bootstrap: merged local profiles into remote candidate (%d -> %d).", remoteSnapshot.size(), merged.size());
                        } else if (remoteIsNewer) {
                            applyRemoteProfilesBlocking(remoteSnapshot, pullResult.revision(), pullResult.lastWriter());
                            remoteApplied = true;
                        }
                    } else if (remoteIsNewer) {
                        applyRemoteProfilesBlocking(remoteSnapshot, pullResult.revision(), pullResult.lastWriter());
                        remoteApplied = true;
                    }
                } catch (Throwable t) {
                    error = SyncJsonUtils.formatSyncException("pull-error", t, SYNC_ERROR_DETAIL_MAX);
                }
            }

            if (error == null && !remoteApplied && (localChanged || pushRequestedByInitialMerge)) {
                try {
                    pushResult = codec.sendPushRequest(baseUrl, deviceId, token, signingKey, timeoutSec, encryptionKey, pushBaseRevision, pushSnapshot);
                    if (pushResult.ok() && pushResult.conflict() && initialSync && !localSnapshot.isEmpty() && pushResult.profiles() != null && pushResult.revision() >= 0) {
                        List<SyncProfileData> conflictMerged = profileStore.mergeProfilesPreferLocal(pushResult.profiles(), localSnapshot);
                        String conflictRemoteFingerprint = codec.computeFingerprint(pushResult.profiles());
                        String conflictMergedFingerprint = codec.computeFingerprint(conflictMerged);
                        if (!conflictMergedFingerprint.equals(conflictRemoteFingerprint)) {
                            pushResult = codec.sendPushRequest(baseUrl, deviceId, token, signingKey, timeoutSec, encryptionKey, pushResult.revision(), conflictMerged);
                            if (pushResult.ok() && pushResult.applied()) effectiveFingerprint = conflictMergedFingerprint;
                        }
                    }
                } catch (Throwable t) {
                    error = SyncJsonUtils.formatSyncException("push-error", t, SYNC_ERROR_DETAIL_MAX);
                }
            }

            SyncCycleResult result = new SyncCycleResult(pullResult, pushResult, remoteApplied, localChanged, effectiveFingerprint, error);
            MinecraftClient.getInstance().execute(() -> finishSyncCycle(result));
        });
    }

    private void finishSyncCycle(SyncCycleResult result) {
        syncInFlight = false;
        if (result.error() != null) {
            lastSyncStatus = result.error();
            diagnostics.logProblem("failed", result.error());
            return;
        }

        if (result.remoteApplied() || (result.pushResult() != null && result.pushResult().ok())) syncInitialSyncPending = false;
        if (result.pullResult() != null && result.pullResult().revision() >= 0) {
            lastKnownSyncRevision = Math.max(lastKnownSyncRevision, result.pullResult().revision());
        }

        if (result.remoteApplied()) {
            lastSyncStatus = "pull-applied";
            diagnostics.clearProblemTracking();
            return;
        }

        if (result.pushResult() != null) {
            applyPushResult(result);
            return;
        }

        if (!result.localChanged()) {
            lastSyncedFingerprint = result.localFingerprint();
            lastSyncStatus = "noop";
            diagnostics.clearProblemTracking();
            return;
        }

        lastSyncStatus = "local-change-pending";
    }

    private void applyPushResult(SyncCycleResult result) {
        SyncPushResult pushResult = result.pushResult();
        if (pushResult.ok() && pushResult.applied()) {
            if (pushResult.revision() >= 0) lastKnownSyncRevision = Math.max(lastKnownSyncRevision, pushResult.revision());
            lastSyncedFingerprint = result.localFingerprint();
            lastSyncStatus = "push-ok";
            diagnostics.clearProblemTracking();
            logSync("AutoLogin sync push ok (rev=%d).", lastKnownSyncRevision);
            if (pushResult.profiles() != null) applyRemoteProfiles(pushResult.profiles(), pushResult.revision(), currentSyncDeviceId());
            return;
        }

        if (pushResult.ok() && pushResult.conflict()) {
            lastSyncStatus = "push-conflict";
            if (pushResult.profiles() != null) applyRemoteProfiles(pushResult.profiles(), pushResult.revision(), pushResult.lastWriter());
            diagnostics.clearProblemTracking();
            logSync("AutoLogin sync push conflict resolved by remote revision (rev=%d).", pushResult.revision());
            return;
        }

        lastSyncStatus = "push-rejected:" + pushResult.error();
        diagnostics.logProblem("push rejected", pushResult.error());
    }

    private SyncRuntimeConfig resolveSyncRuntimeConfig() {
        Modules modules = Modules.get();
        if (modules == null) return null;

        SyncHub syncHub = modules.get(SyncHub.class);
        if (syncHub == null || !syncHub.isFeatureEnabled(SyncHub.SyncFeature.AUTO_LOGIN)) return null;

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
            syncHub.useStream(),
            syncHub.allowHttp(),
            Math.max(3, syncHub.requestTimeoutSec()),
            Math.max(1_000, syncHub.streamWaitMs()),
            encryptionKey,
            signingKey
        );
    }

    private String currentSyncDeviceId() {
        SyncRuntimeConfig config = resolveSyncRuntimeConfig();
        return config == null ? "" : config.deviceId();
    }

    private void applyRemoteProfilesBlocking(List<SyncProfileData> remoteProfiles, long revision, String sourceWriter) {
        CompletableFuture<Void> applied = new CompletableFuture<>();
        MinecraftClient.getInstance().execute(() -> {
            try {
                applyRemoteProfiles(remoteProfiles, revision, sourceWriter);
                applied.complete(null);
            } catch (Throwable t) {
                applied.completeExceptionally(t);
            }
        });
        applied.join();
    }

    private void applyRemoteProfiles(List<SyncProfileData> remoteProfiles, long revision, String sourceWriter) {
        List<SyncProfileData> previousProfiles = profileStore.snapshotProfiles();
        profileStore.replaceProfiles(remoteProfiles);
        if (revision >= 0) lastKnownSyncRevision = Math.max(lastKnownSyncRevision, revision);
        lastSyncedFingerprint = codec.computeFingerprint(profileStore.snapshotProfiles());
        lastSyncStatus = "pull-applied(" + remoteProfiles.size() + ")";
        logSyncProfileDiff(previousProfiles, remoteProfiles, formatSyncWriter(sourceWriter));
    }

    private void logSyncProfileDiff(List<SyncProfileData> previousProfiles, List<SyncProfileData> remoteProfiles, String writer) {
        Map<String, SyncProfileData> previousByKey = indexProfilesByIdentity(previousProfiles);
        Map<String, SyncProfileData> remoteByKey = indexProfilesByIdentity(remoteProfiles);
        ArrayList<SyncProfileData> added = new ArrayList<>();
        ArrayList<SyncProfileData> removed = new ArrayList<>();
        ArrayList<String> changed = new ArrayList<>();

        for (Map.Entry<String, SyncProfileData> entry : remoteByKey.entrySet()) {
            SyncProfileData previous = previousByKey.get(entry.getKey());
            SyncProfileData current = entry.getValue();
            if (previous == null) {
                added.add(current);
                continue;
            }
            String changedFields = describeChangedFields(previous, current);
            if (!changedFields.isEmpty()) changed.add(formatProfileRef(current) + " {" + changedFields + "}");
        }

        for (Map.Entry<String, SyncProfileData> entry : previousByKey.entrySet()) {
            if (!remoteByKey.containsKey(entry.getKey())) removed.add(entry.getValue());
        }

        if (added.isEmpty() && removed.isEmpty() && changed.isEmpty()) return;
        diagnostics.queueSyncDelta(writer, lastKnownSyncRevision, added, removed, changed);
    }

    private void resetState() {
        diagnostics.reset();
        streamController.reset();
        syncInFlight = false;
        lastKnownSyncRevision = -1;
        lastSyncPullAttemptMs = 0;
        lastSyncStatus = "idle";
        lastSyncedFingerprint = codec.computeFingerprint(profileStore.snapshotProfiles());
        syncInitialSyncPending = true;
    }

    private void logSync(String format, Object... args) {
        if (!verboseSupplier.getAsBoolean()) return;
        infoLogger.accept(args.length == 0 ? format : String.format(Locale.ROOT, format, args));
    }

    private static Map<String, SyncProfileData> indexProfilesByIdentity(List<SyncProfileData> snapshot) {
        HashMap<String, SyncProfileData> indexed = new HashMap<>();
        for (SyncProfileData data : snapshot) indexed.put(profileIdentityKey(data), data);
        return indexed;
    }

    private static String profileIdentityKey(SyncProfileData data) {
        return AutoLoginTextRules.normalizeKey(data.username()) + "|" + AutoLoginTextRules.normalizeServerKey(data.server());
    }

    private String formatProfileRef(SyncProfileData data) {
        return displayValue(data.username(), "<empty-user>") + "@" + displayValue(data.server(), "<empty-server>");
    }

    private static String displayValue(String value, String fallback) {
        if (value == null) return fallback;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? fallback : trimmed;
    }

    private static String describeChangedFields(SyncProfileData previous, SyncProfileData current) {
        ArrayList<String> fields = new ArrayList<>();
        if (previous.enabled() != current.enabled()) fields.add("enabled");
        if (previous.mode() != current.mode()) fields.add("mode");
        if (previous.delay() != current.delay()) fields.add("delay");
        if (!stringsEqual(previous.password(), current.password())) fields.add("password");
        return String.join("/", fields);
    }

    private static boolean stringsEqual(String a, String b) {
        if (a == null) return b == null;
        return a.equals(b);
    }

    private String formatSyncWriter(String sourceWriter) {
        String writer = sourceWriter == null ? "" : sourceWriter.trim();
        if (writer.isBlank()) return "<remote>";
        String currentDevice = currentSyncDeviceId();
        if (!currentDevice.isBlank() && writer.equalsIgnoreCase(currentDevice)) return "this-device";
        return writer;
    }
}
