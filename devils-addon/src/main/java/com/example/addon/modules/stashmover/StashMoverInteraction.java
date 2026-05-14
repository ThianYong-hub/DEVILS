package com.example.addon.modules.stashmover;

import com.example.addon.util.runtime.StrictRuntimeLogger;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.world.BlockEntityIterator;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.EnderChestBlock;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.block.enums.ChestType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.ShulkerBoxScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

abstract class StashMoverInteraction extends StashMoverSupport {
    private static final String[] INBOUND_PRIVATE_MARKERS = {
        "whispers",
        "whispers to you",
        "message from",
        "tells you",
        "\u0448\u0435\u043f\u0447\u0435\u0442",
        "\u0441\u043e\u043e\u0431\u0449\u0435\u043d\u0438\u0435 \u043e\u0442",
        "\u043f\u0438\u0448\u0435\u0442 \u0432\u0430\u043c"
    };

    private static final String[] OUTBOUND_PRIVATE_MARKERS = {
        "you whisper to",
        "you tell",
        "\u0432\u044b \u0448\u0435\u043f\u0447\u0435\u0442\u0435",
        "\u0432\u044b \u043f\u0438\u0448\u0435\u0442\u0435"
    };
    protected boolean ensureEnderChestOpen() {
        if (isChestLikeHandler(mc.player.currentScreenHandler) && openedContainerTarget != null) {
            BlockState state = mc.world.getBlockState(openedContainerTarget);
            if (state.getBlock() instanceof EnderChestBlock) return true;
        }

        BlockPos echest = findNearestEnderChest(24);
        if (echest == null) {
            error("No loaded ender chest found while StashMover expected one.");
            toggle();
            return false;
        }

        tryOpenContainer(echest, false);
        return false;
    }

    protected BlockPos findNearestEnderChest(int radius) {
        if (mc.player == null || mc.world == null) return null;

        BlockPos origin = mc.player.getBlockPos();
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;

        for (BlockPos pos : BlockPos.iterateOutwards(origin, radius, Math.max(3, radius / 2), radius)) {
            if (!mc.world.getBlockState(pos).isOf(Blocks.ENDER_CHEST)) continue;

            double dist = Vec3d.ofCenter(pos).squaredDistanceTo(mc.player.getEyePos());
            if (dist < bestDistance) {
                bestDistance = dist;
                best = pos.toImmutable();
            }
        }

        return best;
    }

    protected boolean tryOpenContainer(BlockPos pos, boolean markAsLootSource) {
        if (pos == null || mc.player == null || mc.world == null) return false;

        Vec3d hitVec = interactionPoint(pos);
        if (mc.player.getEyePos().distanceTo(hitVec) > CONTAINER_REACH) {
            StrictRuntimeLogger.logStashMover(
                "container-open",
                "result=out-of-range pos=" + formatBlockPosForFeedback(pos) + " hit=" + formatVecForFeedback(hitVec)
            );
            requestGoal(GoalKind.CONTAINER_INTERACT, freeBlockAroundChest(pos), "container-out-of-range");
            return false;
        }

        Direction side = closestInteractSide(pos);
        BlockHitResult hit = new BlockHitResult(hitVec, side, pos, false);
        StrictRuntimeLogger.logStashMover(
            "container-open",
            "result=attempt pos=" + formatBlockPosForFeedback(pos)
                + " hit=" + formatVecForFeedback(hitVec)
                + " side=" + side
                + " markAsLootSource=" + markAsLootSource
        );

        Rotations.rotate(Rotations.getYaw(hitVec), Rotations.getPitch(hitVec), ACTION_ROTATION_PRIORITY, () -> {
            if (mc.player == null) return;

            ActionResult result = mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hit);
            if (!result.isAccepted() && mc.getNetworkHandler() != null) {
                mc.getNetworkHandler().sendPacket(new PlayerInteractBlockC2SPacket(Hand.MAIN_HAND, hit, 0));
            }

            openedContainerTarget = pos.toImmutable();
            if (markAsLootSource) currentLootSourceChest = pos.toImmutable();
            StrictRuntimeLogger.logStashMover(
                "container-open",
                "result=interact pos=" + formatBlockPosForFeedback(pos)
                    + " accepted=" + result.isAccepted()
                    + " handler="
                    + (mc.player.currentScreenHandler == null
                        ? "<null>"
                        : mc.player.currentScreenHandler.getClass().getSimpleName() + "#" + mc.player.currentScreenHandler.syncId)
            );
        });

