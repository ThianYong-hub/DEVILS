package com.devils.addon.util.runtime;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class StrictRuntimeLogger {
    private static final Logger LOG = LoggerFactory.getLogger("Devils/StrictRuntime");
    private static final String ENABLE_PROPERTY = "devils.strict.runtime.logging";
    private static final String OUTPUT_DIR_PROPERTY = "devils.strict.runtime.dir";
    private static final String ACTOR_PROPERTY = "devils.strict.runtime.actor";
    private static final String MAIN_LOG = "runtime-main.log";
    private static final int MAX_DETAIL_LENGTH = 2_048;
    private static final Map<String, String> CATEGORY_FILES = createCategoryFiles();
    private static final Object LOCK = new Object();

    private StrictRuntimeLogger() {
    }

    public static boolean isEnabled() {
        return Boolean.getBoolean(ENABLE_PROPERTY);
    }

    public static void resetAll() {
        if (!isEnabled()) return;

        synchronized (LOCK) {
            resetPath(resolveLogPath(MAIN_LOG));
            for (String fileName : CATEGORY_FILES.values()) {
                resetPath(resolveLogPath(fileName));
            }
        }
    }

    public static void logHarness(String scope, String line) {
        log(scope, "HARNESS", line);
    }

    public static void logInput(String event, String detail) {
        log("INPUT", event, detail);
    }

    public static void logStashMover(String event, String detail) {
        log("STASHMOVER", event, detail);
    }

    public static void logAutoWasp(String event, String detail) {
        log("AUTOWASP", event, detail);
    }

    private static void log(String scope, String event, String detail) {
        if (!isEnabled()) return;

        String normalizedScope = normalizeScope(scope);
        String normalizedEvent = event == null || event.isBlank() ? "EVENT" : event.trim().toUpperCase(Locale.ROOT);
        String payload = decoratePayload(detail);
        String line = Instant.now() + " [" + normalizedScope + "] " + normalizedEvent + (payload.isEmpty() ? "" : " " + payload);

        synchronized (LOCK) {
            append(resolveLogPath(MAIN_LOG), line);
            String categoryFile = CATEGORY_FILES.get(normalizedScope);
            if (categoryFile != null) append(resolveLogPath(categoryFile), line);
        }
    }

    private static String normalizeScope(String scope) {
        if (scope == null || scope.isBlank()) return "GENERAL";
        return scope.trim().toUpperCase(Locale.ROOT);
    }

    private static String decoratePayload(String detail) {
        String payload = trimDetail(detail);
        String actor = System.getProperty(ACTOR_PROPERTY, "").trim();
        if (actor.isBlank()) return payload;
        if (payload.isEmpty()) return "actor=" + actor;
        return "actor=" + actor + ' ' + payload;
    }

    private static String trimDetail(String detail) {
        String payload = detail == null ? "" : detail.trim();
        if (payload.length() <= MAX_DETAIL_LENGTH) return payload;
        return payload.substring(0, MAX_DETAIL_LENGTH) + "...<truncated>";
    }

    private static Path resolveLogPath(String fileName) {
        String configured = System.getProperty(OUTPUT_DIR_PROPERTY, "").trim();
        Path directory = configured.isBlank()
            ? Path.of("devils debug log").toAbsolutePath().normalize()
            : Path.of(configured).toAbsolutePath().normalize();
        return directory.resolve(fileName);
    }

    private static void resetPath(Path path) {
        try {
            Path parent = path.getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.writeString(path, "", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            LOG.warn("Failed to reset strict runtime log at {}", path, e);
        }
    }

    private static void append(Path path, String line) {
        try {
            Path parent = path.getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.writeString(path, line + System.lineSeparator(), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            LOG.warn("Failed to append strict runtime log line to {}", path, e);
        }
    }

    private static Map<String, String> createCategoryFiles() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("INPUT", "input-runtime.log");
        files.put("STASHMOVER", "stashmover-runtime.log");
        files.put("AUTOWASP", "autowasp-runtime.log");
        return Map.copyOf(files);
    }
}
