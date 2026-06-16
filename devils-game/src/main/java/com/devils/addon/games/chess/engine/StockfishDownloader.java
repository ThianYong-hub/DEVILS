package com.devils.addon.games.chess.engine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Downloads the official Stockfish binary from GitHub releases on first launch.
 * <p>
 * The {@code stockfish.exe} includes a built-in NNUE network, so no separate
 * NNUE download is required.
 * <p>
 * Cache location: {@code .minecraft/devils-game/engine/stockfish.exe}
 */
public final class StockfishDownloader {

    private static final Logger LOG = LoggerFactory.getLogger(StockfishDownloader.class);

    // Stockfish release version and platform variant.
    // avx2 is compatible with virtually all CPUs from 2013+.
    public static final String SF_VERSION = "sf_17.1";
    public static final String SF_VARIANT = "stockfish-windows-x86-64-avx2";
    public static final String SF_URL =
        "https://github.com/official-stockfish/Stockfish/releases/download/"
        + SF_VERSION + "/" + SF_VARIANT + ".zip";

    private static final long MIN_EXE_SIZE = 1_000_000L;
    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 180_000;

    private StockfishDownloader() {}

    /**
     * Ensures the Stockfish binary exists and returns its path.
     * Downloads and extracts from a ZIP archive on first run.
     *
     * @param minecraftDir path to {@code .minecraft} directory (from FabricLoader)
     * @return path to {@code stockfish.exe}
     * @throws IOException if download or extraction fails
     */
    public static Path ensureEngine(Path minecraftDir) throws IOException {
        Path engineDir = minecraftDir.resolve("devils-game").resolve("engine");
        Path exe = engineDir.resolve("stockfish.exe");

        // Cache hit: file exists and looks reasonable
        if (Files.exists(exe) && Files.size(exe) > MIN_EXE_SIZE) {
            LOG.info("[StockfishDownloader] Cached engine: {}", exe);
            return exe;
        }

        Files.createDirectories(engineDir);
        Path zip = engineDir.resolve("sf.zip");

        LOG.info("[StockfishDownloader] Downloading Stockfish (first run, ~40 MB): {}", SF_URL);
        download(SF_URL, zip);

        extractExeFromZip(zip, exe);
        Files.deleteIfExists(zip);

        if (!Files.exists(exe) || Files.size(exe) < MIN_EXE_SIZE) {
            throw new IOException("stockfish.exe could not be extracted from archive");
        }

        LOG.info("[StockfishDownloader] Ready: {} ({} MB)",
            exe, Files.size(exe) / 1_048_576);
        return exe;
    }

    private static void download(String urlString, Path target) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlString).openConnection();
        conn.setInstanceFollowRedirects(true);
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setRequestProperty("User-Agent", "devils-game/1.0");

        try (InputStream in = conn.getInputStream();
             OutputStream out = Files.newOutputStream(target)) {
            in.transferTo(out);
        }
    }

    private static void extractExeFromZip(Path zip, Path exe) throws IOException {
        try (ZipInputStream zin = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                if (!entry.isDirectory() && entry.getName().toLowerCase().endsWith(".exe")) {
                    try (OutputStream out = Files.newOutputStream(exe)) {
                        zin.transferTo(out);
                    }
                    return;
                }
            }
        }
    }
}
