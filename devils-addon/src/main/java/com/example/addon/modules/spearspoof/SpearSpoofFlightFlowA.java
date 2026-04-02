package com.example.addon.modules.spearspoof;

import com.example.addon.modules.SpearSpoof;
import meteordevelopment.meteorclient.events.entity.player.PlayerMoveEvent;
import meteordevelopment.meteorclient.mixin.AbstractBlockAccessor;
import meteordevelopment.meteorclient.mixin.DirectionAccessor;
import meteordevelopment.meteorclient.mixininterface.IVec3d;
import meteordevelopment.meteorclient.settings.Setting;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;


abstract class SpearSpoofFlightFlowA extends SpearSpoofFlightContext {
    protected SpearSpoofFlightFlowA(SpearSpoof module, SpearSpoofRuntime runtime, SpearSpoofFlightPathfinder pathfinder, SpearSpoofTargetingService targeting, SpearSpoofCombatService combat, SpearSpoofDebugLogger debugLogger, Setting<Boolean> onlyWhileElytra, Setting<Boolean> attributeSwap, Setting<Double> minRange, Setting<Double> maxRange, Setting<Double> smallTargetRange, Setting<Double> horizontalSpeed, Setting<Double> verticalSpeed, Setting<Double> approachRange, Setting<Double> retreatRange, Setting<Boolean> topDownEnabled, Setting<Double> topDownHeight, Setting<Boolean> obstacleAvoidance, Setting<Boolean> autoRelaunch, Setting<Boolean> testFlyUntilDamage, Setting<Boolean> mode4x) {
        super(module, runtime, pathfinder, targeting, combat, debugLogger, onlyWhileElytra, attributeSwap, minRange, maxRange, smallTargetRange, horizontalSpeed, verticalSpeed, approachRange, retreatRange, topDownEnabled, topDownHeight, obstacleAvoidance, autoRelaunch, testFlyUntilDamage, mode4x);
    }

    protected void handleLostTargetMove(PlayerMoveEvent event, long now, boolean gliding, boolean inLiquid) {
        if (module.client().player == null || module.client().world == null) return;

        boolean hasSnapshot = runtime.lastKnownTargetSeenAtMs > 0L
            && (now - runtime.lastKnownTargetSeenAtMs) <= LOST_TARGET_FOLLOW_MS;
        if (onlyWhileElytra.get() && !gliding && !inLiquid) {
            if (!hasSnapshot) {
                applyIdleBrake(event);
                return;
            }

            Vec3d playerPos = module.client().player.getEntityPos();
            long ageMs = Math.max(0L, now - runtime.lastKnownTargetSeenAtMs);
            double predictTicks = MathHelper.clamp(ageMs / 50.0, 0.0, LOST_TARGET_MAX_PREDICT_TICKS);
            Vec3d predicted = runtime.lastKnownTargetPos.add(runtime.lastKnownTargetVel.multiply(predictTicks));
            Vec3d toward = normalizeOrFallback(horizontal(predicted.subtract(playerPos)), horizontal(module.client().player.getRotationVector()));
            if (module.client().player.isOnGround()) module.client().player.jump();
            double up = module.client().player.isOnGround() ? 0.42 : 0.24;
            Vec3d relaunchAssist = new Vec3d(toward.x * 0.95, up, toward.z * 0.95);
            ((IVec3d) event.movement).meteor$set(relaunchAssist.x, relaunchAssist.y, relaunchAssist.z);
            debugLogger.logMove("lost-target-relaunch ageMs=" + ageMs, null, runtime, relaunchAssist);
            return;
        }

        if (!hasSnapshot) {
            pathfinder.clearTargetState();
            applyIdleBrake(event);
            return;
        }

        Vec3d playerPos = module.client().player.getEntityPos();
        long ageMs = Math.max(0L, now - runtime.lastKnownTargetSeenAtMs);
        double predictTicks = MathHelper.clamp(ageMs / 50.0, 0.0, LOST_TARGET_MAX_PREDICT_TICKS);
        Vec3d predicted = runtime.lastKnownTargetPos.add(runtime.lastKnownTargetVel.multiply(predictTicks));
        Vec3d steer = predicted;

        if (obstacleAvoidance.get()) {
            Vec3d safe = pathfinder.adjustToSafeCorridor(predicted, playerPos);
            if (ageMs <= LOST_TARGET_PATHING_MS) {
                pathfinder.tickPathing(playerPos, safe, runtime.stuckTicks);
                steer = pathfinder.steerTarget(safe);
            } else {
                pathfinder.clearTargetState();
                steer = safe;
            }
        }

        Vec3d velocity = computeAutoWaspVelocity(playerPos, steer);
        double maxHorizontal = resolveHorizontalCap(horizontalSpeed.get());
        double maxVertical = resolveVerticalCap();

        Vec3d base = new Vec3d(velocity.x, applyVerticalSafety(velocity.y, playerPos, maxVertical), velocity.z);
        Vec3d avoid = obstacleAvoidance.get()
            ? computeLocalAvoidance(steer, base, maxHorizontal, maxVertical)
            : Vec3d.ZERO;
        Vec3d out = applyDimensionFlightLimit(applyEmergencyBraking(base.add(avoid)), playerPos);
        out = new Vec3d(out.x * LOST_TARGET_FOLLOW_DAMPING, out.y, out.z * LOST_TARGET_FOLLOW_DAMPING);

        double horizontalVelocity = horizontal(out).length();
        if (horizontalVelocity > maxHorizontal && horizontalVelocity > 1.0E-6) {
            double scale = maxHorizontal / horizontalVelocity;
            out = new Vec3d(out.x * scale, out.y, out.z * scale);
        }

        out = new Vec3d(out.x, MathHelper.clamp(out.y, -maxVertical, maxVertical), out.z);
        ((IVec3d) event.movement).meteor$set(out.x, out.y, out.z);
        debugLogger.logMove("lost-target-follow ageMs=" + ageMs, null, runtime, out);
    }

