package com.devils.addon.modules.spearspoof;

import com.devils.addon.modules.SpearSpoof;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.utils.entity.SortPriority;
import meteordevelopment.meteorclient.utils.entity.TargetUtils;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.passive.PassiveEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Heightmap;

public final class SpearSpoofTargetingService {
    private static final double LANE_RAY_STEP = 0.20;
    private static final double TOP_DOWN_ROUTE_HEIGHT = 3.6;
    private static final double TOP_DOWN_ROUTE_HEIGHT_ALT = 2.4;
    private static final double POCKET_RADIUS_NEAR = 2.6;
    private static final double POCKET_RADIUS_MID = 3.4;
    private static final double POCKET_RADIUS_FAR = 4.4;

    private final SpearSpoof module;
    private final Setting<SortPriority> priority;
    private final Setting<SpearSpoof.TargetEntityMode> targetEntity;
    private final Setting<Boolean> ignoreFriends;
    private final Setting<Integer> targetStickMs;
    private final Setting<Integer> retargetDelayMs;

    public SpearSpoofTargetingService(
        SpearSpoof module,
        Setting<SortPriority> priority,
        Setting<SpearSpoof.TargetEntityMode> targetEntity,
        Setting<Boolean> ignoreFriends,
        Setting<Integer> targetStickMs,
        Setting<Integer> retargetDelayMs
    ) {
        this.module = module;
        this.priority = priority;
        this.targetEntity = targetEntity;
        this.ignoreFriends = ignoreFriends;
        this.targetStickMs = targetStickMs;
        this.retargetDelayMs = retargetDelayMs;
    }

    public LivingEntity resolve(SpearSpoofRuntime runtime) {
        long now = System.currentTimeMillis();
        LivingEntity current = runtime.target;

        if (current != null) {
            // Sticky lock so targeting does not flicker every tick.
            if (isHardLockedTargetAlive(current)) {
                if (runtime.targetLockedAtMs == 0) runtime.targetLockedAtMs = now;
                long lockAgeMs = Math.max(0L, now - runtime.targetLockedAtMs);
                if (lockAgeMs < Math.max(0, targetStickMs.get())) return current;

                LivingEntity candidate = findCandidate();
                if (candidate == null || candidate == current) return current;
                runtime.target = candidate;
                runtime.targetLockedAtMs = now;
                return candidate;
            }
        }

        if (current != null) {
            runtime.target = null;
            runtime.targetLostAtMs = now;
            runtime.targetLockedAtMs = 0;
        }

        if (now - runtime.targetLostAtMs < retargetDelayMs.get()) return null;

        LivingEntity candidate = findCandidate();
        if (candidate != null) {
            runtime.target = candidate;
            runtime.targetLockedAtMs = now;
            return candidate;
        }

        return null;
    }
    public boolean isValid(Entity entity) {
        if (!(entity instanceof LivingEntity living)) return false;
        return isHardLockedTargetAlive(living);
    }

    private boolean isCandidateValid(Entity entity) {
        if (!(entity instanceof LivingEntity living)) return false;
        if (module.client().player == null) return false;

        if (!passesBaseEntityFilters(entity, living)) return false;
        return passesWorldBlockFilters(living);
    }

    private boolean isHardLockedTargetAlive(LivingEntity living) {
        if (living == null || module.client().player == null || module.client().world == null) return false;
        if (living == module.client().player) return false;
        if (living.isRemoved() || !living.isAlive() || living.isDead()) return false;
        if (module.client().world.getEntityById(living.getId()) == null) return false;
        return module.client().player.distanceTo(living) <= module.permanentTargetRange();
    }

    private LivingEntity findCandidate() {
        Entity generic = TargetUtils.get(this::isCandidateValid, priority.get());
        return generic instanceof LivingEntity living ? living : null;
    }

    private boolean allowsPlayers() {
        return targetEntity.get() == SpearSpoof.TargetEntityMode.Players
            || targetEntity.get() == SpearSpoof.TargetEntityMode.Any;
    }

