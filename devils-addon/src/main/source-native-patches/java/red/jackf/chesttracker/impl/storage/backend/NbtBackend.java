package red.jackf.chesttracker.impl.storage.backend;

import com.mojang.datafixers.util.Pair;
import net.minecraft.registry.RegistryWrapper.WrapperLookup;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;
import red.jackf.chesttracker.impl.ChestTracker;
import red.jackf.chesttracker.impl.config.ChestTrackerConfig;
import red.jackf.chesttracker.impl.memory.MemoryBankImpl;
import red.jackf.chesttracker.impl.memory.MemoryKeyImpl;
import red.jackf.chesttracker.impl.memory.metadata.Metadata;
import red.jackf.chesttracker.impl.util.Constants;
import red.jackf.chesttracker.impl.util.FileUtil;
import red.jackf.chesttracker.impl.util.Misc;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class NbtBackend extends FileBasedBackend {
    private static final Logger LOGGER = LogManager.getLogger(ChestTracker.class.getCanonicalName() + "/NBT");
    private final Map<String, CompletableFuture<Boolean>> pendingSavesNbt = new ConcurrentHashMap<>();
    private final Map<String, Object> saveLocks = new ConcurrentHashMap<>();

    @Nullable
    @Override
    public MemoryBankImpl load(String id, @Nullable WrapperLookup registries) {
        Optional<Metadata> meta = this.loadMetadata(id);
        if (meta.isEmpty()) return null;

        Path path = Constants.STORAGE_DIR.resolve(id + this.extension());
        Pair<Optional<Map<Identifier, MemoryKeyImpl>>, Long> result = Misc.time(() -> loadWithRecovery(id, path, registries));
        if (result.getFirst().isPresent()) {
            LOGGER.debug("Loaded {} in {}ns", path, result.getSecond());
            return new MemoryBankImpl(meta.get(), result.getFirst().get());
        }

        return new MemoryBankImpl(meta.get(), new HashMap<>());
    }

    private Optional<Map<Identifier, MemoryKeyImpl>> loadWithRecovery(String id, Path path, @Nullable WrapperLookup registries) {
        Optional<Map<Identifier, MemoryKeyImpl>> loaded = FileUtil.loadFromNbt(MemoryBankImpl.DATA_CODEC, path, registries);
        if (loaded.isPresent()) return loaded;

        for (String suffix : List.of(".old", ".corrupt")) {
            Path backupPath = Constants.STORAGE_DIR.resolve(id + this.extension() + suffix);
            if (!Files.isRegularFile(backupPath)) continue;

            Optional<Map<Identifier, MemoryKeyImpl>> recovered = FileUtil.loadFromNbt(MemoryBankImpl.DATA_CODEC, backupPath, registries);
            if (recovered.isEmpty()) continue;

            restoreRecoveredBank(path, backupPath);
            LOGGER.warn("Recovered memory bank '{}' from {}", id, backupPath.getFileName());
            return recovered;
        }

        return Optional.empty();
    }

    private void restoreRecoveredBank(Path finalPath, Path recoveredFrom) {
        try {
            Files.copy(recoveredFrom, finalPath, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            LOGGER.warn("Recovered memory bank copy-back failed for {} -> {}", recoveredFrom.getFileName(), finalPath.getFileName(), e);
        }
    }

    @Override
    public boolean save(MemoryBankImpl memoryBank, @Nullable WrapperLookup registries) {
        if (((ChestTrackerConfig) ChestTrackerConfig.INSTANCE.instance()).storage.AsyncSaving) {
            String id = memoryBank.getId();
            int entriesCount = memoryBank.getMemories().values().stream().mapToInt(key -> key.getMemories().size()).sum();
            Metadata metadataSnapshot = memoryBank.getMetadata().deepCopy();
            HashMap<Identifier, MemoryKeyImpl> memoriesSnapshot = new HashMap<>(memoryBank.getMemories());
            metadataSnapshot.updateModified();
            LOGGER.debug("Created snapshot for {} ({} entries)", id, entriesCount);
            CompletableFuture<Boolean> previous = this.pendingSavesNbt.get(id);
            if (previous != null && !previous.isDone()) {
                LOGGER.debug("Previous save for {} still in progress, cancelling...", id);
                previous.cancel(true);

                try {
                    previous.get(5L, TimeUnit.SECONDS);
                } catch (Exception e) {
                    LOGGER.warn("Previous save cancellation timed out or failed", e);
                }
            }

            CompletableFuture<Boolean> future = CompletableFuture
                .supplyAsync(() -> {
                    Object lock = this.saveLocks.computeIfAbsent(id, k -> new Object());
                    synchronized (lock) {
                        if (Thread.currentThread().isInterrupted()) {
                            LOGGER.debug("Save for {} was cancelled before start", id);
                            return false;
                        }

                        LOGGER.debug("Starting async save for {}", id);
                        Path finalFile = Constants.STORAGE_DIR.resolve(id + this.extension());
                        long timestamp = System.currentTimeMillis();
                        Path tempFile = Constants.STORAGE_DIR.resolve(id + this.extension() + ".tmp." + timestamp);
                        Path oldFile = Constants.STORAGE_DIR.resolve(id + this.extension() + ".old");

                        try {
                            if (Thread.currentThread().isInterrupted()) {
                                LOGGER.debug("Save for {} was cancelled", id);
                                return false;
                            }

                            if (!this.saveMetadata(id, metadataSnapshot)) {
                                LOGGER.error("Failed to save metadata for {}", id);
                                return false;
                            }

                            if (Thread.currentThread().isInterrupted()) {
                                LOGGER.debug("Save for {} was cancelled during metadata save", id);
                                return false;
                            }

                            LOGGER.debug("Saving {} to temporary file: {}", id, tempFile.getFileName());
                            boolean saveSuccess = FileUtil.saveToNbt(memoriesSnapshot, MemoryBankImpl.DATA_CODEC, tempFile, registries);
                            if (!saveSuccess) {
                                LOGGER.error("Failed to save NBT data to temporary file for {}", id);
                                Files.deleteIfExists(tempFile);
                                return false;
                            }

                            if (Thread.currentThread().isInterrupted()) {
                                LOGGER.debug("Save for {} was cancelled after NBT save", id);
                                Files.deleteIfExists(tempFile);
                                return false;
                            }

                            if (Files.exists(oldFile)) {
                                LOGGER.debug("Removing previous .old file: {}", oldFile.getFileName());

                                try {
                                    Files.delete(oldFile);
                                } catch (IOException e) {
                                    LOGGER.warn("Failed to delete old backup file: {}", oldFile.getFileName(), e);
                                }
                            }

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

                            LOGGER.debug("Renaming temporary file to final");

                            try {
                                Files.move(tempFile, finalFile, StandardCopyOption.ATOMIC_MOVE);
                            } catch (AtomicMoveNotSupportedException e) {
                                LOGGER.warn("Atomic move not supported, using regular move");
                                Files.move(tempFile, finalFile, StandardCopyOption.REPLACE_EXISTING);
                            } catch (IOException e) {
                                LOGGER.error("Failed to rename temporary file to final for {}", id, e);
                                if (Files.exists(oldFile)) {
                                    try {
                                        Files.move(oldFile, finalFile, StandardCopyOption.REPLACE_EXISTING);
                                        LOGGER.info("Restored from .old backup");
                                    } catch (IOException restoreException) {
                                        LOGGER.error("Failed to restore from backup!", restoreException);
                                    }
                                }

                                Files.deleteIfExists(tempFile);
                                return false;
                            }

                            LOGGER.debug("Successfully completed atomic save for {}", id);

                            try {
                                Files.list(Constants.STORAGE_DIR)
                                    .filter(p -> p.getFileName().toString().startsWith(id + this.extension() + ".tmp."))
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
                            } catch (IOException cleanupException) {
                                LOGGER.warn("Failed to cleanup temporary file", cleanupException);
                            }

                            return false;
                        }
                    }
                }, Util.getMainWorkerExecutor())
                .exceptionally(ex -> {
                    LOGGER.error("Exception during async save for {}", id, ex);
                    return false;
                });

            this.pendingSavesNbt.put(id, future);
            future.thenAccept(success -> {
                this.pendingSavesNbt.remove(id);
                if (!success) {
                    LOGGER.warn("Save failed for {}, data may be incomplete", id);
                } else {
                    LOGGER.debug("Successfully saved {} ({} entries)", id, entriesCount);
                }
            });
            return true;
        }

        LOGGER.debug("Saving {}", memoryBank.getId());
        memoryBank.getMetadata().updateModified();
        if (!this.saveMetadata(memoryBank.getId(), memoryBank.getMetadata())) return false;
        return FileUtil.saveToNbt(
            memoryBank.getMemories(),
            MemoryBankImpl.DATA_CODEC,
            Constants.STORAGE_DIR.resolve(memoryBank.getId() + this.extension()),
            registries
        );
    }

    public void waitForPendingSaves() {
        if (this.pendingSavesNbt.isEmpty()) {
            LOGGER.debug("No pending saves to wait for");
            return;
        }

        LOGGER.debug("Waiting for {} pending save(s) to complete...", this.pendingSavesNbt.size());
        CompletableFuture<Void> allSaves = CompletableFuture.allOf(this.pendingSavesNbt.values().toArray(new CompletableFuture[0]));

        try {
            allSaves.get(300L, TimeUnit.SECONDS);
            LOGGER.debug("All pending saves completed");
        } catch (Exception e) {
            LOGGER.error("Error or timeout waiting for saves to complete", e);
        }
    }

    @Override
    public String extension() {
        return ".nbt";
    }
}
