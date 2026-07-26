package com.devils.addon.modules.stashmover;

import com.devils.addon.util.runtime.StrictRuntimeLogger;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import net.minecraft.block.BlockState;
import net.minecraft.block.TrapdoorBlock;
import net.minecraft.entity.projectile.thrown.EnderPearlEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

abstract class StashMoverRuntime extends StashMoverInteraction {
    protected void tickMover() {
        BlockPos pearlChest = pearlChestPos();
        BlockPos lootChest = lootChestPos();
        BlockPos water = waterPos();
        Vec3d chamber = chamberLookPos();
        switch (moverPhase) {
            case SEND_LOAD_PEARL_MSG -> tickSendLoadMessage();
            case WAIT_FOR_PEARL -> tickWaitForPearl(pearlChest, lootChest, water, chamber);
            case THROWING_PEARL -> tickThrowingPearl(water, chamber);
            case AWAITING_RETURN_DEATH -> tickAwaitingReturnDeath();
            case PUT_BACK_PEARLS -> tickPutBackPearl(pearlChest, lootChest);
            case ECHEST_LOOT -> tickEchestLoot();
            case LOOT -> tickLoot(lootChest);
            case WALKING_TO_CHEST -> tickWalkingToDestination(lootChest);
            case ECHEST_FILL -> tickEchestFill();
        }
        detectOperationalStall(lootChest);
    }

    protected void tickLoader() {
        Vec3d chamber = chamberLookPos();
        if (chamber == null) return;
        if (loaderPhase == LoaderPhase.WAITING) return;
        BlockPos chamberBlock = BlockPos.ofFloored(chamber.x, chamber.y, chamber.z);
        if (mc.player.getEyePos().distanceTo(chamber) > 4.0) {
            requestGoal(GoalKind.CHAMBER, freeBlockAroundChest(chamberBlock), "loader-chamber-approach");
            return;
        }
        if (!isLoaderReadyToTriggerChamber(chamberBlock, chamber)) return;
        if (useBlock(chamberBlock, chamber)) {
            StrictRuntimeLogger.logStashMover(
                "loader-click",
                StashMoverTelemetry.loaderClick(this, mc.player.getEntityPos(), chamber)
            );
            sendLoaderLoadAck();
            loaderPhase = LoaderPhase.WAITING;
            loaderAckPending = false;
        }
    }

    protected void tickSendLoadMessage() {
        if (loadAckReceived) {
            enterWaitForPearlPhase();
            return;
        }
        if (resendLoadMessageTicks > 0) {
            resendLoadMessageTicks--;
            return;
        }
        String tokenA = UUID.randomUUID().toString().substring(0, 4);
        String tokenB = UUID.randomUUID().toString().substring(0, 4);
        sendChatCommand("msg " + partnerName.get() + " " + tokenA + loadMessage.get() + " " + tokenB);
        resendLoadMessageTicks = LOAD_MESSAGE_RESEND_TICKS;
    }

    protected void tickWaitForPearl(BlockPos pearlChest, BlockPos lootChest, BlockPos water, Vec3d chamber) {
        boolean waitingForLoadedPearlArrival = !loadingReturnPearlAfterDeposit && !isPlayerInventoryEmpty();
        if (waitingForLoadedPearlArrival) {
            if (lootChest != null
                && mc.player.getEyePos().squaredDistanceTo(Vec3d.ofCenter(lootChest)) <= CONTAINER_REACH * CONTAINER_REACH) {
                closeHandledScreen();
                cancelGoal("loaded-pearl-arrived-at-destination");
                loadAckReceived = false;
                setMoverPhase(MoverPhase.WALKING_TO_CHEST, "loaded-pearl-arrived-deposit-first");
            }
            return;
        }
        if (findPearlHotbarSlot() != -1) {
            if (!ensureChamberOpenForThrow(chamber, "hotbar-pearl-before-throw")) return;
            if (!ensureThrowContextReady("hotbar-pearl-before-throw")) return;
            cancelGoal("pearl-ready-in-hotbar");
            loadAckReceived = false;
            pearlChestNoPearlRetries = 0;
            setMoverPhase(MoverPhase.THROWING_PEARL, "pearl-ready-in-hotbar");
            return;
        }
        Vec3d pearlTarget = resolvePearlTarget(water, chamber);
        BlockPos pearlApproachGoal = resolvePearlApproachGoal(water, pearlTarget, chamber);
        if (pearlApproachGoal != null) requestExactGoal(GoalKind.WATER, pearlApproachGoal, "wait-for-pearl-approach");
        else if (water != null) requestGoal(GoalKind.WATER, water, "wait-for-pearl-water");
        BlockPos chamberBlock = chamber == null ? null : BlockPos.ofFloored(chamber.x, chamber.y, chamber.z);
        if (chamberBlock != null) {
            BlockState state = mc.world.getBlockState(chamberBlock);
            if (state.getBlock() instanceof TrapdoorBlock && state.contains(TrapdoorBlock.OPEN) && !state.get(TrapdoorBlock.OPEN)) {
                if (useBlock(chamberBlock, chamber)) actionCooldownTicks = 10;
                return;
            }
        }
        loadAckReceived = false;
        if (!isOpenTarget(pearlChest)) {
            tryOpenContainer(pearlChest, false);
            return;
        }
        PearlTakeResult result = takeSinglePearlFromOpenContainer();
        if (result == PearlTakeResult.NO_SAFE_SLOT) {
            error("No safe hotbar slot available for the single pearl retrieval policy.");
            toggle();
        } else if (result == PearlTakeResult.NO_PEARL_IN_CONTAINER) {
            pearlChestNoPearlRetries++;
            StrictRuntimeLogger.logStashMover(
                "pearl-chest-retry",
                "reason=no-pearl-visible retries=" + pearlChestNoPearlRetries + " target=" + formatBlockPosForFeedback(pearlChest)
            );
            closeHandledScreen();
            actionCooldownTicks = 4;
            if (pearlChestNoPearlRetries >= 5) {
                error("Pearl chest still did not expose ender pearls after retries.");
                toggle();
            }
        } else if (result == PearlTakeResult.NO_CONTAINER) {
            openedContainerTarget = null;
            actionCooldownTicks = 2;
        } else if (result == PearlTakeResult.SUCCESS) {
            pearlChestNoPearlRetries = 0;
        }
    }

    protected void sendLoaderLoadAck() {
        if (!loaderAckPending) return;
        String ackA = UUID.randomUUID().toString().substring(0, 4);
        String ackB = UUID.randomUUID().toString().substring(0, 4);
        sendChatCommand("msg " + partnerName.get() + " " + ackA + " RECEIVED MESSAGE " + ackB);
    }

