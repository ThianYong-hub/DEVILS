package com.devils.addon.modules;

import com.devils.addon.DevilsAddon;
import com.devils.addon.modules.spearspoof.SpearSpoofCombatService;
import com.devils.addon.modules.spearspoof.SpearSpoofDevDebugService;
import com.devils.addon.modules.spearspoof.SpearSpoofDebugLogger;
import com.devils.addon.modules.spearspoof.SpearSpoofEventService;
import com.devils.addon.modules.spearspoof.SpearSpoofFlightPathfinder;
import com.devils.addon.modules.spearspoof.SpearSpoofFlightService;
import com.devils.addon.modules.spearspoof.SpearSpoofRuntime;
import com.devils.addon.modules.spearspoof.SpearSpoofTargetingService;
import com.devils.addon.util.CrashGuard;
import meteordevelopment.meteorclient.events.entity.player.PlayerMoveEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.entity.SortPriority;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.MinecraftClient;

public class SpearSpoof extends Module {
    private static final double PERMANENT_TARGET_RANGE = 200.0;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgTargeting = settings.createGroup("Targeting");
    private final SettingGroup sgMovement = settings.createGroup("Movement");
    private final SettingGroup sgFlight = settings.createGroup("Flight");
    private final SettingGroup sgStrike = settings.createGroup("Strike");
    private final SettingGroup sgDebug = settings.createGroup("Debug");

    private final SettingGroup sgRender = settings.createGroup("Render");

    public final Setting<Boolean> enableAutoFlight = sgFlight.add(new BoolSetting.Builder()
        .name("enable-auto-flight")
        .description("Automatically fly towards target. If false, you can use normal Flight module manually.")
        .defaultValue(true)
        .build()
    );

    public final Setting<Boolean> renderTarget = sgRender.add(new BoolSetting.Builder()
        .name("render-target")
        .description("Highlight the current target.")
        .defaultValue(true)
        .build()
    );

    public final Setting<Double> nearRange = sgRender.add(new DoubleSetting.Builder()
        .name("near-range")
        .description("Range for near color.")
        .defaultValue(7.0)
        .min(1.0)
        .sliderRange(1.0, 16.0)
        .visible(renderTarget::get)
        .build()
    );

    public final Setting<SettingColor> nearColor = sgRender.add(new ColorSetting.Builder()
        .name("near-color")
        .description("Color when target is near.")
        .defaultValue(new SettingColor(255, 230, 40, 90))
        .visible(renderTarget::get)
        .build()
    );

    public final Setting<SettingColor> farColor = sgRender.add(new ColorSetting.Builder()
        .name("far-color")
        .description("Color when target is far.")
        .defaultValue(new SettingColor(70, 255, 90, 80))
        .visible(renderTarget::get)
        .build()
    );

    public final Setting<Double> dashDistance = sgStrike.add(new DoubleSetting.Builder()
        .name("dash-distance")
        .description("Extra distance applied when attack starts (in blocks).")
        .defaultValue(0.0)
        .min(0.0)
        .sliderRange(0.0, 10.0)
        .build()
    );

    public final Setting<Double> dashSpeed = sgStrike.add(new DoubleSetting.Builder()
        .name("dash-speed")
        .description("Speed of dash in blocks per tick.")
        .defaultValue(0.35)
        .min(0.01)
        .sliderRange(0.01, 2.0)
        .visible(() -> dashDistance.get() > 0.0)
        .build()
    );

