package com.devils.addon.modules.games;

import java.util.function.Consumer;
import meteordevelopment.meteorclient.events.meteor.KeyEvent;
import meteordevelopment.meteorclient.events.meteor.MouseClickEvent;
import meteordevelopment.meteorclient.utils.misc.input.KeyAction;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;

final class DoomWindow {
    private static final int DARK_BG = 0xD010131B;
    private static final int HEADER_BG = 0xD8372413;
    private static final int HEADER_BORDER = 0xFFC98B43;
    private static final int PANEL_BG = 0xCC101826;
    private static final int PANEL_BORDER = 0xFF4E6A91;
    private static final int VIEW_BG = 0xFF05070E;
    private static final int VIEW_BORDER = 0xFF8BB3E6;

    private final int minW;
    private final int minH;
    private final int maxW;
    private final int maxH;
    private final DoomSession session = new DoomSession();

    private int windowX;
    private int windowY;
    private int windowW;
    private int windowH;
    private boolean dragging;
    private boolean resizing;
    private int dragOffsetX;
    private int dragOffsetY;
    private int resizeStartX;
    private int resizeStartY;
    private int resizeStartW;
    private int resizeStartH;

    DoomWindow(int minW, int minH, int maxW, int maxH) {
        this.minW = minW;
        this.minH = minH;
        this.maxW = maxW;
        this.maxH = maxH;
    }

    void reset(int x, int y, int w, int h) {
        windowX = x;
        windowY = y;
        windowW = w;
        windowH = h;
        dragging = false;
        resizing = false;
    }
    void restoreBounds(int x, int y, int w, int h) {
        windowX = x;
        windowY = y;
        windowW = w;
        windowH = h;
        stopInteraction();
    }

    void onActivate() {
        session.startIfNeeded();
    }

    void onTick() {
        session.startIfNeeded();
    }

    boolean isInputFocused() {
        return session.inputFocused();
    }

    void releaseInputFocus() {
        session.setInputFocused(false);
    }

    void stopInteraction() {
        dragging = false;
        resizing = false;
    }

    void shutdown(MinecraftClient mc) {
        session.shutdown();
        session.setInputFocused(false);
        stopInteraction();
    }

    void render(DrawContext context, MinecraftClient mc, boolean pinned) {
        if (!shouldRender(mc, pinned)) return;
        session.startIfNeeded();
        session.updateFrameTexture(mc);

        int mouseX = scaledMouseX(mc);
        int mouseY = scaledMouseY(mc);
        updateWindowTransform(mc, mouseX, mouseY);

        Layout l = computeLayout();
        TextRenderer tr = mc.textRenderer;
        context.fill(l.x, l.y, l.x + l.w, l.y + l.h, DARK_BG);
        drawHeader(context, tr, l, pinned);
        drawViewport(context, tr, l);
        drawStatus(context, tr, l);
        drawResizeHandle(context, l, mouseX, mouseY);
    }

    boolean onKey(KeyEvent event, MinecraftClient mc, boolean pinned, Runnable closeOverlay) {
        if (!shouldRender(mc, pinned)) return false;
        if (event.action == KeyAction.Press && event.key() == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
            closeOverlay.run();
            return true;
        }

        if (!session.inputFocused()) return false;
        if (event.action != KeyAction.Press && event.action != KeyAction.Release && event.action != KeyAction.Repeat) return false;
        boolean pressed = event.action != KeyAction.Release;
        return session.handleKey(event.key(), pressed);
    }

