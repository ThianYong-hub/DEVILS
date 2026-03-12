package com.example.addon.chesttracker.impl.gui.util;

import net.minecraft.client.gui.GuiGraphics;

public final class DevilsThemeRenderer {
    private DevilsThemeRenderer() {
    }

    public static boolean isEnabled() {
        return ChestTrackerRuntimeState.isDevilsThemeEnabled();
    }

    public static int titleColor(int fallback) {
        if (!isEnabled()) return fallback;
        return argb(255, lighten(ChestTrackerRuntimeState.getDevilsAccentColor(), 0.62f));
    }

    public static void renderPanelOverlay(GuiGraphics graphics, int left, int top, int width, int height) {
        if (!isEnabled()) return;
        if (width <= 2 || height <= 2) return;

        int accent = ChestTrackerRuntimeState.getDevilsAccentColor();
        int alpha = ChestTrackerRuntimeState.getDevilsOverlayAlpha();

        int deep = darken(accent, 0.84f);
        int mid = darken(accent, 0.60f);
        int high = lighten(accent, 0.24f);
        int highlight = lighten(accent, 0.48f);

        int bodyAlpha = clamp(alpha + 10, 96, 255);
        int topAlpha = clamp(alpha + 34, 120, 255);
        int sectionLineAlpha = clamp(alpha - 36, 34, 180);

        graphics.fill(left + 1, top + 1, left + width - 1, top + height - 1, argb(bodyAlpha, deep));
        graphics.fill(left + 1, top + 1, left + width - 1, top + 20, argb(topAlpha, mid));

        // Crisp horizontal etching to keep a premium "solid crimson plate" effect.
        for (int y = top + 24; y < top + height - 2; y += 3) {
            graphics.fill(left + 2, y, left + width - 2, y + 1, argb(sectionLineAlpha, darken(accent, 0.72f)));
        }

        // Outer + inner frame for a dense frame look.
        graphics.fill(left, top, left + width, top + 1, argb(255, highlight));
        graphics.fill(left, top + height - 1, left + width, top + height, argb(255, high));
        graphics.fill(left, top, left + 1, top + height, argb(255, high));
        graphics.fill(left + width - 1, top, left + width, top + height, argb(255, darken(accent, 0.44f)));

        graphics.fill(left + 1, top + 1, left + width - 1, top + 2, argb(180, lighten(accent, 0.35f)));
        graphics.fill(left + 1, top + height - 2, left + width - 1, top + height - 1, argb(180, darken(accent, 0.70f)));
        graphics.fill(left + 1, top + 1, left + 2, top + height - 1, argb(180, darken(accent, 0.54f)));
        graphics.fill(left + width - 2, top + 1, left + width - 1, top + height - 1, argb(180, darken(accent, 0.36f)));

        // Toolbar separator line.
        graphics.fill(left + 1, top + 20, left + width - 1, top + 21, argb(210, lighten(accent, 0.30f)));
    }

    public static void renderSectionOverlay(GuiGraphics graphics, int left, int top, int width, int height) {
        if (!isEnabled()) return;
        if (width <= 2 || height <= 2) return;

        int accent = ChestTrackerRuntimeState.getDevilsAccentColor();
        int alpha = ChestTrackerRuntimeState.getDevilsOverlayAlpha();

        int body = darken(accent, 0.75f);
        int border = lighten(accent, 0.20f);
        int shadow = darken(accent, 0.58f);

        graphics.fill(left + 1, top + 1, left + width - 1, top + height - 1, argb(clamp(alpha - 34, 52, 190), body));
        graphics.fill(left, top, left + width, top + 1, argb(220, border));
        graphics.fill(left, top + height - 1, left + width, top + height, argb(220, shadow));
        graphics.fill(left, top, left + 1, top + height, argb(220, shadow));
        graphics.fill(left + width - 1, top, left + width, top + height, argb(220, darken(accent, 0.48f)));
    }

    public static void renderForegroundVeil(GuiGraphics graphics, int left, int top, int width, int height) {
        if (!isEnabled()) return;
        if (width <= 2 || height <= 2) return;

        int accent = ChestTrackerRuntimeState.getDevilsAccentColor();
        int alpha = ChestTrackerRuntimeState.getDevilsOverlayAlpha();
        graphics.fill(left + 1, top + 1, left + width - 1, top + height - 1, argb(clamp(alpha - 144, 20, 82), darken(accent, 0.67f)));
    }

    public static int fullScreenOverlayColor() {
        if (!isEnabled()) return 0xB0000000;
        int accent = ChestTrackerRuntimeState.getDevilsAccentColor();
        int alpha = clamp(ChestTrackerRuntimeState.getDevilsOverlayAlpha() + 20, 120, 240);
        return argb(alpha, darken(accent, 0.88f));
    }

    private static int argb(int alpha, int rgb) {
        return ((alpha & 0xFF) << 24) | (rgb & 0x00FFFFFF);
    }

    private static int darken(int rgb, float amount) {
        float t = clamp01(amount);
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        r = clamp((int) Math.round(r * (1.0f - t)), 0, 255);
        g = clamp((int) Math.round(g * (1.0f - t)), 0, 255);
        b = clamp((int) Math.round(b * (1.0f - t)), 0, 255);
        return (r << 16) | (g << 8) | b;
    }

    private static int lighten(int rgb, float amount) {
        float t = clamp01(amount);
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        r = clamp((int) Math.round(r + (255 - r) * t), 0, 255);
        g = clamp((int) Math.round(g + (255 - g) * t), 0, 255);
        b = clamp((int) Math.round(b + (255 - b) * t), 0, 255);
        return (r << 16) | (g << 8) | b;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static float clamp01(float value) {
        if (value < 0) return 0;
        return Math.min(value, 1);
    }
}
