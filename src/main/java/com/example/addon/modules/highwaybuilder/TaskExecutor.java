package com.example.addon.modules.highwaybuilder;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.fluid.FluidState;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

public class TaskExecutor {
    private static final MinecraftClient mc = MinecraftClient.getInstance();

    private final HighwayBuilder module;

    public TaskExecutor(HighwayBuilder module) {
        this.module = module;
    }

    public void doTask(BlockTask blockTask, boolean updateOnly) {
        if (!updateOnly) blockTask.onTick();

        switch (blockTask.taskState) {
            case RESTOCK -> { if (!updateOnly) doRestock(); }
            case PICKUP -> { if (!updateOnly) doPickup(); }
            case OPEN_CONTAINER -> { if (!updateOnly) doOpenContainer(); }
            case BREAKING -> doBreaking(blockTask, updateOnly);
            case BROKEN -> doBroken(blockTask);
            case PLACED -> doPlaced(blockTask);
            case BREAK -> doBreak(blockTask, updateOnly);
            case PLACE, LIQUID -> doPlace(blockTask, updateOnly);
            case PENDING_BREAK -> doPendingBreak(blockTask);
            case PENDING_PLACE -> doPendingPlace(blockTask);
            case IMPOSSIBLE_PLACE -> { if (!updateOnly) doImpossiblePlace(); }
            case DONE -> { /* nothing */ }
        }
    }

    private void doRestock() {
        // Delegate to container handler
        module.containerHandler.doRestock();
    }

    private void doPickup() {
        module.containerHandler.doPickup();
    }

    private void doOpenContainer() {
        module.containerHandler.doOpenContainer();
    }

    private void doBreaking(BlockTask blockTask, boolean updateOnly) {
        if (mc.world == null) return;
        Block block = mc.world.getBlockState(blockTask.blockPos).getBlock();

        if (block == Blocks.AIR) {
            module.inventoryHandler.waitTicks = module.breakDelay.get();
            blockTask.updateState(TaskState.BROKEN);
            return;
        }

        FluidState fluidState = mc.world.getFluidState(blockTask.blockPos);
        if (!fluidState.isEmpty()) {
            module.liquidHandler.updateLiquidTask(blockTask);
            return;
        }

        if (!updateOnly
            && module.inventoryHandler.swapOrMoveBestTool(blockTask)
            && module.inventoryHandler.packetLimiter.size() < module.interactionLimit.get()) {
            module.blockBreaker.mineBlock(blockTask);
        }
    }

