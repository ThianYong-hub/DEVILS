package com.example.addon.modules;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.entity.DamageUtils;
import meteordevelopment.meteorclient.utils.entity.SortPriority;
import meteordevelopment.meteorclient.utils.entity.TargetUtils;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.HandSwingC2SPacket;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

public class AutoCev extends Module {
    private static final Direction[] CARDINAL = {
        Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST
    };

    private static final int ROTATE_PRIORITY = 50;
    private static final int CRYSTAL_RETRY_TICKS = 2;
    private static final double CRYSTAL_SEARCH_RADIUS_SQ = 4.0;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Double> targetRange = sgGeneral.add(new DoubleSetting.Builder()
        .name("target-range")
        .description("Target search range.")
        .defaultValue(5.0)
        .min(1.0)
        .max(7.0)
        .sliderRange(2.0, 6.0)
        .build()
    );

    private final Setting<Boolean> antiSuicide = sgGeneral.add(new BoolSetting.Builder()
        .name("anti-suicide")
        .description("Prevents placing/attacking crystal if it can kill you.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> rotate = sgGeneral.add(new BoolSetting.Builder()
        .name("rotate")
        .description("Rotate before place/mine/attack.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> swingHand = sgGeneral.add(new BoolSetting.Builder()
        .name("swing-hand")
        .description("Swing hand while interacting.")
        .defaultValue(true)
        .build()
    );

    private final Setting<SwapMode> swapMode = sgGeneral.add(new EnumSetting.Builder<SwapMode>()
        .name("swap-mode")
        .description("Crystal/obsidian switching method.")
        .defaultValue(SwapMode.Silent)
        .build()
    );

    private PlayerEntity target;
    private String targetName;
    private BlockPos activePos;
    private boolean activeIsFallback;
    private boolean cycleStarted;
    private int crystalRetry;

    public AutoCev() {
        super(AddonTemplate.CATEGORY, "auto-cev", "Places obsidian, places crystal, mines block, then detonates.");
    }

    @Override
    public void onActivate() {
        resetAll();
    }

    @Override
    public void onDeactivate() {
        resetAll();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null || mc.interactionManager == null) return;

        target = (PlayerEntity) TargetUtils.get(entity -> {
            if (!(entity instanceof PlayerEntity player)) return false;
            if (player == mc.player) return false;
            if (player.isDead() || player.getHealth() <= 0) return false;
            if (Friends.get().isFriend(player)) return false;
            return mc.player.distanceTo(player) <= targetRange.get();
        }, SortPriority.LowestDistance);

        if (target == null) {
            resetAll();
            return;
        }

        if (targetName == null || !target.getGameProfile().getName().equalsIgnoreCase(targetName)) {
            targetName = target.getGameProfile().getName();
            activePos = null;
            activeIsFallback = false;
            resetCycle();
        }

        BlockPos nextPos = resolveCevPos(target);
        if (nextPos == null) {
            resetCycle();
            return;
        }

        if (!nextPos.equals(activePos)) {
            activePos = nextPos;
            activeIsFallback = !isTopBase(nextPos, target);
            resetCycle();
        }

        BlockState state = mc.world.getBlockState(activePos);
        EndCrystalEntity crystal = findCrystalAt(activePos.up());

        if (state.isOf(Blocks.OBSIDIAN)) {
            cycleStarted = true;
            mineObsidian(activePos);
            placeCrystal(activePos, crystal);
            return;
        }

        if (state.isAir() || state.isReplaceable()) {
            if (!cycleStarted) {
                placeObsidian(activePos);
            } else {
                detonateAfterBreak(crystal);
            }
            return;
        }

        activePos = null;
        activeIsFallback = false;
        resetCycle();
    }

    private BlockPos resolveCevPos(PlayerEntity player) {
        int x = player.getBlockX();
        int z = player.getBlockZ();
        int headY = (int) Math.floor(player.getBoundingBox().maxY);

        BlockPos top = new BlockPos(x, headY + 1, z);
        boolean topUsable = isValidBase(top, player, true);
        boolean topCrystalBlocked = isTopCrystalBlocked(top, player);

        if (activePos != null) {
            if (!activeIsFallback && topUsable && activePos.equals(top)) return activePos;
            if (activeIsFallback && topCrystalBlocked && isValidFallback(activePos, player)) return activePos;
        }

        if (topUsable) return top;
        if (!topCrystalBlocked) return null;

        BlockPos center = new BlockPos(x, headY, z);
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;

        for (Direction direction : CARDINAL) {
            BlockPos candidate = center.offset(direction);
            if (!isValidFallback(candidate, player)) continue;

            double distance = mc.player.squaredDistanceTo(Vec3d.ofCenter(candidate));
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate;
            }
        }

        return best;
    }

