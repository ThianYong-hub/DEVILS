package com.devils.addon.modules.games;
import com.devils.addon.games.MiniGamesContracts.SessionView;
import com.devils.addon.games.chess.ChessLogic;
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
final class ChessOverlayWindow {
    private static final int DARK_BG = 0xCF0D121B;
    private static final int HEADER_BG = 0xD81A2D46;
    private static final int HEADER_BORDER = 0xFF5F87B8;
    private static final int PANEL_BG = 0xD0111C2E;
    private static final int PANEL_BORDER = 0xFF3E5B82;
    private static final int FRAME_OUTER = 0xFF1A1208;
    private static final int FRAME_INNER = 0xFF5B3B1D;
    private static final int TEXTURE_SIZE = 256;
    private static final int SELECT_OVERLAY = 0x66F0C548;
    private static final int MOVE_HINT = 0xA04EAEE0;
    private static final int CAPTURE_OVERLAY = 0x66D25A41;
    private static final int CAPTURE_BORDER = 0xFFD25A41;
    private static final int CAPTURE_HINT_BORDER = 0xFFFF7F66;
    private static final Identifier SQUARE_LIGHT = Identifier.of("devils-game", "textures/games/chess/square_light.png");
    private static final Identifier SQUARE_DARK = Identifier.of("devils-game", "textures/games/chess/square_dark.png");
    private static final Identifier W_KING = Identifier.of("devils-game", "textures/games/chess/w_king.png");
    private static final Identifier W_QUEEN = Identifier.of("devils-game", "textures/games/chess/w_queen.png");
    private static final Identifier W_ROOK = Identifier.of("devils-game", "textures/games/chess/w_rook.png");
    private static final Identifier W_BISHOP = Identifier.of("devils-game", "textures/games/chess/w_bishop.png");
    private static final Identifier W_KNIGHT = Identifier.of("devils-game", "textures/games/chess/w_knight.png");
    private static final Identifier W_PAWN = Identifier.of("devils-game", "textures/games/chess/w_pawn.png");
    private static final Identifier B_KING = Identifier.of("devils-game", "textures/games/chess/b_king.png");
    private static final Identifier B_QUEEN = Identifier.of("devils-game", "textures/games/chess/b_queen.png");
    private static final Identifier B_ROOK = Identifier.of("devils-game", "textures/games/chess/b_rook.png");
    private static final Identifier B_BISHOP = Identifier.of("devils-game", "textures/games/chess/b_bishop.png");
    private static final Identifier B_KNIGHT = Identifier.of("devils-game", "textures/games/chess/b_knight.png");
    private static final Identifier B_PAWN = Identifier.of("devils-game", "textures/games/chess/b_pawn.png");
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
    ChessOverlayWindow(int minW, int minH, int maxW, int maxH) {
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
    void render(DrawContext context, MinecraftClient mc, ChessOverlay.PlayMode mode, boolean pinned, ChessOverlaySession session, int scriptLevel) {
        if (!shouldRender(mc, pinned)) return;
        int mouseX = scaledMouseX(mc); int mouseY = scaledMouseY(mc);
        updateWindowTransform(mc, mouseX, mouseY);
        SessionView syncSession = session.prepare(mode);
        boolean whiteBottom = session.resolveOrientation(mode, syncSession);
        Layout l = computeLayout();
        char[][] matrix = ChessLogic.board(session.boardFen());
        List<ChessLogic.Move> legal = ChessLogic.legalMoves(session.boardFen());
        session.refreshSelection(legal);
        List<ChessOverlaySession.BoardCoord> captureTargets = session.collectCaptureTargets(matrix, legal);
        context.fill(l.x, l.y, l.x + l.w, l.y + l.h, DARK_BG);
        drawHeader(context, mc.textRenderer, l, mode, pinned, whiteBottom, scriptLevel);
        drawBoard(context, mc.textRenderer, l, whiteBottom, captureTargets, session.hasSelection(), session.selectedX(), session.selectedY());
        drawMoveHints(context, l, whiteBottom, matrix, session.selectedMoves(), session);
        drawPieces(context, l, whiteBottom, matrix);
        drawFooter(context, mc.textRenderer, l, mode, syncSession, session.statusText(), session.boardFen());
        drawResizeHandle(context, l, mouseX, mouseY);
    }
    boolean onMouse(MouseClickEvent event, MinecraftClient mc, ChessOverlay.PlayMode mode, boolean pinned, int scriptLevel, Consumer<Boolean> setPinned, Consumer<ChessOverlay.PlayMode> setMode, IntConsumer setScriptLevel, Runnable cycleGame, Runnable closeOverlay, ChessOverlaySession session) {
        if (!shouldRender(mc, pinned)) return false;
        int mouseX = scaledMouseX(mc);
        int mouseY = scaledMouseY(mc);
        if (event.button() != 0 && event.button() != 1) return false;
        if (event.action == KeyAction.Release) { dragging = false; resizing = false; return false; }
        if (event.action != KeyAction.Press) return false;
        Layout l = computeLayout();
        if (!inside(mouseX, mouseY, l.x, l.y, l.w, l.h)) return false;
        if (inside(mouseX, mouseY, l.pinX, l.pinY, l.btnW, l.btnH)) {
            setPinned.accept(!pinned);
            return true;
        }
        int titleW = mc != null && mc.textRenderer != null ? mc.textRenderer.getWidth("Chess") : 34;
        if (inside(mouseX, mouseY, l.x + 6, l.y + 2, titleW + 2, l.btnH)) { cycleGame.run(); return true; }
        if (inside(mouseX, mouseY, l.modeX, l.modeY, l.btnW, l.btnH)) {
            if (mode == ChessOverlay.PlayMode.SCRIPT) {
                if (event.button() == 0) {
                    setScriptLevel.accept(scriptLevel >= 7 ? 1 : scriptLevel + 1);
                } else {
                    setMode.accept(ChessOverlay.PlayMode.SYNC);
                    session.onActivate(ChessOverlay.PlayMode.SYNC);
                }
            } else {
                setMode.accept(ChessOverlay.PlayMode.SCRIPT);
                session.onActivate(ChessOverlay.PlayMode.SCRIPT);
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
        session.clearSelection();
        return true;
    }
    private void drawHeader(DrawContext context, TextRenderer tr, Layout l, ChessOverlay.PlayMode mode, boolean pinned, boolean whiteBottom, int scriptLevel) {
        context.fill(l.x, l.y, l.x + l.w, l.y + l.headerH, HEADER_BG);
        context.fill(l.x, l.y, l.x + l.w, l.y + 1, HEADER_BORDER);
        context.fill(l.x, l.y + l.headerH - 1, l.x + l.w, l.y + l.headerH, HEADER_BORDER);
        context.drawTextWithShadow(tr, "Chess", l.x + 6, l.y + 4, 0xFFFFFFFF);
        drawHeaderButton(context, tr, l.pinX, l.pinY, l.btnW, l.btnH, pinned ? "Unpin" : "Pin");
        String modeText = mode == ChessOverlay.PlayMode.SCRIPT ? ("L" + scriptLevel) : "Sync";
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
        List<ChessOverlaySession.BoardCoord> captureTargets,
        boolean hasSelection,
        int selectedX,
        int selectedY
    ) {
        context.fill(l.boardX - 6, l.boardY - 6, l.boardX + l.boardSize + 6, l.boardY + l.boardSize + 6, FRAME_OUTER);
        context.fill(l.boardX - 2, l.boardY - 2, l.boardX + l.boardSize + 2, l.boardY + l.boardSize + 2, FRAME_INNER);
        for (int dy = 0; dy < 8; dy++) {
            for (int dx = 0; dx < 8; dx++) {
                int x1 = l.boardX + dx * l.cell;
                int y1 = l.boardY + dy * l.cell;
                Identifier tile = ((dx + dy) & 1) == 1 ? SQUARE_DARK : SQUARE_LIGHT;
                context.drawTexture(
                    RenderPipelines.GUI_TEXTURED,
                    tile,
                    x1,
                    y1,
                    0,
                    0,
                    l.cell,
                    l.cell,
                    TEXTURE_SIZE,
                    TEXTURE_SIZE,
                    TEXTURE_SIZE,
                    TEXTURE_SIZE,
                    0xFFFFFFFF
                );
            }
        }
        for (ChessOverlaySession.BoardCoord capture : captureTargets) {
            drawCellOverlay(context, l, whiteBottom, capture.x(), capture.y(), CAPTURE_OVERLAY);
            drawCellBorder(context, l, whiteBottom, capture.x(), capture.y(), CAPTURE_BORDER);
        }
        if (hasSelection) {
            drawCellOverlay(context, l, whiteBottom, selectedX, selectedY, SELECT_OVERLAY);
            drawCellBorder(context, l, whiteBottom, selectedX, selectedY, 0xFFF8D96C);
        }
        drawCoords(context, l, whiteBottom, tr);
    }
    private void drawMoveHints(
        DrawContext context,
        Layout l,
        boolean whiteBottom,
        char[][] matrix,
        List<ChessLogic.Move> selectedMoves,
        ChessOverlaySession session
    ) {
        for (ChessLogic.Move move : selectedMoves) {
            ChessOverlaySession.BoardCoord captured = session.capturedForHint(matrix, move);
            if (captured != null) {
                drawCellBorder(context, l, whiteBottom, move.toX(), move.toY(), CAPTURE_HINT_BORDER);
            } else {
                int tx = toDrawX(move.toX(), whiteBottom);
                int ty = toDrawY(move.toY(), whiteBottom);
                int cx = l.boardX + tx * l.cell + l.cell / 2;
                int cy = l.boardY + ty * l.cell + l.cell / 2;
                drawDot(context, cx, cy, Math.max(4, l.cell / 9), MOVE_HINT);
            }
        }
    }
    private void drawCoords(DrawContext context, Layout l, boolean whiteBottom, TextRenderer tr) {
        for (int i = 0; i < 8; i++) {
            int file = whiteBottom ? i : 7 - i;
            int rank = whiteBottom ? 7 - i : i;
            String fileLabel = String.valueOf((char) ('A' + file));
            String rankLabel = String.valueOf(rank + 1);
            int fx = l.boardX + i * l.cell + l.cell / 2 - tr.getWidth(fileLabel) / 2;
            int fy = l.boardY + l.boardSize + 2;
            context.drawTextWithShadow(tr, fileLabel, fx, fy, 0xFFF2E5CD);
            int rx = l.boardX - 14;
            int ry = l.boardY + i * l.cell + l.cell / 2 - 4;
            context.drawTextWithShadow(tr, rankLabel, rx, ry, 0xFFF2E5CD);
        }
    }
    private void drawPieces(DrawContext context, Layout l, boolean whiteBottom, char[][] matrix) {
        for (int by = 0; by < 8; by++) {
            for (int bx = 0; bx < 8; bx++) {
                char piece = matrix[by][bx]; if (piece == '.') continue;
                int dx = toDrawX(bx, whiteBottom);
                int dy = toDrawY(by, whiteBottom);
                Identifier texture = switch (piece) {
                    case 'K' -> W_KING;
                    case 'Q' -> W_QUEEN;
                    case 'R' -> W_ROOK;
                    case 'B' -> W_BISHOP;
                    case 'N' -> W_KNIGHT;
                    case 'P' -> W_PAWN;
                    case 'k' -> B_KING;
                    case 'q' -> B_QUEEN;
                    case 'r' -> B_ROOK;
                    case 'b' -> B_BISHOP;
                    case 'n' -> B_KNIGHT;
                    case 'p' -> B_PAWN;
                    default -> null;
                };
                if (texture == null) continue;
                int inset = Math.max(1, l.cell / 28);
                int px = l.boardX + dx * l.cell + inset;
                int py = l.boardY + dy * l.cell + inset;
                int size = l.cell - inset * 2;
                context.drawTexture(
                    RenderPipelines.GUI_TEXTURED,
                    texture,
                    px,
                    py,
                    0,
                    0,
                    size,
                    size,
                    TEXTURE_SIZE,
                    TEXTURE_SIZE,
                    TEXTURE_SIZE,
                    TEXTURE_SIZE,
                    0xFFFFFFFF
                );
            }
        }
    }
    private void drawFooter(
        DrawContext context,
        TextRenderer tr,
        Layout l,
        ChessOverlay.PlayMode mode,
        SessionView session,
        String statusText,
        String boardFen
    ) {
        context.fill(l.panelX, l.panelY, l.panelX + l.panelW, l.panelY + l.panelH, PANEL_BG);
        context.fill(l.panelX, l.panelY, l.panelX + l.panelW, l.panelY + 1, PANEL_BORDER);
        context.fill(l.panelX, l.panelY + l.panelH - 1, l.panelX + l.panelW, l.panelY + l.panelH, PANEL_BORDER);
        context.drawTextWithShadow(tr, "Mode: " + (mode == ChessOverlay.PlayMode.SYNC ? "Game Sync" : "Script"), l.panelX + 8, l.panelY + 7, 0xFFE9F2FF);
        context.drawTextWithShadow(tr, "Turn: " + (ChessLogic.isWhiteTurn(boardFen) ? "White" : "Black"), l.panelX + 8, l.panelY + 20, 0xFFE9F2FF);
        if (statusText != null && !statusText.isBlank()) context.drawTextWithShadow(tr, statusText, l.panelX + 8, l.panelY + 35, 0xFFFFE7A4);
        if (mode == ChessOverlay.PlayMode.SYNC && session != null && session.active()) {
            String side = session.localWhite() ? "You: White" : "You: Black";
            context.drawTextWithShadow(tr, side, l.panelX + 8, l.panelY + 50, 0xFFC6DEFF);
            context.drawTextWithShadow(tr, "Opp: " + safe(session.opponentName()), l.panelX + 116, l.panelY + 50, 0xFFC6DEFF);
        } else if (mode == ChessOverlay.PlayMode.SCRIPT) {
            context.drawTextWithShadow(tr, "Play White vs script (Black).", l.panelX + 8, l.panelY + 50, 0xFFC6DEFF);
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
        int panelX = windowX + 8;
        int panelY = boardY + boardSize + 18;
        int panelW = windowW - 16;
        int btnW = 42;
        int btnH = 14;
        int closeX = windowX + windowW - btnW - 5;
        int resetX = closeX - btnW - 4;
        int sideX = resetX - btnW - 4;
        int modeX = sideX - btnW - 4;
        int pinX = modeX - btnW - 4;
        return new Layout(
            windowX,
            windowY,
            windowW,
            windowH,
            headerH,
            boardX,
            boardY,
            boardSize,
            cell,
            panelX,
            panelY,
            panelW,
            panelH,
            pinX,
            windowY + 3,
            modeX,
            windowY + 3,
            sideX,
            windowY + 3,
            resetX,
            windowY + 3,
            closeX,
            windowY + 3,
            btnW,
            btnH,
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
    private static int toDrawX(int boardX, boolean whiteBottom) {
        return whiteBottom ? boardX : 7 - boardX;
    }
    private static int toDrawY(int boardY, boolean whiteBottom) {
        return whiteBottom ? boardY : 7 - boardY;
    }
    private static String safe(String value) {
        return value == null || value.isBlank() ? "<unknown>" : value;
    }
    private record Layout(
        int x,
        int y,
        int w,
        int h,
        int headerH,
        int boardX,
        int boardY,
        int boardSize,
        int cell,
        int panelX,
        int panelY,
        int panelW,
        int panelH,
        int pinX,
        int pinY,
        int modeX,
        int modeY,
        int sideX,
        int sideY,
        int resetX,
        int resetY,
        int closeX,
        int closeY,
        int btnW,
        int btnH,
        int resizeX,
        int resizeY,
        int resizeSize
    ) {
    }
}

