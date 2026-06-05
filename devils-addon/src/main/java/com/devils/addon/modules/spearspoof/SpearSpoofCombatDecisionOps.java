package com.devils.addon.modules.spearspoof;

import com.devils.addon.modules.SpearSpoof;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.utils.player.Rotations;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.PhantomEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;



abstract class SpearSpoofCombatDecisionOps extends SpearSpoofCombatRuntimeOps {
    protected SpearSpoofCombatDecisionOps(
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

    protected boolean updateHitConfirmation(long now, LivingEntity currentTarget) {
        if (!runtime.hitConfirmPending) return false;

        if (currentTarget == null || currentTarget.getId() != runtime.hitConfirmTargetId || currentTarget.isRemoved() || !currentTarget.isAlive()) {
            runtime.clearHitConfirm();
            clearPacketConfirm();
            runtime.onReject("HitUnconfirmed");
            runtime.beginReset(now, 240L);
            debugLogger.logSkip("HitUnconfirmed", "target-invalid-before-confirm", currentTarget, null, runtime);
            return true;
        }

        float healthNow = currentTarget.getHealth();
        float absorbNow = currentTarget.getAbsorptionAmount();
        boolean damaged = (healthNow + absorbNow) < (runtime.hitConfirmBaseHealth + runtime.hitConfirmBaseAbsorption) - 0.01f;
        boolean dead = currentTarget.isDead() || healthNow <= 0.0f;
        boolean packetConfirmed = packetConfirmTargetId == runtime.hitConfirmTargetId
            && packetConfirmAtMs >= runtime.hitConfirmStartMs;
        boolean hurtAnimation = hasServerDamageSignalOnEntity(currentTarget, now);

        if (damaged || dead || packetConfirmed || hurtAnimation) {
            SpearSpoofCombatTypes.AttackContext confirmCtx = buildContext(currentTarget);
            runtime.onHit(now);
            runtime.nextAttemptAtMs = Math.max(runtime.nextAttemptAtMs, now + recoveryDelayMs.get());
            lockApproachDirection(currentTarget);
            debugLogger.logHit(currentTarget, confirmCtx, runtime);
            clearPacketConfirm();
            return false;
        }

        if (now <= runtime.hitConfirmUntilMs) {
            maybeRetryStrikeDuringConfirm(now, currentTarget);
            runtime.nextAttemptAtMs = Math.max(runtime.nextAttemptAtMs, now + 25L);
            return true;
        }

        runtime.clearHitConfirm();
        clearPacketConfirm();
        runtime.onReject("HitUnconfirmed");
        runtime.beginReset(now, 300L);
        runtime.repositionUntilMs = Math.max(runtime.repositionUntilMs, now + 180L);
        runtime.nextAttemptAtMs = Math.max(runtime.nextAttemptAtMs, now + 95L);
        debugLogger.logSkip("HitUnconfirmed", "confirm-timeout", currentTarget, null, runtime);
        return true;
    }

    protected void maybeRetryStrikeDuringConfirm(long now, LivingEntity currentTarget) {
        if (runtime.hitConfirmRetryCount >= 3) return;
        if (module.client().player == null || module.client().interactionManager == null) return;

        long ageMs = now - runtime.hitConfirmStartMs;
        long confirmWindowMs = Math.max(120L, runtime.hitConfirmUntilMs - runtime.hitConfirmStartMs);
        long threshold;
        if (runtime.hitConfirmRetryCount == 0) threshold = (long) (confirmWindowMs * HIT_CONFIRM_RETRY_1_FRACTION);
        else if (runtime.hitConfirmRetryCount == 1) threshold = (long) (confirmWindowMs * HIT_CONFIRM_RETRY_2_FRACTION);
        else threshold = (long) (confirmWindowMs * HIT_CONFIRM_RETRY_3_FRACTION);
        threshold = Math.max(120L, threshold);
        if (ageMs < threshold) return;

        SpearSpoofCombatTypes.AttackContext ctx = buildContext(currentTarget);
        if (ctx == null) return;
        if (hasTargetIFrameSignal(currentTarget)) return;
        double minRange = Math.max(0.05, effectiveMinRange(ctx, currentTarget) - 0.20);
        double maxRange = effectiveMaxRange(ctx, currentTarget) + 0.35;
        boolean inRetryRange = ctx.distance >= minRange && ctx.distance <= maxRange;
        boolean facingOkay = ctx.forwardDot >= -0.20 || ctx.lookDot >= 0.55;
        if (!inRetryRange || !facingOkay) return;

        if (rotate.get()) {
            Rotations.rotate(ctx.yaw, ctx.pitch, ROTATE_PRIORITY, () -> {
                if (module.client().player == null || module.client().interactionManager == null) return;
                module.client().interactionManager.attackEntity(module.client().player, currentTarget);
                module.client().player.swingHand(Hand.MAIN_HAND);
            });
        } else {
            module.client().interactionManager.attackEntity(module.client().player, currentTarget);
            module.client().player.swingHand(Hand.MAIN_HAND);
        }

        runtime.hitConfirmRetryCount++;
        debugLogger.logSkip(
            "StrikeRetry",
            "await-hit-confirm retry=" + runtime.hitConfirmRetryCount + " ageMs=" + ageMs,
            currentTarget,
            ctx,
            runtime
        );
    }

    protected void handleAdaptiveReposition(String reason) {
        if (!adaptiveReposition.get()) return;

        boolean speedLocked = "LowSpeed".equals(reason) && runtime.speedRejectStreak >= repositionRejectStreak.get();
        boolean forwardLocked = "BadForward".equals(reason) && runtime.forwardRejectStreak >= repositionRejectStreak.get();
        if (!speedLocked && !forwardLocked) return;

        long now = System.currentTimeMillis();
        runtime.repositionUntilMs = now + repositionHoldMs.get();
        runtime.nextAttemptAtMs = runtime.repositionUntilMs;
        runtime.beginReset(now, repositionHoldMs.get());
    }

    protected SpearSpoofCombatTypes.Decision evaluateStrike(LivingEntity strikeTarget, SpearSpoofCombatTypes.AttackContext ctx) {
        long now = System.currentTimeMillis();
        boolean pitVerticalLock = isPitVerticalLockActive(strikeTarget, now);
        if (runtime.repositionUntilMs > now) {
            return SpearSpoofCombatTypes.Decision.reject("RepositionLock", "remainMs=" + (runtime.repositionUntilMs - now), ctx.stage);
        }

        if (ctx.stage == SpearSpoofCombatTypes.RunStage.WINDUP) {
            return SpearSpoofCombatTypes.Decision.reject("WindupNotReady", "heldMs=" + ctx.holdMs + " need=" + minWindupMs.get(), ctx.stage);
        }

        if (ctx.stage == SpearSpoofCombatTypes.RunStage.RECOVERY) {
            return SpearSpoofCombatTypes.Decision.reject("Recovery", "heldMs=" + ctx.holdMs + " stage=RECOVERY", ctx.stage);
        }

        if (hasTargetIFrameSignal(strikeTarget)) {
            return SpearSpoofCombatTypes.Decision.reject("TargetIFrames", "hurt/recovery-frame-active", ctx.stage);
        }

        double minAllowedRange = effectiveMinRange(ctx, strikeTarget);
        double maxAllowedRange = effectiveMaxRange(ctx, strikeTarget);
        if (ctx.distance < minAllowedRange || ctx.distance > maxAllowedRange) {
            return SpearSpoofCombatTypes.Decision.reject(
                "Distance",
                "dist=" + f2(ctx.distance) + " range=[" + f2(minAllowedRange) + ".." + f2(maxAllowedRange) + "]",
                ctx.stage
            );
        }

        double maxVerticalAllowed = pitVerticalLock ? Math.max(maxVerticalDelta.get(), 7.5) : maxVerticalDelta.get();
        if (ctx.verticalDiff > maxVerticalAllowed) {
            return SpearSpoofCombatTypes.Decision.reject(
                "VerticalDelta",
                "vertical=" + f2(ctx.verticalDiff) + " max=" + f2(maxVerticalAllowed),
                ctx.stage
            );
        }

        double minSpeedRequired = effectiveMinSpeedBps(strikeTarget);
        double effectiveSpeedBps = ctx.speedBps;
        if (pitVerticalLock) {
            Vec3d rel3d = ctx.playerVel.subtract(ctx.targetVel);
            effectiveSpeedBps = Math.max(effectiveSpeedBps, rel3d.length() * 20.0);
        }
        if (effectiveSpeedBps < minSpeedRequired) {
            return SpearSpoofCombatTypes.Decision.reject(
                "LowSpeed",
                "speed=" + f2(effectiveSpeedBps) + " rawH=" + f2(ctx.speedBps) + " need=" + f2(minSpeedRequired),
                ctx.stage
            );
        }

        double requiredForward = effectiveMinForwardDot(ctx);
        if (ctx.forwardDot < requiredForward) {
            boolean fallbackLookReady = ctx.lookDot >= requiredLookDot(ctx);
            boolean fallbackClosingReady = ctx.closingSpeedBps >= effectiveMinClosingBps(ctx);
            if (!(fallbackLookReady && fallbackClosingReady)) {
                return SpearSpoofCombatTypes.Decision.reject(
                    "BadForward",
                    "dot=" + f2(ctx.forwardDot)
                        + " need=" + f2(requiredForward)
                        + " look=" + f2(ctx.lookDot)
                        + " needLook=" + f2(requiredLookDot(ctx))
                        + " closing=" + f2(ctx.closingSpeedBps)
                        + " needClosing=" + f2(effectiveMinClosingBps(ctx)),
                    ctx.stage
                );
            }
        }

        double minClosing = effectiveMinClosingBps(ctx);
        if (ctx.closingSpeedBps < minClosing) {
            return SpearSpoofCombatTypes.Decision.reject(
                "LowClosingSpeed",
                "closing=" + f2(ctx.closingSpeedBps) + " need=" + f2(minClosing),
                ctx.stage
            );
        }

        if (ctx.cooldown < minCooldown.get()) {
            return SpearSpoofCombatTypes.Decision.reject("Cooldown", "cooldown=" + f2(ctx.cooldown) + " need=" + f2(minCooldown.get()), ctx.stage);
        }

        if (rotate.get()) {
            if (ctx.yawError > maxYawError.get()) {
                return SpearSpoofCombatTypes.Decision.reject("YawError", "yawErr=" + f2(ctx.yawError) + " max=" + f2(maxYawError.get()), ctx.stage);
            }
            if (ctx.pitchError > maxPitchError.get()) {
                return SpearSpoofCombatTypes.Decision.reject("PitchError", "pitchErr=" + f2(ctx.pitchError) + " max=" + f2(maxPitchError.get()), ctx.stage);
            }
        }

        if (requireLineOfSight.get() && !module.client().player.canSee(strikeTarget)) {
            return SpearSpoofCombatTypes.Decision.reject("NoLineOfSight", "target-not-visible", ctx.stage);
        }
        if (!isSpearRayHit(strikeTarget, ctx)) {
            return SpearSpoofCombatTypes.Decision.reject(
                "RayMiss",
                "ray-miss small=" + ctx.smallTarget
                    + " hb=" + f2(ctx.targetWidth) + "x" + f2(ctx.targetHeight)
                    + " look=" + f2(ctx.lookDot)
                    + " fwd=" + f2(ctx.forwardDot),
                ctx.stage
            );
        }

        long sinceLast = now - runtime.lastStrikeAtMs;
        if (sinceLast < STRIKE_INTERVAL_MS) {
            return SpearSpoofCombatTypes.Decision.reject("StrikeInterval", "delayMs=" + sinceLast + " need=" + STRIKE_INTERVAL_MS, ctx.stage);
        }

        return SpearSpoofCombatTypes.Decision.allow(ctx.stage);
    }

    protected boolean isInsideAttemptWindow(SpearSpoofCombatTypes.AttackContext ctx) {
        if (ctx == null) return false;
        double minRange = effectiveMinRange(ctx, runtime.target);
        double maxRange = effectiveMaxRange(ctx, runtime.target);
        return ctx.distance >= minRange && ctx.distance <= maxRange;
    }

    protected boolean canAttemptInCurrentPhase(SpearSpoofCombatTypes.AttackContext ctx, long now) {
        if (ctx == null) return false;
        if (attributeSwap.get()) return true;
        if (runtime.passPhase == SpearSpoofRuntime.PassPhase.APPROACH) return true;
        if (runtime.passPhase == SpearSpoofRuntime.PassPhase.RESET && isPitVerticalLockActive(runtime.target, now)) return true;
        return false;
    }

    protected String buildPhaseGateDetail(SpearSpoofCombatTypes.AttackContext ctx, long now) {
        long resetAge = now - runtime.passPhaseStartMs;
        double minRange = effectiveMinRange(ctx, runtime.target);
        double maxRange = effectiveMaxRange(ctx, runtime.target);
        boolean rangeReady = ctx.distance >= minRange && ctx.distance <= maxRange;
        boolean forwardReady = ctx.forwardDot >= effectiveMinForwardDot(ctx)
            || (ctx.lookDot >= requiredLookDot(ctx) && ctx.closingSpeedBps >= effectiveMinClosingBps(ctx));
        boolean cooldownReady = ctx.cooldown >= minCooldown.get();
        boolean holdReady = ctx.holdMs >= minWindupMs.get();

        return "phase=" + runtime.passPhase
            + " resetAge=" + resetAge
            + " resetActive=" + runtime.isResetActive(now)
            + " rangeReady=" + rangeReady
            + " range=[" + f2(minRange) + ".." + f2(maxRange) + "]"
            + " forwardReady=" + forwardReady
            + " forward=" + f2(ctx.forwardDot)
            + " look=" + f2(ctx.lookDot)
            + " closing=" + f2(ctx.closingSpeedBps)
            + " cooldownReady=" + cooldownReady
            + " holdReady=" + holdReady;
    }

    protected double effectiveMinRange(SpearSpoofCombatTypes.AttackContext ctx, LivingEntity target) {
        double base = ((ctx != null && ctx.smallTarget) || isSmallTarget(target))
            ? ENFORCED_SMALL_MIN_RANGE
            : ENFORCED_MIN_RANGE;
        if (uses4xRangeWindow(target)) base = Math.max(base, MODE_4X_MIN_RANGE);
        return base;
    }

    protected double effectiveMaxRange(SpearSpoofCombatTypes.AttackContext ctx, LivingEntity target) {
        if (uses4xRangeWindow(target)) return Math.min(ENFORCED_MAX_RANGE, MODE_4X_MAX_RANGE);
        return ENFORCED_MAX_RANGE;
    }

    protected boolean uses4xRangeWindow(LivingEntity target) {
        if (mode4x.get()) return true;
        if (target == null) return false;
        long now = System.currentTimeMillis();
        return runtime.pitVerticalLockTargetId == target.getId()
            && runtime.pitVerticalLockUntilMs > now;
    }

    protected double effectiveMinSpeedBps(LivingEntity target) {
        if (target != null && !(target instanceof PlayerEntity)) return minSpeedBps.get() + NON_PLAYER_SPEED_BONUS_BPS;
        return minSpeedBps.get();
    }

    protected boolean isSpearRayHit(LivingEntity target, SpearSpoofCombatTypes.AttackContext ctx) {
        if (module.client().player == null || target == null || ctx == null || ctx.aimPos == null) return false;

        Vec3d eyePos = module.client().player.getEyePos();
        Vec3d toAim = ctx.aimPos.subtract(eyePos);
        double toAimLength = toAim.length();
        if (toAimLength < 1.0E-6) return false;

        Vec3d dir = toAim.multiply(1.0 / toAimLength);
        double reach = Math.max(ENFORCED_MAX_RANGE + 0.65, toAimLength + 0.15);
        Vec3d end = eyePos.add(dir.multiply(reach));
        double expansion = ctx.smallTarget ? 0.08 : 0.15;
        if (target.getBoundingBox().expand(expansion).raycast(eyePos, end).isPresent()) return true;

        Vec3d targetCenter = target.getBoundingBox().getCenter();
        Vec3d toCenter = targetCenter.subtract(eyePos);
        double toCenterLen = toCenter.length();
        if (toCenterLen < 1.0E-6) return false;
        Vec3d centerEnd = eyePos.add(toCenter.multiply(1.0 / toCenterLen).multiply(Math.max(ENFORCED_MAX_RANGE + 0.65, toCenterLen + 0.15)));
        return target.getBoundingBox().expand(expansion).raycast(eyePos, centerEnd).isPresent();
    }

    protected long strikeReadyDelayMs(LivingEntity target, SpearSpoofCombatTypes.AttackContext ctx, long now) {
        long delay = 0L;

        if (runtime.repositionUntilMs > now) {
            delay = Math.max(delay, runtime.repositionUntilMs - now);
        }

        if (runtime.rmbRechargeReleaseTicks > 0) {
            delay = Math.max(delay, runtime.rmbRechargeReleaseTicks * 50L);
        }

        if (ctx.stage == SpearSpoofCombatTypes.RunStage.WINDUP) {
            delay = Math.max(delay, Math.max(0L, minWindupMs.get() - ctx.holdMs));
        } else if (ctx.stage == SpearSpoofCombatTypes.RunStage.RECOVERY) {
            delay = Math.max(delay, Math.max(50L, minWindupMs.get() / 2L));
        }

        if (ctx.cooldown < minCooldown.get()) {
            delay = Math.max(delay, 50L);
        }

        long sinceLast = now - runtime.lastStrikeAtMs;
        if (sinceLast < STRIKE_INTERVAL_MS) {
            delay = Math.max(delay, STRIKE_INTERVAL_MS - sinceLast);
        }

        if (runtime.nextAttemptAtMs > now) {
            delay = Math.max(delay, runtime.nextAttemptAtMs - now);
        }

        if (target == null || !targeting.isValid(target)) return Long.MAX_VALUE;
        return delay;
    }

    protected boolean isSmallTarget(LivingEntity entity) {
        if (entity == null) return false;
        Box box = entity.getBoundingBox();
        double width = Math.max(box.getLengthX(), box.getLengthZ());
        double height = box.getLengthY();
        return entity instanceof PhantomEntity || (width <= 0.90 && height <= 1.10);
    }

    protected double effectiveMinForwardDot(SpearSpoofCombatTypes.AttackContext ctx) {
        if (ctx == null) return minForwardDot.get();
        if (isPitVerticalLockActive(runtime.target, System.currentTimeMillis())) return -1.0;
        double base = minForwardDot.get();
        if (ctx.smallTarget) base -= 0.36;
        if (runtime.passPhase == SpearSpoofRuntime.PassPhase.RESET) base = Math.min(base, -0.28);
        return MathHelper.clamp(base, -0.80, 1.0);
    }

    protected double effectiveMinClosingBps(SpearSpoofCombatTypes.AttackContext ctx) {
        if (ctx == null) return minClosingSpeedBps.get();
        if (isPitVerticalLockActive(runtime.target, System.currentTimeMillis())) return -2.0;
        double base = minClosingSpeedBps.get();
        if (ctx.smallTarget) base = Math.min(base, -0.60);
        else if (runtime.passPhase == SpearSpoofRuntime.PassPhase.RESET) base = Math.min(base, -0.25);
        return base;
    }

    protected boolean isPitVerticalLockActive(LivingEntity target, long now) {
        if (target == null) return false;
        return runtime.pitVerticalLockTargetId == target.getId() && now < runtime.pitVerticalLockUntilMs;
    }

    protected long resolveHitConfirmWindowMs() {
        int ping = 0;
        if (module.client().getNetworkHandler() != null
            && module.client().getNetworkHandler().getPlayerListEntry(module.client().player.getUuid()) != null) {
            ping = Math.max(0, module.client().getNetworkHandler().getPlayerListEntry(module.client().player.getUuid()).getLatency());
        }

        long dynamic = (long) (HIT_CONFIRM_WINDOW_MIN_MS + MathHelper.clamp(ping, 0, 450) * HIT_CONFIRM_PING_FACTOR);
        return MathHelper.clamp(dynamic, HIT_CONFIRM_WINDOW_MIN_MS, HIT_CONFIRM_WINDOW_MAX_MS);
    }

    protected boolean hasTargetIFrameSignal(LivingEntity target) {
        if (target == null) return false;
        int hurtTime = readIntMember(target, "hurtTime", "getHurtTime");
        if (hurtTime > 0) return true;
        int regen = readIntMember(target, "timeUntilRegen", "getTimeUntilRegen");
        return regen > 0;
    }

    protected boolean hasServerDamageSignalOnEntity(LivingEntity target, long now) {
        if (target == null) return false;
        int hurtTime = readIntMember(target, "hurtTime", "getHurtTime");
        if (hurtTime > 0 && hurtTime != hitConfirmBaseHurtTime) return true;
        int regen = readIntMember(target, "timeUntilRegen", "getTimeUntilRegen");
        if (regen > 0 && regen != hitConfirmBaseRegenTime) return true;

        // Failsafe: if we are very close to confirm timeout and target is in active hurt/recovery frames,
        // allow one final positive to avoid false timeout on delayed health sync packets.
        long ageMs = now - runtime.hitConfirmStartMs;
        long windowMs = Math.max(1L, runtime.hitConfirmUntilMs - runtime.hitConfirmStartMs);
        boolean nearTimeout = ageMs >= (long) (windowMs * 0.88);
        return nearTimeout && (hurtTime > 0 || regen > 0);
    }

}
