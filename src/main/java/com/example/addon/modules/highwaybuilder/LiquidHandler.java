package com.example.addon.modules.highwaybuilder;

import net.minecraft.block.Block;
import net.minecraft.client.MinecraftClient;
import net.minecraft.fluid.FluidState;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

public class LiquidHandler {
    private static final MinecraftClient mc = MinecraftClient.getInstance();
    private static final double LIQUID_REACH_EPSILON = 0.2;
    private static final double PRE_SCAN_FORWARD_PADDING = 2.0;
    private static final double PRE_SCAN_LATERAL_PADDING = 2.0;

    private final HighwayBuilder module;

    public LiquidHandler(HighwayBuilder module) {
        this.module = module;
    }

    /**
     * Checks for liquid blocks adjacent to a break task and schedules only one best liquid
     * mitigation task (source-first, then closest threat to the player).
     */
    public boolean handleLiquid(BlockTask blockTask) {
        if (mc.world == null || mc.player == null || module.taskManager == null) return false;

        LiquidCandidate best = null;

        for (Direction side : Direction.values()) {
            BlockPos neighbourPos = blockTask.blockPos.offset(side);
            LiquidCandidate candidate = evaluateLiquidCandidate(neighbourPos, blockTask.blockPos, true);
            best = pickBetter(best, candidate);
        }

        if (best == null) return false;
        return upsertLiquidTask(best);
    }

    /**
     * Proactively scans the active mining/build area ahead and pre-queues one liquid
     * mitigation task before the bot reaches exposed lava.
     */
    public boolean preScanAheadLiquids() {
        if (mc.world == null || mc.player == null || module.taskManager == null || module.pathfinder == null) {
            return false;
        }

        if (hasNearbyLiquidTask()) return false;

        double maxForward = module.miningReach.get() + PRE_SCAN_FORWARD_PADDING;
        double maxLateral = Math.max(2.0, module.width.get() / 2.0 + PRE_SCAN_LATERAL_PADDING);

        LiquidCandidate best = null;
        for (BlockTask task : module.taskManager.getTasks().values()) {
            if (!isAheadWorkTask(task, maxForward, maxLateral)) continue;

            LiquidCandidate self = evaluateLiquidCandidate(task.blockPos, task.blockPos, false);
            best = pickBetter(best, self);

            for (Direction side : Direction.values()) {
                LiquidCandidate around = evaluateLiquidCandidate(task.blockPos.offset(side), task.blockPos, false);
                best = pickBetter(best, around);
            }
        }

        if (best == null) return false;
        return upsertLiquidTask(best);
    }

    public void updateLiquidTask(BlockTask blockTask) {
        if (blockTask == null) return;
        blockTask.targetBlock = resolveLiquidTarget(blockTask.blockPos);
        blockTask.updateState(TaskState.LIQUID);
    }

    private boolean isAheadWorkTask(BlockTask task, double maxForward, double maxLateral) {
        if (task == null) return false;
        if (task.taskState == TaskState.DONE || task.taskState == TaskState.BROKEN || task.taskState == TaskState.PLACED) {
            return false;
        }

        double forward = module.pathfinder.startingDirection.forwardProgress(module.pathfinder.currentBlockPos, task.blockPos);
        if (forward < -1.0 || forward > maxForward) return false;

        double lateral = Math.abs(module.pathfinder.startingDirection.lateralOffset(module.pathfinder.currentBlockPos, task.blockPos));
        if (lateral > maxLateral) return false;

        int dy = Math.abs(task.blockPos.getY() - mc.player.getBlockY());
        return dy <= module.miningRangeUp.get() + 2;
    }

    private boolean hasNearbyLiquidTask() {
        if (mc.player == null || module.taskManager == null) return false;

        Vec3d eyePos = mc.player.getEyePos();
        double range = module.maxReach.get() + 1.0;
        for (BlockTask task : module.taskManager.getTasks().values()) {
            if (task.taskState != TaskState.LIQUID) continue;
            if (eyePos.distanceTo(Vec3d.ofCenter(task.blockPos)) <= range) return true;
        }
        return false;
    }

