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
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractEntityC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

public class AutoCev extends Module {
    private static final Direction[] CARDINAL = { Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST };
    private static final int ROTATE_PRIORITY = 50;
    private static final int CRYSTAL_RETRY_TICKS = 0;
    private static final int VANILLA_POST_BREAK_WAIT_TICKS = 0;
    private static final int INSTA_POST_BREAK_WAIT_TICKS = 0;
    private static final int INSTA_START_RETRY_TICKS = 6;
    private static final double CRYSTAL_SEARCH_RADIUS_SQ = 9.0;

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
        .description("Prevents crystal actions that can kill you.")
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

    private final Setting<MineMode> mineMode = sgGeneral.add(new EnumSetting.Builder<MineMode>()
        .name("mine-mode")
        .description("Vanilla: AutoCev mines obsidian itself. Insta: AutoCev never re-clicks obsidian and waits for external instamine.")
        .defaultValue(MineMode.Vanilla)
        .build()
    );

    private final Setting<SwapMode> swapMode = sgGeneral.add(new EnumSetting.Builder<SwapMode>()
        .name("swap-mode")
        .description("Item switch mode.")
        .defaultValue(SwapMode.Silent)
        .build()
    );

    private PlayerEntity target;
    private String targetId;
    private BlockPos activeBase;
    private boolean activeFallback;
    private boolean crystalPlacedInCycle;
    private int crystalRetryTicks;
    private int postBreakTicks;
    private boolean instaStartSentForTarget;
    private BlockPos pendingInstaStartPos;
    private int pendingInstaStartTicks;
    private BlockPos preferredBase;
    private boolean preferredFallback;

    public AutoCev() {
        super(AddonTemplate.CATEGORY, "auto-cev", "Places one cev base, puts crystal, mines obsidian and detonates crystal every cycle.");
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

        tickPendingInstaStart();

        target = selectTarget();
        if (target == null) {
            resetAll();
            return;
        }

        String id = target.getUuidAsString();
        if (targetId == null || !targetId.equals(id)) {
            targetId = id;
            instaStartSentForTarget = false;
            preferredBase = null;
            preferredFallback = false;
            resetCycle();
        }

        if (activeBase != null) {
            tickCycle();
            if (activeBase != null) return;
        }

        BaseChoice choice = chooseBase(target);
        if (choice == null) return;

        BlockState state = mc.world.getBlockState(choice.pos());
        if (state.isOf(Blocks.OBSIDIAN)) {
            startCycle(choice);
            tickCycle();
            return;
        }

        if (state.isAir() || state.isReplaceable()) {
            placeObsidian(choice);
        }
    }

    private PlayerEntity selectTarget() {
        return (PlayerEntity) TargetUtils.get(entity -> {
            if (!(entity instanceof PlayerEntity player)) return false;
            if (player == mc.player) return false;
            if (player.isDead() || player.getHealth() <= 0) return false;
            if (Friends.get().isFriend(player)) return false;
            return mc.player.distanceTo(player) <= targetRange.get();
        }, SortPriority.LowestDistance);
    }

