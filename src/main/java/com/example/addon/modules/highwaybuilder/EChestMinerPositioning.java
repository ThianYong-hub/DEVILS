package com.example.addon.modules.highwaybuilder;

import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.ItemEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class EChestMinerPositioning {
    private static final MinecraftClient mc = MinecraftClient.getInstance();

    private final EChestMiner miner;
    private final HighwayBuilder module;

    EChestMinerPositioning(EChestMiner miner) {
        this.miner = miner;
        this.module = miner.module;
    }

    BlockPos findAdjacentPlacePos() {
        return findAdjacentPlacePos(null);
    }

    BlockPos findAdjacentPlacePos(BlockPos avoidPos) {
        if (mc.player == null || mc.world == null) return null;

        BlockPos playerPos = mc.player.getBlockPos();
        BlockPos currentPos = module.pathfinder.currentBlockPos;
        double maxReach = module.maxReach.get();

        if (miner.actionPos != null
            && (avoidPos == null || !miner.actionPos.equals(avoidPos))
            && isValidPlacePos(miner.actionPos, maxReach)) {
            return miner.actionPos;
        }

        HWDirection hwDir = module.pathfinder != null ? module.pathfinder.startingDirection : null;
        List<BlockPos> closeCandidates = new ArrayList<>();
        Set<BlockPos> closeUnique = new HashSet<>();
        addPlacementCandidates(closeCandidates, closeUnique, playerPos, hwDir, 1);
        if (!currentPos.equals(playerPos)) {
            addPlacementCandidates(closeCandidates, closeUnique, currentPos, hwDir, 1);
        }

        for (BlockPos pos : closeCandidates) {
            if (avoidPos != null && pos.equals(avoidPos)) continue;
            if (isValidPlacePos(pos, maxReach)) return pos;
        }

        for (BlockPos base : closeCandidates) {
            BlockPos pos = base.up();
            if (avoidPos != null && pos.equals(avoidPos)) continue;
            if (isValidPlacePos(pos, maxReach)) return pos;
        }

        List<BlockPos> farCandidates = new ArrayList<>();
        Set<BlockPos> farUnique = new HashSet<>();
        addPlacementCandidates(farCandidates, farUnique, playerPos, hwDir, EChestMinerSupport.FAR_PLACEMENT_DISTANCE);
        if (!currentPos.equals(playerPos)) {
            addPlacementCandidates(farCandidates, farUnique, currentPos, hwDir, EChestMinerSupport.FAR_PLACEMENT_DISTANCE);
        }

        for (BlockPos pos : farCandidates) {
            if (avoidPos != null && pos.equals(avoidPos)) continue;
            if (isValidPlacePos(pos, maxReach)) return pos;
        }

        for (BlockPos base : farCandidates) {
            BlockPos pos = base.up();
            if (avoidPos != null && pos.equals(avoidPos)) continue;
            if (isValidPlacePos(pos, maxReach)) return pos;
        }

        return null;
    }

    boolean tryRelocateActionPos() {
        BlockPos old = miner.actionPos;
        BlockPos relocated = findAdjacentPlacePos(old);
        if (relocated == null || relocated.equals(old)) return false;

        miner.actionPos = relocated;
        miner.standPos = findStandPos(relocated);
        miner.stuckTicks = 0;
        return true;
    }

    void nudgeAwayFromActionPos() {
        if (mc.player == null || miner.actionPos == null) return;

        Vec3d player = mc.player.getPos();
        Vec3d center = Vec3d.ofCenter(miner.actionPos);
        double dx = player.x - center.x;
        double dz = player.z - center.z;
        if (dx * dx + dz * dz < 1.0e-4 && module.pathfinder != null) {
            dx = -module.pathfinder.startingDirection.directionVec.getX();
            dz = -module.pathfinder.startingDirection.directionVec.getZ();
        }
        if (dx * dx + dz * dz < 1.0e-4) {
            dx = 1.0;
            dz = 0.0;
        }

        Vec3d selfCenter = Vec3d.ofCenter(mc.player.getBlockPos());
        dx += (selfCenter.x - player.x) * 0.90;
        dz += (selfCenter.z - player.z) * 0.90;

        if (miner.standPos != null) {
            Vec3d standTarget = getSafeStandPoint(miner.standPos, miner.actionPos);
            dx += (standTarget.x - player.x) * 0.65;
            dz += (standTarget.z - player.z) * 0.65;
        }

        double len = Math.sqrt(dx * dx + dz * dz);
        dx /= len;
        dz /= len;

        double speed = Math.max(0.26, Math.min(0.46, module.moveSpeed.get() + 0.14));
        mc.player.setVelocity(dx * speed, mc.player.getVelocity().y, dz * speed);
    }

    BlockPos findStandPos(BlockPos placePos) {
        if (mc.world == null || mc.player == null || placePos == null) return null;

        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (Direction dir : Direction.Type.HORIZONTAL) {
            BlockPos candidate = placePos.offset(dir);
            if (!isValidStandPos(candidate, placePos)) continue;

            double distance = mc.player.getPos().squaredDistanceTo(Vec3d.ofCenter(candidate));
            if (distance < bestDist) {
                bestDist = distance;
                best = candidate;
            }
        }

        if (best != null) return best;
        BlockPos playerPos = mc.player.getBlockPos();
        return isValidStandPos(playerPos, placePos) ? playerPos : null;
    }

    boolean ensureActionPosition() {
        if (mc.player == null || mc.world == null) return false;

        if (miner.actionPos == null || !isUsableActionPos(miner.actionPos)) {
            reselectActionPosition(miner.actionPos);
            if (miner.actionPos == null) {
                module.pathfinder.clearMinerGoal();
                return false;
            }
        }

        if (miner.standPos == null || !isValidStandPos(miner.standPos, miner.actionPos)) {
            miner.standPos = findStandPos(miner.actionPos);
        }

        boolean selfBlocking = mc.player.getBoundingBox().intersects(new Box(miner.actionPos));
        if (selfBlocking) {
            miner.selfBlockTicks++;
            nudgeAwayFromActionPos();
            if (miner.standPos != null) {
                Vec3d standTarget = getSafeStandPoint(miner.standPos, miner.actionPos);
                if (horizontalDistanceSq(mc.player.getPos(), standTarget)
                    <= EChestMinerSupport.MANUAL_CENTER_RANGE * EChestMinerSupport.MANUAL_CENTER_RANGE) {
                    module.pathfinder.clearMinerGoal();
                    moveTowardStandPoint(standTarget);
                } else {
                    module.pathfinder.setMinerGoal(miner.standPos);
                }
            }

            if (miner.selfBlockTicks >= EChestMinerSupport.SELF_BLOCK_RELOCATE_TICKS) {
                if (!tryRelocateActionPos()) reselectActionPosition(miner.actionPos);
                miner.selfBlockTicks = 0;
            }
        } else {
            miner.selfBlockTicks = 0;
        }

        if (canOperateFromCurrentPos(miner.actionPos)) {
            module.pathfinder.clearMinerGoal();
            miner.ensureNoMoveTicks = 0;
            miner.lastEnsurePos = mc.player.getPos();
            return true;
        }

        if (miner.standPos == null) {
            miner.ensureNoMoveTicks++;
            if (miner.ensureNoMoveTicks > EChestMinerSupport.POSITION_STUCK_TICKS) {
                if (!tryRelocateActionPos()) reselectActionPosition(miner.actionPos);
                miner.ensureNoMoveTicks = 0;
            }
            return false;
        }

        Vec3d standTarget = getSafeStandPoint(miner.standPos, miner.actionPos);
        double distSq = horizontalDistanceSq(mc.player.getPos(), standTarget);

        if (distSq <= EChestMinerSupport.MANUAL_CENTER_RANGE * EChestMinerSupport.MANUAL_CENTER_RANGE) {
            module.pathfinder.clearMinerGoal();
            moveTowardStandPoint(standTarget);
        } else {
            module.pathfinder.setMinerGoal(miner.standPos);
        }

        Vec3d currentPos = mc.player.getPos();
        if (miner.lastEnsurePos != null && currentPos.squaredDistanceTo(miner.lastEnsurePos) <= EChestMinerSupport.MOVE_EPSILON_SQ) {
            miner.ensureNoMoveTicks++;
        } else {
            miner.ensureNoMoveTicks = 0;
        }
        miner.lastEnsurePos = currentPos;

        if (miner.ensureNoMoveTicks > EChestMinerSupport.POSITION_STUCK_TICKS) {
            if (!tryRelocateActionPos()) reselectActionPosition(miner.actionPos);
            miner.ensureNoMoveTicks = 0;
        }

        return false;
    }

    boolean ensureMiningAccess() {
        if (mc.player == null || mc.world == null) return false;
        if (!ensureActionPosition()) {
            miner.miningAccessStuckTicks++;
            if (miner.miningAccessStuckTicks >= EChestMinerSupport.MINING_POSITION_RECOVER_TICKS) {
                recoverMiningPosition();
                miner.miningAccessStuckTicks = 0;
            }
            return false;
        }

        miner.miningAccessStuckTicks = 0;
        return true;
    }

    Direction findSupportSide(BlockPos pos) {
        if (mc.world == null) return null;

        BlockPos below = pos.down();
        if (!mc.world.getBlockState(below).isAir() && !mc.world.getBlockState(below).isReplaceable()) {
            return Direction.DOWN;
        }
        for (Direction dir : Direction.values()) {
            if (dir == Direction.DOWN) continue;
            BlockPos neighbor = pos.offset(dir);
            if (!mc.world.getBlockState(neighbor).isAir()
                && !mc.world.getBlockState(neighbor).isReplaceable()) {
                return dir;
            }
        }
        return null;
    }

    private void addPlacementCandidates(List<BlockPos> candidates, Set<BlockPos> unique, BlockPos anchor, HWDirection hwDir, int distance) {
        if (anchor == null || distance < 1) return;

        if (hwDir != null) {
            int fx = Integer.signum(hwDir.directionVec.getX());
            int fz = Integer.signum(hwDir.directionVec.getZ());
            int bx = -fx * distance;
            int bz = -fz * distance;

            HWDirection lateral = hwDir.lateralDirection();
            int sx = Integer.signum(lateral.directionVec.getX());
            int sz = Integer.signum(lateral.directionVec.getZ());
            int lx = sx * distance;
            int lz = sz * distance;

            if (bx != 0 || bz != 0) addPlacementCandidate(candidates, unique, anchor.add(bx, 0, bz));
            if (lx != 0 || lz != 0) {
                addPlacementCandidate(candidates, unique, anchor.add(lx, 0, lz));
                addPlacementCandidate(candidates, unique, anchor.add(-lx, 0, -lz));
                addPlacementCandidate(candidates, unique, anchor.add(bx + lx, 0, bz + lz));
                addPlacementCandidate(candidates, unique, anchor.add(bx - lx, 0, bz - lz));
            }
        }

        for (Direction dir : Direction.Type.HORIZONTAL) {
            addPlacementCandidate(candidates, unique, anchor.offset(dir, distance));
        }
        addPlacementCandidate(candidates, unique, anchor.add(distance, 0, distance));
        addPlacementCandidate(candidates, unique, anchor.add(distance, 0, -distance));
        addPlacementCandidate(candidates, unique, anchor.add(-distance, 0, distance));
        addPlacementCandidate(candidates, unique, anchor.add(-distance, 0, -distance));
    }

    private void addPlacementCandidate(List<BlockPos> list, Set<BlockPos> unique, BlockPos pos) {
        if (pos == null) return;
        if (unique.add(pos)) list.add(pos);
    }

    private boolean isValidPlacePos(BlockPos pos, double maxReach) {
        if (mc.world == null || mc.player == null) return false;
        if (pos.equals(mc.player.getBlockPos())) return false;
        if (!mc.world.getBlockState(pos).isAir() && !mc.world.getBlockState(pos).isReplaceable()) return false;

        BlockPos below = pos.down();
        if (mc.world.getBlockState(below).isAir()
            || mc.world.getBlockState(below).isReplaceable()
            || !mc.world.getFluidState(below).isEmpty()) {
            return false;
        }

        if (!mc.world.getOtherEntities(
            null,
            new Box(pos),
            entity -> entity != mc.player && !(entity instanceof ItemEntity)
        ).isEmpty()) {
            return false;
        }

        if (findSupportSide(pos) == null) return false;
        double dist = mc.player.getEyePos().distanceTo(Vec3d.ofCenter(pos));
        if (dist > maxReach + 1.0) return false;
        return canOperateFromCurrentPos(pos) || hasAnyValidStandPos(pos);
    }

    private boolean hasAnyValidStandPos(BlockPos placePos) {
        if (placePos == null || mc.player == null) return false;
        for (Direction dir : Direction.Type.HORIZONTAL) {
            if (isValidStandPos(placePos.offset(dir), placePos)) return true;
        }
        return isValidStandPos(mc.player.getBlockPos(), placePos);
    }

    private boolean isValidStandPos(BlockPos standPos, BlockPos placePos) {
        if (mc.world == null || mc.player == null || standPos == null || placePos == null) return false;
        if (standPos.equals(placePos)) return false;

        if (!mc.world.getBlockState(standPos).isAir() && !mc.world.getBlockState(standPos).isReplaceable()) return false;
        if (!mc.world.getBlockState(standPos.up()).isAir() && !mc.world.getBlockState(standPos.up()).isReplaceable()) return false;

        BlockPos below = standPos.down();
        if (mc.world.getBlockState(below).isAir()
            || mc.world.getBlockState(below).isReplaceable()
            || !mc.world.getFluidState(below).isEmpty()) {
            return false;
        }

        Vec3d center = Vec3d.ofCenter(standPos);
        double eyeOffset = mc.player.getEyeY() - mc.player.getY();
        Vec3d standEye = new Vec3d(center.x, standPos.getY() + eyeOffset, center.z);
        if (standEye.distanceTo(Vec3d.ofCenter(placePos)) > module.maxReach.get() + 0.25) return false;

        double halfWidth = mc.player.getWidth() / 2.0;
        Box playerBox = new Box(
            center.x - halfWidth,
            standPos.getY(),
            center.z - halfWidth,
            center.x + halfWidth,
            standPos.getY() + mc.player.getHeight(),
            center.z + halfWidth
        );
        return !playerBox.intersects(new Box(placePos));
    }

    private boolean canOperateFromCurrentPos(BlockPos placePos) {
        if (mc.player == null || placePos == null) return false;
        if (mc.player.getBoundingBox().intersects(new Box(placePos))) return false;
        if (mc.player.getEyePos().distanceTo(Vec3d.ofCenter(placePos)) > module.maxReach.get() + 0.25) return false;
        return findSupportSide(placePos) != null;
    }

    private void recoverMiningPosition() {
        if (mc.player == null || mc.world == null || miner.actionPos == null) return;

        if (mc.world.getBlockState(miner.actionPos).getBlock() == Blocks.ENDER_CHEST) {
            miner.standPos = findStandPos(miner.actionPos);
            if (miner.standPos != null) {
                Vec3d standTarget = getSafeStandPoint(miner.standPos, miner.actionPos);
                if (horizontalDistanceSq(mc.player.getPos(), standTarget)
                    <= EChestMinerSupport.MANUAL_CENTER_RANGE * EChestMinerSupport.MANUAL_CENTER_RANGE) {
                    module.pathfinder.clearMinerGoal();
                    moveTowardStandPoint(standTarget);
                } else {
                    module.pathfinder.setMinerGoal(miner.standPos);
                }
            } else {
                nudgeAwayFromActionPos();
            }
            miner.tickDelay = 1;
            return;
        }

        if (!tryRelocateActionPos()) {
            reselectActionPosition(miner.actionPos);
            if (miner.actionPos == null) {
                miner.collection.goToCollecting();
                return;
            }
        }

        miner.tickDelay = 1;
    }

    private boolean isUsableActionPos(BlockPos pos) {
        if (mc.world == null || pos == null) return false;
        Block current = mc.world.getBlockState(pos).getBlock();
        if (current != Blocks.ENDER_CHEST
            && !mc.world.getBlockState(pos).isAir()
            && !mc.world.getBlockState(pos).isReplaceable()) {
            return false;
        }
        return findSupportSide(pos) != null;
    }

    private void reselectActionPosition(BlockPos avoidPos) {
        miner.actionPos = findAdjacentPlacePos(avoidPos);
        if (miner.actionPos == null && avoidPos != null) miner.actionPos = findAdjacentPlacePos();
        miner.standPos = miner.actionPos != null ? findStandPos(miner.actionPos) : null;
        miner.lastEnsurePos = null;
    }

    private Vec3d getSafeStandPoint(BlockPos standBlock, BlockPos placePos) {
        if (mc.player == null || standBlock == null) return Vec3d.ZERO;

        Vec3d center = Vec3d.ofCenter(standBlock);
        double halfWidth = mc.player.getWidth() / 2.0;
        double margin = halfWidth + EChestMinerSupport.STAND_EDGE_PADDING;
        double minX = standBlock.getX() + margin;
        double maxX = standBlock.getX() + 1.0 - margin;
        double minZ = standBlock.getZ() + margin;
        double maxZ = standBlock.getZ() + 1.0 - margin;

        if (minX > maxX || minZ > maxZ) return center;

        double tx = center.x;
        double tz = center.z;
        if (placePos != null) {
            int dx = placePos.getX() - standBlock.getX();
            int dz = placePos.getZ() - standBlock.getZ();
            tx -= Math.signum(dx) * EChestMinerSupport.STAND_BIAS_AWAY;
            tz -= Math.signum(dz) * EChestMinerSupport.STAND_BIAS_AWAY;

            double clear = halfWidth + EChestMinerSupport.STAND_ACTION_CLEARANCE;
            if (dx < 0) tx = Math.max(tx, standBlock.getX() + clear);
            else if (dx > 0) tx = Math.min(tx, standBlock.getX() + 1.0 - clear);
            if (dz < 0) tz = Math.max(tz, standBlock.getZ() + clear);
            else if (dz > 0) tz = Math.min(tz, standBlock.getZ() + 1.0 - clear);
        }

        tx = clamp(tx, minX, maxX);
        tz = clamp(tz, minZ, maxZ);
        return new Vec3d(tx, center.y, tz);
    }

    private void moveTowardStandPoint(Vec3d target) {
        if (mc.player == null || target == null) return;

        double dx = target.x - mc.player.getX();
        double dz = target.z - mc.player.getZ();
        if (dx * dx + dz * dz <= EChestMinerSupport.ACTION_TOLERANCE * EChestMinerSupport.ACTION_TOLERANCE) {
            mc.player.setVelocity(0.0, mc.player.getVelocity().y, 0.0);
            return;
        }

        double speed = Math.max(0.18, Math.min(0.32, module.moveSpeed.get() + 0.08));
        mc.player.setVelocity(
            clamp(dx, -speed, speed),
            mc.player.getVelocity().y,
            clamp(dz, -speed, speed)
        );
    }

    private double horizontalDistanceSq(Vec3d a, Vec3d b) {
        double dx = a.x - b.x;
        double dz = a.z - b.z;
        return dx * dx + dz * dz;
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}



