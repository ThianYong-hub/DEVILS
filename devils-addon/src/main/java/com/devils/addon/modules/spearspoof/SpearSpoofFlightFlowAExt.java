package com.devils.addon.modules.spearspoof;

import com.devils.addon.modules.SpearSpoof;
import meteordevelopment.meteorclient.settings.Setting;
import net.minecraft.block.Blocks;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.registry.tag.BlockTags;


abstract class SpearSpoofFlightFlowAExt extends SpearSpoofFlightFlowA {
    protected SpearSpoofFlightFlowAExt(
        SpearSpoof module,
        SpearSpoofRuntime runtime,
        SpearSpoofFlightPathfinder pathfinder,
        SpearSpoofTargetingService targeting,
        SpearSpoofCombatService combat,
        SpearSpoofDebugLogger debugLogger,
        Setting<Boolean> onlyWhileElytra,
        Setting<Boolean> attributeSwap,
        Setting<Double> minRange,
        Setting<Double> maxRange,
        Setting<Double> smallTargetRange,
        Setting<Double> horizontalSpeed,
        Setting<Double> verticalSpeed,
        Setting<Double> approachRange,
        Setting<Double> retreatRange,
        Setting<Boolean> topDownEnabled,
        Setting<Double> topDownHeight,
        Setting<Boolean> obstacleAvoidance,
        Setting<Boolean> autoRelaunch,
        Setting<Boolean> testFlyUntilDamage,
        Setting<Boolean> mode4x
    ) {
        super(
            module,
            runtime,
            pathfinder,
            targeting,
            combat,
            debugLogger,
            onlyWhileElytra,
            attributeSwap,
            minRange,
            maxRange,
            smallTargetRange,
            horizontalSpeed,
            verticalSpeed,
            approachRange,
            retreatRange,
            topDownEnabled,
            topDownHeight,
            obstacleAvoidance,
            autoRelaunch,
            testFlyUntilDamage,
            mode4x
        );
    }

    public void onDeactivate() {
        pathfinder.onDeactivate();
        runtime.adaptiveHorizontalCap = -1.0;
        runtime.antiCheatSlowTicks = 0;
        runtime.relaunchGroundTicks = 0;
        runtime.relaunchJumpTicks = 0;
        runtime.lastStuckSampleMs = 0;
        runtime.lastStuckSamplePos = Vec3d.ZERO;
        runtime.unstuckUntilMs = 0;
        runtime.stuckTicks = 0;
    }

    public void onTick() {
        if (module.client().player == null || module.client().world == null) return;
        pathfinder.tickCaches();

        long now = System.currentTimeMillis();
        boolean hasValidTarget = runtime.target != null && targeting.isValid(runtime.target);
        boolean hasRecentSnapshot = runtime.lastKnownTargetSeenAtMs > 0L
            && (now - runtime.lastKnownTargetSeenAtMs) <= LOST_TARGET_FOLLOW_MS;
        if (autoRelaunch.get() && (hasValidTarget || hasRecentSnapshot)) ensureElytraRelaunch();

        if (attributeSwap.get()) {
            pathfinder.clearTargetState();
            toApproachWithTrace(System.currentTimeMillis(), "attribute-swap", runtime.target);
            return;
        }

        if (!hasValidTarget) {
            pathfinder.clearTargetState();
            toApproachWithTrace(now, "target-invalid", runtime.target);
            return;
        }
        long strikeDelayMs = combat.strikeReadyDelayMs(runtime.target);
        updatePassPhase(runtime.target, strikeDelayMs);
    }


