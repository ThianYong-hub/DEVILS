package com.devils.addon.util.xaerosync;

import com.devils.addon.DevilsAddon;

import java.util.List;
import java.util.Objects;

public final class XaeroWaypointVisibility {
    private XaeroWaypointVisibility() {
    }

    public static void ensureWaypointsVisible(XaeroWaypointContext context) {
        if (context.waypointVisibilityEnforced) return;

        try {
            Class<?> worldMapClass = Class.forName("xaero.map.WorldMap");
            Object worldMapInstance = worldMapClass.getField("INSTANCE").get(null);
            if (worldMapInstance == null) {
                XaeroWaypointContext.debugWaypointVisibilityIssue(context, "world map instance unavailable.");
                return;
            }

            Object configs = XaeroWaypointReflection.invokeNoArg(worldMapInstance, "getConfigs");
            if (configs == null) {
                XaeroWaypointContext.debugWaypointVisibilityIssue(context, "configs unavailable.");
                return;
            }

            Object clientConfigManager = XaeroWaypointReflection.invokeNoArg(configs, "getClientConfigManager");
            if (clientConfigManager == null) {
                XaeroWaypointContext.debugWaypointVisibilityIssue(context, "client config manager unavailable.");
                return;
            }

            boolean profileChanged = false;
            Object currentProfile = XaeroWaypointReflection.invokeNoArg(clientConfigManager, "getCurrentProfile");
            if (currentProfile != null) {
                profileChanged |= setProfileOption(currentProfile, "xaero.map.common.config.option.WorldMapProfiledConfigOptions", "WAYPOINTS", Boolean.TRUE);
                profileChanged |= setProfileOption(currentProfile, "xaero.map.common.config.option.WorldMapProfiledConfigOptions", "RENDER_WAYPOINTS", Boolean.TRUE);
                profileChanged |= setProfileOption(currentProfile, "xaero.map.common.config.option.WorldMapProfiledConfigOptions", "MIN_ZOOM_LOCAL_WAYPOINTS", 0.0);
            }

            Object minimapConfigs = null;
            Object minimapProfile = null;
            try {
                Object hudModInstance = XaeroWaypointReflection.readStaticField("xaero.common.HudMod", "INSTANCE");
                if (hudModInstance != null) {
                    minimapConfigs = XaeroWaypointReflection.invokeNoArg(hudModInstance, "getHudConfigs");
                    Object minimapClientConfigManager = minimapConfigs == null ? null : XaeroWaypointReflection.invokeNoArg(minimapConfigs, "getClientConfigManager");
                    minimapProfile = minimapClientConfigManager == null ? null : XaeroWaypointReflection.invokeNoArg(minimapClientConfigManager, "getCurrentProfile");
                }
            } catch (Throwable ignored) {
            }

            boolean minimapProfileChanged = false;
            if (minimapProfile != null) {
                minimapProfileChanged |= setProfileOption(minimapProfile, "xaero.hud.minimap.common.config.option.MinimapProfiledConfigOptions", "WAYPOINTS_IN_WORLD", Boolean.TRUE);
                minimapProfileChanged |= setProfileOption(minimapProfile, "xaero.hud.minimap.common.config.option.MinimapProfiledConfigOptions", "WAYPOINT_NAME_IN_WORLD", Boolean.TRUE);
                minimapProfileChanged |= setProfileOption(minimapProfile, "xaero.hud.minimap.common.config.option.MinimapProfiledConfigOptions", "WAYPOINTS_ON_MINIMAP", Boolean.TRUE);
                minimapProfileChanged |= setProfileOption(minimapProfile, "xaero.hud.minimap.common.config.option.MinimapProfiledConfigOptions", "WAYPOINT_DISTANCE_IN_WORLD", 2);
                minimapProfileChanged |= setProfileOption(minimapProfile, "xaero.hud.minimap.common.config.option.MinimapProfiledConfigOptions", "WAYPOINT_SHORT_DISTANCE_IN_WORLD", Boolean.TRUE);
                minimapProfileChanged |= setProfileOption(minimapProfile, "xaero.hud.minimap.common.config.option.MinimapProfiledConfigOptions", "WAYPOINT_ICON_SCALE_IN_WORLD", 0);
                minimapProfileChanged |= setProfileOption(minimapProfile, "xaero.hud.minimap.common.config.option.MinimapProfiledConfigOptions", "WAYPOINT_NAME_SCALE_IN_WORLD", 0);
                minimapProfileChanged |= setProfileOption(minimapProfile, "xaero.hud.minimap.common.config.option.MinimapProfiledConfigOptions", "WAYPOINT_DISTANCE_SCALE_IN_WORLD", 0);
                minimapProfileChanged |= setProfileOption(minimapProfile, "xaero.hud.minimap.common.config.option.MinimapProfiledConfigOptions", "WAYPOINT_CLOSE_SCALE_IN_WORLD", 1.0);
            }

            boolean primaryChanged = false;
            Object primaryConfigManager = XaeroWaypointReflection.invokeNoArg(clientConfigManager, "getPrimaryConfigManager");
            if (primaryConfigManager != null) {
                Object primaryConfig = XaeroWaypointReflection.invokeNoArg(primaryConfigManager, "getConfig");
                if (primaryConfig != null) {
                    Object onlyCurrentMapWaypointsOption = XaeroWaypointReflection.readStaticField(
                        "xaero.map.config.primary.option.WorldMapPrimaryClientConfigOptions",
                        "ONLY_CURRENT_MAP_WAYPOINTS"
                    );
                    if (onlyCurrentMapWaypointsOption != null) {
                        Object onlyCurrentMapWaypoints = XaeroWaypointReflection.invokeSingleArg(primaryConfig, "get", onlyCurrentMapWaypointsOption);
                        if (Boolean.TRUE.equals(onlyCurrentMapWaypoints)) {
                            XaeroWaypointReflection.invokeTwoArgs(primaryConfig, "set", onlyCurrentMapWaypointsOption, Boolean.FALSE);
                            primaryChanged = true;
                        }
                    }
                }
            }

            if (profileChanged) {
                Object profileIo = XaeroWaypointReflection.invokeNoArg(configs, "getClientConfigProfileIO");
                if (profileIo != null && currentProfile != null) XaeroWaypointReflection.invokeSingleArg(profileIo, "save", currentProfile);
            }
            if (minimapProfileChanged) {
                Object minimapProfileIo = minimapConfigs == null ? null : XaeroWaypointReflection.invokeNoArg(minimapConfigs, "getClientConfigProfileIO");
                if (minimapProfileIo != null && minimapProfile != null) XaeroWaypointReflection.invokeSingleArg(minimapProfileIo, "save", minimapProfile);
            }
            if (primaryChanged) {
                Object primaryIo = XaeroWaypointReflection.invokeNoArg(configs, "getPrimaryClientConfigManagerIO");
                if (primaryIo != null) XaeroWaypointReflection.invokeNoArg(primaryIo, "save");
            }

            context.waypointVisibilityEnforced = true;
            context.lastWaypointVisibilityIssue = "";
            XaeroWaypointContext.debug(
                context,
                "waypoint visibility enforced: profileChanged=%s primaryChanged=%s minimapProfileChanged=%s",
                profileChanged,
                primaryChanged,
                minimapProfileChanged
            );
        } catch (Throwable throwable) {
            DevilsAddon.LOG.debug("[Devils/XaeroSync] Failed to enforce waypoint visibility options.", throwable);
            XaeroWaypointContext.debugWaypointVisibilityIssue(context, "enforce failed: " + throwable.getClass().getSimpleName());
        }
    }

