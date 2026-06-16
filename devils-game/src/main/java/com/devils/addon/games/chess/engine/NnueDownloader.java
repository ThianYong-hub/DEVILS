package com.devils.addon.games.chess.engine;

import com.devils.addon.games.DevilsGameAddon;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/**
 * Downloads official Stockfish NNUE network files from GitHub releases.
 * <p>
 * NNUE files are required by Stockfish for evaluation. They are normally
 * bundled inside the JAR under {@code /native/<platform>/nn-*.nnue}.
 * This downloader fetches them at runtime when:
 * <ul>
 *   <li>The bundled NNUE is outdated</li>
 *   <li>The user wants to switch to a different NNUE variant</li>
 *   <li>The JAR was built without bundled NNUE files</li>
 * </ul>
 * <p>
 * Source: <a href="https://github.com/official-stockfish/Stockfish">Stockfish GitHub</a>
 */
public final class NnueDownloader {

    private NnueDownloader() {}

    /** Stockfish official GitHub raw base URL for NNUE files. */
    private static final String BASE_URL =
            "https://raw.githubusercontent.com/official-stockfish/Stockfish/refs/heads/master/src/nnue";

    /** Default big (halfkp) net filename used by Stockfish. */
    private static final String DEFAULT_BIG_NET = "nn-1111cefa1111.nnue";

    /** Default small (nnue.md) net filename used by Stockfish. */
    private static final String DEFAULT_SMALL_NET = "nn-37f18f62d772.nnue";

    /** HTTP client, created once. */
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    // ─── Public API ─────────────────────────────────────────────────────────

    /**
     * Ensures the default NNUE files exist in the given directory.
     * <p>
     * If the files already exist and are non-empty, they are left untouched.
     * Otherwise they are downloaded from the official Stockfish repository.
     *
     * @param targetDir directory to place NNUE files into
     * @return {@code true} if both files are present (already existed or successfully downloaded)
     */
    public static boolean ensureNnueFiles(Path targetDir) {
        boolean bigOk  = ensureFile(targetDir, DEFAULT_BIG_NET);
        boolean smallOk = ensureFile(targetDir, DEFAULT_SMALL_NET);
        return bigOk && smallOk;
    }

    /**
     * Downloads a single NNUE file if it is missing or empty.
     *
     * @param targetDir destination directory
     * @param fileName  NNUE filename, e.g. {@code "nn-1111cefa1111.nnue"}
     * @return {@code true} if the file is now present and non-empty
     */
    public static boolean ensureFile(Path targetDir, String fileName) {
        Path target = targetDir.resolve(fileName);

        // Already present and non-empty → skip
        if (Files.exists(target) && fileNonEmpty(target)) {
            DevilsGameAddon.LOG.debug("[NnueDownloader] NNUE already present: {}", fileName);
            return true;
        }

        return doDownload(target, fileName);
    }

    /**
     * Downloads a specific NNUE file regardless of whether it already exists.
     *
     * @param targetDir destination directory
     * @param fileName  NNUE filename
     * @return {@code true} on success
     */
    public static boolean forceDownload(Path targetDir, String fileName) {
        return doDownload(targetDir.resolve(fileName), fileName);
    }

    // ─── Internal ───────────────────────────────────────────────────────────

    private static boolean doDownload(Path target, String fileName) {
        String url = BASE_URL + "/" + fileName;
        try {
            Files.createDirectories(target.getParent());
        } catch (IOException e) {
            DevilsGameAddon.LOG.error("[NnueDownloader] Cannot create directory for {}: {}",
                    fileName, e.getMessage());
            return false;
        }

        DevilsGameAddon.LOG.info("[NnueDownloader] Downloading {} ...", fileName);

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(60))
                    .GET()
                    .build();

            HttpResponse<InputStream> response = HTTP_CLIENT.send(request,
                    HttpResponse.BodyHandlers.ofInputStream());

            int status = response.statusCode();
            if (status != 200) {
                DevilsGameAddon.LOG.error("[NnueDownloader] HTTP {} for {}", status, url);
                return false;
            }

            Files.copy(response.body(), target);
            response.body().close();

            if (fileNonEmpty(target)) {
                long sizeKb = Files.size(target) / 1024;
                DevilsGameAddon.LOG.info("[NnueDownloader] Downloaded {} ({} KB)", fileName, sizeKb);
                return true;
            } else {
                DevilsGameAddon.LOG.error("[NnueDownloader] Downloaded file is empty: {}", fileName);
                Files.deleteIfExists(target);
                return false;
            }

        } catch (IOException | InterruptedException e) {
            DevilsGameAddon.LOG.error("[NnueDownloader] Failed to download {}: {}",
                    fileName, e.getMessage());
            return false;
        }
    }

    private static boolean fileNonEmpty(Path path) {
        try {
            return Files.exists(path) && Files.size(path) > 0;
        } catch (IOException e) {
            return false;
        }
    }
}