    protected void tickThrowingPearl(BlockPos water, Vec3d chamber) {
        if (ownPearlTracker.isAwaitingSpawn()) return;
        if (ownPearlTracker.hasTrackedPearl()) {
            tickThrownPearlStasis(water, chamber);
            return;
        }
        FindItemResult pearl = InvUtils.findInHotbar(Items.ENDER_PEARL);
        if (!pearl.found()) {
            recordPearlFailure("pearl-missing-from-hotbar");
            setMoverPhase(MoverPhase.WAIT_FOR_PEARL, "pearl-missing-from-hotbar");
            return;
        }
        Vec3d pearlTarget = resolvePearlTarget(water, chamber);
        if (pearlTarget == null) {
            releaseSneakRecovery();
            recordPearlFailure("pearl-target-unavailable");
            setMoverPhase(MoverPhase.WAIT_FOR_PEARL, "pearl-target-unavailable");
            return;
        }
        if (!ensureThrowContextReady("throwing-pearl-before-use")) return;
        if (shouldUseLocalThrowSnap()) snapPearlThrowPosition(water, pearlTarget, chamber);
        if (!ensureChamberOpenForThrow(chamber, "throwing-pearl-before-use")) return;
        if (!ensureReturnPearlThrowPose(pearlTarget)) return;
        if (shouldStabilizePearlThrow(water)) {
            if (!shouldUseLocalThrowSnap() && stationaryTicks >= 20 && snapPearlThrowPosition(water, pearlTarget, chamber)) {
                releaseSneakRecovery();
                StrictRuntimeLogger.logStashMover(
                    "pearl-throw-stabilize-recovery",
                    StashMoverTelemetry.pearlThrowStabilizeRecovery(this, mc.player.getEntityPos(), water)
                );
                actionCooldownTicks = 2;
                return;
            }
            actionCooldownTicks = 2;
            return;
        }
        releaseSneakRecovery();
        if (isCurrentPearlThrowPosition(water, pearlTarget, chamber)) {
            cancelGoal("current-position-ready-for-throw");
        } else {
            BlockPos pearlApproachGoal = resolvePearlApproachGoal(water, pearlTarget, chamber);
            if (pearlApproachGoal != null) {
                requestExactGoal(GoalKind.WATER, pearlApproachGoal, "throwing-pearl-approach");
                if (!isAtPearlApproachGoal(pearlApproachGoal, pearlTarget, water, chamber)) {
                    actionCooldownTicks = 2;
                    return;
                }
                cancelGoal("water-approach-ready-for-throw");
            }
        }
        if (isPearlTargetObstructed(pearlTarget, water, chamber)) {
            StrictRuntimeLogger.logStashMover(
                "collision-check",
                "result=obstructed playerPos=" + formatVecForFeedback(mc.player.getEntityPos()) + " target=" + formatVecForFeedback(pearlTarget)
            );
            warning("Pearl target is obstructed; refusing to throw into a block.");
            recordPearlFailure("pearl-target-obstructed");
            actionCooldownTicks = 5;
            return;
        }
        StrictRuntimeLogger.logStashMover(
            "collision-check",
            "result=clear playerPos=" + formatVecForFeedback(mc.player.getEntityPos()) + " target=" + formatVecForFeedback(pearlTarget)
        );
        float yaw = (float) Rotations.getYaw(pearlTarget);
        float pitch = (float) Rotations.getPitch(pearlTarget);
        lastPearlTarget = pearlTarget;
        lastPearlThrowYaw = yaw;
        lastPearlThrowPitch = pitch;
        StrictRuntimeLogger.logStashMover(
            "pearl-throw",
            StashMoverTelemetry.pearlThrow(this, mc.player.getEntityPos(), pearlTarget, yaw, pitch)
        );
        debugLog(
            "throw pearl target=" + formatVecForFeedback(pearlTarget)
                + " yaw=" + yaw
                + " pitch=" + pitch
        );
        Rotations.rotate(yaw, pitch, ACTION_ROTATION_PRIORITY, () -> {
            if (mc.player == null || mc.interactionManager == null) return;
            mc.player.setSprinting(false);
            mc.player.setYaw(yaw);
            mc.player.setPitch(pitch);
            if (isPearlTargetObstructed(pearlTarget, water, chamber)) {
                warning("Pearl target became obstructed after rotation; aborting throw.");
                recordPearlFailure("pearl-target-obstructed");
                actionCooldownTicks = 5;
                return;
            }
            if (!InvUtils.swap(pearl.slot(), false)) return;
            ActionResult result = mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
            InvUtils.swapBack();
            if (!result.isAccepted()) {
                warning("Pearl use was not accepted after server rotation sync.");
                recordPearlFailure("pearl-use-not-accepted");
                setMoverPhase(MoverPhase.WAIT_FOR_PEARL, "pearl-use-not-accepted");
                return;
            }
            ownPearlTracker.beginAwaitingSpawn(OWN_PEARL_SPAWN_TIMEOUT_TICKS);
            ownPearlStasisTicks = 0;
            lastPearlFailureReason = "none";
            actionCooldownTicks = 2;
        });
    }

    protected boolean ensureReturnPearlThrowPose(Vec3d pearlTarget) {
        if (!loadingReturnPearlAfterDeposit || mc.player == null || pearlTarget == null) return true;

        Vec3d playerPos = mc.player.getEntityPos();
        Vec3d velocity = mc.player.getVelocity();
        StashMoverThrowGeometry.ReturnThrowPose pose =
            StashMoverThrowGeometry.evaluateReturnThrowPose(playerPos, mc.player.getEyePos(), velocity, pearlTarget);

        if (pose.ready()) {
            releaseSneakRecovery();
            return true;
        }

        if (pose.aboveMaxEyeHeight()) holdSneakRecovery();
        else releaseSneakRecovery();

        if (pose.needsHorizontalNudge()) {
            Vec3d nudged = pose.nudgedVelocity(velocity.y);
            mc.player.setVelocity(nudged.x, nudged.y, nudged.z);
        }

        if (phaseAgeTicks == 1 || phaseAgeTicks % 10 == 0) {
            StrictRuntimeLogger.logStashMover(
                "return-pearl-throw-pose-wait",
                StashMoverTelemetry.returnThrowPoseWait(this, playerPos, pearlTarget, pose)
            );
        }

        actionCooldownTicks = 1;
        return false;
    }

