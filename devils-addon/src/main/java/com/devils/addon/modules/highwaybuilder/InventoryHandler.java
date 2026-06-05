package com.devils.addon.modules.highwaybuilder;

import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.util.math.Vec3d;

import java.util.concurrent.ConcurrentLinkedDeque;

public class InventoryHandler {
    private static final MinecraftClient mc = MinecraftClient.getInstance();

    private final HighwayBuilder module;
    public Vec3d lastHitVec = Vec3d.ZERO;
    public int waitTicks = 0;
    public final ConcurrentLinkedDeque<Long> packetLimiter = new ConcurrentLinkedDeque<>();
    public int swapBackSlot = -1;
    private int junkCleanupDelay = 0;
    private final InventoryJunkDropper junkDropper;
    private final InventoryJunkRules junkRules;
    private final InventoryRoleSlotGuard roleSlotGuard = new InventoryRoleSlotGuard();

    public InventoryHandler(HighwayBuilder module) {
        this.module = module;
        this.junkDropper = new InventoryJunkDropper(module);
        this.junkRules = new InventoryJunkRules(this);
    }

    public void cleanupPacketLimiter() {
        long now = System.currentTimeMillis();
        while (!packetLimiter.isEmpty() && now - packetLimiter.peekFirst() > 1000) {
            packetLimiter.pollFirst();
        }
    }

    public void captureInitialPreferredHotbarSlots() {
        roleSlotGuard.captureInitialPreferredHotbarSlots();
    }

    public void refreshProtectedHotbarSlotsDynamically() {
        roleSlotGuard.refreshProtectedHotbarSlotsDynamically(swapBackSlot);
    }

    public boolean canUseHotbarSlot(int slot, Item incomingItem) {
        return roleSlotGuard.canUseHotbarSlot(slot, incomingItem);
    }

    public boolean isProtectedHotbarSlot(int slot) {
        return roleSlotGuard.isProtectedHotbarSlot(slot);
    }

    public boolean isAppleReservedHotbarSlot(int slot) {
        return roleSlotGuard.isAppleReservedHotbarSlot(slot);
    }

    public void cleanupJunkInventory() {
        if (mc.player == null || mc.interactionManager == null) return;
        if (!module.autoJunkCleanup.get()) return;
        if (module.containerHandler != null
            && module.containerHandler.containerTask.taskState != TaskState.DONE) return;
        if (mc.player.currentScreenHandler == null || mc.player.currentScreenHandler.syncId != 0) return;

        if (junkCleanupDelay > 0) {
            junkCleanupDelay--;
            return;
        }

        int netherrackKeep = Math.max(0, module.keepNetherrack.get());
        int netherrackCount = countItem(Items.NETHERRACK);

        // Drop listed junk first.
        for (int slot : junkDropper.getCleanupScanOrder()) {
            ItemStack stack = mc.player.getInventory().getStack(slot);
            if (stack.isEmpty()) continue;
            if (!junkRules.shouldDropJunkStack(stack)) continue;
            if (isFortuneThreePickaxeNoSilk(stack)) continue;

            junkDropper.dropInventorySlot(slot, true);
            junkCleanupDelay = 1;
            return;
        }

        // Keep exactly requested amount of netherrack for lava plugs.
        if (netherrackCount <= netherrackKeep) return;

        int excess = netherrackCount - netherrackKeep;
        int slot = junkDropper.findNetherrackCleanupSlot(excess);
        if (slot == -1) return;

        boolean dropWholeStack = mc.player.getInventory().getStack(slot).getCount() <= excess;
        junkDropper.dropInventorySlot(slot, dropWholeStack);
        junkCleanupDelay = dropWholeStack ? 1 : 0;
    }

