package com.devils.addon.modules.highwaybuilder;

import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

public class ContainerHandler {
    private static final MinecraftClient mc = MinecraftClient.getInstance();

    final HighwayBuilder module;
    final ContainerPlacementPlanner placementPlanner;
    final ContainerInventorySupport inventorySupport;
    final ContainerRestockTransfer restockTransfer;
    final ContainerOpenController openController;
    final ContainerPickupTracker pickupTracker;

    public BlockTask containerTask;
    public int grindCycles = 0;

    Item targetItem = null;
    int transferDelay = 0;
    int openAttempts = 0;
    int openDelay = 0;
    int handSwapDelay = 0;
    int pickupMissTicks = 0;
    boolean waitingForScreenClose = false;
    int waitingForScreenCloseTicks = 0;
    int openLoadedWaitTicks = 0;
    int openSideIndex = 0;
    boolean restockingFortunePickaxe = false;
    boolean tookReplacementPickaxe = false;
    int pickaxeConfirmWaitTicks = 0;
    int pickaxeSearchMissTicks = 0;
    Item activeContainerItem = null;
    int activeContainerCountBeforePlace = -1;
    int activeContainerHotbarSlot = -1;

    public ContainerHandler(HighwayBuilder module) {
        this.module = module;
        this.placementPlanner = new ContainerPlacementPlanner(module);
        this.inventorySupport = new ContainerInventorySupport(this);
        this.restockTransfer = new ContainerRestockTransfer(this);
        this.openController = new ContainerOpenController(this);
        this.pickupTracker = new ContainerPickupTracker(this);
        this.containerTask = new BlockTask(BlockPos.ORIGIN, TaskState.DONE, Blocks.AIR);
    }

    public void handleRestock(Item item) {
        if (!module.storageManagement.get()) {
            module.disableWithError("No usable material/tool found and storage management is disabled.");
            return;
        }
        if (containerTask.taskState != TaskState.DONE || mc.player == null) return;

        if (item != Items.DIAMOND_PICKAXE && restockingFortunePickaxe) {
            if (!inventorySupport.hasFortunePickaxeInInventory()) {
                if (restartFortunePickaxeRestockCycle()) return;
                module.disableWithError("No Fortune III pickaxe available for restock workflow.");
                return;
            }
            resetPickaxeRestockState();
        }

        if (item != Items.DIAMOND_PICKAXE && restockingFortunePickaxe && !inventorySupport.hasFortunePickaxeInInventory()) {
            if (restartFortunePickaxeRestockCycle()) return;
            module.disableWithError("No Fortune III pickaxe available for restock workflow.");
            return;
        }

        if (item != Items.DIAMOND_PICKAXE && !inventorySupport.hasFortunePickaxeInInventory()) {
            if (inventorySupport.findShulkerWithFortunePickaxe() != -1) {
                handleFortunePickaxeRestock();
                return;
            }
            module.disableWithError("No Fortune III pickaxe available for restock workflow.");
            return;
        }

        if (item == Items.ENDER_CHEST) {
            int desiredEnderChestCount = restockTransfer.getDesiredEnderChestCount();
            if (inventorySupport.countInventoryItem(Items.ENDER_CHEST) >= desiredEnderChestCount) return;
        }

        clearRestockStandTarget();
        if (!inventorySupport.hasSpaceForContainerDrop() && !restockTransfer.ensureReservedPickupSlot()) {
            module.disableWithError("Need at least one free inventory slot for shulker pickup.");
            return;
        }

        int shulkerSlot = inventorySupport.findShulkerWithItem(item);
        if (shulkerSlot == -1) {
            module.disableWithError("No shulker box found containing " + item.getName().getString());
            return;
        }

        resetPickaxeRestockState();
        targetItem = item;
        startRestockCycle(shulkerSlot, item);
    }

