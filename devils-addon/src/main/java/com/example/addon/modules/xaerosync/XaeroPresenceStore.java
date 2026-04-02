package com.example.addon.modules.xaerosync;

import com.example.addon.modules.Ping;
import com.example.addon.modules.XaeroSync;
import com.example.addon.shared.sync.SyncJsonUtils;
import com.example.addon.util.XaeroSyncWaypoints;
import com.google.gson.JsonObject;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.Utils;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class XaeroPresenceStore {
    private final XaeroSync module;
    private final XaeroSyncDebugController debugController;
    private final XaeroJumpEntryCollector jumpEntryCollector;

    private final LinkedHashMap<String, PresenceMarker> syncedPresence = new LinkedHashMap<>();
    private final LinkedHashMap<String, PresenceMotionState> presenceMotion = new LinkedHashMap<>();
    private PresenceMarker localPresenceCache;
    private long localPresenceSequence;

    public XaeroPresenceStore(XaeroSync module, XaeroSyncDebugController debugController) {
        this.module = module;
        this.debugController = debugController;
        this.jumpEntryCollector = new XaeroJumpEntryCollector(module);
    }

    public void clear() {
        syncedPresence.clear();
        presenceMotion.clear();
        localPresenceCache = null;
        localPresenceSequence = 0;
        XaeroTrackedPingRenderCache.clear();
    }

    public void applySyncedSnapshot(List<SyncXaeroData> snapshot) {
        mergeSyncedPresence(decodeToPresenceMap(snapshot));
    }

    public List<SyncXaeroData> mergeSnapshots(List<SyncXaeroData> remoteSnapshot, List<SyncXaeroData> localSnapshot) {
        LinkedHashMap<String, PresenceMarker> mergedPresence = decodeToPresenceMap(remoteSnapshot);
        LinkedHashMap<String, PresenceMarker> localPresence = decodeToPresenceMap(localSnapshot);

        for (PresenceMarker localPresenceMarker : localPresence.values()) {
            PresenceMarker existing = mergedPresence.get(localPresenceMarker.id());
            if (isPresenceNewer(localPresenceMarker, existing)) {
                mergedPresence.put(localPresenceMarker.id(), localPresenceMarker);
            }
        }

        List<PresenceMarker> sortedPresence = new ArrayList<>(mergedPresence.values());
        sortedPresence.sort(Comparator.comparingLong(PresenceMarker::updatedAtMs).reversed());
        if (sortedPresence.size() > XaeroSyncConstants.MAX_SYNC_PRESENCE) {
            sortedPresence = sortedPresence.subList(0, XaeroSyncConstants.MAX_SYNC_PRESENCE);
        }

        ArrayList<SyncXaeroData> merged = new ArrayList<>(sortedPresence.size());
        for (PresenceMarker presence : sortedPresence) merged.add(encodePresence(presence));
        return merged;
    }

    public List<SyncXaeroData> snapshotSyncData(String runtimeSyncDeviceId) {
        long now = System.currentTimeMillis();
        PresenceMarker localPresence = buildOrReuseLocalPresence(runtimeSyncDeviceId);
        if (localPresence == null) {
            localPresenceCache = null;
            return List.of();
        }

        syncedPresence.put(localPresence.id(), localPresence);
        pruneSyncedPresence(now);
        return List.of(encodePresence(localPresence));
    }

    public void applyXaeroPresenceSnapshot() {
        String selfName = XaeroSyncValueUtils.normalizeKey(currentUsername());
        String selfUuid = XaeroSyncValueUtils.normalizeKey(currentPlayerUuid());
        String serverKey = XaeroSyncValueUtils.normalizeServerKey(currentServerKey());

        long now = System.currentTimeMillis();
        ArrayList<XaeroSyncWaypoints.PlayerMarker> xaeroMarkers = new ArrayList<>();
        Set<String> activePresenceIds = new HashSet<>();

        for (PresenceMarker presence : syncedPresence.values()) {
            if (presence == null) continue;
            if ((now - presence.updatedAtMs()) > XaeroSyncConstants.PRESENCE_STALE_MS) continue;

            String sender = XaeroSyncValueUtils.normalizeKey(presence.sender());
            String senderUuid = XaeroSyncValueUtils.normalizeKey(presence.playerUuid());
            if (sender.isBlank()) continue;
            if (!selfUuid.isBlank() && selfUuid.equals(senderUuid)) continue;
            if (!selfName.isBlank() && sender.equals(selfName)) continue;

            String markerServer = XaeroSyncValueUtils.normalizeServerKey(presence.server());
            if (!serverKey.isBlank() && !markerServer.isBlank() && !serverKey.equals(markerServer)) continue;

            activePresenceIds.add(presence.id());
            PresenceRenderPosition renderPosition = resolvePresenceRenderPosition(presence, now);
            xaeroMarkers.add(new XaeroSyncWaypoints.PlayerMarker(
                presence.id(),
                presence.sender(),
                presence.playerUuid(),
                presence.dimension(),
                renderPosition.x(),
                renderPosition.y(),
                renderPosition.z()
            ));
        }

        cleanupPresenceMotion(activePresenceIds, now);

        ArrayList<XaeroSyncWaypoints.MapWaypointMarker> mapMarkers = new ArrayList<>();
        PingMarkerRouting pingRouting = collectPingTrackedMarkers(serverKey);
        xaeroMarkers.addAll(pingRouting.trackedMarkers());
        mapMarkers.addAll(pingRouting.mapMarkers());
        XaeroTrackedPingRenderCache.update(pingRouting.trackedMarkers());

        boolean clearWaypoints = xaeroMarkers.isEmpty() && mapMarkers.isEmpty();
        debugController.debugWaypointPipelineSnapshot(
            "presenceStore=" + syncedPresence.size()
                + "|presenceVisible=" + xaeroMarkers.size()
                + "|pingMarkers=" + pingRouting.totalCount()
                + "|pingTracked=" + pingRouting.trackedCount()
                + "|pingFallback=" + pingRouting.fallbackCount()
                + "|pingUuidCollisions=" + pingRouting.collisionCount()
                + "|mapWaypoints=" + mapMarkers.size()
                + "|action=" + (clearWaypoints ? "clear" : "apply")
        );

        if (clearWaypoints) {
            XaeroTrackedPingRenderCache.clear();
            XaeroSyncWaypoints.clear();
            return;
        }

        XaeroSyncWaypoints.apply(xaeroMarkers, mapMarkers);
    }

    public List<JumpEntry> collectJumpEntries() {
        return jumpEntryCollector.collect(syncedPresence.values());
    }

    private SyncXaeroData encodePresence(PresenceMarker presence) {
        JsonObject payload = new JsonObject();
        payload.addProperty("schema", XaeroSyncConstants.PRESENCE_SCHEMA);
        payload.addProperty("sender", presence.sender());
        payload.addProperty("uuid", presence.playerUuid());
        payload.addProperty("senderDevice", presence.senderDevice());
        payload.addProperty("dim", presence.dimension());
        payload.addProperty("x", presence.x());
        payload.addProperty("y", presence.y());
        payload.addProperty("z", presence.z());
        payload.addProperty("vx", presence.vx());
        payload.addProperty("vy", presence.vy());
        payload.addProperty("vz", presence.vz());
        payload.addProperty("seq", presence.sequence());
        payload.addProperty("updatedAt", presence.updatedAtMs());

        return new SyncXaeroData(
            true,
            XaeroSyncConstants.PRESENCE_USERNAME_PREFIX + presence.sender(),
            presence.server(),
            payload.toString(),
            0
        );
    }

    private LinkedHashMap<String, PresenceMarker> decodeToPresenceMap(List<SyncXaeroData> list) {
        LinkedHashMap<String, PresenceMarker> map = new LinkedHashMap<>();
        if (list == null) return map;

        for (SyncXaeroData data : list) {
            PresenceMarker presence = decodePresence(data);
            if (presence == null) continue;

            PresenceMarker existing = map.get(presence.id());
            if (isPresenceNewer(presence, existing)) {
                map.put(presence.id(), presence);
            }
        }

        return map;
    }

    private PresenceMarker decodePresence(SyncXaeroData data) {
        if (data == null || !data.enabled()) return null;

        String payload = XaeroSyncValueUtils.safe(data.payload());
        if (payload.isBlank()) return null;

        JsonObject json = SyncJsonUtils.parseJsonObject(payload);
        if (json == null) return null;
        if (!XaeroSyncConstants.PRESENCE_SCHEMA.equals(SyncJsonUtils.readString(json, "schema", ""))) return null;

        String sender = SyncJsonUtils.readString(json, "sender", "");
        if (sender.isBlank() && data.username() != null && data.username().startsWith(XaeroSyncConstants.PRESENCE_USERNAME_PREFIX)) {
            sender = data.username().substring(XaeroSyncConstants.PRESENCE_USERNAME_PREFIX.length());
        }

        String playerUuid = SyncJsonUtils.readString(json, "uuid", "");
        String senderDevice = SyncJsonUtils.readString(json, "senderDevice", "");
        String server = XaeroSyncValueUtils.safe(data.server());
        if (server.isBlank()) server = SyncJsonUtils.readString(json, "server", "");
        String dimension = SyncJsonUtils.readString(json, "dim", "");
        double x = SyncJsonUtils.readDouble(json, "x", Double.NaN);
        double y = XaeroSyncValueUtils.clampY(SyncJsonUtils.readDouble(json, "y", Double.NaN));
        double z = SyncJsonUtils.readDouble(json, "z", Double.NaN);
        double vx = SyncJsonUtils.readDouble(json, "vx", Double.NaN);
        double vy = SyncJsonUtils.readDouble(json, "vy", Double.NaN);
        double vz = SyncJsonUtils.readDouble(json, "vz", Double.NaN);
        long sequence = SyncJsonUtils.readLong(json, "seq", 0);
        long updatedAt = SyncJsonUtils.readLong(json, "updatedAt", 0);

        if (sender.isBlank() || server.isBlank()) return null;
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) return null;
        if (!Double.isFinite(vx)) vx = 0.0;
        if (!Double.isFinite(vy)) vy = 0.0;
        if (!Double.isFinite(vz)) vz = 0.0;

        clampVelocity[] velocity = clampVelocity(vx, vy, vz);
        vx = velocity[0].value;
        vy = velocity[1].value;
        vz = velocity[2].value;

        long nowMs = System.currentTimeMillis();
        if (updatedAt <= 0L) {
            updatedAt = nowMs;
        } else if (updatedAt > (nowMs + XaeroSyncConstants.PRESENCE_FUTURE_SKEW_TOLERANCE_MS)) {
            updatedAt = nowMs;
        }

        String identity = XaeroSyncValueUtils.normalizeKey(playerUuid);
        if (identity.isBlank()) identity = XaeroSyncValueUtils.normalizeKey(sender);
        String id = identity + "|" + XaeroSyncValueUtils.normalizeServerKey(server);
        return new PresenceMarker(id, sender, playerUuid, senderDevice, server, dimension, x, y, z, vx, vy, vz, sequence, updatedAt);
    }

    private PresenceMarker buildOrReuseLocalPresence(String runtimeSyncDeviceId) {
        if (module.client().player == null || module.client().world == null) return null;

        String sender = currentUsername();
        String playerUuid = currentPlayerUuid();
        String server = currentServerKey();
        if (sender.isBlank() || runtimeSyncDeviceId == null || runtimeSyncDeviceId.isBlank() || server.isBlank()) return null;

        String dimension = module.client().world.getRegistryKey().getValue().toString();
        double x = module.client().player.getX();
        double y = XaeroSyncValueUtils.clampY(module.client().player.getY());
        double z = module.client().player.getZ();
        Vec3d velocity = module.client().player.getVelocity();
        double vx = velocity == null ? 0.0 : velocity.x * 20.0;
        double vy = velocity == null ? 0.0 : velocity.y * 20.0;
        double vz = velocity == null ? 0.0 : velocity.z * 20.0;

        clampVelocity[] clampedVelocity = clampVelocity(vx, vy, vz);
        vx = clampedVelocity[0].value;
        vy = clampedVelocity[1].value;
        vz = clampedVelocity[2].value;

        long now = System.currentTimeMillis();
        String localId = (XaeroSyncValueUtils.normalizeKey(playerUuid).isBlank()
            ? XaeroSyncValueUtils.normalizeKey(sender)
            : XaeroSyncValueUtils.normalizeKey(playerUuid))
            + "|" + XaeroSyncValueUtils.normalizeServerKey(server);

        if (localPresenceCache == null) {
            long seq = ++localPresenceSequence;
            localPresenceCache = new PresenceMarker(localId, sender, playerUuid, runtimeSyncDeviceId, server, dimension, x, y, z, vx, vy, vz, seq, now);
            return localPresenceCache;
        }

        boolean connectionChanged = !XaeroSyncValueUtils.normalizeKey(localPresenceCache.sender()).equals(XaeroSyncValueUtils.normalizeKey(sender))
            || !XaeroSyncValueUtils.normalizeKey(localPresenceCache.playerUuid()).equals(XaeroSyncValueUtils.normalizeKey(playerUuid))
            || !XaeroSyncValueUtils.normalizeServerKey(localPresenceCache.server()).equals(XaeroSyncValueUtils.normalizeServerKey(server))
            || !XaeroSyncValueUtils.normalizeKey(localPresenceCache.senderDevice()).equals(XaeroSyncValueUtils.normalizeKey(runtimeSyncDeviceId))
            || !XaeroSyncValueUtils.normalizeKey(localPresenceCache.dimension()).equals(XaeroSyncValueUtils.normalizeKey(dimension));

        double dx = localPresenceCache.x() - x;
        double dy = localPresenceCache.y() - y;
        double dz = localPresenceCache.z() - z;
        double movedSq = (dx * dx) + (dy * dy) + (dz * dz);
        long age = now - localPresenceCache.updatedAtMs();
        double moveThresholdSq = Math.max(XaeroSyncConstants.PRESENCE_MOVE_THRESHOLD_SQ, 0.0);
        boolean movedEnough = ((moveThresholdSq == 0.0) ? movedSq > 1.0e-7 : movedSq >= moveThresholdSq)
            && age >= XaeroSyncConstants.PRESENCE_MIN_UPDATE_MS;

        double dvx = localPresenceCache.vx() - vx;
        double dvy = localPresenceCache.vy() - vy;
        double dvz = localPresenceCache.vz() - vz;
        double velocityDeltaSq = (dvx * dvx) + (dvy * dvy) + (dvz * dvz);
        boolean velocityChanged = velocityDeltaSq >= 0.0009 && age >= XaeroSyncConstants.PRESENCE_MIN_UPDATE_MS;
        boolean forceRefresh = age >= XaeroSyncConstants.PRESENCE_FORCE_UPDATE_MS;

        if (connectionChanged || movedEnough || velocityChanged || forceRefresh) {
            long seq = Math.max(localPresenceCache.sequence() + 1, localPresenceSequence + 1);
            localPresenceSequence = seq;
            localPresenceCache = new PresenceMarker(localId, sender, playerUuid, runtimeSyncDeviceId, server, dimension, x, y, z, vx, vy, vz, seq, now);
        }

        return localPresenceCache;
    }

    private void mergeSyncedPresence(Map<String, PresenceMarker> incoming) {
        if (incoming != null && !incoming.isEmpty()) {
            for (PresenceMarker marker : incoming.values()) {
                if (marker == null) continue;
                PresenceMarker existing = syncedPresence.get(marker.id());
                if (isPresenceNewer(marker, existing)) {
                    syncedPresence.put(marker.id(), marker);
                }
            }
        }

        pruneSyncedPresence(System.currentTimeMillis());
    }

    private void pruneSyncedPresence(long now) {
        syncedPresence.entrySet().removeIf(entry -> {
            PresenceMarker marker = entry.getValue();
            return marker == null || (now - marker.updatedAtMs()) > XaeroSyncConstants.PRESENCE_STALE_MS;
        });

        if (syncedPresence.size() <= XaeroSyncConstants.MAX_SYNC_PRESENCE) return;
        ArrayList<PresenceMarker> sorted = new ArrayList<>(syncedPresence.values());
        sorted.sort(Comparator.comparingLong(PresenceMarker::updatedAtMs).reversed());
        syncedPresence.clear();
        for (int i = 0; i < Math.min(sorted.size(), XaeroSyncConstants.MAX_SYNC_PRESENCE); i++) {
            PresenceMarker marker = sorted.get(i);
            syncedPresence.put(marker.id(), marker);
        }
    }

    private PingMarkerRouting collectPingTrackedMarkers(String serverKey) {
        ArrayList<XaeroSyncWaypoints.PlayerMarker> trackedMarkers = new ArrayList<>();
        ArrayList<XaeroSyncWaypoints.MapWaypointMarker> mapMarkers = new ArrayList<>();
        Modules modules = Modules.get();
        if (modules == null) return new PingMarkerRouting(trackedMarkers, mapMarkers, 0, 0, 0, 0);

        Ping ping = modules.get(Ping.class);
        if (ping == null) return new PingMarkerRouting(trackedMarkers, mapMarkers, 0, 0, 0, 0);

        Ping.InfoMode infoMode = ping.xaeroInfoMode();
        if (infoMode == null) infoMode = Ping.InfoMode.Distance;

        int totalCount = 0;
        for (Ping.MarkerJumpTarget marker : ping.snapshotMarkerTargets()) {
            if (marker == null) continue;

            String markerServer = XaeroSyncValueUtils.normalizeServerKey(marker.server());
            if (!serverKey.isBlank() && !markerServer.isBlank() && !serverKey.equals(markerServer)) continue;
            if (!Double.isFinite(marker.x()) || !Double.isFinite(marker.y()) || !Double.isFinite(marker.z())) continue;
            totalCount++;

            String sender = XaeroSyncValueUtils.safe(marker.sender()).trim();
            if (sender.isBlank()) sender = "Marker";
            String coords = String.format(Locale.ROOT, "%d %d %d", Math.round(marker.x()), Math.round(marker.y()), Math.round(marker.z()));
            String displayName = switch (infoMode) {
                case Distance -> "[PING] " + sender;
                case Coords -> "[PING] " + sender + " | " + coords;
            };
            mapMarkers.add(new XaeroSyncWaypoints.MapWaypointMarker(
                "ping:" + XaeroSyncValueUtils.safe(marker.id()),
                displayName,
                marker.dimension(),
                marker.x(),
                marker.y(),
                marker.z(),
                marker.iconPath(),
                false
            ));
        }

        return new PingMarkerRouting(trackedMarkers, mapMarkers, totalCount, 0, mapMarkers.size(), 0);
    }

    private PresenceRenderPosition resolvePresenceRenderPosition(PresenceMarker presence, long nowMs) {
        String sourceDimension = XaeroSyncValueUtils.normalizeKey(presence.dimension());
        double renderX = presence.x();
        double renderY = XaeroSyncValueUtils.clampY(presence.y());
        double renderZ = presence.z();
        presenceMotion.put(presence.id(), new PresenceMotionState(
            presence.id(),
            sourceDimension,
            presence.sequence(),
            presence.updatedAtMs(),
            nowMs,
            presence.x(),
            XaeroSyncValueUtils.clampY(presence.y()),
            presence.z(),
            presence.vx(),
            presence.vy(),
            presence.vz(),
            nowMs,
            renderX,
            renderY,
            renderZ
        ));
        return new PresenceRenderPosition(renderX, renderY, renderZ);
    }

    private void cleanupPresenceMotion(Set<String> activeIds, long nowMs) {
        if (presenceMotion.isEmpty()) return;
        presenceMotion.entrySet().removeIf(entry -> {
            PresenceMotionState state = entry.getValue();
            if (state == null) return true;
            if (activeIds != null && activeIds.contains(entry.getKey())) return false;
            return (nowMs - state.sourceReceivedAtMs()) > (XaeroSyncConstants.PRESENCE_STALE_MS * 2L);
        });
    }

    private String currentUsername() {
        if (module.client().getSession() == null || module.client().getSession().getUsername() == null) return "";
        return module.client().getSession().getUsername().trim();
    }

    private String currentPlayerUuid() {
        if (module.client().player != null && module.client().player.getUuid() != null) return module.client().player.getUuidAsString();
        return "";
    }

    private String currentServerKey() {
        if (module.client().getCurrentServerEntry() != null && module.client().getCurrentServerEntry().address != null) {
            String address = module.client().getCurrentServerEntry().address.trim();
            if (!address.isEmpty()) return address;
        }
        String worldName = Utils.getWorldName();
        return worldName == null ? "" : worldName.trim();
    }

    private static clampVelocity[] clampVelocity(double vx, double vy, double vz) {
        double speedSq = (vx * vx) + (vy * vy) + (vz * vz);
        double maxSq = XaeroSyncConstants.PRESENCE_MAX_SPEED_BLOCKS_PER_SEC * XaeroSyncConstants.PRESENCE_MAX_SPEED_BLOCKS_PER_SEC;
        if (speedSq <= maxSq) {
            return new clampVelocity[]{new clampVelocity(vx), new clampVelocity(vy), new clampVelocity(vz)};
        }

        double speed = Math.sqrt(speedSq);
        if (speed <= 0.0) {
            return new clampVelocity[]{new clampVelocity(0.0), new clampVelocity(0.0), new clampVelocity(0.0)};
        }

        double clampScale = XaeroSyncConstants.PRESENCE_MAX_SPEED_BLOCKS_PER_SEC / speed;
        return new clampVelocity[]{
            new clampVelocity(vx * clampScale),
            new clampVelocity(vy * clampScale),
            new clampVelocity(vz * clampScale)
        };
    }

    private static boolean isPresenceNewer(PresenceMarker incoming, PresenceMarker current) {
        if (incoming == null) return false;
        if (current == null) return true;

        boolean sameSenderDevice = XaeroSyncValueUtils.normalizeKey(incoming.senderDevice()).equals(XaeroSyncValueUtils.normalizeKey(current.senderDevice()));
        if (sameSenderDevice && incoming.sequence() > 0 && current.sequence() > 0) {
            if (incoming.sequence() > current.sequence()) return true;
            if (incoming.sequence() < current.sequence()) return incoming.updatedAtMs() > (current.updatedAtMs() + 1_000L);
        }

        if (incoming.updatedAtMs() != current.updatedAtMs()) return incoming.updatedAtMs() > current.updatedAtMs();
        double incomingSum = incoming.x() + incoming.y() + incoming.z();
        double currentSum = current.x() + current.y() + current.z();
        return incomingSum >= currentSum;
    }

    private record clampVelocity(double value) {
    }
}


