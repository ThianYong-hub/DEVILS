package com.example.addon.modules.xaerosync;

import com.example.addon.modules.Ping;
import com.example.addon.modules.XaeroSync;
import com.example.addon.util.MapIconManager;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.client.gui.PlayerSkinDrawer;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.network.PlayerListEntry;

import java.util.Locale;
import java.util.UUID;

public final class XaeroEntryFormatting {
    private final XaeroSync module;

    public XaeroEntryFormatting(XaeroSync module) {
        this.module = module;
    }

    public String formatEntry(JumpEntry entry) {
        return switch (entry.type()) {
            case PLAYER -> formatPlayerLabelWithDistance(entry);
            case PING -> formatPingLabelText(entry);
            case MARKER -> formatMapMarkerLabel(entry);
        };
    }

    public String resolveEntryIconPath(JumpEntry entry) {
        if (entry == null) return configuredMarkerIconPath();
        if (entry.type() == JumpType.PLAYER) return "";

        String explicit = MapIconManager.normalizeIconPath(entry.iconPath());
        if (!explicit.isBlank()) return explicit;
        return configuredMarkerIconPath();
    }

    public void drawPlayerHead(net.minecraft.client.gui.DrawContext drawContext, JumpEntry entry, int x, int y) {
        PlayerListEntry listEntry = resolvePlayerListEntry(entry);
        if (listEntry != null) {
            try {
                PlayerSkinDrawer.draw(drawContext, listEntry.getSkinTextures(), x, y, 8, 0xFFFFFFFF);
                return;
            } catch (Throwable ignored) {
            }
        }

        String configured = MapIconManager.normalizeIconPath(module.playerFallbackIconPathValue());
        drawMarkerIcon(drawContext, x, y, 8, configured.isBlank() ? XaeroSyncConstants.DEFAULT_DEVILS_MAP_ICON_PATH : configured);
    }

    public void drawMarkerIcon(net.minecraft.client.gui.DrawContext drawContext, int x, int y, int size, String iconPath) {
        if (MapIconManager.drawCustomIcon(drawContext, iconPath, x, y, size, 0xFFFFFFFF)) return;
        drawContext.drawTexture(
            RenderPipelines.GUI_TEXTURED,
            XaeroSyncConstants.XAERO_SYNC_ICON_TEXTURE,
            x,
            y,
            XaeroSyncConstants.DEVILS_MARKER_ICON_U,
            XaeroSyncConstants.DEVILS_MARKER_ICON_V,
            size,
            size,
            XaeroSyncConstants.DEVILS_MARKER_ICON_REGION_W,
            XaeroSyncConstants.DEVILS_MARKER_ICON_REGION_H,
            XaeroSyncConstants.DEVILS_MAP_ICON_SOURCE_SIZE,
            XaeroSyncConstants.DEVILS_MAP_ICON_SOURCE_SIZE,
            0xFFFFFFFF
        );
    }

    private String formatMapMarkerLabel(JumpEntry entry) {
        return formatPingMapLabel(entry, normalizePingSenderName(entry == null ? "" : entry.name()));
    }

    private String normalizePingSenderName(String raw) {
        String value = XaeroSyncValueUtils.safe(raw).trim();
        if (value.isBlank()) return "Marker";

        if (value.contains("|")) {
            String[] parts = value.split("\\|");
            value = XaeroSyncValueUtils.safe(parts[0]).trim();
        } else if (value.contains("/") || value.contains("\\")) {
            String[] parts = value.replace('\\', '/').split("/");
            if (parts.length >= 3 && XaeroSyncValueUtils.safe(parts[0]).toLowerCase(Locale.ROOT).contains("[ping]")) {
                value = XaeroSyncValueUtils.safe(parts[1]).trim();
            } else {
                value = XaeroSyncValueUtils.safe(parts[parts.length - 1]).trim();
            }
        }

        if (value.regionMatches(true, 0, "[PING]", 0, 6)) {
            value = XaeroSyncValueUtils.safe(value.substring(6)).trim();
        }

        int lastSpace = value.lastIndexOf(' ');
        if (lastSpace > 0) {
            String tail = XaeroSyncValueUtils.safe(value.substring(lastSpace + 1)).trim().toLowerCase(Locale.ROOT);
            if (tail.equals("~") || tail.equals("--") || tail.matches("\\d+(?:\\.\\d+)?(?:m|k)?")) {
                value = XaeroSyncValueUtils.safe(value.substring(0, lastSpace)).trim();
            }
        }

        value = value.replace("[Devils Sync]", "").trim();
        return value.isBlank() ? "Marker" : value;
    }

