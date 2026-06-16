package com.devils.addon.games.chess.engine;

/**
 * JNI bridge to the Stockfish chess engine.
 * <p>
 * Communication model: Stockfish runs its UCI loop on a background thread inside
 * the native library. Java sends commands via {@link #sendCommand} and reads
 * responses via {@link #readLine}. Stdin/stdout are redirected to in-memory pipes.
 * <p>
 * Lifecycle:
 * <ol>
 *   <li>{@link NativeLoader#load()} — extracts and loads the shared library</li>
 *   <li>{@link #init(String)} — starts the Stockfish UCI loop on a native thread</li>
 *   <li>{@link #sendCommand} / {@link #readLine} — UCI dialogue</li>
 *   <li>{@link #shutdown()} — sends "quit" and joins the native thread</li>
 * </ol>
 * <p>
 * Thread safety: all native methods are internally synchronized on the native side.
 * {@link #readLine()} blocks until a line is available or the engine shuts down.
 */
public final class StockfishBridge {

    private StockfishBridge() {}

    // ─── Native methods ───────────────────────────────────────────────────────

    /**
     * Initializes the Stockfish engine.
     * <p>
     * Starts a background thread running the UCI loop. Stdin/stdout are captured
     * via internal pipes so that {@link #sendCommand} and {@link #readLine} can
     * communicate with the engine.
     *
     * @param workDir working directory path (where NNUE files reside); may be null
     *                to use the current directory
     * @throws IllegalStateException if the engine is already running
     */
    public static native void init(String workDir);

    /**
     * Sends a UCI command to the engine.
     * <p>
     * The command is written to the engine's redirected stdin. A trailing newline
     * is appended automatically if not present.
     *
     * @param command the UCI command string, e.g. "uci", "position startpos", "go depth 20"
     * @throws IllegalStateException if the engine is not running
     */
    public static native void sendCommand(String command);

    /**
     * Reads one line from the engine's redirected stdout.
     * <p>
     * This method blocks until a complete line is available. Returns null if the
     * engine has shut down and the pipe is empty.
     *
     * @return a line of engine output, or null if EOF
     */
    public static native String readLine();

    /**
     * Returns true if the engine process is still running and can accept commands.
     */
    public static native boolean isRunning();

    /**
     * Sends "quit" to the engine and waits for the background thread to finish.
     * <p>
     * Safe to call multiple times. After this call, {@link #init(String)} may be
     * called again to restart.
     */
    public static native void shutdown();
}
