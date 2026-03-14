package com.example.addon.modules;

import com.example.addon.AddonTemplate;
import com.example.addon.mixin.ClientPlayerInteractionManagerInvoker;
import com.example.addon.util.CrashGuard;
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
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.HandSwingC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractEntityC2SPacket;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;

import java.util.ArrayDeque;
import java.util.Deque;

public class AutoCev extends Module {
    private static final Direction[] CARDINAL = { Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST };
    private static final int FACE_SEARCH_RADIUS = 2;
    private static final double FACE_WIDE_SEARCH_DISTANCE = 5.0;
    private static final int ROTATE_PRIORITY = 50;
    private static final int DEBUG_LOG_LIMIT = 40;

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
        .description("Rotate before place, mine and attack.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> swingHand = sgGeneral.add(new BoolSetting.Builder()
        .name("swing-hand")
        .description("Swing hand while placing or attacking.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> pauseOnEat = sgGeneral.add(new BoolSetting.Builder()
        .name("pause-on-eat")
        .description("Pause while eating, drinking or using items.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> pauseOnSword = sgGeneral.add(new BoolSetting.Builder()
        .name("pause-on-sword")
        .description("Pause while holding a sword.")
        .defaultValue(false)
        .build()
    );

    private final Setting<MineMode> mineMode = sgGeneral.add(new EnumSetting.Builder<MineMode>()
        .name("mine-mode")
        .description("Vanilla mines the block itself. Insta only starts mining for an external instamine.")
        .defaultValue(MineMode.Insta)
        .build()
    );

    private final Setting<SwapMode> swapMode = sgGeneral.add(new EnumSetting.Builder<SwapMode>()
        .name("swap-mode")
        .description("Item switch mode.")
        .defaultValue(SwapMode.Silent)
        .build()
    );

    private final Setting<Boolean> debugClipboard = sgGeneral.add(new BoolSetting.Builder()
        .name("debug-clipboard")
        .description("Copies important AutoCev actions to the clipboard.")
        .defaultValue(false)
        .build()
    );

    private PlayerEntity target;
    private String targetId;
    private BlockPos activeBase;
    private PlanType activeType;
    private BlockPos lockedFaceBase;
    private BlockPos lockedHeadMineBase;
    private BlockPos instaMinePos;
    private final Deque<String> debugLines = new ArrayDeque<>();
    private int debugCounter;

    public AutoCev() {
        super(AddonTemplate.CATEGORY, "auto-cev", "Places obsidian and crystals for a simple, aggressive cev cycle.");
    }

    @Override
    public void onActivate() {
        resetState();
    }

    @Override
    public void onDeactivate() {
        resetState();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        CrashGuard.run(this, "onTickPre", this::tickSafe);
    }

    private void tickSafe() {
        if (mc.player == null || mc.world == null || mc.interactionManager == null) return;
        if (PlayerUtils.shouldPause(false, pauseOnEat.get(), pauseOnEat.get())) return;
        if (pauseOnSword.get() && mc.player.getMainHandStack().isIn(ItemTags.SWORDS)) return;

        target = selectTarget();
        if (target == null) {
            debug("no target -> reset");
            resetState();
            return;
        }

        String id = target.getUuidAsString();
        if (!id.equals(targetId)) {
            debug("target changed -> " + target.getName().getString());
            targetId = id;
            activeBase = null;
            activeType = null;
            lockedFaceBase = null;
            lockedHeadMineBase = null;
            instaMinePos = null;
        }

        CyclePlan head = chooseHeadPlan(target);
        CyclePlan plan = choosePlan(target);
        if (plan == null) {
            if ((activeType == PlanType.HEAD || activeType == PlanType.HEAD_CLEAR || activeType == PlanType.HEAD_BLOCKER) && lockedHeadMineBase != null) {
                debug("no plan -> keep head lock " + formatPos(lockedHeadMineBase));
                return;
            }
            debug("no plan for " + target.getName().getString() + " head=" + planSummary(head));
            activeBase = null;
            activeType = null;
            lockedFaceBase = null;
            lockedHeadMineBase = null;
            instaMinePos = null;
            return;
        }

        if ((plan.type() == PlanType.FACE || plan.type() == PlanType.FACE_BLOCKER) && head != null && head.type() == PlanType.HEAD_BLOCKER) {
            if (lockedFaceBase == null || !lockedFaceBase.equals(plan.pos())) instaMinePos = null;
            lockedFaceBase = plan.pos().toImmutable();
            debug("lock face base -> " + formatPos(lockedFaceBase) + " head=" + planSummary(head));
        } else if (plan.type() == PlanType.HEAD) {
            if (lockedFaceBase != null) debug("unlock face base -> head open");
            lockedFaceBase = null;
        }

        boolean keepsHeadMineLock = (plan.type() == PlanType.HEAD || plan.type() == PlanType.HEAD_CLEAR || plan.type() == PlanType.HEAD_BLOCKER)
            && lockedHeadMineBase != null
            && lockedHeadMineBase.equals(plan.pos());
        if (!keepsHeadMineLock) {
            if (lockedHeadMineBase != null) {
                debug("unlock head mine -> " + formatPos(lockedHeadMineBase));
            }
            lockedHeadMineBase = null;
        }

        if (activeBase == null || !activeBase.equals(plan.pos())) {
            if (instaMinePos != null) debug("clear insta pos -> active base changed from " + formatPos(activeBase) + " to " + formatPos(plan.pos()));
            instaMinePos = null;
        }
        activeBase = plan.pos();
        activeType = plan.type();
        debug("plan -> " + planSummary(plan));
        executePlan(plan);
    }

    private PlayerEntity selectTarget() {
        return (PlayerEntity) TargetUtils.get(entity -> {
            if (!(entity instanceof PlayerEntity player)) return false;
            if (player == mc.player) return false;
            if (player.isDead() || player.getHealth() <= 0) return false;
            if (Friends.get().isFriend(player)) return false;
            if (isSameHole(mc.player, player)) return false;
            if (isPlayerInBlocks(player)) return false;
            return mc.player.distanceTo(player) <= targetRange.get();
        }, SortPriority.LowestDistance);
    }

    private CyclePlan choosePlan(PlayerEntity player) {
        CyclePlan head = chooseHeadPlan(player);
        CyclePlan active = getActivePlan(player);
        CyclePlan lockedHead = getLockedHeadPlan(player);
        CyclePlan face = chooseFacePlan(player, shouldUseWideFaceSearch(player, head));
        CyclePlan lockedFace = getLockedFacePlan(player);

        if (lockedHead != null) return lockedHead;
        if (head != null && head.type() == PlanType.HEAD_CLEAR) return head;
        if (head != null && head.type() == PlanType.HEAD_BLOCKER) {
            if (lockedFace != null) return lockedFace;
            if (face != null) return preferActive(face, active);
            if (lockedFaceBase != null) debug("clear face lock -> no open face, use head blocker");
            lockedFaceBase = null;
            return head;
        }
        if (lockedFace != null) return lockedFace;

        if (head != null && head.type() == PlanType.HEAD) return preferActive(head, active);
        if (active != null && (active.type() == PlanType.FACE || active.type() == PlanType.FACE_BLOCKER)) return active;
        if (face != null) return preferActive(face, active);
        if (head != null && head.type() == PlanType.HEAD_BLOCKER) return head;
        return null;
    }

    private CyclePlan chooseHeadPlan(PlayerEntity player) {
        BlockPos pos = getTopBase(player);
        if (pos == null) return null;

        SpaceState state = getSpaceStateForBase(pos, player);
        if (state == SpaceState.OPEN) return new CyclePlan(pos.toImmutable(), PlanType.HEAD, score(pos));
        if (state == SpaceState.OBSIDIAN_BLOCKER) return new CyclePlan(pos.toImmutable(), PlanType.HEAD_BLOCKER, score(pos));
        if (state == SpaceState.ITEM_BLOCKER && mc.world != null && mc.world.getBlockState(pos).isOf(Blocks.OBSIDIAN)) {
            return new CyclePlan(pos.toImmutable(), PlanType.HEAD_CLEAR, score(pos));
        }
        return null;
    }

    private CyclePlan getActivePlan(PlayerEntity player) {
        if (activeBase == null || activeType == null) return null;

        return switch (activeType) {
            case HEAD -> {
                CyclePlan head = chooseHeadPlan(player);
                yield head != null && head.type() == PlanType.HEAD && head.pos().equals(activeBase) ? head : null;
            }
            case HEAD_CLEAR -> {
                CyclePlan head = chooseHeadPlan(player);
                yield head != null && head.type() == PlanType.HEAD_CLEAR && head.pos().equals(activeBase) ? head : null;
            }
            case FACE -> {
                SpaceState state = getSpaceStateForBase(activeBase, player);
                yield state == SpaceState.OPEN ? new CyclePlan(activeBase.toImmutable(), PlanType.FACE, score(activeBase) - 1e-3) : null;
            }
            case FACE_BLOCKER -> {
                SpaceState state = getSpaceStateForBase(activeBase, player);
                yield state == SpaceState.OBSIDIAN_BLOCKER ? new CyclePlan(activeBase.toImmutable(), PlanType.FACE_BLOCKER, score(activeBase) - 1e-3) : null;
            }
            case HEAD_BLOCKER -> {
                CyclePlan head = chooseHeadPlan(player);
                yield head != null && head.type() == PlanType.HEAD_BLOCKER && head.pos().equals(activeBase) ? head : null;
            }
        };
    }

    private CyclePlan getLockedFacePlan(PlayerEntity player) {
        if (lockedFaceBase == null || player == null || mc.world == null) return null;
        if (!isRelevantBase(lockedFaceBase, player)) {
            lockedFaceBase = null;
            return null;
        }

        BlockState blockState = mc.world.getBlockState(lockedFaceBase);
        if (blockState.isOf(Blocks.BEDROCK) || (!blockState.isOf(Blocks.OBSIDIAN) && !blockState.isAir() && !blockState.isReplaceable())) {
            lockedFaceBase = null;
            return null;
        }

        SpaceState faceState = getSpaceStateForBase(lockedFaceBase, player);
        if (faceState == SpaceState.OPEN) return new CyclePlan(lockedFaceBase.toImmutable(), PlanType.FACE, score(lockedFaceBase) - 1e-3);
        if (faceState == SpaceState.OBSIDIAN_BLOCKER) return new CyclePlan(lockedFaceBase.toImmutable(), PlanType.FACE_BLOCKER, score(lockedFaceBase) - 1e-3);

        lockedFaceBase = null;
        return null;
    }

    private CyclePlan getLockedHeadPlan(PlayerEntity player) {
        if (lockedHeadMineBase == null || player == null || mc.world == null) return null;
        if (activeType != PlanType.HEAD && activeType != PlanType.HEAD_CLEAR && activeType != PlanType.HEAD_BLOCKER) return null;
        if (!isTopBase(lockedHeadMineBase, player)) return null;

        BlockState state = mc.world.getBlockState(lockedHeadMineBase);
        if (state.isOf(Blocks.BEDROCK) || (!state.isOf(Blocks.OBSIDIAN) && !state.isAir() && !state.isReplaceable())) return null;

        CyclePlan head = chooseHeadPlan(player);
        if (head != null && head.pos().equals(lockedHeadMineBase)) {
            return new CyclePlan(lockedHeadMineBase.toImmutable(), head.type(), score(lockedHeadMineBase) - 1e-3);
        }

        return new CyclePlan(lockedHeadMineBase.toImmutable(), PlanType.HEAD, score(lockedHeadMineBase) - 1e-3);
    }

    private CyclePlan chooseFacePlan(PlayerEntity player, boolean wideSearch) {
        if (player == null || mc.player == null || mc.world == null) return null;

        BlockPos center = getFaceCenter(player);
        if (center == null) return null;

        CyclePlan bestOpen = null;
        CyclePlan bestBlocker = null;
        if (!wideSearch) {
            for (Direction dir : CARDINAL) {
                BlockPos pos = center.offset(dir);
                if (!isFreshFaceBase(pos)) continue;

                SpaceState state = getSpaceStateForBase(pos, player);
                if (state == SpaceState.OPEN) {
                    CyclePlan plan = new CyclePlan(pos.toImmutable(), PlanType.FACE, faceScore(pos, player));
                    if (bestOpen == null || plan.score() < bestOpen.score()) bestOpen = plan;
                } else if (state == SpaceState.OBSIDIAN_BLOCKER) {
                    CyclePlan plan = new CyclePlan(pos.toImmutable(), PlanType.FACE_BLOCKER, faceScore(pos, player));
                    if (bestBlocker == null || plan.score() < bestBlocker.score()) bestBlocker = plan;
                }
            }
        } else {
            for (int x = -FACE_SEARCH_RADIUS; x <= FACE_SEARCH_RADIUS; x++) {
                for (int z = -FACE_SEARCH_RADIUS; z <= FACE_SEARCH_RADIUS; z++) {
                    if (x == 0 && z == 0) continue;

                    BlockPos pos = center.add(x, 0, z);
                    if (!isFreshFaceBase(pos)) continue;

                    SpaceState state = getSpaceStateForBase(pos, player);
                    if (state == SpaceState.OPEN) {
                        CyclePlan plan = new CyclePlan(pos.toImmutable(), PlanType.FACE, faceScore(pos, player));
                        if (bestOpen == null || plan.score() < bestOpen.score()) bestOpen = plan;
                    } else if (state == SpaceState.OBSIDIAN_BLOCKER) {
                        CyclePlan plan = new CyclePlan(pos.toImmutable(), PlanType.FACE_BLOCKER, faceScore(pos, player));
                        if (bestBlocker == null || plan.score() < bestBlocker.score()) bestBlocker = plan;
                    }
                }
            }
        }

        return bestOpen != null ? bestOpen : bestBlocker;
    }

    private CyclePlan preferActive(CyclePlan candidate, CyclePlan active) {
        if (candidate == null) return active;
        if (active == null) return candidate;
        if (!candidate.pos().equals(active.pos())) return candidate;
        return active;
    }

    private SpaceState getSpaceStateForBase(BlockPos pos, PlayerEntity player) {
        if (pos == null || player == null || mc.player == null || mc.world == null) return SpaceState.INVALID;
        if (!isRelevantBase(pos, player)) return SpaceState.INVALID;
        if (!canOccupyBase(pos, player)) return SpaceState.INVALID;

        BlockState state = mc.world.getBlockState(pos);
        if (state.isOf(Blocks.BEDROCK)) return SpaceState.INVALID;
        if (!(state.isOf(Blocks.OBSIDIAN) || state.isAir() || state.isReplaceable())) return SpaceState.INVALID;

        return getSpaceState(pos);
    }

    private double score(BlockPos pos) {
        if (mc.player == null || pos == null) return Double.MAX_VALUE;
        double score = mc.player.squaredDistanceTo(Vec3d.ofCenter(pos));
        if (activeBase != null && activeBase.equals(pos)) score -= 1e-3;
        return score;
    }

    private double faceScore(BlockPos pos, PlayerEntity player) {
        if (pos == null || player == null) return Double.MAX_VALUE;

        double score = score(pos);
        score -= DamageUtils.crystalDamage(player, crystalCenter(pos)) * 100.0;
        return score;
    }

    private boolean shouldUseWideFaceSearch(PlayerEntity player, CyclePlan head) {
        if (player == null || mc.player == null) return false;
        if (mc.player.distanceTo(player) > FACE_WIDE_SEARCH_DISTANCE) return true;
        if (head != null && head.type() == PlanType.HEAD_BLOCKER) return true;
        if (activeType == PlanType.FACE_BLOCKER) return true;
        if (lockedFaceBase == null) return false;

        SpaceState state = getSpaceStateForBase(lockedFaceBase, player);
        return state == SpaceState.OBSIDIAN_BLOCKER;
    }

    private boolean baseNeedsPlacement(BlockPos pos) {
        if (pos == null || mc.world == null) return false;
        BlockState state = mc.world.getBlockState(pos);
        return state.isAir() || state.isReplaceable();
    }

    private boolean isFreshFaceBase(BlockPos pos) {
        if (pos == null || mc.world == null) return false;
        if (lockedFaceBase != null && lockedFaceBase.equals(pos)) return true;

        BlockState state = mc.world.getBlockState(pos);
        return state.isAir() || state.isReplaceable();
    }

    private void executePlan(CyclePlan plan) {
        if (plan == null || mc.player == null || mc.world == null) return;

        switch (plan.type()) {
            case HEAD, FACE -> executeCyclePlan(plan);
            case FACE_BLOCKER -> executeFaceBlockerPlan(plan);
            case HEAD_CLEAR -> executeHeadClearPlan(plan);
            case HEAD_BLOCKER -> executeHeadBlockerPlan(plan);
        }
    }

    private void executeFaceBlockerPlan(CyclePlan plan) {
        if (plan == null || mc.world == null || target == null) return;

        if (baseNeedsPlacement(plan.pos())) {
            if (placeObsidian(plan.pos())) {
                tryContinueCycleSameTick(new CyclePlan(plan.pos(), PlanType.FACE_BLOCKER, score(plan.pos())));
            }
            return;
        }

        if (mineCrystalBlocker(plan.pos())) return;

        if (getSpaceStateForBase(plan.pos(), target) == SpaceState.OPEN) {
            executeCyclePlan(new CyclePlan(plan.pos(), PlanType.FACE, score(plan.pos())));
        }
    }

    private void executeHeadClearPlan(CyclePlan plan) {
        if (plan == null || mc.world == null) return;
        if (!mc.world.getBlockState(plan.pos()).isOf(Blocks.OBSIDIAN)) return;

        if (mineMode.get() == MineMode.Insta && lockedHeadMineBase != null && lockedHeadMineBase.equals(plan.pos())) {
            debug("head clear -> keep external mine " + formatPos(plan.pos()));
            return;
        }

        debug("head clear -> mine base " + formatPos(plan.pos()));
        mineBase(plan.pos());
    }

    private void executeCyclePlan(CyclePlan plan) {
        if (plan == null || mc.player == null || mc.world == null) return;

        BlockState state = mc.world.getBlockState(plan.pos());
        EndCrystalEntity crystal = findCrystalAt(plan.pos());

        if (state.isOf(Blocks.OBSIDIAN)) {
            if (crystal != null) {
                if (antiSuicide(crystal.getPos())) mineBase(plan.pos());
                return;
            }

            if (!antiSuicide(crystalCenter(plan.pos()))) return;

            if (placeCrystal(plan.pos())) mineBase(plan.pos());
            return;
        }

        if (!state.isAir() && !state.isReplaceable()) return;

        if (crystal != null) {
            if (antiSuicide(crystal.getPos())) attackCrystal(crystal);
            return;
        }

        if (getSpaceStateForBase(plan.pos(), target) != SpaceState.OPEN) return;
        if (placeObsidian(plan.pos())) tryContinueCycleSameTick(plan);
    }

    private void executeHeadBlockerPlan(CyclePlan plan) {
        if (plan == null || mc.world == null || target == null) return;

        if (mineCrystalBlocker(plan.pos())) return;

        if (baseNeedsPlacement(plan.pos())) {
            debug("head blocker setup -> place obsidian " + formatPos(plan.pos()));
            if (placeObsidian(plan.pos())) {
                tryContinueCycleSameTick(new CyclePlan(plan.pos(), PlanType.HEAD, score(plan.pos())));
            }
            return;
        }
    }

    private BlockPos getTopBase(PlayerEntity player) {
        if (player == null) return null;
        return new BlockPos(player.getBlockX(), MathHelper.floor(player.getBoundingBox().maxY) + 1, player.getBlockZ());
    }

    private boolean isTopBase(BlockPos pos, PlayerEntity player) {
        if (pos == null || player == null) return false;
        return pos.equals(getTopBase(player));
    }

    private BlockPos getFaceCenter(PlayerEntity player) {
        if (player == null) return null;
        int faceY = MathHelper.floor(player.getEyeY());
        return new BlockPos(player.getBlockX(), faceY, player.getBlockZ());
    }

    private boolean isRelevantBase(BlockPos pos, PlayerEntity player) {
        if (pos == null || player == null) return false;
        if (isTopBase(pos, player)) return true;

        return isFaceCandidate(pos, player);
    }

    private boolean isFaceCandidate(BlockPos pos, PlayerEntity player) {
        if (pos == null || player == null) return false;

        BlockPos center = getFaceCenter(player);
        if (center == null || pos.getY() != center.getY()) return false;

        int dx = Math.abs(pos.getX() - center.getX());
        int dz = Math.abs(pos.getZ() - center.getZ());
        return (dx != 0 || dz != 0) && dx <= FACE_SEARCH_RADIUS && dz <= FACE_SEARCH_RADIUS;
    }

    private boolean canOccupyBase(BlockPos pos, PlayerEntity player) {
        if (pos == null || player == null || mc.player == null || mc.world == null) return false;
        if (mc.world.isOutOfHeightLimit(pos.getY()) || mc.world.isOutOfHeightLimit(pos.getY() + 2)) return false;

        Box blockBox = new Box(pos);
        if (player.getBoundingBox().intersects(blockBox)) return false;
        if (mc.player.getBoundingBox().intersects(blockBox)) return false;

        for (Entity entity : mc.world.getOtherEntities(null, blockBox)) {
            if (entity instanceof EndCrystalEntity) continue;
            return false;
        }

        return true;
    }

    private SpaceState getSpaceState(BlockPos base) {
        if (base == null || mc.world == null) return SpaceState.INVALID;
        if (findCrystalAt(base) != null) return SpaceState.OPEN;

        BlockState up1 = mc.world.getBlockState(base.up());
        BlockState up2 = mc.world.getBlockState(base.up(2));

        boolean obsidianBlocker = false;
        boolean itemBlocker = false;

        if (!up1.isAir()) {
            if (up1.isOf(Blocks.OBSIDIAN)) obsidianBlocker = true;
            else return SpaceState.OTHER_BLOCKER;
        }

        if (!up2.isAir()) {
            if (up2.isOf(Blocks.OBSIDIAN)) obsidianBlocker = true;
            else return SpaceState.OTHER_BLOCKER;
        }

        Box crystalBox = new Box(
            base.getX(),
            base.getY() + 1.0,
            base.getZ(),
            base.getX() + 1.0,
            base.getY() + 3.0,
            base.getZ() + 1.0
        );

        for (Entity entity : mc.world.getOtherEntities(null, crystalBox)) {
            if (entity instanceof EndCrystalEntity) continue;
            if (entity.isRemoved()) continue;
            if (entity instanceof ItemEntity) {
                itemBlocker = true;
                continue;
            }
            return SpaceState.OTHER_BLOCKER;
        }

        if (itemBlocker) return SpaceState.ITEM_BLOCKER;
        return obsidianBlocker ? SpaceState.OBSIDIAN_BLOCKER : SpaceState.OPEN;
    }

    private boolean placeObsidian(BlockPos pos) {
        if (pos == null || mc.player == null || mc.world == null) return false;

        FindItemResult obsidian = InvUtils.findInHotbar(Items.OBSIDIAN);
        if (!obsidian.found()) {
            error("No obsidian in hotbar.");
            toggle();
            return false;
        }

        debug("place obsidian attempt -> " + formatPos(pos));
        boolean placed = BlockUtils.place(
            pos,
            obsidian,
            rotate.get(),
            rotate.get() ? ROTATE_PRIORITY : 0,
            swingHand.get(),
            true,
            swapMode.get() == SwapMode.Silent
        );
        debug("place obsidian result -> " + formatPos(pos) + " success=" + placed);
        return placed;
    }

    private void tryContinueCycleSameTick(CyclePlan plan) {
        if (plan == null || mc.world == null || target == null) return;
        if (!mc.world.getBlockState(plan.pos()).isOf(Blocks.OBSIDIAN)) return;

        if (plan.type() == PlanType.HEAD || plan.type() == PlanType.FACE) executeCyclePlan(plan);
        else if (plan.type() == PlanType.FACE_BLOCKER) executeFaceBlockerPlan(plan);
    }

    private boolean placeCrystal(BlockPos base) {
        if (base == null || mc.player == null || mc.world == null) return false;

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
            if (mc.player == null || mc.world == null) return;

            Hand hand = crystal.getHand();
            boolean switched = false;

            if (hand == null) {
                if (!InvUtils.swap(crystal.slot(), !silent)) return;
                hand = Hand.MAIN_HAND;
                switched = true;
            }

            if (!mc.player.getStackInHand(hand).isOf(Items.END_CRYSTAL)) {
                if (switched) restoreSlot(previousSlot, silent);
                return;
            }

            BlockHitResult hit = new BlockHitResult(crystalCenter(base), Direction.UP, base, false);
            ActionResult result = interactBlock(hand, hit);

            success[0] = result.isAccepted();
            if (success[0]) {
                debug("place crystal -> " + formatPos(base));
                swing(hand);
            }

            if (switched) restoreSlot(previousSlot, silent);
        };

        if (rotate.get()) {
            Vec3d look = crystalCenter(base);
            Rotations.rotate(Rotations.getYaw(look), Rotations.getPitch(look), ROTATE_PRIORITY, place);
        } else {
            place.run();
        }

        return success[0];
    }

    private void restoreSlot(int previousSlot, boolean silent) {
        if (silent) InvUtils.swap(previousSlot, false);
        else InvUtils.swapBack();
    }

    private ActionResult interactBlock(Hand hand, BlockHitResult hit) {
        if (mc.player == null || mc.interactionManager == null) return ActionResult.PASS;

        boolean wasSneaking = mc.player.isSneaking();
        mc.player.setSneaking(false);

        ActionResult result = mc.interactionManager.interactBlock(mc.player, hand, hit);

        mc.player.setSneaking(wasSneaking);
        return result;
    }

    private void mineBase(BlockPos pos) {
        if (mineMode.get() == MineMode.Insta) {
            if ((activeType == PlanType.HEAD || activeType == PlanType.HEAD_CLEAR || activeType == PlanType.HEAD_BLOCKER) && pos != null && pos.equals(lockedHeadMineBase)) {
                debug("skip mineBase -> head lock owns " + formatPos(pos));
                return;
            }
            ensureInstaMine(pos);
        }
        else {
            debug("vanilla mine -> " + formatPos(pos));
            mineBlock(pos);
        }
    }

    private boolean mineCrystalBlocker(BlockPos base) {
        if (base == null || mc.world == null) return false;
        if (target != null && isTopBase(base, target) && chooseFacePlan(target, shouldUseWideFaceSearch(target, chooseHeadPlan(target))) != null) {
            debug("skip head blocker mine -> face exists");
            return false;
        }
        if (mineBlockingBlock(base.up())) return true;
        return mineBlockingBlock(base.up(2));
    }

    private boolean mineBlockingBlock(BlockPos pos) {
        if (pos == null || mc.world == null) return false;
        if (!mc.world.getBlockState(pos).isOf(Blocks.OBSIDIAN)) return false;

        if (mineMode.get() == MineMode.Insta) return ensureInstaMine(pos);
        debug("vanilla mine blocker -> " + formatPos(pos));
        mineBlock(pos);
        return true;
    }

    private void mineBlock(BlockPos pos) {
        Runnable mine = () -> {
            if (mc.interactionManager == null) return;
            debug("attack block -> " + formatPos(pos) + " dir=UP");
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

    private boolean ensureInstaMine(BlockPos pos) {
        if (pos == null || mc.interactionManager == null) return false;
        BlockPos immutable = pos.toImmutable();
        if ((activeType == PlanType.HEAD || activeType == PlanType.HEAD_CLEAR || activeType == PlanType.HEAD_BLOCKER) && immutable.equals(lockedHeadMineBase)) {
            debug("skip insta mine -> head locked " + formatPos(immutable));
            return false;
        }
        if (immutable.equals(instaMinePos)) {
            debug("skip insta mine -> insta pos already set " + formatPos(immutable));
            return false;
        }
        if (isBreakingBlock(immutable)) {
            debug("skip insta mine -> client already breaking " + formatPos(immutable));
            return false;
        }

        Runnable mine = () -> {
            if (mc.interactionManager == null) return;
            debug("start insta mine -> " + formatPos(immutable) + " type=" + activeType);
            debug("attack block -> " + formatPos(immutable) + " dir=" + BlockUtils.getDirection(immutable));
            mc.interactionManager.attackBlock(immutable, BlockUtils.getDirection(immutable));
        };

        if (rotate.get()) {
            Vec3d center = Vec3d.ofCenter(immutable);
            Rotations.rotate(Rotations.getYaw(center), Rotations.getPitch(center), ROTATE_PRIORITY, mine);
        } else {
            mine.run();
        }

        if (activeType == PlanType.HEAD || activeType == PlanType.HEAD_CLEAR || activeType == PlanType.HEAD_BLOCKER) lockedHeadMineBase = immutable;
        instaMinePos = immutable;
        return true;
    }

    private void attackCrystal(EndCrystalEntity crystal) {
        if (crystal == null || mc.player == null || mc.world == null) return;

        Runnable attack = () -> {
            if (mc.player == null) return;
            debug("attack crystal -> " + formatPos(BlockPos.ofFloored(crystal.getPos())));
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
        if (base == null || mc.world == null) return null;

        Vec3d center = new Vec3d(base.getX() + 0.5, base.getY() + 1.5, base.getZ() + 0.5);
        Box crystalBox = new Box(
            base.getX() + 1e-3,
            base.getY() + 1.0,
            base.getZ() + 1e-3,
            base.getX() + 1.0 - 1e-3,
            base.getY() + 3.0,
            base.getZ() + 1.0 - 1e-3
        );

        EndCrystalEntity best = null;
        double bestDistance = Double.MAX_VALUE;

        for (Entity entity : mc.world.getOtherEntities(null, crystalBox)) {
            if (!(entity instanceof EndCrystalEntity crystal)) continue;
            if (crystal.isRemoved()) continue;
            if (!BlockPos.ofFloored(crystal.getX(), crystal.getY() - 1.0, crystal.getZ()).equals(base)) continue;

            double distance = crystal.squaredDistanceTo(center);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = crystal;
            }
        }

        return best;
    }

    private Vec3d crystalCenter(BlockPos base) {
        return new Vec3d(base.getX() + 0.5, base.getY() + 1.0, base.getZ() + 0.5);
    }

    private boolean antiSuicide(Vec3d crystalPos) {
        if (crystalPos == null || mc.player == null) return false;
        double hp = mc.player.getHealth() + mc.player.getAbsorptionAmount();
        float selfDamage = DamageUtils.crystalDamage(mc.player, crystalPos);
        return hp - selfDamage > safe.get();
    }

    private void swing(Hand hand) {
        if (mc.player == null) return;
        debug("swing -> " + hand.name() + " client=" + swingHand.get());
        if (swingHand.get()) {
            mc.player.swingHand(hand);
        } else if (mc.getNetworkHandler() != null) {
            mc.getNetworkHandler().sendPacket(new HandSwingC2SPacket(hand));
        }
    }

    private boolean isBreakingBlock(BlockPos pos) {
        if (pos == null || mc.interactionManager == null) return false;
        return ((ClientPlayerInteractionManagerInvoker) mc.interactionManager).devilsAddon$isCurrentlyBreaking(pos);
    }

    private boolean isPlayerInBlocks(PlayerEntity player) {
        if (player == null || mc.world == null) return false;

        Box box = player.getBoundingBox().contract(1e-3);
        int minX = MathHelper.floor(box.minX);
        int minY = MathHelper.floor(box.minY);
        int minZ = MathHelper.floor(box.minZ);
        int maxX = MathHelper.floor(box.maxX);
        int maxY = MathHelper.floor(box.maxY);
        int maxZ = MathHelper.floor(box.maxZ);

        BlockPos.Mutable mutable = new BlockPos.Mutable();

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    mutable.set(x, y, z);

                    BlockState state = mc.world.getBlockState(mutable);
                    if (state.isAir() || state.isReplaceable()) continue;

                    VoxelShape shape = state.getCollisionShape(mc.world, mutable);
                    if (shape.isEmpty()) continue;

                    for (Box shapeBox : shape.getBoundingBoxes()) {
                        if (shapeBox.offset(mutable).intersects(box)) return true;
                    }
                }
            }
        }

        return false;
    }

