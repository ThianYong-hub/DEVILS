package com.example.addon.modules.highwaybuilder;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.ItemEntity;
import net.minecraft.fluid.FluidState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class TaskManager {
    private static final MinecraftClient mc = MinecraftClient.getInstance();

    private final HighwayBuilder module;
    private final TaskStateRules stateRules;
    private final TaskPriorityPlanner priorityPlanner;
    private final ConcurrentHashMap<BlockPos, BlockTask> tasks = new ConcurrentHashMap<>();
    public BlockTask lastTask = null;

    public TaskManager(HighwayBuilder module) {
        this.module = module;
        this.stateRules = new TaskStateRules(module);
        this.priorityPlanner = new TaskPriorityPlanner(module, stateRules);
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

            return !blueprint.containsKey(entry.getKey()) || TaskSpatialRules.startPadding(module, entry.getKey());
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

        // Start padding - don't break behind player
        if (TaskSpatialRules.startPadding(module, blockPos)) return;

        // Don't override container task
        if (blockPos.equals(module.containerHandler.containerTask.blockPos)) return;

        // Ignored blocks
        if (TaskSpatialRules.shouldBeIgnored(module, blockPos, currentState)) {
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

        if (!stateRules.isWithinMiningHeight(blockPos)) {
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

    public boolean isWithinActiveMiningBounds(BlockPos pos) {
        return stateRules.isWithinMiningBounds(pos);
    }

    public double getEffectiveMiningReach() {
        return stateRules.getEffectiveMiningReach();
    }

    public void addTask(BlockTask blockTask, BlueprintTask blueprintTask) {
        if (mc.world == null) return;

        updateTaskData(blockTask);
        blockTask.isFiller = blueprintTask.isFiller;
        blockTask.isSupport = blueprintTask.isSupport;

        if (module.multiBuilding.get()) blockTask.shuffle();

        BlockTask existing = tasks.get(blockTask.blockPos);
        if (existing != null) {
            if (stateRules.shouldReplaceExistingTask(existing, blockTask)) {
                tasks.put(blockTask.blockPos, blockTask);
            }
        } else {
            tasks.put(blockTask.blockPos, blockTask);
        }
    }

    private void updateTaskData(BlockTask blockTask) {
        if (mc.world == null || mc.player == null) return;

        blockTask.isLiquidSource = HWUtils.isLiquidSource(blockTask.blockPos);

        blockTask.setStartDistance(Vec3d.ofCenter(module.pathfinder.startingBlockPos)
            .distanceTo(Vec3d.ofCenter(blockTask.blockPos)));
        blockTask.setEyeDistance(mc.player.getEyePos().distanceTo(Vec3d.ofCenter(blockTask.blockPos)));

        if (stateRules.isSequenceDrivenState(blockTask.taskState)) {
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
                TaskContainerRecovery.recoverContainerTask(module, containerTask);
            } else {
                module.taskExecutor.doTask(containerTask, false);
            }
            return;
        }

        if (module.storageManagement.get()) {
        }

        if (module.inventoryHandler.waitTicks > 0) module.inventoryHandler.waitTicks--;

        List<BlockTask> sorted = new ArrayList<>(tasks.values());
        for (BlockTask task : sorted) {
            stateRules.sanitizeBreakTaskState(task);
            module.taskExecutor.doTask(task, true);
            if (task.taskState != TaskState.DONE) {
                updateTaskData(task);
            }
        }

        sorted.sort(priorityPlanner.comparator());

        BlockTask liquidTask = priorityPlanner.findTopPriorityLiquidTask(sorted);
        if (liquidTask != null) {
            if (stateRules.checkStuckTimeout(liquidTask)) {
                if (liquidTask.taskState != TaskState.DONE && module.inventoryHandler.waitTicks > 0) return;
                module.taskExecutor.doTask(liquidTask, false);
            }
            return;
        }

        int actionsThisTick = 0;
        int maxActions = module.blocksPerTick.get();
        boolean hasBreakPhaseTasks = hasActiveBreakWork();

        for (BlockTask task : sorted) {
            if (hasBreakPhaseTasks) {
                if (!stateRules.isBreakPhaseState(task)) continue;
                if (!stateRules.isBreakPhaseActionableNow(task)) continue;
            } else if (!stateRules.isPlacePhaseState(task)) {
                continue;
            } else if (!stateRules.isPlacePhaseActionableNow(task)) {
                continue;
            }
            if (!stateRules.checkStuckTimeout(task)) continue;
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

    public boolean hasActiveBreakWork() {
        for (BlockTask task : tasks.values()) {
            if (!stateRules.isBreakPhaseState(task)) continue;
            if (!isWithinActiveMiningBounds(task.blockPos)) continue;
            if (task.taskState == TaskState.DONE) continue;
            return true;
        }
        return false;
    }

    public boolean isBehindPos(BlockPos origin, BlockPos check) {
        return TaskSpatialRules.isBehindPos(module, origin, check);
    }

    public void clearTasks() {
        tasks.clear();
        module.containerHandler.containerTask.updateState(TaskState.DONE);
        lastTask = null;
        module.containerHandler.grindCycles = 0;
    }
}


