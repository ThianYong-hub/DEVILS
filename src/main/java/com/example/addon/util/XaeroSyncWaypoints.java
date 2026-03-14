package com.example.addon.util;

import com.example.addon.AddonTemplate;
import com.example.addon.modules.Ping;
import meteordevelopment.meteorclient.systems.modules.Modules;
import com.mojang.authlib.GameProfile;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;
import net.minecraft.world.World;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public final class XaeroSyncWaypoints {
    private static final String LEGACY_SET_NAME = "Devils Sync";
    private static final String LEGACY_WAYPOINT_SUFFIX = " [Devils Sync]";
    // Legacy suffix kept for cleanup compatibility with older builds.
    private static final String MANAGED_WAYPOINT_SUFFIX = "\u2063\u2063";
    private static final String MANAGED_WAYPOINT_SUFFIX_LEGACY_VISIBLE = " [Devils Sync]";
    private static final int PLAYER_WAYPOINT_COLOR = 10;
    private static final int MARKER_WAYPOINT_COLOR = 0;
    private static final String PLAYER_FALLBACK_SYMBOL = "P";
    private static final String PING_FALLBACK_SYMBOL = "M";
    // Legacy symbol from old builds; kept for backward compatibility when reading existing waypoints.
    private static final String INVISIBLE_WAYPOINT_SYMBOL = "\u2063";
    private static final String INVISIBLE_WAYPOINT_NAME = "\u2063";

    private static final Set<UUID> activeTrackedPlayers = new HashSet<>();
    private static final Map<String, String> managedWaypointIconPaths = new HashMap<>();
    private static final long WAYPOINT_APPLY_RETRY_MS = 100L;
    private static boolean unavailableLogged;
    private static boolean legacyCleanupDone;
    private static boolean trackedPlayersVisibilityEnforced;
    private static boolean waypointVisibilityEnforced;
    private static String lastWaypointSignature = "";
    private static String lastWaypointApplyAttemptSignature = "";
    private static long nextWaypointApplyAttemptAtMs;
    private static String lastTrackedPlayersDebugSnapshot = "";
    private static String lastWaypointVisibilityIssue = "";
    private static boolean trackedPlayersVisibilityWarningShown;
    private static Constructor<?> waypointConstructor;
    private static volatile Consumer<String> debugListener;

    private XaeroSyncWaypoints() {}

    public static void setDebugListener(Consumer<String> listener) {
        debugListener = listener;
    }

    public static void clear() {
        lastWaypointSignature = "";
        trackedPlayersVisibilityEnforced = false;
        waypointVisibilityEnforced = false;
        lastWaypointApplyAttemptSignature = "";
        nextWaypointApplyAttemptAtMs = 0L;
        lastTrackedPlayersDebugSnapshot = "";
        lastWaypointVisibilityIssue = "";
        trackedPlayersVisibilityWarningShown = false;
        clearTrackedPlayers();
        clearManagedWaypoints();
        managedWaypointIconPaths.clear();
        cleanupLegacyWaypoints();
    }

    public static void apply(Collection<PlayerMarker> markers) {
        apply(markers, List.of());
    }

    public static void apply(Collection<PlayerMarker> markers, Collection<MapWaypointMarker> mapMarkers) {
        RegistryKey<World> targetDimension = currentDimensionKey();
        boolean trackedPlayersUsable = canRenderTrackedPlayers();
        boolean includePlayersFallback = !trackedPlayersUsable;
        boolean forcePlayersFallback = !trackedPlayersUsable;
        String waypointSignature = "";
        ArrayList<MapWaypointMarker> effectiveMapMarkers = new ArrayList<>();

        try {
            if (trackedPlayersUsable) {
                if (!ensureTrackedPlayersVisible()) {
                    // If config introspection is unavailable, do not duplicate markers via fallback.
                    // We still try tracked-player sync directly through manager updates.
                    if (!trackedPlayersVisibilityWarningShown) {
                        debug("tracked-players visibility check unavailable; proceeding with manager sync.");
                        trackedPlayersVisibilityWarningShown = true;
                    }
                }

                Object manager = getTrackedPlayerManager();
                if (manager == null) {
                    clearTrackedPlayers();
                    includePlayersFallback = true;
                    forcePlayersFallback = true;
                    logUnavailableOnce("Xaero map session is unavailable, skipping tracked player sync.");
                    debug("tracked-players manager unavailable; using waypoint fallback for players.");
                } else {
                    Map<String, UUID> uuidByName = resolveOnlinePlayerUuids();
                    Set<UUID> desired = new HashSet<>();

                    if (markers != null) {
                        for (PlayerMarker marker : markers) {
                            if (marker == null) continue;

                            UUID uuid = resolveUuid(marker, uuidByName);
                            if (uuid == null) {
                                includePlayersFallback = true;
                                effectiveMapMarkers.add(new MapWaypointMarker(
                                    marker.key(),
                                    marker.name(),
                                    marker.dimension(),
                                    marker.x(),
                                    marker.y(),
                                    marker.z(),
                                    "",
                                    true
                                ));
                                continue;
                            }

                            RegistryKey<World> sourceDimension = parseDimensionKey(marker.dimension());
                            RegistryKey<World> markerDimension = sourceDimension != null ? sourceDimension : targetDimension;
                            if (markerDimension == null) markerDimension = World.OVERWORLD;

                            invokeManagerUpdate(
                                manager,
                                uuid,
                                marker.x(),
                                marker.y(),
                                marker.z(),
                                markerDimension
                            );
                            desired.add(uuid);
                        }
                    }

                    if (mapMarkers != null) {
                        for (MapWaypointMarker marker : mapMarkers) {
                            if (marker == null) continue;
                            if (!Double.isFinite(marker.x()) || !Double.isFinite(marker.y()) || !Double.isFinite(marker.z())) continue;
                            effectiveMapMarkers.add(marker);
                        }
                    }

                    for (UUID uuid : new HashSet<>(activeTrackedPlayers)) {
                        if (desired.contains(uuid)) continue;
                        invokeManagerRemove(manager, uuid);
                    }

                    activeTrackedPlayers.clear();
                    activeTrackedPlayers.addAll(desired);
                    unavailableLogged = false;
                    String trackedPlayersSnapshot = String.format(
                        Locale.ROOT,
                        "tracked-players updated: desired=%d active=%d fallbackPlayers=%s",
                        desired.size(),
                        activeTrackedPlayers.size(),
                        includePlayersFallback
                    );
                    if (!trackedPlayersSnapshot.equals(lastTrackedPlayersDebugSnapshot)) {
                        lastTrackedPlayersDebugSnapshot = trackedPlayersSnapshot;
                        debug("%s", trackedPlayersSnapshot);
                    }
                }
            } else {
                clearTrackedPlayers();
                includePlayersFallback = true;
                forcePlayersFallback = true;
                if (mapMarkers != null) effectiveMapMarkers.addAll(mapMarkers);
            }

        } catch (ClassNotFoundException e) {
            clearTrackedPlayers();
            includePlayersFallback = true;
            forcePlayersFallback = true;
            logUnavailableOnce("Xaero World Map mod not found, skipping tracked player sync.");
            debug("tracked-players class missing; using waypoint fallback.");
            if (mapMarkers != null) effectiveMapMarkers.addAll(mapMarkers);
        } catch (Throwable t) {
            includePlayersFallback = true;
            forcePlayersFallback = true;
            AddonTemplate.LOG.debug("[Devils/Ping] Failed to apply Xaero tracked players.", t);
            debug("tracked-players stage failed: %s", t.getClass().getSimpleName());
            if (mapMarkers != null) effectiveMapMarkers.addAll(mapMarkers);
        }

        if (includePlayersFallback && forcePlayersFallback) {
            appendPlayerMarkersAsFallback(markers, effectiveMapMarkers);
        }

        waypointSignature = waypointSignature(effectiveMapMarkers, targetDimension, includePlayersFallback);
        boolean waypointSignatureChanged = !waypointSignature.equals(lastWaypointSignature);
        long nowMs = System.currentTimeMillis();
        boolean waypointAttemptDue = waypointSignatureChanged
            && (!waypointSignature.equals(lastWaypointApplyAttemptSignature) || nowMs >= nextWaypointApplyAttemptAtMs);
        boolean waypointApplySuccessful = !waypointSignatureChanged;
        WaypointApplyStats applyStats = null;
        String refreshResult = waypointSignatureChanged ? "pending" : "skipped-signature-same";
        try {
            if (waypointAttemptDue) {
                ensureWaypointsVisible();
                applyStats = applyManagedWaypoints(effectiveMapMarkers, targetDimension, includePlayersFallback);
                if (applyStats.listAvailable()) {
                    refreshResult = requestWaypointsRefresh();
                    waypointApplySuccessful = true;
                } else {
                    refreshResult = "waypoint-list-unavailable";
                }
            } else if (waypointSignatureChanged) {
                refreshResult = "retry-cooldown";
            }
        } catch (Throwable t) {
            AddonTemplate.LOG.debug("[Devils/XaeroSync] Failed to apply managed waypoints.", t);
            refreshResult = "apply-exception:" + t.getClass().getSimpleName();
            debug("waypoint stage failed: %s", t.getClass().getSimpleName());
        }

        if (waypointSignatureChanged && waypointAttemptDue) {
            if (applyStats != null) {
                debug(
                    "waypoint stage: players=%d mapMarkers=%d desired=%d removed=%d added=%d refresh=%s success=%s fallbackPlayers=%s dim=%s",
                    count(markers),
                    count(mapMarkers),
                    applyStats.desiredCount(),
                    applyStats.removedManagedCount(),
                    applyStats.addedCount(),
                    refreshResult,
                    waypointApplySuccessful,
                    includePlayersFallback,
                    targetDimension == null ? "null" : targetDimension.getValue()
                );
            } else {
                debug(
                    "waypoint stage: players=%d mapMarkers=%d refresh=%s success=%s fallbackPlayers=%s dim=%s",
                    count(markers),
                    count(mapMarkers),
                    refreshResult,
                    waypointApplySuccessful,
                    includePlayersFallback,
                    targetDimension == null ? "null" : targetDimension.getValue()
                );
            }
        }

        if (waypointAttemptDue) {
            lastWaypointApplyAttemptSignature = waypointSignature;
            if (waypointApplySuccessful) nextWaypointApplyAttemptAtMs = 0L;
            else nextWaypointApplyAttemptAtMs = nowMs + WAYPOINT_APPLY_RETRY_MS;
        }

        if (waypointApplySuccessful) {
            lastWaypointSignature = waypointSignature;
            lastWaypointApplyAttemptSignature = waypointSignature;
            nextWaypointApplyAttemptAtMs = 0L;
        }
        cleanupLegacyWaypoints();
    }

    private static void clearTrackedPlayers() {
        if (activeTrackedPlayers.isEmpty()) return;

        try {
            Object manager = getTrackedPlayerManager();
            if (manager == null) {
                activeTrackedPlayers.clear();
                return;
            }

            for (UUID uuid : new HashSet<>(activeTrackedPlayers)) {
                invokeManagerRemove(manager, uuid);
            }
        } catch (Throwable ignored) {
        } finally {
            activeTrackedPlayers.clear();
        }
    }

    private static Object getTrackedPlayerManager() throws Exception {
        Class<?> sessionClass = Class.forName("xaero.map.WorldMapSession");
        Method getCurrentSession = sessionClass.getMethod("getCurrentSession");
        Object session = getCurrentSession.invoke(null);
        if (session == null) return null;

        Object mapProcessor = session.getClass().getMethod("getMapProcessor").invoke(session);
        if (mapProcessor == null) return null;

        return mapProcessor.getClass().getMethod("getClientSyncedTrackedPlayerManager").invoke(mapProcessor);
    }

    private static void invokeManagerUpdate(
        Object manager,
        UUID uuid,
        double x,
        double y,
        double z,
        RegistryKey<World> dimension
    ) throws Exception {
        for (Method method : manager.getClass().getMethods()) {
            if (!method.getName().equals("update")) continue;
            if (method.getParameterCount() != 5) continue;
            method.invoke(manager, uuid, x, y, z, dimension);
            return;
        }
        throw new NoSuchMethodException("update(UUID,double,double,double,RegistryKey)");
    }

    private static void invokeManagerRemove(Object manager, UUID uuid) throws Exception {
        for (Method method : manager.getClass().getMethods()) {
            if (!method.getName().equals("remove")) continue;
            if (method.getParameterCount() != 1) continue;
            method.invoke(manager, uuid);
            return;
        }
        throw new NoSuchMethodException("remove(UUID)");
    }

    private static Map<String, UUID> resolveOnlinePlayerUuids() {
        Map<String, UUID> map = new HashMap<>();
        if (mc.getNetworkHandler() == null) return map;

        for (PlayerListEntry entry : mc.getNetworkHandler().getPlayerList()) {
            if (entry == null) continue;
            GameProfile profile = entry.getProfile();
            if (profile == null || profile.getId() == null || profile.getName() == null) continue;
            map.put(normalize(profile.getName()), profile.getId());
        }

        return map;
    }

    private static WaypointApplyStats applyManagedWaypoints(
        Collection<MapWaypointMarker> markers,
        RegistryKey<World> targetDimension,
        boolean includePlayersFallback
    ) throws Exception {
        List<MapWaypointMarker> desiredMarkers = sanitizeWaypointMarkers(markers, targetDimension, includePlayersFallback);
        HashMap<String, String> iconPathsByWaypoint = new HashMap<>();
        LinkedHashMap<String, MapWaypointMarker> desiredByStateKey = new LinkedHashMap<>();
        for (MapWaypointMarker marker : desiredMarkers) {
            if (marker == null) continue;
            String markerName = formatManagedWaypointName(marker);
            int x = toWaypointBlockCoord(marker.x());
            int y = toWaypointBlockCoord(marker.y());
            int z = toWaypointBlockCoord(marker.z());
            String symbol = marker.player() ? PLAYER_FALLBACK_SYMBOL : PING_FALLBACK_SYMBOL;
            String stateKey = managedWaypointStateKey(markerName, x, y, z, symbol);
            desiredByStateKey.put(stateKey, marker);

            if (!marker.player()) {
                String renderKey = managedWaypointRenderKey(markerName, x, y, z);
                String iconPath = MapIconManager.normalizeIconPath(marker.iconPath());
                if (iconPath.isBlank()) iconPath = MapIconManager.DEFAULT_EMBEDDED_ICON_PATH;
                if (!iconPath.isBlank()) {
                    iconPathsByWaypoint.put(renderKey, iconPath);
                }
            }
        }
        managedWaypointIconPaths.clear();
        managedWaypointIconPaths.putAll(iconPathsByWaypoint);

        List<Object> waypoints = getMinimapWaypointList();
        if (waypoints == null) {
            return new WaypointApplyStats(false, desiredMarkers.size(), 0, 0, 0, 0);
        }

        int beforeCount = waypoints.size();
        int removedManagedCount = 0;
        int addedCount = 0;

        LinkedHashMap<String, Object> existingManagedByStateKey = new LinkedHashMap<>();
        ArrayList<Object> duplicateManagedWaypoints = new ArrayList<>();
        for (Object waypoint : waypoints) {
            if (!isManagedWaypoint(waypoint)) continue;
            String stateKey = managedWaypointStateKey(waypoint);
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

        for (Map.Entry<String, MapWaypointMarker> desiredEntry : desiredByStateKey.entrySet()) {
            if (existingManagedByStateKey.containsKey(desiredEntry.getKey())) continue;
            MapWaypointMarker marker = desiredEntry.getValue();
            Object waypoint = createManagedWaypoint(marker, targetDimension);
            if (waypoint != null) {
                waypoints.add(waypoint);
                addedCount++;
            }
        }
        return new WaypointApplyStats(true, desiredMarkers.size(), removedManagedCount, addedCount, beforeCount, waypoints.size());
    }

    private static void clearManagedWaypoints() {
        try {
            managedWaypointIconPaths.clear();
            List<Object> waypoints = getMinimapWaypointList();
            if (waypoints == null) return;
            waypoints.removeIf(XaeroSyncWaypoints::isManagedWaypoint);
            requestWaypointsRefresh();
        } catch (Throwable ignored) {
        }
    }

    private static String requestWaypointsRefresh() {
        try {
            Object supportMinimap = readStaticField("xaero.map.mods.SupportMods", "xaeroMinimap");
            if (supportMinimap == null) return "support-minimap-null";

            try {
                Method refreshMethod = supportMinimap.getClass().getMethod("requestWaypointsRefresh");
                refreshMethod.setAccessible(true);
                refreshMethod.invoke(supportMinimap);
                return "method:requestWaypointsRefresh";
            } catch (Throwable ignored) {
            }

            // Fallback for versions where the helper method name/signature changed.
            boolean fieldWriteApplied = writeFieldValue(supportMinimap, "refreshWaypoints", true);
            return fieldWriteApplied ? "field:refreshWaypoints" : "refresh-hook-unavailable";
        } catch (Throwable t) {
            return "refresh-exception:" + t.getClass().getSimpleName();
        }
    }

    private static List<MapWaypointMarker> sanitizeWaypointMarkers(
        Collection<MapWaypointMarker> markers,
        RegistryKey<World> targetDimension,
        boolean includePlayersFallback
    ) {
        LinkedHashMap<String, MapWaypointMarker> dedup = new LinkedHashMap<>();
        if (markers == null) return new ArrayList<>();

        for (MapWaypointMarker marker : markers) {
            if (marker == null) continue;
            if (marker.player() && !includePlayersFallback) continue;
            if (!Double.isFinite(marker.x()) || !Double.isFinite(marker.y()) || !Double.isFinite(marker.z())) continue;

            String key = normalize(marker.id());
            if (key.isBlank()) key = normalize(marker.name()) + "|" + Math.round(marker.x()) + "|" + Math.round(marker.z()) + "|" + marker.player();
            dedup.put(key, marker);
        }

        return new ArrayList<>(dedup.values());
    }

    private static List<Object> getMinimapWaypointList() throws Exception {
        // Modern path (1.21.x): BuiltInHudModules -> MinimapSession -> WorldManager -> CurrentWorld -> CurrentWaypointSet.
        Object minimap = readStaticField("xaero.hud.minimap.BuiltInHudModules", "MINIMAP");
        if (minimap != null) {
            Object minimapSession = invokeNoArg(minimap, "getCurrentSession");
            List<Object> list = extractWaypointListFromMinimapSession(minimapSession);
            if (list != null) {
                debug("waypoint list source=minimap-world size=%d", list.size());
                return list;
            }
        }

        // Legacy path for older minimap builds.
        try {
            Class<?> minimapSessionClass = Class.forName("xaero.common.XaeroMinimapSession");
            Object minimapSession = minimapSessionClass.getMethod("getCurrentSession").invoke(null);
            if (minimapSession != null) {
                Object waypointsManager = invokeNoArg(minimapSession, "getWaypointsManager");
                Object waypointSet = waypointsManager == null ? null : invokeNoArg(waypointsManager, "getWaypoints");
                List<Object> list = extractWaypointList(waypointSet);
                if (list != null) {
                    debug("waypoint list source=legacy-minimap-session size=%d", list.size());
                    return list;
                }
            }
        } catch (Throwable ignored) {
        }

        // Last-resort path via world map support module internals.
        Object supportMinimap = readStaticField("xaero.map.mods.SupportMods", "xaeroMinimap");
        if (supportMinimap != null) {
            Object waypointWorld = readFieldValue(supportMinimap, "waypointWorld");
            if (waypointWorld == null) waypointWorld = readFieldValue(supportMinimap, "mapWaypointWorld");
            List<Object> list = extractWaypointListFromWorld(waypointWorld);
            if (list != null) {
                debug("waypoint list source=supportmods-waypoint-world size=%d", list.size());
                return list;
            }
        }

        debug("waypoint list unavailable in all known minimap paths.");
        return null;
    }

    private static List<Object> extractWaypointListFromMinimapSession(Object minimapSession) {
        if (minimapSession == null) return null;

        Object worldManager = invokeNoArg(minimapSession, "getWorldManager");
        Object currentWorld = worldManager == null ? null : invokeNoArg(worldManager, "getCurrentWorld");
        List<Object> list = extractWaypointListFromWorld(currentWorld);
        if (list != null) return list;

        // Compatibility fallback for versions exposing a waypoints manager directly.
        Object waypointsManager = invokeNoArg(minimapSession, "getWaypointsManager");
        Object waypointSet = waypointsManager == null ? null : invokeNoArg(waypointsManager, "getWaypoints");
        return extractWaypointList(waypointSet);
    }

    private static List<Object> extractWaypointListFromWorld(Object minimapWorld) {
        if (minimapWorld == null) return null;

        Object waypointSet = invokeNoArg(minimapWorld, "getCurrentWaypointSet");
        if (waypointSet == null) {
            Object currentSetId = invokeNoArg(minimapWorld, "getCurrentWaypointSetId");
            if (currentSetId instanceof String setId && !setId.isBlank()) {
                waypointSet = invokeSingleArg(minimapWorld, "getWaypointSet", setId);
            }
        }
        if (waypointSet == null) {
            Object iterableSets = invokeNoArg(minimapWorld, "getIterableWaypointSets");
            if (iterableSets instanceof Iterable<?> iterable) {
                for (Object candidateSet : iterable) {
                    waypointSet = candidateSet;
                    if (waypointSet != null) break;
                }
            }
        }
        return extractWaypointList(waypointSet);
    }

    private static boolean isManagedWaypoint(Object waypoint) {
        if (waypoint == null) return false;
        Boolean temporary = invokeBooleanNoArg(waypoint, "isTemporary");
        if (!Boolean.TRUE.equals(temporary)) return false;

        String name = readWaypointName(waypoint);
        if (name == null) return false;
        if (name.endsWith(MANAGED_WAYPOINT_SUFFIX) || name.endsWith(MANAGED_WAYPOINT_SUFFIX_LEGACY_VISIBLE)) return true;

        String symbol = readWaypointSymbol(waypoint);
        String normalizedName = normalize(name);
        if ((PING_FALLBACK_SYMBOL.equals(symbol) || INVISIBLE_WAYPOINT_SYMBOL.equals(symbol) || normalize(symbol).isBlank()) && normalizedName.startsWith("[ping]")) return true;
        return PLAYER_FALLBACK_SYMBOL.equals(symbol) && normalizedName.startsWith("[player]");
    }

    public static boolean isDevilsManagedWaypoint(Object waypoint) {
        return isManagedWaypoint(waypoint);
    }

    public static String resolveManagedWaypointRenderName(Object waypoint, String originalName) {
        if (!isManagedWaypoint(waypoint)) return originalName;
        String strippedName = stripManagedWaypointSuffix(originalName == null ? "" : originalName.trim());
        if (strippedName.isBlank()) return strippedName;
        if (!strippedName.regionMatches(true, 0, "[PING]", 0, 6)) return strippedName;

        String sender = extractPingSenderFromLabel(strippedName);
        if (sender.isBlank()) sender = "Marker";
        double x = readWaypointCoordinate(waypoint, "getX");
        double y = readWaypointCoordinate(waypoint, "getY");
        double z = readWaypointCoordinate(waypoint, "getZ");
        String coords = formatWaypointCoords(x, y, z);
        return formatManagedPingLabel(sender, coords, resolvePingInfoMode());
    }

    public static String resolveManagedWaypointIconPath(Object waypoint) {
        String rawName = readWaypointName(waypoint);
        String symbol = readWaypointSymbol(waypoint);
        if (!isManagedWaypoint(waypoint)) {
            String normalizedName = normalize(rawName);
            boolean looksLikeManagedPing = (PING_FALLBACK_SYMBOL.equals(symbol) || INVISIBLE_WAYPOINT_SYMBOL.equals(symbol) || normalize(symbol).isBlank()) && normalizedName.startsWith("[ping]");
            boolean looksLikeManagedPlayer = PLAYER_FALLBACK_SYMBOL.equals(symbol) && normalizedName.startsWith("[player]");
            if (!looksLikeManagedPing && !looksLikeManagedPlayer) return "";
        }

        String name = stripManagedWaypointSuffix(rawName == null ? "" : rawName);
        int x = readWaypointCoordinate(waypoint, "getX");
        int y = readWaypointCoordinate(waypoint, "getY");
        int z = readWaypointCoordinate(waypoint, "getZ");
        String key = managedWaypointRenderKey(name, x, y, z);
        String direct = managedWaypointIconPaths.getOrDefault(key, "");
        if (!direct.isBlank()) return direct;

        String strippedLegacyName = stripLegacyIconTag(name);
        if (!strippedLegacyName.equals(name)) {
            String strippedKey = managedWaypointRenderKey(strippedLegacyName, x, y, z);
            String strippedLookup = managedWaypointIconPaths.getOrDefault(strippedKey, "");
            if (!strippedLookup.isBlank()) return strippedLookup;
        }

        String legacyPath = parseLegacyIconPath(rawName == null ? "" : rawName);
        if (!legacyPath.isBlank()) return legacyPath;
        return MapIconManager.DEFAULT_EMBEDDED_ICON_PATH;
    }

    private static String stripManagedWaypointSuffix(String rawName) {
        String value = rawName == null ? "" : rawName;
        value = stripLegacyIconTag(value);
        if (value.endsWith(MANAGED_WAYPOINT_SUFFIX)) return value.substring(0, value.length() - MANAGED_WAYPOINT_SUFFIX.length());
        if (value.endsWith(MANAGED_WAYPOINT_SUFFIX_LEGACY_VISIBLE)) return value.substring(0, value.length() - MANAGED_WAYPOINT_SUFFIX_LEGACY_VISIBLE.length());
        return value;
    }

    private static String stripLegacyIconTag(String rawName) {
        String value = rawName == null ? "" : rawName;
        int index = findLegacyIconTagIndex(value);
        if (index >= 0) return value.substring(0, index).trim();
        return value;
    }

    private static int findLegacyIconTagIndex(String value) {
        if (value == null || value.isBlank()) return -1;
        int hidden = value.indexOf("\u2063icon=");
        if (hidden >= 0) return hidden;
        String lowered = value.toLowerCase(Locale.ROOT);
        int plain = lowered.indexOf(" icon=");
        if (plain >= 0 && lowered.contains("assets/")) return plain;
        return -1;
    }

    private static String parseLegacyIconPath(String rawName) {
        if (rawName == null || rawName.isBlank()) return "";
        int hidden = rawName.indexOf("\u2063icon=");
        if (hidden >= 0) {
            String value = rawName.substring(hidden + 6).trim();
            int separator = value.indexOf('\u2063');
            if (separator >= 0) value = value.substring(0, separator).trim();
            return MapIconManager.normalizeIconPath(value);
        }
        String lowered = rawName.toLowerCase(Locale.ROOT);
        int plain = lowered.indexOf("icon=");
        if (plain >= 0) {
            String value = rawName.substring(plain + 5).trim();
            int separator = value.indexOf(' ');
            if (separator >= 0) value = value.substring(0, separator).trim();
            return MapIconManager.normalizeIconPath(value);
        }
        return "";
    }

    private static String managedWaypointRenderKey(String name, int x, int y, int z) {
        return normalize(name) + "|" + x + "|" + y + "|" + z;
    }

    private static String managedWaypointStateKey(String name, int x, int y, int z, String symbol) {
        return managedWaypointRenderKey(name, x, y, z) + "|" + normalize(symbol);
    }

    private static String managedWaypointStateKey(Object waypoint) {
        if (waypoint == null) return "";
        String name = stripManagedWaypointSuffix(readWaypointName(waypoint));
        int x = readWaypointCoordinate(waypoint, "getX");
        int y = readWaypointCoordinate(waypoint, "getY");
        int z = readWaypointCoordinate(waypoint, "getZ");
        String symbol = readWaypointSymbol(waypoint);
        return managedWaypointStateKey(name, x, y, z, symbol);
    }

    private static int readWaypointCoordinate(Object waypoint, String methodName) {
        Object value = invokeNoArg(waypoint, methodName);
        if (value instanceof Number number) return number.intValue();
        return 0;
    }

    private static String formatWaypointCoords(double x, double y, double z) {
        return String.format(Locale.ROOT, "%d %d %d", Math.round(x), Math.round(y), Math.round(z));
    }

    private static String formatManagedPingLabel(String sender, String coords, Ping.InfoMode mode) {
        String safeSender = sender == null || sender.isBlank() ? "Marker" : sender;
        String safeCoords = coords == null || coords.isBlank() ? "0 0 0" : coords;
        return switch (mode) {
            case Coords -> "[PING] " + safeSender + " | " + safeCoords;
            case Distance -> "[PING] " + safeSender;
        };
    }

    private static Ping.InfoMode resolvePingInfoMode() {
        try {
            Modules modules = Modules.get();
            if (modules == null) return Ping.InfoMode.Distance;
            Ping ping = modules.get(Ping.class);
            if (ping == null) return Ping.InfoMode.Distance;
            Ping.InfoMode mode = ping.xaeroInfoMode();
            return mode == null ? Ping.InfoMode.Distance : mode;
        } catch (Throwable ignored) {
            return Ping.InfoMode.Distance;
        }
    }

    private static String extractPingSenderFromLabel(String raw) {
        if (raw == null) return "";
        String value = raw.trim();
        if (value.regionMatches(true, 0, "[PING]", 0, 6)) {
            value = value.substring(6).trim();
        }

        // Legacy: "[PING] / nick / distance".
        if (value.startsWith("/")) value = value.substring(1).trim();
        if (value.contains("|")) {
            String[] parts = value.split("\\|");
            value = parts.length == 0 ? value : parts[0].trim();
        } else if (value.contains("/") || value.contains("\\")) {
            String[] parts = value.replace('\\', '/').split("/");
            if (parts.length >= 2) {
                value = parts[0].trim();
            } else {
                value = parts[parts.length - 1].trim();
            }
        }

        int lastSpace = value.lastIndexOf(' ');
        if (lastSpace > 0) {
            String tail = value.substring(lastSpace + 1).trim();
            if (looksLikeDistanceToken(tail)) value = value.substring(0, lastSpace).trim();
        }
        return value;
    }

    private static boolean looksLikeDistanceToken(String token) {
        if (token == null) return false;
        String value = token.trim().toLowerCase(Locale.ROOT);
        if (value.isBlank()) return false;
        if (value.equals("~") || value.equals("--")) return true;
        return value.matches("\\d+(?:\\.\\d+)?(?:m|k)?");
    }

    private static Object createManagedWaypoint(MapWaypointMarker marker, RegistryKey<World> targetDimension) throws Exception {
        Constructor<?> constructor = resolveWaypointConstructor();
        if (constructor == null) {
            debug("waypoint constructor unresolved, skipping marker '%s'.", marker == null ? "" : marker.name());
            return null;
        }

        int x = toWaypointBlockCoord(marker.x());
        int y = toWaypointBlockCoord(marker.y());
        int z = toWaypointBlockCoord(marker.z());
        int color = marker.player() ? PLAYER_WAYPOINT_COLOR : MARKER_WAYPOINT_COLOR;

        String markerName = formatManagedWaypointName(marker);
        String name = markerName;
        String label = marker.player() ? PLAYER_FALLBACK_SYMBOL : PING_FALLBACK_SYMBOL;

        constructor.setAccessible(true);
        return constructor.newInstance(x, y, z, name, label, color, 0, true);
    }

    private static int toWaypointBlockCoord(double value) {
        if (!Double.isFinite(value)) return 0;
        return (int) Math.floor(value);
    }

    private static String formatManagedWaypointName(MapWaypointMarker marker) {
        if (marker == null) return INVISIBLE_WAYPOINT_NAME;

        String rawName = marker.name() == null ? "" : marker.name().trim();
        rawName = stripLegacyIconTag(rawName);
        if (marker.player()) {
            if (rawName.regionMatches(true, 0, "[PING]", 0, 6)) {
                String stripped = rawName.substring(6).trim();
                return stripped.isBlank() ? "[PING]" : "[PING] " + stripped;
            }
            if (rawName.isBlank()) return "[PLAYER] Player";
            if (rawName.regionMatches(true, 0, "[PLAYER]", 0, 8)) {
                String stripped = rawName.substring(8).trim();
                return stripped.isBlank() ? "[PLAYER] Player" : "[PLAYER] " + stripped;
            }
            if (rawName.regionMatches(true, 0, "[P]", 0, 3)) {
                String stripped = rawName.substring(3).trim();
                return stripped.isBlank() ? "[PLAYER] Player" : "[PLAYER] " + stripped;
            }
            return "[PLAYER] " + rawName;
        }

        return rawName.isBlank() ? "[PING]" : rawName;
    }

    private static Constructor<?> resolveWaypointConstructor() throws Exception {
        if (waypointConstructor != null) return waypointConstructor;
        Class<?> waypointClass = Class.forName("xaero.common.minimap.waypoints.Waypoint");
        for (Constructor<?> constructor : waypointClass.getDeclaredConstructors()) {
            Class<?>[] params = constructor.getParameterTypes();
            if (params.length != 8) continue;
            if (!isIntegerLike(params[0]) || !isIntegerLike(params[1]) || !isIntegerLike(params[2])) continue;
            if (params[3] != String.class || params[4] != String.class) continue;
            if (!isIntegerLike(params[5]) || !isIntegerLike(params[6])) continue;
            if (params[7] != boolean.class && params[7] != Boolean.class) continue;
            waypointConstructor = constructor;
            debug("waypoint constructor resolved: %s", constructor.toGenericString());
            return waypointConstructor;
        }
        debug("waypoint constructor not found in xaero.common.minimap.waypoints.Waypoint.");
        return null;
    }

    private static boolean isIntegerLike(Class<?> type) {
        return type == int.class || type == Integer.class;
    }

    private static boolean canRenderTrackedPlayers() {
        if (mc == null) return false;
        if (mc.getServer() != null) return true;
        try {
            return ensurePseudoServerLevelId();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean ensurePseudoServerLevelId() {
        if (mc == null || mc.world == null) return false;
        if (mc.getServer() != null) return true;

        Object worldData;
        try {
            Class<?> helperClass = Class.forName("xaero.map.mcworld.WorldMapClientWorldDataHelper");
            Method getter = helperClass.getMethod("getCurrentWorldData");
            worldData = getter.invoke(null);
        } catch (Throwable ignored) {
            return false;
        }
        if (worldData == null) return false;

        int pseudoLevelId = computePseudoServerLevelId();
        Object current = readFieldValue(worldData, "serverLevelId");
        if (!(current instanceof Integer integer) || integer.intValue() != pseudoLevelId) {
            boolean updatedViaProcessor = false;
            try {
                Class<?> sessionClass = Class.forName("xaero.map.WorldMapSession");
                Object session = sessionClass.getMethod("getCurrentSession").invoke(null);
                if (session != null) {
                    Object mapProcessor = session.getClass().getMethod("getMapProcessor").invoke(session);
                    if (mapProcessor != null) {
                        Method onServerLevelId = mapProcessor.getClass().getMethod("onServerLevelId", int.class);
                        onServerLevelId.setAccessible(true);
                        onServerLevelId.invoke(mapProcessor, pseudoLevelId);
                        updatedViaProcessor = true;
                    }
                }
            } catch (Throwable ignored) {
            }

            if (!updatedViaProcessor) {
                writeFieldValue(worldData, "serverLevelId", pseudoLevelId);
                writeFieldValue(worldData, "usedServerLevelId", pseudoLevelId);
            }
        }

        return readFieldValue(worldData, "serverLevelId") instanceof Integer;
    }

    private static int computePseudoServerLevelId() {
        String key = "";
        try {
            if (mc != null && mc.getCurrentServerEntry() != null && mc.getCurrentServerEntry().address != null) {
                key = mc.getCurrentServerEntry().address;
            }
        } catch (Throwable ignored) {
        }

        if (key == null || key.isBlank()) {
            try {
                if (mc != null && mc.getNetworkHandler() != null && mc.getNetworkHandler().getConnection() != null) {
                    key = String.valueOf(mc.getNetworkHandler().getConnection().getAddress());
                }
            } catch (Throwable ignored) {
            }
        }

        if (key == null || key.isBlank()) key = "devils-sync";
        int hash = key.trim().toLowerCase(Locale.ROOT).hashCode();
        if (hash == Integer.MIN_VALUE) return 1;
        return Math.max(1, Math.abs(hash));
    }

    private static boolean ensureTrackedPlayersVisible() {
        if (trackedPlayersVisibilityEnforced) return true;

        try {
            Class<?> worldMapClass = Class.forName("xaero.map.WorldMap");
            Object worldMapInstance = worldMapClass.getField("INSTANCE").get(null);
            if (worldMapInstance == null) return false;

            Object configs = invokeNoArg(worldMapInstance, "getConfigs");
            if (configs == null) return false;

            Object clientConfigManager = invokeNoArg(configs, "getClientConfigManager");
            if (clientConfigManager == null) return false;

            Class<?> optionsClass = Class.forName("xaero.map.common.config.option.WorldMapProfiledConfigOptions");
            Object displayTrackedPlayersOption = optionsClass.getField("DISPLAY_TRACKED_PLAYERS").get(null);

            Object serverSynced = invokeNoArg(clientConfigManager, "getServerSynced");
            if (serverSynced != null) {
                Object serverValue = invokeSingleArg(serverSynced, "getEffective", displayTrackedPlayersOption);
                if (Boolean.TRUE.equals(serverValue)) {
                    trackedPlayersVisibilityEnforced = true;
                    return true;
                }
            }

            Object currentProfile = invokeNoArg(clientConfigManager, "getCurrentProfile");
            if (currentProfile == null) return false;

            Object current = invokeSingleArg(currentProfile, "get", displayTrackedPlayersOption);
            if (Boolean.TRUE.equals(current)) {
                trackedPlayersVisibilityEnforced = true;
                return true;
            }

            invokeTwoArgs(currentProfile, "set", displayTrackedPlayersOption, Boolean.TRUE);

            Object profileIo = invokeNoArg(configs, "getClientConfigProfileIO");
            if (profileIo != null) {
                invokeSingleArg(profileIo, "save", currentProfile);
            }

            Object updatedCurrent = invokeSingleArg(currentProfile, "get", displayTrackedPlayersOption);
            trackedPlayersVisibilityEnforced = Boolean.TRUE.equals(updatedCurrent);
            return trackedPlayersVisibilityEnforced;
        } catch (Throwable t) {
            AddonTemplate.LOG.debug("[Devils/XaeroSync] Failed to enforce DISPLAY_TRACKED_PLAYERS.", t);
            return false;
        }
    }

    private static void ensureWaypointsVisible() {
        if (waypointVisibilityEnforced) return;

        try {
            Class<?> worldMapClass = Class.forName("xaero.map.WorldMap");
            Object worldMapInstance = worldMapClass.getField("INSTANCE").get(null);
            if (worldMapInstance == null) {
                debugWaypointVisibilityIssue("world map instance unavailable.");
                return;
            }

            Object configs = invokeNoArg(worldMapInstance, "getConfigs");
            if (configs == null) {
                debugWaypointVisibilityIssue("configs unavailable.");
                return;
            }

            Object clientConfigManager = invokeNoArg(configs, "getClientConfigManager");
            if (clientConfigManager == null) {
                debugWaypointVisibilityIssue("client config manager unavailable.");
                return;
            }

            boolean profileChanged = false;
            Object currentProfile = invokeNoArg(clientConfigManager, "getCurrentProfile");
            if (currentProfile != null) {
                Object waypointsOption = readStaticField(
                    "xaero.map.common.config.option.WorldMapProfiledConfigOptions",
                    "WAYPOINTS"
                );
                if (waypointsOption != null) {
                    Object currentWaypoints = invokeSingleArg(currentProfile, "get", waypointsOption);
                    if (!Boolean.TRUE.equals(currentWaypoints)) {
                        invokeTwoArgs(currentProfile, "set", waypointsOption, Boolean.TRUE);
                        profileChanged = true;
                    }
                }

                Object renderWaypointsOption = readStaticField(
                    "xaero.map.common.config.option.WorldMapProfiledConfigOptions",
                    "RENDER_WAYPOINTS"
                );
                if (renderWaypointsOption != null) {
                    Object currentRenderWaypoints = invokeSingleArg(currentProfile, "get", renderWaypointsOption);
                    if (!Boolean.TRUE.equals(currentRenderWaypoints)) {
                        invokeTwoArgs(currentProfile, "set", renderWaypointsOption, Boolean.TRUE);
                        profileChanged = true;
                    }
                }

                Object minZoomLocalWaypointsOption = readStaticField(
                    "xaero.map.common.config.option.WorldMapProfiledConfigOptions",
                    "MIN_ZOOM_LOCAL_WAYPOINTS"
                );
                if (minZoomLocalWaypointsOption != null) {
                    Object currentMinZoom = invokeSingleArg(currentProfile, "get", minZoomLocalWaypointsOption);
                    double minZoomValue = currentMinZoom instanceof Number number ? number.doubleValue() : 0.0;
                    if (Math.abs(minZoomValue) > 1.0E-6) {
                        invokeTwoArgs(currentProfile, "set", minZoomLocalWaypointsOption, 0.0);
                        profileChanged = true;
                    }
                }
            }

            boolean minimapProfileChanged = false;
            Object minimapProfile = null;
            Object minimapConfigs = null;
            try {
                Object hudModInstance = readStaticField("xaero.common.HudMod", "INSTANCE");
                if (hudModInstance != null) {
                    minimapConfigs = invokeNoArg(hudModInstance, "getHudConfigs");
                    Object minimapClientConfigManager = minimapConfigs == null ? null : invokeNoArg(minimapConfigs, "getClientConfigManager");
                    minimapProfile = minimapClientConfigManager == null ? null : invokeNoArg(minimapClientConfigManager, "getCurrentProfile");
                }
            } catch (Throwable ignored) {
            }
            if (minimapProfile != null) {
                minimapProfileChanged |= ensureProfileOptionValue(
                    minimapProfile,
                    "xaero.hud.minimap.common.config.option.MinimapProfiledConfigOptions",
                    "WAYPOINTS_IN_WORLD",
                    Boolean.TRUE
                );
                minimapProfileChanged |= ensureProfileOptionValue(
                    minimapProfile,
                    "xaero.hud.minimap.common.config.option.MinimapProfiledConfigOptions",
                    "WAYPOINT_NAME_IN_WORLD",
                    Boolean.TRUE
                );
                minimapProfileChanged |= ensureProfileOptionValue(
                    minimapProfile,
                    "xaero.hud.minimap.common.config.option.MinimapProfiledConfigOptions",
                    "WAYPOINTS_ON_MINIMAP",
                    Boolean.TRUE
                );
                // Keep the marker highlighted at any distance so the label is always rendered.
                minimapProfileChanged |= ensureProfileOptionValue(
                    minimapProfile,
                    "xaero.hud.minimap.common.config.option.MinimapProfiledConfigOptions",
                    "WAYPOINT_DISTANCE_IN_WORLD",
                    2
                );
                minimapProfileChanged |= ensureProfileOptionValue(
                    minimapProfile,
                    "xaero.hud.minimap.common.config.option.MinimapProfiledConfigOptions",
                    "WAYPOINT_SHORT_DISTANCE_IN_WORLD",
                    Boolean.TRUE
                );
                // Keep readable but not oversized labels/icons.
                minimapProfileChanged |= ensureProfileOptionValue(
                    minimapProfile,
                    "xaero.hud.minimap.common.config.option.MinimapProfiledConfigOptions",
                    "WAYPOINT_ICON_SCALE_IN_WORLD",
                    0
                );
                minimapProfileChanged |= ensureProfileOptionValue(
                    minimapProfile,
                    "xaero.hud.minimap.common.config.option.MinimapProfiledConfigOptions",
                    "WAYPOINT_NAME_SCALE_IN_WORLD",
                    0
                );
                minimapProfileChanged |= ensureProfileOptionValue(
                    minimapProfile,
                    "xaero.hud.minimap.common.config.option.MinimapProfiledConfigOptions",
                    "WAYPOINT_DISTANCE_SCALE_IN_WORLD",
                    0
                );
                minimapProfileChanged |= ensureProfileOptionValue(
                    minimapProfile,
                    "xaero.hud.minimap.common.config.option.MinimapProfiledConfigOptions",
                    "WAYPOINT_CLOSE_SCALE_IN_WORLD",
                    1.0
                );
            }

            boolean primaryChanged = false;
            Object primaryConfigManager = invokeNoArg(clientConfigManager, "getPrimaryConfigManager");
            if (primaryConfigManager != null) {
                Object primaryConfig = invokeNoArg(primaryConfigManager, "getConfig");
                if (primaryConfig != null) {
                    Object onlyCurrentMapWaypointsOption = readStaticField(
                        "xaero.map.config.primary.option.WorldMapPrimaryClientConfigOptions",
                        "ONLY_CURRENT_MAP_WAYPOINTS"
                    );
                    if (onlyCurrentMapWaypointsOption != null) {
                        Object onlyCurrentMapWaypoints = invokeSingleArg(primaryConfig, "get", onlyCurrentMapWaypointsOption);
                        if (Boolean.TRUE.equals(onlyCurrentMapWaypoints)) {
                            invokeTwoArgs(primaryConfig, "set", onlyCurrentMapWaypointsOption, Boolean.FALSE);
                            primaryChanged = true;
                        }
                    }
                }
            }

            if (profileChanged) {
                Object profileIo = invokeNoArg(configs, "getClientConfigProfileIO");
                if (profileIo != null && currentProfile != null) {
                    invokeSingleArg(profileIo, "save", currentProfile);
                }
            }

            if (minimapProfileChanged) {
                Object minimapProfileIo = minimapConfigs == null ? null : invokeNoArg(minimapConfigs, "getClientConfigProfileIO");
                if (minimapProfileIo != null && minimapProfile != null) {
                    invokeSingleArg(minimapProfileIo, "save", minimapProfile);
                }
            }

            if (primaryChanged) {
                Object primaryIo = invokeNoArg(configs, "getPrimaryClientConfigManagerIO");
                if (primaryIo != null) {
                    invokeNoArg(primaryIo, "save");
                }
            }

            waypointVisibilityEnforced = true;
            lastWaypointVisibilityIssue = "";
            debug(
                "waypoint visibility enforced: profileChanged=%s primaryChanged=%s minimapProfileChanged=%s",
                profileChanged,
                primaryChanged,
                minimapProfileChanged
            );
        } catch (Throwable t) {
            AddonTemplate.LOG.debug("[Devils/XaeroSync] Failed to enforce waypoint visibility options.", t);
            debugWaypointVisibilityIssue("enforce failed: " + t.getClass().getSimpleName());
        }
    }

    private static boolean ensureProfileOptionValue(Object profile, String optionClassName, String optionFieldName, Object desiredValue) {
        if (profile == null || optionClassName == null || optionClassName.isBlank() || optionFieldName == null || optionFieldName.isBlank()) {
            return false;
        }

        Object option = readStaticField(optionClassName, optionFieldName);
        if (option == null) return false;

        Object current = invokeSingleArg(profile, "get", option);
        if (Objects.equals(current, desiredValue)) return false;
        invokeTwoArgs(profile, "set", option, desiredValue);
        Object updated = invokeSingleArg(profile, "get", option);
        return Objects.equals(updated, desiredValue);
    }

    private static void debugWaypointVisibilityIssue(String reason) {
        String normalized = reason == null ? "" : reason.trim();
        if (normalized.isBlank()) return;
        if (normalized.equals(lastWaypointVisibilityIssue)) return;
        lastWaypointVisibilityIssue = normalized;
        debug("waypoint visibility: %s", normalized);
    }

    private static UUID resolveUuid(PlayerMarker marker, Map<String, UUID> uuidByName) {
        if (marker == null) return null;
        String normalizedName = normalize(marker.name());
        UUID byName = uuidByName.get(normalizedName);
        if (byName != null) return byName;

        String raw = marker.uuid() == null ? "" : marker.uuid().trim();
        if (!raw.isBlank()) {
            try {
                return UUID.fromString(raw);
            } catch (Throwable ignored) {
                if (!normalizedName.isBlank()) {
                    return UUID.nameUUIDFromBytes(("OfflinePlayer:" + normalizedName).getBytes(StandardCharsets.UTF_8));
                }
            }
        }

        if (raw.isBlank()) return null;
        if (normalizedName.isBlank()) return null;
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + normalizedName).getBytes(StandardCharsets.UTF_8));
    }

    private static void appendPlayerMarkersAsFallback(
        Collection<PlayerMarker> markers,
        Collection<MapWaypointMarker> destination
    ) {
        if (markers == null || destination == null) return;
        for (PlayerMarker marker : markers) {
            if (marker == null) continue;
            destination.add(new MapWaypointMarker(
                marker.key(),
                marker.name(),
                marker.dimension(),
                marker.x(),
                marker.y(),
                marker.z(),
                "",
                true
            ));
        }
    }

    private static RegistryKey<World> currentDimensionKey() {
        if (mc.world == null) return World.OVERWORLD;
        return mc.world.getRegistryKey();
    }

    private static RegistryKey<World> parseDimensionKey(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            Identifier id = raw.contains(":") ? Identifier.of(raw) : Identifier.of("minecraft", raw);
            return RegistryKey.of(RegistryKeys.WORLD, id);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String waypointSignature(
        Collection<MapWaypointMarker> markers,
        RegistryKey<World> targetDimension,
        boolean includePlayersFallback
    ) {
        ArrayList<String> rows = new ArrayList<>();
        rows.add("target=" + (targetDimension == null ? "null" : targetDimension.getValue()));
        rows.add("fallbackPlayers=" + includePlayersFallback);
        for (MapWaypointMarker marker : sanitizeWaypointMarkers(markers, targetDimension, includePlayersFallback)) {
            rows.add(
                normalize(marker.id()) + "|"
                    + normalize(marker.name()) + "|"
                    + normalize(marker.dimension()) + "|"
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

    private static void cleanupLegacyWaypoints() {
        if (legacyCleanupDone) return;

        try {
            Class<?> sessionClass = Class.forName("xaero.hud.minimap.BuiltInHudModules");
            Object minimap = sessionClass.getField("MINIMAP").get(null);
            Object session = minimap.getClass().getMethod("getCurrentSession").invoke(minimap);
            if (session == null) return;

            Object worldManager = session.getClass().getMethod("getWorldManager").invoke(session);
            if (worldManager == null) return;

            Object world = worldManager.getClass().getMethod("getCurrentWorld").invoke(worldManager);
            if (world == null) return;

            Object set = world.getClass().getMethod("getWaypointSet", String.class).invoke(world, LEGACY_SET_NAME);
            if (set == null) {
                legacyCleanupDone = true;
                return;
            }

            List<Object> list = extractWaypointList(set);
            if (list != null) {
                list.removeIf(waypoint -> {
                    String name = readWaypointName(waypoint);
                    return name != null && name.endsWith(LEGACY_WAYPOINT_SUFFIX);
                });
            }

            trySaveWorld(session, world);
            legacyCleanupDone = true;
        } catch (ClassNotFoundException ignored) {
            legacyCleanupDone = true;
        } catch (Throwable ignored) {
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Object> extractWaypointList(Object set) {
        if (set == null) return null;

        for (String methodName : List.of("getWaypoints", "getList")) {
            try {
                Object result = set.getClass().getMethod(methodName).invoke(set);
                if (result instanceof List<?> list) return (List<Object>) list;
            } catch (Throwable ignored) {
            }
        }

        for (Field field : set.getClass().getDeclaredFields()) {
            if (!List.class.isAssignableFrom(field.getType())) continue;
            try {
                field.setAccessible(true);
                Object value = field.get(set);
                if (value instanceof List<?> list) return (List<Object>) list;
            } catch (Throwable ignored) {
            }
        }

        return null;
    }

    private static String readWaypointName(Object waypoint) {
        if (waypoint == null) return null;
        try {
            Object value = waypoint.getClass().getMethod("getName").invoke(waypoint);
            return value instanceof String s ? s : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String readWaypointSymbol(Object waypoint) {
        if (waypoint == null) return null;
        try {
            Object value = waypoint.getClass().getMethod("getSymbol").invoke(waypoint);
            return value instanceof String s ? s : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void trySaveWorld(Object session, Object world) {
        if (session == null || world == null) return;
        try {
            Object worldIo = session.getClass().getMethod("getWorldManagerIO").invoke(session);
            if (worldIo == null) return;

            for (Method method : worldIo.getClass().getMethods()) {
                if (!method.getName().equals("saveWorld")) continue;
                Class<?>[] params = method.getParameterTypes();
                if (params.length == 1 && params[0].isAssignableFrom(world.getClass())) {
                    method.invoke(worldIo, world);
                    return;
                }
                if (params.length == 2 && params[0].isAssignableFrom(world.getClass()) && params[1] == boolean.class) {
                    method.invoke(worldIo, world, false);
                    return;
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static Object readFieldValue(Object target, String fieldName) {
        if (target == null || fieldName == null || fieldName.isBlank()) return null;
        Class<?> cursor = target.getClass();
        while (cursor != null) {
            try {
                Field field = cursor.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException ignored) {
                cursor = cursor.getSuperclass();
            } catch (Throwable ignored) {
                return null;
            }
        }
        return null;
    }

    private static Object readStaticField(String className, String fieldName) {
        if (className == null || className.isBlank() || fieldName == null || fieldName.isBlank()) return null;
        try {
            Class<?> klass = Class.forName(className);
            Field field = klass.getField(fieldName);
            field.setAccessible(true);
            return field.get(null);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean writeFieldValue(Object target, String fieldName, Object value) {
        if (target == null || fieldName == null || fieldName.isBlank()) return false;
        Class<?> cursor = target.getClass();
        while (cursor != null) {
            try {
                Field field = cursor.getDeclaredField(fieldName);
                field.setAccessible(true);
                field.set(target, value);
                return true;
            } catch (NoSuchFieldException ignored) {
                cursor = cursor.getSuperclass();
            } catch (Throwable ignored) {
                return false;
            }
        }
        return false;
    }

    private static void logUnavailableOnce(String message) {
        if (unavailableLogged) return;
        unavailableLogged = true;
        AddonTemplate.LOG.debug("[Devils/Ping] " + message);
    }

    private static int count(Collection<?> values) {
        return values == null ? 0 : values.size();
    }

    private static int countManagedWaypoints(List<Object> waypoints) {
        if (waypoints == null || waypoints.isEmpty()) return 0;
        int count = 0;
        for (Object waypoint : waypoints) {
            if (isManagedWaypoint(waypoint)) count++;
        }
        return count;
    }

    private static void debug(String format, Object... args) {
        Consumer<String> listener = debugListener;
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

    private static Object invokeNoArg(Object target, String methodName) {
        if (target == null || methodName == null || methodName.isBlank()) return null;
        try {
            Method method = target.getClass().getMethod(methodName);
            return method.invoke(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object invokeSingleArg(Object target, String methodName, Object arg) {
        if (target == null || methodName == null || methodName.isBlank()) return null;
        Method fallback = null;
        for (Method method : target.getClass().getMethods()) {
            if (!method.getName().equals(methodName) || method.getParameterCount() != 1) continue;
            if (!isParameterCompatible(method.getParameterTypes()[0], arg)) {
                if (fallback == null) fallback = method;
                continue;
            }
            try {
                method.setAccessible(true);
                return method.invoke(target, arg);
            } catch (Throwable ignored) {
                return null;
            }
        }

        if (fallback != null) {
            try {
                fallback.setAccessible(true);
                return fallback.invoke(target, arg);
            } catch (Throwable ignored) {
                return null;
            }
        }

        return null;
    }

    private static Boolean invokeBooleanNoArg(Object target, String methodName) {
        if (target == null || methodName == null || methodName.isBlank()) return null;
        try {
            Method method = target.getClass().getMethod(methodName);
            method.setAccessible(true);
            Object value = method.invoke(target);
            return value instanceof Boolean b ? b : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void invokeTwoArgs(Object target, String methodName, Object arg1, Object arg2) {
        if (target == null || methodName == null || methodName.isBlank()) return;
        Method fallback = null;
        for (Method method : target.getClass().getMethods()) {
            if (!method.getName().equals(methodName) || method.getParameterCount() != 2) continue;
            Class<?>[] params = method.getParameterTypes();
            if (!isParameterCompatible(params[0], arg1) || !isParameterCompatible(params[1], arg2)) {
                if (fallback == null) fallback = method;
                continue;
            }
            try {
                method.setAccessible(true);
                method.invoke(target, arg1, arg2);
                return;
            } catch (Throwable ignored) {
                return;
            }
        }

        if (fallback != null) {
            try {
                fallback.setAccessible(true);
                fallback.invoke(target, arg1, arg2);
            } catch (Throwable ignored) {
            }
        }
    }

    private static boolean isParameterCompatible(Class<?> parameterType, Object value) {
        if (parameterType == null) return false;
        if (value == null) return !parameterType.isPrimitive();
        if (parameterType.isAssignableFrom(value.getClass())) return true;
        if (!parameterType.isPrimitive()) return false;
        return (parameterType == boolean.class && value instanceof Boolean)
            || (parameterType == int.class && value instanceof Integer)
            || (parameterType == long.class && value instanceof Long)
            || (parameterType == float.class && value instanceof Float)
            || (parameterType == double.class && value instanceof Double)
            || (parameterType == short.class && value instanceof Short)
            || (parameterType == byte.class && value instanceof Byte)
            || (parameterType == char.class && value instanceof Character);
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

    private record WaypointApplyStats(
        boolean listAvailable,
        int desiredCount,
        int removedManagedCount,
        int addedCount,
        int beforeCount,
        int afterCount
    ) {
    }
}
