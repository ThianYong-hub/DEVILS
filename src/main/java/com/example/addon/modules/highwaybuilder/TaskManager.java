package com.example.addon.modules.highwaybuilder;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.fluid.FluidState;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class TaskManager {
    private static final MinecraftClient mc = MinecraftClient.getInstance();

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

        // Out of reach
        if (eyePos.distanceTo(Vec3d.ofCenter(blockPos)) >= maxReach + 1) return;

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
            if (!mc.world.getOtherEntities(null, new Box(blockPos)).isEmpty()) {
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

        BlockTask blockTask = new BlockTask(blockPos, TaskState.BREAK, blueprintTask.targetBlock);
        updateTaskData(blockTask);
        if (blockTask.getEyeDistance() < maxReach) {
            addTask(blockTask, blueprintTask);
        }
    }

    public void addTask(BlockTask blockTask, BlueprintTask blueprintTask) {
        if (mc.world == null) return;

        updateTaskData(blockTask);
        blockTask.isFiller = blueprintTask.isFiller;
        blockTask.isSupport = blueprintTask.isSupport;

        if (module.multiBuilding.get()) blockTask.shuffle();

        BlockTask existing = tasks.get(blockTask.blockPos);
        if (existing != null) {
            if (existing.getStuckTicks() > existing.taskState.stuckTimeout
                || blockTask.taskState == TaskState.LIQUID
                || (existing.taskState != blockTask.taskState
                    && (existing.taskState == TaskState.DONE
                        || existing.taskState == TaskState.IMPOSSIBLE_PLACE
                        || (existing.taskState == TaskState.PLACE
                            && !HWUtils.isPlaceable(existing.blockPos))))) {
                tasks.put(blockTask.blockPos, blockTask);
            }
        } else {
            tasks.put(blockTask.blockPos, blockTask);
        }
    }

    private void updateTaskData(BlockTask blockTask) {
        if (mc.world == null || mc.player == null) return;

        blockTask.isLiquidSource = HWUtils.isLiquidSource(blockTask.blockPos);

        if (blockTask.taskState == TaskState.PLACE || blockTask.taskState == TaskState.LIQUID) {
            blockTask.sequence = HWUtils.getNeighbourSequence(
                blockTask.blockPos,
                module.placementSearch.get(),
                module.maxReach.get(),
                !module.illegalPlacements.get()
            );
        }

        blockTask.setStartDistance(Vec3d.ofCenter(module.pathfinder.startingBlockPos)
            .distanceTo(Vec3d.ofCenter(blockTask.blockPos)));
        blockTask.setEyeDistance(mc.player.getEyePos().distanceTo(Vec3d.ofCenter(blockTask.blockPos)));
    }

    public void runTasks() {
        if (mc.player == null || mc.world == null) return;

        BlockTask containerTask = module.containerHandler.containerTask;

        // Container task first
        if (containerTask.taskState != TaskState.DONE) {
            updateTaskData(containerTask);
            if (containerTask.getStuckTicks() > containerTask.taskState.stuckTimeout) {
                if (containerTask.taskState == TaskState.PICKUP) {
                    module.pathfinder.moveState = MovementState.RUNNING;
                }
                containerTask.updateState(TaskState.DONE);
            } else {
                tasks.values().forEach(t -> module.taskExecutor.doTask(t, true));
                module.taskExecutor.doTask(containerTask, false);
            }
            return;
        }

        // Check tools restock
        if (module.storageManagement.get()) {
            // Could add pickaxe/food restock checks here
        }

        // Run normal tasks
        module.inventoryHandler.waitTicks--;

        List<BlockTask> sorted = new ArrayList<>(tasks.values());
        for (BlockTask task : sorted) {
            module.taskExecutor.doTask(task, true);
            updateTaskData(task);
        }

        sorted.sort(blockTaskComparator());

        int actionsThisTick = 0;
        int maxActions = module.blocksPerTick.get();

        for (BlockTask task : sorted) {
            if (!checkStuckTimeout(task)) return;
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
            case PLACE -> {
                if (module.dynamicDelay.get() && module.blockPlacer.extraPlaceDelay < 10
                    && module.pathfinder.moveState != MovementState.BRIDGE) {
                    module.blockPlacer.extraPlaceDelay += 1;
                }
            }
            case PICKUP -> {
                blockTask.updateState(TaskState.DONE);
                module.pathfinder.moveState = MovementState.RUNNING;
            }
            default -> blockTask.updateState(TaskState.DONE);
        }
        return false;
    }

    private boolean startPadding(BlockPos c) {
        BlockPos padStart = module.pathfinder.startingBlockPos.add(
            module.pathfinder.startingDirection.directionVec);
        return isBehindPos(padStart, c);
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
                } else {
                    return module.multiBuilding.get() ? t.getShuffle() : t.getStartDistance();
                }
            })
            .thenComparingDouble(BlockTask::getEyeDistance);
    }

    public void clearTasks() {
        tasks.clear();
        module.containerHandler.containerTask.updateState(TaskState.DONE);
        lastTask = null;
        module.containerHandler.grindCycles = 0;
    }
}
