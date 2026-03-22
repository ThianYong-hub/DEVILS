package com.example.addon.modules.spearspoof;

import com.example.addon.modules.SpearSpoof;
import meteordevelopment.meteorclient.mixininterface.ICamera;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.world.TickRate;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.PhantomEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.s2c.play.EntityStatusS2CPacket;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.Locale;

public final class SpearSpoofCombatService {
    private static final int ROTATE_PRIORITY = 80;
    private static final int RMB_RECHARGE_RELEASE_TICKS = 1;
    private static final long FORCED_USE_INTERACT_RETRY_MS = 90L;
    private static final long STRIKE_INTERVAL_MS = 90L;
    private static final double ENFORCED_MIN_RANGE = 0.40;
    private static final double ENFORCED_SMALL_MIN_RANGE = 0.28;
    private static final double ENFORCED_MAX_RANGE = 4.5;
    private static final double MODE_4X_MIN_RANGE = 4.0;
    private static final double MODE_4X_MAX_RANGE = 4.5;
    private static final double NON_PLAYER_SPEED_BONUS_BPS = 0.2;
    private static final int HOTBAR_SWITCH_DELAY_TICKS = 1;
    private static final float LAG_PAUSE_THRESHOLD = 1.0f;
    private static final float LAG_PAUSE_MAX_VALID = 8.0f;
    private static final long TARGET_SWITCH_WINDUP_RESTART_MARGIN_MS = 180L;
    private static final long RECHARGE_REBUILD_MS = 950L;
    private static final long RECHARGE_RESET_HOLD_MS = 340L;
    private static final long HIT_CONFIRM_WINDOW_MIN_MS = 520L;
    private static final long HIT_CONFIRM_WINDOW_MAX_MS = 1400L;
    private static final double HIT_CONFIRM_PING_FACTOR = 1.5;
    private static final double HIT_CONFIRM_RETRY_1_FRACTION = 0.30;
    private static final double HIT_CONFIRM_RETRY_2_FRACTION = 0.58;
    private static final double HIT_CONFIRM_RETRY_3_FRACTION = 0.82;
    private static final int DAMAGE_STATUS_HURT = 2;
    private static final int DAMAGE_STATUS_DEAD = 3;

    private final SpearSpoof module;
    private final SpearSpoofRuntime runtime;
    private final SpearSpoofTargetingService targeting;
    private final SpearSpoofDebugLogger debugLogger;

    private final Setting<Boolean> onlyWhileElytra;
    private final Setting<Boolean> autoSwitch;
    private final Setting<Boolean> autoHoldUse;
    private final Setting<Boolean> autoRestartWindup;
    private final Setting<Boolean> attributeSwap;
    private final Setting<Boolean> rotate;
    private final Setting<Boolean> yawCamera;
    private final Setting<Boolean> mode4x;

    private final Setting<Double> minRange;
    private final Setting<Double> maxRange;
    private final Setting<Double> smallTargetRange;
    private final Setting<Double> fatigueRangePenalty;
    private final Setting<Double> maxVerticalDelta;
    private final Setting<Double> minSpeedBps;
    private final Setting<Double> minForwardDot;
    private final Setting<Double> minClosingSpeedBps;
    private final Setting<Double> minCooldown;
    private final Setting<Double> maxYawError;
    private final Setting<Double> maxPitchError;

    private final Setting<Integer> minWindupMs;
    private final Setting<Integer> readyWindowMs;
    private final Setting<Integer> fatigueWindowMs;
    private final Setting<Integer> recoveryDelayMs;

    private final Setting<Boolean> requireLineOfSight;
    private final Setting<Boolean> adaptiveReposition;
    private final Setting<Integer> repositionRejectStreak;
    private final Setting<Integer> repositionHoldMs;

    private long packetConfirmAtMs;
    private int packetConfirmTargetId = -1;
    private String packetConfirmType = "";
    private int hitConfirmBaseHurtTime = -1;
    private int hitConfirmBaseRegenTime = -1;

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
        Setting<Double> minRange,
        Setting<Double> maxRange,
        Setting<Double> smallTargetRange,
        Setting<Double> fatigueRangePenalty,
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
        this.module = module;
        this.runtime = runtime;
        this.targeting = targeting;
        this.debugLogger = debugLogger;

