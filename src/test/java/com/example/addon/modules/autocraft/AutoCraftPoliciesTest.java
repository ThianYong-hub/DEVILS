package com.example.addon.modules.autocraft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AutoCraftPoliciesTest {
    @Test
    void computesRemainingLimitAndBatchCap() {
        assertEquals(Integer.MAX_VALUE, AutoCraftPolicies.remainingLimit(0, 12));
        assertEquals(6, AutoCraftPolicies.remainingLimit(10, 4));
        assertEquals(2, AutoCraftPolicies.maxBatchesForLimit(10, 2, 4));
        assertEquals(0, AutoCraftPolicies.maxBatchesForLimit(3, 0, 4));
    }

    @Test
    void separatesDropAndFastClosePolicies() {
        assertTrue(AutoCraftPolicies.shouldDropFinalOutput(true, true));
        assertFalse(AutoCraftPolicies.shouldDropFinalOutput(true, false));
        assertTrue(AutoCraftPolicies.shouldFastClose(true, true, true));
        assertFalse(AutoCraftPolicies.shouldFastClose(true, false, true));
        assertTrue(AutoCraftPolicies.shouldPlanNextCycle(true, 4));
        assertTrue(AutoCraftPolicies.shouldPlanNextCycle(false, 0));
        assertFalse(AutoCraftPolicies.shouldPlanNextCycle(false, 1));
    }

    @Test
    void autoOpenSuppressionPreventsImmediateReopenLoop() {
        long suppressedUntil = AutoCraftPolicies.suppressAutoOpenUntil(100, 60);

        assertFalse(AutoCraftPolicies.shouldAttemptAutoOpen(120, suppressedUntil, 100, 20));
        assertTrue(AutoCraftPolicies.shouldAttemptAutoOpen(160, suppressedUntil, 100, 20));
        assertFalse(AutoCraftPolicies.shouldAttemptAutoOpen(165, 0, 160, 20));
        assertTrue(AutoCraftPolicies.shouldAttemptAutoOpen(180, 0, 160, 20));
    }
}
