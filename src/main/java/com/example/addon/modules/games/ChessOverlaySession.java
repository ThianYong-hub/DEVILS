package com.example.addon.modules.games;

import com.example.addon.games.MiniGamesContracts.GameType;
import com.example.addon.games.MiniGamesContracts.MoveSubmitResult;
import com.example.addon.games.MiniGamesContracts.SessionView;
import com.example.addon.games.chess.ChessLogic;
import com.example.addon.games.sync.MiniGamesSyncRuntime;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class ChessOverlaySession {
    private static final ExecutorService BOT_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "devils-chess-script");
        t.setDaemon(true);
        return t;
    });

    private final MiniGamesSyncRuntime runtime = MiniGamesSyncRuntime.get();
    private final Random random = new Random();
    private final ArrayList<ChessLogic.Move> selectedMoves = new ArrayList<>();

    private String boardFen = ChessLogic.initialFen();
    private String statusText = "";
    private int selectedX = -1;
    private int selectedY = -1;
    private boolean flipView;
    private boolean scriptPlayerWhite = true;
    private int scriptLevel = 4;
    private long botRequestId;
    private CompletableFuture<BotResult> pendingBotFuture;

    void setScriptLevel(int level) {
        scriptLevel = Math.max(1, Math.min(7, level));
    }

    void onActivate(ChessOverlay.PlayMode mode) {
        clearSelection();
        statusText = "";
        botRequestId++;
        pendingBotFuture = null;
        if (mode == ChessOverlay.PlayMode.SCRIPT) {
            boardFen = ChessLogic.initialFen();
            if (!scriptPlayerWhite) scheduleScriptTurn();
        }
    }

    void onTick(ChessOverlay.PlayMode mode) {
        if (mode == ChessOverlay.PlayMode.SYNC) runtime.tick();
        else processScriptTurn();
    }

    SessionView prepare(ChessOverlay.PlayMode mode) {
        if (mode != ChessOverlay.PlayMode.SYNC) return null;
        SessionView session = runtime.sessionView(GameType.CHESS);
        applySyncSnapshot(session);
        return session;
    }

    String boardFen() {
        return boardFen;
    }

    String statusText() {
        return statusText;
    }

    boolean hasSelection() {
        return selectedX >= 0 && selectedY >= 0;
    }

    int selectedX() {
        return selectedX;
    }

    int selectedY() {
        return selectedY;
    }

    List<ChessLogic.Move> selectedMoves() {
        return selectedMoves;
    }

    void toggleViewSide(ChessOverlay.PlayMode mode) {
        if (mode == ChessOverlay.PlayMode.SCRIPT) {
            scriptPlayerWhite = !scriptPlayerWhite;
            flipView = false;
            onActivate(mode);
            statusText = "Side switched to " + (scriptPlayerWhite ? "White" : "Black") + ". Game reset.";
            return;
        }
        flipView = !flipView;
    }

    boolean resolveOrientation(ChessOverlay.PlayMode mode, SessionView session) {
        boolean whiteBottom = mode == ChessOverlay.PlayMode.SYNC
            ? session == null || !session.active() || session.localWhite()
            : scriptPlayerWhite;
        return flipView ? !whiteBottom : whiteBottom;
    }

    void refreshSelection(List<ChessLogic.Move> legal) {
        if (!hasSelection()) {
            selectedMoves.clear();
            return;
        }

        selectedMoves.clear();
        for (ChessLogic.Move move : legal) {
            if (move.fromX() == selectedX && move.fromY() == selectedY) selectedMoves.add(move);
        }
        if (selectedMoves.isEmpty()) clearSelection();
    }

    void clearSelection() {
        selectedX = -1;
        selectedY = -1;
        selectedMoves.clear();
    }

    void onBoardClick(ChessOverlay.PlayMode mode, int x, int y, SessionView session) {
        boolean localWhite;
        if (mode == ChessOverlay.PlayMode.SYNC) {
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
            localWhite = scriptPlayerWhite;
            if (ChessLogic.isWhiteTurn(boardFen) != localWhite) return;
        }

        char[][] board = ChessLogic.board(boardFen);
        char clicked = board[y][x];
        boolean clickedMine = clicked != '.' && (Character.isUpperCase(clicked) == localWhite);
        List<ChessLogic.Move> legal = ChessLogic.legalMoves(boardFen);

        if (!hasSelection()) {
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
        if (mode == ChessOverlay.PlayMode.SYNC) {
            MoveSubmitResult result = runtime.submitMove(GameType.CHESS, move);
            statusText = result.ok() ? "Move sent: " + move : "Move rejected: " + result.error();
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

        scheduleScriptTurn();
    }

    private void scheduleScriptTurn() {
        if (pendingBotFuture != null && !pendingBotFuture.isDone()) return;
        long requestId = ++botRequestId;
        String fenSnapshot = boardFen;
        int levelSnapshot = scriptLevel;
        long seed = random.nextLong();
        statusText = "Script L" + levelSnapshot + " thinking...";
        pendingBotFuture = CompletableFuture.supplyAsync(
            () -> new BotResult(requestId, fenSnapshot, levelSnapshot, ChessLogic.scriptMove(fenSnapshot, new Random(seed), levelSnapshot)),
            BOT_EXECUTOR
        );
    }

    private void processScriptTurn() {
        if (pendingBotFuture == null || !pendingBotFuture.isDone()) return;
        BotResult result;
        try {
            result = pendingBotFuture.getNow(null);
        } catch (Throwable t) {
            pendingBotFuture = null;
            statusText = "Script calculation failed.";
            return;
        }
        pendingBotFuture = null;
        if (result == null || result.requestId != botRequestId) return;
        if (!result.fenSnapshot.equals(boardFen)) return;
        if (result.move.isBlank()) {
            statusText = "Script has no legal move.";
            return;
        }
        ChessLogic.ApplyResult botApplied = ChessLogic.applyMove(boardFen, result.move);
        if (!botApplied.ok()) {
            statusText = "Script move rejected.";
            return;
        }
        boardFen = botApplied.fen();
        statusText = botApplied.winner().isBlank()
            ? "Script L" + result.level + " move: " + result.move
            : "Winner: " + botApplied.winner();
    }

    List<BoardCoord> collectCaptureTargets(char[][] board, List<ChessLogic.Move> legal) {
        ArrayList<BoardCoord> targets = new ArrayList<>();
        boolean onlySelected = hasSelection();
        for (ChessLogic.Move move : legal) {
            if (onlySelected && (move.fromX() != selectedX || move.fromY() != selectedY)) continue;
            BoardCoord captured = capturedSquareForMove(board, move);
            if (captured == null) continue;
            if (!containsCoord(targets, captured.x, captured.y)) targets.add(captured);
        }
        return targets;
    }

    BoardCoord capturedForHint(char[][] board, ChessLogic.Move move) {
        return capturedSquareForMove(board, move);
    }

    private void applySyncSnapshot(SessionView session) {
        if (session != null && session.active() && session.boardState() != null && !session.boardState().isBlank()) {
            boardFen = session.boardState();
        }

        if (session == null || !session.active()) {
            statusText = "No active chess session in SyncHub.";
            clearSelection();
            return;
        }

        if (!session.winner().isBlank()) statusText = "Winner: " + session.winner();
        else statusText = session.localTurn() ? "Your turn." : "Opponent turn.";
    }

    private void selectPiece(int x, int y, List<ChessLogic.Move> legal) {
        selectedX = x;
        selectedY = y;
        selectedMoves.clear();
        for (ChessLogic.Move move : legal) {
            if (move.fromX() == x && move.fromY() == y) selectedMoves.add(move);
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

    record BoardCoord(int x, int y) {
    }

    private record BotResult(long requestId, String fenSnapshot, int level, String move) {
    }
}
