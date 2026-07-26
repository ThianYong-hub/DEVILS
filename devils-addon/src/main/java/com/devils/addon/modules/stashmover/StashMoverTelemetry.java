package com.devils.addon.modules.stashmover;

import java.util.Locale;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/**
 * Builds the detail payloads passed to StrictRuntimeLogger for StashMover events. The module is
 * only used for censor-aware position formatting and read-only phase/setting values.
 */
final class StashMoverTelemetry {
    private StashMoverTelemetry() {
    }

    static String loaderClick(StashMoverSupport module, Vec3d playerPos, Vec3d chamber) {
        return "pos=" + module.formatVecForFeedback(playerPos)
            + " chamber=" + module.formatVecForFeedback(chamber)
            + " ackPending=" + module.loaderAckPending;
    }

    static String pearlThrowStabilizeRecovery(StashMoverSupport module, Vec3d playerPos, BlockPos water) {
        return "playerPos=" + module.formatVecForFeedback(playerPos)
            + " water=" + module.formatBlockPosForFeedback(water)
            + " stationaryTicks=" + module.stationaryTicks;
    }

    static String pearlThrow(StashMoverSupport module, Vec3d playerPos, Vec3d target, float yaw, float pitch) {
        return "playerPos=" + module.formatVecForFeedback(playerPos)
            + " target=" + module.formatVecForFeedback(target)
            + " yaw=" + String.format(Locale.ROOT, "%.2f", yaw)
            + " pitch=" + String.format(Locale.ROOT, "%.2f", pitch);
    }

    static String returnThrowPoseWait(
        StashMoverSupport module,
        Vec3d playerPos,
        Vec3d target,
        StashMoverThrowGeometry.ReturnThrowPose pose
    ) {
        return "playerPos=" + module.formatVecForFeedback(playerPos)
            + " target=" + module.formatVecForFeedback(target)
            + " horizontalSq=" + String.format(Locale.ROOT, "%.4f", pose.horizontalSq())
            + " eyeVerticalDelta=" + String.format(Locale.ROOT, "%.3f", pose.eyeVerticalDelta())
            + " horizontalVelocitySq=" + String.format(Locale.ROOT, "%.5f", pose.horizontalVelocitySq())
            + " centered=" + pose.centered()
            + " heightReady=" + pose.heightReady()
            + " calm=" + pose.calm();
    }

    static String pearlStasisWait(StashMoverSupport module, int entityId, int ticks, StashMoverPearlFlight flight) {
        return pearlFlight(module, entityId, ticks, flight)
            + " horizontalVelocitySq=" + String.format(Locale.ROOT, "%.4f", flight.horizontalVelocitySq());
    }

    static String pearlFlight(StashMoverSupport module, int entityId, int ticks, StashMoverPearlFlight flight) {
        return "entityId=" + entityId + " " + flightMetrics(module, ticks, flight);
    }

    static String pearlReturnCommandDispatched(
        StashMoverSupport module,
        int entityId,
        String signal,
        int ticks,
        StashMoverPearlFlight flight,
        String command
    ) {
        return "entityId=" + entityId
            + " signal=" + signal
            + " " + flightMetrics(module, ticks, flight)
            + " returnCommand=" + command;
    }

    static String awaitingReturnDeath(StashMoverSupport module, String returnCommand, Vec3d playerPos) {
        return "ticks=" + module.phaseAgeTicks
            + " returnCommand=" + returnCommand
            + " playerPos=" + module.formatVecForFeedback(playerPos);
    }

    static String pearlThrowStabilize(StashMoverSupport module, Vec3d playerPos, Vec3d waterCenter, double horizontalSq) {
        return "playerPos=" + module.formatVecForFeedback(playerPos)
            + " waterCenter=" + module.formatVecForFeedback(waterCenter)
            + " horizontalSq=" + String.format(Locale.ROOT, "%.3f", horizontalSq);
    }

