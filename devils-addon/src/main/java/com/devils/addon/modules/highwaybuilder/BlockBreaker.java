package com.devils.addon.modules.highwaybuilder;

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
    private static final double CONTAINER_BREAK_EXTRA_REACH = 0.75;

    private final HighwayBuilder module;

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

        double reachLimit = getReachLimit(blockTask);
        Direction side = resolveMiningSide(blockTask, reachLimit);
        if (side == null) {
            blockTask.onStuck();
            module.inventoryHandler.restoreSilentSwap();
            return;
        }

        Vec3d hitVec = HWUtils.getHitVec(blockTask.blockPos, side);
        if (mc.player.getEyePos().distanceTo(hitVec) > reachLimit + MINING_REACH_EPSILON) {
            blockTask.onStuck();
            module.inventoryHandler.restoreSilentSwap();
            return;
        }

        module.inventoryHandler.lastHitVec = hitVec;

        // Temporary container (shulker) breaks are latency-sensitive.
        // Force vanilla continuous breaking to avoid packet-mode desync.
        if (isContainerBreakTask(blockTask)
            && mc.interactionManager != null
            && isWithinBreakReach(blockTask, blockTask.blockPos, side)) {
            mineBlockVanilla(blockTask, side);
            blockTask.ticksMined += 1;
            return;
        }

        if (blockTask.ticksMined > ticksNeeded * 1.1 && blockTask.taskState == TaskState.BREAKING) {
            blockTask.updateState(TaskState.BREAK);
            blockTask.ticksMined = 0;
        }

        mineBlockNormal(blockTask, side, ticksNeeded);

        blockTask.ticksMined += 1;
    }

    private void mineBlockNormal(BlockTask blockTask, Direction side, int ticks) {
        if (blockTask.taskState == TaskState.BREAK) {
            blockTask.updateState(TaskState.BREAKING);
            sendMiningPackets(blockTask.blockPos, side, true, false, false);
        } else {
            if (blockTask.ticksMined >= ticks) {
                module.inventoryHandler.waitTicks = module.breakDelay.get();
                blockTask.updateState(TaskState.PENDING_BREAK);
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
                boolean started = mc.interactionManager.attackBlock(blockTask.blockPos, side);
                if (!started) {
                    // Keep current side for container break and retry vanilla next tick.
                    blockTask.onStuck();
                    if (!isContainerBreakTask(blockTask)) restoreSilentSwapSlot();
                    return;
                }
                blockTask.updateState(TaskState.BREAKING);
            } else {
                boolean progressed = mc.interactionManager.updateBlockBreakingProgress(blockTask.blockPos, side);
                if (!progressed) {
                    if (isContainerBreakTask(blockTask)) {
                        // For shulker break keep the same side and restart vanilla hold immediately.
                        boolean restarted = mc.interactionManager.attackBlock(blockTask.blockPos, side);
                        if (!restarted) blockTask.onStuck();
                    } else {
                        // Lost break context (rotation/lag/face invalid), restart cleanly.
                        blockTask.miningSide = null;
                        blockTask.updateState(TaskState.BREAK);
                        blockTask.onStuck();
                    }
                    if (!isContainerBreakTask(blockTask)) restoreSilentSwapSlot();
                    return;
                }
            }

            mc.player.swingHand(Hand.MAIN_HAND);
            if (!isContainerBreakTask(blockTask)) restoreSilentSwapSlot();
        });
    }

    private boolean isWithinBreakReach(BlockTask blockTask, BlockPos pos, Direction side) {
        if (mc.player == null) return false;
        return mc.player.getEyePos().distanceTo(HWUtils.getHitVec(pos, side))
            <= getReachLimit(blockTask) + MINING_REACH_EPSILON;
    }

    private Direction resolveMiningSide(BlockTask blockTask, double reachLimit) {
        Direction side = blockTask.miningSide;

        if (isContainerBreakTask(blockTask)) {
            // Keep a deterministic side for the whole shulker break cycle.
            if (side == null || blockTask.taskState == TaskState.BREAK) {
                Direction best = findClosestSideWithinReach(blockTask.blockPos, reachLimit + MINING_REACH_EPSILON);
                if (best != null) side = best;
                else side = Direction.UP;
            }
            blockTask.miningSide = side;
            return side;
        }

        // Re-evaluate side at BREAK start; keep it stable while BREAKING/PENDING.
        if (side == null || blockTask.taskState == TaskState.BREAK) {
            side = HWUtils.getMiningSide(blockTask.blockPos);
            if (side == null) side = HWUtils.getMiningSideFallback(blockTask.blockPos);
        }

        double currentDist = mc.player.getEyePos().distanceTo(HWUtils.getHitVec(blockTask.blockPos, side));
        if (currentDist > reachLimit + MINING_REACH_EPSILON) {
            Direction bestSide = findClosestSideWithinReach(blockTask.blockPos, reachLimit + MINING_REACH_EPSILON);
            if (bestSide != null) side = bestSide;
        }

        blockTask.miningSide = side;
        return side;
    }

    private boolean isContainerBreakTask(BlockTask blockTask) {
        return module.containerHandler != null && blockTask == module.containerHandler.containerTask;
    }

    private double getReachLimit(BlockTask blockTask) {
        if (module.containerHandler == null) return Math.min(module.miningReach.get(), module.maxReach.get());
        return blockTask == module.containerHandler.containerTask
            ? module.maxReach.get() + CONTAINER_BREAK_EXTRA_REACH
            : (module.taskManager != null
                ? module.taskManager.getEffectiveMiningReach()
                : Math.min(module.miningReach.get(), module.maxReach.get()));
    }

    private Direction findClosestSideWithinReach(BlockPos pos, double reachLimit) {
        if (mc.player == null) return null;

        Vec3d eyePos = mc.player.getEyePos();
        Direction best = null;
        double bestDist = Double.MAX_VALUE;

        for (Direction candidate : Direction.values()) {
            double dist = eyePos.distanceTo(HWUtils.getHitVec(pos, candidate));
            if (dist > reachLimit) continue;
            if (dist < bestDist) {
                bestDist = dist;
                best = candidate;
            }
        }

        return best;
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



