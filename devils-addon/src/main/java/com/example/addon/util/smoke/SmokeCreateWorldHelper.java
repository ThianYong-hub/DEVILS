package com.example.addon.util.smoke;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.Element;
import net.minecraft.client.gui.screen.world.CreateWorldScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.input.MouseInput;
import net.minecraft.text.Text;

final class SmokeCreateWorldHelper {
    private static final String CREATE_WORLD_TRANSLATION_KEY = "selectWorld.create";

    private SmokeCreateWorldHelper() {
    }

    static boolean submitCreateWorldIfPresent(MinecraftClient client) {
        if (client == null || !(client.currentScreen instanceof CreateWorldScreen screen)) return false;

        String createLabel = Text.translatable(CREATE_WORLD_TRANSLATION_KEY).getString();
        for (Element element : screen.children()) {
            if (element instanceof ButtonWidget button && createLabel.equals(button.getMessage().getString())) {
                button.onClick(new Click(button.getX() + 1.0, button.getY() + 1.0, new MouseInput(0, 0)), false);
                return true;
            }
        }

        return false;
    }
}
