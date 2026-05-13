package com.example.addon.modules.stashmover;

import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

final class StashMoverPearlApproach {
    private static final double APPROACH_HORIZONTAL_SQ = 0.28 * 0.28;
    private static final double APPROACH_MAX_SPEED_SQ = 0.02 * 0.02;
    private StashMoverPearlApproach() {
    }

    static Vec3d resolveTarget(Vec3d explicitTarget, BlockPos water, Vec3d chamber) {
        if (explicitTarget != null) return explicitTarget;
        if (water != null) return Vec3d.ofCenter(water);
        return chamber;
    }

    static boolean isTargetObstructed(MinecraftClient mc, Vec3d target, BlockPos water, Vec3d chamber) {
        if (mc == null || mc.player == null) return true;
        return isTargetObstructed(mc, mc.player.getEyePos(), target, water, chamber);
    }

    static boolean isTargetObstructed(MinecraftClient mc, Vec3d eyePos, Vec3d target, BlockPos water, Vec3d chamber) {
        if (mc == null || mc.world == null || eyePos == null || target == null) return true;

        HitResult hit = mc.world.raycast(new RaycastContext(
            eyePos,
            target,
            RaycastContext.ShapeType.COLLIDER,
            RaycastContext.FluidHandling.NONE,
            mc.player
        ));

        if (hit.getType() != HitResult.Type.BLOCK) return false;
        if (!(hit instanceof BlockHitResult blockHit)) return true;

        BlockPos hitPos = blockHit.getBlockPos();
        BlockPos targetBlock = BlockPos.ofFloored(target.x, target.y, target.z);
        if (hitPos.equals(targetBlock)) return false;
        if (water != null && hitPos.equals(water)) return false;
        if (chamber != null && hitPos.equals(BlockPos.ofFloored(chamber.x, chamber.y, chamber.z))) return false;
        return eyePos.distanceTo(hit.getPos()) + 0.10 < eyePos.distanceTo(target);
    }

    static BlockPos resolveApproachGoal(MinecraftClient mc, BlockPos water, Vec3d target, Vec3d chamber) {
        if (mc == null || mc.player == null || mc.world == null || water == null || target == null) return water;
        if (isCurrentThrowPosition(mc, water, target, chamber)) return null;

        BlockPos adjacent = resolveAdjacentApproachGoal(mc, water, target, chamber);
        if (adjacent != null) return adjacent;
        return null;
    }

    static boolean isAtApproachGoal(MinecraftClient mc, BlockPos goal, Vec3d target, BlockPos water, Vec3d chamber) {
        if (mc == null || mc.player == null || goal == null) return false;
        if (!mc.player.getBlockPos().equals(goal)) return false;

        Vec3d goalCenter = Vec3d.ofCenter(goal);
        double dx = mc.player.getX() - goalCenter.x;
        double dz = mc.player.getZ() - goalCenter.z;
        if (dx * dx + dz * dz > APPROACH_HORIZONTAL_SQ) return false;
        if (mc.player.getVelocity().x * mc.player.getVelocity().x + mc.player.getVelocity().z * mc.player.getVelocity().z > APPROACH_MAX_SPEED_SQ) {
            return false;
        }

        return !isTargetObstructed(mc, target, water, chamber);
    }

    static boolean isCurrentThrowPosition(MinecraftClient mc, BlockPos water, Vec3d target, Vec3d chamber) {
        if (mc == null || mc.player == null || mc.world == null || water == null || target == null) return false;

        double speedSq = mc.player.getVelocity().x * mc.player.getVelocity().x + mc.player.getVelocity().z * mc.player.getVelocity().z;
        if (speedSq > APPROACH_MAX_SPEED_SQ) return false;
        if (isTargetObstructed(mc, target, water, chamber)) return false;

        BlockPos playerBlock = mc.player.getBlockPos();
        boolean waterLoadedAtFeet = mc.world.getFluidState(playerBlock).isIn(FluidTags.WATER)
            || mc.world.getFluidState(playerBlock.down()).isIn(FluidTags.WATER);
        if ((playerBlock.equals(water) || waterLoadedAtFeet) && isCenteredOverWater(mc.player.getEntityPos(), water)) return true;

        BlockPos adjacent = resolveAdjacentApproachGoal(mc, water, target, chamber);
        if (adjacent != null) {
            if (!adjacent.equals(playerBlock)) return false;

            Vec3d goalCenter = Vec3d.ofCenter(adjacent);
            double dx = mc.player.getX() - goalCenter.x;
            double dz = mc.player.getZ() - goalCenter.z;
            return dx * dx + dz * dz <= APPROACH_HORIZONTAL_SQ;
        }

        return false;
    }