    public final Setting<Boolean> stayGrounded = sgStrike.add(new BoolSetting.Builder()
        .name("dash-stay-grounded")
        .description("Drops vertical boost motion during dash.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> onlyWhileElytra = sgGeneral.add(new BoolSetting.Builder()
        .name("only-while-elytra")
        .description("Run logic only while gliding with elytra.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> autoSwitch = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-switch")
        .description("Switch to spear from hotbar automatically.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> autoHoldUse = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-hold-use")
        .description("Hold RMB to maintain spear windup.")
        .defaultValue(true)
        .visible(this::showAdvanced)
        .build()
    );

    private final Setting<Boolean> rotate = sgGeneral.add(new BoolSetting.Builder()
        .name("rotate")
        .description("Rotate to predicted hitbox before attack.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> yawCamera = sgGeneral.add(new BoolSetting.Builder()
        .name("yaw-camera")
        .description("Force camera yaw/pitch to attack rotation.")
        .defaultValue(true)
        .visible(rotate::get)
        .build()
    );

    private final Setting<TargetEntityMode> targetEntity = sgTargeting.add(new EnumSetting.Builder<TargetEntityMode>()
        .name("target-entity")
        .description("Who to target.")
        .defaultValue(TargetEntityMode.Players)
        .build()
    );

    private final Setting<SortPriority> priority = sgTargeting.add(new EnumSetting.Builder<SortPriority>()
        .name("priority")
        .description("Target sorting mode.")
        .defaultValue(SortPriority.ClosestAngle)
        .build()
    );

    private final Setting<Boolean> ignoreFriends = sgTargeting.add(new BoolSetting.Builder()
        .name("ignore-friends")
        .description("Do not target friends.")
        .defaultValue(true)
        .visible(() -> targetEntity.get() == TargetEntityMode.Players || targetEntity.get() == TargetEntityMode.Any)
        .build()
    );

    private final Setting<Integer> retargetDelayMs = sgTargeting.add(new IntSetting.Builder()
        .name("retarget-delay-ms")
        .description("Delay before reacquiring a target after loss.")
        .defaultValue(80)
        .range(0, 1000)
        .sliderRange(0, 500)
        .visible(this::showAdvanced)
        .build()
    );

    private final Setting<Double> minSpeedBps = sgMovement.add(new DoubleSetting.Builder()
        .name("min-speed-bps")
        .description("Minimum horizontal speed for valid spear damage.")
        .defaultValue(4.6)
        .range(0.0, 20.0)
        .sliderRange(0.0, 12.0)
        .visible(this::showAdvanced)
        .build()
    );

    private final Setting<Double> minForwardDot = sgMovement.add(new DoubleSetting.Builder()
        .name("min-forward-dot")
        .description("How aligned movement direction must be to target.")
        .defaultValue(0.18)
        .range(-1.0, 1.0)
        .sliderRange(-0.2, 0.8)
        .visible(this::showAdvanced)
        .build()
    );

    private final Setting<Double> minClosingSpeedBps = sgMovement.add(new DoubleSetting.Builder()
        .name("min-closing-speed-bps")
        .description("Minimum relative closing speed toward target.")
        .defaultValue(-0.1)
        .range(-10.0, 10.0)
        .sliderRange(-3.0, 4.0)
        .visible(this::showAdvanced)
        .build()
    );

    private final Setting<Double> maxVerticalDelta = sgMovement.add(new DoubleSetting.Builder()
        .name("max-vertical-delta")
        .description("Maximum allowed vertical difference to aim point.")
        .defaultValue(4.0)
        .range(0.5, 8.0)
        .sliderRange(1.0, 6.0)
        .build()
    );

    private final Setting<Double> horizontalSpeed = sgFlight.add(new DoubleSetting.Builder()
        .name("horizontal-speed")
        .description("Horizontal chase speed.")
        .defaultValue(2.4)
        .range(0.2, 4.0)
        .sliderRange(0.4, 4.0)
        .build()
    );

    private final Setting<Double> verticalSpeed = sgFlight.add(new DoubleSetting.Builder()
        .name("vertical-speed")
        .description("Vertical correction speed.")
        .defaultValue(1.0)
        .range(0.1, 3.0)
        .sliderRange(0.2, 3.0)
        .build()
    );

    private final Setting<Double> approachRange = sgFlight.add(new DoubleSetting.Builder()
        .name("approach-range")
        .description("Desired range while approaching target.")
        .defaultValue(3.7)
        .range(2.0, 5.5)
        .sliderRange(2.5, 4.8)
        .visible(this::showAdvanced)
        .build()
    );

    private final Setting<Double> retreatRange = sgFlight.add(new DoubleSetting.Builder()
        .name("retreat-range")
        .description("Desired range while reset phase is active.")
        .defaultValue(4.2)
        .range(2.5, 6.0)
        .sliderRange(3.0, 5.2)
        .visible(this::showAdvanced)
        .build()
    );

    private final Setting<Boolean> topDownEnabled = sgFlight.add(new BoolSetting.Builder()
        .name("top-down-enabled")
        .description("Allow top-down dive approach on suitable targets.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> topDownHeight = sgFlight.add(new DoubleSetting.Builder()
        .name("top-down-height")
        .description("Height above target used for top-down approach.")
        .defaultValue(2.8)
        .range(1.2, 5.0)
        .sliderRange(1.5, 4.0)
        .visible(this::showAdvanced)
        .build()
    );

    private final Setting<Boolean> obstacleAvoidance = sgFlight.add(new BoolSetting.Builder()
        .name("obstacle-avoidance")
        .description("Enable local obstacle avoidance while gliding.")
        .defaultValue(true)
        .visible(this::showAdvanced)
        .build()
    );

    private final Setting<Boolean> mode4x = sgFlight.add(new BoolSetting.Builder()
        .name("mode-4x")
        .description("Run-up spear loop: retreat 5 blocks, approach 4 blocks, after hit retreat 2 blocks.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> autoRelaunch = sgFlight.add(new BoolSetting.Builder()
        .name("auto-relaunch")
        .description("Attempt to restart elytra glide when it drops.")
        .defaultValue(true)
        .visible(onlyWhileElytra::get)
        .build()
    );

    private final Setting<Double> minRange = sgStrike.add(new DoubleSetting.Builder()
        .name("min-range")
        .description("Minimum eye-to-hitbox strike distance. Never goes below the spear's own 1.875 dead zone.")
        .defaultValue(3.0)
        .range(0.0, 4.0)
        .sliderRange(0.0, 3.0)
        .build()
    );

    private final Setting<Double> maxRange = sgStrike.add(new DoubleSetting.Builder()
        .name("max-range")
        .description("Maximum eye-to-hitbox strike distance. Capped by the spear's own reach, plus the lunge bonus.")
        .defaultValue(4.5)
        .range(2.5, 6.0)
        .sliderRange(3.0, 5.0)
        .build()
    );

    private final Setting<Double> smallTargetRange = sgStrike.add(new DoubleSetting.Builder()
        .name("small-target-range")
        .description("Maximum strike distance for small/flying targets.")
        .defaultValue(4.25)
        .range(2.5, 6.0)
        .sliderRange(3.0, 5.0)
        .visible(this::showAdvanced)
        .build()
    );

    private final Setting<Boolean> requireLineOfSight = sgStrike.add(new BoolSetting.Builder()
        .name("require-line-of-sight")
        .description("Reject strike when target is not visible.")
        .defaultValue(true)
        .visible(this::showAdvanced)
        .build()
    );

    private final Setting<Boolean> debugAttemptLog = sgDebug.add(new BoolSetting.Builder()
        .name("debug-attempt-log")
        .description("Write detailed decision traces to spearspoof/attempt-debug.log.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> clearDebugOnEnable = sgDebug.add(new BoolSetting.Builder()
        .name("clear-debug-on-enable")
        .description("Clear attempt-debug.log when module is enabled.")
        .defaultValue(true)
        .visible(debugAttemptLog::get)
        .build()
    );

    private final Setting<Boolean> debugPacketLog = sgDebug.add(new BoolSetting.Builder()
        .name("debug-packet-log")
        .description("Write packet timeline to spearspoof/packet-debug.log for desync analysis.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> clearPacketDebugOnEnable = sgDebug.add(new BoolSetting.Builder()
        .name("clear-packet-debug-on-enable")
        .description("Clear packet-debug.log when module is enabled.")
        .defaultValue(true)
        .visible(debugPacketLog::get)
        .build()
    );

    private final Setting<Boolean> devDebug = sgDebug.add(new BoolSetting.Builder()
        .name("dev-debug")
        .description("Disable automation and only log manual controls/hits into spearspoof/dev-debug.log.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> clearDevDebugOnEnable = sgDebug.add(new BoolSetting.Builder()
        .name("clear-dev-debug-on-enable")
        .description("Clear dev-debug.log when module is enabled.")
        .defaultValue(true)
        .visible(devDebug::get)
        .build()
    );

    private final Setting<Integer> devDebugIntervalMs = sgDebug.add(new IntSetting.Builder()
        .name("dev-debug-interval-ms")
        .description("How often Dev Debug state snapshots are written.")
        .defaultValue(180)
        .range(50, 5000)
        .sliderRange(50, 1000)
        .visible(devDebug::get)
        .build()
    );

    private final Setting<Boolean> testFlyUntilDamage = sgDebug.add(new BoolSetting.Builder()
        .name("test-fly-until-damage")
        .description("Test mode: keep pushing into target and do not retreat until hit is confirmed.")
        .defaultValue(false)
        .build()
    );

    private final SpearSpoofRuntime runtime = new SpearSpoofRuntime();

    private final SpearSpoofTargetingService targetingService = new SpearSpoofTargetingService(
        this,
        priority,
        targetEntity,
        ignoreFriends,
        retargetDelayMs
    );

    private final SpearSpoofDebugLogger debugLogger = new SpearSpoofDebugLogger(debugAttemptLog, debugPacketLog, devDebug);
    private final SpearSpoofFlightPathfinder flightPathfinder = new SpearSpoofFlightPathfinder(this);
    private final SpearSpoofCombatService combatService = new SpearSpoofCombatService(
        this,
        runtime,
        targetingService,
        debugLogger,
        onlyWhileElytra,
        autoSwitch,
        autoHoldUse,
        rotate,
        yawCamera,
        mode4x,
        maxVerticalDelta,
        minSpeedBps,
        minForwardDot,
        minClosingSpeedBps,
        minRange,
        maxRange,
        requireLineOfSight
    );

    private final SpearSpoofFlightService flightService = new SpearSpoofFlightService(
        this,
        runtime,
        flightPathfinder,
        targetingService,
        combatService,
        debugLogger,
        onlyWhileElytra,
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

    private final SpearSpoofDevDebugService devDebugService = new SpearSpoofDevDebugService(
        this,
        runtime,
        targetingService,
        combatService,
        flightService,
        debugLogger,
        devDebugIntervalMs
    );

    private final SpearSpoofEventService eventService = new SpearSpoofEventService(
        this,
        runtime,
        combatService,
        flightService,
        devDebugService,
        debugLogger,
        devDebug,
        debugPacketLog,
        renderTarget,
        nearRange,
        nearColor,
        farColor
    );

    public SpearSpoof() {
        super(DevilsAddon.CATEGORY, "spear-spoof", "Full spear FSM: targeting, movement controller, attack contour and debug pipeline.");
    }

    @Override
    public void onActivate() {
        runtime.resetOnActivate();
        debugLogger.onActivate(clearDebugOnEnable.get(), clearPacketDebugOnEnable.get(), clearDevDebugOnEnable.get());
        devDebugService.onDisable();
    }

    @Override
    public void onDeactivate() {
        devDebugService.onDisable();
        combatService.onDeactivate();
        flightService.onDeactivate();
        runtime.resetOnDeactivate();
        debugLogger.onDeactivate();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        CrashGuard.run(this, "onTickPre", eventService::onTickSafe);
    }

    @EventHandler
    private void onMove(PlayerMoveEvent event) {
        CrashGuard.run(this, "onMove", () -> eventService.onMoveSafe(event));
    }

    @EventHandler
    private void onPacketSend(PacketEvent.Send event) {
        CrashGuard.run(this, "onPacketSend", () -> eventService.onPacketSendSafe(event));
    }

    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        CrashGuard.run(this, "onPacketReceive", () -> eventService.onPacketReceiveSafe(event));
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        CrashGuard.run(this, "onRender3D", () -> eventService.onRender3DSafe(event));
    }

    private boolean showAdvanced() {
        return false;
    }

    public double permanentTargetRange() {
        return PERMANENT_TARGET_RANGE;
    }

    public MinecraftClient client() {
        return mc;
    }

    public enum TargetEntityMode {
        Players("Players"),
        Passive("Passive"),
        Hostile("Hostile"),
        Any("Any");

        private final String title;

        TargetEntityMode(String title) {
            this.title = title;
        }

        @Override
        public String toString() {
            return title;
        }
    }
}
