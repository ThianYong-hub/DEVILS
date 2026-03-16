package com.example.addon.modules;

import com.example.addon.AddonTemplate;
import com.example.addon.audio.JoinSoundPlayer;
import com.example.addon.gui.screens.settings.LocalIconSelectScreen;
import com.example.addon.gui.screens.settings.SelectionScreens.LocalSoundSelectScreen;
import com.example.addon.modules.ping.PingConstants;
import com.example.addon.modules.ping.PingFormattingUtils;
import com.example.addon.modules.ping.PingMarkerController;
import com.example.addon.modules.ping.PingRenderController;
import com.example.addon.modules.ping.PingSyncController;
import com.example.addon.util.CrashGuard;
import com.example.addon.util.MapIconManager;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.game.GameJoinedEvent;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.meteor.KeyEvent;
import meteordevelopment.meteorclient.events.meteor.MouseButtonEvent;
import meteordevelopment.meteorclient.events.render.Render2DEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.gui.widgets.containers.WContainer;
import meteordevelopment.meteorclient.gui.widgets.containers.WVerticalList;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.misc.input.KeyAction;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.client.MinecraftClient;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.Util;

import java.util.List;

public class Ping extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Render");

    private final Setting<Boolean> logoutSpots = sgGeneral.add(new BoolSetting.Builder()
        .name("logout-spots")
        .description("Keep marker visible after sender leaves loaded world entities.")
        .defaultValue(false)
        .build()
    );

    private final Setting<InfoMode> infoMode = sgGeneral.add(new EnumSetting.Builder<InfoMode>()
        .name("info")
        .description("Information shown near ping marker.")
        .defaultValue(InfoMode.Distance)
        .build()
    );

    private final Setting<Boolean> icon = sgGeneral.add(new BoolSetting.Builder()
        .name("icon")
        .description("Show icon near ping marker text.")
        .defaultValue(true)
        .build()
    );

    private final Setting<String> iconPath = sgGeneral.add(new StringSetting.Builder()
        .name("icon-path")
        .description("Custom icon file (.png/.jpg) from <gameDir>/devils-addon/icons. Empty = built-in Devils icon.")
        .defaultValue(PingConstants.DEFAULT_PING_ICON_PATH)
        .visible(() -> false)
        .build()
    );

    private final Setting<Boolean> syncVerbose = sgGeneral.add(new BoolSetting.Builder()
        .name("sync-verbose-log")
        .description("Show technical sync lifecycle messages in chat.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> xaeroMapDebug = sgGeneral.add(new BoolSetting.Builder()
        .name("xaero-map-debug")
        .description("Enable debug logs for Ping -> Xaero World Map marker bridge.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Double> raycastRange = sgGeneral.add(new DoubleSetting.Builder()
        .name("raycast-range")
        .description("Range used to resolve ping point from crosshair.")
        .defaultValue(160)
        .min(8)
        .sliderRange(8, 512)
        .build()
    );

    private final Setting<Integer> pingVolume = sgGeneral.add(new IntSetting.Builder()
        .name("volume")
        .description("Ping sound volume in percent.")
        .defaultValue(100)
        .min(0)
        .sliderRange(0, 100)
        .build()
    );

    private final Setting<SettingColor> markerColor = sgRender.add(new ColorSetting.Builder()
        .name("marker-color")
        .description("Color of marker at ping position.")
        .defaultValue(new SettingColor(255, 132, 20, 95))
        .build()
    );

    private final Setting<SettingColor> textColor = sgRender.add(new ColorSetting.Builder()
        .name("text-color")
        .description("Color of ping info text.")
        .defaultValue(new SettingColor(255, 255, 255, 255))
        .build()
    );

    private final Setting<SettingColor> textBackgroundColor = sgRender.add(new ColorSetting.Builder()
        .name("text-background-color")
        .description("Background color for ping info text.")
        .defaultValue(new SettingColor(0, 0, 0, 120))
        .build()
    );

    private final PingMarkerController markerController = new PingMarkerController(this);
    private final PingRenderController renderController = new PingRenderController(this, markerController);
    private final PingSyncController syncController = new PingSyncController(this, markerController);

    private String pingSoundPath = "";

    public Ping() {
        super(AddonTemplate.CATEGORY, "ping", "Sends synchronized ping markers to friends with Devils addon + sync enabled.");
        autoSubscribe = false;
        runInMainMenu = true;
        MeteorClient.EVENT_BUS.subscribe(this);
    }

    @Override
    public void onActivate() {
        toggleOnBindRelease = false;
        if (MapIconManager.normalizeIconPath(iconPath.get()).isBlank()) iconPath.set(PingConstants.DEFAULT_PING_ICON_PATH);
    }

    @Override
    public void onDeactivate() {
        syncController.onDeactivate();
        markerController.onDeactivate();
    }

    @Override
    public NbtCompound toTag() {
        NbtCompound tag = super.toTag();
        tag.putString("ping-sound-path", pingSoundPath);
        tag.putString("ping-icon-path", iconPath.get());
        return tag;
    }

    @Override
    public Module fromTag(NbtCompound tag) {
        super.fromTag(tag);
        pingSoundPath = PingFormattingUtils.normalizeLocalSoundPath(tag.getString("ping-sound-path", ""));
        String loaded = MapIconManager.normalizeIconPath(tag.getString("ping-icon-path", iconPath.get()));
        if (loaded.isBlank()) loaded = PingConstants.DEFAULT_PING_ICON_PATH;
        iconPath.set(loaded);
        return this;
    }

    @Override
    public WWidget getWidget(GuiTheme theme) {
        WVerticalList list = theme.verticalList();
        WContainer controls = list.add(theme.horizontalList()).expandX().widget();

        WButton selectSound = controls.add(theme.button("Select Sound")).expandX().widget();
        selectSound.action = () -> client().setScreen(new LocalSoundSelectScreen(theme, value -> pingSoundPath = PingFormattingUtils.normalizeLocalSoundPath(value)));

        WButton openSoundFolder = controls.add(theme.button("Open Folder")).expandX().widget();
        openSoundFolder.action = () -> Util.getOperatingSystem().open(JoinSoundPlayer.ensureSoundsDirectory().toUri().toString());

        WButton openIconFolder = controls.add(theme.button("Icons Folder")).expandX().widget();
        openIconFolder.action = () -> Util.getOperatingSystem().open(MapIconManager.ensureIconsDirectory().toUri().toString());

        WButton selectIcon = controls.add(theme.button("Select Icon")).expandX().widget();
        selectIcon.action = () -> client().setScreen(new LocalIconSelectScreen(theme, value -> iconPath.set(MapIconManager.normalizeIconPath(value))));

        String normalizedIcon = MapIconManager.normalizeIconPath(iconPath.get());
        list.add(theme.label("Current icon: " + (normalizedIcon.isBlank() ? "(built-in)" : normalizedIcon))).expandX();
        list.add(theme.label("Sync status: " + PingFormattingUtils.safe(syncController.lastSyncStatus()))).expandX();
        return list;
    }

    @EventHandler
    private void onGameJoined(GameJoinedEvent event) {
        CrashGuard.run(this, "onGameJoined", () -> {
            if (!isActive()) return;
            markerController.onGameJoined();
            syncController.onGameJoined();
        });
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        CrashGuard.run(this, "onGameLeft", () -> {
            if (!isActive()) return;
            markerController.onGameLeft();
            syncController.onGameLeft();
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST + 1)
    private void onBindKey(KeyEvent event) {
        CrashGuard.run(this, "onBindKey", () -> handleBindEvent(true, event.key, event.modifiers, event.action, event));
    }

    @EventHandler(priority = EventPriority.HIGHEST + 1)
    private void onBindMouse(MouseButtonEvent event) {
        CrashGuard.run(this, "onBindMouse", () -> handleBindEvent(false, event.button, 0, event.action, event));
    }

    private void handleBindEvent(boolean isKey, int value, int modifiers, KeyAction action, Object eventRef) {
        if (!keybind.matches(isKey, value, modifiers)) return;
        if (eventRef instanceof KeyEvent keyEvent) keyEvent.setCancelled(true);
        if (eventRef instanceof MouseButtonEvent mouseEvent) mouseEvent.setCancelled(true);
        if (action != KeyAction.Press || !isActive() || client().currentScreen != null) return;
        markerController.createPingFromCrosshair();
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        CrashGuard.run(this, "onTick", () -> {
            if (!isActive()) return;
            markerController.pruneExpiredMarkers();
            syncController.handleSyncTick();
        });
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        CrashGuard.run(this, "onRender3D", () -> {
            if (isActive()) renderController.renderMarkers3D(event);
        });
    }

    @EventHandler
    private void onRender2D(Render2DEvent event) {
        CrashGuard.run(this, "onRender2D", () -> {
            if (isActive()) renderController.renderMarkers2D(event);
        });
    }

    public MinecraftClient client() {
        return mc;
    }

    public List<MarkerJumpTarget> snapshotMarkerTargets() {
        return markerController.snapshotMarkerTargets();
    }

    public boolean xaeroMapDebug() {
        return xaeroMapDebug.get();
    }

    public InfoMode xaeroInfoMode() {
        return infoMode.get();
    }

    public boolean logoutSpotsEnabled() {
        return logoutSpots.get();
    }

    public InfoMode infoModeValue() {
        return infoMode.get();
    }

    public boolean iconEnabled() {
        return icon.get();
    }

    public String iconPathValue() {
        return iconPath.get();
    }

    public double raycastRangeValue() {
        return raycastRange.get();
    }

    public int pingVolumeValue() {
        return pingVolume.get();
    }

    public SettingColor markerColorValue() {
        return markerColor.get();
    }

    public SettingColor textColorValue() {
        return textColor.get();
    }

    public SettingColor textBackgroundColorValue() {
        return textBackgroundColor.get();
    }

    public String pingSoundPathValue() {
        return pingSoundPath;
    }

    public String currentUsername() {
        if (mc.getSession() == null || mc.getSession().getUsername() == null) return "";
        return mc.getSession().getUsername().trim();
    }

    public String currentServerKey() {
        if (mc.getCurrentServerEntry() != null && mc.getCurrentServerEntry().address != null) {
            String address = mc.getCurrentServerEntry().address.trim();
            if (!address.isEmpty()) return address;
        }
        String worldName = meteordevelopment.meteorclient.utils.Utils.getWorldName();
        return worldName == null ? "" : worldName.trim();
    }

    public String currentSyncDeviceId() {
        return syncController.currentSyncDeviceId();
    }

    public void logSyncInternal(String format, Object... args) {
        if (!syncVerbose.get()) return;
        info(format, args);
    }

    public record MarkerJumpTarget(
        String id,
        String sender,
        String server,
        String dimension,
        double x,
        double y,
        double z,
        long createdAtMs,
        String iconPath
    ) {
    }

    public enum InfoMode {
        Distance,
        Coords
    }
}




