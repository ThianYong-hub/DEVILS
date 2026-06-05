package com.devils.addon.modules;

import com.devils.addon.DevilsAddon;
import com.devils.addon.modules.spearspoof.SpearSpoofCombatService;
import com.devils.addon.modules.spearspoof.SpearSpoofDevDebugService;
import com.devils.addon.modules.spearspoof.SpearSpoofDebugLogger;
import com.devils.addon.modules.spearspoof.SpearSpoofFlightPathfinder;
import com.devils.addon.modules.spearspoof.SpearSpoofFlightService;
import com.devils.addon.modules.spearspoof.SpearSpoofRuntime;
import com.devils.addon.modules.spearspoof.SpearSpoofTargetingService;
import com.devils.addon.util.CrashGuard;
import meteordevelopment.meteorclient.events.entity.player.PlayerMoveEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.mixininterface.IPlayerInteractEntityC2SPacket;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.entity.SortPriority;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.MinecraftClient;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractEntityC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.network.packet.s2c.play.DamageTiltS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityStatusS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityTrackerUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityVelocityUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.HealthUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerPositionLookS2CPacket;

import java.lang.reflect.Method;

public class SpearSpoof extends Module {
    private static final double PERMANENT_TARGET_RANGE = 200.0;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgTargeting = settings.createGroup("Targeting");
    private final SettingGroup sgMovement = settings.createGroup("Movement");
    private final SettingGroup sgFlight = settings.createGroup("Flight");
    private final SettingGroup sgStrike = settings.createGroup("Strike");
    private final SettingGroup sgStage = settings.createGroup("Stage");
    private final SettingGroup sgRecovery = settings.createGroup("Recovery");
    private final SettingGroup sgDebug = settings.createGroup("Debug");

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

