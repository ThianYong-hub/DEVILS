package com.example.addon.modules.highwaybuilder;

import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.ItemEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class HWUtils {
    private static final MinecraftClient mc = MinecraftClient.getInstance();

    public static Vec3d getHitVec(BlockPos pos, Direction side) {
        return Vec3d.ofCenter(pos).add(
            side.getOffsetX() * 0.5,
            side.getOffsetY() * 0.5,
            side.getOffsetZ() * 0.5
        );
    }

    public static Vec3d getHitVecOffset(Direction side) {
        return new Vec3d(
            0.5 + side.getOffsetX() * 0.5,
            0.5 + side.getOffsetY() * 0.5,
            0.5 + side.getOffsetZ() * 0.5
        );
    }

    public static Direction getMiningSide(BlockPos pos) {
        if (mc.player == null || mc.world == null) return null;

        Vec3d eyePos = mc.player.getEyePos();
        Direction best = null;
        double bestDist = Double.MAX_VALUE;

        for (Direction side : Direction.values()) {
            Vec3d hitVec = getHitVec(pos, side);

            // Check if face is visible from player
            BlockPos adjacent = pos.offset(side);
            BlockState adjacentState = mc.world.getBlockState(adjacent);
            if (!adjacentState.isAir()
                && !adjacentState.isReplaceable()
                && adjacentState.isOpaque()) continue;

            double dist = eyePos.squaredDistanceTo(hitVec);
            if (dist < bestDist) {
                bestDist = dist;
                best = side;
            }
        }

        return best;
    }

    public static Direction getMiningSideFallback(BlockPos pos) {
        if (mc.player == null) return Direction.UP;

        Vec3d eye = mc.player.getEyePos();
        Vec3d center = Vec3d.ofCenter(pos);
        double dx = eye.x - center.x;
        double dy = eye.y - center.y;
        double dz = eye.z - center.z;

        return Direction.getFacing(dx, dy, dz);
    }

    public static List<PlaceInfo> getNeighbourSequence(BlockPos pos, int depth, double reach, boolean visibleOnly) {
        if (mc.player == null || mc.world == null) return Collections.emptyList();

        Vec3d eyePos = mc.player.getEyePos();

        // Direct neighbours first (depth 1)
        for (Direction side : Direction.values()) {
            BlockPos neighbour = pos.offset(side);
            if (eyePos.squaredDistanceTo(Vec3d.ofCenter(neighbour)) > reach * reach) continue;

            BlockState state = mc.world.getBlockState(neighbour);
            if (!state.isAir() && state.isSolidBlock(mc.world, neighbour)) {
                if (visibleOnly) {
                    BlockPos checkPos = neighbour.offset(side.getOpposite());
                    BlockState checkState = mc.world.getBlockState(checkPos);
                    if (!checkState.isAir() && !checkState.isReplaceable()) continue;
                }

                List<PlaceInfo> result = new ArrayList<>();
                result.add(new PlaceInfo(neighbour, side.getOpposite()));
                return result;
            }
        }

        // Deep search (depth > 1)
        if (depth > 1) {
            for (Direction side : Direction.values()) {
                BlockPos neighbour = pos.offset(side);
                if (eyePos.squaredDistanceTo(Vec3d.ofCenter(neighbour)) > reach * reach) continue;

                BlockState state = mc.world.getBlockState(neighbour);
                if (!state.isAir() && !state.isReplaceable()) continue;

                List<PlaceInfo> deeper = getNeighbourSequence(neighbour, depth - 1, reach, visibleOnly);
                if (!deeper.isEmpty()) {
                    List<PlaceInfo> result = new ArrayList<>(deeper);
                    result.add(new PlaceInfo(neighbour, side.getOpposite()));
                    return result;
                }
            }
        }

        return Collections.emptyList();
    }

    public static boolean isPlaceable(BlockPos pos) {
        if (mc.world == null) return false;
        BlockState state = mc.world.getBlockState(pos);
        if (!state.isAir() && !state.isReplaceable()) return false;

        // Item drops should not block placement checks.
        Box box = new Box(pos);
        return mc.world.getOtherEntities(null, box, entity -> !(entity instanceof ItemEntity)).isEmpty();
    }

    public static boolean isLiquid(BlockPos pos) {
        if (mc.world == null) return false;
        return !mc.world.getFluidState(pos).isEmpty();
    }

    public static boolean isLiquidSource(BlockPos pos) {
        if (mc.world == null) return false;
        return !mc.world.getFluidState(pos).isEmpty() && mc.world.getFluidState(pos).isStill();
    }
}
