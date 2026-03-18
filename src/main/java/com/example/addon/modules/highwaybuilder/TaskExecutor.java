package com.example.addon.modules.highwaybuilder;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.fluid.FluidState;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

public class TaskExecutor {
    private static final MinecraftClient mc = MinecraftClient.getInstance();

    private final HighwayBuilder module;
    private final TaskExecutionSupport executionSupport;

    public TaskExecutor(HighwayBuilder module) {
        this.module = module;
        this.executionSupport = new TaskExecutionSupport(module);
    }

    public void doTask(BlockTask blockTask, boolean updateOnly) {
        if (!updateOnly) blockTask.onTick();

        switch (blockTask.taskState) {
            case RESTOCK -> { if (!updateOnly) doRestock(); }
            case PICKUP -> { if (!updateOnly) doPickup(); }
            case OPEN_CONTAINER -> { if (!updateOnly) doOpenContainer(); }
            case BREAKING -> doBreaking(blockTask, updateOnly);
            case BROKEN -> executionSupport.handleBroken(blockTask);
            case PLACED -> executionSupport.handlePlaced(blockTask);
            case BREAK -> doBreak(blockTask, updateOnly);
            case PLACE, LIQUID -> doPlace(blockTask, updateOnly);
            case PENDING_BREAK -> executionSupport.handlePendingBreak(blockTask);
            case PENDING_PLACE -> executionSupport.handlePendingPlace(blockTask);
            case IMPOSSIBLE_PLACE -> { if (!updateOnly) executionSupport.handleImpossiblePlace(); }
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
        boolean containerBreakTask = isContainerBreakTask(blockTask);
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

        if (executionSupport.shouldSkipBreakForRange(blockTask)) {
            if (containerBreakTask) {
                blockTask.resetStuck();
                return;
            }
            if (blockTask.taskState == TaskState.BREAKING) {
                blockTask.updateState(TaskState.BREAK);
            }
            return;
        }

        if (!updateOnly) {
            if (containerBreakTask) {
                if (!module.inventoryHandler.swapOrMoveContainerBreakTool()) return;
            } else {
                if (!module.inventoryHandler.swapOrMoveBestTool(blockTask)) return;
            }
            if (!containerBreakTask
                && module.inventoryHandler.packetLimiter.size() >= module.interactionLimit.get()) {
                module.inventoryHandler.restoreSilentSwap();
                return;
            }
            if (containerBreakTask && mc.player != null) {
                mc.player.setVelocity(0.0, mc.player.getVelocity().y, 0.0);
            }
            module.blockBreaker.mineBlock(blockTask);
        }
    }

    private void doBreak(BlockTask blockTask, boolean updateOnly) {
        if (mc.world == null || mc.player == null) return;
        boolean containerBreakTask = isContainerBreakTask(blockTask);
        if (containerBreakTask
            && mc.player.currentScreenHandler != null
            && mc.player.currentScreenHandler.syncId != 0) {
            // BREAK stage is terminal for this restock cycle: never reopen container here.
            mc.player.closeHandledScreen();
            if (module.containerHandler != null) {
                module.containerHandler.containerTask.isOpen = false;
                module.containerHandler.containerTask.isLoaded = false;
            }
            blockTask.resetStuck();
            return;
        }

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

        // Block is already gone. Always pass through BROKEN first to avoid
        // BREAK <-> PLACE thrashing on desync-heavy servers.
        if (currentBlock == Blocks.AIR) {
            blockTask.updateState(TaskState.BROKEN);
            return;
        }

        // Is liquid
        if (!mc.world.getFluidState(blockTask.blockPos).isEmpty()) {
            module.liquidHandler.updateLiquidTask(blockTask);
            return;
        }

        if (executionSupport.shouldSkipBreakForRange(blockTask)) {
            return;
        }

        if (!updateOnly) {
            if (!containerBreakTask && module.liquidHandler.handleLiquid(blockTask)) {
                module.inventoryHandler.restoreSilentSwap();
                return;
            }
            if (containerBreakTask) {
                if (!module.inventoryHandler.swapOrMoveContainerBreakTool()) return;
            } else {
                if (!module.inventoryHandler.swapOrMoveBestTool(blockTask)) return;
            }
            if (!containerBreakTask
                && module.inventoryHandler.packetLimiter.size() >= module.interactionLimit.get()) {
                module.inventoryHandler.restoreSilentSwap();
                return;
            }
            if (containerBreakTask) {
                mc.player.setVelocity(0.0, mc.player.getVelocity().y, 0.0);
            }
            module.blockBreaker.mineBlock(blockTask);
        }
    }

    private void doPlace(BlockTask blockTask, boolean updateOnly) {
        if (mc.world == null) return;

        BlockState currentState = mc.world.getBlockState(blockTask.blockPos);
        Block currentBlock = currentState.getBlock();
        Block material = module.getMaterial();
        Block fillerMat = module.getFillerMat();

        // Hard guard against stale tasks: never place a non-AIR block into a blueprint AIR cell.
        // Skip temporary container task (shulker restock), it can be intentionally outside/over blueprint.
        BlockTask containerTask = module.containerHandler.containerTask;
        if (blockTask != containerTask
            && blockTask.taskState != TaskState.LIQUID
            && module.blueprintGenerator != null) {
            BlueprintTask blueprintTask = module.blueprintGenerator.getBlueprint().get(blockTask.blockPos);
            if (blueprintTask == null) {
                // Stale task outside current blueprint window.
                module.taskManager.getTasks().remove(blockTask.blockPos);
                blockTask.updateState(TaskState.DONE);
                return;
            }
            if (blueprintTask != null && blueprintTask.targetBlock == Blocks.AIR
                && blockTask.targetBlock != Blocks.AIR) {
                blockTask.targetBlock = Blocks.AIR;
                if (currentBlock == Blocks.AIR || currentState.isReplaceable()) {
                    blockTask.updateState(TaskState.DONE);
                } else {
                    blockTask.updateState(TaskState.BREAK);
                }
                return;
            }
        }

        // Liquid handling completion. For AIR targets, clear temporary plug blocks after drain.
        if (blockTask.taskState == TaskState.LIQUID) {
            boolean fluidGone = mc.world.getFluidState(blockTask.blockPos).isEmpty();
            if (fluidGone) {
                if (blockTask.targetBlock == Blocks.AIR) {
                    if (currentBlock != Blocks.AIR && !currentState.isReplaceable()) {
                        blockTask.updateState(TaskState.BREAK);
                    } else {
                        blockTask.updateState(TaskState.DONE);
                    }
                } else {
                    blockTask.updateState(TaskState.PLACE);
                }
                return;
            }
        }

        // A LIQUID task with AIR target must not directly place primary material.
        if (blockTask.taskState == TaskState.LIQUID && blockTask.targetBlock == Blocks.AIR
            && currentBlock == material) {
            blockTask.updateState(TaskState.BREAK);
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

        // Recover stale PLACE state even during update pass so TaskManager can
        // switch to break phase in the same tick.
        if (blockTask != containerTask
            && blockTask.taskState != TaskState.LIQUID
            && blockTask.targetBlock != Blocks.AIR
            && !currentState.isAir()
            && !currentState.isReplaceable()
            && currentBlock != blockTask.targetBlock) {
            blockTask.updateState(TaskState.BREAK);
            return;
        }

        if (updateOnly) return;

        // Global phase guard: never perform normal placement while there is
        // pending break work in active mining bounds.
        if (blockTask != containerTask
            && blockTask.taskState != TaskState.LIQUID
            && module.taskManager != null
            && module.taskManager.hasActiveBreakWork()) {
            blockTask.resetStuck();
            return;
        }

        boolean selfBlockingPlacement = mc.player != null
            && mc.player.getBoundingBox().intersects(new Box(blockTask.blockPos));
        if (selfBlockingPlacement) {
            if (blockTask == containerTask && module.containerHandler.tryRelocateContainerPlacement()) {
                return;
            }

            ContainerPlacementRecovery.nudgeAwayFromPlacementBlock(module, blockTask, blockTask == containerTask);
            blockTask.onStuck(2);

            if (blockTask == containerTask
                && ContainerPlacementRecovery.tryRecoverContainerPlacementStuck(module, blockTask)) {
                return;
            }
            return;
        }

        if (blockTask == containerTask
            && module.pathfinder.moveState == MovementState.RESTOCK) {
            if (!module.containerHandler.ensureContainerPlacementItemReady()) {
                blockTask.onStuck();
                return;
            }

            if (!module.pathfinder.isCenteredForRestock()
                && !module.containerHandler.canInteractWithContainerFromCurrentPos()) {
                if (module.containerHandler.tryRelocateContainerPlacement()) {
                    return;
                }

                ContainerPlacementRecovery.nudgeAwayFromPlacementBlock(module, blockTask, true);
                blockTask.onStuck(2);
                if (ContainerPlacementRecovery.tryRecoverContainerPlacementStuck(module, blockTask)) {
                    return;
                }
                return;
            }

            if (mc.player != null
                && mc.player.getEyePos().distanceTo(Vec3d.ofCenter(blockTask.blockPos)) > module.maxReach.get() + 0.2) {
                // Wait until pathing brings us into reach.
                blockTask.resetStuck();
                return;
            }
        }

        // If a non-container placement task still has a solid block in the cell,
        // this is a stale PLACE state. Recover by breaking first instead of
        // repeatedly trying to place and rendering bright blue flicker.
        if (blockTask != containerTask
            && blockTask.taskState != TaskState.LIQUID
            && blockTask.targetBlock != Blocks.AIR
            && !currentState.isAir()
            && !currentState.isReplaceable()
            && currentBlock != blockTask.targetBlock) {
            blockTask.updateState(TaskState.BREAK);
            return;
        }

        boolean isLiquidCell = !mc.world.getFluidState(blockTask.blockPos).isEmpty();
        if (!HWUtils.isPlaceable(blockTask.blockPos) && !(blockTask.taskState == TaskState.LIQUID && isLiquidCell)) {
            // Another solid block occupies the target cell: break it first.
            if (blockTask != containerTask
                && !currentState.isAir()
                && !currentState.isReplaceable()) {
                blockTask.updateState(TaskState.BREAK);
                return;
            }

            if (mc.player != null && mc.player.getBoundingBox().intersects(new Box(blockTask.blockPos))) {
                ContainerPlacementRecovery.nudgeAwayFromPlacementBlock(module, blockTask, blockTask == containerTask);
                blockTask.onStuck(2);
                if (blockTask == containerTask
                    && ContainerPlacementRecovery.tryRecoverContainerPlacementStuck(module, blockTask)) {
                    return;
                }
                return;
            }

            if (blockTask == containerTask) {
                if (module.containerHandler.tryRelocateContainerPlacement()) {
                    return;
                }
                // Don't destroy the container task if placement spot is temporarily blocked
                // (usually by player positioning while centering).
                containerTask.onStuck(2);
                if (ContainerPlacementRecovery.tryRecoverContainerPlacementStuck(module, containerTask)) {
                    return;
                }
            } else {
                module.taskManager.getTasks().remove(blockTask.blockPos);
            }
            return;
        }

        if (blockTask == containerTask) {
            if (!module.containerHandler.ensureContainerPlacementItemReady()) {
                blockTask.onStuck();
                return;
            }
        } else {
            if (!module.inventoryHandler.swapOrMoveBlock(blockTask)) {
                blockTask.onStuck();
                return;
            }
        }

        module.blockPlacer.placeBlock(blockTask);
    }

    private boolean isContainerBreakTask(BlockTask blockTask) {
        BlockTask containerTask = module.containerHandler.containerTask;
        return blockTask == containerTask
            || (containerTask != null
            && containerTask.taskState != TaskState.DONE
            && blockTask.blockPos.equals(containerTask.blockPos));
    }

}