    public static void cleanupLegacyWaypoints(XaeroWaypointContext context) {
        if (context.legacyCleanupDone) return;

        try {
            Class<?> sessionClass = Class.forName("xaero.hud.minimap.BuiltInHudModules");
            Object minimap = sessionClass.getField("MINIMAP").get(null);
            Object session = minimap.getClass().getMethod("getCurrentSession").invoke(minimap);
            if (session == null) return;

            Object worldManager = session.getClass().getMethod("getWorldManager").invoke(session);
            if (worldManager == null) return;

            Object world = worldManager.getClass().getMethod("getCurrentWorld").invoke(worldManager);
            if (world == null) return;

            Object set = world.getClass().getMethod("getWaypointSet", String.class).invoke(world, XaeroWaypointContext.LEGACY_SET_NAME);
            if (set == null) {
                context.legacyCleanupDone = true;
                return;
            }

            List<Object> list = XaeroWaypointReflection.extractWaypointList(set);
            if (list != null) {
                list.removeIf(waypoint -> {
                    String name = XaeroWaypointReflection.readWaypointName(waypoint);
                    return name != null && name.endsWith(XaeroWaypointContext.LEGACY_WAYPOINT_SUFFIX);
                });
            }

            XaeroWaypointReflection.trySaveWorld(session, world);
            context.legacyCleanupDone = true;
        } catch (ClassNotFoundException ignored) {
            context.legacyCleanupDone = true;
        } catch (Throwable ignored) {
        }
    }

    private static boolean setProfileOption(Object profile, String optionClassName, String optionFieldName, Object desiredValue) {
        if (profile == null || optionClassName == null || optionClassName.isBlank() || optionFieldName == null || optionFieldName.isBlank()) {
            return false;
        }

        Object option = XaeroWaypointReflection.readStaticField(optionClassName, optionFieldName);
        if (option == null) return false;

        Object current = XaeroWaypointReflection.invokeSingleArg(profile, "get", option);
        if (Objects.equals(current, desiredValue)) return false;
        XaeroWaypointReflection.invokeTwoArgs(profile, "set", option, desiredValue);
        Object updated = XaeroWaypointReflection.invokeSingleArg(profile, "get", option);
        return Objects.equals(updated, desiredValue);
    }
}