    protected void tickThrownPearlStasis(BlockPos water, Vec3d chamber) {
        if (mc.world == null) return;

        int trackedEntityId = ownPearlTracker.trackedEntityId();
        if (!(mc.world.getEntityById(trackedEntityId) instanceof EnderPearlEntity pearl)) {
            ownPearlStasisTicks++;
            if (ownPearlStasisTicks > 6) {
                recordPearlFailure("tracked-pearl-entity-missing-before-stasis");
                setMoverPhase(MoverPhase.WAIT_FOR_PEARL, "tracked-pearl-entity-missing-before-stasis");
            }
            return;
        }

        ownPearlStasisTicks++;
        Vec3d target = resolvePearlTarget(water, chamber);
        if (target == null) target = lastPearlTarget;
        StashMoverPearlFlight flight = StashMoverPearlFlight.of(target, pearl.getEntityPos(), pearl.getVelocity());
        boolean nearTarget = flight.nearTarget();
        String returnCommandValue = returnCommand.get() == null ? "" : returnCommand.get().trim();
        boolean lethalReturn = loadingReturnPearlAfterDeposit
            && (!returnCommandValue.isEmpty()
                && (returnCommandValue.equalsIgnoreCase("kill")
                || returnCommandValue.toLowerCase(Locale.ROOT).startsWith("kill ")));
        boolean returnPearlFlightConfirmed = loadingReturnPearlAfterDeposit
            && !lethalReturn
            && flight.isReturnFlightConfirmed(ownPearlStasisTicks);
        boolean returnPearlReady = loadingReturnPearlAfterDeposit
            && !lethalReturn
            && flight.isReturnSettled(ownPearlStasisTicks);
        boolean lethalReturnFlightConfirmed = loadingReturnPearlAfterDeposit
            && lethalReturn
            && flight.isLethalReturnFlightConfirmed(ownPearlStasisTicks);
        boolean lethalReturnPearlLaunched = loadingReturnPearlAfterDeposit
            && lethalReturn
            && flight.isLethalReturnLaunched(ownPearlStasisTicks);
        boolean lethalReturnPearlReady = loadingReturnPearlAfterDeposit
            && lethalReturn
            && flight.isLethalReturnSettled(ownPearlStasisTicks);
        boolean chamberSettled = flight.isChamberSettled(ownPearlStasisTicks);
        boolean genericStasisReady = chamberSettled || (ownPearlStasisTicks >= OWN_PEARL_STASIS_MIN_TICKS && nearTarget);
        boolean lethalReturnChestCleanupPending = loadingReturnPearlAfterDeposit
            && lethalReturn
            && hasPearlChestCleanupPendingAfterThrow();
        boolean lethalReturnCommandFlightConfirmed = loadingReturnPearlAfterDeposit
            && lethalReturn
            && !lethalReturnChestCleanupPending
            && (lethalReturnFlightConfirmed || lethalReturnPearlLaunched);
        boolean lethalReturnCommandReady = loadingReturnPearlAfterDeposit
            && lethalReturn
            && !lethalReturnChestCleanupPending
            && lethalReturnPearlReady;
        boolean readyForPutBack = loadingReturnPearlAfterDeposit && lethalReturn
            ? (lethalReturnChestCleanupPending && lethalReturnPearlLaunched)
            : (returnPearlFlightConfirmed || returnPearlReady || genericStasisReady);
        trackedPearlReadyOnRemoval = loadingReturnPearlAfterDeposit
            && lethalReturn
            && (lethalReturnPearlLaunched || lethalReturnPearlReady);
        String readyEvent = loadingReturnPearlAfterDeposit && lethalReturn
            ? (lethalReturnPearlLaunched ? "pearl-return-kill-launched" : "pearl-return-kill-ready")
            : (returnPearlFlightConfirmed
                ? "pearl-return-flight-confirmed"
                : (returnPearlReady ? "pearl-return-ready" : "pearl-stasis-ready"));
        String readyReason = loadingReturnPearlAfterDeposit && lethalReturn
            ? (lethalReturnPearlLaunched ? "tracked-own-pearl-return-kill-launched" : "tracked-own-pearl-return-kill-ready")
            : (returnPearlFlightConfirmed
                ? "tracked-own-pearl-return-flight-confirmed"
                : (returnPearlReady ? "tracked-own-pearl-return-ready" : "tracked-own-pearl-stasis-ready"));

        boolean detailedReturnTelemetry = loadingReturnPearlAfterDeposit && ownPearlStasisTicks <= 40 && ownPearlStasisTicks % 5 == 0;
        if (ownPearlStasisTicks == 1 || ownPearlStasisTicks % 20 == 0 || detailedReturnTelemetry) {
            StrictRuntimeLogger.logStashMover(
                "pearl-stasis-wait",
                StashMoverTelemetry.pearlStasisWait(this, trackedEntityId, ownPearlStasisTicks, flight)
            );
        }

        if (lethalReturnCommandFlightConfirmed || lethalReturnCommandReady) {
            String lethalSignal = lethalReturnCommandFlightConfirmed
                ? "kill-flight-confirmed"
                : "kill-ready";
            String lethalReason = lethalReturnCommandFlightConfirmed
                ? "tracked-own-pearl-return-kill-flight-confirmed"
                : "tracked-own-pearl-return-kill-ready";
            dispatchLethalReturnAfterTrackedPearl(trackedEntityId, lethalReason, lethalSignal, flight);
            return;
        }

        if (readyForPutBack) {
            StrictRuntimeLogger.logStashMover(
                readyEvent,
                StashMoverTelemetry.pearlFlight(this, trackedEntityId, ownPearlStasisTicks, flight)
            );
            setMoverPhase(MoverPhase.PUT_BACK_PEARLS, readyReason);
            actionCooldownTicks = 1;
            return;
        }

        if (ownPearlStasisTicks > OWN_PEARL_STASIS_TIMEOUT_TICKS) {
            recordPearlFailure("tracked-pearl-stasis-timeout");
            setMoverPhase(MoverPhase.WAIT_FOR_PEARL, "tracked-pearl-stasis-timeout");
            actionCooldownTicks = 5;
        }
    }

    protected void tickAwaitingReturnDeath() {
        cancelGoal("awaiting-return-death");
        closeHandledScreen();
        if (phaseAgeTicks == 1 || phaseAgeTicks % 20 == 0) {
            StrictRuntimeLogger.logStashMover(
                "awaiting-return-death",
                StashMoverTelemetry.awaitingReturnDeath(this, normalizedReturnCommand(), playerPosOrNull())
            );
        }
    }

