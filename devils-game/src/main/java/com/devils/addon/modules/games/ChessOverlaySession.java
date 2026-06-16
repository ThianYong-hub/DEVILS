package com.devils.addon.modules.games;

import com.devils.addon.games.MiniGamesContracts.GameType;
import com.devils.addon.games.MiniGamesContracts.MoveSubmitResult;
import com.devils.addon.games.MiniGamesContracts.SessionView;
import com.devils.addon.games.chess.ChessLogic;
import com.devils.addon.games.chess.engine.ChessEngine;
import com.devils.addon.games.sync.MiniGamesSyncRuntime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

final class ChessOverlaySession {
    private final MiniGamesSyncRuntime runtime = MiniGamesSyncRuntime.get();
    private final ArrayList<ChessLogic.Move> selectedMoves = new ArrayList<>();

    private String boardFen = ChessLogic.initialFen();
    private String statusText = "";
    private int selectedX = -1;
    private int selectedY = -1;
    private boolean flipView;
    private boolean whiteToMove = true;
    private long botRequestId;

    private ChessEngine stockfishEngine;
    private int sfDepth = 15;
    private int sfSkill = 20;
    private int sfHash = 128;

    void setStockfishConfig(int depth, int skill, int hash) {
        this.sfDepth = Math.max(1, Math.min(30, depth));
        this.sfSkill = Math.max(0, Math.min(20, skill));
        this.sfHash = Math.max(1, Math.min(1024, hash));
    }

    void onActivate(ChessOverlay.PlayMode mode) {
        clearSelection();
        statusText = "";
        botRequestId++;
        if (mode == ChessOverlay.PlayMode.STOCKFISH) {
            initEngine();
            boardFen = ChessLogic.initialFen();
        }
    }

    void onTick(ChessOverlay.PlayMode mode) {
        if (mode == ChessOverlay.PlayMode.SYNC) runtime.tick();
        else if (mode == ChessOverlay.PlayMode.STOCKFISH) processStockfishTurn();
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
        if (mode == ChessOverlay.PlayMode.STOCKFISH) {
            whiteToMove = !whiteToMove;
            flipView = false;
            onActivate(mode);
            statusText = "Side switched to " + (whiteToMove ? "White" : "Black") + ". Game reset.";
            return;
        }
        flipView = !flipView;
    }

    boolean resolveOrientation(ChessOverlay.PlayMode mode, SessionView session) {
        boolean whiteBottom = mode == ChessOverlay.PlayMode.SYNC
            ? session == null || !session.active() || session.localWhite()
            : whiteToMove;
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
            localWhite = whiteToMove;
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

        scheduleStockfishTurn();
    }

    // --- Stockfish engine integration ---

    private void initEngine() {
        if (stockfishEngine != null) return;
        stockfishEngine = new ChessEngine();
        try {
            stockfishEngine.init();
            stockfishEngine.setSkillLevel(sfSkill);
            stockfishEngine.setHashMb(sfHash);
        } catch (Exception e) {
            statusText = "Stockfish init failed: " + e.getMessage();
            stockfishEngine = null;
        }
    }

    void shutdownEngine() {
        if (stockfishEngine != null) {
            stockfishEngine.close();
            stockfishEngine = null;
        }
    }

    private CompletableFuture<StockfishBotResult> pendingStockfishFuture;

    private void scheduleStockfishTurn() {
        if (stockfishEngine == null) {
            statusText = "Stockfish engine not available.";
            return;
        }
        if (pendingStockfishFuture != null && !pendingStockfishFuture.isDone()) return;
        long requestId = ++botRequestId;
        String fenSnapshot = boardFen;
        int depthSnapshot = sfDepth;
        statusText = "Stockfish D" + depthSnapshot + " thinking...";
        pendingStockfishFuture = stockfishEngine.getBestMove(fenSnapshot, depthSnapshot, 0)
            .thenApply(result -> new StockfishBotResult(requestId, fenSnapshot, result.bestMove(), result.depth(), result.score()));
    }

    private void processStockfishTurn() {
        if (pendingStockfishFuture == null || !pendingStockfishFuture.isDone()) return;
        StockfishBotResult result;
        try {
            result = pendingStockfishFuture.getNow(null);
        } catch (Throwable t) {
            pendingStockfishFuture = null;
            statusText = "Stockfish calculation failed.";
            return;
        }
        pendingStockfishFuture = null;
        if (result == null || result.requestId != botRequestId) return;
        if (!result.fenSnapshot.equals(boardFen)) return;
        if (result.bestMove == null || result.bestMove.isBlank()) {
            statusText = "Stockfish has no legal move.";
            return;
        }
        ChessLogic.ApplyResult applied = ChessLogic.applyMove(boardFen, result.bestMove);
        if (!applied.ok()) {
            statusText = "Stockfish move rejected: " + result.bestMove;
            return;
        }
        boardFen = applied.fen();
        statusText = applied.winner().isBlank()
            ? "Stockfish D" + result.depth + " score:" + result.score + " move: " + result.bestMove
            : "Winner: " + applied.winner();
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
            statusText = "No active chess session in Game Sync.";
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

    private record StockfishBotResult(long requestId, String fenSnapshot, String bestMove, int depth, int score) {
    }
}

