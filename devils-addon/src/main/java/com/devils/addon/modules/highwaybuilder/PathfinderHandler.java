package com.devils.addon.modules.highwaybuilder;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.function.Predicate;

public class PathfinderHandler {
    private static final MinecraftClient mc = MinecraftClient.getInstance();
    private static final double RESTOCK_NEAR_RANGE = 3.0;
    private static final double MAX_PROGRESS_UPDATE_DIST_SQ = 9.0;
    private static final double STEP_FORWARD_DIST_SQ = 3.24;
    private static final double CONTAINER_BREAK_EXTRA_REACH = 0.75;
    private static final double BREAK_REACH_EPSILON = 0.05;

    private final HighwayBuilder module;

    public HWDirection startingDirection = HWDirection.NORTH;
    public BlockPos currentBlockPos = BlockPos.ORIGIN;
    public BlockPos startingBlockPos = BlockPos.ORIGIN;
    public MovementState moveState = MovementState.RUNNING;
    public long rubberbandTimer = 0;

    private BlockPos goal = null;
    private BlockPos minerGoal = null;
    private final PathfinderBaritoneBridge baritone;
    private final PathfinderMovementController movement;

    public PathfinderHandler(HighwayBuilder module) {
        this.module = module;
        this.baritone = new PathfinderBaritoneBridge(module);
        this.movement = new PathfinderMovementController(module);
    }


    public void setupPathing() {
        if (mc.player == null) return;
        moveState = MovementState.RUNNING;
        startingBlockPos = mc.player.getBlockPos();
        currentBlockPos = startingBlockPos;
        startingDirection = HWDirection.fromYaw(mc.player.getYaw());
        movement.clearRunningTracking();
        movement.resetRestockTracking();
    }

    public void setMinerGoal(BlockPos pos) {
        minerGoal = pos;
    }

    public void clearMinerGoal() {
        minerGoal = null;
    }

    public boolean hasMinerGoal() {
        return minerGoal != null;
    }


    public void startPickup(Predicate<net.minecraft.item.ItemStack> filter) {
        baritone.startPickup(filter);
    }

    public void stopPickup() {
        baritone.stopPickup();
    }

    public boolean isPickupActive() {
        return baritone.isPickupActive();
    }

    public void updatePathing() {
        if (mc.player == null || mc.world == null) return;

        // Guard against stale FollowProcess lock.
        if (baritone.isPickupActive() && (module.echestMiner == null || !module.echestMiner.isActive())) {
            stopPickup();
        }

        // Baritone FollowProcess is handling pickup вЂ” don't interfere
        if (baritone.isPickupActive()) return;

        // External goal from EChest miner вЂ” bypass normal movement logic
        if (minerGoal != null) {
            goal = minerGoal;
            updateBaritoneGoal();
            return;
        }

        normalizeMovementState();

        switch (moveState) {
            case RUNNING -> updateRunning();
            case BRIDGE -> updateBridge();
            case PICKUP -> {
                goal = module.containerHandler.getCollectingPosition();
            }
            case RESTOCK -> {
                Vec3d standTarget = module.containerHandler != null
                    ? module.containerHandler.getRestockStandPos()
                    : Vec3d.ofCenter(currentBlockPos);
                boolean canInteract = module.containerHandler != null
                    && module.containerHandler.canInteractWithContainerFromCurrentPos();

                if (movement.horizontalDistanceSq(mc.player.getEntityPos(), standTarget)
                    <= RESTOCK_NEAR_RANGE * RESTOCK_NEAR_RANGE) {
                    goal = null;
                    if (movement.isCenteredOn(standTarget)) {
                        if (canInteract) {
                            movement.stopHorizontalMovement();
                        } else {
                            // Center target is not actually usable, force a new candidate.
                            if (module.containerHandler != null) {
                                module.containerHandler.invalidateRestockStandTarget();
                                standTarget = module.containerHandler.getRestockStandPos();
                            }
                            movement.moveTo(standTarget);
                        }
                    } else {
                        movement.moveTo(standTarget);
                    }
                } else {
                    goal = new BlockPos(
                        (int) Math.floor(standTarget.x),
                        (int) Math.floor(standTarget.y),
                        (int) Math.floor(standTarget.z)
                    );
                }

                movement.applyRestockStallRecovery(moveState, standTarget, canInteract);
            }
        }

        updateBaritoneGoal();
        movement.applyRunningStallNudge(goal, moveState, module.taskManager::populateTasks, this::refreshAfterRunningStall);
    }

