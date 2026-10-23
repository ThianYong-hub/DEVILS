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
import java.security.DigestInputStream;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Downloads the official Stockfish binary from GitHub releases on first launch.
 * <p>
 * The {@code stockfish.exe} includes a built-in NNUE network, so no separate
 * NNUE download is required.
 * <p>
 * The downloaded archive and the extracted executable are both verified against
 * pinned SHA-256 hashes; a tampered or truncated download is never executed.
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

    // SHA-256 of the sf_17.1 release artifacts (https://github.com/official-stockfish/Stockfish/releases/tag/sf_17.1).
    private static final String SF_ZIP_SHA256 = "92a77f8d8116b4331696eeb7b232bd03db30d6641a6ce1be1759478b8931d28b";
    private static final String SF_EXE_SHA256 = "5f95eaea0d4eb697381989187ce6eb4d6ad59283c34421765ecc73cdb09ba766";

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
     * @throws IOException if download, verification or extraction fails
     */
    public static Path ensureEngine(Path minecraftDir) throws IOException {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            throw new IOException("Bundled Stockfish auto-download is only available for Windows.");
        }

        Path engineDir = minecraftDir.resolve("devils-game").resolve("engine");
        Path exe = engineDir.resolve("stockfish.exe");
        Path marker = engineDir.resolve("stockfish.exe.sha256");

        if (Files.exists(exe)) {
            String cached = null;
            try {
                cached = sha256Of(exe);
            } catch (IOException e) {
                LOG.warn("[StockfishDownloader] Could not hash cached engine, re-downloading", e);
            }
            if (cached != null) {
                if (cached.equals(SF_EXE_SHA256)) {
                    writeMarker(marker, cached);
                    LOG.info("[StockfishDownloader] Cached engine verified: {}", exe);
                    return exe;
                }
                String recorded = readMarker(marker);
                if (cached.equals(recorded)) {
                    LOG.info("[StockfishDownloader] Cached engine verified (marker): {}", exe);
                    return exe;
                }
                LOG.warn("[StockfishDownloader] Cached engine failed integrity check, re-downloading");
            }
            Files.deleteIfExists(exe);
            Files.deleteIfExists(marker);
        }

        Files.createDirectories(engineDir);
        Path zip = engineDir.resolve("sf.zip");

        try {
            LOG.info("[StockfishDownloader] Downloading Stockfish (first run, ~40 MB): {}", SF_URL);
            String zipHash = download(SF_URL, zip);
            if (!SF_ZIP_SHA256.equals(zipHash)) {
                throw new IOException("Stockfish archive integrity check failed (got " + zipHash + ")");
            }

            String exeHash = extractExeFromZip(zip, exe);
            if (!SF_EXE_SHA256.equals(exeHash)) {
                Files.deleteIfExists(exe);
                throw new IOException("Extracted stockfish.exe integrity check failed (got " + exeHash + ")");
            }

            if (!Files.exists(exe) || Files.size(exe) < MIN_EXE_SIZE) {
                Files.deleteIfExists(exe);
                throw new IOException("stockfish.exe could not be extracted from archive");
            }

            writeMarker(marker, exeHash);
        } finally {
            Files.deleteIfExists(zip);
        }

        LOG.info("[StockfishDownloader] Ready: {} ({} MB)",
            exe, Files.size(exe) / 1_048_576);
        return exe;
    }

    /** Downloads {@code urlString} to {@code target}, returning the SHA-256 of the written file. */
    private static String download(String urlString, Path target) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) URI.create(urlString).toURL().openConnection();
        Path temp = target.resolveSibling(target.getFileName() + ".tmp");
        MessageDigest digest = newSha256();

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
            try (InputStream in = new DigestInputStream(conn.getInputStream(), digest);
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

        return hex(digest.digest());
    }

    /** Extracts the engine exe, returning the SHA-256 of the written executable. */
    private static String extractExeFromZip(Path zip, Path exe) throws IOException {
        Path parent = exe.getParent();
        Path temp = exe.resolveSibling(exe.getFileName() + ".tmp");
        MessageDigest digest = newSha256();

        try (ZipInputStream zin = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;

                String name = entry.getName().replace('\\', '/');
                Path normalized = parent.resolve(name).normalize();
                if (!normalized.startsWith(parent)) {
                    throw new IOException("Unsafe Stockfish archive entry: " + entry.getName());
                }
                String lower = name.toLowerCase(Locale.ROOT);
                if (!lower.endsWith(".exe") || !lower.contains("stockfish")) {
                    continue;
                }

                try (OutputStream out = new DigestOutputStream(Files.newOutputStream(temp), digest)) {
                    zin.transferTo(out);
                }
                moveReplacing(temp, exe);
                return hex(digest.digest());
            }
        } finally {
            Files.deleteIfExists(temp);
        }

        throw new IOException("Stockfish archive does not contain stockfish.exe");
    }

    private static String sha256Of(Path file) throws IOException {
        MessageDigest digest = newSha256();
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        return hex(digest.digest());
    }

    private static void writeMarker(Path marker, String hash) {
        try {
            Files.writeString(marker, hash);
        } catch (IOException e) {
            LOG.debug("[StockfishDownloader] Could not write hash marker", e);
        }
    }

    private static String readMarker(Path marker) {
        try {
            return Files.readString(marker).trim();
        } catch (IOException e) {
            return null;
        }
    }

    private static MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private static void moveReplacing(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