    public boolean swapOrMoveBestTool(BlockTask blockTask) {
        if (mc.player == null || mc.world == null) return false;

        // Never mine by hand: Fortune III non-silk pickaxe is required for break logic.
        if (shouldRestockFortunePickaxe()) {
            if (module.storageManagement.get()
                && module.containerHandler.containerTask.taskState == TaskState.DONE
                && module.containerHandler.findShulkerWithFortunePickaxe() != -1) {
                module.containerHandler.handleFortunePickaxeRestock();
                return false;
            }

            module.disableWithError("No Fortune III pickaxe available. Mining by hand is blocked.");
            return false;
        }

        return swapToBestTool(blockTask);
    }

    public boolean swapOrMoveContainerBreakTool() {
        if (mc.player == null) return false;

        // For temporary shulker break use any pickaxe; never break by hand.
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (!stack.isIn(ItemTags.PICKAXES)) continue;
            captureSwapBackSlotIfSilent(module.pickaxeSwapMode.get());
            InvUtils.swap(i, false);
            return true;
        }

        for (int i = 9; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (!stack.isIn(ItemTags.PICKAXES)) continue;
            captureSwapBackSlotIfSilent(module.pickaxeSwapMode.get());
            int hotbar = findPreferredToolHotbarSlot(false);
            if (hotbar == -1) return false;
            InvUtils.move().from(i).toHotbar(hotbar);
            InvUtils.swap(hotbar, false);
            return true;
        }

