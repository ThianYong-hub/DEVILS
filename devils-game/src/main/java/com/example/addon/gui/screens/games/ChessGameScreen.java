package com.example.addon.gui.screens.games;
import com.example.addon.games.MiniGamesContracts.GameType;
import com.example.addon.games.MiniGamesContracts.MoveSubmitResult;
import com.example.addon.games.MiniGamesContracts.SessionView;
import com.example.addon.games.chess.ChessLogic;
import com.example.addon.games.sync.MiniGamesSyncRuntime;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
public final class ChessGameScreen extends Screen {
    public enum Mode {
        SCRIPT,
        SYNC
    }
    private static final int DARK_BG = 0xEF0D1421;
    private static final int TOP_BAND = 0x99243F66;
    private static final int BOTTOM_BAND = 0x8810182B;
    private static final int PANEL_BG = 0xCC152238;
    private static final int PANEL_BORDER = 0xFF42648D;
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
    private final Screen parent;
    private final Mode mode;
    private final MiniGamesSyncRuntime runtime = MiniGamesSyncRuntime.get();
    private final Random random = new Random();
    private final ArrayList<ButtonHitbox> buttons = new ArrayList<>();
    private final ArrayList<ChessLogic.Move> selectedMoves = new ArrayList<>();
    private String boardFen = ChessLogic.initialFen();
    private String statusText = "";
    private int selectedX = -1;
    private int selectedY = -1;
    public ChessGameScreen(Screen parent, Mode mode) {
        super(Text.literal("Chess"));
        this.parent = parent;
        this.mode = mode == null ? Mode.SCRIPT : mode;
    }
    @Override
    public void tick() {
        if (mode == Mode.SYNC) runtime.tick();
    }
    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        drawBackground(context);
        buttons.clear();
        SessionView syncSession = mode == Mode.SYNC ? runtime.sessionView(GameType.CHESS) : null;
        boolean whiteBottom = resolveOrientation(syncSession);
        if (mode == Mode.SYNC) applySyncSnapshot(syncSession);
        BoardMetrics board = computeBoard();
        char[][] matrix = ChessLogic.board(boardFen);
        List<ChessLogic.Move> legal = ChessLogic.legalMoves(boardFen);
        refreshSelection(legal);
        boolean onlySelected = selectedX >= 0 && selectedY >= 0;
        List<BoardCoord> captureTargets = collectCaptureTargets(matrix, legal, onlySelected);
        drawBoard(context, board, whiteBottom, captureTargets);
        drawMoveHints(context, board, whiteBottom, matrix);
        drawPieces(context, board, whiteBottom, matrix);
        drawBottomPanel(context, board, syncSession);
        super.render(context, mouseX, mouseY, delta);
    }
    private void drawBackground(DrawContext context) {
        context.fill(0, 0, width, height, DARK_BG);
        context.fill(0, 0, width, 50, TOP_BAND);
        context.fill(0, height - 62, width, height, BOTTOM_BAND);
    }
    private void applySyncSnapshot(SessionView session) {
        if (session.active() && session.boardState() != null && !session.boardState().isBlank()) {
            boardFen = session.boardState();
        }
        if (!session.active()) {
            statusText = "No active chess session. Open the Chess launcher and use Game Sync to invite a player.";
            clearSelection();
            return;
        }
        if (!session.winner().isBlank()) statusText = "Winner: " + session.winner();
        else statusText = session.localTurn() ? "Your turn." : "Opponent turn.";
    }
    private boolean resolveOrientation(SessionView session) {
        if (mode != Mode.SYNC || session == null || !session.active()) return true;
        return session.localWhite();
    }
    private BoardMetrics computeBoard() {
        int maxSize = Math.min(Math.min(width - 80, height - 172), 640);
        int cell = Math.max(36, maxSize / 8);
        int size = cell * 8;
        int x = (width - size) / 2;
        int y = 40;
        return new BoardMetrics(x, y, cell, size);
    }
    private void drawBoard(DrawContext context, BoardMetrics board, boolean whiteBottom, List<BoardCoord> captureTargets) {
        int frame = 8;
        context.fill(board.x - frame, board.y - frame, board.x + board.size + frame, board.y + board.size + frame, FRAME_OUTER);
        context.fill(board.x - 2, board.y - 2, board.x + board.size + 2, board.y + board.size + 2, FRAME_INNER);
        for (int dy = 0; dy < 8; dy++) {
            for (int dx = 0; dx < 8; dx++) {
                boolean dark = ((dx + dy) & 1) == 1;
                int x1 = board.x + dx * board.cell;
                int y1 = board.y + dy * board.cell;
                Identifier tile = dark ? SQUARE_DARK : SQUARE_LIGHT;
                context.drawTexture(
                    RenderPipelines.GUI_TEXTURED,
                    tile,
                    x1,
                    y1,
                    0,
                    0,
                    board.cell,
                    board.cell,
                    TEXTURE_SIZE,
                    TEXTURE_SIZE,
                    TEXTURE_SIZE,
                    TEXTURE_SIZE,
                    0xFFFFFFFF
                );
            }
        }
        for (BoardCoord capture : captureTargets) {
            drawCellOverlay(context, board, whiteBottom, capture.x, capture.y, CAPTURE_OVERLAY);
            drawCellBorder(context, board, whiteBottom, capture.x, capture.y, CAPTURE_BORDER);
        }
        if (selectedX >= 0 && selectedY >= 0) {
            drawCellOverlay(context, board, whiteBottom, selectedX, selectedY, SELECT_OVERLAY);
            drawCellBorder(context, board, whiteBottom, selectedX, selectedY, 0xFFF8D96C);
        }
        drawCoords(context, board, whiteBottom);
    }
    private void drawMoveHints(DrawContext context, BoardMetrics board, boolean whiteBottom, char[][] matrix) {
        for (ChessLogic.Move move : selectedMoves) {
            BoardCoord captured = capturedSquareForMove(matrix, move);
            if (captured != null) {
                drawCellBorder(context, board, whiteBottom, move.toX(), move.toY(), CAPTURE_HINT_BORDER);
            } else {
                int tx = toDrawX(move.toX(), whiteBottom);
                int ty = toDrawY(move.toY(), whiteBottom);
                int cx = board.x + tx * board.cell + board.cell / 2;
                int cy = board.y + ty * board.cell + board.cell / 2;
                drawDot(context, cx, cy, Math.max(4, board.cell / 9), MOVE_HINT);
            }
        }
    }
    private void drawCoords(DrawContext context, BoardMetrics board, boolean whiteBottom) {
        for (int i = 0; i < 8; i++) {
            int file = whiteBottom ? i : 7 - i;
            int rank = whiteBottom ? 7 - i : i;
            String fileLabel = String.valueOf((char) ('A' + file));
            String rankLabel = String.valueOf(rank + 1);
            int fx = board.x + i * board.cell + board.cell / 2 - textRenderer.getWidth(fileLabel) / 2;
            int fy = board.y + board.size + 5;
            context.drawTextWithShadow(textRenderer, Text.literal(fileLabel), fx, fy, 0xFFF2E5CD);
            int rx = board.x - 14;
            int ry = board.y + i * board.cell + board.cell / 2 - 4;
            context.drawTextWithShadow(textRenderer, Text.literal(rankLabel), rx, ry, 0xFFF2E5CD);
        }
    }
    private void drawPieces(DrawContext context, BoardMetrics board, boolean whiteBottom, char[][] matrix) {
        for (int by = 0; by < 8; by++) {
            for (int bx = 0; bx < 8; bx++) {
                char piece = matrix[by][bx];
                if (piece == '.') continue;
                int dx = toDrawX(bx, whiteBottom);
                int dy = toDrawY(by, whiteBottom);
                Identifier texture = pieceTexture(piece);
                if (texture == null) continue;

                int inset = Math.max(1, board.cell / 28);
                int px = board.x + dx * board.cell + inset;
                int py = board.y + dy * board.cell + inset;
                int size = board.cell - inset * 2;
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
    private void drawBottomPanel(DrawContext context, BoardMetrics board, SessionView session) {
        int panelY = board.y + board.size + 26;
        int panelH = 88;
        context.fill(board.x, panelY, board.x + board.size, panelY + panelH, PANEL_BG);
        context.fill(board.x, panelY, board.x + board.size, panelY + 1, PANEL_BORDER);
        context.fill(board.x, panelY + panelH - 1, board.x + board.size, panelY + panelH, PANEL_BORDER);
        context.fill(board.x, panelY, board.x + 1, panelY + panelH, PANEL_BORDER);
        context.fill(board.x + board.size - 1, panelY, board.x + board.size, panelY + panelH, PANEL_BORDER);
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 14, 0xFFFFFFFF);
        String modeText = mode == Mode.SYNC ? "Mode: Game Sync" : "Mode: Script";
        context.drawTextWithShadow(textRenderer, Text.literal(modeText), board.x + 10, panelY + 8, 0xFFE5EEFF);
        String turnText = "Turn: " + (ChessLogic.isWhiteTurn(boardFen) ? "White" : "Black");
        context.drawTextWithShadow(textRenderer, Text.literal(turnText), board.x + 10, panelY + 22, 0xFFE5EEFF);
        if (!statusText.isBlank()) {
            context.drawTextWithShadow(textRenderer, Text.literal(statusText), board.x + 10, panelY + 38, 0xFFFFE6A4);
        }
        if (mode == Mode.SYNC && session != null && session.active()) {
            String you = session.localWhite() ? "You: White" : "You: Black";
            context.drawTextWithShadow(textRenderer, Text.literal(you), board.x + 10, panelY + 52, 0xFFC5D9FF);
            context.drawTextWithShadow(textRenderer, Text.literal("Opponent: " + safe(session.opponentName())), board.x + 130, panelY + 52, 0xFFC5D9FF);
        } else if (mode == Mode.SCRIPT) {
            context.drawTextWithShadow(textRenderer, Text.literal("Play White vs script (Black)."), board.x + 10, panelY + 52, 0xFFC5D9FF);
        }
        int btnY = panelY + 64;
        if (mode == Mode.SYNC) {
            addButton(context, board.x + 10, btnY, 132, 20, "Leave Match", () -> {
                runtime.leaveSession(GameType.CHESS);
                close();
            });
        } else {
            addButton(context, board.x + 10, btnY, 132, 20, "Restart", () -> {
                boardFen = ChessLogic.initialFen();
                statusText = "";
                clearSelection();
            });
        }
        addButton(context, board.x + board.size - 132 - 10, btnY, 132, 20, "Back", this::close);
    }
    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        if (click.button() != 0) return super.mouseClicked(click, doubled);
        double mouseX = click.x();
        double mouseY = click.y();
        for (ButtonHitbox hitbox : buttons) {
            if (hitbox.contains(mouseX, mouseY)) {
                hitbox.action.run();
                return true;
            }
        }
        SessionView syncSession = mode == Mode.SYNC ? runtime.sessionView(GameType.CHESS) : null;
        boolean whiteBottom = resolveOrientation(syncSession);
        BoardMetrics board = computeBoard();
        if (!board.contains(mouseX, mouseY)) {
            clearSelection();
            return super.mouseClicked(click, doubled);
        }
        int drawX = (int) ((mouseX - board.x) / board.cell);
        int drawY = (int) ((mouseY - board.y) / board.cell);
        int boardX = whiteBottom ? drawX : 7 - drawX;
        int boardY = whiteBottom ? drawY : 7 - drawY;
        onBoardClick(boardX, boardY, syncSession);
        return true;
    }
    private void onBoardClick(int x, int y, SessionView session) {
        boolean localWhite;
        if (mode == Mode.SYNC) {
            if (session == null || !session.active()) {
                statusText = "Session unavailable.";
                clearSelection();
                return;
            }
            localWhite = session.localWhite();
            boardFen = session.boardState();
            if (!session.localTurn()) {
                statusText = "Wait for your turn.";
                return;
            }
        } else {
            localWhite = true;
            if (!ChessLogic.isWhiteTurn(boardFen)) return;
        }
        char[][] board = ChessLogic.board(boardFen);
        char clicked = board[y][x];
        boolean clickedMine = clicked != '.' && (Character.isUpperCase(clicked) == localWhite);
        List<ChessLogic.Move> legal = ChessLogic.legalMoves(boardFen);
        if (selectedX < 0 || selectedY < 0) {
            if (!clickedMine) return;
            selectPiece(x, y, legal);
            return;
        }
        if (selectedX == x && selectedY == y) {
            clearSelection();
            return;
        }
        if (clickedMine) {
            selectPiece(x, y, legal);
            return;
        }
        ChessLogic.Move chosen = chooseMoveTo(x, y);
        if (chosen == null) {
            statusText = "Illegal destination.";
            return;
        }
        clearSelection();
        String move = chosen.toUci();
        if (mode == Mode.SYNC) {
            MoveSubmitResult result = runtime.submitMove(GameType.CHESS, move);
            if (!result.ok()) statusText = "Move rejected: " + result.error();
            else statusText = "Move sent: " + move;
            return;
        }
        ChessLogic.ApplyResult applied = ChessLogic.applyMove(boardFen, move);
        if (!applied.ok()) {
            statusText = "Illegal move.";
            return;
        }
        boardFen = applied.fen();
        if (!applied.winner().isBlank()) {
            statusText = "Winner: " + applied.winner();
            return;
        }
        String botMove = ChessLogic.randomScriptMove(boardFen, random);
        if (botMove.isBlank()) {
            statusText = "Script has no legal move.";
            return;
        }
        ChessLogic.ApplyResult botApplied = ChessLogic.applyMove(boardFen, botMove);
        if (botApplied.ok()) {
            boardFen = botApplied.fen();
            if (!botApplied.winner().isBlank()) statusText = "Winner: " + botApplied.winner();
            else statusText = "Script move: " + botMove;
        }
    }
    private void selectPiece(int x, int y, List<ChessLogic.Move> legal) {
        selectedX = x;
        selectedY = y;
        selectedMoves.clear();
        for (ChessLogic.Move move : legal) {
            if (move.fromX() == x && move.fromY() == y) selectedMoves.add(move);
        }
        if (selectedMoves.isEmpty()) {
            selectedX = -1;
            selectedY = -1;
        }
    }
    private void refreshSelection(List<ChessLogic.Move> legal) {
        if (selectedX < 0 || selectedY < 0) {
            selectedMoves.clear();
            return;
        }
        selectedMoves.clear();
        for (ChessLogic.Move move : legal) {
            if (move.fromX() == selectedX && move.fromY() == selectedY) selectedMoves.add(move);
        }
        if (selectedMoves.isEmpty()) clearSelection();
    }
    private ChessLogic.Move chooseMoveTo(int x, int y) {
        ChessLogic.Move fallback = null;
        for (ChessLogic.Move move : selectedMoves) {
            if (move.toX() != x || move.toY() != y) continue;
            if (move.promotion() == 'Q' || move.promotion() == 0) return move;
            if (fallback == null) fallback = move;
        }
        return fallback;
    }
    private List<BoardCoord> collectCaptureTargets(char[][] board, List<ChessLogic.Move> legal, boolean onlySelected) {
        ArrayList<BoardCoord> targets = new ArrayList<>();
        for (ChessLogic.Move move : legal) {
            if (onlySelected && (move.fromX() != selectedX || move.fromY() != selectedY)) continue;
            BoardCoord captured = capturedSquareForMove(board, move);
            if (captured == null) continue;
            if (!containsCoord(targets, captured.x, captured.y)) targets.add(captured);
        }
        return targets;
    }
    private BoardCoord capturedSquareForMove(char[][] board, ChessLogic.Move move) {
        char piece = board[move.fromY()][move.fromX()];
        if (piece == '.') return null;
        char target = board[move.toY()][move.toX()];
        if (target != '.' && Character.isUpperCase(target) != Character.isUpperCase(piece)) {
            return new BoardCoord(move.toX(), move.toY());
        }
        if (Character.toLowerCase(piece) != 'p') return null;
        if (move.fromX() == move.toX() || target != '.') return null;
        int captureY = move.fromY();
        if (captureY < 0 || captureY > 7) return null;
        char ep = board[captureY][move.toX()];
        if (ep == '.' || Character.isUpperCase(ep) == Character.isUpperCase(piece)) return null;
        return new BoardCoord(move.toX(), captureY);
    }
    private static boolean containsCoord(List<BoardCoord> list, int x, int y) {
        for (BoardCoord c : list) {
            if (c.x == x && c.y == y) return true;
        }
        return false;
    }
    private void clearSelection() {
        selectedX = -1;
        selectedY = -1;
        selectedMoves.clear();
    }
    private void addButton(DrawContext context, int x, int y, int w, int h, String label, Runnable action) {
        context.fill(x, y, x + w, y + h, 0xCC274D78);
        context.fill(x, y, x + w, y + 1, 0xFF9ED0FF);
        context.fill(x, y + h - 1, x + w, y + h, 0xFF9ED0FF);
        context.fill(x, y, x + 1, y + h, 0xFF9ED0FF);
        context.fill(x + w - 1, y, x + w, y + h, 0xFF9ED0FF);
        int tx = x + (w / 2) - (textRenderer.getWidth(label) / 2);
        context.drawTextWithShadow(textRenderer, Text.literal(label), tx, y + 6, 0xFFFFFFFF);
        buttons.add(new ButtonHitbox(x, y, w, h, action));
    }
    private void drawCellOverlay(DrawContext context, BoardMetrics board, boolean whiteBottom, int boardX, int boardY, int color) {
        int dx = toDrawX(boardX, whiteBottom);
        int dy = toDrawY(boardY, whiteBottom);
        int x1 = board.x + dx * board.cell;
        int y1 = board.y + dy * board.cell;
        context.fill(x1, y1, x1 + board.cell, y1 + board.cell, color);
    }
    private void drawCellBorder(DrawContext context, BoardMetrics board, boolean whiteBottom, int boardX, int boardY, int color) {
        int dx = toDrawX(boardX, whiteBottom);
        int dy = toDrawY(boardY, whiteBottom);
        int x1 = board.x + dx * board.cell;
        int y1 = board.y + dy * board.cell;
        int x2 = x1 + board.cell;
        int y2 = y1 + board.cell;
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
    private static int toDrawX(int boardX, boolean whiteBottom) {
        return whiteBottom ? boardX : 7 - boardX;
    }
    private static int toDrawY(int boardY, boolean whiteBottom) {
        return whiteBottom ? boardY : 7 - boardY;
    }
    private static Identifier pieceTexture(char piece) {
        return switch (piece) {
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
    }
    @Override
    public void close() {
        if (client != null) client.setScreen(parent);
    }
    private static String safe(String value) {
        return value == null || value.isBlank() ? "<unknown>" : value;
    }
    private record BoardCoord(int x, int y) {
    }
    private record BoardMetrics(int x, int y, int cell, int size) {
        private boolean contains(double px, double py) {
            return px >= x && px < x + size && py >= y && py < y + size;
        }
    }
    private record ButtonHitbox(int x, int y, int w, int h, Runnable action) {
        private boolean contains(double px, double py) {
            return px >= x && px <= x + w && py >= y && py <= y + h;
        }
    }
}

