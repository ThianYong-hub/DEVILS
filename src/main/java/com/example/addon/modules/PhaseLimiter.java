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
    private static final double PRELOCK_MARGIN = 0.01;
    private static final double MIN_ARM_DISTANCE_SQ = 0.04; // 0.20 blocks
    private static final int MIN_ARM_TICKS = 24;
    private static final int MAX_ARM_TICKS = 180;
    private static final double MIN_INSIDE_BUFFER = 0.05;
    private static final double CENTER_LOCK_RADIUS = 0.08;
    private static final double MIN_EFFECTIVE_LIMIT = 0.40;
    private static final double PRELOCK_BACKSLIDE_EPS = 1e-4;

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
    private double maxDepthThisLock;
    private double hardLockDepth;
    private double bestCenterDistSq;
    private double lastGoodX;
    private double lastGoodZ;
    private boolean debugLimitLogged;
    private long debugSeq;

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
        logTickState("tick-pre");

        if (lockedBlock != null) {
            if (!isLockStillValid()) {
                clearLock("invalid-state");
                return;
            }

            if (!hardLocked) enforcePrelockProgress();
            updateDepthStats();
            updateHardLockState();
            if (hardLocked) enforceFrozenPosition();
            logTickState("tick-locked");
            return;
        }

        if (armedTicks <= 0) return;
        armedTicks--;
        logDebugf("tick-armed left=%d dist2=%.4f minDist2=%.4f", armedTicks, pearlThrowPos != null ? mc.player.squaredDistanceTo(pearlThrowPos) : -1.0, MIN_ARM_DISTANCE_SQ);

        if (pearlThrowPos != null && mc.player.squaredDistanceTo(pearlThrowPos) < MIN_ARM_DISTANCE_SQ) {
            if (armedTicks <= 0) clearArm();
            return;
        }

        BlockPos inside = findInsideSolidBlock(mc.player.getBoundingBox(), minLockOverlap);
        if (inside != null) {
            logDebugf("tick-latch-candidate block=%s", inside);
            latchToBlock(inside);
            return;
        }

        if (armedTicks <= 0) clearArm();
    }

    @EventHandler
    private void onPlayerMove(PlayerMoveEvent event) {
        if (mc.player == null || mc.world == null) return;
        if (lockedBlock == null) return;
        if (!isLockStillValid()) {
            clearLock("invalid-move");
            return;
        }

        logDebugf("move in=(%.4f,%.4f,%.4f) hard=%s", event.movement.x, event.movement.y, event.movement.z, hardLocked);

        if (debugLogs.get()) {
            double nextX = mc.player.getX() + event.movement.x;
            double nextZ = mc.player.getZ() + event.movement.z;
            if (!debugLimitLogged && reachedLimitAt(nextX, nextZ)) {
                debugLimitLogged = true;
                logDebug(String.format("debug-limit-cross depth=%.3f limit=%.3f", getDepthFor(nextX, nextZ), getEffectivePenetrationLimit()));
            }
            logDebugf("move-debug nextX=%.4f nextZ=%.4f depthNow=%.4f depthNext=%.4f", nextX, nextZ, getCurrentDepth(), getDepthFor(nextX, nextZ));
        }

        double curX = mc.player.getX();
        double curZ = mc.player.getZ();

        if (!hardLocked) {
            double nextX = curX + event.movement.x;
            double nextZ = curZ + event.movement.z;
            boolean reachCenter = isAtCenter(nextX, nextZ);
            boolean awayFromCenter = isMovingAwayFromCenter(curX, curZ, nextX, nextZ);
            logDebugf("move-check nextX=%.4f nextZ=%.4f depthNow=%.4f depthNext=%.4f reachCenter=%s away=%s", nextX, nextZ, getCurrentDepth(), getDepthFor(nextX, nextZ), reachCenter, awayFromCenter);
            if (awayFromCenter && !reachCenter) {
                ((IVec3d) event.movement).meteor$set(0.0, event.movement.y, 0.0);
                logDebug("move-prelock-stop-away-from-center");
                return;
            }
            if (reachCenter) {
                double centerX = getCenterX();
                double centerZ = getCenterZ();
                engageHardLock(centerX, centerZ, "move-center");
                ((IVec3d) event.movement).meteor$set(centerX - curX, event.movement.y, centerZ - curZ);
                logDebugf("move-center-lock x=%.4f z=%.4f", centerX, centerZ);
            }
            return;
        }

        ((IVec3d) event.movement).meteor$set(0.0, event.movement.y, 0.0);
        logDebug("move-hardlock-stop");
    }

    @EventHandler(priority = EventPriority.HIGHEST + 1)
    private void onPacketSend(PacketEvent.Send event) {
        if (mc.player == null || mc.world == null) return;

        if (event.packet instanceof PlayerInteractItemC2SPacket && holdsPearl()) {
            armForPearl();
            return;
        }

        if (!(event.packet instanceof PlayerMoveC2SPacket packet)) return;
        logDebugf("packet-move recv hard=%s lock=%s changesPos=%s cancelEnabled=%s", hardLocked, lockedBlock != null, packet.changesPosition(), cancelMovePackets.get());
        if (lockedBlock != null && !cancelMovePackets.get()) logDebug("packet-warning lock-active-but-cancel-disabled");
        if (!cancelMovePackets.get()) return;
        if (lockedBlock == null) return;
        if (!isLockStillValid()) {
            clearLock("invalid-packet");
            return;
        }
        if (!packet.changesPosition()) return;

        double baseX = mc.player.getX();
        double baseZ = mc.player.getZ();
        double packetX = packet.getX(baseX);
        double packetZ = packet.getZ(baseZ);

        if (!hardLocked) {
            boolean reachCenter = isAtCenter(packetX, packetZ);
            boolean awayFromProgress = distanceSqToCenter(packetX, packetZ) > bestCenterDistSq + PRELOCK_BACKSLIDE_EPS;
            logDebugf("packet-prelock-check base=(%.4f,%.4f) pkt=(%.4f,%.4f) depthPkt=%.4f reachCenter=%s awayProgress=%s", baseX, baseZ, packetX, packetZ, getDepthFor(packetX, packetZ), reachCenter, awayFromProgress);
            if (awayFromProgress && !reachCenter) {
                event.packet = rewritePacketXZ(packet, lastGoodX, lastGoodZ);
                logDebug("packet-prelock-rewrite-away-from-center");
                return;
            }
            if (!reachCenter) return;

            engageHardLock(getCenterX(), getCenterZ(), "packet-center");

            enforceFrozenPosition();
            event.packet = rewritePacketXZ(packet, freezeX, freezeZ);
            logDebug("packet-prelock-rewrite-center");
            return;
        }

        PlayerMoveC2SPacket forced = forceHorizontalPacket(packet);
        if (forced != packet) {
            event.packet = forced;
            if (Math.abs(packetX - freezeX) > EPSILON || Math.abs(packetZ - freezeZ) > EPSILON) {
                logDebugf("packet-hardlock-rewrite dx=%.5f dz=%.5f", packetX - freezeX, packetZ - freezeZ);
            }
        } else {
            boolean horizontalDelta = Math.abs(packetX - freezeX) > EPSILON || Math.abs(packetZ - freezeZ) > EPSILON;
            if (horizontalDelta) {
                event.cancel();
                logDebugf("packet-hardlock-cancel dx=%.5f dz=%.5f", packetX - freezeX, packetZ - freezeZ);
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
            double dist2 = distanceSqToCenter(oldVec.x, oldVec.z);
            if (dist2 > bestCenterDistSq + PRELOCK_BACKSLIDE_EPS) {
                PlayerPosition newPos = new PlayerPosition(
                    new Vec3d(lastGoodX, oldVec.y, lastGoodZ),
                    oldPos.deltaMovement(),
                    oldPos.yaw(),
                    oldPos.pitch()
                );
                event.packet = PlayerPositionLookS2CPacket.of(packet.teleportId(), newPos, packet.relatives());
                logDebugf("packet-s2c-prelock-rewrite dx=%.5f dz=%.5f", oldVec.x - lastGoodX, oldVec.z - lastGoodZ);
            }
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
        logDebugf("packet-s2c-rewrite dx=%.5f dz=%.5f", oldVec.x - freezeX, oldVec.z - freezeZ);
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
        logDebugf("arm ping=%d armedTicks=%d throwPos=(%.3f,%.3f,%.3f) minOverlap=%.4f", ping, armedTicks, pearlThrowPos.x, pearlThrowPos.y, pearlThrowPos.z, minLockOverlap);
    }

    private void latchToBlock(BlockPos block) {
        lockedBlock = block.toImmutable();
        hardLocked = false;

        Vec3d origin = pearlThrowPos != null ? pearlThrowPos : mc.player.getPos();
        lockX = resolveAxisLock(origin.x, mc.player.getX(), lockedBlock.getX());
        lockZ = resolveAxisLock(origin.z, mc.player.getZ(), lockedBlock.getZ());
        primaryAxis = resolvePrimaryAxis(origin, mc.player.getPos());

        double centerX = lockedBlock.getX() + 0.5;
        double centerZ = lockedBlock.getZ() + 0.5;
        freezeX = centerX;
        freezeZ = centerZ;
        maxDepthThisLock = 0.0;
        hardLockDepth = -1.0;
        bestCenterDistSq = distanceSqToCenter(mc.player.getX(), mc.player.getZ());
        lastGoodX = mc.player.getX();
        lastGoodZ = mc.player.getZ();
        debugLimitLogged = false;
        clearArm();
        double limit = getEffectivePenetrationLimit();
        logDebug(String.format("latch block=%s axis=%s lockX=%s lockZ=%s limit=%.3f prelock=%.3f cfg=%.3f half=%.3f",
            lockedBlock, primaryAxis, lockX, lockZ, limit, Math.max(0.0, limit - PRELOCK_MARGIN), maxPenetration.get(), getHalfHorizontalSize()));
        logTickState("latch-state");
    }

    private void updateHardLockState() {
        if (hardLocked) return;

        boolean reachedCenter = hasReachedCenter();
        if (debugLogs.get() && !debugLimitLogged && reachedCenter) {
            debugLimitLogged = true;
            logDebug(String.format("debug-center-reached x=%.3f z=%.3f", mc.player.getX(), mc.player.getZ()));
        }

        logDebugf("hardlock-check centerReached=%s", reachedCenter);
        if (!reachedCenter) return;

        engageHardLock(getCenterX(), getCenterZ(), "tick-center");
        enforceFrozenPosition();
    }

    private boolean hasReachedCenter() {
        return isAtCenter(mc.player.getX(), mc.player.getZ());
    }

    private void enforcePrelockProgress() {
        double x = mc.player.getX();
        double z = mc.player.getZ();
        double dist2 = distanceSqToCenter(x, z);

        if (dist2 <= bestCenterDistSq + EPSILON) {
            if (dist2 < bestCenterDistSq) bestCenterDistSq = dist2;
            lastGoodX = x;
            lastGoodZ = z;
            return;
        }

        if (dist2 > bestCenterDistSq + PRELOCK_BACKSLIDE_EPS) {
            mc.player.setPosition(lastGoodX, mc.player.getY(), lastGoodZ);
            ((IVec3d) mc.player.getVelocity()).meteor$set(0.0, mc.player.getVelocity().y, 0.0);
            logDebugf("prelock-revert dist2=%.5f best=%.5f", dist2, bestCenterDistSq);
        }
    }

    private boolean isMovingAwayFromCenter(double curX, double curZ, double nextX, double nextZ) {
        double curDist2 = distanceSqToCenter(curX, curZ);
        double nextDist2 = distanceSqToCenter(nextX, nextZ);
        boolean away = nextDist2 > curDist2 + EPSILON;
        logDebugf("center-delta curDist2=%.5f nextDist2=%.5f away=%s", curDist2, nextDist2, away);
        return away;
    }

    private double distanceSqToCenter(double x, double z) {
        double dx = x - getCenterX();
        double dz = z - getCenterZ();
        return dx * dx + dz * dz;
    }

    private boolean isAtCenter(double x, double z) {
        double dx = x - getCenterX();
        double dz = z - getCenterZ();
        double dist2 = dx * dx + dz * dz;
        double radius2 = CENTER_LOCK_RADIUS * CENTER_LOCK_RADIUS;
        boolean inside = dist2 <= radius2 + EPSILON;
        logDebugf("center-check x=%.4f z=%.4f dx=%.4f dz=%.4f dist2=%.5f r=%.3f inside=%s", x, z, dx, dz, dist2, CENTER_LOCK_RADIUS, inside);
        return inside;
    }

    private double getCenterX() {
        return lockedBlock.getX() + 0.5;
    }

    private double getCenterZ() {
        return lockedBlock.getZ() + 0.5;
    }

    private boolean reachedLimitAt(double x, double z) {
        double depth = getDepthFor(x, z);
        double limit = getEffectivePenetrationLimit();
        boolean reached = depth >= limit - EPSILON;

        if (primaryAxis == PrimaryAxis.X) {
            logDebugf("limit-check axis=X x=%.4f depth=%.4f limit=%.4f reached=%s", x, depth, limit, reached);
            return reached;
        }

        logDebugf("limit-check axis=Z z=%.4f depth=%.4f limit=%.4f reached=%s", z, depth, limit, reached);
        return reached;
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
        int checked = 0;
        int solid = 0;
        int overlapMatch = 0;

        for (int x = xMin; x <= xMax; x++) {
            for (int y = yMin; y <= yMax; y++) {
                for (int z = zMin; z <= zMax; z++) {
                    checked++;
                    BlockPos pos = new BlockPos(x, y, z);
                    if (!isSolidAt(pos)) continue;
                    solid++;

                    double overlap = intersectionVolume(shrunk, pos);
                    if (overlap <= EPSILON) continue;
                    double hOverlap = horizontalOverlap(shrunk, pos);
                    if (hOverlap < requiredOverlap) continue;
                    overlapMatch++;

                    if (overlap > bestOverlap + EPSILON) {
                        bestOverlap = overlap;
                        best = pos;
                    }
                }
            }
        }

        if (best != null) {
            logDebugf("find-inside best=%s checked=%d solid=%d matched=%d bestOverlap=%.6f reqH=%.4f", best, checked, solid, overlapMatch, bestOverlap, requiredOverlap);
            return best;
        }

        BlockPos body = getBodyBlock();
        if (isSolidAt(body)) {
            logDebugf("find-inside fallback=body %s", body);
            return body;
        }
        BlockPos eyes = BlockPos.ofFloored(mc.player.getEyePos());
        if (isSolidAt(eyes)) {
            logDebugf("find-inside fallback=eyes %s", eyes);
            return eyes;
        }
        BlockPos feet = mc.player.getBlockPos();
        if (isSolidAt(feet)) {
            logDebugf("find-inside fallback=feet %s", feet);
            return feet;
        }

        logDebugf("find-inside none checked=%d solid=%d matched=%d reqH=%.4f", checked, solid, overlapMatch, requiredOverlap);

        return null;
    }

    private boolean isLockStillValid() {
        if (lockedBlock == null) return false;
        if (!isSolidAt(lockedBlock)) {
            logDebug("valid=false reason=locked-block-not-solid");
            return false;
        }

        Box playerBox = mc.player.getBoundingBox();
        double blockMinY = lockedBlock.getY();
        double blockMaxY = lockedBlock.getY() + 1.0;
        boolean overlapY = playerBox.maxY > blockMinY + EPSILON && playerBox.minY < blockMaxY - EPSILON;
        if (!overlapY) {
            logDebugf("valid=false reason=vertical-out minY=%.4f maxY=%.4f blockY=[%.1f,%.1f]", playerBox.minY, playerBox.maxY, blockMinY, blockMaxY);
            return false;
        }

        double intersection = intersectionVolume(playerBox, lockedBlock);
        if (intersection > EPSILON) {
            logDebugf("valid=true reason=intersection overlap=%.6f", intersection);
            return true;
        }

        BlockPos feet = mc.player.getBlockPos();
        BlockPos body = getBodyBlock();
        BlockPos eyes = BlockPos.ofFloored(mc.player.getEyePos());
        if (feet.equals(lockedBlock) || body.equals(lockedBlock) || eyes.equals(lockedBlock)) {
            logDebugf("valid=true reason=block-match feet=%s body=%s eyes=%s", feet.equals(lockedBlock), body.equals(lockedBlock), eyes.equals(lockedBlock));
            return true;
        }

        double cx = lockedBlock.getX() + 0.5;
        double cz = lockedBlock.getZ() + 0.5;
        double dx = Math.abs(mc.player.getX() - cx);
        double dz = Math.abs(mc.player.getZ() - cz);
        boolean near = dx <= 0.90 && dz <= 0.90;
        logDebugf("valid=%s reason=distance-check dx=%.4f dz=%.4f", near, dx, dz);
        return near;
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

    private PrimaryAxis resolvePrimaryAxis(Vec3d origin, Vec3d current) {
        double dx = Math.abs(current.x - origin.x);
        double dz = Math.abs(current.z - origin.z);
        return dx >= dz ? PrimaryAxis.X : PrimaryAxis.Z;
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
        logDebugf("depth now=%.4f max=%.4f hard=%s", depth, maxDepthThisLock, hardLocked);
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

    private boolean willReachLimit(double nextX, double nextZ) {
        double limit = Math.max(0.0, getEffectivePenetrationLimit() - PRELOCK_MARGIN);
        if (primaryAxis == PrimaryAxis.X) {
            double depth = getDepthFor(nextX, mc.player.getZ());
            boolean reached = depth >= limit - EPSILON;
            logDebugf("will-reach axis=X depth=%.4f preLimit=%.4f reached=%s", depth, limit, reached);
            return reached;
        }
        double depth = getDepthFor(mc.player.getX(), nextZ);
        boolean reached = depth >= limit - EPSILON;
        logDebugf("will-reach axis=Z depth=%.4f preLimit=%.4f reached=%s", depth, limit, reached);
        return reached;
    }

    private double getHalfHorizontalSize() {
        if (mc.player == null) return 0.3;
        return (mc.player.getBoundingBox().maxX - mc.player.getBoundingBox().minX) * 0.5;
    }

    private double getEffectivePenetrationLimit() {
        double half = getHalfHorizontalSize();
        double effectiveBuffer = Math.max(insideBuffer.get(), MIN_INSIDE_BUFFER);
        double effective = Math.max(maxPenetration.get(), half + effectiveBuffer);
        effective = Math.max(effective, MIN_EFFECTIVE_LIMIT);
        return MathHelper.clamp(effective, 0.01, 0.99);
    }

    private void engageHardLock(double x, double z, String source) {
        hardLocked = true;
        freezeX = x;
        freezeZ = z;
        hardLockDepth = getCurrentDepth();
        logDebug(String.format("hard-lock source=%s depth=%.3f limit=%.3f freezeX=%.3f freezeZ=%.3f", source, hardLockDepth, getEffectivePenetrationLimit(), freezeX, freezeZ));
        logTickState("hard-lock-state");
    }

    private boolean holdsPearl() {
        return mc.player.getMainHandStack().isOf(Items.ENDER_PEARL)
            || mc.player.getOffHandStack().isOf(Items.ENDER_PEARL);
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
            logTickState("unlock-state");
        }

        lockedBlock = null;
        lockX = null;
        lockZ = null;
        primaryAxis = null;
        hardLocked = false;
        freezeX = 0.0;
        freezeZ = 0.0;
        maxDepthThisLock = 0.0;
        hardLockDepth = -1.0;
        bestCenterDistSq = 0.0;
        lastGoodX = 0.0;
        lastGoodZ = 0.0;
        debugLimitLogged = false;
    }

    private void clearArm() {
        armedTicks = 0;
        pearlThrowPos = null;
    }

    private void clearState() {
        clearLock("state-reset");
        clearArm();
        minLockOverlap = 0.0;
    }

    private void logDebug(String message) {
        if (!debugLogs.get()) return;
        debugSeq++;
        info("[dbg#" + debugSeq + "] " + message);
    }

    private void logDebugf(String pattern, Object... args) {
        if (!debugLogs.get()) return;
        logDebug(String.format(pattern, args));
    }

    private void logTickState(String tag) {
        if (!debugLogs.get()) return;
        if (mc.player == null) return;

        String block = lockedBlock == null ? "null" : lockedBlock.toString();
        String axis = primaryAxis == null ? "null" : primaryAxis.name();
        logDebugf("%s pos=(%.4f,%.4f,%.4f) vel=(%.4f,%.4f,%.4f) lock=%s hard=%s axis=%s lockX=%s lockZ=%s freeze=(%.4f,%.4f) depth=%.4f maxDepth=%.4f arm=%d",
            tag,
            mc.player.getX(), mc.player.getY(), mc.player.getZ(),
            mc.player.getVelocity().x, mc.player.getVelocity().y, mc.player.getVelocity().z,
            block, hardLocked, axis, lockX, lockZ, freezeX, freezeZ, getCurrentDepth(), maxDepthThisLock, armedTicks);
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
