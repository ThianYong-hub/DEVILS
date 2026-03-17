package com.example.addon.modules.autocev;

import com.example.addon.modules.AutoCev;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.utils.entity.DamageUtils;
import meteordevelopment.meteorclient.utils.entity.SortPriority;
import meteordevelopment.meteorclient.utils.entity.TargetUtils;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;

public final class AutoCevPlanner {
    private static final Direction[] CARDINAL = {Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST};
    private static final int FACE_SEARCH_RADIUS = 2;
    private static final double FACE_WIDE_SEARCH_DISTANCE = 5.0;

    private final AutoCev module;

    public AutoCevPlanner(AutoCev module) {
        this.module = module;
    }

    public PlayerEntity selectTarget() {
        return (PlayerEntity) TargetUtils.get(entity -> {
            if (!(entity instanceof PlayerEntity player)) return false;
            if (player == module.client().player) return false;
            if (player.isDead() || player.getHealth() <= 0) return false;
            if (Friends.get().isFriend(player)) return false;
            if (isSameHole(module.client().player, player)) return false;
            if (isPlayerInBlocks(player)) return false;
            return module.client().player != null && module.client().player.distanceTo(player) <= module.getTargetRange();
        }, SortPriority.LowestDistance);
    }

    public AutoCev.CyclePlan choosePlan(PlayerEntity player) {
        AutoCev.CyclePlan head = chooseHeadPlan(player);
        AutoCev.CyclePlan active = getActivePlan(player);
        AutoCev.CyclePlan lockedHead = getLockedHeadPlan(player);
        AutoCev.CyclePlan face = chooseFacePlan(player, shouldUseWideFaceSearch(player, head));
        AutoCev.CyclePlan lockedFace = getLockedFacePlan(player);

        if (lockedHead != null) return lockedHead;
        if (head != null && head.type() == AutoCev.PlanType.HEAD_CLEAR) return head;
        if (head != null && head.type() == AutoCev.PlanType.HEAD_BLOCKER) {
            if (lockedFace != null) return lockedFace;
            if (face != null) return preferActive(face, active);
            if (module.getLockedFaceBase() != null) module.debug("clear face lock -> no open face, use head blocker");
            module.setLockedFaceBase(null);
            return head;
        }
        if (lockedFace != null) return lockedFace;
        if (head != null && head.type() == AutoCev.PlanType.HEAD) return preferActive(head, active);
        if (active != null && (active.type() == AutoCev.PlanType.FACE || active.type() == AutoCev.PlanType.FACE_BLOCKER)) return active;
        if (face != null) return preferActive(face, active);
        if (head != null && head.type() == AutoCev.PlanType.HEAD_BLOCKER) return head;
        return null;
    }

    public AutoCev.CyclePlan chooseHeadPlan(PlayerEntity player) {
        BlockPos pos = getTopBase(player);
        if (pos == null) return null;

        AutoCev.SpaceState state = getSpaceStateForBase(pos, player);
        if (state == AutoCev.SpaceState.OPEN) return new AutoCev.CyclePlan(pos.toImmutable(), AutoCev.PlanType.HEAD, score(pos));
        if (state == AutoCev.SpaceState.OBSIDIAN_BLOCKER) return new AutoCev.CyclePlan(pos.toImmutable(), AutoCev.PlanType.HEAD_BLOCKER, score(pos));
        if (state == AutoCev.SpaceState.ITEM_BLOCKER && module.client().world != null && module.client().world.getBlockState(pos).isOf(Blocks.OBSIDIAN)) {
            return new AutoCev.CyclePlan(pos.toImmutable(), AutoCev.PlanType.HEAD_CLEAR, score(pos));
        }
        return null;
    }

