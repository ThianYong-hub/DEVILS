package com.example.addon.modules.games;

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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class DevilsGameOverlaySession {
    private static final long MOVE_ANIM_MS = 220;
    private static final long BOT_DELAY_MS = 280;
    private static final ExecutorService BOT_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "devils-checkers-script");
        t.setDaemon(true);
        return t;
    });

    private final MiniGamesSyncRuntime runtime = MiniGamesSyncRuntime.get();
    private final Random random = new Random();
    private final ArrayList<Coord> stagedPath = new ArrayList<>();

    private String boardState = CheckersLogic.initialState();
    private String statusText = "";
    private boolean flipView;
    private boolean scriptPlayerWhite = true;
    private MoveAnimation animation;
    private CompletableFuture<BotResult> pendingBotFuture;
    private long botMoveAtMs;
    private int scriptLevel = 4;
    private long botRequestId;

    void setScriptLevel(int level) {
        scriptLevel = Math.max(1, Math.min(7, level));
    }

    void onActivate(DevilsGameOverlay.PlayMode mode) {
        stagedPath.clear();
        statusText = "";
        animation = null;
        pendingBotFuture = null;
        botMoveAtMs = 0;
        botRequestId++;
        if (mode == DevilsGameOverlay.PlayMode.SCRIPT) {
            boardState = CheckersLogic.initialState();
            if (!scriptPlayerWhite) scheduleBotTurn();
        }
    }

    void onTick(DevilsGameOverlay.PlayMode mode) {
        if (mode == DevilsGameOverlay.PlayMode.SYNC) runtime.tick();
        if (animation != null && System.currentTimeMillis() - animation.startedAtMs() >= animation.durationMs()) {
            animation = null;
        }
        if (mode == DevilsGameOverlay.PlayMode.SCRIPT) processPendingBotMove();
    }

    SessionView prepare(DevilsGameOverlay.PlayMode mode) {
        if (mode != DevilsGameOverlay.PlayMode.SYNC) return null;
        SessionView session = runtime.sessionView(GameType.CHECKERS);
        applySyncSnapshot(session);
        return session;
    }

    String boardState() {
        return boardState;
    }

    String statusText() {
        return statusText;
    }

    List<Coord> stagedPath() {
        return stagedPath;
    }

    void toggleViewSide(DevilsGameOverlay.PlayMode mode) {
        if (mode == DevilsGameOverlay.PlayMode.SCRIPT) {
            scriptPlayerWhite = !scriptPlayerWhite;
            flipView = false;
            onActivate(mode);
            statusText = "Side switched to " + (scriptPlayerWhite ? "White" : "Black") + ". Game reset.";
            return;
        }
        flipView = !flipView;
    }

    boolean flippedView() {
        return flipView;
    }

    boolean resolveOrientation(DevilsGameOverlay.PlayMode mode, SessionView session) {
        boolean whiteBottom = mode == DevilsGameOverlay.PlayMode.SYNC
            ? session == null || !session.active() || session.localWhite()
            : scriptPlayerWhite;
        return flipView ? !whiteBottom : whiteBottom;
    }

    void clearStagedPath() {
        stagedPath.clear();
    }

    AnimatedPiece animatedPiece() {
        if (animation == null) return null;
        long elapsed = System.currentTimeMillis() - animation.startedAtMs();
        if (elapsed >= animation.durationMs()) {
            animation = null;
            return null;
        }

        float t = Math.max(0f, Math.min(1f, elapsed / (float) animation.durationMs()));
        float eased = t * t * (3f - 2f * t);
        float x = animation.fromX() + ((animation.toX() - animation.fromX()) * eased);
        float y = animation.fromY() + ((animation.toY() - animation.fromY()) * eased);
        return new AnimatedPiece(animation.piece(), x, y, animation.toX(), animation.toY());
    }

    boolean shouldSkipStaticPiece(int x, int y) {
        return animation != null && animation.toX() == x && animation.toY() == y;
    }

    void onBoardClick(DevilsGameOverlay.PlayMode mode, int x, int y, SessionView session) {
        if (animation != null) return;

        boolean localWhite;
        if (mode == DevilsGameOverlay.PlayMode.SYNC) {
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
            localWhite = scriptPlayerWhite;
            if (CheckersLogic.isWhiteTurn(boardState) != localWhite) return;
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

        if (mode == DevilsGameOverlay.PlayMode.SYNC) {
            MoveSubmitResult result = runtime.submitMove(GameType.CHECKERS, encoded);
            statusText = result.ok() ? "Move sent: " + encoded : "Move rejected: " + result.error();
            return;
        }

        String before = boardState;
        CheckersLogic.ApplyResult applied = CheckersLogic.applyMove(boardState, encoded);
        if (!applied.ok()) {
            statusText = "Illegal move.";
            return;
        }
        boardState = applied.state();
        startAnimation(before, boardState, exact);
        if (!applied.winner().isBlank()) {
            statusText = "Winner: " + applied.winner();
            return;
        }

        scheduleBotTurn();
    }

    List<Coord> collectHints(List<Move> legal) {
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

    List<Coord> collectCaptureTargets(char[][] board, List<Move> legal) {
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

    private void applySyncSnapshot(SessionView session) {
        if (session != null && session.active() && session.boardState() != null && !session.boardState().isBlank()) {
            boardState = session.boardState();
        }

        if (session == null || !session.active()) {
            statusText = "No active checkers session in SyncHub.";
            stagedPath.clear();
            return;
        }

        if (!session.winner().isBlank()) statusText = "Winner: " + session.winner();
        else statusText = session.localTurn() ? "Your turn." : "Opponent turn.";
    }

    private void processPendingBotMove() {
        if (pendingBotFuture == null || !pendingBotFuture.isDone()) return;
        if (animation != null) return;
        if (System.currentTimeMillis() < botMoveAtMs) return;

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
        if (!result.stateSnapshot.equals(boardState)) return;
        String botMove = result.move;
        if (botMove.isBlank()) {
            statusText = "Script has no legal move.";
            return;
        }
        String before = boardState;
        CheckersLogic.ApplyResult botApplied = CheckersLogic.applyMove(boardState, botMove);
        if (!botApplied.ok()) return;

        boardState = botApplied.state();
        Move parsed = CheckersLogic.parseMove(botMove);
        startAnimation(before, boardState, parsed);
        statusText = botApplied.winner().isBlank()
            ? "Script L" + result.level + " move: " + botMove
            : "Winner: " + botApplied.winner();
    }

    private void scheduleBotTurn() {
        if (pendingBotFuture != null && !pendingBotFuture.isDone()) return;
        long requestId = ++botRequestId;
        String snapshot = boardState;
        int levelSnapshot = scriptLevel;
        long seed = random.nextLong();
        botMoveAtMs = System.currentTimeMillis() + BOT_DELAY_MS;
        statusText = "Script L" + levelSnapshot + " thinking...";
        pendingBotFuture = CompletableFuture.supplyAsync(
            () -> new BotResult(requestId, snapshot, levelSnapshot, CheckersLogic.scriptMove(snapshot, new Random(seed), levelSnapshot)),
            BOT_EXECUTOR
        );
    }

    private void startAnimation(String beforeState, String afterState, Move move) {
        if (move == null || move.path() == null || move.path().size() < 2) {
            animation = null;
            return;
        }

        Coord from = move.path().get(0);
        Coord to = move.path().get(move.path().size() - 1);
        char[][] after = CheckersLogic.board(afterState);
        char piece = after[to.y()][to.x()];
        if (piece == '.') {
            char[][] before = CheckersLogic.board(beforeState);
            piece = before[from.y()][from.x()];
        }
        if (piece == '.') {
            animation = null;
            return;
        }

        animation = new MoveAnimation(piece, from.x(), from.y(), to.x(), to.y(), System.currentTimeMillis(), MOVE_ANIM_MS);
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

    private static boolean hasMoveStartingWith(List<Move> legal, List<Coord> prefix) {
        for (Move move : legal) if (startsWith(move.path(), prefix)) return true;
        return false;
    }

    private static boolean hasLongerWithSamePrefix(List<Move> legal, List<Coord> prefix) {
        for (Move move : legal) if (move.path().size() > prefix.size() && startsWith(move.path(), prefix)) return true;
        return false;
    }

    private static Move findExactMove(List<Move> legal, List<Coord> path) {
        for (Move move : legal) if (move.path().size() == path.size() && startsWith(move.path(), path)) return move;
        return null;
    }

    private static boolean startsWith(List<Coord> path, List<Coord> prefix) {
        if (path == null || prefix == null || path.size() < prefix.size()) return false;
        for (int i = 0; i < prefix.size(); i++) {
            Coord a = path.get(i);
            Coord b = prefix.get(i);
            if (a.x() != b.x() || a.y() != b.y()) return false;
        }
        return true;
    }

    private static boolean containsCoord(List<Coord> list, Coord value) {
        for (Coord c : list) if (c.x() == value.x() && c.y() == value.y()) return true;
        return false;
    }

    record AnimatedPiece(char piece, float x, float y, int toX, int toY) {
    }

    private record MoveAnimation(char piece, int fromX, int fromY, int toX, int toY, long startedAtMs, long durationMs) {
    }

    private record BotResult(long requestId, String stateSnapshot, int level, String move) {
    }
}
