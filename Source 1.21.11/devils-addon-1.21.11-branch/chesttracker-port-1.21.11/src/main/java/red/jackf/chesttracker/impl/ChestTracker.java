package com.example.addon.chesttracker.impl;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.resource.v1.ResourceLoader;
import net.fabricmc.fabric.api.resource.v1.pack.PackActivationType;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.EntrypointContainer;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;
import com.example.addon.chesttracker.api.ChestTrackerPlugin;
import com.example.addon.chesttracker.api.gui.ScreenBlacklist;
import com.example.addon.chesttracker.impl.config.ChestTrackerConfig;
import com.example.addon.chesttracker.impl.gui.DeveloperOverlay;
import com.example.addon.chesttracker.impl.gui.invbutton.ButtonPositionMap;
import com.example.addon.chesttracker.impl.gui.invbutton.CTButtonScreenDuck;
import com.example.addon.chesttracker.impl.gui.invbutton.InventoryButtonFeature;
import com.example.addon.chesttracker.impl.gui.screen.ChestTrackerScreen;
import com.example.addon.chesttracker.impl.gui.util.ChestTrackerRuntimeState;
import com.example.addon.chesttracker.impl.gui.util.CTTitleOverrideDuck;
import com.example.addon.chesttracker.impl.gui.util.ImagePixelReader;
import com.example.addon.chesttracker.impl.memory.MemoryBankAccessImpl;
import com.example.addon.chesttracker.impl.memory.MemoryIntegrity;
import com.example.addon.chesttracker.impl.memory.MemoryKeyImpl;
import com.example.addon.chesttracker.impl.memory.key.OverrideInfo;
import com.example.addon.chesttracker.impl.providers.InteractionTrackerImpl;
import com.example.addon.chesttracker.impl.providers.ProviderHandler;
import com.example.addon.chesttracker.impl.providers.ScreenCloseContextImpl;
import com.example.addon.chesttracker.impl.providers.ScreenOpenContextImpl;
import com.example.addon.chesttracker.impl.storage.ConnectionSettings;
import com.example.addon.chesttracker.impl.storage.Storage;
import com.example.addon.chesttracker.impl.storage.backend.JsonBackend;
import com.example.addon.chesttracker.impl.storage.backend.NbtBackend;
import com.example.addon.chesttracker.impl.util.Constants;
import red.jackf.whereisit.client.api.events.ShouldIgnoreKey;
import static com.example.addon.chesttracker.impl.storage.Storage.backend;

import java.util.Optional;