    public AutoCev.CyclePlan chooseFacePlan(PlayerEntity player, boolean wideSearch) {
        if (player == null || module.client().player == null || module.client().world == null) return null;

        BlockPos center = getFaceCenter(player);
        if (center == null) return null;

        AutoCev.CyclePlan bestOpen = null;
        AutoCev.CyclePlan bestBlocker = null;
        if (!wideSearch) {
            for (Direction direction : CARDINAL) {
                BlockPos pos = center.offset(direction);
                if (!isFreshFaceBase(pos)) continue;

                AutoCev.SpaceState state = getSpaceStateForBase(pos, player);
                if (state == AutoCev.SpaceState.OPEN) {
                    AutoCev.CyclePlan plan = new AutoCev.CyclePlan(pos.toImmutable(), AutoCev.PlanType.FACE, faceScore(pos, player));
                    if (bestOpen == null || plan.score() < bestOpen.score()) bestOpen = plan;
                } else if (state == AutoCev.SpaceState.OBSIDIAN_BLOCKER) {
                    AutoCev.CyclePlan plan = new AutoCev.CyclePlan(pos.toImmutable(), AutoCev.PlanType.FACE_BLOCKER, faceScore(pos, player));
                    if (bestBlocker == null || plan.score() < bestBlocker.score()) bestBlocker = plan;
                }
            }
        } else {
            for (int x = -FACE_SEARCH_RADIUS; x <= FACE_SEARCH_RADIUS; x++) {
                for (int z = -FACE_SEARCH_RADIUS; z <= FACE_SEARCH_RADIUS; z++) {
                    if (x == 0 && z == 0) continue;

                    BlockPos pos = center.add(x, 0, z);
                    if (!isFreshFaceBase(pos)) continue;

                    AutoCev.SpaceState state = getSpaceStateForBase(pos, player);
                    if (state == AutoCev.SpaceState.OPEN) {
                        AutoCev.CyclePlan plan = new AutoCev.CyclePlan(pos.toImmutable(), AutoCev.PlanType.FACE, faceScore(pos, player));
                        if (bestOpen == null || plan.score() < bestOpen.score()) bestOpen = plan;
                    } else if (state == AutoCev.SpaceState.OBSIDIAN_BLOCKER) {
                        AutoCev.CyclePlan plan = new AutoCev.CyclePlan(pos.toImmutable(), AutoCev.PlanType.FACE_BLOCKER, faceScore(pos, player));
                        if (bestBlocker == null || plan.score() < bestBlocker.score()) bestBlocker = plan;
                    }
                }
            }
        }

        return bestOpen != null ? bestOpen : bestBlocker;
    }

    public boolean shouldUseWideFaceSearch(PlayerEntity player, AutoCev.CyclePlan head) {
        if (player == null || module.client().player == null) return false;
        if (module.client().player.distanceTo(player) > FACE_WIDE_SEARCH_DISTANCE) return true;
        if (head != null && head.type() == AutoCev.PlanType.HEAD_BLOCKER) return true;
        if (module.getActiveType() == AutoCev.PlanType.FACE_BLOCKER) return true;
        if (module.getLockedFaceBase() == null) return false;

        return getSpaceStateForBase(module.getLockedFaceBase(), player) == AutoCev.SpaceState.OBSIDIAN_BLOCKER;
    }

    public AutoCev.SpaceState getSpaceStateForBase(BlockPos pos, PlayerEntity player) {
        if (pos == null || player == null || module.client().player == null || module.client().world == null) return AutoCev.SpaceState.INVALID;
        if (!isRelevantBase(pos, player)) return AutoCev.SpaceState.INVALID;
        if (!canOccupyBase(pos, player)) return AutoCev.SpaceState.INVALID;

        BlockState state = module.client().world.getBlockState(pos);
        if (state.isOf(Blocks.BEDROCK)) return AutoCev.SpaceState.INVALID;
        if (!(state.isOf(Blocks.OBSIDIAN) || state.isAir() || state.isReplaceable())) return AutoCev.SpaceState.INVALID;

        return getSpaceState(pos);
    }

    public double score(BlockPos pos) {
        if (module.client().player == null || pos == null) return Double.MAX_VALUE;
        double score = module.client().player.squaredDistanceTo(Vec3d.ofCenter(pos));
        if (module.getActiveBase() != null && module.getActiveBase().equals(pos)) score -= 1e-3;
        return score;
    }

    public boolean baseNeedsPlacement(BlockPos pos) {
        if (pos == null || module.client().world == null) return false;
        BlockState state = module.client().world.getBlockState(pos);
        return state.isAir() || state.isReplaceable();
    }

    public BlockPos getTopBase(PlayerEntity player) {
        if (player == null) return null;
        return new BlockPos(player.getBlockX(), MathHelper.floor(player.getBoundingBox().maxY) + 1, player.getBlockZ());
    }

    public boolean isTopBase(BlockPos pos, PlayerEntity player) {
        return pos != null && player != null && pos.equals(getTopBase(player));
    }

