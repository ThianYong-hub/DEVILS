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
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
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
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.HashSet;
import java.util.Set;

public class AutoCev extends Module {
    private static final Direction[] CARDINAL = { Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST };
    private static final int ROTATE_PRIORITY = 50;
    private static final int CRYSTAL_RETRY_TICKS = 2;
    private static final int VANILLA_POST_BREAK_WAIT_TICKS = 0;
    private static final int INSTA_POST_BREAK_WAIT_TICKS = 0;
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

    private final Setting<Double> safe = sgGeneral.add(new DoubleSetting.Builder()
        .name("safe")
        .description("Minimum HP+absorption left after crystal damage.")
        .defaultValue(6.0)
        .min(0.0)
        .max(20.0)
        .sliderRange(0.0, 20.0)
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

    private final Setting<Boolean> pauseOnEat = sgGeneral.add(new BoolSetting.Builder()
        .name("pause-on-eat")
        .description("Pauses while eating/drinking or using items.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> pauseOnSword = sgGeneral.add(new BoolSetting.Builder()
        .name("pause-on-sword")
        .description("Pauses while holding a sword.")
        .defaultValue(false)
        .build()
    );

    private final Setting<MineMode> mineMode = sgGeneral.add(new EnumSetting.Builder<MineMode>()
        .name("mine-mode")
        .description("Vanilla: mines obsidian itself. Insta: sends one start-mine per block and waits for external instamine.")
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

    private BlockPos preferredBase;
    private boolean preferredFallback;

    private boolean crystalPlacedInCycle;
    private int crystalRetryTicks;
    private int postBreakTicks;

    private BlockPos instaMarksBase;
    private final Set<BlockPos> instaMinedBlocks = new HashSet<>();
    private MineMode lastMineMode;

    public AutoCev() {
        super(AddonTemplate.CATEGORY, "auto-cev", "Automatically places obsidian, end crystals and breaks the base to damage nearby players.");
    }

    @Override
    public void onActivate() {
        lastMineMode = mineMode.get();
        resetAll();
    }

    @Override
    public void onDeactivate() {
        resetAll();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null || mc.interactionManager == null) return;
        if (PlayerUtils.shouldPause(false, pauseOnEat.get(), pauseOnEat.get())) return;
        if (pauseOnSword.get() && mc.player.getMainHandStack().isIn(ItemTags.SWORDS)) return;
        if (lastMineMode != mineMode.get()) {
            clearInstaState();
            lastMineMode = mineMode.get();
        }

        target = selectTarget();
        if (target == null) {
            resetAll();
            return;
        }

        String id = target.getUuidAsString();
        if (targetId == null || !targetId.equals(id)) {
            targetId = id;
            preferredBase = null;
            preferredFallback = false;
            clearInstaState();
            resetCycle();
        }

        if (activeBase != null && tickCycle()) return;

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

    private boolean tickCycle() {
        if (activeBase == null || target == null) {
            resetCycle();
            return false;
        }

        if (activeFallback) {
            BaseChoice topChoice = chooseTopPriorityBase(target);
            if (topChoice != null && !topChoice.pos().equals(activeBase)) {
                resetCycle();
                return trySwitchToBase(topChoice);
            }
        }

        BlockState state = mc.world.getBlockState(activeBase);
        EndCrystalEntity crystal = findCrystalAt(activeBase);

        if (state.isOf(Blocks.OBSIDIAN)) {
            postBreakTicks = 0;

            if (crystal != null) {
                crystalPlacedInCycle = true;
                crystalRetryTicks = 0;

                if (!antiSuicide(crystal.getPos())) return true;

                if (mineMode.get() == MineMode.Vanilla) mineObsidian(activeBase);
                else tickInstaMine(activeBase);
                return true;
            }

            if (crystalRetryTicks > 0) {
                crystalRetryTicks--;
                return true;
            }

            if (!canPlaceCrystalAt(activeBase)) {
                BlockerType blockerType = getBlockerType(activeBase);

                if (blockerType == BlockerType.Obsidian) {
                    if (!activeFallback && isTopBase(activeBase, target)) {
                        BaseChoice openFace = chooseOpenFaceBase(target, activeBase);
                        if (openFace != null && trySwitchToBase(openFace)) return true;
                    }

                    mineCrystalBlocker(activeBase);
                    return true;
                }

                // Keep strict head priority: if current base is top and there is no obsidian blocker,
                // do not switch to face on temporary crystal-space conflicts.
                if (!activeFallback && isTopBase(activeBase, target)) {
                    if (blockerType == BlockerType.Other) {
                        BaseChoice alternative = chooseUnblockedBase(target, activeBase);
                        if (alternative != null && trySwitchToBase(alternative)) return true;
                    }
                    return true;
                }

                BaseChoice alternative = chooseUnblockedBase(target, activeBase);
                if (alternative != null && trySwitchToBase(alternative)) return true;

                resetCycle();
                return false;
            }

            if (!antiSuicide(crystalCenter(activeBase))) return true;

            if (placeCrystal(activeBase)) {
                crystalPlacedInCycle = true;
                crystalRetryTicks = CRYSTAL_RETRY_TICKS;

                if (mineMode.get() == MineMode.Vanilla) mineObsidian(activeBase);
                else tickInstaMine(activeBase);
            } else {
                crystalRetryTicks = CRYSTAL_RETRY_TICKS;
            }

            return true;
        }

        if (state.isAir() || state.isReplaceable()) {
            if (crystal != null) {
                if (antiSuicide(crystal.getPos())) {
                    crystalPlacedInCycle = true;
                    postBreakTicks = 0;
                    attackCrystal(crystal);
                }
                return true;
            }

            if (!crystalPlacedInCycle) {
                resetCycle();
                return false;
            }

            int waitTicks = mineMode.get() == MineMode.Insta ? INSTA_POST_BREAK_WAIT_TICKS : VANILLA_POST_BREAK_WAIT_TICKS;
            if (postBreakTicks < waitTicks) {
                postBreakTicks++;
                return true;
            }

            resetCycle();
            return false;
        }

        resetCycle();
        return false;
    }

    private BaseChoice chooseBase(PlayerEntity player) {
        BaseChoice topChoice = chooseTopPriorityBase(player);
        if (topChoice != null) return topChoice;

        BaseChoice preferred = choosePreferredBase(player);
        if (preferred != null) return preferred;

        BlockPos fallback = chooseFallbackBase(player);
        if (fallback != null) return new BaseChoice(fallback, true);

        return null;
    }

    private BaseChoice chooseTopPriorityBase(PlayerEntity player) {
        int x = player.getBlockX();
        int z = player.getBlockZ();
        int headY = MathHelper.floor(player.getBoundingBox().maxY);
        BlockPos top = new BlockPos(x, headY + 1, z);

        if (!isUsableBase(top, player, false)) return null;
        BlockerType blockerType = getBlockerType(top);
        if (blockerType == BlockerType.Other) return null;
        if (blockerType == BlockerType.Obsidian && chooseOpenFaceBase(player, null) != null) return null;
        return new BaseChoice(top, false);
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

        if (preferredFallback && isBlockedObsidianBase(preferredBase, player) && chooseOpenFaceBase(player, preferredBase) != null) {
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

    private boolean isTopBase(BlockPos pos, PlayerEntity player) {
        int x = player.getBlockX();
        int z = player.getBlockZ();
        int headY = MathHelper.floor(player.getBoundingBox().maxY);
        return pos.equals(new BlockPos(x, headY + 1, z));
    }

    private BlockPos chooseFallbackBase(PlayerEntity player) {
        int x = player.getBlockX();
        int z = player.getBlockZ();
        int faceY = MathHelper.floor(player.getEyeY());

        BlockPos center = new BlockPos(x, faceY, z);
        BlockPos bestOpen = null;
        double bestOpenScore = Double.MAX_VALUE;
        BlockPos bestBlocked = null;
        double bestBlockedScore = Double.MAX_VALUE;
        boolean preferSticky = preferredBase != null && preferredFallback && isPreferredStillRelevant(preferredBase, player);

        for (Direction dir : CARDINAL) {
            BlockPos candidate = center.offset(dir);
            boolean open = isUsableBase(candidate, player, true);
            boolean blocked = !open && isBlockedObsidianBase(candidate, player);
            if (!open && !blocked) continue;

            double score = mc.player.squaredDistanceTo(Vec3d.ofCenter(candidate));
            if (preferSticky && candidate.equals(preferredBase)) score -= 1e-3;

            if (open) {
                if (score < bestOpenScore) {
                    bestOpenScore = score;
                    bestOpen = candidate;
                }
            } else if (score < bestBlockedScore) {
                bestBlockedScore = score;
                bestBlocked = candidate;
            }
        }

        return bestOpen != null ? bestOpen : bestBlocked;
    }

    private BaseChoice chooseUnblockedBase(PlayerEntity player, BlockPos exclude) {
        int x = player.getBlockX();
        int z = player.getBlockZ();
        int headY = MathHelper.floor(player.getBoundingBox().maxY);
        BlockPos top = new BlockPos(x, headY + 1, z);
        if (!top.equals(exclude) && isUsableBase(top, player, true)) return new BaseChoice(top, false);

        if (preferredBase != null
            && !preferredBase.equals(exclude)
            && isPreferredStillRelevant(preferredBase, player)
            && isUsableBase(preferredBase, player, true)) {
            return new BaseChoice(preferredBase, preferredFallback);
        }

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

    private BaseChoice chooseOpenFaceBase(PlayerEntity player, BlockPos exclude) {
        int x = player.getBlockX();
        int z = player.getBlockZ();
        int faceY = MathHelper.floor(player.getEyeY());
        BlockPos center = new BlockPos(x, faceY, z);

        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;

        for (Direction dir : CARDINAL) {
            BlockPos candidate = center.offset(dir);
            if (exclude != null && candidate.equals(exclude)) continue;
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
            return placeObsidian(choice);
        }

        return false;
    }

    private boolean isBlockedObsidianBase(BlockPos pos, PlayerEntity player) {
        return mc.world.getBlockState(pos).isOf(Blocks.OBSIDIAN)
            && isUsableBase(pos, player, false)
            && getBlockerType(pos) != BlockerType.Other;
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

    private boolean placeObsidian(BaseChoice choice) {
        FindItemResult obsidian = InvUtils.findInHotbar(Items.OBSIDIAN);
        if (!obsidian.found()) {
            error("No obsidian in hotbar.");
            toggle();
            return false;
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

        if (placed) startCycle(choice);
        return placed;
    }

    private void startCycle(BaseChoice choice) {
        activeBase = choice.pos().toImmutable();
        activeFallback = choice.fallback();
        preferredBase = activeBase;
        preferredFallback = activeFallback;
        crystalPlacedInCycle = findCrystalAt(activeBase) != null;
        crystalRetryTicks = 0;
        postBreakTicks = 0;
        syncInstaMarks(activeBase);
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
        if (!state.isOf(Blocks.OBSIDIAN)) return false;
        if (mineMode.get() == MineMode.Insta) return instaMineOnce(pos);
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

    private void tickInstaMine(BlockPos pos) {
        if (!mc.world.getBlockState(pos).isOf(Blocks.OBSIDIAN)) return;
        instaMineOnce(pos);
    }

    private boolean instaMineOnce(BlockPos pos) {
        BlockPos immutable = pos.toImmutable();
        if (instaMinedBlocks.contains(immutable)) return false;

        mineBlock(immutable);
        instaMinedBlocks.add(immutable);
        return true;
    }

    private void syncInstaMarks(BlockPos base) {
        if (mineMode.get() != MineMode.Insta) return;

        BlockPos immutable = base.toImmutable();
        if (instaMarksBase == null || !instaMarksBase.equals(immutable)) {
            instaMarksBase = immutable;
            instaMinedBlocks.clear();
        }
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

            Vec3d hitPos = crystalCenter(base);
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
            Vec3d look = crystalCenter(base);
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
        BlockPos exact = base.up();
        for (Entity entity : mc.world.getEntities()) {
            if (!(entity instanceof EndCrystalEntity crystal)) continue;
            if (crystal.isRemoved()) continue;
            if (crystal.getBlockPos().equals(exact)) return crystal;
        }

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

        return best;
    }

    private boolean canPlaceCrystalAt(BlockPos base) {
        BlockState baseState = mc.world.getBlockState(base);
        if (!(baseState.isOf(Blocks.OBSIDIAN) || baseState.isOf(Blocks.BEDROCK))) return false;
        if (findCrystalAt(base) != null) return false;
        return hasCrystalSpace(base);
    }

    private BlockerType getBlockerType(BlockPos base) {
        BlockState up1 = mc.world.getBlockState(base.up());
        BlockState up2 = mc.world.getBlockState(base.up(2));

        boolean hasObsidian = false;

        if (!up1.isAir() && !up1.isReplaceable()) {
            if (up1.isOf(Blocks.OBSIDIAN)) hasObsidian = true;
            else return BlockerType.Other;
        }

        if (!up2.isAir() && !up2.isReplaceable()) {
            if (up2.isOf(Blocks.OBSIDIAN)) hasObsidian = true;
            else return BlockerType.Other;
        }

        return hasObsidian ? BlockerType.Obsidian : BlockerType.None;
    }

    private Vec3d crystalCenter(BlockPos base) {
        return new Vec3d(base.getX() + 0.5, base.getY() + 1.0, base.getZ() + 0.5);
    }

    private boolean antiSuicide(Vec3d crystalPos) {
        double hp = mc.player.getHealth() + mc.player.getAbsorptionAmount();
        float selfDamage = DamageUtils.crystalDamage(mc.player, crystalPos);
        return hp - selfDamage > safe.get();
    }

    private void swing(Hand hand) {
        if (swingHand.get()) {
            mc.player.swingHand(hand);
        } else if (mc.getNetworkHandler() != null) {
            mc.getNetworkHandler().sendPacket(new HandSwingC2SPacket(hand));
        }
    }

    private void clearInstaState() {
        instaMarksBase = null;
        instaMinedBlocks.clear();
    }

    private void resetCycle() {
        activeBase = null;
        activeFallback = false;
        crystalPlacedInCycle = false;
        crystalRetryTicks = 0;
        postBreakTicks = 0;
    }

    private void resetAll() {
        target = null;
        targetId = null;
        preferredBase = null;
        preferredFallback = false;
        clearInstaState();
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

    private enum BlockerType {
        None,
        Obsidian,
        Other
    }
}
