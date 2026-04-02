package com.example.addon.util;

import com.example.addon.util.xaerosync.XaeroWaypointContext;
import com.example.addon.util.xaerosync.XaeroWaypointManagedWaypoints;
import com.example.addon.util.xaerosync.XaeroWaypointNaming;
import com.example.addon.util.xaerosync.XaeroWaypointReflection;
import com.example.addon.util.xaerosync.XaeroWaypointTrackedPlayers;
import com.example.addon.util.xaerosync.XaeroWaypointVisibility;
import net.minecraft.registry.RegistryKey;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.Consumer;

public final class XaeroSyncWaypoints {
    private static final XaeroWaypointContext CONTEXT = new XaeroWaypointContext();

    private XaeroSyncWaypoints() {
    }

    public static void setDebugListener(Consumer<String> listener) {
        XaeroWaypointContext.setListener(CONTEXT, listener);
    }

    public static void clear() {
        CONTEXT.lastWaypointSignature = "";
        CONTEXT.trackedPlayersVisibilityEnforced = false;
        CONTEXT.waypointVisibilityEnforced = false;
        CONTEXT.lastWaypointApplyAttemptSignature = "";
        CONTEXT.nextWaypointApplyAttemptAtMs = 0L;
        CONTEXT.lastTrackedPlayersDebugSnapshot = "";
        CONTEXT.lastWaypointVisibilityIssue = "";
        CONTEXT.trackedPlayersVisibilityWarningShown = false;

        XaeroWaypointTrackedPlayers.clearTrackedPlayers(CONTEXT);
        XaeroWaypointManagedWaypoints.clearManagedWaypoints(CONTEXT);
        CONTEXT.managedWaypointIconPaths.clear();
        XaeroWaypointVisibility.cleanupLegacyWaypoints(CONTEXT);
    }

    public static void apply(Collection<PlayerMarker> markers) {
        apply(markers, List.of());
    }

    public static void apply(Collection<PlayerMarker> markers, Collection<MapWaypointMarker> mapMarkers) {
        RegistryKey<World> targetDimension = XaeroWaypointReflection.currentDimensionKey();
        XaeroWaypointContext.TrackedPlayersResult trackedPlayersResult = XaeroWaypointTrackedPlayers.applyTrackedPlayers(CONTEXT, markers, mapMarkers, targetDimension);

        String waypointSignature = waypointSignature(
            trackedPlayersResult.effectiveMapMarkers(),
            targetDimension,
            trackedPlayersResult.includePlayersFallback()
        );
        boolean waypointSignatureChanged = !waypointSignature.equals(CONTEXT.lastWaypointSignature);
        long nowMs = System.currentTimeMillis();
        boolean waypointAttemptDue = waypointSignatureChanged
            && (!waypointSignature.equals(CONTEXT.lastWaypointApplyAttemptSignature) || nowMs >= CONTEXT.nextWaypointApplyAttemptAtMs);
        boolean waypointApplySuccessful = !waypointSignatureChanged;
        XaeroWaypointContext.ApplyStats applyStats = null;
        String refreshResult = waypointSignatureChanged ? "pending" : "skipped-signature-same";

        try {
            if (waypointAttemptDue) {
                XaeroWaypointVisibility.ensureWaypointsVisible(CONTEXT);
                applyStats = XaeroWaypointManagedWaypoints.applyManagedWaypoints(
                    CONTEXT,
                    trackedPlayersResult.effectiveMapMarkers(),
                    targetDimension,
                    trackedPlayersResult.includePlayersFallback()
                );
                if (applyStats.listAvailable()) {
                    refreshResult = XaeroWaypointManagedWaypoints.requestWaypointsRefresh();
                    waypointApplySuccessful = true;
                } else {
                    refreshResult = "waypoint-list-unavailable";
                }
            } else if (waypointSignatureChanged) {
                refreshResult = "retry-cooldown";
            }
        } catch (Throwable throwable) {
            refreshResult = "apply-exception:" + throwable.getClass().getSimpleName();
            XaeroWaypointContext.debug(CONTEXT, "waypoint stage failed: %s", throwable.getClass().getSimpleName());
        }

        if (waypointSignatureChanged && waypointAttemptDue) {
            if (applyStats != null) {
                XaeroWaypointContext.debug(
                    CONTEXT,
                    "waypoint stage: players=%d mapMarkers=%d desired=%d removed=%d added=%d refresh=%s success=%s fallbackPlayers=%s dim=%s",
                    XaeroWaypointContext.count(markers),
                    XaeroWaypointContext.count(mapMarkers),
                    applyStats.desiredCount(),
                    applyStats.removedManagedCount(),
                    applyStats.addedCount(),
                    refreshResult,
                    waypointApplySuccessful,
                    trackedPlayersResult.includePlayersFallback(),
                    targetDimension == null ? "null" : targetDimension.getValue()
                );
            } else {
                XaeroWaypointContext.debug(
                    CONTEXT,
                    "waypoint stage: players=%d mapMarkers=%d refresh=%s success=%s fallbackPlayers=%s dim=%s",
                    XaeroWaypointContext.count(markers),
                    XaeroWaypointContext.count(mapMarkers),
                    refreshResult,
                    waypointApplySuccessful,
                    trackedPlayersResult.includePlayersFallback(),
                    targetDimension == null ? "null" : targetDimension.getValue()
                );
            }
        }

        if (waypointAttemptDue) {
            CONTEXT.lastWaypointApplyAttemptSignature = waypointSignature;
            CONTEXT.nextWaypointApplyAttemptAtMs = waypointApplySuccessful ? 0L : nowMs + XaeroWaypointContext.WAYPOINT_APPLY_RETRY_MS;
        }

        if (waypointApplySuccessful) {
            CONTEXT.lastWaypointSignature = waypointSignature;
            CONTEXT.lastWaypointApplyAttemptSignature = waypointSignature;
            CONTEXT.nextWaypointApplyAttemptAtMs = 0L;
        }

        XaeroWaypointVisibility.cleanupLegacyWaypoints(CONTEXT);
    }

