package com.example.addon.modules.highwaybuilder;

import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.ItemTags;

final class ContainerInventorySupport {
    private static final MinecraftClient mc = MinecraftClient.getInstance();

    private final ContainerHandler handler;
    private final HighwayBuilder module;

    ContainerInventorySupport(ContainerHandler handler) {
        this.handler = handler;
        this.module = handler.module;
    }

    boolean hasFortunePickaxeInInventory() {
        return findBestFortunePickaxeSlotInInventory() != -1;
    }

    int findBestFortunePickaxeSlotInInventory() {
        if (mc.player == null) return -1;

        int bestSlot = -1;
        int bestRemaining = -1;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (!isFortuneThreePickaxeNoSilk(stack)) continue;

            int remaining = stack.isDamageable()
                ? stack.getMaxDamage() - stack.getDamage()
                : Integer.MAX_VALUE;
            if (remaining > bestRemaining) {
                bestRemaining = remaining;
                bestSlot = i;
            }
        }

        return bestSlot;
    }

    void normalizePostPickaxeRestockHotbar() {
        if (mc.player == null) return;

        int selected = mc.player.getInventory().getSelectedSlot();
        int fortunePickSlot = findBestFortunePickaxeSlotInInventory();
        if (fortunePickSlot == -1) return;

        if (fortunePickSlot < 9) {
            if (fortunePickSlot != selected) InvUtils.swap(fortunePickSlot, false);
            return;
        }

        InvUtils.move().from(fortunePickSlot).toHotbar(selected);
    }

    int countInventoryItem(Item item) {
        if (mc.player == null) return 0;

        int count = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.getItem() == item) count += stack.getCount();
        }
        return count;
    }

    boolean hasSpaceForContainerDrop() {
        if (mc.player == null) return false;

        for (int i = 0; i < 36; i++) {
            if (mc.player.getInventory().getStack(i).isEmpty()) return true;
        }
        return false;
    }

    int findShulkerWithItem(Item item) {
        if (mc.player == null) return -1;

        if (item == Items.ENDER_CHEST) {
            int fallback = -1;
            for (int i = 0; i < 36; i++) {
                ItemStack stack = mc.player.getInventory().getStack(i);
                if (stack.isEmpty()) continue;
                if (!(stack.getItem() instanceof BlockItem bi)) continue;
                if (!(bi.getBlock() instanceof ShulkerBoxBlock)) continue;

                ContainerComponent container = stack.get(DataComponentTypes.CONTAINER);
                if (container == null) continue;

                boolean hasEnderChest = false;
                boolean hasObsidian = false;
                for (ItemStack contained : container.iterateNonEmpty()) {
                    if (contained.getItem() == Items.ENDER_CHEST) hasEnderChest = true;
                    if (contained.getItem() == Items.OBSIDIAN) hasObsidian = true;
                }

                if (!hasEnderChest) continue;
                if (hasObsidian) return i;
                if (fallback == -1) fallback = i;
            }
            return fallback;
        }

        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.isEmpty()) continue;
            if (!(stack.getItem() instanceof BlockItem bi)) continue;
            if (!(bi.getBlock() instanceof ShulkerBoxBlock)) continue;

            ContainerComponent container = stack.get(DataComponentTypes.CONTAINER);
            if (container == null) continue;

            for (ItemStack contained : container.iterateNonEmpty()) {
                if (contained.getItem() == item) return i;
            }
        }

        return -1;
    }

    int findShulkerWithFortunePickaxe() {
        if (mc.player == null) return -1;

        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.isEmpty()) continue;
            if (!(stack.getItem() instanceof BlockItem bi)) continue;
            if (!(bi.getBlock() instanceof ShulkerBoxBlock)) continue;

            ContainerComponent container = stack.get(DataComponentTypes.CONTAINER);
            if (container == null) continue;

            for (ItemStack contained : container.iterateNonEmpty()) {
                if (isFortuneThreePickaxeNoSilk(contained)) return i;
            }
        }

        return -1;
    }

    boolean isFortuneThreePickaxeNoSilk(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        if (!stack.isIn(ItemTags.PICKAXES)) return false;
        if (Utils.hasEnchantment(stack, Enchantments.SILK_TOUCH)) return false;
        return Utils.getEnchantmentLevel(stack, Enchantments.FORTUNE) == 3;
    }

    boolean ensureContainerPlacementItemReady() {
        if (mc.player == null) return false;
        if (handler.activeContainerItem == null) return true;

        if (mc.player.getMainHandStack().getItem() == handler.activeContainerItem) return true;

        if (handler.activeContainerHotbarSlot >= 0 && handler.activeContainerHotbarSlot < 9
            && mc.player.getInventory().getStack(handler.activeContainerHotbarSlot).getItem() == handler.activeContainerItem) {
            InvUtils.swap(handler.activeContainerHotbarSlot, false);
            return mc.player.getMainHandStack().getItem() == handler.activeContainerItem;
        }

        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getStack(i).getItem() == handler.activeContainerItem) {
                handler.activeContainerHotbarSlot = i;
                InvUtils.swap(i, false);
                return mc.player.getMainHandStack().getItem() == handler.activeContainerItem;
            }
        }

        int targetHotbar = handler.activeContainerHotbarSlot >= 0 && handler.activeContainerHotbarSlot < 9
            ? handler.activeContainerHotbarSlot
            : findPreferredContainerHotbarSlot();
        if (targetHotbar == -1) {
            int selected = mc.player.getInventory().getSelectedSlot();
            if (canUseHotbarSlot(selected, handler.activeContainerItem)) targetHotbar = selected;
        }
        if (targetHotbar == -1) return false;

        for (int i = 9; i < 36; i++) {
            if (mc.player.getInventory().getStack(i).getItem() != handler.activeContainerItem) continue;
            InvUtils.move().from(i).toHotbar(targetHotbar);
            handler.activeContainerHotbarSlot = targetHotbar;
            InvUtils.swap(targetHotbar, false);
            return mc.player.getMainHandStack().getItem() == handler.activeContainerItem;
        }

        return false;
    }

    int prepareContainerItemOnHotbar(int sourceSlot) {
        if (mc.player == null) return -1;

        if (sourceSlot < 9) {
            InvUtils.swap(sourceSlot, false);
            return sourceSlot;
        }

        int targetHotbar = findPreferredContainerHotbarSlot();
        if (targetHotbar == -1) return -1;

        InvUtils.move().from(sourceSlot).toHotbar(targetHotbar);
        InvUtils.swap(targetHotbar, false);
        return targetHotbar;
    }

    boolean canUseHotbarSlot(int slot, Item incomingItem) {
        if (slot < 0 || slot >= 9) return false;
        if (module.inventoryHandler == null) return true;
        return module.inventoryHandler.canUseHotbarSlot(slot, incomingItem);
    }

    boolean isDisposableForSlotReserve(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        if (!(stack.getItem() instanceof BlockItem bi)) return false;
        if (bi.getBlock() instanceof ShulkerBoxBlock) return false;

        Block block = bi.getBlock();
        return block == Blocks.OBSIDIAN
            || block == Blocks.NETHERRACK
            || block == module.getMaterial()
            || block == module.getFillerMat();
    }

    private int findPreferredContainerHotbarSlot() {
        if (mc.player == null) return -1;

        for (int i = 0; i < 9; i++) {
            if (!canUseHotbarSlot(i, handler.activeContainerItem)) continue;
            if (mc.player.getInventory().getStack(i).isEmpty()) return i;
        }

        for (int i = 0; i < 9; i++) {
            if (!canUseHotbarSlot(i, handler.activeContainerItem)) continue;
            if (isDisposableForSlotReserve(mc.player.getInventory().getStack(i))) return i;
        }

        for (int i = 0; i < 9; i++) {
            if (!canUseHotbarSlot(i, handler.activeContainerItem)) continue;
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.isEmpty()) return i;
            if (!stack.isIn(ItemTags.PICKAXES)) return i;
        }

        int pickCount = countHotbarPickaxes();
        if (pickCount > 1) {
            int selected = mc.player.getInventory().getSelectedSlot();
            for (int i = 0; i < 9; i++) {
                if (i == selected) continue;
                if (!canUseHotbarSlot(i, handler.activeContainerItem)) continue;
                ItemStack stack = mc.player.getInventory().getStack(i);
                if (stack.isIn(ItemTags.PICKAXES)) return i;
            }
            if (canUseHotbarSlot(selected, handler.activeContainerItem)) return selected;
        }

        return -1;
    }

    private int countHotbarPickaxes() {
        if (mc.player == null) return 0;

        int count = 0;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.isIn(ItemTags.PICKAXES)) count++;
        }
        return count;
    }
}


