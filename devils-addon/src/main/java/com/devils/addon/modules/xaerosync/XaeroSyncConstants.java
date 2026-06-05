package com.devils.addon.modules.xaerosync;

import com.devils.addon.modules.XaeroSync;
import com.devils.addon.shared.sync.SyncDomainRoutes;
import com.devils.addon.util.MapIconManager;
import net.minecraft.util.Identifier;

import java.util.List;
import java.util.Locale;

public final class XaeroSyncConstants {
    public static final String SYNC_PULL_PATH = SyncDomainRoutes.CORE_PULL_PATH;
    public static final String SYNC_PUSH_PATH = SyncDomainRoutes.CORE_PUSH_PATH;
    public static final String SYNC_STREAM_PATH = SyncDomainRoutes.CORE_STREAM_PATH;
    public static final String SYNC_STREAM_PATH_LEGACY = SyncDomainRoutes.LEGACY_STREAM_PATH;
    public static final long PULL_FALLBACK_INTERVAL_MS = 100;
    public static final long SYNC_STREAM_RECONNECT_MS = 250;
    public static final long SYNC_AUTH_BACKOFF_MS = 1_000;
    public static final long SYNC_CRYPTO_BACKOFF_MS = 1_000;
    public static final long SYNC_NETWORK_BACKOFF_MS = 150;
    public static final long SYNC_CONFIG_BACKOFF_MS = 1_000;
    public static final long SYNC_STREAM_UNSUPPORTED_BACKOFF_MS = 300_000;
    public static final long SYNC_PROBLEM_LOG_COOLDOWN_MS = 20_000;
    public static final int SYNC_ERROR_DETAIL_MAX = 120;
    public static final int WORLD_MIN_Y = -65;
    public static final int WORLD_MAX_Y = 365;
    public static final String MODULE_NAMESPACE = "xaero-world-map";
    public static final String PRESENCE_SCHEMA = "devils-xaero-presence-v1";
    public static final String PRESENCE_USERNAME_PREFIX = "__presence__:";
    public static final long PRESENCE_STALE_MS = 30_000;
    public static final long PRESENCE_MIN_UPDATE_MS = 25;
    public static final long PRESENCE_FORCE_UPDATE_MS = 1_000;
    public static final double PRESENCE_MOVE_THRESHOLD_SQ = 0.0;
    public static final int MAX_SYNC_PRESENCE = 64;
    public static final double PRESENCE_MAX_SPEED_BLOCKS_PER_SEC = 230.0;
    public static final long PRESENCE_FUTURE_SKEW_TOLERANCE_MS = 30_000;
    public static final int MAX_PARALLEL_SYNC_CYCLES = 2;
    public static final int BUTTON_W = 20;
    public static final int BUTTON_H = 20;
    public static final int ROW_H = 14;
    public static final int PANEL_W = 240;
    public static final int DEVILS_MAP_ICON_SOURCE_SIZE = 1024;
    public static final int DEVILS_MAP_ICON_U = 256;
    public static final int DEVILS_MAP_ICON_V = 167;
    public static final int DEVILS_MAP_ICON_REGION_W = 525;
    public static final int DEVILS_MAP_ICON_REGION_H = 612;
    public static final int DEVILS_MARKER_ICON_U = 230;
    public static final int DEVILS_MARKER_ICON_V = 120;
    public static final int DEVILS_MARKER_ICON_REGION_W = 560;
    public static final int DEVILS_MARKER_ICON_REGION_H = 500;
    public static final Identifier XAERO_SYNC_ICON_TEXTURE = Identifier.of("devils-addon", "textures/gui/devils_map_icon.png");
    public static final int DEVILS_ACCENT_BORDER = 0xFF5C0000;
    public static final int DEVILS_PANEL_BACKGROUND = 0xD0110505;
    public static final int DEVILS_TOOLTIP_BACKGROUND = 0xD0140606;
    public static final int DEVILS_ROW_HOVER = 0x70401A1A;
    public static final int DEVILS_TEXT_PRIMARY = 0xFFF2DCDC;
    public static final int DEVILS_TEXT_SECONDARY = 0xFFE2BDBD;
    public static final int DEVILS_TEXT_MUTED = 0xFFB89090;
    public static final int DEVILS_BUTTON_BACKGROUND = 0xC01A0909;
    public static final int DEVILS_BUTTON_BACKGROUND_HOVER = 0xD0250D0D;
    public static final int DEVILS_BUTTON_BACKGROUND_ACTIVE = 0xE0341212;
    public static final int DEVILS_MAP_LABEL_BG = 0xB8110505;
    public static final int DEVILS_MAP_LABEL_ICON = 10;
    public static final int DEVILS_MAP_POINT_ICON = 12;
    public static final int DEVILS_MAP_LABEL_OFFSET_Y = 11;
    public static final int DEVILS_MAP_LABEL_MARGIN = 160;
    public static final int DEVILS_MAP_LABEL_MAX = 80;
    public static final int XAERO_TOOLBAR_WIDTH = 24;
    public static final String DEFAULT_DEVILS_MAP_ICON_PATH = MapIconManager.DEFAULT_MAP_ICON_PATH;