    private boolean isTopBase(BlockPos pos, PlayerEntity player) {
        int x = player.getBlockX();
        int z = player.getBlockZ();
        int headY = (int) Math.floor(player.getBoundingBox().maxY);
        return pos.getX() == x && pos.getY() == headY + 1 && pos.getZ() == z;
    }

    private boolean isTopCrystalBlocked(BlockPos top, PlayerEntity player) {
        if (!isBaseBlockValid(top, player)) return false;
        return !isCrystalColumnFree(top);
    }

    private boolean isValidFallback(BlockPos pos, PlayerEntity player) {
        if (isTopBase(pos, player)) return false;
        return isValidBase(pos, player, true);
    }

    private boolean isValidBase(BlockPos pos, PlayerEntity player, boolean requireCrystalSpace) {
        if (!isBaseBlockValid(pos, player)) return false;
        return !requireCrystalSpace || isCrystalColumnFree(pos);
    }

    private boolean isBaseBlockValid(BlockPos pos, PlayerEntity player) {
        if (mc.world.isOutOfHeightLimit(pos.getY()) || mc.world.isOutOfHeightLimit(pos.getY() + 2)) return false;

        BlockState state = mc.world.getBlockState(pos);
        if (state.isOf(Blocks.BEDROCK)) return false;
        if (!(state.isOf(Blocks.OBSIDIAN) || state.isReplaceable())) return false;

        Box blockBox = new Box(pos);
        if (player.getBoundingBox().intersects(blockBox)) return false;
        if (mc.player.getBoundingBox().intersects(blockBox)) return false;

        for (Entity entity : mc.world.getOtherEntities(null, blockBox)) {
            if (entity instanceof EndCrystalEntity) continue;
            return false;
        }

        return true;
    }

    private boolean isCrystalColumnFree(BlockPos base) {
        if (!mc.world.getBlockState(base.up()).isAir()) return false;
        if (!mc.world.getBlockState(base.up(2)).isAir()) return false;

        Box crystalBox = new Box(base.getX(), base.getY() + 1, base.getZ(), base.getX() + 1, base.getY() + 3, base.getZ() + 1);
        for (Entity entity : mc.world.getOtherEntities(null, crystalBox)) {
            if (entity instanceof EndCrystalEntity) continue;
            if (entity.isRemoved()) continue;
            return false;
        }

        return true;
    }

    private void placeObsidian(BlockPos pos) {
        FindItemResult obsidian = InvUtils.findInHotbar(Items.OBSIDIAN);
        if (!obsidian.found()) {
            error("No obsidian in hotbar.");
            toggle();
            return;
        }

        boolean placed = BlockUtils.place(
            pos,
            obsidian,
            rotate.get(),
            rotate.get() ? ROTATE_PRIORITY : 0,
            swingHand.get(),
            true,
            swapMode.get() == SwapMode.Silent
        );

        if (!placed) return;

        cycleStarted = true;
        crystalRetry = 0;
        mineObsidian(pos);
    }

    private void mineObsidian(BlockPos pos) {
        if (!mc.world.getBlockState(pos).isOf(Blocks.OBSIDIAN)) return;

        Runnable mine = () -> {
            if (mc.interactionManager == null) return;
            mc.interactionManager.attackBlock(pos, Direction.UP);
            swing(Hand.MAIN_HAND);
        };

        if (rotate.get()) {
            Vec3d center = Vec3d.ofCenter(pos);
            Rotations.rotate(Rotations.getYaw(center), Rotations.getPitch(center), ROTATE_PRIORITY, mine);
        } else {
            mine.run();
        }
    }

    private void placeCrystal(BlockPos base, EndCrystalEntity crystal) {
        if (crystal != null) {
            crystalRetry = 0;
            return;
        }

        if (crystalRetry > 0) {
            crystalRetry--;
            return;
        }

        if (!canPlaceCrystalAt(base)) {
            crystalRetry = CRYSTAL_RETRY_TICKS;
            return;
        }

        Vec3d crystalPos = new Vec3d(base.getX() + 0.5, base.getY() + 1.0, base.getZ() + 0.5);
        if (antiSuicide.get() && !canExplodeSafely(crystalPos)) return;

        if (!placeCrystalOnBase(base)) {
            crystalRetry = CRYSTAL_RETRY_TICKS;
        }
    }

    private void detonateAfterBreak(EndCrystalEntity crystal) {
        if (crystal == null) {
            resetCycle();
            return;
        }

        if (antiSuicide.get() && !canExplodeSafely(crystal.getPos())) return;

        attackCrystal(crystal);
    }