    static String pearlThrowSnap(
        StashMoverSupport module,
        Vec3d from,
        Vec3d to,
        String reason,
        double horizontalSq,
        double verticalDelta
    ) {
        return "from=" + module.formatVecForFeedback(from)
            + " to=" + module.formatVecForFeedback(to)
            + " reason=" + reason
            + " horizontalSq=" + String.format(Locale.ROOT, "%.3f", horizontalSq)
            + " verticalDelta=" + String.format(Locale.ROOT, "%.3f", verticalDelta);
    }

    static String trapdoorClick(StashMoverSupport module, String reason, Vec3d playerPos, Vec3d chamber) {
        return "reason=" + reason
            + " playerPos=" + module.formatVecForFeedback(playerPos)
            + " chamber=" + module.formatVecForFeedback(chamber)
            + " open=false";
    }

    static String sourceOpenRetry(StashMoverSupport module, BlockPos source, BlockPos openedContainer, ScreenHandler handler) {
        return "source=" + module.formatBlockPosForFeedback(source)
            + " openedContainer=" + module.formatValue(openedContainer)
            + " handler=" + describeHandler(handler);
    }

    static String sourceSelected(StashMoverSupport module, BlockPos source, BlockPos lootChest, BlockPos pearlChest) {
        return "source=" + module.formatBlockPosForFeedback(source)
            + " destinationLootChest=" + module.formatBlockPosForFeedback(lootChest)
            + " pearlChest=" + module.formatBlockPosForFeedback(pearlChest);
    }

    static String destinationFull(StashMoverSupport module, BlockPos pos, int occupiedSlots, int storageSlots, int markedFull) {
        return "pos=" + module.formatBlockPosForFeedback(pos)
            + " occupiedSlots=" + occupiedSlots
            + " storageSlots=" + storageSlots
            + " markedFull=" + markedFull;
    }

    static String replenishPearlReturn(StashMoverSupport module, int slot, int count, BlockPos pearlChest) {
        return "slot=" + slot
            + " count=" + count
            + " pearlChest=" + module.formatBlockPosForFeedback(pearlChest)
            + " stage=after-return-throw";
    }

    static String playerDeath(StashMoverSupport module, Vec3d playerPos) {
        return "mode=" + module.mode.get()
            + " moverPhase=" + module.moverPhase
            + " loaderPhase=" + module.loaderPhase
            + " pos=" + module.formatVecForFeedback(playerPos)
            + " returnCommand=" + module.returnCommand.get();
    }

    static String playerRespawnRequested(StashMoverSupport module) {
        return "mode=" + module.mode.get()
            + " nextMoverPhase=" + module.moverPhase
            + " returnCommand=" + module.returnCommand.get();
    }

    static String returnDeathConfirmed(StashMoverSupport module, String reason, String signal, Vec3d playerPos) {
        return "reason=" + reason
            + " signal=" + signal
            + " mode=" + module.mode.get()
            + " pos=" + module.formatVecForFeedback(playerPos)
            + " returnCommand=" + module.returnCommand.get();
    }

    private static String flightMetrics(StashMoverSupport module, int ticks, StashMoverPearlFlight flight) {
        return "ticks=" + ticks
            + " pos=" + module.formatVecForFeedback(flight.pearlPos())
            + " target=" + module.formatVecForFeedback(flight.target())
            + " distanceSq=" + String.format(Locale.ROOT, "%.3f", flight.distanceSq())
            + " horizontalSq=" + String.format(Locale.ROOT, "%.3f", flight.horizontalSq())
            + " verticalDelta=" + String.format(Locale.ROOT, "%.3f", flight.verticalDelta())
            + " verticalAbs=" + String.format(Locale.ROOT, "%.3f", flight.verticalAbs())
            + " verticalBelow=" + String.format(Locale.ROOT, "%.3f", flight.verticalBelow())
            + " velocity=" + module.formatVecForFeedback(flight.pearlVelocity())
            + " speedSq=" + String.format(Locale.ROOT, "%.4f", flight.speedSq());
    }

    private static String describeHandler(ScreenHandler handler) {
        return handler == null ? "<null>" : handler.getClass().getSimpleName() + "#" + handler.syncId;
    }
}