    private String formatPlayerLabelText(String rawName) {
        String name = XaeroSyncValueUtils.safe(rawName).trim();
        if (name.isBlank()) return "[PLAYER] Player";
        if (name.regionMatches(true, 0, "[PLAYER]", 0, 8)) {
            String stripped = XaeroSyncValueUtils.safe(name.substring(8)).trim();
            return stripped.isBlank() ? "[PLAYER] Player" : "[PLAYER] " + stripped;
        }
        if (name.regionMatches(true, 0, "[P]", 0, 3)) {
            String stripped = XaeroSyncValueUtils.safe(name.substring(3)).trim();
            return stripped.isBlank() ? "[PLAYER] Player" : "[PLAYER] " + stripped;
        }
        return "[PLAYER] " + name;
    }

    private String formatPingLabelText(JumpEntry entry) {
        return formatPingMapLabel(entry, normalizePingSenderName(entry == null ? "" : entry.name()));
    }

    private String formatPingMapLabel(JumpEntry entry, String sender) {
        String safeSender = XaeroSyncValueUtils.safe(sender).trim();
        if (safeSender.isBlank()) safeSender = "Marker";
        String coords = entry == null ? "-- -- --" : formatCoords(entry.x(), entry.y(), entry.z());
        return buildPingLabelByMode(safeSender, coords, resolvePingInfoMode());
    }

    private String formatPlayerLabelWithDistance(JumpEntry entry) {
        String base = formatPlayerLabelText(entry == null ? "" : entry.name());
        String distance = formatMapDistance(entry).toUpperCase(Locale.ROOT);
        return base + " - " + distance;
    }

    private String formatMapDistance(JumpEntry entry) {
        if (module.client().player == null || module.client().world == null || entry == null) return "--";

        String playerDimension = XaeroSyncValueUtils.normalizeKey(module.client().world.getRegistryKey().getValue().toString());
        String markerDimension = XaeroSyncValueUtils.normalizeKey(entry.dimension());
        if (!markerDimension.isBlank() && !playerDimension.isBlank() && !markerDimension.equals(playerDimension)) return "~";

        double dx = entry.x() - module.client().player.getX();
        double dy = entry.y() - module.client().player.getY();
        double dz = entry.z() - module.client().player.getZ();
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (!Double.isFinite(distance)) return "--";

        if (distance >= 1000) return String.format(Locale.ROOT, "%.1fk", distance / 1000.0);
        if (distance >= 100) return String.format(Locale.ROOT, "%.0fm", distance);
        return String.format(Locale.ROOT, "%.1fm", distance);
    }

    private PlayerListEntry resolvePlayerListEntry(JumpEntry entry) {
        if (module.client().getNetworkHandler() == null || entry == null) return null;

        String uuidRaw = XaeroSyncValueUtils.safe(entry.playerUuid());
        if (!uuidRaw.isBlank()) {
            try {
                PlayerListEntry byUuid = module.client().getNetworkHandler().getPlayerListEntry(UUID.fromString(uuidRaw));
                if (byUuid != null) return byUuid;
            } catch (Throwable ignored) {
            }
        }

        String needle = XaeroSyncValueUtils.normalizeKey(entry.name());
        for (PlayerListEntry listEntry : module.client().getNetworkHandler().getPlayerList()) {
            if (listEntry == null || listEntry.getProfile() == null || listEntry.getProfile().getName() == null) continue;
            if (needle.equals(XaeroSyncValueUtils.normalizeKey(listEntry.getProfile().getName()))) return listEntry;
        }
        return null;
    }

    private String configuredMarkerIconPath() {
        String configured = MapIconManager.normalizeIconPath(module.markerIconPathValue());
        return configured.isBlank() ? XaeroSyncConstants.DEFAULT_DEVILS_MAP_ICON_PATH : configured;
    }

    private String formatCoords(double x, double y, double z) {
        return String.format(Locale.ROOT, "%d %d %d", Math.round(x), Math.round(y), Math.round(z));
    }

    private String buildPingLabelByMode(String sender, String coords, Ping.InfoMode mode) {
        Ping.InfoMode effectiveMode = mode == null ? Ping.InfoMode.Distance : mode;
        return switch (effectiveMode) {
            case Distance -> "[PING] " + sender;
            case Coords -> "[PING] " + sender + " | " + coords;
        };
    }

    private Ping.InfoMode resolvePingInfoMode() {
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
}


