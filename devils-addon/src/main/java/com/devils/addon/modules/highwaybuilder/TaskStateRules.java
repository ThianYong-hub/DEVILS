package com.devils.addon.modules.highwaybuilder;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

final class TaskStateRules {
    private static final MinecraftClient mc = MinecraftClient.getInstance();
    private static final double MINING_REACH_EPSILON = 0.05;
    private static final double VANILLA_STRICT_BREAK_REACH = 4.0;

    private final HighwayBuilder module;

    TaskStateRules(HighwayBuilder module) {
        this.module = module;
    }

    double getBreakReachDistance(BlockPos pos) {
        if (mc.player == null) return Double.MAX_VALUE;

        Vec3d eyePos = mc.player.getEyePos();
        double best = eyePos.distanceTo(Vec3d.ofCenter(pos));
        for (Direction side : Direction.values()) {
            double dist = eyePos.distanceTo(HWUtils.getHitVec(pos, side));
            if (dist < best) best = dist;
        }
        return best;
    }

    boolean isWithinMiningHeight(BlockPos pos) {
        if (mc.player == null) return false;

        int playerY = mc.player.getBlockY();
        return pos.getY() <= playerY + module.miningRangeUp.get()
            && pos.getY() >= playerY - 1;
    }

    boolean isWithinMiningBounds(BlockPos pos) {
        return isWithinMiningReach(pos) && isWithinMiningHeight(pos);
    }

    double getEffectiveMiningReach() {
        double configured = module.miningReach.get();
        double placeReach = module.maxReach.get();
        return Math.min(configured, Math.min(placeReach, VANILLA_STRICT_BREAK_REACH));
    }

    boolean shouldReplaceExistingTask(BlockTask existing, BlockTask next) {
        if (existing == null || next == null) return true;

        if (existing.getStuckTicks() > existing.taskState.stuckTimeout) return true;
        if (next.taskState == TaskState.LIQUID) return true;
        if (existing.targetBlock != next.targetBlock) return true;
        if (existing.taskState == TaskState.DONE || existing.taskState == TaskState.IMPOSSIBLE_PLACE) return true;

        if (existing.taskState != next.taskState) {
            if (isBreakLikeState(next.taskState) && isPlaceLikeState(existing.taskState)) return true;
            if (existing.taskState == TaskState.PLACE && !HWUtils.isPlaceable(existing.blockPos)) return true;
        }

        return false;
    }

    boolean isSequenceDrivenState(TaskState state) {
        return switch (state) {
            case PLACE, LIQUID, IMPOSSIBLE_PLACE -> true;
            default -> false;
        };
    }

    boolean isBreakPhaseState(BlockTask task) {
        if (task == null) return false;

        return switch (task.taskState) {
            case BREAK, BREAKING, PENDING_BREAK -> true;
            case LIQUID -> task.targetBlock == Blocks.AIR;
            default -> false;
        };
    }

    boolean isBreakPhaseActionableNow(BlockTask task) {
        if (mc.player == null || task == null) return false;

        if (task.taskState == TaskState.LIQUID && task.targetBlock == Blocks.AIR) {
            return mc.player.getEyePos().distanceTo(Vec3d.ofCenter(task.blockPos))
                <= module.maxReach.get() + 0.8;
        }

        if (!isWithinMiningBounds(task.blockPos)) return false;
        double reachLimit = getEffectiveMiningReach() + MINING_REACH_EPSILON;
        return getBreakReachDistance(task.blockPos) <= reachLimit;
    }

    boolean isPlacePhaseActionableNow(BlockTask task) {
        if (mc.player == null || task == null) return false;

        double dist = mc.player.getEyePos().distanceTo(Vec3d.ofCenter(task.blockPos));
        double placeReach = module.maxReach.get() + 0.8;
        return switch (task.taskState) {
            case PLACE, PENDING_PLACE -> dist <= placeReach;
            case IMPOSSIBLE_PLACE -> dist <= placeReach && !task.sequence.isEmpty();
            case LIQUID -> task.targetBlock != Blocks.AIR && dist <= placeReach;
            default -> false;
        };
    }

    boolean isPlacePhaseState(BlockTask task) {
        if (task == null) return false;

        return switch (task.taskState) {
            case PLACE, PENDING_PLACE, IMPOSSIBLE_PLACE -> true;
            case LIQUID -> task.targetBlock != Blocks.AIR;
            default -> false;
        };
    }

    void sanitizeBreakTaskState(BlockTask task) {
        if (mc.world == null || task == null || !isBreakPhaseState(task)) return;

        BlockState state = mc.world.getBlockState(task.blockPos);
        boolean isAirLike = state.isAir() || state.isReplaceable();
        boolean hasFluid = !mc.world.getFluidState(task.blockPos).isEmpty();

        if (task.taskState == TaskState.LIQUID && task.targetBlock == Blocks.AIR) {
            if (!hasFluid) {
                if (isAirLike) task.updateState(TaskState.DONE);
                else task.updateState(TaskState.BREAK);
            }
            return;
        }

        if (isAirLike) {
            if (task.targetBlock == Blocks.AIR) task.updateState(TaskState.DONE);
            else task.updateState(TaskState.PLACE);
        }
    }

    boolean checkStuckTimeout(BlockTask blockTask) {
        int timeout = blockTask.taskState.stuckTimeout;
        if (blockTask.getStuckTicks() < timeout) return true;
        if (blockTask.taskState == TaskState.DONE) return true;

        if (blockTask.taskState == TaskState.PENDING_BREAK) {
            blockTask.updateState(TaskState.BREAK);
            return false;
        }

        if (blockTask.taskState == TaskState.PENDING_PLACE) {
            blockTask.updateState(TaskState.PLACE);
            return false;
        }

        switch (blockTask.taskState) {
            case BREAK, BREAKING -> {
                blockTask.miningSide = null;
                blockTask.updateState(TaskState.BREAK);
            }
            case PLACE -> {
                if (module.dynamicDelay.get() && module.blockPlacer.extraPlaceDelay < 10
                    && module.pathfinder.moveState != MovementState.BRIDGE) {
                    module.blockPlacer.extraPlaceDelay += 1;
                }
            }
            case LIQUID -> {
                if (mc.world != null && !mc.world.getFluidState(blockTask.blockPos).isEmpty()) {
                    blockTask.resetStuck();
                    blockTask.updateState(TaskState.LIQUID);
                } else {
                    if (blockTask.targetBlock == Blocks.AIR) blockTask.updateState(TaskState.DONE);
                    else blockTask.updateState(TaskState.PLACE);
                }
            }
            case IMPOSSIBLE_PLACE -> {
                blockTask.sequence.clear();
                blockTask.updateState(TaskState.PLACE);
            }
            case PICKUP -> {
                blockTask.updateState(TaskState.DONE);
                module.pathfinder.moveState = MovementState.RUNNING;
            }
            default -> blockTask.updateState(TaskState.DONE);
        }
        return false;
    }

    private boolean isWithinMiningReach(BlockPos pos) {
        return getBreakReachDistance(pos) <= getEffectiveMiningReach() + MINING_REACH_EPSILON;
    }

    private boolean isBreakLikeState(TaskState state) {
        return switch (state) {
            case BREAK, BREAKING, PENDING_BREAK, LIQUID -> true;
            default -> false;
        };
    }

    private boolean isPlaceLikeState(TaskState state) {
        return switch (state) {
            case PLACE, PENDING_PLACE, IMPOSSIBLE_PLACE, LIQUID -> true;
            default -> false;
        };
    }
}



