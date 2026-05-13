package red.jackf.chesttracker.impl.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import net.minecraft.client.multiplayer.ClientRegistryLayer;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.*;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;
import red.jackf.chesttracker.impl.ChestTracker;
import red.jackf.chesttracker.impl.config.ChestTrackerConfig;

import java.io.IOException;
import java.nio.file.CopyOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Utilities for working with files.
 */
public class FileUtil {
    public static final Logger LOGGER = ChestTracker.getLogger("FileUtil");
    private static final Gson GSON_COMPACT = new GsonBuilder().create();
    private static final Gson GSON = GSON_COMPACT.newBuilder().setPrettyPrinting().create();
    private static volatile @Nullable HolderLookup.Provider[] builtinRegistryFallbacks;

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
            Files.createDirectories(path.getParent());
            DataResult<Tag> tag = encodeWithRegistryFallbacks(codec, object, registries);

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
        if (!Files.isRegularFile(path)) {
            return Optional.empty();
        }

        try {
            var tag = NbtIo.readCompressed(path, NbtAccounter.unlimitedHeap());
            var loaded = decodeWithRegistryFallbacks(codec, tag, registries);
            if (loaded.isError() && registries != null) {
                LOGGER.warn("Registry NBT decode failed ({}); retrying without registries", loaded.error().get().message());
                loaded = codec.decode(NbtOps.INSTANCE, tag);
            }

            if (loaded.isError()) {
                //noinspection OptionalGetWithoutIsPresent
                throw new IOException("Invalid NBT: %s".formatted(loaded.error().get().message()));
            }

            return loaded.result().map(Pair::getFirst);
        } catch (IOException | RuntimeException ex) {
            LOGGER.error("Error loading object at {}", path, ex);
            FileUtil.tryMove(path, path.resolveSibling(path.getFileName() + ".corrupt"), StandardCopyOption.REPLACE_EXISTING);
            return Optional.empty();
        }
    }

    private static <T> DataResult<Tag> encodeWithRegistryFallbacks(Codec<T> codec, T object, @Nullable HolderLookup.Provider registries) {
        DataResult<Tag> result = codec.encodeStart(getOps(registries), object);
        if (!result.isError()) return result;

        for (HolderLookup.Provider fallback : getBuiltinRegistryFallbacks()) {
            if (fallback == registries) continue;
            result = codec.encodeStart(getOps(fallback), object);
            if (!result.isError()) return result;
        }

        return result;
    }

    private static <T> DataResult<Pair<T, Tag>> decodeWithRegistryFallbacks(Codec<T> codec, Tag tag, @Nullable HolderLookup.Provider registries) {
        DataResult<Pair<T, Tag>> result = codec.decode(getOps(registries), tag);
        if (!result.isError()) return result;

        for (HolderLookup.Provider fallback : getBuiltinRegistryFallbacks()) {
            if (fallback == registries) continue;
            result = codec.decode(getOps(fallback), tag);
            if (!result.isError()) return result;
        }

        return result;
    }

    private static DynamicOps<Tag> getOps(@Nullable HolderLookup.Provider registries) {
        return registries == null ? NbtOps.INSTANCE : registries.createSerializationContext(NbtOps.INSTANCE);
    }

    private static HolderLookup.Provider[] getBuiltinRegistryFallbacks() {
        HolderLookup.Provider[] cached = builtinRegistryFallbacks;
        if (cached != null) return cached;

        HolderLookup.Provider[] created = createBuiltinRegistryFallbacks();
        if (created.length > 0) builtinRegistryFallbacks = created;
        return created;
    }

    private static HolderLookup.Provider[] createBuiltinRegistryFallbacks() {
        List<HolderLookup.Provider> fallbacks = new ArrayList<>(2);
        addBuiltinRegistryFallback(fallbacks, "client", () -> ClientRegistryLayer.createRegistryAccess().compositeAccess());
        addBuiltinRegistryFallback(fallbacks, "vanilla", () -> RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY));
        return fallbacks.toArray(HolderLookup.Provider[]::new);
    }

    private static void addBuiltinRegistryFallback(List<HolderLookup.Provider> fallbacks, String label, RegistryFallbackFactory factory) {
        try {
            fallbacks.add(factory.create());
        } catch (IllegalStateException | ExceptionInInitializerError e) {
            LOGGER.debug("Skipping {} registry fallback while registries are still unavailable: {}", label, e.getMessage());
        }
    }

    @FunctionalInterface
    private interface RegistryFallbackFactory {
        HolderLookup.Provider create();
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
