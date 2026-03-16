package com.example.addon.modules;

import com.example.addon.AddonTemplate;
import com.example.addon.modules.antiwasp.AntiWaspPathMath;
import com.example.addon.modules.antiwasp.AntiWaspPathMath.FlightFigure;
import com.example.addon.util.CrashGuard;
import meteordevelopment.meteorclient.events.entity.player.PlayerMoveEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.mixininterface.IVec3d;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.hit.HitResult;
import net.minecraft.world.RaycastContext;

public class AntiWasp extends Module {
    private final AntiWaspObstacleAvoidance obstacleAvoidance = new AntiWaspObstacleAvoidance(this);
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgPath = settings.createGroup("Path");
    private final SettingGroup sgMovement = settings.createGroup("Movement");
    private final SettingGroup sgCamera = settings.createGroup("Camera");
    private final SettingGroup sgAvoidance = settings.createGroup("Avoidance");
    private final SettingGroup sgAssist = settings.createGroup("Assist");

    private final Setting<FlightFigure> figure = sgGeneral.add(new EnumSetting.Builder<FlightFigure>()
        .name("figure")
        .description("Primary figure.")
        .defaultValue(FlightFigure.Circle)
        .build()
    );

    private final Setting<Boolean> autoCycleFigures = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-cycle-figures")
        .description("Switches figure while module is active.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> switchInterval = sgGeneral.add(new IntSetting.Builder()
        .name("switch-interval")
        .description("Ticks between figure switches.")
        .defaultValue(320)
        .min(40)
        .sliderMax(1600)
        .visible(autoCycleFigures::get)
        .build()
    );

