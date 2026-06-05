package com.devils.addon.modules;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NukerPlusTest {
    @Test
    void cubeAnchorStaysOnSameLayerForSoulSandHeight() {
        assertEquals(65, NukerPlus.resolveCubeBaseY(65.0));
        assertEquals(65, NukerPlus.resolveCubeBaseY(64.875));
    }

    @Test
    void cubeAnchorDoesNotJumpUpWhileFlyingLowInBlock() {
        assertEquals(64, NukerPlus.resolveCubeBaseY(64.3));
        assertEquals(65, NukerPlus.resolveCubeBaseY(64.999));
    }

    @Test
    void instaChainEligibilityUsesHalfDeltaThreshold() {
        assertFalse(NukerPlus.isInstaChainEligible(0.5f));
        assertTrue(NukerPlus.isInstaChainEligible(0.5001f));
        assertTrue(NukerPlus.isInstaChainEligible(0.9f));
    }

    @Test
    void vanillaBreakTicksUseInverseDeltaCeil() {
        assertEquals(0, NukerPlus.calculateVanillaBreakTicks(0.0f));
        assertEquals(5, NukerPlus.calculateVanillaBreakTicks(0.2f));
        assertEquals(3, NukerPlus.calculateVanillaBreakTicks(0.34f));
        assertEquals(80, NukerPlus.calculateVanillaBreakTicks(0.0125f));
    }

    @Test
    void targetBreakTicksUseMioRemainingProgress() {
        assertEquals(1, NukerPlus.calculateTargetBreakTicks(100, 1.00));
        assertEquals(10, NukerPlus.calculateTargetBreakTicks(100, 0.90));
        assertEquals(20, NukerPlus.calculateTargetBreakTicks(100, 0.80));
        assertEquals(30, NukerPlus.calculateTargetBreakTicks(100, 0.70));
        assertEquals(40, NukerPlus.calculateTargetBreakTicks(100, 0.60));
    }

    @Test
    void targetBreakTicksClampIntoValidRange() {
        assertEquals(0, NukerPlus.calculateTargetBreakTicks(0, 0.70));
        assertEquals(1, NukerPlus.calculateTargetBreakTicks(1, 0.60));
        assertEquals(4, NukerPlus.calculateTargetBreakTicks(10, 0.10));
        assertEquals(1, NukerPlus.calculateTargetBreakTicks(10, 5.0));
    }

    @Test
    void targetBreakTicksUseContinuousDeltaWhenAvailable() {
        assertEquals(1, NukerPlus.calculateTargetBreakTicks(8, 1.00, 0.13f));
        assertEquals(1, NukerPlus.calculateTargetBreakTicks(8, 0.90, 0.13f));
        assertEquals(2, NukerPlus.calculateTargetBreakTicks(8, 0.80, 0.13f));
        assertEquals(4, NukerPlus.calculateTargetBreakTicks(8, 0.60, 0.13f));
        assertEquals(1, NukerPlus.calculateTargetBreakTicks(2, 0.60, 0.80f));
    }
}
