package com.devils.addon.modules.spearspoof;

import com.devils.addon.modules.SpearSpoof;
import meteordevelopment.meteorclient.settings.Setting;
import net.minecraft.block.Blocks;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;


abstract class SpearSpoofFlightFlowB extends SpearSpoofFlightFlowAExt {
    protected SpearSpoofFlightFlowB(
        SpearSpoof module,
        SpearSpoofRuntime runtime,
        SpearSpoofFlightPathfinder pathfinder,
        SpearSpoofTargetingService targeting,
        SpearSpoofCombatService combat,
        SpearSpoofDebugLogger debugLogger,
        Setting<Boolean> onlyWhileElytra,
        Setting<Boolean> attributeSwap,
        Setting<Double> minRange,
        Setting<Double> maxRange,
        Setting<Double> smallTargetRange,
        Setting<Double> horizontalSpeed,
        Setting<Double> verticalSpeed,
        Setting<Double> approachRange,
        Setting<Double> retreatRange,
        Setting<Boolean> topDownEnabled,
        Setting<Double> topDownHeight,
        Setting<Boolean> obstacleAvoidance,
        Setting<Boolean> autoRelaunch,
        Setting<Boolean> testFlyUntilDamage,
        Setting<Boolean> mode4x
    ) {
        super(
            module,
            runtime,
            pathfinder,
            targeting,
            combat,
            debugLogger,
            onlyWhileElytra,
            attributeSwap,
            minRange,
            maxRange,
            smallTargetRange,
            horizontalSpeed,
            verticalSpeed,
            approachRange,
            retreatRange,
            topDownEnabled,
            topDownHeight,
            obstacleAvoidance,
            autoRelaunch,
            testFlyUntilDamage,
            mode4x
        );
    }

    protected boolean isHardBlocking(double x, double y, double z) {
        if (module.client().world == null) return false;
        BlockPos pos = BlockPos.ofFloored(x, y, z);
        if (module.client().world.isOutOfHeightLimit(pos.getY())) return true;
        return !module.client().world.getBlockState(pos).getCollisionShape(module.client().world, pos).isEmpty();
    }

    protected boolean isSmallTarget(LivingEntity living) {
        if (living == null) return false;
        Box box = living.getBoundingBox();
        double width = Math.max(box.maxX - box.minX, box.maxZ - box.minZ);
        double height = box.maxY - box.minY;
        return width <= SMALL_TARGET_MAX_WIDTH && height <= SMALL_TARGET_MAX_HEIGHT;
    }

    protected boolean isGroundedLargeTarget(LivingEntity living) {
        if (living == null || isSmallTarget(living)) return false;
        if (living.isOnGround()) return true;
        return Math.abs(living.getVelocity().y) < 0.12;
    }

    // Keep flight lane near torso level, but do not force +1 block over grounded players.
    protected Vec3d anchorAtBodyHead(Vec3d predictedTargetPos, LivingEntity target) {
        if (predictedTargetPos == null || target == null) return predictedTargetPos;

        double height = Math.max(0.2, target.getHeight());
        double offset;

        if (isSmallTarget(target)) {
            offset = MathHelper.clamp(height * 0.52, 0.30, 0.80);
        } else if (target instanceof PlayerEntity) {
            if (target.isOnGround()) offset = MathHelper.clamp(height * 0.56, 0.88, 1.12);
            else offset = MathHelper.clamp(height * 0.60, 0.96, 1.26);
        } else {
            if (target.isOnGround()) offset = MathHelper.clamp(height * 0.44, 0.56, 0.96);
            else offset = MathHelper.clamp(height * 0.52, 0.58, 1.20);
        }

        double anchorY = predictedTargetPos.y + offset;
        return new Vec3d(predictedTargetPos.x, anchorY, predictedTargetPos.z);
    }

    protected Vec3d clampPlayerGroundLane(Vec3d targetPos, LivingEntity target) {
        if (targetPos == null || !(target instanceof PlayerEntity) || !target.isOnGround()) return targetPos;

        // For grounded PvP lock flight lane into chest/head band.
        double minY = target.getY() + PLAYER_GROUND_LANE_MIN_Y;
        double maxY = target.getY() + PLAYER_GROUND_LANE_MAX_Y;
        double y = MathHelper.clamp(targetPos.y, minY, maxY);
        return new Vec3d(targetPos.x, y, targetPos.z);
    }

