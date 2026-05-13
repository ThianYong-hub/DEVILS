package red.jackf.chesttracker.impl.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import net.minecraft.client.network.ClientDynamicRegistryType;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtSizeTracker;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.registry.Registries;
import net.minecraft.registry.ServerDynamicRegistryType;
import net.minecraft.registry.RegistryWrapper.WrapperLookup;
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

public class FileUtil {
    public static final Logger LOGGER = ChestTracker.getLogger("FileUtil");
    private static final Gson GSON_COMPACT = new GsonBuilder().create();
    private static final Gson GSON = GSON_COMPACT.newBuilder().setPrettyPrinting().create();
    @Nullable
    private static volatile WrapperLookup[] builtinRegistryFallbacks;

    public static <T> boolean saveToNbt(T object, Codec<T> codec, Path path, @Nullable WrapperLookup registries) {
        try {
            Files.createDirectories(path.getParent());

            DataResult<NbtElement> tag = encodeWithRegistryFallbacks(codec, object, registries);
            if (tag.isError() && registries != null) {
                LOGGER.warn("Registry NBT encode failed ({}); retrying without registries", tag.error().orElseThrow().message());
                tag = codec.encodeStart(NbtOps.INSTANCE, object);
            }

            if (tag.isError()) {
                throw new IOException("Error encoding to NBT %s".formatted(tag.error().orElseThrow()));
            }

            if (tag.result().orElse(null) instanceof NbtCompound compound) {
                NbtIo.writeCompressed(compound, path);
                return true;
            }

            throw new IOException("Error encoding to NBT: not a compound tag: %s".formatted(tag.result().orElse(null)));
        } catch (IOException e) {
            LOGGER.error("Error saving object", e);
            return false;
        }
    }

    public static <T> Optional<T> loadFromNbt(Codec<T> codec, Path path, @Nullable WrapperLookup registries) {
        if (!Files.isRegularFile(path)) return Optional.empty();

        try {
            NbtCompound tag = NbtIo.readCompressed(path, NbtSizeTracker.ofUnlimitedBytes());

            DataResult<Pair<T, NbtElement>> loaded = decodeWithRegistryFallbacks(codec, tag, registries);
            if (loaded.isError() && registries != null) {
                LOGGER.warn("Registry NBT decode failed ({}); retrying without registries", loaded.error().orElseThrow().message());
                loaded = decodeNbt(codec, NbtOps.INSTANCE, tag);
            }

            if (loaded.isError()) {
                throw new IOException("Invalid NBT: %s".formatted(loaded.error().orElseThrow().message()));
            }

            return loaded.result().map(Pair::getFirst);
        } catch (IOException | RuntimeException e) {
            LOGGER.error("Error loading object at {}", path, e);
            tryMove(path, path.resolveSibling(path.getFileName() + ".corrupt"), StandardCopyOption.REPLACE_EXISTING);
            return Optional.empty();
        }
    }

    private static <T> DataResult<Pair<T, NbtElement>> decodeNbt(Codec<T> codec, DynamicOps<NbtElement> ops, NbtCompound tag) {
        return codec.decode(ops, tag);
    }

    private static <T> DataResult<NbtElement> encodeWithRegistryFallbacks(Codec<T> codec, T object, @Nullable WrapperLookup registries) {
        DataResult<NbtElement> result = codec.encodeStart(getOps(registries), object);
        if (!result.isError()) return result;

        for (WrapperLookup fallback : getBuiltinRegistryFallbacks()) {
            if (fallback == registries) continue;
            result = codec.encodeStart(getOps(fallback), object);
            if (!result.isError()) return result;
        }

        return result;
    }

    private static <T> DataResult<Pair<T, NbtElement>> decodeWithRegistryFallbacks(Codec<T> codec, NbtCompound tag, @Nullable WrapperLookup registries) {
        DataResult<Pair<T, NbtElement>> result = decodeNbt(codec, getOps(registries), tag);
        if (!result.isError()) return result;

        for (WrapperLookup fallback : getBuiltinRegistryFallbacks()) {
            if (fallback == registries) continue;
            result = decodeNbt(codec, getOps(fallback), tag);
            if (!result.isError()) return result;
        }

        return result;
    }

    private static DynamicOps<NbtElement> getOps(@Nullable WrapperLookup registries) {
        return registries == null ? NbtOps.INSTANCE : registries.getOps(NbtOps.INSTANCE);
    }

    private static WrapperLookup[] getBuiltinRegistryFallbacks() {
        WrapperLookup[] cached = builtinRegistryFallbacks;
        if (cached != null) return cached;

        WrapperLookup[] created = createBuiltinRegistryFallbacks();
        if (created.length > 0) builtinRegistryFallbacks = created;
        return created;
    }

    private static WrapperLookup[] createBuiltinRegistryFallbacks() {
        List<WrapperLookup> fallbacks = new ArrayList<>(3);
        addBuiltinRegistryFallback(fallbacks, "client", () -> ClientDynamicRegistryType.createCombinedDynamicRegistries().getCombinedRegistryManager());
        addBuiltinRegistryFallback(fallbacks, "server", () -> ServerDynamicRegistryType.createCombinedDynamicRegistries().getCombinedRegistryManager());
        addBuiltinRegistryFallback(fallbacks, "vanilla", () -> DynamicRegistryManager.of(Registries.REGISTRIES));
        return fallbacks.toArray(WrapperLookup[]::new);
    }

    private static void addBuiltinRegistryFallback(List<WrapperLookup> fallbacks, String label, RegistryFallbackFactory factory) {
        try {
            fallbacks.add(factory.create());
        } catch (IllegalStateException | ExceptionInInitializerError e) {
            LOGGER.debug("Skipping {} registry fallback while registries are still unavailable: {}", label, e.getMessage());
        }
    }

    @FunctionalInterface
    private interface RegistryFallbackFactory {
        WrapperLookup create();
    }

    public static void tryMove(Path from, Path to, CopyOption... options) {
        try {
            Files.move(from, to, options);
        } catch (IOException e) {
            LOGGER.error("Error moving %s to %s".formatted(from, to), e);
        }
    }

    public static Gson gson() {
        return ((ChestTrackerConfig) ChestTrackerConfig.INSTANCE.instance()).storage.readableJsonMemories ? GSON : GSON_COMPACT;
    }
}
