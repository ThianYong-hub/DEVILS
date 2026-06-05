package com.devils.addon.util.xaerosync;

import com.devils.addon.modules.Ping;
import com.devils.addon.util.MapIconManager;
import meteordevelopment.meteorclient.systems.modules.Modules;

import java.util.Locale;

public final class XaeroWaypointNaming {
    private XaeroWaypointNaming() {
    }

    public static boolean isManagedWaypoint(XaeroWaypointContext context, Object waypoint) {
        if (waypoint == null) return false;
        Boolean temporary = XaeroWaypointReflection.invokeBooleanNoArg(waypoint, "isTemporary");
        if (!Boolean.TRUE.equals(temporary)) return false;

        String name = XaeroWaypointReflection.readWaypointName(waypoint);
        if (name == null) return false;
        if (name.endsWith(XaeroWaypointContext.MANAGED_WAYPOINT_SUFFIX) || name.endsWith(XaeroWaypointContext.MANAGED_WAYPOINT_SUFFIX_LEGACY_VISIBLE)) {
            return true;
        }

        String symbol = XaeroWaypointReflection.readWaypointSymbol(waypoint);
        String normalizedName = XaeroWaypointReflection.normalize(name);
        if ((XaeroWaypointContext.PING_FALLBACK_SYMBOL.equals(symbol)
            || XaeroWaypointContext.INVISIBLE_WAYPOINT_SYMBOL.equals(symbol)
            || XaeroWaypointReflection.normalize(symbol).isBlank()) && normalizedName.startsWith("[ping]")) {
            return true;
        }
        return XaeroWaypointContext.PLAYER_FALLBACK_SYMBOL.equals(symbol) && normalizedName.startsWith("[player]");
    }

    public static String resolveManagedWaypointRenderName(XaeroWaypointContext context, Object waypoint, String originalName) {
        if (!isManagedWaypoint(context, waypoint)) return originalName;
        String strippedName = stripManagedWaypointSuffix(originalName == null ? "" : originalName.trim());
        if (strippedName.isBlank()) return strippedName;
        if (!strippedName.regionMatches(true, 0, "[PING]", 0, 6)) return strippedName;

        String sender = extractPingSenderFromLabel(strippedName);
        if (sender.isBlank()) sender = "Marker";
        String coords = formatWaypointCoords(
            readWaypointCoordinate(waypoint, "getX"),
            readWaypointCoordinate(waypoint, "getY"),
            readWaypointCoordinate(waypoint, "getZ")
        );
        return formatManagedPingLabel(sender, coords, resolvePingInfoMode());
    }

    public static String resolveManagedWaypointIconPath(XaeroWaypointContext context, Object waypoint) {
        String rawName = XaeroWaypointReflection.readWaypointName(waypoint);
        String symbol = XaeroWaypointReflection.readWaypointSymbol(waypoint);
        if (!isManagedWaypoint(context, waypoint)) {
            String normalizedName = XaeroWaypointReflection.normalize(rawName);
            boolean looksLikeManagedPing = (XaeroWaypointContext.PING_FALLBACK_SYMBOL.equals(symbol)
                || XaeroWaypointContext.INVISIBLE_WAYPOINT_SYMBOL.equals(symbol)
                || XaeroWaypointReflection.normalize(symbol).isBlank()) && normalizedName.startsWith("[ping]");
            boolean looksLikeManagedPlayer = XaeroWaypointContext.PLAYER_FALLBACK_SYMBOL.equals(symbol) && normalizedName.startsWith("[player]");
            if (!looksLikeManagedPing && !looksLikeManagedPlayer) return "";
        }

        String name = stripManagedWaypointSuffix(rawName == null ? "" : rawName);
        int x = readWaypointCoordinate(waypoint, "getX");
        int y = readWaypointCoordinate(waypoint, "getY");
        int z = readWaypointCoordinate(waypoint, "getZ");
        String key = managedWaypointRenderKey(name, x, y, z);
        String direct = context.managedWaypointIconPaths.getOrDefault(key, "");
        if (!direct.isBlank()) return direct;

        String strippedLegacyName = stripLegacyIconTag(name);
        if (!strippedLegacyName.equals(name)) {
            String strippedKey = managedWaypointRenderKey(strippedLegacyName, x, y, z);
            String strippedLookup = context.managedWaypointIconPaths.getOrDefault(strippedKey, "");
            if (!strippedLookup.isBlank()) return strippedLookup;
        }

        String legacyPath = parseLegacyIconPath(rawName == null ? "" : rawName);
        if (!legacyPath.isBlank()) return legacyPath;
        return MapIconManager.DEFAULT_MAP_ICON_PATH;
    }

    public static String formatManagedWaypointName(com.devils.addon.util.XaeroSyncWaypoints.MapWaypointMarker marker) {
        if (marker == null) return XaeroWaypointContext.INVISIBLE_WAYPOINT_NAME;

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

    public static String managedWaypointRenderKey(String name, int x, int y, int z) {
        return XaeroWaypointReflection.normalize(name) + "|" + x + "|" + y + "|" + z;
    }

    public static String managedWaypointStateKey(String name, int x, int y, int z, String symbol) {
        return managedWaypointRenderKey(name, x, y, z) + "|" + XaeroWaypointReflection.normalize(symbol);
    }

    public static String managedWaypointStateKey(XaeroWaypointContext context, Object waypoint) {
        if (waypoint == null) return "";
        String name = stripManagedWaypointSuffix(XaeroWaypointReflection.readWaypointName(waypoint));
        int x = readWaypointCoordinate(waypoint, "getX");
        int y = readWaypointCoordinate(waypoint, "getY");
        int z = readWaypointCoordinate(waypoint, "getZ");
        String symbol = XaeroWaypointReflection.readWaypointSymbol(waypoint);
        return managedWaypointStateKey(name, x, y, z, symbol);
    }

    public static int readWaypointCoordinate(Object waypoint, String methodName) {
        Object value = XaeroWaypointReflection.invokeNoArg(waypoint, methodName);
        if (value instanceof Number number) return number.intValue();
        return 0;
    }

    public static String stripManagedWaypointSuffix(String rawName) {
        String value = rawName == null ? "" : rawName;
        value = stripLegacyIconTag(value);
        if (value.endsWith(XaeroWaypointContext.MANAGED_WAYPOINT_SUFFIX)) {
            return value.substring(0, value.length() - XaeroWaypointContext.MANAGED_WAYPOINT_SUFFIX.length());
        }
        if (value.endsWith(XaeroWaypointContext.MANAGED_WAYPOINT_SUFFIX_LEGACY_VISIBLE)) {
            return value.substring(0, value.length() - XaeroWaypointContext.MANAGED_WAYPOINT_SUFFIX_LEGACY_VISIBLE.length());
        }
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
        if (value.regionMatches(true, 0, "[PING]", 0, 6)) value = value.substring(6).trim();
        if (value.startsWith("/")) value = value.substring(1).trim();

        if (value.contains("|")) {
            String[] parts = value.split("\\|");
            value = parts.length == 0 ? value : parts[0].trim();
        } else if (value.contains("/") || value.contains("\\")) {
            String[] parts = value.replace('\\', '/').split("/");
            value = parts.length >= 2 ? parts[0].trim() : parts[parts.length - 1].trim();
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
}


