package com.example.addon.modules.xaerosync;

import meteordevelopment.meteorclient.MeteorClient;
import net.minecraft.client.network.PlayerListEntry;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class XaeroTrackedPingRenderCache {
    private static final Map<UUID, PingRenderMarker> trackedPingRenderMarkers = new HashMap<>();

    private XaeroTrackedPingRenderCache() {
    }

    public static synchronized void update(Collection<com.example.addon.util.XaeroSyncWaypoints.PlayerMarker> trackedMarkers) {
        trackedPingRenderMarkers.clear();
        if (trackedMarkers == null) return;

        for (com.example.addon.util.XaeroSyncWaypoints.PlayerMarker marker : trackedMarkers) {
            if (marker == null) continue;
            String uuidRaw = XaeroSyncValueUtils.safe(marker.uuid()).trim();
            UUID uuid = null;
            try {
                if (!uuidRaw.isBlank()) uuid = UUID.fromString(uuidRaw);
            } catch (Throwable ignored) {
            }
            if (uuid == null) uuid = resolveOnlinePlayerUuidByName(marker.name());
            if (uuid == null) continue;
            trackedPingRenderMarkers.put(uuid, new PingRenderMarker(
                XaeroSyncValueUtils.normalizeKey(marker.name()).isBlank() ? "Marker" : marker.name(),
                marker.dimension(),
                marker.x(),
                marker.y(),
                marker.z()
            ));
        }
    }

    public static synchronized void clear() {
        trackedPingRenderMarkers.clear();
    }

    public static synchronized String resolveTrackedPingDisplayName(UUID uuid, String fallbackName) {
        String safeFallback = XaeroSyncValueUtils.safe(fallbackName);
        if (uuid == null) return formatPlayerTrackedLabel(safeFallback);
        PingRenderMarker marker = trackedPingRenderMarkers.get(uuid);
        if (marker == null) return formatPlayerTrackedLabel(safeFallback);
        String sender = XaeroSyncValueUtils.safe(marker.name()).trim();
        if (sender.isBlank()) sender = safeFallback.isBlank() ? "Marker" : safeFallback;
        return formatPingDisplayLabel(sender, marker.dimension(), marker.x(), marker.y(), marker.z());
    }

    private static UUID resolveOnlinePlayerUuidByName(String rawName) {
        String targetName = XaeroSyncValueUtils.normalizeKey(rawName);
        if (targetName.isBlank() || MeteorClient.mc == null || MeteorClient.mc.getNetworkHandler() == null) return null;
        for (PlayerListEntry entry : MeteorClient.mc.getNetworkHandler().getPlayerList()) {
            if (entry == null || entry.getProfile() == null || entry.getProfile().id() == null) continue;
            String entryName = XaeroSyncValueUtils.normalizeKey(entry.getProfile().name());
            if (entryName.equals(targetName)) return entry.getProfile().id();
        }
        return null;
    }

    private static String formatPlayerTrackedLabel(String fallbackName) {
        String name = XaeroSyncValueUtils.safe(fallbackName).trim();
        if (name.isBlank()) return "[PLAYER] Player";
        if (name.regionMatches(true, 0, "[PLAYER]", 0, 8)) {
            String stripped = XaeroSyncValueUtils.safe(name.substring(8)).trim();
            return stripped.isBlank() ? "[PLAYER] Player" : "[PLAYER] " + stripped;
        }
        return "[PLAYER] " + name;
    }

    private static String formatPingDisplayLabel(String sender, String dimension, double x, double y, double z) {
        String safeSender = XaeroSyncValueUtils.safe(sender).trim();
        if (safeSender.isBlank()) safeSender = "Marker";
        String coords = String.format(java.util.Locale.ROOT, "%d %d %d", Math.round(x), Math.round(y), Math.round(z));
        return buildPingLabelByMode(safeSender, coords, resolvePingInfoMode());
    }

    private static String buildPingLabelByMode(String sender, String coords, com.example.addon.modules.Ping.InfoMode mode) {
        com.example.addon.modules.Ping.InfoMode effectiveMode = mode == null ? com.example.addon.modules.Ping.InfoMode.Distance : mode;
        return switch (effectiveMode) {
            case Distance -> "[PING] " + sender;
            case Coords -> "[PING] " + sender + " | " + coords;
        };
    }

    private static com.example.addon.modules.Ping.InfoMode resolvePingInfoMode() {
        try {
            var modules = meteordevelopment.meteorclient.systems.modules.Modules.get();
            if (modules == null) return com.example.addon.modules.Ping.InfoMode.Distance;
            com.example.addon.modules.Ping ping = modules.get(com.example.addon.modules.Ping.class);
            if (ping == null) return com.example.addon.modules.Ping.InfoMode.Distance;
            com.example.addon.modules.Ping.InfoMode mode = ping.xaeroInfoMode();
            return mode == null ? com.example.addon.modules.Ping.InfoMode.Distance : mode;
        } catch (Throwable ignored) {
            return com.example.addon.modules.Ping.InfoMode.Distance;
        }
    }
}


