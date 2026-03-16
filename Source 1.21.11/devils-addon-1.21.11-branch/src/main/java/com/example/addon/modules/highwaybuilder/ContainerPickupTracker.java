package com.example.addon.modules.highwaybuilder;

import net.minecraft.block.BlockState;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.ItemEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

final class ContainerPickupTracker {
    private static final MinecraftClient mc = MinecraftClient.getInstance();
    private static final double SHULKER_PICKUP_SEARCH_RADIUS = 32.0;
    private static final int PICKUP_MISS_GRACE_TICKS = 20;

    private final ContainerHandler handler;
    private final HighwayBuilder module;

    ContainerPickupTracker(ContainerHandler handler) {
        this.handler = handler;
        this.module = handler.module;
    }

    void doPickup() {
        if (mc.world == null) return;

        BlockState atContainerPos = mc.world.getBlockState(handler.containerTask.blockPos);
        if (atContainerPos.getBlock() instanceof ShulkerBoxBlock) {
            handler.beginContainerBreak();
            module.pathfinder.moveState = MovementState.RESTOCK;
            return;
        }

        if (hasRecoveredContainerItem()) {
            finishPickupSuccess();
            return;
        }

        ItemEntity shulkerDrop = findClosestShulkerDrop();
        if (shulkerDrop != null) {
            handler.pickupMissTicks = 0;
            if (!handler.inventorySupport.hasSpaceForContainerDrop()) {
                if (!handler.restockTransfer.ensureReservedPickupSlot()) {
                    handler.containerTask.onStuck();
                    return;
                }
            }
            handler.containerTask.resetStuck();
            return;
        }

        handler.pickupMissTicks++;
        if (handler.pickupMissTicks < PICKUP_MISS_GRACE_TICKS) {
            handler.containerTask.resetStuck();
            return;
        }

        if (handler.restockingFortunePickaxe && !handler.inventorySupport.hasFortunePickaxeInInventory()) {
            if (handler.restartFortunePickaxeRestockCycle()) return;
            module.disableWithError("No Fortune III pickaxe found after restock attempt.");
            return;
        }

        handler.containerTask.resetStuck();
    }

    BlockPos getCollectingPosition() {
        if (mc.world == null) return null;
        if (handler.containerTask.taskState == TaskState.PICKUP) {
            ItemEntity shulkerDrop = findClosestShulkerDrop();
            if (shulkerDrop != null) return shulkerDrop.getBlockPos();
            if (handler.placementPlanner.lastContainerPos() != null) return handler.placementPlanner.lastContainerPos();
            return handler.containerTask.blockPos;
        }
        return null;
    }

    private void finishPickupSuccess() {
        if (handler.restockingFortunePickaxe && !handler.inventorySupport.hasFortunePickaxeInInventory()) {
            if (handler.restartFortunePickaxeRestockCycle()) return;
            module.disableWithError("Fortune III pickaxe restock did not complete.");
            return;
        }

        if (handler.restockingFortunePickaxe) {
            handler.inventorySupport.normalizePostPickaxeRestockHotbar();
        }
        handler.clearRestockStandTarget();
        handler.resetPickaxeRestockState();
        handler.containerTask.updateState(TaskState.DONE);
        module.pathfinder.moveState = MovementState.RUNNING;
        handler.grindCycles++;
    }

    private boolean hasRecoveredContainerItem() {
        if (handler.activeContainerItem == null || handler.activeContainerCountBeforePlace < 0) return false;

        boolean recoveredContainer = handler.inventorySupport.countInventoryItem(handler.activeContainerItem)
            >= handler.activeContainerCountBeforePlace;
        if (!recoveredContainer) return false;
        if (handler.restockingFortunePickaxe) return handler.inventorySupport.hasFortunePickaxeInInventory();
        return true;
    }

    private ItemEntity findClosestShulkerDrop() {
        if (mc.world == null) return null;

        BlockPos centerPos = handler.placementPlanner.lastContainerPos() != null
            ? handler.placementPlanner.lastContainerPos()
            : handler.containerTask.blockPos;
        Vec3d center = Vec3d.ofCenter(centerPos);
        Box searchBox = new Box(centerPos).expand(SHULKER_PICKUP_SEARCH_RADIUS);

        ItemEntity closest = null;
        double bestDist = Double.MAX_VALUE;
        for (ItemEntity itemEntity : mc.world.getEntitiesByClass(
            ItemEntity.class,
            searchBox,
            entity -> entity.getStack().getItem() == handler.containerTask.targetBlock.asItem()
        )) {
            double dist = com.example.addon.util.EntityPositionCompat.pos(itemEntity).squaredDistanceTo(center);
            if (dist < bestDist) {
                bestDist = dist;
                closest = itemEntity;
            }
        }
        return closest;
    }
}