    private LiquidCandidate evaluateLiquidCandidate(BlockPos liquidPos, BlockPos relatedPos, boolean strictReach) {
        if (mc.world == null || mc.player == null) return null;

        FluidState fluidState = mc.world.getFluidState(liquidPos);
        if (fluidState.isEmpty()) return null;

        double allowedReach = strictReach
            ? module.maxReach.get() + LIQUID_REACH_EPSILON
            : module.maxReach.get() + 1.0;

        Vec3d eyePos = mc.player.getEyePos();
        double eyeDistance = eyePos.distanceTo(Vec3d.ofCenter(liquidPos));
        if (eyeDistance > allowedReach) return null;

        if (HWUtils.getNeighbourSequence(
            liquidPos,
            module.placementSearch.get(),
            allowedReach,
            !module.illegalPlacements.get()
        ).isEmpty()) {
            return null;
        }

        boolean isLava = fluidState.isIn(FluidTags.LAVA);
        boolean isSource = fluidState.isStill();
        double threatDistance = mc.player.getPos().distanceTo(Vec3d.ofCenter(liquidPos));
        double relationDistance = Vec3d.ofCenter(relatedPos).distanceTo(Vec3d.ofCenter(liquidPos));

        return new LiquidCandidate(
            liquidPos,
            resolveLiquidTarget(liquidPos),
            isLava,
            isSource,
            threatDistance,
            relationDistance
        );
    }

    private LiquidCandidate pickBetter(LiquidCandidate current, LiquidCandidate candidate) {
        if (candidate == null) return current;
        if (current == null) return candidate;

        if (current.isLava != candidate.isLava) return candidate.isLava ? candidate : current;
        if (current.isSource != candidate.isSource) return candidate.isSource ? candidate : current;
        if (Math.abs(current.threatDistance - candidate.threatDistance) > 1.0e-3) {
            return candidate.threatDistance < current.threatDistance ? candidate : current;
        }
        if (Math.abs(current.relationDistance - candidate.relationDistance) > 1.0e-3) {
            return candidate.relationDistance < current.relationDistance ? candidate : current;
        }
        return current;
    }

    private boolean upsertLiquidTask(LiquidCandidate candidate) {
        if (module.taskManager == null) return false;

        BlockTask existing = module.taskManager.getTasks().get(candidate.blockPos);
        if (existing != null) {
            existing.targetBlock = candidate.targetBlock;
            existing.isLiquidSource = candidate.isSource;
            existing.updateState(TaskState.LIQUID);
            return true;
        }

        BlockTask newTask = new BlockTask(candidate.blockPos, TaskState.LIQUID, candidate.targetBlock);
        newTask.isLiquidSource = candidate.isSource;

        BlueprintTask blueprintTask = null;
        if (module.blueprintGenerator != null) {
            blueprintTask = module.blueprintGenerator.getBlueprint().get(candidate.blockPos);
        }

        if (blueprintTask != null) {
            module.taskManager.addTask(newTask, blueprintTask);
        } else {
            boolean filler = candidate.targetBlock == module.getFillerMat();
            module.taskManager.addTask(newTask, new BlueprintTask(candidate.targetBlock, filler, false));
        }

        return true;
    }

    private Block resolveLiquidTarget(BlockPos pos) {
        if (module.blueprintGenerator != null) {
            BlueprintTask blueprintTask = module.blueprintGenerator.getBlueprint().get(pos);
            if (blueprintTask != null) return blueprintTask.targetBlock;
        }
        return module.getFillerMat();
    }

    private static final class LiquidCandidate {
        private final BlockPos blockPos;
        private final Block targetBlock;
        private final boolean isLava;
        private final boolean isSource;
        private final double threatDistance;
        private final double relationDistance;

        private LiquidCandidate(
            BlockPos blockPos,
            Block targetBlock,
            boolean isLava,
            boolean isSource,
            double threatDistance,
            double relationDistance
        ) {
            this.blockPos = blockPos;
            this.targetBlock = targetBlock;
            this.isLava = isLava;
            this.isSource = isSource;
            this.threatDistance = threatDistance;
            this.relationDistance = relationDistance;
        }
    }
}
