package com.example.addon.modules;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.systems.modules.Module;

import java.util.UUID;

public class SyncHub extends Module {
    private static final int REQUEST_TIMEOUT_SEC = 3;
    private static final int STREAM_WAIT_MS = 25;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgFeatures = settings.createGroup("Features");

    private final Setting<String> baseUrl = sgGeneral.add(new StringSetting.Builder()
        .name("base-url")
        .description("Sync API base URL, e.g. http://127.0.0.1:7878 or https://sync.example.com.")
        .defaultValue("")
        .build()
    );

    private final Setting<String> token = sgGeneral.add(new StringSetting.Builder()
        .name("token")
        .description("Bearer token used for sync hub authentication.")
        .defaultValue("")
        .build()
    );

    private final Setting<String> encryptionKey = sgGeneral.add(new StringSetting.Builder()
        .name("encryption-key")
        .description("E2E encryption key for synced payloads.")
        .defaultValue("")
        .build()
    );

    private final Setting<String> requestSigningKey = sgGeneral.add(new StringSetting.Builder()
        .name("request-signing-key")
        .description("HMAC key for signed sync requests. Token alone is not enough when backend requires signatures.")
        .defaultValue("")
        .build()
    );

    private final Setting<String> deviceId = sgGeneral.add(new StringSetting.Builder()
        .name("device-id")
        .description("Stable ID for this client in sync hub.")
        .defaultValue(UUID.randomUUID().toString())
        .build()
    );

    private final Setting<Boolean> allowHttp = sgGeneral.add(new BoolSetting.Builder()
        .name("allow-http")
        .description("Allow plain HTTP endpoints (unsafe on public networks).")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> useStream = sgGeneral.add(new BoolSetting.Builder()
        .name("use-stream")
        .description("Use server stream channel for sync updates when supported.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> autoLoginSync = sgFeatures.add(new BoolSetting.Builder()
        .name("auto-login")
        .description("Allow AutoLogin module to sync through this hub.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> pingSync = sgFeatures.add(new BoolSetting.Builder()
        .name("ping")
        .description("Allow Ping module to sync through this hub.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> chestTrackerSync = sgFeatures.add(new BoolSetting.Builder()
        .name("chest-tracker")
        .description("Allow ChestTracker module to sync through this hub.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> xaeroWorldMapSync = sgFeatures.add(new BoolSetting.Builder()
        .name("xaero-world-map")
        .description("Allow XaeroSync module to sync live player map markers for Xaero World Map.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> gamesSync = sgFeatures.add(new BoolSetting.Builder()
        .name("mini-games")
        .description("Allow Chess / Checkers mini-games to sync through this hub.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> xaeroDebugPipeline = sgFeatures.add(new BoolSetting.Builder()
        .name("xaero-debug-pipeline")
        .description("Enable Xaero map pipeline debug logs in chat.")
        .defaultValue(false)
        .build()
    );

    public SyncHub() {
        super(
            AddonTemplate.CATEGORY,
            "sync-hub",
            "Shared sync configuration for Devils modules."
        );
        XaeroSync.ensureInternal();
    }

    public boolean isFeatureEnabled(SyncFeature feature) {
        if (!isActive()) return false;
        return switch (feature) {
            case AUTO_LOGIN -> autoLoginSync.get();
            case PING -> pingSync.get();
            case CHEST_TRACKER -> chestTrackerSync.get();
            case XAERO_WORLD_MAP -> xaeroWorldMapSync.get();
            case GAMES -> gamesSync.get();
        };
    }

    public String getBaseUrl() {
        return baseUrl.get() == null ? "" : baseUrl.get().trim();
    }

    public String getToken() {
        return token.get() == null ? "" : token.get().trim();
    }

    public String getEncryptionKeyMaterial() {
        String value = encryptionKey.get() == null ? "" : encryptionKey.get().trim();
        return value;
    }

    public String getRequestSigningKey() {
        return requestSigningKey.get() == null ? "" : requestSigningKey.get().trim();
    }

    public String getOrCreateDeviceId() {
        String value = deviceId.get() == null ? "" : deviceId.get().trim();
        if (!value.isBlank()) return value;

        value = UUID.randomUUID().toString();
        deviceId.set(value);
        return value;
    }

    public boolean allowHttp() {
        return allowHttp.get();
    }

    public boolean useStream() {
        return useStream.get();
    }

    public int requestTimeoutSec() {
        return REQUEST_TIMEOUT_SEC;
    }

    public int streamWaitMs() {
        return STREAM_WAIT_MS;
    }

    public boolean xaeroDebugPipeline() {
        return xaeroDebugPipeline.get();
    }

    public enum SyncFeature {
        AUTO_LOGIN,
        PING,
        CHEST_TRACKER,
        XAERO_WORLD_MAP,
        GAMES
    }
}


