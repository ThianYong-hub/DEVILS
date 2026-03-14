package com.example.addon.modules;

import com.example.addon.AddonTemplate;
import com.example.addon.util.SyncCrypto;
import com.example.addon.util.SyncRequestSigner;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import meteordevelopment.meteorclient.events.game.GameJoinedEvent;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.gui.widgets.containers.WHorizontalList;
import meteordevelopment.meteorclient.gui.widgets.containers.WVerticalList;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtSizeTracker;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Util;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
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
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public class ChestTrackerModule extends Module {
    private static final String RUNTIME_STATE_CLASS = "com.example.addon.chesttracker.impl.gui.util.ChestTrackerRuntimeState";
    private static final String CONFIG_CLASS = "com.example.addon.chesttracker.impl.config.ChestTrackerConfig";
    private static final String CONFIG_SCREEN_BUILDER_CLASS = "com.example.addon.chesttracker.impl.config.ChestTrackerConfigScreenBuilder";
    private static final String CHEST_TRACKER_CLASS = "com.example.addon.chesttracker.impl.ChestTracker";
    private static final String BACKEND_TYPE_CLASS = "com.example.addon.chesttracker.impl.storage.backend.Backend$Type";
    private static final String MEMORY_ACCESS_CLASS = "com.example.addon.chesttracker.impl.memory.MemoryBankAccessImpl";
    private static final String STRINGS_CLASS = "com.example.addon.chesttracker.impl.util.Strings";
    private static final String SYNC_PULL_PATH = "/pull";
    private static final String SYNC_PUSH_PATH = "/push";
    private static final String SYNC_STREAM_PATH = "/v1/sync/stream";
    private static final String SYNC_STREAM_PATH_LEGACY = "/stream";
    private static final String SYNC_MODULE = "chest-tracker";
    private static final String SNAPSHOT_SCHEMA = "devils-ct-file-v1";
    private static final int SYNC_TICK_RATE = 20;
    private static final int PULL_FALLBACK_INTERVAL_MS = 1_500;
    private static final int STREAM_FALLBACK_PULL_INTERVAL_MS = 2_500;
    private static final long RECONNECT_MS = 5_000;
    private static final long STREAM_UNSUPPORTED_BACKOFF_MS = 300_000;
    private static final long REMOTE_APPLY_SKEW_MS = 2_000;
    private static final String ENDER_CHEST_SUFFIX = ":ender_chest";
    private static final String SKYBLOCK_ENDER_CHEST_SUFFIX = ":skyblock_ender_chest";
    private static final String SHARE_ENDER_CHEST_NAMESPACE = "shareenderchest:";
    private static final String LOCAL_MODULE_SETTINGS_FILE = "chesttracker/module-settings.json";
    private static final int DEVILS_THEME_ACCENT_R = 92;
    private static final int DEVILS_THEME_ACCENT_G = 0;
    private static final int DEVILS_THEME_ACCENT_B = 0;
    private static final int DEVILS_THEME_ACCENT_A = 255;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgTheme = settings.createGroup("Devils Theme");
    private final SettingGroup sgStorage = settings.createGroup("Storage");
    private final SettingGroup sgSync = settings.createGroup("Sync");

    private final Setting<Boolean> inventoryButton = sgGeneral.add(new BoolSetting.Builder()
        .name("inventory-button")
        .description("Show ChestTracker quick button in inventory/container screens.")
        .defaultValue(true)
        .onChanged(v -> onModuleSettingChanged(true))
        .build()
    );

    private final Setting<Boolean> devilsTheme = sgTheme.add(new BoolSetting.Builder()
        .name("devils-theme")
        .description("Enable Devils crimson style in ChestTracker screens.")
        .defaultValue(true)
        .onChanged(v -> onModuleSettingChanged(true))
        .build()
    );

    private final Setting<SettingColor> accentColor = sgTheme.add(new ColorSetting.Builder()
        .name("accent-color")
        .description("Primary crimson accent color for ChestTracker GUI.")
        .defaultValue(new SettingColor(DEVILS_THEME_ACCENT_R, DEVILS_THEME_ACCENT_G, DEVILS_THEME_ACCENT_B, DEVILS_THEME_ACCENT_A))
        .onChanged(v -> onModuleSettingChanged(true))
        .build()
    );

    private final Setting<Integer> overlayAlpha = sgTheme.add(new IntSetting.Builder()
        .name("overlay-alpha")
        .description("Opacity for the crimson UI layer.")
        .defaultValue(196)
        .min(0)
        .sliderRange(0, 255)
        .onChanged(v -> onModuleSettingChanged(true))
        .build()
    );

    private final Setting<Boolean> asyncSaving = sgStorage.add(new BoolSetting.Builder()
        .name("async-saving")
        .description("Use async saving for memory files.")
        .defaultValue(false)
        .onChanged(v -> onModuleSettingChanged(true))
        .build()
    );

    private final Setting<Boolean> syncVerbose = sgSync.add(new BoolSetting.Builder()
        .name("sync-verbose-log")
        .description("Show technical sync lifecycle messages in chat.")
        .defaultValue(false)
        .onChanged(v -> onModuleSettingChanged(false))
        .build()
    );

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private int syncTickCounter;
    private boolean syncBootstrap = true;
    private boolean syncInFlight;
    private long lastPullAttemptMs;
    private long lastKnownRevision = -1;
    private String lastFingerprint = "";
    private String activeNamespace = "";
    private String activeBankId = "";
    private CompletableFuture<Void> streamFuture;
    private volatile boolean streamStop;
    private volatile boolean streamConnected;
    private volatile boolean streamConnecting;
    private volatile boolean streamUpdatePending;
    private volatile long streamPendingRevision = -1;
    private volatile long streamReconnectAtMs;
    private volatile String streamConnectionKey = "";
    private volatile boolean streamUseLegacyPath;
    private volatile boolean streamUnsupported;
    private volatile long streamUnsupportedUntilMs;
    private boolean loadingLocalModuleSettings;

    public ChestTrackerModule() {
        super(
            AddonTemplate.CATEGORY,
            "chest-tracker",
            "Devils-integrated ChestTracker module with custom theme and storage controls."
        );
        runInMainMenu = true;
        loadLocalModuleSettings();
    }

    @Override
    public void onActivate() {
        loadLocalModuleSettings();
        resetSyncState();
        applySettings(false);
        saveLocalModuleSettings();
    }

    @Override
    public void onDeactivate() {
        stopStream();
        flushLoadedBankToDisk();
        setRuntimeEnabled(false);
        saveChestTrackerConfig();
        saveLocalModuleSettings();
    }

    @Override
    public Module fromTag(NbtCompound tag) {
        super.fromTag(tag);
        loadLocalModuleSettings();
        return this;
    }

    @EventHandler
    private void onGameJoined(GameJoinedEvent event) {
        if (!isActive()) return;
        syncBootstrap = true;
        lastPullAttemptMs = 0;
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        if (!isActive()) return;
        flushLoadedBankToDisk();
        stopStream();
        saveChestTrackerConfig();
        saveLocalModuleSettings();
        activeNamespace = "";
        activeBankId = "";
        syncBootstrap = true;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (!isActive()) return;
        if (++syncTickCounter < SYNC_TICK_RATE) return;
        syncTickCounter = 0;
        flushLoadedBankToDisk();
        handleSyncTick();
    }

    @Override
    public WWidget getWidget(GuiTheme theme) {
        WVerticalList list = theme.verticalList();
        WHorizontalList controls = list.add(theme.horizontalList()).expandX().widget();

        WButton openGui = controls.add(theme.button("Open GUI")).expandX().widget();
        openGui.action = this::openTrackerGui;

        WButton openConfig = controls.add(theme.button("Open CT Config")).expandX().widget();
        openConfig.action = this::openTrackerConfig;

        WButton openFolder = list.add(theme.button("Open Data Folder")).expandX().widget();
        openFolder.action = () -> {
            Path path = FabricLoader.getInstance().getGameDir().resolve("devils-addon");
            Util.getOperatingSystem().open(path.toUri().toString());
        };

        return list;
    }

    private void openTrackerGui() {
        try {
            Class<?> chestTracker = Class.forName(CHEST_TRACKER_CLASS);
            Method openInGame = chestTracker.getMethod("openInGame", MinecraftClient.class, Screen.class);
            MinecraftClient mc = MinecraftClient.getInstance();
            openInGame.invoke(null, mc, mc.currentScreen);
        } catch (Throwable t) {
            error("Failed to open ChestTracker GUI: " + t.getClass().getSimpleName());
            AddonTemplate.LOG.error("[Devils/ChestTracker] open GUI failed.", t);
        }
    }

    private void openTrackerConfig() {
        try {
            Class<?> builder = Class.forName(CONFIG_SCREEN_BUILDER_CLASS);
            Method build = builder.getMethod("build", Screen.class);
            MinecraftClient mc = MinecraftClient.getInstance();
            Object built = build.invoke(null, mc.currentScreen);
            if (built instanceof Screen screen) mc.setScreen(screen);
        } catch (Throwable t) {
            error("Failed to open ChestTracker config: " + t.getClass().getSimpleName());
            AddonTemplate.LOG.error("[Devils/ChestTracker] open config failed.", t);
        }
    }

    private void applySettings(boolean save) {
        setRuntimeEnabled(isActive());
        applyRuntimeTheme();
        applyConfigState(save);
    }

    private void onModuleSettingChanged(boolean applyChestTrackerConfig) {
        if (loadingLocalModuleSettings) return;
        if (applyChestTrackerConfig) applySettings(false);
        saveLocalModuleSettings();
    }

    private Path localModuleSettingsPath() {
        return storageDir().resolve(LOCAL_MODULE_SETTINGS_FILE);
    }

    private void loadLocalModuleSettings() {
        Path path = localModuleSettingsPath();
        if (!Files.isRegularFile(path)) return;

        loadingLocalModuleSettings = true;
        try {
            JsonObject json = parseJsonObject(Files.readString(path, StandardCharsets.UTF_8));
            if (json == null) return;

            inventoryButton.set(readBoolean(json, "inventoryButton", inventoryButton.get()));
            devilsTheme.set(readBoolean(json, "devilsTheme", devilsTheme.get()));

            int r = readInt(json, "accentR", accentColor.get().r);
            int g = readInt(json, "accentG", accentColor.get().g);
            int b = readInt(json, "accentB", accentColor.get().b);
            int a = readInt(json, "accentA", accentColor.get().a);
            if (isLegacyAccentDefault(r, g, b, a)) {
                r = DEVILS_THEME_ACCENT_R;
                g = DEVILS_THEME_ACCENT_G;
                b = DEVILS_THEME_ACCENT_B;
                a = DEVILS_THEME_ACCENT_A;
            }
            accentColor.set(new SettingColor(clampColor(r), clampColor(g), clampColor(b), clampColor(a)));

            overlayAlpha.set(clampColor(readInt(json, "overlayAlpha", overlayAlpha.get())));
            asyncSaving.set(readBoolean(json, "asyncSaving", asyncSaving.get()));
            syncVerbose.set(readBoolean(json, "syncVerbose", syncVerbose.get()));
        } catch (Throwable t) {
            AddonTemplate.LOG.warn("[Devils/ChestTracker] Failed to load local module settings from {}", path, t);
        } finally {
            loadingLocalModuleSettings = false;
        }
    }

    private void saveLocalModuleSettings() {
        if (loadingLocalModuleSettings) return;

        try {
            JsonObject json = new JsonObject();
            json.addProperty("version", 1);
            json.addProperty("inventoryButton", inventoryButton.get());
            json.addProperty("devilsTheme", devilsTheme.get());
            json.addProperty("accentR", accentColor.get().r);
            json.addProperty("accentG", accentColor.get().g);
            json.addProperty("accentB", accentColor.get().b);
            json.addProperty("accentA", accentColor.get().a);
            json.addProperty("overlayAlpha", overlayAlpha.get());
            json.addProperty("asyncSaving", asyncSaving.get());
            json.addProperty("syncVerbose", syncVerbose.get());

            writeAtomically(localModuleSettingsPath(), json.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Throwable t) {
            AddonTemplate.LOG.warn("[Devils/ChestTracker] Failed to save local module settings.", t);
        }
    }

    private boolean isLegacyAccentDefault(int r, int g, int b, int a) {
        return r == 142 && g == 16 && b == 33 && a == 255;
    }

    private void setRuntimeEnabled(boolean enabled) {
        try {
            Class<?> runtime = Class.forName(RUNTIME_STATE_CLASS);
            Method setEnabled = runtime.getMethod("setModuleEnabled", boolean.class);
            setEnabled.invoke(null, enabled);
        } catch (Throwable t) {
            AddonTemplate.LOG.debug("[Devils/ChestTracker] Runtime bridge unavailable for moduleEnabled.", t);
        }
    }

    private void applyRuntimeTheme() {
        try {
            Class<?> runtime = Class.forName(RUNTIME_STATE_CLASS);
            runtime.getMethod("setDevilsThemeEnabled", boolean.class).invoke(null, devilsTheme.get());
            runtime.getMethod("setDevilsAccentColor", int.class).invoke(null, rgb(accentColor.get()));
            runtime.getMethod("setDevilsOverlayAlpha", int.class).invoke(null, overlayAlpha.get());
        } catch (Throwable t) {
            AddonTemplate.LOG.debug("[Devils/ChestTracker] Runtime bridge unavailable for theme settings.", t);
        }
    }

    private void applyConfigState(boolean save) {
        try {
            Object handler = getConfigHandler();
            if (handler == null) return;

            Object config = handler.getClass().getMethod("instance").invoke(handler);
            Object gui = getField(config, "gui");
            Object storage = getField(config, "storage");

            setBoolean(gui, "inventoryButton", "enabled", inventoryButton.get() && isActive());
            setBoolean(gui, "devilsTheme", devilsTheme.get());
            setInt(gui, "devilsAccentColor", rgb(accentColor.get()));
            setInt(gui, "devilsOverlayAlpha", overlayAlpha.get());

            setBoolean(storage, "AsyncSaving", asyncSaving.get());
            setBoolean(storage, "entityMemories", false);
            setBoolean(storage, "readableJsonMemories", false);

            Class<?> backendTypeClass = Class.forName(BACKEND_TYPE_CLASS);
            @SuppressWarnings({"unchecked", "rawtypes"})
            Object backendType = Enum.valueOf((Class<? extends Enum>) backendTypeClass, "NBT");
            Field backendField = storage.getClass().getField("storageBackend");
            backendField.set(storage, backendType);

            Method validate = config.getClass().getMethod("validate");
            validate.invoke(config);

            if (save) handler.getClass().getMethod("save").invoke(handler);
        } catch (Throwable t) {
            AddonTemplate.LOG.debug("[Devils/ChestTracker] Config bridge unavailable.", t);
        }
    }

    private void saveChestTrackerConfig() {
        try {
            Object handler = getConfigHandler();
            if (handler != null) handler.getClass().getMethod("save").invoke(handler);
        } catch (Throwable t) {
            AddonTemplate.LOG.debug("[Devils/ChestTracker] Failed to save ChestTracker config.", t);
        }
    }

    private void handleSyncTick() {
        SyncRuntimeConfig cfg = resolveSyncConfig();
        if (cfg == null || syncInFlight) return;

        String baseUrl = normalizeBaseUrl(cfg.baseUrl());
        if (baseUrl.isBlank()) return;

        String serverKey = currentServerKey();
        if (serverKey.isBlank()) return;

        String normalizedServer = normalizeServer(serverKey);
        String bankId = resolveSyncBankId(normalizedServer);
        if (bankId.isBlank()) return;
        String namespace = SYNC_MODULE + ":" + namespaceKey(normalizedServer);

        if (!namespace.equals(activeNamespace) || !bankId.equals(activeBankId)) {
            stopStream();
            activeNamespace = namespace;
            activeBankId = bankId;
            lastKnownRevision = -1;
            lastFingerprint = "";
            syncBootstrap = true;
            lastPullAttemptMs = 0;
        }

        if (cfg.useStream()) ensureStream(baseUrl, cfg, namespace);
        else stopStream();

        Snapshot local = readLocalSnapshot(bankId, normalizedServer);
        String localFp = fingerprint(local);
        boolean localChanged = !localFp.equals(lastFingerprint);
        boolean streamPull = consumeStreamSignal();
        // Stream mode must be event-driven: no periodic pull polling while stream sync is enabled.
        long now = System.currentTimeMillis();
        boolean periodicPull = !cfg.useStream() && (now - lastPullAttemptMs) >= PULL_FALLBACK_INTERVAL_MS;
        boolean streamFallbackPull = cfg.useStream()
            && !streamConnected
            && !streamConnecting
            && (now - lastPullAttemptMs) >= STREAM_FALLBACK_PULL_INTERVAL_MS;
        boolean shouldPull = syncBootstrap || streamPull || periodicPull || streamFallbackPull;
        boolean shouldRun = shouldPull || localChanged;
        if (!shouldRun) return;

        syncInFlight = true;
        boolean bootstrapMode = syncBootstrap;
        lastPullAttemptMs = System.currentTimeMillis();
        CompletableFuture
            .supplyAsync(() -> runSyncCycle(baseUrl, cfg, namespace, normalizedServer, bankId, local, localFp, shouldPull, localChanged, bootstrapMode))
            .exceptionally(e -> new SyncResult(local, localFp, false, "sync-error:" + e.getClass().getSimpleName(), null, false))
            .thenAccept(result -> MinecraftClient.getInstance().execute(() -> finishSyncCycle(bankId, result)));
    }

    private SyncResult runSyncCycle(
        String baseUrl,
        SyncRuntimeConfig cfg,
        String namespace,
        String serverKey,
        String bankId,
        Snapshot local,
        String localFp,
        boolean shouldPull,
        boolean localChanged,
        boolean bootstrapMode
    ) {
        Snapshot effective = local;
        String effectiveFp = localFp;
        boolean localHasData = snapshotHasSyncData(local);
        boolean remoteApplied = false;
        String error = null;
        SyncPush push = null;
        boolean skipBootstrapEmptyPush = false;
        long pushBaseRevision = lastKnownRevision;

        if (shouldPull) {
            SyncPull pull = pull(baseUrl, cfg, namespace);
            if (!pull.ok) return new SyncResult(local, localFp, false, "pull:" + pull.error, null, false);
            if (pull.revision >= 0) pushBaseRevision = pull.revision;
            Snapshot remote = selectRemoteSnapshot(pull.rows, bankId, serverKey);
            if (remote != null) {
                String remoteFp = fingerprint(remote);
                boolean remoteHasData = snapshotHasSyncData(remote);
                if (!remoteFp.equals(localFp) && shouldApplyRemote(local, remote, localHasData, remoteHasData) && writeSnapshot(remote)) {
                    effective = remote;
                    effectiveFp = remoteFp;
                    localHasData = remoteHasData;
                    remoteApplied = true;
                }
            }
        }

        if (!remoteApplied && localChanged) {
            if (bootstrapMode && !localHasData) {
                // Protect remote state: on relog bootstrap local bank can be recreated empty before real data is pulled.
                skipBootstrapEmptyPush = true;
            } else {
                push = push(baseUrl, cfg, namespace, pushBaseRevision, toRows(local));
                if (!push.ok) error = "push:" + push.error;
            }
        }

        return new SyncResult(effective, effectiveFp, remoteApplied, error, push, skipBootstrapEmptyPush);
    }

    private void finishSyncCycle(String bankId, SyncResult result) {
        syncInFlight = false;
        if (result == null) return;
        if (result.error != null) {
            if (syncVerbose.get()) info("ChestTracker sync %s", result.error);
            return;
        }

        if (result.remoteApplied) {
            lastFingerprint = result.fingerprint;
            syncBootstrap = false;
            reloadLoadedBankFromDisk(bankId);
            if (syncVerbose.get()) info("ChestTracker sync pull applied (rev=%d).", lastKnownRevision);
            return;
        }

        if (result.skipBootstrapEmptyPush) {
            lastFingerprint = result.fingerprint;
            syncBootstrap = false;
            if (syncVerbose.get()) info("ChestTracker sync guarded bootstrap-empty state (push skipped).");
            return;
        }

        if (result.push != null && result.push.ok && result.push.applied) {
            if (result.push.revision >= 0) lastKnownRevision = Math.max(lastKnownRevision, result.push.revision);
            lastFingerprint = result.fingerprint;
            syncBootstrap = false;
            if (syncVerbose.get()) info("ChestTracker sync push ok (rev=%d).", lastKnownRevision);
            return;
        }

        if (result.push != null && result.push.ok && !result.push.applied) {
            // A concurrent writer advanced the revision: force an early pull on next tick.
            syncBootstrap = true;
            if (syncVerbose.get()) info("ChestTracker sync push conflict (%s). Pulling remote...", result.push.error);
        }
    }

    private SyncPull pull(String baseUrl, SyncRuntimeConfig cfg, String namespace) {
        try {
            JsonObject payload = new JsonObject();
            payload.addProperty("deviceId", cfg.deviceId());
            payload.addProperty("knownRevision", lastKnownRevision);
            payload.addProperty("module", SYNC_MODULE);
            payload.addProperty("namespace", namespace);
            HttpResponse<String> response = http.send(buildRequest(baseUrl + SYNC_PULL_PATH, cfg, payload.toString()), HttpResponse.BodyHandlers.ofString());
            return parsePull(response, cfg.encryptionKey());
        } catch (Throwable t) {
            return new SyncPull(false, -1, List.of(), t.getClass().getSimpleName());
        }
    }

    private SyncPush push(String baseUrl, SyncRuntimeConfig cfg, String namespace, long baseRevision, List<Row> rows) {
        try {
            JsonObject payload = new JsonObject();
            payload.addProperty("deviceId", cfg.deviceId());
            payload.addProperty("baseRevision", baseRevision);
            payload.addProperty("module", SYNC_MODULE);
            payload.addProperty("namespace", namespace);
            payload.add("profiles", SyncCrypto.encryptProfiles(toJsonArray(rows), cfg.encryptionKey(), SYNC_MODULE));
            HttpResponse<String> response = http.send(buildRequest(baseUrl + SYNC_PUSH_PATH, cfg, payload.toString()), HttpResponse.BodyHandlers.ofString());
            return parsePush(response);
        } catch (Throwable t) {
            return new SyncPush(false, false, -1, t.getClass().getSimpleName());
        }
    }

    private void ensureStream(String baseUrl, SyncRuntimeConfig cfg, String namespace) {
        long now = System.currentTimeMillis();
        if (streamUnsupported && streamUnsupportedUntilMs > now) return;
        if (streamUnsupported && streamUnsupportedUntilMs <= now) {
            streamUnsupported = false;
            streamUnsupportedUntilMs = 0;
        }

        String key = baseUrl
            + "|"
            + namespace
            + "|"
            + cfg.deviceId()
            + "|"
            + cfg.timeoutSec()
            + "|"
            + cfg.streamWaitMs()
            + "|"
            + (streamUseLegacyPath ? "legacy" : "v1")
            + "|"
            + Integer.toHexString(cfg.signingKey().hashCode());
        if ((streamConnected || streamConnecting) && !key.equals(streamConnectionKey)) stopStream();
        if (streamConnected || streamConnecting || streamReconnectAtMs > System.currentTimeMillis()) return;
        streamStop = false;
        streamConnecting = true;
        streamConnectionKey = key;
        streamFuture = CompletableFuture.runAsync(() -> runStream(baseUrl, cfg, namespace));
    }

    private void runStream(String baseUrl, SyncRuntimeConfig cfg, String namespace) {
        String error = null;
        try {
            String streamPath = streamUseLegacyPath ? SYNC_STREAM_PATH_LEGACY : SYNC_STREAM_PATH;
            URI uri = URI.create(baseUrl + streamPath
                + "?deviceId=" + encode(cfg.deviceId())
                + "&module=" + encode(SYNC_MODULE)
                + "&namespace=" + encode(namespace)
                + "&knownRevision=" + lastKnownRevision
                + "&waitMs=" + Math.max(1_000, cfg.streamWaitMs()));

            HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(Math.max(10, cfg.timeoutSec() + 25)))
                .header("Accept", "text/event-stream")
                .GET();
            if (!cfg.token().isBlank()) builder.header("Authorization", "Bearer " + cfg.token().trim());
            SyncRequestSigner.applySignedHeaders(builder, uri, "GET", "", cfg.signingKey());

            HttpResponse<InputStream> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                String errorBody = "";
                try (InputStream input = response.body()) {
                    if (input != null) errorBody = new String(input.readNBytes(512), StandardCharsets.UTF_8);
                }
                if (response.statusCode() == 404 && !streamUseLegacyPath) {
                    streamUseLegacyPath = true;
                    throw new IllegalStateException("stream-404-switching-to-legacy");
                }
                if (response.statusCode() == 404 && streamUseLegacyPath) {
                    streamUnsupported = true;
                    streamUnsupportedUntilMs = System.currentTimeMillis() + STREAM_UNSUPPORTED_BACKOFF_MS;
                    throw new IllegalStateException("stream-unsupported:http-404");
                }
                throw new IllegalStateException(parseHttpError(response.statusCode(), errorBody));
            }

            MinecraftClient.getInstance().execute(() -> {
                streamConnecting = false;
                streamConnected = true;
            });

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                String line;
                StringBuilder data = new StringBuilder();
                while (!streamStop && (line = reader.readLine()) != null) {
                    if (line.startsWith("data:")) {
                        if (data.length() > 0) data.append('\n');
                        data.append(line.substring(5).stripLeading());
                        continue;
                    }

                    if (line.isBlank() && data.length() > 0) {
                        JsonObject json = parseJsonObject(data.toString());
                        long revision = readLong(json, "revision", -1);
                        if (revision > lastKnownRevision) {
                            streamPendingRevision = revision;
                            streamUpdatePending = true;
                        }
                        data.setLength(0);
                    }
                }
            }
        } catch (Throwable t) {
            if (!streamStop) error = t.getClass().getSimpleName() + ":" + (t.getMessage() == null ? "" : t.getMessage());
        } finally {
            String finalError = error;
            MinecraftClient.getInstance().execute(() -> {
                streamConnected = false;
                streamConnecting = false;
                streamFuture = null;
                if (!streamStop) {
                    if (streamUnsupported && streamUnsupportedUntilMs > System.currentTimeMillis()) {
                        streamReconnectAtMs = streamUnsupportedUntilMs;
                    } else {
                        streamReconnectAtMs = System.currentTimeMillis() + RECONNECT_MS;
                    }
                    if (syncVerbose.get() && finalError != null) info("ChestTracker stream disconnected: %s", finalError);
                }
            });
        }
    }

    private boolean consumeStreamSignal() {
        if (!streamUpdatePending) return false;
        streamUpdatePending = false;
        return streamPendingRevision > lastKnownRevision;
    }

    private void stopStream() {
        streamStop = true;
        streamConnected = false;
        streamConnecting = false;
        streamUpdatePending = false;
        streamPendingRevision = -1;
        streamReconnectAtMs = 0;
        streamConnectionKey = "";

        CompletableFuture<Void> future = streamFuture;
        streamFuture = null;
        if (future != null) future.cancel(true);
    }

    private SyncRuntimeConfig resolveSyncConfig() {
        Modules modules = Modules.get();
        if (modules == null) return null;

        SyncHub syncHub = modules.get(SyncHub.class);
        if (syncHub == null || !syncHub.isFeatureEnabled(SyncHub.SyncFeature.CHEST_TRACKER)) return null;

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
            syncHub.useStream(),
            Math.max(3, syncHub.requestTimeoutSec()),
            Math.max(1_000, syncHub.streamWaitMs()),
            encryptionKey,
            signingKey
        );
    }

    private String currentServerKey() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.getCurrentServerEntry() == null || mc.getCurrentServerEntry().address == null) return "";
        return mc.getCurrentServerEntry().address.trim();
    }

    private Path storageDir() {
        return FabricLoader.getInstance().getGameDir().resolve("devils-addon");
    }

    private Snapshot readLocalSnapshot(String bankId, String serverKey) {
        try {
            Path nbt = storageDir().resolve(bankId + ".nbt");
            if (!Files.isRegularFile(nbt)) return null;

            byte[] nbtBytes = Files.readAllBytes(nbt);
            byte[] syncSafeNbtBytes = stripPrivateEnderChestMemories(nbtBytes);
            long updated = lastModified(nbt);

            // Sync payload intentionally carries only real memory data (NBT).
            // Metadata contains ticking fields (loadedTime/lastModified) and would cause constant false diffs.
            return new Snapshot(bankId, serverKey, updated, zipBase64(syncSafeNbtBytes), "");
        } catch (Throwable ignored) {
            return null;
        }
    }

    private boolean writeSnapshot(Snapshot snapshot) {
        if (snapshot == null) return false;
        try {
            byte[] nbtBytes = unzipBase64(snapshot.nbt);
            if (nbtBytes.length == 0) return false;
            writeAtomically(storageDir().resolve(snapshot.bankId + ".nbt"), nbtBytes);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private Snapshot selectRemoteSnapshot(List<Row> rows, String bankId, String serverKey) {
        if (rows == null || rows.isEmpty()) return null;
        Snapshot newest = null;

        for (Row row : rows) {
            if (!row.enabled || !normalizeServer(row.server).equals(normalizeServer(serverKey))) continue;
            JsonObject payload = parseJsonObject(row.payload);
            if (payload == null) continue;
            if (!SNAPSHOT_SCHEMA.equals(readString(payload, "schema", ""))) continue;

            Snapshot snapshot = new Snapshot(
                bankId,
                serverKey,
                Math.max(0, readLong(payload, "updatedAt", 0)),
                sanitizeSnapshotNbt(readString(payload, "nbt", "")),
                readString(payload, "meta", "")
            );
            if (snapshot.nbt.isBlank()) continue;
            if (newest == null || snapshot.updatedAt >= newest.updatedAt) newest = snapshot;
        }

        return newest;
    }

    private List<Row> toRows(Snapshot snapshot) {
        if (snapshot == null) return List.of();

        JsonObject payload = new JsonObject();
        payload.addProperty("schema", SNAPSHOT_SCHEMA);
        payload.addProperty("bankId", snapshot.bankId);
        payload.addProperty("server", snapshot.serverKey);
        payload.addProperty("updatedAt", snapshot.updatedAt);
        payload.addProperty("nbt", snapshot.nbt);
        if (!snapshot.meta.isBlank()) payload.addProperty("meta", snapshot.meta);

        return List.of(new Row(true, "bank:" + snapshot.bankId, snapshot.serverKey, payload.toString(), 0));
    }

    private static String fingerprint(Snapshot snapshot) {
        if (snapshot == null) return "";
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(snapshot.bankId.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) '|');
            digest.update(snapshot.serverKey.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) '|');
            digest.update(stableNbtFingerprintBytes(snapshot.nbt));
            return HexFormat.of().formatHex(digest.digest());
        } catch (Throwable ignored) {
            return Integer.toHexString(snapshot.hashCode());
        }
    }

    private static boolean shouldApplyRemote(Snapshot local, Snapshot remote, boolean localHasData, boolean remoteHasData) {
        if (remote == null) return false;
        if (!remoteHasData) return false;
        String localFingerprint = fingerprint(local);
        String remoteFingerprint = fingerprint(remote);
        if (localFingerprint.equals(remoteFingerprint)) return false;
        if (local == null) return true;
        if (!localHasData) return true;
        if (remote.updatedAt > 0 && local.updatedAt > 0 && remote.updatedAt + REMOTE_APPLY_SKEW_MS < local.updatedAt) return false;
        return true;
    }

    private static boolean snapshotHasSyncData(Snapshot snapshot) {
        if (snapshot == null || snapshot.nbt == null || snapshot.nbt.isBlank()) return false;
        try {
            byte[] raw = unzipBase64(snapshot.nbt);
            if (raw.length == 0) return false;
            NbtCompound root = readMemoryBankNbt(raw);
            if (root == null || root.isEmpty()) return false;
            removePrivateEnderChestKeys(root);
            return hasNestedNbtData(root);
        } catch (Throwable ignored) {
            // If payload cannot be parsed, treat it as meaningful to avoid destructive assumptions.
            return true;
        }
    }

    private static boolean hasNestedNbtData(NbtElement element) {
        if (element == null) return false;
        if (element instanceof NbtCompound compound) {
            for (String key : compound.getKeys()) {
                if (hasNestedNbtData(compound.get(key))) return true;
            }
            return false;
        }
        if (element instanceof NbtList list) {
            for (int i = 0; i < list.size(); i++) {
                if (hasNestedNbtData(list.get(i))) return true;
            }
            return false;
        }
        return true;
    }

    private SyncPull parsePull(HttpResponse<String> response, String encryptionKey) {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            return new SyncPull(false, -1, List.of(), "http-" + response.statusCode());
        }

        JsonObject json = parseJsonObject(response.body());
        if (json == null) return new SyncPull(false, -1, List.of(), "bad-json");

        boolean ok = readBoolean(json, "ok", true);
        long revision = readLong(json, "revision", -1);
        if (revision >= 0) lastKnownRevision = Math.max(lastKnownRevision, revision);
        try {
            return new SyncPull(ok, revision, readRows(json, encryptionKey), readString(json, "error", ""));
        } catch (Exception decryptError) {
            return new SyncPull(false, revision, List.of(), "decrypt:" + decryptError.getClass().getSimpleName());
        }
    }

    private SyncPush parsePush(HttpResponse<String> response) {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            return new SyncPush(false, false, -1, "http-" + response.statusCode());
        }

        JsonObject json = parseJsonObject(response.body());
        if (json == null) return new SyncPush(false, false, -1, "bad-json");

        boolean ok = readBoolean(json, "ok", true);
        boolean applied = readBoolean(json, "applied", ok);
        long revision = readLong(json, "revision", -1);
        return new SyncPush(ok, applied, revision, readString(json, "error", ""));
    }

    private List<Row> readRows(JsonObject json, String encryptionKey) throws Exception {
        JsonArray array = readArray(json, "profiles");
        if (array == null) return List.of();
        array = SyncCrypto.decryptProfiles(array, encryptionKey, SYNC_MODULE);

        ArrayList<Row> rows = new ArrayList<>();
        for (int i = 0; i < array.size(); i++) {
            if (!array.get(i).isJsonObject()) continue;
            JsonObject profile = array.get(i).getAsJsonObject();
            rows.add(new Row(
                readBoolean(profile, "enabled", true),
                readString(profile, "username", ""),
                readString(profile, "server", ""),
                readString(profile, "password", ""),
                readInt(profile, "delay", 0)
            ));
        }
        return rows;
    }

    private JsonArray toJsonArray(List<Row> rows) {
        JsonArray array = new JsonArray();
        for (Row row : rows) {
            JsonObject profile = new JsonObject();
            profile.addProperty("enabled", row.enabled);
            profile.addProperty("username", row.username);
            profile.addProperty("server", row.server);
            profile.addProperty("mode", "LOGIN");
            profile.addProperty("password", row.payload);
            profile.addProperty("delay", row.delay);
            array.add(profile);
        }
        return array;
    }

    private HttpRequest buildRequest(String url, SyncRuntimeConfig cfg, String body) {
        URI uri = URI.create(url);
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(Math.max(3, cfg.timeoutSec())))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json");
        if (!cfg.token().isBlank()) builder.header("Authorization", "Bearer " + cfg.token().trim());
        SyncRequestSigner.applySignedHeaders(builder, uri, "POST", body, cfg.signingKey());
        return builder.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
    }

    private void resetSyncState() {
        syncTickCounter = 0;
        syncBootstrap = true;
        syncInFlight = false;
        lastPullAttemptMs = 0;
        lastKnownRevision = -1;
        lastFingerprint = "";
        activeNamespace = "";
        activeBankId = "";
        streamUseLegacyPath = false;
        streamUnsupported = false;
        streamUnsupportedUntilMs = 0;
        stopStream();
    }

    private static String normalizeBaseUrl(String url) {
        if (url == null) return "";
        String base = url.trim();
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return base;
    }

    private static String normalizeServer(String value) {
        if (value == null) return "";
        String out = value.trim().toLowerCase(Locale.ROOT);
        while (out.endsWith(".")) out = out.substring(0, out.length() - 1);
        if (out.isBlank()) return "";

        // Normalize default Minecraft port so "host" and "host:25565" share one sync namespace.
        if (out.startsWith("[")) {
            int end = out.indexOf(']');
            if (end > 0 && out.length() > end + 2 && out.charAt(end + 1) == ':') {
                String port = out.substring(end + 2).trim();
                if ("25565".equals(port)) out = out.substring(0, end + 1);
            }
            return out;
        }

        int firstColon = out.indexOf(':');
        int lastColon = out.lastIndexOf(':');
        if (firstColon > 0 && firstColon == lastColon && lastColon + 1 < out.length()) {
            String port = out.substring(lastColon + 1).trim();
            boolean numericPort = !port.isEmpty() && port.chars().allMatch(Character::isDigit);
            if (numericPort && "25565".equals(port)) out = out.substring(0, lastColon);
        }
        return out;
    }

    private static String namespaceKey(String value) {
        String out = normalizeServer(value).replaceAll("[^a-z0-9._-]+", "_");
        return out.isBlank() ? "unknown" : out;
    }

    private String bankIdForServer(String server) {
        String source = namespaceKey(server);
        try {
            Class<?> stringsClass = Class.forName(STRINGS_CLASS);
            Method sanitize = stringsClass.getMethod("sanitizeForPath", String.class);
            Object sanitized = sanitize.invoke(null, source);
            if (sanitized instanceof String value && !value.isBlank()) source = value;
        } catch (Throwable ignored) {
        }
        return "multiplayer/" + source;
    }

    private String legacyBankIdForServer(String server) {
        String source = namespaceKey(server);
        try {
            Class<?> stringsClass = Class.forName(STRINGS_CLASS);
            Method sanitize = stringsClass.getMethod("sanitizeForPath", String.class);
            Object sanitized = sanitize.invoke(null, source);
            if (sanitized instanceof String value && !value.isBlank()) source = value;
        } catch (Throwable ignored) {
        }
        return "server-" + source;
    }

    private String resolveSyncBankId(String normalizedServer) {
        String loaded = getLoadedBankId();
        if (!loaded.isBlank()) return loaded;

        String canonical = bankIdForServer(normalizedServer);
        if (bankFilesExist(canonical)) return canonical;

        String portVariant = portVariantBankIdForServer(normalizedServer);
        if (!portVariant.isBlank() && bankFilesExist(portVariant)) return portVariant;

        String legacy = legacyBankIdForServer(normalizedServer);
        if (bankFilesExist(legacy)) return legacy;

        // Wait for provider-driven bank selection/load instead of guessing a new empty file ID.
        return "";
    }

    private String portVariantBankIdForServer(String server) {
        if (server == null || server.isBlank()) return "";

        String base = server.trim().toLowerCase(Locale.ROOT);
        while (base.endsWith(".")) base = base.substring(0, base.length() - 1);
        if (base.isBlank()) return "";

        String withPort;
        if (base.startsWith("[")) {
            if (!base.endsWith("]")) return "";
            withPort = base + ":25565";
        } else if (base.indexOf(':') >= 0) {
            return "";
        } else {
            withPort = base + ":25565";
        }

        String source = withPort.replaceAll("[^a-z0-9._-]+", "_");
        try {
            Class<?> stringsClass = Class.forName(STRINGS_CLASS);
            Method sanitize = stringsClass.getMethod("sanitizeForPath", String.class);
            Object sanitized = sanitize.invoke(null, source);
            if (sanitized instanceof String value && !value.isBlank()) source = value;
        } catch (Throwable ignored) {
        }
        return "multiplayer/" + source;
    }

    private String getLoadedBankId() {
        try {
            Class<?> accessClass = Class.forName(MEMORY_ACCESS_CLASS);
            Object instance = accessClass.getField("INSTANCE").get(null);
            Object optional = accessClass.getMethod("getLoadedInternal").invoke(instance);
            if (!(optional instanceof Optional<?> loadedOpt) || loadedOpt.isEmpty()) return "";
            Object loaded = loadedOpt.get();
            Object idObj = loaded.getClass().getMethod("getId").invoke(loaded);
            return idObj instanceof String id ? id : "";
        } catch (Throwable ignored) {
            return "";
        }
    }

    private boolean bankFilesExist(String id) {
        if (id == null || id.isBlank()) return false;
        Path root = storageDir();
        return Files.isRegularFile(root.resolve(id + ".nbt"))
            || Files.isRegularFile(root.resolve(id + ".nbt.meta"))
            || Files.isRegularFile(root.resolve(id + ".json"))
            || Files.isRegularFile(root.resolve(id + ".json.meta"));
    }

    private static void writeAtomically(Path path, byte[] data) throws Exception {
        if (path.getParent() != null) Files.createDirectories(path.getParent());
        if (data == null || data.length == 0) {
            Files.deleteIfExists(path);
            return;
        }

        Path tmp = path.resolveSibling(path.getFileName() + ".sync.tmp");
        Files.write(tmp, data);
        try {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static long lastModified(Path path) {
        try {
            return Files.isRegularFile(path) ? Files.getLastModifiedTime(path).toMillis() : 0;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static String zipBase64(byte[] bytes) throws Exception {
        if (bytes == null || bytes.length == 0) return "";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
            gzip.write(bytes);
        }
        return Base64.getEncoder().encodeToString(out.toByteArray());
    }

    private static byte[] unzipBase64(String value) throws Exception {
        if (value == null || value.isBlank()) return new byte[0];
        byte[] compressed = Base64.getDecoder().decode(value);
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
            return gzip.readAllBytes();
        }
    }

    private static String sanitizeSnapshotNbt(String base64GzipNbt) {
        if (base64GzipNbt == null || base64GzipNbt.isBlank()) return "";
        try {
            byte[] decoded = unzipBase64(base64GzipNbt);
            byte[] sanitized = stripPrivateEnderChestMemories(decoded);
            return zipBase64(sanitized);
        } catch (Throwable ignored) {
            return base64GzipNbt;
        }
    }

    private static byte[] stableNbtFingerprintBytes(String base64GzipNbt) {
        if (base64GzipNbt == null || base64GzipNbt.isBlank()) return new byte[0];
        try {
            byte[] raw = unzipBase64(base64GzipNbt);
            NbtCompound root = readMemoryBankNbt(raw);
            if (root == null) return raw;

            // Keep fingerprint stable by removing private ender data and serializing without gzip headers.
            removePrivateEnderChestKeys(root);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (DataOutputStream dataOut = new DataOutputStream(out)) {
                NbtIo.writeCompound(root, dataOut);
            }
            return out.toByteArray();
        } catch (Throwable ignored) {
            try {
                return unzipBase64(base64GzipNbt);
            } catch (Throwable ignoredAgain) {
                return base64GzipNbt.getBytes(StandardCharsets.UTF_8);
            }
        }
    }

    private static byte[] stripPrivateEnderChestMemories(byte[] rawNbtBytes) {
        if (rawNbtBytes == null || rawNbtBytes.length == 0) return new byte[0];

        try {
            NbtCompound root = readMemoryBankNbt(rawNbtBytes);
            if (root == null || root.isEmpty()) return rawNbtBytes;

            boolean changed = removePrivateEnderChestKeys(root);
            if (!changed) return rawNbtBytes;

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            NbtIo.writeCompressed(root, out);
            return out.toByteArray();
        } catch (Throwable ignored) {
            return rawNbtBytes;
        }
    }

    private static NbtCompound readMemoryBankNbt(byte[] rawNbtBytes) throws Exception {
        try (ByteArrayInputStream in = new ByteArrayInputStream(rawNbtBytes)) {
            return NbtIo.readCompressed(in, NbtSizeTracker.ofUnlimitedBytes());
        } catch (Throwable compressedReadFailed) {
            try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(rawNbtBytes))) {
                return NbtIo.readCompound(in, NbtSizeTracker.ofUnlimitedBytes());
            }
        }
    }

    private static boolean removePrivateEnderChestKeys(NbtCompound compound) {
        boolean changed = false;

        ArrayList<String> keysToRemove = new ArrayList<>();
        for (String key : compound.getKeys()) {
            if (isPrivateEnderChestMemoryKey(key)) keysToRemove.add(key);
        }

        for (String key : keysToRemove) {
            compound.remove(key);
            changed = true;
        }

        for (String key : new ArrayList<>(compound.getKeys())) {
            NbtElement child = compound.get(key);
            if (child instanceof NbtCompound childCompound) {
                if (removePrivateEnderChestKeys(childCompound)) changed = true;
            } else if (child instanceof NbtList childList) {
                if (removePrivateEnderChestKeys(childList)) changed = true;
            }
        }

        return changed;
    }

    private static boolean removePrivateEnderChestKeys(NbtList list) {
        boolean changed = false;
        for (int i = 0; i < list.size(); i++) {
            NbtElement child = list.get(i);
            if (child instanceof NbtCompound childCompound) {
                if (removePrivateEnderChestKeys(childCompound)) changed = true;
            } else if (child instanceof NbtList childList) {
                if (removePrivateEnderChestKeys(childList)) changed = true;
            }
        }
        return changed;
    }

    private static boolean isPrivateEnderChestMemoryKey(String rawKey) {
        if (rawKey == null || rawKey.isBlank()) return false;
        String key = rawKey.trim().toLowerCase(Locale.ROOT);
        return key.equals("ender_chest")
            || key.endsWith(ENDER_CHEST_SUFFIX)
            || key.endsWith(SKYBLOCK_ENDER_CHEST_SUFFIX)
            || key.startsWith(SHARE_ENDER_CHEST_NAMESPACE);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private void flushLoadedBankToDisk() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.world == null || client.player == null) return;

        try {
            if (client.world.getRegistryManager().getOptional(RegistryKeys.ENCHANTMENT).isEmpty()) return;
        } catch (Throwable ignored) {
            return;
        }

        try {
            Class<?> accessClass = Class.forName(MEMORY_ACCESS_CLASS);
            Object instance = accessClass.getField("INSTANCE").get(null);
            accessClass.getMethod("save").invoke(instance);
        } catch (Throwable ignored) {
        }
    }

    private void reloadLoadedBankFromDisk(String bankId) {
        try {
            Class<?> accessClass = Class.forName(MEMORY_ACCESS_CLASS);
            Object instance = accessClass.getField("INSTANCE").get(null);
            Object optional = accessClass.getMethod("getLoadedInternal").invoke(instance);
            if (!(optional instanceof Optional<?> loadedOpt) || loadedOpt.isEmpty()) return;

            Object loaded = loadedOpt.get();
            Object loadedId = loaded.getClass().getMethod("getId").invoke(loaded);
            if (!(loadedId instanceof String id) || !bankId.equals(id)) return;

            Field loadedField = accessClass.getDeclaredField("loaded");
            loadedField.setAccessible(true);
            loadedField.set(null, null);
            accessClass.getMethod("loadOrCreate", String.class, String.class).invoke(instance, bankId, bankId);
        } catch (Throwable ignored) {
        }
    }

    private Object getConfigHandler() throws Exception {
        Class<?> configClass = Class.forName(CONFIG_CLASS);
        return configClass.getField("INSTANCE").get(null);
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

    private static String parseHttpError(int statusCode, String body) {
        String safeBody = body == null ? "" : body.replaceAll("\\s+", " ").trim();
        if (safeBody.length() > 120) safeBody = safeBody.substring(0, 120) + "...";
        if (!safeBody.isBlank()) return "http-" + statusCode + "-" + safeBody;
        return "http-" + statusCode;
    }

    private static JsonObject parseJsonObject(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            if (!JsonParser.parseString(raw).isJsonObject()) return null;
            return JsonParser.parseString(raw).getAsJsonObject();
        } catch (Throwable ignored) {
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
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static int readInt(JsonObject json, String key, int fallback) {
        if (json == null || key == null || !json.has(key) || json.get(key).isJsonNull()) return fallback;
        try {
            return json.get(key).getAsInt();
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static long readLong(JsonObject json, String key, long fallback) {
        if (json == null || key == null || !json.has(key) || json.get(key).isJsonNull()) return fallback;
        try {
            return json.get(key).getAsLong();
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static boolean readBoolean(JsonObject json, String key, boolean fallback) {
        if (json == null || key == null || !json.has(key) || json.get(key).isJsonNull()) return fallback;
        try {
            return json.get(key).getAsBoolean();
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private record SyncRuntimeConfig(String baseUrl, String token, String deviceId, boolean useStream, int timeoutSec, int streamWaitMs, String encryptionKey, String signingKey) {
    }

    private record Snapshot(String bankId, String serverKey, long updatedAt, String nbt, String meta) {
    }

    private record Row(boolean enabled, String username, String server, String payload, int delay) {
    }

    private record SyncPull(boolean ok, long revision, List<Row> rows, String error) {
    }

    private record SyncPush(boolean ok, boolean applied, long revision, String error) {
    }

    private record SyncResult(
        Snapshot snapshot,
        String fingerprint,
        boolean remoteApplied,
        String error,
        SyncPush push,
        boolean skipBootstrapEmptyPush
    ) {
    }

    private static int clampColor(int value) {
        return Math.max(0, Math.min(255, value));
    }

    private static int rgb(SettingColor color) {
        return ((color.r & 0xFF) << 16) | ((color.g & 0xFF) << 8) | (color.b & 0xFF);
    }
}
