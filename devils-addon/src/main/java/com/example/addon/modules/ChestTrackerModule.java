package com.example.addon.modules;

import com.example.addon.AddonTemplate;
import com.example.addon.modules.chesttracker.ChestTrackerBridge;
import com.example.addon.modules.chesttracker.ChestTrackerSettingsManager;
import com.example.addon.modules.chesttracker.ChestTrackerSnapshotStore;
import com.example.addon.modules.chesttracker.ChestTrackerStreamController;
import com.example.addon.modules.chesttracker.ChestTrackerSyncApi;
import com.example.addon.modules.chesttracker.ChestTrackerSupport.Snapshot;
import com.example.addon.modules.chesttracker.ChestTrackerSupport.SyncPull;
import com.example.addon.modules.chesttracker.ChestTrackerSupport.SyncPush;
import com.example.addon.modules.chesttracker.ChestTrackerSupport.SyncResult;
import com.example.addon.modules.chesttracker.ChestTrackerSupport.SyncRuntimeConfig;
import com.example.addon.shared.sync.SyncDomainRoutes;
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
import net.minecraft.client.MinecraftClient;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.Util;

import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

public class ChestTrackerModule extends Module {
    private static final String RUNTIME_STATE_CLASS = "red.jackf.chesttracker.impl.gui.util.ChestTrackerRuntimeState";
    private static final String CONFIG_CLASS = "red.jackf.chesttracker.impl.config.ChestTrackerConfig";
    private static final String CONFIG_SCREEN_BUILDER_CLASS = "red.jackf.chesttracker.impl.config.ChestTrackerConfigScreenBuilder";
    private static final String CHEST_TRACKER_CLASS = "red.jackf.chesttracker.impl.ChestTracker";
    private static final String BACKEND_TYPE_CLASS = "red.jackf.chesttracker.impl.storage.backend.Backend$Type";
    private static final String MEMORY_ACCESS_CLASS = "red.jackf.chesttracker.impl.memory.MemoryBankAccessImpl";
    private static final String STRINGS_CLASS = "red.jackf.chesttracker.impl.util.Strings";
    private static final String SYNC_PULL_PATH = SyncDomainRoutes.CORE_PULL_PATH;
    private static final String SYNC_PUSH_PATH = SyncDomainRoutes.CORE_PUSH_PATH;
    private static final String SYNC_STREAM_PATH = SyncDomainRoutes.CORE_STREAM_PATH;
    private static final String SYNC_STREAM_PATH_LEGACY = SyncDomainRoutes.LEGACY_STREAM_PATH;
    private static final String SYNC_MODULE = "chest-tracker";
    private static final String SNAPSHOT_SCHEMA = "devils-ct-file-v1";
    private static final int SYNC_TICK_RATE = 20;
    private static final int PULL_FALLBACK_INTERVAL_MS = 1_500;
    private static final int STREAM_FALLBACK_PULL_INTERVAL_MS = 2_500;
    private static final long RECONNECT_MS = 5_000;
    private static final long STREAM_UNSUPPORTED_BACKOFF_MS = 300_000;
    private static final long REMOTE_APPLY_SKEW_MS = 2_000;

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
        .defaultValue(new SettingColor(92, 0, 0, 255))
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

    private final ChestTrackerBridge bridge = new ChestTrackerBridge(RUNTIME_STATE_CLASS, CONFIG_CLASS, BACKEND_TYPE_CLASS);
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ChestTrackerBridge.ScreenLauncher screenLauncher = new ChestTrackerBridge.ScreenLauncher(CHEST_TRACKER_CLASS, CONFIG_SCREEN_BUILDER_CLASS);
    private final ChestTrackerSettingsManager settingsManager = new ChestTrackerSettingsManager(
        bridge,
        this::isActive,
        inventoryButton,
        devilsTheme,
        accentColor,
        overlayAlpha,
        asyncSaving,
        syncVerbose
    );
    private final ChestTrackerSnapshotStore snapshotStore = new ChestTrackerSnapshotStore(
        MEMORY_ACCESS_CLASS,
        STRINGS_CLASS,
        SNAPSHOT_SCHEMA,
        REMOTE_APPLY_SKEW_MS
    );
    private final ChestTrackerSyncApi syncApi = new ChestTrackerSyncApi(http, SYNC_MODULE, SYNC_PULL_PATH, SYNC_PUSH_PATH);
    private final ChestTrackerStreamController streamController = new ChestTrackerStreamController(
        http,
        SYNC_MODULE,
        SYNC_STREAM_PATH,
        SYNC_STREAM_PATH_LEGACY,
        RECONNECT_MS,
        STREAM_UNSUPPORTED_BACKOFF_MS,
        this::info,
        () -> syncVerbose.get()
    );

