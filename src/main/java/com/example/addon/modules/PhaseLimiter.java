package com.example.addon.modules;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.entity.player.PlayerMoveEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.mixininterface.IVec3d;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.block.BlockState;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.entity.player.PlayerPosition;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.PlayerInteractItemC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.network.packet.s2c.play.PlayerPositionLookS2CPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;

public class PhaseLimiter extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Double> maxPenetration = sgGeneral.add(new DoubleSetting.Builder()
        .name("max-penetration")
        .description("How deep your player center can move into the locked block before horizontal movement is frozen. Auto-raised if needed to keep hitbox inside.")
        .defaultValue(0.20)
        .min(0.01)
        .max(0.99)
        .sliderRange(0.05, 0.60)
        .build()
    );

    private final Setting<Double> insideBuffer = sgGeneral.add(new DoubleSetting.Builder()
        .name("inside-buffer")
        .description("Extra depth added to keep your hitbox inside the block after lock.")
        .defaultValue(0.05)
        .min(0.0)
        .max(0.20)
        .sliderRange(0.0, 0.08)
        .build()
    );

    private final Setting<Boolean> cancelMovePackets = sgGeneral.add(new BoolSetting.Builder()
        .name("cancel-move-packets")
        .description("Cancels horizontal PlayerMove packets when lock is active.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> debugLogs = sgGeneral.add(new BoolSetting.Builder()
        .name("debug-logs")
        .description("Logs penetration depth and lock/unlock reasons for tuning.")
        .defaultValue(false)
        .build()
    );

    private static final double EPSILON = 1e-6;
    private static final double DEPTH_EPSILON = 1e-4;
    private static final double MIN_ARM_DISTANCE_SQ = 0.04; // 0.20 blocks
    private static final int MIN_ARM_TICKS = 24;
    private static final int MAX_ARM_TICKS = 180;
    private static final long DEBUG_REPEAT_TICKS = 100;
    private static final int INVALID_GRACE_TICKS = 4;
    private static final double VERTICAL_DISABLE_DELTA = 0.08;

    private int armedTicks;
    private Vec3d pearlThrowPos;
    private double minLockOverlap;

    private BlockPos lockedBlock;
    private AxisLock lockX;
    private AxisLock lockZ;
    private PrimaryAxis primaryAxis;
    private boolean hardLocked;
    private double freezeX;
    private double freezeZ;
    private double lockY;
    private double maxDepthThisLock;
    private double hardLockDepth;
    private long debugSeq;
    private String lastDebugKey;
    private long lastDebugTick;
    private int invalidTicks;

    public PhaseLimiter() {
        super(AddonTemplate.CATEGORY, "phase-limiter", "Locks horizontal movement only after you reach the penetration limit in a phased block.");
    }

    @Override
    public void onActivate() {
        clearState();
        logDebug("activate");
    }

    @Override
    public void onDeactivate() {
        logDebug("deactivate");
        clearState();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;

        if (lockedBlock != null) {
            if (!isSolidAt(lockedBlock)) {
                clearLock("locked-block-broken");
                toggle();
                return;
            }

            if (Math.abs(mc.player.getY() - lockY) > VERTICAL_DISABLE_DELTA) {
                clearLock("vertical-exit");
                toggle();
                return;
            }

            if (!isLockStillValid()) {
                invalidTicks++;
                if (invalidTicks >= INVALID_GRACE_TICKS) clearLock("invalid-state");
                return;
            }
            invalidTicks = 0;

            updateDepthStats();
            if (!hardLocked && reachedLimitAt(mc.player.getX(), mc.player.getZ())) {
                Vec3d clamped = clampToLimit(mc.player.getX(), mc.player.getZ());
                engageHardLock(clamped.x, clamped.z, "tick-limit");
            }
            if (hardLocked) enforceFrozenPosition();
            return;
        }

        if (armedTicks <= 0) return;
        armedTicks--;

        if (pearlThrowPos != null && mc.player.squaredDistanceTo(pearlThrowPos) < MIN_ARM_DISTANCE_SQ) {
            if (armedTicks <= 0) clearArm();
            return;
        }

        BlockPos inside = findInsideSolidBlock(mc.player.getBoundingBox(), minLockOverlap);
        if (inside != null) {
            latchToBlock(inside);
            return;
        }

        if (armedTicks <= 0) clearArm();
    }

    @EventHandler
    private void onPlayerMove(PlayerMoveEvent event) {
        if (mc.player == null || mc.world == null) return;
        if (lockedBlock == null) return;
        if (!isLockStillValid()) return;

        double curX = mc.player.getX();
        double curZ = mc.player.getZ();
        double nextX = curX + event.movement.x;
        double nextZ = curZ + event.movement.z;

        if (hardLocked) {
            ((IVec3d) event.movement).meteor$set(0.0, event.movement.y, 0.0);
            logDebugRateLimited("move-hard-stop", "move-stop hard-lock");
            return;
        }

        double curDepth = getDepthFor(curX, curZ);
        Vec3d secondaryClamped = clampSecondaryAxis(nextX, nextZ);
        nextX = secondaryClamped.x;
        nextZ = secondaryClamped.z;
        double nextDepth = getDepthFor(nextX, nextZ);

        if (nextDepth < curDepth - DEPTH_EPSILON) {
            double allowedX = nextX;
            double allowedZ = nextZ;
            if (primaryAxis == PrimaryAxis.X) allowedX = curX;
            else allowedZ = curZ;
            ((IVec3d) event.movement).meteor$set(allowedX - curX, event.movement.y, allowedZ - curZ);
            logDebugRateLimited("move-outward-stop", String.format("move-stop outward cur=%.3f next=%.3f", curDepth, nextDepth));
            return;
        }

        if (!reachedLimitAt(nextX, nextZ)) {
            if (Math.abs(nextX - (curX + event.movement.x)) > EPSILON || Math.abs(nextZ - (curZ + event.movement.z)) > EPSILON) {
                ((IVec3d) event.movement).meteor$set(nextX - curX, event.movement.y, nextZ - curZ);
            }
            return;
        }

        Vec3d clamped = clampToLimit(nextX, nextZ);
        engageHardLock(clamped.x, clamped.z, "move-limit");
        ((IVec3d) event.movement).meteor$set(clamped.x - curX, event.movement.y, clamped.z - curZ);
    }

    @EventHandler(priority = EventPriority.HIGHEST + 1)
    private void onPacketSend(PacketEvent.Send event) {
        if (mc.player == null || mc.world == null) return;

        if (event.packet instanceof PlayerInteractItemC2SPacket packet
            && isPearlInHand(packet.getHand())) {
            armForPearl();
            return;
        }

        if (!(event.packet instanceof PlayerMoveC2SPacket packet)) return;
        if (!cancelMovePackets.get()) return;
        if (lockedBlock == null) return;
        if (!isLockStillValid()) return;
        if (!packet.changesPosition()) return;

        double baseX = mc.player.getX();
        double baseZ = mc.player.getZ();
        double packetX = packet.getX(baseX);
        double packetZ = packet.getZ(baseZ);

        if (!hardLocked) {
            Vec3d secondaryClamped = clampSecondaryAxis(packetX, packetZ);
            packetX = secondaryClamped.x;
            packetZ = secondaryClamped.z;
            double curDepth = getDepthFor(baseX, baseZ);
            double packetDepth = getDepthFor(packetX, packetZ);

            if (packetDepth < curDepth - DEPTH_EPSILON) {
                double allowedX = packetX;
                double allowedZ = packetZ;
                if (primaryAxis == PrimaryAxis.X) allowedX = baseX;
                else allowedZ = baseZ;
                event.packet = rewritePacketXZ(packet, allowedX, allowedZ);
                logDebugRateLimited("packet-outward-stop", String.format("packet-stop outward cur=%.3f next=%.3f", curDepth, packetDepth));
                return;
            }

            if (!reachedLimitAt(packetX, packetZ)) {
                double originalX = packet.getX(baseX);
                double originalZ = packet.getZ(baseZ);
                if (Math.abs(originalX - packetX) > EPSILON || Math.abs(originalZ - packetZ) > EPSILON) {
                    event.packet = rewritePacketXZ(packet, packetX, packetZ);
                }
                return;
            }

            Vec3d clamped = clampToLimit(packetX, packetZ);
            engageHardLock(clamped.x, clamped.z, "packet-limit");
            event.packet = rewritePacketXZ(packet, clamped.x, clamped.z);
            return;
        }

        PlayerMoveC2SPacket forced = forceHorizontalPacket(packet);
        if (forced != packet) {
            event.packet = forced;
            return;
        } else {
            boolean horizontalDelta = Math.abs(packetX - freezeX) > EPSILON || Math.abs(packetZ - freezeZ) > EPSILON;
            if (horizontalDelta) {
                event.cancel();
                logDebugRateLimited("packet-hard-cancel", String.format("packet-cancel hard-lock dx=%.5f dz=%.5f", packetX - freezeX, packetZ - freezeZ));
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST + 1)
    private void onPacketReceive(PacketEvent.Receive event) {
        if (mc.player == null || mc.world == null) return;
        if (lockedBlock == null) return;
        if (!(event.packet instanceof PlayerPositionLookS2CPacket packet)) return;

        PlayerPosition oldPos = packet.change();
        Vec3d oldVec = oldPos.position();

        if (!hardLocked) {
            double curDepth = getCurrentDepth();
            Vec3d secondaryClamped = clampSecondaryAxis(oldVec.x, oldVec.z);
            double serverX = secondaryClamped.x;
            double serverZ = secondaryClamped.z;
            double serverDepth = getDepthFor(serverX, serverZ);

            if (serverDepth < curDepth - DEPTH_EPSILON) {
                double allowedX = serverX;
                double allowedZ = serverZ;
                if (primaryAxis == PrimaryAxis.X) allowedX = mc.player.getX();
                else allowedZ = mc.player.getZ();
                PlayerPosition kept = new PlayerPosition(
                    new Vec3d(allowedX, oldVec.y, allowedZ),
                    oldPos.deltaMovement(),
                    oldPos.yaw(),
                    oldPos.pitch()
                );
                event.packet = PlayerPositionLookS2CPacket.of(packet.teleportId(), kept, packet.relatives());
                logDebugRateLimited("s2c-outward-stop", String.format("s2c-stop outward cur=%.3f srv=%.3f", curDepth, serverDepth));
                return;
            }

            if (!reachedLimitAt(serverX, serverZ)) {
                if (Math.abs(oldVec.x - serverX) > EPSILON || Math.abs(oldVec.z - serverZ) > EPSILON) {
                    PlayerPosition clampedSecondary = new PlayerPosition(
                        new Vec3d(serverX, oldVec.y, serverZ),
                        oldPos.deltaMovement(),
                        oldPos.yaw(),
                        oldPos.pitch()
                    );
                    event.packet = PlayerPositionLookS2CPacket.of(packet.teleportId(), clampedSecondary, packet.relatives());
                }
                return;
            }

            Vec3d clamped = clampToLimit(serverX, serverZ);
            engageHardLock(clamped.x, clamped.z, "s2c-limit");
            PlayerPosition limited = new PlayerPosition(
                new Vec3d(clamped.x, oldVec.y, clamped.z),
                oldPos.deltaMovement(),
                oldPos.yaw(),
                oldPos.pitch()
            );
            event.packet = PlayerPositionLookS2CPacket.of(packet.teleportId(), limited, packet.relatives());
            return;
        }

        boolean horizontalDelta = Math.abs(oldVec.x - freezeX) > EPSILON || Math.abs(oldVec.z - freezeZ) > EPSILON;
        if (!horizontalDelta) return;

        PlayerPosition newPos = new PlayerPosition(
            new Vec3d(freezeX, oldVec.y, freezeZ),
            oldPos.deltaMovement(),
            oldPos.yaw(),
            oldPos.pitch()
        );

        event.packet = PlayerPositionLookS2CPacket.of(packet.teleportId(), newPos, packet.relatives());
    }

    private PlayerMoveC2SPacket forceHorizontalPacket(PlayerMoveC2SPacket packet) {
        return rewritePacketXZ(packet, freezeX, freezeZ);
    }

    private PlayerMoveC2SPacket rewritePacketXZ(PlayerMoveC2SPacket packet, double x, double z) {
        double y = packet.getY(mc.player.getY());
        boolean onGround = packet.isOnGround();
        boolean horizontalCollision = mc.player.horizontalCollision;

        if (packet instanceof PlayerMoveC2SPacket.Full) {
            float yaw = packet.getYaw(mc.player.getYaw());
            float pitch = packet.getPitch(mc.player.getPitch());
            return new PlayerMoveC2SPacket.Full(x, y, z, yaw, pitch, onGround, horizontalCollision);
        }

        if (packet instanceof PlayerMoveC2SPacket.PositionAndOnGround) {
            return new PlayerMoveC2SPacket.PositionAndOnGround(x, y, z, onGround, horizontalCollision);
        }

        return packet;
    }

    private void armForPearl() {
        int ping = getLatency();
        armedTicks = MathHelper.clamp(30 + ping / 6, MIN_ARM_TICKS, MAX_ARM_TICKS);
        pearlThrowPos = mc.player.getPos();

        // Smaller max-penetration -> earlier latch candidate.
        minLockOverlap = MathHelper.clamp(maxPenetration.get() * 0.65, 0.08, 0.28);

        clearLock("rearm");
        logDebug(String.format("arm ping=%d armedTicks=%d throwPos=(%.3f,%.3f,%.3f) minOverlap=%.4f",
            ping, armedTicks, pearlThrowPos.x, pearlThrowPos.y, pearlThrowPos.z, minLockOverlap));
    }

    private void latchToBlock(BlockPos block) {
        lockedBlock = block.toImmutable();
        hardLocked = false;

        Vec3d origin = pearlThrowPos != null ? pearlThrowPos : mc.player.getPos();
        lockX = resolveAxisLock(origin.x, mc.player.getX(), lockedBlock.getX());
        lockZ = resolveAxisLock(origin.z, mc.player.getZ(), lockedBlock.getZ());
        primaryAxis = resolvePrimaryAxis(origin, mc.player.getPos(), lockedBlock, lockX, lockZ);

        freezeX = mc.player.getX();
        freezeZ = mc.player.getZ();
        lockY = mc.player.getY();
        maxDepthThisLock = 0.0;
        hardLockDepth = -1.0;
        invalidTicks = 0;
        clearArm();
        logDebug(String.format("latch block=%s axis=%s lockX=%s lockZ=%s cfg=%.3f effective=%.3f",
            lockedBlock, primaryAxis, lockX, lockZ, maxPenetration.get(), getEffectivePenetrationLimit()));
    }

    private boolean reachedLimitAt(double x, double z) {
        double depth = getDepthFor(x, z);
        double limit = getEffectivePenetrationLimit();
        return depth >= limit - EPSILON;
    }

    private Vec3d clampToLimit(double x, double z) {
        if (lockedBlock == null || primaryAxis == null) return new Vec3d(x, 0.0, z);

        if (primaryAxis == PrimaryAxis.X) {
            double clampedX = clampAxisByLock(x, lockX, lockedBlock.getX());
            return new Vec3d(clampedX, 0.0, z);
        }

        double clampedZ = clampAxisByLock(z, lockZ, lockedBlock.getZ());
        return new Vec3d(x, 0.0, clampedZ);
    }

    private Vec3d clampSecondaryAxis(double x, double z) {
        if (lockedBlock == null || primaryAxis == null) return new Vec3d(x, 0.0, z);

        double half = getHalfHorizontalSize();
        double minX = lockedBlock.getX() + half;
        double maxX = lockedBlock.getX() + 1.0 - half;
        double minZ = lockedBlock.getZ() + half;
        double maxZ = lockedBlock.getZ() + 1.0 - half;

        if (primaryAxis == PrimaryAxis.X) {
            double clampedZ = MathHelper.clamp(z, minZ, maxZ);
            return new Vec3d(x, 0.0, clampedZ);
        }

        double clampedX = MathHelper.clamp(x, minX, maxX);
        return new Vec3d(clampedX, 0.0, z);
    }

    private void enforceFrozenPosition() {
        double x = mc.player.getX();
        double z = mc.player.getZ();
        if (Math.abs(x - freezeX) <= EPSILON && Math.abs(z - freezeZ) <= EPSILON) return;
        mc.player.setPosition(freezeX, mc.player.getY(), freezeZ);
    }

    private BlockPos findInsideSolidBlock(Box playerBox, double requiredOverlap) {
        Box shrunk = shrink(playerBox);
        if (shrunk == null) return null;

        int xMin = MathHelper.floor(shrunk.minX);
        int yMin = MathHelper.floor(shrunk.minY);
        int zMin = MathHelper.floor(shrunk.minZ);
        int xMax = MathHelper.floor(shrunk.maxX - EPSILON);
        int yMax = MathHelper.floor(shrunk.maxY - EPSILON);
        int zMax = MathHelper.floor(shrunk.maxZ - EPSILON);

        BlockPos best = null;
        double bestOverlap = 0.0;

        for (int x = xMin; x <= xMax; x++) {
            for (int y = yMin; y <= yMax; y++) {
                for (int z = zMin; z <= zMax; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (!isSolidAt(pos)) continue;

                    double overlap = intersectionVolume(shrunk, pos);
                    if (overlap <= EPSILON) continue;
                    double hOverlap = horizontalOverlap(shrunk, pos);
                    if (hOverlap < requiredOverlap) continue;

                    if (overlap > bestOverlap + EPSILON) {
                        bestOverlap = overlap;
                        best = pos;
                    }
                }
            }
        }

        if (best != null) return best;

        BlockPos body = getBodyBlock();
        if (isSolidAt(body)) return body;
        BlockPos eyes = BlockPos.ofFloored(mc.player.getEyePos());
        if (isSolidAt(eyes)) return eyes;
        BlockPos feet = mc.player.getBlockPos();
        if (isSolidAt(feet)) return feet;

        return null;
    }

    private boolean isLockStillValid() {
        if (lockedBlock == null) return false;
        if (!isSolidAt(lockedBlock)) return false;

        Box playerBox = mc.player.getBoundingBox();
        double intersection = intersectionVolume(playerBox, lockedBlock);
        if (intersection > EPSILON) return true;

        BlockPos feet = mc.player.getBlockPos();
        BlockPos body = getBodyBlock();
        BlockPos eyes = BlockPos.ofFloored(mc.player.getEyePos());
        if (feet.equals(lockedBlock) || body.equals(lockedBlock) || eyes.equals(lockedBlock)) return true;

        double cx = lockedBlock.getX() + 0.5;
        double cz = lockedBlock.getZ() + 0.5;
        double dx = Math.abs(mc.player.getX() - cx);
        double dz = Math.abs(mc.player.getZ() - cz);
        double dy = Math.abs(mc.player.getY() - (lockedBlock.getY() + 0.5));
        return dx <= 1.05 && dz <= 1.05 && dy <= 1.6;
    }

    private boolean isSolidAt(BlockPos pos) {
        BlockState state = mc.world.getBlockState(pos);
        if (state.isAir()) return false;
        VoxelShape shape = state.getCollisionShape(mc.world, pos);
        return !shape.isEmpty();
    }

    private double intersectionVolume(Box playerBox, BlockPos pos) {
        VoxelShape shape = mc.world.getBlockState(pos).getCollisionShape(mc.world, pos);
        if (shape.isEmpty()) return 0.0;

        Box blockBox = shape.getBoundingBox().offset(pos);
        if (!playerBox.intersects(blockBox)) return 0.0;

        double minX = Math.max(playerBox.minX, blockBox.minX);
        double minY = Math.max(playerBox.minY, blockBox.minY);
        double minZ = Math.max(playerBox.minZ, blockBox.minZ);
        double maxX = Math.min(playerBox.maxX, blockBox.maxX);
        double maxY = Math.min(playerBox.maxY, blockBox.maxY);
        double maxZ = Math.min(playerBox.maxZ, blockBox.maxZ);

        double dx = maxX - minX;
        double dy = maxY - minY;
        double dz = maxZ - minZ;
        if (dx <= 0 || dy <= 0 || dz <= 0) return 0.0;
        return dx * dy * dz;
    }

    private double horizontalOverlap(Box playerBox, BlockPos pos) {
        VoxelShape shape = mc.world.getBlockState(pos).getCollisionShape(mc.world, pos);
        if (shape.isEmpty()) return 0.0;

        Box blockBox = shape.getBoundingBox().offset(pos);
        if (!playerBox.intersects(blockBox)) return 0.0;

        double minX = Math.max(playerBox.minX, blockBox.minX);
        double minZ = Math.max(playerBox.minZ, blockBox.minZ);
        double maxX = Math.min(playerBox.maxX, blockBox.maxX);
        double maxZ = Math.min(playerBox.maxZ, blockBox.maxZ);

        double overlapX = maxX - minX;
        double overlapZ = maxZ - minZ;
        if (overlapX <= 0 || overlapZ <= 0) return 0.0;
        return Math.max(overlapX, overlapZ);
    }

    private Box shrink(Box box) {
        Box shrunk = new Box(
            box.minX + EPSILON,
            box.minY + EPSILON,
            box.minZ + EPSILON,
            box.maxX - EPSILON,
            box.maxY - EPSILON,
            box.maxZ - EPSILON
        );
        if (shrunk.maxX <= shrunk.minX || shrunk.maxY <= shrunk.minY || shrunk.maxZ <= shrunk.minZ) return null;
        return shrunk;
    }

    private AxisLock resolveAxisLock(double originCoord, double currentCoord, int blockCoord) {
        double min = blockCoord;
        double max = blockCoord + 1.0;

        if (originCoord <= min + EPSILON) return AxisLock.Min;
        if (originCoord >= max - EPSILON) return AxisLock.Max;

        double middle = (min + max) * 0.5;
        if (originCoord < middle) return AxisLock.Min;
        if (originCoord > middle) return AxisLock.Max;

        double distToMin = Math.abs(currentCoord - min);
        double distToMax = Math.abs(max - currentCoord);
        return distToMin <= distToMax ? AxisLock.Min : AxisLock.Max;
    }

    private PrimaryAxis resolvePrimaryAxis(Vec3d origin, Vec3d current, BlockPos block, AxisLock axisX, AxisLock axisZ) {
        double depthX = axisX == AxisLock.Min ? current.x - block.getX() : (block.getX() + 1.0) - current.x;
        double depthZ = axisZ == AxisLock.Min ? current.z - block.getZ() : (block.getZ() + 1.0) - current.z;

        depthX = MathHelper.clamp(depthX, 0.0, 1.0);
        depthZ = MathHelper.clamp(depthZ, 0.0, 1.0);

        // The axis with smaller current depth is usually the entry face axis.
        if (Math.abs(depthX - depthZ) > 0.02) return depthX <= depthZ ? PrimaryAxis.X : PrimaryAxis.Z;

        double dx = Math.abs(current.x - origin.x);
        double dz = Math.abs(current.z - origin.z);
        if (Math.abs(dx - dz) > DEPTH_EPSILON) return dx >= dz ? PrimaryAxis.X : PrimaryAxis.Z;

        return depthX <= depthZ ? PrimaryAxis.X : PrimaryAxis.Z;
    }

    private double clampAxisByLock(double value, AxisLock lock, int blockCoord) {
        double limit = getEffectivePenetrationLimit();
        if (lock == AxisLock.Min) {
            double centerLimit = blockCoord + limit;
            return Math.min(value, centerLimit);
        }

        double centerLimit = blockCoord + 1.0 - limit;
        return Math.max(value, centerLimit);
    }

    private void updateDepthStats() {
        double depth = getCurrentDepth();
        if (depth > maxDepthThisLock) maxDepthThisLock = depth;
    }

    private double getCurrentDepth() {
        return getDepthFor(mc.player.getX(), mc.player.getZ());
    }

    private double getDepthFor(double x, double z) {
        if (lockedBlock == null || primaryAxis == null) return 0.0;

        if (primaryAxis == PrimaryAxis.X) {
            if (lockX == AxisLock.Min) return x - lockedBlock.getX();
            return (lockedBlock.getX() + 1.0) - x;
        }
        if (lockZ == AxisLock.Min) return z - lockedBlock.getZ();
        return (lockedBlock.getZ() + 1.0) - z;
    }

    private double getHalfHorizontalSize() {
        if (mc.player == null) return 0.3;
        return (mc.player.getBoundingBox().maxX - mc.player.getBoundingBox().minX) * 0.5;
    }

    private double getEffectivePenetrationLimit() {
        double half = getHalfHorizontalSize();
        double effectiveBuffer = Math.max(insideBuffer.get(), 0.0);
        double effective = Math.max(maxPenetration.get(), half + effectiveBuffer);
        return MathHelper.clamp(effective, 0.01, 0.99);
    }

    private void engageHardLock(double x, double z, String source) {
        hardLocked = true;
        freezeX = x;
        freezeZ = z;
        hardLockDepth = getDepthFor(x, z);
        logDebug(String.format("hard-lock source=%s depth=%.3f effective=%.3f freezeX=%.3f freezeZ=%.3f",
            source, hardLockDepth, getEffectivePenetrationLimit(), freezeX, freezeZ));
    }

    private boolean isPearlInHand(Hand hand) {
        if (hand == Hand.MAIN_HAND) return mc.player.getMainHandStack().isOf(Items.ENDER_PEARL);
        if (hand == Hand.OFF_HAND) return mc.player.getOffHandStack().isOf(Items.ENDER_PEARL);
        return false;
    }

    private BlockPos getBodyBlock() {
        double y = mc.player.getY() + mc.player.getHeight() * 0.5;
        return BlockPos.ofFloored(mc.player.getX(), y, mc.player.getZ());
    }

    private int getLatency() {
        if (mc.getNetworkHandler() == null || mc.player == null) return 0;
        PlayerListEntry entry = mc.getNetworkHandler().getPlayerListEntry(mc.player.getUuid());
        return entry != null ? Math.max(0, entry.getLatency()) : 0;
    }

    private void clearLock() {
        clearLock("clear");
    }

    private void clearLock(String reason) {
        if (lockedBlock != null) {
            logDebug(String.format("unlock reason=%s maxDepth=%.3f hardDepth=%.3f block=%s", reason, maxDepthThisLock, hardLockDepth, lockedBlock));
        }

        lockedBlock = null;
        lockX = null;
        lockZ = null;
        primaryAxis = null;
        hardLocked = false;
        freezeX = 0.0;
        freezeZ = 0.0;
        lockY = 0.0;
        maxDepthThisLock = 0.0;
        hardLockDepth = -1.0;
        invalidTicks = 0;
    }

    private void clearArm() {
        armedTicks = 0;
        pearlThrowPos = null;
    }

    private void clearState() {
        clearLock("state-reset");
        clearArm();
        minLockOverlap = 0.0;
        lastDebugKey = null;
        lastDebugTick = Long.MIN_VALUE;
    }

    private void logDebug(String message) {
        if (!debugLogs.get()) return;
        debugSeq++;
        info("[dbg] #" + debugSeq + " " + message);
    }

    private void logDebugRateLimited(String key, String message) {
        if (!debugLogs.get()) return;
        if (mc.world != null) {
            long nowTick = mc.world.getTime();
            if (key.equals(lastDebugKey) && nowTick - lastDebugTick < DEBUG_REPEAT_TICKS) return;
            lastDebugKey = key;
            lastDebugTick = nowTick;
        }

        logDebug(message);
    }

    @Override
    public String getInfoString() {
        if (lockedBlock != null) return hardLocked
            ? "locked " + lockedBlock.getX() + " " + lockedBlock.getY() + " " + lockedBlock.getZ()
            : "limit";
        if (armedTicks > 0) return "armed " + armedTicks;
        return String.format("%.0f%%", maxPenetration.get() * 100);
    }

    private enum AxisLock {
        Min,
        Max
    }

    private enum PrimaryAxis {
        X,
        Z
    }
}