    protected void applyIdleBrake(PlayerMoveEvent event) {
        Vec3d current = module.client().player.getVelocity();
        Vec3d damped = new Vec3d(
            current.x * LOST_TARGET_IDLE_BRAKE_HORIZONTAL,
            MathHelper.clamp(current.y * LOST_TARGET_IDLE_BRAKE_VERTICAL, -0.26, 0.26),
            current.z * LOST_TARGET_IDLE_BRAKE_HORIZONTAL
        );
        ((IVec3d) event.movement).meteor$set(damped.x, damped.y, damped.z);
    }

    protected void updatePassPhase(LivingEntity target, long strikeDelayMs) {
        long now = System.currentTimeMillis();
        double distance = distanceFromPointToHitbox(module.client().player.getEntityPos(), target);
        double engageMin = getEngageMinRange(target);
        double engageMax = getEngageMaxRange(target);
        boolean smallTarget = isSmallTarget(target);
        double desiredRetreat = desiredRetreatDistance(target, engageMin, engageMax, now);
        boolean pitResetMode = runtime.pitVerticalLockTargetId == target.getId() && now < runtime.pitVerticalLockUntilMs;
        boolean awaitingHitConfirm = runtime.isAwaitingHitConfirm(target, now);
        boolean forceUntilDamageMode = testFlyUntilDamage.get()
            && awaitingHitConfirm
            && runtime.hitConfirmTargetId == target.getId();

        if (distance <= engageMin + 0.02 && runtime.passPhase != SpearSpoofRuntime.PassPhase.RESET) {
            runtime.resetDirection = normalizeOrFallback(
                horizontal(module.client().player.getEntityPos().subtract(target.getEntityPos())),
                runtime.lockedApproachDirection
            );
            boolean emergency = distance < engageMin - 0.12;
            if ((!awaitingHitConfirm || emergency) && !forceUntilDamageMode) {
                beginResetWithTrace(now, smallTarget ? SMALL_RESET_HOLD_MS : DEFAULT_RESET_HOLD_MS, "update-phase too-close", target);
            }
        }

        if (runtime.passPhase == SpearSpoofRuntime.PassPhase.RESET) {
            if (pitResetMode && !Double.isFinite(runtime.resetRequiredY)) {
                runtime.resetStartY = module.client().player.getY();
                double verticalRetreat = currentResetVerticalRetreatBlocks(now);
                runtime.resetRequiredY = Math.max(
                    runtime.resetStartY + verticalRetreat,
                    target.getY() + verticalRetreat
                );
            } else if (!pitResetMode) {
                runtime.resetStartY = Double.NaN;
                runtime.resetRequiredY = Double.NaN;
            }

            long resetAge = now - runtime.passPhaseStartMs;
            long minDwell = smallTarget ? 100L : RESET_MIN_DWELL_MS;
            boolean minDwellMet = resetAge >= minDwell;
            boolean farEnough = distance >= desiredRetreat;
            boolean timeout = now >= runtime.movementResetUntilMs;
            boolean strikeReady = strikeDelayMs <= 0L;
            boolean climbedEnough = !Double.isFinite(runtime.resetRequiredY)
                || module.client().player.getY() >= runtime.resetRequiredY - RESET_VERTICAL_RETREAT_EPS;
            boolean climbFailsafe = resetAge >= RESET_VERTICAL_RETREAT_FAILSAFE_MS;
            boolean climbGateOpen = climbedEnough || climbFailsafe;
            if (minDwellMet && farEnough && strikeReady && climbGateOpen) {
                toApproachWithTrace(now, "reset-exit retreat+strike-ready", target);
            } else if (minDwellMet && farEnough && timeout && climbGateOpen) {
                toApproachWithTrace(now, "reset-exit retreat+timeout", target);
            }
        } else if (distance > engageMax + 0.25) {
            toApproachWithTrace(now, "approach-maintain out-of-range", target);
        } else if (strikeDelayMs > 220L && distance < engageMin + 0.06) {
            runtime.resetDirection = normalizeOrFallback(
                horizontal(module.client().player.getEntityPos().subtract(target.getEntityPos())),
                runtime.lockedApproachDirection
            );
            long holdMs = Math.min(smallTarget ? 150L : 180L, strikeDelayMs);
            boolean emergency = distance < engageMin - 0.12;
            if ((!awaitingHitConfirm || emergency) && !forceUntilDamageMode) {
                beginResetWithTrace(now, holdMs, "update-phase strike-delay-close", target);
            }
        }
    }

