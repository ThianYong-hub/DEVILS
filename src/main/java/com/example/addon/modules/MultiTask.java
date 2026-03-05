package com.example.addon.modules;

import com.example.addon.AddonTemplate;
import com.example.addon.util.CrashGuard;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

public class MultiTask extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> noSlow = sgGeneral.add(new BoolSetting.Builder()
        .name("no-slow")
        .description("Use RELEASE_USE_ITEM pulses to reduce NoSlow checks while keeping item use active.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> releaseInterval = sgGeneral.add(new IntSetting.Builder()
        .name("release-interval")
        .description("Ticks between NoSlow pulses for non-consumables.")
        .defaultValue(2)
        .min(2)
        .max(10)
        .sliderRange(2, 10)
        .visible(noSlow::get)
        .build()
    );

    private final Setting<Integer> minReleaseGapMs = sgGeneral.add(new IntSetting.Builder()
        .name("min-release-gap-ms")
        .description("Minimum time between RELEASE_USE_ITEM packets. Keep this >= 70 for NCP.")
        .defaultValue(90)
        .min(70)
        .max(500)
        .sliderRange(70, 250)
        .visible(noSlow::get)
        .build()
    );

    private final Setting<Boolean> onlyWhenMoving = sgGeneral.add(new BoolSetting.Builder()
        .name("only-while-moving")
        .description("Send NoSlow pulses only when you are actually moving.")
        .defaultValue(true)
        .visible(noSlow::get)
        .build()
    );

    private final Setting<Double> minMoveSpeed = sgGeneral.add(new DoubleSetting.Builder()
        .name("min-move-speed")
        .description("Minimum horizontal speed required to send NoSlow pulses.")
        .defaultValue(0.08)
        .min(0.0)
        .max(0.5)
        .sliderRange(0.0, 0.25)
        .visible(() -> noSlow.get() && onlyWhenMoving.get())
        .build()
    );

    private final Setting<Boolean> pauseWhileMiningConsumables = sgGeneral.add(new BoolSetting.Builder()
        .name("pause-while-mining-food")
        .description("Do not pulse while attacking and eating/drinking to avoid canceling consume progress.")
        .defaultValue(true)
        .visible(noSlow::get)
        .build()
    );

    private final Setting<Boolean> pulseConsumables = sgGeneral.add(new BoolSetting.Builder()
        .name("pulse-consumables")
        .description("Also send NoSlow pulses for food/drink usage. Can prevent finishing consume.")
        .defaultValue(false)
        .visible(noSlow::get)
        .build()
    );

    private final Setting<Boolean> autoResumeConsumables = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-resume-consume")
        .description("Re-start eat/drink when use key is held but another action interrupted it.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> resumeDelayTicks = sgGeneral.add(new IntSetting.Builder()
        .name("resume-delay")
        .description("Ticks between consume resume attempts.")
        .defaultValue(1)
        .min(0)
        .max(5)
        .sliderRange(0, 3)
        .visible(autoResumeConsumables::get)
        .build()
    );

    private final Setting<Boolean> mioSpoofUseKey = sgGeneral.add(new BoolSetting.Builder()
        .name("mio-spoof-use-key")
        .description("For Mio callers, report use key as not pressed to reduce pause checks.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> mioSpoofUsingItem = sgGeneral.add(new BoolSetting.Builder()
        .name("mio-spoof-using-item")
        .description("For Mio callers, report player as not using item. Can break eating with auto-swap.")
        .defaultValue(false)
        .build()
    );

    private int tickCounter;
    private int resumeCooldownTicks;
    private long lastReleaseMs;

    public MultiTask() {
        super(AddonTemplate.CATEGORY, "multi-task", "Allows using items and breaking blocks simultaneously with optional NCP-oriented NoSlow pulses.");
    }

    @Override
    public void onActivate() {
        tickCounter = 0;
        resumeCooldownTicks = 0;
        lastReleaseMs = 0L;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        CrashGuard.run(this, "onTick", () -> onTickSafe(event));
    }

    private void onTickSafe(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null || mc.getNetworkHandler() == null || mc.interactionManager == null) return;
        tickResumeCooldown();
        if (autoResumeConsumables.get()) tryResumeConsumableUse();

        if (!noSlow.get()) return;
        if (!mc.player.isUsingItem()) {
            tickCounter = 0;
            return;
        }

        Hand activeHand = mc.player.getActiveHand();
        if (activeHand == null) {
            tickCounter = 0;
            return;
        }
        ItemStack activeStack = mc.player.getStackInHand(activeHand);
        if (activeStack.isEmpty()) {
            tickCounter = 0;
            return;
        }

        boolean consumableUse = isConsumableUse(activeStack);
        if (consumableUse && !pulseConsumables.get()) {
            tickCounter = 0;
            return;
        }

        if (pauseWhileMiningConsumables.get() && mc.options != null && mc.options.attackKey.isPressed() && consumableUse) {
            tickCounter = 0;
            return;
        }

        if (onlyWhenMoving.get() && getHorizontalSpeed() < minMoveSpeed.get()) {
            tickCounter = 0;
            return;
        }

        tickCounter++;
        if (tickCounter >= releaseInterval.get()) {
            long now = System.currentTimeMillis();
            if (lastReleaseMs != 0L && now - lastReleaseMs < minReleaseGapMs.get()) return;

            tickCounter = 0;
            lastReleaseMs = now;

            // NCP toggles isUsingItem=false on RELEASE_USE_ITEM.
            // We re-send use immediately after so item usage can continue.
            mc.getNetworkHandler().sendPacket(new PlayerActionC2SPacket(
                PlayerActionC2SPacket.Action.RELEASE_USE_ITEM,
                BlockPos.ORIGIN,
                Direction.DOWN
            ));

            mc.interactionManager.interactItem(mc.player, activeHand);
        }
    }

    private void tickResumeCooldown() {
        if (resumeCooldownTicks > 0) resumeCooldownTicks--;
    }

    private void tryResumeConsumableUse() {
        if (mc.options == null) return;
        if (!mc.options.useKey.isPressed()) return;
        if (mc.player.isUsingItem()) return;
        if (resumeCooldownTicks > 0) return;

        Hand hand = getConsumableHand();
        if (hand == null) return;

        mc.interactionManager.interactItem(mc.player, hand);
        resumeCooldownTicks = resumeDelayTicks.get();
    }

    private Hand getConsumableHand() {
        ItemStack main = mc.player.getMainHandStack();
        if (!main.isEmpty() && isConsumableUse(main)) return Hand.MAIN_HAND;

        ItemStack off = mc.player.getOffHandStack();
        if (!off.isEmpty() && isConsumableUse(off)) return Hand.OFF_HAND;

        return null;
    }

    private double getHorizontalSpeed() {
        double vx = mc.player.getVelocity().x;
        double vz = mc.player.getVelocity().z;
        return Math.sqrt(vx * vx + vz * vz);
    }

    private boolean isConsumableUse(ItemStack stack) {
        return stack.get(DataComponentTypes.FOOD) != null
            || stack.isOf(Items.POTION)
            || stack.isOf(Items.SPLASH_POTION)
            || stack.isOf(Items.LINGERING_POTION)
            || stack.isOf(Items.MILK_BUCKET)
            || stack.isOf(Items.HONEY_BOTTLE);
    }

    private static MultiTask getModule() {
        Modules modules = Modules.get();
        if (modules == null) return null;
        return modules.get(MultiTask.class);
    }

    public static boolean isEnabled() {
        Modules modules = Modules.get();
        return modules != null && modules.isActive(MultiTask.class);
    }

    public static boolean shouldSpoofMioUseKey() {
        MultiTask module = getModule();
        return module != null && module.isActive() && module.mioSpoofUseKey.get();
    }

    public static boolean shouldSpoofMioUsingItem() {
        MultiTask module = getModule();
        return module != null && module.isActive() && module.mioSpoofUsingItem.get();
    }
}