    private XaeroSyncConstants() {
    }
}

final class XaeroSyncProblemTracker {
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

    void logProblem(XaeroSync module, String context, String error) {
        String safeError = error == null ? "unknown" : error;
        String signature = (context + "|" + safeError).toLowerCase(Locale.ROOT);
        long now = System.currentTimeMillis();

        if (signature.equals(lastSyncProblemSignature) && (now - lastSyncProblemLogMs) < XaeroSyncConstants.SYNC_PROBLEM_LOG_COOLDOWN_MS) {
            lastSyncProblemSuppressed++;
            return;
        }

        if (lastSyncProblemSuppressed > 0 && !lastSyncProblemSignature.isBlank()) {
            module.logSyncInternal("XaeroSync suppressed repeated errors: %d", lastSyncProblemSuppressed);
        }

        lastSyncProblemSignature = signature;
        lastSyncProblemLogMs = now;
        lastSyncProblemSuppressed = 0;

        module.logSyncInternal("XaeroSync %s: %s", context, safeError);
        SyncErrorType type = classifySyncError(safeError);
        if (type == SyncErrorType.AUTH) {
            syncBackoffUntilMs = Math.max(syncBackoffUntilMs, now + XaeroSyncConstants.SYNC_AUTH_BACKOFF_MS);
            return;
        }
        if (type == SyncErrorType.CONFIG) {
            syncBackoffUntilMs = Math.max(syncBackoffUntilMs, now + XaeroSyncConstants.SYNC_CONFIG_BACKOFF_MS);
            return;
        }
        if (type == SyncErrorType.CRYPTO) {
            syncBackoffUntilMs = Math.max(syncBackoffUntilMs, now + XaeroSyncConstants.SYNC_CRYPTO_BACKOFF_MS);
            return;
        }
        if (type == SyncErrorType.NETWORK) {
            syncBackoffUntilMs = Math.max(syncBackoffUntilMs, now + XaeroSyncConstants.SYNC_NETWORK_BACKOFF_MS);
        }
    }

    SyncErrorType classifySyncError(String error) {
        String normalized = error == null ? "" : error.toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) return SyncErrorType.OTHER;
        if (normalized.contains("401") || normalized.contains("unauthorized") || normalized.contains("forbidden")) return SyncErrorType.AUTH;
        if (normalized.contains("404") || normalized.contains("not-found") || normalized.contains("not_found")) return SyncErrorType.CONFIG;
        if (normalized.contains("undefined scheme") || normalized.contains("bad-base-url") || normalized.contains("unsupported scheme") || normalized.contains("uri with undefined host")) return SyncErrorType.CONFIG;
        if (normalized.contains("certificateexception") || normalized.contains("sslhandshakeexception") || normalized.contains("pkix") || normalized.contains("subject alternative names")) return SyncErrorType.CONFIG;
        if (normalized.contains("decrypt") || normalized.contains("aeadbadtagexception") || normalized.contains("tag mismatch")) return SyncErrorType.CRYPTO;
        if (normalized.contains("timeout") || normalized.contains("connect") || normalized.contains("ioexception") || normalized.contains("eofexception") || normalized.contains("connection") || normalized.contains("refused") || normalized.contains("reset") || normalized.contains("unreachable") || normalized.contains("unknownhost") || normalized.contains("noroutetohost")) {
            return SyncErrorType.NETWORK;
        }
        return SyncErrorType.OTHER;
    }
}

record SyncCycleResult(
    SyncPullResult pullResult,
    SyncPushResult pushResult,
    boolean remoteApplied,
    boolean localChanged,
    List<SyncXaeroData> snapshot,
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

record SyncPullResult(
    boolean ok,
    long revision,
    List<SyncXaeroData> profiles,
    String error,
    String lastWriter
) {
}

record SyncPushResult(
    boolean ok,
    boolean applied,
    boolean conflict,
    long revision,
    List<SyncXaeroData> profiles,
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

record SyncXaeroData(
    boolean enabled,
    String username,
    String server,
    String payload,
    int delay
) {
}