    private boolean placeCrystalOnBase(BlockPos base) {
        Hand hand;
        boolean swapBack = false;

        if (mc.player.getOffHandStack().isOf(Items.END_CRYSTAL)) {
            hand = Hand.OFF_HAND;
        } else if (mc.player.getMainHandStack().isOf(Items.END_CRYSTAL)) {
            hand = Hand.MAIN_HAND;
        } else {
            FindItemResult crystal = InvUtils.findInHotbar(Items.END_CRYSTAL);
            if (!crystal.found()) {
                error("No end crystals in hotbar.");
                toggle();
                return false;
            }

            boolean silent = swapMode.get() == SwapMode.Silent;
            if (!InvUtils.swap(crystal.slot(), silent)) return false;
            hand = Hand.MAIN_HAND;
            swapBack = silent;
        }

        if (!mc.player.getStackInHand(hand).isOf(Items.END_CRYSTAL)) {
            if (swapBack) InvUtils.swapBack();
            return false;
        }

        Hand useHand = hand;
        boolean[] result = { false };
        Runnable place = () -> {
            if (mc.interactionManager == null) return;

            Vec3d hitVec = new Vec3d(base.getX() + 0.5, base.getY() + 1.0, base.getZ() + 0.5);
            BlockHitResult hit = new BlockHitResult(hitVec, Direction.UP, base, false);
            ActionResult action = mc.interactionManager.interactBlock(mc.player, useHand, hit);
            result[0] = action.isAccepted();
            if (result[0]) swing(useHand);
        };

        if (rotate.get()) {
            Vec3d look = new Vec3d(base.getX() + 0.5, base.getY() + 1.0, base.getZ() + 0.5);
            Rotations.rotate(Rotations.getYaw(look), Rotations.getPitch(look), ROTATE_PRIORITY, place);
        } else {
            place.run();
        }

        if (swapBack) InvUtils.swapBack();

        return result[0];
    }

    private void attackCrystal(EndCrystalEntity crystal) {
        Runnable attack = () -> {
            if (mc.interactionManager == null) return;
            mc.interactionManager.attackEntity(mc.player, crystal);
            swing(Hand.MAIN_HAND);
        };

        if (rotate.get()) {
            Rotations.rotate(Rotations.getYaw(crystal.getPos()), Rotations.getPitch(crystal.getPos()), ROTATE_PRIORITY, attack);
        } else {
            attack.run();
        }
    }

    private EndCrystalEntity findCrystalAt(BlockPos crystalPos) {
        for (Entity entity : mc.world.getEntities()) {
            if (!(entity instanceof EndCrystalEntity crystal)) continue;
            if (!crystal.getBlockPos().equals(crystalPos)) continue;
            return crystal;
        }

        Vec3d center = Vec3d.ofCenter(crystalPos);
        EndCrystalEntity best = null;
        double bestDistance = CRYSTAL_SEARCH_RADIUS_SQ;

        for (Entity entity : mc.world.getEntities()) {
            if (!(entity instanceof EndCrystalEntity crystal)) continue;
            double distance = crystal.squaredDistanceTo(center);
            if (distance > bestDistance) continue;

            bestDistance = distance;
            best = crystal;
        }

        return best;
    }

    private boolean canPlaceCrystalAt(BlockPos base) {
        BlockState baseState = mc.world.getBlockState(base);
        if (!(baseState.isOf(Blocks.OBSIDIAN) || baseState.isOf(Blocks.BEDROCK))) return false;
        if (!mc.world.getBlockState(base.up()).isAir()) return false;
        if (!mc.world.getBlockState(base.up(2)).isAir()) return false;

        Box crystalBox = new Box(base.getX(), base.getY() + 1, base.getZ(), base.getX() + 1, base.getY() + 3, base.getZ() + 1);
        for (Entity entity : mc.world.getOtherEntities(null, crystalBox)) {
            if (entity instanceof EndCrystalEntity) continue;
            if (entity.isRemoved()) continue;
            return false;
        }

        return true;
    }

    private boolean canExplodeSafely(Vec3d crystalPos) {
        float selfDamage = DamageUtils.crystalDamage(mc.player, crystalPos);
        return selfDamage < mc.player.getHealth() + mc.player.getAbsorptionAmount();
    }

    private void swing(Hand hand) {
        if (swingHand.get()) {
            mc.player.swingHand(hand);
            return;
        }

        if (mc.getNetworkHandler() != null) {
            mc.getNetworkHandler().sendPacket(new HandSwingC2SPacket(hand));
        }
    }

    private void resetCycle() {
        cycleStarted = false;
        crystalRetry = 0;
    }

    private void resetAll() {
        target = null;
        targetName = null;
        activePos = null;
        activeIsFallback = false;
        resetCycle();
    }

    @Override
    public String getInfoString() {
        return target != null ? target.getName().getString() : null;
    }

    public enum SwapMode {
        Normal,
        Silent
    }
}