    private final Setting<Boolean> autoRestartWindup = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-restart-windup")
        .description("Force restart windup when spear enters recovery stage.")
        .defaultValue(true)
        .visible(this::showAdvanced)
        .build()
    );

    private final Setting<Boolean> attributeSwap = sgGeneral.add(new BoolSetting.Builder()
        .name("attribute-swap")
        .description("Swap off spear briefly after successful hit.")
        .defaultValue(false)
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

    private final Setting<Integer> targetStickMs = sgTargeting.add(new IntSetting.Builder()
        .name("target-stick-ms")
        .description("Minimum lock time before switching to a better target.")
        .defaultValue(700)
        .range(0, 5000)
        .sliderRange(0, 2000)
        .visible(this::showAdvanced)
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
        .defaultValue(2.4)
        .range(0.5, 6.0)
        .sliderRange(1.0, 4.0)
        .visible(this::showAdvanced)
        .build()
    );

    private final Setting<Double> maxYawError = sgMovement.add(new DoubleSetting.Builder()
        .name("max-yaw-error")
        .description("Maximum yaw mismatch (degrees) before attack.")
        .defaultValue(34.0)
        .range(1.0, 180.0)
        .sliderRange(5.0, 60.0)
        .visible(this::showAdvanced)
        .build()
    );

    private final Setting<Double> maxPitchError = sgMovement.add(new DoubleSetting.Builder()
        .name("max-pitch-error")
        .description("Maximum pitch mismatch (degrees) before attack.")
        .defaultValue(30.0)
        .range(1.0, 90.0)
        .sliderRange(5.0, 45.0)
        .visible(this::showAdvanced)
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
        .description("Minimum strike distance.")
        .defaultValue(3.0)
        .range(0.0, 4.0)
        .sliderRange(0.0, 3.0)
        .visible(this::showAdvanced)
        .build()
    );

    private final Setting<Double> maxRange = sgStrike.add(new DoubleSetting.Builder()
        .name("max-range")
        .description("Maximum strike distance for normal targets.")
        .defaultValue(4.5)
        .range(2.5, 6.0)
        .sliderRange(3.0, 5.0)
        .visible(this::showAdvanced)
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

    private final Setting<Double> minCooldown = sgStrike.add(new DoubleSetting.Builder()
        .name("min-cooldown")
        .description("Required vanilla attack cooldown progress.")
        .defaultValue(0.92)
        .range(0.0, 1.0)
        .sliderRange(0.75, 1.0)
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

    private final Setting<Integer> minWindupMs = sgStage.add(new IntSetting.Builder()
        .name("min-windup-ms")
        .description("Minimum RMB hold before strike.")
        .defaultValue(180)
        .range(0, 1000)
        .sliderRange(0, 500)
        .visible(this::showAdvanced)
        .build()
    );

    private final Setting<Integer> readyWindowMs = sgStage.add(new IntSetting.Builder()
        .name("ready-window-ms")
        .description("Duration of spear READY stage after windup.")
        .defaultValue(5000)
        .range(100, 10000)
        .sliderRange(400, 5000)
        .visible(this::showAdvanced)
        .build()
    );

    private final Setting<Integer> fatigueWindowMs = sgStage.add(new IntSetting.Builder()
        .name("fatigue-window-ms")
        .description("Duration of spear FATIGUE stage after READY.")
        .defaultValue(2600)
        .range(100, 10000)
        .sliderRange(400, 5000)
        .visible(this::showAdvanced)
        .build()
    );

    private final Setting<Integer> recoveryDelayMs = sgStage.add(new IntSetting.Builder()
        .name("recovery-delay-ms")
        .description("Extra delay after successful hit before next attempt.")
        .defaultValue(70)
        .range(0, 1000)
        .sliderRange(0, 300)
        .visible(this::showAdvanced)
        .build()
    );

    private final Setting<Boolean> adaptiveReposition = sgRecovery.add(new BoolSetting.Builder()
        .name("adaptive-reposition")
        .description("Pause attack attempts after repeated speed/forward rejects.")
        .defaultValue(true)
        .visible(this::showAdvanced)
        .build()
    );

    private final Setting<Integer> repositionRejectStreak = sgRecovery.add(new IntSetting.Builder()
        .name("reposition-reject-streak")
        .description("Reject streak needed to trigger reposition lock.")
        .defaultValue(4)
        .range(1, 20)
        .sliderRange(1, 10)
        .visible(this::showAdvanced)
        .build()
    );

    private final Setting<Integer> repositionHoldMs = sgRecovery.add(new IntSetting.Builder()
        .name("reposition-hold-ms")
        .description("How long attack attempts are paused during reposition.")
        .defaultValue(260)
        .range(0, 5000)
        .sliderRange(0, 2000)
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
        targetStickMs,
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
        autoRestartWindup,
        attributeSwap,
        rotate,
        yawCamera,
        mode4x,
        maxVerticalDelta,
        minSpeedBps,
        minForwardDot,
        minClosingSpeedBps,
        minCooldown,
        maxYawError,
        maxPitchError,
        minWindupMs,
        readyWindowMs,
        fatigueWindowMs,
        recoveryDelayMs,
        requireLineOfSight,
        adaptiveReposition,
        repositionRejectStreak,
        repositionHoldMs
    );

    private final SpearSpoofFlightService flightService = new SpearSpoofFlightService(
        this,
        runtime,
        flightPathfinder,
        targetingService,
        combatService,
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

    private final SpearSpoofDevDebugService devDebugService = new SpearSpoofDevDebugService(
        this,
        runtime,
        targetingService,
        combatService,
        flightService,
        debugLogger,
        devDebugIntervalMs
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
        CrashGuard.run(this, "onTickPre", this::onTickSafe);
    }

    @EventHandler
    private void onMove(PlayerMoveEvent event) {
        CrashGuard.run(this, "onMove", () -> onMoveSafe(event));
    }

    @EventHandler
    private void onPacketSend(PacketEvent.Send event) {
        CrashGuard.run(this, "onPacketSend", () -> onPacketSendSafe(event));
    }

    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        CrashGuard.run(this, "onPacketReceive", () -> onPacketReceiveSafe(event));
    }

    private void onTickSafe() {
        if (devDebug.get()) {
            devDebugService.onTick();
            return;
        }

        devDebugService.onDisable();

        combatService.onTick();
        flightService.onTick();
    }

    private void onMoveSafe(PlayerMoveEvent event) {
        if (devDebug.get()) return;
        flightService.onMove(event);
    }

    private void onPacketSendSafe(PacketEvent.Send event) {
        if (event == null || event.packet == null) return;
        if (devDebug.get()) devDebugService.onPacketSend(event.packet);
        if (!isInterestingSendPacket(event.packet)) return;
        debugLogger.logPacketSend(event.packet, describeSendPacket(event.packet), runtime);
    }

    private void onPacketReceiveSafe(PacketEvent.Receive event) {
        if (event == null || event.packet == null) return;
        if (devDebug.get()) devDebugService.onPacketReceive(event.packet);
        else combatService.onPacketReceive(event.packet);
        boolean interesting = isInterestingReceivePacket(event.packet);
        if (!interesting && !debugPacketLog.get()) return;
        debugLogger.logPacketReceive(event.packet, describeReceivePacket(event.packet), runtime);
    }

    private boolean isInterestingSendPacket(Object packet) {
        return packet instanceof IPlayerInteractEntityC2SPacket
            || packet instanceof ClientCommandC2SPacket
            || packet instanceof PlayerMoveC2SPacket;
    }

    private boolean isInterestingReceivePacket(Object packet) {
        return packet instanceof EntityStatusS2CPacket
            || packet instanceof EntityTrackerUpdateS2CPacket
            || packet instanceof EntityVelocityUpdateS2CPacket
            || packet instanceof PlayerPositionLookS2CPacket
            || packet instanceof HealthUpdateS2CPacket
            || packet instanceof DamageTiltS2CPacket
            || packet instanceof EntityS2CPacket;
    }

    private String describeSendPacket(Object packet) {
        StringBuilder detail = new StringBuilder();
        detail.append("name=").append(packet.getClass().getSimpleName());

        if (packet instanceof IPlayerInteractEntityC2SPacket interact) {
            detail.append(" type=").append(interact.meteor$getType());
            if (interact.meteor$getType() == PlayerInteractEntityC2SPacket.InteractType.ATTACK) detail.append(" attack=true");
            if (interact.meteor$getEntity() != null) {
                detail.append(" entityId=").append(interact.meteor$getEntity().getId());
                detail.append(" entity=").append(interact.meteor$getEntity().getName().getString());
            }
        }

        if (packet instanceof ClientCommandC2SPacket) {
            Object mode = invokeNoArg(packet, "getMode");
            if (mode == null) mode = invokeNoArg(packet, "mode");
            if (mode != null) detail.append(" mode=").append(mode);
        }

        Object onGround = invokeNoArg(packet, "isOnGround");
        if (onGround instanceof Boolean value) detail.append(" onGround=").append(value);

        return detail.toString();
    }

    private String describeReceivePacket(Object packet) {
        StringBuilder detail = new StringBuilder();
        String name = packet.getClass().getSimpleName();
        detail.append("name=").append(name);

        Integer entityId = extractEntityId(packet);
        if (entityId != null) {
            detail.append(" entityId=").append(entityId);
        }

        Object status = invokeNoArg(packet, "getStatus");
        if (status != null) detail.append(" status=").append(status);

        if (packet instanceof PlayerPositionLookS2CPacket) {
            detail.append(" rubberband=true");
        }

        return detail.toString();
    }

    private Integer extractEntityId(Object packet) {
        Object value = invokeNoArg(packet, "getEntityId");
        if (value instanceof Integer i) return i;
        value = invokeNoArg(packet, "getId");
        if (value instanceof Integer i) return i;
        return null;
    }

    private Object invokeNoArg(Object target, String methodName) {
        if (target == null || methodName == null || methodName.isBlank()) return null;
        try {
            Method method = target.getClass().getMethod(methodName);
            method.setAccessible(true);
            return method.invoke(target);
        } catch (Throwable ignored) {
            return null;
        }
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