    public static boolean isDevilsManagedWaypoint(Object waypoint) {
        return XaeroWaypointNaming.isManagedWaypoint(CONTEXT, waypoint);
    }

    public static String resolveManagedWaypointRenderName(Object waypoint, String originalName) {
        return XaeroWaypointNaming.resolveManagedWaypointRenderName(CONTEXT, waypoint, originalName);
    }

    public static String resolveManagedWaypointIconPath(Object waypoint) {
        return XaeroWaypointNaming.resolveManagedWaypointIconPath(CONTEXT, waypoint);
    }

    private static String waypointSignature(
        Collection<MapWaypointMarker> markers,
        RegistryKey<World> targetDimension,
        boolean includePlayersFallback
    ) {
        ArrayList<String> rows = new ArrayList<>();
        rows.add("target=" + (targetDimension == null ? "null" : targetDimension.getValue()));
        rows.add("fallbackPlayers=" + includePlayersFallback);
        for (MapWaypointMarker marker : sanitizeWaypointMarkers(markers, includePlayersFallback)) {
            rows.add(
                XaeroWaypointReflection.normalize(marker.id()) + "|"
                    + XaeroWaypointReflection.normalize(marker.name()) + "|"
                    + XaeroWaypointReflection.normalize(marker.dimension()) + "|"
                    + marker.player() + "|"
                    + MapIconManager.normalizeIconPath(marker.iconPath()) + "|"
                    + Math.round(marker.x() * 10.0) + "|"
                    + Math.round(marker.y() * 10.0) + "|"
                    + Math.round(marker.z() * 10.0)
            );
        }
        rows.sort(Comparator.naturalOrder());
        return String.join("\n", rows);
    }

    private static List<MapWaypointMarker> sanitizeWaypointMarkers(Collection<MapWaypointMarker> markers, boolean includePlayersFallback) {
        LinkedHashMap<String, MapWaypointMarker> dedup = new LinkedHashMap<>();
        if (markers == null) return new ArrayList<>();

        for (MapWaypointMarker marker : markers) {
            if (marker == null) continue;
            if (marker.player() && !includePlayersFallback) continue;
            if (!Double.isFinite(marker.x()) || !Double.isFinite(marker.y()) || !Double.isFinite(marker.z())) continue;

            String key = XaeroWaypointReflection.normalize(marker.id());
            if (key.isBlank()) {
                key = XaeroWaypointReflection.normalize(marker.name())
                    + "|" + Math.round(marker.x())
                    + "|" + Math.round(marker.z())
                    + "|" + marker.player();
            }
            dedup.put(key, marker);
        }

        return new ArrayList<>(dedup.values());
    }

    public record PlayerMarker(
        String key,
        String name,
        String uuid,
        String dimension,
        double x,
        double y,
        double z
    ) {
    }

    public record MapWaypointMarker(
        String id,
        String name,
        String dimension,
        double x,
        double y,
        double z,
        String iconPath,
        boolean player
    ) {
    }
}



