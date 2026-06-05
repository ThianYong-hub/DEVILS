package com.devils.addon.modules.stashmover;

import com.devils.addon.util.runtime.StrictRuntimeLogger;
import com.devils.addon.util.CrashGuard;
import java.util.Locale;
import meteordevelopment.meteorclient.events.entity.EntityAddedEvent;
import meteordevelopment.meteorclient.events.entity.EntityRemovedEvent;
import meteordevelopment.meteorclient.events.game.GameJoinedEvent;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.game.ReceiveMessageEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.thrown.EnderPearlEntity;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

public class StashMover extends StashMoverRuntime {
    @Override
    public void onActivate() {
        resetRuntime(true);
        blacklistedSourceChests.clear();
        renderedSourceChests.clear();
        lastPacketReceivedAtMs = System.currentTimeMillis();
    }

    @Override
    public void onDeactivate() {
        releaseSneakRecovery();
        closeHandledScreen();
        cancelGoal("module-deactivated");
        ownPearlTracker.reset();
        openedContainerTarget = null;
        currentLootSourceChest = null;
        renderedSourceChests.clear();
    }

    @Override
    public String getInfoString() {
        String movedSummary = "Dubs: " + Math.floor(movedStacks / 54.0);
        return switch (mode.get()) {
            case LOADER -> loaderPhase.name();
            case MOVER -> moverPhase.name() + " " + movedSummary;
        };
    }

    @EventHandler
    private void onGameJoined(GameJoinedEvent event) {
        CrashGuard.run(this, "onGameJoined", this::onGameJoinedSafe);
    }

    private void onGameJoinedSafe() {
        lastPacketReceivedAtMs = System.currentTimeMillis();
        if (isActive() && mode.get() == Mode.MOVER && reconnectNudgeAfterJoin) {
            reconnectNudgeAfterJoin = false;
            reconnectNudgeTicks = RECONNECT_NUDGE_DELAY_TICKS;
        }
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        CrashGuard.run(this, "onGameLeft", this::onGameLeftSafe);
    }

