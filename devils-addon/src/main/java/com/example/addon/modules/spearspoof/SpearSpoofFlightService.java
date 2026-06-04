package com.example.addon.modules.spearspoof;

import com.example.addon.modules.SpearSpoof;
import meteordevelopment.meteorclient.events.entity.player.PlayerMoveEvent;
import meteordevelopment.meteorclient.mixininterface.IVec3d;
import meteordevelopment.meteorclient.settings.Setting;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;


public final class SpearSpoofFlightService extends SpearSpoofFlightMovePhase2 {
    public SpearSpoofFlightService(SpearSpoof module, SpearSpoofRuntime runtime, SpearSpoofFlightPathfinder pathfinder, SpearSpoofTargetingService targeting, SpearSpoofCombatService combat, SpearSpoofDebugLogger debugLogger, Setting<Boolean> onlyWhileElytra, Setting<Boolean> attributeSwap, Setting<Double> minRange, Setting<Double> maxRange, Setting<Double> smallTargetRange, Setting<Double> horizontalSpeed, Setting<Double> verticalSpeed, Setting<Double> approachRange, Setting<Double> retreatRange, Setting<Boolean> topDownEnabled, Setting<Double> topDownHeight, Setting<Boolean> obstacleAvoidance, Setting<Boolean> autoRelaunch, Setting<Boolean> testFlyUntilDamage, Setting<Boolean> mode4x) {
        super(module, runtime, pathfinder, targeting, combat, debugLogger, onlyWhileElytra, attributeSwap, minRange, maxRange, smallTargetRange, horizontalSpeed, verticalSpeed, approachRange, retreatRange, topDownEnabled, topDownHeight, obstacleAvoidance, autoRelaunch, testFlyUntilDamage, mode4x);
    }

