package com.devils.addon.modules.games;

import com.devils.addon.games.MiniGamesContracts.SessionView;
import com.devils.addon.games.checkers.CheckersLogic;
import com.devils.addon.games.checkers.CheckersLogic.Coord;
import com.devils.addon.games.checkers.CheckersLogic.Move;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import meteordevelopment.meteorclient.events.meteor.MouseClickEvent;
import meteordevelopment.meteorclient.utils.misc.input.KeyAction;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.util.Identifier;

final class CheckersOverlayWindow {
    private static final int DARK_BG = 0xCF0D121B;
    private static final int HEADER_BG = 0xD81A2D46;
    private static final int HEADER_BORDER = 0xFF5F87B8;
    private static final int PANEL_BG = 0xD0111C2E;
    private static final int PANEL_BORDER = 0xFF3E5B82;
    private static final int FRAME_OUTER = 0xFF1A1208;
    private static final int FRAME_INNER = 0xFF5B3B1D;
    private static final int CHESS_SQUARE_TEXTURE_SIZE = 256;
    private static final int CHECKER_PIECE_TEXTURE_SIZE = 315;
    private static final Identifier SQUARE_LIGHT = Identifier.of("devils-game", "textures/games/chess/square_light.png");
    private static final Identifier SQUARE_DARK = Identifier.of("devils-game", "textures/games/chess/square_dark.png");
    private static final Identifier WHITE_PIECE = Identifier.of("devils-game", "textures/games/checkers/piece_white.png");
    private static final Identifier BLACK_PIECE = Identifier.of("devils-game", "textures/games/checkers/piece_black.png");
    private static final int PATH_OVERLAY = 0x77F0D769;
    private static final int HINT_OVERLAY = 0xA04DD6FF;
    private static final int CAPTURE_OVERLAY = 0x66CF4E39;
    private static final int CAPTURE_BORDER = 0xFFCF4E39;

    private final int minW;
    private final int minH;
    private final int maxW;
    private final int maxH;

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