    protected void beginResetWithTrace(long now, long holdMs, String reason, LivingEntity target) {
        boolean changed = runtime.passPhase != SpearSpoofRuntime.PassPhase.RESET;
        if (changed) {
            boolean pitResetMode = target != null && (
                (runtime.pitVerticalLockTargetId == target.getId() && now < runtime.pitVerticalLockUntilMs)
                    || isTargetPitMode(target.getEntityPos(), target)
            );
            if (pitResetMode && module.client().player != null) {
                runtime.resetStartY = module.client().player.getEntityPos().y;
                double targetY = target != null ? target.getY() : runtime.resetStartY;
                double verticalRetreat = currentResetVerticalRetreatBlocks(now);
                runtime.resetRequiredY = Math.max(
                    runtime.resetStartY + verticalRetreat,
                    targetY + verticalRetreat
                );
            } else {
                runtime.resetStartY = Double.NaN;
                runtime.resetRequiredY = Double.NaN;
            }
        }
        runtime.beginReset(now, holdMs);
        if (changed) debugLogger.logPhaseChange("Phase->RESET", reason + " holdMs=" + holdMs, target, runtime);
    }

    protected void toApproachWithTrace(long now, String reason, LivingEntity target) {
        boolean changed = runtime.passPhase != SpearSpoofRuntime.PassPhase.APPROACH;
        runtime.toApproach(now);
        if (changed) debugLogger.logPhaseChange("Phase->APPROACH", reason, target, runtime);
    }

    protected double getEngageMinRange(LivingEntity target) {
        double base = isSmallTarget(target) ? SMALL_TARGET_MIN_RANGE : ENFORCED_MIN_RANGE;
        if (mode4x.get() && target != null) base = Math.max(base, MODE_4X_MIN_RANGE);
        return base;
    }

    protected double getEngageMaxRange(LivingEntity target) {
        if (mode4x.get() && target != null) return Math.min(ENFORCED_MAX_RANGE, MODE_4X_MAX_RANGE);
        return ENFORCED_MAX_RANGE;
    }

