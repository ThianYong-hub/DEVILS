package com.devils.addon.modules.games;

import java.util.function.Consumer;
import meteordevelopment.meteorclient.events.meteor.MouseClickEvent;
import meteordevelopment.meteorclient.utils.misc.input.KeyAction;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.util.Identifier;

final class SlotMachineWindow {
    private static final int DARK_BG = 0xD00E1218;
    private static final int HEADER_BG = 0xD8332A1F;
    private static final int HEADER_BORDER = 0xFFC18845;
    private static final int MACHINE_BG = 0xD010141B;
    private static final int MACHINE_BORDER = 0xFF5D7DA9;
    private static final int REEL_BG = 0x66FFFFFF;
    private static final int REEL_BORDER = 0x66C9CED6;
    private static final int PANEL_BG = 0xCC131A27;
    private static final int PANEL_BORDER = 0xFF4E6A91;
    private static final int PAYLINE_COLOR = 0xFFD6C071;

    private static final int FRAME_W = 229;
    private static final int FRAME_H = 102;
    private static final int SPIN_W = 32;
    private static final int SPIN_H = 20;

    private static final Identifier FRAME = Identifier.of("devils-game", "textures/games/slot/smframe_blank.png");
    private static final Identifier SPIN = Identifier.of("devils-game", "textures/games/slot/spin.png");
    private static final Identifier SPIN_PRESSED = Identifier.of("devils-game", "textures/games/slot/spin_pressed.png");

    private final int minW;
    private final int minH;
    private final int maxW;
    private final int maxH;
    private final SlotMachineSession session = new SlotMachineSession();

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

    SlotMachineWindow(int minW, int minH, int maxW, int maxH) {
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
        session.reset();
    }
    void restoreBounds(int x, int y, int w, int h) {
        windowX = x;
        windowY = y;
        windowW = w;
        windowH = h;
        stopInteraction();
    }

    void stopInteraction() {
        dragging = false;
        resizing = false;
    }

    void onTick() {
        session.onTick();
    }

    void render(DrawContext context, MinecraftClient mc, boolean pinned) {
        if (!shouldRender(mc, pinned)) return;
        int mouseX = scaledMouseX(mc);
        int mouseY = scaledMouseY(mc);
        updateWindowTransform(mc, mouseX, mouseY);

        Layout l = computeLayout();
        context.fill(l.x, l.y, l.x + l.w, l.y + l.h, DARK_BG);
        drawHeader(context, l, pinned);
        drawMachine(context, l);
        drawFooter(context, l, mouseX, mouseY);
        drawResizeHandle(context, l, mouseX, mouseY);
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
        int mouseX = scaledMouseX(mc);
        int mouseY = scaledMouseY(mc);
        if (event.button() != 0) return false;

        if (event.action == KeyAction.Release) {
            dragging = false;
            resizing = false;
            return false;
        }
        if (event.action != KeyAction.Press) return false;

        Layout l = computeLayout();
        if (!inside(mouseX, mouseY, l.x, l.y, l.w, l.h)) return false;

        if (inside(mouseX, mouseY, l.pinX, l.pinY, l.btnW, l.btnH)) {
            setPinned.accept(!pinned);
            return true;
        }
        int titleW = mc != null && mc.textRenderer != null ? mc.textRenderer.getWidth("Slot Machine") : 64;
        if (inside(mouseX, mouseY, l.x + 6, l.y + 2, titleW + 2, l.btnH)) {
            cycleGame.run();
            return true;
        }
        if (inside(mouseX, mouseY, l.closeX, l.closeY, l.btnW, l.btnH)) {
            closeOverlay.run();
            return true;
        }
        if (!pinned && inside(mouseX, mouseY, l.resizeX, l.resizeY, l.resizeSize, l.resizeSize)) {
            resizing = true;
            dragging = false;
            resizeStartX = mouseX;
            resizeStartY = mouseY;
            resizeStartW = windowW;
            resizeStartH = windowH;
            return true;
        }
        if (!pinned && inside(mouseX, mouseY, l.x, l.y, l.w, l.headerH)) {
            dragging = true;
            resizing = false;
            dragOffsetX = mouseX - windowX;
            dragOffsetY = mouseY - windowY;
            return true;
        }

        if (inside(mouseX, mouseY, l.betMinusX, l.betBtnY, l.betBtnW, l.betBtnH)) {
            session.decreaseBet();
            return true;
        }
        if (inside(mouseX, mouseY, l.betPlusX, l.betBtnY, l.betBtnW, l.betBtnH)) {
            session.increaseBet();
            return true;
        }
        if (inside(mouseX, mouseY, l.betMaxX, l.betBtnY, l.betBtnW + 10, l.betBtnH)) {
            session.maxBet();
            return true;
        }
        if (inside(mouseX, mouseY, l.spinX, l.spinY, l.spinW, l.spinH)) {
            session.spin();
            return true;
        }
        return true;
    }

