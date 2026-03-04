package com.example.addon.modules.highwaybuilder;

import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.Rotations;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.math.Vec3d;

import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedDeque;

public class InventoryHandler {
    private static final MinecraftClient mc = MinecraftClient.getInstance();
    private static final int ROLE_SWORD = 0;
    private static final int ROLE_PICKAXE = 1;
    private static final int ROLE_APPLE = 2;
    private static final int ROLE_OBSIDIAN = 3;
    private static final int ROLE_ENDER_CHEST = 4;
    private static final int ROLE_COUNT = 5;
    private static final Set<Item> JUNK_ITEMS = Set.of(
        Items.ANCIENT_DEBRIS,
        Items.NETHER_GOLD_ORE,
        Items.NETHER_QUARTZ_ORE,
        Items.QUARTZ,
        Items.GLOWSTONE,
        Items.BLACKSTONE,
        Items.BASALT,
        Items.SOUL_SAND,
        Items.SOUL_SOIL,
        Items.NETHER_WART,
        Items.SHROOMLIGHT,
        Items.BLAZE_ROD,
        Items.BLAZE_POWDER,
        Items.WITHER_SKELETON_SKULL,
        Items.BONE,
        Items.COAL,
        Items.GHAST_TEAR,
        Items.MAGMA_CREAM,
        Items.GOLD_NUGGET,
        Items.GOLDEN_SWORD,
        Items.ROTTEN_FLESH,
        Items.PORKCHOP,
        Items.LEATHER,
        Items.CRYING_OBSIDIAN,
        Items.SPECTRAL_ARROW,
        Items.STRING,
        Items.GRAVEL,
        Items.DIAMOND,
        Items.GOLD_INGOT,
        Items.GOLD_BLOCK,
        Items.SADDLE,
        Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE
    );

    private final HighwayBuilder module;
    public Vec3d lastHitVec = Vec3d.ZERO;
    public int waitTicks = 0;
    public final ConcurrentLinkedDeque<Long> packetLimiter = new ConcurrentLinkedDeque<>();
    public int swapBackSlot = -1;
    private int junkCleanupDelay = 0;
    private int junkDropDirectionIndex = 0;
    private final int[] protectedRoleSlots = new int[ROLE_COUNT];
    private boolean protectedSlotsCaptured = false;

    public InventoryHandler(HighwayBuilder module) {
        this.module = module;
        Arrays.fill(protectedRoleSlots, -1);
    }

    public void cleanupPacketLimiter() {
        long now = System.currentTimeMillis();
        while (!packetLimiter.isEmpty() && now - packetLimiter.peekFirst() > 1000) {
            packetLimiter.pollFirst();
        }
    }

    public void captureInitialPreferredHotbarSlots() {
        if (mc.player == null) return;
        recaptureProtectedRoleSlots();
        protectedSlotsCaptured = true;
    }

    public void refreshProtectedHotbarSlotsDynamically() {
        if (mc.player == null) return;
        if (swapBackSlot >= 0) return;
        if (mc.player.currentScreenHandler == null || mc.player.currentScreenHandler.syncId != 0) return;
        ensureProtectedSlotsCaptured();
        recaptureProtectedRoleSlots();
    }

    public boolean canUseHotbarSlot(int slot, Item incomingItem) {
        if (slot < 0 || slot >= 9) return false;
        ensureProtectedSlotsCaptured();

        int lockedRole = getLockedRoleForSlot(slot);
        if (lockedRole == -1) return true;

        int incomingRole = roleOfItem(incomingItem);
        return incomingRole != -1 && incomingRole == lockedRole;
    }

    public boolean isProtectedHotbarSlot(int slot) {
        if (slot < 0 || slot >= 9) return false;
        ensureProtectedSlotsCaptured();
        return getLockedRoleForSlot(slot) != -1;
    }

    public boolean isAppleReservedHotbarSlot(int slot) {
        if (slot < 0 || slot >= 9) return false;
        ensureProtectedSlotsCaptured();
        return protectedRoleSlots[ROLE_APPLE] == slot;
    }

    private void ensureProtectedSlotsCaptured() {
        if (!protectedSlotsCaptured) captureInitialPreferredHotbarSlots();
    }