        this.onlyWhileElytra = onlyWhileElytra;
        this.autoSwitch = autoSwitch;
        this.autoHoldUse = autoHoldUse;
        this.autoRestartWindup = autoRestartWindup;
        this.attributeSwap = attributeSwap;
        this.rotate = rotate;
        this.yawCamera = yawCamera;
        this.mode4x = mode4x;

        this.minRange = minRange;
        this.maxRange = maxRange;
        this.smallTargetRange = smallTargetRange;
        this.fatigueRangePenalty = fatigueRangePenalty;
        this.maxVerticalDelta = maxVerticalDelta;
        this.minSpeedBps = minSpeedBps;
        this.minForwardDot = minForwardDot;
        this.minClosingSpeedBps = minClosingSpeedBps;
        this.minCooldown = minCooldown;
        this.maxYawError = maxYawError;
        this.maxPitchError = maxPitchError;

        this.minWindupMs = minWindupMs;
        this.readyWindowMs = readyWindowMs;
        this.fatigueWindowMs = fatigueWindowMs;
        this.recoveryDelayMs = recoveryDelayMs;

        this.requireLineOfSight = requireLineOfSight;
        this.adaptiveReposition = adaptiveReposition;
        this.repositionRejectStreak = repositionRejectStreak;
        this.repositionHoldMs = repositionHoldMs;
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

    private void lockApproachDirection(LivingEntity target) {
        if (module.client().player == null || target == null) return;
        Vec3d fromTarget = horizontal(module.client().player.getEntityPos().subtract(target.getEntityPos()));
        Vec3d fallback = horizontal(module.client().player.getRotationVector()).multiply(-1.0);
        runtime.lockedApproachDirection = normalizeOrFallback(fromTarget, fallback);
    }

    private boolean isSoftTrackableDuringConfirm(LivingEntity target) {
        if (target == null || module.client().player == null) return false;
        if (target.isRemoved() || !target.isAlive() || target.isDead()) return false;
        double maxDistance = module.permanentTargetRange() + 2.5;
        return module.client().player.distanceTo(target) <= maxDistance;
    }

    private void onTargetChanged(long now, LivingEntity newTarget) {
        lockApproachDirection(newTarget);
        runtime.hitChain = 0;
        // Keep existing RMB hold/charge across target switches.
        // This prevents unnecessary re-click when mobs die close to each other.
        runtime.nextAttemptAtMs = Math.max(runtime.nextAttemptAtMs, now + 35L);

        // If charge is already in late fatigue/recovery on target switch, restart windup automatically.
        // This prevents "new target reached but spear already discharged" cases that required manual RMB.
        if (!attributeSwap.get() && autoHoldUse.get() && runtime.useStartedAtMs > 0L) {
            long heldMs = runtime.holdMs(now);
            long fullWindow = fullChargeWindowMs();
            long restartAt = Math.max(minWindupMs.get(), fullWindow - TARGET_SWITCH_WINDUP_RESTART_MARGIN_MS);
            if (heldMs >= restartAt) {
                triggerWindupRestart(now);
                runtime.pendingRmbRecharge = false;
                runtime.rmbRechargeReleaseTicks = 0;
                runtime.nextAttemptAtMs = Math.max(runtime.nextAttemptAtMs, now + 55L);
            }
        }

        if (module.client().player != null && newTarget != null) {
            double distance = module.client().player.getEntityPos().distanceTo(newTarget.getEntityPos());
            double minRange = effectiveMinRange(null, newTarget);
            double maxRange = effectiveMaxRange(null, newTarget);
            boolean closeSwitch = distance <= maxRange + 0.80;
            if (distance < minRange + 0.08 || closeSwitch) {
                long holdMs = isSmallTarget(newTarget) ? 170L : 240L;
                runtime.beginReset(now, holdMs);
                long rampLockMs = isSmallTarget(newTarget) ? 120L : 170L;
                runtime.repositionUntilMs = Math.max(runtime.repositionUntilMs, now + rampLockMs);
                runtime.nextAttemptAtMs = Math.max(runtime.nextAttemptAtMs, runtime.repositionUntilMs);
            }
        }
    }

