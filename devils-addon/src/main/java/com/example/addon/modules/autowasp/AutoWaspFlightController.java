package com.example.addon.modules.autowasp;

import com.example.addon.modules.AutoWasp;
import meteordevelopment.meteorclient.events.entity.player.PlayerMoveEvent;
import meteordevelopment.meteorclient.mixininterface.IVec3d;
import net.minecraft.block.Blocks;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

public final class AutoWaspFlightController {
    public static final int STUCK_REPATH_TICKS = 12;

    private static final double FLOOR_CLEARANCE = 2.2;
    private static final double CEILING_CLEARANCE = 1.5;
    private static final int SAFETY_SCAN = 14;
    private static final double OBSTACLE_LOOK_AHEAD = 7.5;
    private static final double AVOIDANCE_STRENGTH = 0.85;
    private static final double COLLISION_STEP = 0.55;
    private static final double NETHER_MAX_FLIGHT_Y = 123.0;

    private final AutoWasp module;
    private final AutoWaspPathfinder pathfinder;

    private int jumpTimer;
    private boolean incrementJumpTimer;
    private Vec3d lastPlayerPos;
    private int stuckTicks;
    private double adaptiveHorizontalCap = -1;
    private int antiCheatSlowTicks;

    public AutoWaspFlightController(AutoWasp module, AutoWaspPathfinder pathfinder) {
        this.module = module;
        this.pathfinder = pathfinder;
    }

    public void onDeactivate() {
        jumpTimer = 0;
        incrementJumpTimer = false;
        lastPlayerPos = null;
        stuckTicks = 0;
        adaptiveHorizontalCap = -1;
        antiCheatSlowTicks = 0;
    }

    public void resetNavigationState() {
        jumpTimer = 0;
        incrementJumpTimer = false;
        lastPlayerPos = module.client().player != null ? module.client().player.getEntityPos() : null;
        stuckTicks = 0;
        adaptiveHorizontalCap = -1;
        antiCheatSlowTicks = 0;
    }

    public void resetFlightTracking() {
        jumpTimer = 0;
        incrementJumpTimer = false;
        pathfinder.resetPathState();
        stuckTicks = 0;
        adaptiveHorizontalCap = -1;
        antiCheatSlowTicks = 0;
        lastPlayerPos = module.client().player != null ? module.client().player.getEntityPos() : null;
    }

    public void clearStuckState() {
        stuckTicks = 0;
        lastPlayerPos = module.client().player != null ? module.client().player.getEntityPos() : null;
    }

    public void handleNotGliding() {
        if (module.client().player == null || module.client().world == null) return;

        if (incrementJumpTimer) jumpTimer++;
        if (!incrementJumpTimer) incrementJumpTimer = true;

        boolean inLiquid = module.client().player.isTouchingWater() || module.client().player.isInLava();
        if (module.client().player.isOnGround() && incrementJumpTimer) {
            module.client().player.jump();
            return;
        }

        int reopenDelay = inLiquid ? 1 : 4;
        if (jumpTimer >= reopenDelay) {
            jumpTimer = 0;
            module.client().player.setJumping(false);
            module.client().player.setSprinting(true);
            if (module.client().getNetworkHandler() != null) {
                module.client().getNetworkHandler().sendPacket(new ClientCommandC2SPacket(module.client().player, ClientCommandC2SPacket.Mode.START_FALL_FLYING));
            }
        }

        stuckTicks = 0;
        lastPlayerPos = module.client().player.getEntityPos();
        adaptiveHorizontalCap = -1;
        antiCheatSlowTicks = 0;
    }

    public void onGlidingTick() {
        incrementJumpTimer = false;
        jumpTimer = 0;
        updateStuckState();
    }

    public int getStuckTicks() {
        return stuckTicks;
    }