    protected boolean shouldStabilizePearlThrow(BlockPos water) {
        if (mc.player == null || mc.world == null || water == null) return false;

        Vec3d playerPos = mc.player.getEntityPos();
        StashMoverThrowGeometry.WaterStabilize stabilize = StashMoverThrowGeometry.evaluateWaterStabilize(playerPos, water);
        BlockPos playerBlock = mc.player.getBlockPos();
        boolean waterLoadedAtFeet = playerBlock.equals(water)
            || mc.world.getFluidState(playerBlock).isIn(FluidTags.WATER);

        if (stabilize.nearWaterCenter() && stabilize.tooHighAboveWater() && waterLoadedAtFeet) {
            holdSneakRecovery();
            StrictRuntimeLogger.logStashMover(
                "pearl-throw-stabilize",
                StashMoverTelemetry.pearlThrowStabilize(this, playerPos, stabilize.waterCenter(), stabilize.horizontalSq())
            );
            return true;
        }

        return false;
    }

    protected boolean shouldUseLocalThrowSnap() {
        return localThrowSnapEnabled && mc != null && mc.isInSingleplayer();
    }

    protected boolean snapPearlThrowPosition(BlockPos water, Vec3d target, Vec3d chamber) {
        if (mc.player == null || mc.player.networkHandler == null || water == null) return false;

        BlockPos approachGoal = resolvePearlApproachGoal(water, target, chamber);
        StashMoverThrowGeometry.SnapTarget snapTarget = StashMoverThrowGeometry.resolveSnapTarget(approachGoal, water);
        Vec3d snapped = snapTarget.position();
        Vec3d current = mc.player.getEntityPos();
        double dx = current.x - snapped.x;
        double dy = current.y - snapped.y;
        double dz = current.z - snapped.z;
        double horizontalSq = dx * dx + dz * dz;

        if (horizontalSq <= 0.03 * 0.03 && Math.abs(dy) <= 0.08) return false;

        mc.player.setVelocity(0.0, 0.0, 0.0);
        mc.player.setPosition(snapped.x, snapped.y, snapped.z);
        mc.player.networkHandler.sendPacket(new PlayerMoveC2SPacket.PositionAndOnGround(
            snapped.x,
            snapped.y,
            snapped.z,
            false,
            mc.player.horizontalCollision
        ));
        StrictRuntimeLogger.logStashMover(
            "pearl-throw-snap",
            StashMoverTelemetry.pearlThrowSnap(this, current, snapped, snapTarget.reason(), horizontalSq, dy)
        );
        return true;
    }

    protected boolean shouldTreatTrackedPearlRemovalAsReady(EnderPearlEntity pearl) {
        return false;
    }

    protected void acceptTrackedPearlRemovalAsReady(EnderPearlEntity pearl) {
        if (pearl == null) return;

        BlockPos water = waterPos();
        Vec3d chamber = chamberLookPos();
        Vec3d target = resolvePearlTarget(water, chamber);
        if (target == null) target = lastPearlTarget;
        StashMoverPearlFlight flight = StashMoverPearlFlight.of(target, pearl.getEntityPos(), pearl.getVelocity());
        StrictRuntimeLogger.logStashMover(
            "pearl-return-kill-removal-confirmed",
            StashMoverTelemetry.pearlFlight(this, pearl.getId(), ownPearlStasisTicks, flight)
        );
        cancelGoal("tracked-pearl-removal-confirmed");
        trackedPearlReadyOnRemoval = false;
        setMoverPhase(MoverPhase.PUT_BACK_PEARLS, "tracked-own-pearl-return-kill-removal-confirmed");
        actionCooldownTicks = 1;
    }

    protected boolean hasPearlChestCleanupPendingAfterThrow() {
        return pearlChestSwapPending || resolveBorrowedPearlHotbarSlot() != -1 || playerInventoryPearlCount() > 0;
    }

    protected void dispatchLethalReturnAfterTrackedPearl(
        int trackedEntityId,
        String phaseReason,
        String signal,
        StashMoverPearlFlight flight
    ) {
        String command = normalizedReturnCommand();
        closeHandledScreen();
        cancelGoal("lethal-return-command-dispatched");
        loadingReturnPearlAfterDeposit = false;
        currentLootSourceChest = null;
        renderedSourceChests.clear();
        trackedPearlReadyOnRemoval = false;
        StrictRuntimeLogger.logStashMover(
            "pearl-return-command-dispatched",
            StashMoverTelemetry.pearlReturnCommandDispatched(this, trackedEntityId, signal, ownPearlStasisTicks, flight, command)
        );
        StrictRuntimeLogger.logStashMover(
            "pearl-return-lethal-precondition",
            "require=enderman_pearl_survives_owner_death command=" + command
                + " note=modern-vanilla-needs-enderPearlsVanishOnDeath-false"
        );
        debugLog("tracked return pearl confirmed, dispatching /" + command);
        sendChatCommand(command);
        setMoverPhase(MoverPhase.AWAITING_RETURN_DEATH, phaseReason + "-dispatch-return-command");
        actionCooldownTicks = 10;
    }

    protected String normalizedReturnCommand() {
        return returnCommand.get() == null || returnCommand.get().isBlank() ? "kill" : returnCommand.get().trim();
    }

    protected boolean ensureThrowContextReady(String reason) {
        if (mc.player == null) return false;

        ScreenHandler handler = mc.player.currentScreenHandler;
        if (handler == null || handler.syncId == 0) return true;

        StrictRuntimeLogger.logStashMover(
            "throw-screen-close",
            "reason=" + reason
                + " handler=" + handler.getClass().getSimpleName() + "#" + handler.syncId
        );
        closeHandledScreen();
        actionCooldownTicks = 2;
        return false;
    }

    protected boolean ensureChamberOpenForThrow(Vec3d chamber, String reason) {
        if (chamber == null || mc.world == null || mc.player == null) return true;
        BlockPos chamberBlock = BlockPos.ofFloored(chamber.x, chamber.y, chamber.z);
        BlockState state = mc.world.getBlockState(chamberBlock);
        if (!(state.getBlock() instanceof TrapdoorBlock) || !state.contains(TrapdoorBlock.OPEN) || state.get(TrapdoorBlock.OPEN)) {
            return true;
        }

        StrictRuntimeLogger.logStashMover(
            "trapdoor-click",
            StashMoverTelemetry.trapdoorClick(this, reason, mc.player.getEntityPos(), chamber)
        );
        if (useBlock(chamberBlock, chamber)) actionCooldownTicks = 4;
        return false;
    }

