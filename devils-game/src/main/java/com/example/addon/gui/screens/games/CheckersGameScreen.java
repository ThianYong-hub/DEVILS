package com.example.addon.gui.screens.games;
import com.example.addon.games.MiniGamesContracts.GameType;
import com.example.addon.games.MiniGamesContracts.MoveSubmitResult;
import com.example.addon.games.MiniGamesContracts.SessionView;
import com.example.addon.games.checkers.CheckersLogic;
import com.example.addon.games.checkers.CheckersLogic.Coord;
import com.example.addon.games.checkers.CheckersLogic.Move;
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
public final class CheckersGameScreen extends Screen {
    public enum Mode {
        SCRIPT,
        SYNC
    }
    private static final int DARK_BG = 0xEF101A12;
    private static final int TOP_BAND = 0x993A5D2D;
    private static final int BOTTOM_BAND = 0x8821301D;
    private static final int PANEL_BG = 0xCC173326;
    private static final int PANEL_BORDER = 0xFF55865F;
    private static final int FRAME_OUTER = 0xFF1C1309;
    private static final int FRAME_INNER = 0xFF5B3010;
    private static final int CHESS_SQUARE_TEXTURE_SIZE = 256;
    private static final int CHECKER_PIECE_TEXTURE_SIZE = 315;
    private static final Identifier SQUARE_LIGHT = Identifier.of("devils-game", "textures/games/chess/square_light.png");
    private static final Identifier SQUARE_DARK = Identifier.of("devils-game", "textures/games/chess/square_dark.png");
    private static final Identifier WHITE_PIECE = Identifier.of("devils-game", "textures/games/checkers/piece_white.png");
    private static final Identifier BLACK_PIECE = Identifier.of("devils-game", "textures/games/checkers/piece_black.png");
    private static final int PATH_OVERLAY = 0x88F0D769;
    private static final int HINT_OVERLAY = 0x904DD6FF;
    private static final int CAPTURE_OVERLAY = 0x66CF4E39;
    private static final int CAPTURE_BORDER = 0xFFCF4E39;
    private final Screen parent;
    private final Mode mode;
    private final MiniGamesSyncRuntime runtime = MiniGamesSyncRuntime.get();
    private final Random random = new Random();
    private final ArrayList<ButtonHitbox> buttons = new ArrayList<>();
    private final ArrayList<Coord> stagedPath = new ArrayList<>();
    private String boardState = CheckersLogic.initialState();
    private String statusText = "";
    public CheckersGameScreen(Screen parent, Mode mode) {
        super(Text.literal("Checkers"));
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
        SessionView syncSession = mode == Mode.SYNC ? runtime.sessionView(GameType.CHECKERS) : null;
        boolean whiteBottom = resolveOrientation(syncSession);
        if (mode == Mode.SYNC) applySyncSnapshot(syncSession);
        BoardMetrics board = computeBoard();
        List<Move> legal = CheckersLogic.legalMoves(boardState);
        char[][] matrix = CheckersLogic.board(boardState);
        List<Coord> captureTargets = collectCaptureTargets(matrix, legal);
        drawBoard(context, board, whiteBottom, legal, captureTargets);
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
            boardState = session.boardState();
        }
        if (!session.active()) {
            statusText = "No active checkers session. Open the Checkers launcher and use Game Sync to invite a player.";
            stagedPath.clear();
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
    private void drawBoard(
        DrawContext context,
        BoardMetrics board,
        boolean whiteBottom,
        List<Move> legalMoves,
        List<Coord> captureTargets
    ) {
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
                    CHESS_SQUARE_TEXTURE_SIZE,
                    CHESS_SQUARE_TEXTURE_SIZE,
                    CHESS_SQUARE_TEXTURE_SIZE,
                    CHESS_SQUARE_TEXTURE_SIZE,
                    0xFFFFFFFF
                );
            }
        }
        for (Coord capture : captureTargets) {
            drawCellOverlay(context, board, whiteBottom, capture.x(), capture.y(), CAPTURE_OVERLAY);
            drawCellBorder(context, board, whiteBottom, capture.x(), capture.y(), CAPTURE_BORDER);
        }
        for (Coord staged : stagedPath) {
            drawCellOverlay(context, board, whiteBottom, staged.x(), staged.y(), PATH_OVERLAY);
            drawCellBorder(context, board, whiteBottom, staged.x(), staged.y(), 0xFFE0CA6E);
        }
        for (Coord hint : collectHints(legalMoves)) {
            int dx = toDrawX(hint.x(), whiteBottom);
            int dy = toDrawY(hint.y(), whiteBottom);
            int cx = board.x + dx * board.cell + board.cell / 2;
            int cy = board.y + dy * board.cell + board.cell / 2;
            drawDot(context, cx, cy, Math.max(5, board.cell / 8), HINT_OVERLAY);
        }
        drawCoords(context, board, whiteBottom);
    }
    private void drawCoords(DrawContext context, BoardMetrics board, boolean whiteBottom) {
        for (int i = 0; i < 8; i++) {
            int file = whiteBottom ? i : 7 - i;
            int rank = whiteBottom ? 7 - i : i;
            String fileLabel = String.valueOf((char) ('A' + file));
            String rankLabel = String.valueOf(rank + 1);
            int fx = board.x + i * board.cell + board.cell / 2 - textRenderer.getWidth(fileLabel) / 2;
            int fy = board.y + board.size + 5;
            context.drawTextWithShadow(textRenderer, Text.literal(fileLabel), fx, fy, 0xFFF4E7D0);
            int rx = board.x - 14;
            int ry = board.y + i * board.cell + board.cell / 2 - 4;
            context.drawTextWithShadow(textRenderer, Text.literal(rankLabel), rx, ry, 0xFFF4E7D0);
        }
    }
    private void drawPieces(DrawContext context, BoardMetrics board, boolean whiteBottom, char[][] matrix) {
        for (int by = 0; by < 8; by++) {
            for (int bx = 0; bx < 8; bx++) {
                char piece = matrix[by][bx];
                if (piece == '.') continue;
                int dx = toDrawX(bx, whiteBottom);
                int dy = toDrawY(by, whiteBottom);
                int cx = board.x + dx * board.cell + board.cell / 2;
                int cy = board.y + dy * board.cell + board.cell / 2;
                boolean whitePiece = piece == 'w' || piece == 'W';
                boolean king = piece == 'W' || piece == 'B';
                Identifier texture = whitePiece ? WHITE_PIECE : BLACK_PIECE;
                int inset = Math.max(2, board.cell / 14);
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
                    CHECKER_PIECE_TEXTURE_SIZE,
                    CHECKER_PIECE_TEXTURE_SIZE,
                    CHECKER_PIECE_TEXTURE_SIZE,
                    CHECKER_PIECE_TEXTURE_SIZE,
                    0xFFFFFFFF
                );
                if (king) {
                    int outer = Math.max(6, board.cell / 5);
                    int inner = Math.max(3, outer - 3);
                    int center = whitePiece ? 0xFFEFEFEF : 0xFF141414;
                    drawDisc(context, cx, cy, outer, 0xCCDCAA4B);
                    drawDisc(context, cx, cy, inner, center);
                    drawDisc(context, cx, cy, Math.max(2, inner / 3), 0xFFDAAA47);
                }
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
        context.drawTextWithShadow(textRenderer, Text.literal(modeText), board.x + 10, panelY + 8, 0xFFDFF8E3);
        String turnText = "Turn: " + (CheckersLogic.isWhiteTurn(boardState) ? "White" : "Black");
        context.drawTextWithShadow(textRenderer, Text.literal(turnText), board.x + 10, panelY + 22, 0xFFDFF8E3);
        if (!statusText.isBlank()) {
            context.drawTextWithShadow(textRenderer, Text.literal(statusText), board.x + 10, panelY + 38, 0xFFFFE8A7);
        }
        if (mode == Mode.SYNC && session != null && session.active()) {
            String you = session.localWhite() ? "You: White" : "You: Black";
            context.drawTextWithShadow(textRenderer, Text.literal(you), board.x + 10, panelY + 52, 0xFFBDEEC8);
            context.drawTextWithShadow(textRenderer, Text.literal("Opponent: " + safe(session.opponentName())), board.x + 130, panelY + 52, 0xFFBDEEC8);
        } else if (mode == Mode.SCRIPT) {
            context.drawTextWithShadow(textRenderer, Text.literal("Play White vs script (Black)."), board.x + 10, panelY + 52, 0xFFBDEEC8);
        }
        int btnY = panelY + 64;
        if (mode == Mode.SYNC) {
            addButton(context, board.x + 10, btnY, 132, 20, "Leave Match", () -> {
                runtime.leaveSession(GameType.CHECKERS);
                close();
            });
        } else {
            addButton(context, board.x + 10, btnY, 132, 20, "Restart", () -> {
                boardState = CheckersLogic.initialState();
                statusText = "";
                stagedPath.clear();
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
        SessionView syncSession = mode == Mode.SYNC ? runtime.sessionView(GameType.CHECKERS) : null;
        boolean whiteBottom = resolveOrientation(syncSession);
        BoardMetrics board = computeBoard();
        if (!board.contains(mouseX, mouseY)) {
            stagedPath.clear();
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
                stagedPath.clear();
                return;
            }
            localWhite = session.localWhite();
            boardState = session.boardState();
            if (!session.localTurn()) {
                statusText = "Wait for your turn.";
                return;
            }
        } else {
            localWhite = true;
            if (!CheckersLogic.isWhiteTurn(boardState)) return;
        }
        List<Move> legal = CheckersLogic.legalMoves(boardState);
        if (legal.isEmpty()) {
            statusText = "No legal moves.";
            stagedPath.clear();
            return;
        }
        char[][] board = CheckersLogic.board(boardState);
        Coord clicked = new Coord(x, y);
        if (stagedPath.isEmpty()) {
            char piece = board[y][x];
            if (piece == '.') return;
            boolean isWhitePiece = piece == 'w' || piece == 'W';
            if (isWhitePiece != localWhite) return;
            if (!hasMoveStartingWith(legal, List.of(clicked))) return;
            stagedPath.add(clicked);
            return;
        }
        ArrayList<Coord> candidate = new ArrayList<>(stagedPath);
        candidate.add(clicked);
        if (!hasMoveStartingWith(legal, candidate)) {
            stagedPath.clear();
            char piece = board[y][x];
            if (piece != '.') {
                boolean isWhitePiece = piece == 'w' || piece == 'W';
                if (isWhitePiece == localWhite && hasMoveStartingWith(legal, List.of(clicked))) stagedPath.add(clicked);
            }
            return;
        }
        stagedPath.add(clicked);
        Move exact = findExactMove(legal, stagedPath);
        if (exact == null) return;
        if (hasLongerWithSamePrefix(legal, stagedPath)) return;
        String encoded = exact.encode();
        stagedPath.clear();
        if (encoded.isBlank()) return;
        if (mode == Mode.SYNC) {
            MoveSubmitResult result = runtime.submitMove(GameType.CHECKERS, encoded);
            if (!result.ok()) statusText = "Move rejected: " + result.error();
            else statusText = "Move sent: " + encoded;
            return;
        }
        CheckersLogic.ApplyResult applied = CheckersLogic.applyMove(boardState, encoded);
        if (!applied.ok()) {
            statusText = "Illegal move.";
            return;
        }
        boardState = applied.state();
        if (!applied.winner().isBlank()) {
            statusText = "Winner: " + applied.winner();
            return;
        }
        String botMove = CheckersLogic.randomScriptMove(boardState, random);
        if (botMove.isBlank()) {
            statusText = "Script has no legal move.";
            return;
        }
        CheckersLogic.ApplyResult botApplied = CheckersLogic.applyMove(boardState, botMove);
        if (botApplied.ok()) {
            boardState = botApplied.state();
            if (!botApplied.winner().isBlank()) statusText = "Winner: " + botApplied.winner();
            else statusText = "Script move: " + botMove;
        }
    }
    private List<Coord> collectHints(List<Move> legal) {
        ArrayList<Coord> hints = new ArrayList<>();
        if (legal.isEmpty()) return hints;
        if (stagedPath.isEmpty()) {
            for (Move move : legal) {
                Coord start = move.path().get(0);
                if (!containsCoord(hints, start)) hints.add(start);
            }
            return hints;
        }
        for (Move move : legal) {
            if (!startsWith(move.path(), stagedPath)) continue;
            if (move.path().size() == stagedPath.size()) continue;
            Coord next = move.path().get(stagedPath.size());
            if (!containsCoord(hints, next)) hints.add(next);
        }
        return hints;
    }
    private List<Coord> collectCaptureTargets(char[][] board, List<Move> legal) {
        ArrayList<Coord> targets = new ArrayList<>();
        if (stagedPath.size() > 1) return targets;
        for (Move move : legal) {
            if (!move.capture() || move.path() == null || move.path().size() < 2) continue;
            if (!stagedPath.isEmpty()) {
                Coord start = move.path().get(0);
                Coord selected = stagedPath.get(0);
                if (start.x() != selected.x() || start.y() != selected.y()) continue;
            }
            Coord taken = capturedInSegment(board, move.path().get(0), move.path().get(1));
            if (taken != null && !containsCoord(targets, taken)) targets.add(taken);
        }
        return targets;
    }
    private Coord capturedInSegment(char[][] board, Coord from, Coord to) {
        int stepX = Integer.compare(to.x(), from.x());
        int stepY = Integer.compare(to.y(), from.y());
        if (stepX == 0 || stepY == 0) return null;
        int x = from.x() + stepX;
        int y = from.y() + stepY;
        while (x != to.x() || y != to.y()) {
            char piece = board[y][x];
            if (piece != '.') return new Coord(x, y);
            x += stepX;
            y += stepY;
        }
        return null;
    }
    private static boolean containsCoord(List<Coord> list, Coord value) {
        for (Coord c : list) {
            if (c.x() == value.x() && c.y() == value.y()) return true;
        }
        return false;
    }
    private static boolean hasMoveStartingWith(List<Move> legal, List<Coord> prefix) {
        for (Move move : legal) {
            if (startsWith(move.path(), prefix)) return true;
        }
        return false;
    }
    private static boolean hasLongerWithSamePrefix(List<Move> legal, List<Coord> prefix) {
        for (Move move : legal) {
            if (move.path().size() <= prefix.size()) continue;
            if (startsWith(move.path(), prefix)) return true;
        }
        return false;
    }
    private static Move findExactMove(List<Move> legal, List<Coord> path) {
        for (Move move : legal) {
            if (move.path().size() != path.size()) continue;
            if (startsWith(move.path(), path)) return move;
        }
        return null;
    }
    private static boolean startsWith(List<Coord> path, List<Coord> prefix) {
        if (path == null || prefix == null) return false;
        if (path.size() < prefix.size()) return false;
        for (int i = 0; i < prefix.size(); i++) {
            Coord a = path.get(i);
            Coord b = prefix.get(i);
            if (a.x() != b.x() || a.y() != b.y()) return false;
        }
        return true;
    }
    private void addButton(DrawContext context, int x, int y, int w, int h, String label, Runnable action) {
        context.fill(x, y, x + w, y + h, 0xCC2F6B42);
        context.fill(x, y, x + w, y + 1, 0xFF9CE7B4);
        context.fill(x, y + h - 1, x + w, y + h, 0xFF9CE7B4);
        context.fill(x, y, x + 1, y + h, 0xFF9CE7B4);
        context.fill(x + w - 1, y, x + w, y + h, 0xFF9CE7B4);
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
    private void drawDisc(DrawContext context, int cx, int cy, int radius, int color) {
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
    @Override
    public void close() {
        if (client != null) client.setScreen(parent);
    }
    private static String safe(String value) {
        return value == null || value.isBlank() ? "<unknown>" : value;
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