    protected double resolveHorizontalCap(double baseCap) {
        double cappedBase = Math.max(baseCap, getCruiseHorizontalSpeed());
        boolean drag = isInDragState();
        if (!drag) {
            runtime.adaptiveHorizontalCap = cappedBase;
            return cappedBase;
        }

        double actual = horizontal(module.client().player.getVelocity()).length();
        if (runtime.adaptiveHorizontalCap < 0 || runtime.adaptiveHorizontalCap > cappedBase) {
            runtime.adaptiveHorizontalCap = cappedBase;
        }

        double targetCap = Math.min(cappedBase, Math.max(0.18, actual * 1.18 + 0.05));
        if (runtime.antiCheatSlowTicks > 8) {
            targetCap = Math.min(targetCap, Math.max(0.16, actual * 1.05 + 0.03));
        }

        runtime.adaptiveHorizontalCap += (targetCap - runtime.adaptiveHorizontalCap) * 0.35;
        runtime.adaptiveHorizontalCap = MathHelper.clamp(runtime.adaptiveHorizontalCap, 0.14, cappedBase);
        return runtime.adaptiveHorizontalCap;
    }

    protected double resolveVerticalCap() {
        return verticalSpeed.get();
    }

    protected double getCruiseHorizontalSpeed() {
        return Math.max(MIN_CRUISE_HORIZONTAL_SPEED, horizontalSpeed.get());
    }

    protected Vec3d enforceHorizontalSpeedFloor(Vec3d velocity, Vec3d direction, double floor) {
        double current = horizontal(velocity).length();
        if (current >= floor || floor <= 1.0E-6) return velocity;

        if (current > 1.0E-6) {
            double scale = floor / current;
            return new Vec3d(velocity.x * scale, velocity.y, velocity.z * scale);
        }

        Vec3d dir = normalizeOrFallback(horizontal(direction), module.client().player != null ? horizontal(module.client().player.getRotationVector()) : Vec3d.ZERO);
        return new Vec3d(dir.x * floor, velocity.y, dir.z * floor);
    }

    protected double applyVerticalSafety(double yVel, Vec3d currentPos, double maxVertical) {
        double floorDist = pathfinder.distanceToSolidBelow(currentPos, SAFETY_SCAN);
        double ceilingDist = pathfinder.distanceToSolidAbove(currentPos, SAFETY_SCAN);

        double downBudget = Math.max(0.0, floorDist - FLOOR_CLEARANCE - 0.35);
        double safeMaxDown = Math.min(maxVertical, downBudget * 0.8);
        yVel = Math.max(yVel, -safeMaxDown);

        if (floorDist < FLOOR_CLEARANCE + 0.35) yVel = Math.max(yVel, 0.12);
        if (ceilingDist < CEILING_CLEARANCE + 0.25) yVel = Math.min(yVel, -0.08);
        return yVel;
    }

