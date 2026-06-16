package com.devils.addon.games.chess.engine;

import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Provides the path to the Stockfish chess engine binary.
 * <p>
 * On first launch the official {@code stockfish.exe} (with built-in NNUE)
 * is downloaded via {@link StockfishDownloader} and cached under
 * {@code .minecraft/devils-game/engine/}.
 * Subsequent launches reuse the cached binary.
 */
public final class NativeLoader {

    private static final Logger LOG = LoggerFactory.getLogger(NativeLoader.class);

    private NativeLoader() {}

    /**
     * Returns an absolute path to a usable stockfish binary.
     * Downloads the binary on first run if it is not yet cached.
     *
     * @return absolute path to stockfish executable
     * @throws IllegalStateException if the download fails
     */
    public static String getStockfishPath() {
        Path mcDir = FabricLoader.getInstance().getGameDir();
        try {
            Path exe = StockfishDownloader.ensureEngine(mcDir);
            LOG.info("[NativeLoader] Stockfish binary: {}", exe);
            return exe.toAbsolutePath().toString();
        } catch (IOException e) {
            throw new IllegalStateException(
                "Не удалось получить Stockfish: " + e.getMessage(), e);
        }
    }
}
