package com.example.addon.modules.autocev;

import com.example.addon.mixin.ClientPlayerInteractionManagerInvoker;
import com.example.addon.modules.AutoCev;
import meteordevelopment.meteorclient.utils.entity.DamageUtils;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.HandSwingC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractEntityC2SPacket;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

public final class AutoCevActionExecutor {
    private static final int ROTATE_PRIORITY = 50;

    private final AutoCev module;
    private final AutoCevPlanner planner;

    public AutoCevActionExecutor(AutoCev module, AutoCevPlanner planner) {
        this.module = module;
        this.planner = planner;
    }

    public void executePlan(AutoCev.CyclePlan plan) {
        if (plan == null || module.client().player == null || module.client().world == null) return;

        switch (plan.type()) {
            case HEAD, FACE -> executeCyclePlan(plan);
            case FACE_BLOCKER -> executeFaceBlockerPlan(plan);
            case HEAD_CLEAR -> executeHeadClearPlan(plan);
            case HEAD_BLOCKER -> executeHeadBlockerPlan(plan);
        }
    }

    public EndCrystalEntity findCrystalAt(BlockPos base) {
        if (base == null || module.client().world == null) return null;

        Vec3d center = new Vec3d(base.getX() + 0.5, base.getY() + 1.5, base.getZ() + 0.5);
        Box crystalBox = new Box(base.getX() + 1e-3, base.getY() + 1.0, base.getZ() + 1e-3, base.getX() + 1.0 - 1e-3, base.getY() + 3.0, base.getZ() + 1.0 - 1e-3);
        EndCrystalEntity best = null;
        double bestDistance = Double.MAX_VALUE;

        for (Entity entity : module.client().world.getOtherEntities(null, crystalBox)) {
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

    public Vec3d crystalCenter(BlockPos base) {
        return new Vec3d(base.getX() + 0.5, base.getY() + 1.0, base.getZ() + 0.5);
    }

    private void executeFaceBlockerPlan(AutoCev.CyclePlan plan) {
        if (plan == null || module.client().world == null || module.getTarget() == null) return;

        if (planner.baseNeedsPlacement(plan.pos())) {
            if (placeObsidian(plan.pos())) {
                tryContinueCycleSameTick(new AutoCev.CyclePlan(plan.pos(), AutoCev.PlanType.FACE_BLOCKER, planner.score(plan.pos())));
            }
            return;
        }

        if (mineCrystalBlocker(plan.pos())) return;
        if (planner.getSpaceStateForBase(plan.pos(), module.getTarget()) == AutoCev.SpaceState.OPEN) {
            executeCyclePlan(new AutoCev.CyclePlan(plan.pos(), AutoCev.PlanType.FACE, planner.score(plan.pos())));
        }
    }

    private void executeHeadClearPlan(AutoCev.CyclePlan plan) {
        if (plan == null || module.client().world == null) return;
        if (!module.client().world.getBlockState(plan.pos()).isOf(Blocks.OBSIDIAN)) return;

        if (module.getMineMode() == AutoCev.MineMode.Insta && plan.pos().equals(module.getLockedHeadMineBase())) {
            module.debug("head clear -> keep external mine " + module.formatPos(plan.pos()));
            return;
        }

        module.debug("head clear -> mine base " + module.formatPos(plan.pos()));
        mineBase(plan.pos());
    }

    private void executeCyclePlan(AutoCev.CyclePlan plan) {
        if (plan == null || module.client().player == null || module.client().world == null) return;

        BlockState state = module.client().world.getBlockState(plan.pos());
        EndCrystalEntity crystal = findCrystalAt(plan.pos());

        if (state.isOf(Blocks.OBSIDIAN)) {
            if (crystal != null) {
                if (antiSuicide(com.example.addon.util.EntityPositionCompat.pos(crystal))) mineBase(plan.pos());
                return;
            }

            if (!antiSuicide(crystalCenter(plan.pos()))) return;
            if (placeCrystal(plan.pos())) mineBase(plan.pos());
            return;
        }

        if (!state.isAir() && !state.isReplaceable()) return;
        if (crystal != null) {
            if (antiSuicide(com.example.addon.util.EntityPositionCompat.pos(crystal))) attackCrystal(crystal);
            return;
        }

        if (planner.getSpaceStateForBase(plan.pos(), module.getTarget()) != AutoCev.SpaceState.OPEN) return;
        if (placeObsidian(plan.pos())) tryContinueCycleSameTick(plan);
    }

    private void executeHeadBlockerPlan(AutoCev.CyclePlan plan) {
        if (plan == null || module.client().world == null || module.getTarget() == null) return;

        if (mineCrystalBlocker(plan.pos())) return;
        if (planner.baseNeedsPlacement(plan.pos())) {
            module.debug("head blocker setup -> place obsidian " + module.formatPos(plan.pos()));
            if (placeObsidian(plan.pos())) {
                tryContinueCycleSameTick(new AutoCev.CyclePlan(plan.pos(), AutoCev.PlanType.HEAD, planner.score(plan.pos())));
            }
        }
    }

    private boolean placeObsidian(BlockPos pos) {
        if (pos == null || module.client().player == null || module.client().world == null) return false;

        FindItemResult obsidian = InvUtils.findInHotbar(Items.OBSIDIAN);
        if (!obsidian.found()) {
            module.disableWithError("No obsidian in hotbar.");
            return false;
        }

        module.debug("place obsidian attempt -> " + module.formatPos(pos));
        boolean placed = BlockUtils.place(
            pos,
            obsidian,
            module.shouldRotate(),
            module.shouldRotate() ? ROTATE_PRIORITY : 0,
            module.shouldSwingHand(),
            true,
            module.getSwapMode() == AutoCev.SwapMode.Silent
        );
        module.debug("place obsidian result -> " + module.formatPos(pos) + " success=" + placed);
        return placed;
    }

    private void tryContinueCycleSameTick(AutoCev.CyclePlan plan) {
        if (plan == null || module.client().world == null || module.getTarget() == null) return;
        if (!module.client().world.getBlockState(plan.pos()).isOf(Blocks.OBSIDIAN)) return;

        if (plan.type() == AutoCev.PlanType.HEAD || plan.type() == AutoCev.PlanType.FACE) executeCyclePlan(plan);
        else if (plan.type() == AutoCev.PlanType.FACE_BLOCKER) executeFaceBlockerPlan(plan);
    }

    private boolean placeCrystal(BlockPos base) {
        if (base == null || module.client().player == null || module.client().world == null) return false;

        FindItemResult crystal = InvUtils.findInHotbar(Items.END_CRYSTAL);
        if (!crystal.found()) {
            module.disableWithError("No end crystals in hotbar.");
            return false;
        }

        int previousSlot = module.client().player.getInventory().getSelectedSlot();
        boolean silent = module.getSwapMode() == AutoCev.SwapMode.Silent;
        boolean[] success = {false};

        Runnable place = () -> {
            if (module.client().player == null || module.client().world == null) return;

            Hand hand = crystal.getHand();
            boolean switched = false;
            if (hand == null) {
                if (!InvUtils.swap(crystal.slot(), !silent)) return;
                hand = Hand.MAIN_HAND;
                switched = true;
            }

            if (!module.client().player.getStackInHand(hand).isOf(Items.END_CRYSTAL)) {
                if (switched) restoreSlot(previousSlot, silent);
                return;
            }

            BlockHitResult hit = new BlockHitResult(crystalCenter(base), Direction.UP, base, false);
            ActionResult result = interactBlock(hand, hit);
            success[0] = result.isAccepted();
            if (success[0]) {
                module.debug("place crystal -> " + module.formatPos(base));
                swing(hand);
            }

            if (switched) restoreSlot(previousSlot, silent);
        };

        if (module.shouldRotate()) {
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
        if (module.client().player == null || module.client().interactionManager == null) return ActionResult.PASS;

        boolean wasSneaking = module.client().player.isSneaking();
        module.client().player.setSneaking(false);
        ActionResult result = module.client().interactionManager.interactBlock(module.client().player, hand, hit);
        module.client().player.setSneaking(wasSneaking);
        return result;
    }

    private void mineBase(BlockPos pos) {
        if (module.getMineMode() == AutoCev.MineMode.Insta) {
            if (module.isHeadType(module.getActiveType()) && pos != null && pos.equals(module.getLockedHeadMineBase())) {
                module.debug("skip mineBase -> head lock owns " + module.formatPos(pos));
                return;
            }
            ensureInstaMine(pos);
            return;
        }

        module.debug("vanilla mine -> " + module.formatPos(pos));
        mineBlock(pos);
    }

    private boolean mineCrystalBlocker(BlockPos base) {
        if (base == null || module.client().world == null) return false;

        if (module.getTarget() != null && planner.isTopBase(base, module.getTarget())) {
            AutoCev.CyclePlan head = planner.chooseHeadPlan(module.getTarget());
            AutoCev.CyclePlan face = planner.chooseFacePlan(module.getTarget(), planner.shouldUseWideFaceSearch(module.getTarget(), head));
            if (face != null) {
                module.debug("skip head blocker mine -> face exists");
                return false;
            }
        }

        if (mineBlockingBlock(base.up())) return true;
        return mineBlockingBlock(base.up(2));
    }

    private boolean mineBlockingBlock(BlockPos pos) {
        if (pos == null || module.client().world == null) return false;
        if (!module.client().world.getBlockState(pos).isOf(Blocks.OBSIDIAN)) return false;

        if (module.getMineMode() == AutoCev.MineMode.Insta) return ensureInstaMine(pos);
        module.debug("vanilla mine blocker -> " + module.formatPos(pos));
        mineBlock(pos);
        return true;
    }

    private void mineBlock(BlockPos pos) {
        Runnable mine = () -> {
            if (module.client().interactionManager == null) return;
            module.debug("attack block -> " + module.formatPos(pos) + " dir=UP");
            module.client().interactionManager.attackBlock(pos, Direction.UP);
            swing(Hand.MAIN_HAND);
        };

        if (module.shouldRotate()) {
            Vec3d center = Vec3d.ofCenter(pos);
            Rotations.rotate(Rotations.getYaw(center), Rotations.getPitch(center), ROTATE_PRIORITY, mine);
        } else {
            mine.run();
        }
    }

    private boolean ensureInstaMine(BlockPos pos) {
        if (pos == null || module.client().interactionManager == null) return false;

        BlockPos immutable = pos.toImmutable();
        if (module.isHeadType(module.getActiveType()) && immutable.equals(module.getLockedHeadMineBase())) {
            module.debug("skip insta mine -> head locked " + module.formatPos(immutable));
            return false;
        }
        if (immutable.equals(module.getInstaMinePos())) {
            module.debug("skip insta mine -> insta pos already set " + module.formatPos(immutable));
            return false;
        }
        if (isBreakingBlock(immutable)) {
            module.debug("skip insta mine -> client already breaking " + module.formatPos(immutable));
            return false;
        }

        Runnable mine = () -> {
            if (module.client().interactionManager == null) return;
            Direction direction = BlockUtils.getDirection(immutable);
            module.debug("start insta mine -> " + module.formatPos(immutable) + " type=" + module.getActiveType());
            module.debug("attack block -> " + module.formatPos(immutable) + " dir=" + direction);
            module.client().interactionManager.attackBlock(immutable, direction);
        };

        if (module.shouldRotate()) {
            Vec3d center = Vec3d.ofCenter(immutable);
            Rotations.rotate(Rotations.getYaw(center), Rotations.getPitch(center), ROTATE_PRIORITY, mine);
        } else {
            mine.run();
        }

        if (module.isHeadType(module.getActiveType())) module.setLockedHeadMineBase(immutable);
        module.setInstaMinePos(immutable);
        return true;
    }

    private void attackCrystal(EndCrystalEntity crystal) {
        if (crystal == null || module.client().player == null || module.client().world == null) return;

        Runnable attack = () -> {
            if (module.client().player == null) return;
            module.debug("attack crystal -> " + module.formatPos(BlockPos.ofFloored(com.example.addon.util.EntityPositionCompat.pos(crystal))));
            if (module.client().getNetworkHandler() != null) {
                module.client().getNetworkHandler().sendPacket(PlayerInteractEntityC2SPacket.attack(crystal, module.client().player.isSneaking()));
            } else if (module.client().interactionManager != null) {
                module.client().interactionManager.attackEntity(module.client().player, crystal);
            }
            swing(Hand.MAIN_HAND);
        };

        if (module.shouldRotate()) {
            Rotations.rotate(Rotations.getYaw(com.example.addon.util.EntityPositionCompat.pos(crystal)), Rotations.getPitch(com.example.addon.util.EntityPositionCompat.pos(crystal)), ROTATE_PRIORITY, attack);
        } else {
            attack.run();
        }
    }

    private boolean antiSuicide(Vec3d crystalPos) {
        if (crystalPos == null || module.client().player == null) return false;
        double hp = module.client().player.getHealth() + module.client().player.getAbsorptionAmount();
        float selfDamage = DamageUtils.crystalDamage(module.client().player, crystalPos);
        return hp - selfDamage > module.getSafeHealth();
    }

    private void swing(Hand hand) {
        if (module.client().player == null) return;
        module.debug("swing -> " + hand.name() + " client=" + module.shouldSwingHand());
        if (module.shouldSwingHand()) {
            module.client().player.swingHand(hand);
        } else if (module.client().getNetworkHandler() != null) {
            module.client().getNetworkHandler().sendPacket(new HandSwingC2SPacket(hand));
        }
    }

    private boolean isBreakingBlock(BlockPos pos) {
        if (pos == null || module.client().interactionManager == null) return false;
        return ((ClientPlayerInteractionManagerInvoker) module.client().interactionManager).devilsAddon$isCurrentlyBreaking(pos);
    }
}