    public void onMove(PlayerMoveEvent event, Vec3d steerTarget) {
        if (module.client().player == null || module.client().world == null || steerTarget == null) return;

        Vec3d safeTarget = pathfinder.adjustToSafeCorridor(steerTarget, module.client().player.getEntityPos());
        Vec3d toTarget = safeTarget.subtract(module.client().player.getEntityPos());
        double horizontal = Math.sqrt(toTarget.x * toTarget.x + toTarget.z * toTarget.z);

        double maxHorizontal = resolveHorizontalCap();
        double maxVertical = resolveVerticalCap();
        double xVel = 0;
        double zVel = 0;
        if (horizontal > 1.0E-5) {
            double speed = Math.min(maxHorizontal, horizontal);
            xVel = (toTarget.x / horizontal) * speed;
            zVel = (toTarget.z / horizontal) * speed;
        }

        double yVel = MathHelper.clamp(toTarget.y, -maxVertical, maxVertical);
        yVel = applyVerticalSafety(yVel, module.client().player.getEntityPos());

        Vec3d base = new Vec3d(xVel, yVel, zVel);
        Vec3d avoid = computeLocalAvoidance(safeTarget, base, maxHorizontal);
        Vec3d velocity = applyDimensionFlightLimit(applyEmergencyBraking(base.add(avoid)), module.client().player.getEntityPos());

        double horizontalVelocity = Math.sqrt(velocity.x * velocity.x + velocity.z * velocity.z);
        if (horizontalVelocity > maxHorizontal && horizontalVelocity > 1.0E-6) {
            double scale = maxHorizontal / horizontalVelocity;
            velocity = new Vec3d(velocity.x * scale, velocity.y, velocity.z * scale);
        }

        double actualHoriz = Math.sqrt(
            module.client().player.getVelocity().x * module.client().player.getVelocity().x
                + module.client().player.getVelocity().z * module.client().player.getVelocity().z
        );
        boolean drag = isInDragState();
        boolean serverRejected = horizontalVelocity > 0.30 && actualHoriz < horizontalVelocity * 0.45;
        if (drag && serverRejected) antiCheatSlowTicks = Math.min(20, antiCheatSlowTicks + 1);
        else antiCheatSlowTicks = Math.max(0, antiCheatSlowTicks - 2);

        velocity = new Vec3d(velocity.x, MathHelper.clamp(velocity.y, -maxVertical, maxVertical), velocity.z);
        ((IVec3d) event.movement).meteor$set(velocity.x, velocity.y, velocity.z);
    }

    private void updateStuckState() {
        if (module.client().player == null) return;

        Vec3d now = module.client().player.getEntityPos();
        if (lastPlayerPos == null) {
            lastPlayerPos = now;
            stuckTicks = 0;
            return;
        }

        if (now.squaredDistanceTo(lastPlayerPos) < 0.03) stuckTicks++;
        else stuckTicks = 0;
        lastPlayerPos = now;
    }

    private double resolveHorizontalCap() {
        double baseCap = module.horizontalSpeedValue();
        boolean drag = isInDragState();
        if (!drag) {
            adaptiveHorizontalCap = baseCap;
            return baseCap;
        }

        double actual = Math.sqrt(
            module.client().player.getVelocity().x * module.client().player.getVelocity().x
                + module.client().player.getVelocity().z * module.client().player.getVelocity().z
        );
        if (adaptiveHorizontalCap < 0 || adaptiveHorizontalCap > baseCap) adaptiveHorizontalCap = baseCap;

        double targetCap = Math.min(baseCap, Math.max(0.18, actual * 1.18 + 0.05));
        if (antiCheatSlowTicks > 8) targetCap = Math.min(targetCap, Math.max(0.16, actual * 1.05 + 0.03));

        adaptiveHorizontalCap += (targetCap - adaptiveHorizontalCap) * 0.35;
        adaptiveHorizontalCap = MathHelper.clamp(adaptiveHorizontalCap, 0.14, baseCap);
        return adaptiveHorizontalCap;
    }

    private double resolveVerticalCap() {
        return module.verticalSpeedValue();
    }

    private boolean isInDragState() {
        return module.client().player != null && isInsideSoftDragBlock(module.client().player.getBoundingBox());
    }