    CheckersOverlayWindow(int minW, int minH, int maxW, int maxH) {
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

    void stopInteraction() {
        dragging = false;
        resizing = false;
    }

    void render(
        DrawContext context,
        MinecraftClient mc,
        CheckersOverlay.PlayMode mode,
        boolean pinned,
        CheckersOverlaySession session,
        int scriptLevel
    ) {
        if (!shouldRender(mc, pinned)) return;
        int mouseX = scaledMouseX(mc);
        int mouseY = scaledMouseY(mc);
        updateWindowTransform(mc, mouseX, mouseY);

        SessionView syncSession = session.prepare(mode);
        Layout l = computeLayout();
        boolean whiteBottom = session.resolveOrientation(mode, syncSession);
        List<Move> legal = CheckersLogic.legalMoves(session.boardState());
        char[][] matrix = CheckersLogic.board(session.boardState());
        List<Coord> captureTargets = session.collectCaptureTargets(matrix, legal);
        CheckersOverlaySession.AnimatedPiece animated = session.animatedPiece();

        context.fill(l.x, l.y, l.x + l.w, l.y + l.h, DARK_BG);
        drawHeader(context, mc.textRenderer, l, mode, pinned, whiteBottom, scriptLevel);
        drawBoard(context, mc.textRenderer, l, whiteBottom, legal, captureTargets, session.collectHints(legal), session.stagedPath());
        drawPieces(context, l, whiteBottom, matrix, animated, session);
        drawFooter(context, mc.textRenderer, l, mode, syncSession, session.statusText(), session.boardState());
        drawResizeHandle(context, l, mouseX, mouseY);
    }

    boolean onMouse(
        MouseClickEvent event,
        MinecraftClient mc,
        CheckersOverlay.PlayMode mode,
        boolean pinned,
        int scriptLevel,
        Consumer<Boolean> setPinned,
        Consumer<CheckersOverlay.PlayMode> setMode,
        IntConsumer setScriptLevel,
        Runnable cycleGame,
        Runnable closeOverlay,
        CheckersOverlaySession session
    ) {
        if (!shouldRender(mc, pinned)) return false;

        int mouseX = scaledMouseX(mc);
        int mouseY = scaledMouseY(mc);
        if (event.button() != 0 && event.button() != 1) return false;

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
        int titleW = mc != null && mc.textRenderer != null ? mc.textRenderer.getWidth("Checkers") : 54;
        if (inside(mouseX, mouseY, l.x + 6, l.y + 2, titleW + 2, l.btnH)) {
            cycleGame.run();
            return true;
        }
        if (inside(mouseX, mouseY, l.modeX, l.modeY, l.btnW, l.btnH)) {
            if (mode == CheckersOverlay.PlayMode.SCRIPT) {
                if (event.button() == 0) {
                    setScriptLevel.accept(scriptLevel >= 7 ? 1 : scriptLevel + 1);
                } else {
                    setMode.accept(CheckersOverlay.PlayMode.SYNC);
                    session.onActivate(CheckersOverlay.PlayMode.SYNC);
                }
            } else {
                setMode.accept(CheckersOverlay.PlayMode.SCRIPT);
                session.onActivate(CheckersOverlay.PlayMode.SCRIPT);
            }
            return true;
        }
        if (inside(mouseX, mouseY, l.sideX, l.sideY, l.btnW, l.btnH)) {
            session.toggleViewSide(mode);
            return true;
        }
        if (inside(mouseX, mouseY, l.resetX, l.resetY, l.btnW, l.btnH)) {
            session.onActivate(mode);
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
        if (inside(mouseX, mouseY, l.boardX, l.boardY, l.boardSize, l.boardSize)) {
            SessionView syncSession = session.prepare(mode);
            boolean whiteBottom = session.resolveOrientation(mode, syncSession);
            int drawX = (mouseX - l.boardX) / l.cell;
            int drawY = (mouseY - l.boardY) / l.cell;
            int boardX = whiteBottom ? drawX : 7 - drawX;
            int boardY = whiteBottom ? drawY : 7 - drawY;
            session.onBoardClick(mode, boardX, boardY, syncSession);
            return true;
        }

        session.clearStagedPath();
        return true;
    }

    private void drawHeader(
        DrawContext context,
        TextRenderer tr,
        Layout l,
        CheckersOverlay.PlayMode mode,
        boolean pinned,
        boolean whiteBottom,
        int scriptLevel
    ) {
        context.fill(l.x, l.y, l.x + l.w, l.y + l.headerH, HEADER_BG);
        context.fill(l.x, l.y, l.x + l.w, l.y + 1, HEADER_BORDER);
        context.fill(l.x, l.y + l.headerH - 1, l.x + l.w, l.y + l.headerH, HEADER_BORDER);
        context.drawTextWithShadow(tr, "Checkers", l.x + 6, l.y + 4, 0xFFFFFFFF);
        drawHeaderButton(context, tr, l.pinX, l.pinY, l.btnW, l.btnH, pinned ? "Unpin" : "Pin");
        String modeText = mode == CheckersOverlay.PlayMode.SCRIPT ? ("L" + scriptLevel) : "Sync";
        drawHeaderButton(context, tr, l.modeX, l.modeY, l.btnW, l.btnH, modeText);
        drawHeaderButton(context, tr, l.sideX, l.sideY, l.btnW, l.btnH, whiteBottom ? "White" : "Black");
        drawHeaderButton(context, tr, l.resetX, l.resetY, l.btnW, l.btnH, "Reset");
        drawHeaderButton(context, tr, l.closeX, l.closeY, l.btnW, l.btnH, "X");
    }

    private void drawBoard(
        DrawContext context,
        TextRenderer tr,
        Layout l,
        boolean whiteBottom,
        List<Move> legal,
        List<Coord> captureTargets,
        List<Coord> hints,
        List<Coord> stagedPath
    ) {
        context.fill(l.boardX - 6, l.boardY - 6, l.boardX + l.boardSize + 6, l.boardY + l.boardSize + 6, FRAME_OUTER);
        context.fill(l.boardX - 2, l.boardY - 2, l.boardX + l.boardSize + 2, l.boardY + l.boardSize + 2, FRAME_INNER);

        for (int dy = 0; dy < 8; dy++) {
            for (int dx = 0; dx < 8; dx++) {
                Identifier tile = ((dx + dy) & 1) == 1 ? SQUARE_DARK : SQUARE_LIGHT;
                int x1 = l.boardX + dx * l.cell;
                int y1 = l.boardY + dy * l.cell;
                context.drawTexture(
                    RenderPipelines.GUI_TEXTURED, tile, x1, y1, 0, 0, l.cell, l.cell,
                    CHESS_SQUARE_TEXTURE_SIZE, CHESS_SQUARE_TEXTURE_SIZE, CHESS_SQUARE_TEXTURE_SIZE, CHESS_SQUARE_TEXTURE_SIZE, 0xFFFFFFFF
                );
            }
        }

        for (Coord capture : captureTargets) {
            drawCellOverlay(context, l, whiteBottom, capture.x(), capture.y(), CAPTURE_OVERLAY);
            drawCellBorder(context, l, whiteBottom, capture.x(), capture.y(), CAPTURE_BORDER);
        }
        for (Coord staged : stagedPath) {
            drawCellOverlay(context, l, whiteBottom, staged.x(), staged.y(), PATH_OVERLAY);
            drawCellBorder(context, l, whiteBottom, staged.x(), staged.y(), 0xFFE0CA6E);
        }
        for (Coord hint : hints) {
            int dx = toDrawX(hint.x(), whiteBottom);
            int dy = toDrawY(hint.y(), whiteBottom);
            int cx = l.boardX + dx * l.cell + l.cell / 2;
            int cy = l.boardY + dy * l.cell + l.cell / 2;
            drawDot(context, cx, cy, Math.max(4, l.cell / 8), HINT_OVERLAY);
        }
        drawCoords(context, l, whiteBottom, tr);
    }

    private void drawCoords(DrawContext context, Layout l, boolean whiteBottom, TextRenderer tr) {
        if (tr == null) return;
        for (int i = 0; i < 8; i++) {
            int file = whiteBottom ? i : 7 - i;
            int rank = whiteBottom ? 7 - i : i;
            String fileLabel = String.valueOf((char) ('A' + file));
            String rankLabel = String.valueOf(rank + 1);
            int fx = l.boardX + i * l.cell + l.cell / 2 - tr.getWidth(fileLabel) / 2;
            int fy = l.boardY + l.boardSize + 2;
            context.drawTextWithShadow(tr, fileLabel, fx, fy, 0xFFF1E4CD);
            int rx = l.boardX - 13;
            int ry = l.boardY + i * l.cell + l.cell / 2 - 4;
            context.drawTextWithShadow(tr, rankLabel, rx, ry, 0xFFF1E4CD);
        }
    }

    private void drawPieces(
        DrawContext context,
        Layout l,
        boolean whiteBottom,
        char[][] matrix,
        CheckersOverlaySession.AnimatedPiece animated,
        CheckersOverlaySession session
    ) {
        for (int by = 0; by < 8; by++) {
            for (int bx = 0; bx < 8; bx++) {
                char piece = matrix[by][bx];
                if (piece == '.') continue;
                if (session.shouldSkipStaticPiece(bx, by)) continue;
                int dx = toDrawX(bx, whiteBottom);
                int dy = toDrawY(by, whiteBottom);
                int cx = l.boardX + dx * l.cell + l.cell / 2;
                int cy = l.boardY + dy * l.cell + l.cell / 2;
                boolean whitePiece = piece == 'w' || piece == 'W';
                boolean king = piece == 'W' || piece == 'B';
                Identifier texture = whitePiece ? WHITE_PIECE : BLACK_PIECE;
                int inset = Math.max(2, l.cell / 14);
                int px = l.boardX + dx * l.cell + inset;
                int py = l.boardY + dy * l.cell + inset;
                int size = l.cell - inset * 2;
                context.drawTexture(
                    RenderPipelines.GUI_TEXTURED, texture, px, py, 0, 0, size, size,
                    CHECKER_PIECE_TEXTURE_SIZE, CHECKER_PIECE_TEXTURE_SIZE, CHECKER_PIECE_TEXTURE_SIZE, CHECKER_PIECE_TEXTURE_SIZE, 0xFFFFFFFF
                );
                if (king) {
                    int outer = Math.max(5, l.cell / 5);
                    int inner = Math.max(3, outer - 2);
                    int center = whitePiece ? 0xFFEFEFEF : 0xFF141414;
                    drawDot(context, cx, cy, outer, 0xCCDAAA47);
                    drawDot(context, cx, cy, inner, center);
                    drawDot(context, cx, cy, Math.max(2, inner / 3), 0xFFDAAA47);
                }
            }
        }

        if (animated != null) {
            float boardX = whiteBottom ? animated.x() : 7f - animated.x();
            float boardY = whiteBottom ? animated.y() : 7f - animated.y();
            int cellX = l.boardX + Math.round(boardX * l.cell);
            int cellY = l.boardY + Math.round(boardY * l.cell);
            boolean whitePiece = animated.piece() == 'w' || animated.piece() == 'W';
            boolean king = animated.piece() == 'W' || animated.piece() == 'B';
            Identifier texture = whitePiece ? WHITE_PIECE : BLACK_PIECE;
            int inset = Math.max(2, l.cell / 14);
            int px = cellX + inset;
            int py = cellY + inset;
            int size = l.cell - inset * 2;
            context.drawTexture(
                RenderPipelines.GUI_TEXTURED, texture, px, py, 0, 0, size, size,
                CHECKER_PIECE_TEXTURE_SIZE, CHECKER_PIECE_TEXTURE_SIZE, CHECKER_PIECE_TEXTURE_SIZE, CHECKER_PIECE_TEXTURE_SIZE, 0xFFFFFFFF
            );
            if (king) {
                int cx = cellX + l.cell / 2;
                int cy = cellY + l.cell / 2;
                int outer = Math.max(5, l.cell / 5);
                int inner = Math.max(3, outer - 2);
                int center = whitePiece ? 0xFFEFEFEF : 0xFF141414;
                drawDot(context, cx, cy, outer, 0xCCDAAA47);
                drawDot(context, cx, cy, inner, center);
                drawDot(context, cx, cy, Math.max(2, inner / 3), 0xFFDAAA47);
            }
        }
    }

    private void drawFooter(
        DrawContext context,
        TextRenderer tr,
        Layout l,
        CheckersOverlay.PlayMode mode,
        SessionView session,
        String status,
        String boardState
    ) {
        context.fill(l.panelX, l.panelY, l.panelX + l.panelW, l.panelY + l.panelH, PANEL_BG);
        context.fill(l.panelX, l.panelY, l.panelX + l.panelW, l.panelY + 1, PANEL_BORDER);
        context.fill(l.panelX, l.panelY + l.panelH - 1, l.panelX + l.panelW, l.panelY + l.panelH, PANEL_BORDER);
        context.drawTextWithShadow(tr, "Mode: " + (mode == CheckersOverlay.PlayMode.SYNC ? "Game Sync" : "Script"), l.panelX + 8, l.panelY + 7, 0xFFE9F2FF);
        context.drawTextWithShadow(tr, "Turn: " + (CheckersLogic.isWhiteTurn(boardState) ? "White" : "Black"), l.panelX + 8, l.panelY + 20, 0xFFE9F2FF);
        if (status != null && !status.isBlank()) context.drawTextWithShadow(tr, status, l.panelX + 8, l.panelY + 35, 0xFFFFE7A4);
        if (mode == CheckersOverlay.PlayMode.SYNC && session != null && session.active()) {
            String side = session.localWhite() ? "You: White" : "You: Black";
            context.drawTextWithShadow(tr, side, l.panelX + 8, l.panelY + 50, 0xFFC6DEFF);
            context.drawTextWithShadow(tr, "Opp: " + safe(session.opponentName()), l.panelX + 110, l.panelY + 50, 0xFFC6DEFF);
        } else if (mode == CheckersOverlay.PlayMode.SCRIPT) {
            context.drawTextWithShadow(tr, "Pinned window: chat + game together.", l.panelX + 8, l.panelY + 50, 0xFFC6DEFF);
        }
    }

    private void drawResizeHandle(DrawContext context, Layout l, int mouseX, int mouseY) {
        int color = inside(mouseX, mouseY, l.resizeX, l.resizeY, l.resizeSize, l.resizeSize) ? 0xFF9FD4FF : 0xFF5A86B4;
        context.fill(l.resizeX, l.resizeY + l.resizeSize - 2, l.resizeX + l.resizeSize, l.resizeY + l.resizeSize, color);
        context.fill(l.resizeX + l.resizeSize - 2, l.resizeY, l.resizeX + l.resizeSize, l.resizeY + l.resizeSize, color);
    }

    private void drawHeaderButton(DrawContext context, TextRenderer tr, int x, int y, int w, int h, String text) {
        context.fill(x, y, x + w, y + h, 0xCC274D78);
        context.fill(x, y, x + w, y + 1, 0xFF9ED0FF);
        context.fill(x, y + h - 1, x + w, y + h, 0xFF9ED0FF);
        int tx = x + (w - tr.getWidth(text)) / 2;
        context.drawTextWithShadow(tr, text, tx, y + 3, 0xFFFFFFFF);
    }

    private void drawCellOverlay(DrawContext context, Layout l, boolean whiteBottom, int boardX, int boardY, int color) {
        int dx = toDrawX(boardX, whiteBottom);
        int dy = toDrawY(boardY, whiteBottom);
        int x1 = l.boardX + dx * l.cell;
        int y1 = l.boardY + dy * l.cell;
        context.fill(x1, y1, x1 + l.cell, y1 + l.cell, color);
    }

    private void drawCellBorder(DrawContext context, Layout l, boolean whiteBottom, int boardX, int boardY, int color) {
        int dx = toDrawX(boardX, whiteBottom);
        int dy = toDrawY(boardY, whiteBottom);
        int x1 = l.boardX + dx * l.cell;
        int y1 = l.boardY + dy * l.cell;
        int x2 = x1 + l.cell;
        int y2 = y1 + l.cell;
        context.fill(x1, y1, x2, y1 + 2, color);
        context.fill(x1, y2 - 2, x2, y2, color);
        context.fill(x1, y1, x1 + 2, y2, color);
        context.fill(x2 - 2, y1, x2, y2, color);
    }

    private void drawDot(DrawContext context, int cx, int cy, int radius, int color) {
        for (int dy = -radius; dy <= radius; dy++) {
            int dx = (int) Math.sqrt((radius * radius) - (dy * dy));
            context.fill(cx - dx, cy + dy, cx + dx + 1, cy + dy + 1, color);
        }
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
        int pad = 8;
        int panelH = 70;
        int boardSpaceW = windowW - pad * 2;
        int boardSpaceH = windowH - headerH - panelH - pad * 3 - 18;
        int cell = Math.max(24, Math.min(boardSpaceW / 8, boardSpaceH / 8));
        int boardSize = cell * 8;
        int boardX = windowX + (windowW - boardSize) / 2;
        int boardY = windowY + headerH + pad + Math.max(0, (boardSpaceH - boardSize) / 2);
        int panelY = boardY + boardSize + 18;
        int btnW = 42;
        int btnH = 14;
        int closeX = windowX + windowW - btnW - 5;
        int resetX = closeX - btnW - 4;
        int sideX = resetX - btnW - 4;
        int modeX = sideX - btnW - 4;
        int pinX = modeX - btnW - 4;
        return new Layout(
            windowX, windowY, windowW, windowH, headerH,
            boardX, boardY, cell, boardSize,
            boardX, panelY, boardSize, panelH,
            pinX, windowY + 3, modeX, windowY + 3, sideX, windowY + 3, resetX, windowY + 3, closeX, windowY + 3, btnW, btnH,
            windowX + windowW - 12, windowY + windowH - 12, 12
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

    private static int toDrawX(int boardX, boolean whiteBottom) {
        return whiteBottom ? boardX : 7 - boardX;
    }

    private static int toDrawY(int boardY, boolean whiteBottom) {
        return whiteBottom ? boardY : 7 - boardY;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static boolean inside(int px, int py, int x, int y, int w, int h) {
        return px >= x && px <= x + w && py >= y && py <= y + h;
    }

    private static String safe(String value) {
        return value == null || value.isBlank() ? "<unknown>" : value;
    }

    private record Layout(
        int x, int y, int w, int h, int headerH,
        int boardX, int boardY, int cell, int boardSize,
        int panelX, int panelY, int panelW, int panelH,
        int pinX, int pinY, int modeX, int modeY, int sideX, int sideY, int resetX, int resetY, int closeX, int closeY, int btnW, int btnH,
        int resizeX, int resizeY, int resizeSize
    ) {
    }
}