    private void doBroken(BlockTask blockTask) {
        if (mc.world == null) return;

        if (mc.world.getBlockState(blockTask.blockPos).getBlock() != Blocks.AIR) {
            blockTask.updateState(TaskState.BREAK);
            return;
        }

        module.statistics.totalBlocksBroken++;

        // Reset stuck on all break tasks
        for (BlockTask task : module.taskManager.getTasks().values()) {
            if (task.taskState == TaskState.BREAK) task.resetStuck();
        }

        // Instant break priming
        if (blockTask.blockPos.equals(module.blockBreaker.prePrimedPos)) {
            module.blockBreaker.primedPos = module.blockBreaker.prePrimedPos;
            module.blockBreaker.prePrimedPos = BlockPos.ORIGIN;
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

        if (blockTask.targetBlock == Blocks.AIR) {
            blockTask.updateState(TaskState.DONE);
        } else {
            blockTask.updateState(TaskState.PLACE);
        }
    }

    private void doPlaced(BlockTask blockTask) {
        if (mc.world == null) return;
        BlockState currentState = mc.world.getBlockState(blockTask.blockPos);
        Block currentBlock = currentState.getBlock();

        if ((blockTask.targetBlock == currentBlock || blockTask.isFiller)
            && !currentState.isReplaceable()) {
            // Successfully placed
            module.statistics.totalBlocksPlaced++;
            module.blockBreaker.prePrimedPos = blockTask.blockPos;
            module.statistics.simpleMovingAveragePlaces.add(System.currentTimeMillis());

            if (module.dynamicDelay.get() && module.blockPlacer.extraPlaceDelay > 0) {
                module.blockPlacer.extraPlaceDelay /= 2;
            }

            BlockTask containerTask = module.containerHandler.containerTask;
            if (blockTask == containerTask) {
                if (containerTask.destroy) {
                    containerTask.updateState(TaskState.BREAK);
                } else {
                    containerTask.updateState(TaskState.OPEN_CONTAINER);
                }
            } else {
                blockTask.updateState(TaskState.DONE);
            }

            // Reset stuck on place tasks
            for (BlockTask task : module.taskManager.getTasks().values()) {
                if (task.taskState == TaskState.PLACE) task.resetStuck();
            }
        } else if (blockTask.targetBlock == Blocks.AIR && currentBlock != Blocks.AIR) {
            blockTask.updateState(TaskState.BREAK);
        } else {
            blockTask.updateState(TaskState.PLACE);
        }
    }

    private void doBreak(BlockTask blockTask, boolean updateOnly) {
        if (mc.world == null || mc.player == null) return;

        Block currentBlock = mc.world.getBlockState(blockTask.blockPos).getBlock();
        String regName = Registries.BLOCK.getId(currentBlock).toString();

        // Skip ignored/special blocks
        if ((module.getIgnoreBlocks().contains(regName)
            && !blockTask.isShulker()
            && !module.blueprintGenerator.isInsideBlueprintBuild(blockTask.blockPos))
            || currentBlock == Blocks.END_PORTAL || currentBlock == Blocks.END_PORTAL_FRAME
            || currentBlock == Blocks.BEDROCK) {
            blockTask.updateState(TaskState.DONE);
            return;
        }

        // Filler checks
        Block fillerMat = module.getFillerMat();
        Block material = module.getMaterial();
        if (blockTask.targetBlock == fillerMat) {
            if (mc.world.getBlockState(blockTask.blockPos.up()).getBlock() == material) {
                blockTask.updateState(TaskState.DONE);
                return;
            }
        } else if (blockTask.targetBlock == material && currentBlock == material) {
            blockTask.updateState(TaskState.DONE);
            return;
        }

        // Block is already air
        if (currentBlock == Blocks.AIR) {
            if (blockTask.targetBlock == Blocks.AIR) {
                blockTask.updateState(TaskState.BROKEN);
            } else {
                blockTask.updateState(TaskState.PLACE);
            }
            return;
        }

        // Is liquid
        if (!mc.world.getFluidState(blockTask.blockPos).isEmpty()) {
            module.liquidHandler.updateLiquidTask(blockTask);
            return;
        }

        if (!updateOnly
            && mc.player.isOnGround()
            && module.inventoryHandler.swapOrMoveBestTool(blockTask)
            && !module.liquidHandler.handleLiquid(blockTask)
            && module.inventoryHandler.packetLimiter.size() < module.interactionLimit.get()) {
            module.blockBreaker.mineBlock(blockTask);
        }
    }

    private void doPlace(BlockTask blockTask, boolean updateOnly) {
        if (mc.world == null) return;

        Block currentBlock = mc.world.getBlockState(blockTask.blockPos).getBlock();
        Block material = module.getMaterial();
        Block fillerMat = module.getFillerMat();

        // Liquid already gone
        if (blockTask.taskState == TaskState.LIQUID
            && mc.world.getFluidState(blockTask.blockPos).isEmpty()) {
            blockTask.updateState(TaskState.DONE);
            return;
        }

        // Already placed correctly
        if (blockTask.targetBlock == material && currentBlock == material) {
            blockTask.updateState(TaskState.PLACED);
            return;
        }
        if (blockTask.targetBlock == fillerMat && currentBlock == fillerMat) {
            blockTask.updateState(TaskState.PLACED);
            return;
        }
        if (blockTask.targetBlock == Blocks.AIR) {
            if (mc.world.getFluidState(blockTask.blockPos).isEmpty()) {
                if (currentBlock != Blocks.AIR) {
                    blockTask.updateState(TaskState.BREAK);
                } else {
                    blockTask.updateState(TaskState.BROKEN);
                }
                return;
            }
        }

        if (updateOnly) return;

        if (!HWUtils.isPlaceable(blockTask.blockPos)) {
            BlockTask containerTask = module.containerHandler.containerTask;
            if (blockTask == containerTask) {
                containerTask.updateState(TaskState.BREAK);
            } else {
                module.taskManager.getTasks().remove(blockTask.blockPos);
            }
            return;
        }

        if (!module.inventoryHandler.swapOrMoveBlock(blockTask)) {
            blockTask.onStuck();
            return;
        }

        module.blockPlacer.placeBlock(blockTask);
    }

    private void doPendingBreak(BlockTask blockTask) {
        if (mc.world == null) { blockTask.onStuck(); return; }

        Block currentBlock = mc.world.getBlockState(blockTask.blockPos).getBlock();
        if (currentBlock == Blocks.AIR) {
            // Block is gone — confirmed broken
            module.inventoryHandler.waitTicks = module.breakDelay.get();
            blockTask.updateState(TaskState.BROKEN);
        } else {
            blockTask.onStuck();
        }
    }

    private void doPendingPlace(BlockTask blockTask) {
        if (mc.world == null) { blockTask.onStuck(); return; }

        BlockState currentState = mc.world.getBlockState(blockTask.blockPos);
        if ((blockTask.targetBlock == currentState.getBlock() || blockTask.isFiller)
            && !currentState.isReplaceable()) {
            // Block already placed — skip waiting
            blockTask.updateState(TaskState.PLACED);
        } else {
            blockTask.onStuck();
        }
    }

    private void doImpossiblePlace() {
        if (mc.player == null) return;

        if (module.pathfinder.shouldBridge()
            && module.pathfinder.moveState != MovementState.RESTOCK
            && mc.player.getPos().distanceTo(Vec3d.ofCenter(module.pathfinder.currentBlockPos)) < 1) {
            module.pathfinder.moveState = MovementState.BRIDGE;
        }
    }
}
