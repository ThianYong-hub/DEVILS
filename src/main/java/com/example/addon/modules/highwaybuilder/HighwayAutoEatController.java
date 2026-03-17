package com.example.addon.modules.highwaybuilder;

import meteordevelopment.meteorclient.utils.player.InvUtils;
import net.minecraft.client.MinecraftClient;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.util.Hand;

final class HighwayAutoEatController {
    private static final MinecraftClient mc = MinecraftClient.getInstance();

    private final HighwayBuilder module;
    private int restoreSlot = -1;

    HighwayAutoEatController(HighwayBuilder module) {
        this.module = module;
    }

    boolean handleAutoEat() {
        if (mc.player == null || mc.interactionManager == null) return false;

        if (!module.autoEat.get()) {
            stopAutoEat();
            return false;
        }

        int hunger = mc.player.getHungerManager().getFoodLevel();
        float health = mc.player.getHealth();
        boolean needEat = health <= module.autoEatHealth.get() || hunger <= module.autoEatHunger.get();

        if (!needEat) {
            stopAutoEat();
            return false;
        }

        if (mc.player.currentScreenHandler != null && mc.player.currentScreenHandler.syncId != 0) {
            mc.player.closeHandledScreen();
            return true;
        }

        if (!ensureFoodInMainHand()) {
            if (module.pathfinder != null) module.pathfinder.resetBaritone();
            mc.player.setVelocity(0.0, mc.player.getVelocity().y, 0.0);
            stopAutoEat();
            return true;
        }

        if (module.pathfinder != null) module.pathfinder.resetBaritone();
        mc.player.setVelocity(0.0, mc.player.getVelocity().y, 0.0);

        mc.options.useKey.setPressed(true);

        if (!mc.player.isUsingItem()) {
            var result = mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
            if (result.isAccepted()) mc.player.swingHand(Hand.MAIN_HAND);
        }

        return true;
    }

    void stopAutoEat() {
        if (mc == null || mc.options == null) return;

        mc.options.useKey.setPressed(false);

        if (mc.player != null && restoreSlot >= 0 && restoreSlot < 9) {
            int selected = mc.player.getInventory().getSelectedSlot();
            if (selected != restoreSlot) {
                InvUtils.swap(restoreSlot, false);
            }
        }

        restoreSlot = -1;
    }

    private boolean ensureFoodInMainHand() {
        if (mc.player == null) return false;

        if (isFoodStack(mc.player.getMainHandStack())) return true;

        int selected = mc.player.getInventory().getSelectedSlot();
        int foodSlot = findFoodHotbarSlot();
        if (foodSlot == -1) {
            int inventoryFood = findFoodInventorySlot();
            if (inventoryFood == -1) return false;

            int targetHotbar = findAutoEatHotbarTarget(selected);
            if (targetHotbar == -1) return false;

            if (restoreSlot == -1) restoreSlot = selected;
            InvUtils.move().from(inventoryFood).toHotbar(targetHotbar);
            foodSlot = targetHotbar;
        }

        if (restoreSlot == -1) restoreSlot = selected;
        if (selected != foodSlot) InvUtils.swap(foodSlot, false);

        return isFoodStack(mc.player.getMainHandStack());
    }

    private int findFoodHotbarSlot() {
        if (mc.player == null) return -1;
        for (int i = 0; i < 9; i++) {
            if (isFoodStack(mc.player.getInventory().getStack(i))) return i;
        }
        return -1;
    }

    private int findFoodInventorySlot() {
        if (mc.player == null) return -1;
        for (int i = 9; i < 36; i++) {
            if (isFoodStack(mc.player.getInventory().getStack(i))) return i;
        }
        return -1;
    }

    private int findAutoEatHotbarTarget(int selected) {
        if (mc.player == null) return -1;

        if (selected >= 0 && selected < 9) {
            ItemStack stack = mc.player.getInventory().getStack(selected);
            boolean selectedProtected = module.inventoryHandler != null && module.inventoryHandler.isProtectedHotbarSlot(selected);
            boolean selectedAppleReserved = module.inventoryHandler != null && module.inventoryHandler.isAppleReservedHotbarSlot(selected);
            if ((stack.isEmpty() || !stack.isIn(ItemTags.PICKAXES))
                && (!selectedProtected || selectedAppleReserved)) {
                return selected;
            }
        }

        for (int i = 0; i < 9; i++) {
            boolean protectedSlot = module.inventoryHandler != null && module.inventoryHandler.isProtectedHotbarSlot(i);
            boolean appleReserved = module.inventoryHandler != null && module.inventoryHandler.isAppleReservedHotbarSlot(i);
            if (protectedSlot && !appleReserved) continue;
            if (mc.player.getInventory().getStack(i).isEmpty()) return i;
        }

        for (int i = 0; i < 9; i++) {
            boolean protectedSlot = module.inventoryHandler != null && module.inventoryHandler.isProtectedHotbarSlot(i);
            boolean appleReserved = module.inventoryHandler != null && module.inventoryHandler.isAppleReservedHotbarSlot(i);
            if (protectedSlot && !appleReserved) continue;
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (!stack.isIn(ItemTags.PICKAXES)) return i;
        }

        return selected;
    }

    private boolean isFoodStack(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        if (stack.get(DataComponentTypes.FOOD) == null) return false;

        Item item = stack.getItem();
        return item != Items.ROTTEN_FLESH
            && item != Items.SPIDER_EYE
            && item != Items.POISONOUS_POTATO
            && item != Items.PUFFERFISH;
    }
}


