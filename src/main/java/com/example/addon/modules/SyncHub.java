package com.example.addon.modules;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.systems.modules.Module;

import java.util.UUID;

public class SyncHub extends Module {
    private static final int REQUEST_TIMEOUT_SEC = 15;
    private static final int STREAM_WAIT_MS = 25_000;

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

    public SyncHub() {
        super(
            AddonTemplate.CATEGORY,
            "sync-hub",
            "Shared sync configuration for Devils modules."
        );
    }

    public boolean isFeatureEnabled(SyncFeature feature) {
        if (!isActive()) return false;
        return switch (feature) {
            case AUTO_LOGIN -> autoLoginSync.get();
            case PING -> pingSync.get();
            case CHEST_TRACKER -> chestTrackerSync.get();
        };
    }

    public String getBaseUrl() {
        return baseUrl.get() == null ? "" : baseUrl.get().trim();
    }

    public String getToken() {
        return token.get() == null ? "" : token.get().trim();
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

    public enum SyncFeature {
        AUTO_LOGIN,
        PING,
        CHEST_TRACKER
    }
}
