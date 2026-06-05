package com.devils.addon.util.xaerosync;

import com.devils.addon.DevilsAddon;
import com.devils.addon.util.XaeroSyncWaypoints;
import net.minecraft.registry.RegistryKey;
import net.minecraft.world.World;

import java.lang.reflect.Constructor;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

public final class XaeroWaypointContext {
    public static final String LEGACY_SET_NAME = "Devils Sync";
    public static final String LEGACY_WAYPOINT_SUFFIX = " [Devils Sync]";
    public static final String MANAGED_WAYPOINT_SUFFIX = "\u2063\u2063";
    public static final String MANAGED_WAYPOINT_SUFFIX_LEGACY_VISIBLE = " [Devils Sync]";
    public static final int PLAYER_WAYPOINT_COLOR = 10;
    public static final int MARKER_WAYPOINT_COLOR = 0;
    public static final String PLAYER_FALLBACK_SYMBOL = "P";
    public static final String PING_FALLBACK_SYMBOL = "M";
    public static final String INVISIBLE_WAYPOINT_SYMBOL = "\u2063";
    public static final String INVISIBLE_WAYPOINT_NAME = "\u2063";
    public static final long WAYPOINT_APPLY_RETRY_MS = 100L;
    public static final long TRACKED_PLAYER_MISSING_GRACE_MS = 1_400L;
    public static final long TRACKED_PLAYER_INTERPOLATION_MIN_MS = 30L;
    public static final long TRACKED_PLAYER_INTERPOLATION_MAX_MS = 180L;
    public static final double TRACKED_PLAYER_TELEPORT_SNAP_DISTANCE_SQ = 192.0 * 192.0;

    public final Set<UUID> activeTrackedPlayers = new HashSet<>();
    public final Map<UUID, TrackedPlayerMotionState> trackedPlayerMotionStates = new HashMap<>();
    public final Map<UUID, Long> trackedPlayerLastSeenAtMs = new HashMap<>();
    public final Map<String, String> managedWaypointIconPaths = new HashMap<>();
    public boolean unavailableLogged;
    public boolean legacyCleanupDone;
    public boolean trackedPlayersVisibilityEnforced;
    public boolean waypointVisibilityEnforced;
    public String lastWaypointSignature = "";
    public String lastWaypointApplyAttemptSignature = "";
    public long nextWaypointApplyAttemptAtMs;
    public String lastTrackedPlayersDebugSnapshot = "";
    public String lastWaypointVisibilityIssue = "";
    public boolean trackedPlayersVisibilityWarningShown;
    public Constructor<?> waypointConstructor;
    public volatile Consumer<String> debugListener;

    public static void setListener(XaeroWaypointContext context, Consumer<String> listener) {
        context.debugListener = listener;
    }

    public static void debug(XaeroWaypointContext context, String format, Object... args) {
        Consumer<String> listener = context.debugListener;
        if (listener == null || format == null || format.isBlank()) return;

        String message;
        try {
            message = args == null || args.length == 0 ? format : String.format(Locale.ROOT, format, args);
        } catch (Throwable ignored) {
            message = format;
        }
        if (message.isBlank()) return;

        try {
            listener.accept(message);
        } catch (Throwable ignored) {
        }
    }

    public static void logUnavailableOnce(XaeroWaypointContext context, String message) {
        if (context.unavailableLogged) return;
        context.unavailableLogged = true;
        DevilsAddon.LOG.debug("[Devils/Ping] " + message);
    }

    public static void debugWaypointVisibilityIssue(XaeroWaypointContext context, String reason) {
        String normalized = reason == null ? "" : reason.trim();
        if (normalized.isBlank() || normalized.equals(context.lastWaypointVisibilityIssue)) return;
        context.lastWaypointVisibilityIssue = normalized;
        debug(context, "waypoint visibility: %s", normalized);
    }

    public static int count(Collection<?> values) {
        return values == null ? 0 : values.size();
    }

    public record ApplyStats(
        boolean listAvailable,
        int desiredCount,
        int removedManagedCount,
        int addedCount,
        int beforeCount,
        int afterCount
    ) {
    }

    public record TrackedPlayersResult(
        boolean includePlayersFallback,
        boolean forcePlayersFallback,
        List<XaeroSyncWaypoints.MapWaypointMarker> effectiveMapMarkers
    ) {
    }

    public record TrackedPlayerRenderSnapshot(
        double x,
        double y,
        double z,
        RegistryKey<World> dimension
    ) {
    }

    public static final class TrackedPlayerMotionState {
        public RegistryKey<World> dimension;
        public double startX;
        public double startY;
        public double startZ;
        public double targetX;
        public double targetY;
        public double targetZ;
        public long transitionStartMs;
        public long transitionDurationMs;
        public long lastSourceReceivedAtMs;
        public double renderX;
        public double renderY;
        public double renderZ;
        public long lastRenderUpdateMs;

        public TrackedPlayerMotionState(
            RegistryKey<World> dimension,
            double startX,
            double startY,
            double startZ,
            double targetX,
            double targetY,
            double targetZ,
            long transitionStartMs,
            long transitionDurationMs,
            long lastSourceReceivedAtMs,
            double renderX,
            double renderY,
            double renderZ,
            long lastRenderUpdateMs
        ) {
            this.dimension = dimension;
            this.startX = startX;
            this.startY = startY;
            this.startZ = startZ;
            this.targetX = targetX;
            this.targetY = targetY;
            this.targetZ = targetZ;
            this.transitionStartMs = transitionStartMs;
            this.transitionDurationMs = transitionDurationMs;
            this.lastSourceReceivedAtMs = lastSourceReceivedAtMs;
            this.renderX = renderX;
            this.renderY = renderY;
            this.renderZ = renderZ;
            this.lastRenderUpdateMs = lastRenderUpdateMs;
        }
    }
}


