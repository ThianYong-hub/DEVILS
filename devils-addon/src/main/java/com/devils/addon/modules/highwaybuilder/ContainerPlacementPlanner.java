package com.devils.addon.modules.highwaybuilder;

import net.minecraft.block.BlockState;
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

final class ContainerPlacementPlanner {
    private static final MinecraftClient mc = MinecraftClient.getInstance();
    private static final double RESTOCK_EDGE_PADDING = 0.06;
    private static final double RESTOCK_BIAS_AWAY_FROM_CONTAINER = 0.08;
    private static final double RESTOCK_CONTAINER_CLEARANCE = 0.03;
    private static final int CONTAINER_FAR_DISTANCE = 2;

    private final HighwayBuilder module;

    private BlockPos lastContainerPos;
    private BlockPos restockStandBlock;
    private Vec3d restockStandPos;

    ContainerPlacementPlanner(HighwayBuilder module) {
        this.module = module;
    }

    BlockPos getRemotePos() {
        return getRemotePos(null);
    }

    BlockPos getRemotePos(BlockPos avoidPos) {
        if (mc.player == null || mc.world == null) return null;

        BlockPos playerPos = mc.player.getBlockPos();
        BlockPos currentPos = module.pathfinder != null ? module.pathfinder.currentBlockPos : playerPos;
        double maxReach = module.maxReach.get();
        HWDirection hwDir = module.pathfinder != null ? module.pathfinder.startingDirection : null;

        if (lastContainerPos != null
            && (avoidPos == null || !lastContainerPos.equals(avoidPos))
            && isPreferredContainerPos(lastContainerPos, playerPos, hwDir)
            && isValidContainerPos(lastContainerPos, maxReach, false)) {
            return lastContainerPos;
        }

        List<BlockPos> candidates = new ArrayList<>();
        Set<BlockPos> unique = new HashSet<>();

        addContainerCandidates(candidates, unique, playerPos, hwDir, 1);
        if (!currentPos.equals(playerPos)) {
            addContainerCandidates(candidates, unique, currentPos, hwDir, 1);
        }

        for (BlockPos pos : candidates) {
            if (avoidPos != null && pos.equals(avoidPos)) continue;
            if (!isPreferredContainerPos(pos, playerPos, hwDir)) continue;
            if (isValidContainerPos(pos, maxReach, false)) return pos;
        }

        for (BlockPos base : candidates) {
            BlockPos pos = base.up();
            if (avoidPos != null && pos.equals(avoidPos)) continue;
            if (!isPreferredContainerPos(pos, playerPos, hwDir)) continue;
            if (isValidContainerPos(pos, maxReach, false)) return pos;
        }

        for (BlockPos pos : candidates) {
            if (avoidPos != null && pos.equals(avoidPos)) continue;
            if (!isPreferredContainerPos(pos, playerPos, hwDir)) continue;
            if (isValidContainerPos(pos, maxReach, true)) return pos;
        }

        for (BlockPos base : candidates) {
            BlockPos pos = base.up();
            if (avoidPos != null && pos.equals(avoidPos)) continue;
            if (!isPreferredContainerPos(pos, playerPos, hwDir)) continue;
            if (isValidContainerPos(pos, maxReach, true)) return pos;
        }

        for (BlockPos pos : candidates) {
            if (avoidPos != null && pos.equals(avoidPos)) continue;
            if (isValidContainerPos(pos, maxReach, false)) return pos;
        }

        for (BlockPos base : candidates) {
            BlockPos pos = base.up();
            if (avoidPos != null && pos.equals(avoidPos)) continue;
            if (isValidContainerPos(pos, maxReach, false)) return pos;
        }

        for (BlockPos pos : candidates) {
            if (avoidPos != null && pos.equals(avoidPos)) continue;
            if (isValidContainerPos(pos, maxReach, true)) return pos;
        }

        for (BlockPos base : candidates) {
            BlockPos pos = base.up();
            if (avoidPos != null && pos.equals(avoidPos)) continue;
            if (isValidContainerPos(pos, maxReach, true)) return pos;
        }

        List<BlockPos> farCandidates = new ArrayList<>();
        Set<BlockPos> farUnique = new HashSet<>();
        addContainerCandidates(farCandidates, farUnique, playerPos, hwDir, CONTAINER_FAR_DISTANCE);
        if (!currentPos.equals(playerPos)) {
            addContainerCandidates(farCandidates, farUnique, currentPos, hwDir, CONTAINER_FAR_DISTANCE);
        }

        for (BlockPos pos : farCandidates) {
            if (avoidPos != null && pos.equals(avoidPos)) continue;
            if (!isPreferredContainerPos(pos, playerPos, hwDir, CONTAINER_FAR_DISTANCE)) continue;
            if (isValidContainerPos(pos, maxReach, false)) return pos;
        }

        for (BlockPos base : farCandidates) {
            BlockPos pos = base.up();
            if (avoidPos != null && pos.equals(avoidPos)) continue;
            if (!isPreferredContainerPos(pos, playerPos, hwDir, CONTAINER_FAR_DISTANCE)) continue;
            if (isValidContainerPos(pos, maxReach, false)) return pos;
        }

        for (BlockPos pos : farCandidates) {
            if (avoidPos != null && pos.equals(avoidPos)) continue;
            if (isValidContainerPos(pos, maxReach, false)) return pos;
        }

        for (BlockPos base : farCandidates) {
            BlockPos pos = base.up();
            if (avoidPos != null && pos.equals(avoidPos)) continue;
            if (isValidContainerPos(pos, maxReach, false)) return pos;
        }

        for (BlockPos pos : farCandidates) {
            if (avoidPos != null && pos.equals(avoidPos)) continue;
            if (isValidContainerPos(pos, maxReach, true)) return pos;
        }

        for (BlockPos base : farCandidates) {
            BlockPos pos = base.up();
            if (avoidPos != null && pos.equals(avoidPos)) continue;
            if (isValidContainerPos(pos, maxReach, true)) return pos;
        }

        if (lastContainerPos != null
            && (avoidPos == null || !lastContainerPos.equals(avoidPos))
            && isValidContainerPos(lastContainerPos, maxReach, false)) {
            return lastContainerPos;
        }

        if (lastContainerPos != null
            && (avoidPos == null || !lastContainerPos.equals(avoidPos))
            && isValidContainerPos(lastContainerPos, maxReach, true)) {
            return lastContainerPos;
        }

        return null;
    }

