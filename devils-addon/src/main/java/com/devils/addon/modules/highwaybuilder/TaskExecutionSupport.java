package com.devils.addon.modules.highwaybuilder;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

final class TaskExecutionSupport {
    private static final MinecraftClient mc = MinecraftClient.getInstance();
    private static final double CONTAINER_BREAK_EXTRA_REACH = 0.75;

    private final HighwayBuilder module;

    TaskExecutionSupport(HighwayBuilder module) {
        this.module = module;
    }

    void handleBroken(BlockTask blockTask) {
        if (mc.world == null) return;

        if (mc.world.getBlockState(blockTask.blockPos).getBlock() != Blocks.AIR) {
            blockTask.updateState(TaskState.BREAK);
            return;
        }

        module.inventoryHandler.restoreSilentSwap();
        module.statistics.totalBlocksBroken++;

        for (BlockTask task : module.taskManager.getTasks().values()) {
            if (task.taskState == TaskState.BREAK) task.resetStuck();
        }

        module.statistics.simpleMovingAverageBreaks.add(System.currentTimeMillis());

        BlockTask containerTask = module.containerHandler.containerTask;
        if (blockTask == containerTask) {
            if (containerTask.collect) {
                module.pathfinder.moveState = MovementState.PICKUP;
                blockTask.updateState(TaskState.PICKUP);
            } else {
                blockTask.updateState(TaskState.DONE);
            }
            return;
        }

        if (blockTask.targetBlock == Blocks.AIR) blockTask.updateState(TaskState.DONE);
        else blockTask.updateState(TaskState.PLACE);
    }

    void handlePlaced(BlockTask blockTask) {
        if (mc.world == null) return;

        BlockState currentState = mc.world.getBlockState(blockTask.blockPos);
        Block currentBlock = currentState.getBlock();
        BlockTask containerTask = module.containerHandler.containerTask;

        if ((blockTask.targetBlock == currentBlock || blockTask.isFiller) && !currentState.isReplaceable()) {
            module.statistics.totalBlocksPlaced++;
            module.statistics.simpleMovingAveragePlaces.add(System.currentTimeMillis());

            if (module.dynamicDelay.get() && module.blockPlacer.extraPlaceDelay > 0) {
                module.blockPlacer.extraPlaceDelay /= 2;
            }

            if (blockTask == containerTask) {
                if (containerTask.destroy) {
                    containerTask.updateState(TaskState.BREAK);
                } else {
                    containerTask.updateState(TaskState.OPEN_CONTAINER);
                    module.containerHandler.setOpenDelay(4);
                }
            } else {
                blockTask.updateState(TaskState.DONE);
            }

            for (BlockTask task : module.taskManager.getTasks().values()) {
                if (task.taskState == TaskState.PLACE) task.resetStuck();
            }
        } else if (blockTask.targetBlock == Blocks.AIR && currentBlock != Blocks.AIR) {
            blockTask.updateState(TaskState.BREAK);
        } else {
            blockTask.updateState(TaskState.PLACE);
        }
    }

    void handlePendingBreak(BlockTask blockTask) {
        if (mc.world == null) {
            blockTask.onStuck();
            return;
        }

        Block currentBlock = mc.world.getBlockState(blockTask.blockPos).getBlock();
        if (currentBlock == Blocks.AIR) {
            module.inventoryHandler.waitTicks = module.breakDelay.get();
            blockTask.updateState(TaskState.BROKEN);
        } else {
            blockTask.onStuck();
        }
    }

    void handlePendingPlace(BlockTask blockTask) {
        if (mc.world == null) {
            blockTask.onStuck();
            return;
        }

        BlockState currentState = mc.world.getBlockState(blockTask.blockPos);
        if ((blockTask.targetBlock == currentState.getBlock() || blockTask.isFiller) && !currentState.isReplaceable()) {
            blockTask.updateState(TaskState.PLACED);
        } else if (!currentState.isAir()
            && !currentState.isReplaceable()
            && blockTask.targetBlock != Blocks.AIR
            && blockTask.targetBlock != currentState.getBlock()) {
            blockTask.updateState(TaskState.BREAK);
        } else if ((currentState.isAir() || currentState.isReplaceable()) && blockTask.targetBlock != Blocks.AIR) {
            blockTask.updateState(TaskState.PLACE);
        } else {
            blockTask.onStuck();
        }
    }

    void handleImpossiblePlace() {
        if (mc.player == null) return;

        if (module.pathfinder.shouldBridge()
            && module.pathfinder.moveState != MovementState.RESTOCK
            && mc.player.getEntityPos().distanceTo(Vec3d.ofCenter(module.pathfinder.currentBlockPos)) < 1) {
            module.pathfinder.moveState = MovementState.BRIDGE;
        }
    }

    boolean shouldSkipBreakForRange(BlockTask blockTask) {
        if (isContainerBreakTask(blockTask)) return !isContainerBreakInRange(blockTask.blockPos);
        return !module.taskManager.isWithinActiveMiningBounds(blockTask.blockPos);
    }

    private boolean isContainerBreakTask(BlockTask blockTask) {
        BlockTask containerTask = module.containerHandler.containerTask;
        return blockTask == containerTask
            || (containerTask != null
            && containerTask.taskState != TaskState.DONE
            && blockTask.blockPos.equals(containerTask.blockPos));
    }

    private boolean isContainerBreakInRange(BlockPos pos) {
        if (mc.player == null) return false;

        Vec3d eyePos = mc.player.getEyePos();
        double best = eyePos.distanceTo(Vec3d.ofCenter(pos));
        for (Direction side : Direction.values()) {
            double dist = eyePos.distanceTo(HWUtils.getHitVec(pos, side));
            if (dist < best) best = dist;
        }

        return best <= module.maxReach.get() + CONTAINER_BREAK_EXTRA_REACH + 0.15;
    }
}



