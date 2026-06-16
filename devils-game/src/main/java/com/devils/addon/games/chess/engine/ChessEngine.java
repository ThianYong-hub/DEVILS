package com.devils.addon.games.chess.engine;

import com.devils.addon.games.DevilsGameAddon;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * High-level async API for the Stockfish chess engine.
 * <p>
 * Usage:
 * <pre>{@code
 *   ChessEngine engine = new ChessEngine();
 *   if (engine.init()) {
 *       engine.getBestMove("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1", 20, 3000)
 *             .thenAccept(result -> {
 *                 System.out.println("Best move: " + result.bestMove());
 *             });
 *   }
 * }</pre>
 */
public final class ChessEngine implements AutoCloseable {

    // ─── Result types ─────────────────────────────────────────────────────────

    public record BestMoveResult(
        String bestMove,
        String ponder,
        int depth,
        int score,
        long timeMs
    ) {}

    // ─── Configuration ────────────────────────────────────────────────────────

    private int hashMb = 128;
    private int threads = 1;
    private int skillLevel = 20;      // 0–20, Stockfish default = 20 (full strength)
    private int moveOverhead = 10;     // ms
    private boolean ponder = false;
    private int multiPv = 1;
    private int defaultDepth = 20;
    private int defaultMoveTime = 5000; // ms

    // ─── State ────────────────────────────────────────────────────────────────

    private final AtomicBoolean initialized = new AtomicBoolean(false);
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread readerThread;

    /** Queue where the reader thread pushes raw UCI lines. */
    private final BlockingQueue<String> lineQueue = new LinkedBlockingQueue<>();

    /** Pending bestmove future, set when a "go" command is in flight. */
    private volatile CompletableFuture<BestMoveResult> pendingFuture;
    private volatile BestMoveAccumulator currentAccumulator;

    private static final Pattern BESTMOVE_PATTERN =
        Pattern.compile("bestmove\\s+(\\S+)(?:\\s+ponder\\s+(\\S+))?");
    private static final Pattern INFO_DEPTH_PATTERN =
        Pattern.compile("info\\s+.*depth\\s+(\\d+)");
    private static final Pattern INFO_SCORE_PATTERN =
        Pattern.compile("info\\s+.*score\\s+cp\\s+(-?\\d+)");
    private static final Pattern INFO_MATE_PATTERN =
        Pattern.compile("info\\s+.*score\\s+mate\\s+(-?\\d+)");

    // ─── Public API ───────────────────────────────────────────────────────────

    public ChessEngine() {}

    /** Initialize the engine. Returns false if native library is unavailable. */
    public boolean init() {
        if (initialized.get()) return true;

        try {
            StockfishBridge.init(null);
        } catch (Exception e) {
            DevilsGameAddon.LOG.error("[ChessEngine] Failed to init Stockfish bridge", e);
            return false;
        }

        initialized.set(true);
        running.set(true);

        // Start reader thread BEFORE UCI handshake so lineQueue is fed
        readerThread = new Thread(this::readerLoop, "stockfish-reader");
        readerThread.setDaemon(true);
        readerThread.start();

        // Configure UCI options (sends "uci" / "isready" and waits for responses)
        sendUciOptions();

        DevilsGameAddon.LOG.info("[ChessEngine] Stockfish initialized");
        return true;
    }

    /**
     * Request the best move for the given position.
     *
     * @param fen      FEN string of the position
     * @param depth    search depth (0 = use movetime instead)
     * @param movetime time limit in milliseconds (0 = use depth instead)
     * @return future that completes with the best move result
     */
    public CompletableFuture<BestMoveResult> getBestMove(String fen, int depth, int movetime) {
        if (!running.get()) {
            System.err.println("[ChessEngine] getBestMove REJECTED: engine not running");
            return CompletableFuture.failedFuture(
                new IllegalStateException("Engine is not running"));
        }

        if (pendingFuture != null && !pendingFuture.isDone()) {
            // Cancel previous search
            System.err.println("[ChessEngine] Cancelling previous search before new request");
            StockfishBridge.sendCommand("stop");
            if (pendingFuture != null) {
                pendingFuture.completeExceptionally(new RuntimeException("Search cancelled by new request"));
            }
        }

        CompletableFuture<BestMoveResult> future = new CompletableFuture<>();
        pendingFuture = future;
        currentAccumulator = new BestMoveAccumulator();

        String positionCmd = "position fen " + fen;
        StockfishBridge.sendCommand(positionCmd);
        System.err.println("[ChessEngine] >> " + positionCmd);

        StringBuilder goCmd = new StringBuilder("go");
        if (depth > 0) goCmd.append(" depth ").append(depth);
        if (movetime > 0) goCmd.append(" movetime ").append(movetime);
        if (depth <= 0 && movetime <= 0) {
            goCmd.append(" depth ").append(defaultDepth);
        }
        StockfishBridge.sendCommand(goCmd.toString());
        System.err.println("[ChessEngine] >> " + goCmd + " (depth=" + depth + ", movetime=" + movetime + ", defaultDepth=" + defaultDepth + ")");

        return future;
    }

    /**
     * Quick best move with default parameters.
     */
    public CompletableFuture<BestMoveResult> getBestMove(String fen) {
        return getBestMove(fen, defaultDepth, 0);
    }

    /**
     * Stop current search (if any) and wait for result.
     */
    public void stopSearch() {
        if (running.get()) {
            StockfishBridge.sendCommand("stop");
        }
    }

    /** Returns true if the engine is initialized and running. */
    public boolean isReady() {
        return initialized.get() && running.get();
    }

    // ─── Configuration setters ────────────────────────────────────────────────

