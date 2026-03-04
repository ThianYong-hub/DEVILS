package com.example.addon.modules.highwaybuilder;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.fluid.FluidState;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

public class TaskExecutor {
    private static final MinecraftClient mc = MinecraftClient.getInstance();
    private static final double CONTAINER_BREAK_EXTRA_REACH = 0.75;

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

        if (shouldSkipBreakForRange(blockTask)) {
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

    private void doBroken(BlockTask blockTask) {
        if (mc.world == null) return;

        if (mc.world.getBlockState(blockTask.blockPos).getBlock() != Blocks.AIR) {
            blockTask.updateState(TaskState.BREAK);
            return;
        }

        // Finalize any silent tool swap after successful break completion.
        module.inventoryHandler.restoreSilentSwap();

        module.statistics.totalBlocksBroken++;

        // Reset stuck on all break tasks
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
        BlockTask containerTask = module.containerHandler.containerTask;
        boolean isContainerTask = blockTask == containerTask;

        if ((blockTask.targetBlock == currentBlock || blockTask.isFiller)
            && !currentState.isReplaceable()) {
            // Successfully placed
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
                    // Give the server a few ticks to fully register the placed block
                    // before attempting to interact/open it.
                    module.containerHandler.setOpenDelay(4);
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

        // Block is already air
        if (currentBlock == Blocks.AIR) {
            if (containerBreakTask) {
                blockTask.updateState(TaskState.BROKEN);
            } else if (blockTask.targetBlock == Blocks.AIR) {
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

        if (shouldSkipBreakForRange(blockTask)) {
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
        if (blockTask != containerTask && module.blueprintGenerator != null) {
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

        if (updateOnly) return;

        if (blockTask == containerTask
            && module.pathfinder.moveState == MovementState.RESTOCK) {
            if (!module.containerHandler.ensureContainerPlacementItemReady()) {
                blockTask.onStuck();
                return;
            }

            if (!module.pathfinder.isCenteredForRestock()
                && !module.containerHandler.canInteractWithContainerFromCurrentPos()) {
                blockTask.resetStuck();
                return;
            }

            if (mc.player != null && mc.player.getBoundingBox().intersects(new Box(blockTask.blockPos))) {
                // Never place while player's hitbox intersects the placement block.
                blockTask.resetStuck();
                return;
            }

            if (mc.player != null
                && mc.player.getEyePos().distanceTo(Vec3d.ofCenter(blockTask.blockPos)) > module.maxReach.get() + 0.2) {
                // Wait until pathing brings us into reach.
                blockTask.resetStuck();
                return;
            }
        }

        if (!HWUtils.isPlaceable(blockTask.blockPos)) {
            if (blockTask == containerTask) {
                // Don't destroy the container task if placement spot is temporarily blocked
                // (usually by player positioning while centering).
                containerTask.resetStuck();
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
        } else if ((currentState.isAir() || currentState.isReplaceable())
            && blockTask.targetBlock != Blocks.AIR) {
            // Placement did not land (lag/packet drop). Retry immediately instead of sitting in black state.
            blockTask.updateState(TaskState.PLACE);
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

    private boolean shouldSkipBreakForRange(BlockTask blockTask) {
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