    protected Vec3d applyEmergencyBraking(Vec3d velocity) {
        if (module.client().player == null) return velocity;

        double horizontalSpeedNow = horizontal(velocity).length();
        if (horizontalSpeedNow < 1.0E-4) return velocity;

        Vec3d pos = module.client().player.getEntityPos();
        Vec3d dir = new Vec3d(velocity.x / horizontalSpeedNow, 0.0, velocity.z / horizontalSpeedNow);
        Vec3d left = new Vec3d(-dir.z, 0.0, dir.x);
        Vec3d right = left.multiply(-1.0);

        double checkDistance = Math.max(1.5, Math.min(OBSTACLE_LOOK_AHEAD + 1.0, horizontalSpeedNow * 4.2));
        double clearAhead = probeClearDistance(pos, dir, checkDistance);
        if (clearAhead > checkDistance * 0.88) return velocity;

        double clearLeft = probeClearDistance(pos, left, checkDistance * 0.9);
        double clearRight = probeClearDistance(pos, right, checkDistance * 0.9);
        double clearUpLeft = probeClearDistance(pos, left.add(0.0, 0.42, 0.0).normalize(), checkDistance * 0.85);
        double clearUpRight = probeClearDistance(pos, right.add(0.0, 0.42, 0.0).normalize(), checkDistance * 0.85);

        Vec3d bestEscape = dir;
        double bestEscapeClear = clearAhead;
        if (clearLeft > bestEscapeClear) {
            bestEscape = left;
            bestEscapeClear = clearLeft;
        }
        if (clearRight > bestEscapeClear) {
            bestEscape = right;
            bestEscapeClear = clearRight;
        }
        if (clearUpLeft > bestEscapeClear) {
            bestEscape = left.add(0.0, 0.42, 0.0).normalize();
            bestEscapeClear = clearUpLeft;
        }
        if (clearUpRight > bestEscapeClear) {
            bestEscape = right.add(0.0, 0.42, 0.0).normalize();
            bestEscapeClear = clearUpRight;
        }

        double speedFactor = MathHelper.clamp(clearAhead / checkDistance, 0.35, 1.0);
        double desiredHorizontal = horizontalSpeedNow * speedFactor;
        if (bestEscape != dir && bestEscapeClear > clearAhead + 0.35) {
            double steer = MathHelper.clamp((checkDistance - clearAhead) / checkDistance, 0.2, 0.72);
            Vec3d blended = dir.multiply(1.0 - steer).add(bestEscape.multiply(steer)).normalize();
            desiredHorizontal = horizontalSpeedNow * Math.max(0.45, speedFactor);
            velocity = new Vec3d(blended.x * desiredHorizontal, velocity.y, blended.z * desiredHorizontal);
        } else {
            double scale = Math.max(0.35, speedFactor);
            velocity = new Vec3d(velocity.x * scale, velocity.y, velocity.z * scale);
        }

        double vx = velocity.x;
        double vz = velocity.z;
        double vy = velocity.y;
        double floorDist = pathfinder.distanceToSolidBelow(pos, SAFETY_SCAN);
        double ceilingDist = pathfinder.distanceToSolidAbove(pos, SAFETY_SCAN);

        if (clearAhead < 0.8) {
            vx *= 0.55;
            vz *= 0.55;
            vy = Math.max(vy, 0.24);
            runtime.stuckTicks = Math.max(runtime.stuckTicks, SpearSpoofFlightPathSearch.STUCK_REPATH_TICKS);
        } else if (clearAhead < 1.35) {
            vy = Math.max(vy, 0.14);
        }

        if (floorDist < FLOOR_CLEARANCE + 0.45) vy = Math.max(vy, 0.10);
        if (ceilingDist < CEILING_CLEARANCE + 0.2) vy = Math.min(vy, 0.05);
        return new Vec3d(vx, vy, vz);
    }

    protected Vec3d computeLocalAvoidance(Vec3d steerTarget, Vec3d baseVelocity, double horizontalCap, double maxVertical) {
        if (module.client().player == null) return Vec3d.ZERO;

        Vec3d pos = module.client().player.getEntityPos();
        Vec3d avoid = Vec3d.ZERO;
        double floorDist = pathfinder.distanceToSolidBelow(pos, SAFETY_SCAN);
        double ceilingDist = pathfinder.distanceToSolidAbove(pos, SAFETY_SCAN);

        if (floorDist < FLOOR_CLEARANCE) {
            double push = (FLOOR_CLEARANCE - floorDist) * 0.75;
            avoid = avoid.add(0.0, Math.min(maxVertical, push), 0.0);
        }
        if (ceilingDist < CEILING_CLEARANCE) {
            double push = (CEILING_CLEARANCE - ceilingDist) * 0.75;
            avoid = avoid.add(0.0, -Math.min(maxVertical, push), 0.0);
        }

        Vec3d heading = steerTarget.subtract(pos);
        double headingLen = horizontal(heading).length();
        if (headingLen < 1.0E-4) {
            heading = new Vec3d(baseVelocity.x, 0.0, baseVelocity.z);
            headingLen = horizontal(heading).length();
        }
        if (headingLen <= 1.0E-4) return avoid;

        Vec3d forward = new Vec3d(heading.x / headingLen, 0.0, heading.z / headingLen);
        double frontClear = probeClearDistance(pos, forward, OBSTACLE_LOOK_AHEAD);
        if (frontClear >= OBSTACLE_LOOK_AHEAD - 0.15) return avoid;

        Vec3d left = new Vec3d(-forward.z, 0.0, forward.x);
        Vec3d right = left.multiply(-1.0);
        Vec3d targetDir = normalizeOrFallback(steerTarget.subtract(pos), forward);
        Vec3d[] candidates = new Vec3d[] {
            left,
            right,
            left.add(forward.multiply(0.45)).normalize(),
            right.add(forward.multiply(0.45)).normalize(),
            left.add(0.0, 0.7, 0.0).normalize(),
            right.add(0.0, 0.7, 0.0).normalize(),
            new Vec3d(0.0, 1.0, 0.0),
            left.add(forward.multiply(-0.35)).normalize(),
            right.add(forward.multiply(-0.35)).normalize()
        };

        Vec3d bestDir = left;
        double bestScore = Double.NEGATIVE_INFINITY;
        double bestSpace = 0.0;
        for (Vec3d candidate : candidates) {
            double space = probeClearDistance(pos, candidate, OBSTACLE_LOOK_AHEAD);
            double align = candidate.dotProduct(targetDir);
            Vec3d sample = pos.add(candidate.multiply(Math.max(0.9, space * 0.65)));
            double sampleFloor = pathfinder.distanceToSolidBelow(sample, Math.max(6, SAFETY_SCAN / 2));
            double sampleCeiling = pathfinder.distanceToSolidAbove(sample, Math.max(6, SAFETY_SCAN / 2));
            int sideOpenings = pathfinder.countLateralOpenings(sample, 0.9);
            double score = space * 1.55 + align * 1.05 + sideOpenings * 0.3;
            if (sampleFloor < FLOOR_CLEARANCE) score -= (FLOOR_CLEARANCE - sampleFloor) * 1.4;
            if (sampleCeiling < CEILING_CLEARANCE) score -= (CEILING_CLEARANCE - sampleCeiling) * 1.4;
            if (candidate.y > 0.0 && floorDist < FLOOR_CLEARANCE + 0.7) score += 1.0;
            if (candidate.y > 0.0 && ceilingDist < CEILING_CLEARANCE + 0.7) score -= 0.8;
            if (score > bestScore) {
                bestScore = score;
                bestDir = candidate;
                bestSpace = space;
            }
        }

        double urgency = Math.max(0.0, 1.0 - bestSpace / OBSTACLE_LOOK_AHEAD);
        double sideSpeed = horizontalCap * (0.45 + urgency) * AVOIDANCE_STRENGTH;
        return avoid.add(bestDir.multiply(sideSpeed));
    }