    boolean onMouse(
        MouseClickEvent event,
        MinecraftClient mc,
        boolean pinned,
        Consumer<Boolean> setPinned,
        Runnable cycleGame,
        Runnable closeOverlay
    ) {
        if (!shouldRender(mc, pinned)) return false;
        if (event.button() != 0 && event.button() != 1 && event.button() != 2) return false;

        int mouseX = scaledMouseX(mc);
        int mouseY = scaledMouseY(mc);
        Layout l = computeLayout();

        if (event.action == KeyAction.Release) {
            dragging = false;
            resizing = false;
            if (session.inputFocused()) session.handleMouseButton(event.button(), false);
            return false;
        }
        if (event.action != KeyAction.Press) return false;

        if (!inside(mouseX, mouseY, l.x, l.y, l.w, l.h)) {
            session.setInputFocused(false);
            return false;
        }

        if (inside(mouseX, mouseY, l.pinX, l.pinY, l.btnW, l.btnH)) {
            session.setInputFocused(false);
            setPinned.accept(!pinned);
            return true;
        }
        int titleW = mc != null && mc.textRenderer != null ? mc.textRenderer.getWidth("DevilsDoom") : 74;
        if (inside(mouseX, mouseY, l.x + 6, l.y + 2, titleW + 2, l.btnH)) {
            session.setInputFocused(false);
            cycleGame.run();
            return true;
        }
        if (inside(mouseX, mouseY, l.resetX, l.resetY, l.btnW, l.btnH)) {
            session.setInputFocused(false);
            session.restart();
            return true;
        }
        if (inside(mouseX, mouseY, l.closeX, l.closeY, l.btnW, l.btnH)) {
            session.setInputFocused(false);
            closeOverlay.run();
            return true;
        }
        if (!pinned && inside(mouseX, mouseY, l.resizeX, l.resizeY, l.resizeSize, l.resizeSize)) {
            session.setInputFocused(false);
            resizing = true;
            dragging = false;
            resizeStartX = mouseX;
            resizeStartY = mouseY;
            resizeStartW = windowW;
            resizeStartH = windowH;
            return true;
        }
        if (!pinned && inside(mouseX, mouseY, l.x, l.y, l.w, l.headerH)) {
            session.setInputFocused(false);
            dragging = true;
            resizing = false;
            dragOffsetX = mouseX - windowX;
            dragOffsetY = mouseY - windowY;
            return true;
        }
        if (inside(mouseX, mouseY, l.viewX, l.viewY, l.viewW, l.viewH)) {
            session.setInputFocused(true);
            session.handleMouseButton(event.button(), true);
            return true;
        }

        session.setInputFocused(false);
        return true;
    }

    private void drawHeader(DrawContext context, TextRenderer tr, Layout l, boolean pinned) {
        context.fill(l.x, l.y, l.x + l.w, l.y + l.headerH, HEADER_BG);
        context.fill(l.x, l.y, l.x + l.w, l.y + 1, HEADER_BORDER);
        context.fill(l.x, l.y + l.headerH - 1, l.x + l.w, l.y + l.headerH, HEADER_BORDER);
        context.drawTextWithShadow(tr, "DevilsDoom", l.x + 6, l.y + 4, 0xFFFFFFFF);
        drawHeaderButton(context, tr, l.pinX, l.pinY, l.btnW, l.btnH, pinned ? "Unpin" : "Pin");
        drawHeaderButton(context, tr, l.resetX, l.resetY, l.btnW, l.btnH, "Reset");
        drawHeaderButton(context, tr, l.closeX, l.closeY, l.btnW, l.btnH, "X");
    }