    private boolean passesBaseEntityFilters(Entity entity, LivingEntity living) {
        if (entity == module.client().player || entity.isRemoved() || !entity.isAlive() || living.isDead()) return false;
        if (module.client().player.distanceTo(entity) > module.permanentTargetRange()) return false;

        if (entity instanceof PlayerEntity player) {
            if (!allowsPlayers()) return false;
            if (player.isSpectator() || player.isCreative()) return false;
            if (ignoreFriends.get() && Friends.get().isFriend(player)) return false;
            return true;
        }

        return switch (targetEntity.get()) {
            case Players -> false;
            case Passive -> entity instanceof PassiveEntity;
            case Hostile -> entity instanceof HostileEntity;
            case Any -> true;
        };
    }
    private boolean passesWorldBlockFilters(LivingEntity living) {
        if (living == null || module.client().world == null || module.client().player == null) return false;
        if (isTargetFullyInsideHardBlock(living)) return false;
        if (isTargetFullyHardEnclosed(living)) return false;
        boolean reachableLane = hasReachableAttackLane(living);
        boolean stablePocket = false;
        if (!reachableLane && isHardRoofedTarget(living)) {
            stablePocket = hasStableCombatPocket(living);
            if (!stablePocket) return false;
        }
        boolean routeAvailable = reachableLane || stablePocket;
        if (!routeAvailable) return false;
        if (isDeepUndergroundTarget(living, routeAvailable)) return false;
        return true;
    }

    private boolean isTargetFullyInsideHardBlock(LivingEntity living) {
        Box box = living.getBoundingBox();
        double cx = (box.minX + box.maxX) * 0.5;
        double cz = (box.minZ + box.maxZ) * 0.5;
        double x1 = MathHelper.lerp(0.18, box.minX, box.maxX);
        double x2 = MathHelper.lerp(0.82, box.minX, box.maxX);
        double z1 = MathHelper.lerp(0.18, box.minZ, box.maxZ);
        double z2 = MathHelper.lerp(0.82, box.minZ, box.maxZ);
        double yFeet = box.minY + 0.08;
        double yBody = box.minY + Math.max(0.18, (box.maxY - box.minY) * 0.52);
        double yHead = box.maxY - 0.08;

        Vec3d[] samples = new Vec3d[] {
            new Vec3d(cx, yFeet, cz),
            new Vec3d(cx, yBody, cz),
            new Vec3d(cx, yHead, cz),
            new Vec3d(x1, yBody, z1),
            new Vec3d(x1, yBody, z2),
            new Vec3d(x2, yBody, z1),
            new Vec3d(x2, yBody, z2)
        };

        int blocked = 0;
        for (Vec3d sample : samples) {
            if (isHardBlocking(BlockPos.ofFloored(sample))) blocked++;
        }
        return blocked >= samples.length;
    }

