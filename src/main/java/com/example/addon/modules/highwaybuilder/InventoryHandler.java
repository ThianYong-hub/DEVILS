package com.example.addon.modules.highwaybuilder;

import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.util.math.Vec3d;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;

public class InventoryHandler {
    private static final MinecraftClient mc = MinecraftClient.getInstance();

    private final HighwayBuilder module;
    public Vec3d lastHitVec = Vec3d.ZERO;
    public int waitTicks = 0;
    public final ConcurrentLinkedDeque<Long> packetLimiter = new ConcurrentLinkedDeque<>();
    public int swapBackSlot = -1;

    public InventoryHandler(HighwayBuilder module) {
        this.module = module;
    }

    public void cleanupPacketLimiter() {
        long now = System.currentTimeMillis();
        while (!packetLimiter.isEmpty() && now - packetLimiter.peekFirst() > 1000) {
            packetLimiter.pollFirst();
        }
    }

    /**
     * Find and swap to the best tool for breaking the given block task.
     * Returns true if a suitable tool was equipped.
     */
    public boolean swapOrMoveBestTool(BlockTask blockTask) {
        if (mc.player == null || mc.world == null) return false;

        // If no pickaxes at all, try restock from shulker
        if (countPickaxes() == 0 && module.storageManagement.get()) {
            if (module.containerHandler.containerTask.taskState == TaskState.DONE) {
                Item pickaxeType = findBestPickaxeType();
                if (pickaxeType != null) {
                    module.containerHandler.handleRestock(pickaxeType);
                }
                return false;
            }
        }

        return swapToBestTool(blockTask);
    }

    private boolean swapToBestTool(BlockTask blockTask) {
        if (mc.player == null || mc.world == null) return false;

        int bestSlot = -1;
        float bestSpeed = 0;

        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.isEmpty()) continue;

