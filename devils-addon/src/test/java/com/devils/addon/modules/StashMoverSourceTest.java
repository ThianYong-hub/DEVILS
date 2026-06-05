package com.devils.addon.modules;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StashMoverSourceTest {
    @Test
    void DevilsAddonRegistersStashMoverModuleAndCommand() throws IOException {
        String source = Files.readString(Path.of("src", "main", "java", "com", "devils", "addon", "DevilsAddon.java"));

        assertTrue(source.contains("modules.add(new StashMover());"));
        assertTrue(source.contains("Commands.add(new StashMoverCommand());"));
    }

    @Test
    void nativeSourceUsesRotationSyncOwnPearlTrackingAndSlotPolicy() throws IOException {
        String module = Files.readString(Path.of("src", "main", "java", "com", "devils", "addon", "modules", "stashmover", "StashMover.java"));
        String support = Files.readString(Path.of("src", "main", "java", "com", "devils", "addon", "modules", "stashmover", "StashMoverSupport.java"));
        String runtime = Files.readString(Path.of("src", "main", "java", "com", "devils", "addon", "modules", "stashmover", "StashMoverRuntime.java"));
        String interaction = Files.readString(Path.of("src", "main", "java", "com", "devils", "addon", "modules", "stashmover", "StashMoverInteraction.java"));
        String approach = Files.readString(Path.of("src", "main", "java", "com", "devils", "addon", "modules", "stashmover", "StashMoverPearlApproach.java"));
        String bridge = Files.readString(Path.of("src", "main", "java", "com", "devils", "addon", "modules", "stashmover", "StashMoverOwnPearlTracker.java"));

        assertTrue(runtime.contains("Rotations.rotate(yaw, pitch, ACTION_ROTATION_PRIORITY"));
        assertTrue(module.contains("ownPearlTracker.onPearlAdded"));
        assertTrue(runtime.contains("ownPearlTracker.tickAwaitingSpawn()"));
        assertTrue(interaction.contains("StashMoverSlotPolicy.selectPearlHotbarSlot"));
        assertTrue(runtime.contains("restoreDisplacedPearlChestStack"));
        assertTrue(runtime.contains("borrowedPearlChestSlot"));
        assertTrue(runtime.contains("pearlChestSwapPending"));
        assertTrue(support.contains(".name(\"source-loot-delay\")"));
        assertTrue(support.contains(".name(\"return-command\")"));
        assertTrue(runtime.contains("readyForChestAction(sourceLootDelay.get())"));
        assertTrue(runtime.contains("requestGoal(GoalKind.WATER, water, \"wait-for-pearl-water\")"));
        assertTrue(runtime.contains("cancelGoal(\"inventory-empty-load-return-pearl\")"));
        assertTrue(runtime.contains("tracked-own-pearl-return-kill-ready"));
        assertTrue(runtime.contains("lethalReturnPearlReady"));
        assertTrue(runtime.contains("MoverPhase.AWAITING_RETURN_DEATH"));
        assertTrue(runtime.contains("pearl-return-command-dispatched"));
        assertTrue(runtime.contains("hasPearlChestCleanupPendingAfterThrow"));
        assertTrue(runtime.contains("ensureThrowContextReady(\"hotbar-pearl-before-throw\")"));
        assertTrue(runtime.contains("ensureThrowContextReady(\"throwing-pearl-before-use\")"));
        assertTrue(runtime.contains("throw-screen-close"));
        assertTrue(runtime.contains("cancelGoal(\"player-died\")"));
        assertTrue(runtime.contains("Destination chest is full; keeping it open and waiting for free slots."));
        assertTrue(runtime.contains("waitingForDestinationSpace = true;"));
        assertFalse(runtime.contains("if (autoDisable.get()) toggle();"));
        assertTrue(runtime.contains("resolvePearlTarget(water, chamber)"));
        assertTrue(runtime.contains("isCurrentPearlThrowPosition(water, pearlTarget, chamber)"));
        assertTrue(runtime.contains("cancelGoal(\"current-position-ready-for-throw\")"));
        assertTrue(runtime.contains("resolvePearlApproachGoal(water, pearlTarget, chamber)"));
        assertTrue(runtime.contains("requestExactGoal(GoalKind.WATER, pearlApproachGoal, \"throwing-pearl-approach\")"));
        assertTrue(runtime.contains("cancelGoal(\"water-approach-ready-for-throw\")"));
        assertTrue(runtime.contains("isPearlTargetObstructed(pearlTarget, water, chamber)"));
        assertTrue(runtime.contains("pearl-target-obstructed"));
        assertTrue(runtime.contains("float pitch = (float) Rotations.getPitch(pearlTarget);"));
        assertTrue(runtime.contains("mc.player.setPitch(pitch);"));
        assertTrue(approach.contains("return Vec3d.ofCenter(water);"));
        assertTrue(approach.contains("resolveApproachGoal"));
        assertTrue(approach.contains("isAtApproachGoal"));
        assertTrue(approach.contains("isCurrentThrowPosition"));
        assertTrue(approach.contains("isCenteredOverWater"));
        assertTrue(approach.contains("BlockPos adjacent = resolveAdjacentApproachGoal(mc, water, target, chamber);"));
        assertTrue(interaction.contains("StashMoverPearlApproach.resolveApproachGoal"));
        assertTrue(interaction.contains("StashMoverPearlApproach.isAtApproachGoal"));
        assertTrue(interaction.contains("StashMoverPearlApproach.isCurrentThrowPosition"));
        assertTrue(interaction.contains("protected boolean requestExactGoal"));
        assertTrue(interaction.contains("containsChatNameToken"));
        assertTrue(interaction.contains("expectedNeighborType"));
        assertTrue(interaction.contains("neighborState.get(ChestBlock.FACING) != state.get(ChestBlock.FACING)"));
        assertTrue(interaction.contains("boolean accepted = exact ? baritone.goToExact(pos) : baritone.goTo(pos);"));
        assertFalse(interaction.contains("mc.player.getEyePos().distanceTo(Vec3d.ofCenter(lootChest)) < 6.0"));
        assertFalse(runtime.contains("baritone-water-exit-before-throw"));
        assertFalse(runtime.contains("baritone-water-exit-before-loot"));
        assertFalse(interaction.contains("post-pearl-water-exit-goal"));
        assertFalse(support.contains("resolveWaterExitGoal"));
        assertFalse(support.contains("waterExitGoalStatus"));
        assertFalse(support.contains("isReadyToThrowFromExitGoal"));
        assertFalse(support.contains("isPlayerWet"));
        assertFalse(support.contains("pearl-target-y-offset"));
        assertTrue(support.contains("waitingDestinationSpace="));
        assertTrue(bridge.contains("GoalGetToBlock"));
        assertTrue(bridge.contains("GoalBlock"));
        assertTrue(bridge.contains("boolean goToExact(BlockPos pos)"));
        assertTrue(bridge.contains("allowBreak"));
        assertTrue(bridge.contains("allowBreakAnyway"));
        assertTrue(bridge.contains("restoreNoBreakGuard"));
        assertTrue(bridge.contains("cancelEverything"));
        assertFalse(runtime.contains("rusherhack-plugin.json"));
        assertFalse(runtime.contains("requestGoal(GoalKind.WATER, water, \"throwing-pearl-water\")"));
    }

    @Test
    void liveRuntimeHarnessKeepsRealisticUserWorldLootFirstOrdering() throws IOException {
        String harness = Files.readString(Path.of("src", "main", "java", "com", "devils", "addon", "util", "smoke", "StashMoverLiveRuntimeValidation.java"));

        assertTrue(harness.contains("boolean realisticBootstrapWarmup = useUserWorld() && realisticMode() && runIndex == 0 && !bootstrapWarmupCompleted;"));
        assertTrue(harness.contains("reason=real-player-thrown-initial-pearl-required"));
        assertTrue(harness.contains("STAGE bootstrap-throw-prepared"));
    }

    @Test
    void nativeModuleFilesExistAndRhPluginMetadataIsNotRuntimeWired() {
        assertTrue(Files.exists(Path.of("src", "main", "java", "com", "devils", "addon", "modules", "stashmover", "StashMover.java")));
        assertTrue(Files.exists(Path.of("src", "main", "java", "com", "devils", "addon", "modules", "stashmover", "StashMoverCommand.java")));
        assertTrue(Files.exists(Path.of("src", "main", "java", "com", "devils", "addon", "modules", "stashmover", "StashMoverPearlApproach.java")));
        assertFalse(Files.exists(Path.of("src", "main", "java", "com", "devils", "addon", "modules", "stashmover", "StashMoverPearlTrajectory.java")));
        assertFalse(Files.exists(Path.of("src", "main", "resources", "rusherhack-plugin.json")));
    }
}