    static boolean isCenteredOverWater(Vec3d playerPos, BlockPos water) {
        if (playerPos == null || water == null) return false;

        Vec3d waterCenter = Vec3d.ofCenter(water);
        double dx = playerPos.x - waterCenter.x;
        double dz = playerPos.z - waterCenter.z;
        return dx * dx + dz * dz <= APPROACH_HORIZONTAL_SQ;
    }

    private static boolean isStandable(MinecraftClient mc, BlockPos pos) {
        if (mc.world == null) return false;

        BlockState feet = mc.world.getBlockState(pos);
        BlockState head = mc.world.getBlockState(pos.up());
        BlockState floor = mc.world.getBlockState(pos.down());

        if (!feet.getCollisionShape(mc.world, pos).isEmpty()) return false;
        if (!head.getCollisionShape(mc.world, pos.up()).isEmpty()) return false;
        if (mc.world.getFluidState(pos).isIn(FluidTags.WATER)) return false;
        if (mc.world.getFluidState(pos.up()).isIn(FluidTags.WATER)) return false;
        return !floor.getCollisionShape(mc.world, pos.down()).isEmpty();
    }

    private static Vec3d candidateEyePos(ClientPlayerEntity player, BlockPos pos) {
        return new Vec3d(pos.getX() + 0.5, pos.getY() + player.getEyeHeight(player.getPose()), pos.getZ() + 0.5);
    }

    private static BlockPos resolveAdjacentApproachGoal(MinecraftClient mc, BlockPos water, Vec3d target, Vec3d chamber) {
        BlockPos chamberPreferred = resolveChamberAdjacentApproachGoal(mc, water, target, chamber);
        if (chamberPreferred != null) return chamberPreferred;

        BlockPos bestUnobstructed = null;
        double bestUnobstructedScore = Double.MAX_VALUE;
        BlockPos bestStandable = null;
        double bestStandableScore = Double.MAX_VALUE;

        int[] yOffsets = {1, 0, -1};
        for (int yOffset : yOffsets) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) continue;

                    BlockPos candidate = water.add(dx, yOffset, dz);
                    if (!isStandable(mc, candidate)) continue;

                    double playerDistance = Vec3d.ofCenter(candidate).squaredDistanceTo(mc.player.getX(), mc.player.getY(), mc.player.getZ());
                    if (playerDistance < bestStandableScore) {
                        bestStandableScore = playerDistance;
                        bestStandable = candidate.toImmutable();
                    }

                    if (isTargetObstructed(mc, candidateEyePos(mc.player, candidate), target, water, chamber)) continue;

                    double waterDistance = Vec3d.ofCenter(candidate).squaredDistanceTo(Vec3d.ofCenter(water));
                    double elevationPenalty = yOffset == 1 ? 0.0 : (yOffset == 0 ? 0.5 : 2.0);
                    double score = playerDistance + waterDistance * 0.25 + elevationPenalty;
                    if (score < bestUnobstructedScore) {
                        bestUnobstructedScore = score;
                        bestUnobstructed = candidate.toImmutable();
                    }
                }
            }
        }

        if (bestUnobstructed != null) return bestUnobstructed;
        return bestStandable;
    }

    private static BlockPos resolveChamberAdjacentApproachGoal(MinecraftClient mc, BlockPos water, Vec3d target, Vec3d chamber) {
        if (mc == null || mc.player == null || chamber == null) return null;

        BlockPos chamberBlock = BlockPos.ofFloored(chamber.x, chamber.y, chamber.z);
        BlockPos bestStandable = null;
        double bestStandableScore = Double.MAX_VALUE;

        BlockPos[] candidates = {
            chamberBlock.east(),
            chamberBlock.north(),
            chamberBlock.south(),
            chamberBlock.west(),
            chamberBlock.east(2),
            chamberBlock.north(2),
            chamberBlock.south(2),
            chamberBlock.west(2)
        };

        for (BlockPos candidate : candidates) {
            if (candidate == null || candidate.equals(water)) continue;
            if (!isStandable(mc, candidate)) continue;

            double playerDistance = Vec3d.ofCenter(candidate).squaredDistanceTo(mc.player.getX(), mc.player.getY(), mc.player.getZ());
            if (playerDistance < bestStandableScore) {
                bestStandableScore = playerDistance;
                bestStandable = candidate.toImmutable();
            }

            if (!isTargetObstructed(mc, candidateEyePos(mc.player, candidate), target, water, chamber)) {
                return candidate.toImmutable();
            }
        }

        return bestStandable;
    }
}
