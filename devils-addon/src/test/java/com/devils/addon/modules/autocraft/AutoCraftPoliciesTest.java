package com.devils.addon.modules.autocraft;

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

    @Test
    void remainingLimitTreatsNonPositiveLimitsAsUnlimited() {
        assertEquals(Integer.MAX_VALUE, AutoCraftPolicies.remainingLimit(AutoCraftPolicies.UNLIMITED_LIMIT, 0));
        assertEquals(Integer.MAX_VALUE, AutoCraftPolicies.remainingLimit(-5, 3));
        assertEquals(0, AutoCraftPolicies.UNLIMITED_LIMIT);
    }

    @Test
    void remainingLimitNeverGoesNegativeAndIgnoresNegativeCraftedCounts() {
        assertEquals(0, AutoCraftPolicies.remainingLimit(10, 10));
        assertEquals(0, AutoCraftPolicies.remainingLimit(10, 999));
        assertEquals(10, AutoCraftPolicies.remainingLimit(10, -5));
        assertEquals(1, AutoCraftPolicies.remainingLimit(10, 9));
    }

    @Test
    void maxBatchesForLimitFloorsAndGuardsAgainstEmptyRecipes() {
        assertEquals(Integer.MAX_VALUE, AutoCraftPolicies.maxBatchesForLimit(0, 500, 4));
        assertEquals(2, AutoCraftPolicies.maxBatchesForLimit(11, 0, 4));
        assertEquals(0, AutoCraftPolicies.maxBatchesForLimit(3, 0, 4));
        assertEquals(0, AutoCraftPolicies.maxBatchesForLimit(64, 0, 0));
        assertEquals(0, AutoCraftPolicies.maxBatchesForLimit(64, 0, -1));
        assertEquals(0, AutoCraftPolicies.maxBatchesForLimit(0, 0, 0), "an unlimited run still cannot batch a 0-output recipe");
    }

    @Test
    void planNextCycleOnlyStopsWhenSomethingWasCraftedWithoutCraftAll() {
        assertTrue(AutoCraftPolicies.shouldPlanNextCycle(true, 0));
        assertTrue(AutoCraftPolicies.shouldPlanNextCycle(true, 64));
        assertTrue(AutoCraftPolicies.shouldPlanNextCycle(false, 0));
        assertTrue(AutoCraftPolicies.shouldPlanNextCycle(false, -1));
        assertFalse(AutoCraftPolicies.shouldPlanNextCycle(false, 1));
        assertFalse(AutoCraftPolicies.shouldPlanNextCycle(false, 64));
    }

    @Test
    void dropAndFastCloseCoverTheirFullTruthTables() {
        assertTrue(AutoCraftPolicies.shouldDropFinalOutput(true, true));
        assertFalse(AutoCraftPolicies.shouldDropFinalOutput(true, false));
        assertFalse(AutoCraftPolicies.shouldDropFinalOutput(false, true));
        assertFalse(AutoCraftPolicies.shouldDropFinalOutput(false, false));

        assertTrue(AutoCraftPolicies.shouldFastClose(true, true, true));
        assertFalse(AutoCraftPolicies.shouldFastClose(false, true, true));
        assertFalse(AutoCraftPolicies.shouldFastClose(true, false, true), "never close a screen the module did not open");
        assertFalse(AutoCraftPolicies.shouldFastClose(true, true, false), "never close while the executor is mid-step");
        assertFalse(AutoCraftPolicies.shouldFastClose(false, false, false));
    }

    @Test
    void autoOpenRetryHonoursTheCooldownBoundaryAndFirstAttempt() {
        // A negative "last attempt" marks a run that has never tried to open a table.
        assertTrue(AutoCraftPolicies.shouldAttemptAutoOpen(0, 0, -1, 20));
        assertFalse(AutoCraftPolicies.shouldAttemptAutoOpen(50, 100, -1, 20), "suppression outranks the first attempt");

        assertFalse(AutoCraftPolicies.shouldAttemptAutoOpen(179, 0, 160, 20));
        assertTrue(AutoCraftPolicies.shouldAttemptAutoOpen(180, 0, 160, 20));

        // Suppression is exclusive on its own tick.
        assertFalse(AutoCraftPolicies.shouldAttemptAutoOpen(99, 100, -1, 20));
        assertTrue(AutoCraftPolicies.shouldAttemptAutoOpen(100, 100, -1, 20));

        // A negative cooldown collapses to "retry immediately".
        assertTrue(AutoCraftPolicies.shouldAttemptAutoOpen(160, 0, 160, -5));
    }

    @Test
    void suppressAutoOpenUntilClampsNegativeSuppressionWindows() {
        assertEquals(160, AutoCraftPolicies.suppressAutoOpenUntil(100, 60));
        assertEquals(100, AutoCraftPolicies.suppressAutoOpenUntil(100, 0));
        assertEquals(100, AutoCraftPolicies.suppressAutoOpenUntil(100, -40));
    }

    @Test
    void manualCloseSuppressionExpiresWellInsideTheStallBudget() {
        long closedAt = 1_000;
        long suppressedUntil = AutoCraftPolicies.suppressAutoOpenUntil(closedAt, AutoCraftPolicies.DEFAULT_MANUAL_CLOSE_SUPPRESSION_TICKS);

        assertFalse(AutoCraftPolicies.shouldAttemptAutoOpen(
            closedAt + AutoCraftPolicies.DEFAULT_MANUAL_CLOSE_SUPPRESSION_TICKS - 1,
            suppressedUntil,
            -1,
            AutoCraftPolicies.DEFAULT_AUTO_OPEN_RETRY_TICKS
        ));
        assertTrue(AutoCraftPolicies.shouldAttemptAutoOpen(
            closedAt + AutoCraftPolicies.DEFAULT_MANUAL_CLOSE_SUPPRESSION_TICKS,
            suppressedUntil,
            -1,
            AutoCraftPolicies.DEFAULT_AUTO_OPEN_RETRY_TICKS
        ));

        // If the module could not retry before the stall budget elapsed it would replan forever.
        assertTrue(
            AutoCraftPolicies.DEFAULT_MANUAL_CLOSE_SUPPRESSION_TICKS < AutoCraftPolicies.DEFAULT_BLOCKED_STALL_TICKS,
            "suppression window must fit inside the stall budget"
        );
        assertTrue(
            AutoCraftPolicies.DEFAULT_AUTO_OPEN_RETRY_TICKS < AutoCraftPolicies.DEFAULT_BLOCKED_STALL_TICKS,
            "at least one auto-open retry must fit inside the stall budget"
        );
        assertTrue(AutoCraftPolicies.DEFAULT_BLOCKED_STALL_TICKS > 0);
    }

    @Test
    void stallBudgetLeavesRoomForSeveralAutoOpenRetries() {
        long lastAttemptTick = -1;
        int attempts = 0;

        for (long tick = 0; tick < AutoCraftPolicies.DEFAULT_BLOCKED_STALL_TICKS; tick++) {
            if (!AutoCraftPolicies.shouldAttemptAutoOpen(tick, 0, lastAttemptTick, AutoCraftPolicies.DEFAULT_AUTO_OPEN_RETRY_TICKS)) {
                continue;
            }
            attempts++;
            lastAttemptTick = tick;
        }

        assertEquals(10, attempts, "auto-open retries that fit before a blocked step is abandoned");
        assertEquals(180, lastAttemptTick);
    }
}
