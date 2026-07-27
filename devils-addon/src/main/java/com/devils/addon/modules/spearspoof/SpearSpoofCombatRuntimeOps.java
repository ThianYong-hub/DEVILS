package com.devils.addon.modules.spearspoof;

import com.devils.addon.modules.SpearSpoof;
import meteordevelopment.meteorclient.mixininterface.ICamera;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.AttackRangeComponent;
import net.minecraft.component.type.KineticWeaponComponent;
import net.minecraft.component.type.PiercingWeaponComponent;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.PhantomEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.Locale;

abstract class SpearSpoofCombatRuntimeOps extends SpearSpoofCombatContext {
    protected SpearSpoofCombatRuntimeOps(
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

    protected void applyTrackingRotation(LivingEntity target) {
        if (target == null || !rotate.get()) return;

        SpearSpoofCombatTypes.AttackContext ctx = buildContext(target);
        if (ctx == null) return;
        double yaw = ctx.yaw;
        double pitch = ctx.pitch;

        if (yawCamera.get()) applyCameraLook(yaw, pitch);
        Rotations.rotate(yaw, pitch, ROTATE_PRIORITY - 4);
    }

    protected int findBestSpearSlot() {
        if (module.client().player == null) return -1;

        double targetDistance = Double.POSITIVE_INFINITY;
        if (runtime.target != null && targeting.isValid(runtime.target)) {
            targetDistance = module.client().player.getEntityPos().distanceTo(runtime.target.getEntityPos());
        }

        boolean preferNoLunge = targetDistance <= maxRange.get() + 0.30;
        int fallback = -1;
        int secondary = -1;

        for (int i = 0; i < 9; i++) {
            ItemStack stack = module.client().player.getInventory().getStack(i);
            if (!isSpear(stack)) continue;
            if (fallback == -1) fallback = i;

            boolean hasLunge = Utils.getEnchantmentLevel(stack, Enchantments.LUNGE) > 0;
            if (preferNoLunge && !hasLunge) return i;
            if (!preferNoLunge && hasLunge) return i;
            if (secondary == -1) secondary = i;
        }

        return secondary != -1 ? secondary : fallback;
    }

    protected void tickUseKey(boolean hasSpear) {
        boolean shouldHold = autoHoldUse.get() && hasSpear && (!onlyWhileElytra.get() || module.client().player.isGliding());
        if (shouldHold && !module.client().options.useKey.isPressed()) {
            module.client().options.useKey.setPressed(true);
            runtime.useKeyInjected = true;
        }

        if (shouldHold && hasSpear && !module.client().player.isUsingItem()) {
            long now = System.currentTimeMillis();
            if (now - runtime.lastForcedUseInteractMs >= FORCED_USE_INTERACT_RETRY_MS) {
                module.client().interactionManager.interactItem(module.client().player, Hand.MAIN_HAND);
                runtime.lastForcedUseInteractMs = now;
            }
        }

        if (!shouldHold && runtime.useKeyInjected) {
            releaseUseKey();
        }
    }

    protected void releaseUseKey() {
        if (runtime.useKeyInjected) module.client().options.useKey.setPressed(false);
        runtime.useKeyInjected = false;
        runtime.lastForcedUseInteractMs = 0L;
    }

    protected void tickWindupTimer(boolean hasSpear) {
        if (hasSpear && module.client().player.isUsingItem()) {
            if (runtime.useStartedAtMs == 0L) runtime.useStartedAtMs = System.currentTimeMillis();
        } else {
            runtime.useStartedAtMs = 0L;
        }
    }

    /** KINETIC_WEAPON off the main-hand stack, or null for items that do not carry one. */
    protected KineticWeaponComponent kineticWeapon() {
        if (module.client().player == null) return null;
        return module.client().player.getMainHandStack().get(DataComponentTypes.KINETIC_WEAPON);
    }

    /** ATTACK_RANGE the server will use for us, straight off the held stack. */
    protected AttackRangeComponent attackRange() {
        if (module.client().player == null) return null;
        return module.client().player.getAttackRange();
    }

    /**
     * How long RMB must be held before the charge is armed. This is
     * {@code KineticWeaponComponent.delayTicks}, not an invented constant: usageTick does nothing at
     * all until {@code useTicks >= delayTicks}.
     */
    protected long spearWindupMs() {
        KineticWeaponComponent kinetic = kineticWeapon();
        if (kinetic == null) return FALLBACK_WINDUP_MS;
        return kinetic.delayTicks() * 50L;
    }

    /**
     * The server's kinetic relative speed: {@code h = max(0, look.dot(amplifiedSelf) - look.dot(amplifiedTarget))}
     * where {@code amplified = getKineticAttackMovement() * 20} (KineticWeaponComponent.usageTick).
     */
    protected double kineticRelativeSpeedBps(LivingEntity target) {
        if (module.client().player == null) return 0.0;
        Vec3d look = module.client().player.getRotationVector();
        double self = look.dotProduct(KineticWeaponComponent.getAmplifiedMovement(module.client().player));
        double other = target == null ? 0.0 : look.dotProduct(KineticWeaponComponent.getAmplifiedMovement(target));
        return Math.max(0.0, self - other);
    }

    /** Extra reach ProjectileUtil grants the piercing ray: {@code max(0, movement.dot(look))}. */
    protected double lungeReachBonus() {
        if (module.client().player == null) return 0.0;
        return Math.max(0.0, module.client().player.getMovement().dotProduct(module.client().player.getRotationVector()));
    }

    protected SpearSpoofCombatTypes.AttackContext buildContext(LivingEntity entity) {
        if (module.client().player == null) return null;

        Vec3d playerPos = module.client().player.getEntityPos();
        Vec3d playerVel = module.client().player.getVelocity();
        Vec3d targetPos = entity.getEntityPos();
        Vec3d targetVel = entity.getVelocity();

        // No lead. A remote entity's getVelocity() is a latched knockback vector with no decay, and the
        // only ping source available here is a 15 s / 4-sample keep-alive EMA, so any lead built from
        // them is noise that would also contaminate the range gate.
        Box targetBox = entity.getBoundingBox();
        double width = Math.max(targetBox.getLengthX(), targetBox.getLengthZ());
        double height = targetBox.getLengthY();
        boolean smallTarget = entity instanceof PhantomEntity || (width <= 0.90 && height <= 1.10);

        Vec3d eyePos = module.client().player.getEyePos();
        boolean mode4xStableAim = mode4x.get();
        Vec3d aimPos;
        if (smallTarget || mode4xStableAim) {
            Vec3d center = targetBox.getCenter();
            double minAimY = targetBox.minY + 0.05;
            double maxAimY = targetBox.maxY - 0.05;
            double bodyFactor = smallTarget ? 0.58 : 0.62;
            double bodyAimY = targetBox.minY + height * bodyFactor;
            double aimY = MathHelper.clamp(bodyAimY, minAimY, maxAimY);
            aimPos = new Vec3d(center.x, aimY, center.z);
        } else {
            if (targetBox.maxY + 0.20 < module.client().player.getY()) {
                double x = MathHelper.clamp(eyePos.x, targetBox.minX, targetBox.maxX);
                double z = MathHelper.clamp(eyePos.z, targetBox.minZ, targetBox.maxZ);
                double minAimY = targetBox.minY + 0.05;
                double maxAimY = targetBox.maxY - 0.05;
                double bodyAimY = targetBox.minY + height * 0.72;
                double y = MathHelper.clamp(bodyAimY, minAimY, maxAimY);
                aimPos = new Vec3d(x, y, z);
            } else {
                aimPos = closestPoint(targetBox.expand(0.08), eyePos);
            }
        }

        float yaw = (float) Rotations.getYaw(aimPos);
        float pitch = (float) Rotations.getPitch(aimPos);

        // Eye to nearest hitbox point, the exact quantity AttackRangeComponent.isWithinRange measures
        // via Box.squaredMagnitude(getEyePos()).
        Vec3d playerClosest = closestPoint(targetBox, eyePos);
        double distance = eyePos.distanceTo(playerClosest);
        double verticalDiff = Math.abs(eyePos.y - playerClosest.y);

        double speedBps = kineticRelativeSpeedBps(entity);
        Vec3d horizontalVelocity = horizontal(playerVel);
        Vec3d toTarget = horizontal(aimPos.subtract(playerPos));

        double forwardDot = -1.0;
        if (horizontalVelocity.lengthSquared() > 1.0E-6 && toTarget.lengthSquared() > 1.0E-6) {
            forwardDot = horizontalVelocity.normalize().dotProduct(toTarget.normalize());
        }

        double lookDot = -1.0;
        Vec3d lookHorizontal = horizontal(module.client().player.getRotationVector());
        if (lookHorizontal.lengthSquared() > 1.0E-6 && toTarget.lengthSquared() > 1.0E-6) {
            lookDot = lookHorizontal.normalize().dotProduct(toTarget.normalize());
        }

        double closingSpeedBps = 0.0;
        if (toTarget.lengthSquared() > 1.0E-6) {
            Vec3d rel = new Vec3d(playerVel.x - targetVel.x, 0.0, playerVel.z - targetVel.z);
            closingSpeedBps = rel.dotProduct(toTarget.normalize()) * 20.0;
        }

        // 0.5f is the base time PlayerEntity.attack itself uses and matches the server's baseTime of 5.
        double cooldown = module.client().player.getAttackCooldownProgress(0.5f);
        long holdMs = runtime.holdMs(System.currentTimeMillis());
        SpearSpoofCombatTypes.RunStage stage = SpearSpoofCombatTypes.RunStage.fromHold(holdMs, spearWindupMs());

        return new SpearSpoofCombatTypes.AttackContext(
            playerPos,
            playerVel,
            targetPos,
            targetVel,
            aimPos, yaw, pitch, distance, verticalDiff,
            speedBps,
            forwardDot,
            lookDot,
            closingSpeedBps,
            cooldown,
            holdMs,
            smallTarget,
            width,
            height,
            stage
        );
    }

    protected void tryStrike(LivingEntity strikeTarget) {
        if (module.client().player == null || module.client().interactionManager == null) return;
        if (strikeTarget == null || !targeting.isValid(strikeTarget)) return;

        SpearSpoofCombatTypes.AttackContext ctx = buildContext(strikeTarget);
        if (ctx == null) return;
        long now = System.currentTimeMillis();
        if (!canAttemptInCurrentPhase(ctx, now)) {
            debugLogger.logSkip("PhaseGateTry", buildPhaseGateDetail(ctx, now), strikeTarget, ctx, runtime);
            return;
        }

        SpearSpoofCombatTypes.Decision decision = evaluateStrike(strikeTarget, ctx);
        if (!decision.allowed) {
            runtime.onReject(decision.reason);
            debugLogger.logReject(decision.reason, decision.detail, decision.stage, strikeTarget, ctx, runtime);
            return;
        }

        sendStrike(strikeTarget, ctx);

        double dash = module.dashDistance.get();
        if (dash > 0.0) {
            Vec3d direction = module.client().player.getRotationVector();
            if (module.stayGrounded.get()) direction = new Vec3d(direction.x, 0.0, direction.z);
            if (direction.lengthSquared() > 1.0E-6) {
                runtime.dashDirection = direction.normalize();
                runtime.dashRemaining = dash;
            }
        }

        // Marks contact for the flight controller whether or not a packet went out: under a live
        // kinetic charge the correct outbound traffic is none at all.
        runtime.onStrikeSent(System.currentTimeMillis(), strikeTarget);
        lockApproachDirection(strikeTarget);
    }

    /**
     * Mirrors MinecraftClient.doAttack. A spear carries PIERCING_WEAPON, and vanilla never routes it
     * through attackEntity: it sends PlayerActionC2SPacket(Action.STAB), which is the only client packet
     * that reaches PiercingWeaponComponent.stab. While the item is in use there is no legal attack at
     * all - vanilla swallows the attack key - and the damage is produced server-side by
     * KineticWeaponComponent.usageTick every tick, so the correct action is to send nothing.
     *
     * @return true when a packet actually went out.
     */
    protected boolean sendStrike(LivingEntity strikeTarget, SpearSpoofCombatTypes.AttackContext ctx) {
        ItemStack held = module.client().player.getMainHandStack();
        PiercingWeaponComponent piercing = held.get(DataComponentTypes.PIERCING_WEAPON);

        if (piercing != null) {
            if (module.client().player.isUsingItem()) {
                debugLogger.logSkip("ChargeHeld", "kinetic-charge-active no-packet-needed", strikeTarget, ctx, runtime);
                return false;
            }
            if (module.client().interactionManager.isFlyingLocked()) {
                debugLogger.logSkip("FlyingLocked", "interaction-manager-locked", strikeTarget, ctx, runtime);
                return false;
            }
            module.client().interactionManager.attackWithPiercingWeapon(piercing);
            module.client().player.swingHand(Hand.MAIN_HAND);
            debugLogger.logSkip("StrikeSent", "stab", strikeTarget, ctx, runtime);
            return true;
        }

        module.client().interactionManager.attackEntity(module.client().player, strikeTarget);
        module.client().player.swingHand(Hand.MAIN_HAND);
        debugLogger.logSkip("StrikeSent", "attack-entity", strikeTarget, ctx, runtime);
        return true;
    }

    /**
     * Vanilla's own client-side charge predicate (MinecraftClient.doAttack), which is also what the
     * server re-checks with a 5 tick leniency. Items without MINIMUM_ATTACK_CHARGE always return false
     * there, so fall back to Meteor KillAura's full-cooldown test for them.
     */
    protected boolean isAttackChargeReady(SpearSpoofCombatTypes.AttackContext ctx) {
        if (module.client().player == null) return false;
        ItemStack held = module.client().player.getMainHandStack();
        if (held.get(DataComponentTypes.MINIMUM_ATTACK_CHARGE) != null) {
            return !module.client().player.isBelowMinimumAttackCharge(held, 0);
        }
        return ctx == null || ctx.cooldown >= 1.0;
    }

    protected abstract boolean canAttemptInCurrentPhase(SpearSpoofCombatTypes.AttackContext ctx, long now);
    protected abstract String buildPhaseGateDetail(SpearSpoofCombatTypes.AttackContext ctx, long now);
    protected abstract SpearSpoofCombatTypes.Decision evaluateStrike(LivingEntity strikeTarget, SpearSpoofCombatTypes.AttackContext ctx);

    protected double requiredLookDot(SpearSpoofCombatTypes.AttackContext ctx) {
        if (ctx == null) return 0.75;
        if (ctx.smallTarget) return 0.70;
        return 0.78;
    }

    protected String f2(double value) {
        return String.format(Locale.US, "%.2f", value);
    }

    protected void applyCameraLook(double yaw, double pitch) {
        if (module.client() == null || module.client().player == null) return;

        float yawF = (float) yaw;
        float pitchF = MathHelper.clamp((float) pitch, -90.0f, 90.0f);
        module.client().player.setYaw(yawF);
        module.client().player.setPitch(pitchF);
        Rotations.setCamRotation(yawF, pitchF);

        if (module.client().gameRenderer != null && module.client().gameRenderer.getCamera() instanceof ICamera camera) {
            camera.meteor$setRot(yawF, pitchF);
        }
    }

    protected static Vec3d horizontal(Vec3d value) {
        return new Vec3d(value.x, 0.0, value.z);
    }

    protected static Vec3d normalizeOrFallback(Vec3d vector, Vec3d fallback) {
        if (vector != null && vector.lengthSquared() > 1.0E-6) return vector.normalize();
        if (fallback != null && fallback.lengthSquared() > 1.0E-6) return fallback.normalize();
        return new Vec3d(1.0, 0.0, 0.0);
    }

    protected Vec3d closestPoint(Box box, Vec3d from) {
        return new Vec3d(
            MathHelper.clamp(from.x, box.minX, box.maxX),
            MathHelper.clamp(from.y, box.minY, box.maxY),
            MathHelper.clamp(from.z, box.minZ, box.maxZ)
        );
    }

}
