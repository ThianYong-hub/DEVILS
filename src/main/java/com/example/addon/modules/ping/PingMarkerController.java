package com.example.addon.modules.ping;

import com.example.addon.audio.JoinSoundPlayer;
import com.example.addon.modules.Ping;
import com.example.addon.modules.sync.SyncJsonUtils;
import com.example.addon.settings.TrackerPlayerRule.SoundSourceMode;
import com.example.addon.util.MapIconManager;
import com.google.gson.JsonObject;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class PingMarkerController {
    private final Ping module;
    private final LinkedHashMap<String, PingMarker> markers = new LinkedHashMap<>();
    private final Map<String, Boolean> logoutSpotEligible = new HashMap<>();

    private String lastPlayedSoundStamp = "";
    private long lastLocalMarkerCreatedAtMs;

    public PingMarkerController(Ping module) {
        this.module = module;
    }

    public void onDeactivate() {
        lastLocalMarkerCreatedAtMs = 0L;
    }

    public void onGameJoined() {
        lastLocalMarkerCreatedAtMs = 0L;
    }

    public void onGameLeft() {
        lastLocalMarkerCreatedAtMs = 0L;
    }

    public void createPingFromCrosshair() {
        if (module.client().player == null || module.client().world == null || module.client().cameraEntity == null) return;
        if (module.client().currentScreen != null) return;

        boolean detachedCamera = module.client().cameraEntity != module.client().player;
        Vec3d eye = detachedCamera ? module.client().cameraEntity.getCameraPosVec(1.0f) : module.client().player.getEyePos();
        Vec3d rotation = detachedCamera ? module.client().cameraEntity.getRotationVec(1.0f) : module.client().player.getRotationVec(1.0f);
        double maxRange = Math.max(8, module.raycastRangeValue());
        Vec3d fallback = eye.add(rotation.multiply(maxRange));

        HitResult hit = detachedCamera
            ? module.client().cameraEntity.raycast(maxRange, 1.0f, false)
            : module.client().player.raycast(maxRange, 1.0f, false);

        Vec3d raw = fallback;
        if (hit != null && hit.getPos() != null) {
            if (hit.getType() != HitResult.Type.MISS) {
                raw = hit instanceof BlockHitResult blockHitResult ? Vec3d.ofCenter(blockHitResult.getBlockPos()) : hit.getPos();
            } else {
                double missDistance = hit.getPos().distanceTo(eye);
                raw = missDistance >= (maxRange * 0.95) ? hit.getPos() : fallback;
            }
        }

        String markerIconPath = MapIconManager.normalizeIconPath(module.iconPathValue());
        if (markerIconPath.isBlank()) markerIconPath = PingConstants.DEFAULT_PING_ICON_PATH;

        long now = System.currentTimeMillis();
        long createdAt = now <= lastLocalMarkerCreatedAtMs ? lastLocalMarkerCreatedAtMs + 1L : now;
        lastLocalMarkerCreatedAtMs = createdAt;

        PingMarker marker = new PingMarker(
            PingFormattingUtils.buildMarkerId(module.currentUsername(), module.currentServerKey()),
            module.currentUsername(),
            module.currentSyncDeviceId(),
            module.currentServerKey(),
            module.client().world.getRegistryKey().getValue().toString(),
            raw.x,
            PingFormattingUtils.clampY(raw.y, PingConstants.WORLD_MIN_Y, PingConstants.WORLD_MAX_Y),
            raw.z,
            createdAt,
            module.iconEnabled(),
            markerIconPath
        );

        applyMarker(marker);
        trimMarkerCapacity();
        playLocalPingSound();
    }

    public List<PingMarker> collectVisibleMarkers() {
        if (markers.isEmpty()) return List.of();

        String serverKey = PingFormattingUtils.normalizeServerKey(module.currentServerKey());
        ArrayList<PingMarker> visible = new ArrayList<>();
        for (PingMarker marker : markers.values()) {
            if (marker != null && isMarkerAudienceAllowed(marker, serverKey)) visible.add(marker);
        }

        visible.sort(Comparator.comparingLong(PingMarker::createdAtMs).reversed());
        return visible;
    }

    public List<Ping.MarkerJumpTarget> snapshotMarkerTargets() {
        if (markers.isEmpty()) return List.of();

        String serverKey = PingFormattingUtils.normalizeServerKey(module.currentServerKey());
        ArrayList<Ping.MarkerJumpTarget> targets = new ArrayList<>();
        for (PingMarker marker : markers.values()) {
            if (marker == null || !isMarkerAudienceAllowed(marker, serverKey)) continue;
            targets.add(new Ping.MarkerJumpTarget(
                marker.id(),
                marker.sender(),
                marker.server(),
                marker.dimension(),
                marker.x(),
                marker.y(),
                marker.z(),
                marker.createdAtMs(),
                marker.iconPath()
            ));
        }

        targets.sort(Comparator.comparingLong(Ping.MarkerJumpTarget::createdAtMs).reversed());
        return targets;
    }

    public void pruneExpiredMarkers() {
        if (markers.isEmpty()) return;

        long now = System.currentTimeMillis();
        boolean changed = false;
        Iterator<Map.Entry<String, PingMarker>> iterator = markers.entrySet().iterator();
        while (iterator.hasNext()) {
            PingMarker marker = iterator.next().getValue();
            if (now - marker.createdAtMs() <= PingConstants.MARKER_TTL_MS) continue;
            iterator.remove();
            logoutSpotEligible.remove(marker.id());
            changed = true;
        }

        if (changed) trimMarkerCapacity();
    }

    public List<SyncPingData> snapshotSyncData(boolean runtimePingSyncEnabled, String runtimeSyncDeviceId) {
        ArrayList<SyncPingData> snapshot = new ArrayList<>();
        if (!runtimePingSyncEnabled) return snapshot;

        String selfName = PingFormattingUtils.normalizeKey(module.currentUsername());
        String selfDevice = PingFormattingUtils.normalizeKey(runtimeSyncDeviceId);
        ArrayList<PingMarker> ordered = new ArrayList<>(markers.values());
        ordered.sort(Comparator.comparingLong(PingMarker::createdAtMs).reversed());
        if (ordered.size() > PingConstants.MAX_SYNC_MARKERS) {
            ordered = new ArrayList<>(ordered.subList(0, PingConstants.MAX_SYNC_MARKERS));
        }

        for (PingMarker marker : ordered) {
            if (marker == null) continue;
            boolean localOwned = (!selfDevice.isBlank() && selfDevice.equals(PingFormattingUtils.normalizeKey(marker.senderDevice())))
                || (!selfName.isBlank() && selfName.equals(PingFormattingUtils.normalizeKey(marker.sender())));
            if (localOwned) snapshot.add(encodeMarker(marker));
        }

        return snapshot;
    }

    public List<SyncPingData> mergeSnapshots(List<SyncPingData> remoteSnapshot, List<SyncPingData> localSnapshot) {
        LinkedHashMap<String, PingMarker> mergedMarkers = decodeToMarkerMap(remoteSnapshot);
        LinkedHashMap<String, PingMarker> localMarkers = decodeToMarkerMap(localSnapshot);

        for (PingMarker localMarker : localMarkers.values()) {
            PingMarker existing = mergedMarkers.get(localMarker.id());
            if (existing == null || localMarker.createdAtMs() >= existing.createdAtMs()) {
                mergedMarkers.put(localMarker.id(), localMarker);
            }
        }

        List<PingMarker> sortedMarkers = new ArrayList<>(mergedMarkers.values());
        sortedMarkers.sort(Comparator.comparingLong(PingMarker::createdAtMs).reversed());
        if (sortedMarkers.size() > PingConstants.MAX_SYNC_MARKERS) {
            sortedMarkers = sortedMarkers.subList(0, PingConstants.MAX_SYNC_MARKERS);
        }

        ArrayList<SyncPingData> merged = new ArrayList<>(sortedMarkers.size());
        for (PingMarker marker : sortedMarkers) merged.add(encodeMarker(marker));
        return merged;
    }

    public void applySyncedSnapshot(List<SyncPingData> snapshot, boolean runtimePingSyncEnabled, String runtimeSyncDeviceId) {
        if (!runtimePingSyncEnabled) return;

        String selfName = PingFormattingUtils.normalizeKey(module.currentUsername());
        String serverKey = PingFormattingUtils.normalizeServerKey(module.currentServerKey());
        String selfDevice = PingFormattingUtils.normalizeKey(runtimeSyncDeviceId);
        LinkedHashMap<String, PingMarker> oldMarkers = new LinkedHashMap<>(markers);
        LinkedHashMap<String, PingMarker> merged = decodeToMarkerMap(snapshot);

        for (PingMarker local : oldMarkers.values()) {
            if (local == null) continue;
            boolean localOwned = (!selfDevice.isBlank() && selfDevice.equals(PingFormattingUtils.normalizeKey(local.senderDevice())))
                || PingFormattingUtils.normalizeKey(local.sender()).equals(selfName);
            if (!localOwned) continue;

            PingMarker remote = merged.get(local.id());
            if (remote == null || local.createdAtMs() > remote.createdAtMs()) merged.put(local.id(), local);
        }

        Map<String, Boolean> oldEligibility = new HashMap<>(logoutSpotEligible);
        markers.clear();
        logoutSpotEligible.clear();
        markers.putAll(merged);
        trimMarkerCapacity();

        for (PingMarker marker : markers.values()) {
            if (oldEligibility.getOrDefault(marker.id(), false) || isPlayerInView(marker.sender())) {
                logoutSpotEligible.put(marker.id(), true);
            }
        }

        if (shouldPlayRemoteSound(oldMarkers, markers, selfName, serverKey)) {
            JoinSoundPlayer.play(SoundSourceMode.LocalFolder, module.pingSoundPathValue(), PingConstants.DEFAULT_SOUND, module.pingVolumeValue());
        }
    }

    public boolean isMarkerAlive(PingMarker marker, long nowMs) {
        long age = nowMs - marker.createdAtMs();
        return age >= 0 && age <= PingConstants.MARKER_TTL_MS;
    }

    private boolean isMarkerAudienceAllowed(PingMarker marker, String normalizedServerKey) {
        if (marker == null) return false;
        String markerServer = PingFormattingUtils.normalizeServerKey(marker.server());
        if (!normalizedServerKey.isBlank() && !markerServer.isBlank() && !markerServer.equals(normalizedServerKey)) return false;
        return !PingFormattingUtils.normalizeKey(marker.sender()).isBlank();
    }

    private boolean isPlayerInView(String playerName) {
        PlayerEntity player = findLoadedPlayer(playerName);
        if (player == null || module.client().cameraEntity == null) return false;

        Vec3d cameraPos = module.client().cameraEntity.getCameraPosVec(1.0f);
        Vec3d look = module.client().cameraEntity.getRotationVec(1.0f);
        Vec3d toTarget = player.getBoundingBox().getCenter().subtract(cameraPos);
        double distance = toTarget.length();
        if (distance < 0.001) return true;

        Vec3d dir = toTarget.multiply(1.0 / distance);
        double dot = look.dotProduct(dir);
        double fov = 90.0;
        try {
            fov = module.client().options.getFov().getValue();
        } catch (Throwable ignored) {
        }

        double threshold = Math.cos(Math.toRadians(Math.max(30.0, Math.min(170.0, fov)) / 2.0));
        if (dot < threshold) return false;
        return module.client().player == null || module.client().player.canSee(player);
    }

    private PlayerEntity findLoadedPlayer(String playerName) {
        String normalized = PingFormattingUtils.normalizeKey(playerName);
        if (normalized.isBlank() || module.client().world == null) return null;

        for (PlayerEntity player : module.client().world.getPlayers()) {
            if (player == null || player.getGameProfile() == null) continue;
            if (normalized.equals(PingFormattingUtils.normalizeKey(player.getGameProfile().getName()))) return player;
        }
        return null;
    }

    private void trimMarkerCapacity() {
        int max = Math.max(1, PingConstants.MAX_SYNC_MARKERS);
        if (markers.size() <= max) return;

        List<PingMarker> ordered = new ArrayList<>(markers.values());
        ordered.sort(Comparator.comparingLong(PingMarker::createdAtMs).reversed());

        Map<String, Boolean> oldEligibility = new HashMap<>(logoutSpotEligible);
        markers.clear();
        logoutSpotEligible.clear();
        for (int i = 0; i < Math.min(max, ordered.size()); i++) {
            PingMarker marker = ordered.get(i);
            markers.put(marker.id(), marker);
            if (oldEligibility.getOrDefault(marker.id(), false)) logoutSpotEligible.put(marker.id(), true);
        }
    }

    private void applyMarker(PingMarker marker) {
        if (marker == null) return;

        PingMarker canonical = canonicalizeMarker(marker);
        boolean keepEligibility = logoutSpotEligible.getOrDefault(canonical.id(), false);
        markers.put(canonical.id(), canonical);
        if (keepEligibility || isPlayerInView(canonical.sender())) logoutSpotEligible.put(canonical.id(), true);
        else logoutSpotEligible.remove(canonical.id());
    }

    private PingMarker canonicalizeMarker(PingMarker marker) {
        String canonicalId = PingFormattingUtils.buildMarkerId(marker.sender(), marker.server());
        double canonicalY = PingFormattingUtils.clampY(marker.y(), PingConstants.WORLD_MIN_Y, PingConstants.WORLD_MAX_Y);
        if (canonicalId.equals(marker.id()) && Double.compare(canonicalY, marker.y()) == 0) return marker;

        return new PingMarker(
            canonicalId,
            marker.sender(),
            marker.senderDevice(),
            marker.server(),
            marker.dimension(),
            marker.x(),
            canonicalY,
            marker.z(),
            marker.createdAtMs(),
            marker.icon(),
            marker.iconPath()
        );
    }

    private LinkedHashMap<String, PingMarker> decodeToMarkerMap(List<SyncPingData> list) {
        LinkedHashMap<String, PingMarker> map = new LinkedHashMap<>();
        if (list == null) return map;

        for (SyncPingData data : list) {
            PingMarker marker = decodeMarker(data);
            if (marker == null) continue;

            PingMarker existing = map.get(marker.id());
            if (existing == null || marker.createdAtMs() >= existing.createdAtMs()) map.put(marker.id(), marker);
        }

        return map;
    }

    private SyncPingData encodeMarker(PingMarker marker) {
        JsonObject payload = new JsonObject();
        payload.addProperty("schema", PingConstants.MARKER_SCHEMA);
        payload.addProperty("id", marker.id());
        payload.addProperty("sender", marker.sender());
        payload.addProperty("senderDevice", marker.senderDevice());
        payload.addProperty("dim", marker.dimension());
        payload.addProperty("x", marker.x());
        payload.addProperty("y", marker.y());
        payload.addProperty("z", marker.z());
        payload.addProperty("createdAt", marker.createdAtMs());
        payload.addProperty("icon", marker.icon());
        payload.addProperty("iconPath", marker.iconPath());
        return new SyncPingData(true, marker.sender(), marker.server(), payload.toString(), 0);
    }

    private PingMarker decodeMarker(SyncPingData data) {
        if (data == null || !data.enabled()) return null;

        String sender = PingFormattingUtils.safe(data.username());
        String server = PingFormattingUtils.safe(data.server());
        String payload = PingFormattingUtils.safe(data.payload());
        if (sender.isBlank() || server.isBlank() || payload.isBlank()) return null;

        JsonObject json = SyncJsonUtils.parseJsonObject(payload);
        if (json == null) return null;

        String schema = SyncJsonUtils.readString(json, "schema", "");
        if (!schema.isBlank() && !PingConstants.MARKER_SCHEMA.equals(schema)) return null;

        String senderDevice = SyncJsonUtils.readString(json, "senderDevice", "");
        String dimension = SyncJsonUtils.readString(
            json,
            "dim",
            module.client().world == null ? "minecraft:overworld" : module.client().world.getRegistryKey().getValue().toString()
        );
        double x = SyncJsonUtils.readDouble(json, "x", Double.NaN);
        double y = PingFormattingUtils.clampY(SyncJsonUtils.readDouble(json, "y", Double.NaN), PingConstants.WORLD_MIN_Y, PingConstants.WORLD_MAX_Y);
        double z = SyncJsonUtils.readDouble(json, "z", Double.NaN);
        long createdAt = SyncJsonUtils.readLong(json, "createdAt", 0);
        boolean iconValue = SyncJsonUtils.readBoolean(json, "icon", true);
        String iconPathValue = MapIconManager.normalizeIconPath(
            SyncJsonUtils.readString(json, "iconPath", SyncJsonUtils.readString(json, "icon-path", ""))
        );

        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) return null;
        if (createdAt <= 0) createdAt = System.currentTimeMillis();
        return new PingMarker(
            PingFormattingUtils.buildMarkerId(sender, server),
            sender,
            senderDevice,
            server,
            dimension,
            x,
            y,
            z,
            createdAt,
            iconValue,
            iconPathValue
        );
    }

    private boolean shouldPlayRemoteSound(
        Map<String, PingMarker> previous,
        Map<String, PingMarker> current,
        String selfName,
        String serverKey
    ) {
        if (current == null || current.isEmpty()) return false;

        ArrayList<PingMarker> ordered = new ArrayList<>(current.values());
        ordered.sort(Comparator.comparingLong(PingMarker::createdAtMs).reversed());
        for (PingMarker marker : ordered) {
            if (!isMarkerAudienceAllowed(marker, serverKey)) continue;

            String sender = PingFormattingUtils.normalizeKey(marker.sender());
            if (sender.isBlank() || sender.equals(selfName)) continue;

            PingMarker before = previous == null ? null : previous.get(marker.id());
            if (before != null && before.createdAtMs() >= marker.createdAtMs()) continue;

            String soundStamp = marker.id() + ":" + marker.createdAtMs();
            if (soundStamp.equals(lastPlayedSoundStamp)) continue;
            lastPlayedSoundStamp = soundStamp;
            return true;
        }

        return false;
    }

    private void playLocalPingSound() {
        JoinSoundPlayer.play(SoundSourceMode.LocalFolder, module.pingSoundPathValue(), PingConstants.DEFAULT_SOUND, module.pingVolumeValue());
    }
}