    private boolean isTargetFullyHardEnclosed(LivingEntity living) {
        if (living == null) return false;
        Box box = living.getBoundingBox();
        double cx = (box.minX + box.maxX) * 0.5;
        double cz = (box.minZ + box.maxZ) * 0.5;
        double w = box.maxX - box.minX;
        double d = box.maxZ - box.minZ;
        double radius = Math.max(0.55, Math.max(w, d) * 0.70);
        double yFeet = box.minY + 0.08;
        double yBody = box.minY + Math.max(0.20, (box.maxY - box.minY) * 0.52);
        double yHead = box.maxY - 0.06;
        double yTop = box.maxY + 0.10;
        double invSqrt2 = 0.70710678118;

        double[][] dirs = new double[][] {
            {1.0, 0.0},
            {-1.0, 0.0},
            {0.0, 1.0},
            {0.0, -1.0},
            {invSqrt2, invSqrt2},
            {invSqrt2, -invSqrt2},
            {-invSqrt2, invSqrt2},
            {-invSqrt2, -invSqrt2}
        };
        double[] yLevels = new double[] {yFeet, yBody, yHead};
        for (double y : yLevels) {
            for (double[] dir : dirs) {
                BlockPos side = BlockPos.ofFloored(cx + dir[0] * radius, y, cz + dir[1] * radius);
                if (!isHardBlocking(side)) return false;
            }
        }

        BlockPos topCenter = BlockPos.ofFloored(cx, yTop, cz);
        if (!isHardBlocking(topCenter)) return false;

        double x1 = MathHelper.lerp(0.22, box.minX, box.maxX);
        double x2 = MathHelper.lerp(0.78, box.minX, box.maxX);
        double z1 = MathHelper.lerp(0.22, box.minZ, box.maxZ);
        double z2 = MathHelper.lerp(0.78, box.minZ, box.maxZ);
        BlockPos[] topCorners = new BlockPos[] {
            BlockPos.ofFloored(x1, yTop, z1),
            BlockPos.ofFloored(x1, yTop, z2),
            BlockPos.ofFloored(x2, yTop, z1),
            BlockPos.ofFloored(x2, yTop, z2)
        };
        for (BlockPos top : topCorners) {
            if (!isHardBlocking(top)) return false;
        }

        return true;
    }

    private boolean isDeepUndergroundTarget(LivingEntity living, boolean sideReachable) {
        if (living == null || module.client().world == null || module.client().player == null) return false;
        if (!module.client().world.getDimension().hasSkyLight()) return false;

        double belowBy = module.client().player.getY() - living.getY();
        if (belowBy < 4.0) return false;

        double topY = living.getY() + living.getHeight() + 0.35;
        BlockPos top = BlockPos.ofFloored(living.getX(), topY, living.getZ());
        int surfaceY = module.client().world.getTopY(
            Heightmap.Type.MOTION_BLOCKING_NO_LEAVES,
            MathHelper.floor(living.getX()),
            MathHelper.floor(living.getZ())
        );
        double coveredBy = surfaceY - topY;
        boolean skyVisible = isSkyVisibleAround(top);

        // hack: do not dive into cave ESP targets; pathing gets stupid down there.
        if (!skyVisible && belowBy >= 5.0) return true;

        if (sideReachable) {
            if (!skyVisible && belowBy >= 3.0 && coveredBy >= 2.0) return true;
            if (!skyVisible && belowBy >= 4.0 && coveredBy >= 1.4) return true;
            return false;
        }

        if (coveredBy >= 2.5) return true;
        if (!skyVisible && belowBy >= 3.0 && coveredBy >= 1.2) return true;

        return false;
    }

    private boolean hasReachableAttackLane(LivingEntity living) {
        if (living == null || module.client().player == null || module.client().world == null) return false;

        Vec3d eye = module.client().player.getEyePos();
        Vec3d body = module.client().player.getEntityPos().add(0.0, Math.max(0.9, module.client().player.getHeight() * 0.55), 0.0);
        Box box = living.getBoundingBox();

        double cx = (box.minX + box.maxX) * 0.5;
        double cz = (box.minZ + box.maxZ) * 0.5;
        double x1 = MathHelper.lerp(0.22, box.minX, box.maxX);
        double x2 = MathHelper.lerp(0.78, box.minX, box.maxX);
        double z1 = MathHelper.lerp(0.22, box.minZ, box.maxZ);
        double z2 = MathHelper.lerp(0.78, box.minZ, box.maxZ);
        double yFeet = box.minY + 0.10;
        double yBody = box.minY + Math.max(0.18, (box.maxY - box.minY) * 0.52);
        double yHead = box.maxY - 0.08;

        Vec3d[] targetSamples = new Vec3d[] {
            new Vec3d(cx, yFeet, cz),
            new Vec3d(cx, yBody, cz),
            new Vec3d(cx, yHead, cz),
            new Vec3d(x1, yBody, z1),
            new Vec3d(x1, yBody, z2),
            new Vec3d(x2, yBody, z1),
            new Vec3d(x2, yBody, z2)
        };

        for (Vec3d sample : targetSamples) {
            if (!isHardPathBlocked(eye, sample) || !isHardPathBlocked(body, sample)) return true;
        }

        Vec3d[] above = new Vec3d[] {
            new Vec3d(cx, box.maxY + TOP_DOWN_ROUTE_HEIGHT, cz),
            new Vec3d(cx, box.maxY + TOP_DOWN_ROUTE_HEIGHT_ALT, cz)
        };
        for (Vec3d top : above) {
            boolean reachTop = !isHardPathBlocked(eye, top) || !isHardPathBlocked(body, top);
            if (!reachTop) continue;

            for (Vec3d sample : targetSamples) {
                if (!isHardPathBlocked(top, sample)) return true;
            }
        }

        return false;
    }

