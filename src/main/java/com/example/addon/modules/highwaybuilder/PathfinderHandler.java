package com.example.addon.modules.highwaybuilder;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.lang.reflect.Method;
import java.util.function.Predicate;

public class PathfinderHandler {
    private static final MinecraftClient mc = MinecraftClient.getInstance();
    private static final double RESTOCK_NEAR_RANGE = 3.0;
    private static final double RESTOCK_CENTER_TOLERANCE = 0.08;

    private final HighwayBuilder module;

    public HWDirection startingDirection = HWDirection.NORTH;
    public BlockPos currentBlockPos = BlockPos.ORIGIN;
    public BlockPos startingBlockPos = BlockPos.ORIGIN;
    public MovementState moveState = MovementState.RUNNING;
    public long rubberbandTimer = 0;

    private BlockPos goal = null;
    private BlockPos minerGoal = null; // External goal from EChestMiner — takes priority
    private boolean baritoneActive = false;
    private boolean baritoneAvailable = false;

    // Cached reflection objects for Baritone
    private Object baritoneInstance;
    private Method setGoalAndPathMethod;
    private Method setGoalMethod;
    private java.lang.reflect.Constructor<?> goalBlockConstructor;

    // FollowProcess for item pickup
    private Object followProcess;
    private Method pickupMethod;
    private Method followCancelMethod;
    private boolean pickupActive = false;

    public PathfinderHandler(HighwayBuilder module) {
        this.module = module;
        initBaritoneReflection();
    }

    private void initBaritoneReflection() {
        try {
            Class<?> apiClass = Class.forName("baritone.api.BaritoneAPI");
            Method getProvider = apiClass.getMethod("getProvider");
            Object provider = getProvider.invoke(null);
            Method getPrimary = provider.getClass().getMethod("getPrimaryBaritone");
            baritoneInstance = getPrimary.invoke(provider);

            Method getCustomGoalProcess = baritoneInstance.getClass().getMethod("getCustomGoalProcess");
            Object process = getCustomGoalProcess.invoke(baritoneInstance);

            Class<?> goalClass = Class.forName("baritone.api.pathing.goals.Goal");
            Class<?> goalBlockClass = Class.forName("baritone.api.pathing.goals.GoalBlock");
            goalBlockConstructor = goalBlockClass.getConstructor(BlockPos.class);

            setGoalAndPathMethod = process.getClass().getMethod("setGoalAndPath", goalClass);
            setGoalMethod = process.getClass().getMethod("setGoal", goalClass);

            baritoneAvailable = true;
        } catch (Exception e) {
            baritoneAvailable = false;
        }

        // FollowProcess for item pickup — separate try so it can't break core pathing
        if (baritoneAvailable) {
            try {
                Method getFollowProcess = baritoneInstance.getClass().getMethod("getFollowProcess");
                followProcess = getFollowProcess.invoke(baritoneInstance);

                pickupMethod = followProcess.getClass().getMethod("pickup", Predicate.class);
                followCancelMethod = followProcess.getClass().getMethod("cancel");
            } catch (Exception e) {
                // Pickup not available — EChestMiner will fall back to manual collection
                followProcess = null;
                pickupMethod = null;
                followCancelMethod = null;
            }
        }
    }