    private boolean isSameHole(PlayerEntity first, PlayerEntity second) {
        if (first == null || second == null || mc.world == null) return false;

        BlockPos firstPos = first.getBlockPos();
        BlockPos secondPos = second.getBlockPos();

        if (firstPos.equals(secondPos)) return isSingleHole(firstPos) || isDoubleHoleCell(firstPos);
        if (firstPos.getY() != secondPos.getY()) return false;

        int dx = Math.abs(firstPos.getX() - secondPos.getX());
        int dz = Math.abs(firstPos.getZ() - secondPos.getZ());
        if (dx + dz != 1) return false;

        return isDoubleHole(firstPos, secondPos);
    }

    private boolean isSingleHole(BlockPos pos) {
        if (pos == null || mc.world == null) return false;
        if (!mc.world.getBlockState(pos).isAir()) return false;
        if (!mc.world.getBlockState(pos.up()).isAir()) return false;

        return isHoleWall(pos.down())
            && isHoleWall(pos.north())
            && isHoleWall(pos.south())
            && isHoleWall(pos.east())
            && isHoleWall(pos.west());
    }

    private boolean isDoubleHoleCell(BlockPos pos) {
        if (pos == null) return false;

        for (Direction direction : CARDINAL) {
            if (isDoubleHole(pos, pos.offset(direction))) return true;
        }

        return false;
    }