    private void updateRunning() {
        goal = currentBlockPos;
        double distToCurrentSq = movement.horizontalDistanceSq(mc.player.getEntityPos(), Vec3d.ofCenter(currentBlockPos));

        // Don't advance while there is still reachable work (break/place/liquid) to do.
        // This forces the bot to finish ALL tasks within mining reach before moving,
        // instead of inching forward one slice at a time.
        if (hasActionableWork()) return;

        // If liquid is detected near the front but currently out of placement reach,
        // advance one safe step so it can enter interaction range.
        if (distToCurrentSq <= STEP_FORWARD_DIST_SQ && hasOutOfReachFrontLiquidTask()) {
            if (tryAdvanceOneStep()) return;
        }

        // All work done вЂ” find the farthest contiguous completed position
        BlockPos farthest = currentBlockPos;
        BlockPos candidate = currentBlockPos;
        for (int i = 0; i < 20; i++) {
            BlockPos next = candidate.add(
                startingDirection.directionVec.getX(),
                startingDirection.directionVec.getY(),
                startingDirection.directionVec.getZ()
            );

            if (!isTaskDone(next.up())
                || !isTaskDone(next)
                || !isTaskDone(next.down())) break;

            BlockState downState = mc.world.getBlockState(next.down());
            if (downState.isAir() || downState.isReplaceable()) break;

            farthest = next;
            candidate = next;
        }

        if (!currentBlockPos.equals(farthest) && distToCurrentSq <= MAX_PROGRESS_UPDATE_DIST_SQ) {
            // Count each block advanced for distance/h statistics
            BlockPos temp = currentBlockPos;
            while (!temp.equals(farthest)) {
                temp = temp.add(startingDirection.directionVec);
                module.statistics.simpleMovingAverageDistance.add(System.currentTimeMillis());
            }
            module.inventoryHandler.lastHitVec = Vec3d.ZERO;
            currentBlockPos = farthest;
            module.taskManager.populateTasks();
            return;
        }

        // If nothing is actionable in current reach, but there is unfinished work ahead,
        // advance one step so that next slice enters active range.
        if (distToCurrentSq <= STEP_FORWARD_DIST_SQ && hasUnfinishedAhead()) {
            tryAdvanceOneStep();
        }
    }

    private boolean hasActionableWork() {
        for (BlockTask task : module.taskManager.getTasks().values()) {
            if (!isMovementBlockingState(task.taskState)) continue;
            if (isDeferredBreakTask(task)) continue;
            if (!isTaskExecutableNow(task)) continue;
            return true;
        }
        return false;
    }

    private boolean hasUnfinishedAhead() {
        double currentProgress = getForwardProgressFromStart(currentBlockPos);
        for (BlockTask task : module.taskManager.getTasks().values()) {
            if (!isMovementBlockingState(task.taskState)) continue;
            if (getForwardProgressFromStart(task.blockPos) <= currentProgress + 0.25) continue;
            return true;
        }
        return false;
    }

    private boolean hasOutOfReachFrontLiquidTask() {
        if (mc.player == null) return false;

        double currentProgress = getForwardProgressFromStart(currentBlockPos);
        double maxForward = module.miningReach.get() + 2.0;
        double maxLateral = Math.max(2.5, module.width.get() / 2.0 + 2.0);

        for (BlockTask task : module.taskManager.getTasks().values()) {
            if (task == null || task.taskState != TaskState.LIQUID) continue;
            if (isTaskExecutableNow(task)) continue;

            double progress = getForwardProgressFromStart(task.blockPos);
            if (progress < currentProgress - 1.0 || progress > currentProgress + maxForward) continue;

            double lateral = Math.abs(startingDirection.lateralOffset(currentBlockPos, task.blockPos));
            if (lateral > maxLateral) continue;

            int dy = Math.abs(task.blockPos.getY() - mc.player.getBlockY());
            if (dy > module.miningRangeUp.get() + 2) continue;

            return true;
        }

        return false;
    }

