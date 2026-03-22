package com.example.addon.modules.spearspoof;

import com.example.addon.modules.SpearSpoof;
import meteordevelopment.meteorclient.events.entity.player.PlayerMoveEvent;
import meteordevelopment.meteorclient.mixin.AbstractBlockAccessor;
import meteordevelopment.meteorclient.mixin.DirectionAccessor;
import meteordevelopment.meteorclient.mixininterface.IVec3d;
import meteordevelopment.meteorclient.settings.Setting;
import net.minecraft.block.Blocks;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.world.World;

public final class SpearSpoofFlightService {
    private static final double ENFORCED_MIN_RANGE = 0.40;
    private static final double SMALL_TARGET_MIN_RANGE = 0.28;
    private static final double ENFORCED_MAX_RANGE = 4.5;
    private static final double FAR_CHASE_DIRECT_DISTANCE = 8.0;
    private static final double FAR_CHASE_INTERCEPT_FACTOR = 0.12;
    private static final double FAR_CHASE_INTERCEPT_MAX = 3.0;
    private static final double SMALL_TARGET_MAX_WIDTH = 0.90;
    private static final double SMALL_TARGET_MAX_HEIGHT = 1.10;
    private static final double SOFT_KEEP_OUT_OFFSET = 0.12;
    private static final double NETHER_MAX_FLIGHT_Y = 123.0;
    private static final long STUCK_SAMPLE_MS = 120L;
    private static final double STUCK_MOVE_EPS = 0.08;
    private static final int STUCK_TICKS_LIMIT = 4;
    private static final long UNSTUCK_BURST_MS = 420L;
    private static final long RESET_MIN_DWELL_MS = 140L;
    private static final double RESET_RETREAT_DISTANCE = 5.0;
    private static final double SMALL_RESET_RETREAT_DISTANCE = 5.0;
    private static final double RESET_VERTICAL_RETREAT_BLOCKS = 5.0;
    private static final double RESET_VERTICAL_RETREAT_BLOCKS_RECHARGE = 5.6;
    private static final double RECHARGE_RETREAT_DISTANCE = 5.0;
    private static final double SMALL_RECHARGE_RETREAT_DISTANCE = 5.0;
    private static final double MAX_RESET_RETREAT_DISTANCE = 6.0;
    private static final double RESET_VERTICAL_RETREAT_EPS = 0.10;
    private static final long RESET_VERTICAL_RETREAT_FAILSAFE_MS = 1200L;
    private static final long SMALL_RESET_HOLD_MS = 170L;
    private static final long DEFAULT_RESET_HOLD_MS = 220L;
    private static final double PLAIN_ALIGN_Y_THRESHOLD = 0.65;
    private static final double PLAIN_ALIGN_VERTICAL_MIN = 0.42;
    private static final double PLAIN_DESCEND_START_DISTANCE = 7.0;
    private static final double PLAIN_DESCEND_START_HORIZONTAL = 4.8;
    private static final double PLAIN_BLOCKED_ASCEND_STEP = 1.35;
    private static final double PLAIN_BLOCKED_ASCEND_ABOVE_TARGET = 1.80;
    private static final double PLAIN_BLOCKED_ASCEND_MAX_ABOVE_TARGET = 10.0;
    private static final double PLAIN_BLOCKED_ROUTE_DISTANCE = 12.0;
    private static final double VERTICAL_DIVE_TRIGGER = -0.55;
    private static final double CAVE_ESCAPE_TRIGGER = 1.20;
    private static final double VERTICAL_ROUTE_HEIGHT = 3.0;
    private static final double VERTICAL_ROUTE_TARGET_BELOW_Y = -1.0;
    private static final double VERTICAL_ROUTE_ASCEND_MARGIN = 0.35;
    private static final double VERTICAL_ROUTE_DIVE_RADIUS = 1.65;
    private static final double PIT_ROUTE_SELF_ASCEND_MIN = 1.35;
    private static final double PIT_ROUTE_EXTRA_HEIGHT = 1.35;
    private static final double PIT_ROUTE_DIVE_RADIUS = 1.20;
    private static final double PIT_ROUTE_DIVE_READY_ABOVE = 1.05;
    private static final double PIT_AXIS_LOCK_RADIUS = 0.70;
    private static final double PIT_AXIS_LOCK_MIN_SPEED = 0.55;
    private static final double PIT_AXIS_LOCK_MAX_SPEED = 1.25;
    private static final double PIT_BLOCKED_ASCEND_STEP = 1.20;
    private static final double PIT_BLOCKED_ASCEND_ABOVE_ROUTE = 2.00;
    private static final double PIT_BLOCKED_ASCEND_MAX_ABOVE_TARGET = 8.0;
    private static final double PIT_ROUTE_DIVE_RUNUP_MIN = 1.85;
    private static final double PIT_ROUTE_DIVE_RUNUP_MAX = 3.45;
    private static final double PIT_ROUTE_DIVE_RUNUP_TARGET = 2.9;
    private static final double PIT_ROUTE_HEIGHT_MIN = 2.25;
    private static final double PIT_ROUTE_HEIGHT_MAX = 3.05;
    private static final double PIT_ROUTE_HEIGHT_OFFSET = 0.45;
    private static final double PIT_RUNUP_Y_STEP_CAP = 0.45;
    private static final double PIT_MIN_CLIMB_ABOVE_TARGET = 0.22;
    private static final double PIT_NEAR_STRIKE_NO_CLIMB_RANGE = 0.90;
    private static final double PIT_KEEP_OUT_SLACK = 0.18;
    private static final double PIT_VERTICAL_ASCENT_MIN = 0.34;
    private static final double PIT_VERTICAL_DESCENT_MIN = 0.42;
    private static final long PIT_VERTICAL_LOCK_MS = 1800L;
    private static final double HORIZONTAL_BLOCK_SCAN_STEP = 0.45;
    private static final double HORIZONTAL_BLOCK_SCAN_MAX = 12.0;
    private static final double PIT_BASIN_SAMPLE_RADIUS = 2.35;
    private static final double PIT_BASIN_MIN_RIM_DELTA = 1.10;
    private static final int PIT_BASIN_REQUIRED_RIM_SAMPLES = 4;
    private static final double MIN_CRUISE_HORIZONTAL_SPEED = 2.4; // 48 bps
    private static final double RESET_CRUISE_FACTOR = 1.00;
    private static final long HIT_PIERCE_WINDOW_MS = 320L;
    private static final double HIT_PIERCE_SPEED_FACTOR = 1.12;
    private static final double PASS_THROUGH_REVERSE_DISTANCE = 1.45;
    private static final double PASS_THROUGH_REVERSE_DOT = -0.30;
    private static final long PASS_THROUGH_RESET_HOLD_MS_PLAYER = 190L;
    private static final long PASS_THROUGH_RESET_HOLD_MS_OTHER = 150L;
    private static final double PLAYER_HARD_KEEP_OUT_MIN = 0.95;
    private static final double PLAYER_SOFT_KEEP_OUT_MIN = 1.25;
    private static final double MODE_4X_RUNUP_DISTANCE = 5.0;
    private static final double MODE_4X_MIN_RANGE = 4.0;
    private static final double MODE_4X_MAX_RANGE = 4.5;
    private static final double MODE_4X_APPROACH_DISTANCE = 4.25;
    private static final double MODE_4X_APPROACH_STEP_EPS = 0.01;
    private static final double MODE_4X_POST_HIT_RETREAT = 2.0;
    private static final double MODE_4X_HARD_KEEP_OUT = 4.0;
    private static final double MODE_4X_SOFT_KEEP_OUT = 4.35;
    private static final long LOST_TARGET_FOLLOW_MS = 2600L;
    private static final long LOST_TARGET_PATHING_MS = 1400L;
    private static final double LOST_TARGET_MAX_PREDICT_TICKS = 12.0;
    private static final double LOST_TARGET_IDLE_BRAKE_HORIZONTAL = 0.80;
    private static final double LOST_TARGET_IDLE_BRAKE_VERTICAL = 0.55;
    private static final double LOST_TARGET_FOLLOW_DAMPING = 0.94;
    private static final double FLOOR_CLEARANCE = 2.2;
    private static final double CEILING_CLEARANCE = 1.5;
    private static final int SAFETY_SCAN = 14;
    private static final double OBSTACLE_LOOK_AHEAD = 7.5;
    private static final double AVOIDANCE_STRENGTH = 0.85;
    private static final double COLLISION_STEP = 0.55;
    private static final double PLAYER_GROUND_LANE_MIN_Y = 0.88;
    private static final double PLAYER_GROUND_LANE_MAX_Y = 1.18;

