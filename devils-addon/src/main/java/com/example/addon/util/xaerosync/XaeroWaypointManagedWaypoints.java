package com.example.addon.util.xaerosync;

import com.example.addon.util.MapIconManager;
import com.example.addon.util.XaeroSyncWaypoints;
import net.minecraft.registry.RegistryKey;
import net.minecraft.world.World;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class XaeroWaypointManagedWaypoints {
    private XaeroWaypointManagedWaypoints() {
    }

    public static XaeroWaypointContext.ApplyStats applyManagedWaypoints(
        XaeroWaypointContext context,
        java.util.Collection<XaeroSyncWaypoints.MapWaypointMarker> markers,
        RegistryKey<World> targetDimension,
        boolean includePlayersFallback
    ) throws Exception {
        List<XaeroSyncWaypoints.MapWaypointMarker> desiredMarkers = sanitizeWaypointMarkers(markers, includePlayersFallback);
        HashMap<String, String> iconPathsByWaypoint = new HashMap<>();
        LinkedHashMap<String, XaeroSyncWaypoints.MapWaypointMarker> desiredByStateKey = new LinkedHashMap<>();
        for (XaeroSyncWaypoints.MapWaypointMarker marker : desiredMarkers) {
            if (marker == null) continue;
            String markerName = XaeroWaypointNaming.formatManagedWaypointName(marker);
            int x = toWaypointBlockCoord(marker.x());
            int y = toWaypointBlockCoord(marker.y());
            int z = toWaypointBlockCoord(marker.z());
            String symbol = marker.player() ? XaeroWaypointContext.PLAYER_FALLBACK_SYMBOL : XaeroWaypointContext.INVISIBLE_WAYPOINT_SYMBOL;
            String stateKey = XaeroWaypointNaming.managedWaypointStateKey(markerName, x, y, z, symbol);
            desiredByStateKey.put(stateKey, marker);

            if (!marker.player()) {
                String renderKey = XaeroWaypointNaming.managedWaypointRenderKey(markerName, x, y, z);
                String iconPath = MapIconManager.normalizeIconPath(marker.iconPath());
                if (iconPath.isBlank()) iconPath = MapIconManager.DEFAULT_MAP_ICON_PATH;
                if (!iconPath.isBlank()) iconPathsByWaypoint.put(renderKey, iconPath);
            }
        }

        context.managedWaypointIconPaths.clear();
        context.managedWaypointIconPaths.putAll(iconPathsByWaypoint);

        List<Object> waypoints = getMinimapWaypointList(context);
        if (waypoints == null) return new XaeroWaypointContext.ApplyStats(false, desiredMarkers.size(), 0, 0, 0, 0);

        int beforeCount = waypoints.size();
        int removedManagedCount = 0;
        int addedCount = 0;

        LinkedHashMap<String, Object> existingManagedByStateKey = new LinkedHashMap<>();
        ArrayList<Object> duplicateManagedWaypoints = new ArrayList<>();
        for (Object waypoint : waypoints) {
            if (!XaeroWaypointNaming.isManagedWaypoint(context, waypoint)) continue;
            String stateKey = XaeroWaypointNaming.managedWaypointStateKey(context, waypoint);
            Object previous = existingManagedByStateKey.putIfAbsent(stateKey, waypoint);
            if (previous != null) duplicateManagedWaypoints.add(waypoint);
        }

        if (!duplicateManagedWaypoints.isEmpty()) {
            waypoints.removeIf(duplicateManagedWaypoints::contains);
            removedManagedCount += duplicateManagedWaypoints.size();
        }

        for (Map.Entry<String, Object> existingEntry : new ArrayList<>(existingManagedByStateKey.entrySet())) {
            if (desiredByStateKey.containsKey(existingEntry.getKey())) continue;
            if (waypoints.remove(existingEntry.getValue())) removedManagedCount++;
        }

        for (Map.Entry<String, XaeroSyncWaypoints.MapWaypointMarker> desiredEntry : desiredByStateKey.entrySet()) {
            if (existingManagedByStateKey.containsKey(desiredEntry.getKey())) continue;
            Object waypoint = createManagedWaypoint(context, desiredEntry.getValue(), targetDimension);
            if (waypoint != null) {
                waypoints.add(waypoint);
                addedCount++;
            }
        }

        return new XaeroWaypointContext.ApplyStats(true, desiredMarkers.size(), removedManagedCount, addedCount, beforeCount, waypoints.size());
    }

    public static void clearManagedWaypoints(XaeroWaypointContext context) {
        try {
            context.managedWaypointIconPaths.clear();
            List<Object> waypoints = getMinimapWaypointList(context);
            if (waypoints == null) return;
            waypoints.removeIf(waypoint -> XaeroWaypointNaming.isManagedWaypoint(context, waypoint));
            requestWaypointsRefresh();
        } catch (Throwable ignored) {
        }
    }

    public static String requestWaypointsRefresh() {
        try {
            Object supportMinimap = XaeroWaypointReflection.readStaticField("xaero.map.mods.SupportMods", "xaeroMinimap");
            if (supportMinimap == null) return "support-minimap-null";

            try {
                java.lang.reflect.Method refreshMethod = supportMinimap.getClass().getMethod("requestWaypointsRefresh");
                refreshMethod.setAccessible(true);
                refreshMethod.invoke(supportMinimap);
                return "method:requestWaypointsRefresh";
            } catch (Throwable ignored) {
            }

            boolean fieldWriteApplied = XaeroWaypointReflection.writeFieldValue(supportMinimap, "refreshWaypoints", true);
            return fieldWriteApplied ? "field:refreshWaypoints" : "refresh-hook-unavailable";
        } catch (Throwable throwable) {
            return "refresh-exception:" + throwable.getClass().getSimpleName();
        }
    }

    private static List<XaeroSyncWaypoints.MapWaypointMarker> sanitizeWaypointMarkers(
        java.util.Collection<XaeroSyncWaypoints.MapWaypointMarker> markers,
        boolean includePlayersFallback
    ) {
        LinkedHashMap<String, XaeroSyncWaypoints.MapWaypointMarker> dedup = new LinkedHashMap<>();
        if (markers == null) return new ArrayList<>();

        for (XaeroSyncWaypoints.MapWaypointMarker marker : markers) {
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

    private static List<Object> getMinimapWaypointList(XaeroWaypointContext context) throws Exception {
        Object minimap = XaeroWaypointReflection.readStaticField("xaero.hud.minimap.BuiltInHudModules", "MINIMAP");
        if (minimap != null) {
            Object minimapSession = XaeroWaypointReflection.invokeNoArg(minimap, "getCurrentSession");
            List<Object> list = extractWaypointListFromMinimapSession(minimapSession);
            if (list != null) {
                XaeroWaypointContext.debug(context, "waypoint list source=minimap-world size=%d", list.size());
                return list;
            }
        }

        try {
            Class<?> minimapSessionClass = Class.forName("xaero.common.XaeroMinimapSession");
            Object minimapSession = minimapSessionClass.getMethod("getCurrentSession").invoke(null);
            if (minimapSession != null) {
                Object waypointsManager = XaeroWaypointReflection.invokeNoArg(minimapSession, "getWaypointsManager");
                Object waypointSet = waypointsManager == null ? null : XaeroWaypointReflection.invokeNoArg(waypointsManager, "getWaypoints");
                List<Object> list = XaeroWaypointReflection.extractWaypointList(waypointSet);
                if (list != null) {
                    XaeroWaypointContext.debug(context, "waypoint list source=legacy-minimap-session size=%d", list.size());
                    return list;
                }
            }
        } catch (Throwable ignored) {
        }

        Object supportMinimap = XaeroWaypointReflection.readStaticField("xaero.map.mods.SupportMods", "xaeroMinimap");
        if (supportMinimap != null) {
            Object waypointWorld = XaeroWaypointReflection.readFieldValue(supportMinimap, "waypointWorld");
            if (waypointWorld == null) waypointWorld = XaeroWaypointReflection.readFieldValue(supportMinimap, "mapWaypointWorld");
            List<Object> list = extractWaypointListFromWorld(waypointWorld);
            if (list != null) {
                XaeroWaypointContext.debug(context, "waypoint list source=supportmods-waypoint-world size=%d", list.size());
                return list;
            }
        }

        XaeroWaypointContext.debug(context, "waypoint list unavailable in all known minimap paths.");
        return null;
    }

    private static List<Object> extractWaypointListFromMinimapSession(Object minimapSession) {
        if (minimapSession == null) return null;

        Object worldManager = XaeroWaypointReflection.invokeNoArg(minimapSession, "getWorldManager");
        Object currentWorld = worldManager == null ? null : XaeroWaypointReflection.invokeNoArg(worldManager, "getCurrentWorld");
        List<Object> list = extractWaypointListFromWorld(currentWorld);
        if (list != null) return list;

        Object waypointsManager = XaeroWaypointReflection.invokeNoArg(minimapSession, "getWaypointsManager");
        Object waypointSet = waypointsManager == null ? null : XaeroWaypointReflection.invokeNoArg(waypointsManager, "getWaypoints");
        return XaeroWaypointReflection.extractWaypointList(waypointSet);
    }

    private static List<Object> extractWaypointListFromWorld(Object minimapWorld) {
        if (minimapWorld == null) return null;

        Object waypointSet = XaeroWaypointReflection.invokeNoArg(minimapWorld, "getCurrentWaypointSet");
        if (waypointSet == null) {
            Object currentSetId = XaeroWaypointReflection.invokeNoArg(minimapWorld, "getCurrentWaypointSetId");
            if (currentSetId instanceof String setId && !setId.isBlank()) {
                waypointSet = XaeroWaypointReflection.invokeSingleArg(minimapWorld, "getWaypointSet", setId);
            }
        }
        if (waypointSet == null) {
            Object iterableSets = XaeroWaypointReflection.invokeNoArg(minimapWorld, "getIterableWaypointSets");
            if (iterableSets instanceof Iterable<?> iterable) {
                for (Object candidateSet : iterable) {
                    waypointSet = candidateSet;
                    if (waypointSet != null) break;
                }
            }
        }
        return XaeroWaypointReflection.extractWaypointList(waypointSet);
    }

    private static Object createManagedWaypoint(
        XaeroWaypointContext context,
        XaeroSyncWaypoints.MapWaypointMarker marker,
        RegistryKey<World> targetDimension
    ) throws Exception {
        Constructor<?> constructor = resolveWaypointConstructor(context);
        if (constructor == null) {
            XaeroWaypointContext.debug(context, "waypoint constructor unresolved, skipping marker '%s'.", marker == null ? "" : marker.name());
            return null;
        }

        int x = toWaypointBlockCoord(marker.x());
        int y = toWaypointBlockCoord(marker.y());
        int z = toWaypointBlockCoord(marker.z());
        int color = marker.player() ? XaeroWaypointContext.PLAYER_WAYPOINT_COLOR : XaeroWaypointContext.MARKER_WAYPOINT_COLOR;
        String name = XaeroWaypointNaming.formatManagedWaypointName(marker);
        String label = marker.player() ? XaeroWaypointContext.PLAYER_FALLBACK_SYMBOL : XaeroWaypointContext.INVISIBLE_WAYPOINT_SYMBOL;

        constructor.setAccessible(true);
        return constructor.newInstance(x, y, z, name, label, color, 0, true);
    }

    private static int toWaypointBlockCoord(double value) {
        if (!Double.isFinite(value)) return 0;
        return (int) Math.floor(value);
    }

    private static Constructor<?> resolveWaypointConstructor(XaeroWaypointContext context) throws Exception {
        if (context.waypointConstructor != null) return context.waypointConstructor;
        Class<?> waypointClass = Class.forName("xaero.common.minimap.waypoints.Waypoint");
        for (Constructor<?> constructor : waypointClass.getDeclaredConstructors()) {
            Class<?>[] params = constructor.getParameterTypes();
            if (params.length != 8) continue;
            if (!isIntegerLike(params[0]) || !isIntegerLike(params[1]) || !isIntegerLike(params[2])) continue;
            if (params[3] != String.class || params[4] != String.class) continue;
            if (!isIntegerLike(params[5]) || !isIntegerLike(params[6])) continue;
            if (params[7] != boolean.class && params[7] != Boolean.class) continue;
            context.waypointConstructor = constructor;
            XaeroWaypointContext.debug(context, "waypoint constructor resolved: %s", constructor.toGenericString());
            return context.waypointConstructor;
        }
        XaeroWaypointContext.debug(context, "waypoint constructor not found in xaero.common.minimap.waypoints.Waypoint.");
        return null;
    }

    private static boolean isIntegerLike(Class<?> type) {
        return type == int.class || type == Integer.class;
    }
}