        return false;
    }

    private boolean swapToBestTool(BlockTask blockTask) {
        if (mc.player == null || mc.world == null) return false;

        int bestSlot = -1;
        double bestScore = Double.NEGATIVE_INFINITY;
        var targetState = mc.world.getBlockState(blockTask.blockPos);

        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.isEmpty()) continue;
            if (!isFortuneThreePickaxeNoSilk(stack)) continue;

            float speed = stack.getMiningSpeedMultiplier(targetState);
            boolean suitable = stack.isSuitableFor(targetState);

            // Strongly prefer tools that are actually suitable for the target block.
            double score = speed + (suitable ? 1000.0 : 0.0);

            boolean isBetter = score > bestScore;

            if (!isBetter) continue;

            bestScore = score;
            bestSlot = i;
        }

        if (bestSlot == -1) return false;

        blockTask.toolToUse = mc.player.getInventory().getStack(bestSlot);
        captureSwapBackSlotIfSilent(module.pickaxeSwapMode.get());

        if (bestSlot < 9) {
            InvUtils.swap(bestSlot, false);
            return true;
        }

        int targetHotbar = findPreferredToolHotbarSlot(module.pickaxeSwapMode.get() == ToolSwapMode.Silent);
        if (targetHotbar == -1) return false;

        InvUtils.move().from(bestSlot).toHotbar(targetHotbar);
        InvUtils.swap(targetHotbar, false);
        return true;
    }

    private int findPreferredToolHotbarSlot(boolean avoidSelectedInSilent) {
        if (mc.player == null) return -1;

        int selected = mc.player.getInventory().getSelectedSlot();

        for (int i = 0; i < 9; i++) {
            if (avoidSelectedInSilent && i == selected) continue;
            if (!canUseHotbarSlot(i, Items.DIAMOND_PICKAXE)) continue;
            if (mc.player.getInventory().getStack(i).isEmpty()) return i;
        }

        for (int i = 0; i < 9; i++) {
            if (avoidSelectedInSilent && i == selected) continue;
            if (!canUseHotbarSlot(i, Items.DIAMOND_PICKAXE)) continue;
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (isDisposableBuildStack(stack)) return i;
        }

        for (int i = 0; i < 9; i++) {
            if (avoidSelectedInSilent && i == selected) continue;
            if (!canUseHotbarSlot(i, Items.DIAMOND_PICKAXE)) continue;
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (!isPickaxeStack(stack)) return i;
        }

        if (!avoidSelectedInSilent && canUseHotbarSlot(selected, Items.DIAMOND_PICKAXE)) return selected;

        // Last resort in silent: only if absolutely no other target slot exists.
        return -1;
    }

    public boolean swapOrMoveBlock(BlockTask blockTask) {
        if (mc.player == null) return false;

        Block useMat = findMaterial(blockTask);
        if (useMat == Blocks.AIR) return false;

        captureSwapBackSlotIfSilent(module.swapMode.get() == EChestSwapMode.Silent);

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
            int targetHotbar = findPreferredBuildHotbarSlot(useMat.asItem());
            if (targetHotbar == -1) return false;
            InvUtils.move().from(invResult.slot()).toHotbar(targetHotbar);
            InvUtils.swap(targetHotbar, false);
            return true;
        }

        return false;
    }

    private void captureSwapBackSlotIfSilent(ToolSwapMode mode) {
        if (mode != ToolSwapMode.Silent) return;
        captureSwapBackSlotIfSilent(true);
    }

    private void captureSwapBackSlotIfSilent(boolean silentMode) {
        if (mc.player == null) return;
        if (!silentMode) return;

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
                // If material is obsidian and we have ender chests вЂ” let EChest Miner handle it
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

    private int findPreferredBuildHotbarSlot(Item incomingItem) {
        if (mc.player == null) return -1;

        int selected = mc.player.getInventory().getSelectedSlot();
        ItemStack selectedStack = mc.player.getInventory().getStack(selected);
        if (!isPickaxeStack(selectedStack) && canUseHotbarSlot(selected, incomingItem)) return selected;

        for (int i = 0; i < 9; i++) {
            if (!canUseHotbarSlot(i, incomingItem)) continue;
            if (mc.player.getInventory().getStack(i).isEmpty()) return i;
        }

        for (int i = 0; i < 9; i++) {
            if (!canUseHotbarSlot(i, incomingItem)) continue;
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (isDisposableBuildStack(stack)) return i;
        }

        for (int i = 0; i < 9; i++) {
            if (!canUseHotbarSlot(i, incomingItem)) continue;
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (!isPickaxeStack(stack)) return i;
        }

        // Last resort: allow replacing a pick slot only if at least one pickaxe
        // remains in hotbar afterwards.
        int pickCount = countHotbarPickaxes();
        if (pickCount > 1) {
            for (int i = 0; i < 9; i++) {
                if (i == selected) continue;
                if (!canUseHotbarSlot(i, incomingItem)) continue;
                if (isPickaxeStack(mc.player.getInventory().getStack(i))) return i;
            }
        }

        return -1;
    }

    private int countHotbarPickaxes() {
        if (mc.player == null) return 0;
        int count = 0;
        for (int i = 0; i < 9; i++) {
            if (isPickaxeStack(mc.player.getInventory().getStack(i))) count++;
        }
        return count;
    }

    private boolean isPickaxeStack(ItemStack stack) {
        return stack != null && !stack.isEmpty() && stack.isIn(ItemTags.PICKAXES);
    }

    private boolean isDisposableBuildStack(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        if (!(stack.getItem() instanceof BlockItem bi)) return false;
        if (bi.getBlock() instanceof net.minecraft.block.ShulkerBoxBlock) return false;
        Block block = bi.getBlock();
        return block == Blocks.OBSIDIAN
            || block == Blocks.NETHERRACK
            || block == module.getMaterial()
            || block == module.getFillerMat();
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

    private boolean shouldRestockFortunePickaxe() {
        if (mc.player == null) return false;

        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (isFortuneThreePickaxeNoSilk(stack)) return false;
        }

        return true;
    }

    boolean isFortuneThreePickaxeNoSilk(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        if (!stack.isIn(ItemTags.PICKAXES)) return false;
        if (Utils.hasEnchantment(stack, Enchantments.SILK_TOUCH)) return false;
        return Utils.getEnchantmentLevel(stack, Enchantments.FORTUNE) == 3;
    }

    private int countItem(Item item) {
        if (mc.player == null) return 0;
        int count = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.getItem() == item) count += stack.getCount();
        }
        return count;
    }

}



