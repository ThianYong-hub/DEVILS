package com.example.addon.modules.highwaybuilder;

import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import net.minecraft.entity.ItemEntity;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class ContainerHandler {
    private static final MinecraftClient mc = MinecraftClient.getInstance();
    private static final int MAX_EXACT_TRANSFER_PER_TICK = 8;
    private static final int ENDER_CHEST_RESERVE = 16;
    private static final double RESTOCK_EDGE_PADDING = 0.04;
    private static final double RESTOCK_BIAS_AWAY_FROM_CONTAINER = 0.08;
    private static final int HAND_SWAP_OPEN_DELAY_TICKS = 2;

    private final HighwayBuilder module;
    public BlockTask containerTask;
    public int grindCycles = 0;

    private Item targetItem = null;
    private int transferDelay = 0;
    private int openAttempts = 0;
    private int openDelay = 0;
    private int handSwapDelay = 0;
    private boolean waitingForScreenClose = false;
    private BlockPos lastContainerPos = null;
    private BlockPos restockStandBlock = null;
    private Vec3d restockStandPos = null;

    public ContainerHandler(HighwayBuilder module) {
        this.module = module;
        this.containerTask = new BlockTask(BlockPos.ORIGIN, TaskState.DONE, Blocks.AIR);
    }

    /**
     * Start a restock cycle: find a shulker with the target item, place it, open, take items, break, collect.
     */
    public void handleRestock(Item item) {
        if (!module.storageManagement.get()) {
            module.disableWithError("No usable material/tool found and storage management is disabled.");
            return;
        }

        if (containerTask.taskState != TaskState.DONE) return;

        if (mc.player == null) return;

        clearRestockStandTarget();

        // Reserve at least one slot for the shulker item after breaking it.
        if (!hasSpaceForContainerDrop()) {
            module.disableWithError("Need at least one free inventory slot for shulker pickup.");
            return;
        }

        // Find a shulker box in inventory containing the target item
        int shulkerSlot = findShulkerWithItem(item);
        if (shulkerSlot == -1) {
            module.disableWithError("No shulker box found containing " + item.getName().getString());
            return;
        }

        // Get the shulker block type from the item
        ItemStack shulkerStack = mc.player.getInventory().getStack(shulkerSlot);
        Block shulkerBlock = ((BlockItem) shulkerStack.getItem()).getBlock();

        // Move shulker to hotbar slot 0 if it's in main inventory
        if (shulkerSlot >= 9) {
            InvUtils.move().from(shulkerSlot).toHotbar(0);
        }

        // Find a valid position to place the shulker
        BlockPos containerPos = getRemotePos();
        if (containerPos == null) {
            module.disableWithError("No valid position found for container placement.");
            return;
        }
        lastContainerPos = containerPos;

        restockStandBlock = selectRestockStandBlock(containerPos);
        if (restockStandBlock == null) {
            module.disableWithError("No safe standing position found for container restock.");
            return;
        }
        restockStandPos = getSafeRestockPoint(restockStandBlock, containerPos);
        if (restockStandPos == null) {
            module.disableWithError("Failed to compute safe restock center.");
            return;
        }

        containerTask = new BlockTask(containerPos, TaskState.PLACE, shulkerBlock);
        containerTask.item = item;
        containerTask.collect = true;
        containerTask.destroy = false; // open first, then destroy after taking items

        targetItem = item;
        transferDelay = 0;
        openAttempts = 0;
        openDelay = 0;
        handSwapDelay = 0;
        waitingForScreenClose = false;

        module.pathfinder.moveState = MovementState.RESTOCK;
    }

    /**
     * Open the placed shulker box.
     */
    public void doOpenContainer() {
        if (mc.player == null || mc.world == null) return;

        module.pathfinder.moveState = MovementState.RESTOCK;

        // If screen already opened but packet flag was missed, continue to restock.
        if (!containerTask.isOpen && mc.player.currentScreenHandler != null
            && mc.player.currentScreenHandler.syncId != 0) {
            containerTask.isOpen = true;
            containerTask.isLoaded = true;
        }

        // If already open, transition to RESTOCK to take items
        if (containerTask.isOpen) {
            openAttempts = 0;
            containerTask.updateState(TaskState.RESTOCK);
            return;
        }

        if (module.pathfinder != null
            && !module.pathfinder.isCenteredForRestock()
            && !canInteractWithContainerFromCurrentPos()) {
            return;
        }

        // Delay between open attempts — don't spam interact packets
        if (openDelay > 0) {
            openDelay--;
            return;
        }

        // Give one tick for forced hotbar swap to apply client/server-side.
        if (handSwapDelay > 0) {
            handSwapDelay--;
            return;
        }

        // Check reach distance
        double dist = mc.player.getEyePos().distanceTo(Vec3d.ofCenter(containerTask.blockPos));
        if (dist > module.maxReach.get() + 0.2) {
            // Wait for pathfinder to bring us closer
            return;
        }

        // Verify the shulker is still there
        Block currentBlock = mc.world.getBlockState(containerTask.blockPos).getBlock();
        if (!(currentBlock instanceof ShulkerBoxBlock)) {
            // Block update can be late for a tick or two, don't abort immediately.
            openDelay = 2;
            openAttempts++;
            if (openAttempts > 30) {
                clearRestockStandTarget();
                containerTask.updateState(TaskState.DONE);
                module.pathfinder.moveState = MovementState.RUNNING;
                openAttempts = 0;
            }
            return;
        }

        HandSafetyResult handSafety = ensureSafeMainHandForContainerInteraction();
        if (handSafety == HandSafetyResult.WAIT_SWAP_APPLY) return;
        if (handSafety == HandSafetyResult.FAILED) {
            containerTask.onStuck();
            return;
        }

        Direction side = getContainerInteractSide(containerTask.blockPos);
        if (side == null) side = Direction.UP;
        Vec3d hitVec = HWUtils.getHitVec(containerTask.blockPos, side);
        BlockHitResult hitResult = new BlockHitResult(hitVec, side, containerTask.blockPos, false);

        Runnable interact = () -> {
            if (mc.player == null) return;

            // Stop local drift while interacting.
            mc.player.setVelocity(0.0, mc.player.getVelocity().y, 0.0);

            if (mc.interactionManager != null) {
                mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hitResult);
            } else if (mc.getNetworkHandler() != null) {
                mc.getNetworkHandler().sendPacket(new PlayerInteractBlockC2SPacket(Hand.MAIN_HAND, hitResult, 0));
            }
            mc.player.swingHand(Hand.MAIN_HAND);
        };

        if (module.rotate.get()) {
            Rotations.rotate(Rotations.getYaw(hitVec), Rotations.getPitch(hitVec), 50, interact);
        } else {
            interact.run();
        }

        openDelay = 6; // wait before retrying (server needs time to respond)
        openAttempts++;
        if (openAttempts > 25) {
            // Stuck trying to open — break and abort
            containerTask.destroy = true;
            containerTask.collect = true;
            containerTask.updateState(TaskState.BREAK);
            openAttempts = 0;
        }
    }

    private Direction getContainerInteractSide(BlockPos pos) {
        Direction side = HWUtils.getMiningSide(pos);
        if (side != null) return side;
        if (mc.player == null) return Direction.UP;

        Vec3d eye = mc.player.getEyePos();
        Vec3d center = Vec3d.ofCenter(pos);
        double dx = eye.x - center.x;
        double dy = eye.y - center.y;
        double dz = eye.z - center.z;

        if (Math.abs(dy) > Math.abs(dx) + Math.abs(dz)) {
            return dy > 0 ? Direction.UP : Direction.DOWN;
        }
        return Direction.getFacing(dx, 0.0, dz);
    }

    private enum HandSafetyResult {
        READY,
        WAIT_SWAP_APPLY,
        FAILED
    }

    private HandSafetyResult ensureSafeMainHandForContainerInteraction() {
        if (mc.player == null) return HandSafetyResult.FAILED;

        if (!isBlockedContainerInteractItem(mc.player.getMainHandStack())) {
            return HandSafetyResult.READY;
        }

        int selected = mc.player.getInventory().getSelectedSlot();
        boolean swapped = false;

        // 1) Prefer switching to another already-safe hotbar slot.
        int safeHotbarSlot = findSafeHotbarSlot(selected);
        if (safeHotbarSlot != -1) {
            swapped = selectHotbarSlot(safeHotbarSlot);
        }

        // 2) If none exists, pull a safe inventory stack into an empty hotbar slot and select it.
        if (!swapped) {
            int safeInventorySlot = findSafeInventorySlot();
            if (safeInventorySlot != -1) {
                int emptyHotbar = findEmptyHotbarSlot(selected);
                int targetHotbarSlot = emptyHotbar != -1 ? emptyHotbar : selected;
                InvUtils.move().from(safeInventorySlot).toHotbar(targetHotbarSlot);
                if (targetHotbarSlot != selected) {
                    swapped = selectHotbarSlot(targetHotbarSlot);
                } else {
                    swapped = true;
                }
            }
        }

        // 3) Last resort: switch to an empty hotbar slot (empty hand is valid for opening).
        if (!swapped) {
            int emptyHotbar = findEmptyHotbarSlot(selected);
            if (emptyHotbar != -1) {
                swapped = selectHotbarSlot(emptyHotbar);
            }
        }

        if (!swapped) return HandSafetyResult.FAILED;

        // Wait for a real selected-slot update before interact packet.
        handSwapDelay = HAND_SWAP_OPEN_DELAY_TICKS;
        return HandSafetyResult.WAIT_SWAP_APPLY;
    }

    private int findSafeHotbarSlot(int excludeSlot) {
        if (mc.player == null) return -1;
        for (int i = 0; i < 9; i++) {
            if (i == excludeSlot) continue;
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.isEmpty() || !isBlockedContainerInteractItem(stack)) {
                return i;
            }
        }
        return -1;
    }

    private int findSafeInventorySlot() {
        if (mc.player == null) return -1;
        for (int i = 9; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.isEmpty()) continue;
            if (!isBlockedContainerInteractItem(stack)) return i;
        }
        return -1;
    }

    private int findEmptyHotbarSlot(int excludeSlot) {
        if (mc.player == null) return -1;
        for (int i = 0; i < 9; i++) {
            if (i == excludeSlot) continue;
            if (mc.player.getInventory().getStack(i).isEmpty()) return i;
        }
        return -1;
    }

    private boolean selectHotbarSlot(int slot) {
        if (mc.player == null || slot < 0 || slot > 8) return false;
        if (mc.player.getInventory().getSelectedSlot() == slot) return true;
        InvUtils.swap(slot, false);
        return mc.player.getInventory().getSelectedSlot() == slot;
    }

    private boolean isBlockedContainerInteractItem(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        Item item = stack.getItem();
        return item == Items.ENCHANTED_GOLDEN_APPLE
            || item == Items.END_CRYSTAL
            || stack.isIn(ItemTags.SWORDS);
    }

    /**
     * Take target items from the open shulker, then close and break it.
     */
    public void doRestock() {
        if (mc.player == null || mc.interactionManager == null) return;

        if (waitingForScreenClose) {
            if (mc.player.currentScreenHandler == null || mc.player.currentScreenHandler.syncId == 0) {
                waitingForScreenClose = false;
                containerTask.isOpen = false;
                containerTask.isLoaded = false;
                containerTask.destroy = true;
                containerTask.collect = true;
                containerTask.updateState(TaskState.BREAK);
            }
            return;
        }

        // Wait for container contents to load
        if (!containerTask.isLoaded) {
            containerTask.onStuck();
            return;
        }

        // Delay between transfers
        if (transferDelay > 0) {
            transferDelay--;
            return;
        }

        var handler = mc.player.currentScreenHandler;
        if (handler == null || handler.syncId == 0) {
            // Screen closed unexpectedly — reopen if shulker is still present.
            containerTask.isOpen = false;
            containerTask.isLoaded = false;
            containerTask.updateState(TaskState.OPEN_CONTAINER);
            return;
        }

        if (targetItem == Items.ENDER_CHEST) {
            doEnderChestRestock(handler);
            return;
        }

        if (targetItem != null && !canTakeAnotherStack(targetItem)) {
            closeAndBreak();
            return;
        }

        // Search shulker slots (0-26) for the target item
        boolean found = false;
        for (int i = 0; i < 27; i++) {
            ItemStack slotStack = handler.getSlot(i).getStack();
            if (slotStack.isEmpty()) continue;
            if (targetItem != null && slotStack.getItem() != targetItem) continue;

            // Quick-move this stack to player inventory
            mc.interactionManager.clickSlot(handler.syncId, i, 0, SlotActionType.QUICK_MOVE, mc.player);
            transferDelay = 2; // small delay between transfers
            found = true;
            break;
        }

        if (!found) {
            // No more target items in shulker — close, break, collect
            closeAndBreak();
        }
    }

    /**
     * Wait for the shulker item to be picked up after breaking.
     */
    public void doPickup() {
        if (mc.world == null) return;

        if (getCollectingPosition() == null) {
            clearRestockStandTarget();
            containerTask.updateState(TaskState.DONE);
            module.pathfinder.moveState = MovementState.RUNNING;
            grindCycles++;
            return;
        }

        // Wait until the dropped shulker item is gone (picked up), then resume.
        boolean hasShulkerDrop = mc.world.getEntitiesByClass(
            ItemEntity.class,
            new Box(containerTask.blockPos).expand(1.5),
            itemEntity -> itemEntity.getStack().getItem() == containerTask.targetBlock.asItem()
        ).stream().findAny().isPresent();

        if (!hasShulkerDrop) {
            clearRestockStandTarget();
            containerTask.updateState(TaskState.DONE);
            module.pathfinder.moveState = MovementState.RUNNING;
            grindCycles++;
            return;
        }

        containerTask.onStuck();
    }

    /**
     * Close the shulker screen and transition to BREAK state.
     */
    private void closeAndBreak() {
        if (mc.player != null
            && mc.player.currentScreenHandler != null
            && mc.player.currentScreenHandler.syncId != 0) {
            mc.player.closeHandledScreen();
            waitingForScreenClose = true;
            transferDelay = 2;
            return;
        }

        waitingForScreenClose = false;
        containerTask.isOpen = false;
        containerTask.isLoaded = false;
        containerTask.destroy = true;
        containerTask.collect = true;
        containerTask.updateState(TaskState.BREAK);
    }

    private boolean canTakeAnotherStack(Item item) {
        if (mc.player == null) return false;

        int emptySlots = 0;
        boolean hasPartialTargetStack = false;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.isEmpty()) {
                emptySlots++;
                continue;
            }
            if (stack.getItem() == item && stack.getCount() < stack.getMaxCount()) {
                hasPartialTargetStack = true;
            }
        }

        if (hasPartialTargetStack) return true;
        // Keep one slot reserved for shulker pickup.
        return emptySlots > 1;
    }

    private void doEnderChestRestock(ScreenHandler handler) {
        if (mc.player == null || mc.interactionManager == null) return;

        // Phase 1: always drain all obsidian from this shulker first (if any).
        if (tryMoveObsidianFromContainer(handler)) {
            transferDelay = 2;
            return;
        }

        int desiredTotal = getDesiredEnderChestCount();
        int currentCount = countInventoryItem(Items.ENDER_CHEST);
        int needed = desiredTotal - currentCount;

        if (desiredTotal <= 0 || needed <= 0) {
            closeAndBreak();
            return;
        }

        if (findPlayerSlotForSingleInsert(handler, Items.ENDER_CHEST) == -1) {
            // No room for additional ender chests right now.
            closeAndBreak();
            return;
        }

        // Keep cursor clean. If something is already held, wait until next tick.
        if (!handler.getCursorStack().isEmpty()) {
            containerTask.onStuck();
            return;
        }

        for (int i = 0; i < 27; i++) {
            ItemStack slotStack = handler.getSlot(i).getStack();
            if (slotStack.isEmpty() || slotStack.getItem() != Items.ENDER_CHEST) continue;

            int toMove = Math.min(needed, slotStack.getCount());
            if (toMove == slotStack.getCount()) {
                mc.interactionManager.clickSlot(handler.syncId, i, 0, SlotActionType.QUICK_MOVE, mc.player);
                transferDelay = 2;
                return;
            }

            int moved = moveExactItemsFromContainer(
                handler,
                i,
                Items.ENDER_CHEST,
                Math.min(toMove, MAX_EXACT_TRANSFER_PER_TICK)
            );
            if (moved > 0) {
                transferDelay = 2;
            } else {
                containerTask.onStuck();
            }
            return;
        }

        // No more ender chests in this shulker.
        closeAndBreak();
    }

    private boolean tryMoveObsidianFromContainer(ScreenHandler handler) {
        if (mc.player == null || mc.interactionManager == null) return false;
        if (!canTakeAnotherStack(Items.OBSIDIAN)) return false;

        for (int i = 0; i < 27; i++) {
            ItemStack slotStack = handler.getSlot(i).getStack();
            if (slotStack.isEmpty() || slotStack.getItem() != Items.OBSIDIAN) continue;

            mc.interactionManager.clickSlot(handler.syncId, i, 0, SlotActionType.QUICK_MOVE, mc.player);
            return true;
        }

        return false;
    }

    private int moveExactItemsFromContainer(ScreenHandler handler, int sourceSlot, Item item, int amount) {
        if (mc.player == null || mc.interactionManager == null || amount <= 0) return 0;

        ItemStack sourceStack = handler.getSlot(sourceSlot).getStack();
        if (sourceStack.isEmpty() || sourceStack.getItem() != item) return 0;

        // Pick up the full stack from the container slot.
        mc.interactionManager.clickSlot(handler.syncId, sourceSlot, 0, SlotActionType.PICKUP, mc.player);

        int moved = 0;
        for (int i = 0; i < amount; i++) {
            int playerSlot = findPlayerSlotForSingleInsert(handler, item);
            if (playerSlot == -1) break;

            // Right-click places exactly one item from cursor.
            mc.interactionManager.clickSlot(handler.syncId, playerSlot, 1, SlotActionType.PICKUP, mc.player);
            moved++;
        }

        // Put the remaining cursor stack back into the source slot.
        if (!handler.getCursorStack().isEmpty()) {
            mc.interactionManager.clickSlot(handler.syncId, sourceSlot, 0, SlotActionType.PICKUP, mc.player);
        }

        return moved;
    }

    private int findPlayerSlotForSingleInsert(ScreenHandler handler, Item item) {
        int playerStart = Math.max(0, handler.slots.size() - 36);
        int playerEnd = handler.slots.size();

        // Prefer topping up existing stacks first.
        for (int i = playerStart; i < playerEnd; i++) {
            ItemStack stack = handler.getSlot(i).getStack();
            if (stack.isEmpty()) continue;
            if (stack.getItem() == item && stack.getCount() < stack.getMaxCount()) {
                return i;
            }
        }

        // Then use empty slots.
        for (int i = playerStart; i < playerEnd; i++) {
            if (handler.getSlot(i).getStack().isEmpty()) return i;
        }

        return -1;
    }

    private int getDesiredEnderChestCount() {
        int capacity = countObsidianCapacityWithReserve();
        // Keep reserve and add only EC count that can be fully converted into obsidian.
        int forObsidian = capacity < 8 ? 0 : capacity / 8;
        return ENDER_CHEST_RESERVE + forObsidian;
    }

    private int countObsidianCapacityWithReserve() {
        if (mc.player == null) return 0;

        int emptySlots = 0;
        int partialObsidianSpace = 0;

        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.isEmpty()) {
                emptySlots++;
                continue;
            }
            if (stack.getItem() == Items.OBSIDIAN && stack.getCount() < stack.getMaxCount()) {
                partialObsidianSpace += stack.getMaxCount() - stack.getCount();
            }
        }

        // Reserve one empty slot for shulker pickup after breaking it.
        if (emptySlots > 0) emptySlots--;

        return partialObsidianSpace + emptySlots * 64;
    }

    private int countInventoryItem(Item item) {
        if (mc.player == null) return 0;
        int count = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.getItem() == item) count += stack.getCount();
        }
        return count;
    }

    private boolean hasSpaceForContainerDrop() {
        if (mc.player == null) return false;
        for (int i = 0; i < 36; i++) {
            if (mc.player.getInventory().getStack(i).isEmpty()) return true;
        }
        return false;
    }

    // ── Shulker scanning ─────────────────────────────────────────────────

    /**
     * Find an inventory slot containing a shulker box that holds the given item.
     * Returns -1 if none found.
     */
    public int findShulkerWithItem(Item item) {
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
                if (hasObsidian) return i; // Prefer mixed shulkers: pull obsidian first, then ECs.
                if (fallback == -1) fallback = i;
            }
            return fallback;
        }

        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.isEmpty()) continue;
            if (!(stack.getItem() instanceof BlockItem bi)) continue;
            if (!(bi.getBlock() instanceof ShulkerBoxBlock)) continue;

            // Check shulker contents via DataComponents
            ContainerComponent container = stack.get(DataComponentTypes.CONTAINER);
            if (container == null) continue;

            for (ItemStack contained : container.iterateNonEmpty()) {
                if (contained.getItem() == item) {
                    return i;
                }
            }
        }
        return -1;
    }

    // ── Position helpers ─────────────────────────────────────────────────

    /**
     * Find a valid position adjacent to the player to place a container.
     * Prioritises the position behind the player (relative to highway direction),
     * then falls back to all four cardinal directions.
     * Does NOT exclude blueprint positions — the shulker is temporary.
     */
    private BlockPos getRemotePos() {
        if (mc.player == null || mc.world == null) return null;

        BlockPos playerPos = mc.player.getBlockPos();
        BlockPos anchorPos = module.pathfinder != null ? module.pathfinder.currentBlockPos : playerPos;
        double maxReach = module.maxReach.get();

        // Reuse previous successful container position if still valid.
        if (lastContainerPos != null && isValidContainerPos(lastContainerPos, maxReach)) {
            return lastContainerPos;
        }

        // 1) Try behind anchor block (already-built highway — guaranteed solid floor)
        HWDirection hwDir = module.pathfinder != null ? module.pathfinder.startingDirection : null;
        if (hwDir != null) {
            BlockPos behind = anchorPos.add(
                -hwDir.directionVec.getX(), 0, -hwDir.directionVec.getZ());
            if (isValidContainerPos(behind, maxReach)) return behind;
        }

        // 2) Try all four cardinal directions around anchor
        for (Direction dir : Direction.Type.HORIZONTAL) {
            BlockPos pos = anchorPos.offset(dir);
            if (isValidContainerPos(pos, maxReach)) return pos;
        }

        // 3) Try one block above in the same directions (in case floor is uneven)
        if (hwDir != null) {
            BlockPos behindUp = anchorPos.add(
                -hwDir.directionVec.getX(), 1, -hwDir.directionVec.getZ());
            if (isValidContainerPos(behindUp, maxReach)) return behindUp;
        }
        for (Direction dir : Direction.Type.HORIZONTAL) {
            BlockPos pos = anchorPos.offset(dir).up();
            if (isValidContainerPos(pos, maxReach)) return pos;
        }

        // 4) Fallback around actual player block (edge cases on uneven terrain)
        if (!anchorPos.equals(playerPos)) {
            for (Direction dir : Direction.Type.HORIZONTAL) {
                BlockPos pos = playerPos.offset(dir);
                if (isValidContainerPos(pos, maxReach)) return pos;
            }
        }

        return null;
    }

    private boolean isValidContainerPos(BlockPos pos, double maxReach) {
        if (mc.world == null || mc.player == null) return false;
        // Position must be air or replaceable
        if (!mc.world.getBlockState(pos).isAir()
            && !mc.world.getBlockState(pos).isReplaceable()) return false;
        // Never place container where player (or another blocking entity) is standing.
        Box targetBox = new Box(pos);
        if (mc.player.getBoundingBox().intersects(targetBox)) return false;
        if (!mc.world.getOtherEntities(null, targetBox, entity -> !(entity instanceof ItemEntity)).isEmpty()) {
            return false;
        }
        // Must have solid ground below (not air, not replaceable, not fluid)
        BlockPos below = pos.down();
        if (mc.world.getBlockState(below).isAir()
            || mc.world.getBlockState(below).isReplaceable()
            || !mc.world.getFluidState(below).isEmpty()) return false;
        // Must be within reach
        double dist = mc.player.getEyePos().distanceTo(Vec3d.ofCenter(pos));
        return dist <= maxReach;
    }

    public Vec3d getRestockStandPos() {
        if (mc.player == null) return Vec3d.ZERO;

        if (containerTask.taskState == TaskState.DONE) {
            clearRestockStandTarget();
            BlockPos anchor = module.pathfinder != null ? module.pathfinder.currentBlockPos : mc.player.getBlockPos();
            return Vec3d.ofCenter(anchor);
        }

        BlockPos containerPos = containerTask.blockPos;

        if (restockStandBlock == null || !isSafeRestockStandBlock(restockStandBlock, containerPos)) {
            restockStandBlock = selectRestockStandBlock(containerPos);
            restockStandPos = null;
        }

        if (restockStandBlock == null) {
            restockStandBlock = selectFallbackStandBlock(containerPos);
            restockStandPos = null;
        }

        if (restockStandBlock == null) return Vec3d.ofCenter(mc.player.getBlockPos());

        if (restockStandPos == null) {
            restockStandPos = getSafeRestockPoint(restockStandBlock, containerPos);
        }

        if (restockStandPos == null) return Vec3d.ofCenter(restockStandBlock);
        return restockStandPos;
    }

    private void clearRestockStandTarget() {
        restockStandBlock = null;
        restockStandPos = null;
    }

    public void invalidateRestockStandTarget() {
        clearRestockStandTarget();
    }

    private BlockPos selectRestockStandBlock(BlockPos containerPos) {
        if (mc.world == null || mc.player == null) return null;

        List<BlockPos> candidates = new ArrayList<>();
        Set<BlockPos> unique = new HashSet<>();

        BlockPos current = module.pathfinder != null ? module.pathfinder.currentBlockPos : mc.player.getBlockPos();
        BlockPos playerPos = mc.player.getBlockPos();

        // Prefer blocks directly adjacent to container.
        for (Direction dir : Direction.Type.HORIZONTAL) {
            addCandidate(candidates, unique, containerPos.offset(dir));
        }

        // Keep previous stand block as sticky fallback.
        addCandidate(candidates, unique, restockStandBlock);
        addCandidate(candidates, unique, current);
        addCandidate(candidates, unique, playerPos);

        // Last-resort candidates around current/player position.
        for (Direction dir : Direction.Type.HORIZONTAL) {
            addCandidate(candidates, unique, current.offset(dir));
            addCandidate(candidates, unique, playerPos.offset(dir));
        }

        // Rare edge case on diagonal highways.
        for (Direction dir : Direction.Type.HORIZONTAL) {
            addCandidate(candidates, unique, containerPos.offset(dir).up());
        }

        candidates.sort((a, b) -> {
            double da = mc.player.getPos().squaredDistanceTo(Vec3d.ofCenter(a));
            double db = mc.player.getPos().squaredDistanceTo(Vec3d.ofCenter(b));
            return Double.compare(da, db);
        });

        for (BlockPos candidate : candidates) {
            if (isSafeRestockStandBlock(candidate, containerPos)) return candidate;
        }

        return null;
    }

    private void addCandidate(List<BlockPos> list, Set<BlockPos> unique, BlockPos pos) {
        if (pos == null) return;
        if (unique.add(pos)) list.add(pos);
    }

    private boolean isSafeRestockStandBlock(BlockPos standBlock, BlockPos containerPos) {
        if (mc.world == null || mc.player == null) return false;
        if (standBlock.equals(containerPos)) return false;

        // Keep feet/head cells passable.
        if (!mc.world.getBlockState(standBlock).isAir()
            && !mc.world.getBlockState(standBlock).isReplaceable()) return false;
        if (!mc.world.getBlockState(standBlock.up()).isAir()
            && !mc.world.getBlockState(standBlock.up()).isReplaceable()) return false;

        // Ensure floor support.
        BlockPos below = standBlock.down();
        if (mc.world.getBlockState(below).isAir()
            || mc.world.getBlockState(below).isReplaceable()
            || !mc.world.getFluidState(below).isEmpty()) return false;

        Vec3d target = getSafeRestockPoint(standBlock, containerPos);
        if (target == null) return false;

        // Must be in interaction range.
        double eyeOffset = mc.player.getEyeY() - mc.player.getY();
        Vec3d targetEye = new Vec3d(target.x, standBlock.getY() + eyeOffset, target.z);
        if (targetEye.distanceTo(Vec3d.ofCenter(containerPos)) > module.maxReach.get() + 0.15) {
            return false;
        }

        // Player hitbox at target must not overlap container block.
        double halfWidth = mc.player.getWidth() / 2.0;
        double feetY = standBlock.getY();
        Box playerBox = new Box(
            target.x - halfWidth,
            feetY,
            target.z - halfWidth,
            target.x + halfWidth,
            feetY + mc.player.getHeight(),
            target.z + halfWidth
        );
        return !playerBox.intersects(new Box(containerPos));
    }

    private BlockPos selectFallbackStandBlock(BlockPos containerPos) {
        if (mc.world == null || mc.player == null) return null;

        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (Direction dir : Direction.Type.HORIZONTAL) {
            BlockPos candidate = containerPos.offset(dir);
            if (candidate.equals(containerPos)) continue;

            if (!mc.world.getBlockState(candidate).isAir()
                && !mc.world.getBlockState(candidate).isReplaceable()) continue;
            if (!mc.world.getBlockState(candidate.up()).isAir()
                && !mc.world.getBlockState(candidate.up()).isReplaceable()) continue;

            BlockPos below = candidate.down();
            if (mc.world.getBlockState(below).isAir()
                || mc.world.getBlockState(below).isReplaceable()
                || !mc.world.getFluidState(below).isEmpty()) continue;

            double d = mc.player.getPos().squaredDistanceTo(Vec3d.ofCenter(candidate));
            if (d < bestDist) {
                bestDist = d;
                best = candidate;
            }
        }

        return best;
    }

    private Vec3d getSafeRestockPoint(BlockPos standBlock, BlockPos containerPos) {
        if (mc.player == null) return null;

        Vec3d center = Vec3d.ofCenter(standBlock);

        double halfWidth = mc.player.getWidth() / 2.0;
        double safetyMargin = halfWidth + RESTOCK_EDGE_PADDING;
        double minX = standBlock.getX() + safetyMargin;
        double maxX = standBlock.getX() + 1.0 - safetyMargin;
        double minZ = standBlock.getZ() + safetyMargin;
        double maxZ = standBlock.getZ() + 1.0 - safetyMargin;

        if (minX > maxX || minZ > maxZ) return center;

        double tx = center.x;
        double tz = center.z;

        // Move slightly away from the container so the player never clips into it.
        if (containerPos != null) {
            int dx = containerPos.getX() - standBlock.getX();
            int dz = containerPos.getZ() - standBlock.getZ();
            tx -= Math.signum(dx) * RESTOCK_BIAS_AWAY_FROM_CONTAINER;
            tz -= Math.signum(dz) * RESTOCK_BIAS_AWAY_FROM_CONTAINER;
        }

        tx = clamp(tx, minX, maxX);
        tz = clamp(tz, minZ, maxZ);
        return new Vec3d(tx, center.y, tz);
    }

    public boolean canInteractWithContainerFromCurrentPos() {
        if (mc.player == null || containerTask.taskState == TaskState.DONE) return false;

        Vec3d containerCenter = Vec3d.ofCenter(containerTask.blockPos);
        if (mc.player.getEyePos().distanceTo(containerCenter) > module.maxReach.get() + 0.2) {
            return false;
        }

        return !mc.player.getBoundingBox().intersects(new Box(containerTask.blockPos));
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    public BlockPos getCollectingPosition() {
        if (mc.world == null) return null;
        if (containerTask.taskState == TaskState.PICKUP) {
            return containerTask.blockPos;
        }
        return null;
    }
}
