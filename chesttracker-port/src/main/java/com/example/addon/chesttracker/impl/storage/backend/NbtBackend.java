package com.example.addon.chesttracker.impl.storage.backend;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.Util;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;
import com.example.addon.chesttracker.impl.ChestTracker;
import com.example.addon.chesttracker.impl.memory.MemoryBankImpl;
import com.example.addon.chesttracker.impl.memory.MemoryKeyImpl;
import com.example.addon.chesttracker.impl.memory.metadata.Metadata;
import com.example.addon.chesttracker.impl.util.Constants;
import com.example.addon.chesttracker.impl.util.FileUtil;
import com.example.addon.chesttracker.impl.util.Misc;
import com.example.addon.chesttracker.impl.config.ChestTrackerConfig;

import java.io.IOException;
import java.io.DataInputStream;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class NbtBackend extends FileBasedBackend {
    private static final Logger LOGGER = LogManager.getLogger(ChestTracker.class.getCanonicalName() + "/NBT");
    private static final List<String> RECOVERY_SUFFIXES = List.of(
        ".old",
        ".corrupt",
        ".corrupt.corrupt",
        ".old.corrupt"
    );

    // Tracking active saves
    private final Map<String, CompletableFuture<Boolean>> pendingSavesNbt = new ConcurrentHashMap<>();
    private final Map<String, Object> saveLocks = new ConcurrentHashMap<>();

    @Override
    public @Nullable MemoryBankImpl load(String id, @Nullable HolderLookup.Provider registries) {
        var path = Constants.STORAGE_DIR.resolve(id + extension());
        var meta = loadMetadata(id);
        if (meta.isEmpty() && Files.isRegularFile(path)) {
            Metadata recovered = Metadata.blankWithName(defaultNameForId(id));
            if (saveMetadata(id, recovered)) {
                LOGGER.warn("Recovered missing metadata for '{}'.", id);
                meta = Optional.of(recovered);
            }
        }
        if (meta.isEmpty()) return null;

        var result = Misc.time(() -> loadBankDataWithRecovery(path, registries));
        if (result.getFirst().isPresent()) {
            LOGGER.debug("Loaded {} in {}ns", path, result.getSecond());
            return new MemoryBankImpl(meta.get(), result.getFirst().get());
        } else {
            // Never silently replace an existing non-empty bank with an empty one when decode fails.
            // Returning null lets the caller decide and prevents destructive overwrite-on-save.
            if (Files.isRegularFile(path)) {
                try {
                    if (Files.size(path) > 0L) {
                        LOGGER.error("Failed to decode existing memory bank '{}'; refusing to load as empty.", path);
                        return null;
                    }
                } catch (IOException ignored) {
                    LOGGER.error("Failed to stat existing memory bank '{}'; refusing empty fallback.", path);
                    return null;
                }
            }
            return new MemoryBankImpl(meta.get(), new HashMap<>());
        }
    }

    private static String defaultNameForId(String id) {
        if (id == null || id.isBlank()) return "Memory Bank";
        String normalized = id.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        String leaf = slash >= 0 && slash + 1 < normalized.length() ? normalized.substring(slash + 1) : normalized;
        leaf = leaf.replace('_', ' ').trim();
        return leaf.isBlank() ? "Memory Bank" : leaf;
    }

    private Optional<Map<ResourceLocation, MemoryKeyImpl>> loadBankDataWithRecovery(Path path, @Nullable HolderLookup.Provider registries) {
        Optional<Map<ResourceLocation, MemoryKeyImpl>> primary = FileUtil.loadFromNbt(MemoryBankImpl.DATA_CODEC, path, registries);
        if (primary.isPresent()) {
            Map<ResourceLocation, MemoryKeyImpl> primaryData = primary.get();
            if (!primaryData.isEmpty()) return primary;

            Optional<Map<ResourceLocation, MemoryKeyImpl>> convertedPrimaryNonEmpty =
                tryConvertLegacySingleKeyFile(path, path, registries, true, "legacy-single-key root (primary)");
            if (convertedPrimaryNonEmpty.isPresent()) return convertedPrimaryNonEmpty;

            // If current file is unexpectedly empty but backup/corrupt still has data, restore it.
            Optional<Map<ResourceLocation, MemoryKeyImpl>> nonEmptyRecovery = tryRecoverNonEmpty(path, registries);
            if (nonEmptyRecovery.isPresent()) return nonEmptyRecovery;
            return primary;
        }

        Optional<Map<ResourceLocation, MemoryKeyImpl>> convertedPrimary = tryConvertLegacySingleKeyFile(path, path, registries, false, "legacy-single-key root");
        if (convertedPrimary.isPresent()) {
            return convertedPrimary;
        }

        for (Path candidate : recoveryCandidates(path)) {
            String reason = candidate.getFileName().toString();

            Optional<Map<ResourceLocation, MemoryKeyImpl>> recovered = FileUtil.loadFromNbt(MemoryBankImpl.DATA_CODEC, candidate, registries);
            if (recovered.isPresent()) {
                restorePrimaryFile(path, candidate, reason + " recovery");
                return recovered;
            }

            Optional<Map<ResourceLocation, MemoryKeyImpl>> converted = tryConvertLegacySingleKeyFile(candidate, path, registries, false, reason + " converted");
            if (converted.isPresent()) {
                return converted;
            }
        }

        return Optional.empty();
    }

    private Optional<Map<ResourceLocation, MemoryKeyImpl>> tryRecoverNonEmpty(Path path, @Nullable HolderLookup.Provider registries) {
        for (Path candidate : recoveryCandidates(path)) {
            String reason = candidate.getFileName().toString();

            Optional<Map<ResourceLocation, MemoryKeyImpl>> recovered = FileUtil.loadFromNbt(MemoryBankImpl.DATA_CODEC, candidate, registries);
            if (recovered.isPresent() && !recovered.get().isEmpty()) {
                restorePrimaryFile(path, candidate, reason + " (non-empty recovery)");
                return recovered;
            }

            Optional<Map<ResourceLocation, MemoryKeyImpl>> converted = tryConvertLegacySingleKeyFile(candidate, path, registries, true, reason + " converted (non-empty)");
            if (converted.isPresent()) {
                return converted;
            }
        }

        return Optional.empty();
    }

    private List<Path> recoveryCandidates(Path path) {
        LinkedHashSet<Path> candidates = new LinkedHashSet<>();
        String baseName = path.getFileName().toString();
        Path parent = path.getParent() == null ? Constants.STORAGE_DIR : path.getParent();

        for (String suffix : RECOVERY_SUFFIXES) {
            Path candidate = parent.resolve(baseName + suffix);
            if (Files.isRegularFile(candidate)) candidates.add(candidate);
        }

        try (var list = Files.list(parent)) {
            list.filter(Files::isRegularFile)
                .filter(p -> {
                    String name = p.getFileName().toString();
                    if (name.equals(baseName)) return false;
                    return name.startsWith(baseName + ".corrupt")
                        || name.startsWith(baseName + ".old.corrupt")
                        || name.startsWith(baseName + ".old.");
                })
                .sorted((a, b) -> Long.compare(lastModifiedOrZero(b), lastModifiedOrZero(a)))
                .forEach(candidates::add);
        } catch (IOException ignored) {
        }

        return new ArrayList<>(candidates);
    }

    private long lastModifiedOrZero(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException ignored) {
            return 0L;
        }
    }

    private Optional<Map<ResourceLocation, MemoryKeyImpl>> tryConvertLegacySingleKeyFile(
        Path source,
        Path primaryTarget,
        @Nullable HolderLookup.Provider registries,
        boolean requireNonEmpty,
        String reason
    ) {
        Optional<CompoundTag> raw = readCompound(source);
        if (raw.isEmpty()) return Optional.empty();

        CompoundTag root = raw.get();
        if (!looksLikeLegacySingleKeyRoot(root)) return Optional.empty();

        CompoundTag wrapped = new CompoundTag();
        wrapped.put(inferLegacySingleKeyId(root), root.copy());

        Optional<Map<ResourceLocation, MemoryKeyImpl>> decoded = decodeCompound(wrapped, registries);
        if (decoded.isEmpty()) return Optional.empty();
        if (requireNonEmpty && decoded.get().isEmpty()) return Optional.empty();

        restorePrimaryCompound(primaryTarget, wrapped, reason);
        return decoded;
    }

    private Optional<CompoundTag> readCompound(Path source) {
        if (!Files.isRegularFile(source)) return Optional.empty();
        try {
            return Optional.of(NbtIo.readCompressed(source, NbtAccounter.unlimitedHeap()));
        } catch (Throwable compressedReadError) {
            try (InputStream in = Files.newInputStream(source); DataInputStream dataIn = new DataInputStream(in)) {
                var tag = NbtIo.read(dataIn, NbtAccounter.unlimitedHeap());
                return tag instanceof CompoundTag compound ? Optional.of(compound) : Optional.empty();
            } catch (Throwable ignored) {
                return Optional.empty();
            }
        }
    }

    private Optional<Map<ResourceLocation, MemoryKeyImpl>> decodeCompound(CompoundTag tag, @Nullable HolderLookup.Provider registries) {
        Path temp = null;
        try {
            temp = Files.createTempFile(Constants.STORAGE_DIR, "ct-recover-", extension());
            NbtIo.writeCompressed(tag, temp);
            return FileUtil.loadFromNbt(MemoryBankImpl.DATA_CODEC, temp, registries);
        } catch (Throwable ignored) {
            return Optional.empty();
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                }
            }
        }
    }

    private boolean looksLikeLegacySingleKeyRoot(CompoundTag root) {
        if (root.isEmpty()) return false;
        boolean hasMemories = root.contains("memories");
        boolean hasOverrides = root.contains("overrides");
        if (!hasMemories && !hasOverrides) return false;

        for (String key : root.keySet()) {
            if (isResourceLikeKey(key)) return false;
        }
        return true;
    }

    private String inferLegacySingleKeyId(CompoundTag legacyRoot) {
        final String fallback = "minecraft:chest";
        if (!legacyRoot.contains("memories")) return fallback;

        Optional<CompoundTag> memoriesOpt = legacyRoot.getCompound("memories");
        if (memoriesOpt.isEmpty()) return fallback;
        CompoundTag memories = memoriesOpt.get();
        if (memories.isEmpty()) return fallback;

        Map<String, Integer> counts = new HashMap<>();
        for (String pos : memories.keySet()) {
            Optional<CompoundTag> memoryOpt = memories.getCompound(pos);
            if (memoryOpt.isEmpty()) continue;
            CompoundTag memory = memoryOpt.get();
            Optional<String> containerOpt = memory.getString("container");
            if (containerOpt.isEmpty()) continue;
            String container = containerOpt.get();
            if (!isResourceLikeKey(container)) continue;
            counts.put(container, counts.getOrDefault(container, 0) + 1);
        }

        return counts.entrySet().stream()
            .max(Map.Entry.comparingByValue())
            .map(Map.Entry::getKey)
            .orElse(fallback);
    }

    private boolean isResourceLikeKey(String value) {
        if (value == null || value.isBlank()) return false;
        int colon = value.indexOf(':');
        if (colon <= 0 || colon == value.length() - 1) return false;
        return value.chars().noneMatch(Character::isWhitespace);
    }

    private void restorePrimaryFile(Path primary, Path source, String reason) {
        try {
            if (primary.getParent() != null) Files.createDirectories(primary.getParent());
            Files.copy(source, primary, StandardCopyOption.REPLACE_EXISTING);
            LOGGER.warn("Restored '{}' from {}.", primary.getFileName(), reason);
        } catch (IOException e) {
            LOGGER.warn("Failed to restore '{}' from '{}': {}", primary, source, e.getMessage());
        }
    }

    private void restorePrimaryCompound(Path primary, CompoundTag source, String reason) {
        try {
            if (primary.getParent() != null) Files.createDirectories(primary.getParent());
            NbtIo.writeCompressed(source, primary);
            LOGGER.warn("Restored '{}' from {}.", primary.getFileName(), reason);
        } catch (IOException e) {
            LOGGER.warn("Failed to restore '{}' from converted source: {}", primary, e.getMessage());
        }
    }

    @Override
    public boolean save(MemoryBankImpl memoryBank, @Nullable HolderLookup.Provider registries) {
        if (ChestTrackerConfig.INSTANCE.instance().storage.AsyncSaving) {
            String id = memoryBank.getId();
            int entriesCount = memoryBank.getMemories().values().stream()
                    .mapToInt(key -> key.getMemories().size())
                    .sum();

            // Taking snapshots of data before transferring it in an async stream (thread safety)
            var metadataSnapshot = memoryBank.getMetadata().deepCopy();
            var memoriesSnapshot = new HashMap<>(memoryBank.getMemories());
            metadataSnapshot.updateModified();
            LOGGER.debug("Created snapshot for {} ({} entries)", id, entriesCount);

            // Cancel the previous save if it is still in progress.
            CompletableFuture<Boolean> previous = pendingSavesNbt.get(id);
            if (previous != null && !previous.isDone()) {
                LOGGER.debug("Previous save for {} still in progress, cancelling...", id);
                previous.cancel(true);
                try {
                    previous.get(5, TimeUnit.SECONDS);
                } catch (Exception e) {
                    LOGGER.warn("Previous save cancellation timed out or failed", e);
                }
            }

            // Async saving
            CompletableFuture<Boolean> future = CompletableFuture.supplyAsync(() -> {
                // We get a block for this ID
                Object lock = saveLocks.computeIfAbsent(id, k -> new Object());

                synchronized (lock) {
                    if (Thread.currentThread().isInterrupted()) {
                        LOGGER.debug("Save for {} was cancelled before start", id);
                        return false;
                    }

                    LOGGER.debug("Starting async save for {}", id);

                    // Determine file paths with a unique temporary name
                    Path finalFile = Constants.STORAGE_DIR.resolve(id + extension());
                    long timestamp = System.currentTimeMillis();
                    Path tempFile = Constants.STORAGE_DIR.resolve(id + extension() + ".tmp." + timestamp);
                    Path oldFile = Constants.STORAGE_DIR.resolve(id + extension() + ".old");

                    try {
                        // Check for cancellation
                        if (Thread.currentThread().isInterrupted()) {
                            LOGGER.debug("Save for {} was cancelled", id);
                            return false;
                        }

                        // Save metadata
                        if (!saveMetadata(id, metadataSnapshot)) {
                            LOGGER.error("Failed to save metadata for {}", id);
                            return false;
                        }

                        // Check for cancellation
                        if (Thread.currentThread().isInterrupted()) {
                            LOGGER.debug("Save for {} was cancelled during metadata save", id);
                            return false;
                        }

                        // Save NBT data to a temporary file
                        LOGGER.debug("Saving {} to temporary file: {}", id, tempFile.getFileName());
                        boolean saveSuccess = FileUtil.saveToNbt(
                                memoriesSnapshot,
                                MemoryBankImpl.DATA_CODEC,
                                tempFile,
                                registries
                        );

                        if (!saveSuccess) {
                            LOGGER.error("Failed to save NBT data to temporary file for {}", id);
                            Files.deleteIfExists(tempFile);
                            return false;
                        }

                        // Check for cancellation before critical section
                        if (Thread.currentThread().isInterrupted()) {
                            LOGGER.debug("Save for {} was cancelled after NBT save", id);
                            Files.deleteIfExists(tempFile);
                            return false;
                        }

                        // Delete the old .old file if it exists.
                        if (Files.exists(oldFile)) {
                            LOGGER.debug("Removing previous .old file: {}", oldFile.getFileName());
                            try {
                                Files.delete(oldFile);
                            } catch (IOException e) {
                                LOGGER.warn("Failed to delete old backup file: {}", oldFile.getFileName(), e);
                            }
                        }

                        // If the current file exists, rename it to .old
                        if (Files.exists(finalFile)) {
                            LOGGER.debug("Renaming current file to .old");
                            try {
                                Files.move(finalFile, oldFile, StandardCopyOption.REPLACE_EXISTING);
                            } catch (IOException e) {
                                LOGGER.error("Failed to rename current file to .old for {}", id, e);
                                Files.deleteIfExists(tempFile);
                                return false;
                            }
                        }

                        // Rename temp file to final
                        LOGGER.debug("Renaming temporary file to final");
                        try {
                            Files.move(tempFile, finalFile, StandardCopyOption.ATOMIC_MOVE);
                        } catch (AtomicMoveNotSupportedException e) {
                            LOGGER.warn("Atomic move not supported, using regular move");
                            Files.move(tempFile, finalFile, StandardCopyOption.REPLACE_EXISTING);
                        } catch (IOException e) {
                            LOGGER.error("Failed to rename temporary file to final for {}", id, e);
                            // Trying to restore from .old
                            if (Files.exists(oldFile)) {
                                try {
                                    Files.move(oldFile, finalFile, StandardCopyOption.REPLACE_EXISTING);
                                    LOGGER.info("Restored from .old backup");
                                } catch (IOException restoreEx) {
                                    LOGGER.error("Failed to restore from backup!", restoreEx);
                                }
                            }
                            Files.deleteIfExists(tempFile);
                            return false;
                        }

                        LOGGER.debug("Successfully completed atomic save for {}", id);

                        // Delete old temporary files (if any remain from previous unsuccessful saves)
                        try {
                            Files.list(Constants.STORAGE_DIR)
                                    .filter(p -> p.getFileName().toString().startsWith(id + extension() + ".tmp."))
                                    .filter(p -> !p.equals(tempFile))
                                    .forEach(p -> {
                                        try {
                                            Files.deleteIfExists(p);
                                            LOGGER.debug("Cleaned up old temporary file: {}", p.getFileName());
                                        } catch (IOException e) {
                                            LOGGER.debug("Could not clean up old temp file: {}", e.getMessage());
                                        }
                                    });
                        } catch (IOException e) {
                            LOGGER.debug("Could not list directory for cleanup: {}", e.getMessage());
                        }

                        return true;

                    } catch (IOException e) {
                        LOGGER.error("IO Exception during atomic save for {}", id, e);
                        try {
                            Files.deleteIfExists(tempFile);
                        } catch (IOException cleanupEx) {
                            LOGGER.warn("Failed to cleanup temporary file", cleanupEx);
                        }
                        return false;
                    }
                }
            }, Util.backgroundExecutor()).exceptionally(ex -> {
                LOGGER.error("Exception during async save for {}", id, ex);
                return false;
            });

            // Save the future for tracking
            pendingSavesNbt.put(id, future);

            // Remove from the map after completion
            future.thenAccept(success -> {
                pendingSavesNbt.remove(id);
                if (!success) {
                    LOGGER.warn("Save failed for {}, data may be incomplete", id);
                } else {
                    LOGGER.debug("Successfully saved {} ({} entries)", id, entriesCount);
                }
            });
            return true;
        } else {
            LOGGER.debug("Saving {}", memoryBank.getId());
            memoryBank.getMetadata().updateModified();
            if (!saveMetadata(memoryBank.getId(), memoryBank.getMetadata())) return false;
            return FileUtil.saveToNbt(memoryBank.getMemories(), MemoryBankImpl.DATA_CODEC, Constants.STORAGE_DIR.resolve(memoryBank.getId() + extension()), registries);
        }
    }

    // Waits for all active saves to complete if the game closes/the world is exited
    public void waitForPendingSaves() {
        if (pendingSavesNbt.isEmpty()) {
            LOGGER.debug("No pending saves to wait for");
            return;
        }
        LOGGER.debug("Waiting for {} pending save(s) to complete...", pendingSavesNbt.size());

        CompletableFuture<Void> allSaves = CompletableFuture.allOf(
                pendingSavesNbt.values().toArray(new CompletableFuture[0])
        );

        try {
            // Waiting of 30 seconds in case of game freezing.
            allSaves.get(300, TimeUnit.SECONDS);
            LOGGER.debug("All pending saves completed");
        } catch (Exception ex) {
            LOGGER.error("Error or timeout waiting for saves to complete", ex);
        }
    }

    @Override
    public String extension() {
        return ".nbt";
    }
}
