package com.devils.addon.modules.spearspoof;

import com.devils.addon.modules.SpearSpoof;
import meteordevelopment.meteorclient.settings.Setting;
import net.minecraft.component.type.AttackRangeComponent;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.PhantomEntity;
import net.minecraft.entity.player.PlayerEntity;
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
        Setting<Boolean> rotate,
        Setting<Boolean> yawCamera,
        Setting<Boolean> mode4x,
        Setting<Double> maxVerticalDelta,
        Setting<Double> minSpeedBps,
        Setting<Double> minForwardDot,
        Setting<Double> minClosingSpeedBps,
        Setting<Double> minRange,
        Setting<Double> maxRange,
        Setting<Boolean> requireLineOfSight
    ) {
        super(
            module,
            runtime,
            targeting,
            debugLogger,
            onlyWhileElytra,
            autoSwitch,
            autoHoldUse,
            rotate,
            yawCamera,
            mode4x,
            maxVerticalDelta,
            minSpeedBps,
            minForwardDot,
            minClosingSpeedBps,
            minRange,
            maxRange,
            requireLineOfSight
        );
    }

    protected SpearSpoofCombatTypes.Decision evaluateStrike(LivingEntity strikeTarget, SpearSpoofCombatTypes.AttackContext ctx) {
        long now = System.currentTimeMillis();
        boolean pitVerticalLock = isPitVerticalLockActive(strikeTarget, now);
        if (runtime.repositionUntilMs > now) {
            return SpearSpoofCombatTypes.Decision.reject("RepositionLock", "remainMs=" + (runtime.repositionUntilMs - now), ctx.stage);
        }

        if (ctx.stage == SpearSpoofCombatTypes.RunStage.WINDUP) {
            return SpearSpoofCombatTypes.Decision.reject("WindupNotReady", "heldMs=" + ctx.holdMs + " need=" + spearWindupMs(), ctx.stage);
        }

        if (hasTargetIFrameSignal(strikeTarget)) {
            return SpearSpoofCombatTypes.Decision.reject("TargetIFrames", "hurt-frame-active", ctx.stage);
        }

        double minAllowedRange = effectiveMinRange(ctx, strikeTarget);
        double maxAllowedRange = effectiveMaxRange(ctx, strikeTarget);
        if (ctx.distance < minAllowedRange || ctx.distance > maxAllowedRange) {
            return SpearSpoofCombatTypes.Decision.reject(
                "Distance",
                "eyeDist=" + f2(ctx.distance) + " range=[" + f2(minAllowedRange) + ".." + f2(maxAllowedRange) + "]",
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
                "relSpeed=" + f2(effectiveSpeedBps) + " rawRel=" + f2(ctx.speedBps) + " need=" + f2(minSpeedRequired),
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

        if (!isAttackChargeReady(ctx)) {
            return SpearSpoofCombatTypes.Decision.reject("Cooldown", "charge=" + f2(ctx.cooldown) + " below-minimum-attack-charge", ctx.stage);
        }

        if (requireLineOfSight.get() && !module.client().player.canSee(strikeTarget)) {
            return SpearSpoofCombatTypes.Decision.reject("NoLineOfSight", "target-not-visible", ctx.stage);
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
        boolean cooldownReady = isAttackChargeReady(ctx);
        boolean holdReady = ctx.holdMs >= spearWindupMs();

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

    /**
     * Eye-to-hitbox floor the spear ray actually starts at: {@code getEffectiveMinRange - hitboxMargin}
     * (1.875 for every spear tier). Anything closer is a guaranteed miss because the ray does not exist
     * there. The user's min-range setting can only raise it, so the combat gate and the flight
     * controller's standoff agree.
     */
    protected double effectiveMinRange(SpearSpoofCombatTypes.AttackContext ctx, LivingEntity target) {
        return stableMinRange();
    }

    /**
     * {@code getEffectiveMaxRange + hitboxMargin}, extended by the lunge bonus ProjectileUtil adds
     * ({@code max(0, movement.dot(look))}). The user's max-range setting narrows the item window but
     * never removes the lunge extension - at elytra speed that is over a block of real reach.
     */
    protected double effectiveMaxRange(SpearSpoofCombatTypes.AttackContext ctx, LivingEntity target) {
        return Math.max(stableMaxRange() + lungeReachBonus(), stableMinRange() + 0.20);
    }

    /** Item window without the per-tick lunge term, so the flight controller has a steady band to hold. */
    protected double stableMinRange() {
        AttackRangeComponent range = attackRange();
        double base = FALLBACK_MIN_RANGE;
        if (range != null && module.client().player != null) {
            base = range.getEffectiveMinRange(module.client().player) - range.hitboxMargin();
        }
        return Math.max(base, minRange.get());
    }

    protected double stableMaxRange() {
        AttackRangeComponent range = attackRange();
        double base = FALLBACK_MAX_RANGE;
        if (range != null && module.client().player != null) {
            base = range.getEffectiveMaxRange(module.client().player) + range.hitboxMargin();
        }
        double capped = Math.min(base, Math.max(maxRange.get(), FALLBACK_MIN_RANGE + 0.20));
        return Math.max(capped, stableMinRange() + 0.20);
    }

    protected double effectiveMinSpeedBps(LivingEntity target) {
        if (target != null && !(target instanceof PlayerEntity)) return minSpeedBps.get() + NON_PLAYER_SPEED_BONUS_BPS;
        return minSpeedBps.get();
    }

    protected long strikeReadyDelayMs(LivingEntity target, SpearSpoofCombatTypes.AttackContext ctx, long now) {
        long delay = 0L;

        if (runtime.repositionUntilMs > now) {
            delay = Math.max(delay, runtime.repositionUntilMs - now);
        }

        if (ctx.stage == SpearSpoofCombatTypes.RunStage.WINDUP) {
            delay = Math.max(delay, Math.max(0L, spearWindupMs() - ctx.holdMs));
        }

        if (!isAttackChargeReady(ctx)) {
            delay = Math.max(delay, 50L);
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

    /**
     * Only hurtTime. timeUntilRegen is set to 20 on the client after damage from any source including
     * third parties, while the spear's own per-entity contact cooldown is 10 ticks - blocking on it
     * stalled the module for a full second for damage it did not deal.
     */
    protected boolean hasTargetIFrameSignal(LivingEntity target) {
        if (target == null) return false;
        return target.hurtTime > 0;
    }

}