    private boolean tryAdvanceOneStep() {
        BlockPos next = currentBlockPos.add(
            startingDirection.directionVec.getX(),
            startingDirection.directionVec.getY(),
            startingDirection.directionVec.getZ()
        );

        BlockState downState = mc.world.getBlockState(next.down());
        if (downState.isAir() || downState.isReplaceable()) return false;

        module.statistics.simpleMovingAverageDistance.add(System.currentTimeMillis());
        module.inventoryHandler.lastHitVec = Vec3d.ZERO;
        currentBlockPos = next;
        module.taskManager.populateTasks();
        return true;
    }

    private boolean isTaskExecutableNow(BlockTask task) {
        if (mc.player == null) return false;

        double dist = mc.player.getEyePos().distanceTo(Vec3d.ofCenter(task.blockPos));
        double placeReach = module.maxReach.get() + 0.8;

        return switch (task.taskState) {
            case BREAK, BREAKING, PENDING_BREAK -> canExecuteBreakTaskNow(task);
            case PLACE, PENDING_PLACE -> dist <= placeReach;
            case IMPOSSIBLE_PLACE -> dist <= placeReach && !task.sequence.isEmpty();
            case LIQUID -> task.targetBlock == Blocks.AIR
                ? canExecuteBreakTaskNow(task)
                : dist <= placeReach;
            default -> false;
        };
    }

    private boolean canExecuteBreakTaskNow(BlockTask task) {
        if (mc.player == null) return false;
        if (!module.taskManager.isWithinActiveMiningBounds(task.blockPos)) return false;

        return getBestBreakDistance(task.blockPos) <= getBreakReachLimit(task);
    }

    private double getBreakReachLimit(BlockTask task) {
        BlockTask containerTask = module.containerHandler != null ? module.containerHandler.containerTask : null;
        if (containerTask != null && task == containerTask) {
            return module.maxReach.get() + CONTAINER_BREAK_EXTRA_REACH + BREAK_REACH_EPSILON;
        }
        double effective = module.taskManager != null
            ? module.taskManager.getEffectiveMiningReach()
            : Math.min(module.miningReach.get(), module.maxReach.get());
        return effective + BREAK_REACH_EPSILON;
    }

    private double getBestBreakDistance(BlockPos pos) {
        if (mc.player == null) return Double.MAX_VALUE;
        Vec3d eyePos = mc.player.getEyePos();
        double best = eyePos.distanceTo(Vec3d.ofCenter(pos));
        for (Direction side : Direction.values()) {
            double dist = eyePos.distanceTo(HWUtils.getHitVec(pos, side));
            if (dist < best) best = dist;
        }
        return best;
    }

    private void updateBridge() {
        goal = null;
        BlockState belowState = mc.world.getBlockState(mc.player.getBlockPos().down());
        boolean isAboveAir = belowState.isAir() || belowState.isReplaceable();

        if (isAboveAir && mc.player.input != null) {
            mc.player.input.playerInput = new net.minecraft.util.PlayerInput(
                mc.player.input.playerInput.forward(),
                mc.player.input.playerInput.backward(),
                mc.player.input.playerInput.left(),
                mc.player.input.playerInput.right(),
                mc.player.input.playerInput.jump(),
                true,
                mc.player.input.playerInput.sprint()
            );
        }

        if (shouldBridge()) {
            Vec3d target = Vec3d.ofCenter(currentBlockPos).add(
                startingDirection.directionVec.getX(),
                0,
                startingDirection.directionVec.getZ()
            );
            movement.moveTo(target);
        } else if (!isAboveAir) {
            moveState = MovementState.RUNNING;
        }
    }

    private boolean isTaskDone(BlockPos pos) {
        if (mc.world == null) return false;
        BlockTask task = module.taskManager.getTasks().get(pos);
        if (task == null) return false;

        if (task.taskState != TaskState.DONE) return false;
        if (HWUtils.isLiquid(pos)) return false;

        // Verify the block still matches what we expect
        // If someone broke it, the task is stale вЂ” re-populate
        net.minecraft.block.Block currentBlock = mc.world.getBlockState(pos).getBlock();
        if (task.targetBlock != net.minecraft.block.Blocks.AIR && currentBlock == net.minecraft.block.Blocks.AIR) {
            // Block was destroyed externally вЂ” force re-check
            module.taskManager.populateTasks();
            return false;
        }

        return true;
    }

