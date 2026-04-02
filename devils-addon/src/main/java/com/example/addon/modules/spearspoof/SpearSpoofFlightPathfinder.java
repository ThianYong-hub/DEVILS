package com.example.addon.modules.spearspoof;

import com.example.addon.modules.SpearSpoof;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class SpearSpoofFlightPathfinder {
    private static final int CACHE_SWEEP_TICKS = 64;
    private static final int MAX_CACHE_ENTRIES = 20000;
    private static final double WAYPOINT_REACH = 2.0;
    private static final double TARGET_REPATH_DISTANCE_SQ = 6.25;
    private static final double FLOOR_CLEARANCE = 2.2;
    private static final double CEILING_CLEARANCE = 1.5;
    private static final int SAFETY_SCAN = 14;
    private static final double COLLISION_STEP = 0.55;
    private static final double NETHER_MAX_FLIGHT_Y = 123.0;

    private final SpearSpoof module;
    private final SpearSpoofFlightPathSearch search;
    private final Map<Long, Boolean> clearanceCache = new HashMap<>();
    private final Map<Long, Double> floorDistanceCache = new HashMap<>();
    private final Map<Long, Double> ceilingDistanceCache = new HashMap<>();

    private List<Vec3d> currentPath = new ArrayList<>();
    private int currentWaypointIndex;
    private int pathTimer;
    private Vec3d lastPathTarget;
    private int cacheSweepTicks;

    public SpearSpoofFlightPathfinder(SpearSpoof module) {
        this.module = module;
        this.search = new SpearSpoofFlightPathSearch(module, this);
    }

    public void onDeactivate() {
        currentPath.clear();
        currentWaypointIndex = 0;
        pathTimer = 0;
        lastPathTarget = null;
        cacheSweepTicks = 0;
        clearCaches();
    }

    public void resetNavigationState() {
        currentPath.clear();
        currentWaypointIndex = 0;
        pathTimer = 0;
        lastPathTarget = null;
        cacheSweepTicks = 0;
        clearCaches();
    }

    public void resetPathState() {
        currentPath.clear();
        currentWaypointIndex = 0;
        pathTimer = 0;
        lastPathTarget = null;
    }

    public void clearTargetState() {
        resetPathState();
    }

    public void tickCaches() {
        if (++cacheSweepTicks >= CACHE_SWEEP_TICKS || shouldTrimCaches()) {
            cacheSweepTicks = 0;
            clearCaches();
        }
    }

    public void tickPathing(Vec3d playerPos, Vec3d targetPos, int stuckTicks) {
        if (playerPos == null || targetPos == null) return;

        pathTimer++;
        boolean targetMovedFar = lastPathTarget == null || lastPathTarget.squaredDistanceTo(targetPos) > TARGET_REPATH_DISTANCE_SQ;
        boolean pathBlocked = currentWaypointIndex < currentPath.size() && !isDirectPathClear(playerPos, currentPath.get(currentWaypointIndex));
        boolean pathDepleted = currentPath.isEmpty() || currentWaypointIndex >= currentPath.size();
        boolean needsRepath = pathDepleted
            || (targetMovedFar && pathTimer >= SpearSpoofFlightPathSearch.PATH_UPDATE_TICKS)
            || pathTimer >= SpearSpoofFlightPathSearch.PATH_FORCE_REFRESH_TICKS
            || stuckTicks >= SpearSpoofFlightPathSearch.STUCK_REPATH_TICKS
            || pathBlocked;

        if (needsRepath) {
            pathTimer = 0;
            lastPathTarget = targetPos;
            currentPath = search.computePath(playerPos, targetPos);
            currentWaypointIndex = 0;
        }

        advanceWaypoints(playerPos);
    }

    public Vec3d steerTarget(Vec3d fallback) {
        if (currentWaypointIndex < currentPath.size()) return currentPath.get(currentWaypointIndex);
        return fallback;
    }

    public Vec3d adjustToSafeCorridor(Vec3d desired, Vec3d fallbackRef) {
        return adjustToSafeCorridor(desired, fallbackRef, FLOOR_CLEARANCE);
    }

    public Vec3d adjustToSafeCorridor(Vec3d desired, Vec3d fallbackRef, double floorClearance) {
        if (desired == null) return fallbackRef == null ? Vec3d.ZERO : fallbackRef;

        desired = clampToDimensionFlightCap(desired);
        double floorY = nearestSolidBelow(desired, SAFETY_SCAN);
        double ceilingY = nearestSolidAbove(desired, SAFETY_SCAN);
        double minY = floorY + (floorClearance > 0 ? floorClearance : FLOOR_CLEARANCE);
        double maxY = ceilingY - playerHeight() - CEILING_CLEARANCE;
        if (hasNetherFlightCap()) maxY = Math.min(maxY, NETHER_MAX_FLIGHT_Y);
        double y = desired.y;

        if (minY <= maxY) {
            y = MathHelper.clamp(y, minY, maxY);
        } else {
            double fallback = fallbackRef != null ? fallbackRef.y : y;
            y = Math.max(floorY + 1.0, Math.min(ceilingY - playerHeight() - 0.25, fallback));
        }

        Vec3d candidate = new Vec3d(desired.x, y, desired.z);
        if (hasPlayerClearance(candidate)) return candidate;

        for (int i = 1; i <= 12; i++) {
            double shift = i * 0.35;
            Vec3d up = candidate.add(0, shift, 0);
            if (hasPlayerClearance(up)) return up;

            Vec3d down = candidate.add(0, -shift, 0);
            if (hasPlayerClearance(down)) return down;
        }

        return candidate;
    }

    public boolean isDirectPathClear(Vec3d start, Vec3d goal) {
        Vec3d diff = goal.subtract(start);
        double dist = diff.length();
        if (dist < 0.2) return true;

        Vec3d dir = diff.normalize();
        double step = Math.max(0.25, COLLISION_STEP);
        for (double d = 0; d <= dist; d += step) {
            if (!hasPlayerClearance(start.add(dir.multiply(d)))) return false;
        }

        return true;
    }

    public boolean hasPlayerClearance(Vec3d center) {
        if (module.client().world == null) return false;
        if (hasNetherFlightCap() && center.y > NETHER_MAX_FLIGHT_Y) return false;

        long key = BlockPos.ofFloored(center).asLong();
        Boolean cached = clearanceCache.get(key);
        if (cached != null) return cached;

        double halfWidth = 0.35;
        double height = playerHeight();
        int minX = MathHelper.floor(center.x - halfWidth);
        int maxX = MathHelper.floor(center.x + halfWidth);
        int minY = MathHelper.floor(center.y);
        int maxY = MathHelper.floor(center.y + height);
        int minZ = MathHelper.floor(center.z - halfWidth);
        int maxZ = MathHelper.floor(center.z + halfWidth);
        BlockPos.Mutable mutable = new BlockPos.Mutable();

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                if (module.client().world.isOutOfHeightLimit(y)) {
                    clearanceCache.put(key, false);
                    return false;
                }
                for (int z = minZ; z <= maxZ; z++) {
                    mutable.set(x, y, z);
                    if (!module.client().world.getBlockState(mutable).getCollisionShape(module.client().world, mutable).isEmpty()) {
                        clearanceCache.put(key, false);
                        return false;
                    }
                }
            }
        }

        clearanceCache.put(key, true);
        return true;
    }

    public boolean isSolid(BlockPos pos) {
        if (module.client().world == null || module.client().world.isOutOfHeightLimit(pos.getY())) return true;
        return !module.client().world.getBlockState(pos).getCollisionShape(module.client().world, pos).isEmpty();
    }

    public double distanceToSolidBelow(Vec3d pos, int maxDepth) {
        long key = BlockPos.ofFloored(pos).asLong();
        Double cached = floorDistanceCache.get(key);
        if (cached != null) return cached;

        double value = pos.y - nearestSolidBelow(pos, maxDepth);
        floorDistanceCache.put(key, value);
        return value;
    }

    public double distanceToSolidAbove(Vec3d pos, int maxHeight) {
        long key = BlockPos.ofFloored(pos).asLong();
        Double cached = ceilingDistanceCache.get(key);
        if (cached != null) return cached;

        double value = nearestSolidAbove(pos, maxHeight) - (pos.y + playerHeight());
        ceilingDistanceCache.put(key, value);
        return value;
    }

    public int countLateralOpenings(Vec3d center, double distance) {
        int openings = 0;
        for (Direction direction : SpearSpoofFlightPathSearch.CARDINAL_DIRECTIONS) {
            Vec3d sample = center.add(direction.getOffsetX() * distance, 0.0, direction.getOffsetZ() * distance);
            if (hasPlayerClearance(sample)) openings++;
        }
        return openings;
    }

    public boolean hasNetherFlightCap() {
        return module.client().world != null && module.client().world.getRegistryKey() == net.minecraft.world.World.NETHER;
    }

    public Vec3d clampToDimensionFlightCap(Vec3d pos) {
        if (!hasNetherFlightCap() || pos.y <= NETHER_MAX_FLIGHT_Y) return pos;
        return new Vec3d(pos.x, NETHER_MAX_FLIGHT_Y, pos.z);
    }

    BlockPos clampToDimensionFlightCap(BlockPos pos) {
        if (!hasNetherFlightCap() || pos.getY() <= (int) NETHER_MAX_FLIGHT_Y) return pos;
        return new BlockPos(pos.getX(), (int) NETHER_MAX_FLIGHT_Y, pos.getZ());
    }

    double playerHeight() {
        return 1.8;
    }

    private void advanceWaypoints(Vec3d playerPos) {
        double reachSq = WAYPOINT_REACH * WAYPOINT_REACH;
        while (currentWaypointIndex < currentPath.size()) {
            if (playerPos.squaredDistanceTo(currentPath.get(currentWaypointIndex)) <= reachSq) currentWaypointIndex++;
            else break;
        }
    }

    private void clearCaches() {
        clearanceCache.clear();
        floorDistanceCache.clear();
        ceilingDistanceCache.clear();
    }

    private boolean shouldTrimCaches() {
        return clearanceCache.size() > MAX_CACHE_ENTRIES
            || floorDistanceCache.size() > MAX_CACHE_ENTRIES
            || ceilingDistanceCache.size() > MAX_CACHE_ENTRIES;
    }

    private double nearestSolidBelow(Vec3d pos, int maxDepth) {
        int x = MathHelper.floor(pos.x);
        int z = MathHelper.floor(pos.z);
        int yStart = MathHelper.floor(pos.y);
        BlockPos.Mutable mutable = new BlockPos.Mutable(x, yStart, z);

        for (int i = 0; i <= maxDepth; i++) {
            int y = yStart - i;
            if (module.client().world.isOutOfHeightLimit(y)) return y;
            mutable.set(x, y, z);
            if (isSolid(mutable)) return y + 1.0;
        }

        return pos.y - maxDepth;
    }

    private double nearestSolidAbove(Vec3d pos, int maxHeight) {
        int x = MathHelper.floor(pos.x);
        int z = MathHelper.floor(pos.z);
        int yStart = MathHelper.floor(pos.y + playerHeight());
        BlockPos.Mutable mutable = new BlockPos.Mutable(x, yStart, z);

        for (int i = 0; i <= maxHeight; i++) {
            int y = yStart + i;
            if (module.client().world.isOutOfHeightLimit(y)) return y;
            mutable.set(x, y, z);
            if (isSolid(mutable)) return y;
        }

        return pos.y + maxHeight;
    }
}