    private final Setting<Boolean> clockwise = sgGeneral.add(new BoolSetting.Builder()
        .name("clockwise")
        .description("Direction along path.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> figureSize = sgPath.add(new DoubleSetting.Builder()
        .name("figure-size")
        .description("Figure size in blocks. Square=side, Circle=diameter, Triangle=side.")
        .defaultValue(200.0)
        .min(40.0)
        .sliderRange(80.0, 600.0)
        .build()
    );

    private final Setting<Double> phaseStep = sgPath.add(new DoubleSetting.Builder()
        .name("phase-step")
        .description("Path speed multiplier. 1.0 = normal traversal speed for current horizontal-speed and figure size.")
        .defaultValue(1.0)
        .min(0.2)
        .max(3.0)
        .sliderRange(0.5, 2.0)
        .build()
    );

    private final Setting<Double> lookAhead = sgPath.add(new DoubleSetting.Builder()
        .name("look-ahead")
        .description("How far ahead on path to aim movement/camera.")
        .defaultValue(0.060)
        .min(0.0)
        .max(0.5)
        .sliderRange(0.01, 0.2)
        .build()
    );

    private final Setting<Double> horizontalSpeed = sgMovement.add(new DoubleSetting.Builder()
        .name("horizontal-speed")
        .description("Horizontal flight speed towards path.")
        .defaultValue(2.0)
        .min(0.1)
        .sliderMax(6.0)
        .build()
    );

    private final Setting<Double> verticalSpeed = sgMovement.add(new DoubleSetting.Builder()
        .name("vertical-speed")
        .description("Vertical correction speed.")
        .defaultValue(1.2)
        .min(0.05)
        .sliderMax(5.0)
        .build()
    );

    private final Setting<Double> altitudeOffset = sgMovement.add(new DoubleSetting.Builder()
        .name("altitude-offset")
        .description("Vertical offset from activation height.")
        .defaultValue(0.0)
        .min(-80.0)
        .max(80.0)
        .sliderRange(-20.0, 20.0)
        .build()
    );

    final Setting<Boolean> avoidObstacles = sgAvoidance.add(new BoolSetting.Builder()
        .name("avoid-obstacles")
        .description("Attempts to avoid blocks ahead using climb and side-step logic.")
        .defaultValue(true)
        .build()
    );

    final Setting<Double> obstacleLookAhead = sgAvoidance.add(new DoubleSetting.Builder()
        .name("obstacle-look-ahead")
        .description("How many blocks ahead to scan for collisions.")
        .defaultValue(8.0)
        .min(2.0)
        .max(20.0)
        .sliderRange(4.0, 14.0)
        .visible(avoidObstacles::get)
        .build()
    );

    final Setting<Double> obstacleClimbHeight = sgAvoidance.add(new DoubleSetting.Builder()
        .name("obstacle-climb-height")
        .description("Preferred climb height while avoiding frontal obstacles.")
        .defaultValue(2.2)
        .min(0.4)
        .max(6.0)
        .sliderRange(1.0, 4.0)
        .visible(avoidObstacles::get)
        .build()
    );

    final Setting<Double> obstacleClimbSpeed = sgAvoidance.add(new DoubleSetting.Builder()
        .name("obstacle-climb-speed")
        .description("Maximum extra vertical speed used when climbing over obstacles.")
        .defaultValue(1.8)
        .min(0.2)
        .max(5.0)
        .sliderRange(0.5, 3.0)
        .visible(avoidObstacles::get)
        .build()
    );

    final Setting<Double> obstacleSideBias = sgAvoidance.add(new DoubleSetting.Builder()
        .name("obstacle-side-bias")
        .description("How strongly to strafe sideways when avoiding.")
        .defaultValue(1.2)
        .min(0.2)
        .max(3.0)
        .sliderRange(0.5, 2.0)
        .visible(avoidObstacles::get)
        .build()
    );

    final Setting<Integer> obstacleMemoryTicks = sgAvoidance.add(new IntSetting.Builder()
        .name("obstacle-memory-ticks")
        .description("How long to keep the selected avoid side to prevent jitter.")
        .defaultValue(20)
        .min(2)
        .sliderMax(80)
        .visible(avoidObstacles::get)
        .build()
    );

    private final Setting<Boolean> rotateCamera = sgCamera.add(new BoolSetting.Builder()
        .name("rotate-camera")
        .description("Smoothly aligns camera with path direction.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> maxYawStep = sgCamera.add(new DoubleSetting.Builder()
        .name("max-yaw-step")
        .description("Maximum yaw change per tick.")
        .defaultValue(6.0)
        .min(0.5)
        .max(60.0)
        .sliderRange(1.0, 20.0)
        .visible(rotateCamera::get)
        .build()
    );

    private final Setting<Double> maxPitchStep = sgCamera.add(new DoubleSetting.Builder()
        .name("max-pitch-step")
        .description("Maximum pitch change per tick.")
        .defaultValue(4.0)
        .min(0.5)
        .max(45.0)
        .sliderRange(1.0, 15.0)
        .visible(rotateCamera::get)
        .build()
    );

    private final Setting<Double> yawDeadZone = sgCamera.add(new DoubleSetting.Builder()
        .name("yaw-dead-zone")
        .description("Ignore very small yaw corrections.")
        .defaultValue(0.20)
        .min(0.0)
        .max(5.0)
        .sliderRange(0.0, 1.0)
        .visible(rotateCamera::get)
        .build()
    );

    private final Setting<Boolean> autoWalk = sgAssist.add(new BoolSetting.Builder()
        .name("auto-walk")
        .description("Keeps forward key pressed.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> autoTakeoff = sgAssist.add(new BoolSetting.Builder()
        .name("auto-takeoff")
        .description("Auto-start elytra flight.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> useFireworks = sgAssist.add(new BoolSetting.Builder()
        .name("use-fireworks")
        .description("Uses rockets periodically.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> fireworkDelay = sgAssist.add(new IntSetting.Builder()
        .name("firework-delay")
        .description("Ticks between rocket uses.")
        .defaultValue(35)
        .min(5)
        .sliderMax(200)
        .visible(useFireworks::get)
        .build()
    );

    private Vec3d origin;
    private double phase;
    private FlightFigure activeFigure;

    private int figureTicks, fireworkTicks, takeoffCooldown, noRocketWarnCooldown;
    int avoidTicks, avoidStrafeDir;
    private Vec3d cameraOverrideDir = Vec3d.ZERO;

    private boolean forcedForward;
    private boolean prevForwardState;

    public AntiWasp() {
        super(AddonTemplate.CATEGORY, "anti-wasp", "Elytra flight patterns (circle, square, triangle) to evade wasp attacks.");
    }

    @Override
    public void onActivate() {
        if (mc.player == null) return;

        activeFigure = figure.get();
        phase = 0.0;
        figureTicks = 0;
        fireworkTicks = 0;
        takeoffCooldown = 0;
        noRocketWarnCooldown = 0;
        avoidTicks = 0;
        avoidStrafeDir = 0;
        cameraOverrideDir = Vec3d.ZERO;

        forcedForward = false;
        prevForwardState = mc.options.forwardKey.isPressed();

        origin = mc.player.getPos();
    }

    @Override
    public void onDeactivate() {
        if (forcedForward) {
            mc.options.forwardKey.setPressed(prevForwardState);
            forcedForward = false;
        }
        cameraOverrideDir = Vec3d.ZERO;
    }

    @EventHandler(priority = EventPriority.HIGH)
    private void onTickPre(TickEvent.Pre event) {
        CrashGuard.run(this, "onTickPre", () -> onTickPreSafe(event));
    }

    private void onTickPreSafe(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;

        applyAutoWalkState();

        if (takeoffCooldown > 0) takeoffCooldown--;
        if (noRocketWarnCooldown > 0) noRocketWarnCooldown--;
        if (avoidTicks > 0) {
            avoidTicks--;
            if (avoidTicks == 0) avoidStrafeDir = 0;
        }

        if (!mc.player.isGliding()) {
            tryRecoverFlight();
            return;
        }

        if (autoCycleFigures.get()) {
            figureTicks++;
            if (figureTicks >= switchInterval.get()) {
                figureTicks = 0;
                activeFigure = AntiWaspPathMath.nextFigure(activeFigure);
                phase = 0.0;
                origin = mc.player.getPos();
                info("Figure: " + activeFigure.name().toLowerCase());
            }
        } else {
            activeFigure = figure.get();
        }

        double direction = clockwise.get() ? 1.0 : -1.0;
        double perimeter = AntiWaspPathMath.getPerimeter(activeFigure, figureSize.get());
        double progress = (horizontalSpeed.get() * phaseStep.get()) / Math.max(perimeter, 1e-6);
        phase = AntiWaspPathMath.wrap01(phase + progress * direction);

        if (rotateCamera.get()) updateCamera();
        handleFireworks();
    }

    @EventHandler(priority = EventPriority.LOW)
    private void onTickPost(TickEvent.Post event) {
        CrashGuard.run(this, "onTickPost", () -> onTickPostSafe(event));
    }

    private void onTickPostSafe(TickEvent.Post event) {
        if (mc.player == null || mc.world == null) return;
        applyAutoWalkState();
    }

    @EventHandler
    private void onMove(PlayerMoveEvent event) {
        CrashGuard.run(this, "onMove", () -> onMoveSafe(event));
    }

    private void onMoveSafe(PlayerMoveEvent event) {
        if (mc.player == null || mc.world == null) return;
        if (!mc.player.isGliding()) return;
        if (!isWearingElytra()) return;

        Vec3d target = AntiWaspPathMath.getPathPoint(origin, activeFigure, phase + lookAhead.get(), figureSize.get(), altitudeOffset.get());
        double xDist = target.x - mc.player.getX();
        double zDist = target.z - mc.player.getZ();
        double yDist = target.y - mc.player.getY();

        Vec3d planarVelocity = AntiWaspPathMath.distributePlanarSpeed(xDist, zDist, horizontalSpeed.get());
        double yVel = AntiWaspPathMath.axisSpeed(yDist, verticalSpeed.get());
        Vec3d adjustedVelocity = obstacleAvoidance.apply(new Vec3d(planarVelocity.x, yVel, planarVelocity.z));
        cameraOverrideDir = obstacleAvoidance.normalizeOrZero(adjustedVelocity);
        ((IVec3d) event.movement).meteor$set(adjustedVelocity.x, adjustedVelocity.y, adjustedVelocity.z);
    }

    private void applyAutoWalkState() {
        if (autoWalk.get() && mc.player.isGliding()) {
            if (!forcedForward) prevForwardState = mc.options.forwardKey.isPressed();
            mc.options.forwardKey.setPressed(true);
            forcedForward = true;
        } else if (forcedForward) {
            mc.options.forwardKey.setPressed(prevForwardState);
            forcedForward = false;
        }
    }

    private void updateCamera() {
        Vec3d lookTarget;
        if (avoidTicks > 0 && cameraOverrideDir.lengthSquared() > 1.0E-8) {
            lookTarget = mc.player.getEyePos().add(cameraOverrideDir.multiply(12.0));
        } else {
            lookTarget = AntiWaspPathMath.getPathPoint(origin, activeFigure, phase + lookAhead.get(), figureSize.get(), altitudeOffset.get());
        }

        double dx = lookTarget.x - mc.player.getX();
        double dz = lookTarget.z - mc.player.getZ();
        double dy = lookTarget.y - mc.player.getEyeY();

        if (dx * dx + dz * dz < 1e-6) return;

        float targetYaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float currentYaw = mc.player.getYaw();
        float deltaYaw = MathHelper.wrapDegrees(targetYaw - currentYaw);
        if (Math.abs(deltaYaw) > yawDeadZone.get()) {
            float yawStep = (float) Math.min(Math.abs(deltaYaw), maxYawStep.get());
            float nextYaw = currentYaw + Math.copySign(yawStep, deltaYaw);
            mc.player.setYaw(nextYaw);
            mc.player.setHeadYaw(nextYaw);
            mc.player.setBodyYaw(nextYaw);
        }

        double horizontal = Math.sqrt(dx * dx + dz * dz);
        float targetPitch = (float) -Math.toDegrees(Math.atan2(dy, horizontal));
        float currentPitch = mc.player.getPitch();
        float deltaPitch = targetPitch - currentPitch;
        float pitchStep = (float) Math.min(Math.abs(deltaPitch), maxPitchStep.get());
        mc.player.setPitch(currentPitch + Math.copySign(pitchStep, deltaPitch));
    }

    private void tryRecoverFlight() {
        if (mc.player == null) return;
        if (!isWearingElytra()) return;

        if (mc.player.isOnGround()) {
            if (!autoTakeoff.get()) return;
            mc.player.jump();
            return;
        }

        if (takeoffCooldown > 0) return;

        if (mc.player.getVelocity().y < 0) {
            mc.player.networkHandler.sendPacket(new ClientCommandC2SPacket(
                mc.player, ClientCommandC2SPacket.Mode.START_FALL_FLYING
            ));
            takeoffCooldown = 5;
        }
    }

    private void handleFireworks() {
        if (!useFireworks.get()) return;
        fireworkTicks++;
        if (fireworkTicks >= fireworkDelay.get()) {
            fireworkTicks = 0;
            useFirework();
        }
    }

    private boolean isWearingElytra() {
        return mc.player.getEquippedStack(EquipmentSlot.CHEST).getItem() == Items.ELYTRA;
    }

    private void useFirework() {
        if (mc.player == null || mc.interactionManager == null) return;

        int slot = findFireworkSlot();
        if (slot == -1) {
            if (noRocketWarnCooldown == 0) {
                warning("No fireworks in hotbar.");
                noRocketWarnCooldown = 100;
            }
            return;
        }

        InvUtils.swap(slot, false);
        mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
        InvUtils.swapBack();
    }

    private int findFireworkSlot() {
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getStack(i).getItem() == Items.FIREWORK_ROCKET) return i;
        }
        return -1;
    }

}


