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

    public SyncHub() {
        super(
            DevilsAddon.CATEGORY,
            "sync-hub",
            "Sensitive sync configuration for Devils core modules."
        );
    }

    public boolean isFeatureEnabled(SyncFeature feature) {
        if (!isActive()) return false;
        return switch (feature) {
            case AUTO_LOGIN -> autoLoginSync.get();
            case PING -> pingSync.get();
        };
    }

    public enum SyncFeature {
        AUTO_LOGIN,
        PING
    }
}
