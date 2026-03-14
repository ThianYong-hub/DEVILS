package com.example.addon.modules;

import com.example.addon.AddonTemplate;
import com.example.addon.gui.screens.settings.LocalIconSelectScreen;
import com.example.addon.util.CrashGuard;
import com.example.addon.util.MapIconManager;
import com.example.addon.util.SyncCrypto;
import com.example.addon.util.SyncRequestSigner;
import com.example.addon.util.XaeroSyncWaypoints;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.meteor.MouseButtonEvent;
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
import meteordevelopment.meteorclient.utils.misc.input.KeyAction;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.client.gui.PlayerSkinDrawer;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.CompletableFuture;

public class XaeroSync extends Module {
    private static final Pattern HOST_PORT_PATTERN = Pattern.compile("^([^:]+):(\\d+)$");
    private static final Pattern BRACKETED_HOST_PORT_PATTERN = Pattern.compile("^\\[([^\\]]+)](?::(\\d+))?$");
    private static XaeroSync internalInstance;
    private static final String SYNC_PULL_PATH = "/pull";
    private static final String SYNC_PUSH_PATH = "/push";
    private static final String SYNC_STREAM_PATH = "/v1/sync/stream";
    private static final String SYNC_STREAM_PATH_LEGACY = "/stream";
    private static final long PULL_FALLBACK_INTERVAL_MS = 25;
    private static final long SYNC_STREAM_RECONNECT_MS = 250;
    private static final long SYNC_AUTH_BACKOFF_MS = 1_000;
    private static final long SYNC_CRYPTO_BACKOFF_MS = 1_000;
    private static final long SYNC_NETWORK_BACKOFF_MS = 150;
    private static final long SYNC_CONFIG_BACKOFF_MS = 1_000;
    private static final long SYNC_STREAM_UNSUPPORTED_BACKOFF_MS = 300_000;
    private static final long SYNC_PROBLEM_LOG_COOLDOWN_MS = 20_000;
    private static final int SYNC_ERROR_DETAIL_MAX = 120;
    private static final int WORLD_MIN_Y = -65;
    private static final int WORLD_MAX_Y = 365;

    private static final String MODULE_NAMESPACE = "xaero-world-map";
    private static final String PRESENCE_SCHEMA = "devils-xaero-presence-v1";
    private static final String PRESENCE_USERNAME_PREFIX = "__presence__:";

    private static final long PRESENCE_STALE_MS = 30_000;
    // Keep high responsiveness without flooding sync hub on fast movement.
    private static final long PRESENCE_MIN_UPDATE_MS = 1;
    // Keep occasional heartbeat while idle without flooding push traffic.
    private static final long PRESENCE_FORCE_UPDATE_MS = 5;
    // Treat any meaningful movement as an update.
    private static final double PRESENCE_MOVE_THRESHOLD_SQ = 0.0;
    private static final int MAX_SYNC_PRESENCE = 64;
    private static final double PRESENCE_MAX_SPEED_BLOCKS_PER_SEC = 230.0;
    private static final int MAX_PARALLEL_SYNC_CYCLES = 12;
    private static final long AGGRESSIVE_RENDER_SYNC_INTERVAL_MS = 5L;

