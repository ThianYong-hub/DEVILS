package com.example.addon.modules;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Vec3d;

public class AntiElytraWasp extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgPath = settings.createGroup("Path");
    private final SettingGroup sgAssist = settings.createGroup("Assist");

    private final Setting<FlightFigure> figure = sgGeneral.add(new EnumSetting.Builder<FlightFigure>()
        .name("figure")
        .description("Primary figure to fly.")
        .defaultValue(FlightFigure.Circle)
        .build()
    );

    private final Setting<Boolean> autoCycleFigures = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-cycle-figures")
        .description("Switches between different flight figures while enabled.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> switchInterval = sgGeneral.add(new IntSetting.Builder()
        .name("switch-interval")
        .description("Ticks between figure switches when auto-cycle is enabled.")
        .defaultValue(280)
        .min(40)
        .sliderMax(1200)
        .visible(autoCycleFigures::get)
        .build()
    );

    private final Setting<Boolean> clockwise = sgGeneral.add(new BoolSetting.Builder()
        .name("clockwise")
        .description("Direction along the current path.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> radius = sgPath.add(new DoubleSetting.Builder()
        .name("radius")
        .description("Main horizontal size of the figure in blocks.")
        .defaultValue(150.0)
        .min(30.0)
        .sliderRange(50.0, 300.0)
        .build()
    );

    private final Setting<Double> phaseStep = sgPath.add(new DoubleSetting.Builder()
        .name("phase-step")
        .description("Figure phase progression per tick (radians). Lower = wider, smoother turns.")
        .defaultValue(0.010)
        .min(0.001)
        .max(0.08)
        .sliderRange(0.002, 0.03)
        .build()
    );

    private final Setting<Double> lookAhead = sgPath.add(new DoubleSetting.Builder()
        .name("look-ahead")
        .description("How far ahead on the curve the module looks when steering.")
        .defaultValue(0.24)
        .min(0.05)
        .max(1.2)
        .sliderRange(0.1, 0.8)
        .build()
    );

    private final Setting<Boolean> autoWalk = sgAssist.add(new BoolSetting.Builder()
        .name("auto-walk")
        .description("Holds forward key while enabled.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> autoTakeoff = sgAssist.add(new BoolSetting.Builder()
        .name("auto-takeoff")
        .description("Attempts to auto start elytra flight when possible.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> lockPitch = sgAssist.add(new BoolSetting.Builder()
        .name("lock-pitch")
        .description("Locks pitch while steering figures.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> pitch = sgAssist.add(new DoubleSetting.Builder()
        .name("pitch")
        .description("Pitch value used if lock-pitch is enabled.")
        .defaultValue(0.0)
        .min(-45.0)
        .max(45.0)
        .sliderRange(-30.0, 30.0)
        .visible(lockPitch::get)
        .build()
    );

    private final Setting<Boolean> useFireworks = sgAssist.add(new BoolSetting.Builder()
        .name("use-fireworks")
        .description("Uses rockets periodically to keep gliding stable.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> fireworkDelay = sgAssist.add(new IntSetting.Builder()
        .name("firework-delay")
        .description("Ticks between firework uses.")
        .defaultValue(35)
        .min(5)
        .sliderMax(200)
        .visible(useFireworks::get)
        .build()
    );

    private final Setting<Boolean> stallRocket = sgAssist.add(new BoolSetting.Builder()
        .name("stall-rocket")
        .description("Uses a rocket early if horizontal speed drops too low.")
        .defaultValue(true)
        .visible(useFireworks::get)
        .build()
    );

    private final Setting<Double> stallSpeed = sgAssist.add(new DoubleSetting.Builder()
        .name("stall-speed")
        .description("Horizontal speed threshold for stall-rocket.")
        .defaultValue(0.08)
        .min(0.0)
        .sliderMax(1.0)
        .visible(() -> useFireworks.get() && stallRocket.get())
        .build()
    );

    private Vec3d origin;
    private double phase;
    private FlightFigure activeFigure;

    private int figureTicks;
    private int fireworkTicks;
    private int stallTicks;
    private int takeoffCooldown;
    private int noRocketWarnCooldown;

    private boolean forcedForward;
    private boolean prevForwardState;

    public AntiElytraWasp() {
        super(AddonTemplate.CATEGORY, "anti-elytra-wasp", "Autowalk elytra autopilot with large figure flight paths.");
    }

    @Override
    public void onActivate() {
        if (mc.player == null) return;

        activeFigure = figure.get();
        phase = 0.0;
        figureTicks = 0;
        fireworkTicks = 0;
        stallTicks = 0;
        takeoffCooldown = 0;
        noRocketWarnCooldown = 0;

        forcedForward = false;
        prevForwardState = mc.options.forwardKey.isPressed();

        anchorToCurrentPosition();
    }

    @Override
    public void onDeactivate() {
        if (forcedForward) {
            mc.options.forwardKey.setPressed(prevForwardState);
            forcedForward = false;
        }
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;

        if (autoWalk.get()) {
            if (!forcedForward) prevForwardState = mc.options.forwardKey.isPressed();
            mc.options.forwardKey.setPressed(true);
            forcedForward = true;
        } else if (forcedForward) {
            mc.options.forwardKey.setPressed(prevForwardState);
            forcedForward = false;
        }

        if (takeoffCooldown > 0) takeoffCooldown--;
        if (noRocketWarnCooldown > 0) noRocketWarnCooldown--;

        if (!mc.player.isGliding()) {
            tryAutoTakeoff();
            return;
        }

        if (autoCycleFigures.get()) {
            figureTicks++;
            if (figureTicks >= switchInterval.get()) {
                figureTicks = 0;
                activeFigure = nextFigure(activeFigure);
                phase = 0.0;
                anchorToCurrentPosition();
                info("Figure: " + activeFigure.name().toLowerCase());
            }
        } else {
            activeFigure = figure.get();
        }

        steerAlongFigure();
        handleFireworks();
    }

    private void steerAlongFigure() {
        if (mc.player == null) return;

        double direction = clockwise.get() ? 1.0 : -1.0;
        phase += phaseStep.get() * direction;
        double targetPhase = phase + lookAhead.get() * direction;

        Vec3d offset = getOffset(activeFigure, targetPhase);
        Vec3d target = new Vec3d(origin.x + offset.x, mc.player.getY(), origin.z + offset.z);

        double dx = target.x - mc.player.getX();
        double dz = target.z - mc.player.getZ();

        if (dx * dx + dz * dz < 1e-6) return;

        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        mc.player.setYaw(yaw);
        mc.player.setHeadYaw(yaw);
        mc.player.setBodyYaw(yaw);

        if (lockPitch.get()) {
            mc.player.setPitch(pitch.get().floatValue());
        }
    }

    private void handleFireworks() {
        if (!useFireworks.get()) return;

        fireworkTicks++;
        if (fireworkTicks >= fireworkDelay.get()) {
            fireworkTicks = 0;
            useFirework();
        }

        if (stallRocket.get()) {
            Vec3d velocity = mc.player.getVelocity();
            double speed = Math.sqrt(velocity.x * velocity.x + velocity.z * velocity.z);

            if (speed < stallSpeed.get()) {
                stallTicks++;
                if (stallTicks >= 20) {
                    stallTicks = 0;
                    fireworkTicks = 0;
                    useFirework();
                }
            } else {
                stallTicks = 0;
            }
        }
    }

    private void tryAutoTakeoff() {
        if (!autoTakeoff.get() || mc.player == null) return;
        if (!isWearingElytra()) return;

        if (mc.player.isOnGround()) {
            mc.player.jump();
            return;
        }

        if (takeoffCooldown > 0) return;

        if (mc.player.getVelocity().y < -0.05) {
            mc.player.networkHandler.sendPacket(new ClientCommandC2SPacket(
                mc.player, ClientCommandC2SPacket.Mode.START_FALL_FLYING
            ));
            takeoffCooldown = 10;
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

    private void anchorToCurrentPosition() {
        Vec3d pos = mc.player.getPos();
        Vec3d atZero = getOffset(activeFigure, 0.0);
        origin = new Vec3d(pos.x - atZero.x, pos.y, pos.z - atZero.z);
    }

    private Vec3d getOffset(FlightFigure figure, double t) {
        double r = radius.get();

        return switch (figure) {
            case Circle -> new Vec3d(r * Math.cos(t), 0.0, r * Math.sin(t));
            case Infinity -> {
                double x = r * Math.sin(t);
                double z = r * Math.sin(t) * Math.cos(t) * 2.0;
                yield new Vec3d(x, 0.0, z);
            }
            case Clover -> {
                double rr = r * Math.cos(3.0 * t);
                double x = rr * Math.cos(t);
                double z = rr * Math.sin(t);
                yield new Vec3d(x, 0.0, z);
            }
            case Wave -> {
                double x = r * Math.cos(t);
                double z = r * 0.38 * Math.sin(2.0 * t);
                yield new Vec3d(x, 0.0, z);
            }
        };
    }

    private FlightFigure nextFigure(FlightFigure current) {
        FlightFigure[] values = FlightFigure.values();
        return values[(current.ordinal() + 1) % values.length];
    }

    public enum FlightFigure {
        Circle,
        Infinity,
        Clover,
        Wave
    }
}