    private void onGameLeftSafe() {
        boolean scheduleReconnectNudge = isActive() && mode.get() == Mode.MOVER;
        resetRuntime(false);
        if (scheduleReconnectNudge) reconnectNudgeAfterJoin = true;
    }

    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        lastPacketReceivedAtMs = System.currentTimeMillis();
    }

    @EventHandler
    private void onReceiveMessage(ReceiveMessageEvent event) {
        CrashGuard.run(this, "onReceiveMessage", () -> onReceiveMessageSafe(event));
    }

    private void onReceiveMessageSafe(ReceiveMessageEvent event) {
        String message = event.getMessage() == null ? "" : event.getMessage().getString();
        if (message.isBlank()) return;
        handleReceivedChatMessage(message);
    }

    private void handleReceivedChatMessage(String message) {
        if (message == null || message.isBlank()) return;
        if (isOwnDiagnosticChatMessage(message)) return;
        StrictRuntimeLogger.logStashMover("chat-receive", "message=" + message);
        if (mode.get() == Mode.MOVER && isReturnDeathConfirmationMessage(message)) {
            handleReturnDeathConfirmed("chat-death-confirmation", message);
            return;
        }
        if (mode.get() == Mode.LOADER) {
            if (isLoadRequestFromPartner(message)) {
                if (message.equals(lastLoaderLoadRequestMessage) && loaderPhase == LoaderPhase.LOAD_PEARL) return;
                lastLoaderLoadRequestMessage = message;
                StrictRuntimeLogger.logStashMover("loader-request", "partner=" + partnerName.get() + " message=" + message);
                debugLog("loader received partner load request: " + message);
                loaderPhase = LoaderPhase.LOAD_PEARL;
                loaderAckPending = true;
                actionCooldownTicks = 10;
            }
            return;
        }

        if (isAckMessageFromPartner(message)) {
            StrictRuntimeLogger.logStashMover("loader-ack", "partner=" + partnerName.get() + " message=" + message);
            debugLog("mover received partner ack: " + message);
            loadAckReceived = true;
            if (moverPhase == MoverPhase.SEND_LOAD_PEARL_MSG) {
                enterWaitForPearlPhase();
            }
        }
    }

    private static boolean isOwnDiagnosticChatMessage(String message) {
        String normalized = message == null ? "" : message.toLowerCase(Locale.ROOT);
        return normalized.contains("[stash mover] [diag]")
            || (normalized.contains("[meteor]") && normalized.contains("[stash mover]"));
    }

    private boolean isReturnDeathConfirmationMessage(String message) {
        if (moverPhase != MoverPhase.AWAITING_RETURN_DEATH || message == null || mc.player == null) return false;

        String playerName = mc.player.getGameProfile().name();
        String normalized = message.toLowerCase(Locale.ROOT);
        String normalizedName = playerName == null ? "" : playerName.toLowerCase(Locale.ROOT);

        if (normalized.contains("death position saved")) return true;
        if (normalized.contains("позиция смерти") || normalized.contains("death waypoint")) return true;
        if (normalizedName.isBlank()) return false;

        return normalized.contains(normalizedName + " was killed")
            || normalized.contains("killed " + normalizedName)
            || normalized.contains(normalizedName + " died")
            || normalized.contains(normalizedName + " был убит")
            || normalized.contains(normalizedName + " умер")
            || normalized.contains("убит " + normalizedName);
    }

    @EventHandler
    private void onEntityAdded(EntityAddedEvent event) {
        CrashGuard.run(this, "onEntityAdded", () -> onEntityAddedSafe(event));
    }

    private void onEntityAddedSafe(EntityAddedEvent event) {
        if (mc.player == null) return;

        Entity entity = event.entity;
        if (entity instanceof EnderPearlEntity pearl) {
            if (ownPearlTracker.onPearlAdded(entity.getId(), pearl.getOwner() == mc.player)
                == StashMoverOwnPearlTracker.CaptureOutcome.TRACKED) {
                StrictRuntimeLogger.logStashMover(
                    "pearl-spawn",
                    "entityId=" + entity.getId() + " pos=" + formatVecForFeedback(entity.getEntityPos()) + " owned=true"
                );
                trackedPearlReadyOnRemoval = false;
                clearPearlFailureState();
                cancelGoal("tracked-own-pearl-spawn");
                ownPearlStasisTicks = 0;
                actionCooldownTicks = 1;
                return;
            }
        }

        if (entity instanceof PlayerEntity player
            && mode.get() == Mode.MOVER
            && moverPhase == MoverPhase.SEND_LOAD_PEARL_MSG
            && player.getGameProfile().name().equalsIgnoreCase(partnerName.get())) {
            enterWaitForPearlPhase();
        }
    }

    @EventHandler
    private void onEntityRemoved(EntityRemovedEvent event) {
        CrashGuard.run(this, "onEntityRemoved", () -> onEntityRemovedSafe(event));
    }

    private void onEntityRemovedSafe(EntityRemovedEvent event) {
        if (mc.player == null || mc.player.isDead()) return;
        if (event.entity instanceof EnderPearlEntity) {
            StrictRuntimeLogger.logStashMover(
                "pearl-remove",
                "entityId=" + event.entity.getId() + " pos=" + formatVecForFeedback(event.entity.getEntityPos()) + " phase=" + moverPhase
            );
        }

        if (ownPearlTracker.onEntityRemoved(event.entity.getId()) == StashMoverOwnPearlTracker.RemovalOutcome.TRACKED_REMOVED
            && shouldResetOnTrackedPearlRemoval()) {
            // hack: some servers remove the pearl entity before our stasis state catches up.
            if (event.entity instanceof EnderPearlEntity pearl && shouldTreatTrackedPearlRemovalAsReady(pearl)) {
                acceptTrackedPearlRemovalAsReady(pearl);
                return;
            }
            cancelGoal("tracked-pearl-removed");
            if (isChestLikeHandler(mc.player.currentScreenHandler)) closeHandledScreen();
            trackedPearlReadyOnRemoval = false;
            recordPearlFailure("tracked-pearl-removed-during-throw");
            setMoverPhase(MoverPhase.WAIT_FOR_PEARL, "tracked-pearl-removed");
            actionCooldownTicks = 0;
        } else if (event.entity instanceof EnderPearlEntity) {
            debugLog("tracked pearl removal ignored in phase=" + moverPhase + " reason=" + lastPhaseReason);
        }
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        CrashGuard.run(this, "onRender3D", () -> onRender3DSafe(event));
    }

    private void onRender3DSafe(Render3DEvent event) {
        if (!renderSourceChests.get() || renderedSourceChests.isEmpty() || mc.player == null) return;

        for (BlockPos pos : renderedSourceChests) {
            if (Vec3d.ofCenter(pos).distanceTo(mc.player.getEyePos()) > renderDistance.get()) continue;
            event.renderer.box(new Box(pos), renderColor.get(), renderColor.get(), ShapeMode.Both, 0);
        }
    }

    @EventHandler
    private void onTickPre(TickEvent.Pre event) {
        CrashGuard.run(this, "onTickPre", this::onTickPreSafe);
    }

    private void onTickPreSafe() {
        if (mc.player == null || mc.world == null) return;

        tickTrackedPearlState();
        updateMovementState();

        if (chestActionCooldownTicks > 0) chestActionCooldownTicks--;

        if (reconnectNudgeTicks > 0) {
            reconnectNudgeTicks--;
            if (reconnectNudgeTicks == 0) {
                BlockPos target = randomNearbyGoal(24);
                if (target != null) requestGoal(GoalKind.RECONNECT_NUDGE, target, "reconnect-nudge");
            }
            return;
        }

        if (actionCooldownTicks > 0) {
            actionCooldownTicks--;
            return;
        }

        if (++logicPulseTicks < LOGIC_INTERVAL_TICKS) return;
        logicPulseTicks = 0;
        phaseAgeTicks++;

        if (!validateConfiguration()) return;
        if (isPacketFreshnessExpired()) return;

        if (mc.player.isDead()) {
            handlePlayerDeath();
            return;
        }

        if (mode.get() == Mode.MOVER) tickMover();
        else tickLoader();
    }

    public String capturePearlChestFromCrosshair() {
        if (mc.world == null || mc.crosshairTarget == null) return "Not in a world.";
        if (!(mc.crosshairTarget instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) {
            return "Look at a block to capture pearl chest.";
        }

        pearlChestSetting.set(StashMoverConfigCodec.encodeBlockPos(hit.getBlockPos()));
        return "Set pearlchest to " + formatBlockPosForFeedback(hit.getBlockPos());
    }

    public String captureLootChestFromCrosshair() {
        if (mc.world == null || mc.crosshairTarget == null) return "Not in a world.";
        if (!(mc.crosshairTarget instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) {
            return "Look at a block to capture loot chest.";
        }

        lootChestSetting.set(StashMoverConfigCodec.encodeBlockPos(hit.getBlockPos()));
        return "Set lootchest to " + formatBlockPosForFeedback(hit.getBlockPos());
    }

    public String captureChamberFromCrosshair() {
        if (mc.world == null || mc.crosshairTarget == null) return "Not in a world.";
        if (!(mc.crosshairTarget instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) {
            return "Look at a block to capture chamber.";
        }

        chamberSetting.set(StashMoverConfigCodec.encodeVec3d(hit.getPos()));
        return "Set chamber to " + formatVecForFeedback(hit.getPos());
    }

    public String captureWaterFromPlayer() {
        if (mc.player == null) return "Not in a world.";

        waterSetting.set(StashMoverConfigCodec.encodeBlockPos(mc.player.getBlockPos()));
        return "Set water to " + formatBlockPosForFeedback(mc.player.getBlockPos());
    }

    public String capturePearlTargetFromCrosshair() {
        if (mc.world == null || mc.crosshairTarget == null) return "Not in a world.";
        if (mc.crosshairTarget.getType() == HitResult.Type.MISS) return "Look at the intended pearl entry point.";

        pearlTargetSetting.set(StashMoverConfigCodec.encodeVec3d(mc.crosshairTarget.getPos()));
        return "Set pearltarget to " + formatVecForFeedback(mc.crosshairTarget.getPos());
    }

    public String clearPosition(String key) {
        if (key == null || key.isBlank()) return "Specify a target to clear.";

        return switch (key.toLowerCase(Locale.ROOT)) {
            case "pearlchest", "pearl-chest" -> {
                pearlChestSetting.set("");
                yield "Cleared pearl chest position.";
            }
            case "lootchest", "loot-chest" -> {
                lootChestSetting.set("");
                yield "Cleared loot chest position.";
            }
            case "water" -> {
                waterSetting.set("");
                yield "Cleared water position.";
            }
            case "pearltarget", "pearl-target" -> {
                pearlTargetSetting.set("");
                yield "Cleared pearl target position.";
            }
            case "chamber" -> {
                chamberSetting.set("");
                yield "Cleared chamber position.";
            }
            default -> "Unknown target. Use pearlchest, lootchest, water, pearltarget, or chamber.";
        };
    }

    public void debugConfigureForHarness(BlockPos pearlChest, BlockPos lootChest, BlockPos water, Vec3d chamber, Vec3d pearlTarget) {
        pearlChestSetting.set(pearlChest == null ? "" : StashMoverConfigCodec.encodeBlockPos(pearlChest));
        lootChestSetting.set(lootChest == null ? "" : StashMoverConfigCodec.encodeBlockPos(lootChest));
        waterSetting.set(water == null ? "" : StashMoverConfigCodec.encodeBlockPos(water));
        chamberSetting.set(chamber == null ? "" : StashMoverConfigCodec.encodeVec3d(chamber));
        pearlTargetSetting.set(pearlTarget == null ? "" : StashMoverConfigCodec.encodeVec3d(pearlTarget));
    }

    public void debugEnableLoggingForHarness(boolean enabled) {
        debugLogging.set(enabled);
    }

    public void debugConfigureForStrictRuntime(
        Mode runtimeMode,
        String runtimePartner,
        boolean ignoreSingles,
        boolean useEnderChest,
        BlockPos pearlChest,
        BlockPos lootChest,
        BlockPos water,
        Vec3d chamber,
        Vec3d pearlTarget
    ) {
        mode.set(runtimeMode);
        if (runtimePartner != null && !runtimePartner.isBlank()) partnerName.set(runtimePartner);
        ignoreSingleChest.set(ignoreSingles);
        useEChest.set(useEnderChest);
        debugConfigureForHarness(pearlChest, lootChest, water, chamber, pearlTarget);
    }

    public void debugSetReturnCommandForHarness(String command) {
        returnCommand.set(command == null || command.isBlank() ? "kill" : command.trim());
    }

    public void debugSetLocalThrowSnapForHarness(boolean enabled) {
        localThrowSnapEnabled = enabled;
    }

    public void debugPrepareBootstrapReturnForHarness() {
        disableAfterPartnerSeen = false;
        loadingReturnPearlAfterDeposit = true;
        ownPearlTracker.reset();
        clearPearlChestBorrowState();
        currentLootSourceChest = null;
        openedContainerTarget = null;
        renderedSourceChests.clear();
        loadAckReceived = false;
        trackedPearlReadyOnRemoval = false;
        setMoverPhase(MoverPhase.WAIT_FOR_PEARL, "bootstrap-initial-return");
        actionCooldownTicks = 0;
        chestActionCooldownTicks = 0;
    }

    public void debugHandleReceivedChatMessageForHarness(String message) {
        handleReceivedChatMessage(message);
    }

    public String debugForceMoverPhaseForHarness(String phaseName) {
        if (phaseName == null || phaseName.isBlank()) return runtimeStatusSummary();
        setMoverPhase(MoverPhase.valueOf(phaseName.trim().toUpperCase(Locale.ROOT)), "debug-force");
        actionCooldownTicks = 0;
        return runtimeStatusSummary();
    }
}
