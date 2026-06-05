package com.devils.addon;

import com.devils.addon.util.smoke.AssimilatedInteractionChecks;
import com.devils.addon.util.smoke.SmokeCheckResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class AssimilatedInteractionRegressionTest {
    @Test
    void chestTrackerLocalSettingsPayloadRoundTripRemainsUsable() {
        assertSuccess(AssimilatedInteractionChecks.chestTrackerLocalSettingsPayloadFlow());
    }

    @Test
    void searchablesFilteringAndAutocompleteRemainUsable() {
        assertSuccess(AssimilatedInteractionChecks.searchablesFlow());
    }

    @Test
    void yaclOptionLifecycleAppliesAndResetsState() {
        assertSuccess(AssimilatedInteractionChecks.yaclOptionLifecycle());
    }

    @Test
    void xaeroRefreshHookRemainsReachable() {
        assertSuccess(AssimilatedInteractionChecks.xaeroRefreshHookFlow());
    }

    @Test
    void xaeroPlusSettingLifecycleRemainsUsable() {
        assertSuccess(AssimilatedInteractionChecks.xaeroPlusSettingLifecycle());
    }

    private static void assertSuccess(SmokeCheckResult result) {
        assertTrue(result.success(), result.id() + " failed: " + result.detail());
    }
}