    private void tickCycle() {
        if (activeBase == null || target == null) {
            resetCycle();
            return;
        }

        BlockState state = mc.world.getBlockState(activeBase);
        EndCrystalEntity crystal = findCrystalAt(activeBase);

        if (state.isOf(Blocks.OBSIDIAN)) {
            postBreakTicks = 0;
            if (crystal != null) {
                crystalPlacedInCycle = true;
                crystalRetryTicks = 0;
                if (mineMode.get() == MineMode.Vanilla) {
                    mineObsidian(activeBase);
                } else {
                    scheduleInstaMineStart(activeBase);
                }
                return;
            }

            if (crystalRetryTicks > 0) {
                crystalRetryTicks--;
                return;
            }

            if (!canPlaceCrystalAt(activeBase)) {
                BaseChoice alternative = chooseUnblockedBase(target, activeBase);
                if (alternative != null && trySwitchToBase(alternative)) return;
                if (mineCrystalBlocker(activeBase)) return;
                resetCycle();
                return;
            }

            Vec3d crystalPos = crystalCenter(activeBase);
            if (antiSuicide.get() && !canExplodeSafely(crystalPos)) return;

            if (placeCrystal(activeBase)) {
                crystalPlacedInCycle = true;
                crystalRetryTicks = CRYSTAL_RETRY_TICKS;
                if (mineMode.get() == MineMode.Vanilla) {
                    mineObsidian(activeBase);
                } else {
                    scheduleInstaMineStart(activeBase);
                }
            } else {
                crystalRetryTicks = 0;
            }
            return;
        }

        if (state.isAir() || state.isReplaceable()) {
            if (crystal != null) {
                if (!antiSuicide.get() || canExplodeSafely(crystal.getPos())) {
                    crystalPlacedInCycle = true;
                    postBreakTicks = 0;
                    attackCrystal(crystal);
                }
                return;
            }

            if (!crystalPlacedInCycle) {
                resetCycle();
                return;
            }

            int waitTicks = mineMode.get() == MineMode.Insta ? INSTA_POST_BREAK_WAIT_TICKS : VANILLA_POST_BREAK_WAIT_TICKS;
            if (postBreakTicks < waitTicks) {
                postBreakTicks++;
                return;
            }

            resetCycle();
            return;
        }

        resetCycle();
    }

    private BaseChoice chooseBase(PlayerEntity player) {
        BaseChoice preferred = choosePreferredBase(player);
        if (preferred != null) return preferred;

        int x = player.getBlockX();
        int z = player.getBlockZ();
        int headY = MathHelper.floor(player.getBoundingBox().maxY);

        BlockPos top = new BlockPos(x, headY + 1, z);
        if (isUsableBase(top, player, true) || isBlockedObsidianBase(top, player)) return new BaseChoice(top, false);

        BlockPos fallback = chooseFallbackBase(player);
        if (fallback != null) return new BaseChoice(fallback, true);

        return null;
    }

    private BaseChoice choosePreferredBase(PlayerEntity player) {
        if (preferredBase == null) return null;
        if (!isPreferredStillRelevant(preferredBase, player)) {
            preferredBase = null;
            preferredFallback = false;
            return null;
        }
        if (!(isUsableBase(preferredBase, player, true) || isBlockedObsidianBase(preferredBase, player))) {
            preferredBase = null;
            preferredFallback = false;
            return null;
        }

        return new BaseChoice(preferredBase, preferredFallback);
    }

    private boolean isPreferredStillRelevant(BlockPos pos, PlayerEntity player) {
        int x = player.getBlockX();
        int z = player.getBlockZ();
        int headY = MathHelper.floor(player.getBoundingBox().maxY);
        if (pos.equals(new BlockPos(x, headY + 1, z))) return true;

        int faceY = MathHelper.floor(player.getEyeY());
        BlockPos center = new BlockPos(x, faceY, z);
        for (Direction dir : CARDINAL) {
            if (pos.equals(center.offset(dir))) return true;
        }

        return false;
    }

    private BlockPos chooseFallbackBase(PlayerEntity player) {
        int x = player.getBlockX();
        int z = player.getBlockZ();
        int faceY = MathHelper.floor(player.getEyeY());

        BlockPos center = new BlockPos(x, faceY, z);
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        boolean preferSticky = preferredBase != null
            && preferredFallback
            && isPreferredStillRelevant(preferredBase, player);

        for (Direction dir : CARDINAL) {
            BlockPos candidate = center.offset(dir);
            if (!(isUsableBase(candidate, player, true) || isBlockedObsidianBase(candidate, player))) continue;

            double score = mc.player.squaredDistanceTo(Vec3d.ofCenter(candidate));
            if (preferSticky && candidate.equals(preferredBase)) score -= 1e-3;

            if (score < bestScore) {
                bestScore = score;
                best = candidate;
            }
        }

        return best;
    }