    public boolean isPlayerInBlocks(PlayerEntity player) {
        if (player == null || module.client().world == null) return false;

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
                    BlockState state = module.client().world.getBlockState(mutable);
                    if (state.isAir() || state.isReplaceable()) continue;

                    VoxelShape shape = state.getCollisionShape(module.client().world, mutable);
                    if (shape.isEmpty()) continue;

                    for (Box shapeBox : shape.getBoundingBoxes()) {
                        if (shapeBox.offset(mutable).intersects(box)) return true;
                    }
                }
            }
        }

        return false;
    }

    private AutoCev.CyclePlan getActivePlan(PlayerEntity player) {
        if (module.getActiveBase() == null || module.getActiveType() == null) return null;

        return switch (module.getActiveType()) {
            case HEAD -> {
                AutoCev.CyclePlan head = chooseHeadPlan(player);
                yield head != null && head.type() == AutoCev.PlanType.HEAD && head.pos().equals(module.getActiveBase()) ? head : null;
            }
            case HEAD_CLEAR -> {
                AutoCev.CyclePlan head = chooseHeadPlan(player);
                yield head != null && head.type() == AutoCev.PlanType.HEAD_CLEAR && head.pos().equals(module.getActiveBase()) ? head : null;
            }
            case FACE -> {
                AutoCev.SpaceState state = getSpaceStateForBase(module.getActiveBase(), player);
                yield state == AutoCev.SpaceState.OPEN ? new AutoCev.CyclePlan(module.getActiveBase().toImmutable(), AutoCev.PlanType.FACE, score(module.getActiveBase()) - 1e-3) : null;
            }
            case FACE_BLOCKER -> {
                AutoCev.SpaceState state = getSpaceStateForBase(module.getActiveBase(), player);
                yield state == AutoCev.SpaceState.OBSIDIAN_BLOCKER ? new AutoCev.CyclePlan(module.getActiveBase().toImmutable(), AutoCev.PlanType.FACE_BLOCKER, score(module.getActiveBase()) - 1e-3) : null;
            }
            case HEAD_BLOCKER -> {
                AutoCev.CyclePlan head = chooseHeadPlan(player);
                yield head != null && head.type() == AutoCev.PlanType.HEAD_BLOCKER && head.pos().equals(module.getActiveBase()) ? head : null;
            }
        };
    }

    private AutoCev.CyclePlan getLockedFacePlan(PlayerEntity player) {
        if (module.getLockedFaceBase() == null || player == null || module.client().world == null) return null;
        if (!isRelevantBase(module.getLockedFaceBase(), player)) {
            module.setLockedFaceBase(null);
            return null;
        }

        BlockState state = module.client().world.getBlockState(module.getLockedFaceBase());
        if (state.isOf(Blocks.BEDROCK) || (!state.isOf(Blocks.OBSIDIAN) && !state.isAir() && !state.isReplaceable())) {
            module.setLockedFaceBase(null);
            return null;
        }

        AutoCev.SpaceState faceState = getSpaceStateForBase(module.getLockedFaceBase(), player);
        if (faceState == AutoCev.SpaceState.OPEN) {
            return new AutoCev.CyclePlan(module.getLockedFaceBase().toImmutable(), AutoCev.PlanType.FACE, score(module.getLockedFaceBase()) - 1e-3);
        }
        if (faceState == AutoCev.SpaceState.OBSIDIAN_BLOCKER) {
            return new AutoCev.CyclePlan(module.getLockedFaceBase().toImmutable(), AutoCev.PlanType.FACE_BLOCKER, score(module.getLockedFaceBase()) - 1e-3);
        }

        module.setLockedFaceBase(null);
        return null;
    }

    private AutoCev.CyclePlan getLockedHeadPlan(PlayerEntity player) {
        if (module.getLockedHeadMineBase() == null || player == null || module.client().world == null) return null;
        if (!module.isHeadType(module.getActiveType())) return null;
        if (!isTopBase(module.getLockedHeadMineBase(), player)) return null;

        BlockState state = module.client().world.getBlockState(module.getLockedHeadMineBase());
        if (state.isOf(Blocks.BEDROCK) || (!state.isOf(Blocks.OBSIDIAN) && !state.isAir() && !state.isReplaceable())) return null;

        AutoCev.CyclePlan head = chooseHeadPlan(player);
        if (head != null && head.pos().equals(module.getLockedHeadMineBase())) {
            return new AutoCev.CyclePlan(module.getLockedHeadMineBase().toImmutable(), head.type(), score(module.getLockedHeadMineBase()) - 1e-3);
        }

        return new AutoCev.CyclePlan(module.getLockedHeadMineBase().toImmutable(), AutoCev.PlanType.HEAD, score(module.getLockedHeadMineBase()) - 1e-3);
    }

    private AutoCev.CyclePlan preferActive(AutoCev.CyclePlan candidate, AutoCev.CyclePlan active) {
        if (candidate == null) return active;
        if (active == null) return candidate;
        if (!candidate.pos().equals(active.pos())) return candidate;
        return active;
    }

    private double faceScore(BlockPos pos, PlayerEntity player) {
        if (pos == null || player == null) return Double.MAX_VALUE;
        return score(pos) - DamageUtils.crystalDamage(player, crystalCenter(pos)) * 100.0;
    }

    private boolean isFreshFaceBase(BlockPos pos) {
        if (pos == null || module.client().world == null) return false;
        if (module.getLockedFaceBase() != null && module.getLockedFaceBase().equals(pos)) return true;
        BlockState state = module.client().world.getBlockState(pos);
        return state.isAir() || state.isReplaceable();
    }

    private BlockPos getFaceCenter(PlayerEntity player) {
        if (player == null) return null;
        return new BlockPos(player.getBlockX(), MathHelper.floor(player.getEyeY()), player.getBlockZ());
    }

    private boolean isRelevantBase(BlockPos pos, PlayerEntity player) {
        return pos != null && player != null && (isTopBase(pos, player) || isFaceCandidate(pos, player));
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
        if (pos == null || player == null || module.client().player == null || module.client().world == null) return false;
        if (module.client().world.isOutOfHeightLimit(pos.getY()) || module.client().world.isOutOfHeightLimit(pos.getY() + 2)) return false;

        Box blockBox = new Box(pos);
        if (player.getBoundingBox().intersects(blockBox)) return false;
        if (module.client().player.getBoundingBox().intersects(blockBox)) return false;

        for (Entity entity : module.client().world.getOtherEntities(null, blockBox)) {
            if (entity instanceof EndCrystalEntity) continue;
            return false;
        }

        return true;
    }

    private AutoCev.SpaceState getSpaceState(BlockPos base) {
        if (base == null || module.client().world == null) return AutoCev.SpaceState.INVALID;
        if (findCrystalAt(base) != null) return AutoCev.SpaceState.OPEN;

        BlockState up1 = module.client().world.getBlockState(base.up());
        BlockState up2 = module.client().world.getBlockState(base.up(2));
        boolean obsidianBlocker = false;
        boolean itemBlocker = false;

        if (!up1.isAir()) {
            if (up1.isOf(Blocks.OBSIDIAN)) obsidianBlocker = true;
            else return AutoCev.SpaceState.OTHER_BLOCKER;
        }
        if (!up2.isAir()) {
            if (up2.isOf(Blocks.OBSIDIAN)) obsidianBlocker = true;
            else return AutoCev.SpaceState.OTHER_BLOCKER;
        }

        Box crystalBox = new Box(base.getX(), base.getY() + 1.0, base.getZ(), base.getX() + 1.0, base.getY() + 3.0, base.getZ() + 1.0);
        for (Entity entity : module.client().world.getOtherEntities(null, crystalBox)) {
            if (entity instanceof EndCrystalEntity || entity.isRemoved()) continue;
            if (entity instanceof ItemEntity) {
                itemBlocker = true;
                continue;
            }
            return AutoCev.SpaceState.OTHER_BLOCKER;
        }

        if (itemBlocker) return AutoCev.SpaceState.ITEM_BLOCKER;
        return obsidianBlocker ? AutoCev.SpaceState.OBSIDIAN_BLOCKER : AutoCev.SpaceState.OPEN;
    }

    private boolean isSameHole(PlayerEntity first, PlayerEntity second) {
        if (first == null || second == null || module.client().world == null) return false;

        BlockPos firstPos = first.getBlockPos();
        BlockPos secondPos = second.getBlockPos();
        if (firstPos.equals(secondPos)) return isSingleHole(firstPos) || isDoubleHoleCell(firstPos);
        if (firstPos.getY() != secondPos.getY()) return false;

        int dx = Math.abs(firstPos.getX() - secondPos.getX());
        int dz = Math.abs(firstPos.getZ() - secondPos.getZ());
        return dx + dz == 1 && isDoubleHole(firstPos, secondPos);
    }

    private boolean isSingleHole(BlockPos pos) {
        if (pos == null || module.client().world == null) return false;
        if (!module.client().world.getBlockState(pos).isAir()) return false;
        if (!module.client().world.getBlockState(pos.up()).isAir()) return false;

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
        if (firstPos == null || secondPos == null || module.client().world == null) return false;
        if (firstPos.getY() != secondPos.getY()) return false;
        if (!module.client().world.getBlockState(firstPos).isAir() || !module.client().world.getBlockState(firstPos.up()).isAir()) return false;
        if (!module.client().world.getBlockState(secondPos).isAir() || !module.client().world.getBlockState(secondPos.up()).isAir()) return false;
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
        if (pos == null || module.client().world == null) return false;
        BlockState state = module.client().world.getBlockState(pos);
        return !state.isAir() && !state.isReplaceable();
    }

    private EndCrystalEntity findCrystalAt(BlockPos base) {
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

    private Vec3d crystalCenter(BlockPos base) {
        return new Vec3d(base.getX() + 0.5, base.getY() + 1.0, base.getZ() + 0.5);
    }
}