    protected boolean isLoaderReadyToTriggerChamber(BlockPos chamberBlock, Vec3d chamber) {
        if (mc.world == null || chamberBlock == null || chamber == null) return false;

        BlockState state = mc.world.getBlockState(chamberBlock);
        if (state.getBlock() instanceof TrapdoorBlock
            && state.contains(TrapdoorBlock.OPEN)
            && !state.get(TrapdoorBlock.OPEN)) {
            if (phaseAgeTicks == 1 || phaseAgeTicks % 20 == 0) {
                StrictRuntimeLogger.logStashMover(
                    "loader-wait",
                    "reason=chamber-already-closed chamber=" + formatVecForFeedback(chamber)
                );
            }
            actionCooldownTicks = Math.max(actionCooldownTicks, 4);
            return false;
        }

        Vec3d target = resolvePearlTarget(waterPos(), chamber);
        if (target == null) target = chamber;
        Box searchBox = new Box(target, target).expand(1.35, 2.0, 1.35);
        boolean stagedPearlPresent = !mc.world.getEntitiesByClass(EnderPearlEntity.class, searchBox, pearl -> pearl.isAlive()).isEmpty();
        if (!stagedPearlPresent) {
            if (phaseAgeTicks == 1 || phaseAgeTicks % 20 == 0) {
                StrictRuntimeLogger.logStashMover(
                    "loader-wait",
                    "reason=no-staged-pearl chamber=" + formatVecForFeedback(chamber)
                        + " target=" + formatVecForFeedback(target)
                );
            }
            actionCooldownTicks = Math.max(actionCooldownTicks, 4);
            return false;
        }

        return true;
    }

    protected void tickPutBackPearl(BlockPos pearlChest, BlockPos lootChest) {
        if (!pearlChestSwapPending && resolveBorrowedPearlHotbarSlot() == -1 && playerInventoryPearlCount() == 0) {
            ownPearlTracker.reset();
            clearPearlChestBorrowState();
            continueAfterPearlStage(lootChest, "put-back-no-hotbar-pearl");
            return;
        }
        if (!isOpenTarget(pearlChest)) {
            tryOpenContainer(pearlChest, false);
            return;
        }
        ScreenHandler handler = mc.player.currentScreenHandler;
        if (!isChestLikeHandler(handler)) return;
        if (loadingReturnPearlAfterDeposit && returnReplenishedPearlsAfterReturnThrow(handler, pearlChest)) return;
        if (pearlChestSwapPending) {
            if (!restoreDisplacedPearlChestStack(handler)) return;
            ownPearlTracker.reset();
            clearPearlChestBorrowState();
            closeHandledScreen();
            continueAfterPearlStage(lootChest, "pearl-chest-swap-restored");
            actionCooldownTicks = 3;
            return;
        }
        int pearlHotbarSlot = resolveBorrowedPearlHotbarSlot();
        if (pearlHotbarSlot == -1) {
            clearPearlChestBorrowState();
            closeHandledScreen();
            continueAfterPearlStage(lootChest, "post-return-throw-pearls-cleaned");
            return;
        }
        if (!returnPearlsToChest(handler, pearlHotbarSlot)) return;
        ownPearlTracker.reset();
        clearPearlChestBorrowState();
        closeHandledScreen();
        continueAfterPearlStage(lootChest, "pearl-put-back-complete");
        actionCooldownTicks = 3;
    }

    protected void tickEchestLoot() {
        if (echestBufferFilled || !useEChest.get()) {
            setMoverPhase(MoverPhase.SEND_LOAD_PEARL_MSG, "echest-buffer-not-needed");
            return;
        }
        if (!ensureEnderChestOpen()) return;
        ScreenHandler handler = mc.player.currentScreenHandler;
        if (!isChestLikeHandler(handler)) return;
        if (isStorageFull(handler) || isPlayerInventoryEmpty()) {
            echestBufferFilled = true;
            closeHandledScreen();
            setMoverPhase(MoverPhase.LOOT, "echest-loot-finished");
            return;
        }

        int storageSlots = storageSlotCount(handler);
        for (int i = storageSlots; i < handler.slots.size(); i++) {
            if (!handler.getSlot(i).hasStack()) continue;
            if (!readyForChestAction()) return;
            mc.interactionManager.clickSlot(handler.syncId, i, 0, SlotActionType.QUICK_MOVE, mc.player);
            return;
        }
    }

    protected void tickLoot(BlockPos lootChest) {
        if (isInventoryFull()) {
            closeHandledScreen();
            if (useEChest.get() && !echestBufferFilled) setMoverPhase(MoverPhase.ECHEST_LOOT, "inventory-full-buffer-echest");
            else setMoverPhase(MoverPhase.SEND_LOAD_PEARL_MSG, "inventory-full-send-load");
            return;
        }

        if (!isChestLikeHandler(mc.player.currentScreenHandler)) {
            if (currentLootSourceChest != null) {
                if (isSourceChestBlacklisted(currentLootSourceChest)) {
                    currentLootSourceChest = null;
                    openedContainerTarget = null;
                    return;
                }

                if (actionCooldownTicks <= 0) {
                    StrictRuntimeLogger.logStashMover(
                        "source-open-retry",
                        StashMoverTelemetry.sourceOpenRetry(this, currentLootSourceChest, openedContainerTarget, mc.player.currentScreenHandler)
                    );
                    openedContainerTarget = null;
                    tryOpenContainer(currentLootSourceChest, true);
                }
                return;
            }

            if (baritone.isPathing() && activeGoalPos != null) {
                if (activeGoalKind == GoalKind.SOURCE_CHEST || activeGoalKind == GoalKind.CONTAINER_INTERACT) {
                    lootNudgeWaitTicks = 0;
                    return;
                }

                // Waiting out an unstick nudge keeps the loot loop from ending the stash early, but LOOT is not
                // covered by detectOperationalStall, so a nudge baritone never finishes must not wait forever.
                if (activeGoalKind == GoalKind.RANDOM_NUDGE || activeGoalKind == GoalKind.RECONNECT_NUDGE) {
                    if (++lootNudgeWaitTicks < LOOT_NUDGE_WAIT_TICKS) return;
                    lootNudgeWaitTicks = 0;
                    cancelGoal("loot-nudge-timeout");
                }
            }

            lootNudgeWaitTicks = 0;

            if (stationaryTicks >= STATIONARY_NUDGE_TICKS) {
                BlockPos target = randomNearbyGoal(3);
                if (target != null) {
                    requestGoal(GoalKind.RANDOM_NUDGE, target, "loot-stationary-nudge");
                    actionCooldownTicks = 10;
                    return;
                }
            }

            BlockPos chest = findClosestSourceChest(lootChest, pearlChestPos());
            if (chest == null) {
                if (isPlayerInventoryEmpty()) {
                    disableAfterPartnerSeen = false;
                    StrictRuntimeLogger.logStashMover(
                        "cycle-complete",
                        "reason=no-source-chests-and-empty-inventory next=disable-at-source"
                    );
                    toggle();
                    return;
                }
                setMoverPhase(MoverPhase.SEND_LOAD_PEARL_MSG, "no-source-chests-left");
                disableAfterPartnerSeen = true;
                return;
            }

            currentLootSourceChest = chest.toImmutable();
            StrictRuntimeLogger.logStashMover(
                "source-selected",
                StashMoverTelemetry.sourceSelected(this, currentLootSourceChest, lootChest, pearlChestPos())
            );
            tryOpenContainer(chest, true);
            return;
        }

        if (!Objects.equals(openedContainerTarget, currentLootSourceChest)) {
            closeHandledScreen();
            return;
        }

        ScreenHandler handler = mc.player.currentScreenHandler;
        if (!isSourceStorageDepleted(handler)) {
            int storageSlots = storageSlotCount(handler);
            for (int i = 0; i < storageSlots; i++) {
                if (!handler.getSlot(i).hasStack()) continue;

                ItemStack stack = handler.getSlot(i).getStack();
                if (onlyShulkers.get()
                    && !(stack.getItem() instanceof net.minecraft.item.BlockItem blockItem && blockItem.getBlock() instanceof net.minecraft.block.ShulkerBoxBlock)) {
                    continue;
                }

                if (!readyForChestAction(sourceLootDelay.get())) return;
                mc.interactionManager.clickSlot(handler.syncId, i, 0, SlotActionType.QUICK_MOVE, mc.player);
                return;
            }
            return;
        }

        closeHandledScreen();
        actionCooldownTicks = 5;
        BlockPos depletedSourceChest = currentLootSourceChest;
        blacklistSourceChest(depletedSourceChest);
        StrictRuntimeLogger.logStashMover(
            "source-depleted",
            "source=" + formatBlockPosForFeedback(depletedSourceChest)
                + " blacklisted=" + blacklistedSourceChests.size()
        );
        currentLootSourceChest = null;
        openedContainerTarget = null;

        if (!hasRemainingEligibleSourceChest(lootChest, pearlChestPos())) {
            if (isPlayerInventoryEmpty()) {
                disableAfterPartnerSeen = false;
                StrictRuntimeLogger.logStashMover(
                    "cycle-complete",
                    "reason=all-source-chests-processed-and-empty-inventory next=disable-at-source"
                );
                toggle();
                return;
            }
            setMoverPhase(MoverPhase.SEND_LOAD_PEARL_MSG, "all-source-chests-processed");
            disableAfterPartnerSeen = true;
        }
    }

