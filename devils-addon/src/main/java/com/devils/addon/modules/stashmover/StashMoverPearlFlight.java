package com.devils.addon.modules.stashmover;

import net.minecraft.util.math.Vec3d;

/**
 * Derived flight metrics for the tracked own-pearl plus the pure readiness rules used by the
 * stasis phase. Values mirror the arithmetic the runtime used inline; nothing here touches module
 * state.
 */
record StashMoverPearlFlight(
    Vec3d target,
    Vec3d pearlPos,
    Vec3d pearlVelocity,
    double distanceSq,
    double horizontalSq,
    double verticalDelta,
    double verticalAbs,
    double verticalBelow,
    double horizontalVelocitySq,
    double speedSq
) {
    static StashMoverPearlFlight of(Vec3d target, Vec3d pearlPos, Vec3d pearlVelocity) {
        double distanceSq = target == null ? 0.0 : pearlPos.squaredDistanceTo(target);
        double horizontalSq = target == null ? 0.0 : squaredHorizontalDistance(pearlPos, target);
        double verticalDelta = target == null ? 0.0 : pearlPos.y - target.y;
        double verticalAbs = Math.abs(verticalDelta);
        double verticalBelow = target == null ? 0.0 : Math.max(0.0, target.y - pearlPos.y);
        double horizontalVelocitySq = pearlVelocity.x * pearlVelocity.x + pearlVelocity.z * pearlVelocity.z;
        double speedSq = pearlVelocity.lengthSquared();
        return new StashMoverPearlFlight(
            target,
            pearlPos,
            pearlVelocity,
            distanceSq,
            horizontalSq,
            verticalDelta,
            verticalAbs,
            verticalBelow,
            horizontalVelocitySq,
            speedSq
        );
    }

    boolean nearTarget() {
        return target == null || distanceSq <= 9.0;
    }

    boolean isReturnFlightConfirmed(int stasisTicks) {
        if (target == null || pearlPos == null || pearlVelocity == null) return false;
        return stasisTicks >= 10
            && horizontalSq <= 0.15 * 0.15
            && verticalBelow <= 5.0
            && pearlVelocity.y >= 0.18
            && pearlVelocity.y <= 0.35
            && horizontalVelocitySq <= 0.015 * 0.015;
    }

    boolean isLethalReturnFlightConfirmed(int stasisTicks) {
        if (target == null || pearlPos == null || pearlVelocity == null) return false;
        return stasisTicks >= 10
            && horizontalSq <= 0.24 * 0.24
            && verticalBelow <= 3.25
            && pearlVelocity.y >= 0.10
            && pearlVelocity.y <= 0.40
            && horizontalVelocitySq <= 0.035 * 0.035;
    }

    boolean isLethalReturnLaunched(int stasisTicks) {
        if (pearlPos == null || pearlVelocity == null) return false;
        return stasisTicks >= 3
            && speedSq >= 0.0025
            && verticalBelow <= 8.0;
    }

    boolean isReturnSettled(int stasisTicks) {
        return target != null
            && stasisTicks >= 20
            && horizontalSq <= 0.12 * 0.12
            && verticalAbs <= 0.25
            && pearlVelocity.y >= 0.0
            && pearlVelocity.y <= 0.12
            && horizontalVelocitySq <= 0.015 * 0.015;
    }

    boolean isLethalReturnSettled(int stasisTicks) {
        return target != null
            && stasisTicks >= 35
            && horizontalSq <= 0.10 * 0.10
            && verticalAbs <= 0.15
            && Math.abs(pearlVelocity.y) <= 0.04
            && horizontalVelocitySq <= 0.010 * 0.010;
    }

    boolean isChamberSettled(int stasisTicks) {
        return target == null
            || (stasisTicks >= 18
                && horizontalSq <= 0.25 * 0.25
                && verticalAbs <= 0.35
                && Math.abs(pearlVelocity.y) <= 0.12);
    }

    private static double squaredHorizontalDistance(Vec3d a, Vec3d b) {
        double dx = a.x - b.x;
        double dz = a.z - b.z;
        return dx * dx + dz * dz;
    }
}