    BlockPos selectRestockStandBlock(BlockPos containerPos) {
        if (mc.world == null || mc.player == null) return null;

        BlockPos current = module.pathfinder != null ? module.pathfinder.currentBlockPos : mc.player.getBlockPos();
        BlockPos playerPos = mc.player.getBlockPos();
        Set<BlockPos> unique = new HashSet<>();

        List<BlockPos> adjacent = new ArrayList<>();
        for (Direction dir : Direction.Type.HORIZONTAL) {
            addCandidate(adjacent, unique, containerPos.offset(dir));
        }
        BlockPos hit = findNearestSafeCandidate(adjacent, containerPos);
        if (hit != null) return hit;

        List<BlockPos> sticky = new ArrayList<>();
        addCandidate(sticky, unique, restockStandBlock);
        addCandidate(sticky, unique, current);
        addCandidate(sticky, unique, playerPos);
        hit = findNearestSafeCandidate(sticky, containerPos);
        if (hit != null) return hit;

        List<BlockPos> around = new ArrayList<>();
        for (int dist = 1; dist <= CONTAINER_FAR_DISTANCE; dist++) {
            for (Direction dir : Direction.Type.HORIZONTAL) {
                addCandidate(around, unique, current.offset(dir, dist));
                addCandidate(around, unique, playerPos.offset(dir, dist));
            }
        }
        hit = findNearestSafeCandidate(around, containerPos);
        if (hit != null) return hit;

        List<BlockPos> raised = new ArrayList<>();
        for (int dist = 1; dist <= CONTAINER_FAR_DISTANCE; dist++) {
            for (Direction dir : Direction.Type.HORIZONTAL) {
                addCandidate(raised, unique, containerPos.offset(dir, dist).up());
            }
        }
        return findNearestSafeCandidate(raised, containerPos);
    }

    boolean isSafeRestockStandBlock(BlockPos standBlock, BlockPos containerPos) {
        if (mc.world == null || mc.player == null || standBlock == null || containerPos == null) return false;

        if (standBlock.equals(containerPos)) return false;

        BlockState standState = mc.world.getBlockState(standBlock);
        if (!standState.isAir() && !standState.isReplaceable()) return false;

        BlockState standHead = mc.world.getBlockState(standBlock.up());
        if (!standHead.isAir() && !standHead.isReplaceable()) return false;

        BlockPos below = standBlock.down();
        if (mc.world.getBlockState(below).isAir()
            || mc.world.getBlockState(below).isReplaceable()
            || !mc.world.getFluidState(below).isEmpty()) {
            return false;
        }

        if (mc.player.getBoundingBox().intersects(new Box(standBlock))) return false;

        Vec3d target = getSafeRestockPoint(standBlock, containerPos);
        if (target == null) return false;

        double interactDist = getBestContainerInteractDistance(containerPos);
        return interactDist <= module.maxReach.get() + 0.35;
    }

