package com.example.addon.modules.spearspoof;

import com.example.addon.modules.SpearSpoof;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.util.math.Vec3d;

import java.util.Locale;


abstract class SpearSpoofCombatContext {
    protected static final int ROTATE_PRIORITY = 80;
    protected static final int RMB_RECHARGE_RELEASE_TICKS = 1;
    protected static final long FORCED_USE_INTERACT_RETRY_MS = 90L;
    protected static final long STRIKE_INTERVAL_MS = 90L;
    protected static final double ENFORCED_MIN_RANGE = 0.40;
    protected static final double ENFORCED_SMALL_MIN_RANGE = 0.28;
    protected static final double ENFORCED_MAX_RANGE = 4.5;
    protected static final double MODE_4X_MIN_RANGE = 4.0;
    protected static final double MODE_4X_MAX_RANGE = 4.5;
    protected static final double NON_PLAYER_SPEED_BONUS_BPS = 0.2;
    protected static final int HOTBAR_SWITCH_DELAY_TICKS = 1;
    protected static final float LAG_PAUSE_THRESHOLD = 1.0f;
    protected static final float LAG_PAUSE_MAX_VALID = 8.0f;
    protected static final long TARGET_SWITCH_WINDUP_RESTART_MARGIN_MS = 180L;
    protected static final long RECHARGE_REBUILD_MS = 950L;
    protected static final long RECHARGE_RESET_HOLD_MS = 340L;
    protected static final long HIT_CONFIRM_WINDOW_MIN_MS = 520L;
    protected static final long HIT_CONFIRM_WINDOW_MAX_MS = 1400L;
    protected static final double HIT_CONFIRM_PING_FACTOR = 1.5;
    protected static final double HIT_CONFIRM_RETRY_1_FRACTION = 0.30;
    protected static final double HIT_CONFIRM_RETRY_2_FRACTION = 0.58;
    protected static final double HIT_CONFIRM_RETRY_3_FRACTION = 0.82;
    protected static final int DAMAGE_STATUS_HURT = 2;
    protected static final int DAMAGE_STATUS_DEAD = 3;

    protected final SpearSpoof module;
    protected final SpearSpoofRuntime runtime;
    protected final SpearSpoofTargetingService targeting;
    protected final SpearSpoofDebugLogger debugLogger;

    protected final Setting<Boolean> onlyWhileElytra;
    protected final Setting<Boolean> autoSwitch;
    protected final Setting<Boolean> autoHoldUse;
    protected final Setting<Boolean> autoRestartWindup;
    protected final Setting<Boolean> attributeSwap;
    protected final Setting<Boolean> rotate;
    protected final Setting<Boolean> yawCamera;
    protected final Setting<Boolean> mode4x;

    protected final Setting<Double> maxVerticalDelta;
    protected final Setting<Double> minSpeedBps;
    protected final Setting<Double> minForwardDot;
    protected final Setting<Double> minClosingSpeedBps;
    protected final Setting<Double> minCooldown;
    protected final Setting<Double> maxYawError;
    protected final Setting<Double> maxPitchError;

    protected final Setting<Integer> minWindupMs;
    protected final Setting<Integer> readyWindowMs;
    protected final Setting<Integer> fatigueWindowMs;
    protected final Setting<Integer> recoveryDelayMs;

    protected final Setting<Boolean> requireLineOfSight;
    protected final Setting<Boolean> adaptiveReposition;
    protected final Setting<Integer> repositionRejectStreak;
    protected final Setting<Integer> repositionHoldMs;

    protected long packetConfirmAtMs;
    protected int packetConfirmTargetId = -1;
    protected String packetConfirmType = "";
    protected int hitConfirmBaseHurtTime = -1;
    protected int hitConfirmBaseRegenTime = -1;

    protected SpearSpoofCombatContext(
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

    protected void lockApproachDirection(LivingEntity target) {
        if (module.client().player == null || target == null) return;
        Vec3d fromTarget = horizontal(module.client().player.getEntityPos().subtract(target.getEntityPos()));
        Vec3d fallback = horizontal(module.client().player.getRotationVector()).multiply(-1.0);
        runtime.lockedApproachDirection = normalizeOrFallback(fromTarget, fallback);
    }

    protected boolean isSoftTrackableDuringConfirm(LivingEntity target) {
        if (target == null || module.client().player == null) return false;
        if (target.isRemoved() || !target.isAlive() || target.isDead()) return false;
        double maxDistance = module.permanentTargetRange() + 2.5;
        return module.client().player.distanceTo(target) <= maxDistance;
    }

    protected void onTargetChanged(long now, LivingEntity newTarget) {
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

    protected void triggerWindupRestart(long now) {
        runtime.windupRestartTicks = Math.max(runtime.windupRestartTicks, 2);
        runtime.useStartedAtMs = 0;
        runtime.lastForcedUseInteractMs = 0L;
        runtime.rechargeRebuildUntilMs = Math.max(runtime.rechargeRebuildUntilMs, now + RECHARGE_REBUILD_MS);
        runtime.beginReset(now, RECHARGE_RESET_HOLD_MS);
    }

    protected boolean ensureSpearInMainHand() {
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

    protected boolean isSpear(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        if (stack.isIn(ItemTags.SPEARS)) return true;

        String idPath = Registries.ITEM.getId(stack.getItem()).getPath().toLowerCase(Locale.ROOT);
        if (idPath.equals("spear") || idPath.endsWith("_spear") || idPath.contains("spear")) return true;

        String translationKey = stack.getItem().getTranslationKey().toLowerCase(Locale.ROOT);
        if (translationKey.contains("spear")) return true;

        String displayName = stack.getName().getString().toLowerCase(Locale.ROOT);
        return displayName.contains("spear");
    }

    protected String describeMainHandItem() {
        if (module.client().player == null) return "player-null";
        ItemStack stack = module.client().player.getMainHandStack();
        if (stack == null || stack.isEmpty()) return "empty";
        String id = Registries.ITEM.getId(stack.getItem()).toString();
        return id + " tagged=" + stack.isIn(ItemTags.SPEARS);
    }

    protected static Vec3d horizontal(Vec3d value) {
        return new Vec3d(value.x, 0.0, value.z);
    }

    protected static Vec3d normalizeOrFallback(Vec3d vector, Vec3d fallback) {
        if (vector != null && vector.lengthSquared() > 1.0E-6) return vector.normalize();
        if (fallback != null && fallback.lengthSquared() > 1.0E-6) return fallback.normalize();
        return new Vec3d(1.0, 0.0, 0.0);
    }

    protected abstract long fullChargeWindowMs();
    protected abstract double effectiveMinRange(SpearSpoofCombatTypes.AttackContext ctx, LivingEntity target);
    protected abstract double effectiveMaxRange(SpearSpoofCombatTypes.AttackContext ctx, LivingEntity target);
    protected abstract boolean isSmallTarget(LivingEntity entity);
    protected abstract int findBestSpearSlot();

}
