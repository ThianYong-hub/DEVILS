package com.example.addon.modules.highwaybuilder;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3i;

public enum HWDirection {
    NORTH(0, new Vec3i(0, 0, -1), false),
    NORTH_EAST(45, new Vec3i(1, 0, -1), true),
    EAST(90, new Vec3i(1, 0, 0), false),
    SOUTH_EAST(135, new Vec3i(1, 0, 1), true),
    SOUTH(180, new Vec3i(0, 0, 1), false),
    SOUTH_WEST(225, new Vec3i(-1, 0, 1), true),
    WEST(270, new Vec3i(-1, 0, 0), false),
    NORTH_WEST(315, new Vec3i(-1, 0, -1), true);

    public final int yaw;
    public final Vec3i directionVec;
    public final boolean isDiagonal;

    HWDirection(int yaw, Vec3i directionVec, boolean isDiagonal) {
        this.yaw = yaw;
        this.directionVec = directionVec;
        this.isDiagonal = isDiagonal;
    }

    public HWDirection clockwise(int steps) {
        HWDirection[] values = values();
        return values[(this.ordinal() + steps) % values.length];
    }

    public HWDirection counterClockwise(int steps) {
        HWDirection[] values = values();
        return values[((this.ordinal() - steps) % values.length + values.length) % values.length];
    }

    public HWDirection lateralDirection() {
        return clockwise(isDiagonal ? 1 : 2);
    }

    public double forwardProgress(BlockPos origin, BlockPos pos) {
        int dx = pos.getX() - origin.getX();
        int dz = pos.getZ() - origin.getZ();

        if (!isDiagonal) {
            return dx * directionVec.getX() + dz * directionVec.getZ();
        }

        HWDirection lateral = lateralDirection();
        if (lateral.directionVec.getX() != 0) {
            return dz * directionVec.getZ();
        }

        return dx * directionVec.getX();
    }

    public double lateralOffset(BlockPos origin, BlockPos pos) {
        int dx = pos.getX() - origin.getX();
        int dz = pos.getZ() - origin.getZ();
        HWDirection lateral = lateralDirection();

        if (!isDiagonal) {
            return dx * lateral.directionVec.getX() + dz * lateral.directionVec.getZ();
        }

        if (lateral.directionVec.getX() != 0) {
            return dx * lateral.directionVec.getX();
        }

        return dz * lateral.directionVec.getZ();
    }

    public BlockPos multiply(Vec3i vec, int factor) {
        return new BlockPos(vec.getX() * factor, vec.getY() * factor, vec.getZ() * factor);
    }

    public BlockPos offset(BlockPos pos, int distance) {
        return pos.add(
            directionVec.getX() * distance,
            directionVec.getY() * distance,
            directionVec.getZ() * distance
        );
    }

    public static HWDirection fromYaw(float yaw) {
        float normalized = ((yaw % 360) + 360) % 360;

        if (normalized >= 337.5 || normalized < 22.5) return SOUTH;
        if (normalized < 67.5) return SOUTH_WEST;
        if (normalized < 112.5) return WEST;
        if (normalized < 157.5) return NORTH_WEST;
        if (normalized < 202.5) return NORTH;
        if (normalized < 247.5) return NORTH_EAST;
        if (normalized < 292.5) return EAST;
        return SOUTH_EAST;
    }

    public static HWDirection fromYawCardinal(float yaw) {
        float normalized = ((yaw % 360) + 360) % 360;

        if (normalized >= 315 || normalized < 45) return SOUTH;
        if (normalized < 135) return WEST;
        if (normalized < 225) return NORTH;
        return EAST;
    }

    public String getDisplayName() {
        return name().charAt(0) + name().substring(1).toLowerCase().replace('_', '-');
    }
}


