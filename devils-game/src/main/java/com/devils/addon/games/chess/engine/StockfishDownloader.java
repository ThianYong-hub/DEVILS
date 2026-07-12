package com.devils.addon.games.chess.engine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
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
    private static final long MAX_ZIP_SIZE = 128L * 1_024 * 1_024;
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
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            throw new IOException("Bundled Stockfish auto-download is only available for Windows.");
        }

        Path engineDir = minecraftDir.resolve("devils-game").resolve("engine");
        Path exe = engineDir.resolve("stockfish.exe");

        if (Files.exists(exe)) {
            if (Files.size(exe) > MIN_EXE_SIZE) {
                LOG.info("[StockfishDownloader] Cached engine: {}", exe);
                return exe;
            }
            Files.delete(exe);
        }

        Files.createDirectories(engineDir);
        Path zip = engineDir.resolve("sf.zip");

        try {
            LOG.info("[StockfishDownloader] Downloading Stockfish (first run, ~40 MB): {}", SF_URL);
            download(SF_URL, zip);

            extractExeFromZip(zip, exe);

            if (!Files.exists(exe) || Files.size(exe) < MIN_EXE_SIZE) {
                Files.deleteIfExists(exe);
                throw new IOException("stockfish.exe could not be extracted from archive");
            }
        } finally {
            Files.deleteIfExists(zip);
        }

        LOG.info("[StockfishDownloader] Ready: {} ({} MB)",
            exe, Files.size(exe) / 1_048_576);
        return exe;
    }

    private static void download(String urlString, Path target) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) URI.create(urlString).toURL().openConnection();
        Path temp = target.resolveSibling(target.getFileName() + ".tmp");

        try {
            conn.setInstanceFollowRedirects(true);
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestProperty("User-Agent", "devils-game/1.0");

            int status = conn.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new IOException("Stockfish download failed with HTTP " + status);
            }

            long contentLength = conn.getContentLengthLong();
            if (contentLength > MAX_ZIP_SIZE) {
                throw new IOException("Stockfish archive is unexpectedly large: " + contentLength + " bytes");
            }

            long written = 0;
            byte[] buffer = new byte[64 * 1024];
            try (InputStream in = conn.getInputStream();
                 OutputStream out = Files.newOutputStream(temp)) {
                int read;
                while ((read = in.read(buffer)) != -1) {
                    written += read;
                    if (written > MAX_ZIP_SIZE) {
                        throw new IOException("Stockfish archive exceeded maximum size");
                    }
                    out.write(buffer, 0, read);
                }
            }

            moveReplacing(temp, target);
        } finally {
            conn.disconnect();
            Files.deleteIfExists(temp);
        }
    }

    private static void extractExeFromZip(Path zip, Path exe) throws IOException {
        Path parent = exe.getParent();
        Path temp = exe.resolveSibling(exe.getFileName() + ".tmp");

        try (ZipInputStream zin = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;

                String name = entry.getName().replace('\\', '/');
                Path normalized = parent.resolve(name).normalize();
                if (!normalized.startsWith(parent)) {
                    throw new IOException("Unsafe Stockfish archive entry: " + entry.getName());
                }
                if (!name.toLowerCase(Locale.ROOT).endsWith("/stockfish.exe")
                    && !name.equalsIgnoreCase("stockfish.exe")) {
                    continue;
                }

                try (OutputStream out = Files.newOutputStream(temp)) {
                    zin.transferTo(out);
                }
                moveReplacing(temp, exe);
                return;
            }
        } finally {
            Files.deleteIfExists(temp);
        }

        throw new IOException("Stockfish archive does not contain stockfish.exe");
    }

    private static void moveReplacing(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
