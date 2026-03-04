package com.example.addon.modules.highwaybuilder;

import meteordevelopment.meteorclient.utils.player.Rotations;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

public class BlockBreaker {
    private static final MinecraftClient mc = MinecraftClient.getInstance();
    private static final int BREAK_ROTATE_PRIORITY = 50;
    private static final double MINING_REACH_EPSILON = 0.05;

    private final HighwayBuilder module;
    public BlockPos prePrimedPos = BlockPos.ORIGIN;
    public BlockPos primedPos = BlockPos.ORIGIN;

    public BlockBreaker(HighwayBuilder module) {
        this.module = module;
    }

    public void mineBlock(BlockTask blockTask) {
        if (mc.player == null || mc.world == null || mc.getNetworkHandler() == null) {
            module.inventoryHandler.restoreSilentSwap();
            return;
        }

        BlockState blockState = mc.world.getBlockState(blockTask.blockPos);

        if (blockState.isOf(Blocks.FIRE)) {
            handleFire(blockTask);
            return;
        }

        float hardness = blockState.calcBlockBreakingDelta(mc.player, mc.world, blockTask.blockPos);
        int ticksNeeded = (int) Math.ceil((1.0 / hardness) * module.miningSpeedFactor.get());

        Direction side = HWUtils.getMiningSide(blockTask.blockPos);
        if (side == null) {
            side = HWUtils.getMiningSideFallback(blockTask.blockPos);
        }

        if (blockTask.blockPos.equals(primedPos) && module.instantMine.get()) {
            side = side.getOpposite();
        }

        Vec3d hitVec = HWUtils.getHitVec(blockTask.blockPos, side);
        if (mc.player.getEyePos().distanceTo(hitVec) > module.miningReach.get() + MINING_REACH_EPSILON) {
            blockTask.onStuck();
            module.inventoryHandler.restoreSilentSwap();
            return;
        }

        module.inventoryHandler.lastHitVec = hitVec;

        if (blockTask.ticksMined > ticksNeeded * 1.1 && blockTask.taskState == TaskState.BREAKING) {
            blockTask.updateState(TaskState.BREAK);
            blockTask.ticksMined = 0;
        }

        boolean instantByHardness = ticksNeeded == 1 || mc.player.getAbilities().creativeMode;
        boolean instantByPrimedExploit = module.instantMine.get() && blockTask.blockPos.equals(primedPos);

        if (instantByHardness || instantByPrimedExploit) {
            mineBlockInstant(blockTask, side);
        } else {
            mineBlockNormal(blockTask, side, ticksNeeded);
        }

        blockTask.ticksMined += 1;
    }

    private void mineBlockInstant(BlockTask blockTask, Direction side) {
        module.inventoryHandler.waitTicks = module.breakDelay.get();
        blockTask.updateState(TaskState.PENDING_BREAK);

        sendMiningPackets(blockTask.blockPos, side, true, false, false);

        if (module.multiBreak.get() && module.blocksPerTick.get() > 1) {
            tryMultiBreak(blockTask);
        }

        // Timeout handled in TaskExecutor via stuckTicks
    }

    private void tryMultiBreak(BlockTask blockTask) {
        if (mc.player == null || mc.world == null) return;
        int remainingExtraBreaks = Math.max(0, module.blocksPerTick.get() - 1);
        if (remainingExtraBreaks == 0) return;

        Vec3d eyePos = mc.player.getEyePos();
        Vec3d viewVec = module.inventoryHandler.lastHitVec.subtract(eyePos).normalize();

        for (BlockTask task : module.taskManager.getTasks().values()) {
            if (remainingExtraBreaks <= 0) break;
            if (task.taskState != TaskState.BREAK || task == blockTask) continue;

            BlockState state = mc.world.getBlockState(task.blockPos);
            float hardness = state.calcBlockBreakingDelta(mc.player, mc.world, task.blockPos);
            int ticksNeeded = (int) Math.ceil((1.0 / hardness) * module.miningSpeedFactor.get());
            if (ticksNeeded > 1) continue;

            if (module.inventoryHandler.packetLimiter.size() > module.interactionLimit.get()) return;

            // Check if block is in line of sight along view vector
            Vec3d blockCenter = Vec3d.ofCenter(task.blockPos);
            Vec3d toBlock = blockCenter.subtract(eyePos);
            double dot = toBlock.normalize().dotProduct(viewVec);
            if (dot < 0.95) continue; // Not aligned enough

            double dist = eyePos.distanceTo(blockCenter);
            if (dist > module.miningReach.get() + MINING_REACH_EPSILON) continue;

            task.updateState(TaskState.PENDING_BREAK);
            Direction miningSide = HWUtils.getMiningSide(task.blockPos);
            if (miningSide == null) miningSide = HWUtils.getMiningSideFallback(task.blockPos);
            if (miningSide == null) continue;
            sendMiningPackets(task.blockPos, miningSide, true, false, false);
            remainingExtraBreaks--;
        }
    }

