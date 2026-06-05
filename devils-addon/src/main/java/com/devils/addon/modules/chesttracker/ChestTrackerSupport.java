package com.devils.addon.modules.chesttracker;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

public final class ChestTrackerSupport {
    private ChestTrackerSupport() {
    }

    public record Row(
        boolean enabled,
        String username,
        String server,
        String payload,
        int delay
    ) {
    }

    public record Snapshot(
        String bankId,
        String serverKey,
        long updatedAt,
        String nbt,
        String meta
    ) {
    }

    public record SyncPull(
        boolean ok,
        long revision,
        List<Row> rows,
        String error
    ) {
    }

    public record SyncPush(
        boolean ok,
        boolean applied,
        long revision,
        String error
    ) {
    }

    public record SyncResult(
        Snapshot snapshot,
        String fingerprint,
        boolean remoteApplied,
        String error,
        SyncPush push,
        boolean skipBootstrapEmptyPush
    ) {
    }

    public record SyncRuntimeConfig(
        String baseUrl,
        String token,
        String deviceId,
        boolean useStream,
        int timeoutSec,
        int streamWaitMs,
        String encryptionKey,
        String signingKey
    ) {
    }

    static void writeAtomically(Path path, byte[] data) throws Exception {
        if (path.getParent() != null) Files.createDirectories(path.getParent());
        if (data == null || data.length == 0) {
            Files.deleteIfExists(path);
            return;
        }

        Path tmp = path.resolveSibling(path.getFileName() + ".sync.tmp");
        Files.write(tmp, data);
        try {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    static long lastModified(Path path) {
        try {
            return Files.isRegularFile(path) ? Files.getLastModifiedTime(path).toMillis() : 0;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }
}


