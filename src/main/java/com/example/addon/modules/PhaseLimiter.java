package com.example.addon.modules;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.entity.player.PlayerMoveEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.mixininterface.IVec3d;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.block.BlockState;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

public class PhaseLimiter extends Module {

    // ── Setting groups ──────────────────────────────────────────────────────

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgDebug   = settings.createGroup("Debug");

    // ── General settings ────────────────────────────────────────────────────

    private final Setting<Double> maxDepth = sgGeneral.add(new DoubleSetting.Builder()
        .name("max-depth")
        .description("How far the player CENTER may cross the entry face. 0 = at face, 0.5 = block middle, 1.0 = opposite face.")
        .defaultValue(0.40)
        .min(0.01).max(0.90)
        .sliderRange(0.05, 0.90)
        .build());

    private final Setting<Boolean> lockVertical = sgGeneral.add(new BoolSetting.Builder()
        .name("lock-vertical")
        .description("Also prevent vertical movement while locked.")
        .defaultValue(false)
        .build());

    private final Setting<Boolean> allowSneak = sgGeneral.add(new BoolSetting.Builder()
        .name("allow-sneak-unlock")
        .description("Shift to release lock.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> allowJump = sgGeneral.add(new BoolSetting.Builder()
        .name("allow-jump-unlock")
        .description("Space to release lock.")
        .defaultValue(true)
        .build());

    private final Setting<Integer> unlockGrace = sgGeneral.add(new IntSetting.Builder()
        .name("unlock-grace-ticks")
        .description("Consecutive ticks depth must be ~0 before auto-unlock.")
        .defaultValue(5)
        .min(1).max(20).sliderRange(1, 20)
        .build());

    private final Setting<Double> yTeleportThreshold = sgGeneral.add(new DoubleSetting.Builder()
        .name("y-teleport-threshold")
        .description("Per-tick Y change that counts as a server teleport and triggers unlock.")
        .defaultValue(0.5)
        .min(0.1).max(2.0)
        .sliderRange(0.1, 2.0)
        .build());

    // ── Debug settings ──────────────────────────────────────────────────────

    private final Setting<Boolean> observeOnly = sgDebug.add(new BoolSetting.Builder()
        .name("observe-only")
        .description("Never lock — only log depth. Use to find the real kick threshold.")
        .defaultValue(false)
        .build());

    private final Setting<Boolean> debugChat = sgDebug.add(new BoolSetting.Builder()
        .name("debug-chat")
        .description("Print LOCK / UNLOCK events to chat.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> debugVerbose = sgDebug.add(new BoolSetting.Builder()
        .name("debug-verbose")
        .description("Print every-tick depth to chat (spammy).")
        .defaultValue(false)
        .build());

    private final Setting<Boolean> logToClipboard = sgDebug.add(new BoolSetting.Builder()
        .name("log-to-clipboard")
        .description("Copy the full debug log to clipboard when module is deactivated.")
        .defaultValue(true)
        .build());

    // ── Entry face ──────────────────────────────────────────────────────────

    private enum Face {
        WEST, EAST, NORTH, SOUTH;

        /**
         * Distance from the player CENTER to this face, measured inward.
         * Positive = center has crossed the face into the block.
         * Negative = center is still outside.
         */
        double centerPenetration(double cx, double cz, int bx, int bz) {
            return switch (this) {
                case WEST  -> cx - bx;            // center past west face → east
                case EAST  -> (bx + 1.0) - cx;    // center past east face → west
                case NORTH -> cz - bz;            // center past north face → south
                case SOUTH -> (bz + 1.0) - cz;    // center past south face → north
            };
        }

        /**
         * Detect entry face when center is inside block on both axes.
         * Picks the face closest to center (= the face the player just crossed).
         */
        static Face detectNearest(double cx, double cz, int bx, int bz) {
            double dW = cx - bx, dE = (bx + 1.0) - cx;
            double dN = cz - bz, dS = (bz + 1.0) - cz;
            double min = Math.min(Math.min(dW, dE), Math.min(dN, dS));
            if      (min == dW) return WEST;
            else if (min == dE) return EAST;
            else if (min == dN) return NORTH;
            else                return SOUTH;
        }
    }

    // ── State ───────────────────────────────────────────────────────────────

    private BlockPos trackedBlock;
    private Face     trackedFace;

    private boolean  locked;
    private Vec3d    lockedPos;
    private BlockPos lockedBlock;
    private Face     lockedFace;

    private double lastDepth;
    private double peakDepth;
    private double lastTickY;
    private int    lockTicks;
    private int    zeroDepthTicks;
    private int    totalTicks;

    private final StringBuilder debugLog = new StringBuilder();

    // ── Constructor ─────────────────────────────────────────────────────────

    public PhaseLimiter() {
        super(AddonTemplate.CATEGORY, "phase-limiter",
            "Freezes horizontal movement when you phase into a block to prevent server kick.");
    }

    @Override
    public void onActivate() {
        locked = false;
        lockedPos = null;
        lockedBlock = null;
        lockedFace = null;
        trackedBlock = null;
        trackedFace = null;
        lastDepth = 0;
        peakDepth = 0;
        lastTickY = mc.player != null ? mc.player.getY() : 0;
        lockTicks = 0;
        zeroDepthTicks = 0;
        totalTicks = 0;
        debugLog.setLength(0);
        log("=== PhaseLimiter ON | maxDepth=%.3f observe=%s (center-from-entry-face) ===",
            maxDepth.get(), observeOnly.get());
    }

    @Override
    public void onDeactivate() {
        if (locked) unlock("module disabled");

        log("=== PhaseLimiter OFF | peakDepth=%.4f totalTicks=%d ===", peakDepth, totalTicks);

        if (logToClipboard.get() && debugLog.length() > 0 && mc.keyboard != null) {
            mc.keyboard.setClipboard(debugLog.toString());
            info("Debug log copied to clipboard (%d chars)", debugLog.length());
        }

        clearTracking();
    }

    // ── Tick ────────────────────────────────────────────────────────────────

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;
        totalTicks++;

        double currentY = mc.player.getY();

        // ── Y teleport detection (ALL states) ───────────────────────────
        double yDelta = Math.abs(currentY - lastTickY);
        if (yDelta > yTeleportThreshold.get()) {
            log("t=%d Y_TELEPORT yDelta=%.4f (%.4f -> %.4f) locked=%s",
                totalTicks, yDelta, lastTickY, currentY, locked);
            if (locked) unlock("server Y teleport");
            clearTracking();
            lastDepth = 0;
            lastTickY = currentY;
            return;
        }
        lastTickY = currentY;

        // ── Manual unlock ───────────────────────────────────────────────
        if (locked) {
            if (allowSneak.get() && mc.options.sneakKey.isPressed()) {
                unlock("sneak pressed");
                clearTracking();
                return;
            }
            if (allowJump.get() && mc.options.jumpKey.isPressed()) {
                unlock("jump pressed");
                clearTracking();
                return;
            }
        }

        // ── Re-snap X/Z BEFORE depth calc when locked ───────────────────
        if (locked && lockedPos != null) {
            mc.player.setPosition(lockedPos.x, mc.player.getY(), lockedPos.z);
        }

        // ── Depth calculation ───────────────────────────────────────────
        double depth;

        if (locked && lockedBlock != null && lockedFace != null) {
            // Y level check
            int footY = (int) Math.floor(mc.player.getBoundingBox().minY + 0.02);
            if (footY != lockedBlock.getY()) {
                log("t=%d Y_LEVEL_MISMATCH footY=%d blockY=%d", totalTicks, footY, lockedBlock.getY());
                unlock("Y level changed");
                clearTracking();
                return;
            }
            // Block still solid?
            BlockState state = mc.world.getBlockState(lockedBlock);
            if (!state.isFullCube(mc.world, lockedBlock)) {
                unlock("block broken/removed");
                clearTracking();
                return;
            }
            depth = calcDepth(mc.player.getBoundingBox(), lockedBlock, lockedFace);
        } else {
            depth = scanPhaseDepth();
        }

        lastDepth = depth;
        if (depth > peakDepth) peakDepth = depth;

        // ── Locked state ────────────────────────────────────────────────
        if (locked) {
            lockTicks++;

            if (depth < 0.001) {
                zeroDepthTicks++;
                if (zeroDepthTicks >= unlockGrace.get()) {
                    log("t=%d UNLOCK reason=left_block zeroFor=%d", totalTicks, zeroDepthTicks);
                    unlock("left block (depth ~0)");
                    clearTracking();
                    return;
                }
            } else {
                zeroDepthTicks = 0;
            }

            if (debugVerbose.get()) {
                debugMsg(String.format("§7[LOCKED t=%d] depth=%.4f face=%s zero=%d pos=(%.4f, %.4f, %.4f)",
                    lockTicks, depth, lockedFace, zeroDepthTicks,
                    mc.player.getX(), mc.player.getY(), mc.player.getZ()));
                log("t=%d LOCKED depth=%.4f face=%s pos=(%.4f,%.4f,%.4f)",
                    totalTicks, depth, lockedFace,
                    mc.player.getX(), mc.player.getY(), mc.player.getZ());
            }

        // ── Unlocked state ──────────────────────────────────────────────
        } else {
            // Clear tracking when depth falls to 0
            if (depth < 0.001 && trackedBlock != null) {
                trackedBlock = null;
                trackedFace = null;
            }

            if (depth > 0.001) {
                if (debugVerbose.get()) {
                    debugMsg(String.format("§7[PHASE] depth=%.4f / %.3f face=%s block=%s",
                        depth, maxDepth.get(), trackedFace,
                        trackedBlock != null ? trackedBlock.toShortString() : "?"));
                }
                log("t=%d PHASE depth=%.4f face=%s block=%s pos=(%.4f,%.4f,%.4f)",
                    totalTicks, depth, trackedFace,
                    trackedBlock != null ? trackedBlock.toShortString() : "?",
                    mc.player.getX(), mc.player.getY(), mc.player.getZ());

                if (!observeOnly.get() && depth >= maxDepth.get()
                        && trackedBlock != null && trackedFace != null) {
                    lock(depth);
                }
            }
        }
    }

    // ── Move: freeze position when locked ──────────────────────────────────

    @EventHandler(priority = EventPriority.HIGH)
    private void onMove(PlayerMoveEvent event) {
        if (!locked || lockedPos == null || mc.player == null) return;

        double yMovement = lockVertical.get() ? 0 : event.movement.y;
        ((IVec3d) event.movement).meteor$set(0, yMovement, 0);
        mc.player.setPosition(lockedPos.x, mc.player.getY(), lockedPos.z);
    }

    // ── Depth calc: distance from CENTER to entry face ──────────────────────

    /**
     * Center-based depth from a specific entry face.
     * Returns how far the player CENTER has crossed the given face, inward.
     * Negative values (center outside) are clamped to 0.
     */
    private static double calcDepth(Box bb, BlockPos block, Face face) {
        double cx = (bb.minX + bb.maxX) / 2.0;
        double cz = (bb.minZ + bb.maxZ) / 2.0;
        double pen = face.centerPenetration(cx, cz, block.getX(), block.getZ());
        return Math.max(0, pen);
    }

    // ── Phase depth scan ────────────────────────────────────────────────────

    /**
     * Scans for solid blocks the player's BB overlaps, detects entry face
     * on first contact, and measures center-based depth from that face.
     * <p>
     * The tracked block+face persist between ticks — depth always increases
     * monotonically along the entry axis as the player walks deeper.
     */
    private double scanPhaseDepth() {
        if (mc.player == null || mc.world == null) return 0;

        Box bb = mc.player.getBoundingBox();
        double cx = (bb.minX + bb.maxX) / 2.0;
        double cz = (bb.minZ + bb.maxZ) / 2.0;
        int playerFootY = (int) Math.floor(bb.minY + 0.02);

        // ── Reuse tracked block+face if still valid ─────────────────────
        if (trackedBlock != null && trackedFace != null) {
            if (trackedBlock.getY() != playerFootY) {
                trackedBlock = null;
                trackedFace = null;
            } else {
                BlockState state = mc.world.getBlockState(trackedBlock);
                if (state.isFullCube(mc.world, trackedBlock)) {
                    double d = calcDepth(bb, trackedBlock, trackedFace);
                    if (d > 0) return d;
                }
                trackedBlock = null;
                trackedFace = null;
            }
        }

        // ── Full scan ───────────────────────────────────────────────────
        int bY = playerFootY;
        int minBX = (int) Math.floor(bb.minX);
        int maxBX = (int) Math.floor(bb.maxX);
        int minBZ = (int) Math.floor(bb.minZ);
        int maxBZ = (int) Math.floor(bb.maxZ);

        double bestDepth = 0;
        BlockPos bestBlock = null;
        Face bestFace = null;

        BlockPos.Mutable mutable = new BlockPos.Mutable();

        for (int bx = minBX; bx <= maxBX; bx++) {
            for (int bz = minBZ; bz <= maxBZ; bz++) {
                mutable.set(bx, bY, bz);
                BlockState state = mc.world.getBlockState(mutable);
                if (!state.isFullCube(mc.world, mutable)) continue;

                // Determine entry face
                boolean insideX = cx > bx && cx < bx + 1;
                boolean insideZ = cz > bz && cz < bz + 1;
                Face face;

                if (!insideX && !insideZ) {
                    continue; // corner overlap
                } else if (!insideX) {
                    face = cx <= bx ? Face.WEST : Face.EAST;
                } else if (!insideZ) {
                    face = cz <= bz ? Face.NORTH : Face.SOUTH;
                } else {
                    // Center inside both axes — use nearest face
                    face = Face.detectNearest(cx, cz, bx, bz);
                }

                double d = calcDepth(bb, mutable, face);
                if (d > bestDepth) {
                    bestDepth = d;
                    bestBlock = mutable.toImmutable();
                    bestFace = face;
                }
            }
        }

        if (bestBlock != null) {
            trackedBlock = bestBlock;
            trackedFace = bestFace;
        }

        return bestDepth;
    }

    // ── Lock / Unlock ───────────────────────────────────────────────────────

    private void lock(double rawDepth) {
        locked = true;
        lockedBlock = trackedBlock;
        lockedFace = trackedFace;
        lockTicks = 0;
        zeroDepthTicks = 0;

        // Snap position back to exactly maxDepth on the entry axis
        // so per-tick overshoot doesn't push us past the safe limit.
        double target = maxDepth.get();
        double px = mc.player.getX();
        double pz = mc.player.getZ();
        int bx = lockedBlock.getX();
        int bz = lockedBlock.getZ();

        if (lockedFace != null) {
            switch (lockedFace) {
                case WEST  -> px = bx + target;              // cx = bx + depth
                case EAST  -> px = (bx + 1.0) - target;     // cx = bx+1 - depth
                case NORTH -> pz = bz + target;              // cz = bz + depth
                case SOUTH -> pz = (bz + 1.0) - target;     // cz = bz+1 - depth
            }
        }

        lockedPos = new Vec3d(px, mc.player.getY(), pz);
        mc.player.setPosition(lockedPos.x, lockedPos.y, lockedPos.z);
        double finalDepth = calcDepth(mc.player.getBoundingBox(), lockedBlock, lockedFace);

        String msg = String.format(
            "§a[LOCKED] depth=%.4f→%.4f face=%s at (%.4f, %.4f, %.4f) | block=%s",
            rawDepth, finalDepth, lockedFace,
            lockedPos.x, lockedPos.y, lockedPos.z,
            lockedBlock != null ? lockedBlock.toShortString() : "?");

        if (debugChat.get()) debugMsg(msg);
        log("t=%d LOCK raw=%.4f snapped=%.4f face=%s pos=(%.4f,%.4f,%.4f) block=%s",
            totalTicks, rawDepth, finalDepth, lockedFace,
            lockedPos.x, lockedPos.y, lockedPos.z,
            lockedBlock != null ? lockedBlock.toShortString() : "?");

        info("Position locked (depth %.3f)", finalDepth);
    }

    private void unlock(String reason) {
        if (!locked) return;

        String msg = String.format("§c[UNLOCKED] reason=%s after %d ticks | depth=%.4f peak=%.4f",
            reason, lockTicks, lastDepth, peakDepth);

        if (debugChat.get()) debugMsg(msg);
        log("t=%d UNLOCK reason=%s lockTicks=%d depth=%.4f peak=%.4f",
            totalTicks, reason, lockTicks, lastDepth, peakDepth);

        locked = false;
        lockedPos = null;
        lockedBlock = null;
        lockedFace = null;
        lockTicks = 0;
        zeroDepthTicks = 0;

        info("Position unlocked (%s)", reason);
    }

    private void clearTracking() {
        trackedBlock = null;
        trackedFace = null;
    }

    // ── Debug helpers ───────────────────────────────────────────────────────

    private void debugMsg(String msg) {
        if (mc.player == null) return;
        ChatUtils.sendMsg(Text.literal("[PhaseLimiter] " + msg));
    }

    private void log(String fmt, Object... args) {
        debugLog.append(String.format(fmt, args)).append('\n');
    }

    // ── HUD info string ─────────────────────────────────────────────────────

    @Override
    public String getInfoString() {
        if (observeOnly.get()) {
            if (lastDepth > 0.001) return String.format("OBS %.3f pk%.3f", lastDepth, peakDepth);
            return "OBS";
        }
        if (locked) return String.format("LOCKED %.3f", lastDepth);
        if (lastDepth > 0.001) return String.format("%.3f", lastDepth);
        return null;
    }
}
