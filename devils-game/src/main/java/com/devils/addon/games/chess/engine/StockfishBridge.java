package com.devils.addon.games.chess.engine;

import com.devils.addon.games.DevilsGameAddon;

import java.io.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * ProcessBuilder bridge to the Stockfish chess engine.
 * <p>
 * Communication model: Stockfish runs as a child process. Java sends commands via
 * stdin and reads responses from stdout.
 * <p>
 * Lifecycle:
 * <ol>
 *   <li>{@link #init(String)} — starts the Stockfish process</li>
 *   <li>{@link #sendCommand} / {@link #readLine} — UCI dialogue</li>
 *   <li>{@link #shutdown()} — sends "quit" and waits for process exit</li>
 * </ol>
 */
public final class StockfishBridge {

    private static final String LOG_PREFIX = "[StockfishBridge]";

    private static volatile Process process;
    private static volatile BufferedWriter stdin;
    private static volatile BlockingQueue<String> outputQueue;
    private static volatile Thread readerThread;
    private static volatile boolean running;

    private StockfishBridge() {}

    /**
     * Initializes the Stockfish engine by launching stockfish.exe as a subprocess.
     *
     * @param workDir working directory path (where NNUE files reside); may be null
     * @throws Exception if the engine fails to start
     */
    public static synchronized void init(String workDir) throws Exception {
        if (running) {
            shutdown();
        }

        String stockfishPath = NativeLoader.getStockfishPath();
        if (stockfishPath == null || stockfishPath.isEmpty()) {
            throw new IllegalStateException(LOG_PREFIX + " Stockfish executable not found.");
        }

        ProcessBuilder pb = new ProcessBuilder(stockfishPath);
        if (workDir != null) {
            pb.directory(new File(workDir));
        }
        pb.redirectErrorStream(true);

        process = pb.start();
        stdin = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), "UTF-8"));
        outputQueue = new LinkedBlockingQueue<>();

        // Start reader thread
        running = true;
        readerThread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), "UTF-8"))) {
                String line;
                while (running && (line = reader.readLine()) != null) {
                    outputQueue.offer(line);
                }
                DevilsGameAddon.LOG.debug("{} reader stream ended", LOG_PREFIX);
            } catch (IOException e) {
                if (running) {
                    DevilsGameAddon.LOG.warn("{} reader error", LOG_PREFIX, e);
                }
            } finally {
                outputQueue.offer("__ENGINE_EXITED__");
            }
        }, "stockfish-process-reader");
        readerThread.setDaemon(true);
        readerThread.start();

        // Wait for UCI handshake
        Thread.sleep(200);
        sendCommand("uci");

        // Read lines until we get "uciok"
        String line;
        while ((line = readLine(5000)) != null) {
            if ("uciok".equals(line)) {
                break;
            }
        }

        sendCommand("isready");
        while ((line = readLine(5000)) != null) {
            if ("readyok".equals(line)) {
                break;
            }
        }

        DevilsGameAddon.LOG.info("{} Stockfish process started (pid={})", LOG_PREFIX, process.pid());
    }

    /**
     * Sends a command to the Stockfish engine.
     *
     * @param command UCI command string
     * @throws IllegalStateException if the engine is not running
     */
    public static synchronized void sendCommand(String command) {
        if (!running || stdin == null) {
            throw new IllegalStateException(LOG_PREFIX + " Engine is not running");
        }
        try {
            DevilsGameAddon.LOG.debug("{} >> {}", LOG_PREFIX, command);
            stdin.write(command);
            stdin.newLine();
            stdin.flush();
        } catch (IOException e) {
            throw new IllegalStateException(LOG_PREFIX + " Failed to send command: " + command, e);
        }
    }

    /**
     * Reads the next line from the Stockfish engine.
     *
     * @param timeoutMs maximum time to wait for a line in milliseconds
     * @return the line read, or null if timed out or engine exited
     */
    public static String readLine(long timeoutMs) {
        if (!running || outputQueue == null) {
            return null;
        }
        try {
            String line = outputQueue.poll(timeoutMs, TimeUnit.MILLISECONDS);
            if ("__ENGINE_EXITED__".equals(line)) {
                DevilsGameAddon.LOG.debug("{} engine exited signal received", LOG_PREFIX);
                running = false;
                return null;
            }
            return line;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /**
     * Shuts down the Stockfish engine process.
     */
    public static synchronized void shutdown() {
        running = false;

        // Send quit command
        if (stdin != null) {
            try {
                stdin.write("quit");
                stdin.newLine();
                stdin.flush();
                stdin.close();
            } catch (Exception ignored) {}
            stdin = null;
        }

        // Wait for process to exit
        if (process != null && process.isAlive()) {
            try {
                process.waitFor(3, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (process.isAlive()) {
                process.destroyForcibly();
            }
            process = null;
        }

        // Interrupt reader thread
        if (readerThread != null) {
            readerThread.interrupt();
            readerThread = null;
        }

        outputQueue = null;
        DevilsGameAddon.LOG.info("{} Stockfish process shut down", LOG_PREFIX);
    }

    /** Returns true if the engine process is running. */
    public static boolean isRunning() {
        return running && process != null && process.isAlive();
    }
}
