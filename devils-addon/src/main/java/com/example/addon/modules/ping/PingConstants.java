package com.example.addon.modules.ping;

import com.example.addon.modules.Ping;
import com.example.addon.shared.sync.SyncDomainRoutes;
import com.example.addon.util.MapIconManager;
import net.minecraft.util.Identifier;

import java.util.List;
import java.util.Locale;

public final class PingConstants {
    public static final String SYNC_PULL_PATH = SyncDomainRoutes.CORE_PULL_PATH;
    public static final String SYNC_PUSH_PATH = SyncDomainRoutes.CORE_PUSH_PATH;
    public static final String SYNC_STREAM_PATH = SyncDomainRoutes.CORE_STREAM_PATH;
    public static final String SYNC_STREAM_PATH_LEGACY = SyncDomainRoutes.LEGACY_STREAM_PATH;
    public static final long SYNC_STREAM_RECONNECT_MS = 250;
    public static final long SYNC_AUTH_BACKOFF_MS = 1_000;
    public static final long SYNC_CRYPTO_BACKOFF_MS = 1_000;
    public static final long SYNC_NETWORK_BACKOFF_MS = 250;
    public static final long SYNC_CONFIG_BACKOFF_MS = 1_000;
    public static final long SYNC_STREAM_UNSUPPORTED_BACKOFF_MS = 300_000;
    public static final long SYNC_PROBLEM_LOG_COOLDOWN_MS = 20_000;
    public static final int SYNC_ERROR_DETAIL_MAX = 120;
    public static final int MAX_SYNC_MARKERS = 256;
    public static final String DEFAULT_PING_ICON_PATH = MapIconManager.DEFAULT_EMBEDDED_ICON_PATH;
    public static final long MARKER_TTL_MS = 10_000;
    public static final long MARKER_PULSE_PERIOD_MS = 1_200;
    public static final long PULL_FALLBACK_INTERVAL_MS = 250;
    public static final int WORLD_MIN_Y = -65;
    public static final int WORLD_MAX_Y = 365;
    public static final String DEFAULT_SOUND = "minecraft:block.note_block.pling";
    public static final Identifier PING_MARKER_ICON_TEXTURE = Identifier.of("devils-addon", "textures/gui/devils_ping_icon_white.png");
    public static final String MODULE_NAMESPACE = "ping";
    public static final String MARKER_SCHEMA = "devils-ping-marker-v1";
    public static final double STATIC_LABEL_SCALE = 1.35;
    public static final int DEVILS_MAP_ICON_SOURCE_SIZE = 1024;
    public static final int DEVILS_MAP_ICON_U = 256;
    public static final int DEVILS_MAP_ICON_V = 167;
    public static final int DEVILS_MAP_ICON_REGION_W = 525;
    public static final int DEVILS_MAP_ICON_REGION_H = 612;
    public static final long PUSH_OK_LOG_COOLDOWN_MS = 120_000;

    private PingConstants() {
    }
}

final class PingSyncProblemTracker {
    private String lastSyncProblemSignature = "";
    private long lastSyncProblemLogMs;
    private int lastSyncProblemSuppressed;
    private long syncBackoffUntilMs;

    long backoffUntilMs() {
        return syncBackoffUntilMs;
    }

    void clear() {
        lastSyncProblemSignature = "";
        lastSyncProblemLogMs = 0;
        lastSyncProblemSuppressed = 0;
        syncBackoffUntilMs = 0;
    }