    protected double desiredRetreatDistance(LivingEntity target, double engageMin, double engageMax, long now) {
        if (mode4x.get() && target != null) {
            double cycleRetreat = runtime.hitChain > 0
                ? MODE_4X_APPROACH_DISTANCE + MODE_4X_POST_HIT_RETREAT
                : MODE_4X_RUNUP_DISTANCE;
            double minCycleRetreat = Math.max(engageMin + 0.35, MODE_4X_APPROACH_DISTANCE + 0.35);
            return MathHelper.clamp(cycleRetreat, minCycleRetreat, 16.0);
        }

        if (isSmallTarget(target)) {
            double base = Math.max(SMALL_RESET_RETREAT_DISTANCE, engageMin + 0.80);
            if (runtime.rechargeRebuildUntilMs > now) {
                base = Math.max(base, SMALL_RECHARGE_RETREAT_DISTANCE);
            }
            return MathHelper.clamp(base, engageMin + 0.60, MAX_RESET_RETREAT_DISTANCE);
        }
        double base = Math.max(RESET_RETREAT_DISTANCE, engageMin + 0.95);
        if (runtime.rechargeRebuildUntilMs > now) {
            base = Math.max(base, RECHARGE_RETREAT_DISTANCE);
        }
        return MathHelper.clamp(base, engageMin + 0.75, MAX_RESET_RETREAT_DISTANCE);
    }

    protected Vec3d mode4xAxis(Vec3d fallback) {
        if (runtime.resetDirection.lengthSquared() > 1.0E-6) return runtime.resetDirection.normalize();
        return normalizeOrFallback(runtime.lockedApproachDirection, fallback);
    }

    protected Vec3d resolveRetreatDirection(Vec3d playerPos, Vec3d targetPos, Vec3d fallback, boolean lock4xAxis) {
        Vec3d away = normalizeOrFallback(horizontal(playerPos.subtract(targetPos)), fallback);
        if (!lock4xAxis) return away;

        Vec3d axis = mode4xAxis(away);
        // If the cached axis points into target due stale state, re-align once.
        if (axis.dotProduct(away) < -0.25) axis = away;
        return normalizeOrFallback(axis, away);
    }

    protected double currentResetVerticalRetreatBlocks(long now) {
        if (runtime.rechargeRebuildUntilMs > now) return RESET_VERTICAL_RETREAT_BLOCKS_RECHARGE;
        return RESET_VERTICAL_RETREAT_BLOCKS;
    }

    protected Vec3d applyVerticalStrategy(
        Vec3d velocity,
        Vec3d playerPos,
        Vec3d targetPos,
        double targetDistance,
        double engageMax,
        boolean diveToLowerTarget,
        boolean caveEscapeNeeded
    ) {
        if (velocity == null || playerPos == null || targetPos == null) return velocity;

        double y = velocity.y;
        double targetDeltaY = targetPos.y - playerPos.y;

        if (diveToLowerTarget && targetDistance <= engageMax + 1.6) {
            double descend = Math.min(verticalSpeed.get(), Math.max(0.24, Math.min(0.72, Math.abs(targetDeltaY) * 0.40)));
            y = Math.min(y, -descend);
        }

        if (caveEscapeNeeded) {
            Vec3d toward = normalizeOrFallback(horizontal(targetPos.subtract(playerPos)), horizontal(velocity));
            double h = Math.max(horizontal(velocity).length(), getCruiseHorizontalSpeed());
            double climb = Math.min(verticalSpeed.get(), Math.max(0.24, Math.min(0.72, targetDeltaY * 0.34)));
            return new Vec3d(toward.x * h, Math.max(y, climb), toward.z * h);
        }

        return new Vec3d(velocity.x, y, velocity.z);
    }

    // AutoWasp movement vector calculation copied 1:1 semantics.
    protected Vec3d computeAutoWaspVelocity(Vec3d playerPos, Vec3d targetPos) {
        double xVel = 0, yVel = 0, zVel = 0;

        double xDist = targetPos.getX() - playerPos.getX();
        double zDist = targetPos.getZ() - playerPos.getZ();

        double absX = Math.abs(xDist);
        double absZ = Math.abs(zDist);

        double diag = 0;
        if (absX > 1.0E-5F && absZ > 1.0E-5F) diag = 1 / Math.sqrt(absX * absX + absZ * absZ);

        if (absX > 1.0E-5F) {
            if (absX < horizontalSpeed.get()) xVel = xDist;
            else xVel = horizontalSpeed.get() * Math.signum(xDist);
            if (diag != 0) xVel *= (absX * diag);
        }

        if (absZ > 1.0E-5F) {
            if (absZ < horizontalSpeed.get()) zVel = zDist;
            else zVel = horizontalSpeed.get() * Math.signum(zDist);
            if (diag != 0) zVel *= (absZ * diag);
        }

        double yDist = targetPos.getY() - playerPos.getY();
        if (Math.abs(yDist) > 1.0E-5F) {
            if (Math.abs(yDist) < verticalSpeed.get()) yVel = yDist;
            else yVel = verticalSpeed.get() * Math.signum(yDist);
        }

        return new Vec3d(xVel, yVel, zVel);
    }