    private BaseChoice chooseUnblockedBase(PlayerEntity player, BlockPos exclude) {
        if (preferredBase != null
            && !preferredBase.equals(exclude)
            && isPreferredStillRelevant(preferredBase, player)
            && isUsableBase(preferredBase, player, true)) {
            return new BaseChoice(preferredBase, preferredFallback);
        }

        int x = player.getBlockX();
        int z = player.getBlockZ();
        int headY = MathHelper.floor(player.getBoundingBox().maxY);
        BlockPos top = new BlockPos(x, headY + 1, z);
        if (!top.equals(exclude) && isUsableBase(top, player, true)) return new BaseChoice(top, false);

        int faceY = MathHelper.floor(player.getEyeY());
        BlockPos center = new BlockPos(x, faceY, z);
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;

        for (Direction dir : CARDINAL) {
            BlockPos candidate = center.offset(dir);
            if (candidate.equals(exclude)) continue;
            if (!isUsableBase(candidate, player, true)) continue;

            double distance = mc.player.squaredDistanceTo(Vec3d.ofCenter(candidate));
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate;
            }
        }

        if (best != null) return new BaseChoice(best, true);
        return null;
    }

    private boolean trySwitchToBase(BaseChoice choice) {
        BlockState state = mc.world.getBlockState(choice.pos());
        if (state.isOf(Blocks.OBSIDIAN)) {
            startCycle(choice);
            tickCycle();
            return true;
        }

        if (state.isAir() || state.isReplaceable()) {
            placeObsidian(choice);
            return true;
        }

        return false;
    }

    private boolean isBlockedObsidianBase(BlockPos pos, PlayerEntity player) {
        return mc.world.getBlockState(pos).isOf(Blocks.OBSIDIAN) && isUsableBase(pos, player, false);
    }

    private boolean isUsableBase(BlockPos pos, PlayerEntity player, boolean requireCrystalSpace) {
        if (mc.world.isOutOfHeightLimit(pos.getY()) || mc.world.isOutOfHeightLimit(pos.getY() + 2)) return false;

        BlockState state = mc.world.getBlockState(pos);
        if (state.isOf(Blocks.BEDROCK)) return false;
        if (!(state.isOf(Blocks.OBSIDIAN) || state.isAir() || state.isReplaceable())) return false;

        Box blockBox = new Box(pos);
        if (player.getBoundingBox().intersects(blockBox)) return false;
        if (mc.player.getBoundingBox().intersects(blockBox)) return false;

        for (Entity entity : mc.world.getOtherEntities(null, blockBox)) {
            if (entity instanceof EndCrystalEntity) continue;
            return false;
        }

        return !requireCrystalSpace || hasCrystalSpace(pos);
    }

    private boolean hasCrystalSpace(BlockPos base) {
        if (!mc.world.getBlockState(base.up()).isAir()) return false;
        if (!mc.world.getBlockState(base.up(2)).isAir()) return false;

        Box crystalBox = new Box(
            base.getX(),
            base.getY() + 1,
            base.getZ(),
            base.getX() + 1.0,
            base.getY() + 3.0,
            base.getZ() + 1.0
        );

        for (Entity entity : mc.world.getOtherEntities(null, crystalBox)) {
            if (entity instanceof EndCrystalEntity) continue;
            if (entity.isRemoved()) continue;
            return false;
        }

        return true;
    }

    private void placeObsidian(BaseChoice choice) {
        FindItemResult obsidian = InvUtils.findInHotbar(Items.OBSIDIAN);
        if (!obsidian.found()) {
            error("No obsidian in hotbar.");
            toggle();
            return;
        }

        boolean placed = BlockUtils.place(
            choice.pos(),
            obsidian,
            rotate.get(),
            rotate.get() ? ROTATE_PRIORITY : 0,
            swingHand.get(),
            true,
            swapMode.get() == SwapMode.Silent
        );

        if (placed) {
            startCycle(choice);
            if (mineMode.get() == MineMode.Insta) {
                scheduleInstaMineStart(choice.pos());
            }
        }
    }

    private void startCycle(BaseChoice choice) {
        activeBase = choice.pos().toImmutable();
        activeFallback = choice.fallback();
        preferredBase = activeBase;
        preferredFallback = activeFallback;
        crystalPlacedInCycle = findCrystalAt(activeBase) != null;
        crystalRetryTicks = 0;
        postBreakTicks = 0;
    }

    private void mineObsidian(BlockPos pos) {
        if (!mc.world.getBlockState(pos).isOf(Blocks.OBSIDIAN)) return;
        mineBlock(pos);
    }

    private boolean mineCrystalBlocker(BlockPos base) {
        if (mineBlockingBlock(base.up())) return true;
        return mineBlockingBlock(base.up(2));
    }

    private boolean mineBlockingBlock(BlockPos pos) {
        BlockState state = mc.world.getBlockState(pos);
        if (state.isAir() || state.isReplaceable()) return false;
        if (state.isOf(Blocks.BEDROCK)) return false;
        mineBlock(pos);
        return true;
    }

    private void mineBlock(BlockPos pos) {
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

    private void scheduleInstaMineStart(BlockPos pos) {
        if (instaStartSentForTarget) return;

        if (tryInstaMineStart(pos)) {
            instaStartSentForTarget = true;
            pendingInstaStartPos = null;
            pendingInstaStartTicks = 0;
            return;
        }

        pendingInstaStartPos = pos.toImmutable();
        pendingInstaStartTicks = INSTA_START_RETRY_TICKS;
    }

    private void tickPendingInstaStart() {
        if (mineMode.get() != MineMode.Insta) {
            pendingInstaStartPos = null;
            pendingInstaStartTicks = 0;
            return;
        }

        if (instaStartSentForTarget) {
            pendingInstaStartPos = null;
            pendingInstaStartTicks = 0;
            return;
        }

        if (pendingInstaStartPos == null || pendingInstaStartTicks <= 0) return;
        if (tryInstaMineStart(pendingInstaStartPos)) {
            instaStartSentForTarget = true;
            pendingInstaStartPos = null;
            pendingInstaStartTicks = 0;
            return;
        }

        pendingInstaStartTicks--;
        if (pendingInstaStartTicks <= 0) {
            pendingInstaStartPos = null;
        }
    }

    private boolean tryInstaMineStart(BlockPos pos) {
        if (!mc.world.getBlockState(pos).isOf(Blocks.OBSIDIAN)) return false;

        Runnable hitOnce = () -> {
            if (mc.interactionManager == null) return;
            mc.interactionManager.attackBlock(pos, Direction.UP);
            swing(Hand.MAIN_HAND);
        };

        if (rotate.get()) {
            Vec3d center = Vec3d.ofCenter(pos);
            Rotations.rotate(Rotations.getYaw(center), Rotations.getPitch(center), ROTATE_PRIORITY, hitOnce);
        } else {
            hitOnce.run();
        }

        return true;
    }

    private boolean placeCrystal(BlockPos base) {
        FindItemResult crystal = InvUtils.findInHotbar(Items.END_CRYSTAL);
        if (!crystal.found()) {
            error("No end crystals in hotbar.");
            toggle();
            return false;
        }

        int previousSlot = mc.player.getInventory().getSelectedSlot();
        boolean silent = swapMode.get() == SwapMode.Silent;
        boolean[] success = { false };

        Runnable place = () -> {
            Hand hand = crystal.getHand();
            boolean switched = false;

            if (hand == null) {
                if (!InvUtils.swap(crystal.slot(), false)) return;
                hand = Hand.MAIN_HAND;
                switched = true;
            }

            if (!mc.player.getStackInHand(hand).isOf(Items.END_CRYSTAL)) {
                if (switched && silent) InvUtils.swap(previousSlot, false);
                return;
            }

            Vec3d hitPos = new Vec3d(base.getX() + 0.5, base.getY() + 1.0, base.getZ() + 0.5);
            BlockHitResult hit = new BlockHitResult(hitPos, Direction.UP, base, false);

            if (mc.getNetworkHandler() != null) {
                mc.getNetworkHandler().sendPacket(new PlayerInteractBlockC2SPacket(hand, hit, 0));
                success[0] = true;
                swing(hand);
            } else if (mc.interactionManager != null) {
                success[0] = mc.interactionManager.interactBlock(mc.player, hand, hit).isAccepted();
                if (success[0]) swing(hand);
            }

            if (switched && silent) InvUtils.swap(previousSlot, false);
        };

        if (rotate.get()) {
            Vec3d look = new Vec3d(base.getX() + 0.5, base.getY() + 1.0, base.getZ() + 0.5);
            Rotations.rotate(Rotations.getYaw(look), Rotations.getPitch(look), ROTATE_PRIORITY, place);
        } else {
            place.run();
        }

        return success[0];
    }

    private void attackCrystal(EndCrystalEntity crystal) {
        Runnable attack = () -> {
            if (mc.getNetworkHandler() != null) {
                mc.getNetworkHandler().sendPacket(PlayerInteractEntityC2SPacket.attack(crystal, mc.player.isSneaking()));
            } else if (mc.interactionManager != null) {
                mc.interactionManager.attackEntity(mc.player, crystal);
            }
            swing(Hand.MAIN_HAND);
        };

        if (rotate.get()) {
            Rotations.rotate(Rotations.getYaw(crystal.getPos()), Rotations.getPitch(crystal.getPos()), ROTATE_PRIORITY, attack);
        } else {
            attack.run();
        }
    }

    private EndCrystalEntity findCrystalAt(BlockPos base) {
        Vec3d center = new Vec3d(base.getX() + 0.5, base.getY() + 1.5, base.getZ() + 0.5);
        Box crystalBox = new Box(
            base.getX(),
            base.getY() + 1.0,
            base.getZ(),
            base.getX() + 1.0,
            base.getY() + 3.0,
            base.getZ() + 1.0
        );

        EndCrystalEntity best = null;
        double bestDist = CRYSTAL_SEARCH_RADIUS_SQ;

        for (Entity entity : mc.world.getOtherEntities(null, crystalBox)) {
            if (!(entity instanceof EndCrystalEntity crystal)) continue;
            if (crystal.isRemoved()) continue;

            double dist = crystal.squaredDistanceTo(center);
            if (dist <= bestDist) {
                bestDist = dist;
                best = crystal;
            }
        }

        if (best != null) return best;

        for (Entity entity : mc.world.getEntities()) {
            if (!(entity instanceof EndCrystalEntity crystal)) continue;
            if (crystal.isRemoved()) continue;

            double dist = crystal.squaredDistanceTo(center);
            if (dist <= bestDist) {
                bestDist = dist;
                best = crystal;
            }
        }

        return best;
    }

    private boolean canPlaceCrystalAt(BlockPos base) {
        BlockState baseState = mc.world.getBlockState(base);
        if (!(baseState.isOf(Blocks.OBSIDIAN) || baseState.isOf(Blocks.BEDROCK))) return false;
        return hasCrystalSpace(base);
    }

    private Vec3d crystalCenter(BlockPos base) {
        return new Vec3d(base.getX() + 0.5, base.getY() + 1.0, base.getZ() + 0.5);
    }

    private boolean canExplodeSafely(Vec3d crystalPos) {
        float selfDamage = DamageUtils.crystalDamage(mc.player, crystalPos);
        return selfDamage < mc.player.getHealth() + mc.player.getAbsorptionAmount();
    }

    private void swing(Hand hand) {
        if (swingHand.get()) {
            mc.player.swingHand(hand);
        } else if (mc.getNetworkHandler() != null) {
            mc.getNetworkHandler().sendPacket(new HandSwingC2SPacket(hand));
        }
    }

    private void resetCycle() {
        activeBase = null;
        activeFallback = false;
        crystalPlacedInCycle = false;
        crystalRetryTicks = 0;
        postBreakTicks = 0;
        pendingInstaStartPos = null;
        pendingInstaStartTicks = 0;
    }

    private void resetAll() {
        target = null;
        targetId = null;
        instaStartSentForTarget = false;
        preferredBase = null;
        preferredFallback = false;
        resetCycle();
    }

    @Override
    public String getInfoString() {
        if (target == null) return null;
        if (activeBase == null) return target.getName().getString();
        return target.getName().getString() + (activeFallback ? " F" : " T");
    }

    private record BaseChoice(BlockPos pos, boolean fallback) {
    }

    public enum SwapMode {
        Normal,
        Silent
    }

    public enum MineMode {
        Vanilla,
        Insta
    }
}
