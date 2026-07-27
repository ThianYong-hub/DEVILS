package com.devils.addon.modules.spearspoof;

import com.devils.addon.modules.SpearSpoof;
import meteordevelopment.meteorclient.settings.Setting;
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

    public void onDeactivate() {
        runtime.clearTargetAndWindup();
        releaseUseKey();
    }

    public void onTick() {
        if (module.client().player == null || module.client().world == null || module.client().interactionManager == null) return;

        boolean hasSpear = ensureSpearInMainHand();
        tickUseKey(hasSpear);
        tickWindupTimer(hasSpear);

        if (runtime.dashRemaining > 0.0) {
            double step = Math.min(module.dashSpeed.get(), runtime.dashRemaining);
            Vec3d motion = runtime.dashDirection.multiply(step);
            module.client().player.setVelocity(module.client().player.getVelocity().add(motion));
            if (!module.stayGrounded.get()) module.client().player.setOnGround(false);
            module.client().player.fallDistance = 0.0F;
            runtime.dashRemaining -= step;
        }

        if (runtime.switchDelayTicks > 0) runtime.switchDelayTicks--;

        if (!hasSpear) {
            runtime.clearTargetAndWindup();
            debugLogger.logSkip("NoSpear", "main-hand=" + describeMainHandItem(), null, null, runtime);
            return;
        }

        long now = System.currentTimeMillis();
        if (onlyWhileElytra.get() && !module.client().player.isGliding()) {
            debugLogger.logSkip("NotGliding", "only-while-elytra=true", runtime.target, null, runtime);
            return;
        }

        LivingEntity previousTarget = runtime.target;
        runtime.target = targeting.resolve(runtime);
        if (runtime.target == null) {
            debugLogger.logSkip("NoTarget", "target-resolve-null", null, null, runtime);
            return;
        }
        if (runtime.target != previousTarget) {
            onTargetChanged(now, runtime.target);
            debugLogger.logSkip("TargetChanged", "keep-rmb-charge", runtime.target, null, runtime);
        }

        // Exactly one queued Meteor rotation per tick. A second one is flushed as its own
        // LookAndOnGround after the attack, and the server treats a look-only packet as zero movement,
        // wiping ServerPlayerEntity.movement - the very field the spear's reach bonus and its
        // relative-speed condition are read from.
        if (!runStrikePipeline(now)) applyTrackingRotation(runtime.target);
    }

    /** @return true when this tick queued its own strike rotation (or struck directly). */
    private boolean runStrikePipeline(long now) {
        float tickGap = TickRate.INSTANCE.getTimeSinceLastTick();
        if (tickGap >= LAG_PAUSE_THRESHOLD && tickGap <= LAG_PAUSE_MAX_VALID) {
            debugLogger.logSkip("LagPause", "tick-gap=" + tickGap, runtime.target, null, runtime);
            return false;
        }
        if (runtime.switchDelayTicks > 0) {
            debugLogger.logSkip("SwitchDelay", "switchDelayTicks=" + runtime.switchDelayTicks, runtime.target, null, runtime);
            return false;
        }

        SpearSpoofCombatTypes.AttackContext tickContext = buildContext(runtime.target);
        if (tickContext == null) {
            runtime.target = null;
            debugLogger.logSkip("NoContext", "buildContext-null", null, null, runtime);
            return false;
        }

        if (!isInsideAttemptWindow(tickContext)) {
            double minRange = effectiveMinRange(tickContext, runtime.target);
            double maxRange = effectiveMaxRange(tickContext, runtime.target);
            debugLogger.logSkip(
                "AttemptWindow",
                "eyeDist=" + f2(tickContext.distance) + " range=[" + f2(minRange) + ".." + f2(maxRange) + "]",
                runtime.target,
                tickContext,
                runtime
            );
            return false;
        }
        if (!canAttemptInCurrentPhase(tickContext, now)) {
            debugLogger.logSkip("PhaseGate", buildPhaseGateDetail(tickContext, now), runtime.target, tickContext, runtime);
            return false;
        }

        LivingEntity strikeTarget = runtime.target;
        if (rotate.get()) {
            // Applied here, on TickEvent.Pre, not inside the callback: the callback runs at
            // SendMovementPacketsEvent.Post where Rotations.resetPreRotation undoes it one statement
            // later, and elytra physics need the aim written before ClientPlayerEntity.tick.
            if (yawCamera.get()) applyCameraLook(tickContext.yaw, tickContext.pitch);
            Rotations.rotate(tickContext.yaw, tickContext.pitch, ROTATE_PRIORITY, () -> tryStrike(strikeTarget));
        } else {
            tryStrike(strikeTarget);
        }
        return true;
    }

    /**
     * EntityStatuses.KINETIC_ATTACK is sent for the ATTACKER when a pierce lands
     * (KineticWeaponComponent.usageTick), so it is a free true positive for our own spear hits.
     * Telemetry only - nothing here may gate the next strike.
     */
    public void onKineticHit() {
        if (module.client().player == null) return;

        long now = System.currentTimeMillis();
        LivingEntity target = runtime.target;
        SpearSpoofCombatTypes.AttackContext ctx = target != null ? buildContext(target) : null;
        runtime.onHit(now);
        if (ctx != null) debugLogger.logHit(target, ctx, runtime);
    }

    /**
     * The band the flight controller must hold, shared with the combat gate so the two stop
     * disagreeing. Excludes the per-tick lunge extension on purpose: steering to a target that moves
     * with your own speed would oscillate.
     */
    public double engageMinRange(LivingEntity target) {
        return stableMinRange();
    }

    public double engageMaxRange(LivingEntity target) {
        return stableMaxRange();
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