    private int syncTickCounter;
    private boolean syncBootstrap = true;
    private boolean syncInFlight;
    private long lastPullAttemptMs;
    private long lastKnownRevision = -1;
    private String lastFingerprint = "";
    private String activeNamespace = "";
    private String activeBankId = "";

    public ChestTrackerModule() {
        super(
            AddonTemplate.CATEGORY,
            "chest-tracker",
            "Devils-integrated ChestTracker module with custom theme and storage controls."
        );
        runInMainMenu = true;
        settingsManager.loadLocalSettings(snapshotStore.storageDir());
    }

    @Override
    public void onActivate() {
        settingsManager.loadLocalSettings(snapshotStore.storageDir());
        resetSyncState();
        settingsManager.applySettings(false);
        settingsManager.saveLocalSettings(snapshotStore.storageDir());
    }

    @Override
    public void onDeactivate() {
        streamController.stop();
        snapshotStore.flushLoadedBankToDisk();
        settingsManager.setRuntimeEnabled(false);
        settingsManager.saveChestTrackerConfig();
        settingsManager.saveLocalSettings(snapshotStore.storageDir());
    }

    @Override
    public Module fromTag(NbtCompound tag) {
        super.fromTag(tag);
        settingsManager.loadLocalSettings(snapshotStore.storageDir());
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
        snapshotStore.flushLoadedBankToDisk();
        streamController.stop();
        settingsManager.saveChestTrackerConfig();
        settingsManager.saveLocalSettings(snapshotStore.storageDir());
        activeNamespace = "";
        activeBankId = "";
        syncBootstrap = true;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (!isActive()) return;
        if (++syncTickCounter < SYNC_TICK_RATE) return;
        syncTickCounter = 0;
        snapshotStore.flushLoadedBankToDisk();
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
            Path path = snapshotStore.storageDir();
            Util.getOperatingSystem().open(path.toUri().toString());
        };