    private double getForwardProgressFromStart(BlockPos pos) {
        return startingDirection.forwardProgress(startingBlockPos, pos);
    }

    private boolean isMovementBlockingState(TaskState state) {
        return switch (state) {
            case BREAK, BREAKING, PLACE, LIQUID, PENDING_BREAK, PENDING_PLACE, IMPOSSIBLE_PLACE -> true;
            default -> false;
        };
    }

    private boolean isDeferredBreakTask(BlockTask task) {
        if (task == null) return false;
        if (!isDeferredBreakState(task)) return false;
        return !module.taskManager.isWithinActiveMiningBounds(task.blockPos);
    }

    private boolean isDeferredBreakState(BlockTask task) {
        return switch (task.taskState) {
            case BREAK, BREAKING, PENDING_BREAK -> true;
            case LIQUID -> task.targetBlock == Blocks.AIR;
            default -> false;
        };
    }

    public boolean shouldBridge() {
        if (!module.scaffold.get()) return false;
        if (mc.world == null) return false;
        if (module.containerHandler.containerTask.taskState != TaskState.DONE) return false;

        BlockPos ahead = currentBlockPos.add(
            startingDirection.directionVec.getX(),
            startingDirection.directionVec.getY(),
            startingDirection.directionVec.getZ()
        );

        BlockState aheadState = mc.world.getBlockState(ahead);
        BlockState aheadUpState = mc.world.getBlockState(ahead.up());
        BlockState aheadDownState = mc.world.getBlockState(ahead.down());

        if (!aheadState.isAir() || !aheadUpState.isAir()) return false;
        if (!aheadDownState.isAir() && !aheadDownState.isReplaceable()) return false;

        for (BlockTask task : module.taskManager.getTasks().values()) {
            if (task.taskState == TaskState.PENDING_PLACE) return false;
            if ((task.taskState == TaskState.PLACE || task.taskState == TaskState.LIQUID)
                && !task.sequence.isEmpty()) return false;
        }

        return true;
    }

    public boolean isCenteredForRestock() {
        if (mc.player == null) return false;
        Vec3d standTarget = module.containerHandler != null
            ? module.containerHandler.getRestockStandPos()
            : Vec3d.ofCenter(currentBlockPos);
        if (!movement.isCenteredOn(standTarget)) return false;

        if (module.containerHandler != null
            && module.containerHandler.containerTask.taskState != TaskState.DONE
            && mc.player.getBoundingBox().intersects(new net.minecraft.util.math.Box(
                module.containerHandler.containerTask.blockPos
            ))) {
            return false;
        }

        return true;
    }

    private void updateBaritoneGoal() {
        baritone.updateGoal(goal, movement::moveTo);
    }


    private void normalizeMovementState() {
        if (module.containerHandler == null) return;

        TaskState containerState = module.containerHandler.containerTask.taskState;
        if (containerState == TaskState.DONE
            && (moveState == MovementState.RESTOCK || moveState == MovementState.PICKUP)) {
            moveState = MovementState.RUNNING;
        }

        if (moveState != MovementState.RESTOCK) {
            movement.resetRestockTracking();
        }

        // Guard against stale external goals from EChest miner.
        if (minerGoal != null && (module.echestMiner == null || !module.echestMiner.isActive())) {
            minerGoal = null;
        }
    }

    public void setupBaritone() {
        baritone.setupSettings();
    }

    public void resetBaritone() {
        baritone.reset();
    }

    public boolean pauseCheck() {
        if (mc.player == null) return true;

        long timeSinceRubberband = System.currentTimeMillis() - rubberbandTimer;
        if (timeSinceRubberband < module.rubberbandTimeout.get() * 50L) return true;

        if (!mc.player.isAlive()) return true;

        return false;
    }

    public void clearProcess() {
        baritone.clearProcess();
        goal = null;
        minerGoal = null;
        movement.clearRunningTracking();
    }

    private void refreshAfterRunningStall() {
        resetBaritone();
        updateBaritoneGoal();
    }
}