    public void setupPathing() {
        if (mc.player == null) return;
        moveState = MovementState.RUNNING;
        startingBlockPos = mc.player.getBlockPos();
        currentBlockPos = startingBlockPos;
        startingDirection = HWDirection.fromYaw(mc.player.getYaw());
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

    /**
     * Start Baritone's FollowProcess in pickup mode — it will automatically
     * path to all matching dropped items (GoalComposite of GoalBlocks).
     */
    public void startPickup(Predicate<net.minecraft.item.ItemStack> filter) {
        pickupActive = true;
        if (!baritoneAvailable || pickupMethod == null || followProcess == null) return;

        try {
            // Cancel any custom goal pathing first
            Object process = baritoneInstance.getClass().getMethod("getCustomGoalProcess")
                .invoke(baritoneInstance);
            setGoalMethod.invoke(process, (Object) null);
            baritoneActive = false;

            pickupMethod.invoke(followProcess, (Predicate<?>) filter);
        } catch (Exception e) {
            // Fallback handled by EChestMiner if pickup doesn't work
        }
    }

    /**
     * Stop Baritone's FollowProcess pickup mode.
     */
    public void stopPickup() {
        pickupActive = false;
        if (!baritoneAvailable || followCancelMethod == null) return;

        try {
            followCancelMethod.invoke(followProcess);
        } catch (Exception ignored) {}
    }

    public boolean isPickupActive() {
        return pickupActive;
    }

    public void updatePathing() {
        if (mc.player == null || mc.world == null) return;

        // Baritone FollowProcess is handling pickup — don't interfere
        if (pickupActive) return;

        // External goal from EChest miner — bypass normal movement logic
        if (minerGoal != null) {
            goal = minerGoal;
            updateBaritoneGoal();
            return;
        }

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

                if (horizontalDistanceSq(mc.player.getPos(), standTarget)
                    <= RESTOCK_NEAR_RANGE * RESTOCK_NEAR_RANGE) {
                    goal = null;
                    if (isCenteredOn(standTarget)) {
                        if (canInteract) {
                            stopHorizontalMovement();
                        } else {
                            // Center target is not actually usable, force a new candidate.
                            if (module.containerHandler != null) {
                                module.containerHandler.invalidateRestockStandTarget();
                                standTarget = module.containerHandler.getRestockStandPos();
                            }
                            moveTo(standTarget);
                        }
                    } else {
                        moveTo(standTarget);
                    }
                } else {
                    goal = new BlockPos(
                        (int) Math.floor(standTarget.x),
                        (int) Math.floor(standTarget.y),
                        (int) Math.floor(standTarget.z)
                    );
                }
            }
        }

