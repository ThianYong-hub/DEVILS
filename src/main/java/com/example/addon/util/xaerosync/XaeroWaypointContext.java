package com.example.addon.util.xaerosync;

import com.example.addon.AddonTemplate;
import com.example.addon.util.XaeroSyncWaypoints;

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

    public final Set<UUID> activeTrackedPlayers = new HashSet<>();
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
        AddonTemplate.LOG.debug("[Devils/Ping] " + message);
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
}