    // AutoWasp avoid-landing check copied 1:1 semantics.
    protected Vec3d applyAutoWaspAvoidLanding(Vec3d targetPos, LivingEntity target) {
        if (target == null || module.client().world == null) return targetPos;
        if (target instanceof PlayerEntity) return targetPos;

        double d = target.getBoundingBox().getLengthX() / 2.0;
        for (Direction dir : DirectionAccessor.meteor$getHorizontal()) {
            BlockPos pos = BlockPos.ofFloored(targetPos.offset(dir, d).offset(dir.rotateYClockwise(), d)).down();
            if (((AbstractBlockAccessor) module.client().world.getBlockState(pos).getBlock()).meteor$isCollidable()
                && Math.abs(targetPos.getY() - (pos.getY() + 1)) <= 0.25) {
                return new Vec3d(targetPos.x, pos.getY() + 1.25, targetPos.z);
            }
        }

        return targetPos;
    }

    protected boolean isOpenSkyNear(Vec3d position) {
        if (position == null || module.client().world == null) return true;

        BlockPos center = BlockPos.ofFloored(position.x, position.y + 1.0, position.z);
        if (module.client().world.isSkyVisible(center)) return true;
        for (Direction direction : DirectionAccessor.meteor$getHorizontal()) {
            if (module.client().world.isSkyVisible(center.offset(direction))) return true;
        }
        return false;
    }

    protected boolean isTargetPitMode(Vec3d targetPos, LivingEntity target) {
        if (targetPos == null || target == null || module.client().world == null || module.client().player == null) return false;

        // Pit/cave signature: poor sky visibility, low lateral openings, or close hard walls around target.
        Vec3d sample = new Vec3d(
            targetPos.x,
            target.getY() + Math.max(0.35, target.getHeight() * 0.40),
            targetPos.z
        );
        boolean skyOpen = isOpenSkyNear(sample);
        int openings = pathfinder.countLateralOpenings(sample, 1.05);
        double ceiling = pathfinder.distanceToSolidAbove(sample, 6);
        int wallsNear = countHardWallsAround(sample, 1.00);
        int wallsFar = countHardWallsAround(sample, 1.45);
        boolean wallPit = wallsNear >= 3 || wallsFar >= 4;
        boolean basinPit = isTerrainBasin(targetPos, target);
        boolean foliageCanopy = hasFoliageCanopy(targetPos, target);
        double belowBy = module.client().player.getY() - target.getY();

        // Tree canopy / leaves overhead is not a cave/pit condition by itself.
        if (foliageCanopy && !wallPit && !basinPit) return false;

        if (!skyOpen) {
            // Under terrain roof: treat as pit only when target is meaningfully below us
            // or strongly enclosed by walls.
            return belowBy >= 1.8 || wallPit || basinPit || ceiling < 2.2;
        }

        // Sky-open pits (craters/ditches): basin + walls/openings should still trigger vertical mode.
        if (basinPit) return true;
        if (wallsNear >= 2 && openings <= 3 && belowBy >= 0.6) return true;
        if (belowBy < 0.8) return false;
        if (wallPit && openings <= 4) return true;
        return openings <= 2 && belowBy >= 1.2;
    }