    protected void tickWalkingToDestination(BlockPos lootChest) {
        if (lootChest == null) return;

        double destinationDistanceSq = mc.player.getEyePos().squaredDistanceTo(Vec3d.ofCenter(lootChest));
        if (destinationDistanceSq > CONTAINER_REACH * CONTAINER_REACH) {
            requestGoal(GoalKind.LOOT_CHEST, freeBlockAroundChest(lootChest), "walking-to-destination");
            return;
        }

        cancelGoal("loot-chest-in-range");

        if (isPlayerInventoryEmpty()) {
            waitingForDestinationSpace = false;
            if (echestBufferFilled && useEChest.get()) {
                setMoverPhase(MoverPhase.ECHEST_FILL, "inventory-empty-fill-echest");
                closeHandledScreen();
                return;
            }

            closeHandledScreen();
            currentLootSourceChest = null;
            renderedSourceChests.clear();
            cancelGoal("inventory-empty-load-return-pearl");
            loadingReturnPearlAfterDeposit = true;
            debugLog("inventory empty at loot chest, loading the next return pearl before /kill");
            setMoverPhase(MoverPhase.WAIT_FOR_PEARL, "inventory-empty-load-return-pearl");
            actionCooldownTicks = 3;
            return;
        }

        if (!isOpenTarget(lootChest)) {
            tryOpenContainer(lootChest, false);
            return;
        }

        ScreenHandler handler = mc.player.currentScreenHandler;
        if (!isChestLikeHandler(handler)) return;

        if (isStorageFull(handler)) {
            markFullDestinationChest(lootChest);
            StrictRuntimeLogger.logStashMover(
                "destination-full",
                StashMoverTelemetry.destinationFull(this, lootChest, occupiedStorageSlots(handler), storageSlotCount(handler), fullDestinationChests.size())
            );
            if (!waitingForDestinationSpace) {
                waitingForDestinationSpace = true;
                warning("Destination chest is full; keeping it open and waiting for free slots.");
                debugLog("destination chest full, keeping screen open and waiting for free slots");
            }
            actionCooldownTicks = Math.max(actionCooldownTicks, 5);
            return;
        }

        if (waitingForDestinationSpace) {
            waitingForDestinationSpace = false;
            debugLog("destination chest has free slots again, resuming deposit");
        }

        int storageSlots = storageSlotCount(handler);
        for (int i = storageSlots; i < handler.slots.size(); i++) {
            if (!handler.getSlot(i).hasStack()) continue;
            if (!readyForChestAction()) return;
            mc.interactionManager.clickSlot(handler.syncId, i, 0, SlotActionType.QUICK_MOVE, mc.player);
            movedStacks += 1.0f;
            return;
        }
    }

    private boolean returnReplenishedPearlsAfterReturnThrow(ScreenHandler handler, BlockPos pearlChest) {
        if (playerInventoryPearlCount() == 0) return false;
        int storageSlots = storageSlotCount(handler);
        if (!StashMoverPearlChestPolicy.canStorageAcceptEnderPearls(handler, storageSlots)) {
            warning("Pearl chest full; cant dump extra pearls before /kill.");
            StrictRuntimeLogger.logStashMover(
                "replenish-pearl-return-blocked",
                "reason=pearl-chest-full-after-return-throw pearlChest=" + formatBlockPosForFeedback(pearlChest)
                    + " pearls=" + playerInventoryPearlCount()
            );
            actionCooldownTicks = Math.max(actionCooldownTicks, 10);
            return true;
        }

        for (int i = storageSlots; i < handler.slots.size(); i++) {
            ItemStack stack = handler.getSlot(i).getStack();
            if (stack.isEmpty() || !stack.isOf(Items.ENDER_PEARL)) continue;
            if (!readyForChestAction()) return true;
            StrictRuntimeLogger.logStashMover(
                "replenish-pearl-return",
                StashMoverTelemetry.replenishPearlReturn(this, i, stack.getCount(), pearlChest)
            );
            mc.interactionManager.clickSlot(handler.syncId, i, 0, SlotActionType.QUICK_MOVE, mc.player);
            chestActionCooldownTicks = 2;
            return true;
        }

        return false;
    }

