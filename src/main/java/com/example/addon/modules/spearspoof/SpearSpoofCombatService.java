package com.example.addon.modules.spearspoof;

import com.example.addon.modules.SpearSpoof;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.world.TickRate;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.math.Vec3d;



public final class SpearSpoofCombatService extends SpearSpoofCombatDecisionOps {
    public SpearSpoofCombatService(
        SpearSpoof module,
        SpearSpoofRuntime runtime,
        SpearSpoofTargetingService targeting,
        SpearSpoofDebugLogger debugLogger,
        Setting<Boolean> onlyWhileElytra,
        Setting<Boolean> autoSwitch,
        Setting<Boolean> autoHoldUse,
        Setting<Boolean> autoRestartWindup,
        Setting<Boolean> attributeSwap,
        Setting<Boolean> rotate,
        Setting<Boolean> yawCamera,
        Setting<Boolean> mode4x,
        Setting<Double> maxVerticalDelta,
        Setting<Double> minSpeedBps,
        Setting<Double> minForwardDot,
        Setting<Double> minClosingSpeedBps,
        Setting<Double> minCooldown,
        Setting<Double> maxYawError,
        Setting<Double> maxPitchError,
        Setting<Integer> minWindupMs,
        Setting<Integer> readyWindowMs,
        Setting<Integer> fatigueWindowMs,
        Setting<Integer> recoveryDelayMs,
        Setting<Boolean> requireLineOfSight,
        Setting<Boolean> adaptiveReposition,
        Setting<Integer> repositionRejectStreak,
        Setting<Integer> repositionHoldMs
    ) {
        super(
            module,
            runtime,
            targeting,
            debugLogger,
            onlyWhileElytra,
            autoSwitch,
            autoHoldUse,
            autoRestartWindup,
            attributeSwap,
            rotate,
            yawCamera,
            mode4x,
            maxVerticalDelta,
            minSpeedBps,
            minForwardDot,
            minClosingSpeedBps,
            minCooldown,
            maxYawError,
            maxPitchError,
            minWindupMs,
            readyWindowMs,
            fatigueWindowMs,
            recoveryDelayMs,
            requireLineOfSight,
            adaptiveReposition,
            repositionRejectStreak,
            repositionHoldMs
        );
    }

    public void onDeactivate() {
        runtime.clearTargetAndWindup();
        clearPacketConfirm();

        if (runtime.swapBackSlot >= 0) {
            InvUtils.swap(runtime.swapBackSlot, false);
            runtime.swapBackSlot = -1;
            runtime.swapBackTicks = 0;
        }

        releaseUseKey();
    }

