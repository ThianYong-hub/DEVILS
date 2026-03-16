package com.example.addon.modules.chesttracker;

import com.example.addon.AddonTemplate;
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
            AddonTemplate.LOG.debug("[Devils/ChestTracker] Runtime bridge unavailable for moduleEnabled.", t);
        }
    }

    public void applyRuntimeTheme(boolean devilsTheme, int accentColorRgb, int overlayAlpha) {
        try {
            Class<?> runtime = Class.forName(runtimeStateClass);
            runtime.getMethod("setDevilsThemeEnabled", boolean.class).invoke(null, devilsTheme);
            runtime.getMethod("setDevilsAccentColor", int.class).invoke(null, accentColorRgb);
            runtime.getMethod("setDevilsOverlayAlpha", int.class).invoke(null, overlayAlpha);
        } catch (Throwable t) {
            AddonTemplate.LOG.debug("[Devils/ChestTracker] Runtime bridge unavailable for theme settings.", t);
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
            Object gui = getField(config, "gui");
            Object storage = getField(config, "storage");

            setBoolean(gui, "inventoryButton", "enabled", inventoryButton && moduleActive);
            setBoolean(gui, "devilsTheme", devilsTheme);
            setInt(gui, "devilsAccentColor", accentColorRgb);
            setInt(gui, "devilsOverlayAlpha", overlayAlpha);

            setBoolean(storage, "AsyncSaving", asyncSaving);
            setBoolean(storage, "entityMemories", false);
            setBoolean(storage, "readableJsonMemories", false);

            Class<?> backendTypeClassObj = Class.forName(backendTypeClass);
            @SuppressWarnings({"unchecked", "rawtypes"})
            Object backendType = Enum.valueOf((Class<? extends Enum>) backendTypeClassObj, "NBT");
            Field backendField = storage.getClass().getField("storageBackend");
            backendField.set(storage, backendType);

            Method validate = config.getClass().getMethod("validate");
            validate.invoke(config);

            if (save) handler.getClass().getMethod("save").invoke(handler);
        } catch (Throwable t) {
            AddonTemplate.LOG.debug("[Devils/ChestTracker] Config bridge unavailable.", t);
        }
    }

    public void saveConfig() {
        try {
            Object handler = getConfigHandler();
            if (handler != null) handler.getClass().getMethod("save").invoke(handler);
        } catch (Throwable t) {
            AddonTemplate.LOG.debug("[Devils/ChestTracker] Failed to save ChestTracker config.", t);
        }
    }

    private Object getConfigHandler() throws Exception {
        Class<?> configClassObj = Class.forName(configClass);
        return configClassObj.getField("INSTANCE").get(null);
    }

    private static Object getField(Object owner, String fieldName) throws Exception {
        return owner.getClass().getField(fieldName).get(owner);
    }

    private static void setBoolean(Object owner, String fieldName, boolean value) throws Exception {
        Field field = owner.getClass().getField(fieldName);
        field.setBoolean(owner, value);
    }

    private static void setInt(Object owner, String fieldName, int value) throws Exception {
        Field field = owner.getClass().getField(fieldName);
        field.setInt(owner, value);
    }

    private static void setBoolean(Object owner, String nestedField, String fieldName, boolean value) throws Exception {
        Object nested = getField(owner, nestedField);
        setBoolean(nested, fieldName, value);
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
            Method openInGame = chestTracker.getMethod("openInGame", MinecraftClient.class, Screen.class);
            MinecraftClient mc = MinecraftClient.getInstance();
            openInGame.invoke(null, mc, mc.currentScreen);
        }

        public void openTrackerConfig() throws Exception {
            Class<?> builder = Class.forName(configScreenBuilderClass);
            Method build = builder.getMethod("build", Screen.class);
            MinecraftClient mc = MinecraftClient.getInstance();
            Object built = build.invoke(null, mc.currentScreen);
            if (built instanceof Screen screen) mc.setScreen(screen);
        }
    }
}


