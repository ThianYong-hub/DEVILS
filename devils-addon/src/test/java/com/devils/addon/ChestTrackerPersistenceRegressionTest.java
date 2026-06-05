package com.devils.addon;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ChestTrackerPersistenceRegressionTest {
    @Test
    void assimilatedStorageSourceMergesCurrentRegistriesWithBuiltinStaticsWithoutCombinedDynamicRegistries() throws IOException {
        String source = readSource(
            "devils-addon",
            "src",
            "main",
            "source-native-patches",
            "java",
            "red",
            "jackf",
            "chesttracker",
            "impl",
            "storage",
            "Storage.java"
        );

        assertTrue(source.contains("private static @Nullable WrapperLookup builtinStaticRegistries;"));
        assertTrue(source.contains("WrapperLookup resolved = withBuiltinRegistries(mc.world.getRegistryManager());"));
        assertTrue(source.contains("WrapperLookup fromConnection = mc.getNetworkHandler().getRegistryManager();"));
        assertTrue(source.contains("WrapperLookup resolved = withBuiltinRegistries(fromConnection);"));
        assertTrue(source.contains("return lastKnownRegistries;"));
        assertTrue(source.contains("private static @Nullable WrapperLookup withBuiltinRegistries(@Nullable WrapperLookup primary)"));
        assertTrue(source.contains("DynamicRegistryManager.of(Registries.REGISTRIES)"));
        assertTrue(source.contains("WrapperLookup.of(mergedWrappers)"));
        assertTrue(source.contains("primary.streamAllRegistryKeys()"));
        assertTrue(source.contains("builtin.stream().filter(wrapper -> registryKeys.add(wrapper.getKey()))"));
        assertTrue(source.contains("Built-in static registries are not ready yet"));
        assertTrue(source.contains("loaded = normalizeBankForRegistries(loaded, registries);"));
        assertTrue(source.contains("MemoryBankImpl normalized = normalizeBankForRegistries(bank, registries);"));
        assertTrue(source.contains("backend.save(normalized, registries);"));
        assertTrue(source.contains("normalizeBankForRegistries"));
        assertTrue(source.contains("registries.getOptional(RegistryKeys.ENCHANTMENT)"));
        assertTrue(source.contains("normalizeVisualIcons"));
        assertTrue(source.contains("normalizeMemoryKey"));
        assertTrue(source.contains("normalizeItemStack"));
        assertTrue(source.contains("rebindEnchantments(normalized, DataComponentTypes.ENCHANTMENTS, enchantmentLookup);"));
        assertTrue(source.contains("rebindEnchantments(normalized, DataComponentTypes.STORED_ENCHANTMENTS, enchantmentLookup);"));
        assertTrue(source.contains("ContainerComponent.fromStacks(container.stream().map(item -> normalizeItemStack(item, enchantmentLookup)).toList())"));
        assertTrue(source.contains("new ItemEnchantmentsComponent.Builder(ItemEnchantmentsComponent.DEFAULT)"));
        assertFalse(source.contains("createCombinedDynamicRegistries"));
        assertFalse(source.contains("withStaticRegistries"));
    }

    @Test
    void assimilatedFileUtilSourceUsesLazySafeRegistryFallbacks() throws IOException {
        String source = readSource(
            "devils-addon",
            "src",
            "main",
            "source-native-patches",
            "java",
            "red",
            "jackf",
            "chesttracker",
            "impl",
            "util",
            "FileUtil.java"
        );

        assertFalse(source.contains("private static final WrapperLookup[] BUILTIN_REGISTRY_FALLBACKS = createBuiltinRegistryFallbacks();"));
        assertTrue(source.contains("private static volatile WrapperLookup[] builtinRegistryFallbacks;"));
        assertTrue(source.contains("for (WrapperLookup fallback : getBuiltinRegistryFallbacks())"));
        assertTrue(source.contains("ClientDynamicRegistryType.createCombinedDynamicRegistries().getCombinedRegistryManager()"));
        assertTrue(source.contains("ServerDynamicRegistryType.createCombinedDynamicRegistries().getCombinedRegistryManager()"));
        assertTrue(source.contains("DynamicRegistryManager.of(Registries.REGISTRIES)"));
        assertTrue(source.contains("IllegalStateException | ExceptionInInitializerError"));
        assertTrue(source.contains("Skipping {} registry fallback while registries are still unavailable"));
        assertTrue(source.contains("encodeWithRegistryFallbacks"));
        assertTrue(source.contains("decodeWithRegistryFallbacks"));
        assertTrue(source.contains("retrying without registries"));
        assertTrue(source.contains("decodeNbt(codec, NbtOps.INSTANCE, tag)"));
        assertTrue(source.contains("IOException | RuntimeException"));
        assertTrue(source.contains("path.getFileName() + \".corrupt\""));
    }

    @Test
    void assimilatedXaeroSourcesGuardAgainstNullWorldDuringHandshake() throws IOException {
        String mixinSource = readSource(
            "devils-addon",
            "src",
            "main",
            "source-native-patches",
            "java",
            "xaero",
            "lib",
            "mixin",
            "MixinClientPacketListener.java"
        );
        String packetSource = readSource(
            "devils-addon",
            "src",
            "main",
            "source-native-patches",
            "java",
            "xaero",
            "lib",
            "common",
            "packet",
            "ClientboundDimensionHandshakePacket.java"
        );

        assertTrue(mixinSource.contains("ClientWorld world = MinecraftClient.getInstance().world;"));
        assertTrue(mixinSource.contains("if (world == null) return;"));
        assertTrue(packetSource.contains("ClientWorld world = MinecraftClient.getInstance().world;"));
        assertTrue(packetSource.contains("if (world == null) return;"));
    }

    @Test
    void assimilatedXaeroMinimapVariantHandlerGuardsBuiltInFallback() throws IOException {
        String source = readSource(
            "devils-addon",
            "src",
            "main",
            "source-native-patches",
            "java",
            "xaero",
            "hud",
            "minimap",
            "radar",
            "icon",
            "cache",
            "id",
            "variant",
            "RadarIconVariantHandler.java"
        );

        assertTrue(source.contains("private final Set<Identifier> brokenBuiltInVariantEntityIds = new HashSet<>();"));
        assertTrue(source.contains("if (this.isBuiltInVariantMethod(variantMethod))"));
        assertTrue(source.contains("variant = this.getBuiltInVariantSafely(entityId, entityTexture, entityRenderer, entity);"));
        assertTrue(source.contains("private boolean isBuiltInVariantMethod(Method variantMethod)"));
        assertTrue(source.contains("variantMethod.getDeclaringClass() == BuiltInRadarIconDefinitions.class"));
        assertTrue(source.contains("variantMethod.getDeclaringClass() == EntityIconDefinitions.class"));
        assertTrue(source.contains("Exception while using the built-in variant ID fallback for "));
        assertTrue(source.contains("Falling back to the base entity texture variant to keep the minimap renderer alive."));
        assertTrue(source.contains("return entityTexture == null ? \"default\" : entityTexture;"));
    }

    @Test
    void assimilatedNbtBackendSourceKeepsBackupRecoveryHooks() throws IOException {
        String source = readSource(
            "devils-addon",
            "src",
            "main",
            "source-native-patches",
            "java",
            "red",
            "jackf",
            "chesttracker",
            "impl",
            "storage",
            "backend",
            "NbtBackend.java"
        );

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

    private static String readSource(String... parts) throws IOException {
        Path path = repoPath(parts);
        assumeTrue(Files.isRegularFile(path), "Optional source-native patch source is absent in this checkout: " + path);
        return Files.readString(path);
    }
}