    public void handleFortunePickaxeRestock() {
        if (!module.storageManagement.get()) {
            module.disableWithError("No usable material/tool found and storage management is disabled.");
            return;
        }
        if (containerTask.taskState != TaskState.DONE || mc.player == null) return;

        if (inventorySupport.hasFortunePickaxeInInventory()) {
            resetPickaxeRestockState();
            return;
        }

        clearRestockStandTarget();
        if (!inventorySupport.hasSpaceForContainerDrop() && !restockTransfer.ensureReservedPickupSlot()) {
            module.disableWithError("Need at least one free inventory slot for shulker pickup.");
            return;
        }

        int shulkerSlot = inventorySupport.findShulkerWithFortunePickaxe();
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

    boolean restartFortunePickaxeRestockCycle() {
        if (mc.player == null) return false;

        int shulkerSlot = inventorySupport.findShulkerWithFortunePickaxe();
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

    void resetPickaxeRestockState() {
        restockingFortunePickaxe = false;
        tookReplacementPickaxe = false;
        pickaxeConfirmWaitTicks = 0;
        pickaxeSearchMissTicks = 0;
        openLoadedWaitTicks = 0;
        activeContainerItem = null;
        activeContainerCountBeforePlace = -1;
        activeContainerHotbarSlot = -1;
    }

    private boolean startRestockCycle(int shulkerSlot, Item item) {
        if (mc.player == null) return false;

        ItemStack shulkerStack = mc.player.getInventory().getStack(shulkerSlot);
        if (!(shulkerStack.getItem() instanceof BlockItem shulkerItem)) {
            module.disableWithError("Selected restock container is not a shulker box.");
            return false;
        }

        BlockPos containerPos = getRemotePos();
        if (containerPos == null) {
            module.disableWithError("No valid position found for container placement.");
            return false;
        }

        activeContainerItem = shulkerStack.getItem();
        activeContainerCountBeforePlace = inventorySupport.countInventoryItem(activeContainerItem);
        activeContainerHotbarSlot = inventorySupport.prepareContainerItemOnHotbar(shulkerSlot);
        if (activeContainerHotbarSlot == -1) {
            module.disableWithError("Failed to prepare shulker in hotbar for placement.");
            return false;
        }

        placementPlanner.setLastContainerPos(containerPos);
        BlockPos standBlock = selectRestockStandBlock(containerPos);
        if (standBlock == null) {
            module.disableWithError("No safe standing position found for container restock.");
            return false;
        }

        Vec3d standPos = getSafeRestockPoint(standBlock, containerPos);
        if (standPos == null) {
            module.disableWithError("Failed to compute safe restock center.");
            return false;
        }

        placementPlanner.setRestockStandBlock(standBlock);
        placementPlanner.setRestockStandPos(standPos);

        containerTask = new BlockTask(containerPos, TaskState.PLACE, shulkerItem.getBlock());
        containerTask.item = item;
        containerTask.collect = true;
        containerTask.destroy = false;

        transferDelay = 0;
        openAttempts = 0;
        openDelay = 0;
        handSwapDelay = 0;
        pickupMissTicks = 0;
        waitingForScreenClose = false;
        waitingForScreenCloseTicks = 0;
        openLoadedWaitTicks = 0;
        openSideIndex = 0;

        module.pathfinder.moveState = MovementState.RESTOCK;
        return true;
    }

    public void doOpenContainer() {
        openController.doOpenContainer();
    }

    public void doRestock() {
        restockTransfer.doRestock();
    }

    public void doPickup() {
        pickupTracker.doPickup();
    }

    void closeAndBreak() {
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

    void beginContainerBreak() {
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

    public int findShulkerWithItem(Item item) {
        return inventorySupport.findShulkerWithItem(item);
    }

    public int findShulkerWithFortunePickaxe() {
        return inventorySupport.findShulkerWithFortunePickaxe();
    }

    public boolean ensureContainerPlacementItemReady() {
        return inventorySupport.ensureContainerPlacementItemReady();
    }

    private BlockPos getRemotePos() {
        return placementPlanner.getRemotePos();
    }

    private BlockPos getRemotePos(BlockPos avoidPos) {
        return placementPlanner.getRemotePos(avoidPos);
    }

    public Vec3d getRestockStandPos() {
        if (mc.player == null) return Vec3d.ZERO;

        if (containerTask.taskState == TaskState.DONE) {
            clearRestockStandTarget();
            BlockPos anchor = module.pathfinder != null ? module.pathfinder.currentBlockPos : mc.player.getBlockPos();
            return Vec3d.ofCenter(anchor);
        }

        BlockPos containerPos = containerTask.blockPos;
        BlockPos standBlock = placementPlanner.restockStandBlock();
        Vec3d standPos = placementPlanner.restockStandPos();

        if (standBlock == null || !isSafeRestockStandBlock(standBlock, containerPos)) {
            standBlock = selectRestockStandBlock(containerPos);
            standPos = null;
        }
        if (standBlock == null) {
            standBlock = selectFallbackStandBlock(containerPos);
            standPos = null;
        }
        if (standBlock == null && tryRelocateContainerPlacement()) {
            containerPos = containerTask.blockPos;
            standBlock = selectRestockStandBlock(containerPos);
            standPos = null;
            if (standBlock == null) standBlock = selectFallbackStandBlock(containerPos);
        }
        if (standBlock == null) return Vec3d.ofCenter(mc.player.getBlockPos());

        if (standPos == null) {
            standPos = getSafeRestockPoint(standBlock, containerPos);
        }

        placementPlanner.setRestockStandBlock(standBlock);
        placementPlanner.setRestockStandPos(standPos);
        return standPos == null ? Vec3d.ofCenter(standBlock) : standPos;
    }

    void clearRestockStandTarget() {
        placementPlanner.clearRestockStandTarget();
    }

    public void invalidateRestockStandTarget() {
        clearRestockStandTarget();
    }

    public boolean tryRelocateContainerPlacement() {
        if (mc.player == null || mc.world == null) return false;
        if (containerTask == null || containerTask.taskState == TaskState.DONE) return false;

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
        placementPlanner.setLastContainerPos(newPos);

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
        return placementPlanner.selectRestockStandBlock(containerPos);
    }

    private boolean isSafeRestockStandBlock(BlockPos standBlock, BlockPos containerPos) {
        return placementPlanner.isSafeRestockStandBlock(standBlock, containerPos);
    }

    private BlockPos selectFallbackStandBlock(BlockPos containerPos) {
        return placementPlanner.selectFallbackStandBlock(containerPos);
    }

    private Vec3d getSafeRestockPoint(BlockPos standBlock, BlockPos containerPos) {
        return placementPlanner.getSafeRestockPoint(standBlock, containerPos);
    }

    double getBestContainerInteractDistance(BlockPos pos) {
        return placementPlanner.getBestContainerInteractDistance(pos);
    }

    public boolean canInteractWithContainerFromCurrentPos() {
        if (mc.player == null || containerTask.taskState == TaskState.DONE) return false;
        if (getBestContainerInteractDistance(containerTask.blockPos) > module.maxReach.get() + 0.35) return false;
        return !mc.player.getBoundingBox().intersects(new Box(containerTask.blockPos));
    }

    public BlockPos getCollectingPosition() {
        return pickupTracker.getCollectingPosition();
    }
}