        actionCooldownTicks = 4;
        return true;
    }

    protected boolean useBlock(BlockPos pos, Vec3d preciseHit) {
        if (pos == null || preciseHit == null || mc.player == null || mc.world == null) return false;
        if (mc.player.getEyePos().distanceTo(preciseHit) > CONTAINER_REACH) {
            requestGoal(GoalKind.CONTAINER_INTERACT, freeBlockAroundChest(pos), "block-use-out-of-range");
            return false;
        }

        Direction side = closestInteractSide(pos);
        BlockHitResult hit = new BlockHitResult(preciseHit, side, pos, false);
        Rotations.rotate(Rotations.getYaw(preciseHit), Rotations.getPitch(preciseHit), ACTION_ROTATION_PRIORITY, () -> {
            if (mc.player == null) return;

            ActionResult result = mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hit);
            if (!result.isAccepted() && mc.getNetworkHandler() != null) {
                mc.getNetworkHandler().sendPacket(new PlayerInteractBlockC2SPacket(Hand.MAIN_HAND, hit, 0));
            }
        });
        actionCooldownTicks = 2;
        return true;
    }

    protected Direction closestInteractSide(BlockPos pos) {
        Vec3d eye = mc.player.getEyePos();
        Direction best = Direction.UP;
        double bestDistance = Double.MAX_VALUE;

        for (Direction side : Direction.values()) {
            Vec3d hitVec = Vec3d.ofCenter(pos).add(
                side.getOffsetX() * 0.5,
                side.getOffsetY() * 0.5,
                side.getOffsetZ() * 0.5
            );
            double dist = eye.squaredDistanceTo(hitVec);
            if (dist < bestDistance) {
                bestDistance = dist;
                best = side;
            }
        }

        return best;
    }

    protected Vec3d interactionPoint(BlockPos pos) {
        return Vec3d.ofCenter(pos);
    }

    protected Vec3d resolvePearlTarget(BlockPos water, Vec3d chamber) {
        return StashMoverPearlApproach.resolveTarget(pearlTargetPos(), water, chamber);
    }

    protected boolean isPearlTargetObstructed(Vec3d target, BlockPos water, Vec3d chamber) {
        return StashMoverPearlApproach.isTargetObstructed(mc, target, water, chamber);
    }

    protected BlockPos resolvePearlApproachGoal(BlockPos water, Vec3d target, Vec3d chamber) {
        return StashMoverPearlApproach.resolveApproachGoal(mc, water, target, chamber);
    }

    protected boolean isAtPearlApproachGoal(BlockPos goal, Vec3d target, BlockPos water, Vec3d chamber) {
        return StashMoverPearlApproach.isAtApproachGoal(mc, goal, target, water, chamber);
    }

    protected boolean isCurrentPearlThrowPosition(BlockPos water, Vec3d target, Vec3d chamber) {
        return StashMoverPearlApproach.isCurrentThrowPosition(mc, water, target, chamber);
    }

    protected PearlTakeResult takeSinglePearlFromOpenContainer() {
        ScreenHandler handler = mc.player.currentScreenHandler;
        if (!isChestLikeHandler(handler)) return PearlTakeResult.NO_CONTAINER;

        int storageSlots = storageSlotCount(handler);
        int pearlSourceSlot = -1;

        for (int i = 0; i < storageSlots; i++) {
            ItemStack stack = handler.getSlot(i).getStack();
            if (!stack.isEmpty() && stack.isOf(Items.ENDER_PEARL)) {
                pearlSourceSlot = i;
                break;
            }
        }

        if (pearlSourceSlot == -1) {
            StrictRuntimeLogger.logStashMover(
                "pearl-chest-scan",
                "result=no-pearl storageSlots=" + storageSlots + " sample=" + describeStorageSample(handler, storageSlots)
            );
            return PearlTakeResult.NO_PEARL_IN_CONTAINER;
        }

        StashMoverSlotPolicy.Selection selection = StashMoverSlotPolicy.selectPearlHotbarSlot(currentHotbarStacks());
        if (selection.status() == StashMoverSlotPolicy.Status.ALREADY_PRESENT) {
            if (borrowedPearlHotbarSlot < 0) borrowedPearlHotbarSlot = selection.slot();
            return PearlTakeResult.SUCCESS;
        }
        if (!selection.success()) return PearlTakeResult.NO_SAFE_SLOT;

        int targetSlotId = hotbarSlotId(handler, selection.slot());
        borrowedPearlHotbarSlot = selection.slot();
        borrowedPearlChestSlot = pearlSourceSlot;
        pearlChestSwapPending = selection.status() == StashMoverSlotPolicy.Status.REPLACEABLE_SLOT;
        mc.interactionManager.clickSlot(handler.syncId, pearlSourceSlot, 0, SlotActionType.PICKUP, mc.player);
        mc.interactionManager.clickSlot(
            handler.syncId,
            targetSlotId,
            pearlChestSwapPending ? 0 : 1,
            SlotActionType.PICKUP,
            mc.player
        );
        mc.interactionManager.clickSlot(handler.syncId, pearlSourceSlot, 0, SlotActionType.PICKUP, mc.player);
        actionCooldownTicks = 2;
        return PearlTakeResult.SUCCESS;
    }

    protected String describeStorageSample(ScreenHandler handler, int storageSlots) {
        if (handler == null || storageSlots <= 0) return "<empty>";
        StringBuilder builder = new StringBuilder();
        int emitted = 0;
        for (int i = 0; i < storageSlots && emitted < 8; i++) {
            ItemStack stack = handler.getSlot(i).getStack();
            if (stack.isEmpty()) continue;
            if (!builder.isEmpty()) builder.append(';');
            builder.append(i)
                .append('=')
                .append(stack.getItem())
                .append('x')
                .append(stack.getCount());
            emitted++;
        }
        return builder.isEmpty() ? "<empty>" : builder.toString();
    }

    protected List<ItemStack> currentHotbarStacks() {
        List<ItemStack> hotbar = new ArrayList<>(9);
        for (int i = 0; i < 9; i++) hotbar.add(mc.player.getInventory().getStack(i));
        return hotbar;
    }

    protected int findPearlHotbarSlot() {
        FindItemResult result = InvUtils.findInHotbar(Items.ENDER_PEARL);
        return result.found() ? result.slot() : -1;
    }

    protected boolean isInventoryFull() {
        for (int i = 0; i < 36; i++) {
            if (mc.player.getInventory().getStack(i).isEmpty()) return false;
        }
        return true;
    }

    protected boolean isPlayerInventoryEmpty() {
        for (int i = 0; i < 36; i++) {
            if (!mc.player.getInventory().getStack(i).isEmpty()) return false;
        }
        return true;
    }

    protected int playerInventoryPearlCount() {
        int count = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.isOf(Items.ENDER_PEARL)) count += stack.getCount();
        }
        return count;
    }

    protected boolean isStorageFull(ScreenHandler handler) {
        int storageSlots = storageSlotCount(handler);
        for (int i = 0; i < storageSlots; i++) {
            if (handler.getSlot(i).getStack().isEmpty()) return false;
        }
        return true;
    }

    protected int occupiedStorageSlots(ScreenHandler handler) {
        int occupied = 0;
        int storageSlots = storageSlotCount(handler);
        for (int i = 0; i < storageSlots; i++) {
            if (handler.getSlot(i).hasStack()) occupied++;
        }
        return occupied;
    }

    protected boolean isStorageEmpty(ScreenHandler handler) {
        int storageSlots = storageSlotCount(handler);
        for (int i = 0; i < storageSlots; i++) {
            if (!handler.getSlot(i).getStack().isEmpty()) return false;
        }
        return true;
    }

    protected boolean isSourceStorageDepleted(ScreenHandler handler) {
        int storageSlots = storageSlotCount(handler);

        if (onlyShulkers.get()) {
            for (int i = 0; i < storageSlots; i++) {
                ItemStack stack = handler.getSlot(i).getStack();
                if (stack.getItem() instanceof BlockItem blockItem && blockItem.getBlock() instanceof ShulkerBoxBlock) {
                    return false;
                }
            }
            return true;
        }

        for (int i = 0; i < storageSlots; i++) {
            if (!handler.getSlot(i).getStack().isEmpty()) return false;
        }
        return true;
    }

    protected boolean isChestLikeHandler(ScreenHandler handler) {
        return handler instanceof GenericContainerScreenHandler || handler instanceof ShulkerBoxScreenHandler;
    }

    protected boolean isOpenTarget(BlockPos target) {
        return target != null
            && isChestLikeHandler(mc.player.currentScreenHandler)
            && Objects.equals(openedContainerTarget, target);
    }

    protected int storageSlotCount(ScreenHandler handler) {
        if (!isChestLikeHandler(handler)) return 0;
        return Math.max(0, handler.slots.size() - 36);
    }

    protected int hotbarSlotId(ScreenHandler handler, int hotbarSlot) {
        return handler.slots.size() - 9 + hotbarSlot;
    }

    protected void closeHandledScreen() {
        if (mc.player == null) return;
        if (mc.player.currentScreenHandler != null && mc.player.currentScreenHandler.syncId != 0) {
            mc.player.closeHandledScreen();
        }
        openedContainerTarget = null;
    }

    protected boolean readyForChestAction() {
        return readyForChestAction(chestDelay.get());
    }

    protected boolean readyForChestAction(int delayTicks) {
        if (chestActionCooldownTicks > 0) return false;
        chestActionCooldownTicks = Math.max(0, delayTicks);
        return true;
    }

    protected BlockPos findClosestSourceChest(BlockPos lootChest, BlockPos pearlChest) {
        if (mc.player == null || mc.world == null) return null;
        renderedSourceChests.clear();
        if (baritone.isPathing()) return null;

        BlockPos closest = null;
        double bestDistance = Double.MAX_VALUE;

        BlockEntityIterator iterator = new BlockEntityIterator();
        while (iterator.hasNext()) {
            BlockEntity blockEntity = iterator.next();
            if (!(blockEntity instanceof ChestBlockEntity)) continue;

            BlockPos pos = blockEntity.getPos().toImmutable();
            if (blacklistedSourceChests.contains(pos)) continue;
            if (pos.equals(lootChest) || pos.equals(pearlChest)) continue;
            if (isSameChestBlock(pos, lootChest) || isSameChestBlock(pos, pearlChest)) continue;

            BlockState state = mc.world.getBlockState(pos);
            if (!(state.getBlock() instanceof ChestBlock)) continue;

            if (ignoreSingleChest.get() && state.contains(ChestBlock.CHEST_TYPE) && state.get(ChestBlock.CHEST_TYPE) == ChestType.SINGLE) {
                continue;
            }

            double distance = Vec3d.ofCenter(pos).distanceTo(mc.player.getEyePos());
            if (distance > scanDistance.get()) continue;

            renderedSourceChests.add(pos);
            if (distance < bestDistance) {
                bestDistance = distance;
                closest = pos;
            }
        }

        if (closest == null) {
            closest = findClosestSourceChestFallback(lootChest, pearlChest);
            if (closest != null) bestDistance = Vec3d.ofCenter(closest).distanceTo(mc.player.getEyePos());
        }

        if (closest != null && bestDistance > CONTAINER_REACH) requestGoal(GoalKind.SOURCE_CHEST, closest, "approach-source-chest");
        return closest;
    }

    protected boolean hasRemainingEligibleSourceChest(BlockPos lootChest, BlockPos pearlChest) {
        if (mc.world == null) return false;

        BlockEntityIterator iterator = new BlockEntityIterator();
        while (iterator.hasNext()) {
            BlockEntity blockEntity = iterator.next();
            if (!(blockEntity instanceof ChestBlockEntity)) continue;

            BlockPos pos = blockEntity.getPos();
            if (blacklistedSourceChests.contains(pos)) continue;
            if (pos.equals(lootChest) || pos.equals(pearlChest)) continue;
            if (isSameChestBlock(pos, lootChest) || isSameChestBlock(pos, pearlChest)) continue;

            BlockState state = mc.world.getBlockState(pos);
            if (!(state.getBlock() instanceof ChestBlock)) continue;

            if (ignoreSingleChest.get() && state.contains(ChestBlock.CHEST_TYPE) && state.get(ChestBlock.CHEST_TYPE) == ChestType.SINGLE) {
                continue;
            }

            return true;
        }

        return hasRemainingEligibleSourceChestFallback(lootChest, pearlChest);
    }

    private BlockPos findClosestSourceChestFallback(BlockPos lootChest, BlockPos pearlChest) {
        if (mc.player == null || mc.world == null) return null;

        BlockPos closest = null;
        double bestDistance = Double.MAX_VALUE;
        for (BlockPos pos : iterateNearbyChestCandidates()) {
            if (!isEligibleSourceChest(pos, lootChest, pearlChest)) continue;

            double distance = Vec3d.ofCenter(pos).distanceTo(mc.player.getEyePos());
            renderedSourceChests.add(pos.toImmutable());
            if (distance < bestDistance) {
                bestDistance = distance;
                closest = pos.toImmutable();
            }
        }

        return closest;
    }

    private boolean hasRemainingEligibleSourceChestFallback(BlockPos lootChest, BlockPos pearlChest) {
        if (mc.player == null || mc.world == null) return false;

        for (BlockPos pos : iterateNearbyChestCandidates()) {
            if (isEligibleSourceChest(pos, lootChest, pearlChest)) return true;
        }

        return false;
    }

    private Iterable<BlockPos> iterateNearbyChestCandidates() {
        BlockPos origin = mc.player.getBlockPos();
        int horizontalRadius = Math.min(scanDistance.get(), 8);
        int verticalRadius = 4;
        return BlockPos.iterateOutwards(origin, horizontalRadius, verticalRadius, horizontalRadius);
    }

    private boolean isEligibleSourceChest(BlockPos pos, BlockPos lootChest, BlockPos pearlChest) {
        if (pos == null || mc.world == null || mc.player == null) return false;
        if (blacklistedSourceChests.contains(pos)) return false;
        if (pos.equals(lootChest) || pos.equals(pearlChest)) return false;
        if (isSameChestBlock(pos, lootChest) || isSameChestBlock(pos, pearlChest)) return false;

        BlockState state = mc.world.getBlockState(pos);
        if (!(state.getBlock() instanceof ChestBlock)) return false;

        if (ignoreSingleChest.get() && state.contains(ChestBlock.CHEST_TYPE) && state.get(ChestBlock.CHEST_TYPE) == ChestType.SINGLE) {
            return false;
        }

        double distance = Vec3d.ofCenter(pos).distanceTo(mc.player.getEyePos());
        return distance <= scanDistance.get();
    }

    protected BlockPos findAlternativeDestinationChest(BlockPos currentLootChest, BlockPos pearlChest) {
        if (mc.world == null || mc.player == null || currentLootChest == null) return null;

        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        BlockEntityIterator iterator = new BlockEntityIterator();
        while (iterator.hasNext()) {
            BlockEntity blockEntity = iterator.next();
            if (!(blockEntity instanceof ChestBlockEntity)) continue;

            BlockPos pos = blockEntity.getPos().toImmutable();
            if (pos.equals(currentLootChest)) continue;
            if (isSameChestBlock(pos, currentLootChest)) continue;
            if (isMarkedFullDestinationChest(pos)) continue;
            if (isSameChestBlock(pos, pearlChest)) continue;
            if (blacklistedSourceChests.contains(pos)) continue;

            BlockState state = mc.world.getBlockState(pos);
            if (!(state.getBlock() instanceof ChestBlock)) continue;
            double stationDistanceSq = Vec3d.ofCenter(pos).squaredDistanceTo(Vec3d.ofCenter(currentLootChest));
            if (stationDistanceSq > 8.0 * 8.0) continue;

            double playerDistanceSq = Vec3d.ofCenter(pos).squaredDistanceTo(mc.player.getEyePos());
            double score = stationDistanceSq + playerDistanceSq * 0.25;
            if (score < bestScore) {
                bestScore = score;
                best = pos;
            }
        }

        return best;
    }

    protected void markFullDestinationChest(BlockPos pos) {
        if (pos == null || mc.world == null) return;
        fullDestinationChests.add(pos.toImmutable());

        BlockPos connected = connectedChestHalf(pos);
        if (connected != null) fullDestinationChests.add(connected.toImmutable());
    }

    protected boolean isMarkedFullDestinationChest(BlockPos pos) {
        if (pos == null) return false;
        if (fullDestinationChests.contains(pos)) return true;
        for (BlockPos full : fullDestinationChests) {
            if (isSameChestBlock(pos, full)) return true;
        }
        return false;
    }

    protected BlockPos connectedChestHalf(BlockPos pos) {
        if (pos == null || mc.world == null) return null;

        BlockState state = mc.world.getBlockState(pos);
        if (!(state.getBlock() instanceof ChestBlock)) return null;
        if (!state.contains(ChestBlock.CHEST_TYPE) || state.get(ChestBlock.CHEST_TYPE) == ChestType.SINGLE) return null;
        ChestType expectedNeighborType = state.get(ChestBlock.CHEST_TYPE) == ChestType.LEFT ? ChestType.RIGHT : ChestType.LEFT;

        for (Direction direction : Direction.Type.HORIZONTAL) {
            BlockPos neighbor = pos.offset(direction);
            BlockState neighborState = mc.world.getBlockState(neighbor);
            if (!(neighborState.getBlock() instanceof ChestBlock)) continue;
            if (!neighborState.contains(ChestBlock.CHEST_TYPE) || neighborState.get(ChestBlock.CHEST_TYPE) != expectedNeighborType) continue;
            if (state.contains(ChestBlock.FACING)
                && neighborState.contains(ChestBlock.FACING)
                && neighborState.get(ChestBlock.FACING) != state.get(ChestBlock.FACING)) {
                continue;
            }

            return neighbor.toImmutable();
        }

        return null;
    }

    protected boolean isSameChestBlock(BlockPos pos, BlockPos target) {
        if (pos == null || target == null || mc.world == null) return false;
        if (pos.equals(target)) return true;
        if (pos.getManhattanDistance(target) != 1) return false;

        BlockState state = mc.world.getBlockState(pos);
        BlockState targetState = mc.world.getBlockState(target);
        if (!(state.getBlock() instanceof ChestBlock) || !(targetState.getBlock() instanceof ChestBlock)) return false;
        if (!state.contains(ChestBlock.CHEST_TYPE) || !targetState.contains(ChestBlock.CHEST_TYPE)) return false;
        if (state.get(ChestBlock.CHEST_TYPE) == ChestType.SINGLE || targetState.get(ChestBlock.CHEST_TYPE) == ChestType.SINGLE) return false;
        if (state.get(ChestBlock.CHEST_TYPE) == targetState.get(ChestBlock.CHEST_TYPE)) return false;
        return !state.contains(ChestBlock.FACING)
            || !targetState.contains(ChestBlock.FACING)
            || state.get(ChestBlock.FACING) == targetState.get(ChestBlock.FACING);
    }

    protected void blacklistSourceChest(BlockPos pos) {
        if (pos == null || mc.world == null) return;

        blacklistedSourceChests.add(pos.toImmutable());

        BlockState state = mc.world.getBlockState(pos);
        if (!(state.getBlock() instanceof ChestBlock)) return;
        if (!state.contains(ChestBlock.CHEST_TYPE) || state.get(ChestBlock.CHEST_TYPE) == ChestType.SINGLE) return;
        ChestType expectedNeighborType = state.get(ChestBlock.CHEST_TYPE) == ChestType.LEFT ? ChestType.RIGHT : ChestType.LEFT;

        for (Direction direction : Direction.Type.HORIZONTAL) {
            BlockPos neighbor = pos.offset(direction);
            BlockState neighborState = mc.world.getBlockState(neighbor);
            if (!(neighborState.getBlock() instanceof ChestBlock)) continue;
            if (!neighborState.contains(ChestBlock.CHEST_TYPE) || neighborState.get(ChestBlock.CHEST_TYPE) != expectedNeighborType) continue;
            if (state.contains(ChestBlock.FACING)
                && neighborState.contains(ChestBlock.FACING)
                && neighborState.get(ChestBlock.FACING) != state.get(ChestBlock.FACING)) {
                continue;
            }

            blacklistedSourceChests.add(neighbor.toImmutable());
            break;
        }
    }

    protected BlockPos freeBlockAroundChest(BlockPos pos) {
        if (mc.world == null || pos == null) return pos;

        for (Direction direction : Direction.Type.HORIZONTAL) {
            BlockPos offset = pos.offset(direction);
            if (!mc.world.getBlockState(offset).isAir()) continue;
            if (!mc.world.getFluidState(offset).isEmpty()) continue;
            if (!mc.world.getFluidState(offset.up()).isEmpty()) continue;
            if (mc.world.getBlockState(offset.down()).isAir()) continue;
            return offset;
        }

        return pos.up();
    }

    protected BlockPos randomNearbyGoal(int radius) {
        if (mc.player == null) return null;

        int x = mc.player.getBlockX() + ThreadLocalRandom.current().nextInt(-radius, radius + 1);
        int z = mc.player.getBlockZ() + ThreadLocalRandom.current().nextInt(-radius, radius + 1);
        return new BlockPos(x, mc.player.getBlockY(), z);
    }

    protected boolean isPartnerLoadedNearby() {
        if (mc.world == null) return false;

        for (PlayerEntity player : mc.world.getPlayers()) {
            if (player == mc.player) continue;
            if (player.getGameProfile().name().equalsIgnoreCase(partnerName.get())) return true;
        }

        return false;
    }

    protected boolean isLoadRequestFromPartner(String message) {
        return matchesInboundPartnerMessage(message, partnerName.get(), loadMessage.get());
    }

    protected boolean isAckMessageFromPartner(String message) {
        return matchesInboundPartnerMessage(message, partnerName.get(), "RECEIVED MESSAGE");
    }

    static boolean matchesInboundPartnerMessage(String message, String partnerName, String payload) {
        if (message == null || partnerName == null || payload == null) return false;
        if (message.isBlank() || partnerName.isBlank() || payload.isBlank()) return false;

        String prefix = extractChatPrefix(message);
        String body = extractChatBody(message);
        if (prefix.isBlank() || body.isBlank()) return false;

        String normalizedPrefix = normalizeChatFragment(prefix);
        String normalizedBody = normalizeChatFragment(body);
        String normalizedPartner = normalizeChatFragment(partnerName);
        String normalizedPayload = normalizeChatFragment(payload);

        if (!containsChatNameToken(normalizedPrefix, normalizedPartner)) return false;
        if (looksLikeOutboundPrivateMessage(normalizedPrefix)) return false;
        if (!normalizedBody.contains(normalizedPayload)) return false;

        return looksLikeInboundPrivateMessage(normalizedPrefix)
            || hasRhTokenWrappedPayload(normalizedBody, normalizedPayload);
    }

    private static String extractChatPrefix(String message) {
        String trimmed = message == null ? "" : message.trim();
        int split = trimmed.lastIndexOf(':');
        if (split < 0) return trimmed;
        return trimmed.substring(0, split).trim();
    }

    private static String extractChatBody(String message) {
        String trimmed = message == null ? "" : message.trim();
        int split = trimmed.lastIndexOf(':');
        if (split < 0 || split + 1 >= trimmed.length()) return "";
        return trimmed.substring(split + 1).trim();
    }

    private static String normalizeChatFragment(String value) {
        if (value == null) return "";
        return value
            .replace('\u00A0', ' ')
            .trim()
            .toLowerCase(Locale.ROOT)
            .replaceAll("\\s+", " ");
    }

    private static boolean containsChatNameToken(String fragment, String token) {
        if (fragment == null || token == null || fragment.isBlank() || token.isBlank()) return false;

        String[] parts = fragment.split("[^a-z0-9_]+");
        for (String part : parts) {
            if (part.equals(token)) return true;
        }

        return false;
    }

    private static boolean looksLikeInboundPrivateMessage(String prefix) {
        for (String marker : INBOUND_PRIVATE_MARKERS) {
            if (prefix.contains(marker)) return true;
        }
        return false;
    }

    private static boolean looksLikeOutboundPrivateMessage(String prefix) {
        for (String marker : OUTBOUND_PRIVATE_MARKERS) {
            if (prefix.contains(marker)) return true;
        }
        return false;
    }

    private static boolean hasRhTokenWrappedPayload(String body, String payload) {
        int payloadIndex = body.indexOf(payload);
        if (payloadIndex < 0) return false;

        String before = body.substring(0, payloadIndex).replace(" ", "");
        String after = body.substring(payloadIndex + payload.length()).trim();
        String prefixToken = before.length() < 4 ? before : before.substring(before.length() - 4);
        String suffixToken = after.isBlank() ? "" : after.split("\\s+", 2)[0];
        return isUuidToken(prefixToken) && isUuidToken(suffixToken);
    }

    private static boolean isUuidToken(String value) {
        if (value == null || value.length() != 4) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
            if (!hex) return false;
        }
        return true;
    }

    protected void sendChatCommand(String command) {
        if (mc.player == null || mc.player.networkHandler == null || command == null || command.isBlank()) return;
        StrictRuntimeLogger.logStashMover("chat-command", "command=" + command);
        mc.player.networkHandler.sendChatCommand(command);
    }

    protected boolean requestGoal(GoalKind kind, BlockPos pos, String reason) {
        return requestGoal(kind, pos, reason, false);
    }

    protected boolean requestExactGoal(GoalKind kind, BlockPos pos, String reason) {
        return requestGoal(kind, pos, reason, true);
    }

    private boolean requestGoal(GoalKind kind, BlockPos pos, String reason, boolean exact) {
        if (pos == null) return false;

        String goalReason = reason == null || reason.isBlank() ? "unspecified" : reason;
        boolean accepted = exact ? baritone.goToExact(pos) : baritone.goTo(pos);
        if (!accepted) {
            activeGoalKind = GoalKind.NONE;
            activeGoalPos = null;
            lastGoalReason = goalReason + "-failed";
            debugLog(
                "goal request failed kind=" + kind
                    + " pos=" + formatBlockPosForFeedback(pos)
                    + " reason=" + goalReason
                    + " exact=" + exact
            );
            return false;
        }

        activeGoalKind = kind == null ? GoalKind.NONE : kind;
        activeGoalPos = pos.toImmutable();
        lastGoalReason = exact ? goalReason + "-exact" : goalReason;
        return true;
    }

    protected void cancelGoal(String reason) {
        baritone.cancel();
        activeGoalKind = GoalKind.NONE;
        activeGoalPos = null;
        lastGoalReason = reason == null || reason.isBlank() ? "none" : reason;
    }

    protected void debugLog(String message) {
        if (!debugLogging.get() || message == null || message.isBlank()) return;
        StrictRuntimeLogger.logStashMover("debug", "message=" + message);
        if (StrictRuntimeLogger.isEnabled()) return;
        info("[diag] " + message);
    }

    protected void clearPearlFailureState() {
        failedPearlThrows = 0;
        lastPearlFailureReason = "none";
    }

    protected void recordPearlFailure(String reason) {
        lastPearlFailureReason = reason == null || reason.isBlank() ? "unknown" : reason;
        failedPearlThrows++;
        StrictRuntimeLogger.logStashMover("pearl-failure", "reason=" + lastPearlFailureReason + " count=" + failedPearlThrows);
        debugLog("pearl-failure=" + lastPearlFailureReason + " count=" + failedPearlThrows);

        if (failedPearlThrows < maxThrowFailures.get()) return;

        lastRecoveryAction = "pearl-failure-threshold";
        stallRecoveryCount++;
        switch (stallAction.get()) {
            case WARN_ONLY -> warning("Repeated pearl failures detected: " + lastPearlFailureReason);
            case RESET_PHASE -> {
                warning("Resetting StashMover pearl phase after repeated failures: " + lastPearlFailureReason);
                setMoverPhase(MoverPhase.WAIT_FOR_PEARL, "repeated-pearl-failures");
                actionCooldownTicks = 10;
            }
            case DISABLE_MODULE -> {
                error("Disabling StashMover after repeated pearl failures: " + lastPearlFailureReason);
                toggle();
            }
        }
    }

    protected void continueAfterPearlStage(BlockPos lootChest, String reason) {
        closeHandledScreen();
        cancelGoal("pearl-stage-complete");
        if (loadingReturnPearlAfterDeposit) {
            loadingReturnPearlAfterDeposit = false;
            currentLootSourceChest = null;
            renderedSourceChests.clear();
            String command = returnCommand.get() == null || returnCommand.get().isBlank() ? "kill" : returnCommand.get().trim();
            boolean lethalReturn = command.equalsIgnoreCase("kill") || command.toLowerCase(Locale.ROOT).startsWith("kill ");
            boolean disableAfterReturn = disableAfterPartnerSeen;
            boolean disableAfterLethalReturn = disableAfterReturn && lethalReturn;
            StrictRuntimeLogger.logStashMover(
                "pearl-stage-complete",
                "lootChest=" + formatBlockPosForFeedback(lootChest)
                    + " reason=" + reason
                    + " next=" + (disableAfterLethalReturn
                        ? "kill-return-then-disable-at-source"
                        : disableAfterReturn
                        ? "disable-after-return"
                        : (lethalReturn ? "kill-return-to-source" : "command-return-to-source"))
                    + " returnCommand=" + command
            );
            debugLog("return pearl loaded after deposit, dispatching /" + command + " for source return");
            sendChatCommand(command);
            if (disableAfterReturn && !lethalReturn) {
                disableAfterPartnerSeen = false;
                setMoverPhase(MoverPhase.LOOT, "return-command-sent");
                actionCooldownTicks = 10;
                toggle();
                return;
            }
            if (lethalReturn) {
                setMoverPhase(MoverPhase.LOOT, disableAfterLethalReturn ? "return-command-sent-await-death" : "return-command-sent");
                actionCooldownTicks = 10;
            } else {
                setMoverPhase(MoverPhase.LOOT, "return-command-sent");
                actionCooldownTicks = 10;
            }
            return;
        }

        setMoverPhase(MoverPhase.WALKING_TO_CHEST, reason);
        StrictRuntimeLogger.logStashMover(
            "pearl-stage-complete",
            "lootChest=" + formatBlockPosForFeedback(lootChest) + " reason=" + reason
        );
        if (lootChest != null && mc.player != null && mc.player.getEyePos().squaredDistanceTo(Vec3d.ofCenter(lootChest)) > CONTAINER_REACH * CONTAINER_REACH) {
            requestGoal(GoalKind.LOOT_CHEST, freeBlockAroundChest(lootChest), "post-pearl-continuation");
        }
    }
}