    public void onTick() {
        if (module.client().player == null || module.client().world == null || module.client().interactionManager == null) return;

        tickSwapBack();
        boolean hasSpear = ensureSpearInMainHand();
        tickUseKey(hasSpear);
        tickWindupTimer(hasSpear);
        if (runtime.switchDelayTicks > 0) runtime.switchDelayTicks--;

        if (!hasSpear) {
            runtime.clearTargetAndWindup();
            debugLogger.logSkip("NoSpear", "main-hand=" + describeMainHandItem(), null, null, runtime);
            return;
        }

        long now = System.currentTimeMillis();
        if (onlyWhileElytra.get() && !module.client().player.isGliding()) {
            if (runtime.hitConfirmPending) {
                LivingEntity confirmTarget = runtime.target;
                if (confirmTarget != null && confirmTarget.getId() == runtime.hitConfirmTargetId) {
                    updateHitConfirmation(now, confirmTarget);
                }
                long confirmAgeMs = Math.max(0L, now - runtime.hitConfirmStartMs);
                if (runtime.hitConfirmPending && confirmAgeMs > 260L) {
                    runtime.clearHitConfirm();
                    clearPacketConfirm();
                    runtime.onReject("HitUnconfirmed");
                    runtime.beginReset(now, 220L);
                    runtime.nextAttemptAtMs = Math.max(runtime.nextAttemptAtMs, now + 80L);
                    debugLogger.logSkip("HitUnconfirmed", "not-gliding-confirm-abort ageMs=" + confirmAgeMs, confirmTarget, null, runtime);
                }
            }
            debugLogger.logSkip("NotGliding", "only-while-elytra=true", runtime.target, null, runtime);
            return;
        }

        LivingEntity previousTarget = runtime.target;
        if (runtime.hitConfirmPending && previousTarget != null && isSoftTrackableDuringConfirm(previousTarget)) {
            runtime.target = previousTarget;
        } else {
            runtime.target = targeting.resolve(runtime);
        }
        if (runtime.target == null) {
            debugLogger.logSkip("NoTarget", "target-resolve-null", null, null, runtime);
            return;
        }
        if (runtime.target != previousTarget) {
            onTargetChanged(now, runtime.target);
            debugLogger.logSkip("TargetChanged", "keep-rmb-charge", runtime.target, null, runtime);
        }

        if (updateHitConfirmation(now, runtime.target)) return;

        float tickGap = TickRate.INSTANCE.getTimeSinceLastTick();
        if (tickGap >= LAG_PAUSE_THRESHOLD && tickGap <= LAG_PAUSE_MAX_VALID) {
            debugLogger.logSkip("LagPause", "tick-gap=" + tickGap, runtime.target, null, runtime);
            return;
        }
        if (runtime.switchDelayTicks > 0) {
            debugLogger.logSkip("SwitchDelay", "switchDelayTicks=" + runtime.switchDelayTicks, runtime.target, null, runtime);
            return;
        }

        if (!attributeSwap.get() && !runtime.pendingRmbRecharge && shouldForceRechargeByStage()) {
            runtime.pendingRmbRecharge = true;
        }

        if (!attributeSwap.get() && runtime.pendingRmbRecharge && runtime.rmbRechargeReleaseTicks <= 0) {
            double distance = module.client().player.getEntityPos().distanceTo(runtime.target.getEntityPos());
            double releaseRange = effectiveMaxRange(null, runtime.target);
            long heldMs = runtime.holdMs(now);
            long fullWindow = fullChargeWindowMs();
            boolean releaseWindow = runtime.passPhase == SpearSpoofRuntime.PassPhase.RESET
                || distance >= Math.max(1.0, releaseRange - 0.10)
                || heldMs >= fullWindow + 120L;
            if (releaseWindow) {
                runtime.rmbRechargeReleaseTicks = Math.max(1, RMB_RECHARGE_RELEASE_TICKS);
                runtime.pendingRmbRecharge = false;
                runtime.hitChain = 0;
                runtime.rechargeRebuildUntilMs = Math.max(runtime.rechargeRebuildUntilMs, now + RECHARGE_REBUILD_MS);
                runtime.beginReset(now, RECHARGE_RESET_HOLD_MS);
                runtime.repositionUntilMs = Math.max(runtime.repositionUntilMs, now + RECHARGE_RESET_HOLD_MS);
                runtime.nextAttemptAtMs = Math.max(runtime.nextAttemptAtMs, runtime.repositionUntilMs);
            }
        }

        SpearSpoofCombatTypes.AttackContext tickContext = buildContext(runtime.target);
        if (tickContext == null) {
            runtime.target = null;
            debugLogger.logSkip("NoContext", "buildContext-null", null, null, runtime);
            return;
        }
        applyTrackingRotation(runtime.target);

        if (!isInsideAttemptWindow(tickContext)) {
            double minRange = effectiveMinRange(tickContext, runtime.target);
            double maxRange = effectiveMaxRange(tickContext, runtime.target);
            debugLogger.logSkip(
                "AttemptWindow",
                "dist=" + f2(tickContext.distance) + " range=[" + f2(minRange) + ".." + f2(maxRange) + "]",
                runtime.target,
                tickContext,
                runtime
            );
            return;
        }
        if (!canAttemptInCurrentPhase(tickContext, now)) {
            debugLogger.logSkip("PhaseGate", buildPhaseGateDetail(tickContext, now), runtime.target, tickContext, runtime);
            return;
        }

        if (autoRestartWindup.get() && tickContext.stage == SpearSpoofCombatTypes.RunStage.RECOVERY) {
            triggerWindupRestart(now);
            runtime.nextAttemptAtMs = Math.max(runtime.nextAttemptAtMs, System.currentTimeMillis() + 45L);
            debugLogger.logSkip("RecoveryRestart", "stage=RECOVERY restart-windup", runtime.target, tickContext, runtime);
            return;
        }

        long strikeDelayMs = strikeReadyDelayMs(runtime.target, tickContext, now);
        if (strikeDelayMs > 0L) {
            runtime.nextAttemptAtMs = Math.max(runtime.nextAttemptAtMs, now + strikeDelayMs);
            debugLogger.logSkip("StrikeDelay", "delayMs=" + strikeDelayMs, runtime.target, tickContext, runtime);
            return;
        }

        if (now < runtime.nextAttemptAtMs) {
            debugLogger.logSkip("NextAttemptGate", "waitMs=" + (runtime.nextAttemptAtMs - now), runtime.target, tickContext, runtime);
            return;
        }

        runtime.nextAttemptAtMs = now + STRIKE_INTERVAL_MS;
        LivingEntity strikeTarget = runtime.target;

        if (rotate.get()) {
            Rotations.rotate(tickContext.yaw, tickContext.pitch, ROTATE_PRIORITY, () -> {
                if (yawCamera.get()) applyCameraLook(tickContext.yaw, tickContext.pitch);
                tryStrike(strikeTarget);
            });
        } else {
            tryStrike(strikeTarget);
        }
    }

    public void onPacketReceive(Object packet) {
        if (packet == null || !runtime.hitConfirmPending) return;
        if (!couldBeDamagePacket(packet)) return;
        Integer packetEntityId = extractEntityId(packet);
        if (packetEntityId == null || packetEntityId != runtime.hitConfirmTargetId) return;
        if (!isDamageConfirmationPacket(packet)) return;

        packetConfirmAtMs = System.currentTimeMillis();
        packetConfirmTargetId = packetEntityId;
        packetConfirmType = packet.getClass().getSimpleName();
    }

    public Vec3d predictedAnchorPos(LivingEntity entity) {
        if (module.client().player == null || entity == null) return Vec3d.ZERO;

        Vec3d predictedCenter = entity.getEntityPos();
        boolean smallTarget = isSmallTarget(entity);
        double y = smallTarget
            ? entity.getY() + Math.max(0.28, entity.getHeight() * 0.52)
            : entity.getY() + Math.max(0.62, entity.getHeight() * 0.72);
        return new Vec3d(predictedCenter.x, y, predictedCenter.z);
    }

    public SpearSpoofCombatTypes.RunStage currentStage() {
        long holdMs = runtime.holdMs(System.currentTimeMillis());
        return SpearSpoofCombatTypes.RunStage.fromHold(holdMs, minWindupMs.get(), readyWindowMs.get(), fatigueWindowMs.get());
    }

    public long strikeReadyDelayMs(LivingEntity target) {
        if (module.client().player == null) return Long.MAX_VALUE;
        if (target == null || !targeting.isValid(target)) return Long.MAX_VALUE;
        if (onlyWhileElytra.get() && !module.client().player.isGliding()) return Long.MAX_VALUE;

        SpearSpoofCombatTypes.AttackContext ctx = buildContext(target);
        if (ctx == null) return Long.MAX_VALUE;
        return strikeReadyDelayMs(target, ctx, System.currentTimeMillis());
    }

}
