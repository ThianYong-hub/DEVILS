package com.devils.addon.modules;

import com.devils.addon.DevilsAddon;
import com.devils.addon.shared.sync.AbstractSyncConfigModule;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;

public class SyncHub extends AbstractSyncConfigModule {
    private final SettingGroup sgFeatures = settings.createGroup("Features");

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

    private final Setting<Boolean> xaeroDebugPipeline = sgFeatures.add(new BoolSetting.Builder()
        .name("xaero-debug-pipeline")
        .description("Enable Xaero map pipeline debug logs in chat.")
        .defaultValue(false)
        .build()
    );

    public SyncHub() {
        super(
            DevilsAddon.CATEGORY,
            "sync-hub",
            "Sensitive sync configuration for Devils core modules."
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
        };
    }

    public boolean xaeroDebugPipeline() {
        return xaeroDebugPipeline.get();
    }

    public enum SyncFeature {
        AUTO_LOGIN,
        PING,
        CHEST_TRACKER,
        XAERO_WORLD_MAP
    }
}