    private void drawViewport(DrawContext context, TextRenderer tr, Layout l) {
        context.fill(l.panelX, l.panelY, l.panelX + l.panelW, l.panelY + l.panelH, PANEL_BG);
        context.fill(l.panelX, l.panelY, l.panelX + l.panelW, l.panelY + 1, PANEL_BORDER);
        context.fill(l.panelX, l.panelY + l.panelH - 1, l.panelX + l.panelW, l.panelY + l.panelH, PANEL_BORDER);
        context.fill(l.panelX, l.panelY, l.panelX + 1, l.panelY + l.panelH, PANEL_BORDER);
        context.fill(l.panelX + l.panelW - 1, l.panelY, l.panelX + l.panelW, l.panelY + l.panelH, PANEL_BORDER);

        context.fill(l.viewX, l.viewY, l.viewX + l.viewW, l.viewY + l.viewH, VIEW_BG);
        int border = session.inputFocused() ? 0xFFE8C36E : VIEW_BORDER;
        context.fill(l.viewX, l.viewY, l.viewX + l.viewW, l.viewY + 1, border);
        context.fill(l.viewX, l.viewY + l.viewH - 1, l.viewX + l.viewW, l.viewY + l.viewH, border);
        context.fill(l.viewX, l.viewY, l.viewX + 1, l.viewY + l.viewH, border);
        context.fill(l.viewX + l.viewW - 1, l.viewY, l.viewX + l.viewW, l.viewY + l.viewH, border);

        if (!session.hasFrame()) {
            context.drawTextWithShadow(tr, "Starting embedded runtime...", l.viewX + 10, l.viewY + 8, 0xFFD8E6FF);
            context.drawTextWithShadow(tr, trim(tr, session.statusText(), l.viewW - 20), l.viewX + 10, l.viewY + 22, 0xFFFFE6A5);
            context.drawTextWithShadow(tr, trim(tr, session.logText(), l.viewW - 20), l.viewX + 10, l.viewY + 36, 0xFF9EC2EA);
            return;
        }

        int srcW = Math.max(1, session.frameWidth());
        int srcH = Math.max(1, session.frameHeight());
        double ratio = srcW / (double) srcH;
        int drawW = l.viewW;
        int drawH = (int) Math.round(drawW / ratio);
        if (drawH > l.viewH) {
            drawH = l.viewH;
            drawW = (int) Math.round(drawH * ratio);
        }
        int dx = l.viewX + (l.viewW - drawW) / 2;
        int dy = l.viewY + (l.viewH - drawH) / 2;

        context.drawTexture(
            RenderPipelines.GUI_TEXTURED,
            session.frameTextureId(),
            dx,
            dy,
            0,
            0,
            drawW,
            drawH,
            srcW,
            srcH,
            srcW,
            srcH,
            0xFFFFFFFF
        );
    }

    private void drawStatus(DrawContext context, TextRenderer tr, Layout l) {
        context.fill(l.statusX, l.statusY, l.statusX + l.statusW, l.statusY + l.statusH, PANEL_BG);
        context.fill(l.statusX, l.statusY, l.statusX + l.statusW, l.statusY + 1, PANEL_BORDER);
        context.fill(l.statusX, l.statusY + l.statusH - 1, l.statusX + l.statusW, l.statusY + l.statusH, PANEL_BORDER);
        context.fill(l.statusX, l.statusY, l.statusX + 1, l.statusY + l.statusH, PANEL_BORDER);
        context.fill(l.statusX + l.statusW - 1, l.statusY, l.statusX + l.statusW, l.statusY + l.statusH, PANEL_BORDER);

        context.drawTextWithShadow(tr, trim(tr, "Status: " + session.statusText(), l.statusW - 16), l.statusX + 8, l.statusY + 6, 0xFFFFE6A5);
        context.drawTextWithShadow(tr, trim(tr, "Log: " + session.logText(), l.statusW - 16), l.statusX + 8, l.statusY + 18, 0xFFB9D3F6);
        String focus = session.inputFocused() ? "Input: active (click outside to release)." : "Click on DOOM frame to capture input.";
        context.drawTextWithShadow(tr, trim(tr, focus, l.statusW - 16), l.statusX + 8, l.statusY + 30, session.inputFocused() ? 0xFFD8F79F : 0xFFC6D5EE);
        context.drawTextWithShadow(tr, "WASD/Arrows move, Ctrl fire, Space use, Enter menu.", l.statusX + 8, l.statusY + 42, 0xFF93B7DE);
    }

    private void drawHeaderButton(DrawContext context, TextRenderer tr, int x, int y, int w, int h, String text) {
        context.fill(x, y, x + w, y + h, 0xCC274D78);
        context.fill(x, y, x + w, y + 1, 0xFF9ED0FF);
        context.fill(x, y + h - 1, x + w, y + h, 0xFF9ED0FF);
        int tx = x + (w - tr.getWidth(text)) / 2;
        context.drawTextWithShadow(tr, text, tx, y + 3, 0xFFFFFFFF);
    }

