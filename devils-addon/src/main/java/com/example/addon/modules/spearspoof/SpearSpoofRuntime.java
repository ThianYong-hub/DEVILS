package com.example.addon.modules.spearspoof;

import net.minecraft.entity.LivingEntity;
import net.minecraft.util.math.Vec3d;

public final class SpearSpoofRuntime {
    LivingEntity target;

    long targetLockedAtMs;
    long targetLostAtMs;

    long useStartedAtMs;
    long lastForcedUseInteractMs;
    long lastStrikeAtMs;
    long nextAttemptAtMs;
    long repositionUntilMs;
    long movementResetUntilMs;
    long passPhaseStartMs;
    long lastVerticalDiveMs;
    long lastStuckSampleMs;
    long unstuckUntilMs;
    long pitVerticalLockUntilMs;
    long rechargeRebuildUntilMs;
    long hitConfirmStartMs;
    long hitConfirmUntilMs;
    long lastConfirmedHitMs;
    long lastKnownTargetSeenAtMs;

    int swapBackSlot = -1;
    int swapBackTicks;
    int windupRestartTicks;
    int relaunchJumpTicks;
    int relaunchGroundTicks;
    int stuckTicks;
    int switchDelayTicks;
    int pitVerticalLockTargetId = -1;

    boolean useKeyInjected;
    boolean pendingRmbRecharge;
    boolean hitConfirmPending;
    int rmbRechargeReleaseTicks;
    int hitConfirmTargetId = -1;
    int hitConfirmRetryCount;
    float hitConfirmBaseHealth;
    float hitConfirmBaseAbsorption;

    long attemptId;

    int hitChain;
    int rejectStreak;
    int speedRejectStreak;
    int forwardRejectStreak;
    String lastRejectReason = "";

    PassPhase passPhase = PassPhase.APPROACH;
    Vec3d lockedApproachDirection = new Vec3d(1.0, 0.0, 0.0);
    Vec3d resetDirection = Vec3d.ZERO;
    Vec3d lastStuckSamplePos = Vec3d.ZERO;
    Vec3d lastKnownTargetPos = Vec3d.ZERO;
    Vec3d lastKnownTargetVel = Vec3d.ZERO;
    double resetStartY = Double.NaN;
    double resetRequiredY = Double.NaN;
    int lastKnownTargetId = -1;

    double adaptiveHorizontalCap = -1.0;
    int antiCheatSlowTicks;

    long devRmbDownSinceMs;
    long devLastStateLogMs;
    long devPendingAttackAtMs;
    long devLastHitAtMs;
    int devPendingAttackTargetId = -1;
    int devLastHitTargetId = -1;
    int devLastLoggedTargetId = -1;
    int devHitCount;
    boolean devKeysInitialized;
    boolean devPrevUsePressed;
    boolean devPrevAttackPressed;
    boolean devPrevJumpPressed;
    boolean devPrevSneakPressed;
    boolean devPrevForwardPressed;
    boolean devPrevBackPressed;
    boolean devPrevLeftPressed;
    boolean devPrevRightPressed;
    boolean devPrevSprintPressed;
    double devLastHitDistance = -1.0;
    double devMaxDistanceAfterHit = 0.0;
    double devLastRetreatDistance = -1.0;
    double devLastReturnDistance = -1.0;
    String devLastHitSignal = "";

    public void resetOnActivate() {
        target = null;
        targetLockedAtMs = 0;
        targetLostAtMs = 0;

        useStartedAtMs = 0;
        lastForcedUseInteractMs = 0;
        lastStrikeAtMs = 0;
        nextAttemptAtMs = 0;
        repositionUntilMs = 0;
        movementResetUntilMs = 0;
        passPhaseStartMs = System.currentTimeMillis();
        lastVerticalDiveMs = 0;
        lastStuckSampleMs = 0;
        unstuckUntilMs = 0;
        pitVerticalLockUntilMs = 0;
        rechargeRebuildUntilMs = 0;
        hitConfirmStartMs = 0;
        hitConfirmUntilMs = 0;
        lastConfirmedHitMs = 0;
        lastKnownTargetSeenAtMs = 0;

        swapBackSlot = -1;
        swapBackTicks = 0;
        windupRestartTicks = 0;
        relaunchJumpTicks = 0;
        relaunchGroundTicks = 0;
        stuckTicks = 0;
        switchDelayTicks = 0;
        pitVerticalLockTargetId = -1;

        useKeyInjected = false;
        pendingRmbRecharge = false;
        hitConfirmPending = false;
        rmbRechargeReleaseTicks = 0;
        hitConfirmTargetId = -1;
        hitConfirmRetryCount = 0;
        hitConfirmBaseHealth = 0.0f;
        hitConfirmBaseAbsorption = 0.0f;

        attemptId = 0;

        hitChain = 0;
        rejectStreak = 0;
        speedRejectStreak = 0;
        forwardRejectStreak = 0;
        lastRejectReason = "";
        passPhase = PassPhase.APPROACH;
        lockedApproachDirection = new Vec3d(1.0, 0.0, 0.0);
        resetDirection = Vec3d.ZERO;
        lastStuckSamplePos = Vec3d.ZERO;
        lastKnownTargetPos = Vec3d.ZERO;
        lastKnownTargetVel = Vec3d.ZERO;
        resetStartY = Double.NaN;
        resetRequiredY = Double.NaN;
        lastKnownTargetId = -1;
        adaptiveHorizontalCap = -1.0;
        antiCheatSlowTicks = 0;
        resetDevState();
    }

