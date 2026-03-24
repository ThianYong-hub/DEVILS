package com.example.addon.modules.spearspoof;

import com.example.addon.modules.SpearSpoof;
import meteordevelopment.meteorclient.events.entity.player.PlayerMoveEvent;
import meteordevelopment.meteorclient.mixininterface.IVec3d;
import meteordevelopment.meteorclient.settings.Setting;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;


abstract class SpearSpoofFlightMovePhase2 extends SpearSpoofFlightFlowB {
    protected SpearSpoofFlightMovePhase2(
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

    protected void onMovePhase2(
        PlayerMoveEvent event,
        long now,
        LivingEntity target,
        Vec3d playerPos,
        Vec3d targetPos,
        Vec3d steerPos,
        long strikeDelayMs,
        double targetDistance,
        double engageMin,
        double engageMax,
        double hardKeepOut,
        double softKeepOut,
        double activeHardKeepOut,
        double activeSoftKeepOut,
        double targetDeltaY,
        boolean diveToLowerTarget,
        boolean caveEscapeNeeded,
        boolean clearHorizontalLane,
        boolean horizontalBlocked,
        boolean routeToTargetBlocked,
        boolean targetPitDetected,
        boolean targetPitLocked,
        boolean verticalRouteMode,
        String verticalRouteStage,
        boolean pitVerticalMode,
        boolean pureVerticalAscent,
        boolean pureVerticalAlign,
        boolean pureVerticalDive,
        boolean pitAxisLock,
        boolean smallTarget,
        boolean playerTarget,
        boolean largeGroundTarget,
        boolean awaitingHitConfirm,
        boolean mode4xHorizontal,
        boolean mode4xActive,
        boolean forceUntilDamageMode,
        boolean hitPierceWindow,
        Vec3d velocity,
        double maxHorizontal,
        double maxVertical
    ) {
        Vec3d baseVelocity;
        Vec3d avoidanceVelocity;
        if (pitVerticalMode && "runup".equals(verticalRouteStage)) {
            Vec3d runupDir = normalizeOrFallback(horizontal(steerPos.subtract(playerPos)), horizontal(runtime.lockedApproachDirection));
            double runupFloor = Math.min(maxHorizontal, Math.max(2.2, getCruiseHorizontalSpeed()));
            velocity = enforceHorizontalSpeedFloor(velocity, runupDir, runupFloor);
        }
        if (pitAxisLock) {
            Vec3d axisVec = horizontal(targetPos.subtract(playerPos));
            double axisDist = axisVec.length();
            if (axisDist < 0.12) {
                velocity = new Vec3d(0.0, 0.0, 0.0);
            } else {
                Vec3d axisDir = axisVec.multiply(1.0 / axisDist);
                double lockSpeed = MathHelper.clamp(axisDist * 0.95, PIT_AXIS_LOCK_MIN_SPEED, PIT_AXIS_LOCK_MAX_SPEED);
                velocity = new Vec3d(axisDir.x * lockSpeed, MathHelper.clamp(velocity.y, -0.14, 0.14), axisDir.z * lockSpeed);
            }
        } else if (pureVerticalAscent) {
            double ascent = Math.min(maxVertical, Math.max(PIT_VERTICAL_ASCENT_MIN, steerPos.y - playerPos.y));
            velocity = new Vec3d(0.0, ascent, 0.0);
        } else if (pureVerticalAlign) {
            double yDist = steerPos.y - playerPos.y;
            if (Math.abs(yDist) < 0.03) velocity = Vec3d.ZERO;
            else {
                double minStep = Math.min(maxVertical, PLAIN_ALIGN_VERTICAL_MIN);
                double yStep = Math.signum(yDist) * Math.min(maxVertical, Math.max(minStep, Math.abs(yDist)));
                velocity = new Vec3d(0.0, yStep, 0.0);
            }
        } else if (pureVerticalDive) {
            double descent = Math.min(maxVertical, Math.max(PIT_VERTICAL_DESCENT_MIN, Math.abs(targetDeltaY) * 0.40));
            velocity = new Vec3d(0.0, -descent, 0.0);
        } else {
            baseVelocity = new Vec3d(velocity.x, applyVerticalSafety(velocity.y, playerPos, maxVertical), velocity.z);
            avoidanceVelocity = obstacleAvoidance.get()
                ? computeLocalAvoidance(steerPos, baseVelocity, maxHorizontal, maxVertical)
                : Vec3d.ZERO;
            velocity = applyDimensionFlightLimit(applyEmergencyBraking(baseVelocity.add(avoidanceVelocity)), playerPos);
        }
        double hSpeed = horizontal(velocity).length();
        Vec3d laneDirection = mode4xHorizontal
            ? mode4xAxis(normalizeOrFallback(runtime.lockedApproachDirection, horizontal(module.client().player.getRotationVector()).multiply(-1.0)))
            : normalizeOrFallback(runtime.lockedApproachDirection, horizontal(module.client().player.getRotationVector()).multiply(-1.0));

        if (mode4xHorizontal && runtime.passPhase == SpearSpoofRuntime.PassPhase.APPROACH && !forceUntilDamageMode) {
            Vec3d toTargetNow = horizontal(targetPos.subtract(playerPos));
            double toTargetLen = toTargetNow.length();
            if (toTargetLen > 1.0E-6) {
                Vec3d toTargetDir = toTargetNow.multiply(1.0 / toTargetLen);
                double maxStep = Math.max(0.0, toTargetLen - MODE_4X_APPROACH_DISTANCE + MODE_4X_APPROACH_STEP_EPS);
                double currentStep = horizontal(velocity).length();
                if (currentStep > maxStep) {
                    if (maxStep <= 1.0E-4) velocity = new Vec3d(0.0, velocity.y, 0.0);
                    else velocity = new Vec3d(toTargetDir.x * maxStep, velocity.y, toTargetDir.z * maxStep);
                    hSpeed = horizontal(velocity).length();
                }
            }
        }

        if (!pitVerticalMode && !pureVerticalAscent && !pureVerticalAlign && !forceUntilDamageMode) {
            Vec3d toTargetNow = horizontal(targetPos.subtract(playerPos));
            double toTargetLen = toTargetNow.length();
            if (toTargetLen > 1.0E-6) {
                Vec3d toTargetDir = toTargetNow.multiply(1.0 / toTargetLen);
                Vec3d moveDir = normalizeOrFallback(horizontal(velocity), horizontal(module.client().player.getVelocity()));
                double movingTowardDot = moveDir.dotProduct(toTargetDir);
                boolean passThroughDetected = runtime.passPhase == SpearSpoofRuntime.PassPhase.APPROACH
                    && targetDistance <= PASS_THROUGH_REVERSE_DISTANCE
                    && movingTowardDot <= PASS_THROUGH_REVERSE_DOT;
                if (passThroughDetected) {
                    Vec3d retreat = resolveRetreatDirection(playerPos, targetPos, laneDirection, mode4xHorizontal);
                    runtime.resetDirection = retreat;
                    long holdMs = playerTarget ? PASS_THROUGH_RESET_HOLD_MS_PLAYER : PASS_THROUGH_RESET_HOLD_MS_OTHER;
                    beginResetWithTrace(now, holdMs, "pass-through-reverse dot=" + format2(movingTowardDot), target);
                    double retreatSpeed = Math.max(hSpeed, getCruiseHorizontalSpeed() * 1.10);
                    velocity = new Vec3d(retreat.x * retreatSpeed, Math.max(velocity.y, 0.05), retreat.z * retreatSpeed);
                    hSpeed = horizontal(velocity).length();
                }
            }
        }

        if (hitPierceWindow && !pureVerticalAscent && !pureVerticalAlign) {
            Vec3d pierceDir = normalizeOrFallback(horizontal(targetPos.subtract(playerPos)), horizontal(velocity));
            double pierceSpeed = Math.max(horizontal(velocity).length(), getCruiseHorizontalSpeed() * HIT_PIERCE_SPEED_FACTOR);
            velocity = enforceHorizontalSpeedFloor(velocity, pierceDir, pierceSpeed);
            if (pitVerticalMode && playerPos.y > targetPos.y + 0.06) {
                double descend = Math.min(maxVertical, Math.max(PIT_VERTICAL_DESCENT_MIN, Math.abs(targetDeltaY) * 0.45));
                velocity = new Vec3d(velocity.x, -descend, velocity.z);
            }
        }

        if (!hitPierceWindow && pitVerticalMode && targetDistance <= activeHardKeepOut + 0.02) {
            velocity = new Vec3d(0.0, Math.max(velocity.y, PIT_VERTICAL_ASCENT_MIN), 0.0);
            boolean emergency = targetDistance < activeHardKeepOut - 0.28;
            if (runtime.passPhase != SpearSpoofRuntime.PassPhase.RESET && (!awaitingHitConfirm || emergency)) {
                beginResetWithTrace(now, smallTarget ? SMALL_RESET_HOLD_MS : DEFAULT_RESET_HOLD_MS, "pit-vertical too-close", target);
            }
        } else if (!hitPierceWindow && !pureVerticalAscent && !pureVerticalAlign && targetDistance <= engageMin + 0.02) {
            Vec3d retreat = resolveRetreatDirection(playerPos, targetPos, laneDirection, mode4xHorizontal);
            double retreatSpeed = Math.max(hSpeed, getCruiseHorizontalSpeed());
            velocity = new Vec3d(retreat.x * retreatSpeed, Math.max(velocity.y, 0.05), retreat.z * retreatSpeed);
            boolean emergency = targetDistance < hardKeepOut - 0.28;
            if (runtime.passPhase != SpearSpoofRuntime.PassPhase.RESET && (!awaitingHitConfirm || emergency)) {
                runtime.resetDirection = retreat;
                beginResetWithTrace(now, smallTarget ? SMALL_RESET_HOLD_MS : DEFAULT_RESET_HOLD_MS, "too-close engageMin", target);
            }
        }

        double predictedDistance = distanceFromPointToHitbox(playerPos.add(velocity), target);
        if (mode4xHorizontal
            && !hitPierceWindow
            && runtime.passPhase == SpearSpoofRuntime.PassPhase.APPROACH
            && !forceUntilDamageMode) {
            boolean floorBreachedNow = targetDistance < MODE_4X_MIN_RANGE - 0.02;
            boolean floorBreachedPred = predictedDistance < MODE_4X_MIN_RANGE - 0.08
                && targetDistance <= MODE_4X_APPROACH_DISTANCE + 0.10;
            if (floorBreachedNow || floorBreachedPred) {
                Vec3d retreat = resolveRetreatDirection(playerPos, targetPos, laneDirection, true);
                runtime.resetDirection = retreat;
                double retreatSpeed = Math.max(hSpeed, getCruiseHorizontalSpeed());
                velocity = new Vec3d(retreat.x * retreatSpeed, 0.0, retreat.z * retreatSpeed);
                hSpeed = horizontal(velocity).length();
                beginResetWithTrace(
                    now,
                    smallTarget ? SMALL_RESET_HOLD_MS : DEFAULT_RESET_HOLD_MS,
                    floorBreachedNow ? "mode4x-floor-now" : "mode4x-floor-pred",
                    target
                );
                predictedDistance = distanceFromPointToHitbox(playerPos.add(velocity), target);
            }
        }
        double distGuard = Math.min(targetDistance, predictedDistance);
        if (!hitPierceWindow && pitVerticalMode && distGuard < activeSoftKeepOut) {
            velocity = new Vec3d(0.0, Math.max(velocity.y, PIT_VERTICAL_ASCENT_MIN), 0.0);
            boolean emergency = targetDistance < activeHardKeepOut - 0.34
                || (!awaitingHitConfirm && predictedDistance < activeHardKeepOut - 0.34);
            if (runtime.passPhase != SpearSpoofRuntime.PassPhase.RESET && (!awaitingHitConfirm || emergency)) {
                beginResetWithTrace(now, smallTarget ? 150L : 180L, "pit-vertical soft-keep-out", target);
            }
        } else if (!hitPierceWindow && !pureVerticalAscent && !pureVerticalAlign && distGuard < softKeepOut) {
            Vec3d away = resolveRetreatDirection(playerPos, targetPos, laneDirection, mode4xHorizontal);
            double urgency = MathHelper.clamp((softKeepOut - distGuard) / Math.max(0.01, softKeepOut - hardKeepOut), 0.0, 1.0);
            Vec3d awayVel = away.multiply(Math.max(hSpeed, getCruiseHorizontalSpeed() * (0.72 + urgency * 0.28)));
            if (mode4xHorizontal) {
                // 4X: no blended side drift near keep-out. Retreat strictly on cycle axis.
                velocity = new Vec3d(awayVel.x, Math.max(velocity.y, 0.03 + urgency * 0.08), awayVel.z);
            } else {
                double mix = 0.30 + urgency * 0.40;
                velocity = new Vec3d(
                    MathHelper.lerp(mix, velocity.x, awayVel.x),
                    Math.max(velocity.y, 0.03 + urgency * 0.08),
                    MathHelper.lerp(mix, velocity.z, awayVel.z)
                );
            }
            boolean hardRisk = targetDistance <= hardKeepOut - 0.02
                || (!awaitingHitConfirm && predictedDistance < hardKeepOut - 0.02);
            boolean emergency = targetDistance < hardKeepOut - 0.34
                || (!awaitingHitConfirm && predictedDistance < hardKeepOut - 0.34);
            if (hardRisk && runtime.passPhase != SpearSpoofRuntime.PassPhase.RESET && (!awaitingHitConfirm || emergency)) {
                runtime.resetDirection = away;
                beginResetWithTrace(now, smallTarget ? 150L : 180L, "soft-keep-out hard-risk", target);
            }
        }

        if (!hitPierceWindow && pitVerticalMode && (targetDistance < activeHardKeepOut || predictedDistance < activeHardKeepOut)) {
            velocity = new Vec3d(0.0, Math.max(velocity.y, PIT_VERTICAL_ASCENT_MIN), 0.0);
            boolean emergency = targetDistance < activeHardKeepOut - 0.34
                || (!awaitingHitConfirm && predictedDistance < activeHardKeepOut - 0.34);
            if (runtime.passPhase != SpearSpoofRuntime.PassPhase.RESET && (!awaitingHitConfirm || emergency)) {
                beginResetWithTrace(now, smallTarget ? 150L : 180L, "pit-vertical hard-keep-out", target);
            }
        } else if (!hitPierceWindow && !pureVerticalAscent && !pureVerticalAlign && (targetDistance < hardKeepOut || predictedDistance < hardKeepOut)) {
            Vec3d away = resolveRetreatDirection(playerPos, targetPos, laneDirection, mode4xHorizontal);
            double retreatSpeed = Math.max(hSpeed, getCruiseHorizontalSpeed());
            velocity = new Vec3d(away.x * retreatSpeed, Math.max(velocity.y, 0.05), away.z * retreatSpeed);
            boolean emergency = targetDistance < hardKeepOut - 0.34
                || (!awaitingHitConfirm && predictedDistance < hardKeepOut - 0.34);
            if (runtime.passPhase != SpearSpoofRuntime.PassPhase.RESET && (!awaitingHitConfirm || emergency)) {
                runtime.resetDirection = away;
                beginResetWithTrace(now, smallTarget ? 150L : 180L, "hard-keep-out", target);
            }
        }

        tickStuckState(now, playerPos, velocity, targetPos, engageMin);
        if (!pitVerticalMode && !pureVerticalAscent && !pureVerticalAlign && runtime.unstuckUntilMs > now && !mode4xHorizontal) {
            Vec3d away = normalizeOrFallback(horizontal(playerPos.subtract(targetPos)), laneDirection);
            Vec3d toward = normalizeOrFallback(horizontal(targetPos.subtract(playerPos)), horizontal(module.client().player.getRotationVector()));
            Vec3d lateral = new Vec3d(-toward.z, 0.0, toward.x);
            double sign = ((now / 90L) & 1L) == 0L ? 1.0 : -1.0;
            double burstSpeed = Math.max(horizontalSpeed.get() * 1.22, hSpeed);

            if (caveEscapeNeeded) {
                Vec3d burstDir = normalizeOrFallback(toward.add(lateral.multiply(0.30 * sign)), toward);
                double climb = Math.min(verticalSpeed.get(), Math.max(0.24, Math.min(0.72, targetDeltaY * 0.34)));
                velocity = new Vec3d(burstDir.x * burstSpeed, Math.max(velocity.y, climb), burstDir.z * burstSpeed);
                beginResetWithTrace(now, 280L, "unstuck-climb", target);
            } else {
                Vec3d lateralAway = new Vec3d(-away.z, 0.0, away.x);
                Vec3d burstDir = normalizeOrFallback(away.add(lateralAway.multiply(0.38 * sign)), away);
                velocity = new Vec3d(burstDir.x * burstSpeed, Math.max(velocity.y, 0.14), burstDir.z * burstSpeed);
                beginResetWithTrace(now, 260L, "unstuck-burst", target);
            }
        }

        if (!pitVerticalMode && !pureVerticalAscent && !pureVerticalAlign) {
            velocity = applyVerticalStrategy(
                velocity,
                playerPos,
                steerPos,
                targetDistance,
                engageMax,
                diveToLowerTarget,
                caveEscapeNeeded
            );
        }

        if (!pitVerticalMode && !pureVerticalAscent && !pureVerticalAlign && runtime.unstuckUntilMs <= now) {
            double cruise = getCruiseHorizontalSpeed();
            double floor = runtime.passPhase == SpearSpoofRuntime.PassPhase.RESET ? cruise * RESET_CRUISE_FACTOR : cruise;
            Vec3d forcedDir = normalizeOrFallback(horizontal(steerPos.subtract(playerPos)), horizontal(velocity));
            velocity = enforceHorizontalSpeedFloor(velocity, forcedDir, floor);
        }

        if (!pitVerticalMode && !pureVerticalAscent && !pureVerticalAlign) {
            baseVelocity = new Vec3d(velocity.x, applyVerticalSafety(velocity.y, playerPos, maxVertical), velocity.z);
            avoidanceVelocity = obstacleAvoidance.get()
                && !mode4xHorizontal
                ? computeLocalAvoidance(steerPos, baseVelocity, maxHorizontal, maxVertical)
                : Vec3d.ZERO;
            velocity = applyDimensionFlightLimit(applyEmergencyBraking(baseVelocity.add(avoidanceVelocity)), playerPos);
        }

        if (pitVerticalMode && runtime.passPhase == SpearSpoofRuntime.PassPhase.RESET) {
            // In pit reset never mix horizontal + vertical (prevents diagonal "return up" arcs).
            velocity = new Vec3d(0.0, Math.max(velocity.y, PIT_VERTICAL_ASCENT_MIN), 0.0);
        }

        if (!pitVerticalMode && !pureVerticalAscent && !pureVerticalAlign && (clearHorizontalLane || mode4xHorizontal)) {
            velocity = new Vec3d(velocity.x, 0.0, velocity.z);
        }

        double horizontalVelocity = horizontal(velocity).length();
        if (horizontalVelocity > maxHorizontal && horizontalVelocity > 1.0E-6) {
            double scale = maxHorizontal / horizontalVelocity;
            velocity = new Vec3d(velocity.x * scale, velocity.y, velocity.z * scale);
        }

        double actualHoriz = horizontal(module.client().player.getVelocity()).length();
        boolean drag = isInDragState();
        boolean serverRejected = horizontalVelocity > 0.30 && actualHoriz < horizontalVelocity * 0.45;
        if (drag && serverRejected) runtime.antiCheatSlowTicks = Math.min(20, runtime.antiCheatSlowTicks + 1);
        else runtime.antiCheatSlowTicks = Math.max(0, runtime.antiCheatSlowTicks - 2);

        velocity = new Vec3d(velocity.x, MathHelper.clamp(velocity.y, -maxVertical, maxVertical), velocity.z);
        double outputHoriz = horizontal(velocity).length();
        double resetNeedUp = Double.isFinite(runtime.resetRequiredY)
            ? Math.max(0.0, runtime.resetRequiredY - playerPos.y)
            : 0.0;
        ((IVec3d) event.movement).meteor$set(velocity.x, velocity.y, velocity.z);
        debugLogger.logMove(
            "dist=" + format2(targetDistance)
                + " pred=" + format2(predictedDistance)
                + " guard=" + format2(distGuard)
                + " range=[" + format2(engageMin) + ".." + format2(engageMax) + "]"
                + " keep=[" + format2(activeHardKeepOut) + ".." + format2(activeSoftKeepOut) + "]"
                + " phase=" + runtime.passPhase.name()
                + " resetActive=" + runtime.isResetActive(now)
                + " resetNeedUp=" + format2(resetNeedUp)
                + " resetReqY=" + (Double.isFinite(runtime.resetRequiredY) ? format2(runtime.resetRequiredY) : "na")
                + " topDown=false"
                + " strikeDelayMs=" + strikeDelayMs
                + " hReq=" + format2(hSpeed)
                + " hOut=" + format2(outputHoriz)
                + " hActual=" + format2(actualHoriz)
                + " groundMode=" + largeGroundTarget
                + " yDelta=" + format2(targetDeltaY)
                + " dive=" + diveToLowerTarget
                + " caveEsc=" + caveEscapeNeeded
                + " vertRoute=" + verticalRouteMode
                + " vertStage=" + verticalRouteStage
                + " pitMode=" + pitVerticalMode
                + " pitDet=" + targetPitDetected
                + " pitLock=" + targetPitLocked
                + " mode4x=" + mode4xActive
                + " forceUntilDamage=" + forceUntilDamageMode
                + " pureAsc=" + pureVerticalAscent
                + " pureAlign=" + pureVerticalAlign
                + " pureDive=" + pureVerticalDive
                + " hBlocked=" + horizontalBlocked
                + " losBlocked=" + routeToTargetBlocked
                + " antiCheatSlowTicks=" + runtime.antiCheatSlowTicks
                + " stuck=" + runtime.stuckTicks
                + " awasp=true",
            target,
            runtime,
            velocity
        );
    }
}