    protected double probeClearDistance(Vec3d start, Vec3d dir, double maxDistance) {
        double step = Math.max(0.25, COLLISION_STEP);
        for (double distance = step; distance <= maxDistance; distance += step) {
            if (!pathfinder.hasPlayerClearance(start.add(dir.multiply(distance)))) return distance - step;
        }
        return maxDistance;
    }

    protected boolean isInDragState() {
        return module.client().player != null && isInsideSoftDragBlock(module.client().player.getBoundingBox());
    }

    protected boolean isInsideSoftDragBlock(Box box) {
        int minX = MathHelper.floor(box.minX + 1.0E-4);
        int maxX = MathHelper.floor(box.maxX - 1.0E-4);
        int minY = MathHelper.floor(box.minY + 1.0E-4);
        int maxY = MathHelper.floor(box.maxY - 1.0E-4);
        int minZ = MathHelper.floor(box.minZ + 1.0E-4);
        int maxZ = MathHelper.floor(box.maxZ - 1.0E-4);

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    if (isSoftDragBlock(new BlockPos(x, y, z))) return true;
                }
            }
        }
        return false;
    }

    protected boolean isSoftDragBlock(BlockPos pos) {
        var state = module.client().world.getBlockState(pos);
        return state.isOf(Blocks.COBWEB)
            || state.isOf(Blocks.VINE)
            || state.isOf(Blocks.WEEPING_VINES)
            || state.isOf(Blocks.WEEPING_VINES_PLANT)
            || state.isOf(Blocks.TWISTING_VINES)
            || state.isOf(Blocks.TWISTING_VINES_PLANT)
            || state.isOf(Blocks.CAVE_VINES)
            || state.isOf(Blocks.CAVE_VINES_PLANT);
    }

    protected void tickStuckState(long now, Vec3d playerPos, Vec3d desiredVelocity, Vec3d targetPos, double engageMin) {
        if (runtime.lastStuckSampleMs <= 0L) {
            runtime.lastStuckSampleMs = now;
            runtime.lastStuckSamplePos = playerPos;
            runtime.stuckTicks = 0;
            return;
        }

        if (now - runtime.lastStuckSampleMs < STUCK_SAMPLE_MS) return;

        double moved = runtime.lastStuckSamplePos.distanceTo(playerPos);
        double desiredHorizontal = horizontal(desiredVelocity).length();
        double targetDistance = horizontal(playerPos.subtract(targetPos)).length();
        boolean tryingToMove = desiredHorizontal > 0.85;
        boolean crampedNearTarget = targetDistance < engageMin + 0.22;
        boolean gliding = module.client().player != null && module.client().player.isGliding();
        boolean blocked = tryingToMove && gliding && moved <= STUCK_MOVE_EPS && !crampedNearTarget;

        if (blocked) runtime.stuckTicks++;
        else runtime.stuckTicks = Math.max(0, runtime.stuckTicks - 1);

        if (runtime.stuckTicks >= STUCK_TICKS_LIMIT) {
            runtime.unstuckUntilMs = now + UNSTUCK_BURST_MS;
            runtime.stuckTicks = 0;
            runtime.beginReset(now, 280L);
        } else if (runtime.unstuckUntilMs > 0L && now >= runtime.unstuckUntilMs) {
            runtime.unstuckUntilMs = 0L;
        }

        runtime.lastStuckSampleMs = now;
        runtime.lastStuckSamplePos = playerPos;
    }

    protected void ensureElytraRelaunch() {
        if (!onlyWhileElytra.get()) return;
        if (module.client().player.isGliding()) {
            runtime.relaunchGroundTicks = 0;
            runtime.relaunchJumpTicks = 0;
            return;
        }
        if (!hasUsableElytra()) return;

        // AutoWasp parity: in liquids we retry START_FALL_FLYING much faster.
        boolean inLiquid = module.client().player.isTouchingWater() || module.client().player.isInLava();

        runtime.relaunchJumpTicks++;
        if (module.client().player.isOnGround()) {
            module.client().player.jump();
            return;
        }

        int reopenDelay = inLiquid ? 1 : 4;
        if (runtime.relaunchJumpTicks < reopenDelay) return;
        runtime.relaunchJumpTicks = 0;

        module.client().player.setJumping(false);
        module.client().player.setSprinting(true);

        // AutoWasp-style relaunch: no extra packet throttle, especially important while touching liquids.
        if (module.client().player.networkHandler != null) {
            module.client().player.networkHandler.sendPacket(
                new ClientCommandC2SPacket(module.client().player, ClientCommandC2SPacket.Mode.START_FALL_FLYING)
            );
        }
    }

    protected boolean hasUsableElytra() {
        ItemStack chest = module.client().player.getEquippedStack(EquipmentSlot.CHEST);
        if (chest == null || chest.isEmpty()) return false;
        if (!chest.contains(DataComponentTypes.GLIDER)) return false;
        return !chest.isDamageable() || chest.getDamage() < chest.getMaxDamage() - 1;
    }

    protected Vec3d applyDimensionFlightLimit(Vec3d velocity, Vec3d currentPos) {
        if (module.client().world == null || module.client().world.getRegistryKey() != World.NETHER) return velocity;

        double y = currentPos.y;
        double vy = velocity.y;
        if (y >= NETHER_MAX_FLIGHT_Y) vy = Math.min(vy, -0.10);
        else if (y >= NETHER_MAX_FLIGHT_Y - 0.75) vy = Math.min(vy, 0.0);
        else if (y >= NETHER_MAX_FLIGHT_Y - 1.5) vy = Math.min(vy, 0.08);
        return new Vec3d(velocity.x, vy, velocity.z);
    }

    protected double distanceFromPointToHitbox(Vec3d point, LivingEntity living) {
        Box hitbox = living.getBoundingBox();
        double x = MathHelper.clamp(point.x, hitbox.minX, hitbox.maxX);
        double y = MathHelper.clamp(point.y, hitbox.minY, hitbox.maxY);
        double z = MathHelper.clamp(point.z, hitbox.minZ, hitbox.maxZ);
        return point.distanceTo(new Vec3d(x, y, z));
    }

    protected static Vec3d horizontal(Vec3d value) {
        return new Vec3d(value.x, 0.0, value.z);
    }

    protected static Vec3d normalizeOrFallback(Vec3d value, Vec3d fallback) {
        if (value != null && value.lengthSquared() > 1.0E-6) return value.normalize();
        if (fallback != null && fallback.lengthSquared() > 1.0E-6) return fallback.normalize();
        return Vec3d.ZERO;
    }

    protected static String format2(double value) {
        return String.format(java.util.Locale.US, "%.2f", value);
    }
}