    private void recaptureProtectedRoleSlots() {
        Arrays.fill(protectedRoleSlots, -1);
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            int role = roleOfStack(stack);
            if (role == -1) continue;
            if (protectedRoleSlots[role] == -1) protectedRoleSlots[role] = i;
        }
    }

    private int getLockedRoleForSlot(int slot) {
        for (int role = 0; role < ROLE_COUNT; role++) {
            if (protectedRoleSlots[role] == slot) return role;
        }
        return -1;
    }

    private int roleOfItem(Item item) {
        if (item == null) return -1;
        ItemStack stack = item.getDefaultStack();
        return roleOfStack(stack);
    }

    private int roleOfStack(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return -1;
        if (isSwordStack(stack)) return ROLE_SWORD;
        if (stack.isIn(ItemTags.PICKAXES)) return ROLE_PICKAXE;
        if (isAppleStackForHotbar(stack)) return ROLE_APPLE;
        if (isObsidianStack(stack)) return ROLE_OBSIDIAN;
        if (isEnderChestStack(stack)) return ROLE_ENDER_CHEST;
        return -1;
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
        for (int slot : getCleanupScanOrder()) {
            ItemStack stack = mc.player.getInventory().getStack(slot);
            if (stack.isEmpty()) continue;
            if (!shouldDropJunkStack(stack)) continue;
            if (isFortuneThreePickaxeNoSilk(stack)) continue;

            dropInventorySlot(slot, true);
            junkCleanupDelay = 1;
            return;
        }

        // Keep exactly requested amount of netherrack for lava plugs.
        if (netherrackCount <= netherrackKeep) return;

        int excess = netherrackCount - netherrackKeep;
        int slot = findNetherrackCleanupSlot(excess);
        if (slot == -1) return;

        boolean dropWholeStack = mc.player.getInventory().getStack(slot).getCount() <= excess;
        dropInventorySlot(slot, dropWholeStack);
        junkCleanupDelay = dropWholeStack ? 1 : 0;
    }

    /**
     * Find and swap to the best tool for breaking the given block task.
     * Returns true if a suitable tool was equipped.
     */
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

    /**
     * Find and swap to the building material for the given block task.
     * Returns true if material was equipped.
     */
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

    private int[] getCleanupScanOrder() {
        // Main inventory first, then hotbar.
        int[] order = new int[36];
        int p = 0;
        for (int i = 9; i < 36; i++) order[p++] = i;
        for (int i = 0; i < 9; i++) order[p++] = i;
        return order;
    }

    private int findNetherrackCleanupSlot(int excess) {
        if (mc.player == null || excess <= 0) return -1;

        int bestSlot = -1;
        int bestCount = Integer.MAX_VALUE;

        for (int slot : getCleanupScanOrder()) {
            ItemStack stack = mc.player.getInventory().getStack(slot);
            if (stack.getItem() != Items.NETHERRACK) continue;

            int count = stack.getCount();
            if (count <= excess) {
                if (count < bestCount) {
                    bestCount = count;
                    bestSlot = slot;
                }
            } else if (bestSlot == -1) {
                // fallback: if all stacks are bigger than excess, drop single items from this one
                bestSlot = slot;
            }
        }

        return bestSlot;
    }

    private void dropInventorySlot(int inventorySlot, boolean fullStack) {
        if (mc.player == null || mc.interactionManager == null || mc.player.currentScreenHandler == null) return;
        int screenSlot = inventorySlotToScreenSlot(inventorySlot);
        if (screenSlot < 0) return;

        Runnable throwAction = () -> {
            if (mc.player == null || mc.interactionManager == null || mc.player.currentScreenHandler == null) return;
            mc.interactionManager.clickSlot(
                mc.player.currentScreenHandler.syncId,
                screenSlot,
                fullStack ? 1 : 0,
                SlotActionType.THROW,
                mc.player
            );
        };

        float throwYaw = getNextJunkDropYaw();
        float throwPitch = -10.0f;
        Rotations.rotate(throwYaw, throwPitch, 30, throwAction);
    }

    private float getNextJunkDropYaw() {
        if (mc.player == null) return 0.0f;

        int mode = Math.floorMod(junkDropDirectionIndex++, 3); // left, right, back
        HWDirection buildDir = module.pathfinder != null ? module.pathfinder.startingDirection : null;
        if (buildDir != null) {
            return switch (mode) {
                case 0 -> buildDir.counterClockwise(2).yaw;
                case 1 -> buildDir.clockwise(2).yaw;
                default -> buildDir.clockwise(4).yaw;
            };
        }

        float yaw = mc.player.getYaw();
        return switch (mode) {
            case 0 -> yaw - 90.0f;
            case 1 -> yaw + 90.0f;
            default -> yaw + 180.0f;
        };
    }

    private int inventorySlotToScreenSlot(int inventorySlot) {
        if (inventorySlot < 0 || inventorySlot >= 36) return -1;
        return inventorySlot < 9 ? 36 + inventorySlot : inventorySlot;
    }

    private boolean isSwordStack(ItemStack stack) {
        return stack != null && !stack.isEmpty() && stack.isIn(ItemTags.SWORDS);
    }

    private boolean isPickaxeStackForHotbar(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        if (!stack.isIn(ItemTags.PICKAXES)) return false;
        return isFortuneThreePickaxeNoSilk(stack) || !isSilkTouchPickaxe(stack);
    }

    private boolean isAppleStackForHotbar(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        return stack.getItem() == Items.ENCHANTED_GOLDEN_APPLE
            || stack.getItem() == Items.GOLDEN_APPLE
            || stack.getItem() == Items.APPLE;
    }

    private boolean isObsidianStack(ItemStack stack) {
        return stack != null && !stack.isEmpty() && stack.getItem() == Items.OBSIDIAN;
    }

    private boolean isEnderChestStack(ItemStack stack) {
        return stack != null && !stack.isEmpty() && stack.getItem() == Items.ENDER_CHEST;
    }

    private boolean shouldDropJunkStack(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        Item item = stack.getItem();
        if (item instanceof BlockItem bi && bi.getBlock() instanceof net.minecraft.block.ShulkerBoxBlock) return false;

        if (item == Items.NETHERRACK) return false;
        if (JUNK_ITEMS.contains(item)) {
            // Keep Fortune III pickaxe, even if enchanted.
            return !isFortuneThreePickaxeNoSilk(stack);
        }

        if (isFireResistancePotion(stack)) return true;

        // Extra rule from request: throw enchanted armor/tools as junk,
        // except the valid Fortune III pickaxe used by the builder.
        if (hasAnyEnchantments(stack) && !isFortuneThreePickaxeNoSilk(stack)) {
            if (stack.isDamageable()) return true;
            if (stack.isIn(ItemTags.SWORDS)) return true;
            if (stack.isIn(ItemTags.AXES)) return true;
            if (stack.isIn(ItemTags.SHOVELS)) return true;
            if (stack.isIn(ItemTags.HOES)) return true;
            if (stack.isIn(ItemTags.PICKAXES)) return true;
        }

        // Soul Speed enchanted book is junk by request.
        return item == Items.ENCHANTED_BOOK
            && Utils.getEnchantmentLevel(stack, Enchantments.SOUL_SPEED) > 0;
    }

    private boolean isFireResistancePotion(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        if (stack.getItem() != Items.POTION
            && stack.getItem() != Items.SPLASH_POTION
            && stack.getItem() != Items.LINGERING_POTION) return false;

        var contents = stack.get(DataComponentTypes.POTION_CONTENTS);
        if (contents == null || contents.potion().isEmpty()) return false;
        var potion = contents.potion().get().value();
        var id = Registries.POTION.getId(potion);
        return id != null && id.getPath().contains("fire_resistance");
    }

    private boolean hasAnyEnchantments(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        return stack.contains(DataComponentTypes.ENCHANTMENTS)
            || stack.contains(DataComponentTypes.STORED_ENCHANTMENTS);
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

    private boolean isFortuneThreePickaxeNoSilk(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        if (!stack.isIn(ItemTags.PICKAXES)) return false;
        if (Utils.hasEnchantment(stack, Enchantments.SILK_TOUCH)) return false;
        return Utils.getEnchantmentLevel(stack, Enchantments.FORTUNE) == 3;
    }

    private boolean isSilkTouchPickaxe(ItemStack stack) {
        return stack != null
            && !stack.isEmpty()
            && stack.isIn(ItemTags.PICKAXES)
            && Utils.hasEnchantment(stack, Enchantments.SILK_TOUCH);
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