        return list;
    }

    private void openTrackerGui() {
        try {
            screenLauncher.openTrackerGui();
        } catch (Throwable t) {
            error("Failed to open ChestTracker GUI: " + t.getClass().getSimpleName());
            AddonTemplate.LOG.error("[Devils/ChestTracker] open GUI failed.", t);
        }
    }

    private void openTrackerConfig() {
        try {
            screenLauncher.openTrackerConfig();
        } catch (Throwable t) {
            error("Failed to open ChestTracker config: " + t.getClass().getSimpleName());
            AddonTemplate.LOG.error("[Devils/ChestTracker] open config failed.", t);
        }
    }

    private void onModuleSettingChanged(boolean applyChestTrackerConfig) {
        if (settingsManager.isLoading()) return;
        if (applyChestTrackerConfig) settingsManager.applySettings(false);
        settingsManager.saveLocalSettings(snapshotStore.storageDir());
    }

    private void handleSyncTick() {
        SyncRuntimeConfig cfg = resolveSyncConfig();
        if (cfg == null || syncInFlight) return;

        String baseUrl = normalizeBaseUrl(cfg.baseUrl());
        if (baseUrl.isBlank()) return;

        String serverKey = currentServerKey();
        if (serverKey.isBlank()) return;

        String normalizedServer = snapshotStore.normalizeServer(serverKey);
        String bankId = snapshotStore.resolveSyncBankId(normalizedServer);
        if (bankId.isBlank()) return;
        String namespace = SYNC_MODULE + ":" + snapshotStore.namespaceKey(normalizedServer);

        if (!namespace.equals(activeNamespace) || !bankId.equals(activeBankId)) {
            streamController.stop();
            activeNamespace = namespace;
            activeBankId = bankId;
            lastKnownRevision = -1;
            lastFingerprint = "";
            syncBootstrap = true;
            lastPullAttemptMs = 0;
        }

        if (cfg.useStream()) streamController.ensureStream(baseUrl, cfg, namespace, lastKnownRevision);
        else streamController.stop();

        Snapshot local = snapshotStore.readLocalSnapshot(bankId, normalizedServer);
        String localFingerprint = snapshotStore.fingerprint(local);
        boolean localChanged = !localFingerprint.equals(lastFingerprint);
        boolean streamTriggeredPull = streamController.consumeStreamSignal(lastKnownRevision);
        long now = System.currentTimeMillis();
        boolean periodicPull = !cfg.useStream() && (now - lastPullAttemptMs) >= PULL_FALLBACK_INTERVAL_MS;
        boolean streamFallbackPull = cfg.useStream()
            && !streamController.isConnected()
            && !streamController.isConnecting()
            && (now - lastPullAttemptMs) >= STREAM_FALLBACK_PULL_INTERVAL_MS;
        boolean shouldPull = syncBootstrap || streamTriggeredPull || periodicPull || streamFallbackPull;
        boolean shouldRun = shouldPull || localChanged;
        if (!shouldRun) return;

        syncInFlight = true;
        boolean bootstrapMode = syncBootstrap;
        lastPullAttemptMs = now;
        CompletableFuture
            .supplyAsync(() -> runSyncCycle(baseUrl, cfg, namespace, normalizedServer, bankId, local, localFingerprint, shouldPull, localChanged, bootstrapMode))
            .exceptionally(e -> new SyncResult(local, localFingerprint, false, "sync-error:" + e.getClass().getSimpleName(), null, false))
            .thenAccept(result -> MinecraftClient.getInstance().execute(() -> finishSyncCycle(bankId, result)));
    }

    private SyncResult runSyncCycle(
        String baseUrl,
        SyncRuntimeConfig cfg,
        String namespace,
        String serverKey,
        String bankId,
        Snapshot local,
        String localFingerprint,
        boolean shouldPull,
        boolean localChanged,
        boolean bootstrapMode
    ) {
        Snapshot effective = local;
        String effectiveFingerprint = localFingerprint;
        boolean localHasData = snapshotStore.snapshotHasSyncData(local);
        boolean remoteApplied = false;
        String error = null;
        SyncPush push = null;
        boolean skipBootstrapEmptyPush = false;
        long pushBaseRevision = lastKnownRevision;

        if (shouldPull) {
            SyncPull pull = syncApi.pull(baseUrl, cfg, namespace, lastKnownRevision);
            if (!pull.ok()) return new SyncResult(local, localFingerprint, false, "pull:" + pull.error(), null, false);
            if (pull.revision() >= 0) {
                pushBaseRevision = pull.revision();
                lastKnownRevision = Math.max(lastKnownRevision, pull.revision());
            }
            Snapshot remote = snapshotStore.selectRemoteSnapshot(pull.rows(), bankId, serverKey);
            if (remote != null) {
                String remoteFingerprint = snapshotStore.fingerprint(remote);
                boolean remoteHasData = snapshotStore.snapshotHasSyncData(remote);
                if (!remoteFingerprint.equals(localFingerprint)
                    && snapshotStore.shouldApplyRemote(local, remote, localHasData, remoteHasData)
                    && snapshotStore.writeSnapshot(remote)) {
                    effective = remote;
                    effectiveFingerprint = remoteFingerprint;
                    localHasData = remoteHasData;
                    remoteApplied = true;
                }
            }
        }

        if (!remoteApplied && localChanged) {
            if (bootstrapMode && !localHasData) {
                skipBootstrapEmptyPush = true;
            } else {
                push = syncApi.push(baseUrl, cfg, namespace, pushBaseRevision, snapshotStore.toRows(local));
                if (!push.ok()) error = "push:" + push.error();
            }
        }

        return new SyncResult(effective, effectiveFingerprint, remoteApplied, error, push, skipBootstrapEmptyPush);
    }

    private void finishSyncCycle(String bankId, SyncResult result) {
        syncInFlight = false;
        if (result == null) return;
        if (result.error() != null) {
            if (syncVerbose.get()) info("ChestTracker sync %s", result.error());
            return;
        }

        if (result.remoteApplied()) {
            lastFingerprint = result.fingerprint();
            syncBootstrap = false;
            snapshotStore.reloadLoadedBankFromDisk(bankId);
            if (syncVerbose.get()) info("ChestTracker sync pull applied (rev=%d).", lastKnownRevision);
            return;
        }

        if (result.skipBootstrapEmptyPush()) {
            lastFingerprint = result.fingerprint();
            syncBootstrap = false;
            if (syncVerbose.get()) info("ChestTracker sync guarded bootstrap-empty state (push skipped).");
            return;
        }

        if (result.push() != null && result.push().ok() && result.push().applied()) {
            if (result.push().revision() >= 0) lastKnownRevision = Math.max(lastKnownRevision, result.push().revision());
            lastFingerprint = result.fingerprint();
            syncBootstrap = false;
            if (syncVerbose.get()) info("ChestTracker sync push ok (rev=%d).", lastKnownRevision);
            return;
        }

        if (result.push() != null && result.push().ok() && !result.push().applied()) {
            syncBootstrap = true;
            if (syncVerbose.get()) info("ChestTracker sync push conflict (%s). Pulling remote...", result.push().error());
        }
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

    private void resetSyncState() {
        syncTickCounter = 0;
        syncBootstrap = true;
        syncInFlight = false;
        lastPullAttemptMs = 0;
        lastKnownRevision = -1;
        lastFingerprint = "";
        activeNamespace = "";
        activeBankId = "";
        streamController.reset();
    }

    private static String normalizeBaseUrl(String url) {
        if (url == null) return "";
        String base = url.trim();
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return base;
    }
}


