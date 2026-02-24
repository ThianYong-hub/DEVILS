package com.example.addon.modules;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.entity.player.PlayerMoveEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.mixininterface.IVec3d;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.entity.SortPriority;
import meteordevelopment.meteorclient.utils.entity.TargetUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.block.Blocks;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.network.packet.s2c.common.DisconnectS2CPacket;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

public class AutoWasp extends Module {
    private static final Direction[] CARDINAL_DIRECTIONS = new Direction[] {
        Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST
    };

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgPathfinding = settings.createGroup("Pathfinding");
    private final SettingGroup sgSafety = settings.createGroup("Flight Safety");

    private final Setting<Double> horizontalSpeed = sgGeneral.add(new DoubleSetting.Builder()
        .name("horizontal-speed")
        .description("Horizontal elytra speed.")
        .defaultValue(2.0)
        .build()
    );

    private final Setting<Double> verticalSpeed = sgGeneral.add(new DoubleSetting.Builder()
        .name("vertical-speed")
        .description("Vertical elytra speed.")
        .defaultValue(3.0)
        .build()
    );

    private final Setting<Boolean> avoidLanding = sgGeneral.add(new BoolSetting.Builder()
        .name("avoid-landing")
        .description("Will try to avoid landing if your target is on the ground.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> predictMovement = sgGeneral.add(new BoolSetting.Builder()
        .name("predict-movement")
        .description("Tries to predict the targets position according to their movement.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> onlyFriends = sgGeneral.add(new BoolSetting.Builder()
        .name("only-friends")
        .description("Will only follow friends.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> friendFilter = sgGeneral.add(new BoolSetting.Builder()
        .name("friend-filter")
        .description("When enabled: onlyFriends=true -> friends, onlyFriends=false -> enemies. When disabled: original logic.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Action> action = sgGeneral.add(new EnumSetting.Builder<Action>()
        .name("action-on-target-loss")
        .description("What to do if you lose the target.")
        .defaultValue(Action.TOGGLE)
        .build()
    );

    private final Setting<Vector3d> offset = sgGeneral.add(new Vector3dSetting.Builder()
        .name("offset")
        .description("How many blocks offset to wasp at from the target.")
        .defaultValue(0, 0, 0)
        .build()
    );

    private final Setting<Boolean> pathfindingEnabled = sgPathfinding.add(new BoolSetting.Builder()
        .name("pathfinding")
        .description("Uses A* pathfinding to route around obstacles.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> pathUpdateTicks = sgPathfinding.add(new IntSetting.Builder()
        .name("path-update-interval")
        .description("How often to recompute path (ticks).")
        .defaultValue(14)
        .min(3)
        .sliderRange(3, 40)
        .visible(pathfindingEnabled::get)
        .build()
    );

    private final Setting<Integer> maxNodes = sgPathfinding.add(new IntSetting.Builder()
        .name("max-nodes")
        .description("Maximum A* nodes to explore.")
        .defaultValue(3500)
        .min(500)
        .sliderRange(1000, 12000)
        .visible(pathfindingEnabled::get)
        .build()
    );

    private final Setting<Integer> pathResolution = sgPathfinding.add(new IntSetting.Builder()
        .name("path-resolution")
        .description("Path grid resolution. 1 is safer near bedrock.")
        .defaultValue(1)
        .min(1)
        .max(3)
        .sliderRange(1, 2)
        .visible(pathfindingEnabled::get)
        .build()
    );

    private final Setting<Double> waypointReach = sgPathfinding.add(new DoubleSetting.Builder()
        .name("waypoint-reach")
        .description("Distance at which waypoint is considered reached.")
        .defaultValue(2.0)
        .min(0.8)
        .sliderRange(1.0, 4.0)
        .visible(pathfindingEnabled::get)
        .build()
    );

    private final Setting<Integer> stuckRepathTicks = sgPathfinding.add(new IntSetting.Builder()
        .name("stuck-repath-ticks")
        .description("Repath after this many low-movement ticks.")
        .defaultValue(12)
        .min(4)
        .sliderRange(6, 30)
        .visible(pathfindingEnabled::get)
        .build()
    );

    private final Setting<Double> floorClearance = sgSafety.add(new DoubleSetting.Builder()
        .name("floor-clearance")
        .description("Preferred minimum distance to floor.")
        .defaultValue(2.2)
        .min(1.0)
        .sliderRange(1.2, 4.0)
        .build()
    );

    private final Setting<Double> ceilingClearance = sgSafety.add(new DoubleSetting.Builder()
        .name("ceiling-clearance")
        .description("Preferred minimum distance to ceiling.")
        .defaultValue(1.5)
        .min(0.75)
        .sliderRange(1.0, 3.0)
        .build()
    );

    private final Setting<Integer> safetyScan = sgSafety.add(new IntSetting.Builder()
        .name("safety-scan")
        .description("Vertical scan distance for floor/ceiling checks.")
        .defaultValue(14)
        .min(6)
        .sliderRange(8, 28)
        .build()
    );

    private final Setting<Double> obstacleLookAhead = sgSafety.add(new DoubleSetting.Builder()
        .name("obstacle-lookahead")
        .description("How far ahead to probe for blocked flight.")
        .defaultValue(7.5)
        .min(2.5)
        .sliderRange(4.0, 14.0)
        .build()
    );

    private final Setting<Double> avoidanceStrength = sgSafety.add(new DoubleSetting.Builder()
        .name("avoidance-strength")
        .description("Strength of local obstacle steering.")
        .defaultValue(0.85)
        .min(0.0)
        .sliderRange(0.2, 1.8)
        .build()
    );

    private final Setting<Double> collisionStep = sgSafety.add(new DoubleSetting.Builder()
        .name("collision-step")
        .description("Collision sample step for ray checks.")
        .defaultValue(0.55)
        .min(0.25)
        .sliderRange(0.3, 1.2)
        .build()
    );

    public PlayerEntity target;
    private int jumpTimer = 0;
    private boolean incrementJumpTimer = false;

    private List<Vec3d> currentPath = new ArrayList<>();
    private int currentWaypointIndex = 0;
    private int pathTimer = 0;
    private Vec3d lastPathTarget = null;
    private Vec3d lastPlayerPos = null;
    private int stuckTicks = 0;
    private double adaptiveHorizontalCap = -1;
    private int antiCheatSlowTicks = 0;
    private int cacheSweepTicks = 0;

    private final Map<Long, Boolean> clearanceCache = new HashMap<>();
    private final Map<Long, Double> floorDistanceCache = new HashMap<>();
    private final Map<Long, Double> ceilingDistanceCache = new HashMap<>();

    public AutoWasp() {
        super(AddonTemplate.CATEGORY, "auto-wasp", "Wasp follow with obstacle-aware elytra routing.");
    }

    @Override
    public void onActivate() {
        if (mc.player == null || mc.world == null) return;

        if (target == null || target.isRemoved()) {
            findTarget();
            
            if (target == null) {
                error("No valid targets.");
                toggle();
                return;
            } else {
                info("Target set to: " + target.getName().getString());
            }
        }

        jumpTimer = 0;
        incrementJumpTimer = false;
        currentPath.clear();
        currentWaypointIndex = 0;
        pathTimer = 0;
        lastPathTarget = null;
        lastPlayerPos = mc.player.getPos();
        stuckTicks = 0;
        adaptiveHorizontalCap = -1;
        antiCheatSlowTicks = 0;
        cacheSweepTicks = 0;
        clearCaches();
    }

    @Override
    public void onDeactivate() {
        target = null;
        currentPath.clear();
        currentWaypointIndex = 0;
        pathTimer = 0;
        lastPathTarget = null;
        lastPlayerPos = null;
        stuckTicks = 0;
        adaptiveHorizontalCap = -1;
        antiCheatSlowTicks = 0;
        cacheSweepTicks = 0;
        clearCaches();
    }

    private void findTarget() {
        target = (PlayerEntity) TargetUtils.get(entity -> {
            if (!(entity instanceof PlayerEntity player) || entity == mc.player) return false;
            if (player.isDead() || player.getHealth() <= 0) return false;
            
            boolean isFriend = Friends.get().isFriend(player);
            
            if (friendFilter.get()) {
                // Новая логика:
                // Если onlyFriends включен -> выбираем только друзей
                // Если onlyFriends выключен -> выбираем только НЕ-друзей
                if (onlyFriends.get()) {
                    return isFriend; // Только друзья
                } else {
                    return !isFriend; // Только не-друзья
                }
            } else {
                // Оригинальная логика:
                // Если onlyFriends включен -> выбираем только друзей
                // Если onlyFriends выключен -> выбираем всех (и друзей, и не-друзей)
                if (onlyFriends.get()) {
                    return isFriend; // Только друзья
                } else {
                    return true; // Все игроки (оригинальное поведение)
                }
            }
        }, SortPriority.LowestDistance);
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;
        if (++cacheSweepTicks >= 8) {
            cacheSweepTicks = 0;
            clearCaches();
        }

        if (target == null || target.isRemoved() || target.isDead() || target.getHealth() <= 0) {
            handleTargetLoss();
            return;
        }

        boolean isFriend = Friends.get().isFriend(target);
        boolean shouldIgnore;

        if (friendFilter.get()) {
            shouldIgnore = (onlyFriends.get() && !isFriend) || (!onlyFriends.get() && isFriend);
        } else {
            shouldIgnore = onlyFriends.get() && !isFriend;
        }

        if (shouldIgnore) {
            warning("Current target no longer matches criteria, finding new target...");
            findTarget();

            if (target == null) {
                handleTargetLoss();
                return;
            } else {
                info("New target: " + target.getName().getString());
            }
        }

        if (!mc.player.getEquippedStack(EquipmentSlot.CHEST).contains(DataComponentTypes.GLIDER)) {
            error("No elytra equipped!");
            toggle();
            return;
        }

        if (incrementJumpTimer) jumpTimer++;

        if (!mc.player.isGliding()) {
            if (!incrementJumpTimer) incrementJumpTimer = true;

            if (mc.player.isOnGround() && incrementJumpTimer) {
                mc.player.jump();
                return;
            }

            if (jumpTimer >= 4) {
                jumpTimer = 0;
                mc.player.setJumping(false);
                mc.player.setSprinting(true);
                if (mc.getNetworkHandler() != null) {
                    mc.getNetworkHandler().sendPacket(new ClientCommandC2SPacket(mc.player, ClientCommandC2SPacket.Mode.START_FALL_FLYING));
                }
            }

            stuckTicks = 0;
            lastPlayerPos = mc.player.getPos();
            adaptiveHorizontalCap = -1;
            antiCheatSlowTicks = 0;
        } else {
            incrementJumpTimer = false;
            jumpTimer = 0;

            updateStuckState();

            if (pathfindingEnabled.get()) {
                pathTimer++;
                Vec3d targetPos = getTargetPos();

                boolean needsRepath = pathTimer >= pathUpdateTicks.get()
                    || currentPath.isEmpty()
                    || (lastPathTarget != null && lastPathTarget.squaredDistanceTo(targetPos) > 16)
                    || stuckTicks >= stuckRepathTicks.get()
                    || (currentWaypointIndex < currentPath.size() && !isDirectPathClear(mc.player.getPos(), currentPath.get(currentWaypointIndex)));

                if (needsRepath) {
                    pathTimer = 0;
                    lastPathTarget = targetPos;
                    computePath(mc.player.getPos(), targetPos);
                }

                advanceWaypoints();
            }
        }
    }

    private void updateStuckState() {
        Vec3d now = mc.player.getPos();
        if (lastPlayerPos == null) {
            lastPlayerPos = now;
            stuckTicks = 0;
            return;
        }

        if (now.squaredDistanceTo(lastPlayerPos) < 0.03) {
            stuckTicks++;
        } else {
            stuckTicks = 0;
        }

        lastPlayerPos = now;
    }

    private void advanceWaypoints() {
        double reachSq = waypointReach.get() * waypointReach.get();
        while (currentWaypointIndex < currentPath.size()) {
            if (mc.player.getPos().squaredDistanceTo(currentPath.get(currentWaypointIndex)) <= reachSq) {
                currentWaypointIndex++;
            } else {
                break;
            }
        }
    }

    private Vec3d getTargetPos() {
        Vec3d targetPos = target.getPos().add(offset.get().x, offset.get().y, offset.get().z);

        if (predictMovement.get()) {
            targetPos = targetPos.add(target.getVelocity());
        }

        if (avoidLanding.get()) {
            targetPos = liftTargetIfOnGround(targetPos);
        }

        return adjustToSafeCorridor(targetPos, mc.player.getPos());
    }

    private Vec3d liftTargetIfOnGround(Vec3d targetPos) {
        double d = Math.max(0.35, target.getBoundingBox().getLengthX() / 2.0);
        for (Direction dir : CARDINAL_DIRECTIONS) {
            BlockPos pos = BlockPos.ofFloored(targetPos.offset(dir, d).offset(dir.rotateYClockwise(), d)).down();
            if (isSolid(pos) && Math.abs(targetPos.getY() - (pos.getY() + 1)) <= 0.35) {
                return new Vec3d(targetPos.x, pos.getY() + 1.35, targetPos.z);
            }
        }
        return targetPos;
    }

    private void handleTargetLoss() {
        warning("Lost target!");

        switch (action.get()) {
            case CHOOSE_NEW_TARGET:
                findTarget();
                if (target == null) {
                    error("No valid targets found.");
                    toggle();
                } else {
                    info("New target: " + target.getName().getString());
                }
                break;
                
            case TOGGLE:
                toggle();
                break;
                
            case DISCONNECT:
                if (mc.player != null && mc.player.networkHandler != null) {
                    mc.player.networkHandler.onDisconnect(new DisconnectS2CPacket(
                        Text.literal("%s[%sAuto Wasp%s] Lost target."
                            .formatted(Formatting.GRAY, Formatting.BLUE, Formatting.GRAY))
                    ));
                }
                break;
        }
    }

    @EventHandler
    private void onMove(PlayerMoveEvent event) {
        if (mc.player == null || mc.world == null) return;
        if (target == null || target.isRemoved() || target.isDead()) return;
        if (!mc.player.getEquippedStack(EquipmentSlot.CHEST).contains(DataComponentTypes.GLIDER)) return;
        if (!mc.player.isGliding()) return;

        Vec3d steerTarget = getTargetPos();
        if (pathfindingEnabled.get() && currentWaypointIndex < currentPath.size()) {
            steerTarget = currentPath.get(currentWaypointIndex);
        }
        steerTarget = adjustToSafeCorridor(steerTarget, mc.player.getPos());

        Vec3d toTarget = steerTarget.subtract(mc.player.getPos());
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
        yVel = applyVerticalSafety(yVel, mc.player.getPos());

        Vec3d base = new Vec3d(xVel, yVel, zVel);
        Vec3d avoid = computeLocalAvoidance(steerTarget, base, maxHorizontal);
        Vec3d velocity = base.add(avoid);
        velocity = applyEmergencyBraking(velocity);

        double velHorizontal = Math.sqrt(velocity.x * velocity.x + velocity.z * velocity.z);
        if (velHorizontal > maxHorizontal && velHorizontal > 1.0E-6) {
            double k = maxHorizontal / velHorizontal;
            velocity = new Vec3d(velocity.x * k, velocity.y, velocity.z * k);
        }

        // If server keeps rejecting movement, decay horizontal speed temporarily.
        double actualHoriz = Math.sqrt(mc.player.getVelocity().x * mc.player.getVelocity().x + mc.player.getVelocity().z * mc.player.getVelocity().z);
        boolean drag = isInDragState();
        boolean serverRejected = velHorizontal > 0.30 && actualHoriz < velHorizontal * 0.45;
        if (drag && serverRejected) antiCheatSlowTicks = Math.min(20, antiCheatSlowTicks + 1);
        else antiCheatSlowTicks = Math.max(0, antiCheatSlowTicks - 2);

        velocity = new Vec3d(
            velocity.x,
            MathHelper.clamp(velocity.y, -maxVertical, maxVertical),
            velocity.z
        );

        ((IVec3d) event.movement).meteor$set(velocity.x, velocity.y, velocity.z);
    }

    private double resolveHorizontalCap() {
        double baseCap = horizontalSpeed.get();
        boolean drag = isInDragState();

        // No drag block -> no artificial speed reduction.
        if (!drag) {
            adaptiveHorizontalCap = baseCap;
            return baseCap;
        }

        double actual = Math.sqrt(mc.player.getVelocity().x * mc.player.getVelocity().x + mc.player.getVelocity().z * mc.player.getVelocity().z);
        if (adaptiveHorizontalCap < 0 || adaptiveHorizontalCap > baseCap) adaptiveHorizontalCap = baseCap;

        double targetCap = Math.min(baseCap, Math.max(0.18, actual * 1.18 + 0.05));
        if (antiCheatSlowTicks > 8) {
            targetCap = Math.min(targetCap, Math.max(0.16, actual * 1.05 + 0.03));
        }

        adaptiveHorizontalCap += (targetCap - adaptiveHorizontalCap) * 0.35;
        adaptiveHorizontalCap = MathHelper.clamp(adaptiveHorizontalCap, 0.14, baseCap);
        return adaptiveHorizontalCap;
    }

    private double resolveVerticalCap() {
        return verticalSpeed.get();
    }

    private boolean isInDragState() {
        if (mc.player.isTouchingWater()) return true;
        return isInsideSoftDragBlock(mc.player.getBoundingBox());
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
        var state = mc.world.getBlockState(pos);
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
        double floorDist = distanceToSolidBelow(currentPos, safetyScan.get());
        double ceilingDist = distanceToSolidAbove(currentPos, safetyScan.get());

        double downBudget = Math.max(0.0, floorDist - floorClearance.get() - 0.35);
        double safeMaxDown = Math.min(verticalSpeed.get(), downBudget * 0.8);
        yVel = Math.max(yVel, -safeMaxDown);

        if (floorDist < floorClearance.get() + 0.35) {
            yVel = Math.max(yVel, 0.12);
        }

        if (ceilingDist < ceilingClearance.get() + 0.25) {
            yVel = Math.min(yVel, -0.08);
        }

        return yVel;
    }

    private Vec3d applyEmergencyBraking(Vec3d velocity) {
        double horizontal = Math.sqrt(velocity.x * velocity.x + velocity.z * velocity.z);
        if (horizontal < 1.0E-4) return velocity;

        Vec3d pos = mc.player.getPos();
        Vec3d dir = new Vec3d(velocity.x / horizontal, 0, velocity.z / horizontal);
        Vec3d left = new Vec3d(-dir.z, 0, dir.x);
        Vec3d right = left.multiply(-1);

        double checkDistance = Math.max(1.5, Math.min(obstacleLookAhead.get() + 1.0, horizontal * 4.2));
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
            velocity = new Vec3d(velocity.x * Math.max(0.35, speedFactor), velocity.y, velocity.z * Math.max(0.35, speedFactor));
        }

        double vx = velocity.x;
        double vz = velocity.z;
        double vy = velocity.y;
        double floorDist = distanceToSolidBelow(pos, safetyScan.get());
        double ceilingDist = distanceToSolidAbove(pos, safetyScan.get());

        if (clearAhead < 0.8) {
            // Imminent collision: almost stop horizontal and climb slightly.
            vx *= 0.55;
            vz *= 0.55;
            vy = Math.max(vy, 0.24);
            stuckTicks = Math.max(stuckTicks, stuckRepathTicks.get());
        } else if (clearAhead < 1.35) {
            vy = Math.max(vy, 0.14);
        }

        if (floorDist < floorClearance.get() + 0.45) vy = Math.max(vy, 0.10);
        if (ceilingDist < ceilingClearance.get() + 0.2) vy = Math.min(vy, 0.05);

        return new Vec3d(vx, vy, vz);
    }

    private Vec3d computeLocalAvoidance(Vec3d steerTarget, Vec3d baseVelocity, double horizontalCap) {
        Vec3d pos = mc.player.getPos();
        Vec3d avoid = Vec3d.ZERO;

        double floorDist = distanceToSolidBelow(pos, safetyScan.get());
        double ceilingDist = distanceToSolidAbove(pos, safetyScan.get());

        if (floorDist < floorClearance.get()) {
            double push = (floorClearance.get() - floorDist) * 0.75;
            avoid = avoid.add(0, Math.min(verticalSpeed.get(), push), 0);
        }

        if (ceilingDist < ceilingClearance.get()) {
            double push = (ceilingClearance.get() - ceilingDist) * 0.75;
            avoid = avoid.add(0, -Math.min(verticalSpeed.get(), push), 0);
        }

        Vec3d heading = steerTarget.subtract(pos);
        double headingLen = Math.sqrt(heading.x * heading.x + heading.z * heading.z);
        if (headingLen < 1.0E-4) {
            heading = new Vec3d(baseVelocity.x, 0, baseVelocity.z);
            headingLen = Math.sqrt(heading.x * heading.x + heading.z * heading.z);
        }

        if (headingLen > 1.0E-4) {
            Vec3d forward = new Vec3d(heading.x / headingLen, 0, heading.z / headingLen);
            double lookAhead = obstacleLookAhead.get();
            double frontClear = probeClearDistance(pos, forward, lookAhead);
            boolean blockedFront = frontClear < lookAhead - 0.15;

            if (blockedFront) {
                Vec3d left = new Vec3d(-forward.z, 0, forward.x);
                Vec3d right = left.multiply(-1);
                Vec3d targetDir = steerTarget.subtract(pos).normalize();

                Vec3d[] candidates = new Vec3d[] {
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
                    double sampleFloor = distanceToSolidBelow(sample, Math.max(6, safetyScan.get() / 2));
                    double sampleCeiling = distanceToSolidAbove(sample, Math.max(6, safetyScan.get() / 2));
                    int sideOpenings = countLateralOpenings(sample, 0.9);
                    double score = space * 1.55 + align * 1.05 + sideOpenings * 0.3;
                    if (sampleFloor < floorClearance.get()) score -= (floorClearance.get() - sampleFloor) * 1.4;
                    if (sampleCeiling < ceilingClearance.get()) score -= (ceilingClearance.get() - sampleCeiling) * 1.4;
                    if (candidate.y > 0 && floorDist < floorClearance.get() + 0.7) score += 1.0;
                    if (candidate.y > 0 && ceilingDist < ceilingClearance.get() + 0.7) score -= 0.8;
                    if (score > bestScore) {
                        bestScore = score;
                        bestDir = candidate;
                        bestSpace = space;
                    }
                }

                double urgency = Math.max(0.0, 1.0 - bestSpace / lookAhead);
                double sideSpeed = horizontalCap * (0.45 + urgency) * avoidanceStrength.get();
                avoid = avoid.add(bestDir.multiply(sideSpeed));
            }
        }

        return avoid;
    }

    private double probeClearDistance(Vec3d start, Vec3d dir, double maxDistance) {
        double step = Math.max(0.25, collisionStep.get());

        for (double d = step; d <= maxDistance; d += step) {
            if (!hasPlayerClearance(start.add(dir.multiply(d)))) {
                return d - step;
            }
        }

        return maxDistance;
    }

    private Vec3d adjustToSafeCorridor(Vec3d desired, Vec3d fallbackRef) {
        double floorY = nearestSolidBelow(desired, safetyScan.get());
        double ceilingY = nearestSolidAbove(desired, safetyScan.get());

        double minY = floorY + floorClearance.get();
        double maxY = ceilingY - playerHeight() - ceilingClearance.get();
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

    private boolean isDirectPathClear(Vec3d start, Vec3d goal) {
        Vec3d diff = goal.subtract(start);
        double dist = diff.length();
        if (dist < 0.2) return true;

        Vec3d dir = diff.normalize();
        double step = Math.max(0.25, collisionStep.get());

        for (double d = 0; d <= dist; d += step) {
            if (!hasPlayerClearance(start.add(dir.multiply(d)))) return false;
        }

        return true;
    }

    private boolean hasPlayerClearance(Vec3d center) {
        if (mc.world == null) return false;

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

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                if (mc.world.isOutOfHeightLimit(y)) {
                    clearanceCache.put(key, false);
                    return false;
                }
                for (int z = minZ; z <= maxZ; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (!mc.world.getBlockState(pos).getCollisionShape(mc.world, pos).isEmpty()) {
                        clearanceCache.put(key, false);
                        return false;
                    }
                }
            }
        }

        clearanceCache.put(key, true);
        return true;
    }

    private boolean isSolid(BlockPos pos) {
        if (mc.world == null || mc.world.isOutOfHeightLimit(pos.getY())) return true;
        return !mc.world.getBlockState(pos).getCollisionShape(mc.world, pos).isEmpty();
    }

    private double nearestSolidBelow(Vec3d pos, int maxDepth) {
        int x = MathHelper.floor(pos.x);
        int z = MathHelper.floor(pos.z);
        int yStart = MathHelper.floor(pos.y);
        BlockPos.Mutable mutable = new BlockPos.Mutable(x, yStart, z);

        for (int i = 0; i <= maxDepth; i++) {
            int y = yStart - i;
            if (mc.world.isOutOfHeightLimit(y)) return y;
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
            if (mc.world.isOutOfHeightLimit(y)) return y;
            mutable.set(x, y, z);
            if (isSolid(mutable)) return y;
        }

        return pos.y + maxHeight;
    }

    private double distanceToSolidBelow(Vec3d pos, int maxDepth) {
        long key = BlockPos.ofFloored(pos).asLong();
        Double cached = floorDistanceCache.get(key);
        if (cached != null) return cached;

        double value = pos.y - nearestSolidBelow(pos, maxDepth);
        floorDistanceCache.put(key, value);
        return value;
    }

    private double distanceToSolidAbove(Vec3d pos, int maxHeight) {
        long key = BlockPos.ofFloored(pos).asLong();
        Double cached = ceilingDistanceCache.get(key);
        if (cached != null) return cached;

        double value = nearestSolidAbove(pos, maxHeight) - (pos.y + playerHeight());
        ceilingDistanceCache.put(key, value);
        return value;
    }

    private double playerHeight() {
        return 1.8;
    }

    private void clearCaches() {
        clearanceCache.clear();
        floorDistanceCache.clear();
        ceilingDistanceCache.clear();
    }

    private void computePath(Vec3d start, Vec3d goal) {
        int res = pathResolution.get();
        BlockPos startBlock = toGrid(BlockPos.ofFloored(start), res);
        BlockPos goalBlock = toGrid(BlockPos.ofFloored(goal), res);

        startBlock = findNearestPassable(startBlock, res, 6);
        goalBlock = findNearestPassable(goalBlock, res, 7);

        if (startBlock == null || goalBlock == null) {
            currentPath.clear();
            currentWaypointIndex = 0;
            return;
        }

        Vec3d safeGoal = adjustToSafeCorridor(goal, start);
        if (isDirectPathClear(start, safeGoal)) {
            currentPath.clear();
            currentPath.add(safeGoal);
            currentWaypointIndex = 0;
            return;
        }

        Map<Long, AStarNode> openMap = new HashMap<>();
        Set<Long> closedSet = new HashSet<>();
        PriorityQueue<AStarNode> openQueue = new PriorityQueue<>(Comparator.comparingDouble(n -> n.f));

        AStarNode startNode = new AStarNode(startBlock, null, 0, heuristic(startBlock, goalBlock));
        openMap.put(startBlock.asLong(), startNode);
        openQueue.add(startNode);

        AStarNode bestNode = startNode;
        int nodesExplored = 0;
        int maxN = maxNodes.get();

        while (!openQueue.isEmpty() && nodesExplored < maxN) {
            AStarNode current = openQueue.poll();
            long currentKey = current.pos.asLong();
            if (closedSet.contains(currentKey)) continue;

            closedSet.add(currentKey);
            openMap.remove(currentKey);
            nodesExplored++;

            if (heuristic(current.pos, goalBlock) < heuristic(bestNode.pos, goalBlock)) {
                bestNode = current;
            }

            if (current.pos.getManhattanDistance(goalBlock) <= res) {
                currentPath = reconstructPath(current, safeGoal);
                currentWaypointIndex = 0;
                return;
            }

            for (int dx = -res; dx <= res; dx += res) {
                for (int dy = -res; dy <= res; dy += res) {
                    for (int dz = -res; dz <= res; dz += res) {
                        if (dx == 0 && dy == 0 && dz == 0) continue;

                        BlockPos neighbor = current.pos.add(dx, dy, dz);
                        long neighborKey = neighbor.asLong();

                        if (closedSet.contains(neighborKey)) continue;
                        if (!isNodeFlyable(neighbor)) continue;
                        if (!isEdgeFlyable(current.pos, neighbor)) continue;

                        double moveCost = Math.sqrt(dx * dx + dy * dy + dz * dz);
                        double g = current.g + moveCost + nodePenalty(neighbor) + turnPenalty(current, neighbor) + Math.abs(dy) * 0.18;
                        double h = heuristic(neighbor, goalBlock);
                        double f = g + h;

                        AStarNode old = openMap.get(neighborKey);
                        if (old != null && old.g <= g) continue;

                        AStarNode next = new AStarNode(neighbor, current, g, f);
                        openMap.put(neighborKey, next);
                        openQueue.add(next);
                    }
                }
            }
        }

        if (bestNode != startNode) {
            currentPath = reconstructPath(bestNode, safeGoal);
            currentWaypointIndex = 0;
        } else {
            currentPath.clear();
            currentWaypointIndex = 0;
        }
    }

    private double nodePenalty(BlockPos node) {
        Vec3d center = Vec3d.ofCenter(node);
        double floorDist = distanceToSolidBelow(center, safetyScan.get());
        double ceilingDist = distanceToSolidAbove(center, safetyScan.get());
        int nearOpen = countLateralOpenings(center, 0.9);
        int farOpen = countLateralOpenings(center, 1.6);

        double penalty = 0;
        if (floorDist < floorClearance.get()) penalty += (floorClearance.get() - floorDist) * 2.8;
        if (ceilingDist < ceilingClearance.get()) penalty += (ceilingClearance.get() - ceilingDist) * 2.8;
        if (floorDist > 10) penalty += 0.35;
        if (nearOpen <= 1) penalty += 2.2;
        if (farOpen <= 1) penalty += 1.4;

        penalty += narrowSpacePenalty(center);
        return penalty;
    }

    private double narrowSpacePenalty(Vec3d center) {
        int openSides = countLateralOpenings(center, 1.0);
        return Math.max(0, 4 - openSides) * 0.5;
    }

    private double turnPenalty(AStarNode current, BlockPos neighbor) {
        if (current.parent == null) return 0;

        int ax = current.pos.getX() - current.parent.pos.getX();
        int ay = current.pos.getY() - current.parent.pos.getY();
        int az = current.pos.getZ() - current.parent.pos.getZ();

        int bx = neighbor.getX() - current.pos.getX();
        int by = neighbor.getY() - current.pos.getY();
        int bz = neighbor.getZ() - current.pos.getZ();

        double aLen = Math.sqrt(ax * ax + ay * ay + az * az);
        double bLen = Math.sqrt(bx * bx + by * by + bz * bz);
        if (aLen < 1.0E-6 || bLen < 1.0E-6) return 0;

        double dot = (ax * bx + ay * by + az * bz) / (aLen * bLen);
        return (1.0 - dot) * 0.2;
    }

    private List<Vec3d> reconstructPath(AStarNode endNode, Vec3d finalGoal) {
        List<Vec3d> path = new ArrayList<>();
        AStarNode node = endNode;

        while (node != null) {
            path.add(adjustToSafeCorridor(Vec3d.ofCenter(node.pos), mc.player != null ? mc.player.getPos() : null));
            node = node.parent;
        }

        java.util.Collections.reverse(path);

        if (!path.isEmpty() && mc.player != null) {
            double reachSq = waypointReach.get() * waypointReach.get();
            if (mc.player.getPos().squaredDistanceTo(path.get(0)) <= reachSq) {
                path.remove(0);
            }
        }

        Vec3d safeGoal = adjustToSafeCorridor(finalGoal, mc.player != null ? mc.player.getPos() : null);
        if (path.isEmpty() || path.get(path.size() - 1).squaredDistanceTo(safeGoal) > 0.5) {
            path.add(safeGoal);
        }

        return simplifyPath(path);
    }

    private List<Vec3d> simplifyPath(List<Vec3d> path) {
        if (path.size() <= 2) return path;

        List<Vec3d> simplified = new ArrayList<>();
        int i = 0;
        simplified.add(path.get(0));

        while (i < path.size() - 1) {
            int farthest = i + 1;
            for (int j = path.size() - 1; j > i + 1; j--) {
                if (isDirectPathClear(path.get(i), path.get(j))) {
                    farthest = j;
                    break;
                }
            }
            simplified.add(path.get(farthest));
            i = farthest;
        }

        return simplified;
    }

    private BlockPos toGrid(BlockPos pos, int res) {
        if (res <= 1) return pos;
        return new BlockPos(
            Math.floorDiv(pos.getX(), res) * res,
            Math.floorDiv(pos.getY(), res) * res,
            Math.floorDiv(pos.getZ(), res) * res
        );
    }

    private double heuristic(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX();
        double dy = a.getY() - b.getY();
        double dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private BlockPos findNearestPassable(BlockPos origin, int res, int maxRadius) {
        if (isNodeFlyable(origin)) return origin;

        for (int r = 1; r <= maxRadius; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dy = -r; dy <= r; dy++) {
                    for (int dz = -r; dz <= r; dz++) {
                        if (Math.abs(dx) != r && Math.abs(dy) != r && Math.abs(dz) != r) continue;

                        BlockPos candidate = origin.add(dx * res, dy * res, dz * res);
                        if (isNodeFlyable(candidate)) return candidate;
                    }
                }
            }
        }

        return null;
    }

    private boolean isNodeFlyable(BlockPos center) {
        Vec3d p = Vec3d.ofCenter(center);
        if (!hasPlayerClearance(p)) return false;

        double floorDist = distanceToSolidBelow(p, Math.max(6, safetyScan.get() / 2));
        double ceilingDist = distanceToSolidAbove(p, Math.max(6, safetyScan.get() / 2));
        if (floorDist <= 0.1 || ceilingDist <= 0.1) return false;

        // Avoid diving into 1x1 holes and tiny pockets unless absolutely necessary.
        if (countLateralOpenings(p, 0.9) <= 1) return false;
        return countLateralOpenings(p, 1.5) >= 1;
    }

    private boolean isEdgeFlyable(BlockPos from, BlockPos to) {
        return isDirectPathClear(Vec3d.ofCenter(from), Vec3d.ofCenter(to));
    }

    private int countLateralOpenings(Vec3d center, double distance) {
        int open = 0;
        for (Direction dir : CARDINAL_DIRECTIONS) {
            Vec3d sample = center.add(dir.getOffsetX() * distance, 0, dir.getOffsetZ() * distance);
            if (hasPlayerClearance(sample)) open++;
        }
        return open;
    }

    private static class AStarNode {
        final BlockPos pos;
        final AStarNode parent;
        final double g;
        final double f;

        AStarNode(BlockPos pos, AStarNode parent, double g, double f) {
            this.pos = pos;
            this.parent = parent;
            this.g = g;
            this.f = f;
        }
    }

    public enum Action {
        TOGGLE("Toggle module"),
        CHOOSE_NEW_TARGET("Choose new target"),
        DISCONNECT("Disconnect");

        private final String title;

        Action(String title) {
            this.title = title;
        }

        @Override
        public String toString() {
            return title;
        }
    }
}
