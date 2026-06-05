package com.devils.addon.modules.chesttracker;

import com.devils.addon.DevilsAddon;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

public final class ChestTrackerBridge {
    private final String runtimeStateClass;
    private final String configClass;
    private final String backendTypeClass;

    public ChestTrackerBridge(String runtimeStateClass, String configClass, String backendTypeClass) {
        this.runtimeStateClass = runtimeStateClass;
        this.configClass = configClass;
        this.backendTypeClass = backendTypeClass;
    }

    public void setRuntimeEnabled(boolean enabled) {
        try {
            Class<?> runtime = Class.forName(runtimeStateClass);
            Method setEnabled = runtime.getMethod("setModuleEnabled", boolean.class);
            setEnabled.invoke(null, enabled);
        } catch (Throwable t) {
            DevilsAddon.LOG.debug("[Devils/ChestTracker] Runtime bridge unavailable for moduleEnabled.", t);
        }
    }

    public void applyRuntimeTheme(boolean devilsTheme, int accentColorRgb, int overlayAlpha) {
        try {
            Class<?> runtime = Class.forName(runtimeStateClass);
            runtime.getMethod("setDevilsThemeEnabled", boolean.class).invoke(null, devilsTheme);
            runtime.getMethod("setDevilsAccentColor", int.class).invoke(null, accentColorRgb);
            runtime.getMethod("setDevilsOverlayAlpha", int.class).invoke(null, overlayAlpha);
        } catch (Throwable t) {
            DevilsAddon.LOG.debug("[Devils/ChestTracker] Runtime bridge unavailable for theme settings.", t);
        }
    }

    public void applyConfigState(
        boolean save,
        boolean moduleActive,
        boolean inventoryButton,
        boolean devilsTheme,
        int accentColorRgb,
        int overlayAlpha,
        boolean asyncSaving
    ) {
        try {
            Object handler = getConfigHandler();
            if (handler == null) return;

            Object config = handler.getClass().getMethod("instance").invoke(handler);
            if (config == null) return;

            Object gui = tryGetField(config, "gui");
            Object storage = tryGetField(config, "storage");

            if (gui != null) {
                setNestedBooleanIfPresent(gui, "inventoryButton", "enabled", inventoryButton && moduleActive);
                setBooleanIfPresent(gui, "devilsTheme", devilsTheme);
                setIntIfPresent(gui, "devilsAccentColor", accentColorRgb);
                setIntIfPresent(gui, "devilsOverlayAlpha", overlayAlpha);
            }

            if (storage != null) {
                setBooleanIfPresent(storage, "AsyncSaving", asyncSaving);
                setBooleanIfPresent(storage, "entityMemories", false);
                setBooleanIfPresent(storage, "readableJsonMemories", false);
                setEnumIfPresent(storage, "storageBackend", backendTypeClass, "NBT");
            }

            invokeIfPresent(config, "validate");
            if (save) invokeIfPresent(handler, "save");
        } catch (Throwable t) {
            DevilsAddon.LOG.debug("[Devils/ChestTracker] Config bridge unavailable.", t);
        }
    }

    public void saveConfig() {
        try {
            Object handler = getConfigHandler();
            if (handler != null) handler.getClass().getMethod("save").invoke(handler);
        } catch (Throwable t) {
            DevilsAddon.LOG.debug("[Devils/ChestTracker] Failed to save ChestTracker config.", t);
        }
    }

    private Object getConfigHandler() throws Exception {
        Class<?> configClassObj = Class.forName(configClass);
        return configClassObj.getField("INSTANCE").get(null);
    }

    private static Object tryGetField(Object owner, String fieldName) {
        if (owner == null || fieldName == null || fieldName.isBlank()) return null;
        try {
            return owner.getClass().getField(fieldName).get(owner);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void setBooleanIfPresent(Object owner, String fieldName, boolean value) {
        if (owner == null || fieldName == null || fieldName.isBlank()) return;
        try {
            Field field = owner.getClass().getField(fieldName);
            field.setBoolean(owner, value);
        } catch (Throwable ignored) {
        }
    }

    private static void setIntIfPresent(Object owner, String fieldName, int value) {
        if (owner == null || fieldName == null || fieldName.isBlank()) return;
        try {
            Field field = owner.getClass().getField(fieldName);
            field.setInt(owner, value);
        } catch (Throwable ignored) {
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void setEnumIfPresent(Object owner, String fieldName, String enumClassName, String enumConstant) {
        if (owner == null || fieldName == null || fieldName.isBlank()) return;
        if (enumClassName == null || enumClassName.isBlank()) return;
        if (enumConstant == null || enumConstant.isBlank()) return;
        try {
            Field field = owner.getClass().getField(fieldName);
            Class<?> enumClass = Class.forName(enumClassName);
            if (!enumClass.isEnum()) return;
            Object enumValue = Enum.valueOf((Class<? extends Enum>) enumClass, enumConstant);
            field.set(owner, enumValue);
        } catch (Throwable ignored) {
        }
    }

    private static void setNestedBooleanIfPresent(Object owner, String nestedField, String fieldName, boolean value) {
        Object nested = tryGetField(owner, nestedField);
        setBooleanIfPresent(nested, fieldName, value);
    }

    private static void invokeIfPresent(Object owner, String methodName) {
        if (owner == null || methodName == null || methodName.isBlank()) return;
        try {
            Method method = owner.getClass().getMethod(methodName);
            method.setAccessible(true);
            method.invoke(owner);
        } catch (Throwable ignored) {
        }
    }

    public static final class ScreenLauncher {
        private final String chestTrackerClass;
        private final String configScreenBuilderClass;

        public ScreenLauncher(String chestTrackerClass, String configScreenBuilderClass) {
            this.chestTrackerClass = chestTrackerClass;
            this.configScreenBuilderClass = configScreenBuilderClass;
        }

        public void openTrackerGui() throws Exception {
            Class<?> chestTracker = Class.forName(chestTrackerClass);
            Method openInGame = resolveOpenInGameMethod(chestTracker);
            if (openInGame == null) throw new NoSuchMethodException("openInGame");
            MinecraftClient mc = MinecraftClient.getInstance();
            openInGame.invoke(null, mc, mc.currentScreen);
        }

        public void openTrackerConfig() throws Exception {
            Class<?> builder = Class.forName(configScreenBuilderClass);
            Method build = resolveBuildMethod(builder);
            if (build == null) throw new NoSuchMethodException("build");
            MinecraftClient mc = MinecraftClient.getInstance();
            Object built = build.invoke(null, mc.currentScreen);
            if (built instanceof Screen screen) mc.setScreen(screen);
        }

        private static Method resolveOpenInGameMethod(Class<?> owner) {
            if (owner == null) return null;
            for (Method method : owner.getMethods()) {
                if (!"openInGame".equals(method.getName())) continue;
                Class<?>[] params = method.getParameterTypes();
                if (params.length != 2) continue;
                if (!params[0].isAssignableFrom(MinecraftClient.class)) continue;
                if (!params[1].isAssignableFrom(Screen.class)) continue;
                method.setAccessible(true);
                return method;
            }
            return null;
        }

        private static Method resolveBuildMethod(Class<?> owner) {
            if (owner == null) return null;
            for (Method method : owner.getMethods()) {
                if (!"build".equals(method.getName())) continue;
                Class<?>[] params = method.getParameterTypes();
                if (params.length != 1) continue;
                if (!params[0].isAssignableFrom(Screen.class)) continue;
                if (!Screen.class.isAssignableFrom(method.getReturnType())) continue;
                method.setAccessible(true);
                return method;
            }
            return null;
        }
    }
}