    protected boolean isTerrainBasin(Vec3d targetPos, LivingEntity target) {
        if (targetPos == null || target == null) return false;

        double targetProbeY = target.getY() + 1.2;
        Vec3d targetProbe = new Vec3d(targetPos.x, targetProbeY, targetPos.z);
        double targetFloorY = targetProbe.y - pathfinder.distanceToSolidBelow(targetProbe, 12);

        double invSqrt2 = 0.70710678118;
        double[][] dirs = new double[][] {
            {1.0, 0.0},
            {-1.0, 0.0},
            {0.0, 1.0},
            {0.0, -1.0},
            {invSqrt2, invSqrt2},
            {invSqrt2, -invSqrt2},
            {-invSqrt2, invSqrt2},
            {-invSqrt2, -invSqrt2}
        };

        int raisedRimSamples = 0;
        for (double[] dir : dirs) {
            Vec3d rimProbe = new Vec3d(
                targetPos.x + dir[0] * PIT_BASIN_SAMPLE_RADIUS,
                targetProbeY + 1.0,
                targetPos.z + dir[1] * PIT_BASIN_SAMPLE_RADIUS
            );
            double rimFloorY = rimProbe.y - pathfinder.distanceToSolidBelow(rimProbe, 14);
            if (rimFloorY - targetFloorY >= PIT_BASIN_MIN_RIM_DELTA) raisedRimSamples++;
        }

        return raisedRimSamples >= PIT_BASIN_REQUIRED_RIM_SAMPLES;
    }

    protected int countHardWallsAround(Vec3d center, double radius) {
        if (center == null || module.client().world == null) return 0;
        double bodyY = center.y;
        double headY = center.y + 1.0;
        double invSqrt2 = 0.70710678118;
        double[][] offsets = new double[][] {
            {1.0, 0.0},
            {-1.0, 0.0},
            {0.0, 1.0},
            {0.0, -1.0},
            {invSqrt2, invSqrt2},
            {invSqrt2, -invSqrt2},
            {-invSqrt2, invSqrt2},
            {-invSqrt2, -invSqrt2}
        };

        int walls = 0;
        for (double[] o : offsets) {
            double x = center.x + o[0] * radius;
            double z = center.z + o[1] * radius;
            if (isHardBlocking(x, bodyY, z) || isHardBlocking(x, headY, z)) walls++;
        }
        return walls;
    }


    protected abstract boolean isHorizontalRouteBlocked(Vec3d playerPos, Vec3d targetPos, LivingEntity target);
    protected abstract boolean isVerticalColumnBlocked(Vec3d playerPos, Vec3d targetPos);
    protected abstract Vec3d selectCanopyBypassAnchor(Vec3d playerPos, Vec3d targetPos);
    protected abstract boolean hasFoliageCanopy(Vec3d targetPos, LivingEntity target);
    protected abstract boolean isHardBlocking(double x, double y, double z);
    protected abstract boolean isSmallTarget(LivingEntity living);
    protected abstract boolean isGroundedLargeTarget(LivingEntity living);
    protected abstract double probeClearDistance(Vec3d start, Vec3d dir, double maxDistance);
    protected abstract void ensureElytraRelaunch();
    protected abstract Vec3d anchorAtBodyHead(Vec3d predictedTargetPos, LivingEntity target);
    protected abstract Vec3d clampPlayerGroundLane(Vec3d targetPos, LivingEntity target);
    protected abstract double distanceFromPointToHitbox(Vec3d point, LivingEntity living);
    protected abstract Vec3d applyDimensionFlightLimit(Vec3d velocity, Vec3d currentPos);
    protected abstract Vec3d applyEmergencyBraking(Vec3d velocity);
    protected abstract Vec3d computeLocalAvoidance(Vec3d steerTarget, Vec3d baseVelocity, double horizontalCap, double maxVertical);
    protected abstract boolean isInDragState();
    protected abstract void tickStuckState(long now, Vec3d playerPos, Vec3d desiredVelocity, Vec3d targetPos, double engageMin);
    protected abstract double resolveHorizontalCap(double baseCap);
    protected abstract double resolveVerticalCap();
    protected abstract Vec3d enforceHorizontalSpeedFloor(Vec3d velocity, Vec3d direction, double floor);
    protected abstract double applyVerticalSafety(double yVel, Vec3d currentPos, double maxVertical);
    protected abstract double getCruiseHorizontalSpeed();

    protected static String format2(double value) {
        return String.format(java.util.Locale.US, "%.2f", value);
    }

    protected static Vec3d horizontal(Vec3d value) {
        return new Vec3d(value.x, 0.0, value.z);
    }

    protected static Vec3d normalizeOrFallback(Vec3d value, Vec3d fallback) {
        if (value != null && value.lengthSquared() > 1.0E-6) return value.normalize();
        if (fallback != null && fallback.lengthSquared() > 1.0E-6) return fallback.normalize();
        return Vec3d.ZERO;
    }
}
