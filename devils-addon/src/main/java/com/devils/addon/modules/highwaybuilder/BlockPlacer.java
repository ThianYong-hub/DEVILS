package com.devils.addon.modules.highwaybuilder;

import meteordevelopment.meteorclient.utils.player.Rotations;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.Set;

public class BlockPlacer {
    private static final MinecraftClient mc = MinecraftClient.getInstance();
    private static final double PLACE_REACH_EPSILON = 0.8;

    private final HighwayBuilder module;
    public int extraPlaceDelay = 0;

    // Blocks that require sneaking to place against
    private static final Set<Block> BLACKLIST_BLOCKS = Set.of(
        Blocks.CRAFTING_TABLE, Blocks.CHEST, Blocks.TRAPPED_CHEST,
        Blocks.ENDER_CHEST, Blocks.ANVIL, Blocks.CHIPPED_ANVIL,
        Blocks.DAMAGED_ANVIL, Blocks.ENCHANTING_TABLE, Blocks.FURNACE,
        Blocks.BLAST_FURNACE, Blocks.SMOKER, Blocks.BREWING_STAND,
        Blocks.HOPPER, Blocks.DROPPER, Blocks.DISPENSER,
        Blocks.BARREL, Blocks.LOOM, Blocks.CARTOGRAPHY_TABLE,
        Blocks.GRINDSTONE, Blocks.SMITHING_TABLE, Blocks.STONECUTTER,
        Blocks.LECTERN, Blocks.BEACON, Blocks.BELL,
        Blocks.NOTE_BLOCK, Blocks.JUKEBOX
    );

    public BlockPlacer(HighwayBuilder module) {
        this.module = module;
    }

    public void placeBlock(BlockTask blockTask) {
        if (mc.player == null || mc.getNetworkHandler() == null) {
            module.inventoryHandler.restoreSilentSwap();
            return;
        }

        // Liquid plugs should always use a direct solid neighbor as anchor.
        // This avoids deep-sequence anchors landing on air and spinning in
        // retry loops without actual placement.
        if (blockTask.taskState == TaskState.LIQUID) {
            PlaceInfo directAnchor = findDirectLiquidAnchor(blockTask.blockPos);
            if (directAnchor != null) {
                placeBlockNormal(blockTask, directAnchor.pos(), directAnchor.side());
                return;
            }
        }

        if (blockTask.sequence.isEmpty()) {
            // No valid anchor right now. Keep task alive and retry after sequence refresh.
            blockTask.onStuck();
            blockTask.updateState(TaskState.IMPOSSIBLE_PLACE);
            module.inventoryHandler.restoreSilentSwap();
            return;
        }

        PlaceInfo last = blockTask.sequence.get(blockTask.sequence.size() - 1);
        placeBlockNormal(blockTask, last.pos(), last.side());
    }

    private void placeBlockNormal(BlockTask blockTask, BlockPos placePos, Direction side) {
        if (mc.world == null || mc.getNetworkHandler() == null || mc.player == null) {
            module.inventoryHandler.restoreSilentSwap();
            return;
        }

        BlockPos targetPos = placePos.offset(side);
        if (!targetPos.equals(blockTask.blockPos)) {
            blockTask.onStuck();
            module.inventoryHandler.restoreSilentSwap();
            return;
        }

        boolean liquidCell = !mc.world.getFluidState(blockTask.blockPos).isEmpty();
        if (!HWUtils.isPlaceable(blockTask.blockPos)
            && !(blockTask.taskState == TaskState.LIQUID && liquidCell)) {
            blockTask.onStuck();
            module.inventoryHandler.restoreSilentSwap();
            return;
        }

        BlockState currentBlock = mc.world.getBlockState(placePos);

        boolean needSneak = BLACKLIST_BLOCKS.contains(currentBlock.getBlock());

        boolean wasSneaking = mc.player.isSneaking();
        if (needSneak && !wasSneaking) {
            mc.player.setSneaking(true);
        }

        // Raw packet вЂ” bypasses client-side reach/placement checks (needed for wide highways)
        Vec3d hitVec = HWUtils.getHitVec(placePos, side);
        if (mc.player.getEyePos().distanceTo(hitVec) > module.maxReach.get() + PLACE_REACH_EPSILON) {
            blockTask.onStuck();
            if (needSneak && !wasSneaking) mc.player.setSneaking(false);
            module.inventoryHandler.restoreSilentSwap();
            return;
        }

        int delay = module.dynamicDelay.get()
            ? module.placeDelay.get() + extraPlaceDelay
            : module.placeDelay.get();
        module.inventoryHandler.waitTicks = delay;
        blockTask.updateState(TaskState.PENDING_PLACE);

        // Capture sneak state for use in callback
        boolean finalNeedSneak = needSneak;
        boolean finalWasSneaking = wasSneaking;

        Runnable doPlace = () -> {
            BlockHitResult hitResult = new BlockHitResult(hitVec, side, placePos, false);
            mc.getNetworkHandler().sendPacket(new PlayerInteractBlockC2SPacket(Hand.MAIN_HAND, hitResult, 0));
            mc.player.swingHand(Hand.MAIN_HAND);

            if (finalNeedSneak && !finalWasSneaking) {
                mc.player.setSneaking(false);
            }

            // Silent swap: restore previous slot after placing
            module.inventoryHandler.restoreSilentSwap();
        };

        if (module.rotate.get()) {
            int rotatePriority = blockTask.taskState == TaskState.LIQUID ? 100 : 50;
            Rotations.rotate(Rotations.getYaw(hitVec), Rotations.getPitch(hitVec), rotatePriority, doPlace);
        } else {
            doPlace.run();
        }
    }

    private PlaceInfo findDirectLiquidAnchor(BlockPos targetPos) {
        if (mc.world == null || mc.player == null || targetPos == null) return null;

        Vec3d eyePos = mc.player.getEyePos();
        PlaceInfo best = null;
        double bestDist = Double.MAX_VALUE;
        double maxReach = module.maxReach.get() + PLACE_REACH_EPSILON;

        for (Direction dir : Direction.values()) {
            BlockPos supportPos = targetPos.offset(dir);
            Direction clickSide = dir.getOpposite();
            BlockState supportState = mc.world.getBlockState(supportPos);
            if (supportState.isAir() || supportState.isReplaceable()) continue;

            Vec3d hitVec = HWUtils.getHitVec(supportPos, clickSide);
            double dist = eyePos.distanceTo(hitVec);
            if (dist > maxReach) continue;

            if (dist < bestDist) {
                bestDist = dist;
                best = new PlaceInfo(supportPos, clickSide);
            }
        }

        return best;
    }
}



