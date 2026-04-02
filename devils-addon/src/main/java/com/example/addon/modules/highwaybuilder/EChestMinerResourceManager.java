package com.example.addon.modules.highwaybuilder;

import meteordevelopment.meteorclient.utils.player.InvUtils;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.ItemTags;

final class EChestMinerResourceManager {
    private static final MinecraftClient mc = MinecraftClient.getInstance();

    private final EChestMiner miner;
    private final HighwayBuilder module;

    EChestMinerResourceManager(EChestMiner miner) {
        this.miner = miner;
        this.module = miner.module;
    }

    boolean swapToPickSilent() {
        if (mc.player == null) return false;
        if (!ensurePickaxeReadyForMining()) return false;

        int selected = mc.player.getInventory().getSelectedSlot();
        if (miner.pickSlot < 9) {
            InvUtils.swap(miner.pickSlot, false);
        } else {
            InvUtils.move().from(miner.pickSlot).toHotbar(selected);
            miner.pickSlot = selected;
        }
        return mc.player.getMainHandStack().isIn(ItemTags.PICKAXES);
    }

    int findBestPickSlot() {
        if (mc.player == null) return -1;

        int bestSlot = -1;
        float bestSpeed = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (!stack.isIn(ItemTags.PICKAXES)) continue;
            float speed = stack.getMiningSpeedMultiplier(Blocks.ENDER_CHEST.getDefaultState());
            if (speed > bestSpeed) {
                bestSpeed = speed;
                bestSlot = i;
            }
        }
        return bestSlot;
    }

    boolean ensurePickaxeReadyForMining() {
        if (mc.player == null) return false;

        if (miner.pickSlot >= 0 && miner.pickSlot < 36) {
            ItemStack cached = mc.player.getInventory().getStack(miner.pickSlot);
            if (cached.isIn(ItemTags.PICKAXES)) return true;
            miner.pickSlot = -1;
        }

        int bestSlot = findBestPickSlot();
        if (bestSlot != -1) {
            miner.pickSlot = bestSlot;
            return true;
        }

        if (tryStartFortunePickaxeRestock()) return false;

        module.disableWithError("No pickaxe found for mining ender chests.");
        return false;
    }

    boolean ensurePickEquippedForMining() {
        if (mc.player == null) return false;
        if (mc.player.getMainHandStack().isIn(ItemTags.PICKAXES)) return true;
        if (!ensurePickaxeReadyForMining()) return false;

        int selected = mc.player.getInventory().getSelectedSlot();
        if (miner.pickSlot < 9) {
            InvUtils.swap(miner.pickSlot, false);
        } else {
            InvUtils.move().from(miner.pickSlot).toHotbar(selected);
            miner.pickSlot = selected;
        }

        return mc.player.getMainHandStack().isIn(ItemTags.PICKAXES);
    }

    int countItem(Item item) {
        if (mc.player == null) return 0;

        int count = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.getItem() == item) count += stack.getCount();
        }
        return count;
    }

    int countFreeObsidianInventorySpace() {
        if (mc.player == null) return 0;

        int space = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.isEmpty()) {
                space += 64;
            } else if (stack.getItem() == Items.OBSIDIAN) {
                space += stack.getMaxCount() - stack.getCount();
            }
        }
        return space;
    }

    int countMissingObsidianForRefill() {
        int inventorySpace = countFreeObsidianInventorySpace();
        if (inventorySpace <= 0) return 0;
        return Math.max(0, inventorySpace - miner.collection.countGroundObsidian());
    }

    int findSafeHotbarSlot() {
        if (mc.player == null) return -1;

        for (int i = 0; i < 9; i++) {
            if (module.inventoryHandler != null && !module.inventoryHandler.canUseHotbarSlot(i, Items.ENDER_CHEST)) continue;
            if (mc.player.getInventory().getStack(i).isEmpty()) return i;
        }

        for (int i = 0; i < 9; i++) {
            if (module.inventoryHandler != null && !module.inventoryHandler.canUseHotbarSlot(i, Items.ENDER_CHEST)) continue;
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.isEmpty() || stack.getItem() == Items.ENDER_CHEST || stack.getItem() == Items.OBSIDIAN) {
                return i;
            }
        }

        return -1;
    }

    boolean tryRequestEchestRestock() {
        if (!module.storageManagement.get() || module.containerHandler == null) return false;

        if (findBestPickSlot() == -1) {
            if (module.containerHandler.containerTask.taskState != TaskState.DONE) return true;
            if (module.containerHandler.findShulkerWithFortunePickaxe() == -1) return false;
            module.containerHandler.handleFortunePickaxeRestock();
            return true;
        }

        if (module.containerHandler.containerTask.taskState != TaskState.DONE) return true;
        if (module.containerHandler.findShulkerWithItem(Items.ENDER_CHEST) == -1) return false;
        module.containerHandler.handleRestock(Items.ENDER_CHEST);
        return true;
    }

    private boolean tryStartFortunePickaxeRestock() {
        if (!module.storageManagement.get() || module.containerHandler == null) return false;
        if (module.containerHandler.containerTask.taskState != TaskState.DONE) return false;
        if (module.containerHandler.findShulkerWithFortunePickaxe() == -1) return false;

        module.containerHandler.handleFortunePickaxeRestock();
        miner.reset(false);
        return true;
    }
}