        updateBaritoneGoal();
    }

    private void updateRunning() {
        goal = currentBlockPos;

        // Don't advance while there is still reachable work (break/place/liquid) to do.
        // This forces the bot to finish ALL tasks within mining reach before moving,
        // instead of inching forward one slice at a time.
        if (hasActionableWork()) return;

        // All work done — find the farthest contiguous completed position
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

        if (!currentBlockPos.equals(farthest)
            && mc.player.getPos().distanceTo(Vec3d.ofCenter(currentBlockPos)) < 2) {
            // Count each block advanced for distance/h statistics
            BlockPos temp = currentBlockPos;
            while (!temp.equals(farthest)) {
                temp = temp.add(startingDirection.directionVec);
                module.statistics.simpleMovingAverageDistance.add(System.currentTimeMillis());
            }
            module.inventoryHandler.lastHitVec = Vec3d.ZERO;
            currentBlockPos = farthest;
            module.taskManager.populateTasks();
        }
    }

    /**
     * Returns true if there are non-deferred, movement-blocking tasks still pending.
     * While this returns true the player stays put and works, instead of advancing.
     */
    private boolean hasActionableWork() {
        for (BlockTask task : module.taskManager.getTasks().values()) {
            if (!isMovementBlockingState(task.taskState)) continue;
            if (isDeferredBreakTask(task)) continue;
            return true;
        }
        return false;
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
            moveTo(target);
        } else if (!isAboveAir) {
            moveState = MovementState.RUNNING;
        }
    }

    private boolean isTaskDone(BlockPos pos) {
        if (mc.world == null) return false;
        BlockTask task = module.taskManager.getTasks().get(pos);
        if (task == null) return false;

        if (task.taskState != TaskState.DONE) {
            if (isDeferredBreakTask(task)) return true;
            return false;
        }
        if (HWUtils.isLiquid(pos)) return false;

        // Verify the block still matches what we expect
        // If someone broke it, the task is stale — re-populate
        net.minecraft.block.Block currentBlock = mc.world.getBlockState(pos).getBlock();
        if (task.targetBlock != net.minecraft.block.Blocks.AIR && currentBlock == net.minecraft.block.Blocks.AIR) {
            // Block was destroyed externally — force re-check
            module.taskManager.populateTasks();
            return false;
        }

        return true;
    }

    private boolean checkForResidue(BlockPos pos) {
        if (module.containerHandler.containerTask.taskState != TaskState.DONE) return false;

        for (BlockTask task : module.taskManager.getTasks().values()) {
            // Block on any unfinished build-critical tasks behind the move plane.
            if (isMovementBlockingState(task.taskState)
                && !isDeferredBreakTask(task)
                && module.taskManager.isBehindPos(pos, task.blockPos)) {
                return false;
            }
        }
        return true;
    }

    private boolean hasPendingTasksBefore(BlockPos nextPos) {
        double nextProgress = getForwardProgressFromStart(nextPos) + 0.5;

        for (BlockTask task : module.taskManager.getTasks().values()) {
            if (!isMovementBlockingState(task.taskState)) continue;
            if (isDeferredBreakTask(task)) continue;
            if (getForwardProgressFromStart(task.blockPos) <= nextProgress) return true;
        }

        return false;
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

    private void moveTo(Vec3d target) {
        if (mc.player == null) return;
        double speed = module.moveSpeed.get();
        mc.player.setVelocity(
            Math.max(-speed, Math.min(speed, target.x - mc.player.getX())),
            mc.player.getVelocity().y,
            Math.max(-speed, Math.min(speed, target.z - mc.player.getZ()))
        );
    }

    public boolean isCenteredForRestock() {
        if (mc.player == null) return false;
        Vec3d standTarget = module.containerHandler != null
            ? module.containerHandler.getRestockStandPos()
            : Vec3d.ofCenter(currentBlockPos);
        return isCenteredOn(standTarget);
    }

    private boolean isCenteredOn(Vec3d target) {
        if (mc.player == null) return false;
        return horizontalDistanceSq(target, mc.player.getPos())
            <= RESTOCK_CENTER_TOLERANCE * RESTOCK_CENTER_TOLERANCE;
    }

    private void stopHorizontalMovement() {
        if (mc.player == null) return;
        mc.player.setVelocity(0.0, mc.player.getVelocity().y, 0.0);
    }

    private double horizontalDistanceSq(Vec3d a, Vec3d b) {
        double dx = a.x - b.x;
        double dz = a.z - b.z;
        return dx * dx + dz * dz;
    }

    private void updateBaritoneGoal() {
        if (!baritoneAvailable) {
            if (goal != null) moveTo(Vec3d.ofCenter(goal));
            return;
        }

        try {
            Object process = baritoneInstance.getClass().getMethod("getCustomGoalProcess")
                .invoke(baritoneInstance);

            if (goal != null) {
                Object goalBlock = goalBlockConstructor.newInstance(goal);
                setGoalAndPathMethod.invoke(process, goalBlock);
                baritoneActive = true;
            } else if (baritoneActive) {
                setGoalMethod.invoke(process, (Object) null);
                baritoneActive = false;
            }
        } catch (Exception e) {
            if (goal != null) moveTo(Vec3d.ofCenter(goal));
        }
    }

    public void setupBaritone() {
        if (!baritoneAvailable) return;

        try {
            Class<?> apiClass = Class.forName("baritone.api.BaritoneAPI");
            Method getSettings = apiClass.getMethod("getSettings");
            Object settings = getSettings.invoke(null);

            setBaritoneField(settings, "allowPlace", false);
            setBaritoneField(settings, "allowBreak", false);
            setBaritoneField(settings, "renderGoal", module.goalRender.get());
            setBaritoneField(settings, "allowInventory", false);
        } catch (Exception e) {
            // Baritone settings not available
        }
    }

    private void setBaritoneField(Object settings, String fieldName, Object value) {
        try {
            var field = settings.getClass().getField(fieldName);
            var setting = field.get(settings);
            var valueField = setting.getClass().getField("value");
            valueField.set(setting, value);
        } catch (Exception ignored) {}
    }

    public void resetBaritone() {
        if (!baritoneAvailable) return;

        try {
            Object process = baritoneInstance.getClass().getMethod("getCustomGoalProcess")
                .invoke(baritoneInstance);
            setGoalMethod.invoke(process, (Object) null);
            baritoneActive = false;
        } catch (Exception ignored) {}

        if (pickupActive) stopPickup();
    }

    public boolean pauseCheck() {
        if (mc.player == null) return true;

        long timeSinceRubberband = System.currentTimeMillis() - rubberbandTimer;
        if (timeSinceRubberband < module.rubberbandTimeout.get() * 50L) return true;

        if (!mc.player.isAlive()) return true;

        return false;
    }

    public void clearProcess() {
        baritoneActive = false;
        goal = null;
        minerGoal = null;
        if (pickupActive) stopPickup();
    }
}