    public ChessEngine setHashMb(int hashMb) {
        this.hashMb = hashMb;
        return this;
    }

    public ChessEngine setThreads(int threads) {
        this.threads = threads;
        return this;
    }

    public ChessEngine setSkillLevel(int skillLevel) {
        this.skillLevel = Math.max(0, Math.min(20, skillLevel));
        return this;
    }

    public ChessEngine setDefaultDepth(int depth) {
        this.defaultDepth = depth;
        return this;
    }

    public ChessEngine setDefaultMoveTime(int ms) {
        this.defaultMoveTime = ms;
        return this;
    }

    // ─── Closeable ────────────────────────────────────────────────────────────

    @Override
    public void close() {
        if (!initialized.compareAndSet(true, false)) return;
        running.set(false);

        try {
            StockfishBridge.shutdown();
        } catch (Exception e) {
            DevilsGameAddon.LOG.warn("[ChessEngine] Error during shutdown", e);
        }

        if (readerThread != null) {
            readerThread.interrupt();
            try {
                readerThread.join(2000);
            } catch (InterruptedException ignored) {}
        }

        if (pendingFuture != null && !pendingFuture.isDone()) {
            pendingFuture.completeExceptionally(new RuntimeException("Engine shut down"));
        }

        DevilsGameAddon.LOG.info("[ChessEngine] Stockfish shut down");
    }

    // ─── Internal ─────────────────────────────────────────────────────────────

    private void sendUciOptions() {
        StockfishBridge.sendCommand("uci");

        // Wait for "uciok" with a timeout
        if (!waitForLine("uciok", 5000)) {
            DevilsGameAddon.LOG.warn("[ChessEngine] Timed out waiting for uciok");
        }

        StockfishBridge.sendCommand("setoption name Hash value " + hashMb);
        StockfishBridge.sendCommand("setoption name Threads value " + threads);

        // Stockfish 16+ has built-in NNUE; no external EvalFile needed.
        StockfishBridge.sendCommand("setoption name Skill Level value " + skillLevel);
        StockfishBridge.sendCommand("setoption name Move Overhead value " + moveOverhead);
        StockfishBridge.sendCommand("setoption name MultiPV value " + multiPv);
        if (!ponder) {
            StockfishBridge.sendCommand("setoption name Ponder value false");
        }

        StockfishBridge.sendCommand("isready");
        if (!waitForLine("readyok", 5000)) {
            DevilsGameAddon.LOG.warn("[ChessEngine] Timed out waiting for readyok");
        }
    }

    private boolean waitForLine(String expected, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            try {
                String line = lineQueue.poll(100, TimeUnit.MILLISECONDS);
                if (line != null && line.trim().equals(expected)) {
                    return true;
                }
                if (line != null) {
                    // Push back for reader loop — but since reader loop also reads from the queue,
                    // we process it inline here. For uciok/readyok we just need the match.
                    processLine(line);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private void readerLoop() {
        System.err.println("[ChessEngine] readerLoop STARTED, running=" + running.get() + " sfRunning=" + StockfishBridge.isRunning());
        try {
            while (running.get() && StockfishBridge.isRunning()) {
                String line = StockfishBridge.readLine(1000);
                if (line == null) {
                    System.err.println("[ChessEngine] readerLoop: readLine returned null (timeout or exit)");
                    break;
                }
                System.err.println("[ChessEngine] << " + line);
                lineQueue.offer(line);
                processLine(line);
            }
        } catch (Exception e) {
            if (running.get()) {
                System.err.println("[ChessEngine] readerLoop EXCEPTION: " + e);
                e.printStackTrace(System.err);
            }
        } finally {
            running.set(false);
            System.err.println("[ChessEngine] readerLoop EXITED");
        }
    }

    private void processLine(String line) {
        if (line == null) return;
        line = line.trim();

        // Check for bestmove
        Matcher bmMatcher = BESTMOVE_PATTERN.matcher(line);
        if (bmMatcher.find()) {
            String bestMove = bmMatcher.group(1);
            String ponderMove = bmMatcher.group(2);
            System.err.println("[ChessEngine] processLine: BEST MOVE FOUND: " + bestMove + " ponder=" + ponderMove);

            BestMoveAccumulator acc = currentAccumulator;
            BestMoveResult result = new BestMoveResult(
                bestMove,
                ponderMove,
                acc != null ? acc.depth : 0,
                acc != null ? acc.score : 0,
                acc != null ? acc.timeMs : 0
            );

            CompletableFuture<BestMoveResult> future = pendingFuture;
            if (future != null && !future.isDone()) {
                future.complete(result);
            }
            pendingFuture = null;
            currentAccumulator = null;
            return;
        }

        // Accumulate info lines
        BestMoveAccumulator acc = currentAccumulator;
        if (acc != null) {
            Matcher depthMatcher = INFO_DEPTH_PATTERN.matcher(line);
            if (depthMatcher.find()) {
                acc.depth = Integer.parseInt(depthMatcher.group(1));
            }

            Matcher cpMatcher = INFO_SCORE_PATTERN.matcher(line);
            if (cpMatcher.find()) {
                acc.score = Integer.parseInt(cpMatcher.group(1));
            }

            Matcher mateMatcher = INFO_MATE_PATTERN.matcher(line);
            if (mateMatcher.find()) {
                int mateIn = Integer.parseInt(mateMatcher.group(1));
                acc.score = mateIn > 0 ? 30000 - mateIn : -30000 - mateIn;
            }
        }
    }

    /** Mutable accumulator for search info while waiting for bestmove. */
    private static class BestMoveAccumulator {
        int depth;
        int score;
        long timeMs;
    }
}