    private void drawResizeHandle(DrawContext context, Layout l, int mouseX, int mouseY) {
        int color = inside(mouseX, mouseY, l.resizeX, l.resizeY, l.resizeSize, l.resizeSize) ? 0xFF9FD4FF : 0xFF5A86B4;
        context.fill(l.resizeX, l.resizeY + l.resizeSize - 2, l.resizeX + l.resizeSize, l.resizeY + l.resizeSize, color);
        context.fill(l.resizeX + l.resizeSize - 2, l.resizeY, l.resizeX + l.resizeSize, l.resizeY + l.resizeSize, color);
    }

    private void updateWindowTransform(MinecraftClient mc, int mouseX, int mouseY) {
        int sw = mc.getWindow() == null ? 1920 : mc.getWindow().getScaledWidth();
        int sh = mc.getWindow() == null ? 1080 : mc.getWindow().getScaledHeight();

        if (dragging) {
            windowX = mouseX - dragOffsetX;
            windowY = mouseY - dragOffsetY;
        } else if (resizing) {
            windowW = clamp(resizeStartW + (mouseX - resizeStartX), minW, Math.min(maxW, sw - 4));
            windowH = clamp(resizeStartH + (mouseY - resizeStartY), minH, Math.min(maxH, sh - 4));
        }

        windowX = clamp(windowX, 0, Math.max(0, sw - windowW));
        windowY = clamp(windowY, 0, Math.max(0, sh - windowH));
    }

    private Layout computeLayout() {
        int headerH = 20;
        int pad = 8;
        int statusH = 58;
        int panelX = windowX + pad;
        int panelY = windowY + headerH + pad;
        int panelW = windowW - pad * 2;
        int panelH = Math.max(140, windowH - headerH - statusH - pad * 3);
        int viewX = panelX + 6;
        int viewY = panelY + 6;
        int viewW = panelW - 12;
        int viewH = panelH - 12;
        int statusX = panelX;
        int statusY = panelY + panelH + pad;
        int statusW = panelW;
        int btnW = 44;
        int btnH = 14;
        int closeX = windowX + windowW - btnW - 5;
        int resetX = closeX - btnW - 4;
        int pinX = resetX - btnW - 4;
        return new Layout(
            windowX, windowY, windowW, windowH, headerH,
            panelX, panelY, panelW, panelH,
            viewX, viewY, viewW, viewH,
            statusX, statusY, statusW, statusH,
            pinX, windowY + 3, resetX, windowY + 3, closeX, windowY + 3, btnW, btnH,
            windowX + windowW - 12, windowY + windowH - 12, 10
        );
    }

    private static String trim(TextRenderer tr, String value, int maxWidth) {
        if (value == null) return "";
        if (tr.getWidth(value) <= maxWidth) return value;
        String ellipsis = "...";
        String text = value;
        while (!text.isEmpty() && tr.getWidth(text + ellipsis) > maxWidth) {
            text = text.substring(0, text.length() - 1);
        }
        return text + ellipsis;
    }

    private static int scaledMouseX(MinecraftClient mc) {
        if (mc.getWindow() == null) return 0;
        return (int) Math.round(mc.mouse.getX() * mc.getWindow().getScaledWidth() / (double) mc.getWindow().getWidth());
    }

    private static int scaledMouseY(MinecraftClient mc) {
        if (mc.getWindow() == null) return 0;
        return (int) Math.round(mc.mouse.getY() * mc.getWindow().getScaledHeight() / (double) mc.getWindow().getHeight());
    }

    private static boolean shouldRender(MinecraftClient mc, boolean pinned) {
        return mc != null && mc.player != null;
    }

    private static boolean inside(int mx, int my, int x, int y, int w, int h) {
        return mx >= x && my >= y && mx < x + w && my < y + h;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private record Layout(
        int x, int y, int w, int h, int headerH,
        int panelX, int panelY, int panelW, int panelH,
        int viewX, int viewY, int viewW, int viewH,
        int statusX, int statusY, int statusW, int statusH,
        int pinX, int pinY, int resetX, int resetY, int closeX, int closeY, int btnW, int btnH,
        int resizeX, int resizeY, int resizeSize
    ) {}
}

