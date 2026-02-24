/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.gui.screens;

import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.render.BetterTooltips;
import meteordevelopment.meteorclient.utils.Utils;
import net.minecraft.class_10799;
import net.minecraft.class_1661;
import net.minecraft.class_1799;
import net.minecraft.class_2960;
import net.minecraft.class_332;
import net.minecraft.class_3532;
import net.minecraft.class_437;
import net.minecraft.class_5537;
import net.minecraft.class_9276;
import net.minecraft.class_9334;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/*
 * i couldn't figure out how to add proper outer borders for the GUI without adding custom textures. @TODO
 */
public class ContainerInventoryScreen extends class_437 {
    private static final class_2960 SLOT_TEXTURE = class_2960.method_60656("container/slot");
    private static final int SLOT_SIZE = 18;
    private static final int SCREEN_WIDTH = 176;

    private final List<class_1799> containerItems;
    private final class_1661 playerInventory;
    private final int containerRows;
    private int x, y;

    private int baseX, baseY;
    private int playerY;

    public ContainerInventoryScreen(class_1799 containerItem) {
        super(containerItem.method_7964());
        this.playerInventory = mc.field_1724.method_31548();

        this.containerItems = new ArrayList<>();
        if (containerItem.method_7909() instanceof class_5537) {
            class_9276 bundleContents = containerItem.method_58694(class_9334.field_49650);
            if (bundleContents != null) {
                bundleContents.method_57421().forEach(containerItems::add);
            }
        } else {
            class_1799[] tempItems = new class_1799[64];
            Utils.getItemsInContainerItem(containerItem, tempItems);
            Collections.addAll(containerItems, tempItems);
        }

        this.containerRows = Math.max(1, class_3532.method_38788(containerItems.size(), 9));
    }

    @Override
    protected void method_25426() {
        super.method_25426();
        this.x = (this.field_22789 - SCREEN_WIDTH) / 2;
        this.y = (this.field_22790 - (114 + containerRows * SLOT_SIZE + 20)) / 2;
    }

    @Override
    public void method_25394(class_332 context, int mouseX, int mouseY, float delta) {
        super.method_25394(context, mouseX, mouseY, delta);

        baseX = x + 8;
        baseY = y + 18;
        playerY = baseY + containerRows * SLOT_SIZE + 20;

        // drawing the slot textures
        for (int row = 0; row < containerRows + 4; row++) {
            for (int col = 0; col < 9; col++) {
                int slotY = row < containerRows ? baseY + row * SLOT_SIZE : playerY + (row - containerRows) * SLOT_SIZE;
                context.method_52706(class_10799.field_56883, SLOT_TEXTURE, baseX + col * SLOT_SIZE, slotY, SLOT_SIZE, SLOT_SIZE);
            }
        }

        // drawing the container items
        for (int i = 0; i < containerItems.size(); i++) {
            class_1799 item = containerItems.get(i);
            if (!item.method_7960()) {
                int itemX = baseX + (i % 9) * SLOT_SIZE + 1;
                int itemY = baseY + (i / 9) * SLOT_SIZE + 1;
                context.method_51427(item, itemX, itemY);
                context.method_51431(field_22793, item, itemX, itemY);
            }
        }

        // drawing your inventory items
        for (int row = 0; row < 4; row++) {
            for (int col = 0; col < 9; col++) {
                int slotIndex = row < 3 ? 9 + row * 9 + col : col;
                class_1799 item = playerInventory.method_5438(slotIndex);
                if (!item.method_7960()) {
                    int itemX = baseX + col * SLOT_SIZE + 1;
                    int itemY = playerY + row * SLOT_SIZE + 1;
                    context.method_51427(item, itemX, itemY);
                    context.method_51431(field_22793, item, itemX, itemY);
                }
            }
        }

        // drawing title headers
        context.method_51448().pushMatrix();
        context.method_51448().translate((float) x, (float) y);
        if (field_22793 != null) {
            context.method_51439(field_22793, field_22785, 8, 6, -12566464, false);
            context.method_51439(field_22793, playerInventory.method_5476(), 8, 18 + containerRows * SLOT_SIZE + 10, -12566464, false);
        }
        context.method_51448().popMatrix();

        // drawing the tooltip
        class_1799 item = getSelectedItem(mouseX, mouseY);
        if (!item.method_7960()) {
            context.method_64038(field_22793, method_25408(mc, item), item.method_32347(), mouseX, mouseY);
        }
    }

    @Override
    public boolean method_25402(double mouseX, double mouseY, int button) {
        BetterTooltips tooltips = Modules.get().get(BetterTooltips.class);

        class_1799 stack = getSelectedItem((int) mouseX, (int) mouseY);
        if (tooltips.shouldOpenContents(false, button, 0)) {
            return tooltips.openContent(stack);
        }

        return false;
    }

    @Override
    public boolean method_25404(int keyCode, int scanCode, int modifiers) {
        BetterTooltips tooltips = Modules.get().get(BetterTooltips.class);

        class_1799 stack = getSelectedItem((int) mc.field_1729.method_68879(mc.method_22683()), (int) mc.field_1729.method_68883(mc.method_22683()));
        if (tooltips.shouldOpenContents(true, keyCode, modifiers)) {
            return tooltips.openContent(stack);
        }

        if (keyCode == GLFW.GLFW_KEY_ESCAPE || mc.field_1690.field_1822.method_1417(keyCode, scanCode)) {
            method_25419();
            return true;
        }

        return false;
    }

    private class_1799 getSelectedItem(int mouseX, int mouseY) {
        if (mouseX < baseX || mouseX > baseX + 9 * SLOT_SIZE) return class_1799.field_8037;

        int col = (mouseX - baseX) / SLOT_SIZE;
        if (col > 8) return class_1799.field_8037;

        if (mouseY >= baseY && mouseY < baseY + containerRows * SLOT_SIZE) {
            int index = ((mouseY - baseY) / SLOT_SIZE) * 9 + col;
            return (index < containerItems.size() ? containerItems.get(index) : class_1799.field_8037);
        }

        if (mouseY >= playerY && mouseY < playerY + 4 * SLOT_SIZE) {
            int row = (mouseY - playerY) / SLOT_SIZE;
            int slotIndex = row < 3 ? 9 + row * 9 + col : col;
            return playerInventory.method_5438(slotIndex);
        }

        return class_1799.field_8037;
    }
}