    private final SpearSpoof module;
    private final SpearSpoofRuntime runtime;
    private final SpearSpoofFlightPathfinder pathfinder;
    private final SpearSpoofTargetingService targeting;
    private final SpearSpoofCombatService combat;
    private final SpearSpoofDebugLogger debugLogger;

    private final Setting<Boolean> onlyWhileElytra;
    private final Setting<Boolean> attributeSwap;
    private final Setting<Double> horizontalSpeed;
    private final Setting<Double> verticalSpeed;
    private final Setting<Boolean> obstacleAvoidance;
    private final Setting<Boolean> autoRelaunch;
    private final Setting<Boolean> testFlyUntilDamage;
    private final Setting<Boolean> mode4x;

    public SpearSpoofFlightService(
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
        this.module = module;
        this.runtime = runtime;
        this.pathfinder = pathfinder;
        this.targeting = targeting;
        this.combat = combat;
        this.debugLogger = debugLogger;

        this.onlyWhileElytra = onlyWhileElytra;
        this.attributeSwap = attributeSwap;
        this.horizontalSpeed = horizontalSpeed;
        this.verticalSpeed = verticalSpeed;
        this.obstacleAvoidance = obstacleAvoidance;
        this.autoRelaunch = autoRelaunch;
        this.testFlyUntilDamage = testFlyUntilDamage;
        this.mode4x = mode4x;
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
                // Stage 1: horizontal axis lock above pit target, no diagonal dive.
                steerPos = new Vec3d(targetPos.x, playerPos.y, targetPos.z);
                verticalRouteStage = "4x-vert-axis";
            } else if (runtime.passPhase == SpearSpoofRuntime.PassPhase.RESET) {
                // Stage 2: retreat straight up by +2 blocks from strike lane.
                steerPos = new Vec3d(playerPos.x, retreatY, playerPos.z);
                forceVerticalClimb = true;
                verticalRouteStage = "4x-vert-reset";
            } else {
                // Stage 3: return strictly on vertical lane to strike distance.
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
                // In pits/caves, reset should climb out first instead of lateral ping-pong in cramped space.
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
                    // In 4X keep one stable reset axis for the whole cycle to avoid lateral ping-pong.
                    resetDir = runtime.resetDirection.lengthSquared() > 1.0E-6 ? runtime.resetDirection : awayNow;
                    if (resetDir.dotProduct(awayNow) < -0.25) resetDir = awayNow;
                } else {
                    resetDir = runtime.resetDirection.lengthSquared() > 1.0E-6 ? runtime.resetDirection : awayNow;
                    // Keep reset direction strictly away from target to avoid ping-pong around the hitbox edge.
                    if (resetDir.dotProduct(awayNow) < 0.15) resetDir = awayNow;
                }
                runtime.resetDirection = normalizeOrFallback(resetDir, awayNow);

