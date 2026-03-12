package com.example.addon.chesttracker.impl.gui.invbutton;

import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.server.packs.PackType;
import com.example.addon.chesttracker.impl.config.ChestTrackerConfig;
import com.example.addon.chesttracker.impl.gui.invbutton.data.InventoryButtonPositionLoader;
import com.example.addon.chesttracker.impl.gui.invbutton.ui.InventoryButton;

import java.util.Optional;

/**
 * Handles data loading and screen events for the button.
 */
public class InventoryButtonFeature {
    public static void setup() {
        ResourceManagerHelper.get(PackType.CLIENT_RESOURCES).registerReloadListener(new InventoryButtonPositionLoader());
    }

    public static void onScreenOpen(Minecraft client, Screen screen, int scaledWidth, int scaledHeight) {
        if (!ChestTrackerConfig.INSTANCE.instance().gui.inventoryButton.enabled) return;
        if (screen instanceof AbstractContainerScreen<?> menuScreen) {
            var position = ButtonPositionMap.getPositionFor(menuScreen);

            var context = Optional.ofNullable(((CTButtonScreenDuck) menuScreen).devilsct$getContext());

            var target = context.flatMap(ctx -> Optional.ofNullable(ctx.getTarget()));

            InventoryButton button = new InventoryButton(menuScreen, position, target);

            ((CTButtonScreenDuck) menuScreen).devilsct$setButton(button);

            // add to start so interactions happen first
            Screens.getButtons(menuScreen).add(0, button);
        }
    }
}