    private boolean isInsideSoftDragBlock(Box box) {
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

    private boolean isSoftDragBlock(BlockPos pos) {
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

    private double applyVerticalSafety(double yVel, Vec3d currentPos) {
        double floorDist = pathfinder.distanceToSolidBelow(currentPos, SAFETY_SCAN);
        double ceilingDist = pathfinder.distanceToSolidAbove(currentPos, SAFETY_SCAN);

        double downBudget = Math.max(0.0, floorDist - FLOOR_CLEARANCE - 0.35);
        double safeMaxDown = Math.min(module.verticalSpeedValue(), downBudget * 0.8);
        yVel = Math.max(yVel, -safeMaxDown);

        if (floorDist < FLOOR_CLEARANCE + 0.35) yVel = Math.max(yVel, 0.12);
        if (ceilingDist < CEILING_CLEARANCE + 0.25) yVel = Math.min(yVel, -0.08);
        return yVel;
    }

    private Vec3d applyDimensionFlightLimit(Vec3d velocity, Vec3d currentPos) {
        if (!pathfinder.hasNetherFlightCap()) return velocity;

        double y = currentPos.y;
        double vy = velocity.y;
        if (y >= NETHER_MAX_FLIGHT_Y) vy = Math.min(vy, -0.10);
        else if (y >= NETHER_MAX_FLIGHT_Y - 0.75) vy = Math.min(vy, 0.0);
        else if (y >= NETHER_MAX_FLIGHT_Y - 1.5) vy = Math.min(vy, 0.08);

        return new Vec3d(velocity.x, vy, velocity.z);
    }

    private Vec3d applyEmergencyBraking(Vec3d velocity) {
        if (module.client().player == null) return velocity;

        double horizontal = Math.sqrt(velocity.x * velocity.x + velocity.z * velocity.z);
        if (horizontal < 1.0E-4) return velocity;

        Vec3d pos = module.client().player.getEntityPos();
        Vec3d dir = new Vec3d(velocity.x / horizontal, 0, velocity.z / horizontal);
        Vec3d left = new Vec3d(-dir.z, 0, dir.x);
        Vec3d right = left.multiply(-1);

        double checkDistance = Math.max(1.5, Math.min(OBSTACLE_LOOK_AHEAD + 1.0, horizontal * 4.2));
        double clearAhead = probeClearDistance(pos, dir, checkDistance);
        if (clearAhead > checkDistance * 0.88) return velocity;

        double clearLeft = probeClearDistance(pos, left, checkDistance * 0.9);
        double clearRight = probeClearDistance(pos, right, checkDistance * 0.9);
        double clearUpLeft = probeClearDistance(pos, left.add(0, 0.42, 0).normalize(), checkDistance * 0.85);
        double clearUpRight = probeClearDistance(pos, right.add(0, 0.42, 0).normalize(), checkDistance * 0.85);

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
            bestEscape = left.add(0, 0.42, 0).normalize();
            bestEscapeClear = clearUpLeft;
        }
        if (clearUpRight > bestEscapeClear) {
            bestEscape = right.add(0, 0.42, 0).normalize();
            bestEscapeClear = clearUpRight;
        }

        double speedFactor = MathHelper.clamp(clearAhead / checkDistance, 0.35, 1.0);
        double desiredHorizontal = horizontal * speedFactor;
        if (bestEscape != dir && bestEscapeClear > clearAhead + 0.35) {
            double steer = MathHelper.clamp((checkDistance - clearAhead) / checkDistance, 0.2, 0.72);
            Vec3d blended = dir.multiply(1.0 - steer).add(bestEscape.multiply(steer)).normalize();
            desiredHorizontal = horizontal * Math.max(0.45, speedFactor);
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
            stuckTicks = Math.max(stuckTicks, STUCK_REPATH_TICKS);
        } else if (clearAhead < 1.35) {
            vy = Math.max(vy, 0.14);
        }

        if (floorDist < FLOOR_CLEARANCE + 0.45) vy = Math.max(vy, 0.10);
        if (ceilingDist < CEILING_CLEARANCE + 0.2) vy = Math.min(vy, 0.05);
        return new Vec3d(vx, vy, vz);
    }

    private Vec3d computeLocalAvoidance(Vec3d steerTarget, Vec3d baseVelocity, double horizontalCap) {
        if (module.client().player == null) return Vec3d.ZERO;

        Vec3d pos = module.client().player.getEntityPos();
        Vec3d avoid = Vec3d.ZERO;
        double floorDist = pathfinder.distanceToSolidBelow(pos, SAFETY_SCAN);
        double ceilingDist = pathfinder.distanceToSolidAbove(pos, SAFETY_SCAN);

        if (floorDist < FLOOR_CLEARANCE) {
            double push = (FLOOR_CLEARANCE - floorDist) * 0.75;
            avoid = avoid.add(0, Math.min(module.verticalSpeedValue(), push), 0);
        }
        if (ceilingDist < CEILING_CLEARANCE) {
            double push = (CEILING_CLEARANCE - ceilingDist) * 0.75;
            avoid = avoid.add(0, -Math.min(module.verticalSpeedValue(), push), 0);
        }

        Vec3d heading = steerTarget.subtract(pos);
        double headingLen = Math.sqrt(heading.x * heading.x + heading.z * heading.z);
        if (headingLen < 1.0E-4) {
            heading = new Vec3d(baseVelocity.x, 0, baseVelocity.z);
            headingLen = Math.sqrt(heading.x * heading.x + heading.z * heading.z);
        }
        if (headingLen <= 1.0E-4) return avoid;

        Vec3d forward = new Vec3d(heading.x / headingLen, 0, heading.z / headingLen);
        double lookAhead = OBSTACLE_LOOK_AHEAD;
        double frontClear = probeClearDistance(pos, forward, lookAhead);
        if (frontClear >= lookAhead - 0.15) return avoid;

        Vec3d left = new Vec3d(-forward.z, 0, forward.x);
        Vec3d right = left.multiply(-1);
        Vec3d targetDir = steerTarget.subtract(pos).normalize();
        Vec3d[] candidates = {
            left,
            right,
            left.add(forward.multiply(0.45)).normalize(),
            right.add(forward.multiply(0.45)).normalize(),
            left.add(0, 0.7, 0).normalize(),
            right.add(0, 0.7, 0).normalize(),
            new Vec3d(0, 1, 0),
            left.add(forward.multiply(-0.35)).normalize(),
            right.add(forward.multiply(-0.35)).normalize()
        };

        Vec3d bestDir = left;
        double bestScore = Double.NEGATIVE_INFINITY;
        double bestSpace = 0;
        for (Vec3d candidate : candidates) {
            double space = probeClearDistance(pos, candidate, lookAhead);
            double align = candidate.dotProduct(targetDir);
            Vec3d sample = pos.add(candidate.multiply(Math.max(0.9, space * 0.65)));
            double sampleFloor = pathfinder.distanceToSolidBelow(sample, Math.max(6, SAFETY_SCAN / 2));
            double sampleCeiling = pathfinder.distanceToSolidAbove(sample, Math.max(6, SAFETY_SCAN / 2));
            int sideOpenings = pathfinder.countLateralOpenings(sample, 0.9);
            double score = space * 1.55 + align * 1.05 + sideOpenings * 0.3;
            if (sampleFloor < FLOOR_CLEARANCE) score -= (FLOOR_CLEARANCE - sampleFloor) * 1.4;
            if (sampleCeiling < CEILING_CLEARANCE) score -= (CEILING_CLEARANCE - sampleCeiling) * 1.4;
            if (candidate.y > 0 && floorDist < FLOOR_CLEARANCE + 0.7) score += 1.0;
            if (candidate.y > 0 && ceilingDist < CEILING_CLEARANCE + 0.7) score -= 0.8;
            if (score > bestScore) {
                bestScore = score;
                bestDir = candidate;
                bestSpace = space;
            }
        }

        double urgency = Math.max(0.0, 1.0 - bestSpace / lookAhead);
        double sideSpeed = horizontalCap * (0.45 + urgency) * AVOIDANCE_STRENGTH;
        return avoid.add(bestDir.multiply(sideSpeed));
    }

    private double probeClearDistance(Vec3d start, Vec3d dir, double maxDistance) {
        double step = Math.max(0.25, COLLISION_STEP);
        for (double distance = step; distance <= maxDistance; distance += step) {
            if (!pathfinder.hasPlayerClearance(start.add(dir.multiply(distance)))) return distance - step;
        }
        return maxDistance;
    }
}


