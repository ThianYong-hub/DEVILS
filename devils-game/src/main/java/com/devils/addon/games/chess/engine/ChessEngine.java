package com.devils.addon.games.chess.engine;

import com.devils.addon.games.DevilsGameAddon;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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

    private volatile int hashMb = 128;
    private volatile int threads = 1;
    private volatile int skillLevel = 20;      // 0–20, Stockfish default = 20 (full strength)
    private volatile int moveOverhead = 10;     // ms
    private volatile boolean ponder = false;
    private volatile int multiPv = 1;
    private int defaultDepth = 20;
    private int defaultMoveTime = 5000; // ms

    // ─── State ────────────────────────────────────────────────────────────────

    private final AtomicBoolean initialized = new AtomicBoolean(false);
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread readerThread;
    /** Increments per issued search; bestmove replies from an older search are ignored. */
    private final java.util.concurrent.atomic.AtomicLong searchGeneration = new java.util.concurrent.atomic.AtomicLong();

    /** Queue where the reader thread pushes raw UCI lines. */
    private final BlockingQueue<String> lineQueue = new LinkedBlockingQueue<>();

    /** Pending bestmove future, set when a "go" command is in flight. */
    private volatile CompletableFuture<BestMoveResult> pendingFuture;
    private volatile BestMoveAccumulator currentAccumulator;
    /** Generation of the search that pendingFuture belongs to; stale bestmove replies are dropped. */
    private volatile long pendingGeneration;
    private volatile boolean handshakeComplete = false;

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

    /** Initialize the engine. Returns false if the process or UCI handshake fails. */
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

        // Configure UCI options (sends "uci" / "isready" and waits for responses).
        // A corrupt or wrong-arch binary must fail here instead of pretending to be ready.
        if (!sendUciOptions()) {
            DevilsGameAddon.LOG.error("[ChessEngine] Stockfish UCI handshake failed; engine unusable");
            close();
            return false;
        }

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
            return CompletableFuture.failedFuture(
                new IllegalStateException("Engine is not running"));
        }

        if (pendingFuture != null && !pendingFuture.isDone()) {
            // Cancel previous search
            stopSearch();
            if (pendingFuture != null) {
                pendingFuture.completeExceptionally(new RuntimeException("Search cancelled by new request"));
            }
        }

        CompletableFuture<BestMoveResult> future = new CompletableFuture<>();
        BestMoveAccumulator accumulator = new BestMoveAccumulator(searchGeneration.incrementAndGet());
        pendingFuture = future;
        currentAccumulator = accumulator;
        pendingGeneration = accumulator.generation;

        String positionCmd = "position fen " + fen;
        StockfishBridge.sendCommand(positionCmd);

        StringBuilder goCmd = new StringBuilder("go");
        if (depth > 0) goCmd.append(" depth ").append(depth);
        if (movetime > 0) goCmd.append(" movetime ").append(movetime);
        if (depth <= 0 && movetime <= 0) {
            goCmd.append(" depth ").append(defaultDepth);
        }
        StockfishBridge.sendCommand(goCmd.toString());

        // Safety timeout: if bestmove not received within 60s, fail the future
        long timeoutMs = Math.max(movetime, defaultDepth * 2000L) + 30000L;
        future.orTimeout(timeoutMs, TimeUnit.MILLISECONDS)
              .exceptionally(ex -> {
                  DevilsGameAddon.LOG.warn("[ChessEngine] Timed out after {}ms without bestmove", timeoutMs);
                  stopSearch();
                  return null;
              });

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
        if (running.get() && StockfishBridge.isRunning()) {
            try {
                StockfishBridge.sendCommand("stop");
            } catch (IllegalStateException e) {
                DevilsGameAddon.LOG.debug("[ChessEngine] Ignored stop after engine exit", e);
            }
        }
    }

    /** Returns true if the engine is initialized and running. */
    public boolean isReady() {
        return initialized.get() && running.get();
    }

    // ─── Configuration setters ────────────────────────────────────────────────
    // The session re-applies these every tick, so each setter only talks to the
    // engine when the value actually changed since the last "setoption".

    public ChessEngine setHashMb(int hashMb) {
        this.hashMb = hashMb;
        if (running.get() && hashMb != appliedHashMb) {
            appliedHashMb = hashMb;
            sendOptionIfRunning("Hash", hashMb);
        }
        return this;
    }

    public ChessEngine setThreads(int threads) {
        this.threads = threads;
        if (running.get() && threads != appliedThreads) {
            appliedThreads = threads;
            sendOptionIfRunning("Threads", threads);
        }
        return this;
    }

    public ChessEngine setSkillLevel(int skillLevel) {
        int clamped = Math.max(0, Math.min(20, skillLevel));
        this.skillLevel = clamped;
        if (running.get() && clamped != appliedSkillLevel) {
            appliedSkillLevel = clamped;
            sendOptionIfRunning("Skill Level", clamped);
        }
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

    private volatile int appliedHashMb = -1;
    private volatile int appliedThreads = -1;
    private volatile int appliedSkillLevel = -1;

    private void sendOptionIfRunning(String name, Object value) {
        if (!running.get() || !StockfishBridge.isRunning()) return;
        try {
            StockfishBridge.sendCommand("setoption name " + name + " value " + value);
            StockfishBridge.sendCommand("isready");
        } catch (IllegalStateException e) {
            DevilsGameAddon.LOG.debug("[ChessEngine] Ignored option update after engine exit: {}", name);
        }
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

    private boolean sendUciOptions() {
        StockfishBridge.sendCommand("uci");

        // Wait for "uciok" with a timeout
        if (!waitForLine("uciok", 5000)) {
            DevilsGameAddon.LOG.warn("[ChessEngine] Timed out waiting for uciok");
            return false;
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
        appliedHashMb = hashMb;
        appliedThreads = threads;
        appliedSkillLevel = skillLevel;

        StockfishBridge.sendCommand("isready");
        if (!waitForLine("readyok", 5000)) {
            DevilsGameAddon.LOG.warn("[ChessEngine] Timed out waiting for readyok");
            return false;
        }

        handshakeComplete = true;
        lineQueue.clear();
        return true;
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
        try {
            while (running.get() && StockfishBridge.isRunning()) {
                String line = StockfishBridge.readLine(2000);
                if (line == null) {
                    if (!StockfishBridge.isRunning()) {
                        break;
                    }
                    continue;
                }
                if (!handshakeComplete) {
                    lineQueue.offer(line);
                }
                processLine(line);
            }
        } catch (Exception e) {
            if (running.get()) {
                DevilsGameAddon.LOG.warn("[ChessEngine] Reader loop failed", e);
            }
        } finally {
            running.set(false);
        }
    }

    private void processLine(String line) {
        if (line == null) return;
        line = line.trim();

        // Check for bestmove
        Matcher bmMatcher = BESTMOVE_PATTERN.matcher(line);
        if (bmMatcher.find()) {
            BestMoveAccumulator acc = currentAccumulator;
            if (acc == null || acc.generation != pendingGeneration) {
                // Reply to a cancelled/previous search; a live search is still waiting
                // for its own bestmove, so this one must not complete it.
                return;
            }

            String bestMove = bmMatcher.group(1);
            String ponderMove = bmMatcher.group(2);

            BestMoveResult result = new BestMoveResult(
                bestMove,
                ponderMove,
                acc.depth,
                acc.score,
                acc.timeMs
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
        final long generation;
        int depth;
        int score;
        long timeMs;

        BestMoveAccumulator(long generation) {
            this.generation = generation;
        }
    }
}