                double desiredRetreat = desiredRetreatDistance(target, engageMin, engageMax, now);
                double outwardStep = targetDistance < desiredRetreat
                    ? Math.max(2.6, desiredRetreat - targetDistance + 2.2)
                    : 2.2;
                steerPos = new Vec3d(
                    playerPos.x + runtime.resetDirection.x * outwardStep,
                    // Keep non-pit reset retreat horizontal. Vertical correction is handled in dedicated phases.
                    playerPos.y,
                    playerPos.z + runtime.resetDirection.z * outwardStep
                );
            }
        } else if (verticalRouteMode) {
            double routeHeight;
            if (targetPitMode) {
                // Keep pit loop compact so descent doesn't stop early at the top ring.
                routeHeight = MathHelper.clamp(
                    target.getHeight() + PIT_ROUTE_HEIGHT_OFFSET,
                    PIT_ROUTE_HEIGHT_MIN,
                    PIT_ROUTE_HEIGHT_MAX
                );
            } else {
                routeHeight = Math.max(VERTICAL_ROUTE_HEIGHT, target.getHeight() + 1.5);
            }
            double aboveY = targetPos.y + routeHeight;
            // Do not anchor climb target to current player Y each tick; that causes infinite ascent.
            // Keep a minimal extra lift only when player is still below target body level.
            if (targetPitMode && playerPos.y < targetPos.y + 0.35) {
                aboveY = Math.max(aboveY, playerPos.y + PIT_ROUTE_SELF_ASCEND_MIN);
            }
            Vec3d laneDirection = normalizeOrFallback(runtime.lockedApproachDirection, horizontal(playerPos.subtract(targetPos)));
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
                // Stage 1: climb almost vertically first; do not pull diagonally into terrain.
                steerPos = new Vec3d(targetPos.x, aboveTarget.y, targetPos.z);
                forceVerticalClimb = true;
                verticalRouteStage = targetPitMode ? "axis-climb" : "climb";
            } else if (targetPitMode && (horizontalBlocked || routeToTargetBlocked) && horizontalToTarget > PIT_AXIS_LOCK_RADIUS + 0.08) {
                // Path to pit center is blocked by crater wall: gain altitude, but never infinitely.
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
                    // At cap: stop climbing and center on axis.
                    steerPos = new Vec3d(targetPos.x, playerPos.y, targetPos.z);
                    verticalRouteStage = "pit-block-axis";
                }
            } else if (targetPitMode && horizontalToTarget > PIT_AXIS_LOCK_RADIUS) {
                // Lock directly above target center before dive. No ring run-up in pits.
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
                // Stage 2: build run-up ring (2-3 blocks), then dive through target.
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
            // Reset is retreat/recharge phase only: no dive logic here.
            diveToLowerTarget = false;
        }

        if (!pitRoutingMode && runtime.passPhase == SpearSpoofRuntime.PassPhase.APPROACH && !verticalRouteMode) {
            double yGap = targetPos.y - playerPos.y;
            if (!clearHorizontalLane) {
                if (horizontalToTarget >= PLAIN_BLOCKED_ROUTE_DISTANCE) {
                    // Long blocked route: use waypoint pathing instead of climbing into sky.
                    steerPos = new Vec3d(targetPos.x, Math.max(playerPos.y, targetPos.y + 0.35), targetPos.z);
                    verticalRouteStage = "plain-block-route";
                } else {
                    // Near blocked route: gain a small altitude margin, but with hard cap.
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
                // Target above us: pure vertical climb first.
                steerPos = new Vec3d(playerPos.x, targetPos.y, playerPos.z);
                forceVerticalAlign = true;
                verticalRouteStage = "plain-align-up";
                diveToLowerTarget = false;
            } else if (yGap < -PLAIN_ALIGN_Y_THRESHOLD
                && (targetDistance <= PLAIN_DESCEND_START_DISTANCE || horizontalToTarget <= PLAIN_DESCEND_START_HORIZONTAL)) {
                // Descend when horizontally aligned, even if 3D distance is large due height delta.
                // If canopy blocks the direct vertical column, move to a side anchor first.
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
                // On open flat lane keep attack pass horizontal; no diagonal downward drift.
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
                // For plain Y-align do not clamp to safe corridor, otherwise descent can be canceled near terrain.
                pathfinder.clearTargetState();
            } else {
                Vec3d safeSteer = pathfinder.adjustToSafeCorridor(steerPos, playerPos);
                if (forceVerticalClimb || pitRoutingMode || mode4xHorizontal) {
                    // Keep pure vertical climb path deterministic.
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

    private void handleLostTargetMove(PlayerMoveEvent event, long now, boolean gliding, boolean inLiquid) {
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

    private void applyIdleBrake(PlayerMoveEvent event) {
        Vec3d current = module.client().player.getVelocity();
        Vec3d damped = new Vec3d(
            current.x * LOST_TARGET_IDLE_BRAKE_HORIZONTAL,
            MathHelper.clamp(current.y * LOST_TARGET_IDLE_BRAKE_VERTICAL, -0.26, 0.26),
            current.z * LOST_TARGET_IDLE_BRAKE_HORIZONTAL
        );
        ((IVec3d) event.movement).meteor$set(damped.x, damped.y, damped.z);
    }

    private void updatePassPhase(LivingEntity target, long strikeDelayMs) {
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

    private void beginResetWithTrace(long now, long holdMs, String reason, LivingEntity target) {
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

    private void toApproachWithTrace(long now, String reason, LivingEntity target) {
        boolean changed = runtime.passPhase != SpearSpoofRuntime.PassPhase.APPROACH;
        runtime.toApproach(now);
        if (changed) debugLogger.logPhaseChange("Phase->APPROACH", reason, target, runtime);
    }

    private double getEngageMinRange(LivingEntity target) {
        double base = isSmallTarget(target) ? SMALL_TARGET_MIN_RANGE : ENFORCED_MIN_RANGE;
        if (mode4x.get() && target != null) base = Math.max(base, MODE_4X_MIN_RANGE);
        return base;
    }

    private double getEngageMaxRange(LivingEntity target) {
        if (mode4x.get() && target != null) return Math.min(ENFORCED_MAX_RANGE, MODE_4X_MAX_RANGE);
        return ENFORCED_MAX_RANGE;
    }

    private double desiredRetreatDistance(LivingEntity target, double engageMin, double engageMax, long now) {
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

    private Vec3d mode4xAxis(Vec3d fallback) {
        if (runtime.resetDirection.lengthSquared() > 1.0E-6) return runtime.resetDirection.normalize();
        return normalizeOrFallback(runtime.lockedApproachDirection, fallback);
    }

    private Vec3d resolveRetreatDirection(Vec3d playerPos, Vec3d targetPos, Vec3d fallback, boolean lock4xAxis) {
        Vec3d away = normalizeOrFallback(horizontal(playerPos.subtract(targetPos)), fallback);
        if (!lock4xAxis) return away;

        Vec3d axis = mode4xAxis(away);
        // If the cached axis points into target due stale state, re-align once.
        if (axis.dotProduct(away) < -0.25) axis = away;
        return normalizeOrFallback(axis, away);
    }

    private double currentResetVerticalRetreatBlocks(long now) {
        if (runtime.rechargeRebuildUntilMs > now) return RESET_VERTICAL_RETREAT_BLOCKS_RECHARGE;
        return RESET_VERTICAL_RETREAT_BLOCKS;
    }

    private Vec3d applyVerticalStrategy(
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
    private Vec3d computeAutoWaspVelocity(Vec3d playerPos, Vec3d targetPos) {
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
    private Vec3d applyAutoWaspAvoidLanding(Vec3d targetPos, LivingEntity target) {
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

    private boolean isOpenSkyNear(Vec3d position) {
        if (position == null || module.client().world == null) return true;

        BlockPos center = BlockPos.ofFloored(position.x, position.y + 1.0, position.z);
        if (module.client().world.isSkyVisible(center)) return true;
        for (Direction direction : DirectionAccessor.meteor$getHorizontal()) {
            if (module.client().world.isSkyVisible(center.offset(direction))) return true;
        }
        return false;
    }

    private boolean isTargetPitMode(Vec3d targetPos, LivingEntity target) {
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

    private boolean isTerrainBasin(Vec3d targetPos, LivingEntity target) {
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

    private int countHardWallsAround(Vec3d center, double radius) {
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

    private boolean isHorizontalRouteBlocked(Vec3d playerPos, Vec3d targetPos, LivingEntity target) {
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

    private boolean isVerticalColumnBlocked(Vec3d playerPos, Vec3d targetPos) {
        if (playerPos == null || targetPos == null || module.client().world == null) return false;
        double topY = Math.max(playerPos.y, targetPos.y + 0.2);
        double bottomY = Math.min(playerPos.y, targetPos.y + 0.2);
        for (double y = topY; y >= bottomY; y -= 0.38) {
            if (isHardBlocking(targetPos.x, y, targetPos.z)) return true;
        }
        return false;
    }

    private Vec3d selectCanopyBypassAnchor(Vec3d playerPos, Vec3d targetPos) {
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

    private boolean hasFoliageCanopy(Vec3d targetPos, LivingEntity target) {
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

    private boolean isHardBlocking(double x, double y, double z) {
        if (module.client().world == null) return false;
        BlockPos pos = BlockPos.ofFloored(x, y, z);
        if (module.client().world.isOutOfHeightLimit(pos.getY())) return true;
        return !module.client().world.getBlockState(pos).getCollisionShape(module.client().world, pos).isEmpty();
    }

    private boolean isSmallTarget(LivingEntity living) {
        if (living == null) return false;
        Box box = living.getBoundingBox();
        double width = Math.max(box.maxX - box.minX, box.maxZ - box.minZ);
        double height = box.maxY - box.minY;
        return width <= SMALL_TARGET_MAX_WIDTH && height <= SMALL_TARGET_MAX_HEIGHT;
    }

    private boolean isGroundedLargeTarget(LivingEntity living) {
        if (living == null || isSmallTarget(living)) return false;
        if (living.isOnGround()) return true;
        return Math.abs(living.getVelocity().y) < 0.12;
    }

    // Keep flight lane near torso level, but do not force +1 block over grounded players.
    private Vec3d anchorAtBodyHead(Vec3d predictedTargetPos, LivingEntity target) {
        if (predictedTargetPos == null || target == null) return predictedTargetPos;

        double height = Math.max(0.2, target.getHeight());
        double offset;

        if (isSmallTarget(target)) {
            offset = MathHelper.clamp(height * 0.52, 0.30, 0.80);
        } else if (target instanceof PlayerEntity) {
            if (target.isOnGround()) offset = MathHelper.clamp(height * 0.56, 0.88, 1.12);
            else offset = MathHelper.clamp(height * 0.60, 0.96, 1.26);
        } else {
            if (target.isOnGround()) offset = MathHelper.clamp(height * 0.44, 0.56, 0.96);
            else offset = MathHelper.clamp(height * 0.52, 0.58, 1.20);
        }

        double anchorY = predictedTargetPos.y + offset;
        return new Vec3d(predictedTargetPos.x, anchorY, predictedTargetPos.z);
    }

    private Vec3d clampPlayerGroundLane(Vec3d targetPos, LivingEntity target) {
        if (targetPos == null || !(target instanceof PlayerEntity) || !target.isOnGround()) return targetPos;

        // For grounded PvP lock flight lane into chest/head band.
        double minY = target.getY() + PLAYER_GROUND_LANE_MIN_Y;
        double maxY = target.getY() + PLAYER_GROUND_LANE_MAX_Y;
        double y = MathHelper.clamp(targetPos.y, minY, maxY);
        return new Vec3d(targetPos.x, y, targetPos.z);
    }

    private double resolveHorizontalCap(double baseCap) {
        double cappedBase = Math.max(baseCap, getCruiseHorizontalSpeed());
        boolean drag = isInDragState();
        if (!drag) {
            runtime.adaptiveHorizontalCap = cappedBase;
            return cappedBase;
        }

        double actual = horizontal(module.client().player.getVelocity()).length();
        if (runtime.adaptiveHorizontalCap < 0 || runtime.adaptiveHorizontalCap > cappedBase) {
            runtime.adaptiveHorizontalCap = cappedBase;
        }

        double targetCap = Math.min(cappedBase, Math.max(0.18, actual * 1.18 + 0.05));
        if (runtime.antiCheatSlowTicks > 8) {
            targetCap = Math.min(targetCap, Math.max(0.16, actual * 1.05 + 0.03));
        }

        runtime.adaptiveHorizontalCap += (targetCap - runtime.adaptiveHorizontalCap) * 0.35;
        runtime.adaptiveHorizontalCap = MathHelper.clamp(runtime.adaptiveHorizontalCap, 0.14, cappedBase);
        return runtime.adaptiveHorizontalCap;
    }

    private double resolveVerticalCap() {
        return verticalSpeed.get();
    }

    private double getCruiseHorizontalSpeed() {
        return Math.max(MIN_CRUISE_HORIZONTAL_SPEED, horizontalSpeed.get());
    }

    private Vec3d enforceHorizontalSpeedFloor(Vec3d velocity, Vec3d direction, double floor) {
        double current = horizontal(velocity).length();
        if (current >= floor || floor <= 1.0E-6) return velocity;

        if (current > 1.0E-6) {
            double scale = floor / current;
            return new Vec3d(velocity.x * scale, velocity.y, velocity.z * scale);
        }

        Vec3d dir = normalizeOrFallback(horizontal(direction), module.client().player != null ? horizontal(module.client().player.getRotationVector()) : Vec3d.ZERO);
        return new Vec3d(dir.x * floor, velocity.y, dir.z * floor);
    }

    private double applyVerticalSafety(double yVel, Vec3d currentPos, double maxVertical) {
        double floorDist = pathfinder.distanceToSolidBelow(currentPos, SAFETY_SCAN);
        double ceilingDist = pathfinder.distanceToSolidAbove(currentPos, SAFETY_SCAN);

        double downBudget = Math.max(0.0, floorDist - FLOOR_CLEARANCE - 0.35);
        double safeMaxDown = Math.min(maxVertical, downBudget * 0.8);
        yVel = Math.max(yVel, -safeMaxDown);

        if (floorDist < FLOOR_CLEARANCE + 0.35) yVel = Math.max(yVel, 0.12);
        if (ceilingDist < CEILING_CLEARANCE + 0.25) yVel = Math.min(yVel, -0.08);
        return yVel;
    }

    private Vec3d applyEmergencyBraking(Vec3d velocity) {
        if (module.client().player == null) return velocity;

        double horizontalSpeedNow = horizontal(velocity).length();
        if (horizontalSpeedNow < 1.0E-4) return velocity;

        Vec3d pos = module.client().player.getEntityPos();
        Vec3d dir = new Vec3d(velocity.x / horizontalSpeedNow, 0.0, velocity.z / horizontalSpeedNow);
        Vec3d left = new Vec3d(-dir.z, 0.0, dir.x);
        Vec3d right = left.multiply(-1.0);

        double checkDistance = Math.max(1.5, Math.min(OBSTACLE_LOOK_AHEAD + 1.0, horizontalSpeedNow * 4.2));
        double clearAhead = probeClearDistance(pos, dir, checkDistance);
        if (clearAhead > checkDistance * 0.88) return velocity;

        double clearLeft = probeClearDistance(pos, left, checkDistance * 0.9);
        double clearRight = probeClearDistance(pos, right, checkDistance * 0.9);
        double clearUpLeft = probeClearDistance(pos, left.add(0.0, 0.42, 0.0).normalize(), checkDistance * 0.85);
        double clearUpRight = probeClearDistance(pos, right.add(0.0, 0.42, 0.0).normalize(), checkDistance * 0.85);

        Vec3d bestEscape = dir;
        double bestEscapeClear = clearAhead;
        if (clearLeft > bestEscapeClear) {
            bestEscape = left;
            bestEscapeClear = clearLeft;
        }
        if (clearRight > bestEscapeClear) {
            bestEscape = right;
            bestEscapeClear = clearRight;
        }
        if (clearUpLeft > bestEscapeClear) {
            bestEscape = left.add(0.0, 0.42, 0.0).normalize();
            bestEscapeClear = clearUpLeft;
        }
        if (clearUpRight > bestEscapeClear) {
            bestEscape = right.add(0.0, 0.42, 0.0).normalize();
            bestEscapeClear = clearUpRight;
        }

        double speedFactor = MathHelper.clamp(clearAhead / checkDistance, 0.35, 1.0);
        double desiredHorizontal = horizontalSpeedNow * speedFactor;
        if (bestEscape != dir && bestEscapeClear > clearAhead + 0.35) {
            double steer = MathHelper.clamp((checkDistance - clearAhead) / checkDistance, 0.2, 0.72);
            Vec3d blended = dir.multiply(1.0 - steer).add(bestEscape.multiply(steer)).normalize();
            desiredHorizontal = horizontalSpeedNow * Math.max(0.45, speedFactor);
            velocity = new Vec3d(blended.x * desiredHorizontal, velocity.y, blended.z * desiredHorizontal);
        } else {
            double scale = Math.max(0.35, speedFactor);
            velocity = new Vec3d(velocity.x * scale, velocity.y, velocity.z * scale);
        }

        double vx = velocity.x;
        double vz = velocity.z;
        double vy = velocity.y;
        double floorDist = pathfinder.distanceToSolidBelow(pos, SAFETY_SCAN);
        double ceilingDist = pathfinder.distanceToSolidAbove(pos, SAFETY_SCAN);

        if (clearAhead < 0.8) {
            vx *= 0.55;
            vz *= 0.55;
            vy = Math.max(vy, 0.24);
            runtime.stuckTicks = Math.max(runtime.stuckTicks, SpearSpoofFlightPathSearch.STUCK_REPATH_TICKS);
        } else if (clearAhead < 1.35) {
            vy = Math.max(vy, 0.14);
        }

        if (floorDist < FLOOR_CLEARANCE + 0.45) vy = Math.max(vy, 0.10);
        if (ceilingDist < CEILING_CLEARANCE + 0.2) vy = Math.min(vy, 0.05);
        return new Vec3d(vx, vy, vz);
    }

    private Vec3d computeLocalAvoidance(Vec3d steerTarget, Vec3d baseVelocity, double horizontalCap, double maxVertical) {
        if (module.client().player == null) return Vec3d.ZERO;

        Vec3d pos = module.client().player.getEntityPos();
        Vec3d avoid = Vec3d.ZERO;
        double floorDist = pathfinder.distanceToSolidBelow(pos, SAFETY_SCAN);
        double ceilingDist = pathfinder.distanceToSolidAbove(pos, SAFETY_SCAN);

        if (floorDist < FLOOR_CLEARANCE) {
            double push = (FLOOR_CLEARANCE - floorDist) * 0.75;
            avoid = avoid.add(0.0, Math.min(maxVertical, push), 0.0);
        }
        if (ceilingDist < CEILING_CLEARANCE) {
            double push = (CEILING_CLEARANCE - ceilingDist) * 0.75;
            avoid = avoid.add(0.0, -Math.min(maxVertical, push), 0.0);
        }

        Vec3d heading = steerTarget.subtract(pos);
        double headingLen = horizontal(heading).length();
        if (headingLen < 1.0E-4) {
            heading = new Vec3d(baseVelocity.x, 0.0, baseVelocity.z);
            headingLen = horizontal(heading).length();
        }
        if (headingLen <= 1.0E-4) return avoid;

        Vec3d forward = new Vec3d(heading.x / headingLen, 0.0, heading.z / headingLen);
        double frontClear = probeClearDistance(pos, forward, OBSTACLE_LOOK_AHEAD);
        if (frontClear >= OBSTACLE_LOOK_AHEAD - 0.15) return avoid;

        Vec3d left = new Vec3d(-forward.z, 0.0, forward.x);
        Vec3d right = left.multiply(-1.0);
        Vec3d targetDir = normalizeOrFallback(steerTarget.subtract(pos), forward);
        Vec3d[] candidates = new Vec3d[] {
            left,
            right,
            left.add(forward.multiply(0.45)).normalize(),
            right.add(forward.multiply(0.45)).normalize(),
            left.add(0.0, 0.7, 0.0).normalize(),
            right.add(0.0, 0.7, 0.0).normalize(),
            new Vec3d(0.0, 1.0, 0.0),
            left.add(forward.multiply(-0.35)).normalize(),
            right.add(forward.multiply(-0.35)).normalize()
        };

        Vec3d bestDir = left;
        double bestScore = Double.NEGATIVE_INFINITY;
        double bestSpace = 0.0;
        for (Vec3d candidate : candidates) {
            double space = probeClearDistance(pos, candidate, OBSTACLE_LOOK_AHEAD);
            double align = candidate.dotProduct(targetDir);
            Vec3d sample = pos.add(candidate.multiply(Math.max(0.9, space * 0.65)));
            double sampleFloor = pathfinder.distanceToSolidBelow(sample, Math.max(6, SAFETY_SCAN / 2));
            double sampleCeiling = pathfinder.distanceToSolidAbove(sample, Math.max(6, SAFETY_SCAN / 2));
            int sideOpenings = pathfinder.countLateralOpenings(sample, 0.9);
            double score = space * 1.55 + align * 1.05 + sideOpenings * 0.3;
            if (sampleFloor < FLOOR_CLEARANCE) score -= (FLOOR_CLEARANCE - sampleFloor) * 1.4;
            if (sampleCeiling < CEILING_CLEARANCE) score -= (CEILING_CLEARANCE - sampleCeiling) * 1.4;
            if (candidate.y > 0.0 && floorDist < FLOOR_CLEARANCE + 0.7) score += 1.0;
            if (candidate.y > 0.0 && ceilingDist < CEILING_CLEARANCE + 0.7) score -= 0.8;
            if (score > bestScore) {
                bestScore = score;
                bestDir = candidate;
                bestSpace = space;
            }
        }

        double urgency = Math.max(0.0, 1.0 - bestSpace / OBSTACLE_LOOK_AHEAD);
        double sideSpeed = horizontalCap * (0.45 + urgency) * AVOIDANCE_STRENGTH;
        return avoid.add(bestDir.multiply(sideSpeed));
    }

    private double probeClearDistance(Vec3d start, Vec3d dir, double maxDistance) {
        double step = Math.max(0.25, COLLISION_STEP);
        for (double distance = step; distance <= maxDistance; distance += step) {
            if (!pathfinder.hasPlayerClearance(start.add(dir.multiply(distance)))) return distance - step;
        }
        return maxDistance;
    }

    private boolean isInDragState() {
        return module.client().player != null && isInsideSoftDragBlock(module.client().player.getBoundingBox());
    }

    private boolean isInsideSoftDragBlock(Box box) {
        int minX = MathHelper.floor(box.minX + 1.0E-4);
        int maxX = MathHelper.floor(box.maxX - 1.0E-4);
        int minY = MathHelper.floor(box.minY + 1.0E-4);
        int maxY = MathHelper.floor(box.maxY - 1.0E-4);
        int minZ = MathHelper.floor(box.minZ + 1.0E-4);
        int maxZ = MathHelper.floor(box.maxZ - 1.0E-4);

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    if (isSoftDragBlock(new BlockPos(x, y, z))) return true;
                }
            }
        }
        return false;
    }

    private boolean isSoftDragBlock(BlockPos pos) {
        var state = module.client().world.getBlockState(pos);
        return state.isOf(Blocks.COBWEB)
            || state.isOf(Blocks.VINE)
            || state.isOf(Blocks.WEEPING_VINES)
            || state.isOf(Blocks.WEEPING_VINES_PLANT)
            || state.isOf(Blocks.TWISTING_VINES)
            || state.isOf(Blocks.TWISTING_VINES_PLANT)
            || state.isOf(Blocks.CAVE_VINES)
            || state.isOf(Blocks.CAVE_VINES_PLANT);
    }

    private void tickStuckState(long now, Vec3d playerPos, Vec3d desiredVelocity, Vec3d targetPos, double engageMin) {
        if (runtime.lastStuckSampleMs <= 0L) {
            runtime.lastStuckSampleMs = now;
            runtime.lastStuckSamplePos = playerPos;
            runtime.stuckTicks = 0;
            return;
        }

        if (now - runtime.lastStuckSampleMs < STUCK_SAMPLE_MS) return;

        double moved = runtime.lastStuckSamplePos.distanceTo(playerPos);
        double desiredHorizontal = horizontal(desiredVelocity).length();
        double targetDistance = horizontal(playerPos.subtract(targetPos)).length();
        boolean tryingToMove = desiredHorizontal > 0.85;
        boolean crampedNearTarget = targetDistance < engageMin + 0.22;
        boolean gliding = module.client().player != null && module.client().player.isGliding();
        boolean blocked = tryingToMove && gliding && moved <= STUCK_MOVE_EPS && !crampedNearTarget;

        if (blocked) runtime.stuckTicks++;
        else runtime.stuckTicks = Math.max(0, runtime.stuckTicks - 1);

        if (runtime.stuckTicks >= STUCK_TICKS_LIMIT) {
            runtime.unstuckUntilMs = now + UNSTUCK_BURST_MS;
            runtime.stuckTicks = 0;
            runtime.beginReset(now, 280L);
        } else if (runtime.unstuckUntilMs > 0L && now >= runtime.unstuckUntilMs) {
            runtime.unstuckUntilMs = 0L;
        }

        runtime.lastStuckSampleMs = now;
        runtime.lastStuckSamplePos = playerPos;
    }

    private void ensureElytraRelaunch() {
        if (!onlyWhileElytra.get()) return;
        if (module.client().player.isGliding()) {
            runtime.relaunchGroundTicks = 0;
            runtime.relaunchJumpTicks = 0;
            return;
        }
        if (!hasUsableElytra()) return;

        // AutoWasp parity: in liquids we retry START_FALL_FLYING much faster.
        boolean inLiquid = module.client().player.isTouchingWater() || module.client().player.isInLava();

        runtime.relaunchJumpTicks++;
        if (module.client().player.isOnGround()) {
            module.client().player.jump();
            return;
        }

        int reopenDelay = inLiquid ? 1 : 4;
        if (runtime.relaunchJumpTicks < reopenDelay) return;
        runtime.relaunchJumpTicks = 0;

        module.client().player.setJumping(false);
        module.client().player.setSprinting(true);

        // AutoWasp-style relaunch: no extra packet throttle, especially important while touching liquids.
        if (module.client().player.networkHandler != null) {
            module.client().player.networkHandler.sendPacket(
                new ClientCommandC2SPacket(module.client().player, ClientCommandC2SPacket.Mode.START_FALL_FLYING)
            );
        }
    }

    private boolean hasUsableElytra() {
        ItemStack chest = module.client().player.getEquippedStack(EquipmentSlot.CHEST);
        if (chest == null || chest.isEmpty()) return false;
        if (!chest.contains(DataComponentTypes.GLIDER)) return false;
        return !chest.isDamageable() || chest.getDamage() < chest.getMaxDamage() - 1;
    }

    private Vec3d applyDimensionFlightLimit(Vec3d velocity, Vec3d currentPos) {
        if (module.client().world == null || module.client().world.getRegistryKey() != World.NETHER) return velocity;

        double y = currentPos.y;
        double vy = velocity.y;
        if (y >= NETHER_MAX_FLIGHT_Y) vy = Math.min(vy, -0.10);
        else if (y >= NETHER_MAX_FLIGHT_Y - 0.75) vy = Math.min(vy, 0.0);
        else if (y >= NETHER_MAX_FLIGHT_Y - 1.5) vy = Math.min(vy, 0.08);
        return new Vec3d(velocity.x, vy, velocity.z);
    }

    private double distanceFromPointToHitbox(Vec3d point, LivingEntity living) {
        Box hitbox = living.getBoundingBox();
        double x = MathHelper.clamp(point.x, hitbox.minX, hitbox.maxX);
        double y = MathHelper.clamp(point.y, hitbox.minY, hitbox.maxY);
        double z = MathHelper.clamp(point.z, hitbox.minZ, hitbox.maxZ);
        return point.distanceTo(new Vec3d(x, y, z));
    }

    private static Vec3d horizontal(Vec3d value) {
        return new Vec3d(value.x, 0.0, value.z);
    }

    private static Vec3d normalizeOrFallback(Vec3d value, Vec3d fallback) {
        if (value != null && value.lengthSquared() > 1.0E-6) return value.normalize();
        if (fallback != null && fallback.lengthSquared() > 1.0E-6) return fallback.normalize();
        return Vec3d.ZERO;
    }

    private static String format2(double value) {
        return String.format(java.util.Locale.US, "%.2f", value);
    }
}
