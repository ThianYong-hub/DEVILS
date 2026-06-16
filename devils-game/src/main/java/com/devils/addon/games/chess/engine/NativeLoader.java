package com.devils.addon.games.chess.engine;

import com.devils.addon.games.DevilsGameAddon;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

/**
 * Loads the Stockfish JNI native library from JAR resources.
 * <p>
 * Expected resource layout inside the JAR:
 * <pre>
 *   /native/windows-x86_64/stockfish_jni.dll
 *   /native/linux-x86_64/libstockfish_jni.so
 *   /native/linux-aarch64/libstockfish_jni.so
 *   /native/macos-x86_64/libstockfish_jni.dylib
 *   /native/macos-aarch64/libstockfish_jni.dylib
 * </pre>
 * NNUE network files are stored alongside:
 * <pre>
 *   /native/windows-x86_64/nn-*.nnue
 *   ...
 * </pre>
 */
public final class NativeLoader {

    private static volatile boolean loaded = false;
    private static volatile String loadError = null;
    private static volatile Path extractionDir = null;

    private NativeLoader() {}

    /** Returns true if the native library loaded successfully. */
    public static boolean isLoaded() {
        return loaded;
    }

    /** Returns the error message if loading failed, null otherwise. */
    public static String getLoadError() {
        return loadError;
    }

    /** Returns the directory where native files were extracted, or null. */
    public static Path getExtractionDir() {
        return extractionDir;
    }

    /**
     * Loads the native library. Safe to call multiple times; only the first call has effect.
     *
     * @return true if the library is now loaded
     */
    public static synchronized boolean load() {
        if (loaded) return true;
        if (loadError != null) return false;

        try {
            String platformKey = detectPlatform();
            if (platformKey == null) {
                loadError = "Unsupported platform: " + osName() + " / " + archName();
                DevilsGameAddon.LOG.error("[Stockfish] {}", loadError);
                return false;
            }

            String libFileName = libraryFileName(platformKey);
            String resourcePath = "/native/" + platformKey + "/" + libFileName;

            // Extract to temp directory
            extractionDir = Files.createTempDirectory("devils-stockfish-");
            extractionDir.toFile().deleteOnExit();

            // Extract native library
            Path libPath = extractionDir.resolve(libFileName);
            extractResource(resourcePath, libPath);

            // Extract all NNUE files from the same platform directory
            extractNnueFiles(platformKey, extractionDir);

            // Load the library
            System.load(libPath.toAbsolutePath().toString());
            loaded = true;
            DevilsGameAddon.LOG.info("[Stockfish] Native library loaded from {}", platformKey);
            return true;

        } catch (UnsatisfiedLinkError e) {
            loadError = "JNI link error: " + e.getMessage();
            DevilsGameAddon.LOG.error("[Stockfish] {}", loadError, e);
            return false;
        } catch (Exception e) {
            loadError = "Failed to load native library: " + e.getMessage();
            DevilsGameAddon.LOG.error("[Stockfish] {}", loadError, e);
            return false;
        }
    }

    /**
     * Returns a platform key like "windows-x86_64", "linux-x86_64", "macos-aarch64",
     * or null if the platform is unsupported.
     */
    static String detectPlatform() {
        String os = osName();
        String arch = archName();

        String osKey;
        if (os.contains("win")) {
            osKey = "windows";
        } else if (os.contains("linux")) {
            osKey = "linux";
        } else if (os.contains("mac") || os.contains("darwin")) {
            osKey = "macos";
        } else {
            return null;
        }

        String archKey;
        if (arch.equals("amd64") || arch.equals("x86_64")) {
            archKey = "x86_64";
        } else if (arch.equals("aarch64") || arch.equals("arm64")) {
            archKey = "aarch64";
        } else {
            return null;
        }

        return osKey + "-" + archKey;
    }

    private static String libraryFileName(String platformKey) {
        if (platformKey.startsWith("windows")) return "stockfish_jni.dll";
        if (platformKey.startsWith("macos"))    return "libstockfish_jni.dylib";
        return "libstockfish_jni.so";
    }

    private static void extractResource(String resourcePath, Path target) throws IOException {
        try (InputStream is = NativeLoader.class.getResourceAsStream(resourcePath)) {
            if (is == null) {
                throw new IOException("Resource not found in JAR: " + resourcePath);
            }
            Files.copy(is, target, StandardCopyOption.REPLACE_EXISTING);
            target.toFile().setExecutable(true, false);
        }
    }

    private static void extractNnueFiles(String platformKey, Path targetDir) throws IOException {
        // NNUE files are stored at /native/{platformKey}/nn-*.nnue
        // We scan the known default NNUE name from Stockfish
        String[] knownNnue = {
            "nn-1111cefa1111.nnue",    // Default big net
            "nn-37f18f62d772.nnue"     // Default small net
        };

        for (String nnue : knownNnue) {
            String path = "/native/" + platformKey + "/" + nnue;
            try (InputStream is = NativeLoader.class.getResourceAsStream(path)) {
                if (is != null) {
                    Files.copy(is, targetDir.resolve(nnue), StandardCopyOption.REPLACE_EXISTING);
                    DevilsGameAddon.LOG.info("[Stockfish] Extracted NNUE: {}", nnue);
                }
            }
        }
    }

    private static String osName() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
    }

    private static String archName() {
        return System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
    }
}