    private void drawHeader(DrawContext context, Layout l, boolean pinned) {
        context.fill(l.x, l.y, l.x + l.w, l.y + l.headerH, HEADER_BG);
        context.fill(l.x, l.y, l.x + l.w, l.y + 1, HEADER_BORDER);
        context.fill(l.x, l.y + l.headerH - 1, l.x + l.w, l.y + l.headerH, HEADER_BORDER);

        TextRenderer tr = MinecraftClient.getInstance().textRenderer;
        context.drawTextWithShadow(tr, "Slot Machine", l.x + 6, l.y + 4, 0xFFFFFFFF);
        drawHeaderButton(context, tr, l.pinX, l.pinY, l.btnW, l.btnH, pinned ? "Unpin" : "Pin");
        drawHeaderButton(context, tr, l.closeX, l.closeY, l.btnW, l.btnH, "X");
    }

    private void drawMachine(DrawContext context, Layout l) {
        context.fill(l.machineX, l.machineY, l.machineX + l.machineW, l.machineY + l.machineH, MACHINE_BG);
        context.fill(l.machineX, l.machineY, l.machineX + l.machineW, l.machineY + 1, MACHINE_BORDER);
        context.fill(l.machineX, l.machineY + l.machineH - 1, l.machineX + l.machineW, l.machineY + l.machineH, MACHINE_BORDER);
        context.fill(l.machineX, l.machineY, l.machineX + 1, l.machineY + l.machineH, MACHINE_BORDER);
        context.fill(l.machineX + l.machineW - 1, l.machineY, l.machineX + l.machineW, l.machineY + l.machineH, MACHINE_BORDER);

        context.drawTexture(
            RenderPipelines.GUI_TEXTURED,
            FRAME,
            l.frameX,
            l.frameY,
            0,
            0,
            l.frameW,
            l.frameH,
            FRAME_W,
            FRAME_H,
            FRAME_W,
            FRAME_H,
            0xFFFFFFFF
        );

        for (int reelIndex = 0; reelIndex < 3; reelIndex++) {
            int rx = l.reelsX + reelIndex * (l.reelW + l.reelGap);
            int ry = l.reelsY;
            drawReel(context, rx, ry, l.reelW, l.reelH, reelIndex);
        }

        int lineY = l.reelsY + l.reelH / 2;
        context.fill(l.reelsX, lineY - 1, l.reelsX + (l.reelW * 3) + (l.reelGap * 2), lineY + 1, PAYLINE_COLOR);
    }

    private void drawReel(DrawContext context, int x, int y, int w, int h, int reelIndex) {
        context.fill(x + 1, y + 1, x + w - 1, y + h - 1, REEL_BG);
        context.fill(x, y, x + w, y + 1, REEL_BORDER);
        context.fill(x, y + h - 1, x + w, y + h, REEL_BORDER);
        context.fill(x, y, x + 1, y + h, REEL_BORDER);
        context.fill(x + w - 1, y, x + w, y + h, REEL_BORDER);

        int rowH = h / 3;
        for (int row = -1; row <= 1; row++) {
            int sy = y + (row + 1) * rowH;
            SlotMachineSession.Symbol symbol = session.symbolAt(reelIndex, row);
            drawSymbolCentered(context, symbol, x + 1, sy + 1, w - 2, rowH - 2);

            if (row == 0) {
                context.fill(x + 1, sy + 1, x + w - 1, sy + 2, 0xAAFFD98A);
                context.fill(x + 1, sy + rowH - 2, x + w - 1, sy + rowH - 1, 0xAAFFD98A);
            }
        }
    }

    private void drawSymbolCentered(DrawContext context, SlotMachineSession.Symbol symbol, int x, int y, int w, int h) {
        int maxW = Math.max(8, w - 2);
        int maxH = Math.max(8, h - 2);
        float ratio = Math.min(maxW / (float) symbol.textureW(), maxH / (float) symbol.textureH());
        int drawW = Math.max(6, Math.round(symbol.textureW() * ratio));
        int drawH = Math.max(6, Math.round(symbol.textureH() * ratio));
        int sx = x + (w - drawW) / 2;
        int sy = y + (h - drawH) / 2;
        context.drawTexture(
            RenderPipelines.GUI_TEXTURED,
            symbol.texture(),
            sx,
            sy,
            0,
            0,
            drawW,
            drawH,
            symbol.textureW(),
            symbol.textureH(),
            symbol.textureW(),
            symbol.textureH(),
            0xFFFFFFFF
        );
    }

