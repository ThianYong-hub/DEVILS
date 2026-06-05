package com.devils.addon.modules.xaerosync;

import com.devils.addon.modules.Ping;
import com.devils.addon.modules.XaeroSync;
import com.devils.addon.util.XaeroSyncWaypoints;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.Utils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class XaeroSyncValueUtils {
    private static final Pattern HOST_PORT_PATTERN = Pattern.compile("^([^:]+):(\\d+)$");
    private static final Pattern BRACKETED_HOST_PORT_PATTERN = Pattern.compile("^\\[([^\\]]+)](?::(\\d+))?$");
    private static final int WORLD_MIN_Y = -65;
    private static final int WORLD_MAX_Y = 365;

    private XaeroSyncValueUtils() {
    }

    public static String safe(String value) {
        return value == null ? "" : value;
    }

    public static String normalizeKey(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    public static String normalizeServerKey(String value) {
        String normalized = normalizeKey(value);
        if (normalized.isBlank()) return normalized;

        String host = normalized;
        String port = "";

        Matcher bracketed = BRACKETED_HOST_PORT_PATTERN.matcher(normalized);
        if (bracketed.matches()) {
            host = normalizeKey(bracketed.group(1));
            port = safe(bracketed.group(2)).trim();
        } else {
            Matcher hostPort = HOST_PORT_PATTERN.matcher(normalized);
            if (hostPort.matches()) {
                host = normalizeKey(hostPort.group(1));
                port = safe(hostPort.group(2)).trim();
            }
        }

        if (host.equals("localhost") || host.equals("127.0.0.1") || host.equals("::1") || host.equals("0:0:0:0:0:0:0:1")) {
            host = "loopback";
        }

        if (port.isBlank() || port.equals("25565")) return host;
        return host + ":" + port;
    }

    public static double clampY(double y) {
        if (!Double.isFinite(y)) return WORLD_MIN_Y;
        if (y < WORLD_MIN_Y) return WORLD_MIN_Y;
        if (y > WORLD_MAX_Y) return WORLD_MAX_Y;
        return y;
    }

    public static double clampDouble(double value, double min, double max) {
        if (!Double.isFinite(value)) return min;
        if (value < min) return min;
        if (value > max) return max;
        return value;
    }

    public static double lerp(double from, double to, double alpha) {
        return from + ((to - from) * alpha);
    }

    public static double distanceSq(double ax, double ay, double az, double bx, double by, double bz) {
        double dx = bx - ax;
        double dy = by - ay;
        double dz = bz - az;
        return (dx * dx) + (dy * dy) + (dz * dz);
    }
}

record JumpEntry(
    JumpType type,
    String id,
    String name,
    String playerUuid,
    String dimension,
    double x,
    double y,
    double z,
    long updatedAtMs,
    String iconPath
) {
}

enum JumpType {
    PLAYER,
    PING,
    MARKER
}

record MapProjection(
    double cameraX,
    double cameraZ,
    double scale,
    int width,
    int height,
    String dimensionId,
    int mapMinX,
    int mapMinY,
    int mapMaxX,
    int mapMaxY
) {
}

record OverlayLayout(
    int buttonX,
    int buttonY,
    int panelX,
    int panelY,
    int panelH,
    int shownRows
) {
}

record PingMarkerRouting(
    List<XaeroSyncWaypoints.PlayerMarker> trackedMarkers,
    List<XaeroSyncWaypoints.MapWaypointMarker> mapMarkers,
    int totalCount,
    int trackedCount,
    int fallbackCount,
    int collisionCount
) {
}

record PingRenderMarker(
    String name,
    String dimension,
    double x,
    double y,
    double z
) {
}

record PresenceMarker(
    String id,
    String sender,
    String playerUuid,
    String senderDevice,
    String server,
    String dimension,
    double x,
    double y,
    double z,
    double vx,
    double vy,
    double vz,
    long sequence,
    long updatedAtMs
) {
}

record PresenceMotionState(
    String id,
    String sourceDimension,
    long sourceSequence,
    long sourceUpdatedAtMs,
    long sourceReceivedAtMs,
    double sourceX,
    double sourceY,
    double sourceZ,
    double velocityX,
    double velocityY,
    double velocityZ,
    long renderUpdatedAtMs,
    double renderX,
    double renderY,
    double renderZ
) {
}

record PresenceRenderPosition(double x, double y, double z) {
}

record ToolbarAnchor(int x, int y) {
}

final class XaeroJumpEntryCollector {
    private final XaeroSync module;

    XaeroJumpEntryCollector(XaeroSync module) {
        this.module = module;
    }

    List<JumpEntry> collect(Iterable<PresenceMarker> syncedPresence) {
        ArrayList<JumpEntry> rows = new ArrayList<>();
        long now = System.currentTimeMillis();
        String selfName = XaeroSyncValueUtils.normalizeKey(currentUsername());
        String selfUuid = XaeroSyncValueUtils.normalizeKey(currentPlayerUuid());
        String serverKey = XaeroSyncValueUtils.normalizeServerKey(currentServerKey());

        for (PresenceMarker presence : syncedPresence) {
            if (presence == null) continue;
            if ((now - presence.updatedAtMs()) > XaeroSyncConstants.PRESENCE_STALE_MS) continue;

            String sender = XaeroSyncValueUtils.normalizeKey(presence.sender());
            if (sender.isBlank()) continue;
            if (!selfUuid.isBlank() && selfUuid.equals(XaeroSyncValueUtils.normalizeKey(presence.playerUuid()))) continue;
            if (!selfName.isBlank() && selfName.equals(sender)) continue;

            String markerServer = XaeroSyncValueUtils.normalizeServerKey(presence.server());
            if (!serverKey.isBlank() && !markerServer.isBlank() && !serverKey.equals(markerServer)) continue;
            rows.add(new JumpEntry(
                JumpType.PLAYER,
                presence.id(),
                presence.sender(),
                presence.playerUuid(),
                presence.dimension(),
                presence.x(),
                presence.y(),
                presence.z(),
                presence.updatedAtMs(),
                ""
            ));
        }

        Modules modules = Modules.get();
        Ping ping = modules == null ? null : modules.get(Ping.class);
        if (ping != null) {
            for (Ping.MarkerJumpTarget marker : ping.snapshotMarkerTargets()) {
                if (marker == null) continue;
                String markerServer = XaeroSyncValueUtils.normalizeServerKey(marker.server());
                if (!serverKey.isBlank() && !markerServer.isBlank() && !serverKey.equals(markerServer)) continue;
                rows.add(new JumpEntry(
                    JumpType.PING,
                    marker.id(),
                    XaeroSyncValueUtils.safe(marker.sender()).trim(),
                    "",
                    marker.dimension(),
                    marker.x(),
                    marker.y(),
                    marker.z(),
                    marker.createdAtMs(),
                    marker.iconPath()
                ));
            }
        }

        rows.sort(Comparator
            .comparing(JumpEntry::type)
            .thenComparing(Comparator.comparingLong(JumpEntry::updatedAtMs).reversed())
            .thenComparing(entry -> XaeroSyncValueUtils.normalizeKey(entry.name())));
        return rows;
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
}