    public void onMove(PlayerMoveEvent event) {
        if (attributeSwap.get()) return;
        if (module.client().player == null || module.client().world == null) return;

        long now = System.currentTimeMillis();
        boolean gliding = module.client().player.isGliding();
        boolean inLiquid = module.client().player.isTouchingWater() || module.client().player.isInLava();

        if (runtime.target == null || !targeting.isValid(runtime.target)) {
            handleLostTargetMove(event, now, gliding, inLiquid);
            return;
        }

        LivingEntity target = runtime.target;
        Vec3d playerPos = module.client().player.getEntityPos();
        runtime.rememberTargetSnapshot(target, now);

        if (onlyWhileElytra.get() && !gliding && !inLiquid) {
            if (module.client().player.isOnGround()) module.client().player.jump();
            Vec3d toward = normalizeOrFallback(horizontal(target.getEntityPos().subtract(playerPos)), horizontal(module.client().player.getRotationVector()));
            double relaunchY = module.client().player.isOnGround() ? 0.42 : 0.24;
            Vec3d relaunchAssist = new Vec3d(toward.x * 0.95, relaunchY, toward.z * 0.95);
            ((IVec3d) event.movement).meteor$set(relaunchAssist.x, relaunchAssist.y, relaunchAssist.z);
            debugLogger.logMove("relaunch-assist glide=false", target, runtime, relaunchAssist);
            return;
        }

        // In liquid while elytra is closed, keep moving + rising so relaunch can recover instead of freezing in place.
        if (!gliding && inLiquid) {
            Vec3d toward = normalizeOrFallback(horizontal(target.getEntityPos().subtract(playerPos)), horizontal(module.client().player.getRotationVector()));
            double swimHorizontal = 0.80;
            double swimVertical = 0.26;
            Vec3d waterVelocity = new Vec3d(toward.x * swimHorizontal, swimVertical, toward.z * swimHorizontal);
            ((IVec3d) event.movement).meteor$set(waterVelocity.x, waterVelocity.y, waterVelocity.z);
            debugLogger.logMove("water-relaunch glide=false liquid=true", target, runtime, waterVelocity);
            return;
        }

        long strikeDelayMs = combat.strikeReadyDelayMs(target);

        Vec3d targetPos = target.getEntityPos();
        targetPos = targetPos.add(
            PlayerEntity.adjustMovementForCollisions(
                target,
                target.getVelocity(),
                target.getBoundingBox(),
                module.client().world,
                module.client().world.getEntityCollisions(target, target.getBoundingBox().stretch(target.getVelocity()))
            )
        );
        targetPos = anchorAtBodyHead(targetPos, target);
        targetPos = clampPlayerGroundLane(targetPos, target);
        boolean horizontalBlocked = isHorizontalRouteBlocked(playerPos, targetPos, target);
        boolean routeToTargetBlocked = !pathfinder.isDirectPathClear(playerPos, targetPos);
        boolean clearHorizontalLane = !horizontalBlocked && !routeToTargetBlocked;
        boolean targetPitDetected = isTargetPitMode(targetPos, target);
        if (runtime.pitVerticalLockUntilMs > 0L && now >= runtime.pitVerticalLockUntilMs) {
            runtime.pitVerticalLockUntilMs = 0L;
            runtime.pitVerticalLockTargetId = -1;
        }
        if (targetPitDetected) {
            runtime.pitVerticalLockTargetId = target.getId();
            runtime.pitVerticalLockUntilMs = now + PIT_VERTICAL_LOCK_MS;
        }
        if (!targetPitDetected && runtime.pitVerticalLockTargetId == target.getId() && runtime.pitVerticalLockUntilMs > now) {
            double yDeltaAbs = Math.abs(targetPos.y - playerPos.y);
            boolean openSkyTarget = isOpenSkyNear(new Vec3d(targetPos.x, target.getY() + target.getHeight() * 0.5, targetPos.z));
            if (openSkyTarget && yDeltaAbs <= 1.25 && !horizontalBlocked && !routeToTargetBlocked) {
                runtime.pitVerticalLockUntilMs = 0L;
                runtime.pitVerticalLockTargetId = -1;
            }
        }
        boolean targetPitLocked = runtime.pitVerticalLockTargetId == target.getId() && now < runtime.pitVerticalLockUntilMs;
        boolean targetPitMode = targetPitDetected || targetPitLocked;
        double targetDeltaY = targetPos.y - playerPos.y;
        boolean preferVerticalRoute = targetPitMode
            && (horizontalBlocked || routeToTargetBlocked || targetDeltaY <= VERTICAL_ROUTE_TARGET_BELOW_Y);
        boolean verticalRouteMode = runtime.passPhase == SpearSpoofRuntime.PassPhase.APPROACH
            && preferVerticalRoute;
        if (verticalRouteMode && targetPitDetected) {
            runtime.pitVerticalLockTargetId = target.getId();
            runtime.pitVerticalLockUntilMs = now + PIT_VERTICAL_LOCK_MS;
            targetPitLocked = true;
            targetPitMode = true;
        }
        String verticalRouteStage = "none";
        boolean diveToLowerTarget = targetDeltaY <= VERTICAL_DIVE_TRIGGER;
        if (obstacleAvoidance.get() && !diveToLowerTarget && !verticalRouteMode) {
            targetPos = applyAutoWaspAvoidLanding(targetPos, target);
            targetPos = clampPlayerGroundLane(targetPos, target);
        }
        targetDeltaY = targetPos.y - playerPos.y;
        diveToLowerTarget = targetDeltaY <= VERTICAL_DIVE_TRIGGER;
        boolean caveEscapeNeeded = targetDeltaY >= CAVE_ESCAPE_TRIGGER && !isOpenSkyNear(playerPos);

        double targetDistance = distanceFromPointToHitbox(playerPos, target);
        double horizontalToTarget = horizontal(targetPos.subtract(playerPos)).length();
        double engageMin = getEngageMinRange(target);
        double engageMax = getEngageMaxRange(target);
        double softKeepOut = Math.min(engageMax - 0.04, engageMin + SOFT_KEEP_OUT_OFFSET);
        double hardKeepOut = engageMin;
        boolean smallTarget = isSmallTarget(target);
        boolean playerTarget = target instanceof PlayerEntity;
        boolean largeGroundTarget = isGroundedLargeTarget(target);
        boolean awaitingHitConfirm = runtime.isAwaitingHitConfirm(target, now);
        boolean mode4xEnabled = mode4x.get();
        boolean mode4xVertical = mode4xEnabled && preferVerticalRoute;
        boolean mode4xHorizontal = mode4xEnabled && !mode4xVertical;
        boolean mode4xActive = mode4xHorizontal || mode4xVertical;
        boolean pitRoutingMode = preferVerticalRoute
            || (runtime.passPhase == SpearSpoofRuntime.PassPhase.RESET && targetPitMode);
        if (mode4xActive) {
            engageMin = Math.max(engageMin, MODE_4X_MIN_RANGE);
            engageMax = Math.min(engageMax, MODE_4X_MAX_RANGE);
            hardKeepOut = Math.max(hardKeepOut, MODE_4X_HARD_KEEP_OUT);
            softKeepOut = Math.max(softKeepOut, MODE_4X_SOFT_KEEP_OUT);
        }
        boolean forceUntilDamageMode = testFlyUntilDamage.get()
            && awaitingHitConfirm
            && runtime.hitConfirmTargetId == target.getId();
        if (forceUntilDamageMode && runtime.passPhase == SpearSpoofRuntime.PassPhase.RESET) {
            toApproachWithTrace(now, "test-force-until-damage", target);
        }
        long sinceStrikeMs = runtime.lastStrikeAtMs > 0L ? Math.max(0L, now - runtime.lastStrikeAtMs) : Long.MAX_VALUE;
        boolean hitPierceWindow = awaitingHitConfirm && (forceUntilDamageMode || sinceStrikeMs <= HIT_PIERCE_WINDOW_MS);

        Vec3d steerPos = targetPos;
        boolean forceVerticalClimb = false;
        boolean forceVerticalAlign = false;
        if (mode4xVertical) {
            double approachY = targetPos.y + MODE_4X_APPROACH_DISTANCE;
            double retreatY = targetPos.y + MODE_4X_APPROACH_DISTANCE + MODE_4X_POST_HIT_RETREAT;
            if (horizontalToTarget > PIT_AXIS_LOCK_RADIUS + 0.08) {
                steerPos = new Vec3d(targetPos.x, playerPos.y, targetPos.z);
                verticalRouteStage = "4x-vert-axis";
            } else if (runtime.passPhase == SpearSpoofRuntime.PassPhase.RESET) {
                steerPos = new Vec3d(playerPos.x, retreatY, playerPos.z);
                forceVerticalClimb = true;
                verticalRouteStage = "4x-vert-reset";
            } else {
                steerPos = new Vec3d(playerPos.x, approachY, playerPos.z);
                forceVerticalAlign = true;
                verticalRouteStage = "4x-vert-approach";
            }
            diveToLowerTarget = false;
            verticalRouteMode = false;
        } else if (runtime.passPhase == SpearSpoofRuntime.PassPhase.RESET) {
            if (pitRoutingMode && !Double.isFinite(runtime.resetRequiredY)) {
                runtime.resetStartY = playerPos.y;
                double verticalRetreat = currentResetVerticalRetreatBlocks(now);
                runtime.resetRequiredY = Math.max(
                    runtime.resetStartY + verticalRetreat,
                    targetPos.y + verticalRetreat
                );
            }
            if (!pitRoutingMode) {
                runtime.resetStartY = Double.NaN;
                runtime.resetRequiredY = Double.NaN;
            }

            if (pitRoutingMode) {
                // TODO: pit routing is still hacky; climb first or it ping-pongs in walls.
                double climbY = Math.max(
                    runtime.resetRequiredY,
                    Math.max(
                        playerPos.y + PIT_ROUTE_SELF_ASCEND_MIN,
                        targetPos.y + Math.max(VERTICAL_ROUTE_HEIGHT + PIT_ROUTE_EXTRA_HEIGHT, target.getHeight() + 2.4)
                    )
                );
                steerPos = new Vec3d(playerPos.x, climbY, playerPos.z);
                forceVerticalClimb = true;
                verticalRouteStage = "pit-reset-climb";
            } else if (playerPos.y < runtime.resetRequiredY - RESET_VERTICAL_RETREAT_EPS) {
                steerPos = new Vec3d(playerPos.x, runtime.resetRequiredY, playerPos.z);
                forceVerticalClimb = true;
                verticalRouteStage = "reset-climb";
            } else {
                Vec3d awayNow = normalizeOrFallback(horizontal(playerPos.subtract(targetPos)), horizontal(runtime.lockedApproachDirection));
                Vec3d resetDir;
                if (mode4xHorizontal) {
                    resetDir = runtime.resetDirection.lengthSquared() > 1.0E-6 ? runtime.resetDirection : awayNow;
                    if (resetDir.dotProduct(awayNow) < -0.25) resetDir = awayNow;
                } else {
                    resetDir = runtime.resetDirection.lengthSquared() > 1.0E-6 ? runtime.resetDirection : awayNow;
                    if (resetDir.dotProduct(awayNow) < 0.15) resetDir = awayNow;
                }
                runtime.resetDirection = normalizeOrFallback(resetDir, awayNow);

                double desiredRetreat = desiredRetreatDistance(target, engageMin, engageMax, now);
                double outwardStep = targetDistance < desiredRetreat
                    ? Math.max(2.6, desiredRetreat - targetDistance + 2.2)
                    : 2.2;
                steerPos = new Vec3d(
                    playerPos.x + runtime.resetDirection.x * outwardStep,
                    playerPos.y,
                    playerPos.z + runtime.resetDirection.z * outwardStep
                );
            }
        } else if (verticalRouteMode) {
            double routeHeight;
            if (targetPitMode) {
                routeHeight = MathHelper.clamp(
                    target.getHeight() + PIT_ROUTE_HEIGHT_OFFSET,
                    PIT_ROUTE_HEIGHT_MIN,
                    PIT_ROUTE_HEIGHT_MAX
                );
            } else {
                routeHeight = Math.max(VERTICAL_ROUTE_HEIGHT, target.getHeight() + 1.5);
            }
            double aboveY = targetPos.y + routeHeight;
            // Do not anchor to current Y every tick or we moonwalk into the sky.
            if (targetPitMode && playerPos.y < targetPos.y + 0.35) {
                aboveY = Math.max(aboveY, playerPos.y + PIT_ROUTE_SELF_ASCEND_MIN);
            }
            Vec3d aboveTarget = new Vec3d(
                targetPos.x,
                aboveY,
                targetPos.z
            );
            double diveReadyAbove = targetPitMode ? PIT_ROUTE_DIVE_READY_ABOVE : 0.85;
            double diveRadius = targetPitMode ? PIT_ROUTE_DIVE_RADIUS : VERTICAL_ROUTE_DIVE_RADIUS;
            boolean needClimb;
            if (targetPitMode) {
                double minDiveAltitude = targetPos.y + diveReadyAbove + PIT_MIN_CLIMB_ABOVE_TARGET;
                needClimb = playerPos.y < minDiveAltitude;
                if (strikeDelayMs <= 160L && targetDistance <= engageMax + PIT_NEAR_STRIKE_NO_CLIMB_RANGE) {
                    needClimb = false;
                }
            } else {
                needClimb = playerPos.y < aboveTarget.y - VERTICAL_ROUTE_ASCEND_MARGIN;
            }
            boolean readyDive;
            if (targetPitMode) {
                readyDive = !needClimb
                    && playerPos.y > targetPos.y + diveReadyAbove
                    && horizontalToTarget <= PIT_AXIS_LOCK_RADIUS + 0.12;
            } else {
                readyDive = !needClimb && playerPos.y > targetPos.y + diveReadyAbove && horizontalToTarget <= diveRadius;
            }
            if (needClimb) {
                steerPos = new Vec3d(targetPos.x, aboveTarget.y, targetPos.z);
                forceVerticalClimb = true;
                verticalRouteStage = targetPitMode ? "axis-climb" : "climb";
            } else if (targetPitMode && (horizontalBlocked || routeToTargetBlocked) && horizontalToTarget > PIT_AXIS_LOCK_RADIUS + 0.08) {
                double blockedCapY = targetPos.y + PIT_BLOCKED_ASCEND_MAX_ABOVE_TARGET;
                if (playerPos.y < blockedCapY - 0.18) {
                    double blockedClimbY = Math.max(
                        playerPos.y + PIT_BLOCKED_ASCEND_STEP,
                        targetPos.y + routeHeight + PIT_BLOCKED_ASCEND_ABOVE_ROUTE
                    );
                    blockedClimbY = Math.min(blockedClimbY, blockedCapY);
                    steerPos = new Vec3d(playerPos.x, blockedClimbY, playerPos.z);
                    forceVerticalClimb = true;
                    verticalRouteStage = "pit-block-climb";
                } else {
                    steerPos = new Vec3d(targetPos.x, playerPos.y, targetPos.z);
                    verticalRouteStage = "pit-block-axis";
                }
            } else if (targetPitMode && horizontalToTarget > PIT_AXIS_LOCK_RADIUS) {
                steerPos = new Vec3d(targetPos.x, playerPos.y, targetPos.z);
                verticalRouteStage = "axis-lock";
            } else if (readyDive) {
                steerPos = targetPos;
                diveToLowerTarget = true;
                verticalRouteStage = "dive";
            } else if (targetPitMode) {
                steerPos = new Vec3d(targetPos.x, playerPos.y, targetPos.z);
                verticalRouteStage = "axis-hold";
            } else {
                steerPos = new Vec3d(targetPos.x, aboveTarget.y, targetPos.z);
                verticalRouteStage = "align";
            }
        } else if (horizontalToTarget > FAR_CHASE_DIRECT_DISTANCE) {
            Vec3d lead = horizontal(target.getVelocity()).multiply(
                MathHelper.clamp(targetDistance * FAR_CHASE_INTERCEPT_FACTOR, 0.0, FAR_CHASE_INTERCEPT_MAX)
            );
            steerPos = targetPos.add(lead);
        }

        if (mode4xHorizontal && runtime.passPhase == SpearSpoofRuntime.PassPhase.APPROACH) {
            Vec3d laneDirection4x = mode4xAxis(normalizeOrFallback(runtime.lockedApproachDirection, horizontal(playerPos.subtract(targetPos))));
            steerPos = new Vec3d(
                targetPos.x + laneDirection4x.x * MODE_4X_APPROACH_DISTANCE,
                playerPos.y,
                targetPos.z + laneDirection4x.z * MODE_4X_APPROACH_DISTANCE
            );
        }

        if (runtime.passPhase == SpearSpoofRuntime.PassPhase.RESET) {
            diveToLowerTarget = false;
        }

        if (!pitRoutingMode && runtime.passPhase == SpearSpoofRuntime.PassPhase.APPROACH && !verticalRouteMode) {
            double yGap = targetPos.y - playerPos.y;
            if (!clearHorizontalLane) {
                if (horizontalToTarget >= PLAIN_BLOCKED_ROUTE_DISTANCE) {
                    steerPos = new Vec3d(targetPos.x, Math.max(playerPos.y, targetPos.y + 0.35), targetPos.z);
                    verticalRouteStage = "plain-block-route";
                } else {
                    double blockedCapY = targetPos.y + PLAIN_BLOCKED_ASCEND_MAX_ABOVE_TARGET;
                    if (playerPos.y < blockedCapY - 0.18) {
                        double climbY = Math.max(
                            playerPos.y + PLAIN_BLOCKED_ASCEND_STEP,
                            targetPos.y + PLAIN_BLOCKED_ASCEND_ABOVE_TARGET
                        );
                        climbY = Math.min(climbY, blockedCapY);
                        steerPos = new Vec3d(playerPos.x, climbY, playerPos.z);
                        forceVerticalAlign = true;
                        verticalRouteStage = "plain-block-climb";
                    } else {
                        steerPos = new Vec3d(targetPos.x, playerPos.y, targetPos.z);
                        verticalRouteStage = "plain-block-cap";
                    }
                }
                diveToLowerTarget = false;
            } else if (yGap > PLAIN_ALIGN_Y_THRESHOLD) {
                steerPos = new Vec3d(playerPos.x, targetPos.y, playerPos.z);
                forceVerticalAlign = true;
                verticalRouteStage = "plain-align-up";
                diveToLowerTarget = false;
            } else if (yGap < -PLAIN_ALIGN_Y_THRESHOLD
                && (targetDistance <= PLAIN_DESCEND_START_DISTANCE || horizontalToTarget <= PLAIN_DESCEND_START_HORIZONTAL)) {
                if (isVerticalColumnBlocked(playerPos, targetPos)) {
                    steerPos = selectCanopyBypassAnchor(playerPos, targetPos);
                    verticalRouteStage = "canopy-bypass";
                } else {
                    steerPos = new Vec3d(playerPos.x, targetPos.y, playerPos.z);
                    forceVerticalAlign = true;
                    verticalRouteStage = "plain-align-down";
                }
                diveToLowerTarget = false;
            } else {
                diveToLowerTarget = false;
            }
        }

        if (obstacleAvoidance.get()) {
            if (!pitRoutingMode
                && runtime.passPhase == SpearSpoofRuntime.PassPhase.APPROACH
                && clearHorizontalLane
                && !forceVerticalAlign
                && !forceVerticalClimb) {
                pathfinder.clearTargetState();
                steerPos = new Vec3d(targetPos.x, playerPos.y, targetPos.z);
            } else if (forceVerticalAlign) {
                pathfinder.clearTargetState();
            } else {
                Vec3d safeSteer = pathfinder.adjustToSafeCorridor(steerPos, playerPos);
                if (forceVerticalClimb || pitRoutingMode || mode4xHorizontal) {
                    pathfinder.clearTargetState();
                    steerPos = mode4xHorizontal ? new Vec3d(safeSteer.x, playerPos.y, safeSteer.z) : safeSteer;
                } else {
                    pathfinder.tickPathing(playerPos, safeSteer, runtime.stuckTicks);
                    steerPos = pathfinder.steerTarget(safeSteer);
                }
            }
        }

        boolean pitVerticalMode = pitRoutingMode;
        boolean pureVerticalAscent = forceVerticalClimb;
        boolean pureVerticalAlign = forceVerticalAlign;
        boolean pureVerticalDive = pitVerticalMode && "dive".equals(verticalRouteStage);
        boolean pitAxisLock = pitVerticalMode && "axis-lock".equals(verticalRouteStage);
        double activeHardKeepOut = pitVerticalMode ? Math.max(1.65, hardKeepOut - PIT_KEEP_OUT_SLACK) : hardKeepOut;
        double activeSoftKeepOut = pitVerticalMode
            ? Math.min(engageMax - 0.04, Math.max(activeHardKeepOut + 0.08, softKeepOut - PIT_KEEP_OUT_SLACK))
            : softKeepOut;
        if (mode4xActive && !forceUntilDamageMode) {
            activeHardKeepOut = Math.max(activeHardKeepOut, MODE_4X_HARD_KEEP_OUT);
            activeSoftKeepOut = Math.max(activeSoftKeepOut, Math.min(engageMax - 0.04, MODE_4X_SOFT_KEEP_OUT));
            if (activeSoftKeepOut <= activeHardKeepOut + 0.06) {
                activeSoftKeepOut = Math.min(engageMax - 0.04, activeHardKeepOut + 0.10);
            }
        }
        if (playerTarget && !pitVerticalMode && !forceUntilDamageMode) {
            activeHardKeepOut = Math.max(activeHardKeepOut, PLAYER_HARD_KEEP_OUT_MIN);
            activeSoftKeepOut = Math.max(activeSoftKeepOut, Math.min(engageMax - 0.04, PLAYER_SOFT_KEEP_OUT_MIN));
            if (activeSoftKeepOut <= activeHardKeepOut + 0.06) {
                activeSoftKeepOut = Math.min(engageMax - 0.04, activeHardKeepOut + 0.10);
            }
        }
        Vec3d velocity = computeAutoWaspVelocity(playerPos, steerPos);
        double maxHorizontal = resolveHorizontalCap(horizontalSpeed.get());
        double maxVertical = resolveVerticalCap();
        onMovePhase2(event, now, target, playerPos, targetPos, steerPos, strikeDelayMs, targetDistance, engageMin, engageMax, hardKeepOut, softKeepOut, activeHardKeepOut, activeSoftKeepOut, targetDeltaY, diveToLowerTarget, caveEscapeNeeded, clearHorizontalLane, horizontalBlocked, routeToTargetBlocked, targetPitDetected, targetPitLocked, verticalRouteMode, verticalRouteStage, pitVerticalMode, pureVerticalAscent, pureVerticalAlign, pureVerticalDive, pitAxisLock, smallTarget, playerTarget, largeGroundTarget, awaitingHitConfirm, mode4xHorizontal, mode4xActive, forceUntilDamageMode, hitPierceWindow, velocity, maxHorizontal, maxVertical);
    }
}
