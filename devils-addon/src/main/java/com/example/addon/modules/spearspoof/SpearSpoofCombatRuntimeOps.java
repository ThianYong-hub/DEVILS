package com.example.addon.modules.spearspoof;

import com.example.addon.modules.SpearSpoof;
import meteordevelopment.meteorclient.mixininterface.ICamera;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.PhantomEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.s2c.play.EntityStatusS2CPacket;
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

    protected void applyTrackingRotation(LivingEntity target) {
        if (target == null || !rotate.get()) return;
        // Do not overwrite queued strike rotation while we are waiting for hit confirmation.
        if (runtime.hitConfirmPending) return;

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

        boolean preferNoLunge = targetDistance <= ENFORCED_MAX_RANGE + 0.30;
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
        if (runtime.rmbRechargeReleaseTicks > 0) {
            runtime.rmbRechargeReleaseTicks--;
            module.client().options.useKey.setPressed(false);
            runtime.useKeyInjected = false;
            return;
        }

        if (runtime.windupRestartTicks > 0) {
            runtime.windupRestartTicks--;
            module.client().options.useKey.setPressed(false);
            runtime.useKeyInjected = false;
            return;
        }

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

    protected boolean shouldForceRechargeByStage() {
        if (attributeSwap.get()) return false;
        if (runtime.useStartedAtMs <= 0L) return false;
        long heldMs = runtime.holdMs(System.currentTimeMillis());
        long fullWindow = fullChargeWindowMs();
        return heldMs >= fullWindow;
    }

    protected long fullChargeWindowMs() {
        return (long) minWindupMs.get() + readyWindowMs.get() + fatigueWindowMs.get();
    }

    protected void releaseUseKey() {
        if (runtime.useKeyInjected) module.client().options.useKey.setPressed(false);
        runtime.useKeyInjected = false;
        runtime.windupRestartTicks = 0;
        runtime.lastForcedUseInteractMs = 0L;
    }

    protected void tickWindupTimer(boolean hasSpear) {
        if (runtime.windupRestartTicks > 0 || runtime.rmbRechargeReleaseTicks > 0) {
            runtime.useStartedAtMs = 0;
            return;
        }

        if (hasSpear && module.client().player.isUsingItem()) {
            if (runtime.useStartedAtMs == 0L) runtime.useStartedAtMs = System.currentTimeMillis();
        } else {
            runtime.useStartedAtMs = 0L;
        }
    }

    protected void tickSwapBack() {
        if (runtime.swapBackSlot < 0) return;
        if (runtime.swapBackTicks > 0) {
            runtime.swapBackTicks--;
            return;
        }

        InvUtils.swap(runtime.swapBackSlot, false);
        runtime.swapBackSlot = -1;
    }

    protected void scheduleAttributeSwap() {
        if (!attributeSwap.get()) return;
        if (!isSpear(module.client().player.getMainHandStack())) return;

        int spearSlot = module.client().player.getInventory().getSelectedSlot();
        int fallbackSlot = findFallbackSlot(spearSlot);
        if (fallbackSlot == -1) return;

        if (InvUtils.swap(fallbackSlot, false)) {
            runtime.swapBackSlot = spearSlot;
            runtime.swapBackTicks = 1;
        }
    }

    protected int findFallbackSlot(int spearSlot) {
        for (int i = 0; i < 9; i++) {
            if (i == spearSlot) continue;
            ItemStack stack = module.client().player.getInventory().getStack(i);
            if (stack.isEmpty()) continue;
            if (!isSpear(stack)) return i;
        }

        for (int i = 0; i < 9; i++) {
            if (i == spearSlot) continue;
            ItemStack stack = module.client().player.getInventory().getStack(i);
            if (!isSpear(stack)) return i;
        }
        return -1;
    }

    protected SpearSpoofCombatTypes.AttackContext buildContext(LivingEntity entity) {
        if (module.client().player == null) return null;

        Vec3d playerPos = module.client().player.getEntityPos();
        Vec3d playerVel = module.client().player.getVelocity();
        Vec3d targetPos = entity.getEntityPos();
        double leadTicks = 0.0;
        Vec3d targetVel = entity.getVelocity();
        double extraPredictTicks = 0.0;
        double totalPredictTicks = 0.0;
        boolean predictionCollisionAware = false;
        boolean predictionAuto = false;
        Vec3d predictedTargetPos = targetPos;
        Box predictedBox = entity.getBoundingBox();
        double width = Math.max(predictedBox.getLengthX(), predictedBox.getLengthZ());
        double height = predictedBox.getLengthY();
        boolean smallTarget = entity instanceof PhantomEntity || (width <= 0.90 && height <= 1.10);

        Vec3d eyePos = module.client().player.getEyePos();
        boolean mode4xStableAim = mode4x.get();
        Vec3d aimPos;
        if (smallTarget || mode4xStableAim) {
            Vec3d center = predictedBox.getCenter();
            double minAimY = predictedBox.minY + 0.05;
            double maxAimY = predictedBox.maxY - 0.05;
            double bodyFactor = smallTarget ? 0.58 : 0.62;
            double bodyAimY = predictedBox.minY + height * bodyFactor;
            double aimY = MathHelper.clamp(bodyAimY, minAimY, maxAimY);
            aimPos = new Vec3d(center.x, aimY, center.z);
        } else {
            if (predictedBox.maxY + 0.20 < module.client().player.getY()) {
                double x = MathHelper.clamp(eyePos.x, predictedBox.minX, predictedBox.maxX);
                double z = MathHelper.clamp(eyePos.z, predictedBox.minZ, predictedBox.maxZ);
                double minAimY = predictedBox.minY + 0.05;
                double maxAimY = predictedBox.maxY - 0.05;
                double bodyAimY = predictedBox.minY + height * 0.72;
                double y = MathHelper.clamp(bodyAimY, minAimY, maxAimY);
                aimPos = new Vec3d(x, y, z);
            } else {
                aimPos = closestPoint(predictedBox.expand(0.08), eyePos);
            }
        }

        float yaw = (float) Rotations.getYaw(aimPos);
        float pitch = (float) Rotations.getPitch(aimPos);
        double yawError = Math.abs(MathHelper.wrapDegrees((float) (yaw - module.client().player.getYaw())));
        double pitchError = Math.abs((double) pitch - module.client().player.getPitch());
        Vec3d playerClosest = closestPoint(predictedBox, playerPos);
        double distance = playerPos.distanceTo(playerClosest);
        double verticalDiff = Math.abs(playerPos.y - playerClosest.y);

        double speedBps = playerVel.horizontalLength() * 20.0;
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

        double cooldown = module.client().player.getAttackCooldownProgress(0.0f);
        long holdMs = runtime.holdMs(System.currentTimeMillis());
        SpearSpoofCombatTypes.RunStage stage = SpearSpoofCombatTypes.RunStage.fromHold(holdMs, minWindupMs.get(), readyWindowMs.get(), fatigueWindowMs.get());

        return new SpearSpoofCombatTypes.AttackContext(
            playerPos,
            playerVel,
            targetPos,
            targetVel,
            predictedTargetPos,
            aimPos, yaw, pitch, yawError, pitchError, distance, verticalDiff,
            speedBps,
            forwardDot,
            lookDot,
            closingSpeedBps,
            cooldown,
            holdMs,
            smallTarget,
            width,
            height,
            leadTicks,
            extraPredictTicks,
            totalPredictTicks,
            predictionAuto,
            predictionCollisionAware,
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
            handleAdaptiveReposition(decision.reason);
            debugLogger.logReject(decision.reason, decision.detail, decision.stage, strikeTarget, ctx, runtime);
            return;
        }

        module.client().interactionManager.attackEntity(module.client().player, strikeTarget);
        module.client().player.swingHand(Hand.MAIN_HAND);

        now = System.currentTimeMillis();
        clearPacketConfirm();
        hitConfirmBaseHurtTime = readIntMember(strikeTarget, "hurtTime", "getHurtTime");
        hitConfirmBaseRegenTime = readIntMember(strikeTarget, "timeUntilRegen", "getTimeUntilRegen");
        runtime.onStrikeSent(now, strikeTarget, resolveHitConfirmWindowMs());
        runtime.nextAttemptAtMs = Math.max(runtime.nextAttemptAtMs, now + recoveryDelayMs.get());
        lockApproachDirection(strikeTarget);

        scheduleAttributeSwap();
        debugLogger.logSkip("StrikeSent", "await-hit-confirm", strikeTarget, ctx, runtime);
    }


    protected boolean isDamageConfirmationPacket(Object packet) {
        if (packet == null) return false;
        if (packet instanceof EntityStatusS2CPacket statusPacket) {
            int status = statusPacket.getStatus();
            return status == DAMAGE_STATUS_HURT || status == DAMAGE_STATUS_DEAD;
        }
        String packetName = packet.getClass().getSimpleName();
        if (packetName != null && packetName.contains("Damage")) return true;

        Integer status = extractStatus(packet);
        return status != null && (status == DAMAGE_STATUS_HURT || status == DAMAGE_STATUS_DEAD);
    }

    protected boolean couldBeDamagePacket(Object packet) {
        if (packet == null) return false;
        if (packet instanceof EntityStatusS2CPacket) return true;
        String packetName = packet.getClass().getSimpleName();
        return packetName != null && packetName.contains("Damage");
    }

    protected Integer extractEntityId(Object packet) {
        Object entityId = invokeNoArg(packet, "getEntityId");
        if (entityId instanceof Integer i) return i;
        entityId = invokeNoArg(packet, "getId");
        if (entityId instanceof Integer i) return i;
        return null;
    }

    protected Integer extractStatus(Object packet) {
        Object status = invokeNoArg(packet, "getStatus");
        if (status instanceof Number n) return n.intValue();
        return null;
    }

    protected Object invokeNoArg(Object target, String methodName) {
        if (target == null || methodName == null || methodName.isBlank()) return null;
        try {
            var method = target.getClass().getMethod(methodName);
            method.setAccessible(true);
            return method.invoke(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    protected int readIntMember(Object target, String fieldName, String getterName) {
        if (target == null) return -1;
        Object getterValue = invokeNoArg(target, getterName);
        if (getterValue instanceof Number number) return number.intValue();

        Class<?> current = target.getClass();
        while (current != null) {
            try {
                var field = current.getDeclaredField(fieldName);
                field.setAccessible(true);
                Object value = field.get(target);
                if (value instanceof Number number) return number.intValue();
                return -1;
            } catch (NoSuchFieldException e) {
                current = current.getSuperclass();
            } catch (Throwable ignored) {
                return -1;
            }
        }
        return -1;
    }

    protected void clearPacketConfirm() {
        packetConfirmAtMs = 0L;
        packetConfirmTargetId = -1;
        packetConfirmType = "";
        hitConfirmBaseHurtTime = -1;
        hitConfirmBaseRegenTime = -1;
    }

    protected abstract boolean canAttemptInCurrentPhase(SpearSpoofCombatTypes.AttackContext ctx, long now);
    protected abstract String buildPhaseGateDetail(SpearSpoofCombatTypes.AttackContext ctx, long now);
    protected abstract SpearSpoofCombatTypes.Decision evaluateStrike(LivingEntity strikeTarget, SpearSpoofCombatTypes.AttackContext ctx);
    protected abstract void handleAdaptiveReposition(String reason);
    protected abstract long resolveHitConfirmWindowMs();

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