    void logProblem(Ping module, String context, String error) {
        String safeError = error == null ? "unknown" : error;
        String signature = (context + "|" + safeError).toLowerCase(Locale.ROOT);
        long now = System.currentTimeMillis();

        if (signature.equals(lastSyncProblemSignature) && (now - lastSyncProblemLogMs) < PingConstants.SYNC_PROBLEM_LOG_COOLDOWN_MS) {
            lastSyncProblemSuppressed++;
            return;
        }

        if (lastSyncProblemSuppressed > 0 && !lastSyncProblemSignature.isBlank()) {
            module.logSyncInternal("Ping sync note: same error repeated %d times.", lastSyncProblemSuppressed);
        }

        lastSyncProblemSignature = signature;
        lastSyncProblemLogMs = now;
        lastSyncProblemSuppressed = 0;

        module.logSyncInternal("Ping sync %s: %s", context, safeError);
        SyncErrorType type = classifySyncError(safeError);
        if (type == SyncErrorType.AUTH) {
            syncBackoffUntilMs = Math.max(syncBackoffUntilMs, now + PingConstants.SYNC_AUTH_BACKOFF_MS);
            module.logSyncInternal("Ping sync hint: check token and request-signing-key (SYNC_TOKEN + SYNC_SIGNING_KEY on server).");
            return;
        }
        if (type == SyncErrorType.CONFIG) {
            syncBackoffUntilMs = Math.max(syncBackoffUntilMs, now + PingConstants.SYNC_CONFIG_BACKOFF_MS);
            module.logSyncInternal("Ping sync hint: check base-url (must include scheme).");
            return;
        }
        if (type == SyncErrorType.CRYPTO) {
            syncBackoffUntilMs = Math.max(syncBackoffUntilMs, now + PingConstants.SYNC_CRYPTO_BACKOFF_MS);
            module.logSyncInternal("Ping sync hint: encryption-key mismatch between clients.");
            return;
        }
        if (type == SyncErrorType.NETWORK) {
            syncBackoffUntilMs = Math.max(syncBackoffUntilMs, now + PingConstants.SYNC_NETWORK_BACKOFF_MS);
            module.logSyncInternal("Ping sync hint: check host/port/firewall and server reachability.");
        }
    }

    SyncErrorType classifySyncError(String error) {
        String normalized = error == null ? "" : error.toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) return SyncErrorType.OTHER;
        if (normalized.contains("401") || normalized.contains("unauthorized") || normalized.contains("forbidden")) return SyncErrorType.AUTH;
        if (normalized.contains("404") || normalized.contains("not-found") || normalized.contains("not_found")) return SyncErrorType.CONFIG;
        if (normalized.contains("undefined scheme") || normalized.contains("bad-base-url") || normalized.contains("unsupported scheme") || normalized.contains("uri with undefined host")) {
            return SyncErrorType.CONFIG;
        }
        if (normalized.contains("certificateexception") || normalized.contains("sslhandshakeexception") || normalized.contains("pkix") || normalized.contains("subject alternative names")) {
            return SyncErrorType.CONFIG;
        }
        if (normalized.contains("decrypt") || normalized.contains("aeadbadtagexception") || normalized.contains("tag mismatch")) {
            return SyncErrorType.CRYPTO;
        }
        if (normalized.contains("timeout")
            || normalized.contains("connect")
            || normalized.contains("ioexception")
            || normalized.contains("eofexception")
            || normalized.contains("connection")
            || normalized.contains("refused")
            || normalized.contains("reset")
            || normalized.contains("unreachable")
            || normalized.contains("unknownhost")
            || normalized.contains("noroutetohost")) {
            return SyncErrorType.NETWORK;
        }
        return SyncErrorType.OTHER;
    }
}

record PingMarker(
    String id,
    String sender,
    String senderDevice,
    String server,
    String dimension,
    double x,
    double y,
    double z,
    long createdAtMs,
    boolean icon,
    String iconPath
) {
}

record SyncCycleResult(
    SyncPullResult pullResult,
    SyncPushResult pushResult,
    boolean remoteApplied,
    boolean localChanged,
    List<SyncPingData> snapshot,
    String snapshotFingerprint,
    String error
) {
}

enum SyncErrorType {
    AUTH,
    CONFIG,
    CRYPTO,
    NETWORK,
    OTHER
}

record SyncPingData(
    boolean enabled,
    String username,
    String server,
    String payload,
    int delay
) {
}

record SyncPullResult(
    boolean ok,
    long revision,
    List<SyncPingData> profiles,
    String error,
    String lastWriter
) {
}

record SyncPushResult(
    boolean ok,
    boolean applied,
    boolean conflict,
    long revision,
    List<SyncPingData> profiles,
    String error,
    String lastWriter
) {
}

record SyncRuntimeConfig(
    String baseUrl,
    String token,
    String deviceId,
    boolean useStream,
    boolean allowHttp,
    int timeoutSec,
    int streamWaitMs,
    String encryptionKey,
    String signingKey
) {
}


