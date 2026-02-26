package com.example.addon.modules;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.fluid.FluidState;
import net.minecraft.fluid.Fluids;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

public class LavaBucket extends Module {
    private static final int ROTATE_PRIORITY = 50;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Double> range = sgGeneral.add(new DoubleSetting.Builder()
        .name("range")
        .description("Range to search for lava source blocks.")
        .defaultValue(4.5)
        .min(0.0)
        .max(6.0)
        .sliderRange(0.0, 6.0)
        .build()
    );

    private final Setting<Integer> delay = sgGeneral.add(new IntSetting.Builder()
        .name("delay")
        .description("Ticks between attempts.")
        .defaultValue(1)
        .min(0)
        .sliderMax(20)
        .build()
    );

    private final Setting<Boolean> rotate = sgGeneral.add(new BoolSetting.Builder()
        .name("rotate")
        .description("Rotate to lava before collecting.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> swingHand = sgGeneral.add(new BoolSetting.Builder()
        .name("swing-hand")
        .description("Render swing animation when collecting.")
        .defaultValue(true)
        .build()
    );

    private int timer;

    public LavaBucket() {
        super(AddonTemplate.CATEGORY, "lava-bucket", "Automatically collects nearby lava source blocks with buckets.");
    }

    @Override
    public void onActivate() {
        timer = 0;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null || mc.interactionManager == null) return;

        if (timer > 0) {
            timer--;
            return;
        }

        FindItemResult bucket = ensureBucketAvailable();
        if (!bucket.found()) return;

        BlockPos lavaPos = findClosestLavaSource();
        if (lavaPos == null) return;

        if (collectLava(lavaPos, bucket)) timer = delay.get();
    }

    private FindItemResult ensureBucketAvailable() {
        FindItemResult bucket = InvUtils.findInHotbar(Items.BUCKET);
        if (bucket.found()) return bucket;

        FindItemResult invBucket = InvUtils.find(stack -> stack.isOf(Items.BUCKET), 9, 35);
        if (!invBucket.found()) return bucket;

        int targetHotbarSlot = findRefillHotbarSlot();
        if (targetHotbarSlot == -1) return bucket;

        InvUtils.move().from(invBucket.slot()).toHotbar(targetHotbarSlot);
        return InvUtils.findInHotbar(Items.BUCKET);
    }

    private int findRefillHotbarSlot() {
        int lavaBucketSlot = -1;

        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getStack(i).isEmpty()) return i;
            if (mc.player.getInventory().getStack(i).isOf(Items.LAVA_BUCKET) && lavaBucketSlot == -1) lavaBucketSlot = i;
        }

        return lavaBucketSlot;
    }

    private BlockPos findClosestLavaSource() {
        double maxRange = range.get();
        double maxRangeSq = maxRange * maxRange;
        int radius = MathHelper.ceil(maxRange);

        Vec3d eyePos = mc.player.getEyePos();
        BlockPos playerPos = mc.player.getBlockPos();

        BlockPos bestPos = null;
        double bestDistSq = Double.MAX_VALUE;

        for (int x = -radius; x <= radius; x++) {
            for (int y = -radius; y <= radius; y++) {
                for (int z = -radius; z <= radius; z++) {
                    BlockPos pos = playerPos.add(x, y, z);
                    Vec3d center = Vec3d.ofCenter(pos);
                    double distSq = eyePos.squaredDistanceTo(center);
                    if (distSq > maxRangeSq) continue;

                    FluidState fluidState = mc.world.getFluidState(pos);
                    if (!fluidState.isOf(Fluids.LAVA) || !fluidState.isStill()) continue;

                    if (distSq < bestDistSq) {
                        bestDistSq = distSq;
                        bestPos = pos.toImmutable();
                    }
                }
            }
        }

        return bestPos;
    }

    private boolean collectLava(BlockPos pos, FindItemResult bucket) {
        Hand hand = bucket.getHand();
        boolean swapped = false;

        if (hand == null) {
            if (!InvUtils.swap(bucket.slot(), true)) return false;
            hand = Hand.MAIN_HAND;
            swapped = true;
        }

        Vec3d hitPos = Vec3d.ofCenter(pos);
        Direction side = facingFromEye(hitPos);
        Hand finalHand = hand;
        boolean[] success = { false };

        Runnable interact = () -> {
            if (mc.interactionManager == null) return;

            success[0] = mc.interactionManager.interactBlock(
                mc.player,
                finalHand,
                new BlockHitResult(hitPos, side, pos, false)
            ).isAccepted();

            if (success[0]) swing(finalHand);
        };

        if (rotate.get()) {
            Rotations.rotate(Rotations.getYaw(hitPos), Rotations.getPitch(hitPos), ROTATE_PRIORITY, interact);
        } else {
            interact.run();
        }

        if (swapped) InvUtils.swapBack();
        return success[0];
    }

    private Direction facingFromEye(Vec3d targetPos) {
        Vec3d eye = mc.player.getEyePos();
        Vec3d delta = eye.subtract(targetPos);
        Direction facing = Direction.getFacing(delta.x, delta.y, delta.z);
        return facing == null ? Direction.UP : facing;
    }

    private void swing(Hand hand) {
        if (swingHand.get()) mc.player.swingHand(hand);
    }
}
