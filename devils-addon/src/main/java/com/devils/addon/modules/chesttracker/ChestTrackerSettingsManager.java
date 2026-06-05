package com.devils.addon.modules.chesttracker;

import com.devils.addon.DevilsAddon;
import com.devils.addon.shared.sync.SyncJsonUtils;
import com.google.gson.JsonObject;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Supplier;

public final class ChestTrackerSettingsManager {
    private static final String LOCAL_MODULE_SETTINGS_FILE = "module-settings.json";
    private static final int DEVILS_THEME_ACCENT_R = 92;
    private static final int DEVILS_THEME_ACCENT_G = 0;
    private static final int DEVILS_THEME_ACCENT_B = 0;
    private static final int DEVILS_THEME_ACCENT_A = 255;

    private final ChestTrackerBridge bridge;
    private final Supplier<Boolean> activeSupplier;
    private final Setting<Boolean> inventoryButton;
    private final Setting<Boolean> devilsTheme;
    private final Setting<SettingColor> accentColor;
    private final Setting<Integer> overlayAlpha;
    private final Setting<Boolean> asyncSaving;
    private final Setting<Boolean> syncVerbose;

    private boolean loadingLocalSettings;

    public ChestTrackerSettingsManager(
        ChestTrackerBridge bridge,
        Supplier<Boolean> activeSupplier,
        Setting<Boolean> inventoryButton,
        Setting<Boolean> devilsTheme,
        Setting<SettingColor> accentColor,
        Setting<Integer> overlayAlpha,
        Setting<Boolean> asyncSaving,
        Setting<Boolean> syncVerbose
    ) {
        this.bridge = bridge;
        this.activeSupplier = activeSupplier;
        this.inventoryButton = inventoryButton;
        this.devilsTheme = devilsTheme;
        this.accentColor = accentColor;
        this.overlayAlpha = overlayAlpha;
        this.asyncSaving = asyncSaving;
        this.syncVerbose = syncVerbose;
    }

    public boolean isLoading() {
        return loadingLocalSettings;
    }

    public void loadLocalSettings(Path storageDir) {
        Path path = storageDir.resolve(LOCAL_MODULE_SETTINGS_FILE);
        if (!Files.isRegularFile(path)) return;

        loadingLocalSettings = true;
        try {
            JsonObject json = SyncJsonUtils.parseJsonObject(Files.readString(path, StandardCharsets.UTF_8));
            if (json == null) return;

            inventoryButton.set(SyncJsonUtils.readBoolean(json, "inventoryButton", inventoryButton.get()));
            devilsTheme.set(SyncJsonUtils.readBoolean(json, "devilsTheme", devilsTheme.get()));

            int r = SyncJsonUtils.readInt(json, "accentR", accentColor.get().r);
            int g = SyncJsonUtils.readInt(json, "accentG", accentColor.get().g);
            int b = SyncJsonUtils.readInt(json, "accentB", accentColor.get().b);
            int a = SyncJsonUtils.readInt(json, "accentA", accentColor.get().a);
            if (isLegacyAccentDefault(r, g, b, a)) {
                r = DEVILS_THEME_ACCENT_R;
                g = DEVILS_THEME_ACCENT_G;
                b = DEVILS_THEME_ACCENT_B;
                a = DEVILS_THEME_ACCENT_A;
            }
            accentColor.set(new SettingColor(
                ChestTrackerNbtUtils.clampColor(r),
                ChestTrackerNbtUtils.clampColor(g),
                ChestTrackerNbtUtils.clampColor(b),
                ChestTrackerNbtUtils.clampColor(a)
            ));

            overlayAlpha.set(ChestTrackerNbtUtils.clampColor(SyncJsonUtils.readInt(json, "overlayAlpha", overlayAlpha.get())));
            asyncSaving.set(SyncJsonUtils.readBoolean(json, "asyncSaving", asyncSaving.get()));
            syncVerbose.set(SyncJsonUtils.readBoolean(json, "syncVerbose", syncVerbose.get()));
        } catch (Throwable t) {
            DevilsAddon.LOG.warn("[Devils/ChestTracker] Failed to load local module settings from {}", path, t);
        } finally {
            loadingLocalSettings = false;
        }
    }

    public void saveLocalSettings(Path storageDir) {
        if (loadingLocalSettings) return;

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

            ChestTrackerSupport.writeAtomically(storageDir.resolve(LOCAL_MODULE_SETTINGS_FILE), json.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Throwable t) {
            DevilsAddon.LOG.warn("[Devils/ChestTracker] Failed to save local module settings.", t);
        }
    }

    public void applySettings(boolean save) {
        bridge.setRuntimeEnabled(activeSupplier.get());
        bridge.applyRuntimeTheme(devilsTheme.get(), ChestTrackerNbtUtils.rgb(accentColor.get()), overlayAlpha.get());
        bridge.applyConfigState(
            save,
            activeSupplier.get(),
            inventoryButton.get(),
            devilsTheme.get(),
            ChestTrackerNbtUtils.rgb(accentColor.get()),
            overlayAlpha.get(),
            asyncSaving.get()
        );
    }

    public void setRuntimeEnabled(boolean enabled) {
        bridge.setRuntimeEnabled(enabled);
    }

    public void saveChestTrackerConfig() {
        bridge.saveConfig();
    }

    private static boolean isLegacyAccentDefault(int r, int g, int b, int a) {
        return r == 142 && g == 16 && b == 33 && a == 255;
    }
}


