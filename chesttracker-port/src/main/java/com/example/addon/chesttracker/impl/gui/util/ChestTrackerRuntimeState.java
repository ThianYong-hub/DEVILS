package com.example.addon.chesttracker.impl.gui.util;

public final class ChestTrackerRuntimeState {
    private static volatile boolean moduleEnabled = false;
    private static volatile boolean devilsThemeEnabled = true;
    private static volatile int devilsAccentColor = 0x8E1021;
    private static volatile int devilsOverlayAlpha = 196;

    private ChestTrackerRuntimeState() {
    }

    public static boolean isModuleEnabled() {
        return moduleEnabled;
    }

    public static void setModuleEnabled(boolean enabled) {
        moduleEnabled = enabled;
    }

    public static boolean isDevilsThemeEnabled() {
        return devilsThemeEnabled;
    }

    public static void setDevilsThemeEnabled(boolean enabled) {
        devilsThemeEnabled = enabled;
    }

    public static int getDevilsAccentColor() {
        return devilsAccentColor & 0x00FFFFFF;
    }

    public static void setDevilsAccentColor(int color) {
        devilsAccentColor = color & 0x00FFFFFF;
    }

    public static int getDevilsOverlayAlpha() {
        return devilsOverlayAlpha;
    }

    public static void setDevilsOverlayAlpha(int alpha) {
        devilsOverlayAlpha = Math.max(0, Math.min(255, alpha));
    }
}
