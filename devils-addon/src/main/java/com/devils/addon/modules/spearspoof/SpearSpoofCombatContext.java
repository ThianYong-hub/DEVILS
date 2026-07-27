package com.devils.addon.modules.spearspoof;

import com.devils.addon.modules.SpearSpoof;
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
    protected static final long FORCED_USE_INTERACT_RETRY_MS = 90L;
    protected static final double NON_PLAYER_SPEED_BONUS_BPS = 0.2;
    protected static final int HOTBAR_SWITCH_DELAY_TICKS = 1;
    protected static final float LAG_PAUSE_THRESHOLD = 1.0f;
    protected static final float LAG_PAUSE_MAX_VALID = 8.0f;

    // Only used for "spear-like" items that carry no KINETIC_WEAPON / ATTACK_RANGE component
    // (isSpear also matches by name, so modded items can land here). Real spears read their own numbers.
    protected static final long FALLBACK_WINDUP_MS = 500L;
    protected static final double FALLBACK_MIN_RANGE = 1.875;
    protected static final double FALLBACK_MAX_RANGE = 4.625;

    protected final SpearSpoof module;
    protected final SpearSpoofRuntime runtime;
    protected final SpearSpoofTargetingService targeting;
    protected final SpearSpoofDebugLogger debugLogger;

    protected final Setting<Boolean> onlyWhileElytra;
    protected final Setting<Boolean> autoSwitch;
    protected final Setting<Boolean> autoHoldUse;
    protected final Setting<Boolean> rotate;
    protected final Setting<Boolean> yawCamera;
    protected final Setting<Boolean> mode4x;

    protected final Setting<Double> maxVerticalDelta;
    protected final Setting<Double> minSpeedBps;
    protected final Setting<Double> minForwardDot;
    protected final Setting<Double> minClosingSpeedBps;
    protected final Setting<Double> minRange;
    protected final Setting<Double> maxRange;

    protected final Setting<Boolean> requireLineOfSight;

    protected SpearSpoofCombatContext(
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
        this.module = module;
        this.runtime = runtime;
        this.targeting = targeting;
        this.debugLogger = debugLogger;

        this.onlyWhileElytra = onlyWhileElytra;
        this.autoSwitch = autoSwitch;
        this.autoHoldUse = autoHoldUse;
        this.rotate = rotate;
        this.yawCamera = yawCamera;
        this.mode4x = mode4x;

        this.maxVerticalDelta = maxVerticalDelta;
        this.minSpeedBps = minSpeedBps;
        this.minForwardDot = minForwardDot;
        this.minClosingSpeedBps = minClosingSpeedBps;
        this.minRange = minRange;
        this.maxRange = maxRange;

        this.requireLineOfSight = requireLineOfSight;
    }

    protected void lockApproachDirection(LivingEntity target) {
        if (module.client().player == null || target == null) return;
        Vec3d fromTarget = horizontal(module.client().player.getEntityPos().subtract(target.getEntityPos()));
        Vec3d fallback = horizontal(module.client().player.getRotationVector()).multiply(-1.0);
        runtime.lockedApproachDirection = normalizeOrFallback(fromTarget, fallback);
    }

    protected void onTargetChanged(long now, LivingEntity newTarget) {
        lockApproachDirection(newTarget);
        runtime.hitChain = 0;
        // Keep the RMB charge when the target swaps: releasing it makes the server call clearActiveItem,
        // which drops the charge and the piercing cooldown map and costs a full delayTicks to re-arm.

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
            }
        }
    }

    protected boolean ensureSpearInMainHand() {
        // Never swaps while a spear is already held: UpdateSelectedSlotC2SPacket makes the server
        // call clearActiveItem, destroying the server-side charge mid-pass.
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

    protected abstract double effectiveMinRange(SpearSpoofCombatTypes.AttackContext ctx, LivingEntity target);
    protected abstract double effectiveMaxRange(SpearSpoofCombatTypes.AttackContext ctx, LivingEntity target);
    protected abstract boolean isSmallTarget(LivingEntity entity);
    protected abstract int findBestSpearSlot();

}
