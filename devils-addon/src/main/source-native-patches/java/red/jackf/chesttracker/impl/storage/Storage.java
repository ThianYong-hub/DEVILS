package red.jackf.chesttracker.impl.storage;

import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.component.ComponentType;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.component.type.ItemEnchantmentsComponent;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryEntryLookup;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.registry.RegistryWrapper.WrapperLookup;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;
import red.jackf.chesttracker.api.memory.Memory;
import red.jackf.chesttracker.impl.ChestTracker;
import red.jackf.chesttracker.impl.config.ChestTrackerConfig;
import red.jackf.chesttracker.impl.memory.MemoryBankAccessImpl;
import red.jackf.chesttracker.impl.memory.MemoryBankImpl;
import red.jackf.chesttracker.impl.memory.MemoryKeyImpl;
import red.jackf.chesttracker.impl.memory.metadata.Metadata;
import red.jackf.chesttracker.impl.storage.backend.Backend;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Collection;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class Storage {
    private static final Logger LOGGER = ChestTracker.getLogger("Storage");
    public static Backend backend;
    private static @Nullable WrapperLookup lastKnownRegistries;
    private static @Nullable WrapperLookup builtinStaticRegistries;

    public static void setBackend(Backend backend) {
        Storage.backend = backend;
    }

    public static void setup() {
        ((ChestTrackerConfig) ChestTrackerConfig.INSTANCE.instance()).storage.storageBackend.load();
        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            if (screen instanceof GameMenuScreen) {
                MemoryBankAccessImpl.INSTANCE.save();
            }
        });
    }

    public static Optional<Metadata> loadMetadata(String id) {
        Optional<MemoryBankImpl> existing = MemoryBankAccessImpl.INSTANCE.getLoadedInternal();
        if (existing.isPresent() && id.equals(existing.get().getId())) {
            return Optional.of(existing.get().getMetadata().deepCopy());
        }

        LOGGER.debug("Loading {} metadata using {}", id, backend.getClass().getSimpleName());
        return backend.loadMetadata(id);
    }

    public static Collection<String> getAllIds() {
        return backend.getAllIds();
    }

    public static boolean exists(String id) {
        return backend.exists(id);
    }

    public static void delete(String id) {
        backend.delete(id);
    }

    public static Text getBackendLabel(String memoryBankId) {
        return backend.getDescriptionLabel(memoryBankId);
    }

    public static Optional<MemoryBankImpl> load(String id) {
        Optional<MemoryBankImpl> existing = MemoryBankAccessImpl.INSTANCE.getLoadedInternal();
        if (existing.isPresent() && id.equals(existing.get().getId())) {
            return existing;
        }

        WrapperLookup registries = resolveRegistries();
        LOGGER.debug("Loading {} using {}", id, backend.getClass().getSimpleName());
        MemoryBankImpl loaded = backend.load(id, registries);
        if (loaded == null) {
            return Optional.empty();
        }

        loaded = normalizeBankForRegistries(loaded, registries);
        loaded.setId(id);
        return Optional.of(loaded);
    }

    public static void save(MemoryBankImpl bank) {
        if (bank == null) {
            LOGGER.warn("Tried to save null Memory Bank");
            return;
        }

        WrapperLookup registries = resolveRegistries();
        bank.getMetadata().updateModified();
        MemoryBankImpl normalized = normalizeBankForRegistries(bank, registries);
        backend.save(normalized, registries);
    }

    private static @Nullable WrapperLookup resolveRegistries() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world != null) {
            WrapperLookup resolved = withBuiltinRegistries(mc.world.getRegistryManager());
            if (resolved != null) {
                lastKnownRegistries = resolved;
                return resolved;
            }
        }

        try {
            if (mc.getNetworkHandler() != null) {
                WrapperLookup fromConnection = mc.getNetworkHandler().getRegistryManager();
                if (fromConnection != null) {
                    WrapperLookup resolved = withBuiltinRegistries(fromConnection);
                    if (resolved != null) {
                        lastKnownRegistries = resolved;
                        return resolved;
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        return lastKnownRegistries;
    }

    private static @Nullable WrapperLookup withBuiltinRegistries(@Nullable WrapperLookup primary) {
        WrapperLookup builtin = getBuiltinStaticRegistries();
        if (primary == null) return builtin;
        if (builtin == null) return primary;

        HashSet<RegistryKey<? extends net.minecraft.registry.Registry<?>>> registryKeys = primary.streamAllRegistryKeys()
            .collect(Collectors.toCollection(HashSet::new));
        Stream<RegistryWrapper.Impl<?>> mergedWrappers = Stream.concat(
            primary.stream(),
            builtin.stream().filter(wrapper -> registryKeys.add(wrapper.getKey()))
        );
        return WrapperLookup.of(mergedWrappers);
    }

    private static @Nullable WrapperLookup getBuiltinStaticRegistries() {
        WrapperLookup cached = builtinStaticRegistries;
        if (cached != null) return cached;

        try {
            WrapperLookup created = DynamicRegistryManager.of(Registries.REGISTRIES);
            builtinStaticRegistries = created;
            return created;
        } catch (IllegalStateException | ExceptionInInitializerError e) {
            LOGGER.debug("Built-in static registries are not ready yet: {}", e.getMessage());
            return null;
        }
    }

    private static MemoryBankImpl normalizeBankForRegistries(MemoryBankImpl bank, @Nullable WrapperLookup registries) {
        if (registries == null) return bank;

        Optional<? extends RegistryEntryLookup<Enchantment>> enchantmentLookup = registries.getOptional(RegistryKeys.ENCHANTMENT);
        if (enchantmentLookup.isEmpty()) return bank;

        Metadata metadataCopy = bank.getMetadata().deepCopy();
        normalizeVisualIcons(metadataCopy, enchantmentLookup.get());

        HashMap<Identifier, MemoryKeyImpl> normalizedKeys = new HashMap<>();
        for (var entry : bank.getMemories().entrySet()) {
            normalizedKeys.put(entry.getKey(), normalizeMemoryKey(entry.getValue(), enchantmentLookup.get()));
        }

        MemoryBankImpl normalized = new MemoryBankImpl(metadataCopy, normalizedKeys);
        normalized.setId(bank.getId());
        return normalized;
    }

    private static void normalizeVisualIcons(Metadata metadata, RegistryEntryLookup<Enchantment> enchantmentLookup) {
        var visualSettings = metadata.getVisualSettings();
        for (var keyId : List.copyOf(visualSettings.getKeyOrder())) {
            visualSettings.setIcon(keyId, normalizeItemStack(visualSettings.getOrCreateIcon(keyId), enchantmentLookup));
        }
    }

    private static MemoryKeyImpl normalizeMemoryKey(MemoryKeyImpl key, RegistryEntryLookup<Enchantment> enchantmentLookup) {
        HashMap<BlockPos, Memory> normalizedMemories = new HashMap<>();
        for (var entry : key.getMemories().entrySet()) {
            normalizedMemories.put(entry.getKey(), normalizeMemory(entry.getValue(), enchantmentLookup));
        }

        return new MemoryKeyImpl(normalizedMemories, new HashMap<>(key.overrides()));
    }

    private static Memory normalizeMemory(Memory memory, RegistryEntryLookup<Enchantment> enchantmentLookup) {
        List<ItemStack> normalizedItems = memory.fullItems().stream()
            .map(stack -> normalizeItemStack(stack, enchantmentLookup))
            .toList();

        return new Memory(
            normalizedItems,
            memory.savedName(),
            memory.otherPositions(),
            memory.container(),
            memory.loadedTimestamp(),
            memory.inGameTimestamp(),
            memory.realTimestamp(),
            memory.entityId(),
            memory.entityUuid()
        );
    }

    private static ItemStack normalizeItemStack(ItemStack stack, RegistryEntryLookup<Enchantment> enchantmentLookup) {
        if (stack.isEmpty()) return ItemStack.EMPTY;

        ItemStack normalized = new ItemStack(stack.getRegistryEntry(), stack.getCount());
        normalized.applyComponentsFrom(stack.getComponents());
        rebindEnchantments(normalized, DataComponentTypes.ENCHANTMENTS, enchantmentLookup);
        rebindEnchantments(normalized, DataComponentTypes.STORED_ENCHANTMENTS, enchantmentLookup);

        ContainerComponent container = normalized.get(DataComponentTypes.CONTAINER);
        if (container != null) {
            normalized.set(
                DataComponentTypes.CONTAINER,
                ContainerComponent.fromStacks(container.stream().map(item -> normalizeItemStack(item, enchantmentLookup)).toList())
            );
        }

        return normalized;
    }

    private static void rebindEnchantments(
        ItemStack stack,
        ComponentType<ItemEnchantmentsComponent> componentType,
        RegistryEntryLookup<Enchantment> enchantmentLookup
    ) {
        ItemEnchantmentsComponent current = stack.get(componentType);
        if (current == null || current.isEmpty()) return;

        ItemEnchantmentsComponent.Builder builder = new ItemEnchantmentsComponent.Builder(ItemEnchantmentsComponent.DEFAULT);
        for (Object2IntMap.Entry<RegistryEntry<Enchantment>> entry : current.getEnchantmentEntries()) {
            Optional<net.minecraft.registry.RegistryKey<Enchantment>> key = entry.getKey().getKey();
            if (key.isEmpty()) continue;

            RegistryEntry.Reference<Enchantment> rebound = enchantmentLookup.getOptional(key.get()).orElse(null);
            if (rebound != null) {
                builder.set(rebound, entry.getIntValue());
            }
        }

        stack.set(componentType, builder.build());
    }
}
