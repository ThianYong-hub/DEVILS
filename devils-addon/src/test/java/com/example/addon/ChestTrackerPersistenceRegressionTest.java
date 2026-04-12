package com.example.addon;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ChestTrackerPersistenceRegressionTest {
    @Test
    void embeddedFileUtilSourceKeepsPlainOpsFallback() throws IOException {
        String source = Files.readString(repoPath(
            "Souce 1.21.11",
            "Source Native Build",
            "chesttracker-port-embedded",
            "red",
            "jackf",
            "chesttracker",
            "impl",
            "util",
            "FileUtil.java"
        ));

        assertTrue(source.contains("retrying without registries"));
        assertTrue(source.contains("decodeNbt(codec, NbtOps.INSTANCE, tag)"));
        assertTrue(source.contains("IOException | RuntimeException"));
        assertTrue(source.contains("path.getFileName() + \".corrupt\""));
    }

    @Test
    void embeddedNbtBackendSourceKeepsBackupRecoveryHooks() throws IOException {
        String source = Files.readString(repoPath(
            "Souce 1.21.11",
            "Source Native Build",
            "chesttracker-port-embedded",
            "red",
            "jackf",
            "chesttracker",
            "impl",
            "storage",
            "backend",
            "NbtBackend.java"
        ));

        assertTrue(source.contains("loadWithRecovery"));
        assertTrue(source.contains(".old"));
        assertTrue(source.contains(".corrupt"));
        assertTrue(source.contains("Recovered memory bank"));
    }

    private static Path repoPath(String... parts) {
        Path path = Path.of("..").normalize();
        for (String part : parts) {
            path = path.resolve(part);
        }
        return path;
    }
}