    public void resetOnDeactivate() {
        target = null;
        targetLockedAtMs = 0;
        targetLostAtMs = 0;

        useStartedAtMs = 0;
        lastForcedUseInteractMs = 0;
        movementResetUntilMs = 0;
        passPhaseStartMs = 0;
        lastVerticalDiveMs = 0;
        lastStuckSampleMs = 0;
        unstuckUntilMs = 0;
        pitVerticalLockUntilMs = 0;
        rechargeRebuildUntilMs = 0;
        hitConfirmStartMs = 0;
        hitConfirmUntilMs = 0;
        lastConfirmedHitMs = 0;
        lastKnownTargetSeenAtMs = 0;

        swapBackSlot = -1;
        swapBackTicks = 0;
        windupRestartTicks = 0;
        relaunchJumpTicks = 0;
        relaunchGroundTicks = 0;
        stuckTicks = 0;
        switchDelayTicks = 0;
        pitVerticalLockTargetId = -1;

        useKeyInjected = false;
        pendingRmbRecharge = false;
        hitConfirmPending = false;
        rmbRechargeReleaseTicks = 0;
        hitConfirmTargetId = -1;
        hitConfirmRetryCount = 0;
        hitConfirmBaseHealth = 0.0f;
        hitConfirmBaseAbsorption = 0.0f;

        hitChain = 0;
        rejectStreak = 0;
        speedRejectStreak = 0;
        forwardRejectStreak = 0;
        lastRejectReason = "";
        passPhase = PassPhase.APPROACH;
        lockedApproachDirection = new Vec3d(1.0, 0.0, 0.0);
        resetDirection = Vec3d.ZERO;
        lastStuckSamplePos = Vec3d.ZERO;
        lastKnownTargetPos = Vec3d.ZERO;
        lastKnownTargetVel = Vec3d.ZERO;
        resetStartY = Double.NaN;
        resetRequiredY = Double.NaN;
        lastKnownTargetId = -1;
        adaptiveHorizontalCap = -1.0;
        antiCheatSlowTicks = 0;
        resetDevState();
    }

    public void clearTargetAndWindup() {
        target = null;
        useStartedAtMs = 0;
        hitChain = 0;
        pendingRmbRecharge = false;
        rmbRechargeReleaseTicks = 0;
        passPhase = PassPhase.APPROACH;
        movementResetUntilMs = 0;
        resetDirection = Vec3d.ZERO;
        resetStartY = Double.NaN;
        resetRequiredY = Double.NaN;
        unstuckUntilMs = 0;
        stuckTicks = 0;
        switchDelayTicks = 0;
        pitVerticalLockUntilMs = 0;
        pitVerticalLockTargetId = -1;
        rechargeRebuildUntilMs = 0;
        hitConfirmPending = false;
        hitConfirmTargetId = -1;
        hitConfirmRetryCount = 0;
        hitConfirmStartMs = 0;
        hitConfirmUntilMs = 0;
        hitConfirmBaseHealth = 0.0f;
        hitConfirmBaseAbsorption = 0.0f;
    }

    public long holdMs(long now) {
        return useStartedAtMs > 0 ? now - useStartedAtMs : 0;
    }

    public long nextAttemptId() {
        return ++attemptId;
    }

    public void onReject(String reason) {
        rejectStreak++;
        lastRejectReason = reason == null ? "" : reason;

        if ("LowSpeed".equals(reason)) speedRejectStreak++;
        else speedRejectStreak = 0;

        if ("BadForward".equals(reason)) forwardRejectStreak++;
        else forwardRejectStreak = 0;

        hitChain = 0;
    }

    public void onHit(long now) {
        lastStrikeAtMs = now;
        lastConfirmedHitMs = now;
        hitConfirmPending = false;
        hitConfirmTargetId = -1;
        hitConfirmRetryCount = 0;
        hitConfirmStartMs = 0;
        hitConfirmUntilMs = 0;
        hitConfirmBaseHealth = 0.0f;
        hitConfirmBaseAbsorption = 0.0f;
        rejectStreak = 0;
        speedRejectStreak = 0;
        forwardRejectStreak = 0;
        lastRejectReason = "";
        hitChain++;
        beginReset(now, 220L);
    }