            float speed = stack.getMiningSpeedMultiplier(mc.world.getBlockState(blockTask.blockPos));
            if (speed > bestSpeed) {
                bestSpeed = speed;
                bestSlot = i;
            }
        }

        if (bestSlot == -1) return false;

        blockTask.toolToUse = mc.player.getInventory().getStack(bestSlot);

        captureSwapBackSlotIfSilent();

        if (bestSlot < 9) {
            InvUtils.swap(bestSlot, false);
        } else {
            FindItemResult result = InvUtils.find(blockTask.toolToUse.getItem());
            if (result.found()) {
                InvUtils.move().from(result.slot()).toHotbar(0);
                InvUtils.swap(0, false);
            }
        }

        return true;
    }

    /**
     * Find and swap to the building material for the given block task.
     * Returns true if material was equipped.
     */
    public boolean swapOrMoveBlock(BlockTask blockTask) {
        if (mc.player == null) return false;

        Block useMat = findMaterial(blockTask);
        if (useMat == Blocks.AIR) return false;

        captureSwapBackSlotIfSilent();

        FindItemResult result = InvUtils.findInHotbar(itemStack ->
            itemStack.getItem() instanceof BlockItem bi && bi.getBlock() == useMat);

        if (result.found()) {
            InvUtils.swap(result.slot(), false);
            return true;
        }

        // Try to find in main inventory and move to hotbar
        FindItemResult invResult = InvUtils.find(itemStack ->
            itemStack.getItem() instanceof BlockItem bi && bi.getBlock() == useMat);

        if (invResult.found()) {
            InvUtils.move().from(invResult.slot()).toHotbar(0);
            InvUtils.swap(0, false);
            return true;
        }

        return false;
    }

    private void captureSwapBackSlotIfSilent() {
        if (mc.player == null) return;
        if (module.swapMode.get() != EChestSwapMode.Silent) return;

        // Keep the very first source slot until we explicitly restore.
        if (swapBackSlot == -1) {
            swapBackSlot = mc.player.getInventory().getSelectedSlot();
        }
    }

    public void restoreSilentSwap() {
        if (mc.player == null) {
            swapBackSlot = -1;
            return;
        }

        if (swapBackSlot >= 0) {
            InvUtils.swap(swapBackSlot, false);
            swapBackSlot = -1;
        }
    }

    private Block findMaterial(BlockTask blockTask) {
        if (mc.player == null) return Blocks.AIR;

        Block material = module.getMaterial();
        Block target = blockTask.targetBlock;
        Block filler = module.getFillerMat();

        // For liquid in AIR-designated cells, use filler only as a temporary plug.
        if (blockTask.taskState == TaskState.LIQUID && target == Blocks.AIR) {
            if (countBlock(filler) > 0) return filler;
            if (module.storageManagement.get()) module.containerHandler.handleRestock(filler.asItem());
            return Blocks.AIR;
        }

        if (target == material) {
            if (countBlock(material) > module.saveMaterial.get()) {
                return material;
            } else {
                // If material is obsidian and we have ender chests — let EChest Miner handle it
                // Don't trigger shulker restock for obsidian when ECs are available
                if (material == Blocks.OBSIDIAN && hasEnderChests()) {
                    return Blocks.AIR;
                }

                // If obsidian is low and ECs are stored in shulkers, restock ECs first.
                if (material == Blocks.OBSIDIAN
                    && module.storageManagement.get()
                    && module.containerHandler.containerTask.taskState == TaskState.DONE
                    && module.containerHandler.findShulkerWithItem(Items.ENDER_CHEST) != -1) {
                    module.containerHandler.handleRestock(Items.ENDER_CHEST);
                    return Blocks.AIR;
                }

                if (module.storageManagement.get()) {
                    module.containerHandler.handleRestock(material.asItem());
                }
                return Blocks.AIR;
            }
        }

        // Try target block first
        if (countBlock(target) > 0) {
            return target;
        }

        // Fallback to material
        if (countBlock(material) > module.saveMaterial.get()) {
            return material;
        }

        // Same check for fallback path
        if (material == Blocks.OBSIDIAN && hasEnderChests()) {
            return Blocks.AIR;
        }

        if (material == Blocks.OBSIDIAN
            && module.storageManagement.get()
            && module.containerHandler.containerTask.taskState == TaskState.DONE
            && module.containerHandler.findShulkerWithItem(Items.ENDER_CHEST) != -1) {
            module.containerHandler.handleRestock(Items.ENDER_CHEST);
            return Blocks.AIR;
        }

        if (module.storageManagement.get()) {
            module.containerHandler.handleRestock(target.asItem());
        }
        return Blocks.AIR;
    }

    private boolean hasEnderChests() {
        if (mc.player == null) return false;
        for (int i = 0; i < 36; i++) {
            if (mc.player.getInventory().getStack(i).getItem() == net.minecraft.item.Items.ENDER_CHEST) {
                return true;
            }
        }
        return false;
    }

    private int countBlock(Block block) {
        if (mc.player == null) return 0;
        int count = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.getItem() instanceof BlockItem bi && bi.getBlock() == block) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private int countPickaxes() {
        if (mc.player == null) return 0;
        int count = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.isIn(ItemTags.PICKAXES)) {
                count++;
            }
        }
        return count;
    }

    private Item findBestPickaxeType() {
        if (mc.player == null) return Items.DIAMOND_PICKAXE;
        Item best = null;
        float bestSpeed = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (!stack.isIn(ItemTags.PICKAXES)) continue;
            float speed = stack.getMiningSpeedMultiplier(
                net.minecraft.block.Blocks.OBSIDIAN.getDefaultState());
            if (speed > bestSpeed) {
                bestSpeed = speed;
                best = stack.getItem();
            }
        }
        // Fallback: search shulkers for any pickaxe type
        if (best == null) {
            for (Item candidate : List.of(Items.NETHERITE_PICKAXE, Items.DIAMOND_PICKAXE, Items.IRON_PICKAXE)) {
                if (module.containerHandler.findShulkerWithItem(candidate) != -1) return candidate;
            }
        }
        return best;
    }
}