    private void triggerWindupRestart(long now) {
        runtime.windupRestartTicks = Math.max(runtime.windupRestartTicks, 2);
        runtime.useStartedAtMs = 0;
        runtime.lastForcedUseInteractMs = 0L;
        runtime.rechargeRebuildUntilMs = Math.max(runtime.rechargeRebuildUntilMs, now + RECHARGE_REBUILD_MS);
        runtime.beginReset(now, RECHARGE_RESET_HOLD_MS);
    }

    private boolean ensureSpearInMainHand() {
        if (isSpear(module.client().player.getMainHandStack())) return true;
        if (!autoSwitch.get()) return false;

        int spearSlot = findBestSpearSlot();
        if (spearSlot < 0) return false;

        if (spearSlot != module.client().player.getInventory().getSelectedSlot()) {
            if (InvUtils.swap(spearSlot, false)) {
                runtime.switchDelayTicks = Math.max(runtime.switchDelayTicks, HOTBAR_SWITCH_DELAY_TICKS);
            }
        }

        return isSpear(module.client().player.getMainHandStack());
    }

    private boolean isSpear(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        if (stack.isIn(ItemTags.SPEARS)) return true;

        String idPath = Registries.ITEM.getId(stack.getItem()).getPath().toLowerCase(Locale.ROOT);
        if (idPath.equals("spear") || idPath.endsWith("_spear") || idPath.contains("spear")) return true;

        String translationKey = stack.getItem().getTranslationKey().toLowerCase(Locale.ROOT);
        if (translationKey.contains("spear")) return true;

        String displayName = stack.getName().getString().toLowerCase(Locale.ROOT);
        return displayName.contains("spear");
    }

    private String describeMainHandItem() {
        if (module.client().player == null) return "player-null";
        ItemStack stack = module.client().player.getMainHandStack();
        if (stack == null || stack.isEmpty()) return "empty";
        String id = Registries.ITEM.getId(stack.getItem()).toString();
        return id + " tagged=" + stack.isIn(ItemTags.SPEARS);
    }

