package com.example.addon.modules;

import com.example.addon.AddonTemplate;
import com.example.addon.gui.screens.settings.LocalIconSelectScreen;
import com.example.addon.modules.xaerosync.XaeroEntryFormatting;
import com.example.addon.modules.xaerosync.XaeroOverlayController;
import com.example.addon.modules.xaerosync.XaeroPresenceStore;
import com.example.addon.modules.xaerosync.XaeroSyncConstants;
import com.example.addon.modules.xaerosync.XaeroSyncDebugController;
import com.example.addon.modules.xaerosync.XaeroSyncRuntimeController;
import com.example.addon.modules.xaerosync.XaeroSyncValueUtils;
import com.example.addon.modules.xaerosync.XaeroTrackedPingRenderCache;
import com.example.addon.util.CrashGuard;
import com.example.addon.util.MapIconManager;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.meteor.MouseClickEvent;
import meteordevelopment.meteorclient.events.render.Render2DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.gui.widgets.containers.WContainer;
import meteordevelopment.meteorclient.gui.widgets.containers.WVerticalList;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.util.Util;

import java.util.UUID;

public class XaeroSync extends Module {
    private static XaeroSync internalInstance;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgIcons = settings.createGroup("Icons");

    private final Setting<Integer> maxRows = sgGeneral.add(new IntSetting.Builder()
        .name("max-rows")
        .description("Max rows visible in Devils Sync panel.")
        .defaultValue(10)
        .min(4)
        .sliderRange(4, 18)
        .build()
    );