    private void drawFooter(DrawContext context, Layout l, int mouseX, int mouseY) {
        context.fill(l.panelX, l.panelY, l.panelX + l.panelW, l.panelY + l.panelH, PANEL_BG);
        context.fill(l.panelX, l.panelY, l.panelX + l.panelW, l.panelY + 1, PANEL_BORDER);
        context.fill(l.panelX, l.panelY + l.panelH - 1, l.panelX + l.panelW, l.panelY + l.panelH, PANEL_BORDER);
        context.fill(l.panelX, l.panelY, l.panelX + 1, l.panelY + l.panelH, PANEL_BORDER);
        context.fill(l.panelX + l.panelW - 1, l.panelY, l.panelX + l.panelW, l.panelY + l.panelH, PANEL_BORDER);

        TextRenderer tr = MinecraftClient.getInstance().textRenderer;
        int row1Y = l.panelY + 8;
        int row2Y = l.panelY + 22;
        String creditsText = "Credits: " + session.credits();
        String jackpotText = "Jackpot: " + session.jackpot();
        String betText = "Bet: " + session.bet();
        String wonText = "Won: " + session.totalWon() + "  Spent: " + session.totalSpent();
        int rightColumnMinX = l.betMaxX + l.betBtnW + 22;
        int rightColumnPreferredX = l.panelX + Math.max(132, l.panelW / 2);
        int rightColumnMaxX = l.panelX + l.panelW - 8 - Math.max(tr.getWidth(jackpotText), tr.getWidth(wonText));
        int rightColumnX = Math.min(Math.max(rightColumnMinX, rightColumnPreferredX), Math.max(rightColumnMinX, rightColumnMaxX));
        context.drawTextWithShadow(tr, creditsText, l.panelX + 8, row1Y, 0xFFDCE9FF);
        context.drawTextWithShadow(tr, betText, l.panelX + 8, row2Y, 0xFFDCE9FF);
        context.drawTextWithShadow(tr, jackpotText, rightColumnX, row1Y, 0xFFFFE69E);
        context.drawTextWithShadow(tr, wonText, rightColumnX, row2Y, 0xFFC0D4F2);

        drawBetButton(context, tr, l.betMinusX, l.betBtnY, l.betBtnW, l.betBtnH, "-");
        drawBetButton(context, tr, l.betPlusX, l.betBtnY, l.betBtnW, l.betBtnH, "+");
        drawBetButton(context, tr, l.betMaxX, l.betBtnY, l.betBtnW + 10, l.betBtnH, "MAX");

        Identifier spinTexture = inside(mouseX, mouseY, l.spinX, l.spinY, l.spinW, l.spinH) ? SPIN_PRESSED : SPIN;
        if (session.spinning()) spinTexture = SPIN_PRESSED;
        context.drawTexture(
            RenderPipelines.GUI_TEXTURED,
            spinTexture,
            l.spinX,
            l.spinY,
            0,
            0,
            l.spinW,
            l.spinH,
            SPIN_W,
            SPIN_H,
            SPIN_W,
            SPIN_H,
            0xFFFFFFFF
        );

        String[] table = session.paytableLines();
        int py = l.panelY + 40;
        for (int i = 0; i < table.length && i < 3; i++) {
            context.drawTextWithShadow(tr, table[i], l.panelX + 8, py, 0xFFBFD4F3);
            py += 10;
        }

        context.drawTextWithShadow(tr, session.status(), l.panelX + 8, l.panelY + l.panelH - 12, session.lastWin() > 0 ? 0xFFFFD88A : 0xFFE6EEF9);
    }

    private void drawBetButton(DrawContext context, TextRenderer tr, int x, int y, int w, int h, String txt) {
        context.fill(x, y, x + w, y + h, 0xCC274D78);
        context.fill(x, y, x + w, y + 1, 0xFF9ED0FF);
        context.fill(x, y + h - 1, x + w, y + h, 0xFF9ED0FF);
        context.fill(x, y, x + 1, y + h, 0xFF9ED0FF);
        context.fill(x + w - 1, y, x + w, y + h, 0xFF9ED0FF);
        int tx = x + (w - tr.getWidth(txt)) / 2;
        context.drawTextWithShadow(tr, txt, tx, y + 3, 0xFFFFFFFF);
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

        int maxX = Math.max(0, sw - windowW);
        int maxY = Math.max(0, sh - windowH);
        windowX = clamp(windowX, 0, maxX);
        windowY = clamp(windowY, 0, maxY);
    }

