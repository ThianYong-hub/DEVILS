package com.example.addon.modules.stashmover;

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
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

abstract class StashMoverRuntime extends StashMoverInteraction {
    protected void tickMover() {
        BlockPos pearlChest = pearlChestPos();
        BlockPos lootChest = lootChestPos();
        BlockPos water = waterPos();
        Vec3d chamber = chamberLookPos();
        switch (moverPhase) {
            case SEND_LOAD_PEARL_MSG -> tickSendLoadMessage();
            case WAIT_FOR_PEARL -> tickWaitForPearl(pearlChest, water, chamber);
            case THROWING_PEARL -> tickThrowingPearl(water, chamber);
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
            requestGoal(GoalKind.CHAMBER, chamberBlock, "loader-chamber-approach");
            return;
        }
        if (useBlock(chamberBlock, chamber)) loaderPhase = LoaderPhase.WAITING;
    }

    protected void tickSendLoadMessage() {
        if (loadAckReceived && isPartnerLoadedNearby()) {
            enterWaitForPearlPhase();
            return;
        }
        if (loadAckReceived) return;
        if (resendLoadMessageTicks > 0) {
            resendLoadMessageTicks--;
            return;
        }
        String tokenA = UUID.randomUUID().toString().substring(0, 4);
        String tokenB = UUID.randomUUID().toString().substring(0, 4);
        sendChatCommand("msg " + partnerName.get() + " " + tokenA + loadMessage.get() + " " + tokenB);
        resendLoadMessageTicks = LOAD_MESSAGE_RESEND_TICKS;
    }

    protected void tickWaitForPearl(BlockPos pearlChest, BlockPos water, Vec3d chamber) {
        if (water != null) requestGoal(GoalKind.WATER, water, "wait-for-pearl-water");
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
        if (findPearlHotbarSlot() != -1) {
            closeHandledScreen();
            setMoverPhase(MoverPhase.THROWING_PEARL, "pearl-ready-in-hotbar");
            return;
        }
        PearlTakeResult result = takeSinglePearlFromOpenContainer();
        if (result == PearlTakeResult.NO_SAFE_SLOT) {
            error("No safe hotbar slot available for the single pearl retrieval policy.");
            toggle();
        } else if (result == PearlTakeResult.NO_PEARL_IN_CONTAINER) {
            error("Pearl chest does not contain any ender pearls.");
            toggle();
        }
    }

