package com.example.addon.modules.highwaybuilder;

import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
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
    private static final double RESTOCK_EDGE_PADDING = 0.06;
    private static final double RESTOCK_BIAS_AWAY_FROM_CONTAINER = 0.08;
    private static final int HAND_SWAP_OPEN_DELAY_TICKS = 2;
    private static final double SHULKER_PICKUP_SEARCH_RADIUS = 32.0;
    private static final int PICKUP_MISS_GRACE_TICKS = 20;
    private static final double RESTOCK_CONTAINER_CLEARANCE = 0.03;

    private final HighwayBuilder module;
    public BlockTask containerTask;
    public int grindCycles = 0;

    private Item targetItem = null;
    private int transferDelay = 0;
    private int openAttempts = 0;
    private int openDelay = 0;
    private int handSwapDelay = 0;
    private int pickupMissTicks = 0;
    private boolean waitingForScreenClose = false;
    private int waitingForScreenCloseTicks = 0;
    private int openLoadedWaitTicks = 0;
    private int openSideIndex = 0;
    private BlockPos lastContainerPos = null;
    private BlockPos restockStandBlock = null;
    private Vec3d restockStandPos = null;
    private boolean restockingFortunePickaxe = false;
    private boolean tookReplacementPickaxe = false;
    private int pickaxeConfirmWaitTicks = 0;
    private int pickaxeSearchMissTicks = 0;
    private Item activeContainerItem = null;
    private int activeContainerCountBeforePlace = -1;
    private int activeContainerHotbarSlot = -1;

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

        // Don't switch to other restock targets while Fortune-pick cycle isn't finished.
        if (item != Items.DIAMOND_PICKAXE && restockingFortunePickaxe && !hasFortunePickaxeInInventory()) {
            if (restartFortunePickaxeRestockCycle()) return;
            module.disableWithError("No Fortune III pickaxe available for restock workflow.");
            return;
        }

        // Global priority: always secure a valid Fortune III pickaxe first.
        // This prevents starting any other restock flow and then breaking shulker by hand.
        if (item != Items.DIAMOND_PICKAXE && !hasFortunePickaxeInInventory()) {
            if (findShulkerWithFortunePickaxe() != -1) {
                handleFortunePickaxeRestock();
                return;
            }
            module.disableWithError("No Fortune III pickaxe available for restock workflow.");
            return;
        }

        clearRestockStandTarget();

        // Reserve at least one slot for the shulker item after breaking it.
        if (!hasSpaceForContainerDrop() && !ensureReservedPickupSlot()) {
            module.disableWithError("Need at least one free inventory slot for shulker pickup.");
            return;
        }

        // Find a shulker box in inventory containing the target item
        int shulkerSlot = findShulkerWithItem(item);
        if (shulkerSlot == -1) {
            module.disableWithError("No shulker box found containing " + item.getName().getString());
            return;
        }
        resetPickaxeRestockState();
        targetItem = item;
        if (!startRestockCycle(shulkerSlot, item)) return;
    }

    public void handleFortunePickaxeRestock() {
        if (!module.storageManagement.get()) {
            module.disableWithError("No usable material/tool found and storage management is disabled.");
            return;
        }

        if (containerTask.taskState != TaskState.DONE) return;
        if (mc.player == null) return;

        clearRestockStandTarget();

        if (!hasSpaceForContainerDrop() && !ensureReservedPickupSlot()) {
            module.disableWithError("Need at least one free inventory slot for shulker pickup.");
            return;
        }

        int shulkerSlot = findShulkerWithFortunePickaxe();
        if (shulkerSlot == -1) {
            module.disableWithError("No shulker box found with Fortune III pickaxe (Silk Touch is not allowed).");
            return;
        }

        restockingFortunePickaxe = true;
        tookReplacementPickaxe = false;
        pickaxeConfirmWaitTicks = 0;
        pickaxeSearchMissTicks = 0;
        targetItem = Items.DIAMOND_PICKAXE;

        if (!startRestockCycle(shulkerSlot, targetItem)) {
            resetPickaxeRestockState();
        }
    }

    private boolean startRestockCycle(int shulkerSlot, Item item) {
        if (mc.player == null) return false;

        ItemStack shulkerStack = mc.player.getInventory().getStack(shulkerSlot);
        if (!(shulkerStack.getItem() instanceof BlockItem shulkerItem)) {
            module.disableWithError("Selected restock container is not a shulker box.");
            return false;
        }
        Block shulkerBlock = shulkerItem.getBlock();
        activeContainerItem = shulkerStack.getItem();
        activeContainerCountBeforePlace = countInventoryItem(activeContainerItem);
        activeContainerHotbarSlot = prepareContainerItemOnHotbar(shulkerSlot);
        if (activeContainerHotbarSlot == -1) {
            module.disableWithError("Failed to prepare shulker in hotbar for placement.");
            return false;
        }

        BlockPos containerPos = getRemotePos();
        if (containerPos == null) {
            module.disableWithError("No valid position found for container placement.");
            return false;
        }
        lastContainerPos = containerPos;

        restockStandBlock = selectRestockStandBlock(containerPos);
        if (restockStandBlock == null) {
            module.disableWithError("No safe standing position found for container restock.");
            return false;
        }
        restockStandPos = getSafeRestockPoint(restockStandBlock, containerPos);
        if (restockStandPos == null) {
            module.disableWithError("Failed to compute safe restock center.");
            return false;
        }

        containerTask = new BlockTask(containerPos, TaskState.PLACE, shulkerBlock);
        containerTask.item = item;
        containerTask.collect = true;
        containerTask.destroy = false;

        transferDelay = 0;
        openAttempts = 0;
        openDelay = 0;
        handSwapDelay = 0;
        pickupMissTicks = 0;
        waitingForScreenCloseTicks = 0;
        openLoadedWaitTicks = 0;
        openSideIndex = 0;
        waitingForScreenClose = false;

        module.pathfinder.moveState = MovementState.RESTOCK;
        return true;
    }

    private void resetPickaxeRestockState() {
        restockingFortunePickaxe = false;
        tookReplacementPickaxe = false;
        pickaxeConfirmWaitTicks = 0;
        pickaxeSearchMissTicks = 0;
        openLoadedWaitTicks = 0;
        activeContainerItem = null;
        activeContainerCountBeforePlace = -1;
        activeContainerHotbarSlot = -1;
    }

    private boolean hasFortunePickaxeInInventory() {
        return findBestFortunePickaxeSlotInInventory() != -1;
    }

    private boolean restartFortunePickaxeRestockCycle() {
        if (mc.player == null) return false;

        int shulkerSlot = findShulkerWithFortunePickaxe();
        if (shulkerSlot == -1) return false;

        restockingFortunePickaxe = true;
        tookReplacementPickaxe = false;
        pickaxeConfirmWaitTicks = 0;
        pickaxeSearchMissTicks = 0;
        targetItem = Items.DIAMOND_PICKAXE;
        clearRestockStandTarget();
        waitingForScreenClose = false;
        waitingForScreenCloseTicks = 0;

        return startRestockCycle(shulkerSlot, targetItem);
    }

    /**
     * Open the placed shulker box.
     */
    public void doOpenContainer() {
        if (mc.player == null || mc.world == null) return;

        module.pathfinder.moveState = MovementState.RESTOCK;

        // If screen already opened but packet flag was missed, continue to restock.
        // Do NOT set isLoaded here — wait for InventoryS2CPacket via PacketHandler
        // to ensure container slot data has actually arrived from the server.
        if (!containerTask.isOpen && mc.player.currentScreenHandler != null
            && mc.player.currentScreenHandler.syncId != 0) {
            containerTask.isOpen = true;
        }

        // If already open, wait for slot data to arrive, then transition to RESTOCK.
        if (containerTask.isOpen) {
            if (!containerTask.isLoaded) {
                // Slot data not yet received from server — wait.
                openLoadedWaitTicks++;
                if (mc.player.currentScreenHandler != null
                    && mc.player.currentScreenHandler.syncId != 0
                    && openLoadedWaitTicks > 8) {
                    // Fallback for servers/modpacks that delay or filter
                    // normal container slot sync packets.
                    containerTask.isLoaded = true;
                }
                containerTask.resetStuck();
                return;
            }
            openAttempts = 0;
            openLoadedWaitTicks = 0;
            openSideIndex = 0;
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

        // Check reach distance using closest interact face, not center.
        if (getBestContainerInteractDistance(containerTask.blockPos) > module.maxReach.get() + 0.35) {
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
                if (restockingFortunePickaxe && !hasFortunePickaxeInInventory()) {
                    if (restartFortunePickaxeRestockCycle()) {
                        openAttempts = 0;
                        return;
                    }
                    module.disableWithError("Failed to continue Fortune III pickaxe restock.");
                    return;
                }
                clearRestockStandTarget();
                BlockPos probePos = lastContainerPos != null ? lastContainerPos : containerTask.blockPos;
                if (mc.world.getBlockState(probePos).getBlock() instanceof ShulkerBoxBlock) {
                    beginContainerBreak();
                    module.pathfinder.moveState = MovementState.RESTOCK;
                } else {
                    containerTask.updateState(TaskState.PICKUP);
                    module.pathfinder.moveState = MovementState.PICKUP;
                }
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
                var result = mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hitResult);
                if (!result.isAccepted() && mc.getNetworkHandler() != null) {
                    mc.getNetworkHandler().sendPacket(new PlayerInteractBlockC2SPacket(Hand.MAIN_HAND, hitResult, 0));
                }
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
        if (openAttempts % 8 == 0) {
            // Force stand target re-evaluation if we keep missing interacts.
            invalidateRestockStandTarget();
        }
        if (openAttempts > 25) {
            // For Fortune-pick restock, never abandon unopened container.
            if (restockingFortunePickaxe && !hasFortunePickaxeInInventory()) {
                invalidateRestockStandTarget();
                openDelay = 4;
                openAttempts = 0;
                containerTask.resetStuck();
                return;
            }

            // Stuck trying to open — break and abort
            beginContainerBreak();
            openAttempts = 0;
        }
    }

    private Direction getContainerInteractSide(BlockPos pos) {
        if (mc.player == null || mc.world == null) return Direction.UP;

        Vec3d eye = mc.player.getEyePos();
        double maxReach = module.maxReach.get() + 0.45;
        List<Direction> visible = new ArrayList<>();

        for (Direction side : Direction.values()) {
            Vec3d hit = HWUtils.getHitVec(pos, side);
            if (eye.distanceTo(hit) > maxReach) continue;

            BlockPos adjacent = pos.offset(side);
            BlockState adjacentState = mc.world.getBlockState(adjacent);
            boolean faceExposed = adjacentState.isAir()
                || adjacentState.isReplaceable()
                || !adjacentState.isOpaque();
            if (faceExposed) visible.add(side);
        }

        if (visible.isEmpty()) {
            Direction best = null;
            double bestDist = Double.MAX_VALUE;
            for (Direction side : Direction.values()) {
                double dist = eye.distanceTo(HWUtils.getHitVec(pos, side));
                if (dist < bestDist) {
                    bestDist = dist;
                    best = side;
                }
            }
            return best != null ? best : Direction.UP;
        }

        visible.sort((a, b) -> Double.compare(
            eye.distanceTo(HWUtils.getHitVec(pos, a)),
            eye.distanceTo(HWUtils.getHitVec(pos, b))
        ));

        Direction side = visible.get(openSideIndex % visible.size());
        openSideIndex++;
        return side;
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
                Item safeItem = mc.player.getInventory().getStack(safeInventorySlot).getItem();
                int targetHotbarSlot = emptyHotbar != -1 ? emptyHotbar
                    : (canUseContainerHotbarSlot(selected, safeItem) ? selected : -1);
                if (targetHotbarSlot == -1) return HandSafetyResult.FAILED;
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
            if (!canUseContainerHotbarSlot(i, null)) continue;
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
                waitingForScreenCloseTicks = 0;
                beginContainerBreak();
                return;
            }

            waitingForScreenCloseTicks++;
            if (waitingForScreenCloseTicks % 6 == 0) {
                mc.player.closeHandledScreen();
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

        if (restockingFortunePickaxe) {
            doFortunePickaxeRestock(handler);
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

        BlockState atContainerPos = mc.world.getBlockState(containerTask.blockPos);
        if (atContainerPos.getBlock() instanceof ShulkerBoxBlock) {
            // Container still exists at the placement position: re-enter break flow.
            beginContainerBreak();
            module.pathfinder.moveState = MovementState.RESTOCK;
            return;
        }

        if (hasRecoveredContainerItem()) {
            finishPickupSuccess();
            return;
        }

        ItemEntity shulkerDrop = findClosestShulkerDrop();
        if (shulkerDrop != null) {
            pickupMissTicks = 0;
            if (!hasSpaceForContainerDrop()) {
                // Last-resort safety: free one slot for shulker pickup.
                if (!ensureReservedPickupSlot()) {
                    containerTask.onStuck();
                    return;
                }
            }
            containerTask.resetStuck();
            return;
        }

        pickupMissTicks++;
        if (pickupMissTicks < PICKUP_MISS_GRACE_TICKS) {
            containerTask.resetStuck();
            return;
        }

        if (restockingFortunePickaxe && !hasFortunePickaxeInInventory()) {
            if (restartFortunePickaxeRestockCycle()) return;
            module.disableWithError("No Fortune III pickaxe found after restock attempt.");
            return;
        }

        // Shulker wasn't confirmed as recovered yet: keep searching and don't
        // switch back to normal build loop prematurely.
        containerTask.resetStuck();
    }

    private void finishPickupSuccess() {
        if (restockingFortunePickaxe && !hasFortunePickaxeInInventory()) {
            if (restartFortunePickaxeRestockCycle()) return;
            module.disableWithError("Fortune III pickaxe restock did not complete.");
            return;
        }

        if (restockingFortunePickaxe) {
            normalizePostPickaxeRestockHotbar();
        }
        clearRestockStandTarget();
        resetPickaxeRestockState();
        containerTask.updateState(TaskState.DONE);
        module.pathfinder.moveState = MovementState.RUNNING;
        grindCycles++;
    }

    private boolean hasRecoveredContainerItem() {
        if (activeContainerItem == null || activeContainerCountBeforePlace < 0) return false;
        boolean recoveredContainer = countInventoryItem(activeContainerItem) >= activeContainerCountBeforePlace;
        if (!recoveredContainer) return false;
        if (restockingFortunePickaxe) return hasFortunePickaxeInInventory();
        return true;
    }

    private void normalizePostPickaxeRestockHotbar() {
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

    private int findBestFortunePickaxeSlotInInventory() {
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

    /**
     * Close the shulker screen and transition to BREAK state.
     */
    private void closeAndBreak() {
        if (mc.player != null
            && mc.player.currentScreenHandler != null
            && mc.player.currentScreenHandler.syncId != 0) {
            mc.player.closeHandledScreen();
            waitingForScreenClose = true;
            waitingForScreenCloseTicks = 0;
            transferDelay = 2;
            return;
        }

        waitingForScreenClose = false;
        waitingForScreenCloseTicks = 0;
        beginContainerBreak();
    }

    private void beginContainerBreak() {
        containerTask.isOpen = false;
        containerTask.isLoaded = false;
        containerTask.destroy = true;
        containerTask.collect = true;
        containerTask.miningSide = null;
        openSideIndex = 0;
        openLoadedWaitTicks = 0;
        pickupMissTicks = 0;
        containerTask.updateState(TaskState.BREAK);
    }

    private boolean ensureReservedPickupSlot() {
        if (hasSpaceForContainerDrop()) return true;
        if (tryStoreDisposableStackInOpenContainer()) return hasSpaceForContainerDrop();
        if (!dropSmallestDisposableStack()) return false;
        return hasSpaceForContainerDrop();
    }

    private boolean dropSmallestDisposableStack() {
        if (mc.player == null || mc.interactionManager == null) return false;

        ScreenHandler handler = mc.player.currentScreenHandler;
        if (handler == null) return false;

        int smallestSlot = findSmallestDisposablePlayerSlot(handler);
        if (smallestSlot == -1) return false;

        // Button=1 throws full stack; we choose the smallest stack to minimize loss.
        mc.interactionManager.clickSlot(handler.syncId, smallestSlot, 1, SlotActionType.THROW, mc.player);
        return true;
    }

    private ItemEntity findClosestShulkerDrop() {
        if (mc.world == null) return null;

        Item shulkerItem = containerTask.targetBlock.asItem();
        BlockPos centerPos = lastContainerPos != null ? lastContainerPos : containerTask.blockPos;
        Vec3d center = Vec3d.ofCenter(centerPos);
        Box searchBox = new Box(centerPos).expand(SHULKER_PICKUP_SEARCH_RADIUS);

        ItemEntity closest = null;
        double bestDist = Double.MAX_VALUE;
        for (ItemEntity itemEntity : mc.world.getEntitiesByClass(
            ItemEntity.class,
            searchBox,
            entity -> entity.getStack().getItem() == shulkerItem
        )) {
            double dist = itemEntity.getPos().squaredDistanceTo(center);
            if (dist < bestDist) {
                bestDist = dist;
                closest = itemEntity;
            }
        }
        return closest;
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

    private void doFortunePickaxeRestock(ScreenHandler handler) {
        if (mc.player == null || mc.interactionManager == null) return;

        // Keep cursor clean so move/quick-move logic stays deterministic.
        if (!handler.getCursorStack().isEmpty()) {
            containerTask.onStuck();
            return;
        }

        if (!tookReplacementPickaxe) {
            int replacementSlot = findBestFortunePickaxeSlotInContainer(handler);
            if (replacementSlot == -1) {
                // Container slot sync can lag for a few ticks after opening.
                // Don't abort restock immediately on first "not found".
                if (pickaxeSearchMissTicks < 20) {
                    pickaxeSearchMissTicks++;
                    transferDelay = 1;
                    containerTask.resetStuck();
                    return;
                }
                closeAndBreak();
                return;
            }
            pickaxeSearchMissTicks = 0;

            // Prefer deterministic hotbar swap: this avoids inventory-drop logic
            // while interacting with shulker and works even with full inventory.
            if (swapContainerSlotToHotbar(handler, replacementSlot)) {
                tookReplacementPickaxe = true;
                pickaxeConfirmWaitTicks = 8;
                transferDelay = 2;
            } else if (hasSpaceForContainerDrop() && quickMoveSlot(handler, replacementSlot)) {
                tookReplacementPickaxe = true;
                pickaxeConfirmWaitTicks = 8;
                transferDelay = 2;
            } else if (tryStoreDisposableStackInOpenContainer()) {
                transferDelay = 2;
            } else if (dropSmallestDisposableStack()) {
                transferDelay = 2;
            } else {
                containerTask.onStuck();
            }
            return;
        }

        // Don't close immediately: wait for server inventory sync and confirm
        // that a valid Fortune III non-silk pickaxe is now in inventory.
        if (findBestFortunePickaxeSlotInInventory() == -1) {
            if (pickaxeConfirmWaitTicks > 0) {
                pickaxeConfirmWaitTicks--;
                containerTask.resetStuck();
                return;
            }

            // No confirmation after grace period — retry taking once more.
            tookReplacementPickaxe = false;
            transferDelay = 1;
            containerTask.resetStuck();
            return;
        }

        closeAndBreak();
    }

    private boolean swapContainerSlotToHotbar(ScreenHandler handler, int containerSlot) {
        if (mc.player == null || mc.interactionManager == null) return false;
        if (containerSlot < 0 || containerSlot >= 27) return false;

        int hotbarSlot = findFortunePickaxeReceiveHotbarSlot();
        if (hotbarSlot == -1) return false;

        mc.interactionManager.clickSlot(handler.syncId, containerSlot, hotbarSlot, SlotActionType.SWAP, mc.player);

        if (mc.player.getInventory().getSelectedSlot() != hotbarSlot) {
            InvUtils.swap(hotbarSlot, false);
        }

        return true;
    }

    private int findFortunePickaxeReceiveHotbarSlot() {
        if (mc.player == null) return -1;

        int selected = mc.player.getInventory().getSelectedSlot();
        ItemStack selectedStack = mc.player.getInventory().getStack(selected);
        if (isSafeFortuneSwapTarget(selectedStack)) return selected;

        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.isEmpty()) return i;
        }

        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (isDisposableForSlotReserve(stack) && isSafeFortuneSwapTarget(stack)) return i;
        }

        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (isSafeFortuneSwapTarget(stack) && !stack.isIn(ItemTags.PICKAXES)) return i;
        }

        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (isSafeFortuneSwapTarget(stack)) return i;
        }

        return -1;
    }

    private boolean isSafeFortuneSwapTarget(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return true;

        // Never swap an active/restock shulker item into opened container:
        // it can desync recovery accounting and break pickup flow.
        if (activeContainerItem != null && stack.getItem() == activeContainerItem) return false;
        if (stack.getItem() instanceof BlockItem bi && bi.getBlock() instanceof ShulkerBoxBlock) return false;

        return true;
    }

    private int findBestFortunePickaxeSlotInContainer(ScreenHandler handler) {
        int bestSlot = -1;
        int bestRemaining = -1;
        for (int i = 0; i < 27; i++) {
            ItemStack stack = handler.getSlot(i).getStack();
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

    private boolean quickMoveSlot(ScreenHandler handler, int slot) {
        if (mc.player == null || mc.interactionManager == null) return false;
        if (slot < 0 || slot >= handler.slots.size()) return false;

        ItemStack before = handler.getSlot(slot).getStack();
        if (before.isEmpty()) return false;

        mc.interactionManager.clickSlot(handler.syncId, slot, 0, SlotActionType.QUICK_MOVE, mc.player);

        // Trust the click packet — on laggy servers the slot may not update until
        // the next tick, so checking count immediately is unreliable.
        // The transferDelay set by callers gives the server time to sync.
        return true;
    }

    private boolean tryStoreDisposableStackInOpenContainer() {
        if (mc.player == null || mc.interactionManager == null) return false;
        ScreenHandler handler = mc.player.currentScreenHandler;
        if (handler == null || handler.syncId == 0) return false;
        if (hasSpaceForContainerDrop()) return true;

        int playerSlot = findSmallestDisposablePlayerSlot(handler);
        if (playerSlot == -1) return false;
        ItemStack stackToMove = handler.getSlot(playerSlot).getStack();
        if (stackToMove.isEmpty()) return false;
        if (!hasContainerRoomForStack(handler, stackToMove)) return false;

        return quickMoveSlot(handler, playerSlot);
    }

    private boolean hasContainerRoomForStack(ScreenHandler handler, ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;

        for (int i = 0; i < 27; i++) {
            ItemStack slotStack = handler.getSlot(i).getStack();
            if (slotStack.isEmpty()) return true;
            if (ItemStack.areItemsAndComponentsEqual(slotStack, stack)
                && slotStack.getCount() < slotStack.getMaxCount()) {
                return true;
            }
        }

        return false;
    }

    private int findSmallestDisposablePlayerSlot(ScreenHandler handler) {
        int playerStart = Math.max(0, handler.slots.size() - 36);
        int playerEnd = handler.slots.size();

        int smallestSlot = -1;
        int smallestCount = Integer.MAX_VALUE;

        for (int i = playerStart; i < playerEnd; i++) {
            ItemStack stack = handler.getSlot(i).getStack();
            if (!isDisposableForSlotReserve(stack)) continue;
            if (stack.getCount() < smallestCount) {
                smallestCount = stack.getCount();
                smallestSlot = i;
            }
        }

        return smallestSlot;
    }

    private boolean isDisposableForSlotReserve(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        if (!(stack.getItem() instanceof BlockItem bi)) return false;

        Block block = bi.getBlock();
        return block == Blocks.OBSIDIAN
            || block == Blocks.NETHERRACK
            || block == module.getMaterial()
            || block == module.getFillerMat();
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

    public int findShulkerWithFortunePickaxe() {
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

    private boolean isFortuneThreePickaxeNoSilk(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        if (!stack.isIn(ItemTags.PICKAXES)) return false;
        if (Utils.hasEnchantment(stack, Enchantments.SILK_TOUCH)) return false;
        return Utils.getEnchantmentLevel(stack, Enchantments.FORTUNE) == 3;
    }

    // ── Position helpers ─────────────────────────────────────────────────

    /**
     * Find a valid position adjacent to the player to place a container.
     * Prioritises behind and back-diagonal positions, then sides.
     * Avoids forward positions unless no other safe adjacent candidate exists.
     * Keeps placement within one horizontal block (adjacent/diagonal), never two blocks away.
     */
    private BlockPos getRemotePos() {
        return getRemotePos(null);
    }

    private BlockPos getRemotePos(BlockPos avoidPos) {
        if (mc.player == null || mc.world == null) return null;

        BlockPos playerPos = mc.player.getBlockPos();
        BlockPos currentPos = module.pathfinder != null ? module.pathfinder.currentBlockPos : playerPos;
        double maxReach = module.maxReach.get();
        HWDirection hwDir = module.pathfinder != null ? module.pathfinder.startingDirection : null;

        // Reuse previous successful container position if still valid, preferred and
        // not currently intersecting player's hitbox.
        if (lastContainerPos != null
            && (avoidPos == null || !lastContainerPos.equals(avoidPos))
            && isPreferredContainerPos(lastContainerPos, playerPos, hwDir)
            && isValidContainerPos(lastContainerPos, maxReach, false)) {
            return lastContainerPos;
        }

        List<BlockPos> candidates = new ArrayList<>();
        Set<BlockPos> unique = new HashSet<>();

        addContainerCandidates(candidates, unique, playerPos, hwDir);
        if (!currentPos.equals(playerPos)) {
            addContainerCandidates(candidates, unique, currentPos, hwDir);
        }

        // Pass 1: strict preference and no self-overlap.
        for (BlockPos pos : candidates) {
            if (avoidPos != null && pos.equals(avoidPos)) continue;
            if (!isPreferredContainerPos(pos, playerPos, hwDir)) continue;
            if (isValidContainerPos(pos, maxReach, false)) return pos;
        }

        // Pass 1b: strict preference one block higher and no self-overlap.
        for (BlockPos base : candidates) {
            BlockPos pos = base.up();
            if (avoidPos != null && pos.equals(avoidPos)) continue;
            if (!isPreferredContainerPos(pos, playerPos, hwDir)) continue;
            if (isValidContainerPos(pos, maxReach, false)) return pos;
        }

        // Pass 2: strict preference, allow self-overlap if nothing else exists.
        for (BlockPos pos : candidates) {
            if (avoidPos != null && pos.equals(avoidPos)) continue;
            if (!isPreferredContainerPos(pos, playerPos, hwDir)) continue;
            if (isValidContainerPos(pos, maxReach, true)) return pos;
        }

        for (BlockPos base : candidates) {
            BlockPos pos = base.up();
            if (avoidPos != null && pos.equals(avoidPos)) continue;
            if (!isPreferredContainerPos(pos, playerPos, hwDir)) continue;
            if (isValidContainerPos(pos, maxReach, true)) return pos;
        }

        // Pass 3: any adjacent safe position (including forward), no self-overlap.
        for (BlockPos pos : candidates) {
            if (avoidPos != null && pos.equals(avoidPos)) continue;
            if (isValidContainerPos(pos, maxReach, false)) return pos;
        }

        for (BlockPos base : candidates) {
            BlockPos pos = base.up();
            if (avoidPos != null && pos.equals(avoidPos)) continue;
            if (isValidContainerPos(pos, maxReach, false)) return pos;
        }

        // Pass 4: final fallback allowing self-overlap.
        for (BlockPos pos : candidates) {
            if (avoidPos != null && pos.equals(avoidPos)) continue;
            if (isValidContainerPos(pos, maxReach, true)) return pos;
        }

        for (BlockPos base : candidates) {
            BlockPos pos = base.up();
            if (avoidPos != null && pos.equals(avoidPos)) continue;
            if (isValidContainerPos(pos, maxReach, true)) return pos;
        }

        // Last fallback: keep previous valid position even if not preferred.
        if (lastContainerPos != null
            && (avoidPos == null || !lastContainerPos.equals(avoidPos))
            && isValidContainerPos(lastContainerPos, maxReach, false)) {
            return lastContainerPos;
        }
        if (lastContainerPos != null
            && (avoidPos == null || !lastContainerPos.equals(avoidPos))
            && isValidContainerPos(lastContainerPos, maxReach, true)) {
            return lastContainerPos;
        }

        return null;
    }

    private boolean isPreferredContainerPos(BlockPos pos, BlockPos playerPos, HWDirection hwDir) {
        if (pos == null || playerPos == null) return false;

        int dx = pos.getX() - playerPos.getX();
        int dz = pos.getZ() - playerPos.getZ();
        if (dx == 0 && dz == 0) return false;
        if (Math.max(Math.abs(dx), Math.abs(dz)) > 1) return false; // never 2+ blocks away

        if (hwDir != null && hwDir.forwardProgress(playerPos, pos) > 0.0) return false; // never forward
        return true;
    }

    private boolean isValidContainerPos(BlockPos pos, double maxReach, boolean allowSelfOverlap) {
        if (mc.world == null || mc.player == null) return false;
        // Never choose the exact block the player currently occupies.
        // Movement recovery handles near-overlap, but same-block placement is always invalid.
        if (pos.equals(mc.player.getBlockPos())) return false;
        // Position must be air or replaceable
        if (!mc.world.getBlockState(pos).isAir()
            && !mc.world.getBlockState(pos).isReplaceable()) return false;
        // Shulker placed on ground opens upward — block above must be clear.
        BlockState aboveState = mc.world.getBlockState(pos.up());
        if (!aboveState.isAir() && !aboveState.isReplaceable() && aboveState.isOpaque()) return false;
        // Reject other blocking entities.
        Box targetBox = new Box(pos);
        if (!mc.world.getOtherEntities(
            null,
            targetBox,
            entity -> entity != mc.player && !(entity instanceof ItemEntity)
        ).isEmpty()) {
            return false;
        }
        // Prefer candidates that do not intersect current player hitbox; this
        // prevents seam-stalls where player blocks its own placement point.
        if (!allowSelfOverlap && mc.player.getBoundingBox().intersects(targetBox)) return false;
        // Must have solid ground below (not air, not replaceable, not fluid)
        BlockPos below = pos.down();
        if (mc.world.getBlockState(below).isAir()
            || mc.world.getBlockState(below).isReplaceable()
            || !mc.world.getFluidState(below).isEmpty()) return false;
        // Allow small tolerance: selection can happen while player is not centered yet.
        double dist = mc.player.getEyePos().distanceTo(Vec3d.ofCenter(pos));
        return dist <= maxReach + 1.0;
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

        if (restockStandBlock == null && tryRelocateContainerPlacement()) {
            containerPos = containerTask.blockPos;
            restockStandBlock = selectRestockStandBlock(containerPos);
            restockStandPos = null;
            if (restockStandBlock == null) {
                restockStandBlock = selectFallbackStandBlock(containerPos);
            }
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

    public boolean tryRelocateContainerPlacement() {
        if (mc.player == null || mc.world == null) return false;
        if (containerTask == null || containerTask.taskState == TaskState.DONE) return false;

        // Already placed/open container should not be relocated.
        Block currentAtTask = mc.world.getBlockState(containerTask.blockPos).getBlock();
        if (currentAtTask instanceof ShulkerBoxBlock) return false;

        BlockPos oldPos = containerTask.blockPos;
        BlockPos newPos = getRemotePos(oldPos);
        if (newPos == null || newPos.equals(oldPos)) return false;

        BlockTask newTask = new BlockTask(newPos, TaskState.PLACE, containerTask.targetBlock);
        newTask.item = containerTask.item;
        newTask.collect = containerTask.collect;
        newTask.destroy = containerTask.destroy;

        containerTask = newTask;
        lastContainerPos = newPos;

        transferDelay = 0;
        openAttempts = 0;
        openDelay = 0;
        handSwapDelay = 0;
        pickupMissTicks = 0;
        waitingForScreenClose = false;
        waitingForScreenCloseTicks = 0;
        openLoadedWaitTicks = 0;
        openSideIndex = 0;

        clearRestockStandTarget();
        module.pathfinder.moveState = MovementState.RESTOCK;
        return true;
    }

    public void setOpenDelay(int ticks) {
        openDelay = ticks;
    }

    private BlockPos selectRestockStandBlock(BlockPos containerPos) {
        if (mc.world == null || mc.player == null) return null;

        BlockPos current = module.pathfinder != null ? module.pathfinder.currentBlockPos : mc.player.getBlockPos();
        BlockPos playerPos = mc.player.getBlockPos();
        Set<BlockPos> unique = new HashSet<>();

        // Tier 1: strictly adjacent to container (highest priority, most reliable).
        List<BlockPos> adjacent = new ArrayList<>();
        for (Direction dir : Direction.Type.HORIZONTAL) {
            addCandidate(adjacent, unique, containerPos.offset(dir));
        }
        BlockPos hit = findNearestSafeCandidate(adjacent, containerPos);
        if (hit != null) return hit;

        // Tier 2: sticky previous/current/player positions.
        List<BlockPos> sticky = new ArrayList<>();
        addCandidate(sticky, unique, restockStandBlock);
        addCandidate(sticky, unique, current);
        addCandidate(sticky, unique, playerPos);
        hit = findNearestSafeCandidate(sticky, containerPos);
        if (hit != null) return hit;

        // Tier 3: around current/player.
        List<BlockPos> around = new ArrayList<>();
        for (Direction dir : Direction.Type.HORIZONTAL) {
            addCandidate(around, unique, current.offset(dir));
            addCandidate(around, unique, playerPos.offset(dir));
        }
        hit = findNearestSafeCandidate(around, containerPos);
        if (hit != null) return hit;

        // Tier 4: uneven floor around container.
        List<BlockPos> raised = new ArrayList<>();
        for (Direction dir : Direction.Type.HORIZONTAL) {
            addCandidate(raised, unique, containerPos.offset(dir).up());
        }
        return findNearestSafeCandidate(raised, containerPos);
    }

    private void addCandidate(List<BlockPos> list, Set<BlockPos> unique, BlockPos pos) {
        if (pos == null) return;
        if (unique.add(pos)) list.add(pos);
    }

    private void addContainerCandidates(List<BlockPos> candidates, Set<BlockPos> unique, BlockPos anchor, HWDirection hwDir) {
        if (anchor == null) return;

        // Preferred order: behind -> behind diagonals -> sides.
        if (hwDir != null) {
            int fx = Integer.signum(hwDir.directionVec.getX());
            int fz = Integer.signum(hwDir.directionVec.getZ());
            int bx = -fx;
            int bz = -fz;

            HWDirection lateral = hwDir.lateralDirection();
            int sx = Integer.signum(lateral.directionVec.getX());
            int sz = Integer.signum(lateral.directionVec.getZ());

            if (bx != 0 || bz != 0) addCandidate(candidates, unique, anchor.add(bx, 0, bz));

            if (sx != 0 || sz != 0) {
                addCandidate(candidates, unique, anchor.add(bx + sx, 0, bz + sz));
                addCandidate(candidates, unique, anchor.add(bx - sx, 0, bz - sz));
            }

            if (sx != 0 || sz != 0) {
                addCandidate(candidates, unique, anchor.add(sx, 0, sz));
                addCandidate(candidates, unique, anchor.add(-sx, 0, -sz));
            }

            if (bx != 0 && bz != 0) {
                addCandidate(candidates, unique, anchor.add(bx, 0, 0));
                addCandidate(candidates, unique, anchor.add(0, 0, bz));
            }
        }

        // General adjacent fallback around anchor.
        for (Direction dir : Direction.Type.HORIZONTAL) {
            addCandidate(candidates, unique, anchor.offset(dir));
        }
        addCandidate(candidates, unique, anchor.add(1, 0, 1));
        addCandidate(candidates, unique, anchor.add(1, 0, -1));
        addCandidate(candidates, unique, anchor.add(-1, 0, 1));
        addCandidate(candidates, unique, anchor.add(-1, 0, -1));
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

            // Keep an explicit extra clearance from the shared container border so
            // the player hitbox never clips into the placement block.
            double clear = halfWidth + RESTOCK_CONTAINER_CLEARANCE;
            if (dx < 0) tx = Math.max(tx, standBlock.getX() + clear);
            else if (dx > 0) tx = Math.min(tx, standBlock.getX() + 1.0 - clear);
            if (dz < 0) tz = Math.max(tz, standBlock.getZ() + clear);
            else if (dz > 0) tz = Math.min(tz, standBlock.getZ() + 1.0 - clear);
        }

        tx = clamp(tx, minX, maxX);
        tz = clamp(tz, minZ, maxZ);
        return new Vec3d(tx, center.y, tz);
    }

    private BlockPos findNearestSafeCandidate(List<BlockPos> candidates, BlockPos containerPos) {
        if (mc.player == null || candidates == null || candidates.isEmpty()) return null;

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

    public boolean canInteractWithContainerFromCurrentPos() {
        if (mc.player == null || containerTask.taskState == TaskState.DONE) return false;
        if (getBestContainerInteractDistance(containerTask.blockPos) > module.maxReach.get() + 0.35) {
            return false;
        }

        return !mc.player.getBoundingBox().intersects(new Box(containerTask.blockPos));
    }

    private double getBestContainerInteractDistance(BlockPos pos) {
        if (mc.player == null) return Double.MAX_VALUE;
        Vec3d eye = mc.player.getEyePos();
        double best = eye.distanceTo(Vec3d.ofCenter(pos));
        for (Direction side : Direction.values()) {
            double dist = eye.distanceTo(HWUtils.getHitVec(pos, side));
            if (dist < best) best = dist;
        }
        return best;
    }

    public boolean ensureContainerPlacementItemReady() {
        if (mc.player == null) return false;
        if (activeContainerItem == null) return true;

        if (mc.player.getMainHandStack().getItem() == activeContainerItem) return true;

        if (activeContainerHotbarSlot >= 0 && activeContainerHotbarSlot < 9
            && mc.player.getInventory().getStack(activeContainerHotbarSlot).getItem() == activeContainerItem) {
            InvUtils.swap(activeContainerHotbarSlot, false);
            return mc.player.getMainHandStack().getItem() == activeContainerItem;
        }

        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getStack(i).getItem() == activeContainerItem) {
                activeContainerHotbarSlot = i;
                InvUtils.swap(i, false);
                return mc.player.getMainHandStack().getItem() == activeContainerItem;
            }
        }

        int targetHotbar = activeContainerHotbarSlot >= 0 && activeContainerHotbarSlot < 9
            ? activeContainerHotbarSlot
            : findPreferredContainerHotbarSlot();
        if (targetHotbar == -1) {
            int selected = mc.player.getInventory().getSelectedSlot();
            if (canUseContainerHotbarSlot(selected, activeContainerItem)) targetHotbar = selected;
        }
        if (targetHotbar == -1) return false;

        for (int i = 9; i < 36; i++) {
            if (mc.player.getInventory().getStack(i).getItem() != activeContainerItem) continue;
            InvUtils.move().from(i).toHotbar(targetHotbar);
            activeContainerHotbarSlot = targetHotbar;
            InvUtils.swap(targetHotbar, false);
            return mc.player.getMainHandStack().getItem() == activeContainerItem;
        }

        return false;
    }

    private int prepareContainerItemOnHotbar(int sourceSlot) {
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

    private int findPreferredContainerHotbarSlot() {
        if (mc.player == null) return -1;

        // 1) Empty slot first.
        for (int i = 0; i < 9; i++) {
            if (!canUseContainerHotbarSlot(i, activeContainerItem)) continue;
            if (mc.player.getInventory().getStack(i).isEmpty()) return i;
        }

        // 2) Prefer replacing disposable building blocks, not tools.
        for (int i = 0; i < 9; i++) {
            if (!canUseContainerHotbarSlot(i, activeContainerItem)) continue;
            if (isDisposableForSlotReserve(mc.player.getInventory().getStack(i))) return i;
        }

        // 3) Avoid replacing pickaxe if possible.
        for (int i = 0; i < 9; i++) {
            if (!canUseContainerHotbarSlot(i, activeContainerItem)) continue;
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.isEmpty()) return i;
            if (!stack.isIn(ItemTags.PICKAXES)) return i;
        }

        // 4) Last resort: replace a pick only if at least one pickaxe stays in hotbar.
        int pickCount = countHotbarPickaxes();
        if (pickCount > 1) {
            int selected = mc.player.getInventory().getSelectedSlot();
            for (int i = 0; i < 9; i++) {
                if (i == selected) continue;
                if (!canUseContainerHotbarSlot(i, activeContainerItem)) continue;
                ItemStack stack = mc.player.getInventory().getStack(i);
                if (stack.isIn(ItemTags.PICKAXES)) return i;
            }
            if (canUseContainerHotbarSlot(selected, activeContainerItem)) return selected;
        }

        return -1;
    }

    private boolean canUseContainerHotbarSlot(int slot, Item incomingItem) {
        if (slot < 0 || slot >= 9) return false;
        if (module.inventoryHandler == null) return true;
        return module.inventoryHandler.canUseHotbarSlot(slot, incomingItem);
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

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    public BlockPos getCollectingPosition() {
        if (mc.world == null) return null;
        if (containerTask.taskState == TaskState.PICKUP) {
            ItemEntity shulkerDrop = findClosestShulkerDrop();
            if (shulkerDrop != null) return shulkerDrop.getBlockPos();
            if (lastContainerPos != null) return lastContainerPos;
            return containerTask.blockPos;
        }
        return null;
    }
}
