package com.devils.addon.modules.highwaybuilder;

import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

final class PathfinderMovementController {
    private static final MinecraftClient mc = MinecraftClient.getInstance();
    private static final double RESTOCK_CENTER_TOLERANCE = 0.04;
    private static final int RUNNING_STALL_TICKS = 16;
    private static final double RUNNING_STALL_MOVE_EPSILON_SQ = 0.0004;
    private static final double RUNNING_NUDGE_MIN_DIST_SQ = 0.04;
    private static final int RESTOCK_STALL_TICKS = 8;
    private static final double RESTOCK_STALL_MOVE_EPSILON_SQ = 0.00025;

    private final HighwayBuilder module;

    private int runningStallTicks;
    private Vec3d lastRunningPos;
    private int restockStallTicks;
    private Vec3d lastRestockPos;

    PathfinderMovementController(HighwayBuilder module) {
        this.module = module;
    }

    void moveTo(Vec3d target) {
        if (mc.player == null) return;
        double speed = module.moveSpeed.get();
        mc.player.setVelocity(
            Math.max(-speed, Math.min(speed, target.x - mc.player.getX())),
            mc.player.getVelocity().y,
            Math.max(-speed, Math.min(speed, target.z - mc.player.getZ()))
        );
    }

    boolean isCenteredOn(Vec3d target) {
        if (mc.player == null) return false;
        return horizontalDistanceSq(target, mc.player.getEntityPos())
            <= RESTOCK_CENTER_TOLERANCE * RESTOCK_CENTER_TOLERANCE;
    }

    void stopHorizontalMovement() {
        if (mc.player == null) return;
        mc.player.setVelocity(0.0, mc.player.getVelocity().y, 0.0);
    }

    double horizontalDistanceSq(Vec3d a, Vec3d b) {
        double dx = a.x - b.x;
        double dz = a.z - b.z;
        return dx * dx + dz * dz;
    }

    void applyRunningStallNudge(BlockPos goal, MovementState moveState, Runnable refreshPathing, Runnable refreshMovement) {
        if (mc.player == null || goal == null || moveState != MovementState.RUNNING) {
            runningStallTicks = 0;
            lastRunningPos = mc.player != null ? mc.player.getEntityPos() : null;
            return;
        }

        Vec3d currentPos = mc.player.getEntityPos();
        Vec3d goalCenter = Vec3d.ofCenter(goal);
        double distSq = horizontalDistanceSq(currentPos, goalCenter);
        if (distSq <= RUNNING_NUDGE_MIN_DIST_SQ) {
            runningStallTicks = 0;
            lastRunningPos = currentPos;
            return;
        }

        if (lastRunningPos != null
            && horizontalDistanceSq(currentPos, lastRunningPos) <= RUNNING_STALL_MOVE_EPSILON_SQ) {
            runningStallTicks++;
        } else {
            runningStallTicks = 0;
        }
        lastRunningPos = currentPos;

        if (runningStallTicks >= RUNNING_STALL_TICKS) {
            refreshPathing.run();
            refreshMovement.run();
            moveTo(goalCenter);
            runningStallTicks = 0;
        }
    }

    void applyRestockStallRecovery(MovementState moveState, Vec3d standTarget, boolean canInteract) {
        if (mc.player == null || module.containerHandler == null || moveState != MovementState.RESTOCK) {
            restockStallTicks = 0;
            lastRestockPos = mc.player != null ? mc.player.getEntityPos() : null;
            return;
        }

        if (isCenteredOn(standTarget) && canInteract) {
            restockStallTicks = 0;
            lastRestockPos = mc.player.getEntityPos();
            return;
        }

        Vec3d currentPos = mc.player.getEntityPos();
        if (lastRestockPos != null
            && horizontalDistanceSq(currentPos, lastRestockPos) <= RESTOCK_STALL_MOVE_EPSILON_SQ) {
            restockStallTicks++;
        } else {
            restockStallTicks = 0;
        }
        lastRestockPos = currentPos;

        if (restockStallTicks < RESTOCK_STALL_TICKS) return;

        module.containerHandler.invalidateRestockStandTarget();
        if (!module.containerHandler.tryRelocateContainerPlacement()) {
            moveTo(module.containerHandler.getRestockStandPos());
        }

        restockStallTicks = 0;
    }

    void resetRestockTracking() {
        restockStallTicks = 0;
        lastRestockPos = mc.player != null ? mc.player.getEntityPos() : null;
    }

    void clearRunningTracking() {
        runningStallTicks = 0;
        lastRunningPos = null;
    }
}