    private void applyTrackingRotation(LivingEntity target) {
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

    private int findBestSpearSlot() {
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

    private void tickUseKey(boolean hasSpear) {
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

    private boolean shouldForceRechargeByStage() {
        if (attributeSwap.get()) return false;
        if (runtime.useStartedAtMs <= 0L) return false;
        long heldMs = runtime.holdMs(System.currentTimeMillis());
        long fullWindow = fullChargeWindowMs();
        return heldMs >= fullWindow;
    }

    private long fullChargeWindowMs() {
        return (long) minWindupMs.get() + readyWindowMs.get() + fatigueWindowMs.get();
    }

    private void releaseUseKey() {
        if (runtime.useKeyInjected) module.client().options.useKey.setPressed(false);
        runtime.useKeyInjected = false;
        runtime.windupRestartTicks = 0;
        runtime.lastForcedUseInteractMs = 0L;
    }

    private void tickWindupTimer(boolean hasSpear) {
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

    private void tickSwapBack() {
        if (runtime.swapBackSlot < 0) return;
        if (runtime.swapBackTicks > 0) {
            runtime.swapBackTicks--;
            return;
        }

        InvUtils.swap(runtime.swapBackSlot, false);
        runtime.swapBackSlot = -1;
    }

    private void scheduleAttributeSwap() {
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

    private int findFallbackSlot(int spearSlot) {
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

    private SpearSpoofCombatTypes.AttackContext buildContext(LivingEntity entity) {
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
        long nowMs = System.currentTimeMillis();
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

    private void tryStrike(LivingEntity strikeTarget) {
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

    private boolean updateHitConfirmation(long now, LivingEntity currentTarget) {
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

    private void maybeRetryStrikeDuringConfirm(long now, LivingEntity currentTarget) {
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

    private void handleAdaptiveReposition(String reason) {
        if (!adaptiveReposition.get()) return;

        boolean speedLocked = "LowSpeed".equals(reason) && runtime.speedRejectStreak >= repositionRejectStreak.get();
        boolean forwardLocked = "BadForward".equals(reason) && runtime.forwardRejectStreak >= repositionRejectStreak.get();
        if (!speedLocked && !forwardLocked) return;

        long now = System.currentTimeMillis();
        runtime.repositionUntilMs = now + repositionHoldMs.get();
        runtime.nextAttemptAtMs = runtime.repositionUntilMs;
        runtime.beginReset(now, repositionHoldMs.get());
    }

    private SpearSpoofCombatTypes.Decision evaluateStrike(LivingEntity strikeTarget, SpearSpoofCombatTypes.AttackContext ctx) {
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

    private boolean isInsideAttemptWindow(SpearSpoofCombatTypes.AttackContext ctx) {
        if (ctx == null) return false;
        double minRange = effectiveMinRange(ctx, runtime.target);
        double maxRange = effectiveMaxRange(ctx, runtime.target);
        return ctx.distance >= minRange && ctx.distance <= maxRange;
    }

    private boolean canAttemptInCurrentPhase(SpearSpoofCombatTypes.AttackContext ctx, long now) {
        if (ctx == null) return false;
        if (attributeSwap.get()) return true;
        if (runtime.passPhase == SpearSpoofRuntime.PassPhase.APPROACH) return true;
        if (runtime.passPhase == SpearSpoofRuntime.PassPhase.RESET && isPitVerticalLockActive(runtime.target, now)) return true;
        return false;
    }

    private String buildPhaseGateDetail(SpearSpoofCombatTypes.AttackContext ctx, long now) {
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

    private double effectiveMinRange(SpearSpoofCombatTypes.AttackContext ctx, LivingEntity target) {
        double base = ((ctx != null && ctx.smallTarget) || isSmallTarget(target))
            ? ENFORCED_SMALL_MIN_RANGE
            : ENFORCED_MIN_RANGE;
        if (uses4xRangeWindow(target)) base = Math.max(base, MODE_4X_MIN_RANGE);
        return base;
    }

    private double effectiveMaxRange(SpearSpoofCombatTypes.AttackContext ctx, LivingEntity target) {
        if (uses4xRangeWindow(target)) return Math.min(ENFORCED_MAX_RANGE, MODE_4X_MAX_RANGE);
        return ENFORCED_MAX_RANGE;
    }

    private boolean uses4xRangeWindow(LivingEntity target) {
        if (mode4x.get()) return true;
        if (target == null) return false;
        long now = System.currentTimeMillis();
        return runtime.pitVerticalLockTargetId == target.getId()
            && runtime.pitVerticalLockUntilMs > now;
    }

    private double effectiveMinSpeedBps(LivingEntity target) {
        if (target != null && !(target instanceof PlayerEntity)) return minSpeedBps.get() + NON_PLAYER_SPEED_BONUS_BPS;
        return minSpeedBps.get();
    }

    private boolean isSpearRayHit(LivingEntity target, SpearSpoofCombatTypes.AttackContext ctx) {
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

    private long strikeReadyDelayMs(LivingEntity target, SpearSpoofCombatTypes.AttackContext ctx, long now) {
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

    private boolean isSmallTarget(LivingEntity entity) {
        if (entity == null) return false;
        Box box = entity.getBoundingBox();
        double width = Math.max(box.getLengthX(), box.getLengthZ());
        double height = box.getLengthY();
        return entity instanceof PhantomEntity || (width <= 0.90 && height <= 1.10);
    }

    private double effectiveMinForwardDot(SpearSpoofCombatTypes.AttackContext ctx) {
        if (ctx == null) return minForwardDot.get();
        if (isPitVerticalLockActive(runtime.target, System.currentTimeMillis())) return -1.0;
        double base = minForwardDot.get();
        if (ctx.smallTarget) base -= 0.36;
        if (runtime.passPhase == SpearSpoofRuntime.PassPhase.RESET) base = Math.min(base, -0.28);
        return MathHelper.clamp(base, -0.80, 1.0);
    }

    private double effectiveMinClosingBps(SpearSpoofCombatTypes.AttackContext ctx) {
        if (ctx == null) return minClosingSpeedBps.get();
        if (isPitVerticalLockActive(runtime.target, System.currentTimeMillis())) return -2.0;
        double base = minClosingSpeedBps.get();
        if (ctx.smallTarget) base = Math.min(base, -0.60);
        else if (runtime.passPhase == SpearSpoofRuntime.PassPhase.RESET) base = Math.min(base, -0.25);
        return base;
    }

    private boolean isPitVerticalLockActive(LivingEntity target, long now) {
        if (target == null) return false;
        return runtime.pitVerticalLockTargetId == target.getId() && now < runtime.pitVerticalLockUntilMs;
    }

    private long resolveHitConfirmWindowMs() {
        int ping = 0;
        if (module.client().getNetworkHandler() != null
            && module.client().getNetworkHandler().getPlayerListEntry(module.client().player.getUuid()) != null) {
            ping = Math.max(0, module.client().getNetworkHandler().getPlayerListEntry(module.client().player.getUuid()).getLatency());
        }

        long dynamic = (long) (HIT_CONFIRM_WINDOW_MIN_MS + MathHelper.clamp(ping, 0, 450) * HIT_CONFIRM_PING_FACTOR);
        return MathHelper.clamp(dynamic, HIT_CONFIRM_WINDOW_MIN_MS, HIT_CONFIRM_WINDOW_MAX_MS);
    }

    private boolean hasTargetIFrameSignal(LivingEntity target) {
        if (target == null) return false;
        int hurtTime = readIntMember(target, "hurtTime", "getHurtTime");
        if (hurtTime > 0) return true;
        int regen = readIntMember(target, "timeUntilRegen", "getTimeUntilRegen");
        return regen > 0;
    }

    private boolean hasServerDamageSignalOnEntity(LivingEntity target, long now) {
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

    private boolean isDamageConfirmationPacket(Object packet) {
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

    private boolean couldBeDamagePacket(Object packet) {
        if (packet == null) return false;
        if (packet instanceof EntityStatusS2CPacket) return true;
        String packetName = packet.getClass().getSimpleName();
        return packetName != null && packetName.contains("Damage");
    }

    private Integer extractEntityId(Object packet) {
        Object entityId = invokeNoArg(packet, "getEntityId");
        if (entityId instanceof Integer i) return i;
        entityId = invokeNoArg(packet, "getId");
        if (entityId instanceof Integer i) return i;
        return null;
    }

    private Integer extractStatus(Object packet) {
        Object status = invokeNoArg(packet, "getStatus");
        if (status instanceof Number n) return n.intValue();
        return null;
    }

    private Object invokeNoArg(Object target, String methodName) {
        if (target == null || methodName == null || methodName.isBlank()) return null;
        try {
            var method = target.getClass().getMethod(methodName);
            method.setAccessible(true);
            return method.invoke(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private int readIntMember(Object target, String fieldName, String getterName) {
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

    private void clearPacketConfirm() {
        packetConfirmAtMs = 0L;
        packetConfirmTargetId = -1;
        packetConfirmType = "";
        hitConfirmBaseHurtTime = -1;
        hitConfirmBaseRegenTime = -1;
    }

    private double requiredLookDot(SpearSpoofCombatTypes.AttackContext ctx) {
        if (ctx == null) return 0.75;
        if (ctx.smallTarget) return 0.70;
        return 0.78;
    }

    private String f2(double value) {
        return String.format(Locale.US, "%.2f", value);
    }

    private void applyCameraLook(double yaw, double pitch) {
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

    private static Vec3d horizontal(Vec3d value) {
        return new Vec3d(value.x, 0.0, value.z);
    }

    private static Vec3d normalizeOrFallback(Vec3d vector, Vec3d fallback) {
        if (vector != null && vector.lengthSquared() > 1.0E-6) return vector.normalize();
        if (fallback != null && fallback.lengthSquared() > 1.0E-6) return fallback.normalize();
        return new Vec3d(1.0, 0.0, 0.0);
    }

    private Vec3d closestPoint(Box box, Vec3d from) {
        return new Vec3d(
            MathHelper.clamp(from.x, box.minX, box.maxX),
            MathHelper.clamp(from.y, box.minY, box.maxY),
            MathHelper.clamp(from.z, box.minZ, box.maxZ)
        );
    }

}