    private boolean hasStableCombatPocket(LivingEntity living) {
        if (living == null || module.client().world == null) return false;

        Box box = living.getBoundingBox();
        double cx = (box.minX + box.maxX) * 0.5;
        double cz = (box.minZ + box.maxZ) * 0.5;
        double yPocket = box.minY + MathHelper.clamp((box.maxY - box.minY) * 0.45, 0.55, 1.15);
        Vec3d[] targetSamples = buildTargetSamples(living);
        double invSqrt2 = 0.70710678118;
        double[][] dirs = new double[][] {
            {1.0, 0.0},
            {-1.0, 0.0},
            {0.0, 1.0},
            {0.0, -1.0},
            {invSqrt2, invSqrt2},
            {invSqrt2, -invSqrt2},
            {-invSqrt2, invSqrt2},
            {-invSqrt2, -invSqrt2}
        };
        double[] radii = new double[] {POCKET_RADIUS_NEAR, POCKET_RADIUS_MID, POCKET_RADIUS_FAR};

        for (double radius : radii) {
            for (double[] dir : dirs) {
                Vec3d pocket = new Vec3d(cx + dir[0] * radius, yPocket, cz + dir[1] * radius);
                if (!hasAnchorClearance(pocket)) continue;

                for (Vec3d sample : targetSamples) {
                    if (!isHardPathBlocked(pocket, sample)) return true;
                }
            }
        }

        return false;
    }