    BlockPos selectFallbackStandBlock(BlockPos containerPos) {
        if (mc.world == null || mc.player == null || containerPos == null) return null;

        List<BlockPos> candidates = new ArrayList<>();
        Set<BlockPos> unique = new HashSet<>();

        BlockPos playerPos = mc.player.getBlockPos();
        for (int dist = 1; dist <= CONTAINER_FAR_DISTANCE + 1; dist++) {
            for (Direction dir : Direction.Type.HORIZONTAL) {
                addCandidate(candidates, unique, playerPos.offset(dir, dist));
                addCandidate(candidates, unique, playerPos.offset(dir, dist).up());
            }
        }

        return findNearestSafeCandidate(candidates, containerPos);
    }

    Vec3d getSafeRestockPoint(BlockPos standBlock, BlockPos containerPos) {
        if (standBlock == null || containerPos == null) return null;

        double x = standBlock.getX() + 0.5;
        double y = standBlock.getY();
        double z = standBlock.getZ() + 0.5;

        double containerCenterX = containerPos.getX() + 0.5;
        double containerCenterZ = containerPos.getZ() + 0.5;

        double pushX = x - containerCenterX;
        double pushZ = z - containerCenterZ;

        double pushLenSq = pushX * pushX + pushZ * pushZ;
        if (pushLenSq > 1.0e-4) {
            double pushLen = Math.sqrt(pushLenSq);
            pushX = (pushX / pushLen) * RESTOCK_BIAS_AWAY_FROM_CONTAINER;
            pushZ = (pushZ / pushLen) * RESTOCK_BIAS_AWAY_FROM_CONTAINER;
            x += pushX;
            z += pushZ;
        }

        double minX = standBlock.getX() + RESTOCK_EDGE_PADDING;
        double maxX = standBlock.getX() + 1.0 - RESTOCK_EDGE_PADDING;
        double minZ = standBlock.getZ() + RESTOCK_EDGE_PADDING;
        double maxZ = standBlock.getZ() + 1.0 - RESTOCK_EDGE_PADDING;

        x = clamp(x, minX, maxX);
        z = clamp(z, minZ, maxZ);

        double clearMinX = standBlock.getX() - RESTOCK_CONTAINER_CLEARANCE;
        double clearMaxX = standBlock.getX() + 1.0 + RESTOCK_CONTAINER_CLEARANCE;
        double clearMinZ = standBlock.getZ() - RESTOCK_CONTAINER_CLEARANCE;
        double clearMaxZ = standBlock.getZ() + 1.0 + RESTOCK_CONTAINER_CLEARANCE;

        if (x >= clearMinX && x <= clearMaxX && z >= clearMinZ && z <= clearMaxZ) {
            double awayX = x - containerCenterX;
            double awayZ = z - containerCenterZ;
            double awayLenSq = awayX * awayX + awayZ * awayZ;
            if (awayLenSq < 1.0e-4) {
                awayX = 1.0;
                awayZ = 0.0;
                awayLenSq = 1.0;
            }
            double awayLen = Math.sqrt(awayLenSq);
            awayX /= awayLen;
            awayZ /= awayLen;

            x = clamp(containerCenterX + awayX * 0.65, minX, maxX);
            z = clamp(containerCenterZ + awayZ * 0.65, minZ, maxZ);
        }

        return new Vec3d(x, y, z);
    }

    double getBestContainerInteractDistance(BlockPos pos) {
        if (mc.player == null) return Double.MAX_VALUE;
        Vec3d eye = mc.player.getEyePos();
        double best = eye.distanceTo(Vec3d.ofCenter(pos));
        for (Direction side : Direction.values()) {
            double dist = eye.distanceTo(HWUtils.getHitVec(pos, side));
            if (dist < best) best = dist;
        }
        return best;
    }

    BlockPos lastContainerPos() {
        return lastContainerPos;
    }

    void setLastContainerPos(BlockPos lastContainerPos) {
        this.lastContainerPos = lastContainerPos;
    }

    BlockPos restockStandBlock() {
        return restockStandBlock;
    }

    void setRestockStandBlock(BlockPos restockStandBlock) {
        this.restockStandBlock = restockStandBlock;
    }

    Vec3d restockStandPos() {
        return restockStandPos;
    }