public class ChestTracker implements ClientModInitializer {
    public static final String MOD_ID = "devils-addon-chesttracker";
    private static final String PLUGIN_ENTRYPOINT_KEY = "devils_chesttracker";
    public static final String ID = "chesttracker";

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(ID, path);
    }

    public static final Logger LOGGER = LogManager.getLogger();

    private static boolean shouldSkipProviderForNextGuiClose = false;

    public static Logger getLogger(String suffix) {
        return LogManager.getLogger(ChestTracker.class.getCanonicalName() + "/" + suffix);
    }
    public static final KeyMapping.Category CHESTTRACKER_CATEGORY =
            new KeyMapping.Category(Identifier.fromNamespaceAndPath("chesttracker", "title"));
    public static final KeyMapping OPEN_GUI = KeyBindingHelper.registerKeyBinding(
            new KeyMapping("key.chesttracker.open_gui", InputConstants.Type.KEYSYM, InputConstants.KEY_GRAVE, CHESTTRACKER_CATEGORY)
    );

    public static void openInGame(Minecraft client, @Nullable Screen parent) {
        if (!ChestTrackerRuntimeState.isModuleEnabled()) return;
        client.setScreen(new ChestTrackerScreen(parent));
    }

    public static void skipProviderForNextGuiClose() {
        shouldSkipProviderForNextGuiClose = true;
    }

    @Override
    public void onInitializeClient() {
        Constants.migrateLegacyStorageLayout();
        ChestTrackerConfig.init();
        applyRuntimeSettingsFromConfig();
        LOGGER.debug("Loading ChestTracker");
        // Register darkmode resourcepack
        ResourceLoader.registerBuiltinPack(
                Identifier.fromNamespaceAndPath("chesttracker", "darkmode_texture"),
                FabricLoader.getInstance().getModContainer(MOD_ID).orElseThrow(),
                Component.literal("Chest Tracker (Unofficial port) - Dark Mode"),
                PackActivationType.NORMAL
        );

        ClientTickEvents.START_CLIENT_TICK.register(client -> {
            if (!ChestTrackerRuntimeState.isModuleEnabled()) return;
            // opening Chest Tracker GUI with no screen open
            if (client.screen == null && client.getOverlay() == null)
                while (OPEN_GUI.consumeClick())
                    openInGame(client, null);
        });

        ClientTickEvents.START_WORLD_TICK.register(ignored -> MemoryBankAccessImpl.INSTANCE.getLoadedInternal().ifPresent(bank -> {
            if (!ChestTrackerRuntimeState.isModuleEnabled()) return;
            bank.getMetadata().incrementLoadedTime();
        }));

        ScreenEvents.BEFORE_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            if (!ChestTrackerRuntimeState.isModuleEnabled()) return;
            if (screen instanceof AbstractContainerScreen<?> containerScreen) {
                ProviderHandler.INSTANCE.getCurrentProvider().ifPresent(provider -> {
                    ScreenOpenContextImpl openContext = ScreenOpenContextImpl.createFor(containerScreen);

                    provider.onScreenOpen(openContext);

                    ((CTButtonScreenDuck) containerScreen).chesttracker$setContext(openContext);

                    if (ChestTrackerConfig.INSTANCE.instance().gui.useCustomNameInGUIs) {
                        MemoryBankAccessImpl.INSTANCE.getLoadedInternal().ifPresent(bank -> {
                            if (openContext.getTarget() != null) {
                                Optional<MemoryKeyImpl> key = bank.getKeyInternal(openContext.getTarget().memoryKey());
                                if (key.isPresent()) {
                                    OverrideInfo override = key.get().overrides().get(openContext.getTarget().position());
                                    if (override != null) {
                                        if (override.getCustomName() != null) {
                                            ((CTTitleOverrideDuck) containerScreen).chesttracker$setTitleOverride(Component.literal(override.getCustomName()));
                                        } else {
                                            ((CTTitleOverrideDuck) containerScreen).chesttracker$clearTitleOverride();
                                        }
                                    } else {
                                        ((CTTitleOverrideDuck) containerScreen).chesttracker$clearTitleOverride();
                                    }
                                }
                            }
                        });
                    }
                });
            }
        });

        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            if (!ChestTrackerRuntimeState.isModuleEnabled()) return;
            if (Minecraft.getInstance().level == null) return;
            if (screen instanceof AbstractContainerScreen<?>) {
                // opening Chest Tracker GUI with a screen open
                ScreenKeyboardEvents.afterKeyPress(screen).register((parent, key) -> {
                    // don't search in search bars, etc
                    if (ShouldIgnoreKey.EVENT.invoker().shouldIgnoreKey()) {
                        return;
                    }

                    if (OPEN_GUI.matches(key)) {
                        openInGame(client, parent);
                    }
                });

                InventoryButtonFeature.onScreenOpen(client, screen, scaledWidth, scaledHeight);

                // counting items after screen close
                if (!ScreenBlacklist.isBlacklisted(screen.getClass()))
                    ScreenEvents.remove(screen).register(screen1 -> {
                        if (!shouldSkipProviderForNextGuiClose) {
                            ProviderHandler.INSTANCE.getCurrentProvider().ifPresent(provider -> {
                                provider.onScreenClose(ScreenCloseContextImpl.createFor((AbstractContainerScreen<?>) screen1));
                            });
                            InteractionTrackerImpl.INSTANCE.clear();
                        } else {
                            shouldSkipProviderForNextGuiClose = false;
                        }
                    });
                else
                    LOGGER.debug("Blacklisted screen class, ignoring");
            }
        });
        // Saving the data file before closing game
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            LOGGER.info("Server stopping, waiting for ChestTracker saves...");
            if (backend instanceof NbtBackend nbtBackend) {
                nbtBackend.waitForPendingSaves();
            }
            if (backend instanceof JsonBackend jsonBackend) {
                jsonBackend.waitForPendingSaves();
            }
        });

        InventoryButtonFeature.setup();

        // auto add placed blocks with data, such as shulker boxes
        ProviderHandler.INSTANCE.setupEvents();
        InteractionTrackerImpl.setup();
        MemoryIntegrity.setup();
        ImagePixelReader.setup();
        Storage.setup();
        DeveloperOverlay.setup();
        ConnectionSettings.load();
        ButtonPositionMap.loadUserPositions();

        for (EntrypointContainer<ChestTrackerPlugin> container : FabricLoader.getInstance().getEntrypointContainers(PLUGIN_ENTRYPOINT_KEY, ChestTrackerPlugin.class)) {
            LOGGER.debug("Loading entrypoint from mod {}", container.getProvider().getMetadata().getId());
            container.getEntrypoint().load();
        }
    }

    private static void applyRuntimeSettingsFromConfig() {
        var gui = ChestTrackerConfig.INSTANCE.instance().gui;
        ChestTrackerRuntimeState.setDevilsThemeEnabled(gui.devilsTheme);
        ChestTrackerRuntimeState.setDevilsAccentColor(gui.devilsAccentColor);
        ChestTrackerRuntimeState.setDevilsOverlayAlpha(gui.devilsOverlayAlpha);
    }
}
