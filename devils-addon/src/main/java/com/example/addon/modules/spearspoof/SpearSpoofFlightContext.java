package com.example.addon.modules.spearspoof;

import com.example.addon.modules.SpearSpoof;
import meteordevelopment.meteorclient.settings.Setting;


abstract class SpearSpoofFlightContext {
    protected static final double ENFORCED_MIN_RANGE = 0.40;
    protected static final double SMALL_TARGET_MIN_RANGE = 0.28;
    protected static final double ENFORCED_MAX_RANGE = 4.5;
    protected static final double FAR_CHASE_DIRECT_DISTANCE = 8.0;
    protected static final double FAR_CHASE_INTERCEPT_FACTOR = 0.12;
    protected static final double FAR_CHASE_INTERCEPT_MAX = 3.0;
    protected static final double SMALL_TARGET_MAX_WIDTH = 0.90;
    protected static final double SMALL_TARGET_MAX_HEIGHT = 1.10;
    protected static final double SOFT_KEEP_OUT_OFFSET = 0.12;
    protected static final double NETHER_MAX_FLIGHT_Y = 123.0;
    protected static final long STUCK_SAMPLE_MS = 120L;
    protected static final double STUCK_MOVE_EPS = 0.08;
    protected static final int STUCK_TICKS_LIMIT = 4;
    protected static final long UNSTUCK_BURST_MS = 420L;
    protected static final long RESET_MIN_DWELL_MS = 140L;
    protected static final double RESET_RETREAT_DISTANCE = 5.0;
    protected static final double SMALL_RESET_RETREAT_DISTANCE = 5.0;
    protected static final double RESET_VERTICAL_RETREAT_BLOCKS = 5.0;
    protected static final double RESET_VERTICAL_RETREAT_BLOCKS_RECHARGE = 5.6;
    protected static final double RECHARGE_RETREAT_DISTANCE = 5.0;
    protected static final double SMALL_RECHARGE_RETREAT_DISTANCE = 5.0;
    protected static final double MAX_RESET_RETREAT_DISTANCE = 6.0;
    protected static final double RESET_VERTICAL_RETREAT_EPS = 0.10;
    protected static final long RESET_VERTICAL_RETREAT_FAILSAFE_MS = 1200L;
    protected static final long SMALL_RESET_HOLD_MS = 170L;
    protected static final long DEFAULT_RESET_HOLD_MS = 220L;
    protected static final double PLAIN_ALIGN_Y_THRESHOLD = 0.65;
    protected static final double PLAIN_ALIGN_VERTICAL_MIN = 0.42;
    protected static final double PLAIN_DESCEND_START_DISTANCE = 7.0;
    protected static final double PLAIN_DESCEND_START_HORIZONTAL = 4.8;
    protected static final double PLAIN_BLOCKED_ASCEND_STEP = 1.35;
    protected static final double PLAIN_BLOCKED_ASCEND_ABOVE_TARGET = 1.80;
    protected static final double PLAIN_BLOCKED_ASCEND_MAX_ABOVE_TARGET = 10.0;
    protected static final double PLAIN_BLOCKED_ROUTE_DISTANCE = 12.0;
    protected static final double VERTICAL_DIVE_TRIGGER = -0.55;
    protected static final double CAVE_ESCAPE_TRIGGER = 1.20;
    protected static final double VERTICAL_ROUTE_HEIGHT = 3.0;
    protected static final double VERTICAL_ROUTE_TARGET_BELOW_Y = -1.0;
    protected static final double VERTICAL_ROUTE_ASCEND_MARGIN = 0.35;
    protected static final double VERTICAL_ROUTE_DIVE_RADIUS = 1.65;
    protected static final double PIT_ROUTE_SELF_ASCEND_MIN = 1.35;
    protected static final double PIT_ROUTE_EXTRA_HEIGHT = 1.35;
    protected static final double PIT_ROUTE_DIVE_RADIUS = 1.20;
    protected static final double PIT_ROUTE_DIVE_READY_ABOVE = 1.05;
    protected static final double PIT_AXIS_LOCK_RADIUS = 0.70;
    protected static final double PIT_AXIS_LOCK_MIN_SPEED = 0.55;
    protected static final double PIT_AXIS_LOCK_MAX_SPEED = 1.25;
    protected static final double PIT_BLOCKED_ASCEND_STEP = 1.20;
    protected static final double PIT_BLOCKED_ASCEND_ABOVE_ROUTE = 2.00;
    protected static final double PIT_BLOCKED_ASCEND_MAX_ABOVE_TARGET = 8.0;
    protected static final double PIT_ROUTE_HEIGHT_MIN = 2.25;
    protected static final double PIT_ROUTE_HEIGHT_MAX = 3.05;
    protected static final double PIT_ROUTE_HEIGHT_OFFSET = 0.45;
    protected static final double PIT_MIN_CLIMB_ABOVE_TARGET = 0.22;
    protected static final double PIT_NEAR_STRIKE_NO_CLIMB_RANGE = 0.90;
    protected static final double PIT_KEEP_OUT_SLACK = 0.18;
    protected static final double PIT_VERTICAL_ASCENT_MIN = 0.34;
    protected static final double PIT_VERTICAL_DESCENT_MIN = 0.42;
    protected static final long PIT_VERTICAL_LOCK_MS = 1800L;
    protected static final double HORIZONTAL_BLOCK_SCAN_STEP = 0.45;
    protected static final double HORIZONTAL_BLOCK_SCAN_MAX = 12.0;
    protected static final double PIT_BASIN_SAMPLE_RADIUS = 2.35;
    protected static final double PIT_BASIN_MIN_RIM_DELTA = 1.10;
    protected static final int PIT_BASIN_REQUIRED_RIM_SAMPLES = 4;
    protected static final double MIN_CRUISE_HORIZONTAL_SPEED = 2.4; // 48 bps
    protected static final double RESET_CRUISE_FACTOR = 1.00;
    protected static final long HIT_PIERCE_WINDOW_MS = 320L;
    protected static final double HIT_PIERCE_SPEED_FACTOR = 1.12;
    protected static final double PASS_THROUGH_REVERSE_DISTANCE = 1.45;
    protected static final double PASS_THROUGH_REVERSE_DOT = -0.30;
    protected static final long PASS_THROUGH_RESET_HOLD_MS_PLAYER = 190L;
    protected static final long PASS_THROUGH_RESET_HOLD_MS_OTHER = 150L;
    protected static final double PLAYER_HARD_KEEP_OUT_MIN = 0.95;
    protected static final double PLAYER_SOFT_KEEP_OUT_MIN = 1.25;
    protected static final double MODE_4X_RUNUP_DISTANCE = 5.0;
    protected static final double MODE_4X_MIN_RANGE = 4.0;
    protected static final double MODE_4X_MAX_RANGE = 4.5;
    protected static final double MODE_4X_APPROACH_DISTANCE = 4.25;
    protected static final double MODE_4X_APPROACH_STEP_EPS = 0.01;
    protected static final double MODE_4X_POST_HIT_RETREAT = 2.0;
    protected static final double MODE_4X_HARD_KEEP_OUT = 4.0;
    protected static final double MODE_4X_SOFT_KEEP_OUT = 4.35;
    protected static final long LOST_TARGET_FOLLOW_MS = 2600L;
    protected static final long LOST_TARGET_PATHING_MS = 1400L;
    protected static final double LOST_TARGET_MAX_PREDICT_TICKS = 12.0;
    protected static final double LOST_TARGET_IDLE_BRAKE_HORIZONTAL = 0.80;
    protected static final double LOST_TARGET_IDLE_BRAKE_VERTICAL = 0.55;
    protected static final double LOST_TARGET_FOLLOW_DAMPING = 0.94;
    protected static final double FLOOR_CLEARANCE = 2.2;
    protected static final double CEILING_CLEARANCE = 1.5;
    protected static final int SAFETY_SCAN = 14;
    protected static final double OBSTACLE_LOOK_AHEAD = 7.5;
    protected static final double AVOIDANCE_STRENGTH = 0.85;
    protected static final double COLLISION_STEP = 0.55;
    protected static final double PLAYER_GROUND_LANE_MIN_Y = 0.88;
    protected static final double PLAYER_GROUND_LANE_MAX_Y = 1.18;

    protected final SpearSpoof module;
    protected final SpearSpoofRuntime runtime;
    protected final SpearSpoofFlightPathfinder pathfinder;
    protected final SpearSpoofTargetingService targeting;
    protected final SpearSpoofCombatService combat;
    protected final SpearSpoofDebugLogger debugLogger;

    protected final Setting<Boolean> onlyWhileElytra;
    protected final Setting<Boolean> attributeSwap;
    protected final Setting<Double> horizontalSpeed;
    protected final Setting<Double> verticalSpeed;
    protected final Setting<Boolean> obstacleAvoidance;
    protected final Setting<Boolean> autoRelaunch;
    protected final Setting<Boolean> testFlyUntilDamage;
    protected final Setting<Boolean> mode4x;

    protected SpearSpoofFlightContext(
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

}