    private boolean hasAnchorClearance(Vec3d pocket) {
        if (module.client().world == null || pocket == null) return false;

        int minX = MathHelper.floor(pocket.x - 0.30);
        int maxX = MathHelper.floor(pocket.x + 0.30);
        int minY = MathHelper.floor(pocket.y + 0.02);
        int maxY = MathHelper.floor(pocket.y + 1.82);
        int minZ = MathHelper.floor(pocket.z - 0.30);
        int maxZ = MathHelper.floor(pocket.z + 0.30);

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    if (isHardBlocking(new BlockPos(x, y, z))) return false;
                }
            }
        }
        return true;
    }

    private boolean isHardRoofedTarget(LivingEntity living) {
        if (living == null) return false;

        Box box = living.getBoundingBox();
        double x1 = MathHelper.lerp(0.22, box.minX, box.maxX);
        double x2 = MathHelper.lerp(0.78, box.minX, box.maxX);
        double z1 = MathHelper.lerp(0.22, box.minZ, box.maxZ);
        double z2 = MathHelper.lerp(0.78, box.minZ, box.maxZ);
        double cx = (box.minX + box.maxX) * 0.5;
        double cz = (box.minZ + box.maxZ) * 0.5;
        double yTopNear = box.maxY + 0.18;
        double yTopFar = box.maxY + 0.78;

        BlockPos[] roofSamples = new BlockPos[] {
            BlockPos.ofFloored(cx, yTopNear, cz),
            BlockPos.ofFloored(x1, yTopNear, z1),
            BlockPos.ofFloored(x1, yTopNear, z2),
            BlockPos.ofFloored(x2, yTopNear, z1),
            BlockPos.ofFloored(x2, yTopNear, z2),
            BlockPos.ofFloored(cx, yTopFar, cz),
            BlockPos.ofFloored(x1, yTopFar, z1),
            BlockPos.ofFloored(x1, yTopFar, z2),
            BlockPos.ofFloored(x2, yTopFar, z1),
            BlockPos.ofFloored(x2, yTopFar, z2)
        };

        int blocked = 0;
        for (BlockPos sample : roofSamples) {
            if (isHardBlocking(sample)) blocked++;
        }
        return blocked >= 6;
    }

    private Vec3d[] buildTargetSamples(LivingEntity living) {
        Box box = living.getBoundingBox();
        double cx = (box.minX + box.maxX) * 0.5;
        double cz = (box.minZ + box.maxZ) * 0.5;
        double x1 = MathHelper.lerp(0.22, box.minX, box.maxX);
        double x2 = MathHelper.lerp(0.78, box.minX, box.maxX);
        double z1 = MathHelper.lerp(0.22, box.minZ, box.maxZ);
        double z2 = MathHelper.lerp(0.78, box.minZ, box.maxZ);
        double yFeet = box.minY + 0.10;
        double yBody = box.minY + Math.max(0.18, (box.maxY - box.minY) * 0.52);
        double yHead = box.maxY - 0.08;

        return new Vec3d[] {
            new Vec3d(cx, yFeet, cz),
            new Vec3d(cx, yBody, cz),
            new Vec3d(cx, yHead, cz),
            new Vec3d(x1, yBody, z1),
            new Vec3d(x1, yBody, z2),
            new Vec3d(x2, yBody, z1),
            new Vec3d(x2, yBody, z2)
        };
    }

    private boolean isHardPathBlocked(Vec3d start, Vec3d end) {
        if (module.client().world == null || start == null || end == null) return true;

        Vec3d delta = end.subtract(start);
        double distance = delta.length();
        if (distance < 1.0E-6) return false;

        Vec3d dir = delta.multiply(1.0 / distance);
        long lastBlock = Long.MIN_VALUE;
        for (double walked = LANE_RAY_STEP; walked < distance; walked += LANE_RAY_STEP) {
            Vec3d point = start.add(dir.multiply(walked));
            BlockPos pos = BlockPos.ofFloored(point);
            long key = pos.asLong();
            if (key == lastBlock) continue;
            lastBlock = key;
            if (isHardBlocking(pos)) return true;
        }

        return false;
    }

    private boolean isSkyVisibleAround(BlockPos center) {
        if (module.client().world == null || center == null) return false;

        if (module.client().world.isSkyVisible(center)) return true;
        if (module.client().world.isSkyVisible(center.north())) return true;
        if (module.client().world.isSkyVisible(center.south())) return true;
        if (module.client().world.isSkyVisible(center.east())) return true;
        if (module.client().world.isSkyVisible(center.west())) return true;
        return false;
    }

    private boolean isHardBlocking(BlockPos pos) {
        if (module.client().world == null) return true;
        if (module.client().world.isOutOfHeightLimit(pos.getY())) return true;

        BlockState state = module.client().world.getBlockState(pos);
        if (state.getCollisionShape(module.client().world, pos).isEmpty()) return false;
        if (isSoftNonHardBlock(state)) return false;
        return true;
    }

    private boolean isSoftNonHardBlock(BlockState state) {
        return state.isOf(Blocks.COBWEB)
            || state.isOf(Blocks.VINE)
            || state.isOf(Blocks.WEEPING_VINES)
            || state.isOf(Blocks.WEEPING_VINES_PLANT)
            || state.isOf(Blocks.TWISTING_VINES)
            || state.isOf(Blocks.TWISTING_VINES_PLANT)
            || state.isOf(Blocks.CAVE_VINES)
            || state.isOf(Blocks.CAVE_VINES_PLANT);
    }
}

