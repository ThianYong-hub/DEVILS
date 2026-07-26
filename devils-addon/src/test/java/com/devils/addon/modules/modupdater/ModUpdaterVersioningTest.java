package com.devils.addon.modules.modupdater;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModUpdaterVersioningTest {
    private static final String SAME_FILE = "somemod.jar";

    private static InstalledMod installed(String version, String fileName) {
        return new InstalledMod(null, fileName, "somemod", version, List.of(), true, null);
    }

    private static RemoteRelease remote(String version, String fileName) {
        return new RemoteRelease(SourceProvider.MODRINTH, "somemod", version, fileName, "https://example.invalid/" + fileName);
    }

    // --- needsUpdate / isRemoteOlderThanInstalled -------------------------------------------------

    @Test
    void updatesWhenTheRemoteFabricVersionIsHigher() {
        assertTrue(ModUpdaterVersioning.needsUpdate(
            installed("1.2.3+1.21.11", SAME_FILE),
            remote("1.2.4+1.21.11", SAME_FILE)
        ));
        assertFalse(ModUpdaterVersioning.isRemoteOlderThanInstalled(
            installed("1.2.3+1.21.11", SAME_FILE),
            remote("1.2.4+1.21.11", SAME_FILE)
        ));
    }

    @Test
    void refusesToDowngradeToAnOlderRemoteVersion() {
        InstalledMod mod = installed("1.2.4+1.21.11", "somemod-1.2.4.jar");
        RemoteRelease release = remote("1.2.3+1.21.11", "somemod-1.2.3.jar");

        assertFalse(ModUpdaterVersioning.needsUpdate(mod, release));
        assertTrue(ModUpdaterVersioning.isRemoteOlderThanInstalled(mod, release));
    }

    @Test
    void identicalVersionsOnlyUpdateWhenTheJarNameChanges() {
        assertFalse(ModUpdaterVersioning.needsUpdate(
            installed("1.2.3+1.21.11", "somemod-1.2.3.jar"),
            remote("1.2.3+1.21.11", "somemod-1.2.3.jar")
        ));
        assertTrue(ModUpdaterVersioning.needsUpdate(
            installed("1.2.3+1.21.11", "somemod-1.2.3.jar"),
            remote("1.2.3+1.21.11", "somemod-1.2.3-rebuild.jar")
        ));
        // File names are matched case-insensitively.
        assertFalse(ModUpdaterVersioning.needsUpdate(
            installed("1.2.3+1.21.11", "SomeMod-1.2.3.JAR"),
            remote("1.2.3+1.21.11", "somemod-1.2.3.jar")
        ));
    }

    @Test
    void ignoresTheEmbeddedGameVersionWhenRankingModVersions() {
        // The mod version went 0.9.0 -> 0.8.0 even though the embedded game version went up.
        InstalledMod mod = installed("mc1.21.11-0.9.0-fabric", SAME_FILE);
        RemoteRelease release = remote("mc1.22.0-0.8.0-fabric", SAME_FILE);

        assertTrue(ModUpdaterVersioning.isRemoteOlderThanInstalled(mod, release));
        assertFalse(ModUpdaterVersioning.needsUpdate(mod, release));

        // ... and the genuine bump is still detected.
        assertTrue(ModUpdaterVersioning.needsUpdate(
            installed("mc1.21.11-0.8.0-fabric", SAME_FILE),
            remote("mc1.21.11-0.9.0-fabric", SAME_FILE)
        ));
    }

    @Test
    void treatsALeadingVPrefixAsTheSameVersionSpace() {
        assertFalse(ModUpdaterVersioning.needsUpdate(
            installed("v2.18.0", SAME_FILE),
            remote("2.18.0", SAME_FILE)
        ));
        assertFalse(ModUpdaterVersioning.isRemoteOlderThanInstalled(
            installed("v2.18.0", SAME_FILE),
            remote("2.18.0", SAME_FILE)
        ));
        assertTrue(ModUpdaterVersioning.needsUpdate(
            installed("v2.18.0", SAME_FILE),
            remote("v2.18.1", SAME_FILE)
        ));
        assertTrue(ModUpdaterVersioning.isRemoteOlderThanInstalled(
            installed("v2.18.1", SAME_FILE),
            remote("v2.18.0", SAME_FILE)
        ));
    }

    @Test
    void comparesSegmentsNumericallyRatherThanLexicographically() {
        assertTrue(ModUpdaterVersioning.needsUpdate(
            installed("0.25.2", SAME_FILE),
            remote("0.25.10", SAME_FILE)
        ));
        assertTrue(ModUpdaterVersioning.isRemoteOlderThanInstalled(
            installed("0.25.10", SAME_FILE),
            remote("0.25.2", SAME_FILE)
        ));
        assertFalse(ModUpdaterVersioning.isRemoteOlderThanInstalled(
            installed("0.25.2", SAME_FILE),
            remote("0.25.10", SAME_FILE)
        ));
    }

    @Test
    void padsMissingVersionSegmentsWithZero() {
        assertFalse(ModUpdaterVersioning.needsUpdate(
            installed("1.2", SAME_FILE),
            remote("1.2.0", SAME_FILE)
        ));
        assertFalse(ModUpdaterVersioning.isRemoteOlderThanInstalled(
            installed("1.2", SAME_FILE),
            remote("1.2.0", SAME_FILE)
        ));
        assertTrue(ModUpdaterVersioning.isRemoteOlderThanInstalled(
            installed("1.2.1", SAME_FILE),
            remote("1.2", SAME_FILE)
        ));
    }

    @Test
    void fallsBackToJarNameComparisonWhenVersionsAreMissing() {
        assertTrue(ModUpdaterVersioning.needsUpdate(installed(null, "old.jar"), remote("", "new.jar")));
        assertFalse(ModUpdaterVersioning.needsUpdate(installed(null, "same.jar"), remote("", "same.jar")));
        assertTrue(ModUpdaterVersioning.needsUpdate(installed("   ", "old.jar"), remote("1.0.0", "new.jar")));
        assertFalse(ModUpdaterVersioning.needsUpdate(installed("   ", "same.jar"), remote("1.0.0", "same.jar")));
    }

    @Test
    void malformedVersionsNeverCountAsADowngrade() {
        assertFalse(ModUpdaterVersioning.isRemoteOlderThanInstalled(null, remote("1.0.0", SAME_FILE)));
        assertFalse(ModUpdaterVersioning.isRemoteOlderThanInstalled(installed("1.0.0", SAME_FILE), null));
        assertFalse(ModUpdaterVersioning.isRemoteOlderThanInstalled(installed(null, SAME_FILE), remote("1.0.0", SAME_FILE)));
        assertFalse(ModUpdaterVersioning.isRemoteOlderThanInstalled(installed("1.0.0", SAME_FILE), remote(" ", SAME_FILE)));
        assertFalse(ModUpdaterVersioning.isRemoteOlderThanInstalled(
            installed("release-candidate", SAME_FILE),
            remote("nightly", SAME_FILE)
        ));
    }

    @Test
    void olderReleaseDetailNamesBothVersions() {
        assertEquals(
            "available-release-older-than-installed current=1.2.4 remote=1.2.3",
            ModUpdaterVersioning.buildOlderReleaseDetail(installed("1.2.4", SAME_FILE), remote("1.2.3", SAME_FILE))
        );
        assertEquals(
            "available-release-older-than-installed",
            ModUpdaterVersioning.buildOlderReleaseDetail(installed(null, SAME_FILE), remote("  ", SAME_FILE))
        );
        assertEquals(
            "available-release-older-than-installed",
            ModUpdaterVersioning.buildOlderReleaseDetail(null, null)
        );
    }

    // --- extractVersionSeries ---------------------------------------------------------------------

    @Test
    void versionSeriesKeepsOnlyMajorAndMinor() {
        assertEquals("1.21", ModUpdaterVersioning.extractVersionSeries("1.21.11"));
        assertEquals("1.21", ModUpdaterVersioning.extractVersionSeries("1.21"));
        assertEquals("1.21", ModUpdaterVersioning.extractVersionSeries("  1.21.4  "));
        assertEquals("1", ModUpdaterVersioning.extractVersionSeries("1"));
        assertEquals("", ModUpdaterVersioning.extractVersionSeries(""));
        assertEquals("", ModUpdaterVersioning.extractVersionSeries(null));
        // No prefix stripping: the caller is expected to pass a bare game version.
        assertEquals("mc1.21", ModUpdaterVersioning.extractVersionSeries("mc1.21.11"));
    }

    // --- isMinecraftVersionRangeContaining ---------------------------------------------------------

    @Test
    void minecraftVersionRangeIsInclusiveAtBothEnds() {
        assertTrue(ModUpdaterVersioning.isMinecraftVersionRangeContaining("1.21.1-1.21.11", "1.21.5"));
        assertTrue(ModUpdaterVersioning.isMinecraftVersionRangeContaining("1.21.1-1.21.11", "1.21.1"));
        assertTrue(ModUpdaterVersioning.isMinecraftVersionRangeContaining("1.21.1-1.21.11", "1.21.11"));
        assertTrue(ModUpdaterVersioning.isMinecraftVersionRangeContaining("1.20-1.21", "1.21"));
        assertTrue(ModUpdaterVersioning.isMinecraftVersionRangeContaining("  1.21.1-1.21.11  ", "1.21.5"));
    }

    @Test
    void minecraftVersionRangeExcludesVersionsOutsideTheBounds() {
        assertFalse(ModUpdaterVersioning.isMinecraftVersionRangeContaining("1.21.1-1.21.11", "1.21.12"));
        assertFalse(ModUpdaterVersioning.isMinecraftVersionRangeContaining("1.21.1-1.21.11", "1.20.6"));
        assertFalse(ModUpdaterVersioning.isMinecraftVersionRangeContaining("1.21.1-1.21.11", "1.22"));
        // 1.21 sorts below the 1.21.1 lower bound.
        assertFalse(ModUpdaterVersioning.isMinecraftVersionRangeContaining("1.21.1-1.21.11", "1.21"));
    }

    @Test
    void minecraftVersionRangeRejectsMalformedCandidatesAndTargets() {
        assertFalse(ModUpdaterVersioning.isMinecraftVersionRangeContaining("1.21.11", "1.21.11"));
        assertFalse(ModUpdaterVersioning.isMinecraftVersionRangeContaining("-1.21.11", "1.21.11"));
        assertFalse(ModUpdaterVersioning.isMinecraftVersionRangeContaining("1.21.1-", "1.21.5"));
        assertFalse(ModUpdaterVersioning.isMinecraftVersionRangeContaining("abc-def", "1.21.5"));
        assertFalse(ModUpdaterVersioning.isMinecraftVersionRangeContaining("1.21.1-snapshot", "1.21.5"));
        assertFalse(ModUpdaterVersioning.isMinecraftVersionRangeContaining("", "1.21.5"));
        assertFalse(ModUpdaterVersioning.isMinecraftVersionRangeContaining(null, "1.21.5"));
        assertFalse(ModUpdaterVersioning.isMinecraftVersionRangeContaining("1.21.1-1.21.11", ""));
        assertFalse(ModUpdaterVersioning.isMinecraftVersionRangeContaining("1.21.1-1.21.11", "not-a-version"));
        // The candidate is trimmed but the target is matched verbatim.
        assertFalse(ModUpdaterVersioning.isMinecraftVersionRangeContaining("1.21.1-1.21.11", " 1.21.5"));
    }

    // --- detectIncompatibleTargetHint --------------------------------------------------------------

    @Test
    void flagsAReleaseBuiltForADifferentGameSeries() {
        assertEquals(
            "1.20.1",
            ModUpdaterVersioning.detectIncompatibleTargetHint(remote("1.0.0", "somemod-mc1.20.1-fabric.jar"), "1.21.11")
        );
        // The hint may come from the version string instead of the file name.
        assertEquals(
            "1.19.4",
            ModUpdaterVersioning.detectIncompatibleTargetHint(remote("mc1.19.4-1.0.0", "somemod.jar"), "1.21.11")
        );
    }

    @Test
    void flagsAReleaseBuiltForANewerPatchOfTheSameSeries() {
        assertEquals(
            "1.21.12",
            ModUpdaterVersioning.detectIncompatibleTargetHint(remote("1.0.0", "somemod-mc1.21.12-fabric.jar"), "1.21.11")
        );
    }

    @Test
    void acceptsReleasesMatchingOrPredatingTheTargetPatch() {
        assertEquals(
            "",
            ModUpdaterVersioning.detectIncompatibleTargetHint(remote("1.0.0", "somemod-mc1.21.11-fabric.jar"), "1.21.11")
        );
        assertEquals(
            "",
            ModUpdaterVersioning.detectIncompatibleTargetHint(remote("1.0.0", "somemod-mc1.21.5-fabric.jar"), "1.21.11")
        );
        // Nothing in the release names a game version at all.
        assertEquals(
            "",
            ModUpdaterVersioning.detectIncompatibleTargetHint(remote("1.0.0", "somemod-1.0.0.jar"), "1.21.11")
        );
    }

    @Test
    void skipsCompatibilityDetectionWithoutAReleaseOrTarget() {
        assertEquals("", ModUpdaterVersioning.detectIncompatibleTargetHint(null, "1.21.11"));
        assertEquals("", ModUpdaterVersioning.detectIncompatibleTargetHint(remote("1.0.0", "somemod-mc1.20.1.jar"), ""));
        assertEquals("", ModUpdaterVersioning.detectIncompatibleTargetHint(remote("1.0.0", "somemod-mc1.20.1.jar"), null));
        // A series-only target has no patch to compare, so same-series patches are accepted.
        assertEquals("", ModUpdaterVersioning.detectIncompatibleTargetHint(remote("1.0.0", "somemod-mc1.21.12.jar"), "1.21"));
    }
}