    private static final int BUTTON_W = 20;
    private static final int BUTTON_H = 20;
    private static final int ROW_H = 14;
    private static final int PANEL_W = 240;
    private static final int DEVILS_MAP_ICON_SOURCE_SIZE = 1024;
    // Cropped non-transparent bounds inside the 1024x1024 source image.
    private static final int DEVILS_MAP_ICON_U = 256;
    private static final int DEVILS_MAP_ICON_V = 167;
    private static final int DEVILS_MAP_ICON_REGION_W = 525;
    private static final int DEVILS_MAP_ICON_REGION_H = 612;
    // Smaller icon region without "DEVILS" text for tiny map markers.
    private static final int DEVILS_MARKER_ICON_U = 230;
    private static final int DEVILS_MARKER_ICON_V = 120;
    private static final int DEVILS_MARKER_ICON_REGION_W = 560;
    private static final int DEVILS_MARKER_ICON_REGION_H = 500;
    private static final Identifier XAERO_SYNC_ICON_TEXTURE = Identifier.of("devils-addon", "textures/gui/devils_map_icon.png");
    private static final int DEVILS_ACCENT_BORDER = 0xFF5C0000;
    private static final int DEVILS_PANEL_BACKGROUND = 0xD0110505;
    private static final int DEVILS_TOOLTIP_BACKGROUND = 0xD0140606;
    private static final int DEVILS_ROW_HOVER = 0x70401A1A;
    private static final int DEVILS_TEXT_PRIMARY = 0xFFF2DCDC;
    private static final int DEVILS_TEXT_SECONDARY = 0xFFE2BDBD;
    private static final int DEVILS_TEXT_MUTED = 0xFFB89090;
    private static final int DEVILS_BUTTON_BACKGROUND = 0xC01A0909;
    private static final int DEVILS_BUTTON_BACKGROUND_HOVER = 0xD0250D0D;
    private static final int DEVILS_BUTTON_BACKGROUND_ACTIVE = 0xE0341212;
    private static final int DEVILS_MAP_LABEL_BG = 0xB8110505;
    private static final int DEVILS_MAP_LABEL_ICON = 10;
    private static final int DEVILS_MAP_POINT_ICON = 12;
    private static final int DEVILS_MAP_LABEL_OFFSET_Y = 11;
    private static final int DEVILS_MAP_LABEL_MARGIN = 160;
    private static final int DEVILS_MAP_LABEL_MAX = 80;
    private static final int XAERO_TOOLBAR_WIDTH = 24;
    private static final String DEFAULT_DEVILS_MAP_ICON_PATH = MapIconManager.DEFAULT_EMBEDDED_ICON_PATH;

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
        .defaultValue(DEFAULT_DEVILS_MAP_ICON_PATH)
        .visible(() -> false)
        .build()
    );

    private final Setting<String> playerFallbackIconPath = sgIcons.add(new StringSetting.Builder()
        .name("player-fallback-icon-path")
        .description("Custom icon file (.png/.jpg) from <gameDir>/devils-addon/icons when player skin is unavailable.")
        .defaultValue(DEFAULT_DEVILS_MAP_ICON_PATH)
        .visible(() -> false)
        .build()
    );

    private final HttpClient syncHttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final LinkedHashMap<String, PresenceMarker> syncedPresence = new LinkedHashMap<>();
    private final LinkedHashMap<String, PresenceMotionState> presenceMotion = new LinkedHashMap<>();
    private long localPresenceSequence;

    private PresenceMarker localPresenceCache;
    private int syncInFlightCycles;
    private long lastKnownSyncRevision = -1;
    private long lastSyncPullAttemptMs;
    private String lastSyncedFingerprint = "";
    private String lastSyncStatus = "idle";
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
    private boolean menuOpen;
    private volatile boolean syncTickQueued;
    private long lastRenderSyncAttemptMs;
    private String lastRuntimeDebugSnapshot = "";
    private String lastWaypointPipelineDebugSnapshot = "";
    private String lastWaypointDebugMessage = "";
    private long lastWaypointDebugMessageAtMs;
    private int suppressedWaypointDebugRepeats;
    private String lastSyncUnavailableReason = "";
    private static final Map<UUID, PingRenderMarker> trackedPingRenderMarkers = new HashMap<>();

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
        autoSubscribe = false;
        runInMainMenu = true;
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
        list.add(theme.label("Sync status: " + safe(lastSyncStatus))).expandX();

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
        final XaeroSync resolved = instance;

        CrashGuard.run(resolved, "screenRenderHook", () -> {
            if (!resolved.isOverlayVisible(screen)) {
                resolved.menuOpen = false;
                return;
            }
            resolved.renderOverlay(screen, drawContext, mouseX, mouseY, cameraX, cameraZ, scale);
        });
    }

    public static boolean onXaeroMapMouseClickHook(Screen screen, double mouseX, double mouseY, int button) {
        XaeroSync instance = internalInstance;
        if (instance == null) instance = ensureInternal();
        if (instance == null) return false;
        final XaeroSync resolved = instance;

        final boolean[] handled = { false };
        CrashGuard.run(resolved, "screenMouseClickHook", () -> handled[0] = resolved.handleOverlayClick(screen, button, mouseX, mouseY));
        return handled[0];
    }

    // Backward-compatible aliases for older hooks.
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
        CrashGuard.run(this, "onGameLeft", () -> {
            clearState();
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST + 1)
    private void onMouseButton(MouseButtonEvent event) {
        CrashGuard.run(this, "onMouseButton", () -> {
            if (handleOverlayClick(event.action, event.button, event)) event.setCancelled(true);
        });
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        CrashGuard.run(this, "onTick", () -> {
            configureWaypointDebugBridge();
            handleSyncTick();
        });
    }

    @EventHandler
    private void onRender2D(Render2DEvent event) {
        CrashGuard.run(this, "onRender2D", () -> {
            if (isActive() && mc.world != null && mc.player != null) {
                long now = System.currentTimeMillis();
                if ((now - lastRenderSyncAttemptMs) >= AGGRESSIVE_RENDER_SYNC_INTERVAL_MS) {
                    lastRenderSyncAttemptMs = now;
                    handleSyncTick();
                }
            }
            if (isXaeroMapScreen(mc.currentScreen)) return;
            renderOverlay(event);
        });
    }

    private void clearState() {
        stopSyncStream();
        syncedPresence.clear();
        presenceMotion.clear();
        localPresenceCache = null;
        localPresenceSequence = 0;
        runtimeSyncDeviceId = "";
        syncInFlightCycles = 0;
        lastKnownSyncRevision = -1;
        lastSyncedFingerprint = "";
        lastSyncPullAttemptMs = 0;
        syncTickQueued = false;
        lastRenderSyncAttemptMs = 0L;
        syncStreamUseLegacyPath = false;
        syncStreamUnsupported = false;
        syncStreamUnsupportedUntilMs = 0;
        menuOpen = false;
        lastRuntimeDebugSnapshot = "";
        lastWaypointPipelineDebugSnapshot = "";
        lastWaypointDebugMessage = "";
        lastWaypointDebugMessageAtMs = 0;
        suppressedWaypointDebugRepeats = 0;
        lastSyncUnavailableReason = "";
        clearTrackedPingRenderSnapshot();
        XaeroSyncWaypoints.setDebugListener(null);
        XaeroSyncWaypoints.clear();
    }

    private void configureWaypointDebugBridge() {
        if (!isWaypointDebugEnabled()) {
            XaeroSyncWaypoints.setDebugListener(null);
            return;
        }
        XaeroSyncWaypoints.setDebugListener(this::forwardWaypointDebug);
    }

    private void forwardWaypointDebug(String message) {
        if (!isWaypointDebugEnabled()) return;
        String safeMessage = safe(message).trim();
        if (safeMessage.isBlank()) return;

        long now = System.currentTimeMillis();
        if (safeMessage.equals(lastWaypointDebugMessage) && (now - lastWaypointDebugMessageAtMs) < 1200) {
            suppressedWaypointDebugRepeats++;
            return;
        }

        if (suppressedWaypointDebugRepeats > 0 && !lastWaypointDebugMessage.isBlank()) {
            info("[XaeroDbg] %s (repeated %d times)", lastWaypointDebugMessage, suppressedWaypointDebugRepeats);
            suppressedWaypointDebugRepeats = 0;
        }

        lastWaypointDebugMessage = safeMessage;
        lastWaypointDebugMessageAtMs = now;
        info("[XaeroDbg] %s", safeMessage);
    }

    private void debugPipeline(String format, Object... args) {
        if (!isWaypointDebugEnabled()) return;
        String message;
        try {
            message = args == null || args.length == 0
                ? safe(format)
                : String.format(Locale.ROOT, safe(format), args);
        } catch (Throwable ignored) {
            message = safe(format);
        }
        if (message.isBlank()) return;
        forwardWaypointDebug(message);
    }

    private boolean isWaypointDebugEnabled() {
        if (waypointDebug.get()) return true;

        Modules modules = Modules.get();
        if (modules == null) return false;

        SyncHub syncHub = modules.get(SyncHub.class);
        if (syncHub != null && syncHub.xaeroDebugPipeline()) return true;

        Ping ping = modules.get(Ping.class);
        return ping != null && ping.xaeroMapDebug();
    }

    private String explainSyncRuntimeUnavailable() {
        Modules modules = Modules.get();
        if (modules == null) return "modules-null";

        SyncHub syncHub = modules.get(SyncHub.class);
        if (syncHub == null) return "sync-hub-missing";
        if (!syncHub.isFeatureEnabled(SyncHub.SyncFeature.XAERO_WORLD_MAP)) return "sync-hub-xaero-feature-disabled";
        if (syncHub.getOrCreateDeviceId().isBlank()) return "sync-hub-device-id-empty";
        if (syncHub.getEncryptionKeyMaterial().isBlank()) return "sync-hub-encryption-key-empty";
        if (syncHub.getRequestSigningKey().isBlank()) return "sync-hub-signing-key-empty";
        return "unknown";
    }

    private void handleSyncTick() {
        SyncRuntimeConfig sync = resolveSyncRuntimeConfig();
        if (sync == null) {
            String unavailableReason = explainSyncRuntimeUnavailable();
            if (!unavailableReason.equals(lastSyncUnavailableReason)) {
                debugPipeline("runtime config unavailable: %s", unavailableReason);
                lastSyncUnavailableReason = unavailableReason;
            }
            clearSyncRuntimeStateKeepingUi();
            applyXaeroPresenceSnapshot();
            return;
        }
        lastSyncUnavailableReason = "";
        if (syncInFlightCycles >= MAX_PARALLEL_SYNC_CYCLES) {
            syncTickQueued = true;
            applyXaeroPresenceSnapshot();
            return;
        }

        runtimeSyncDeviceId = sync.deviceId();

        if (isWaypointDebugEnabled()) {
            String runtimeSnapshot = normalizeSyncBaseUrl(sync.baseUrl())
                + "|stream=" + sync.useStream()
                + "|http=" + sync.allowHttp()
                + "|timeout=" + sync.timeoutSec()
                + "|waitMs=" + sync.streamWaitMs();
            if (!runtimeSnapshot.equals(lastRuntimeDebugSnapshot)) {
                debugPipeline("runtime config active: %s", runtimeSnapshot);
                lastRuntimeDebugSnapshot = runtimeSnapshot;
            }
        }

        String baseUrl = normalizeSyncBaseUrl(sync.baseUrl());
        if (baseUrl.isBlank()) {
            lastSyncStatus = "skip:no-base-url";
            debugPipeline("sync skipped: base URL is blank.");
            stopSyncStream();
            applyXaeroPresenceSnapshot();
            return;
        }

        String baseUrlValidationError = validateSyncBaseUrl(baseUrl);
        if (baseUrlValidationError != null) {
            lastSyncStatus = "skip:bad-base-url";
            logSyncProblem("invalid base-url", baseUrlValidationError);
            debugPipeline("sync skipped: invalid base URL (%s).", baseUrlValidationError);
            stopSyncStream();
            applyXaeroPresenceSnapshot();
            return;
        }

        if (!sync.allowHttp() && baseUrl.startsWith("http://")) {
            lastSyncStatus = "skip:http-disabled";
            debugPipeline("sync skipped: HTTP is disabled while base URL is HTTP.");
            stopSyncStream();
            applyXaeroPresenceSnapshot();
            return;
        }

        if (mc.player == null || mc.world == null) {
            debugPipeline("sync skipped: local player/world not ready.");
            stopSyncStream();
            applyXaeroPresenceSnapshot();
            return;
        }

        if (sync.useStream()) ensureSyncStream(baseUrl, sync.deviceId(), sync.token(), sync.signingKey(), sync.timeoutSec(), sync.streamWaitMs());
        else stopSyncStream();

        if (syncBackoffUntilMs > System.currentTimeMillis()) {
            applyXaeroPresenceSnapshot();
            return;
        }

        long now = System.currentTimeMillis();
        List<SyncXaeroData> localSnapshot = snapshotSyncData();
        String localFingerprint = computeFingerprint(localSnapshot);
        boolean localChanged = !localFingerprint.equals(lastSyncedFingerprint);
        boolean streamTriggeredPull = consumePendingStreamPullSignal();

        boolean shouldBootstrapPull = lastKnownSyncRevision < 0;
        // Hard sync mode: always poll quickly in addition to stream signals.
        boolean periodicPull = (now - lastSyncPullAttemptMs) >= PULL_FALLBACK_INTERVAL_MS;
        boolean shouldPull = streamTriggeredPull || shouldBootstrapPull || periodicPull;
        boolean shouldRun = streamTriggeredPull || localChanged || shouldBootstrapPull || periodicPull;
        if (!shouldRun) {
            applyXaeroPresenceSnapshot();
            return;
        }

        lastSyncPullAttemptMs = now;
        syncInFlightCycles++;
        int pullWaitMs = sync.useStream() ? 0 : sync.streamWaitMs();
        runSyncCycleAsync(
            baseUrl,
            sync.deviceId(),
            sync.token(),
            sync.signingKey(),
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
        String signingKey,
        int timeoutSec,
        int pullWaitMs,
        String encryptionKey,
        long knownRevision,
        List<SyncXaeroData> localSnapshot,
        String localFingerprint,
        boolean localChanged,
        boolean doPull
    ) {
        CompletableFuture.runAsync(() -> {
            SyncPullResult pullResult = null;
            SyncPushResult pushResult = null;
            boolean remoteApplied = false;
            String error = null;

            List<SyncXaeroData> effectiveSnapshot = localSnapshot;
            String effectiveFingerprint = localFingerprint;
            long pushBaseRevision = knownRevision;
            boolean pushRequestedByMerge = false;
            boolean localNeedsPush = localChanged;

            if (doPull) {
                try {
                    pullResult = sendPullRequest(baseUrl, deviceId, token, signingKey, timeoutSec, encryptionKey, knownRevision, pullWaitMs);
                    if (!pullResult.ok()) {
                        error = "pull-rejected:" + pullResult.error();
                    } else {
                        if (pullResult.revision() >= 0) pushBaseRevision = pullResult.revision();

                        List<SyncXaeroData> remoteSnapshot = pullResult.profiles() == null ? List.of() : pullResult.profiles();
                        String remoteFingerprint = computeFingerprint(remoteSnapshot);
                        boolean remoteIsNewer = pullResult.revision() > knownRevision && pullResult.profiles() != null;

                        if (localChanged) {
                            List<SyncXaeroData> merged = mergeSnapshots(remoteSnapshot, localSnapshot);
                            String mergedFingerprint = computeFingerprint(merged);
                            if (!mergedFingerprint.equals(remoteFingerprint)) {
                                effectiveSnapshot = merged;
                                effectiveFingerprint = mergedFingerprint;
                                pushRequestedByMerge = true;
                                localNeedsPush = true;
                            } else {
                                // Remote already has local state; avoid redundant push with local-only snapshot.
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
                    pushResult = sendPushRequest(baseUrl, deviceId, token, signingKey, timeoutSec, encryptionKey, pushBaseRevision, effectiveSnapshot);

                    if (pushResult.ok() && pushResult.conflict() && pushResult.profiles() != null && pushResult.revision() >= 0) {
                        List<SyncXaeroData> conflictMerged = mergeSnapshots(pushResult.profiles(), localSnapshot);
                        String conflictFingerprint = computeFingerprint(conflictMerged);
                        pushResult = sendPushRequest(baseUrl, deviceId, token, signingKey, timeoutSec, encryptionKey, pushResult.revision(), conflictMerged);
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
        if (syncInFlightCycles > 0) syncInFlightCycles--;
        try {
            if (result.error() != null) {
                lastSyncStatus = result.error();
                logSyncProblem("failed", result.error());
                applyXaeroPresenceSnapshot();
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
                applyXaeroPresenceSnapshot();
                return;
            }

            if (result.pushResult() != null) {
                if (result.pushResult().ok() && result.pushResult().applied()) {
                    if (result.pushResult().revision() >= 0) {
                        lastKnownSyncRevision = Math.max(lastKnownSyncRevision, result.pushResult().revision());
                    }
                    lastSyncStatus = "push-ok";
                    applySyncedSnapshot(result.snapshot());
                    lastSyncedFingerprint = result.snapshotFingerprint();
                    clearSyncProblemTracking();
                    applyXaeroPresenceSnapshot();
                    return;
                }

                if (result.pushResult().ok() && result.pushResult().conflict()) {
                    lastSyncStatus = "push-conflict";
                    applySyncedSnapshot(result.snapshot());
                    lastSyncedFingerprint = result.snapshotFingerprint();
                    clearSyncProblemTracking();
                    applyXaeroPresenceSnapshot();
                    return;
                }

                if (result.pushResult().ok()) {
                    lastSyncStatus = "push-rejected:" + result.pushResult().error();
                    logSyncProblem("push rejected", result.pushResult().error());
                    applyXaeroPresenceSnapshot();
                    return;
                }
            }

            if (!result.localChanged()) {
                lastSyncedFingerprint = result.snapshotFingerprint();
                lastSyncStatus = "noop";
                clearSyncProblemTracking();
                applyXaeroPresenceSnapshot();
                return;
            }

            lastSyncStatus = "local-change-pending";
            applyXaeroPresenceSnapshot();
        } finally {
            if (syncTickQueued) {
                syncTickQueued = false;
                handleSyncTick();
            }
        }
    }

    private void applySyncedSnapshot(List<SyncXaeroData> snapshot) {
        mergeSyncedPresence(decodeToPresenceMap(snapshot));
    }

    private List<SyncXaeroData> mergeSnapshots(List<SyncXaeroData> remoteSnapshot, List<SyncXaeroData> localSnapshot) {
        LinkedHashMap<String, PresenceMarker> mergedPresence = decodeToPresenceMap(remoteSnapshot);
        LinkedHashMap<String, PresenceMarker> localPresence = decodeToPresenceMap(localSnapshot);

        for (PresenceMarker localPresenceMarker : localPresence.values()) {
            PresenceMarker existing = mergedPresence.get(localPresenceMarker.id());
            if (isPresenceNewer(localPresenceMarker, existing)) {
                mergedPresence.put(localPresenceMarker.id(), localPresenceMarker);
            }
        }

        List<PresenceMarker> sortedPresence = new ArrayList<>(mergedPresence.values());
        sortedPresence.sort(Comparator.comparingLong(PresenceMarker::updatedAtMs).reversed());
        if (sortedPresence.size() > MAX_SYNC_PRESENCE) sortedPresence = sortedPresence.subList(0, MAX_SYNC_PRESENCE);

        ArrayList<SyncXaeroData> merged = new ArrayList<>(sortedPresence.size());
        for (PresenceMarker presence : sortedPresence) merged.add(encodePresence(presence));
        return merged;
    }

    private List<SyncXaeroData> snapshotSyncData() {
        PresenceMarker localPresence = buildOrReuseLocalPresence();
        if (localPresence == null) {
            localPresenceCache = null;
            return List.of();
        }

        // Publish only local presence. Remote presence must not be rebroadcast back,
        // otherwise stale coordinates can bounce between clients.
        syncedPresence.put(localPresence.id(), localPresence);
        return List.of(encodePresence(localPresence));
    }

    private SyncXaeroData encodePresence(PresenceMarker presence) {
        JsonObject payload = new JsonObject();
        payload.addProperty("schema", PRESENCE_SCHEMA);
        payload.addProperty("sender", presence.sender());
        payload.addProperty("uuid", presence.playerUuid());
        payload.addProperty("senderDevice", presence.senderDevice());
        payload.addProperty("dim", presence.dimension());
        payload.addProperty("x", presence.x());
        payload.addProperty("y", presence.y());
        payload.addProperty("z", presence.z());
        payload.addProperty("vx", presence.vx());
        payload.addProperty("vy", presence.vy());
        payload.addProperty("vz", presence.vz());
        payload.addProperty("seq", presence.sequence());
        payload.addProperty("updatedAt", presence.updatedAtMs());

        return new SyncXaeroData(
            true,
            PRESENCE_USERNAME_PREFIX + presence.sender(),
            presence.server(),
            payload.toString(),
            0
        );
    }

    private LinkedHashMap<String, PresenceMarker> decodeToPresenceMap(List<SyncXaeroData> list) {
        LinkedHashMap<String, PresenceMarker> map = new LinkedHashMap<>();
        if (list == null) return map;

        for (SyncXaeroData data : list) {
            PresenceMarker presence = decodePresence(data);
            if (presence == null) continue;

            PresenceMarker existing = map.get(presence.id());
            if (isPresenceNewer(presence, existing)) {
                map.put(presence.id(), presence);
            }
        }

        return map;
    }

    private PresenceMarker decodePresence(SyncXaeroData data) {
        if (data == null || !data.enabled()) return null;

        String payload = safe(data.payload());
        if (payload.isBlank()) return null;

        JsonObject json;
        try {
            if (!JsonParser.parseString(payload).isJsonObject()) return null;
            json = JsonParser.parseString(payload).getAsJsonObject();
        } catch (Throwable ignored) {
            return null;
        }

        if (!PRESENCE_SCHEMA.equals(readString(json, "schema", ""))) return null;

        String sender = readString(json, "sender", "");
        if (sender.isBlank() && data.username() != null && data.username().startsWith(PRESENCE_USERNAME_PREFIX)) {
            sender = data.username().substring(PRESENCE_USERNAME_PREFIX.length());
        }

        String playerUuid = readString(json, "uuid", "");
        String senderDevice = readString(json, "senderDevice", "");
        String server = safe(data.server());
        if (server.isBlank()) server = readString(json, "server", "");
        String dimension = readString(json, "dim", "");
        double x = readDouble(json, "x", Double.NaN);
        double y = clampY(readDouble(json, "y", Double.NaN));
        double z = readDouble(json, "z", Double.NaN);
        double vx = readDouble(json, "vx", Double.NaN);
        double vy = readDouble(json, "vy", Double.NaN);
        double vz = readDouble(json, "vz", Double.NaN);
        long sequence = readLong(json, "seq", 0);
        long updatedAt = readLong(json, "updatedAt", 0);

        if (sender.isBlank() || server.isBlank()) return null;
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) return null;
        if (!Double.isFinite(vx)) vx = 0.0;
        if (!Double.isFinite(vy)) vy = 0.0;
        if (!Double.isFinite(vz)) vz = 0.0;
        double speedSq = (vx * vx) + (vy * vy) + (vz * vz);
        double maxSq = PRESENCE_MAX_SPEED_BLOCKS_PER_SEC * PRESENCE_MAX_SPEED_BLOCKS_PER_SEC;
        if (speedSq > maxSq) {
            double speed = Math.sqrt(speedSq);
            if (speed > 0.0) {
                double clampScale = PRESENCE_MAX_SPEED_BLOCKS_PER_SEC / speed;
                vx *= clampScale;
                vy *= clampScale;
                vz *= clampScale;
            }
        }
        long nowMs = System.currentTimeMillis();
        if (updatedAt <= 0 || Math.abs(updatedAt - nowMs) > 5_000L) updatedAt = nowMs;

        String identity = normalizeKey(playerUuid);
        if (identity.isBlank()) identity = normalizeKey(sender);
        String id = identity + "|" + normalizeServerKey(server);
        return new PresenceMarker(id, sender, playerUuid, senderDevice, server, dimension, x, y, z, vx, vy, vz, sequence, updatedAt);
    }

    private PresenceMarker buildOrReuseLocalPresence() {
        if (mc.player == null || mc.world == null) return null;

        String sender = currentUsername();
        String playerUuid = currentPlayerUuid();
        String senderDevice = currentSyncDeviceId();
        String server = currentServerKey();
        if (sender.isBlank() || senderDevice.isBlank() || server.isBlank()) return null;

        String dimension = mc.world.getRegistryKey().getValue().toString();
        double x = mc.player.getX();
        double y = clampY(mc.player.getY());
        double z = mc.player.getZ();
        Vec3d velocity = mc.player.getVelocity();
        // Minecraft velocity is blocks/tick; convert to blocks/second for transport.
        double vx = velocity == null ? 0.0 : velocity.x * 20.0;
        double vy = velocity == null ? 0.0 : velocity.y * 20.0;
        double vz = velocity == null ? 0.0 : velocity.z * 20.0;
        double speedSq = (vx * vx) + (vy * vy) + (vz * vz);
        double maxSq = PRESENCE_MAX_SPEED_BLOCKS_PER_SEC * PRESENCE_MAX_SPEED_BLOCKS_PER_SEC;
        if (speedSq > maxSq) {
            double speed = Math.sqrt(speedSq);
            if (speed > 0.0) {
                double clampScale = PRESENCE_MAX_SPEED_BLOCKS_PER_SEC / speed;
                vx *= clampScale;
                vy *= clampScale;
                vz *= clampScale;
            }
        }
        long now = System.currentTimeMillis();
        String localId = (normalizeKey(playerUuid).isBlank() ? normalizeKey(sender) : normalizeKey(playerUuid)) + "|" + normalizeServerKey(server);

        if (localPresenceCache == null) {
            long seq = ++localPresenceSequence;
            localPresenceCache = new PresenceMarker(
                localId,
                sender,
                playerUuid,
                senderDevice,
                server,
                dimension,
                x,
                y,
                z,
                vx,
                vy,
                vz,
                seq,
                now
            );
            return localPresenceCache;
        }

        boolean connectionChanged = !normalizeKey(localPresenceCache.sender()).equals(normalizeKey(sender))
            || !normalizeKey(localPresenceCache.playerUuid()).equals(normalizeKey(playerUuid))
            || !normalizeServerKey(localPresenceCache.server()).equals(normalizeServerKey(server))
            || !normalizeKey(localPresenceCache.senderDevice()).equals(normalizeKey(senderDevice))
            || !normalizeKey(localPresenceCache.dimension()).equals(normalizeKey(dimension));

        double dx = localPresenceCache.x() - x;
        double dy = localPresenceCache.y() - y;
        double dz = localPresenceCache.z() - z;
        double movedSq = (dx * dx) + (dy * dy) + (dz * dz);

        long age = now - localPresenceCache.updatedAtMs();
        boolean movedEnough = movedSq >= PRESENCE_MOVE_THRESHOLD_SQ && age >= PRESENCE_MIN_UPDATE_MS;
        double dvx = localPresenceCache.vx() - vx;
        double dvy = localPresenceCache.vy() - vy;
        double dvz = localPresenceCache.vz() - vz;
        double velocityDeltaSq = (dvx * dvx) + (dvy * dvy) + (dvz * dvz);
        boolean velocityChanged = velocityDeltaSq >= 0.0009 && age >= PRESENCE_MIN_UPDATE_MS;
        boolean forceRefresh = age >= PRESENCE_FORCE_UPDATE_MS;

        if (connectionChanged || movedEnough || velocityChanged || forceRefresh) {
            long seq = Math.max(localPresenceCache.sequence() + 1, localPresenceSequence + 1);
            localPresenceSequence = seq;
            localPresenceCache = new PresenceMarker(
                localId,
                sender,
                playerUuid,
                senderDevice,
                server,
                dimension,
                x,
                y,
                z,
                vx,
                vy,
                vz,
                seq,
                now
            );
        }

        return localPresenceCache;
    }

    private void mergeSyncedPresence(Map<String, PresenceMarker> incoming) {
        if (incoming != null && !incoming.isEmpty()) {
            for (PresenceMarker marker : incoming.values()) {
                if (marker == null) continue;
                PresenceMarker existing = syncedPresence.get(marker.id());
                if (isPresenceNewer(marker, existing)) {
                    syncedPresence.put(marker.id(), marker);
                }
            }
        }

        long now = System.currentTimeMillis();
        syncedPresence.entrySet().removeIf(entry -> {
            PresenceMarker marker = entry.getValue();
            return marker == null || (now - marker.updatedAtMs()) > PRESENCE_STALE_MS;
        });

        if (syncedPresence.size() <= MAX_SYNC_PRESENCE) return;
        ArrayList<PresenceMarker> sorted = new ArrayList<>(syncedPresence.values());
        sorted.sort(Comparator.comparingLong(PresenceMarker::updatedAtMs).reversed());
        syncedPresence.clear();
        for (int i = 0; i < Math.min(sorted.size(), MAX_SYNC_PRESENCE); i++) {
            PresenceMarker marker = sorted.get(i);
            syncedPresence.put(marker.id(), marker);
        }
    }

    private void applyXaeroPresenceSnapshot() {
        String selfName = normalizeKey(currentUsername());
        String selfUuid = normalizeKey(currentPlayerUuid());
        String serverKey = normalizeServerKey(currentServerKey());

        long now = System.currentTimeMillis();
        ArrayList<XaeroSyncWaypoints.PlayerMarker> xaeroMarkers = new ArrayList<>();
        Set<String> activePresenceIds = new HashSet<>();
        Set<String> visiblePresenceSenders = new HashSet<>();

        for (PresenceMarker presence : syncedPresence.values()) {
            if (presence == null) continue;
            if ((now - presence.updatedAtMs()) > PRESENCE_STALE_MS) continue;

            String sender = normalizeKey(presence.sender());
            String senderUuid = normalizeKey(presence.playerUuid());
            if (sender.isBlank()) continue;
            if (!selfUuid.isBlank() && selfUuid.equals(senderUuid)) continue;
            if (!selfName.isBlank() && sender.equals(selfName)) continue;

            String markerServer = normalizeServerKey(presence.server());
            if (!serverKey.isBlank() && !markerServer.isBlank() && !serverKey.equals(markerServer)) continue;

            activePresenceIds.add(presence.id());
            visiblePresenceSenders.add(sender);
            PresenceRenderPosition renderPosition = resolvePresenceRenderPosition(presence, now);

            xaeroMarkers.add(new XaeroSyncWaypoints.PlayerMarker(
                presence.id(),
                presence.sender(),
                presence.playerUuid(),
                presence.dimension(),
                renderPosition.x(),
                renderPosition.y(),
                renderPosition.z()
            ));
        }

        cleanupPresenceMotion(activePresenceIds, now);

        int visiblePresenceCount = xaeroMarkers.size();
        ArrayList<XaeroSyncWaypoints.MapWaypointMarker> mapMarkers = new ArrayList<>();
        PingMarkerRouting pingRouting = collectPingTrackedMarkers(serverKey, visiblePresenceSenders);
        xaeroMarkers.addAll(pingRouting.trackedMarkers());
        mapMarkers.addAll(pingRouting.mapMarkers());
        updateTrackedPingRenderSnapshot(pingRouting.trackedMarkers());

        boolean clearWaypoints = xaeroMarkers.isEmpty() && mapMarkers.isEmpty();
        if (isWaypointDebugEnabled()) {
            String snapshot = "presenceStore=" + syncedPresence.size()
                + "|presenceVisible=" + visiblePresenceCount
                + "|pingMarkers=" + pingRouting.totalCount()
                + "|pingTracked=" + pingRouting.trackedCount()
                + "|pingFallback=" + pingRouting.fallbackCount()
                + "|pingUuidCollisions=" + pingRouting.collisionCount()
                + "|mapWaypoints=" + mapMarkers.size()
                + "|action=" + (clearWaypoints ? "clear" : "apply");
            if (!snapshot.equals(lastWaypointPipelineDebugSnapshot)) {
                debugPipeline("pipeline snapshot: %s", snapshot);
                lastWaypointPipelineDebugSnapshot = snapshot;
            }
        }

        if (clearWaypoints) {
            XaeroSyncWaypoints.clear();
            return;
        }
        XaeroSyncWaypoints.apply(xaeroMarkers, mapMarkers);
    }

    private PingMarkerRouting collectPingTrackedMarkers(String serverKey, Set<String> presenceSenders) {
        ArrayList<XaeroSyncWaypoints.PlayerMarker> trackedMarkers = new ArrayList<>();
        ArrayList<XaeroSyncWaypoints.MapWaypointMarker> mapMarkers = new ArrayList<>();
        Modules modules = Modules.get();
        if (modules == null) return new PingMarkerRouting(trackedMarkers, mapMarkers, 0, 0, 0, 0);

        Ping ping = modules.get(Ping.class);
        if (ping == null) return new PingMarkerRouting(trackedMarkers, mapMarkers, 0, 0, 0, 0);
        Ping.InfoMode infoMode = ping.xaeroInfoMode();
        if (infoMode == null) infoMode = Ping.InfoMode.Distance;

        int totalCount = 0;

        for (Ping.MarkerJumpTarget marker : ping.snapshotMarkerTargets()) {
            if (marker == null) continue;

            String markerServer = normalizeServerKey(marker.server());
            if (!serverKey.isBlank() && !markerServer.isBlank() && !serverKey.equals(markerServer)) continue;
            if (!Double.isFinite(marker.x()) || !Double.isFinite(marker.y()) || !Double.isFinite(marker.z())) continue;
            totalCount++;

            String sender = normalizePingSenderName(marker.sender());
            String displayName = formatPingDisplayLabel(
                sender,
                marker.dimension(),
                marker.x(),
                marker.y(),
                marker.z(),
                infoMode
            );
            mapMarkers.add(new XaeroSyncWaypoints.MapWaypointMarker(
                "ping:" + safe(marker.id()),
                displayName,
                marker.dimension(),
                marker.x(),
                marker.y(),
                marker.z(),
                marker.iconPath(),
                false
            ));

        }

        return new PingMarkerRouting(trackedMarkers, mapMarkers, totalCount, 0, mapMarkers.size(), 0);
    }

    private static synchronized void updateTrackedPingRenderSnapshot(Collection<XaeroSyncWaypoints.PlayerMarker> trackedMarkers) {
        trackedPingRenderMarkers.clear();
        if (trackedMarkers == null) return;

        for (XaeroSyncWaypoints.PlayerMarker marker : trackedMarkers) {
            if (marker == null) continue;
            String uuidRaw = safe(marker.uuid()).trim();
            UUID uuid = null;
            try {
                if (!uuidRaw.isBlank()) uuid = UUID.fromString(uuidRaw);
            } catch (Throwable ignored) {
            }
            if (uuid == null) uuid = resolveOnlinePlayerUuidByName(marker.name());
            if (uuid == null) continue;
            trackedPingRenderMarkers.put(uuid, new PingRenderMarker(
                normalizeKey(marker.name()).isBlank() ? "Marker" : marker.name(),
                marker.dimension(),
                marker.x(),
                marker.y(),
                marker.z()
            ));
        }
    }

    private static UUID resolveOnlinePlayerUuidByName(String rawName) {
        String targetName = normalizeKey(rawName);
        if (targetName.isBlank()) return null;
        if (MeteorClient.mc == null || MeteorClient.mc.getNetworkHandler() == null) return null;
        for (PlayerListEntry entry : MeteorClient.mc.getNetworkHandler().getPlayerList()) {
            if (entry == null || entry.getProfile() == null || entry.getProfile().getId() == null) continue;
            String entryName = normalizeKey(entry.getProfile().getName());
            if (entryName.equals(targetName)) return entry.getProfile().getId();
        }
        return null;
    }

    private static synchronized void clearTrackedPingRenderSnapshot() {
        trackedPingRenderMarkers.clear();
    }

    public static synchronized String resolveTrackedPingDisplayName(UUID uuid, String fallbackName) {
        String safeFallback = safe(fallbackName);
        if (uuid == null) return formatPlayerTrackedLabel(safeFallback);
        PingRenderMarker marker = trackedPingRenderMarkers.get(uuid);
        if (marker == null) return formatPlayerTrackedLabel(safeFallback);
        String sender = safe(marker.name()).trim();
        if (sender.isBlank()) sender = safeFallback.isBlank() ? "Marker" : safeFallback;
        return formatPingDisplayLabel(sender, marker.dimension(), marker.x(), marker.y(), marker.z());
    }

    private static String formatPlayerTrackedLabel(String fallbackName) {
        String name = safe(fallbackName).trim();
        if (name.isBlank()) return "[PLAYER] Player";
        if (name.regionMatches(true, 0, "[PLAYER]", 0, 8)) {
            String stripped = safe(name.substring(8)).trim();
            return stripped.isBlank() ? "[PLAYER] Player" : "[PLAYER] " + stripped;
        }
        return "[PLAYER] " + name;
    }

    private static String formatPingDisplayLabel(String sender, String dimension, double x, double y, double z) {
        return formatPingDisplayLabel(sender, dimension, x, y, z, resolvePingInfoMode());
    }

    private static String formatPingDisplayLabel(String sender, String dimension, double x, double y, double z, Ping.InfoMode mode) {
        String safeSender = safe(sender).trim();
        if (safeSender.isBlank()) safeSender = "Marker";
        String coords = formatCoords(x, y, z);
        return buildPingLabelByMode(safeSender, coords, mode);
    }

    private static String buildPingLabelByMode(String sender, String coords, Ping.InfoMode mode) {
        Ping.InfoMode effectiveMode = mode == null ? Ping.InfoMode.Distance : mode;
        return switch (effectiveMode) {
            case Distance -> "[PING] " + sender;
            case Coords -> "[PING] " + sender + " | " + coords;
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

    private static String formatCoords(double x, double y, double z) {
        return String.format(Locale.ROOT, "%d %d %d", Math.round(x), Math.round(y), Math.round(z));
    }

    private PresenceRenderPosition resolvePresenceRenderPosition(PresenceMarker presence, long nowMs) {
        double x = presence.x();
        double y = clampY(presence.y());
        double z = presence.z();

        // Hard sync mode: no prediction/smoothing, render exact network coordinates immediately.
        PresenceMotionState state = new PresenceMotionState(
            presence.id(),
            normalizeKey(presence.dimension()),
            presence.sequence(),
            presence.updatedAtMs(),
            nowMs,
            x,
            y,
            z,
            presence.vx(),
            presence.vy(),
            presence.vz(),
            nowMs,
            x,
            y,
            z
        );
        presenceMotion.put(presence.id(), state);
        return new PresenceRenderPosition(x, y, z);
    }

    private void cleanupPresenceMotion(Set<String> activeIds, long nowMs) {
        if (presenceMotion.isEmpty()) return;
        presenceMotion.entrySet().removeIf(entry -> {
            PresenceMotionState state = entry.getValue();
            if (state == null) return true;
            if (activeIds != null && activeIds.contains(entry.getKey())) return false;
            return (nowMs - state.sourceReceivedAtMs()) > (PRESENCE_STALE_MS * 2L);
        });
    }

    private void renderOverlay(Render2DEvent event) {
        renderOverlay(mc.currentScreen, event.drawContext, scaledMouseX(), scaledMouseY(), Double.NaN, Double.NaN, Double.NaN);
    }

    private void renderOverlay(Screen screen, net.minecraft.client.gui.DrawContext drawContext, int mouseX, int mouseY) {
        renderOverlay(screen, drawContext, mouseX, mouseY, Double.NaN, Double.NaN, Double.NaN);
    }

    private void renderOverlay(
        Screen screen,
        net.minecraft.client.gui.DrawContext drawContext,
        int mouseX,
        int mouseY,
        double forcedCameraX,
        double forcedCameraZ,
        double forcedScale
    ) {
        if (!isOverlayVisible(screen)) {
            menuOpen = false;
            return;
        }

        List<JumpEntry> rows = collectJumpEntries();
        OverlayLayout layout = computeLayout(rows.size());

        boolean buttonHovered = mouseX >= layout.buttonX()
            && mouseX <= layout.buttonX() + BUTTON_W
            && mouseY >= layout.buttonY()
            && mouseY <= layout.buttonY() + BUTTON_H;
        drawXaeroToolbarButton(drawContext, layout.buttonX(), layout.buttonY(), buttonHovered, menuOpen);

        if (buttonHovered) {
            int tipW = 74;
            int tipH = 12;
            int tipX = Math.max(4, layout.buttonX() - tipW - 4);
            int tipY = layout.buttonY() + 2;
            drawContext.fill(tipX, tipY, tipX + tipW, tipY + tipH, DEVILS_TOOLTIP_BACKGROUND);
            drawBoxOutline(drawContext, tipX, tipY, tipW, tipH, DEVILS_ACCENT_BORDER);
            drawContext.drawText(mc.textRenderer, "Devils Sync", tipX + 4, tipY + 2, DEVILS_TEXT_PRIMARY, false);
        }

        if (!menuOpen) return;

        drawContext.fill(layout.panelX(), layout.panelY(), layout.panelX() + PANEL_W, layout.panelY() + layout.panelH(), DEVILS_PANEL_BACKGROUND);
        drawBoxOutline(drawContext, layout.panelX(), layout.panelY(), PANEL_W, layout.panelH(), DEVILS_ACCENT_BORDER);

        if (rows.isEmpty()) {
            drawContext.drawText(mc.textRenderer, "No synced players", layout.panelX() + 6, layout.panelY() + 4, DEVILS_TEXT_MUTED, false);
            return;
        }

        int shownRows = Math.min(layout.shownRows(), rows.size());
        for (int i = 0; i < shownRows; i++) {
            JumpEntry entry = rows.get(i);
            int rowY = layout.panelY() + 2 + i * ROW_H;
            boolean hovered = mouseX >= layout.panelX() + 1
                && mouseX <= layout.panelX() + PANEL_W - 1
                && mouseY >= rowY
                && mouseY <= rowY + ROW_H - 1;

            if (hovered) drawContext.fill(layout.panelX() + 1, rowY, layout.panelX() + PANEL_W - 1, rowY + ROW_H - 1, DEVILS_ROW_HOVER);

            int textX = layout.panelX() + 5;
            if (entry.type() == JumpType.PLAYER) {
                drawPlayerHead(drawContext, entry, textX, rowY + 2);
                textX += 12;
            } else {
                drawMarkerIcon(drawContext, textX, rowY + 2, 8, resolveEntryIconPath(entry));
                textX += 12;
            }

            String label = formatEntry(entry);
            drawContext.drawText(mc.textRenderer, label, textX, rowY + 3, hovered ? DEVILS_TEXT_PRIMARY : DEVILS_TEXT_SECONDARY, false);
        }
    }

    private void drawXaeroToolbarButton(net.minecraft.client.gui.DrawContext drawContext, int x, int y, boolean hovered, boolean pressed) {
        int background = pressed
            ? DEVILS_BUTTON_BACKGROUND_ACTIVE
            : hovered ? DEVILS_BUTTON_BACKGROUND_HOVER : DEVILS_BUTTON_BACKGROUND;
        drawContext.fill(x, y, x + BUTTON_W, y + BUTTON_H, background);
        drawBoxOutline(drawContext, x, y, BUTTON_W, BUTTON_H, DEVILS_ACCENT_BORDER);

        int iconX = x + (BUTTON_W - 16) / 2;
        int iconY = y + (BUTTON_H - 16) / 2;
        if (pressed) iconY -= 1;

        if (hovered) drawContext.fill(iconX - 1, iconY - 1, iconX + 17, iconY + 17, 0x33FFFFFF);

        int tint = pressed ? 0xFFE6E6E6 : 0xFFFCFCFC;
        drawContext.drawTexture(
            RenderPipelines.GUI_TEXTURED,
            XAERO_SYNC_ICON_TEXTURE,
            iconX,
            iconY,
            DEVILS_MAP_ICON_U,
            DEVILS_MAP_ICON_V,
            16,
            16,
            DEVILS_MAP_ICON_REGION_W,
            DEVILS_MAP_ICON_REGION_H,
            DEVILS_MAP_ICON_SOURCE_SIZE,
            DEVILS_MAP_ICON_SOURCE_SIZE,
            tint
        );
    }

    private boolean handleOverlayClick(KeyAction action, int button, Object eventRef) {
        if (action != KeyAction.Press || button != 0) return false;
        boolean handled = handleOverlayClick(mc.currentScreen, button, scaledMouseX(), scaledMouseY());
        if (handled) {
            cancelInputEvent(eventRef);
        }
        return handled;
    }

    private boolean handleOverlayClick(Screen screen, int button, double mouseXRaw, double mouseYRaw) {
        if (button != 0) return false;
        if (!isOverlayVisible(screen)) return false;

        List<JumpEntry> rows = collectJumpEntries();
        OverlayLayout layout = computeLayout(rows.size());
        int mouseX = (int) Math.round(mouseXRaw);
        int mouseY = (int) Math.round(mouseYRaw);

        boolean inButton = mouseX >= layout.buttonX()
            && mouseX <= layout.buttonX() + BUTTON_W
            && mouseY >= layout.buttonY()
            && mouseY <= layout.buttonY() + BUTTON_H;
        if (inButton) {
            menuOpen = !menuOpen;
            return true;
        }

        if (!menuOpen) return false;

        boolean inPanel = mouseX >= layout.panelX()
            && mouseX <= layout.panelX() + PANEL_W
            && mouseY >= layout.panelY()
            && mouseY <= layout.panelY() + layout.panelH();
        if (!inPanel) {
            menuOpen = false;
            return false;
        }

        int shownRows = Math.min(layout.shownRows(), rows.size());
        for (int i = 0; i < shownRows; i++) {
            int rowY = layout.panelY() + 2 + i * ROW_H;
            if (mouseY < rowY || mouseY > rowY + ROW_H - 1) continue;

            jumpToEntry(rows.get(i));
            menuOpen = false;
            return true;
        }

        return true;
    }

    private List<JumpEntry> collectJumpEntries() {
        ArrayList<JumpEntry> rows = new ArrayList<>();

        long now = System.currentTimeMillis();
        String selfName = normalizeKey(currentUsername());
        String selfUuid = normalizeKey(currentPlayerUuid());
        String serverKey = normalizeServerKey(currentServerKey());

        for (PresenceMarker presence : syncedPresence.values()) {
            if (presence == null) continue;
            if ((now - presence.updatedAtMs()) > PRESENCE_STALE_MS) continue;

            String sender = normalizeKey(presence.sender());
            if (sender.isBlank()) continue;
            if (!selfUuid.isBlank() && selfUuid.equals(normalizeKey(presence.playerUuid()))) continue;
            if (!selfName.isBlank() && selfName.equals(sender)) continue;

            String markerServer = normalizeServerKey(presence.server());
            if (!serverKey.isBlank() && !markerServer.isBlank() && !serverKey.equals(markerServer)) continue;
            rows.add(new JumpEntry(JumpType.PLAYER, presence.id(), presence.sender(), presence.playerUuid(), presence.dimension(), presence.x(), presence.y(), presence.z(), presence.updatedAtMs(), ""));
        }

        Ping ping = Modules.get().get(Ping.class);
        if (ping != null) {
            for (Ping.MarkerJumpTarget marker : ping.snapshotMarkerTargets()) {
                if (marker == null) continue;
                String markerServer = normalizeServerKey(marker.server());
                if (!serverKey.isBlank() && !markerServer.isBlank() && !serverKey.equals(markerServer)) continue;
                String name = normalizePingSenderName(marker.sender());
                rows.add(new JumpEntry(JumpType.PING, marker.id(), name, "", marker.dimension(), marker.x(), marker.y(), marker.z(), marker.createdAtMs(), marker.iconPath()));
            }
        }

        rows.sort(Comparator
            .comparing(JumpEntry::type)
            .thenComparing(Comparator.comparingLong(JumpEntry::updatedAtMs).reversed())
            .thenComparing(entry -> normalizeKey(entry.name())));
        return rows;
    }

    private String formatEntry(JumpEntry entry) {
        return switch (entry.type()) {
            case PLAYER -> formatPlayerLabelWithDistance(entry);
            case PING -> formatPingLabelText(entry);
            case MARKER -> formatMapMarkerLabel(entry);
        };
    }

    private String resolveEntryIconPath(JumpEntry entry) {
        if (entry == null) {
            String configured = MapIconManager.normalizeIconPath(markerIconPath.get());
            return configured.isBlank() ? DEFAULT_DEVILS_MAP_ICON_PATH : configured;
        }
        if (entry.type() == JumpType.PLAYER) return "";

        String explicit = MapIconManager.normalizeIconPath(entry.iconPath());
        if (!explicit.isBlank()) return explicit;
        String configured = MapIconManager.normalizeIconPath(markerIconPath.get());
        return configured.isBlank() ? DEFAULT_DEVILS_MAP_ICON_PATH : configured;
    }

    private void renderMapEntryLabels(
        Screen screen,
        net.minecraft.client.gui.DrawContext drawContext,
        List<JumpEntry> rows,
        double forcedCameraX,
        double forcedCameraZ,
        double forcedScale
    ) {
        if (screen == null || drawContext == null || rows == null || rows.isEmpty()) return;

        MapProjection projection = readMapProjection(screen, forcedCameraX, forcedCameraZ, forcedScale);
        if (projection == null) return;

        String mapDimension = normalizeDimensionForCompare(projection.dimensionId());
        int rendered = 0;

        for (JumpEntry entry : rows) {
            if (entry == null) continue;

            String entryDim = normalizeDimensionForCompare(entry.dimension());
            if (!mapDimension.isBlank() && !entryDim.isBlank() && !mapDimension.equals(entryDim)) continue;

            double relX = (entry.x() - projection.cameraX()) * projection.scale();
            double relZ = (entry.z() - projection.cameraZ()) * projection.scale();
            int rawScreenX = (int) Math.round((projection.width() / 2.0) + relX);
            int rawScreenY = (int) Math.round((projection.height() / 2.0) + relZ);
            int labelScreenY = rawScreenY - DEVILS_MAP_LABEL_OFFSET_Y;

            if (rawScreenX < -DEVILS_MAP_LABEL_MARGIN || rawScreenX > projection.width() + DEVILS_MAP_LABEL_MARGIN) continue;
            if (rawScreenY < -DEVILS_MAP_LABEL_MARGIN || rawScreenY > projection.height() + DEVILS_MAP_LABEL_MARGIN) continue;

            int pointX = clamp(rawScreenX, projection.mapMinX(), projection.mapMaxX());
            int pointY = clamp(rawScreenY, projection.mapMinY(), projection.mapMaxY());
            int labelAnchorY = clamp(labelScreenY, projection.mapMinY(), projection.mapMaxY());

            switch (entry.type()) {
                case PLAYER -> {
                    drawMapPlayerPoint(drawContext, entry, pointX, pointY);
                    drawMapPlayerLabel(drawContext, entry, pointX, labelAnchorY, projection.mapMaxX(), projection.mapMaxY());
                }
                case PING -> {
                    drawMapMarkerPoint(drawContext, pointX, pointY, resolveEntryIconPath(entry));
                    drawMapPingLabel(drawContext, entry, pointX, labelAnchorY, projection.mapMaxX(), projection.mapMaxY());
                }
                case MARKER -> {
                    drawMapMarkerPoint(drawContext, pointX, pointY, resolveEntryIconPath(entry));
                    drawMapMarkerLabel(drawContext, entry, pointX, labelAnchorY, projection.mapMaxX(), projection.mapMaxY());
                }
            }

            rendered++;
            if (rendered >= DEVILS_MAP_LABEL_MAX) break;
        }
    }

    private MapProjection readMapProjection(Screen screen, double forcedCameraX, double forcedCameraZ, double forcedScale) {
        if (screen == null) return null;

        Double cameraX = Double.isFinite(forcedCameraX) ? forcedCameraX : readFieldDouble(screen, "cameraX");
        Double cameraZ = Double.isFinite(forcedCameraZ) ? forcedCameraZ : readFieldDouble(screen, "cameraZ");
        Double scale = (Double.isFinite(forcedScale) && forcedScale > 0) ? forcedScale : readFieldDouble(screen, "scale");

        if (scale == null || !Double.isFinite(scale) || scale <= 0) {
            Double userScale = tryInvokeDouble(screen, "getUserScale");
            if (userScale != null && Double.isFinite(userScale) && userScale > 0) {
                int shortSide = 1080;
                if (mc.getWindow() != null) shortSide = Math.min(mc.getWindow().getScaledWidth(), mc.getWindow().getScaledHeight());
                double scaleMultiplier = shortSide <= 1080 ? 1.0 : (shortSide / 1080.0);
                scale = userScale * scaleMultiplier;
            }
        }
        if (scale == null || !Double.isFinite(scale) || scale <= 0) scale = 1.0;

        if (cameraX == null || !Double.isFinite(cameraX)) cameraX = guessCameraCoordinate(screen, true);
        if (cameraZ == null || !Double.isFinite(cameraZ)) cameraZ = guessCameraCoordinate(screen, false);
        if (cameraX == null || !Double.isFinite(cameraX) || cameraZ == null || !Double.isFinite(cameraZ)) return null;

        String mapDimension = "";
        Object mapProcessor = invokeNoArg(screen, "getMapProcessor");
        Object mapWorld = mapProcessor == null ? null : invokeNoArg(mapProcessor, "getMapWorld");
        Object currentDimensionId = mapWorld == null ? null : invokeNoArg(mapWorld, "getCurrentDimensionId");
        if (currentDimensionId != null) {
            Object resolved = invokeNoArg(currentDimensionId, "getValue");
            if (resolved == null) resolved = invokeNoArg(currentDimensionId, "method_29177");
            mapDimension = safe(resolved == null ? currentDimensionId.toString() : resolved.toString());
        }

        int mapMinX = 2;
        int mapMinY = 2;
        int mapMaxX = Math.max(mapMinX + 1, screen.width - XAERO_TOOLBAR_WIDTH - 2);
        int mapMaxY = Math.max(mapMinY + 1, screen.height - 2);

        return new MapProjection(cameraX, cameraZ, scale, screen.width, screen.height, mapDimension, mapMinX, mapMinY, mapMaxX, mapMaxY);
    }

    private void drawMapPlayerPoint(net.minecraft.client.gui.DrawContext drawContext, JumpEntry entry, int centerX, int centerY) {
        int iconSize = DEVILS_MAP_POINT_ICON;
        int x = centerX - iconSize / 2;
        int y = centerY - iconSize / 2;
        drawPlayerHead(drawContext, entry, x, y);
        drawBoxOutline(drawContext, x - 1, y - 1, iconSize + 2, iconSize + 2, DEVILS_ACCENT_BORDER);
    }

    private void drawMapPlayerLabel(net.minecraft.client.gui.DrawContext drawContext, JumpEntry entry, int centerX, int centerY, int screenW, int screenH) {
        String text = formatMapPlayerLabel(entry);
        int textW = mc.textRenderer.getWidth(text);
        int iconSize = DEVILS_MAP_LABEL_ICON;
        int h = Math.max(iconSize, mc.textRenderer.fontHeight) + 4;
        int w = iconSize + 2 + textW + 6;
        int x = centerX + DEVILS_MAP_POINT_ICON / 2 + 4;
        int y = centerY - h / 2;
        if (x + w > screenW - 4) x = screenW - w - 4;
        if (x < 4) x = 4;
        if (y + h > screenH - 4) y = screenH - h - 4;
        if (y < 4) y = 4;

        drawContext.fill(x, y, x + w, y + h, DEVILS_MAP_LABEL_BG);
        drawBoxOutline(drawContext, x, y, w, h, DEVILS_ACCENT_BORDER);
        drawPlayerHead(drawContext, entry, x + 2, y + (h - iconSize) / 2 + 1);
        drawContext.drawText(mc.textRenderer, text, x + 2 + iconSize + 2, y + (h - mc.textRenderer.fontHeight) / 2, DEVILS_TEXT_PRIMARY, false);
    }

    private void drawMapMarkerPoint(net.minecraft.client.gui.DrawContext drawContext, int centerX, int centerY, String iconPath) {
        int iconSize = DEVILS_MAP_POINT_ICON;
        int x = centerX - iconSize / 2;
        int y = centerY - iconSize / 2;
        drawMarkerIcon(drawContext, x, y, iconSize, iconPath);
        drawBoxOutline(drawContext, x - 1, y - 1, iconSize + 2, iconSize + 2, DEVILS_ACCENT_BORDER);
    }

    private void drawMapMarkerLabel(net.minecraft.client.gui.DrawContext drawContext, JumpEntry entry, int centerX, int centerY, int screenW, int screenH) {
        String text = formatMapMarkerLabel(entry);
        int textW = mc.textRenderer.getWidth(text);
        int iconSize = DEVILS_MAP_LABEL_ICON;
        int h = Math.max(mc.textRenderer.fontHeight, iconSize) + 6;
        int w = iconSize + 2 + textW + 8;
        int x = centerX + DEVILS_MAP_POINT_ICON / 2 + 4;
        int y = centerY - h / 2;
        if (x + w > screenW - 4) x = screenW - w - 4;
        if (x < 4) x = 4;
        if (y + h > screenH - 4) y = screenH - h - 4;
        if (y < 4) y = 4;

        drawContext.fill(x, y, x + w, y + h, DEVILS_MAP_LABEL_BG);
        drawBoxOutline(drawContext, x, y, w, h, DEVILS_ACCENT_BORDER);
        drawMarkerIcon(drawContext, x + 3, y + (h - iconSize) / 2, iconSize, resolveEntryIconPath(entry));
        drawContext.drawText(mc.textRenderer, text, x + iconSize + 6, y + (h - mc.textRenderer.fontHeight) / 2, DEVILS_TEXT_SECONDARY, false);
    }

    private void drawMapPingLabel(net.minecraft.client.gui.DrawContext drawContext, JumpEntry entry, int centerX, int centerY, int screenW, int screenH) {
        String text = formatEntry(entry);
        int textW = mc.textRenderer.getWidth(text);
        int iconSize = DEVILS_MAP_LABEL_ICON;
        int h = Math.max(mc.textRenderer.fontHeight, iconSize) + 6;
        int w = iconSize + 2 + textW + 8;
        int x = centerX + DEVILS_MAP_POINT_ICON / 2 + 4;
        int y = centerY - h / 2;
        if (x + w > screenW - 4) x = screenW - w - 4;
        if (x < 4) x = 4;
        if (y + h > screenH - 4) y = screenH - h - 4;
        if (y < 4) y = 4;

        drawContext.fill(x, y, x + w, y + h, DEVILS_MAP_LABEL_BG);
        drawBoxOutline(drawContext, x, y, w, h, DEVILS_ACCENT_BORDER);
        drawMarkerIcon(drawContext, x + 3, y + (h - iconSize) / 2, iconSize, resolveEntryIconPath(entry));
        drawContext.drawText(mc.textRenderer, text, x + iconSize + 6, y + (h - mc.textRenderer.fontHeight) / 2, DEVILS_TEXT_PRIMARY, false);
    }

    private void drawMarkerIcon(net.minecraft.client.gui.DrawContext drawContext, int x, int y, int size, String iconPath) {
        if (MapIconManager.drawCustomIcon(drawContext, iconPath, x, y, size, 0xFFFFFFFF)) return;
        drawContext.drawTexture(
            RenderPipelines.GUI_TEXTURED,
            XAERO_SYNC_ICON_TEXTURE,
            x,
            y,
            DEVILS_MARKER_ICON_U,
            DEVILS_MARKER_ICON_V,
            size,
            size,
            DEVILS_MARKER_ICON_REGION_W,
            DEVILS_MARKER_ICON_REGION_H,
            DEVILS_MAP_ICON_SOURCE_SIZE,
            DEVILS_MAP_ICON_SOURCE_SIZE,
            0xFFFFFFFF
        );
    }

    private String formatMapMarkerLabel(JumpEntry entry) {
        String sender = normalizePingSenderName(entry == null ? "" : entry.name());
        return formatPingMapLabel(entry, sender);
    }

    private String normalizePingSenderName(String raw) {
        String value = safe(raw).trim();
        if (value.isBlank()) return "Marker";

        // If a formatted marker leaked into sender, keep only nickname segment.
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
        value = value.replace("[Devils Sync]", "").trim();
        if (value.isBlank()) return "Marker";
        return value;
    }

    private String formatPlayerLabelText(String rawName) {
        String name = safe(rawName).trim();
        if (name.isBlank()) return "[PLAYER] Player";
        if (name.regionMatches(true, 0, "[PLAYER]", 0, 8)) {
            String stripped = safe(name.substring(8)).trim();
            return stripped.isBlank() ? "[PLAYER] Player" : "[PLAYER] " + stripped;
        }
        if (name.regionMatches(true, 0, "[P]", 0, 3)) {
            String stripped = safe(name.substring(3)).trim();
            return stripped.isBlank() ? "[PLAYER] Player" : "[PLAYER] " + stripped;
        }
        return "[PLAYER] " + name;
    }

    private String formatPingLabelText(JumpEntry entry) {
        String name = normalizePingSenderName(entry == null ? "" : entry.name());
        return formatPingMapLabel(entry, name);
    }

    private String formatPingMapLabel(JumpEntry entry, String sender) {
        String safeSender = safe(sender).trim();
        if (safeSender.isBlank()) safeSender = "Marker";
        String coords = entry == null ? "-- -- --" : formatCoords(entry.x(), entry.y(), entry.z());
        return buildPingLabelByMode(safeSender, coords, resolvePingInfoMode());
    }

    private String formatMapPlayerLabel(JumpEntry entry) {
        return formatPlayerLabelWithDistance(entry);
    }

    private String formatPlayerLabelWithDistance(JumpEntry entry) {
        String base = formatPlayerLabelText(entry == null ? "" : entry.name());
        String distance = formatMapDistance(entry).toUpperCase(Locale.ROOT);
        return base + " - " + distance;
    }

    private String formatMapDistance(JumpEntry entry) {
        if (mc.player == null || mc.world == null || entry == null) return "--";

        String playerDimension = normalizeKey(mc.world.getRegistryKey().getValue().toString());
        String markerDimension = normalizeKey(entry.dimension());
        if (!markerDimension.isBlank() && !playerDimension.isBlank() && !markerDimension.equals(playerDimension)) return "~";

        double dx = entry.x() - mc.player.getX();
        double dy = entry.y() - mc.player.getY();
        double dz = entry.z() - mc.player.getZ();
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (!Double.isFinite(distance)) return "--";

        if (distance >= 1000) return String.format(Locale.ROOT, "%.1fk", distance / 1000.0);
        if (distance >= 100) return String.format(Locale.ROOT, "%.0fm", distance);
        return String.format(Locale.ROOT, "%.1fm", distance);
    }

    private void drawPlayerHead(net.minecraft.client.gui.DrawContext drawContext, JumpEntry entry, int x, int y) {
        PlayerListEntry listEntry = resolvePlayerListEntry(entry);
        if (listEntry != null) {
            try {
                PlayerSkinDrawer.draw(drawContext, listEntry.getSkinTextures(), x, y, 8, 0xFFFFFFFF);
                return;
            } catch (Throwable ignored) {
            }
        }

        String configured = MapIconManager.normalizeIconPath(playerFallbackIconPath.get());
        drawMarkerIcon(drawContext, x, y, 8, configured.isBlank() ? DEFAULT_DEVILS_MAP_ICON_PATH : configured);
    }

    private PlayerListEntry resolvePlayerListEntry(JumpEntry entry) {
        if (mc.getNetworkHandler() == null || entry == null) return null;

        String uuidRaw = safe(entry.playerUuid());
        if (!uuidRaw.isBlank()) {
            try {
                PlayerListEntry byUuid = mc.getNetworkHandler().getPlayerListEntry(UUID.fromString(uuidRaw));
                if (byUuid != null) return byUuid;
            } catch (Throwable ignored) {
            }
        }

        String needle = normalizeKey(entry.name());
        for (PlayerListEntry listEntry : mc.getNetworkHandler().getPlayerList()) {
            if (listEntry == null || listEntry.getProfile() == null || listEntry.getProfile().getName() == null) continue;
            if (needle.equals(normalizeKey(listEntry.getProfile().getName()))) return listEntry;
        }
        return null;
    }

    private Double tryInvokeDouble(Object owner, String methodName) {
        if (owner == null || methodName == null || methodName.isBlank()) return null;
        try {
            Method method = owner.getClass().getMethod(methodName);
            method.setAccessible(true);
            Object value = method.invoke(owner);
            if (value instanceof Number number) return number.doubleValue();
        } catch (Throwable ignored) {
        }
        return null;
    }

    private Double guessCameraCoordinate(Screen screen, boolean xAxis) {
        double fallback = 0.0;
        if (mc.player != null) fallback = xAxis ? mc.player.getX() : mc.player.getZ();

        double bestValue = fallback;
        double bestScore = Double.POSITIVE_INFINITY;

        Class<?> cursor = screen.getClass();
        while (cursor != null) {
            for (Field field : cursor.getDeclaredFields()) {
                if (field.getType() != double.class && field.getType() != Double.class) continue;
                try {
                    field.setAccessible(true);
                    Object raw = field.get(screen);
                    if (!(raw instanceof Number number)) continue;

                    double value = number.doubleValue();
                    if (!Double.isFinite(value)) continue;
                    if (Math.abs(value) < 32.0) continue;
                    if (Math.abs(value + 1.0) < 0.0001) continue;

                    double score = Math.abs(value - fallback);
                    if (score < bestScore) {
                        bestScore = score;
                        bestValue = value;
                    }
                } catch (Throwable ignored) {
                }
            }
            cursor = cursor.getSuperclass();
        }

        return bestValue;
    }

    private static int clamp(int value, int min, int max) {
        if (value < min) return min;
        if (value > max) return max;
        return value;
    }

    private String normalizeDimensionForCompare(String raw) {
        String value = normalizeKey(raw);
        if (value.isBlank()) return "";

        String best = "";
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) != ':') continue;

            int start = i - 1;
            while (start >= 0 && isDimLeftChar(value.charAt(start))) start--;
            start++;
            if (start >= i) continue;

            int end = i + 1;
            while (end < value.length() && isDimRightChar(value.charAt(end))) end++;
            if (end <= i + 1) continue;

            String candidate = value.substring(start, end);
            if (candidate.startsWith("minecraft:")) return candidate;
            best = candidate;
        }
        return best.isBlank() ? value : best;
    }

    private static boolean isDimLeftChar(char ch) {
        return (ch >= 'a' && ch <= 'z')
            || (ch >= '0' && ch <= '9')
            || ch == '_'
            || ch == '-'
            || ch == '.';
    }

    private static boolean isDimRightChar(char ch) {
        return isDimLeftChar(ch) || ch == '/';
    }

    private OverlayLayout computeLayout(int rowCount) {
        Screen screen = mc.currentScreen;
        int width = screen == null ? (mc.getWindow() == null ? 320 : mc.getWindow().getScaledWidth()) : screen.width;
        int height = screen == null ? (mc.getWindow() == null ? 240 : mc.getWindow().getScaledHeight()) : screen.height;

        int buttonX = Math.max(4, width - BUTTON_W - 6);
        int buttonY = 4;
        ToolbarAnchor anchor = findToolbarAnchor(width, height);
        if (anchor != null) {
            buttonX = anchor.x();
            buttonY = anchor.y();
        }
        buttonX = Math.max(4, Math.min(width - BUTTON_W - 4, buttonX));
        buttonY = Math.max(4, Math.min(height - BUTTON_H - 4, buttonY));

        int shownRows = Math.min(Math.max(1, maxRows.get()), Math.max(1, rowCount));
        int panelH = shownRows * ROW_H + 4;
        int panelX = Math.max(4, buttonX + BUTTON_W - PANEL_W);
        if (panelX + PANEL_W > width - 4) panelX = Math.max(4, width - PANEL_W - 4);

        int panelY = buttonY - panelH - 2;
        if (panelY < 4) panelY = Math.min(height - panelH - 4, buttonY + BUTTON_H + 2);
        if (panelY < 4) panelY = 4;

        return new OverlayLayout(buttonX, buttonY, panelX, panelY, panelH, shownRows);
    }

    private ToolbarAnchor findToolbarAnchor(int width, int height) {
        if (mc.currentScreen == null) return null;

        ToolbarAnchor knownAnchor = findAnchorFromKnownXaeroButtons(mc.currentScreen, width, height);
        if (knownAnchor != null) return knownAnchor;

        Iterable<?> elements = tryGetScreenChildren(mc.currentScreen);
        if (elements == null) return null;

        int bestX = -1;
        int bestW = BUTTON_W;
        int topY = Integer.MAX_VALUE;
        int topH = 0;
        for (Object element : elements) {
            if (element == null) continue;
            Integer x = readWidgetX(element);
            Integer y = readWidgetY(element);
            Integer w = readWidgetWidth(element);
            Integer h = readWidgetHeight(element);
            if (x == null || y == null || w == null || h == null) continue;
            if (w < 12 || w > 30 || h < 12 || h > 30) continue;
            if (x < (width - 90)) continue;
            if (y < topY) {
                bestX = x;
                bestW = w;
                topY = y;
                topH = h;
            }
        }

        if (bestX < 0 || topY == Integer.MAX_VALUE) return null;

        // Prefer placing above the top-right toolbar button so the Devils icon stays with the main stack.
        int y = topY - BUTTON_H - 2;
        if (y < 4) y = topY + topH + 2;
        if (y + BUTTON_H > (height - 4)) y = Math.max(4, height - BUTTON_H - 4);
        if (y < 4) y = 4;
        int x = Math.max(4, Math.min(width - BUTTON_W - 4, bestX + Math.max(0, (bestW - BUTTON_W) / 2)));
        return new ToolbarAnchor(x, y);
    }

    private ToolbarAnchor findAnchorFromKnownXaeroButtons(Screen screen, int width, int height) {
        if (screen == null) return null;

        // Button field names are taken from XaeroWorldMap sources (GuiMap.method_25426).
        String[] xaeroButtonFields = {
            // Xaero map switching module button.
            "switchingButton",
            // XaeroPlus (world map extra stack, includes [XP] dimension switches)
            "switchToNetherButton",
            "switchToOverworldButton",
            "switchToEndButton",
            "coordinateGotoButton",
            "followButton",
            "startDrawingButton",
            "caveModeButton",
            "dimensionToggleButton",
            "zoomInButton",
            "zoomOutButton",
            "keybindingsButton",
            "exportButton",
            "claimsButton",
            "radarButton",
            "playersButton",
            "waypointsButton"
        };

        int bestX = -1;
        int bestW = BUTTON_W;
        int topY = Integer.MAX_VALUE;
        int topH = 0;

        for (String fieldName : xaeroButtonFields) {
            Object button = tryReadFieldValue(screen, fieldName);
            if (button == null) continue;

            Integer x = readWidgetX(button);
            Integer y = readWidgetY(button);
            Integer w = readWidgetWidth(button);
            Integer h = readWidgetHeight(button);
            if (x == null || y == null || w == null || h == null) continue;
            if (w < 12 || w > 40 || h < 12 || h > 40) continue;
            if (x < (width - 90)) continue;

            if (y < topY || (y.equals(topY) && x > bestX)) {
                bestX = x;
                bestW = w;
                topY = y;
                topH = h;
            }
        }

        if (bestX < 0 || topY == Integer.MAX_VALUE) return null;

        int y = topY - BUTTON_H - 2;
        if (y < 4) y = topY + topH + 2;
        if (y < 4) y = 4;
        if (y + BUTTON_H > (height - 4)) y = Math.max(4, height - BUTTON_H - 4);
        int x = Math.max(4, Math.min(width - BUTTON_W - 4, bestX + Math.max(0, (bestW - BUTTON_W) / 2)));
        return new ToolbarAnchor(x, y);
    }

    private boolean isOverlayVisible(Screen screen) {
        if (screen == null) return false;
        return isXaeroMapScreen(screen);
    }

    private boolean isXaeroMapScreen(Screen screen) {
        return screen != null && "xaero.map.gui.GuiMap".equals(screen.getClass().getName());
    }

    private void clearSyncRuntimeStateKeepingUi() {
        stopSyncStream();
        syncedPresence.clear();
        presenceMotion.clear();
        localPresenceCache = null;
        localPresenceSequence = 0;
        runtimeSyncDeviceId = "";
        syncInFlightCycles = 0;
        lastKnownSyncRevision = -1;
        lastSyncedFingerprint = "";
        lastSyncPullAttemptMs = 0;
        syncTickQueued = false;
        lastRenderSyncAttemptMs = 0L;
        syncStreamUseLegacyPath = false;
        syncStreamUnsupported = false;
        syncStreamUnsupportedUntilMs = 0;
        lastRuntimeDebugSnapshot = "";
        lastWaypointPipelineDebugSnapshot = "";
    }

    private void jumpToEntry(JumpEntry target) {
        if (target == null || mc.currentScreen == null) return;
        if (!"xaero.map.gui.GuiMap".equals(mc.currentScreen.getClass().getName())) return;

        try {
            Object guiMap = mc.currentScreen;
            Object mapProcessor = invokeNoArg(guiMap, "getMapProcessor");
            Object mapWorld = mapProcessor == null ? null : invokeNoArg(mapProcessor, "getMapWorld");
            RegistryKey<World> targetDim = parseDimensionKey(target.dimension());

            if (mapWorld != null && targetDim != null) {
                invokeSingleArgIfPresent(mapWorld, "setCustomDimensionId", targetDim);
                invokeSingleArgIfPresent(mapWorld, "setFutureDimensionId", targetDim);
                invokeNoArgIfPresent(mapWorld, "switchToFutureUnsynced");
            }

            Field cameraDestination = findField(guiMap.getClass(), "cameraDestination");
            if (cameraDestination != null) {
                cameraDestination.setAccessible(true);
                cameraDestination.set(guiMap, new int[]{(int) Math.round(target.x()), (int) Math.round(target.z())});
            }

            Method resize = findResizeMethod(guiMap.getClass());
            if (resize != null) {
                resize.setAccessible(true);
                resize.invoke(guiMap, mc, mc.getWindow().getScaledWidth(), mc.getWindow().getScaledHeight());
            }
        } catch (Throwable t) {
            AddonTemplate.LOG.debug("[Devils/XaeroSync] Jump failed.", t);
            error("XaeroSync jump failed: %s", t.getClass().getSimpleName());
        }
    }

    private SyncPullResult sendPullRequest(String baseUrl, String deviceId, String token, String signingKey, int timeoutSec, String encryptionKey, long knownRevision, int waitMs) throws Exception {
        JsonObject payload = new JsonObject();
        payload.addProperty("deviceId", deviceId);
        payload.addProperty("knownRevision", knownRevision);
        if (waitMs > 0) payload.addProperty("waitMs", waitMs);
        payload.addProperty("module", MODULE_NAMESPACE);

        HttpRequest request = buildSyncRequest(baseUrl, SYNC_PULL_PATH, payload.toString(), token, signingKey, timeoutSec);
        HttpResponse<String> response = syncHttpClient.send(request, HttpResponse.BodyHandlers.ofString());
        return parsePullResponse(response, encryptionKey);
    }

    private SyncPushResult sendPushRequest(
        String baseUrl,
        String deviceId,
        String token,
        String signingKey,
        int timeoutSec,
        String encryptionKey,
        long baseRevision,
        List<SyncXaeroData> snapshot
    ) throws Exception {
        JsonObject payload = new JsonObject();
        payload.addProperty("deviceId", deviceId);
        payload.addProperty("baseRevision", baseRevision);
        payload.addProperty("module", MODULE_NAMESPACE);
        payload.add("profiles", SyncCrypto.encryptProfiles(toJsonArray(snapshot), encryptionKey, MODULE_NAMESPACE));

        HttpRequest request = buildSyncRequest(baseUrl, SYNC_PUSH_PATH, payload.toString(), token, signingKey, timeoutSec);
        HttpResponse<String> response = syncHttpClient.send(request, HttpResponse.BodyHandlers.ofString());
        return parsePushResponse(response, encryptionKey);
    }

    private HttpRequest buildSyncRequest(String baseUrl, String path, String body, String token, String signingKey, int timeoutSec) {
        URI uri = URI.create(baseUrl + path);
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(Math.max(3, timeoutSec)))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .header("User-Agent", "Devils-XaeroSync/1.0");
        if (token != null && !token.isBlank()) builder.header("Authorization", "Bearer " + token.trim());
        SyncRequestSigner.applySignedHeaders(builder, uri, "POST", body, signingKey);
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
        List<SyncXaeroData> profiles;
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
        List<SyncXaeroData> profiles;
        try {
            profiles = readProfiles(json, encryptionKey);
        } catch (Exception decryptError) {
            return new SyncPushResult(false, false, conflict, revision, null, "decrypt:" + decryptError.getClass().getSimpleName(), "");
        }
        String error = readString(json, "error", "");
        String lastWriter = readString(json, "lastWriter", readString(json, "last_writer", ""));
        return new SyncPushResult(ok, applied, conflict, revision, profiles, error, lastWriter);
    }

    private List<SyncXaeroData> readProfiles(JsonObject json, String encryptionKey) throws Exception {
        JsonArray array = readArray(json, "profiles");
        if (array == null) array = readArray(json, "data");
        if (array == null) return List.of();
        array = SyncCrypto.decryptProfiles(array, encryptionKey, MODULE_NAMESPACE);

        ArrayList<SyncXaeroData> list = new ArrayList<>();
        for (int i = 0; i < array.size(); i++) {
            if (!array.get(i).isJsonObject()) continue;
            JsonObject profile = array.get(i).getAsJsonObject();
            list.add(new SyncXaeroData(
                readBoolean(profile, "enabled", true),
                readString(profile, "username", ""),
                readString(profile, "server", ""),
                readString(profile, "password", ""),
                readInt(profile, "delay", 0)
            ));
        }
        return list;
    }

    private JsonArray toJsonArray(List<SyncXaeroData> data) {
        JsonArray array = new JsonArray();
        if (data == null) return array;

        for (SyncXaeroData row : data) {
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

    private void ensureSyncStream(String baseUrl, String deviceId, String token, String signingKey, int timeoutSec, int waitMs) {
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
            + Integer.toHexString(tokenValue.hashCode())
            + "|"
            + Integer.toHexString((signingKey == null ? "" : signingKey).hashCode());
        if ((syncStreamConnected || syncStreamConnecting) && !connectionKey.equals(syncStreamConnectionKey)) stopSyncStream();
        if (syncStreamConnected || syncStreamConnecting) return;
        if (syncStreamReconnectAtMs > System.currentTimeMillis()) return;

        syncStreamStopRequested = false;
        syncStreamConnecting = true;
        syncStreamConnectionKey = connectionKey;

        long knownRevision = Math.max(-1, lastKnownSyncRevision);
        int safeWaitMs = Math.max(50, waitMs);
        int requestTimeout = Math.max(10, timeoutSec + 30);
        syncStreamFuture = CompletableFuture.runAsync(() -> runSyncStreamLoop(baseUrl, deviceId, tokenValue, signingKey, requestTimeout, knownRevision, safeWaitMs));
    }

    private void runSyncStreamLoop(String baseUrl, String deviceId, String token, String signingKey, int timeoutSec, long knownRevision, int waitMs) {
        String streamError = null;
        try {
            HttpRequest request = buildSyncStreamRequest(baseUrl, deviceId, token, signingKey, timeoutSec, knownRevision, waitMs);
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
                logSync("XaeroSync stream connected.");
            });

            try (InputStream input = response.body(); BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
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
                    SyncErrorType streamErrorType = classifySyncError(finalStreamError);
                    long reconnectDelay = switch (streamErrorType) {
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
                        // Do not apply global sync backoff for transient stream drops:
                        // push/pull cycle should keep running at full speed while stream reconnects.
                        if (streamErrorType == SyncErrorType.AUTH || streamErrorType == SyncErrorType.CONFIG || streamErrorType == SyncErrorType.CRYPTO) {
                            logSyncProblem("stream disconnected", finalStreamError);
                        } else {
                            logSync("XaeroSync stream disconnected: %s", finalStreamError);
                        }
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
            mc.execute(this::handleSyncTick);
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

    private HttpRequest buildSyncStreamRequest(String baseUrl, String deviceId, String token, String signingKey, int timeoutSec, long knownRevision, int waitMs) {
        URI uri = buildSyncStreamUri(baseUrl, deviceId, knownRevision, waitMs);
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(Math.max(10, timeoutSec)))
            .header("Accept", "text/event-stream")
            .header("Cache-Control", "no-cache")
            .header("User-Agent", "Devils-XaeroSync/1.0")
            .GET();
        if (token != null && !token.isBlank()) builder.header("Authorization", "Bearer " + token);
        SyncRequestSigner.applySignedHeaders(builder, uri, "GET", "", signingKey);
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
        if (!syncHub.isFeatureEnabled(SyncHub.SyncFeature.XAERO_WORLD_MAP)) return null;

        String deviceId = syncHub.getOrCreateDeviceId();
        if (deviceId.isBlank()) return null;
        String encryptionKey = syncHub.getEncryptionKeyMaterial();
        if (encryptionKey.isBlank()) return null;
        String signingKey = syncHub.getRequestSigningKey();
        if (signingKey.isBlank()) return null;

        return new SyncRuntimeConfig(
            syncHub.getBaseUrl(),
            syncHub.getToken(),
            deviceId,
            true,
            syncHub.allowHttp(),
            Math.max(3, syncHub.requestTimeoutSec()),
            Math.max(50, syncHub.streamWaitMs()),
            encryptionKey,
            signingKey
        );
    }

    private String currentUsername() {
        if (mc.getSession() == null || mc.getSession().getUsername() == null) return "";
        return mc.getSession().getUsername().trim();
    }

    private String currentPlayerUuid() {
        if (mc.player != null && mc.player.getUuid() != null) return mc.player.getUuidAsString();
        return "";
    }

    private String currentServerKey() {
        if (mc.getCurrentServerEntry() != null && mc.getCurrentServerEntry().address != null) {
            String address = mc.getCurrentServerEntry().address.trim();
            if (!address.isEmpty()) return address;
        }
        String worldName = meteordevelopment.meteorclient.utils.Utils.getWorldName();
        return worldName == null ? "" : worldName.trim();
    }

    private String currentSyncDeviceId() {
        return runtimeSyncDeviceId == null ? "" : runtimeSyncDeviceId;
    }

    private static String normalizeSyncBaseUrl(String raw) {
        if (raw == null) return "";
        String base = raw.trim();
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return base;
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

    private void logSyncProblem(String context, String error) {
        String safeError = error == null ? "unknown" : error;
        String signature = (context + "|" + safeError).toLowerCase(Locale.ROOT);
        long now = System.currentTimeMillis();

        if (signature.equals(lastSyncProblemSignature) && (now - lastSyncProblemLogMs) < SYNC_PROBLEM_LOG_COOLDOWN_MS) {
            lastSyncProblemSuppressed++;
            return;
        }

        if (lastSyncProblemSuppressed > 0 && !lastSyncProblemSignature.isBlank()) {
            logSync("XaeroSync suppressed repeated errors: %d", lastSyncProblemSuppressed);
        }

        lastSyncProblemSignature = signature;
        lastSyncProblemLogMs = now;
        lastSyncProblemSuppressed = 0;

        logSync("XaeroSync %s: %s", context, safeError);
        SyncErrorType type = classifySyncError(safeError);
        if (type == SyncErrorType.AUTH) {
            syncBackoffUntilMs = Math.max(syncBackoffUntilMs, now + SYNC_AUTH_BACKOFF_MS);
            return;
        }
        if (type == SyncErrorType.CONFIG) {
            syncBackoffUntilMs = Math.max(syncBackoffUntilMs, now + SYNC_CONFIG_BACKOFF_MS);
            return;
        }
        if (type == SyncErrorType.CRYPTO) {
            syncBackoffUntilMs = Math.max(syncBackoffUntilMs, now + SYNC_CRYPTO_BACKOFF_MS);
            return;
        }
        if (type == SyncErrorType.NETWORK) {
            syncBackoffUntilMs = Math.max(syncBackoffUntilMs, now + SYNC_NETWORK_BACKOFF_MS);
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
        if (normalized.contains("undefined scheme") || normalized.contains("bad-base-url") || normalized.contains("unsupported scheme") || normalized.contains("uri with undefined host")) return SyncErrorType.CONFIG;
        if (normalized.contains("certificateexception") || normalized.contains("sslhandshakeexception") || normalized.contains("pkix") || normalized.contains("subject alternative names")) return SyncErrorType.CONFIG;
        if (normalized.contains("decrypt") || normalized.contains("aeadbadtagexception") || normalized.contains("tag mismatch")) return SyncErrorType.CRYPTO;
        if (normalized.contains("timeout") || normalized.contains("connect") || normalized.contains("ioexception") || normalized.contains("eofexception") || normalized.contains("connection") || normalized.contains("refused") || normalized.contains("reset") || normalized.contains("unreachable") || normalized.contains("unknownhost") || normalized.contains("noroutetohost")) {
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

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String normalizeKey(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String normalizeServerKey(String value) {
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

    private static double clampY(double y) {
        if (!Double.isFinite(y)) return WORLD_MIN_Y;
        if (y < WORLD_MIN_Y) return WORLD_MIN_Y;
        if (y > WORLD_MAX_Y) return WORLD_MAX_Y;
        return y;
    }

    private static double clampDouble(double value, double min, double max) {
        if (!Double.isFinite(value)) return min;
        if (value < min) return min;
        if (value > max) return max;
        return value;
    }

    private static double lerp(double from, double to, double alpha) {
        return from + ((to - from) * alpha);
    }

    private static double distanceSq(double ax, double ay, double az, double bx, double by, double bz) {
        double dx = bx - ax;
        double dy = by - ay;
        double dz = bz - az;
        return (dx * dx) + (dy * dy) + (dz * dz);
    }

    private static String computeFingerprint(List<SyncXaeroData> snapshot) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            List<String> rows = new ArrayList<>(snapshot.size());
            for (SyncXaeroData data : snapshot) {
                rows.add(normalizeKey(data.username()) + "|" + normalizeServerKey(data.server()) + "|" + safe(data.payload()));
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

    private static boolean isPresenceNewer(PresenceMarker incoming, PresenceMarker current) {
        if (incoming == null) return false;
        if (current == null) return true;

        boolean sameSenderDevice = normalizeKey(incoming.senderDevice()).equals(normalizeKey(current.senderDevice()));
        if (sameSenderDevice && incoming.sequence() > 0 && current.sequence() > 0) {
            if (incoming.sequence() > current.sequence()) return true;
            if (incoming.sequence() < current.sequence()) {
                // Sequence can reset after client restart. Accept clearly newer timestamp instead of freezing.
                return incoming.updatedAtMs() > (current.updatedAtMs() + 1_000L);
            }
        }

        if (incoming.updatedAtMs() != current.updatedAtMs()) return incoming.updatedAtMs() > current.updatedAtMs();

        // Last tie-breaker by coordinate checksum to keep deterministic replacement behavior.
        double inSum = incoming.x() + incoming.y() + incoming.z();
        double curSum = current.x() + current.y() + current.z();
        return inSum >= curSum;
    }

    private static RegistryKey<World> parseDimensionKey(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            String value = raw.contains(":") ? raw : "minecraft:" + raw;
            return RegistryKey.of(RegistryKeys.WORLD, Identifier.of(value));
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Field findField(Class<?> owner, String name) {
        if (owner == null || name == null || name.isBlank()) return null;
        Class<?> cursor = owner;
        while (cursor != null) {
            try {
                return cursor.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                cursor = cursor.getSuperclass();
            }
        }
        return null;
    }

    private static Method findResizeMethod(Class<?> owner) {
        if (owner == null) return null;
        for (Method method : owner.getMethods()) {
            if (!"method_25423".equals(method.getName())) continue;
            Class<?>[] params = method.getParameterTypes();
            if (params.length != 3) continue;
            if (params[1] != int.class || params[2] != int.class) continue;
            return method;
        }
        return null;
    }

    private static Object invokeNoArg(Object owner, String methodName) {
        if (owner == null || methodName == null || methodName.isBlank()) return null;
        try {
            Method method = owner.getClass().getMethod(methodName);
            method.setAccessible(true);
            return method.invoke(owner);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void invokeNoArgIfPresent(Object owner, String methodName) {
        if (owner == null || methodName == null || methodName.isBlank()) return;
        try {
            Method method = owner.getClass().getMethod(methodName);
            method.setAccessible(true);
            method.invoke(owner);
        } catch (Throwable ignored) {
        }
    }

    private static void invokeSingleArgIfPresent(Object owner, String methodName, Object arg) {
        if (owner == null || methodName == null || methodName.isBlank()) return;
        Method fallback = null;
        for (Method method : owner.getClass().getMethods()) {
            if (!methodName.equals(method.getName())) continue;
            if (method.getParameterCount() != 1) continue;
            Class<?> parameterType = method.getParameterTypes()[0];
            if (arg == null || parameterType.isInstance(arg)) {
                try {
                    method.setAccessible(true);
                    method.invoke(owner, arg);
                    return;
                } catch (Throwable ignored) {
                }
            }
            fallback = method;
        }
        if (fallback != null) {
            try {
                fallback.setAccessible(true);
                fallback.invoke(owner, arg);
            } catch (Throwable ignored) {
            }
        }
    }

    private int scaledMouseX() {
        if (mc.getWindow() == null || mc.mouse == null) return 0;
        return (int) Math.round(mc.mouse.getX() * mc.getWindow().getScaledWidth() / (double) mc.getWindow().getWidth());
    }

    private int scaledMouseY() {
        if (mc.getWindow() == null || mc.mouse == null) return 0;
        return (int) Math.round(mc.mouse.getY() * mc.getWindow().getScaledHeight() / (double) mc.getWindow().getHeight());
    }

    private static void drawBoxOutline(net.minecraft.client.gui.DrawContext drawContext, int x, int y, int w, int h, int color) {
        drawContext.fill(x, y, x + w, y + 1, color);
        drawContext.fill(x, y + h - 1, x + w, y + h, color);
        drawContext.fill(x, y, x + 1, y + h, color);
        drawContext.fill(x + w - 1, y, x + w, y + h, color);
    }

    private static void cancelInputEvent(Object eventRef) {
        if (eventRef instanceof MouseButtonEvent mouseEvent) mouseEvent.setCancelled(true);
    }

    private Iterable<?> tryGetScreenChildren(Screen screen) {
        if (screen == null) return null;
        try {
            Method children = screen.getClass().getMethod("children");
            Object value = children.invoke(screen);
            if (value instanceof Iterable<?> iterable) return iterable;
        } catch (Throwable ignored) {
        }
        return null;
    }

    private Integer tryInvokeInt(Object owner, String methodName) {
        if (owner == null || methodName == null || methodName.isBlank()) return null;
        try {
            Method method = owner.getClass().getMethod(methodName);
            Object value = method.invoke(owner);
            if (value instanceof Integer integer) return integer;
        } catch (Throwable ignored) {
        }
        return null;
    }

    private Integer readWidgetX(Object owner) {
        return firstNonNullInt(
            tryInvokeInt(owner, "getX"),
            tryInvokeInt(owner, "method_46426"),
            tryReadFieldInt(owner, "field_22760"),
            tryReadFieldInt(owner, "x")
        );
    }

    private Integer readWidgetY(Object owner) {
        return firstNonNullInt(
            tryInvokeInt(owner, "getY"),
            tryInvokeInt(owner, "method_46427"),
            tryReadFieldInt(owner, "field_22761"),
            tryReadFieldInt(owner, "y")
        );
    }

    private Integer readWidgetWidth(Object owner) {
        return firstNonNullInt(
            tryInvokeInt(owner, "getWidth"),
            tryReadFieldInt(owner, "field_22758"),
            tryReadFieldInt(owner, "width")
        );
    }

    private Integer readWidgetHeight(Object owner) {
        return firstNonNullInt(
            tryInvokeInt(owner, "getHeight"),
            tryReadFieldInt(owner, "field_22759"),
            tryReadFieldInt(owner, "height")
        );
    }

    private Integer firstNonNullInt(Integer... values) {
        if (values == null) return null;
        for (Integer value : values) {
            if (value != null) return value;
        }
        return null;
    }

    private Integer tryReadFieldInt(Object owner, String fieldName) {
        Object value = tryReadFieldValue(owner, fieldName);
        if (value instanceof Number number) return number.intValue();
        return null;
    }

    private Double readFieldDouble(Object owner, String fieldName) {
        Object value = tryReadFieldValue(owner, fieldName);
        if (value instanceof Number number) return number.doubleValue();
        return null;
    }

    private Object tryReadFieldValue(Object owner, String fieldName) {
        if (owner == null || fieldName == null || fieldName.isBlank()) return null;
        try {
            Field field = findField(owner.getClass(), fieldName);
            if (field == null) return null;
            field.setAccessible(true);
            return field.get(owner);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private record SyncRuntimeConfig(String baseUrl, String token, String deviceId, boolean useStream, boolean allowHttp, int timeoutSec, int streamWaitMs, String encryptionKey, String signingKey) {}
    private record SyncXaeroData(boolean enabled, String username, String server, String payload, int delay) {}
    private record SyncPullResult(boolean ok, long revision, List<SyncXaeroData> profiles, String error, String lastWriter) {}
    private record SyncPushResult(boolean ok, boolean applied, boolean conflict, long revision, List<SyncXaeroData> profiles, String error, String lastWriter) {}
    private record SyncCycleResult(SyncPullResult pullResult, SyncPushResult pushResult, boolean remoteApplied, boolean localChanged, List<SyncXaeroData> snapshot, String snapshotFingerprint, String error) {}
    private record PresenceMarker(
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
    ) {}
    private record PresenceMotionState(
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
    ) {}
    private record PresenceRenderPosition(double x, double y, double z) {}
    private record PingMarkerRouting(
        List<XaeroSyncWaypoints.PlayerMarker> trackedMarkers,
        List<XaeroSyncWaypoints.MapWaypointMarker> mapMarkers,
        int totalCount,
        int trackedCount,
        int fallbackCount,
        int collisionCount
    ) {}
    private record PingRenderMarker(
        String name,
        String dimension,
        double x,
        double y,
        double z
    ) {}
    private record JumpEntry(
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
    ) {}
    private record MapProjection(double cameraX, double cameraZ, double scale, int width, int height, String dimensionId, int mapMinX, int mapMinY, int mapMaxX, int mapMaxY) {}
    private record OverlayLayout(int buttonX, int buttonY, int panelX, int panelY, int panelH, int shownRows) {}
    private record ToolbarAnchor(int x, int y) {}

    private enum JumpType {
        PLAYER,
        PING,
        MARKER
    }

    private enum SyncErrorType {
        AUTH,
        CONFIG,
        CRYPTO,
        NETWORK,
        OTHER
    }
}
