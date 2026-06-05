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
final class RussianRouletteWindow {
    private static final int PANEL_BG = 0xD0141A26;
    private static final int PANEL_BORDER = 0xFF4A6A91;
    private static final int HEADER_BG = 0xD82A1A1A;
    private static final int HEADER_BORDER = 0xFFBC7A6F;
    private static final int BODY_BG = 0xD110131B;
    private static final int BODY_BORDER = 0xFF3D4E70;
    private static final int CHAMBER_BG = 0xFF1D2733;
    private static final int CHAMBER_BORDER = 0xFF678AB6;
    private static final int CHAMBER_ACTIVE = 0xFFF0CE6E;
    private static final int CHAMBER_SPENT = 0xAA000000;
    private static final Identifier BULLET = Identifier.of("devils-game", "textures/games/roulette/bullet.png");
    private static final Identifier PISTOL = Identifier.of("devils-game", "textures/games/roulette/pistol.png");
    private static final Identifier SKULL_ALIVE = Identifier.of("devils-game", "textures/games/roulette/skull_alive.png");
    private static final Identifier SKULL_LOST = Identifier.of("devils-game", "textures/games/roulette/skull_lost.png");
    private static final int BULLET_W = 14;
    private static final int BULLET_H = 38;
    private static final int PISTOL_W = 66;
    private static final int PISTOL_H = 27;
    private static final int SKULL_W = 115;
    private static final int SKULL_H = 139;
    private final int minW;
    private final int minH;
    private final int maxW;
    private final int maxH;
    private final RussianRouletteSession session = new RussianRouletteSession();
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
    RussianRouletteWindow(int minW, int minH, int maxW, int maxH) {
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
    void onTick(MinecraftClient mc) {
        session.onTick(mc);
    }
    void render(DrawContext context, MinecraftClient mc, boolean pinned) {
        if (!shouldRender(mc, pinned)) return;
        int mouseX = scaledMouseX(mc);
        int mouseY = scaledMouseY(mc);
        updateWindowTransform(mc, mouseX, mouseY);
        Layout l = computeLayout();
        context.fill(l.x, l.y, l.x + l.w, l.y + l.h, PANEL_BG);
        drawHeader(context, l, pinned);
        drawBody(context, l);
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
        int titleW = mc != null && mc.textRenderer != null ? mc.textRenderer.getWidth("Russian Roulette") : 90;
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
        if (inside(mouseX, mouseY, l.spinX, l.btnRowY, l.actionBtnW, l.actionBtnH)) {
            session.startSpin();
            return true;
        }
        if (inside(mouseX, mouseY, l.triggerX, l.btnRowY, l.actionBtnW, l.actionBtnH)) {
            session.pullTrigger(mc);
            return true;
        }
        if (inside(mouseX, mouseY, l.restartX, l.btnRowY, l.actionBtnW, l.actionBtnH)) {
            session.reset();
            return true;
        }
        return true;
    }
    private void drawHeader(DrawContext context, Layout l, boolean pinned) {
        context.fill(l.x, l.y, l.x + l.w, l.y + l.headerH, HEADER_BG);
        context.fill(l.x, l.y, l.x + l.w, l.y + 1, HEADER_BORDER);
        context.fill(l.x, l.y + l.headerH - 1, l.x + l.w, l.y + l.headerH, HEADER_BORDER);
        TextRenderer tr = MinecraftClient.getInstance().textRenderer;
        context.drawTextWithShadow(tr, "Russian Roulette", l.x + 6, l.y + 4, 0xFFFFFFFF);
        drawHeaderButton(context, l.pinX, l.pinY, l.btnW, l.btnH, pinned ? "Unpin" : "Pin");
        drawHeaderButton(context, l.closeX, l.closeY, l.btnW, l.btnH, "X");
    }
    private void drawBody(DrawContext context, Layout l) {
        context.fill(l.bodyX, l.bodyY, l.bodyX + l.bodyW, l.bodyY + l.bodyH, BODY_BG);
        context.fill(l.bodyX, l.bodyY, l.bodyX + l.bodyW, l.bodyY + 1, BODY_BORDER);
        context.fill(l.bodyX, l.bodyY + l.bodyH - 1, l.bodyX + l.bodyW, l.bodyY + l.bodyH, BODY_BORDER);
        context.fill(l.bodyX, l.bodyY, l.bodyX + 1, l.bodyY + l.bodyH, BODY_BORDER);
        context.fill(l.bodyX + l.bodyW - 1, l.bodyY, l.bodyX + l.bodyW, l.bodyY + l.bodyH, BODY_BORDER);
        drawPistol(context, l);
        drawCylinder(context, l);
        drawLives(context, l);
        if (session.spinning()) {
            int pulse = (int) ((System.currentTimeMillis() / 60L) % 2L);
            int color = pulse == 0 ? 0xAA274D78 : 0xAA4A79AE;
            context.fill(l.cylinderLabelX - 4, l.cylinderLabelY - 2, l.cylinderLabelX + 150, l.cylinderLabelY + 11, color);
        }
    }
    private void drawPistol(DrawContext context, Layout l) {
        int rightLimit = l.chamberX - 14;
        int freeW = Math.max(84, rightLimit - (l.bodyX + 18));
        int targetW = Math.min(Math.max(90, l.bodyW / 3), freeW);
        int targetH = Math.max(46, (targetW * PISTOL_H) / PISTOL_W);
        int px = l.bodyX + 18;
        int py = l.bodyY + 20;
        context.drawTexture(
            RenderPipelines.GUI_TEXTURED,
            PISTOL,
            px,
            py,
            0,
            0,
            targetW,
            targetH,
            PISTOL_W,
            PISTOL_H,
            PISTOL_W,
            PISTOL_H,
            0xFFFFFFFF
        );
    }
    private void drawCylinder(DrawContext context, Layout l) {
        TextRenderer tr = MinecraftClient.getInstance().textRenderer;
        context.drawTextWithShadow(tr, "Cylinder (6 chambers)", l.cylinderLabelX, l.cylinderLabelY, 0xFFE8F1FF);
        for (int i = 0; i < RussianRouletteSession.CYLINDER_SIZE; i++) {
            int x = l.chamberX + i * (l.chamberSize + l.chamberGap);
            int y = l.chamberY;
            boolean active = i == session.currentChamber() && session.canTrigger();
            boolean spent = session.isSpent(i);
            int border = active ? CHAMBER_ACTIVE : CHAMBER_BORDER;
            context.fill(x, y, x + l.chamberSize, y + l.chamberSize, CHAMBER_BG);
            context.fill(x, y, x + l.chamberSize, y + 1, border);
            context.fill(x, y + l.chamberSize - 1, x + l.chamberSize, y + l.chamberSize, border);
            context.fill(x, y, x + 1, y + l.chamberSize, border);
            context.fill(x + l.chamberSize - 1, y, x + l.chamberSize, y + l.chamberSize, border);
            int bw = Math.max(8, l.chamberSize / 3);
            int bh = Math.max(14, (bw * BULLET_H) / BULLET_W);
            int bx = x + (l.chamberSize - bw) / 2;
            int by = y + (l.chamberSize - bh) / 2;
            context.drawTexture(
                RenderPipelines.GUI_TEXTURED,
                BULLET,
                bx,
                by,
                0,
                0,
                bw,
                bh,
                BULLET_W,
                BULLET_H,
                BULLET_W,
                BULLET_H,
                spent ? 0x55FFFFFF : 0xFFFFFFFF
            );
            if (spent) context.fill(x, y, x + l.chamberSize, y + l.chamberSize, CHAMBER_SPENT);
        }
        String chamberText = "Ready: " + (session.canTrigger() ? "YES" : "NO") + " | Fired: " + session.chambersUsed() + "/" + RussianRouletteSession.CYLINDER_SIZE;
        context.drawTextWithShadow(tr, chamberText, l.chamberX, l.chamberY + l.chamberSize + 8, 0xFFD3E8FF);
    }
    private void drawLives(DrawContext context, Layout l) {
        TextRenderer tr = MinecraftClient.getInstance().textRenderer;
        context.drawTextWithShadow(tr, "Lives", l.livesX, l.livesY - 12, 0xFFE8F1FF);
        for (int i = 0; i < RussianRouletteSession.MAX_LIVES; i++) {
            int x = l.livesX + i * (l.lifeSize + 8);
            int y = l.livesY;
            Identifier skull = i < session.lives() ? SKULL_ALIVE : SKULL_LOST;
            context.drawTexture(
                RenderPipelines.GUI_TEXTURED,
                skull,
                x,
                y,
                0,
                0,
                l.lifeSize,
                l.lifeSize,
                SKULL_W,
                SKULL_H,
                SKULL_W,
                SKULL_H,
                0xFFFFFFFF
            );
        }
    }
    private void drawFooter(DrawContext context, Layout l, int mouseX, int mouseY) {
        context.fill(l.footerX, l.footerY, l.footerX + l.footerW, l.footerY + l.footerH, PANEL_BG);
        context.fill(l.footerX, l.footerY, l.footerX + l.footerW, l.footerY + 1, PANEL_BORDER);
        context.fill(l.footerX, l.footerY + l.footerH - 1, l.footerX + l.footerW, l.footerY + l.footerH, PANEL_BORDER);
        context.fill(l.footerX, l.footerY, l.footerX + 1, l.footerY + l.footerH, PANEL_BORDER);
        context.fill(l.footerX + l.footerW - 1, l.footerY, l.footerX + l.footerW, l.footerY + l.footerH, PANEL_BORDER);
        TextRenderer tr = MinecraftClient.getInstance().textRenderer;
        context.drawTextWithShadow(tr, session.status(), l.footerX + 8, l.footerY + 7, 0xFFFFE8A8);
        int spinColor = session.canSpin() ? 0xFFFFFFFF : 0xFF8CA7C9;
        int triggerColor = session.canTrigger() ? 0xFFFFFFFF : 0xFF8CA7C9;
        drawActionButton(context, mouseX, mouseY, l.spinX, l.btnRowY, l.actionBtnW, l.actionBtnH, "Spin", spinColor);
        drawActionButton(context, mouseX, mouseY, l.triggerX, l.btnRowY, l.actionBtnW, l.actionBtnH, "Trigger", triggerColor);
        drawActionButton(context, mouseX, mouseY, l.restartX, l.btnRowY, l.actionBtnW, l.actionBtnH, "Restart", 0xFFFFFFFF);
    }
    private void drawActionButton(DrawContext context, int mouseX, int mouseY, int x, int y, int w, int h, String text, int textColor) {
        int hover = inside(mouseX, mouseY, x, y, w, h) ? 0xFF3B5F8B : 0xFF274D78;
        context.fill(x, y, x + w, y + h, 0xCC000000 | hover);
        context.fill(x, y, x + w, y + 1, 0xFF9ED0FF);
        context.fill(x, y + h - 1, x + w, y + h, 0xFF9ED0FF);
        context.fill(x, y, x + 1, y + h, 0xFF9ED0FF);
        context.fill(x + w - 1, y, x + w, y + h, 0xFF9ED0FF);
        TextRenderer tr = MinecraftClient.getInstance().textRenderer;
        int tx = x + (w - tr.getWidth(text)) / 2;
        context.drawTextWithShadow(tr, text, tx, y + 4, textColor);
    }
    private void drawHeaderButton(DrawContext context, int x, int y, int w, int h, String text) {
        context.fill(x, y, x + w, y + h, 0xCC274D78);
        context.fill(x, y, x + w, y + 1, 0xFF9ED0FF);
        context.fill(x, y + h - 1, x + w, y + h, 0xFF9ED0FF);
        TextRenderer tr = MinecraftClient.getInstance().textRenderer;
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
        int footerH = 68;
        int pad = 8;
        int bodyX = windowX + pad;
        int bodyY = windowY + headerH + pad;
        int bodyW = windowW - pad * 2;
        int bodyH = windowH - headerH - footerH - pad * 3;
        int footerX = bodyX;
        int footerY = bodyY + bodyH + pad;
        int footerW = bodyW;
        int btnW = 44;
        int btnH = 14;
        int closeX = windowX + windowW - btnW - 5;
        int pinX = closeX - btnW - 4;
        int chamberSize = Math.max(24, Math.min(36, bodyW / 16));
        int chamberGap = Math.max(6, chamberSize / 5);
        int cylinderW = (chamberSize * RussianRouletteSession.CYLINDER_SIZE) + chamberGap * (RussianRouletteSession.CYLINDER_SIZE - 1);
        int chamberX = bodyX + bodyW - cylinderW - 18;
        int chamberY = bodyY + 34;
        int cylinderLabelX = chamberX;
        int cylinderLabelY = chamberY - 14;
        int lifeSize = Math.max(26, Math.min(40, bodyW / 19));
        int livesX = chamberX;
        int livesY = chamberY + chamberSize + 34;
        int actionBtnW = Math.max(88, Math.min(130, bodyW / 5));
        int actionBtnH = 18;
        int rowY = footerY + footerH - actionBtnH - 8;
        int spinX = footerX + 8;
        int triggerX = spinX + actionBtnW + 8;
        int restartX = triggerX + actionBtnW + 8;
        return new Layout(
            windowX,
            windowY,
            windowW,
            windowH,
            headerH,
            bodyX,
            bodyY,
            bodyW,
            bodyH,
            footerX,
            footerY,
            footerW,
            footerH,
            pinX,
            windowY + 3,
            closeX,
            windowY + 3,
            btnW,
            btnH,
            cylinderLabelX,
            cylinderLabelY,
            chamberX,
            chamberY,
            chamberSize,
            chamberGap,
            livesX,
            livesY,
            lifeSize,
            spinX,
            triggerX,
            restartX,
            rowY,
            actionBtnW,
            actionBtnH,
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

    boolean consumeCloseAllRequested() {
        return session.consumeCloseAllRequested();
    }
    RussianRouletteSession.LossOutcome lossOutcome() {
        return session.lossOutcome();
    }
    void executeKill(MinecraftClient mc) {
        session.executeKill(mc);
    }
    void executeCrashNow() {
        session.executeCrashNow();
    }
    void resetSessionState() {
        session.reset();
    }
    private record Layout(
        int x,
        int y,
        int w,
        int h,
        int headerH,
        int bodyX,
        int bodyY,
        int bodyW,
        int bodyH,
        int footerX,
        int footerY,
        int footerW,
        int footerH,
        int pinX,
        int pinY,
        int closeX,
        int closeY,
        int btnW,
        int btnH,
        int cylinderLabelX,
        int cylinderLabelY,
        int chamberX,
        int chamberY,
        int chamberSize,
        int chamberGap,
        int livesX,
        int livesY,
        int lifeSize,
        int spinX,
        int triggerX,
        int restartX,
        int btnRowY,
        int actionBtnW,
        int actionBtnH,
        int resizeX,
        int resizeY,
        int resizeSize
    ) {
    }
}