    private boolean isDoubleHole(BlockPos firstPos, BlockPos secondPos) {
        if (firstPos == null || secondPos == null || mc.world == null) return false;
        if (firstPos.getY() != secondPos.getY()) return false;
        if (!mc.world.getBlockState(firstPos).isAir() || !mc.world.getBlockState(firstPos.up()).isAir()) return false;
        if (!mc.world.getBlockState(secondPos).isAir() || !mc.world.getBlockState(secondPos.up()).isAir()) return false;
        if (!isHoleWall(firstPos.down()) || !isHoleWall(secondPos.down())) return false;

        Direction sharedSide = null;
        for (Direction direction : CARDINAL) {
            if (firstPos.offset(direction).equals(secondPos)) {
                sharedSide = direction;
                break;
            }
        }

        if (sharedSide == null) return false;

        for (Direction direction : CARDINAL) {
            if (direction != sharedSide && !isHoleWall(firstPos.offset(direction))) return false;
            if (direction != sharedSide.getOpposite() && !isHoleWall(secondPos.offset(direction))) return false;
        }

        return true;
    }

    private boolean isHoleWall(BlockPos pos) {
        if (pos == null || mc.world == null) return false;

        BlockState state = mc.world.getBlockState(pos);
        return !state.isAir() && !state.isReplaceable();
    }