    void setRestockStandPos(Vec3d restockStandPos) {
        this.restockStandPos = restockStandPos;
    }

    void clearRestockStandTarget() {
        restockStandBlock = null;
        restockStandPos = null;
    }

    private boolean isPreferredContainerPos(BlockPos pos, BlockPos playerPos, HWDirection hwDir) {
        return isPreferredContainerPos(pos, playerPos, hwDir, 1);
    }

    private boolean isPreferredContainerPos(BlockPos pos, BlockPos playerPos, HWDirection hwDir, int maxHorizontalDistance) {
        if (pos == null || playerPos == null) return false;

        int dx = pos.getX() - playerPos.getX();
        int dz = pos.getZ() - playerPos.getZ();
        if (dx == 0 && dz == 0) return false;
        if (Math.max(Math.abs(dx), Math.abs(dz)) > Math.max(1, maxHorizontalDistance)) return false;

        if (hwDir != null && hwDir.forwardProgress(playerPos, pos) > 0.0) return false;
        return true;
    }

    private boolean isValidContainerPos(BlockPos pos, double maxReach, boolean allowSelfOverlap) {
        if (mc.world == null || mc.player == null) return false;
        if (isForwardPlacement(pos) && !isForwardPlacementLevelSafe(pos)) return false;
        if (pos.equals(mc.player.getBlockPos())) return false;
        if (!mc.world.getBlockState(pos).isAir() && !mc.world.getBlockState(pos).isReplaceable()) return false;

        BlockState aboveState = mc.world.getBlockState(pos.up());
        if (!aboveState.isAir() && !aboveState.isReplaceable() && aboveState.isOpaque()) return false;

        Box targetBox = new Box(pos);
        if (!mc.world.getOtherEntities(
            null,
            targetBox,
            entity -> entity != mc.player && !(entity instanceof ItemEntity)
        ).isEmpty()) {
            return false;
        }

        if (!allowSelfOverlap && mc.player.getBoundingBox().intersects(targetBox)) return false;

        BlockPos below = pos.down();
        if (mc.world.getBlockState(below).isAir()
            || mc.world.getBlockState(below).isReplaceable()
            || !mc.world.getFluidState(below).isEmpty()) return false;

        double dist = mc.player.getEyePos().distanceTo(Vec3d.ofCenter(pos));
        return dist <= maxReach + 1.0;
    }

    private boolean isForwardPlacement(BlockPos pos) {
        if (pos == null || mc.player == null || module.pathfinder == null) return false;
        return module.pathfinder.startingDirection.forwardProgress(mc.player.getBlockPos(), pos) > 0.0;
    }

    private boolean isForwardPlacementLevelSafe(BlockPos pos) {
        if (pos == null || module.pathfinder == null || module.pathfinder.currentBlockPos == null) return false;
        return pos.getY() == module.pathfinder.currentBlockPos.getY();
    }

    private void addCandidate(List<BlockPos> list, Set<BlockPos> unique, BlockPos pos) {
        if (pos == null) return;
        if (unique.add(pos)) list.add(pos);
    }

    private void addContainerCandidates(List<BlockPos> candidates, Set<BlockPos> unique, BlockPos anchor, HWDirection hwDir, int distance) {
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

            if (bx != 0 || bz != 0) addCandidate(candidates, unique, anchor.add(bx, 0, bz));

            if (lx != 0 || lz != 0) {
                addCandidate(candidates, unique, anchor.add(bx + lx, 0, bz + lz));
                addCandidate(candidates, unique, anchor.add(bx - lx, 0, bz - lz));
                addCandidate(candidates, unique, anchor.add(lx, 0, lz));
                addCandidate(candidates, unique, anchor.add(-lx, 0, -lz));
            }

            for (Direction dir : Direction.Type.HORIZONTAL) {
                addCandidate(candidates, unique, anchor.offset(dir, distance));
            }
            return;
        }

        for (Direction dir : Direction.Type.HORIZONTAL) {
            addCandidate(candidates, unique, anchor.offset(dir, distance));
        }
    }

    private BlockPos findNearestSafeCandidate(List<BlockPos> candidates, BlockPos containerPos) {
        if (candidates == null || candidates.isEmpty() || mc.player == null) return null;

        if (containerPos != null && getBestContainerInteractDistance(containerPos) > module.maxReach.get() + 0.35) {
            return null;
        }

        for (BlockPos candidate : candidates) {
            if (isSafeRestockStandBlock(candidate, containerPos)) return candidate;
        }

        return null;
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}