    protected void tickThrowingPearl(BlockPos water, Vec3d chamber) {
        if (ownPearlTracker.isAwaitingSpawn() || ownPearlTracker.hasTrackedPearl()) return;
        FindItemResult pearl = InvUtils.findInHotbar(Items.ENDER_PEARL);
        if (!pearl.found()) {
            recordPearlFailure("pearl-missing-from-hotbar");
            setMoverPhase(MoverPhase.WAIT_FOR_PEARL, "pearl-missing-from-hotbar");
            return;
        }
        Vec3d pearlTarget = resolvePearlTarget(water, chamber);
        if (pearlTarget == null) {
            recordPearlFailure("pearl-target-unavailable");
            setMoverPhase(MoverPhase.WAIT_FOR_PEARL, "pearl-target-unavailable");
            return;
        }
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
            warning("Pearl target is obstructed; refusing to throw into a block.");
            recordPearlFailure("pearl-target-obstructed");
            actionCooldownTicks = 5;
            return;
        }
        float yaw = (float) Rotations.getYaw(pearlTarget);
        float pitch = 90.0f;
        lastPearlTarget = pearlTarget;
        lastPearlThrowYaw = yaw;
        lastPearlThrowPitch = pitch;
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
            lastPearlFailureReason = "none";
            actionCooldownTicks = 2;
        });
    }

    protected void tickPutBackPearl(BlockPos pearlChest, BlockPos lootChest) {
        if (!isOpenTarget(pearlChest)) {
            tryOpenContainer(pearlChest, false);
            return;
        }
        ScreenHandler handler = mc.player.currentScreenHandler;
        if (!isChestLikeHandler(handler)) return;
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
            continueAfterPearlStage(lootChest, "put-back-no-hotbar-pearl");
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
                setMoverPhase(MoverPhase.SEND_LOAD_PEARL_MSG, "no-source-chests-left");
                disableAfterPartnerSeen = true;
                return;
            }

            currentLootSourceChest = chest.toImmutable();
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
        blacklistSourceChest(currentLootSourceChest);

        if (!hasRemainingEligibleSourceChest(lootChest, pearlChestPos())) {
            setMoverPhase(MoverPhase.SEND_LOAD_PEARL_MSG, "all-source-chests-processed");
            disableAfterPartnerSeen = true;
        }
    }

    protected void tickWalkingToDestination(BlockPos lootChest) {
        if (lootChest == null) return;
        if (mc.player.getEyePos().squaredDistanceTo(Vec3d.ofCenter(lootChest)) > DESTINATION_CLOSE_SQ) {
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
            cancelGoal("inventory-empty-self-kill");
            debugLog("inventory empty at loot chest, cleared destination goal and dispatching /kill");
            sendChatCommand("kill");
            actionCooldownTicks = 10;
            return;
        }

        if (!isOpenTarget(lootChest)) {
            tryOpenContainer(lootChest, false);
            return;
        }

        ScreenHandler handler = mc.player.currentScreenHandler;
        if (!isChestLikeHandler(handler)) return;

        if (isStorageFull(handler)) {
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
                error("Pearl chest position is not configured.");
                toggle();
                return false;
            }
            if (lootChestPos() == null) {
                error("Loot chest position is not configured.");
                toggle();
                return false;
            }
            if (waterPos() == null) {
                error("Water position is not configured.");
                toggle();
                return false;
            }
        }

        if (chamberLookPos() == null) {
            error("Chamber position is not configured.");
            toggle();
            return false;
        }

        return true;
    }

    protected void handlePlayerDeath() {
        closeHandledScreen();
        cancelGoal("player-died");
        setMoverPhase(MoverPhase.LOOT, "player-died");
        loaderPhase = LoaderPhase.WAITING;
        ownPearlTracker.reset();
        clearPearlChestBorrowState();
        currentLootSourceChest = null;
        openedContainerTarget = null;
        renderedSourceChests.clear();
        loadAckReceived = false;
        disableAfterPartnerSeen = false;
        waitingForDestinationSpace = false;
        chestActionCooldownTicks = 0;
        actionCooldownTicks = 10;
        logicPulseTicks = 0;
        phaseAgeTicks = 0;
        resendLoadMessageTicks = 0;
        stationaryTicks = 0;
        lastPacketReceivedAtMs = System.currentTimeMillis();
        lastProgressAtMs = System.currentTimeMillis();
        clearPearlFailureState();
        lastStallReason = "none";
        lastRecoveryAction = "none";

        try {
            mc.player.requestRespawn();
        } catch (Throwable ignored) {
        }
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
                setMoverPhase(MoverPhase.PUT_BACK_PEARLS, "tracked-own-pearl-world-scan");
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

        if (disableAfterPartnerSeen) {
            disableAfterPartnerSeen = false;
            toggle();
        }
    }

    protected void setMoverPhase(MoverPhase next) {
        setMoverPhase(next, "unspecified");
    }

    protected void setMoverPhase(MoverPhase next, String reason) {
        if (moverPhase == next) return;

        moverPhase = next;
        phaseAgeTicks = 0;
        lastPhaseReason = reason == null || reason.isBlank() ? "unspecified" : reason;
        lastProgressAtMs = System.currentTimeMillis();
        debugLog("phase=" + moverPhase + " reason=" + lastPhaseReason);

        if (next == MoverPhase.SEND_LOAD_PEARL_MSG) {
            resendLoadMessageTicks = 0;
            loadAckReceived = false;
        }
        if (next == MoverPhase.WAIT_FOR_PEARL) ownPearlTracker.reset();
    }

    protected void detectOperationalStall(BlockPos lootChest) {
        int timeout = Math.max(1, stallTimeoutTicks.get() / LOGIC_INTERVAL_TICKS);
        if (phaseAgeTicks < timeout) return;

        if (moverPhase == MoverPhase.WAIT_FOR_PEARL || moverPhase == MoverPhase.THROWING_PEARL || moverPhase == MoverPhase.PUT_BACK_PEARLS) {
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
        int storageSlot = resolvePearlChestReturnSlot(handler);
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

    private int resolvePearlChestReturnSlot(ScreenHandler handler) {
        int storageSlots = storageSlotCount(handler);
        if (borrowedPearlChestSlot >= 0 && borrowedPearlChestSlot < storageSlots) {
            ItemStack tracked = handler.getSlot(borrowedPearlChestSlot).getStack();
            if (tracked.isEmpty() || tracked.isOf(Items.ENDER_PEARL)) return borrowedPearlChestSlot;
        }
        for (int i = 0; i < storageSlots; i++) {
            if (handler.getSlot(i).getStack().isOf(Items.ENDER_PEARL)) return i;
        }
        for (int i = 0; i < storageSlots; i++) {
            if (handler.getSlot(i).getStack().isEmpty()) return i;
        }
        return -1;
    }
}
