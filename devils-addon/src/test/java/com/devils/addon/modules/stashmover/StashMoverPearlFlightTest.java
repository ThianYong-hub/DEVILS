package com.devils.addon.modules.stashmover;

import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StashMoverPearlFlightTest {
    private static final double EPS = 1.0E-9;
    private static final Vec3d TARGET = new Vec3d(8.5, 64.0, -12.5);

    /** Builds a metrics snapshot directly so every predicate threshold can be probed in isolation. */
    private static StashMoverPearlFlight metrics(
        double distanceSq,
        double horizontalSq,
        double verticalDelta,
        double verticalBelow,
        double velocityY,
        double horizontalVelocitySq,
        double speedSq
    ) {
        return new StashMoverPearlFlight(
            TARGET,
            TARGET,
            new Vec3d(0.0, velocityY, 0.0),
            distanceSq,
            horizontalSq,
            verticalDelta,
            Math.abs(verticalDelta),
            verticalBelow,
            horizontalVelocitySq,
            speedSq
        );
    }

    private static StashMoverPearlFlight flight(
        double horizontalSq,
        double verticalDelta,
        double verticalBelow,
        double velocityY,
        double horizontalVelocitySq
    ) {
        return metrics(
            horizontalSq,
            horizontalSq,
            verticalDelta,
            verticalBelow,
            velocityY,
            horizontalVelocitySq,
            velocityY * velocityY + horizontalVelocitySq
        );
    }

    @Test
    void derivesDistanceAndVerticalMetricsForAPearlAboveTheTarget() {
        Vec3d target = new Vec3d(0.0, 64.0, 0.0);
        Vec3d pearl = new Vec3d(3.0, 68.0, 4.0);
        Vec3d velocity = new Vec3d(0.1, 0.2, -0.3);

        StashMoverPearlFlight flight = StashMoverPearlFlight.of(target, pearl, velocity);

        assertEquals(41.0, flight.distanceSq(), EPS);
        assertEquals(25.0, flight.horizontalSq(), EPS);
        assertEquals(4.0, flight.verticalDelta(), EPS);
        assertEquals(4.0, flight.verticalAbs(), EPS);
        assertEquals(0.0, flight.verticalBelow(), EPS);
        assertEquals(0.10, flight.horizontalVelocitySq(), EPS);
        assertEquals(0.14, flight.speedSq(), EPS);
        assertEquals(target, flight.target());
        assertEquals(pearl, flight.pearlPos());
        assertEquals(velocity, flight.pearlVelocity());
    }

    @Test
    void verticalBelowOnlyCountsHowFarThePearlSitsUnderTheTarget() {
        StashMoverPearlFlight below = StashMoverPearlFlight.of(
            new Vec3d(0.0, 70.0, 0.0),
            new Vec3d(0.0, 64.0, 0.0),
            Vec3d.ZERO
        );

        assertEquals(-6.0, below.verticalDelta(), EPS);
        assertEquals(6.0, below.verticalAbs(), EPS);
        assertEquals(6.0, below.verticalBelow(), EPS);
    }

    @Test
    void missingTargetZeroesGeometryButKeepsVelocityMetrics() {
        StashMoverPearlFlight flight = StashMoverPearlFlight.of(null, new Vec3d(1.0, 2.0, 3.0), new Vec3d(0.3, 0.4, 0.0));

        assertNull(flight.target());
        assertEquals(0.0, flight.distanceSq(), EPS);
        assertEquals(0.0, flight.horizontalSq(), EPS);
        assertEquals(0.0, flight.verticalDelta(), EPS);
        assertEquals(0.0, flight.verticalAbs(), EPS);
        assertEquals(0.0, flight.verticalBelow(), EPS);
        assertEquals(0.09, flight.horizontalVelocitySq(), EPS);
        assertEquals(0.25, flight.speedSq(), EPS);
    }

    @Test
    void nearTargetUsesAThreeBlockRadius() {
        assertTrue(metrics(8.99, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0).nearTarget());
        assertTrue(metrics(9.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0).nearTarget());
        assertFalse(metrics(9.01, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0).nearTarget());

        // Without a target there is nothing to be far from.
        assertTrue(StashMoverPearlFlight.of(null, new Vec3d(500.0, 0.0, 500.0), Vec3d.ZERO).nearTarget());
    }

    @Test
    void returnFlightConfirmationAcceptsTheThresholdValues() {
        StashMoverPearlFlight onEdge = flight(0.15 * 0.15, 0.0, 5.0, 0.18, 0.015 * 0.015);

        assertTrue(onEdge.isReturnFlightConfirmed(10));
        assertTrue(flight(0.0, 0.0, 0.0, 0.35, 0.0).isReturnFlightConfirmed(10));
        assertTrue(flight(0.0, 0.0, 0.0, 0.25, 0.0).isReturnFlightConfirmed(400));
    }

    @Test
    void returnFlightConfirmationRejectsEachThresholdBreach() {
        assertFalse(flight(0.15 * 0.15, 0.0, 5.0, 0.18, 0.015 * 0.015).isReturnFlightConfirmed(9), "stasis ticks");
        assertFalse(flight(0.15 * 0.15 + 1.0E-6, 0.0, 5.0, 0.18, 0.015 * 0.015).isReturnFlightConfirmed(10), "drift");
        assertFalse(flight(0.0, 0.0, 5.01, 0.18, 0.0).isReturnFlightConfirmed(10), "too far below target");
        assertFalse(flight(0.0, 0.0, 0.0, 0.17, 0.0).isReturnFlightConfirmed(10), "rising too slowly");
        assertFalse(flight(0.0, 0.0, 0.0, 0.36, 0.0).isReturnFlightConfirmed(10), "rising too fast");
        assertFalse(flight(0.0, 0.0, 0.0, -0.20, 0.0).isReturnFlightConfirmed(10), "falling");
        assertFalse(flight(0.0, 0.0, 0.0, 0.18, 0.015 * 0.015 + 1.0E-6).isReturnFlightConfirmed(10), "sideways drift");
    }

    @Test
    void returnFlightConfirmationNeedsATargetAndAVelocity() {
        StashMoverPearlFlight noTarget = new StashMoverPearlFlight(
            null, TARGET, new Vec3d(0.0, 0.25, 0.0), 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0625
        );
        StashMoverPearlFlight noVelocity = new StashMoverPearlFlight(
            TARGET, TARGET, null, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0
        );
        StashMoverPearlFlight noPosition = new StashMoverPearlFlight(
            TARGET, null, new Vec3d(0.0, 0.25, 0.0), 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0625
        );

        assertFalse(noTarget.isReturnFlightConfirmed(50));
        assertFalse(noVelocity.isReturnFlightConfirmed(50));
        assertFalse(noPosition.isReturnFlightConfirmed(50));

        assertFalse(noTarget.isLethalReturnFlightConfirmed(50));
        assertFalse(noVelocity.isLethalReturnFlightConfirmed(50));
        assertFalse(noPosition.isLethalReturnFlightConfirmed(50));

        assertFalse(noVelocity.isLethalReturnLaunched(50));
        assertFalse(noPosition.isLethalReturnLaunched(50));
    }

    @Test
    void lethalConfirmationTradesTighterHeightForLooserDriftAndSpeed() {
        // Drifting and rising slowly: only the lethal predicate accepts it.
        StashMoverPearlFlight sloppy = flight(0.20 * 0.20, 0.0, 3.0, 0.12, 0.030 * 0.030);
        assertFalse(sloppy.isReturnFlightConfirmed(12));
        assertTrue(sloppy.isLethalReturnFlightConfirmed(12));

        // Clean but deep below the target: only the normal predicate accepts it.
        StashMoverPearlFlight deep = flight(0.10 * 0.10, 0.0, 4.0, 0.20, 0.010 * 0.010);
        assertTrue(deep.isReturnFlightConfirmed(12));
        assertFalse(deep.isLethalReturnFlightConfirmed(12));
    }

    @Test
    void lethalConfirmationRejectsEachThresholdBreach() {
        assertTrue(flight(0.24 * 0.24, 0.0, 3.25, 0.10, 0.035 * 0.035).isLethalReturnFlightConfirmed(10));

        assertFalse(flight(0.24 * 0.24, 0.0, 3.25, 0.10, 0.035 * 0.035).isLethalReturnFlightConfirmed(9));
        assertFalse(flight(0.24 * 0.24 + 1.0E-6, 0.0, 3.25, 0.10, 0.0).isLethalReturnFlightConfirmed(10));
        assertFalse(flight(0.0, 0.0, 3.26, 0.10, 0.0).isLethalReturnFlightConfirmed(10));
        assertFalse(flight(0.0, 0.0, 0.0, 0.09, 0.0).isLethalReturnFlightConfirmed(10));
        assertFalse(flight(0.0, 0.0, 0.0, 0.41, 0.0).isLethalReturnFlightConfirmed(10));
        assertFalse(flight(0.0, 0.0, 0.0, 0.10, 0.035 * 0.035 + 1.0E-6).isLethalReturnFlightConfirmed(10));
    }

    @Test
    void lethalLaunchDetectionOnlyNeedsSpeedHeightAndThreeTicks() {
        assertTrue(metrics(0.0, 0.0, 0.0, 8.0, 0.05, 0.0, 0.0025).isLethalReturnLaunched(3));
        assertFalse(metrics(0.0, 0.0, 0.0, 8.0, 0.05, 0.0, 0.0025).isLethalReturnLaunched(2));
        assertFalse(metrics(0.0, 0.0, 0.0, 8.0, 0.04, 0.0, 0.0024).isLethalReturnLaunched(3));
        assertFalse(metrics(0.0, 0.0, 0.0, 8.01, 0.05, 0.0, 0.0025).isLethalReturnLaunched(3));

        // Unlike the confirmation predicates this one still fires without a stasis target.
        StashMoverPearlFlight untargeted = new StashMoverPearlFlight(
            null, TARGET, new Vec3d(0.0, 0.5, 0.0), 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.25
        );
        assertTrue(untargeted.isLethalReturnLaunched(3));
    }

    @Test
    void returnSettledRequiresTwentyTicksOfSlowUpwardDrift() {
        assertTrue(flight(0.12 * 0.12, 0.25, 0.0, 0.0, 0.015 * 0.015).isReturnSettled(20));
        assertTrue(flight(0.0, -0.25, 0.0, 0.12, 0.0).isReturnSettled(20));

        assertFalse(flight(0.0, 0.0, 0.0, 0.0, 0.0).isReturnSettled(19));
        assertFalse(flight(0.12 * 0.12 + 1.0E-6, 0.0, 0.0, 0.0, 0.0).isReturnSettled(20));
        assertFalse(flight(0.0, 0.26, 0.0, 0.0, 0.0).isReturnSettled(20));
        assertFalse(flight(0.0, -0.26, 0.0, 0.0, 0.0).isReturnSettled(20));
        assertFalse(flight(0.0, 0.0, 0.0, -0.01, 0.0).isReturnSettled(20), "a sinking pearl is not settled");
        assertFalse(flight(0.0, 0.0, 0.0, 0.13, 0.0).isReturnSettled(20));
        assertFalse(flight(0.0, 0.0, 0.0, 0.0, 0.015 * 0.015 + 1.0E-6).isReturnSettled(20));

        StashMoverPearlFlight noTarget = new StashMoverPearlFlight(
            null, TARGET, Vec3d.ZERO, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0
        );
        assertFalse(noTarget.isReturnSettled(999));
    }

    @Test
    void lethalReturnSettledIsTheStrictestPredicate() {
        StashMoverPearlFlight tight = flight(0.10 * 0.10, 0.15, 0.0, 0.04, 0.010 * 0.010);

        assertTrue(tight.isLethalReturnSettled(35));
        assertFalse(tight.isLethalReturnSettled(34));
        assertTrue(flight(0.0, 0.0, 0.0, -0.04, 0.0).isLethalReturnSettled(35), "symmetric on vertical speed");

        assertFalse(flight(0.10 * 0.10 + 1.0E-6, 0.0, 0.0, 0.0, 0.0).isLethalReturnSettled(35));
        assertFalse(flight(0.0, 0.16, 0.0, 0.0, 0.0).isLethalReturnSettled(35));
        assertFalse(flight(0.0, 0.0, 0.0, 0.041, 0.0).isLethalReturnSettled(35));
        assertFalse(flight(0.0, 0.0, 0.0, -0.041, 0.0).isLethalReturnSettled(35));
        assertFalse(flight(0.0, 0.0, 0.0, 0.0, 0.010 * 0.010 + 1.0E-6).isLethalReturnSettled(35));

        // Anything the strictest predicate accepts, the looser settle predicate accepts too.
        assertTrue(tight.isReturnSettled(35));
        assertTrue(tight.isChamberSettled(35));
    }

    @Test
    void chamberSettleShortCircuitsWithoutATarget() {
        StashMoverPearlFlight untargeted = new StashMoverPearlFlight(
            null, TARGET, new Vec3d(9.0, 9.0, 9.0), 999.0, 999.0, 999.0, 999.0, 999.0, 999.0, 999.0
        );
        assertTrue(untargeted.isChamberSettled(0));

        assertTrue(flight(0.25 * 0.25, 0.35, 0.0, 0.12, 0.0).isChamberSettled(18));
        assertFalse(flight(0.25 * 0.25, 0.35, 0.0, 0.12, 0.0).isChamberSettled(17));
        assertFalse(flight(0.25 * 0.25 + 1.0E-6, 0.0, 0.0, 0.0, 0.0).isChamberSettled(18));
        assertFalse(flight(0.0, 0.36, 0.0, 0.0, 0.0).isChamberSettled(18));
        assertFalse(flight(0.0, 0.0, 0.0, 0.13, 0.0).isChamberSettled(18));
        assertFalse(flight(0.0, 0.0, 0.0, -0.13, 0.0).isChamberSettled(18));

        // The chamber predicate ignores horizontal velocity entirely.
        assertTrue(flight(0.0, 0.0, 0.0, 0.0, 9.0).isChamberSettled(18));
    }
}
