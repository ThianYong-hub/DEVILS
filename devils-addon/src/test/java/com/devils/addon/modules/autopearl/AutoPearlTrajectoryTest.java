package com.devils.addon.modules.autopearl;

import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The pearl arc solver itself ({@code scorePitch}) simulates against {@code mc.world} block states,
 * so it cannot run without a live client. What is testable here is the contract every caller relies
 * on: when the client, world or target is unavailable the solver must hand back the caller's
 * fallback instead of a bogus rotation.
 */
class AutoPearlTrajectoryTest {
    @Test
    void pitchToPointReturnsTheFallbackWithoutAClient() {
        assertEquals(-31.0f, AutoPearlTrajectory.pitchToPoint(null, new Vec3d(10.0, 64.0, -3.0), 90.0f, -31.0f));
        assertEquals(0.0f, AutoPearlTrajectory.pitchToPoint(null, new Vec3d(10.0, 64.0, -3.0), 90.0f, 0.0f));
        assertEquals(45.5f, AutoPearlTrajectory.pitchToPoint(null, null, -180.0f, 45.5f));
    }

    @Test
    void pitchToTargetReturnsTheFallbackWithoutAClientOrTarget() {
        assertEquals(12.5f, AutoPearlTrajectory.pitchToTarget(null, null, 0.0f, 12.5f));
        assertEquals(-80.0f, AutoPearlTrajectory.pitchToTarget(null, null, 137.25f, -80.0f));
    }

    @Test
    void targetValidationRejectsAMissingClientOrTarget() {
        assertFalse(AutoPearlTrajectory.Targeting.isValidTarget(null, null, false, false, 10.0, 20.0));
        assertFalse(AutoPearlTrajectory.Targeting.isValidTarget(null, null, true, true, 0.0, 0.0));
    }

    @Test
    void targetResolutionYieldsNothingWithoutAClient() {
        assertNull(AutoPearlTrajectory.Targeting.resolveTarget(null, null, null, null, player -> true));
        assertNull(AutoPearlTrajectory.Targeting.resolveTarget(null, null, "Bandit", null, player -> true));
    }

    @Test
    void playerLookupYieldsNothingWithoutAWorldOrName() {
        assertNull(AutoPearlTrajectory.Targeting.findPlayerByName(null, "Bandit"));
        assertNull(AutoPearlTrajectory.Targeting.findPlayerByName(null, null));
    }
}