    public void onStrikeSent(long now, LivingEntity strikeTarget, long confirmWindowMs) {
        lastStrikeAtMs = now;
        if (strikeTarget == null) {
            hitConfirmPending = false;
            hitConfirmTargetId = -1;
            hitConfirmStartMs = 0;
            hitConfirmUntilMs = 0;
            hitConfirmBaseHealth = 0.0f;
            hitConfirmBaseAbsorption = 0.0f;
            return;
        }

        hitConfirmPending = true;
        hitConfirmTargetId = strikeTarget.getId();
        hitConfirmRetryCount = 0;
        hitConfirmStartMs = now;
        hitConfirmUntilMs = now + Math.max(60L, confirmWindowMs);
        hitConfirmBaseHealth = strikeTarget.getHealth();
        hitConfirmBaseAbsorption = strikeTarget.getAbsorptionAmount();
    }

    public boolean isAwaitingHitConfirm(LivingEntity entity, long now) {
        if (!hitConfirmPending) return false;
        if (now > hitConfirmUntilMs) return false;
        if (entity == null) return false;
        return entity.getId() == hitConfirmTargetId;
    }

    public void clearHitConfirm() {
        hitConfirmPending = false;
        hitConfirmTargetId = -1;
        hitConfirmRetryCount = 0;
        hitConfirmStartMs = 0;
        hitConfirmUntilMs = 0;
        hitConfirmBaseHealth = 0.0f;
        hitConfirmBaseAbsorption = 0.0f;
    }

    public void beginReset(long now, long holdMs) {
        if (passPhase != PassPhase.RESET) {
            passPhase = PassPhase.RESET;
            passPhaseStartMs = now;
            resetStartY = Double.NaN;
            resetRequiredY = Double.NaN;
        }
        movementResetUntilMs = Math.max(movementResetUntilMs, now + Math.max(0L, holdMs));
    }

    public void toApproach(long now) {
        passPhase = PassPhase.APPROACH;
        passPhaseStartMs = now;
        movementResetUntilMs = 0;
        resetDirection = Vec3d.ZERO;
        resetStartY = Double.NaN;
        resetRequiredY = Double.NaN;
    }

    public boolean isResetActive(long now) {
        return passPhase == PassPhase.RESET && now < movementResetUntilMs;
    }

    public void rememberTargetSnapshot(LivingEntity living, long now) {
        if (living == null) return;
        lastKnownTargetId = living.getId();
        lastKnownTargetPos = living.getEntityPos();
        lastKnownTargetVel = living.getVelocity();
        lastKnownTargetSeenAtMs = now;
    }

    public void resetDevState() {
        devRmbDownSinceMs = 0L;
        devLastStateLogMs = 0L;
        devPendingAttackAtMs = 0L;
        devLastHitAtMs = 0L;
        devPendingAttackTargetId = -1;
        devLastHitTargetId = -1;
        devLastLoggedTargetId = -1;
        devHitCount = 0;
        devKeysInitialized = false;
        devPrevUsePressed = false;
        devPrevAttackPressed = false;
        devPrevJumpPressed = false;
        devPrevSneakPressed = false;
        devPrevForwardPressed = false;
        devPrevBackPressed = false;
        devPrevLeftPressed = false;
        devPrevRightPressed = false;
        devPrevSprintPressed = false;
        devLastHitDistance = -1.0;
        devMaxDistanceAfterHit = 0.0;
        devLastRetreatDistance = -1.0;
        devLastReturnDistance = -1.0;
        devLastHitSignal = "";
    }

    public void onDevAttackPacket(long now, int targetId) {
        devPendingAttackAtMs = now;
        devPendingAttackTargetId = targetId;
    }

    public void onDevHitConfirmed(long now, int targetId, double hitDistance, String signal) {
        devHitCount++;
        devLastRetreatDistance = devHitCount > 1 ? devMaxDistanceAfterHit : -1.0;
        devLastReturnDistance = hitDistance;
        devLastHitDistance = hitDistance;
        devMaxDistanceAfterHit = Math.max(0.0, hitDistance);
        devLastHitAtMs = now;
        devLastHitTargetId = targetId;
        devLastHitSignal = signal == null ? "" : signal;
        devPendingAttackAtMs = 0L;
        devPendingAttackTargetId = -1;
    }

    public void updateDevDistanceAfterHit(double distance) {
        if (distance < 0.0 || devHitCount <= 0) return;
        devMaxDistanceAfterHit = Math.max(devMaxDistanceAfterHit, distance);
    }

    public enum PassPhase {
        APPROACH,
        RESET
    }
}
