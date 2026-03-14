package com.example.addon.modules;

import com.example.addon.AddonTemplate;
import com.example.addon.audio.JoinSoundPlayer;
import com.example.addon.gui.screens.settings.LocalIconSelectScreen;
import com.example.addon.gui.screens.settings.LocalSoundSelectScreen;
import com.example.addon.settings.SoundSourceMode;
import com.example.addon.util.CrashGuard;
import com.example.addon.util.MapIconManager;
import com.example.addon.util.SyncCrypto;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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
import meteordevelopment.meteorclient.renderer.Renderer2D;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.misc.input.KeyAction;
import meteordevelopment.meteorclient.utils.render.NametagUtils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.Util;
import org.joml.Vector3d;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class Ping extends Module {
    private static final String SYNC_PULL_PATH = "/pull";
    private static final String SYNC_PUSH_PATH = "/push";
    private static final String SYNC_STREAM_PATH = "/v1/sync/stream";
    private static final String SYNC_STREAM_PATH_LEGACY = "/stream";
    private static final long SYNC_STREAM_RECONNECT_MS = 250;
    private static final long SYNC_AUTH_BACKOFF_MS = 1_000;
    private static final long SYNC_CRYPTO_BACKOFF_MS = 1_000;
    private static final long SYNC_NETWORK_BACKOFF_MS = 250;
    private static final long SYNC_CONFIG_BACKOFF_MS = 1_000;
    private static final long SYNC_STREAM_UNSUPPORTED_BACKOFF_MS = 300_000;
    private static final long SYNC_PROBLEM_LOG_COOLDOWN_MS = 20_000;
    private static final int SYNC_ERROR_DETAIL_MAX = 120;
    private static final int MAX_SYNC_MARKERS = 256;
    private static final String DEFAULT_PING_ICON_PATH = MapIconManager.DEFAULT_EMBEDDED_ICON_PATH;
    private static final long MARKER_TTL_MS = 10_000;
    private static final long MARKER_PULSE_PERIOD_MS = 1_200;
    private static final long PULL_FALLBACK_INTERVAL_MS = 50;
    private static final int WORLD_MIN_Y = -65;
    private static final int WORLD_MAX_Y = 365;
    private static final String DEFAULT_SOUND = "minecraft:block.note_block.pling";
    private static final Identifier PING_MARKER_ICON_TEXTURE = Identifier.of("devils-addon", "textures/gui/devils_map_icon.png");
    private static final String MODULE_NAMESPACE = "ping";
    private static final String MARKER_SCHEMA = "devils-ping-marker-v1";
    private static final double STATIC_LABEL_SCALE = 1.35;
    private static final int DEVILS_MAP_ICON_SOURCE_SIZE = 1024;
    // Cropped non-transparent bounds inside the 1024x1024 source image.
    private static final int DEVILS_MAP_ICON_U = 256;
    private static final int DEVILS_MAP_ICON_V = 167;
    private static final int DEVILS_MAP_ICON_REGION_W = 525;
    private static final int DEVILS_MAP_ICON_REGION_H = 612;
    private static final long PUSH_OK_LOG_COOLDOWN_MS = 120_000;
    private static volatile Boolean xaeroWaypointRendererPresent;

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
        .defaultValue(DEFAULT_PING_ICON_PATH)
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

    private final HttpClient syncHttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final LinkedHashMap<String, PingMarker> markers = new LinkedHashMap<>();
    private final Map<String, Boolean> logoutSpotEligible = new HashMap<>();
    private String lastPlayedSoundStamp = "";
    private String pingSoundPath = "";

    private boolean syncInFlight;
    private long lastKnownSyncRevision = -1;
    private long lastSyncPullAttemptMs;
    private String lastSyncedFingerprint = "";
    private String lastSyncStatus = "idle";
    private long lastPushOkLogMs;
    private long lastConflictLogMs;
    private long syncBackoffUntilMs;
    private String lastSyncProblemSignature = "";
    private long lastSyncProblemLogMs;
    private int lastSyncProblemSuppressed;

    private CompletableFuture<Void> syncStreamFuture;
    private volatile boolean syncStreamStopRequested;
    private volatile boolean syncStreamConnecting;
    private volatile boolean syncStreamConnected;
    private volatile boolean syncStreamUpdatePending;
    private volatile long syncStreamPendingRevision = -1;
    private volatile long syncStreamReconnectAtMs;
    private volatile String syncStreamConnectionKey = "";
    private volatile boolean syncStreamUseLegacyPath;
    private volatile boolean syncStreamUnsupported;
    private volatile long syncStreamUnsupportedUntilMs;
    private volatile String runtimeSyncDeviceId = "";
    private boolean runtimePingSyncEnabled;
    private volatile boolean syncTickQueued;
    private long lastLocalMarkerCreatedAtMs;

    public Ping() {
        super(
            AddonTemplate.CATEGORY,
            "ping",
            "Sends synchronized ping markers to friends with Devils addon + sync enabled."
        );
        autoSubscribe = false;
        runInMainMenu = true;
        MeteorClient.EVENT_BUS.subscribe(this);
    }

    @Override
    public void onActivate() {
        toggleOnBindRelease = false;
        if (MapIconManager.normalizeIconPath(iconPath.get()).isBlank()) iconPath.set(DEFAULT_PING_ICON_PATH);
    }

    @Override
    public void onDeactivate() {
        stopSyncStream();
        syncTickQueued = false;
        lastLocalMarkerCreatedAtMs = 0L;
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
        pingSoundPath = normalizeLocalSoundPath(tag.getString("ping-sound-path", ""));
        String loaded = MapIconManager.normalizeIconPath(tag.getString("ping-icon-path", iconPath.get()));
        if (loaded.isBlank()) loaded = DEFAULT_PING_ICON_PATH;
        iconPath.set(loaded);
        return this;
    }

    @Override
    public WWidget getWidget(GuiTheme theme) {
        WVerticalList list = theme.verticalList();

        WContainer controls = list.add(theme.horizontalList()).expandX().widget();

        WButton selectSound = controls.add(theme.button("Select Sound")).expandX().widget();
        selectSound.action = () -> mc.setScreen(new LocalSoundSelectScreen(theme, value -> pingSoundPath = normalizeLocalSoundPath(value)));

        WButton openSoundFolder = controls.add(theme.button("Open Folder")).expandX().widget();
        openSoundFolder.action = () -> Util.getOperatingSystem().open(JoinSoundPlayer.ensureSoundsDirectory().toUri().toString());

        WButton openIconFolder = controls.add(theme.button("Icons Folder")).expandX().widget();
        openIconFolder.action = () -> Util.getOperatingSystem().open(MapIconManager.ensureIconsDirectory().toUri().toString());

        WButton selectIcon = controls.add(theme.button("Select Icon")).expandX().widget();
        selectIcon.action = () -> mc.setScreen(new LocalIconSelectScreen(theme, value -> iconPath.set(MapIconManager.normalizeIconPath(value))));

        String normalizedIcon = MapIconManager.normalizeIconPath(iconPath.get());
        list.add(theme.label("Current icon: " + (normalizedIcon.isBlank() ? "(built-in)" : normalizedIcon))).expandX();
        list.add(theme.label("Sync status: " + safe(lastSyncStatus))).expandX();

        return list;
    }

    @EventHandler
    private void onGameJoined(GameJoinedEvent event) {
        CrashGuard.run(this, "onGameJoined", () -> {
            if (!isActive()) return;
            lastSyncPullAttemptMs = 0;
            syncStreamUnsupported = false;
            syncStreamUnsupportedUntilMs = 0;
            syncTickQueued = false;
            lastLocalMarkerCreatedAtMs = 0L;
        });
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        CrashGuard.run(this, "onGameLeft", () -> {
            if (!isActive()) return;
            stopSyncStream();
            lastSyncPullAttemptMs = 0;
            syncTickQueued = false;
            lastLocalMarkerCreatedAtMs = 0L;
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

        if (action != KeyAction.Press) return;
        if (!isActive()) return;
        if (mc.currentScreen != null) return;
        createPingFromCrosshair();
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        CrashGuard.run(this, "onTick", () -> {
            if (!isActive()) return;
            pruneExpiredMarkers();
            handleSyncTick();
        });
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        CrashGuard.run(this, "onRender3D", () -> {
            if (!isActive()) return;
            renderMarkers3D(event);
        });
    }

    @EventHandler
    private void onRender2D(Render2DEvent event) {
        CrashGuard.run(this, "onRender2D", () -> {
            if (!isActive()) return;
            renderMarkers2D(event);
        });
    }

    private void createPingFromCrosshair() {
        if (mc.player == null || mc.world == null || mc.cameraEntity == null) return;
        if (mc.currentScreen != null) return;

        boolean detachedCamera = mc.cameraEntity != mc.player;
        Vec3d eye = detachedCamera ? mc.cameraEntity.getCameraPosVec(1.0f) : mc.player.getEyePos();
        Vec3d rotation = detachedCamera ? mc.cameraEntity.getRotationVec(1.0f) : mc.player.getRotationVec(1.0f);
        double maxRange = Math.max(8, raycastRange.get());
        Vec3d fallback = eye.add(rotation.multiply(maxRange));

        HitResult hit = detachedCamera
            ? mc.cameraEntity.raycast(maxRange, 1.0f, false)
            : mc.player.raycast(maxRange, 1.0f, false);

        Vec3d raw = fallback;
        if (hit != null && hit.getPos() != null) {
            if (hit.getType() != HitResult.Type.MISS) {
                if (hit instanceof BlockHitResult blockHitResult) raw = Vec3d.ofCenter(blockHitResult.getBlockPos());
                else raw = hit.getPos();
            } else {
                double missDistance = hit.getPos().distanceTo(eye);
                raw = missDistance >= (maxRange * 0.95) ? hit.getPos() : fallback;
            }
        }

        double clampedY = clampY(raw.y);
        String sender = currentUsername();
        String server = currentServerKey();
        String deviceId = currentSyncDeviceId();
        String id = buildMarkerId(sender, server);
        String markerIconPath = MapIconManager.normalizeIconPath(iconPath.get());
        if (markerIconPath.isBlank()) markerIconPath = DEFAULT_PING_ICON_PATH;

        long now = System.currentTimeMillis();
        long createdAt = now <= lastLocalMarkerCreatedAtMs ? (lastLocalMarkerCreatedAtMs + 1L) : now;
        lastLocalMarkerCreatedAtMs = createdAt;

        PingMarker marker = new PingMarker(
            id,
            sender,
            deviceId,
            server,
            mc.world == null ? "minecraft:overworld" : mc.world.getRegistryKey().getValue().toString(),
            raw.x,
            clampedY,
            raw.z,
            createdAt,
            icon.get(),
            markerIconPath
        );

        applyMarker(marker);
        trimMarkerCapacity();
        playLocalPingSound();
    }

    private void renderMarkers3D(Render3DEvent event) {
        if (mc.player == null || mc.world == null) return;

        Color baseMarker = markerColor.get();
        long now = System.currentTimeMillis();

        for (PingMarker markerData : collectVisibleMarkers()) {
            if (!isMarkerAlive(markerData, now)) continue;

            Color markerLines = pulseColor(baseMarker, markerData, now, 0.62, 1.0);
            Color markerFill = new Color(markerLines.r, markerLines.g, markerLines.b, Math.max(20, markerLines.a / 3));
            double columnHalf = 0.12;
            event.renderer.box(
                markerData.x() - columnHalf,
                WORLD_MIN_Y,
                markerData.z() - columnHalf,
                markerData.x() + columnHalf,
                WORLD_MAX_Y,
                markerData.z() + columnHalf,
                markerFill,
                markerLines,
                ShapeMode.Both,
                0
            );

            double half = 0.22;
            event.renderer.box(
                markerData.x() - half,
                markerData.y() - 0.05,
                markerData.z() - half,
                markerData.x() + half,
                markerData.y() + 0.05,
                markerData.z() + half,
                markerFill,
                markerLines,
                ShapeMode.Both,
                0
            );
        }
    }

    private void renderMarkers2D(Render2DEvent event) {
        if (mc.player == null || mc.world == null) return;
        if (mc.currentScreen != null) return;
        if (shouldUseXaeroWaypointLabelsOnly()) return;

        Vector3d pos = new Vector3d();
        long now = System.currentTimeMillis();

        for (PingMarker marker : collectVisibleMarkers()) {
            if (!isMarkerAlive(marker, now)) continue;
            pos.set(marker.x(), marker.y() + 0.35, marker.z());
            if (!NametagUtils.to2D(pos, labelScale(marker), false)) continue;

            String label = buildInfoText(marker);
            boolean showIcon = icon.get() && marker.icon();
            if (label.isBlank() && !showIcon) continue;

            NametagUtils.begin(pos, event.drawContext);
            int iconSize = Math.max(8, mc.textRenderer.fontHeight);
            double iconWidth = showIcon ? iconSize : 0;
            double textWidth = label.isBlank() ? 0 : mc.textRenderer.getWidth(label);
            double spacing = showIcon && !label.isBlank() ? 2 : 0;
            double width = iconWidth + spacing + textWidth;
            double height = mc.textRenderer.fontHeight;
            double x = -width / 2;
            double y = -height - 1.5;

            Renderer2D.COLOR.begin();
            Renderer2D.COLOR.quad(
                x - 2,
                y - 1,
                width + 4,
                height + 2,
                textBackgroundColor.get()
            );
            Renderer2D.COLOR.render();

            double cursorX = x;
            if (showIcon) {
                int iconX = (int) Math.round(cursorX);
                int iconY = (int) Math.round(y - 1);
                boolean drawnCustom = MapIconManager.drawCustomIcon(event.drawContext, marker.iconPath(), iconX, iconY, iconSize, 0xFFFFFFFF);
                if (!drawnCustom) {
                    event.drawContext.drawTexture(
                        RenderPipelines.GUI_TEXTURED,
                        PING_MARKER_ICON_TEXTURE,
                        iconX,
                        iconY,
                        DEVILS_MAP_ICON_U,
                        DEVILS_MAP_ICON_V,
                        iconSize,
                        iconSize,
                        DEVILS_MAP_ICON_REGION_W,
                        DEVILS_MAP_ICON_REGION_H,
                        DEVILS_MAP_ICON_SOURCE_SIZE,
                        DEVILS_MAP_ICON_SOURCE_SIZE,
                        0xFFFFFFFF
                    );
                }
                cursorX += iconWidth + spacing;
            }

            if (!label.isBlank()) {
                event.drawContext.drawText(
                    mc.textRenderer,
                    label,
                    (int) Math.round(cursorX),
                    (int) Math.round(y),
                    toArgb(labelColor(marker, now)),
                    false
                );
            }
            NametagUtils.end(event.drawContext);
        }

    }

    private String buildInfoText(PingMarker marker) {
        String sender = normalizeSender(marker.sender());
        String pingPrefix = "[PING] " + sender;
        String coords = formatCoords(marker.x(), marker.y(), marker.z());

        return switch (infoMode.get()) {
            case Distance -> pingPrefix;
            case Coords -> pingPrefix + " | " + coords;
        };
    }

    private static String normalizeSender(String raw) {
        String value = safe(raw).trim();
        if (value.isBlank()) return "Unknown";
        if (value.contains("|")) {
            String[] parts = value.split("\\|");
            value = safe(parts[0]).trim();
        } else if (value.contains("/") || value.contains("\\")) {
            String[] parts = value.replace('\\', '/').split("/");
            if (parts.length >= 3 && safe(parts[0]).toLowerCase(Locale.ROOT).contains("[ping]")) {
                value = safe(parts[1]).trim();
            } else {
                value = safe(parts[parts.length - 1]).trim();
            }
        }
        if (value.regionMatches(true, 0, "[PING]", 0, 6)) {
            value = safe(value.substring(6)).trim();
        }
        int lastSpace = value.lastIndexOf(' ');
        if (lastSpace > 0) {
            String tail = safe(value.substring(lastSpace + 1)).trim().toLowerCase(Locale.ROOT);
            if (tail.equals("~") || tail.equals("--") || tail.matches("\\d+(?:\\.\\d+)?(?:m|k)?")) {
                value = safe(value.substring(0, lastSpace)).trim();
            }
        }
        return value.isBlank() ? "Unknown" : value;
    }

    private List<PingMarker> collectVisibleMarkers() {
        if (markers.isEmpty()) return List.of();

        String serverKey = normalizeServerKey(currentServerKey());

        ArrayList<PingMarker> visible = new ArrayList<>();
        for (PingMarker marker : markers.values()) {
            if (marker == null) continue;

            if (!isMarkerAudienceAllowed(marker, serverKey)) continue;
            visible.add(marker);
        }

        visible.sort(Comparator.comparingLong(PingMarker::createdAtMs).reversed());
        return visible;
    }

    public List<MarkerJumpTarget> snapshotMarkerTargets() {
        if (markers.isEmpty()) return List.of();

        String serverKey = normalizeServerKey(currentServerKey());
        ArrayList<MarkerJumpTarget> targets = new ArrayList<>();
        for (PingMarker marker : markers.values()) {
            if (marker == null) continue;
            if (!isMarkerAudienceAllowed(marker, serverKey)) continue;
            targets.add(new MarkerJumpTarget(marker.id(), marker.sender(), marker.server(), marker.dimension(), marker.x(), marker.y(), marker.z(), marker.createdAtMs(), marker.iconPath()));
        }

        targets.sort(Comparator.comparingLong(MarkerJumpTarget::createdAtMs).reversed());
        return targets;
    }

    public boolean xaeroMapDebug() {
        return xaeroMapDebug.get();
    }

    public InfoMode xaeroInfoMode() {
        return infoMode.get();
    }

    private boolean shouldUseXaeroWaypointLabelsOnly() {
        if (XaeroSync.isWaypointIntegrationRunning()) return true;
        return isXaeroWaypointRendererPresent();
    }

    private boolean isXaeroWaypointRendererPresent() {
        Boolean cached = xaeroWaypointRendererPresent;
        if (cached != null) return cached;

        boolean present;
        try {
            Class.forName("xaero.map.mods.gui.WaypointRenderer", false, Ping.class.getClassLoader());
            present = true;
        } catch (Throwable ignored) {
            present = false;
        }
        xaeroWaypointRendererPresent = present;
        return present;
    }

    private boolean isMarkerAudienceAllowed(PingMarker marker, String normalizedServerKey) {
        if (marker == null) return false;
        String markerServer = normalizeServerKey(marker.server());
        if (!normalizedServerKey.isBlank() && !markerServer.isBlank() && !markerServer.equals(normalizedServerKey)) return false;

        String sender = normalizeKey(marker.sender());
        return !sender.isBlank();
    }

    private boolean isPlayerInLoadedWorld(String playerName) {
        return findLoadedPlayer(playerName) != null;
    }

    private boolean isPlayerInView(String playerName) {
        PlayerEntity player = findLoadedPlayer(playerName);
        if (player == null) return false;
        if (mc.cameraEntity == null) return false;

        Vec3d cameraPos = mc.cameraEntity.getCameraPosVec(1.0f);
        Vec3d look = mc.cameraEntity.getRotationVec(1.0f);
        Vec3d toTarget = player.getBoundingBox().getCenter().subtract(cameraPos);

        double distance = toTarget.length();
        if (distance < 0.001) return true;

        Vec3d dir = toTarget.multiply(1.0 / distance);
        double dot = look.dotProduct(dir);

        double fov = 90.0;
        try {
            fov = mc.options.getFov().getValue();
        } catch (Throwable ignored) {
        }
        double threshold = Math.cos(Math.toRadians(Math.max(30.0, Math.min(170.0, fov)) / 2.0));
        if (dot < threshold) return false;
        return mc.player == null || mc.player.canSee(player);
    }

    private PlayerEntity findLoadedPlayer(String playerName) {
        String normalized = normalizeKey(playerName);
        if (normalized.isBlank() || mc.world == null) return null;

        for (PlayerEntity player : mc.world.getPlayers()) {
            if (player == null || player.getGameProfile() == null) continue;
            if (normalized.equals(normalizeKey(player.getGameProfile().getName()))) return player;
        }
        return null;
    }

    private void pruneExpiredMarkers() {
        if (markers.isEmpty()) return;

        long now = System.currentTimeMillis();

        boolean changed = false;
        Iterator<Map.Entry<String, PingMarker>> it = markers.entrySet().iterator();
        while (it.hasNext()) {
            PingMarker marker = it.next().getValue();
            if (now - marker.createdAtMs() <= MARKER_TTL_MS) continue;
            it.remove();
            logoutSpotEligible.remove(marker.id());
            changed = true;
        }

        if (changed) trimMarkerCapacity();
    }

    private void trimMarkerCapacity() {
        int max = Math.max(1, MAX_SYNC_MARKERS);
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
        String canonicalId = buildMarkerId(marker.sender(), marker.server());
        double canonicalX = marker.x();
        double canonicalY = clampY(marker.y());
        double canonicalZ = marker.z();
        if (canonicalId.equals(marker.id())
            && Double.compare(canonicalX, marker.x()) == 0
            && Double.compare(canonicalY, marker.y()) == 0
            && Double.compare(canonicalZ, marker.z()) == 0) {
            return marker;
        }
        return new PingMarker(
            canonicalId,
            marker.sender(),
            marker.senderDevice(),
            marker.server(),
            marker.dimension(),
            canonicalX,
            canonicalY,
            canonicalZ,
            marker.createdAtMs(),
            marker.icon(),
            marker.iconPath()
        );
    }

    private boolean isMarkerAlive(PingMarker marker, long nowMs) {
        long age = nowMs - marker.createdAtMs();
        return age >= 0 && age <= MARKER_TTL_MS;
    }

    private Color pulseColor(Color base, PingMarker marker, long nowMs, double minMul, double maxMul) {
        double phase = pulsePhase(marker, nowMs);
        double factor = minMul + (maxMul - minMul) * phase;
        return new Color(
            scaleColorChannel(base.r, factor),
            scaleColorChannel(base.g, factor),
            scaleColorChannel(base.b, factor),
            base.a
        );
    }

    private Color labelColor(PingMarker marker, long nowMs) {
        return pulseColor(textColor.get(), marker, nowMs, 0.72, 1.0);
    }

    private double labelScale(PingMarker marker) {
        return STATIC_LABEL_SCALE;
    }

    private double pulsePhase(PingMarker marker, long nowMs) {
        long age = Math.max(0, nowMs - marker.createdAtMs());
        double radians = (age % MARKER_PULSE_PERIOD_MS) * (Math.PI * 2.0 / MARKER_PULSE_PERIOD_MS);
        return (Math.sin(radians) + 1.0) * 0.5;
    }

    private int scaleColorChannel(int channel, double factor) {
        int value = (int) Math.round(channel * factor);
        if (value < 0) return 0;
        return Math.min(value, 255);
    }

    private int toArgb(Color color) {
        int a = color.a & 0xFF;
        int r = color.r & 0xFF;
        int g = color.g & 0xFF;
        int b = color.b & 0xFF;
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private void handleSyncTick() {
        SyncRuntimeConfig sync = resolveSyncRuntimeConfig();
        if (sync == null) {
            runtimePingSyncEnabled = false;
            runtimeSyncDeviceId = "";
            stopSyncStream();
            return;
        }
        if (syncInFlight) {
            syncTickQueued = true;
            return;
        }

        runtimePingSyncEnabled = true;
        runtimeSyncDeviceId = sync.deviceId();

        String baseUrl = normalizeSyncBaseUrl(sync.baseUrl());
        if (baseUrl.isBlank()) {
            lastSyncStatus = "skip:no-base-url";
            stopSyncStream();
            return;
        }

        String baseUrlValidationError = validateSyncBaseUrl(baseUrl);
        if (baseUrlValidationError != null) {
            lastSyncStatus = "skip:bad-base-url";
            logSyncProblem("invalid base-url", baseUrlValidationError);
            stopSyncStream();
            return;
        }

        if (!sync.allowHttp() && baseUrl.startsWith("http://")) {
            lastSyncStatus = "skip:http-disabled";
            stopSyncStream();
            return;
        }

        if (mc.player == null || mc.world == null) {
            stopSyncStream();
            return;
        }

        if (sync.useStream()) ensureSyncStream(baseUrl, sync.deviceId(), sync.token(), sync.timeoutSec(), sync.streamWaitMs());
        else stopSyncStream();

        if (syncBackoffUntilMs > System.currentTimeMillis()) return;

        long now = System.currentTimeMillis();
        List<SyncPingData> localSnapshot = snapshotSyncData();
        String localFingerprint = computeFingerprint(localSnapshot);
        boolean localChanged = !localFingerprint.equals(lastSyncedFingerprint);
        boolean streamTriggeredPull = consumePendingStreamPullSignal();

        boolean shouldBootstrapPull = lastKnownSyncRevision < 0;
        // Hard sync mode: always poll quickly in addition to stream signals.
        boolean periodicPull = (now - lastSyncPullAttemptMs) >= PULL_FALLBACK_INTERVAL_MS;
        boolean shouldPull = streamTriggeredPull || shouldBootstrapPull || periodicPull;
        boolean shouldRun = streamTriggeredPull || localChanged || shouldBootstrapPull || periodicPull;
        if (!shouldRun) return;

        lastSyncPullAttemptMs = now;
        syncInFlight = true;
        int pullWaitMs = sync.useStream() ? 0 : sync.streamWaitMs();
        runSyncCycleAsync(
            baseUrl,
            sync.deviceId(),
            sync.token(),
            sync.timeoutSec(),
            pullWaitMs,
            sync.encryptionKey(),
            lastKnownSyncRevision,
            localSnapshot,
            localFingerprint,
            localChanged,
            shouldPull
        );
    }

    private void runSyncCycleAsync(
        String baseUrl,
        String deviceId,
        String token,
        int timeoutSec,
        int pullWaitMs,
        String encryptionKey,
        long knownRevision,
        List<SyncPingData> localSnapshot,
        String localFingerprint,
        boolean localChanged,
        boolean doPull
    ) {
        CompletableFuture.runAsync(() -> {
            SyncPullResult pullResult = null;
            SyncPushResult pushResult = null;
            boolean remoteApplied = false;
            String error = null;

            List<SyncPingData> effectiveSnapshot = localSnapshot;
            String effectiveFingerprint = localFingerprint;
            long pushBaseRevision = knownRevision;
            boolean pushRequestedByMerge = false;
            boolean localNeedsPush = localChanged;

            if (doPull) {
                try {
                    pullResult = sendPullRequest(baseUrl, deviceId, token, timeoutSec, encryptionKey, knownRevision, pullWaitMs);
                    if (!pullResult.ok()) {
                        error = "pull-rejected:" + pullResult.error();
                    } else {
                        if (pullResult.revision() >= 0) pushBaseRevision = pullResult.revision();

                        List<SyncPingData> remoteSnapshot = pullResult.profiles() == null ? List.of() : pullResult.profiles();
                        String remoteFingerprint = computeFingerprint(remoteSnapshot);
                        boolean remoteIsNewer = pullResult.revision() > knownRevision && pullResult.profiles() != null;

                        if (localChanged) {
                            List<SyncPingData> merged = mergeSnapshots(remoteSnapshot, localSnapshot);
                            String mergedFingerprint = computeFingerprint(merged);
                            if (!mergedFingerprint.equals(remoteFingerprint)) {
                                effectiveSnapshot = merged;
                                effectiveFingerprint = mergedFingerprint;
                                pushRequestedByMerge = true;
                                localNeedsPush = true;
                            } else {
                                effectiveSnapshot = remoteSnapshot;
                                effectiveFingerprint = remoteFingerprint;
                                localNeedsPush = false;
                                if (remoteIsNewer) {
                                    remoteApplied = true;
                                }
                            }
                        } else if (remoteIsNewer) {
                            effectiveSnapshot = remoteSnapshot;
                            effectiveFingerprint = remoteFingerprint;
                            remoteApplied = true;
                        }
                    }
                } catch (Throwable t) {
                    error = formatSyncException("pull-error", t);
                }
            }

            if (error == null && !remoteApplied && (localNeedsPush || pushRequestedByMerge)) {
                try {
                    pushResult = sendPushRequest(baseUrl, deviceId, token, timeoutSec, encryptionKey, pushBaseRevision, effectiveSnapshot);

                    if (pushResult.ok() && pushResult.conflict() && pushResult.profiles() != null && pushResult.revision() >= 0) {
                        List<SyncPingData> conflictMerged = mergeSnapshots(pushResult.profiles(), localSnapshot);
                        String conflictFingerprint = computeFingerprint(conflictMerged);
                        pushResult = sendPushRequest(baseUrl, deviceId, token, timeoutSec, encryptionKey, pushResult.revision(), conflictMerged);
                        effectiveSnapshot = conflictMerged;
                        effectiveFingerprint = conflictFingerprint;
                    }
                } catch (Throwable t) {
                    error = formatSyncException("push-error", t);
                }
            }

            SyncCycleResult result = new SyncCycleResult(
                pullResult,
                pushResult,
                remoteApplied,
                localNeedsPush,
                effectiveSnapshot,
                effectiveFingerprint,
                error
            );
            mc.execute(() -> handleSyncCycleResult(result));
        });
    }

    private void handleSyncCycleResult(SyncCycleResult result) {
        syncInFlight = false;
        try {
            if (result.error() != null) {
                lastSyncStatus = result.error();
                logSyncProblem("failed", result.error());
                return;
            }

            if (result.pullResult() != null && result.pullResult().ok() && result.pullResult().revision() >= 0) {
                lastKnownSyncRevision = Math.max(lastKnownSyncRevision, result.pullResult().revision());
            }

            if (result.remoteApplied()) {
                lastSyncStatus = "pull-applied";
                applySyncedSnapshot(result.snapshot());
                lastSyncedFingerprint = result.snapshotFingerprint();
                clearSyncProblemTracking();
                return;
            }

            if (result.pushResult() != null) {
                if (result.pushResult().ok() && result.pushResult().applied()) {
                    if (result.pushResult().revision() >= 0) {
                        lastKnownSyncRevision = Math.max(lastKnownSyncRevision, result.pushResult().revision());
                    }
                    lastSyncStatus = "push-ok";
                    long now = System.currentTimeMillis();
                    if (now - lastPushOkLogMs >= PUSH_OK_LOG_COOLDOWN_MS) {
                        logSync("Ping sync push ok (rev=%d).", lastKnownSyncRevision);
                        lastPushOkLogMs = now;
                    }
                    applySyncedSnapshot(result.snapshot());
                    lastSyncedFingerprint = result.snapshotFingerprint();
                    clearSyncProblemTracking();
                    return;
                }

                if (result.pushResult().ok() && result.pushResult().conflict()) {
                    lastSyncStatus = "push-conflict";
                    long now = System.currentTimeMillis();
                    if (now - lastConflictLogMs >= PUSH_OK_LOG_COOLDOWN_MS) {
                        logSync("Ping sync conflict handled by merge.");
                        lastConflictLogMs = now;
                    }
                    applySyncedSnapshot(result.snapshot());
                    lastSyncedFingerprint = result.snapshotFingerprint();
                    clearSyncProblemTracking();
                    return;
                }

                if (result.pushResult().ok()) {
                    lastSyncStatus = "push-rejected:" + result.pushResult().error();
                    logSyncProblem("push rejected", result.pushResult().error());
                    return;
                }
            }

            if (!result.localChanged()) {
                lastSyncedFingerprint = result.snapshotFingerprint();
                lastSyncStatus = "noop";
                clearSyncProblemTracking();
                return;
            }

            lastSyncStatus = "local-change-pending";
        } finally {
            if (syncTickQueued) {
                syncTickQueued = false;
                handleSyncTick();
            }
        }
    }

    private void applySyncedSnapshot(List<SyncPingData> snapshot) {
        String selfName = normalizeKey(currentUsername());
        String serverKey = normalizeServerKey(currentServerKey());

        if (runtimePingSyncEnabled) {
            LinkedHashMap<String, PingMarker> oldMarkers = new LinkedHashMap<>(markers);
            LinkedHashMap<String, PingMarker> merged = decodeToMarkerMap(snapshot);
            String selfDevice = normalizeKey(currentSyncDeviceId());

            // Never roll back a fresh local ping to an older pulled snapshot.
            for (PingMarker local : oldMarkers.values()) {
                if (local == null) continue;
                boolean localOwned = (!selfDevice.isBlank() && selfDevice.equals(normalizeKey(local.senderDevice())))
                    || normalizeKey(local.sender()).equals(selfName);
                if (!localOwned) continue;

                PingMarker remote = merged.get(local.id());
                if (remote == null || local.createdAtMs() > remote.createdAtMs()) {
                    merged.put(local.id(), local);
                }
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
                JoinSoundPlayer.play(
                    SoundSourceMode.LocalFolder,
                    pingSoundPath,
                    DEFAULT_SOUND,
                    pingVolume.get()
                );
            }
        }
    }

    private List<SyncPingData> mergeSnapshots(List<SyncPingData> remoteSnapshot, List<SyncPingData> localSnapshot) {
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
        if (sortedMarkers.size() > MAX_SYNC_MARKERS) sortedMarkers = sortedMarkers.subList(0, MAX_SYNC_MARKERS);

        ArrayList<SyncPingData> merged = new ArrayList<>(sortedMarkers.size());
        for (PingMarker marker : sortedMarkers) merged.add(encodeMarker(marker));
        return merged;
    }

    private LinkedHashMap<String, PingMarker> decodeToMarkerMap(List<SyncPingData> list) {
        LinkedHashMap<String, PingMarker> map = new LinkedHashMap<>();
        if (list == null) return map;

        for (SyncPingData data : list) {
            PingMarker marker = decodeMarker(data);
            if (marker == null) continue;

            PingMarker existing = map.get(marker.id());
            if (existing == null || marker.createdAtMs() >= existing.createdAtMs()) {
                map.put(marker.id(), marker);
            }
        }

        return map;
    }

    private List<SyncPingData> snapshotSyncData() {
        ArrayList<SyncPingData> snapshot = new ArrayList<>();

        if (runtimePingSyncEnabled) {
            String selfName = normalizeKey(currentUsername());
            String selfDevice = normalizeKey(currentSyncDeviceId());
            ArrayList<PingMarker> ordered = new ArrayList<>(markers.values());
            ordered.sort(Comparator.comparingLong(PingMarker::createdAtMs).reversed());
            if (ordered.size() > MAX_SYNC_MARKERS) ordered = new ArrayList<>(ordered.subList(0, MAX_SYNC_MARKERS));
            for (PingMarker marker : ordered) {
                if (marker == null) continue;
                boolean localOwned = (!selfDevice.isBlank() && selfDevice.equals(normalizeKey(marker.senderDevice())))
                    || (!selfName.isBlank() && selfName.equals(normalizeKey(marker.sender())));
                if (!localOwned) continue;
                snapshot.add(encodeMarker(marker));
            }
        }

        return snapshot;
    }

    private SyncPingData encodeMarker(PingMarker marker) {
        JsonObject payload = new JsonObject();
        payload.addProperty("schema", MARKER_SCHEMA);
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
        if (data == null) return null;
        if (!data.enabled()) return null;

        String sender = safe(data.username());
        String server = safe(data.server());
        String payload = safe(data.payload());
        if (sender.isBlank() || server.isBlank() || payload.isBlank()) return null;

        JsonObject json;
        try {
            if (!JsonParser.parseString(payload).isJsonObject()) return null;
            json = JsonParser.parseString(payload).getAsJsonObject();
        } catch (Throwable ignored) {
            return null;
        }

        String schema = readString(json, "schema", "");
        if (!schema.isBlank() && !MARKER_SCHEMA.equals(schema)) return null;

        String senderDevice = readString(json, "senderDevice", "");
        String dimension = readString(json, "dim", mc.world == null ? "minecraft:overworld" : mc.world.getRegistryKey().getValue().toString());
        double x = readDouble(json, "x", Double.NaN);
        double y = clampY(readDouble(json, "y", Double.NaN));
        double z = readDouble(json, "z", Double.NaN);
        long createdAt = readLong(json, "createdAt", 0);
        boolean iconValue = readBoolean(json, "icon", true);
        String iconPathValue = MapIconManager.normalizeIconPath(readString(json, "iconPath", readString(json, "icon-path", "")));

        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) return null;
        if (createdAt <= 0) createdAt = System.currentTimeMillis();
        String id = buildMarkerId(sender, server);

        return new PingMarker(id, sender, senderDevice, server, dimension, x, y, z, createdAt, iconValue, iconPathValue);
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

            String sender = normalizeKey(marker.sender());
            if (sender.isBlank() || sender.equals(selfName)) continue;

            PingMarker before = previous == null ? null : previous.get(marker.id());
            if (before != null && before.createdAtMs() >= marker.createdAtMs()) continue;

            String soundStamp = markerSoundStamp(marker);
            if (soundStamp.equals(lastPlayedSoundStamp)) continue;
            lastPlayedSoundStamp = soundStamp;
            return true;
        }

        return false;
    }

    private String markerSoundStamp(PingMarker marker) {
        return marker.id() + ":" + marker.createdAtMs();
    }

    private void playLocalPingSound() {
        JoinSoundPlayer.play(
            SoundSourceMode.LocalFolder,
            pingSoundPath,
            DEFAULT_SOUND,
            pingVolume.get()
        );
    }

    private SyncPullResult sendPullRequest(String baseUrl, String deviceId, String token, int timeoutSec, String encryptionKey, long knownRevision, int waitMs) throws Exception {
        JsonObject payload = new JsonObject();
        payload.addProperty("deviceId", deviceId);
        payload.addProperty("knownRevision", knownRevision);
        if (waitMs > 0) payload.addProperty("waitMs", waitMs);
        payload.addProperty("module", MODULE_NAMESPACE);

        HttpRequest request = buildSyncRequest(baseUrl, SYNC_PULL_PATH, payload.toString(), token, timeoutSec);
        HttpResponse<String> response = syncHttpClient.send(request, HttpResponse.BodyHandlers.ofString());
        return parsePullResponse(response, encryptionKey);
    }

    private SyncPushResult sendPushRequest(
        String baseUrl,
        String deviceId,
        String token,
        int timeoutSec,
        String encryptionKey,
        long baseRevision,
        List<SyncPingData> snapshot
    ) throws Exception {
        JsonObject payload = new JsonObject();
        payload.addProperty("deviceId", deviceId);
        payload.addProperty("baseRevision", baseRevision);
        payload.addProperty("module", MODULE_NAMESPACE);
        payload.add("profiles", SyncCrypto.encryptProfiles(toJsonArray(snapshot), encryptionKey, MODULE_NAMESPACE));

        HttpRequest request = buildSyncRequest(baseUrl, SYNC_PUSH_PATH, payload.toString(), token, timeoutSec);
        HttpResponse<String> response = syncHttpClient.send(request, HttpResponse.BodyHandlers.ofString());
        return parsePushResponse(response, encryptionKey);
    }

    private HttpRequest buildSyncRequest(String baseUrl, String path, String body, String token, int timeoutSec) {
        URI uri = URI.create(baseUrl + path);
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(Math.max(3, timeoutSec)))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .header("User-Agent", "Devils-PingSync/1.0");
        if (token != null && !token.isBlank()) builder.header("Authorization", "Bearer " + token.trim());
        return builder.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
    }

    private SyncPullResult parsePullResponse(HttpResponse<String> response, String encryptionKey) {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            return new SyncPullResult(false, -1, null, parseHttpError(response), "");
        }
        if (response.body() == null || response.body().isBlank()) {
            return new SyncPullResult(true, -1, null, "", "");
        }

        JsonObject json = parseJsonObject(response.body());
        if (json == null) return new SyncPullResult(false, -1, null, "bad-json", "");

        boolean ok = readBoolean(json, "ok", true);
        long revision = readLong(json, "revision", readLong(json, "rev", -1));
        List<SyncPingData> profiles;
        try {
            profiles = readProfiles(json, encryptionKey);
        } catch (Exception decryptError) {
            return new SyncPullResult(false, revision, null, "decrypt:" + decryptError.getClass().getSimpleName(), "");
        }
        String error = readString(json, "error", "");
        String lastWriter = readString(json, "lastWriter", readString(json, "last_writer", ""));
        return new SyncPullResult(ok, revision, profiles, error, lastWriter);
    }

    private SyncPushResult parsePushResponse(HttpResponse<String> response, String encryptionKey) {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            return new SyncPushResult(false, false, false, -1, null, parseHttpError(response), "");
        }
        if (response.body() == null || response.body().isBlank()) {
            return new SyncPushResult(true, true, false, -1, null, "", "");
        }

        JsonObject json = parseJsonObject(response.body());
        if (json == null) return new SyncPushResult(false, false, false, -1, null, "bad-json", "");

        boolean ok = readBoolean(json, "ok", true);
        boolean applied = readBoolean(json, "applied", ok);
        boolean conflict = readBoolean(json, "conflict", false);
        long revision = readLong(json, "revision", -1);
        List<SyncPingData> profiles;
        try {
            profiles = readProfiles(json, encryptionKey);
        } catch (Exception decryptError) {
            return new SyncPushResult(false, false, conflict, revision, null, "decrypt:" + decryptError.getClass().getSimpleName(), "");
        }
        String error = readString(json, "error", "");
        String lastWriter = readString(json, "lastWriter", readString(json, "last_writer", ""));
        return new SyncPushResult(ok, applied, conflict, revision, profiles, error, lastWriter);
    }

    private List<SyncPingData> readProfiles(JsonObject json, String encryptionKey) throws Exception {
        JsonArray array = readArray(json, "profiles");
        if (array == null) array = readArray(json, "data");
        if (array == null) return List.of();
        array = SyncCrypto.decryptProfiles(array, encryptionKey, MODULE_NAMESPACE);

        ArrayList<SyncPingData> list = new ArrayList<>();
        for (int i = 0; i < array.size(); i++) {
            if (!array.get(i).isJsonObject()) continue;
            JsonObject profile = array.get(i).getAsJsonObject();
            list.add(new SyncPingData(
                readBoolean(profile, "enabled", true),
                readString(profile, "username", ""),
                readString(profile, "server", ""),
                readString(profile, "password", ""),
                readInt(profile, "delay", 0)
            ));
        }
        return list;
    }

    private JsonArray toJsonArray(List<SyncPingData> data) {
        JsonArray array = new JsonArray();
        if (data == null) return array;

        for (SyncPingData row : data) {
            JsonObject profile = new JsonObject();
            profile.addProperty("enabled", row.enabled());
            profile.addProperty("username", row.username());
            profile.addProperty("server", row.server());
            profile.addProperty("mode", "LOGIN");
            profile.addProperty("password", row.payload());
            profile.addProperty("delay", row.delay());
            array.add(profile);
        }

        return array;
    }

    private void ensureSyncStream(String baseUrl, String deviceId, String token, int timeoutSec, int waitMs) {
        long now = System.currentTimeMillis();
        if (syncStreamUnsupported && syncStreamUnsupportedUntilMs > now) return;
        if (syncStreamUnsupported && syncStreamUnsupportedUntilMs <= now) {
            syncStreamUnsupported = false;
            syncStreamUnsupportedUntilMs = 0;
        }

        String tokenValue = token == null ? "" : token.trim();
        String connectionKey = baseUrl
            + "|"
            + deviceId
            + "|"
            + timeoutSec
            + "|"
            + waitMs
            + "|"
            + (syncStreamUseLegacyPath ? "legacy" : "v1")
            + "|"
            + Integer.toHexString(tokenValue.hashCode());
        if ((syncStreamConnected || syncStreamConnecting) && !connectionKey.equals(syncStreamConnectionKey)) stopSyncStream();
        if (syncStreamConnected || syncStreamConnecting) return;
        if (syncStreamReconnectAtMs > System.currentTimeMillis()) return;

        syncStreamStopRequested = false;
        syncStreamConnecting = true;
        syncStreamConnectionKey = connectionKey;

        long knownRevision = Math.max(-1, lastKnownSyncRevision);
        int safeWaitMs = Math.max(50, waitMs);
        int requestTimeout = Math.max(10, timeoutSec + 30);
        syncStreamFuture = CompletableFuture.runAsync(() -> runSyncStreamLoop(baseUrl, deviceId, tokenValue, requestTimeout, knownRevision, safeWaitMs));
    }

    private void runSyncStreamLoop(String baseUrl, String deviceId, String token, int timeoutSec, long knownRevision, int waitMs) {
        String streamError = null;
        try {
            HttpRequest request = buildSyncStreamRequest(baseUrl, deviceId, token, timeoutSec, knownRevision, waitMs);
            HttpResponse<InputStream> response = syncHttpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                String errorBody = "";
                try (InputStream input = response.body()) {
                    if (input != null) errorBody = new String(input.readNBytes(512), StandardCharsets.UTF_8);
                }
                if (response.statusCode() == 404 && !syncStreamUseLegacyPath) {
                    syncStreamUseLegacyPath = true;
                    throw new IllegalStateException("stream-404-switching-to-legacy");
                }
                if (response.statusCode() == 404 && syncStreamUseLegacyPath) {
                    syncStreamUnsupported = true;
                    syncStreamUnsupportedUntilMs = System.currentTimeMillis() + SYNC_STREAM_UNSUPPORTED_BACKOFF_MS;
                    throw new IllegalStateException("stream-unsupported:http-404");
                }
                throw new IllegalStateException(parseHttpError(response.statusCode(), errorBody));
            }

            mc.execute(() -> {
                syncStreamConnecting = false;
                syncStreamConnected = true;
                syncStreamReconnectAtMs = 0;
                logSync("Ping sync stream connected.");
            });

            try (InputStream input = response.body();
                 BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
                String line;
                String eventType = "";
                StringBuilder data = new StringBuilder();
                while (!syncStreamStopRequested && (line = reader.readLine()) != null) {
                    if (line.startsWith("event:")) {
                        eventType = line.substring(6).trim();
                        continue;
                    }
                    if (line.startsWith("data:")) {
                        String row = line.length() > 5 ? line.substring(5).stripLeading() : "";
                        if (data.length() > 0) data.append('\n');
                        data.append(row);
                        continue;
                    }
                    if (!line.isBlank()) continue;
                    if (data.length() > 0) processSyncStreamEvent(eventType, data.toString());
                    eventType = "";
                    data.setLength(0);
                }
            }
        } catch (Throwable t) {
            if (!syncStreamStopRequested) streamError = formatSyncException("stream-error", t);
        } finally {
            String finalStreamError = streamError;
            mc.execute(() -> {
                syncStreamConnected = false;
                syncStreamConnecting = false;
                syncStreamFuture = null;
                if (!syncStreamStopRequested) {
                    long reconnectDelay = switch (classifySyncError(finalStreamError)) {
                        case AUTH -> SYNC_AUTH_BACKOFF_MS;
                        case CONFIG -> SYNC_CONFIG_BACKOFF_MS;
                        case CRYPTO -> SYNC_CRYPTO_BACKOFF_MS;
                        case NETWORK -> SYNC_NETWORK_BACKOFF_MS;
                        case OTHER -> SYNC_STREAM_RECONNECT_MS;
                    };
                    long reconnectAt = System.currentTimeMillis() + reconnectDelay;
                    if (syncStreamUnsupported && syncStreamUnsupportedUntilMs > reconnectAt) reconnectAt = syncStreamUnsupportedUntilMs;
                    syncStreamReconnectAtMs = reconnectAt;
                    if (finalStreamError != null && !finalStreamError.isBlank()) {
                        logSyncProblem("stream disconnected", finalStreamError);
                    }
                } else {
                    syncStreamReconnectAtMs = 0;
                }
            });
        }
    }

    private void processSyncStreamEvent(String eventType, String data) {
        if (data == null || data.isBlank()) return;
        JsonObject json = parseJsonObject(data);
        if (json == null) return;

        long revision = readLong(json, "revision", -1);
        if (revision > lastKnownSyncRevision) {
            syncStreamPendingRevision = revision;
            syncStreamUpdatePending = true;
        }
    }

    private boolean consumePendingStreamPullSignal() {
        if (!syncStreamUpdatePending) return false;
        syncStreamUpdatePending = false;
        if (syncStreamPendingRevision >= 0 && syncStreamPendingRevision <= lastKnownSyncRevision) return false;
        return true;
    }

    private void stopSyncStream() {
        syncStreamStopRequested = true;
        syncStreamConnecting = false;
        syncStreamConnected = false;
        syncStreamUpdatePending = false;
        syncStreamPendingRevision = -1;
        syncStreamReconnectAtMs = 0;
        syncStreamConnectionKey = "";

        CompletableFuture<Void> future = syncStreamFuture;
        syncStreamFuture = null;
        if (future != null) future.cancel(true);
    }

    private HttpRequest buildSyncStreamRequest(String baseUrl, String deviceId, String token, int timeoutSec, long knownRevision, int waitMs) {
        URI uri = buildSyncStreamUri(baseUrl, deviceId, knownRevision, waitMs);
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(Math.max(10, timeoutSec)))
            .header("Accept", "text/event-stream")
            .header("Cache-Control", "no-cache")
            .header("User-Agent", "Devils-PingSync/1.0")
            .GET();
        if (token != null && !token.isBlank()) builder.header("Authorization", "Bearer " + token);
        return builder.build();
    }

    private URI buildSyncStreamUri(String baseUrl, String deviceId, long knownRevision, int waitMs) {
        String query =
            "deviceId=" + encodeQueryValue(deviceId)
                + "&module=" + MODULE_NAMESPACE
                + "&knownRevision=" + knownRevision
                + "&waitMs=" + Math.max(50, waitMs);
        String path = syncStreamUseLegacyPath ? SYNC_STREAM_PATH_LEGACY : SYNC_STREAM_PATH;
        return URI.create(baseUrl + path + "?" + query);
    }

    private static String encodeQueryValue(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private SyncRuntimeConfig resolveSyncRuntimeConfig() {
        Modules modules = Modules.get();
        if (modules == null) return null;

        SyncHub syncHub = modules.get(SyncHub.class);
        if (syncHub == null) return null;

        if (!syncHub.isFeatureEnabled(SyncHub.SyncFeature.PING)) return null;

        String deviceId = syncHub.getOrCreateDeviceId();
        if (deviceId.isBlank()) return null;
        String encryptionKey = syncHub.getEncryptionKeyMaterial();
        if (encryptionKey.isBlank()) return null;

        return new SyncRuntimeConfig(
            syncHub.getBaseUrl(),
            syncHub.getToken(),
            deviceId,
            true,
            syncHub.allowHttp(),
            Math.max(3, syncHub.requestTimeoutSec()),
            Math.max(50, syncHub.streamWaitMs()),
            encryptionKey
        );
    }

    private String currentSyncDeviceId() {
        SyncRuntimeConfig config = resolveSyncRuntimeConfig();
        return config == null ? "" : config.deviceId();
    }

    private String currentUsername() {
        if (mc.getSession() == null || mc.getSession().getUsername() == null) return "";
        return mc.getSession().getUsername().trim();
    }

    private String currentServerKey() {
        if (mc.getCurrentServerEntry() != null && mc.getCurrentServerEntry().address != null) {
            String address = mc.getCurrentServerEntry().address.trim();
            if (!address.isEmpty()) return address;
        }
        String worldName = meteordevelopment.meteorclient.utils.Utils.getWorldName();
        return worldName == null ? "" : worldName.trim();
    }

    private static String normalizeSyncBaseUrl(String raw) {
        if (raw == null) return "";
        String base = raw.trim();
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return base;
    }

    private static String normalizeLocalSoundPath(String raw) {
        if (raw == null) return "";
        String value = raw.trim().replace('\\', '/');
        while (value.startsWith("/")) value = value.substring(1);
        return value;
    }

    private static String validateSyncBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) return "base-url-empty";
        try {
            URI uri = URI.create(baseUrl);
            String scheme = uri.getScheme();
            if (scheme == null) return "undefined scheme";
            if (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https")) return "unsupported scheme: " + scheme;
            if (uri.getHost() == null || uri.getHost().isBlank()) return "uri with undefined host";
            return null;
        } catch (Exception e) {
            return formatSyncException("bad-base-url", e);
        }
    }

    private static String computeFingerprint(List<SyncPingData> snapshot) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            List<String> rows = new ArrayList<>(snapshot.size());
            for (SyncPingData data : snapshot) {
                rows.add(
                    normalizeKey(data.username())
                        + "|"
                        + normalizeServerKey(data.server())
                        + "|"
                        + safe(data.payload())
                );
            }
            rows.sort(String::compareTo);
            for (String row : rows) {
                digest.update(row.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) '\n');
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception ignored) {
            return Integer.toHexString(snapshot.hashCode());
        }
    }

    private void logSyncProblem(String context, String error) {
        String safeError = error == null ? "unknown" : error;
        String signature = (context + "|" + safeError).toLowerCase(Locale.ROOT);
        long now = System.currentTimeMillis();

        if (signature.equals(lastSyncProblemSignature) && (now - lastSyncProblemLogMs) < SYNC_PROBLEM_LOG_COOLDOWN_MS) {
            lastSyncProblemSuppressed++;
            return;
        }

        if (lastSyncProblemSuppressed > 0 && !lastSyncProblemSignature.isBlank()) {
            logSync("Ping sync note: same error repeated %d times.", lastSyncProblemSuppressed);
        }

        lastSyncProblemSignature = signature;
        lastSyncProblemLogMs = now;
        lastSyncProblemSuppressed = 0;

        logSync("Ping sync %s: %s", context, safeError);
        SyncErrorType type = classifySyncError(safeError);
        if (type == SyncErrorType.AUTH) {
            syncBackoffUntilMs = Math.max(syncBackoffUntilMs, now + SYNC_AUTH_BACKOFF_MS);
            logSync("Ping sync hint: check token (must match SYNC_TOKEN on server).");
            return;
        }
        if (type == SyncErrorType.CONFIG) {
            syncBackoffUntilMs = Math.max(syncBackoffUntilMs, now + SYNC_CONFIG_BACKOFF_MS);
            logSync("Ping sync hint: check base-url (must include scheme).");
            return;
        }
        if (type == SyncErrorType.CRYPTO) {
            syncBackoffUntilMs = Math.max(syncBackoffUntilMs, now + SYNC_CRYPTO_BACKOFF_MS);
            logSync("Ping sync hint: encryption-key mismatch between clients.");
            return;
        }
        if (type == SyncErrorType.NETWORK) {
            syncBackoffUntilMs = Math.max(syncBackoffUntilMs, now + SYNC_NETWORK_BACKOFF_MS);
            logSync("Ping sync hint: check host/port/firewall and server reachability.");
        }
    }

    private void clearSyncProblemTracking() {
        lastSyncProblemSignature = "";
        lastSyncProblemLogMs = 0;
        lastSyncProblemSuppressed = 0;
        syncBackoffUntilMs = 0;
    }

    private static SyncErrorType classifySyncError(String error) {
        String normalized = error == null ? "" : error.toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) return SyncErrorType.OTHER;
        if (normalized.contains("401") || normalized.contains("unauthorized") || normalized.contains("forbidden")) return SyncErrorType.AUTH;
        if (normalized.contains("404") || normalized.contains("not-found") || normalized.contains("not_found")) return SyncErrorType.CONFIG;
        if (normalized.contains("undefined scheme") || normalized.contains("bad-base-url") || normalized.contains("unsupported scheme") || normalized.contains("uri with undefined host")) {
            return SyncErrorType.CONFIG;
        }
        if (normalized.contains("certificateexception") || normalized.contains("sslhandshakeexception") || normalized.contains("pkix") || normalized.contains("subject alternative names")) {
            return SyncErrorType.CONFIG;
        }
        if (normalized.contains("decrypt") || normalized.contains("aeadbadtagexception") || normalized.contains("tag mismatch")) {
            return SyncErrorType.CRYPTO;
        }
        if (normalized.contains("timeout")
            || normalized.contains("connect")
            || normalized.contains("ioexception")
            || normalized.contains("eofexception")
            || normalized.contains("connection")
            || normalized.contains("refused")
            || normalized.contains("reset")
            || normalized.contains("unreachable")
            || normalized.contains("unknownhost")
            || normalized.contains("noroutetohost")) {
            return SyncErrorType.NETWORK;
        }
        return SyncErrorType.OTHER;
    }

    private static String parseHttpError(HttpResponse<String> response) {
        if (response == null) return "http:error";
        return parseHttpError(response.statusCode(), response.body());
    }

    private static String parseHttpError(int statusCode, String body) {
        String safeBody = safe(body).replaceAll("\\s+", " ").trim();
        if (safeBody.length() > 120) safeBody = safeBody.substring(0, 120) + "...";
        if (!safeBody.isBlank()) return "http-" + statusCode + "-" + safeBody;
        return "http-" + statusCode;
    }

    private static String formatSyncException(String prefix, Throwable throwable) {
        if (throwable == null) return prefix + ":unknown";

        Throwable root = throwable;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();

        String type = root.getClass().getSimpleName();
        if (type == null || type.isBlank()) type = root.getClass().getName();
        type = type.toLowerCase(Locale.ROOT);

        String detail = compactSyncErrorMessage(root.getMessage());
        if (detail.isEmpty()) return prefix + ":" + type;
        return prefix + ":" + type + ":" + detail;
    }

    private static String compactSyncErrorMessage(String raw) {
        if (raw == null) return "";
        String compact = raw.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
        if (compact.isEmpty()) return "";
        if (compact.length() > SYNC_ERROR_DETAIL_MAX) return compact.substring(0, SYNC_ERROR_DETAIL_MAX) + "...";
        return compact;
    }

    private void logSync(String format, Object... args) {
        if (!syncVerbose.get()) return;
        info(format, args);
    }
    private static JsonObject parseJsonObject(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            if (!JsonParser.parseString(raw).isJsonObject()) return null;
            return JsonParser.parseString(raw).getAsJsonObject();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static JsonArray readArray(JsonObject json, String key) {
        if (json == null || key == null || !json.has(key) || !json.get(key).isJsonArray()) return null;
        return json.getAsJsonArray(key);
    }

    private static String readString(JsonObject json, String key, String fallback) {
        if (json == null || key == null || !json.has(key) || json.get(key).isJsonNull()) return fallback;
        try {
            return json.get(key).getAsString();
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static int readInt(JsonObject json, String key, int fallback) {
        if (json == null || key == null || !json.has(key) || json.get(key).isJsonNull()) return fallback;
        try {
            return json.get(key).getAsInt();
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static long readLong(JsonObject json, String key, long fallback) {
        if (json == null || key == null || !json.has(key) || json.get(key).isJsonNull()) return fallback;
        try {
            return json.get(key).getAsLong();
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static double readDouble(JsonObject json, String key, double fallback) {
        if (json == null || key == null || !json.has(key) || json.get(key).isJsonNull()) return fallback;
        try {
            return json.get(key).getAsDouble();
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static boolean readBoolean(JsonObject json, String key, boolean fallback) {
        if (json == null || key == null || !json.has(key) || json.get(key).isJsonNull()) return fallback;
        try {
            return json.get(key).getAsBoolean();
        } catch (Exception ignored) {
            return fallback;
        }
    }
    private static String safe(String value) { return value == null ? "" : value; }
    private static String normalizeKey(String value) { return value == null ? "" : value.trim().toLowerCase(Locale.ROOT); }
    private static String normalizeServerKey(String value) {
        String normalized = normalizeKey(value);
        return normalized.endsWith(":25565") ? normalized.substring(0, normalized.length() - 6) : normalized;
    }

    private static String buildMarkerId(String sender, String server) {
        String base = normalizeKey(sender) + "|" + normalizeServerKey(server);
        if (base.isBlank()) return "ping-marker";
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String hash = HexFormat.of().formatHex(digest.digest(base.getBytes(StandardCharsets.UTF_8)));
            return "ping-" + hash.substring(0, Math.min(24, hash.length()));
        } catch (Exception ignored) {
            return "ping-" + Integer.toHexString(base.hashCode());
        }
    }

    private static double clampY(double y) {
        if (!Double.isFinite(y)) return WORLD_MIN_Y;
        if (y < WORLD_MIN_Y) return WORLD_MIN_Y;
        if (y > WORLD_MAX_Y) return WORLD_MAX_Y;
        return y;
    }

    private static String formatCoords(double x, double y, double z) {
        return String.format(Locale.ROOT, "%d %d %d", Math.round(x), Math.round(y), Math.round(z));
    }

    private record SyncRuntimeConfig(String baseUrl, String token, String deviceId, boolean useStream, boolean allowHttp, int timeoutSec, int streamWaitMs, String encryptionKey) {}
    private record SyncPingData(boolean enabled, String username, String server, String payload, int delay) {}
    private record SyncPullResult(boolean ok, long revision, List<SyncPingData> profiles, String error, String lastWriter) {}
    private record SyncPushResult(boolean ok, boolean applied, boolean conflict, long revision, List<SyncPingData> profiles, String error, String lastWriter) {}
    private record SyncCycleResult(SyncPullResult pullResult, SyncPushResult pushResult, boolean remoteApplied, boolean localChanged, List<SyncPingData> snapshot, String snapshotFingerprint, String error) {}
    private record PingMarker(
        String id,
        String sender,
        String senderDevice,
        String server,
        String dimension,
        double x,
        double y,
        double z,
        long createdAtMs,
        boolean icon,
        String iconPath
    ) {}

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
    ) {}

    private enum SyncErrorType {
        AUTH,
        CONFIG,
        CRYPTO,
        NETWORK,
        OTHER
    }

    public enum InfoMode {
        Distance,
        Coords
    }
}