    protected void tickEchestFill() {
        if (!ensureEnderChestOpen()) return;

        ScreenHandler handler = mc.player.currentScreenHandler;
        if (!isChestLikeHandler(handler)) return;

        if (isStorageEmpty(handler)) {
            echestBufferFilled = false;
            closeHandledScreen();
            setMoverPhase(MoverPhase.WALKING_TO_CHEST, "echest-fill-complete");
            return;
        }

        int storageSlots = storageSlotCount(handler);
        for (int i = 0; i < storageSlots; i++) {
            if (!handler.getSlot(i).hasStack()) continue;
            if (!readyForChestAction()) return;
            mc.interactionManager.clickSlot(handler.syncId, i, 0, SlotActionType.QUICK_MOVE, mc.player);
            return;
        }
    }

    protected boolean validateConfiguration() {
        if (mode.get() == Mode.MOVER) {
            if (pearlChestPos() == null) {
                error("Set pearlchest first.");
                toggle();
                return false;
            }
            if (lootChestPos() == null) {
                error("Set lootchest first.");
                toggle();
                return false;
            }
            if (waterPos() == null) {
                error("Set water first.");
                toggle();
                return false;
            }
        }

        if (chamberLookPos() == null) {
            error("Set chamber first.");
            toggle();
            return false;
        }

        return true;
    }

    protected void handlePlayerDeath() {
        StrictRuntimeLogger.logStashMover(
            "player-death",
            StashMoverTelemetry.playerDeath(this, playerPosOrNull())
        );
        closeHandledScreen();
        cancelGoal("player-died");
        resetCycleStateAfterDeath("player-died", 10, "none");

        try {
            mc.player.requestRespawn();
            StrictRuntimeLogger.logStashMover(
                "player-respawn-requested",
                StashMoverTelemetry.playerRespawnRequested(this)
            );
        } catch (Throwable ignored) {
        }
    }

    protected void handleReturnDeathConfirmed(String reason, String signal) {
        if (moverPhase != MoverPhase.AWAITING_RETURN_DEATH) return;

        StrictRuntimeLogger.logStashMover(
            "return-death-confirmed",
            StashMoverTelemetry.returnDeathConfirmed(this, reason, signal, playerPosOrNull())
        );
        closeHandledScreen();
        cancelGoal("return-death-confirmed");
        resetCycleStateAfterDeath(reason == null ? "return-death-confirmed" : reason, 3, "return-death-confirmed");
    }

    private void resetCycleStateAfterDeath(String phaseReason, int cooldownTicks, String recoveryAction) {
        setMoverPhase(MoverPhase.LOOT, phaseReason);
        loaderPhase = LoaderPhase.WAITING;
        ownPearlTracker.reset();
        clearPearlChestBorrowState();
        currentLootSourceChest = null;
        openedContainerTarget = null;
        renderedSourceChests.clear();
        loadAckReceived = false;
        loadingReturnPearlAfterDeposit = false;
        disableAfterPartnerSeen = false;
        waitingForDestinationSpace = false;
        chestActionCooldownTicks = 0;
        actionCooldownTicks = cooldownTicks;
        logicPulseTicks = 0;
        phaseAgeTicks = 0;
        resendLoadMessageTicks = 0;
        stationaryTicks = 0;
        ownPearlStasisTicks = 0;
        lastPacketReceivedAtMs = System.currentTimeMillis();
        lastProgressAtMs = System.currentTimeMillis();
        clearPearlFailureState();
        lastStallReason = "none";
        lastRecoveryAction = recoveryAction;
    }

    protected void tickTrackedPearlState() {
        if (mc.player == null || mc.world == null) return;

        if (ownPearlTracker.isAwaitingSpawn()) captureOwnPearlFromWorld();

        if (ownPearlTracker.tickAwaitingSpawn() == StashMoverOwnPearlTracker.AwaitOutcome.TIMED_OUT) {
            warning("Timed out while waiting for the player-owned pearl entity.");
            recordPearlFailure("owned-pearl-spawn-timeout");
            setMoverPhase(MoverPhase.WAIT_FOR_PEARL, "owned-pearl-spawn-timeout");
        }
    }

    protected void captureOwnPearlFromWorld() {
        if (mc.player == null || mc.world == null || !ownPearlTracker.isAwaitingSpawn()) return;

        List<EnderPearlEntity> pearls = mc.world.getEntitiesByClass(
            EnderPearlEntity.class,
            mc.player.getBoundingBox().expand(32.0),
            pearl -> pearl.getOwner() == mc.player
        );

        for (EnderPearlEntity pearl : pearls) {
            if (ownPearlTracker.onPearlAdded(pearl.getId(), true) == StashMoverOwnPearlTracker.CaptureOutcome.TRACKED) {
                clearPearlFailureState();
                cancelGoal("tracked-own-pearl-world-scan");
                ownPearlStasisTicks = 0;
                StrictRuntimeLogger.logStashMover(
                    "pearl-spawn",
                    "entityId=" + pearl.getId() + " pos=" + formatVecForFeedback(pearl.getEntityPos()) + " owned=true source=world-scan"
                );
                actionCooldownTicks = 1;
                return;
            }
        }
    }

    protected void updateMovementState() {
        if (mc.player == null) return;

        if (Math.abs(mc.player.getVelocity().x) > 0.001
            || Math.abs(mc.player.getVelocity().y) > 0.001
            || Math.abs(mc.player.getVelocity().z) > 0.001) {
            stationaryTicks = 0;
        } else {
            stationaryTicks++;
        }
    }

    protected boolean isPacketFreshnessExpired() {
        return lastPacketReceivedAtMs > 0 && System.currentTimeMillis() - lastPacketReceivedAtMs > 1_500L;
    }

    protected boolean shouldResetOnTrackedPearlRemoval() {
        return moverPhase == MoverPhase.THROWING_PEARL;
    }

    protected void enterWaitForPearlPhase() {
        setMoverPhase(MoverPhase.WAIT_FOR_PEARL, "enter-wait-for-pearl");
        actionCooldownTicks = 3;
    }

    protected void setMoverPhase(MoverPhase next) {
        setMoverPhase(next, "unspecified");
    }

    protected void setMoverPhase(MoverPhase next, String reason) {
        if (moverPhase == next) return;

        MoverPhase previous = moverPhase;
        moverPhase = next;
        phaseAgeTicks = 0;
        lastPhaseReason = reason == null || reason.isBlank() ? "unspecified" : reason;
        lastProgressAtMs = System.currentTimeMillis();
        StrictRuntimeLogger.logStashMover(
            "phase-transition",
            "from=" + previous + " to=" + moverPhase + " reason=" + lastPhaseReason
        );
        debugLog("phase=" + moverPhase + " reason=" + lastPhaseReason);

        if (next == MoverPhase.SEND_LOAD_PEARL_MSG) {
            resendLoadMessageTicks = 0;
            loadAckReceived = false;
        }
        if (next != MoverPhase.WAIT_FOR_PEARL) pearlChestNoPearlRetries = 0;
        if (next == MoverPhase.WAIT_FOR_PEARL) {
            ownPearlTracker.reset();
            trackedPearlReadyOnRemoval = false;
        }
    }

