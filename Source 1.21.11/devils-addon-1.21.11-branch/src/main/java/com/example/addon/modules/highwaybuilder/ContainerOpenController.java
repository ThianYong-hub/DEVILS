package com.example.addon.modules.highwaybuilder;

import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;

final class ContainerOpenController {
    private static final MinecraftClient mc = MinecraftClient.getInstance();
    private static final int HAND_SWAP_OPEN_DELAY_TICKS = 2;

    private final ContainerHandler handler;
    private final HighwayBuilder module;

    ContainerOpenController(ContainerHandler handler) {
        this.handler = handler;
        this.module = handler.module;
    }

    void doOpenContainer() {
        if (mc.player == null || mc.world == null) return;

        module.pathfinder.moveState = MovementState.RESTOCK;

        if (!handler.containerTask.isOpen && mc.player.currentScreenHandler != null
            && mc.player.currentScreenHandler.syncId != 0) {
            handler.containerTask.isOpen = true;
        }

        if (handler.containerTask.isOpen) {
            if (!handler.containerTask.isLoaded) {
                handler.openLoadedWaitTicks++;
                if (mc.player.currentScreenHandler != null
                    && mc.player.currentScreenHandler.syncId != 0
                    && handler.openLoadedWaitTicks > 8) {
                    handler.containerTask.isLoaded = true;
                }
                handler.containerTask.resetStuck();
                return;
            }
            handler.openAttempts = 0;
            handler.openLoadedWaitTicks = 0;
            handler.openSideIndex = 0;
            handler.containerTask.updateState(TaskState.RESTOCK);
            return;
        }

        if (module.pathfinder != null
            && !module.pathfinder.isCenteredForRestock()
            && !handler.canInteractWithContainerFromCurrentPos()) {
            return;
        }

        if (handler.openDelay > 0) {
            handler.openDelay--;
            return;
        }

        if (handler.handSwapDelay > 0) {
            handler.handSwapDelay--;
            return;
        }

        if (handler.getBestContainerInteractDistance(handler.containerTask.blockPos) > module.maxReach.get() + 0.35) {
            return;
        }

        Block currentBlock = mc.world.getBlockState(handler.containerTask.blockPos).getBlock();
        if (!(currentBlock instanceof ShulkerBoxBlock)) {
            handler.openDelay = 2;
            handler.openAttempts++;
            if (handler.openAttempts > 30) {
                if (handler.restockingFortunePickaxe && !handler.inventorySupport.hasFortunePickaxeInInventory()) {
                    if (handler.restartFortunePickaxeRestockCycle()) {
                        handler.openAttempts = 0;
                        return;
                    }
                    module.disableWithError("Failed to continue Fortune III pickaxe restock.");
                    return;
                }
                handler.clearRestockStandTarget();
                BlockPos probePos = handler.placementPlanner.lastContainerPos() != null
                    ? handler.placementPlanner.lastContainerPos()
                    : handler.containerTask.blockPos;
                if (mc.world.getBlockState(probePos).getBlock() instanceof ShulkerBoxBlock) {
                    handler.beginContainerBreak();
                    module.pathfinder.moveState = MovementState.RESTOCK;
                } else {
                    handler.containerTask.updateState(TaskState.PICKUP);
                    module.pathfinder.moveState = MovementState.PICKUP;
                }
                handler.openAttempts = 0;
            }
            return;
        }

        HandSafetyResult handSafety = ensureSafeMainHandForContainerInteraction();
        if (handSafety == HandSafetyResult.WAIT_SWAP_APPLY) return;
        if (handSafety == HandSafetyResult.FAILED) {
            handler.containerTask.onStuck();
            return;
        }

        Direction side = getContainerInteractSide(handler.containerTask.blockPos);
        if (side == null) side = Direction.UP;
        Vec3d hitVec = HWUtils.getHitVec(handler.containerTask.blockPos, side);
        BlockHitResult hitResult = new BlockHitResult(hitVec, side, handler.containerTask.blockPos, false);

        Runnable interact = () -> {
            if (mc.player == null) return;

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

        handler.openDelay = 6;
        handler.openAttempts++;
        if (handler.openAttempts % 8 == 0) {
            handler.invalidateRestockStandTarget();
        }
        if (handler.openAttempts > 25) {
            if (handler.restockingFortunePickaxe && !handler.inventorySupport.hasFortunePickaxeInInventory()) {
                handler.invalidateRestockStandTarget();
                handler.openDelay = 4;
                handler.openAttempts = 0;
                handler.containerTask.resetStuck();
                return;
            }

            handler.beginContainerBreak();
            handler.openAttempts = 0;
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

        Direction side = visible.get(handler.openSideIndex % visible.size());
        handler.openSideIndex++;
        return side;
    }

    private HandSafetyResult ensureSafeMainHandForContainerInteraction() {
        if (mc.player == null) return HandSafetyResult.FAILED;

        if (!isBlockedContainerInteractItem(mc.player.getMainHandStack())) {
            return HandSafetyResult.READY;
        }

        int selected = mc.player.getInventory().getSelectedSlot();
        boolean swapped = false;

        int safeHotbarSlot = findSafeHotbarSlot(selected);
        if (safeHotbarSlot != -1) {
            swapped = selectHotbarSlot(safeHotbarSlot);
        }

        if (!swapped) {
            int safeInventorySlot = findSafeInventorySlot();
            if (safeInventorySlot != -1) {
                int emptyHotbar = findEmptyHotbarSlot(selected);
                Item safeItem = mc.player.getInventory().getStack(safeInventorySlot).getItem();
                int targetHotbarSlot = emptyHotbar != -1 ? emptyHotbar
                    : (handler.inventorySupport.canUseHotbarSlot(selected, safeItem) ? selected : -1);
                if (targetHotbarSlot == -1) return HandSafetyResult.FAILED;
                InvUtils.move().from(safeInventorySlot).toHotbar(targetHotbarSlot);
                if (targetHotbarSlot != selected) {
                    swapped = selectHotbarSlot(targetHotbarSlot);
                } else {
                    swapped = true;
                }
            }
        }

        if (!swapped) {
            int emptyHotbar = findEmptyHotbarSlot(selected);
            if (emptyHotbar != -1) {
                swapped = selectHotbarSlot(emptyHotbar);
            }
        }

        if (!swapped) return HandSafetyResult.FAILED;

        handler.handSwapDelay = HAND_SWAP_OPEN_DELAY_TICKS;
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
            if (!handler.inventorySupport.canUseHotbarSlot(i, null)) continue;
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

    private enum HandSafetyResult {
        READY,
        WAIT_SWAP_APPLY,
        FAILED
    }
}


