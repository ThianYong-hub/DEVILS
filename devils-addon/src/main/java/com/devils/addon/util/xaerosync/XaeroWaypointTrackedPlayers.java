package com.devils.addon.util.xaerosync;

import com.devils.addon.DevilsAddon;
import com.devils.addon.util.XaeroSyncWaypoints;
import com.mojang.authlib.GameProfile;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.world.World;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public final class XaeroWaypointTrackedPlayers {
    private XaeroWaypointTrackedPlayers() {
    }

    public static XaeroWaypointContext.TrackedPlayersResult applyTrackedPlayers(
        XaeroWaypointContext context,
        java.util.Collection<XaeroSyncWaypoints.PlayerMarker> markers,
        java.util.Collection<XaeroSyncWaypoints.MapWaypointMarker> mapMarkers,
        RegistryKey<World> targetDimension
    ) {
        boolean trackedPlayersUsable = canRenderTrackedPlayers();
        boolean includePlayersFallback = !trackedPlayersUsable;
        boolean forcePlayersFallback = !trackedPlayersUsable;
        ArrayList<XaeroSyncWaypoints.MapWaypointMarker> effectiveMapMarkers = new ArrayList<>();

        try {
            if (trackedPlayersUsable) {
                if (!ensureTrackedPlayersVisible(context) && !context.trackedPlayersVisibilityWarningShown) {
                    XaeroWaypointContext.debug(context, "tracked-players visibility check unavailable; proceeding with manager sync.");
                    context.trackedPlayersVisibilityWarningShown = true;
                }

                Object manager = getTrackedPlayerManager();
                if (manager == null) {
                    clearTrackedPlayers(context);
                    includePlayersFallback = true;
                    forcePlayersFallback = true;
                    XaeroWaypointContext.logUnavailableOnce(context, "Xaero map session is unavailable, skipping tracked player sync.");
                    XaeroWaypointContext.debug(context, "tracked-players manager unavailable; using waypoint fallback for players.");
                } else {
                    Map<String, UUID> uuidByName = resolveOnlinePlayerUuids();
                    Set<UUID> desired = new HashSet<>();
                    long nowMs = System.currentTimeMillis();

                    if (markers != null) {
                        for (XaeroSyncWaypoints.PlayerMarker marker : markers) {
                            if (marker == null) continue;

                            UUID uuid = resolveUuid(marker, uuidByName);
                            if (uuid == null) {
                                includePlayersFallback = true;
                                effectiveMapMarkers.add(new XaeroSyncWaypoints.MapWaypointMarker(
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

                            RegistryKey<World> sourceDimension = XaeroWaypointReflection.parseDimensionKey(marker.dimension());
                            RegistryKey<World> markerDimension = sourceDimension != null ? sourceDimension : targetDimension;
                            if (markerDimension == null) markerDimension = World.OVERWORLD;

                            XaeroWaypointContext.TrackedPlayerRenderSnapshot renderSnapshot = updateTrackedPlayerMotion(
                                context,
                                uuid,
                                marker,
                                markerDimension,
                                nowMs
                            );
                            invokeManagerUpdate(
                                manager,
                                uuid,
                                renderSnapshot.x(),
                                renderSnapshot.y(),
                                renderSnapshot.z(),
                                renderSnapshot.dimension()
                            );
                            desired.add(uuid);
                            context.trackedPlayerLastSeenAtMs.put(uuid, nowMs);
                        }
                    }

                    if (mapMarkers != null) {
                        for (XaeroSyncWaypoints.MapWaypointMarker marker : mapMarkers) {
                            if (marker == null) continue;
                            if (!Double.isFinite(marker.x()) || !Double.isFinite(marker.y()) || !Double.isFinite(marker.z())) continue;
                            effectiveMapMarkers.add(marker);
                        }
                    }

                    for (UUID uuid : new HashSet<>(context.activeTrackedPlayers)) {
                        if (desired.contains(uuid)) continue;
                        if (shouldRetainTrackedPlayer(context, uuid, nowMs)) {
                            XaeroWaypointContext.TrackedPlayerRenderSnapshot retained = advanceTrackedPlayerMotion(
                                context.trackedPlayerMotionStates.get(uuid),
                                nowMs
                            );
                            if (retained != null) {
                                invokeManagerUpdate(
                                    manager,
                                    uuid,
                                    retained.x(),
                                    retained.y(),
                                    retained.z(),
                                    retained.dimension()
                                );
                                desired.add(uuid);
                                continue;
                            }
                        }
                        invokeManagerRemove(manager, uuid);
                        context.trackedPlayerMotionStates.remove(uuid);
                        context.trackedPlayerLastSeenAtMs.remove(uuid);
                    }

                    context.activeTrackedPlayers.clear();
                    context.activeTrackedPlayers.addAll(desired);
                    context.unavailableLogged = false;

                    String snapshot = String.format(
                        Locale.ROOT,
                        "tracked-players updated: desired=%d active=%d fallbackPlayers=%s",
                        desired.size(),
                        context.activeTrackedPlayers.size(),
                        includePlayersFallback
                    );
                    if (!snapshot.equals(context.lastTrackedPlayersDebugSnapshot)) {
                        context.lastTrackedPlayersDebugSnapshot = snapshot;
                        XaeroWaypointContext.debug(context, "%s", snapshot);
                    }
                }
            } else {
                clearTrackedPlayers(context);
                includePlayersFallback = true;
                forcePlayersFallback = true;
                if (mapMarkers != null) effectiveMapMarkers.addAll(mapMarkers);
            }
        } catch (ClassNotFoundException exception) {
            clearTrackedPlayers(context);
            includePlayersFallback = true;
            forcePlayersFallback = true;
            XaeroWaypointContext.logUnavailableOnce(context, "Xaero World Map mod not found, skipping tracked player sync.");
            XaeroWaypointContext.debug(context, "tracked-players class missing; using waypoint fallback.");
            if (mapMarkers != null) effectiveMapMarkers.addAll(mapMarkers);
        } catch (Throwable throwable) {
            includePlayersFallback = true;
            forcePlayersFallback = true;
            DevilsAddon.LOG.debug("[Devils/Ping] Failed to apply Xaero tracked players.", throwable);
            XaeroWaypointContext.debug(context, "tracked-players stage failed: %s", throwable.getClass().getSimpleName());
            if (mapMarkers != null) effectiveMapMarkers.addAll(mapMarkers);
        }

        if (includePlayersFallback && forcePlayersFallback) appendPlayerMarkersAsFallback(markers, effectiveMapMarkers);
        return new XaeroWaypointContext.TrackedPlayersResult(includePlayersFallback, forcePlayersFallback, effectiveMapMarkers);
    }

    public static void clearTrackedPlayers(XaeroWaypointContext context) {
        if (context.activeTrackedPlayers.isEmpty()) {
            context.trackedPlayerMotionStates.clear();
            context.trackedPlayerLastSeenAtMs.clear();
            return;
        }

        try {
            Object manager = getTrackedPlayerManager();
            if (manager == null) {
                context.activeTrackedPlayers.clear();
                context.trackedPlayerMotionStates.clear();
                context.trackedPlayerLastSeenAtMs.clear();
                return;
            }

            for (UUID uuid : new HashSet<>(context.activeTrackedPlayers)) {
                invokeManagerRemove(manager, uuid);
            }
        } catch (Throwable ignored) {
        } finally {
            context.activeTrackedPlayers.clear();
            context.trackedPlayerMotionStates.clear();
            context.trackedPlayerLastSeenAtMs.clear();
        }
    }

    private static boolean shouldRetainTrackedPlayer(XaeroWaypointContext context, UUID uuid, long nowMs) {
        if (context == null || uuid == null) return false;
        Long lastSeenAtMs = context.trackedPlayerLastSeenAtMs.get(uuid);
        if (lastSeenAtMs == null) return false;
        return (nowMs - lastSeenAtMs) <= XaeroWaypointContext.TRACKED_PLAYER_MISSING_GRACE_MS;
    }

    private static XaeroWaypointContext.TrackedPlayerRenderSnapshot updateTrackedPlayerMotion(
        XaeroWaypointContext context,
        UUID uuid,
        XaeroSyncWaypoints.PlayerMarker marker,
        RegistryKey<World> markerDimension,
        long nowMs
    ) {
        double sourceX = marker.x();
        double sourceY = marker.y();
        double sourceZ = marker.z();

        XaeroWaypointContext.TrackedPlayerMotionState state = context.trackedPlayerMotionStates.get(uuid);
        if (state == null || state.dimension == null) {
            XaeroWaypointContext.TrackedPlayerMotionState created = new XaeroWaypointContext.TrackedPlayerMotionState(
                markerDimension,
                sourceX,
                sourceY,
                sourceZ,
                sourceX,
                sourceY,
                sourceZ,
                nowMs,
                XaeroWaypointContext.TRACKED_PLAYER_INTERPOLATION_MIN_MS,
                nowMs,
                sourceX,
                sourceY,
                sourceZ,
                nowMs
            );
            context.trackedPlayerMotionStates.put(uuid, created);
            return new XaeroWaypointContext.TrackedPlayerRenderSnapshot(sourceX, sourceY, sourceZ, markerDimension);
        }

        boolean sameDimension = state.dimension.equals(markerDimension);
        double sourceMoveSq = distanceSq(
            state.targetX,
            state.targetY,
            state.targetZ,
            sourceX,
            sourceY,
            sourceZ
        );

        if (!sameDimension || sourceMoveSq >= XaeroWaypointContext.TRACKED_PLAYER_TELEPORT_SNAP_DISTANCE_SQ) {
            state.dimension = markerDimension;
            state.startX = sourceX;
            state.startY = sourceY;
            state.startZ = sourceZ;
            state.targetX = sourceX;
            state.targetY = sourceY;
            state.targetZ = sourceZ;
            state.transitionStartMs = nowMs;
            state.transitionDurationMs = XaeroWaypointContext.TRACKED_PLAYER_INTERPOLATION_MIN_MS;
            state.lastSourceReceivedAtMs = nowMs;
            state.renderX = sourceX;
            state.renderY = sourceY;
            state.renderZ = sourceZ;
            state.lastRenderUpdateMs = nowMs;
            return new XaeroWaypointContext.TrackedPlayerRenderSnapshot(sourceX, sourceY, sourceZ, markerDimension);
        }

        if (sourceMoveSq > 1.0e-6) {
            // Factual-only updates: no extrapolation and no delayed catch-up smoothing.
            state.startX = sourceX;
            state.startY = sourceY;
            state.startZ = sourceZ;
            state.targetX = sourceX;
            state.targetY = sourceY;
            state.targetZ = sourceZ;
            state.transitionStartMs = nowMs;
            state.transitionDurationMs = XaeroWaypointContext.TRACKED_PLAYER_INTERPOLATION_MIN_MS;
            state.lastSourceReceivedAtMs = nowMs;
            state.renderX = sourceX;
            state.renderY = sourceY;
            state.renderZ = sourceZ;
            state.lastRenderUpdateMs = nowMs;
        }

        return new XaeroWaypointContext.TrackedPlayerRenderSnapshot(
            state.renderX,
            state.renderY,
            state.renderZ,
            state.dimension
        );
    }

    private static XaeroWaypointContext.TrackedPlayerRenderSnapshot advanceTrackedPlayerMotion(
        XaeroWaypointContext.TrackedPlayerMotionState state,
        long nowMs
    ) {
        if (state == null || state.dimension == null) return null;
        state.lastRenderUpdateMs = nowMs;

        return new XaeroWaypointContext.TrackedPlayerRenderSnapshot(
            state.renderX,
            state.renderY,
            state.renderZ,
            state.dimension
        );
    }

    private static double distanceSq(double ax, double ay, double az, double bx, double by, double bz) {
        double dx = bx - ax;
        double dy = by - ay;
        double dz = bz - az;
        return (dx * dx) + (dy * dy) + (dz * dz);
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
            if (profile == null || profile.id() == null || profile.name() == null) continue;
            map.put(XaeroWaypointReflection.normalize(profile.name()), profile.id());
        }

        return map;
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
        Object current = XaeroWaypointReflection.readFieldValue(worldData, "serverLevelId");
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
                XaeroWaypointReflection.writeFieldValue(worldData, "serverLevelId", pseudoLevelId);
                XaeroWaypointReflection.writeFieldValue(worldData, "usedServerLevelId", pseudoLevelId);
            }
        }

        return XaeroWaypointReflection.readFieldValue(worldData, "serverLevelId") instanceof Integer;
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

    private static boolean ensureTrackedPlayersVisible(XaeroWaypointContext context) {
        if (context.trackedPlayersVisibilityEnforced) return true;

        try {
            Class<?> worldMapClass = Class.forName("xaero.map.WorldMap");
            Object worldMapInstance = worldMapClass.getField("INSTANCE").get(null);
            if (worldMapInstance == null) return false;

            Object configs = XaeroWaypointReflection.invokeNoArg(worldMapInstance, "getConfigs");
            if (configs == null) return false;

            Object clientConfigManager = XaeroWaypointReflection.invokeNoArg(configs, "getClientConfigManager");
            if (clientConfigManager == null) return false;

            Class<?> optionsClass = Class.forName("xaero.map.common.config.option.WorldMapProfiledConfigOptions");
            Object displayTrackedPlayersOption = optionsClass.getField("DISPLAY_TRACKED_PLAYERS").get(null);

            Object serverSynced = XaeroWaypointReflection.invokeNoArg(clientConfigManager, "getServerSynced");
            if (serverSynced != null) {
                Object serverValue = XaeroWaypointReflection.invokeSingleArg(serverSynced, "getEffective", displayTrackedPlayersOption);
                if (Boolean.TRUE.equals(serverValue)) {
                    context.trackedPlayersVisibilityEnforced = true;
                    return true;
                }
            }

            Object currentProfile = XaeroWaypointReflection.invokeNoArg(clientConfigManager, "getCurrentProfile");
            if (currentProfile == null) return false;

            Object current = XaeroWaypointReflection.invokeSingleArg(currentProfile, "get", displayTrackedPlayersOption);
            if (Boolean.TRUE.equals(current)) {
                context.trackedPlayersVisibilityEnforced = true;
                return true;
            }

            XaeroWaypointReflection.invokeTwoArgs(currentProfile, "set", displayTrackedPlayersOption, Boolean.TRUE);

            Object profileIo = XaeroWaypointReflection.invokeNoArg(configs, "getClientConfigProfileIO");
            if (profileIo != null) XaeroWaypointReflection.invokeSingleArg(profileIo, "save", currentProfile);

            Object updatedCurrent = XaeroWaypointReflection.invokeSingleArg(currentProfile, "get", displayTrackedPlayersOption);
            context.trackedPlayersVisibilityEnforced = Boolean.TRUE.equals(updatedCurrent);
            return context.trackedPlayersVisibilityEnforced;
        } catch (Throwable throwable) {
            DevilsAddon.LOG.debug("[Devils/XaeroSync] Failed to enforce DISPLAY_TRACKED_PLAYERS.", throwable);
            return false;
        }
    }

    private static UUID resolveUuid(XaeroSyncWaypoints.PlayerMarker marker, Map<String, UUID> uuidByName) {
        if (marker == null) return null;
        String normalizedName = XaeroWaypointReflection.normalize(marker.name());
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

        if (raw.isBlank() || normalizedName.isBlank()) return null;
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + normalizedName).getBytes(StandardCharsets.UTF_8));
    }

    private static void appendPlayerMarkersAsFallback(
        java.util.Collection<XaeroSyncWaypoints.PlayerMarker> markers,
        java.util.Collection<XaeroSyncWaypoints.MapWaypointMarker> destination
    ) {
        if (markers == null || destination == null) return;
        for (XaeroSyncWaypoints.PlayerMarker marker : markers) {
            if (marker == null) continue;
            destination.add(new XaeroSyncWaypoints.MapWaypointMarker(
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
}



