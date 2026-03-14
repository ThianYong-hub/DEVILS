package com.example.addon.chesttracker.impl.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.*;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;
import com.example.addon.chesttracker.impl.ChestTracker;
import com.example.addon.chesttracker.impl.config.ChestTrackerConfig;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.CopyOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

/**
 * Utilities for working with files.
 */
public class FileUtil {
    public static final Logger LOGGER = ChestTracker.getLogger("FileUtil");
    private static final Gson GSON_COMPACT = new GsonBuilder().create();
    private static final Gson GSON = GSON_COMPACT.newBuilder().setPrettyPrinting().create();

    /**
     * Save an object to a path with a given codec as an NBT file
     *
     * @param object Object to serialize
     * @param codec  Codec to serialize said object with
     * @param path   Path to save the object to
     * @param <T>    Type of the serialized object
     * @return Whether the save was successful
     */
    @SuppressWarnings("OptionalGetWithoutIsPresent")
    public static <T> boolean saveToNbt(T object, Codec<T> codec, Path path, @Nullable HolderLookup.Provider registries) {
        try {
            DynamicOps<Tag> ops = registries == null ? NbtOps.INSTANCE : registries.createSerializationContext(NbtOps.INSTANCE);
            Files.createDirectories(path.getParent());
            DataResult<Tag> tag = codec.encodeStart(ops, object);

            // If the registry-aware encode fails, fall back to plain NBT ops to avoid crashing.
            if (tag.isError() && registries != null) {
                LOGGER.warn("Registry NBT encode failed ({}); retrying without registries", tag.error().get().message());
                tag = codec.encodeStart(NbtOps.INSTANCE, object);
            }
            if (tag.isError()) {
                throw new IOException("Error encoding to NBT %s".formatted(tag.error().get()));
            } else if (tag.isSuccess() && tag.result().get() instanceof CompoundTag compound) {
                NbtIo.writeCompressed(compound, path);
                return true;
            } else {
                throw new IOException("Error encoding to NBT: not a compound tag: %s".formatted(tag.result().get()));
            }
        } catch (IOException ex) {
            LOGGER.error("Error saving object", ex);
            return false;
        }
    }

    /**
     * Load an NBT file to an object using a given codec
     *
     * @param codec Codec to deserialize with
     * @param path  Path to read from
     * @param <T>   Type of deserialized object
     * @return An optional containing the deserialized object, or an empty optional if errored
     */
    public static <T> Optional<T> loadFromNbt(Codec<T> codec, Path path, @Nullable HolderLookup.Provider registries) {
        if (Files.isRegularFile(path)) {
            try {
                DynamicOps<Tag> ops = registries == null ? NbtOps.INSTANCE : registries.createSerializationContext(NbtOps.INSTANCE);
                var tag = readNbtTag(path);
                var loaded = codec.decode(ops, tag);
                if (loaded.isError() && registries != null) {
                    // Recover from registry-context mismatches by decoding with plain NBT ops.
                    loaded = codec.decode(NbtOps.INSTANCE, tag);
                }
                if (loaded.isError()) {
                    // Decode failures are not always physical file corruption (e.g. schema/version mismatch).
                    // Keep the original file in place for recovery/migration code paths.
                    //noinspection OptionalGetWithoutIsPresent
                    LOGGER.error("Invalid NBT payload at {}: {}", path, abbreviateCodecMessage(loaded.error().get().message()));
                    return Optional.empty();
                } else {
                    return loaded.result().map(Pair::getFirst);
                }
            } catch (IOException ex) {
                LOGGER.error("Error loading object at {}", path, ex);
                FileUtil.tryMove(path, path.resolveSibling(path.getFileName() + ".corrupt"), StandardCopyOption.REPLACE_EXISTING);
            }
        }
        return Optional.empty();
    }

    private static String abbreviateCodecMessage(String message) {
        if (message == null) return "null";
        final int maxLen = 700;
        if (message.length() <= maxLen) return message;
        return message.substring(0, maxLen) + "... [truncated " + (message.length() - maxLen) + " chars]";
    }

    private static Tag readNbtTag(Path path) throws IOException {
        try {
            return NbtIo.readCompressed(path, NbtAccounter.unlimitedHeap());
        } catch (IOException compressedReadError) {
            // Some external writers may persist plain (non-gzip) NBT. Accept both formats.
            try (InputStream in = Files.newInputStream(path); DataInputStream dataIn = new DataInputStream(in)) {
                return NbtIo.read(dataIn, NbtAccounter.unlimitedHeap());
            } catch (IOException plainReadError) {
                plainReadError.addSuppressed(compressedReadError);
                throw plainReadError;
            }
        }
    }

    public static void tryMove(Path from, Path to, CopyOption... options) {
        try {
            Files.move(from, to, options);
        } catch (IOException e) {
            LOGGER.error("Error moving %s to %s".formatted(from, to), e);
        }
    }

    public static Gson gson() {
        return ChestTrackerConfig.INSTANCE.instance().storage.readableJsonMemories ? GSON : GSON_COMPACT;
    }
}