    protected void detectOperationalStall(BlockPos lootChest) {
        int timeout = Math.max(1, stallTimeoutTicks.get() / LOGIC_INTERVAL_TICKS);
        if (phaseAgeTicks < timeout) return;

        if (moverPhase == MoverPhase.WAIT_FOR_PEARL
            || moverPhase == MoverPhase.THROWING_PEARL
            || moverPhase == MoverPhase.AWAITING_RETURN_DEATH
            || moverPhase == MoverPhase.PUT_BACK_PEARLS) {
            lastStallReason = moverPhase.name().toLowerCase(Locale.ROOT) + "-timeout";
            recoverFromOperationalStall(lootChest);
        }
    }

    protected void recoverFromOperationalStall(BlockPos lootChest) {
        stallRecoveryCount++;
        switch (stallAction.get()) {
            case WARN_ONLY -> {
                warning("StashMover detected a water/pearl stall: " + lastStallReason);
                lastRecoveryAction = "warn-only";
                phaseAgeTicks = 0;
            }
            case RESET_PHASE -> {
                if (moverPhase == MoverPhase.AWAITING_RETURN_DEATH) {
                    warning("StashMover did not die after staging the return pearl; resending the lethal return command.");
                    sendChatCommand(normalizedReturnCommand());
                    phaseAgeTicks = 0;
                    actionCooldownTicks = 10;
                    lastRecoveryAction = "resend-lethal-return-command";
                    return;
                }
                if (moverPhase == MoverPhase.PUT_BACK_PEARLS) {
                    if (borrowedPearlChestSlot >= 0 || pearlChestSwapPending) {
                        warning("StashMover retried a stalled put-back phase to restore pearls and displaced items safely.");
                        closeHandledScreen();
                        phaseAgeTicks = 0;
                        actionCooldownTicks = 8;
                        lastRecoveryAction = "retry-put-back";
                    } else {
                warning("StashMover recovered from stalled put-back phase by continuing to loot chest.");
                continueAfterPearlStage(lootChest, "put-back-stall-recovery");
                lastRecoveryAction = "continue-after-put-back-stall";
            }
        } else {
            if (!loadingReturnPearlAfterDeposit && !isPlayerInventoryEmpty()) {
                warning("StashMover retried a stalled inbound pearl wait by re-requesting the loader click.");
                closeHandledScreen();
                cancelGoal("stall-recovery-resend-load");
                resendLoadMessageTicks = 0;
                loadAckReceived = false;
                setMoverPhase(MoverPhase.SEND_LOAD_PEARL_MSG, "stall-recovery-resend-load");
                actionCooldownTicks = 10;
                lastRecoveryAction = "resend-load-message";
                return;
            }
            warning("StashMover reset stalled water/pearl phase: " + lastStallReason);
            setMoverPhase(MoverPhase.WAIT_FOR_PEARL, "stall-recovery");
            actionCooldownTicks = 10;
            lastRecoveryAction = "reset-to-wait-for-pearl";
        }
            }
            case DISABLE_MODULE -> {
                error("Disabling StashMover after detected stall: " + lastStallReason);
                lastRecoveryAction = "disable-module";
                toggle();
            }
        }
    }

    private int resolveBorrowedPearlHotbarSlot() {
        if (borrowedPearlHotbarSlot >= 0 && borrowedPearlHotbarSlot < 9) {
            ItemStack trackedStack = mc.player.getInventory().getStack(borrowedPearlHotbarSlot);
            if (!trackedStack.isEmpty() && trackedStack.isOf(Items.ENDER_PEARL)) return borrowedPearlHotbarSlot;
        }
        return findPearlHotbarSlot();
    }

    private boolean restoreDisplacedPearlChestStack(ScreenHandler handler) {
        int storageSlot = borrowedPearlChestSlot;
        if (storageSlot < 0 || storageSlot >= storageSlotCount(handler)) {
            clearPearlChestBorrowState();
            warning("Pearl chest restore slot was lost before the displaced stack could be recovered.");
            return false;
        }
        int hotbarSlot = borrowedPearlHotbarSlot >= 0 ? borrowedPearlHotbarSlot : resolveBorrowedPearlHotbarSlot();
        if (hotbarSlot < 0 || hotbarSlot >= 9) return false;
        ItemStack storageStack = handler.getSlot(storageSlot).getStack();
        if (storageStack.isEmpty() || storageStack.isOf(Items.ENDER_PEARL)) {
            pearlChestSwapPending = false;
            int pearlHotbarSlot = resolveBorrowedPearlHotbarSlot();
            return pearlHotbarSlot == -1 || returnPearlsToChest(handler, pearlHotbarSlot);
        }
        int hotbarSlotId = hotbarSlotId(handler, hotbarSlot);
        mc.interactionManager.clickSlot(handler.syncId, storageSlot, 0, SlotActionType.PICKUP, mc.player);
        mc.interactionManager.clickSlot(handler.syncId, hotbarSlotId, 0, SlotActionType.PICKUP, mc.player);
        if (!handler.getCursorStack().isEmpty()) {
            mc.interactionManager.clickSlot(handler.syncId, storageSlot, 0, SlotActionType.PICKUP, mc.player);
        }

        boolean restored = handler.getCursorStack().isEmpty();
        if (restored) pearlChestSwapPending = false;
        return restored;
    }

    private boolean returnPearlsToChest(ScreenHandler handler, int pearlHotbarSlot) {
        int storageSlot = StashMoverPearlChestPolicy.resolvePearlChestReturnSlot(handler, storageSlotCount(handler), borrowedPearlChestSlot);
        if (storageSlot == -1) {
            warning("Pearl chest has no valid slot for returning the borrowed pearl stack.");
            return false;
        }
        int hotbarSlotId = hotbarSlotId(handler, pearlHotbarSlot);
        mc.interactionManager.clickSlot(handler.syncId, hotbarSlotId, 0, SlotActionType.PICKUP, mc.player);
        mc.interactionManager.clickSlot(handler.syncId, storageSlot, 0, SlotActionType.PICKUP, mc.player);
        if (!handler.getCursorStack().isEmpty()) {
            mc.interactionManager.clickSlot(handler.syncId, hotbarSlotId, 0, SlotActionType.PICKUP, mc.player);
            return false;
        }
        return true;
    }

    private Vec3d playerPosOrNull() {
        return mc.player == null ? null : mc.player.getEntityPos();
    }
}