    private final Setting<Boolean> syncVerbose = sgGeneral.add(new BoolSetting.Builder()
        .name("sync-verbose-log")
        .description("Show technical XaeroSync lifecycle messages in chat.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> waypointDebug = sgGeneral.add(new BoolSetting.Builder()
        .name("debug-waypoint-pipeline")
        .description("Print stage-by-stage diagnostics for Ping -> XaeroSync -> Xaero Waypoint pipeline.")
        .defaultValue(false)
        .build()
    );

    private final Setting<String> markerIconPath = sgIcons.add(new StringSetting.Builder()
        .name("marker-icon-path")
        .description("Custom icon file (.png/.jpg) from <gameDir>/devils-addon/icons for ping/map markers. Empty = built-in Devils icon.")
        .defaultValue(XaeroSyncConstants.DEFAULT_DEVILS_MAP_ICON_PATH)
        .visible(() -> false)
        .build()
    );

    private final Setting<String> playerFallbackIconPath = sgIcons.add(new StringSetting.Builder()
        .name("player-fallback-icon-path")
        .description("Custom icon file (.png/.jpg) from <gameDir>/devils-addon/icons when player skin is unavailable.")
        .defaultValue(XaeroSyncConstants.DEFAULT_DEVILS_MAP_ICON_PATH)
        .visible(() -> false)
        .build()
    );

    private final XaeroSyncDebugController debugController;
    private final XaeroPresenceStore presenceStore;
    private final XaeroEntryFormatting entryFormatting;
    private final XaeroOverlayController overlayController;
    private final XaeroSyncRuntimeController syncController;

    public static synchronized XaeroSync ensureInternal() {
        if (internalInstance == null) internalInstance = new XaeroSync();
        return internalInstance;
    }

    public static boolean isWaypointIntegrationRunning() {
        Modules modules = Modules.get();
        if (modules != null) {
            XaeroSync module = modules.get(XaeroSync.class);
            if (module != null && module.isActive()) return true;
        }

        XaeroSync instance = internalInstance;
        return instance != null && MeteorClient.mc != null && MeteorClient.mc.world != null;
    }

    public XaeroSync() {
        super(
            AddonTemplate.CATEGORY,
            "xaero-sync",
            "Standalone Xaero World Map sync with Devils Sync jump panel."
        );
        internalInstance = this;
        autoSubscribe = false;
        runInMainMenu = true;

        debugController = new XaeroSyncDebugController(this);
        presenceStore = new XaeroPresenceStore(this, debugController);
        entryFormatting = new XaeroEntryFormatting(this);
        overlayController = new XaeroOverlayController(this, presenceStore, entryFormatting);
        syncController = new XaeroSyncRuntimeController(this, debugController, presenceStore);

        MeteorClient.EVENT_BUS.subscribe(this);
    }

    @Override
    public WWidget getWidget(GuiTheme theme) {
        WVerticalList list = theme.verticalList();
        WContainer controls = list.add(theme.horizontalList()).expandX().widget();

        WButton markerIcon = controls.add(theme.button("Marker Icon")).expandX().widget();
        markerIcon.action = () -> mc.setScreen(new LocalIconSelectScreen(
            theme,
            value -> markerIconPath.set(MapIconManager.normalizeIconPath(value))
        ));

        WButton playerFallbackIcon = controls.add(theme.button("Player Fallback")).expandX().widget();
        playerFallbackIcon.action = () -> mc.setScreen(new LocalIconSelectScreen(
            theme,
            value -> playerFallbackIconPath.set(MapIconManager.normalizeIconPath(value))
        ));

        WButton openFolder = controls.add(theme.button("Icons Folder")).expandX().widget();
        openFolder.action = () -> Util.getOperatingSystem().open(MapIconManager.ensureIconsDirectory().toUri().toString());

        String markerPath = MapIconManager.normalizeIconPath(markerIconPath.get());
        String fallbackPath = MapIconManager.normalizeIconPath(playerFallbackIconPath.get());
        list.add(theme.label("Marker icon: " + (markerPath.isBlank() ? "(built-in)" : markerPath))).expandX();
        list.add(theme.label("Player fallback: " + (fallbackPath.isBlank() ? "(skin/built-in)" : fallbackPath))).expandX();
        list.add(theme.label("Sync status: " + XaeroSyncValueUtils.safe(syncController.lastSyncStatus()))).expandX();

        return list;
    }

    public static void onXaeroMapRenderHook(Screen screen, net.minecraft.client.gui.DrawContext drawContext, int mouseX, int mouseY, float tickDelta) {
        onXaeroMapRenderProjectedHook(screen, drawContext, mouseX, mouseY, tickDelta, Double.NaN, Double.NaN, Double.NaN);
    }

    public static void onXaeroMapRenderProjectedHook(
        Screen screen,
        net.minecraft.client.gui.DrawContext drawContext,
        int mouseX,
        int mouseY,
        float tickDelta,
        double cameraX,
        double cameraZ,
        double scale
    ) {
        XaeroSync instance = internalInstance;
        if (instance == null) instance = ensureInternal();
        if (instance == null) return;
        XaeroSync resolved = instance;

        CrashGuard.run(resolved, "screenRenderHook", () -> {
            resolved.overlayController.renderOverlay(screen, drawContext, mouseX, mouseY, cameraX, cameraZ, scale);
        });
    }

    public static boolean onXaeroMapMouseClickHook(Screen screen, double mouseX, double mouseY, int button) {
        XaeroSync instance = internalInstance;
        if (instance == null) instance = ensureInternal();
        if (instance == null) return false;
        XaeroSync resolved = instance;

        final boolean[] handled = {false};
        CrashGuard.run(resolved, "screenMouseClickHook", () -> handled[0] = resolved.overlayController.handleOverlayClick(screen, button, mouseX, mouseY));
        return handled[0];
    }

    public static void onScreenRenderHook(Screen screen, net.minecraft.client.gui.DrawContext drawContext, int mouseX, int mouseY, float tickDelta) {
        onXaeroMapRenderHook(screen, drawContext, mouseX, mouseY, tickDelta);
    }

    public static boolean onScreenMouseClickHook(Screen screen, double mouseX, double mouseY, int button) {
        return onXaeroMapMouseClickHook(screen, mouseX, mouseY, button);
    }

    @Override
    public void onDeactivate() {
        clearState();
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        CrashGuard.run(this, "onGameLeft", this::clearState);
    }

    @EventHandler(priority = EventPriority.HIGHEST + 1)
    private void onMouseButton(MouseClickEvent event) {
        CrashGuard.run(this, "onMouseButton", () -> {
            if (overlayController.handleOverlayClick(event.action, event.button(), event)) event.setCancelled(true);
        });
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        CrashGuard.run(this, "onTick", syncController::handleSyncTick);
    }

    @EventHandler
    private void onRender2D(Render2DEvent event) {
        CrashGuard.run(this, "onRender2D", () -> {
            if (client().currentScreen != null && "xaero.map.gui.GuiMap".equals(client().currentScreen.getClass().getName())) return;
            overlayController.renderOverlay(event);
        });
    }

    private void clearState() {
        syncController.clearAll();
        overlayController.clear();
        XaeroTrackedPingRenderCache.clear();
        com.example.addon.util.XaeroSyncWaypoints.clear();
    }

    public MinecraftClient client() {
        return mc;
    }

    public boolean waypointDebugEnabled() {
        return waypointDebug.get();
    }

    public int maxRowsValue() {
        return Math.max(1, maxRows.get());
    }

    public String markerIconPathValue() {
        return markerIconPath.get();
    }

    public String playerFallbackIconPathValue() {
        return playerFallbackIconPath.get();
    }

    public void logSyncInternal(String format, Object... args) {
        if (!syncVerbose.get()) return;
        info(format, args);
    }

    public void handleSyncTickPublic() {
        syncController.handleSyncTick();
    }

    public static synchronized String resolveTrackedPingDisplayName(UUID uuid, String fallbackName) {
        return XaeroTrackedPingRenderCache.resolveTrackedPingDisplayName(uuid, fallbackName);
    }
}


