package com.devils.addon.modules.stashmover;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/**
 * Pure geometry for the pearl throw pose: return-throw centering, water stabilisation checks and
 * the local snap target. Callers keep every player mutation and packet send on their side.
 */
final class StashMoverThrowGeometry {
    private static final double RETURN_THROW_CENTER_TOLERANCE = 0.07;
    private static final double RETURN_THROW_HORIZONTAL_NUDGE_MAX = 0.08;
    private static final double RETURN_THROW_MIN_EYE_HEIGHT_ABOVE_TARGET = 0.35;
    private static final double RETURN_THROW_MAX_EYE_HEIGHT_ABOVE_TARGET = 2.80;
    private static final double RETURN_THROW_MAX_HORIZONTAL_SPEED = 0.025;
    private static final double STABILIZE_HORIZONTAL_TOLERANCE = 0.18;
    private static final double STABILIZE_HEIGHT_TOLERANCE = 0.22;

    private StashMoverThrowGeometry() {
    }

    static ReturnThrowPose evaluateReturnThrowPose(Vec3d playerPos, Vec3d eyePos, Vec3d velocity, Vec3d pearlTarget) {
        double dx = pearlTarget.x - playerPos.x;
        double dz = pearlTarget.z - playerPos.z;
        double horizontalSq = dx * dx + dz * dz;
        double eyeVerticalDelta = eyePos.y - pearlTarget.y;
        double horizontalVelocitySq = velocity.x * velocity.x + velocity.z * velocity.z;
        boolean centered = horizontalSq <= RETURN_THROW_CENTER_TOLERANCE * RETURN_THROW_CENTER_TOLERANCE;
        boolean heightReady = eyeVerticalDelta >= RETURN_THROW_MIN_EYE_HEIGHT_ABOVE_TARGET
            && eyeVerticalDelta <= RETURN_THROW_MAX_EYE_HEIGHT_ABOVE_TARGET;
        boolean calm = horizontalVelocitySq <= RETURN_THROW_MAX_HORIZONTAL_SPEED * RETURN_THROW_MAX_HORIZONTAL_SPEED;
        return new ReturnThrowPose(dx, dz, horizontalSq, eyeVerticalDelta, horizontalVelocitySq, centered, heightReady, calm);
    }

    static WaterStabilize evaluateWaterStabilize(Vec3d playerPos, BlockPos water) {
        Vec3d waterCenter = Vec3d.ofCenter(water);
        double dx = playerPos.x - waterCenter.x;
        double dz = playerPos.z - waterCenter.z;
        double horizontalSq = dx * dx + dz * dz;
        boolean nearWaterCenter = horizontalSq <= STABILIZE_HORIZONTAL_TOLERANCE * STABILIZE_HORIZONTAL_TOLERANCE;
        boolean tooHighAboveWater = playerPos.y > waterCenter.y + STABILIZE_HEIGHT_TOLERANCE;
        return new WaterStabilize(waterCenter, horizontalSq, nearWaterCenter, tooHighAboveWater);
    }

    static SnapTarget resolveSnapTarget(BlockPos approachGoal, BlockPos water) {
        if (approachGoal != null) {
            Vec3d goalCenter = new Vec3d(approachGoal.getX() + 0.5, approachGoal.getY(), approachGoal.getZ() + 0.5);
            return new SnapTarget(goalCenter, "approach-goal");
        }

        Vec3d waterCenter = Vec3d.ofCenter(water);
        return new SnapTarget(new Vec3d(waterCenter.x, water.getY() + 0.61, waterCenter.z), "water-center");
    }

    record ReturnThrowPose(
        double dx,
        double dz,
        double horizontalSq,
        double eyeVerticalDelta,
        double horizontalVelocitySq,
        boolean centered,
        boolean heightReady,
        boolean calm
    ) {
        boolean ready() {
            return centered && heightReady && calm;
        }

        boolean aboveMaxEyeHeight() {
            return eyeVerticalDelta > RETURN_THROW_MAX_EYE_HEIGHT_ABOVE_TARGET;
        }

        boolean needsHorizontalNudge() {
            return horizontalDistance() > RETURN_THROW_CENTER_TOLERANCE;
        }

        Vec3d nudgedVelocity(double velocityY) {
            double distance = horizontalDistance();
            double nudge = Math.min(RETURN_THROW_HORIZONTAL_NUDGE_MAX, Math.max(0.015, distance * 0.35));
            return new Vec3d(dx / distance * nudge, velocityY, dz / distance * nudge);
        }

        private double horizontalDistance() {
            return Math.sqrt(horizontalSq);
        }
    }

    record WaterStabilize(Vec3d waterCenter, double horizontalSq, boolean nearWaterCenter, boolean tooHighAboveWater) {
    }

    record SnapTarget(Vec3d position, String reason) {
    }
}