    private void resetState() {
        target = null;
        targetId = null;
        activeBase = null;
        activeType = null;
        lockedFaceBase = null;
        lockedHeadMineBase = null;
        instaMinePos = null;
        clearDebug();
    }

    @Override
    public String getInfoString() {
        if (target == null) return null;
        if (activeBase == null) return target.getName().getString();
        return target.getName().getString() + ((activeType == PlanType.FACE || activeType == PlanType.FACE_BLOCKER) ? " F" : " T");
    }

    private record CyclePlan(BlockPos pos, PlanType type, double score) {
    }

    public enum SwapMode {
        Normal,
        Silent
    }

    public enum MineMode {
        Vanilla,
        Insta
    }

    private enum SpaceState {
        OPEN,
        OBSIDIAN_BLOCKER,
        ITEM_BLOCKER,
        OTHER_BLOCKER,
        INVALID
    }

    private enum PlanType {
        HEAD,
        HEAD_CLEAR,
        FACE,
        FACE_BLOCKER,
        HEAD_BLOCKER
    }

    private void debug(String message) {
        if (!debugClipboard.get() || mc == null || mc.keyboard == null) return;

        debugCounter++;
        debugLines.addLast(String.format("%03d %s | target=%s active=%s/%s insta=%s faceLock=%s headLock=%s",
            debugCounter,
            message,
            target == null ? "-" : target.getName().getString(),
            activeType == null ? "-" : activeType.name(),
            formatPos(activeBase),
            formatPos(instaMinePos),
            formatPos(lockedFaceBase),
            formatPos(lockedHeadMineBase)
        ));

        while (debugLines.size() > DEBUG_LOG_LIMIT) debugLines.removeFirst();
        mc.keyboard.setClipboard(String.join(System.lineSeparator(), debugLines));
    }

    private void clearDebug() {
        debugLines.clear();
        debugCounter = 0;
    }

    private String planSummary(CyclePlan plan) {
        if (plan == null) return "null";
        return plan.type().name() + "@" + formatPos(plan.pos());
    }

    private String formatPos(BlockPos pos) {
        if (pos == null) return "-";
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }
}
