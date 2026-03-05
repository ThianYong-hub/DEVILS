package com.example.addon.modules.highwaybuilder;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.ItemEntity;
import net.minecraft.fluid.FluidState;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class TaskManager {
    private static final MinecraftClient mc = MinecraftClient.getInstance();
    private static final double MINING_REACH_EPSILON = 0.05;
    private static final double VANILLA_STRICT_BREAK_REACH = 4.0;
    private static final double BACKWARD_TASK_PADDING = 0.10;

    private final HighwayBuilder module;
    private final ConcurrentHashMap<BlockPos, BlockTask> tasks = new ConcurrentHashMap<>();
    public BlockTask lastTask = null;

    public TaskManager(HighwayBuilder module) {
        this.module = module;
    }

    public ConcurrentHashMap<BlockPos, BlockTask> getTasks() {
        return tasks;
    }

    public void populateTasks() {
        if (mc.world == null || mc.player == null) return;

        module.blueprintGenerator.generateBlueprint(
            module.pathfinder.currentBlockPos,
            module.pathfinder.startingDirection
        );

        Map<BlockPos, BlueprintTask> blueprint = module.blueprintGenerator.getBlueprint();
        for (Map.Entry<BlockPos, BlueprintTask> entry : blueprint.entrySet()) {
            generateTask(entry.getKey(), entry.getValue());
        }

        // Drop stale tasks that are no longer part of the active blueprint window.
        BlockTask containerTask = module.containerHandler.containerTask;
        tasks.entrySet().removeIf(entry -> {
            BlockTask task = entry.getValue();
            boolean isActiveContainerPos = entry.getKey().equals(containerTask.blockPos)
                && containerTask.taskState != TaskState.DONE;
            if (isActiveContainerPos) return false;

            // Keep nearby active liquid mitigation tasks even when they are
            // temporarily outside blueprint (e.g., lava adjacent/below break cells).
            if (task != null && task.taskState == TaskState.LIQUID && task.taskState != TaskState.DONE) {
                double dist = Vec3d.ofCenter(module.pathfinder.currentBlockPos)
                    .distanceTo(Vec3d.ofCenter(entry.getKey()));
                double forward = module.pathfinder.startingDirection
                    .forwardProgress(module.pathfinder.currentBlockPos, entry.getKey());
                // Keep nearby liquid mitigation tasks slightly behind the player too,
                // so side/back lava pockets are not dropped before being plugged.
                boolean nearWorkArea = dist <= module.maxReach.get() + 4.0 && forward >= -3.0;
                if (nearWorkArea) return false;
            }

            return !blueprint.containsKey(entry.getKey()) || startPadding(entry.getKey());
        });

        // Remove old done tasks that are far away
        double maxReach = module.maxReach.get();
        List<BlockPos> toRemove = new ArrayList<>();
        for (Map.Entry<BlockPos, BlockTask> entry : tasks.entrySet()) {
            BlockTask task = entry.getValue();
            if (task.taskState == TaskState.DONE
                && Vec3d.ofCenter(module.pathfinder.currentBlockPos).distanceTo(Vec3d.ofCenter(entry.getKey())) > maxReach + 2) {
                if (task.toRemove) {
                    if (System.currentTimeMillis() - task.timestamp > 1000L) {
                        toRemove.add(entry.getKey());
                    }
                } else {
                    task.toRemove = true;
                    task.timestamp = System.currentTimeMillis();
                }
            }
        }
        toRemove.forEach(tasks::remove);
    }

    private void generateTask(BlockPos blockPos, BlueprintTask blueprintTask) {
        if (mc.world == null || mc.player == null) return;

        BlockState currentState = mc.world.getBlockState(blockPos);
        Vec3d eyePos = mc.player.getEyePos();
        double maxReach = module.maxReach.get();

        // Start padding - don't break behind player
        if (startPadding(blockPos)) return;

        // Don't override container task
        if (blockPos.equals(module.containerHandler.containerTask.blockPos)) return;

        // Ignored blocks
        if (shouldBeIgnored(blockPos, currentState)) {
            BlockTask blockTask = new BlockTask(blockPos, TaskState.DONE, currentState.getBlock());
            addTask(blockTask, blueprintTask);
            return;
        }

        // Already in desired state
        if (currentState.getBlock() == blueprintTask.targetBlock) {
            BlockTask blockTask = new BlockTask(blockPos, TaskState.DONE, currentState.getBlock());
            addTask(blockTask, blueprintTask);
            return;
        }

        // Is liquid
        FluidState fluidState = mc.world.getFluidState(blockPos);
        if (!fluidState.isEmpty()) {
            BlockTask blockTask = new BlockTask(blockPos, TaskState.LIQUID, blueprintTask.targetBlock);
            updateTaskData(blockTask);
            if (!blockTask.sequence.isEmpty()) {
                addTask(blockTask, blueprintTask);
            }
            return;
        }

        // To place
        if ((currentState.isAir() || currentState.isReplaceable()) && blueprintTask.targetBlock != Blocks.AIR) {
            // Support not needed if material is already above
            if (blueprintTask.isSupport && mc.world.getBlockState(blockPos.up()).getBlock() == module.getMaterial()) {
                BlockTask blockTask = new BlockTask(blockPos, TaskState.DONE, currentState.getBlock());
                addTask(blockTask, blueprintTask);
                return;
            }

            // Entity collision check
            if (!mc.world.getOtherEntities(null, new Box(blockPos), entity -> !(entity instanceof ItemEntity)).isEmpty()) {
                BlockTask blockTask = new BlockTask(blockPos, TaskState.DONE, currentState.getBlock());
                addTask(blockTask, blueprintTask);
                return;
            }

            BlockTask blockTask = new BlockTask(blockPos, TaskState.PLACE, blueprintTask.targetBlock);
            updateTaskData(blockTask);

            if (!blockTask.sequence.isEmpty()) {
                addTask(blockTask, blueprintTask);
            } else {
                blockTask.updateState(TaskState.IMPOSSIBLE_PLACE);
                addTask(blockTask, blueprintTask);
            }
            return;
        }

        // To break
        if (blueprintTask.isFiller) {
            // Already filled with something
            BlockTask blockTask = new BlockTask(blockPos, TaskState.DONE, currentState.getBlock());
            addTask(blockTask, blueprintTask);
            return;
        }

        if (!isWithinMiningHeight(blockPos)) {
            // Mining-up limit: intentionally ignored.
            BlockTask ignored = new BlockTask(blockPos, TaskState.DONE, currentState.getBlock());
            addTask(ignored, blueprintTask);
            return;
        }

        // Always keep break tasks in the graph; out-of-range ones are deferred by execution bounds.
        BlockTask blockTask = new BlockTask(blockPos, TaskState.BREAK, blueprintTask.targetBlock);
        updateTaskData(blockTask);
        addTask(blockTask, blueprintTask);
    }

    private double getBreakReachDistance(BlockPos pos) {
        if (mc.player == null) return Double.MAX_VALUE;
        Vec3d eyePos = mc.player.getEyePos();
        double best = eyePos.distanceTo(Vec3d.ofCenter(pos));
        for (Direction side : Direction.values()) {
            double dist = eyePos.distanceTo(HWUtils.getHitVec(pos, side));
            if (dist < best) best = dist;
        }
        return best;
    }

    private boolean isWithinMiningReach(BlockPos pos) {
        return getBreakReachDistance(pos) <= getEffectiveMiningReach() + MINING_REACH_EPSILON;
    }

    private boolean isWithinMiningHeight(BlockPos pos) {
        if (mc.player == null) return false;
        int playerY = mc.player.getBlockY();
        return pos.getY() <= playerY + module.miningRangeUp.get()
            && pos.getY() >= playerY - 1;
    }

    private boolean isWithinMiningBounds(BlockPos pos) {
        return isWithinMiningReach(pos) && isWithinMiningHeight(pos);
    }

    public boolean isWithinActiveMiningBounds(BlockPos pos) {
        return isWithinMiningBounds(pos);
    }

    public double getEffectiveMiningReach() {
        double configured = module.miningReach.get();
        double placeReach = module.maxReach.get();
        return Math.min(configured, Math.min(placeReach, VANILLA_STRICT_BREAK_REACH));
    }

    public void addTask(BlockTask blockTask, BlueprintTask blueprintTask) {
        if (mc.world == null) return;

        updateTaskData(blockTask);
        blockTask.isFiller = blueprintTask.isFiller;
        blockTask.isSupport = blueprintTask.isSupport;

        if (module.multiBuilding.get()) blockTask.shuffle();

        BlockTask existing = tasks.get(blockTask.blockPos);
        if (existing != null) {
            if (shouldReplaceExistingTask(existing, blockTask)) {
                tasks.put(blockTask.blockPos, blockTask);
            }
        } else {
            tasks.put(blockTask.blockPos, blockTask);
        }
    }

    private boolean shouldReplaceExistingTask(BlockTask existing, BlockTask next) {
        if (existing == null || next == null) return true;

        if (existing.getStuckTicks() > existing.taskState.stuckTimeout) return true;
        if (next.taskState == TaskState.LIQUID) return true;
        if (existing.targetBlock != next.targetBlock) return true;
        if (existing.taskState == TaskState.DONE || existing.taskState == TaskState.IMPOSSIBLE_PLACE) return true;

        if (existing.taskState != next.taskState) {
            // Critical recovery: never keep stale PLACE/PENDING_PLACE when
            // blueprint regeneration says the block must be broken.
            if (isBreakLikeState(next.taskState) && isPlaceLikeState(existing.taskState)) return true;

            if (existing.taskState == TaskState.PLACE && !HWUtils.isPlaceable(existing.blockPos)) return true;
        }

        return false;
    }

    private boolean isBreakLikeState(TaskState state) {
        return switch (state) {
            case BREAK, BREAKING, PENDING_BREAK -> true;
            case LIQUID -> true;
            default -> false;
        };
    }

    private boolean isPlaceLikeState(TaskState state) {
        return switch (state) {
            case PLACE, PENDING_PLACE, IMPOSSIBLE_PLACE -> true;
            case LIQUID -> true;
            default -> false;
        };
    }

    private void updateTaskData(BlockTask blockTask) {
        if (mc.world == null || mc.player == null) return;

        blockTask.isLiquidSource = HWUtils.isLiquidSource(blockTask.blockPos);

        blockTask.setStartDistance(Vec3d.ofCenter(module.pathfinder.startingBlockPos)
            .distanceTo(Vec3d.ofCenter(blockTask.blockPos)));
        blockTask.setEyeDistance(mc.player.getEyePos().distanceTo(Vec3d.ofCenter(blockTask.blockPos)));

        if (isSequenceDrivenState(blockTask.taskState)) {
            long now = System.currentTimeMillis();
            long recalcInterval = blockTask.taskState == TaskState.IMPOSSIBLE_PLACE
                ? 40L
                : (blockTask.sequence.isEmpty() ? 75L : 200L);
            boolean isNearPlayer = blockTask.getEyeDistance() <= module.maxReach.get() + 1.5;

            boolean shouldRecalculateSequence = isNearPlayer && (blockTask.sequence.isEmpty()
                || blockTask.getStuckTicks() > 0
                || (now - blockTask.lastSequenceUpdate) >= recalcInterval);

            if (shouldRecalculateSequence) {
                boolean visibleOnly = !module.illegalPlacements.get();
                if (blockTask.taskState == TaskState.LIQUID) {
                    // Liquid plugs must not depend on strict face-visibility checks:
                    // on lava cells this frequently yields empty sequence and skips rotate/place.
                    visibleOnly = false;
                }
                blockTask.sequence = HWUtils.getNeighbourSequence(
                    blockTask.blockPos,
                    module.placementSearch.get(),
                    module.maxReach.get(),
                    visibleOnly
                );
                blockTask.lastSequenceUpdate = now;
            }

            if (blockTask.taskState == TaskState.IMPOSSIBLE_PLACE && !blockTask.sequence.isEmpty()) {
                blockTask.updateState(TaskState.PLACE);
            } else if (blockTask.taskState == TaskState.PLACE && blockTask.sequence.isEmpty()) {
                blockTask.updateState(TaskState.IMPOSSIBLE_PLACE);
            }
        }
    }

    public void runTasks() {
        if (mc.player == null || mc.world == null) return;

        BlockTask containerTask = module.containerHandler.containerTask;

        // Container task first
        if (containerTask.taskState != TaskState.DONE) {
            updateTaskData(containerTask);
            if (containerTask.getStuckTicks() > containerTask.taskState.stuckTimeout) {
                recoverContainerTask(containerTask);
            } else {
                module.taskExecutor.doTask(containerTask, false);
            }
            return;
        }

        // Check tools restock
        if (module.storageManagement.get()) {
            // Could add pickaxe/food restock checks here
        }

        // Run normal tasks
        if (module.inventoryHandler.waitTicks > 0) module.inventoryHandler.waitTicks--;

        List<BlockTask> sorted = new ArrayList<>(tasks.values());
        for (BlockTask task : sorted) {
            sanitizeBreakTaskState(task);
            module.taskExecutor.doTask(task, true);
            if (task.taskState != TaskState.DONE) {
                updateTaskData(task);
            }
        }

        sorted.sort(blockTaskComparator());

        BlockTask liquidTask = findTopPriorityLiquidTask(sorted);
        if (liquidTask != null) {
            if (checkStuckTimeout(liquidTask)) {
                if (liquidTask.taskState != TaskState.DONE && module.inventoryHandler.waitTicks > 0) return;
                module.taskExecutor.doTask(liquidTask, false);
            }
            return;
        }

        int actionsThisTick = 0;
        int maxActions = module.blocksPerTick.get();
        // Do not place while there are unresolved break-phase tasks in active bounds.
        // This prevents "skip nearest break -> try place into solid block" behavior.
        boolean hasBreakPhaseTasks = hasActiveBreakWork();

        for (BlockTask task : sorted) {
            if (hasBreakPhaseTasks) {
                if (!isBreakPhaseState(task)) continue;
                if (!isBreakPhaseActionableNow(task)) continue;
            } else if (!isPlacePhaseState(task)) {
                continue;
            } else if (!isPlacePhaseActionableNow(task)) {
                continue;
            }
            if (!checkStuckTimeout(task)) continue;
            if (task.taskState != TaskState.DONE && module.inventoryHandler.waitTicks > 0) return;

            module.taskExecutor.doTask(task, false);
            switch (task.taskState) {
                case DONE, BROKEN, PLACED -> { /* continue to next task */ }
                default -> {
                    actionsThisTick++;
                    if (actionsThisTick >= maxActions) return;
                }
            }
        }
    }

    private boolean checkStuckTimeout(BlockTask blockTask) {
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
                // Never drop break tasks as DONE on timeout: retry from BREAK state.
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
                    // Never mark active liquid mitigation as DONE on timeout.
                    // Keep it alive and retry with refreshed supports.
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

    public boolean hasActiveBreakWork() {
        for (BlockTask task : tasks.values()) {
            if (!isBreakPhaseState(task)) continue;
            if (!isWithinActiveMiningBounds(task.blockPos)) continue;
            if (task.taskState == TaskState.DONE) continue;
            return true;
        }
        return false;
    }

    private boolean isSequenceDrivenState(TaskState state) {
        return switch (state) {
            case PLACE, LIQUID, IMPOSSIBLE_PLACE -> true;
            default -> false;
        };
    }

    private boolean isBreakPhaseState(BlockTask task) {
        return switch (task.taskState) {
            case BREAK, BREAKING, PENDING_BREAK -> true;
            // Liquid tasks targeting AIR are effectively "clear this cell" and should
            // be processed before normal placement to avoid break/place thrashing.
            case LIQUID -> task.targetBlock == Blocks.AIR;
            default -> false;
        };
    }

    private boolean isBreakPhaseActionableNow(BlockTask task) {
        if (mc.player == null || task == null) return false;

        if (task.taskState == TaskState.LIQUID && task.targetBlock == Blocks.AIR) {
            return mc.player.getEyePos().distanceTo(Vec3d.ofCenter(task.blockPos))
                <= module.maxReach.get() + 0.8;
        }

        if (!isWithinActiveMiningBounds(task.blockPos)) return false;

        double reachLimit = getEffectiveMiningReach() + MINING_REACH_EPSILON;
        double best = getBreakReachDistance(task.blockPos);
        return best <= reachLimit;
    }

    private boolean isPlacePhaseActionableNow(BlockTask task) {
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

    private boolean isPlacePhaseState(BlockTask task) {
        return switch (task.taskState) {
            case PLACE, PENDING_PLACE, IMPOSSIBLE_PLACE -> true;
            case LIQUID -> task.targetBlock != Blocks.AIR;
            default -> false;
        };
    }

    private void sanitizeBreakTaskState(BlockTask task) {
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

    private BlockTask findTopPriorityLiquidTask(List<BlockTask> sorted) {
        if (mc.player == null) return null;

        Vec3d eyePos = mc.player.getEyePos();
        double maxReach = module.maxReach.get() + 0.8;
        double maxForward = module.miningReach.get() + 1.5;

        for (BlockTask task : sorted) {
            if (task.taskState != TaskState.LIQUID) continue;
            if (eyePos.distanceTo(Vec3d.ofCenter(task.blockPos)) > maxReach) continue;
            if (getForwardPriority(task.blockPos) > maxForward) continue;
            return task;
        }

        return null;
    }

    private void recoverContainerTask(BlockTask containerTask) {
        if (mc.world == null) return;

        containerTask.resetStuck();
        BlockState state = mc.world.getBlockState(containerTask.blockPos);
        boolean isAirLike = state.isAir() || state.isReplaceable();
        boolean isShulker = state.getBlock() instanceof ShulkerBoxBlock;

        switch (containerTask.taskState) {
            case BREAK, BREAKING, PENDING_BREAK -> {
                if (isAirLike) {
                    containerTask.updateState(TaskState.PICKUP);
                    module.pathfinder.moveState = MovementState.PICKUP;
                } else {
                    containerTask.updateState(TaskState.BREAK);
                    module.pathfinder.moveState = MovementState.RESTOCK;
                }
            }
            case PICKUP -> module.pathfinder.moveState = MovementState.PICKUP;
            case OPEN_CONTAINER, RESTOCK -> {
                if (isShulker) {
                    containerTask.updateState(TaskState.OPEN_CONTAINER);
                    module.pathfinder.moveState = MovementState.RESTOCK;
                } else {
                    if (module.containerHandler.tryRelocateContainerPlacement()) {
                        module.pathfinder.moveState = MovementState.RESTOCK;
                    } else {
                        containerTask.updateState(TaskState.PICKUP);
                        module.pathfinder.moveState = MovementState.PICKUP;
                    }
                }
            }
            case PLACE, PENDING_PLACE, IMPOSSIBLE_PLACE, PLACED -> {
                if (!module.containerHandler.tryRelocateContainerPlacement()) {
                    containerTask.updateState(TaskState.PLACE);
                }
                module.pathfinder.moveState = MovementState.RESTOCK;
            }
            default -> {
                // Intentionally no transition to DONE: never skip unfinished container cycle.
            }
        }
    }

    private boolean startPadding(BlockPos c) {
        HWDirection dir = module.pathfinder.startingDirection;
        BlockPos origin = module.pathfinder.currentBlockPos;
        return dir.forwardProgress(origin, c) < -BACKWARD_TASK_PADDING;
    }

    public boolean isBehindPos(BlockPos origin, BlockPos check) {
        HWDirection dir = module.pathfinder.startingDirection;
        int width = module.width.get();

        HWDirection ccw = dir.counterClockwise(2);
        HWDirection cw = dir.clockwise(2);

        BlockPos a = origin.add(
            ccw.directionVec.getX() * width, 0, ccw.directionVec.getZ() * width);
        BlockPos b = origin.add(
            cw.directionVec.getX() * width, 0, cw.directionVec.getZ() * width);

        return ((b.getX() - a.getX()) * (check.getZ() - a.getZ())
            - (b.getZ() - a.getZ()) * (check.getX() - a.getX())) > 0;
    }

    private boolean shouldBeIgnored(BlockPos blockPos, BlockState currentState) {
        String regName = Registries.BLOCK.getId(currentState.getBlock()).toString();
        return module.getIgnoreBlocks().contains(regName)
            && !module.blueprintGenerator.isInsideBlueprintBuild(blockPos)
            && !module.pathfinder.currentBlockPos.add(
                module.pathfinder.startingDirection.directionVec).equals(blockPos);
    }

    private Comparator<BlockTask> blockTaskComparator() {
        return Comparator.comparingInt((BlockTask t) -> t.taskState.ordinal())
            .thenComparingInt(BlockTask::getStuckTicks)
            .thenComparingInt(t -> t.isLiquidSource ? 0 : 1)
            .thenComparingDouble(t -> {
                if (module.pathfinder.moveState == MovementState.BRIDGE) {
                    return t.sequence.isEmpty() ? 69 : t.sequence.size();
                } else if (isBreakPhaseState(t)) {
                    // Break nearest forward slice first to avoid leaving close blocks behind.
                    return getForwardPriority(t.blockPos);
                } else if (isClosingPlacementState(t.taskState)) {
                    // Prioritize nearest forward slice first (close holes before extending).
                    return getForwardPriority(t.blockPos);
                } else {
                    return module.multiBuilding.get() ? t.getShuffle() : t.getStartDistance();
                }
            })
            .thenComparingDouble(t -> isBreakPhaseState(t)
                ? getLateralPriority(t.blockPos) // center lane first while breaking
                : 0.0)
            .thenComparingDouble(t -> isClosingPlacementState(t.taskState)
                ? getLateralPriority(t.blockPos) // center lane first, edges/railings later
                : 0.0)
            .thenComparingDouble(BlockTask::getEyeDistance)
            .thenComparingDouble(BlockTask::getStartDistance);
    }

    private boolean isClosingPlacementState(TaskState state) {
        return switch (state) {
            case PLACE, LIQUID, PENDING_PLACE, IMPOSSIBLE_PLACE -> true;
            default -> false;
        };
    }

    private double getForwardPriority(BlockPos pos) {
        HWDirection dir = module.pathfinder.startingDirection;
        BlockPos origin = module.pathfinder.currentBlockPos;
        double progress = dir.forwardProgress(origin, pos);
        if (progress < 0.0) return 10_000.0 + Math.abs(progress);
        return progress;
    }

    private double getLateralPriority(BlockPos pos) {
        HWDirection dir = module.pathfinder.startingDirection;
        BlockPos origin = module.pathfinder.currentBlockPos;
        return Math.abs(dir.lateralOffset(origin, pos));
    }

    public void clearTasks() {
        tasks.clear();
        module.containerHandler.containerTask.updateState(TaskState.DONE);
        lastTask = null;
        module.containerHandler.grindCycles = 0;
    }
}