    private Layout computeLayout() {
        int headerH = 20;
        int panelH = 92;
        int pad = 8;

        int machineX = windowX + pad;
        int machineY = windowY + headerH + pad;
        int machineW = windowW - pad * 2;
        int machineH = windowH - headerH - panelH - pad * 3;

        int frameW = Math.max(220, Math.min(machineW - 20, (machineH * FRAME_W) / FRAME_H));
        int frameH = (frameW * FRAME_H) / FRAME_W;
        int frameX = machineX + (machineW - frameW) / 2;
        int frameY = machineY + Math.max(4, (machineH - frameH) / 2);

        int reelsX = frameX + (frameW * 6) / FRAME_W;
        int reelsY = frameY + (frameH * 6) / FRAME_H;
        int reelW = Math.max(18, (frameW * 69) / FRAME_W);
        int reelH = Math.max(18, (frameH * 69) / FRAME_H);
        int reelGap = Math.max(2, (frameW * 5) / FRAME_W);

        int spinW = Math.max(36, (frameW * SPIN_W) / FRAME_W);
        int spinH = Math.max(24, (frameH * SPIN_H) / FRAME_H);
        int spinX = frameX + frameW - spinW - Math.max(4, frameW / 45);
        int spinY = frameY + frameH - spinH - Math.max(4, frameH / 18);

        int panelX = machineX;
        int panelY = machineY + machineH + pad;
        int panelW = machineW;

        int btnW = 44;
        int btnH = 14;
        int closeX = windowX + windowW - btnW - 5;
        int pinX = closeX - btnW - 4;

        TextRenderer tr = MinecraftClient.getInstance().textRenderer;
        int betTextW = tr == null ? 44 : tr.getWidth("Bet: 0000");
        int betBtnY = panelY + 19;
        int betBtnW = 22;
        int betBtnH = 14;
        int betMinusX = panelX + 8 + betTextW + 12;
        int betPlusX = betMinusX + betBtnW + 6;
        int betMaxX = betPlusX + betBtnW + 6;

        return new Layout(
            windowX,
            windowY,
            windowW,
            windowH,
            headerH,
            machineX,
            machineY,
            machineW,
            machineH,
            frameX,
            frameY,
            frameW,
            frameH,
            reelsX,
            reelsY,
            reelW,
            reelH,
            reelGap,
            spinX,
            spinY,
            spinW,
            spinH,
            panelX,
            panelY,
            panelW,
            panelH,
            pinX,
            windowY + 3,
            closeX,
            windowY + 3,
            btnW,
            btnH,
            betMinusX,
            betPlusX,
            betMaxX,
            betBtnY,
            betBtnW,
            betBtnH,
            windowX + windowW - 12,
            windowY + windowH - 12,
            12
        );
    }

    private static boolean shouldRender(MinecraftClient mc, boolean pinned) {
        return mc != null && mc.player != null;
    }

    private static int scaledMouseX(MinecraftClient mc) {
        if (mc.getWindow() == null || mc.mouse == null) return 0;
        return (int) Math.round(mc.mouse.getX() * mc.getWindow().getScaledWidth() / (double) mc.getWindow().getWidth());
    }

    private static int scaledMouseY(MinecraftClient mc) {
        if (mc.getWindow() == null || mc.mouse == null) return 0;
        return (int) Math.round(mc.mouse.getY() * mc.getWindow().getScaledHeight() / (double) mc.getWindow().getHeight());
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static boolean inside(int px, int py, int x, int y, int w, int h) {
        return px >= x && px <= x + w && py >= y && py <= y + h;
    }

    private record Layout(
        int x,
        int y,
        int w,
        int h,
        int headerH,
        int machineX,
        int machineY,
        int machineW,
        int machineH,
        int frameX,
        int frameY,
        int frameW,
        int frameH,
        int reelsX,
        int reelsY,
        int reelW,
        int reelH,
        int reelGap,
        int spinX,
        int spinY,
        int spinW,
        int spinH,
        int panelX,
        int panelY,
        int panelW,
        int panelH,
        int pinX,
        int pinY,
        int closeX,
        int closeY,
        int btnW,
        int btnH,
        int betMinusX,
        int betPlusX,
        int betMaxX,
        int betBtnY,
        int betBtnW,
        int betBtnH,
        int resizeX,
        int resizeY,
        int resizeSize
    ) {
    }
}

