package com.example.addon.modules;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;
import net.minecraft.util.math.MathHelper;

public class OpdehTrololo extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> clockwise = sgGeneral.add(new BoolSetting.Builder()
        .name("clockwise")
        .description("Turn direction of the circle flight.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> turnSpeed = sgGeneral.add(new DoubleSetting.Builder()
        .name("turn-speed")
        .description("How many degrees to rotate every tick while gliding.")
        .defaultValue(4.0)
        .min(0.2)
        .sliderMax(20.0)
        .build()
    );

    private final Setting<Boolean> lockPitch = sgGeneral.add(new BoolSetting.Builder()
        .name("lock-pitch")
        .description("Keep pitch fixed while flying in a circle.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> pitch = sgGeneral.add(new DoubleSetting.Builder()
        .name("pitch")
        .description("Pitch used for circle flight. Negative values climb, positive descend.")
        .defaultValue(0.0)
        .min(-45.0)
        .max(45.0)
        .sliderRange(-30.0, 30.0)
        .visible(lockPitch::get)
        .build()
    );

    private final Setting<Boolean> holdForward = sgGeneral.add(new BoolSetting.Builder()
        .name("hold-forward")
        .description("Automatically holds the forward key while the module is active.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> useFireworks = sgGeneral.add(new BoolSetting.Builder()
        .name("use-fireworks")
        .description("Use firework rockets periodically while gliding.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> fireworkDelay = sgGeneral.add(new IntSetting.Builder()
        .name("firework-delay")
        .description("Ticks between rocket uses.")
        .defaultValue(40)
        .min(5)
        .sliderMax(200)
        .visible(useFireworks::get)
        .build()
    );

    private float yaw;
    private int fireworkTicks;
    private boolean forcedForward;
    private boolean prevForwardState;
    private int noRocketWarnCooldown;

    public OpdehTrololo() {
        super(AddonTemplate.CATEGORY, "opdeh-trololo", "Fly on elytra in a dumb circle.");
    }

    @Override
    public void onActivate() {
        if (mc.player == null) return;

        yaw = mc.player.getYaw();
        fireworkTicks = 0;
        noRocketWarnCooldown = 0;

        forcedForward = false;
        prevForwardState = mc.options.forwardKey.isPressed();
    }

    @Override
    public void onDeactivate() {
        if (mc.player == null) return;

        if (forcedForward) {
            mc.options.forwardKey.setPressed(prevForwardState);
            forcedForward = false;
        }
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;

        if (holdForward.get()) {
            if (!forcedForward) prevForwardState = mc.options.forwardKey.isPressed();
            mc.options.forwardKey.setPressed(true);
            forcedForward = true;
        } else if (forcedForward) {
            mc.options.forwardKey.setPressed(prevForwardState);
            forcedForward = false;
        }

        if (!mc.player.isGliding()) return;

        float delta = turnSpeed.get().floatValue();
        if (!clockwise.get()) delta = -delta;

        yaw = MathHelper.wrapDegrees(yaw + delta);
        mc.player.setYaw(yaw);
        mc.player.setHeadYaw(yaw);
        mc.player.setBodyYaw(yaw);

        if (lockPitch.get()) {
            mc.player.setPitch(pitch.get().floatValue());
        }

        if (useFireworks.get()) {
            fireworkTicks++;
            if (fireworkTicks >= fireworkDelay.get()) {
                fireworkTicks = 0;
                useFirework();
            }
        }

        if (noRocketWarnCooldown > 0) noRocketWarnCooldown--;
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
