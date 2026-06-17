package com.devils.addon;

import com.devils.addon.util.smoke.AssimilatedInteractionChecks;
import com.devils.addon.util.smoke.SmokeCheckResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class AssimilatedInteractionRegressionTest {
    @Test
    void searchablesFilteringAndAutocompleteRemainUsable() {
        assertSuccess(AssimilatedInteractionChecks.searchablesFlow());
    }

    @Test
    void yaclOptionLifecycleAppliesAndResetsState() {
        assertSuccess(AssimilatedInteractionChecks.yaclOptionLifecycle());
    }

    private static void assertSuccess(SmokeCheckResult result) {
        assertTrue(result.success(), result.id() + " failed: " + result.detail());
    }
}
