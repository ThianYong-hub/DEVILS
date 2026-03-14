package com.example.addon.chesttracker.impl.util;

import net.fabricmc.loader.api.FabricLoader;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

public class Constants {
    private static final Logger LOGGER = LogManager.getLogger("DevilsAddon/ChestTracker/StorageLayout");
    private static final List<String> BANK_SUFFIXES = List.of(
        ".nbt",
        ".nbt.meta",
        ".nbt.old",
        ".nbt.corrupt",
        ".json",
        ".json.meta",
        ".json.old",
        ".json.corrupt"
    );
    private static volatile boolean migrated;

    public static final Path ADDON_DIR = FabricLoader.getInstance().getGameDir().resolve("devils-addon");
    public static final Path STORAGE_DIR = ADDON_DIR.resolve("chesttracker");

    // Kept for one-way migration from older Devils/CT builds.
    public static final Path LEGACY_STORAGE_DIR = ADDON_DIR;

    public static synchronized void migrateLegacyStorageLayout() {
        if (migrated) return;
        migrated = true;

        try {
            Files.createDirectories(STORAGE_DIR);
        } catch (IOException e) {
            LOGGER.warn("Failed to create storage directory '{}': {}", STORAGE_DIR, e.getMessage());
            return;
        }

        // Old folders that used to be directly under devils-addon/
        migrateDirectory("multiplayer");
        migrateDirectory("singleplayer");
        migrateDirectory("export");

        // Single files used by ChestTracker in legacy root.
        migrateFile("chesttracker.json5");
        migrateFile("connection_settings.dat");
        migrateFile("user_button_positions.dat");

        // Legacy flat bank files (e.g. server-*.nbt, old host-based ids).
        migrateLegacyRootBankFiles();
    }

    private static void migrateDirectory(String name) {
        Path source = LEGACY_STORAGE_DIR.resolve(name);
        Path target = STORAGE_DIR.resolve(name);

        if (!Files.isDirectory(source)) return;

        try {
            if (!Files.exists(target)) {
                Files.createDirectories(target.getParent());
                Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
                LOGGER.info("Migrated legacy ChestTracker folder '{}' -> '{}'", source, target);
                return;
            }
        } catch (IOException e) {
            LOGGER.warn("Failed to move legacy folder '{}' -> '{}': {}", source, target, e.getMessage());
        }

        // Merge if target already exists.
        try (Stream<Path> walk = Files.walk(source)) {
            walk.filter(Files::isRegularFile).forEach(file -> {
                Path rel = source.relativize(file);
                Path out = target.resolve(rel);
                try {
                    if (out.getParent() != null) Files.createDirectories(out.getParent());
                    if (!Files.exists(out)) {
                        Files.move(file, out, StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (IOException e) {
                    LOGGER.warn("Failed merging legacy file '{}' -> '{}': {}", file, out, e.getMessage());
                }
            });
        } catch (IOException e) {
            LOGGER.warn("Failed to merge legacy folder '{}': {}", source, e.getMessage());
        }

        deleteTreeIfEmpty(source);
    }

    private static void migrateFile(String name) {
        Path source = LEGACY_STORAGE_DIR.resolve(name);
        Path target = STORAGE_DIR.resolve(name);

        if (!Files.isRegularFile(source)) return;
        try {
            if (target.getParent() != null) Files.createDirectories(target.getParent());
            if (!Files.exists(target)) {
                Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
                LOGGER.info("Migrated legacy ChestTracker file '{}' -> '{}'", source, target);
            }
        } catch (IOException e) {
            LOGGER.warn("Failed to migrate legacy file '{}' -> '{}': {}", source, target, e.getMessage());
        }
    }

    private static void migrateLegacyRootBankFiles() {
        if (!Files.isDirectory(LEGACY_STORAGE_DIR)) return;

        try (Stream<Path> list = Files.list(LEGACY_STORAGE_DIR)) {
            list.filter(Files::isRegularFile).forEach(file -> {
                String name = file.getFileName().toString();
                if (!isLikelyLegacyBankFile(name)) return;

                Path target = STORAGE_DIR.resolve(name);
                try {
                    if (!Files.exists(target)) {
                        Files.move(file, target, StandardCopyOption.REPLACE_EXISTING);
                        LOGGER.info("Migrated legacy bank file '{}' -> '{}'", file, target);
                    }
                } catch (IOException e) {
                    LOGGER.warn("Failed to migrate legacy bank file '{}' -> '{}': {}", file, target, e.getMessage());
                }
            });
        } catch (IOException e) {
            LOGGER.warn("Failed scanning legacy storage root '{}': {}", LEGACY_STORAGE_DIR, e.getMessage());
        }
    }

    private static boolean isLikelyLegacyBankFile(String name) {
        if (name == null || name.isBlank()) return false;
        String lower = name.toLowerCase(Locale.ROOT);

        boolean suffixMatches = BANK_SUFFIXES.stream().anyMatch(lower::endsWith);
        if (!suffixMatches) return false;

        if (lower.startsWith("modules.")) return false;
        if ("chesttracker.json5".equals(lower)) return false;
        if ("connection_settings.dat".equals(lower)) return false;
        if ("user_button_positions.dat".equals(lower)) return false;
        if (lower.endsWith(".sync.tmp")) return false;

        String base = stripBankSuffix(lower);
        if (base.isBlank()) return false;
        if (base.startsWith("server-")) return true;

        // For non-server legacy ids, only migrate when the root contains a proper bank pair.
        boolean hasNbt = Files.isRegularFile(LEGACY_STORAGE_DIR.resolve(base + ".nbt"));
        boolean hasJson = Files.isRegularFile(LEGACY_STORAGE_DIR.resolve(base + ".json"));
        boolean hasNbtMeta = Files.isRegularFile(LEGACY_STORAGE_DIR.resolve(base + ".nbt.meta"));
        boolean hasJsonMeta = Files.isRegularFile(LEGACY_STORAGE_DIR.resolve(base + ".json.meta"));

        return (hasNbt || hasJson) && (hasNbtMeta || hasJsonMeta);
    }

    private static String stripBankSuffix(String value) {
        for (String suffix : BANK_SUFFIXES) {
            if (value.endsWith(suffix)) {
                return value.substring(0, value.length() - suffix.length());
            }
        }
        return value;
    }

    private static void deleteTreeIfEmpty(Path root) {
        if (!Files.exists(root)) return;
        try (Stream<Path> walk = Files.walk(root)) {
            List<Path> all = walk.toList();
            // stop if there are still files
            if (all.stream().anyMatch(Files::isRegularFile)) return;
            all.stream()
                .sorted(Comparator.reverseOrder())
                .forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException ignored) {
                    }
                });
        } catch (IOException ignored) {
        }
    }
}