    private void mineBlockNormal(BlockTask blockTask, Direction side, int ticks) {
        if (!module.instantMine.get()
            && mc.player != null && mc.interactionManager != null
            && isWithinBreakReach(blockTask.blockPos, side)) {
            mineBlockVanilla(blockTask, side);
            return;
        }

        if (blockTask.taskState == TaskState.BREAK) {
            blockTask.updateState(TaskState.BREAKING);
            sendMiningPackets(blockTask.blockPos, side, true, false, false);
        } else {
            if (blockTask.ticksMined >= ticks) {
                sendMiningPackets(blockTask.blockPos, side, false, true, false);
            } else {
                sendMiningPackets(blockTask.blockPos, side, false, false, false);
            }
        }
    }

    private void mineBlockVanilla(BlockTask blockTask, Direction side) {
        runWithRotation(blockTask.blockPos, side, () -> {
            if (mc.player == null || mc.interactionManager == null) return;

            if (blockTask.taskState == TaskState.BREAK) {
                blockTask.updateState(TaskState.BREAKING);
                mc.interactionManager.attackBlock(blockTask.blockPos, side);
            } else {
                mc.interactionManager.updateBlockBreakingProgress(blockTask.blockPos, side);
            }

            mc.player.swingHand(Hand.MAIN_HAND);
            restoreSilentSwapSlot();
        });
    }

    private boolean isWithinBreakReach(BlockPos pos, Direction side) {
        if (mc.player == null) return false;
        return mc.player.getEyePos().distanceTo(HWUtils.getHitVec(pos, side))
            <= module.miningReach.get() + MINING_REACH_EPSILON;
    }

    private void handleFire(BlockTask blockTask) {
        Direction side = HWUtils.getMiningSide(blockTask.blockPos);
        if (side == null) {
            side = HWUtils.getMiningSideFallback(blockTask.blockPos);
        }

        module.inventoryHandler.lastHitVec = HWUtils.getHitVec(blockTask.blockPos, side);
        module.inventoryHandler.waitTicks = module.breakDelay.get();
        blockTask.updateState(TaskState.PENDING_BREAK);
        sendMiningPackets(blockTask.blockPos, side, true, false, true);
    }

    public void sendMiningPackets(BlockPos pos, Direction side, boolean start, boolean stop, boolean abort) {
        runWithRotation(pos, side, () -> {
            if (mc.getNetworkHandler() == null || mc.player == null) return;

            module.inventoryHandler.packetLimiter.add(System.currentTimeMillis());

            if (start || module.packetFlood.get()) {
                mc.getNetworkHandler().sendPacket(new PlayerActionC2SPacket(
                    PlayerActionC2SPacket.Action.START_DESTROY_BLOCK, pos, side));
            }
            if (abort) {
                mc.getNetworkHandler().sendPacket(new PlayerActionC2SPacket(
                    PlayerActionC2SPacket.Action.ABORT_DESTROY_BLOCK, pos, side));
            }
            if (stop || module.packetFlood.get()) {
                mc.getNetworkHandler().sendPacket(new PlayerActionC2SPacket(
                    PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK, pos, side));
            }
            mc.player.swingHand(Hand.MAIN_HAND);
            restoreSilentSwapSlot();
        });
    }

    private void runWithRotation(BlockPos pos, Direction side, Runnable action) {
        if (mc.player == null) return;
        Vec3d hitVec = HWUtils.getHitVec(pos, side);
        module.inventoryHandler.lastHitVec = hitVec;
        if (module.rotate.get()) {
            Rotations.rotate(
                Rotations.getYaw(hitVec),
                Rotations.getPitch(hitVec),
                BREAK_ROTATE_PRIORITY,
                action
            );
        } else {
            action.run();
        }
    }

    private void restoreSilentSwapSlot() {
        module.inventoryHandler.restoreSilentSwap();
    }
}