    protected boolean isHorizontalRouteBlocked(Vec3d playerPos, Vec3d targetPos, LivingEntity target) {
        if (playerPos == null || targetPos == null || target == null || module.client().world == null) return false;

        Vec3d flat = horizontal(targetPos.subtract(playerPos));
        double flatDistance = flat.length();
        if (flatDistance < 2.4) return false;

        Vec3d dir = flat.multiply(1.0 / flatDistance);
        double scanDistance = Math.min(HORIZONTAL_BLOCK_SCAN_MAX, flatDistance - 0.35);
        double sampleY = Math.max(playerPos.y + 0.20, targetPos.y + Math.min(1.10, target.getHeight() * 0.55));

        for (double d = HORIZONTAL_BLOCK_SCAN_STEP; d <= scanDistance; d += HORIZONTAL_BLOCK_SCAN_STEP) {
            double sx = playerPos.x + dir.x * d;
            double sz = playerPos.z + dir.z * d;
            if (isHardBlocking(sx, sampleY, sz) || isHardBlocking(sx, sampleY + 1.0, sz)) return true;
        }

        return false;
    }

    protected boolean isVerticalColumnBlocked(Vec3d playerPos, Vec3d targetPos) {
        if (playerPos == null || targetPos == null || module.client().world == null) return false;
        double topY = Math.max(playerPos.y, targetPos.y + 0.2);
        double bottomY = Math.min(playerPos.y, targetPos.y + 0.2);
        for (double y = topY; y >= bottomY; y -= 0.38) {
            if (isHardBlocking(targetPos.x, y, targetPos.z)) return true;
        }
        return false;
    }

    protected Vec3d selectCanopyBypassAnchor(Vec3d playerPos, Vec3d targetPos) {
        Vec3d baseAway = normalizeOrFallback(horizontal(playerPos.subtract(targetPos)), horizontal(runtime.lockedApproachDirection));
        Vec3d left = new Vec3d(-baseAway.z, 0.0, baseAway.x);
        Vec3d right = left.multiply(-1.0);
        Vec3d[] dirs = new Vec3d[] {
            baseAway,
            left,
            right,
            normalizeOrFallback(baseAway.add(left.multiply(0.55)), baseAway),
            normalizeOrFallback(baseAway.add(right.multiply(0.55)), baseAway)
        };

        Vec3d best = new Vec3d(targetPos.x + baseAway.x * 2.4, playerPos.y, targetPos.z + baseAway.z * 2.4);
        double bestScore = Double.NEGATIVE_INFINITY;
        for (Vec3d dir : dirs) {
            for (double radius : new double[] {2.2, 2.8, 3.4}) {
                Vec3d candidate = new Vec3d(targetPos.x + dir.x * radius, playerPos.y, targetPos.z + dir.z * radius);
                if (!pathfinder.hasPlayerClearance(candidate)) continue;
                Vec3d toCandidate = horizontal(candidate.subtract(playerPos));
                double toLen = toCandidate.length();
                if (toLen < 0.12) continue;
                Vec3d toDir = toCandidate.multiply(1.0 / toLen);
                double clear = probeClearDistance(playerPos, toDir, Math.min(8.0, Math.max(2.0, toLen)));
                int openings = pathfinder.countLateralOpenings(candidate, 1.0);
                double score = clear * 1.1 + openings * 0.55 - Math.abs(radius - 2.6) * 0.25;
                if (score > bestScore) {
                    bestScore = score;
                    best = candidate;
                }
            }
        }

        return best;
    }

    protected boolean hasFoliageCanopy(Vec3d targetPos, LivingEntity target) {
        if (targetPos == null || target == null || module.client().world == null) return false;
        double x = targetPos.x;
        double z = targetPos.z;
        double startY = target.getY() + target.getHeight() + 0.25;
        for (int i = 0; i < 6; i++) {
            double y = startY + i * 0.55;
            BlockPos pos = BlockPos.ofFloored(x, y, z);
            if (module.client().world.isOutOfHeightLimit(pos.getY())) break;
            var state = module.client().world.getBlockState(pos);
            if (state.getCollisionShape(module.client().world, pos).isEmpty()) continue;
            if (state.isIn(BlockTags.LEAVES) || state.isOf(Blocks.VINE) || state.isOf(Blocks.CAVE_VINES) || state.isOf(Blocks.CAVE_VINES_PLANT)) {
                return true;
            }
            return false;
        }
        return false;
    }

}
