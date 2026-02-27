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
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.fluid.FluidState;
import net.minecraft.fluid.Fluids;
import net.minecraft.item.Items;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.ActionResult;
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

    private final Setting<Priority> priority = sgGeneral.add(new EnumSetting.Builder<Priority>()
        .name("priority")
        .description("Which lava source block to collect first.")
        .defaultValue(Priority.Closest)
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

    private final Setting<Boolean> dropLava = sgGeneral.add(new BoolSetting.Builder()
        .name("drop-lava")
        .description("Drop lava buckets when no empty buckets are available.")
        .defaultValue(false)
        .build()
    );

    private int timer;

    public LavaBucket() {
        super(AddonTemplate.CATEGORY, "lava-bucket", "Automatically places and collects lava buckets on nearby players.");
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

        if (dropLava.get()) dropAllLavaBuckets();

        FindItemResult bucket = ensureBucketAvailable();
        if (!bucket.found()) return;

        BlockPos lavaPos = findClosestLavaSource();
        if (lavaPos == null) return;

        collectLava(lavaPos, bucket);
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

                    if (isBetterCandidate(pos, distSq, bestPos, bestDistSq)) {
                        bestDistSq = distSq;
                        bestPos = pos.toImmutable();
                    }
                }
            }
        }

        return bestPos;
    }

    private void collectLava(BlockPos pos, FindItemResult bucket) {
        Vec3d hitPos = Vec3d.ofCenter(pos);

        Runnable interact = () -> {
            if (mc.interactionManager == null) return;

            Hand hand = bucket.getHand();
            boolean swapped = false;

            if (hand == null) {
                if (!InvUtils.swap(bucket.slot(), true)) return;
                hand = Hand.MAIN_HAND;
                swapped = true;
            }

            if (!mc.player.getStackInHand(hand).isOf(Items.BUCKET)) {
                if (swapped) InvUtils.swapBack();
                return;
            }

            int emptyBefore = countItem(Items.BUCKET);
            int lavaBefore = countItem(Items.LAVA_BUCKET);

            ActionResult itemResult = mc.interactionManager.interactItem(mc.player, hand);

            // Fallback path: explicit block interaction if generic use did not consume.
            if (!itemResult.isAccepted() && !didCollect(emptyBefore, lavaBefore)) {
                Direction side = Direction.UP;
                for (Direction direction : Direction.values()) {
                    ActionResult blockResult = mc.interactionManager.interactBlock(
                        mc.player,
                        hand,
                        new BlockHitResult(hitPos, direction, pos, false)
                    );

                    if (blockResult.isAccepted()) {
                        side = direction;
                        break;
                    }
                }

                // Retry once with the best known side in case first pass was inconclusive.
                mc.interactionManager.interactBlock(mc.player, hand, new BlockHitResult(hitPos, side, pos, false));
            }

            if (didCollect(emptyBefore, lavaBefore)) {
                swing(hand);
                timer = delay.get();
            }

            if (swapped) InvUtils.swapBack();
        };

        if (rotate.get()) {
            Rotations.rotate(Rotations.getYaw(hitPos), Rotations.getPitch(hitPos), ROTATE_PRIORITY, interact);
        } else {
            interact.run();
        }
    }

    private void dropAllLavaBuckets() {
        if (mc.interactionManager == null) return;
        int syncId = mc.player.currentScreenHandler.syncId;

        // Hotbar: inventory 0-8 → screen handler 36-44
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getStack(i).isOf(Items.LAVA_BUCKET)) {
                mc.interactionManager.clickSlot(syncId, 36 + i, 1, SlotActionType.THROW, mc.player);
            }
        }

        // Main inventory: inventory 9-35 → screen handler 9-35
        for (int i = 9; i < 36; i++) {
            if (mc.player.getInventory().getStack(i).isOf(Items.LAVA_BUCKET)) {
                mc.interactionManager.clickSlot(syncId, i, 1, SlotActionType.THROW, mc.player);
            }
        }
    }

    private int countItem(net.minecraft.item.Item item) {
        return InvUtils.find(item).count();
    }

    private boolean didCollect(int emptyBefore, int lavaBefore) {
        int emptyAfter = countItem(Items.BUCKET);
        int lavaAfter = countItem(Items.LAVA_BUCKET);
        return emptyAfter < emptyBefore || lavaAfter > lavaBefore;
    }

    private boolean isBetterCandidate(BlockPos candidatePos, double candidateDistSq, BlockPos currentBestPos, double currentBestDistSq) {
        if (currentBestPos == null) return true;

        return switch (priority.get()) {
            case Closest -> candidateDistSq < currentBestDistSq;
            case Furthest -> candidateDistSq > currentBestDistSq;
            case Highest -> candidatePos.getY() > currentBestPos.getY()
                || (candidatePos.getY() == currentBestPos.getY() && candidateDistSq < currentBestDistSq);
            case Lowest -> candidatePos.getY() < currentBestPos.getY()
                || (candidatePos.getY() == currentBestPos.getY() && candidateDistSq < currentBestDistSq);
        };
    }

    private void swing(Hand hand) {
        if (swingHand.get()) mc.player.swingHand(hand);
    }

    public enum Priority {
        Closest,
        Furthest,
        Highest,
        Lowest
    }
}
