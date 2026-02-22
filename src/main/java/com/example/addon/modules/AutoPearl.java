package com.example.addon.modules;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.entity.SortPriority;
import meteordevelopment.meteorclient.utils.entity.TargetUtils;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.BlockState;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

public class AutoPearl extends Module {

    // ── Settings groups ──────────────────────────────────────────────────────

    private final SettingGroup sgGeneral   = settings.getDefaultGroup();
    private final SettingGroup sgTargeting = settings.createGroup("Targeting");
    private final SettingGroup sgRender    = settings.createGroup("Render");

    // General
    private final Setting<Double> range = sgGeneral.add(new DoubleSetting.Builder()
        .name("range")
        .description("Maximum distance to search for targets.")
        .defaultValue(30.0).min(5.0).max(100.0).sliderRange(5.0, 100.0)
        .build());

    private final Setting<Integer> delay = sgGeneral.add(new IntSetting.Builder()
        .name("delay")
        .description("Ticks to wait (while target is standing still) before throwing.")
        .defaultValue(10).min(1).sliderMax(60)
        .build());

    private final Setting<Double> stopRange = sgGeneral.add(new DoubleSetting.Builder()
        .name("stop-range")
        .description("Sword range — stop throwing while within this distance. Resume when target moves away.")
        .defaultValue(4.5).min(1.0).max(20.0).sliderRange(1.0, 10.0)
        .build());

    private final Setting<Boolean> autoDisable = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-disable")
        .description("Automatically disable when no ender pearls remain in hotbar.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> rotate = sgGeneral.add(new BoolSetting.Builder()
        .name("rotate")
        .description("Send look packets to match the calculated throw angle.")
        .defaultValue(true)
        .build());

    // Targeting
    private final Setting<SortPriority> priority = sgTargeting.add(new EnumSetting.Builder<SortPriority>()
        .name("priority")
        .description("Which player to target first when no target is locked.")
        .defaultValue(SortPriority.LowestDistance)
        .build());

    private final Setting<Boolean> ignoreFriends = sgTargeting.add(new BoolSetting.Builder()
        .name("ignore-friends")
        .description("Never throw at players marked as friends.")
        .defaultValue(true)
        .build());

    private final Setting<Double> maxHeightDiff = sgTargeting.add(new DoubleSetting.Builder()
        .name("max-height-diff")
        .description("Skip targets more than this many blocks above the player.")
        .defaultValue(5.0).min(0.0).max(30.0).sliderRange(0.0, 15.0)
        .build());

    private final Setting<Boolean> skipGliding = sgTargeting.add(new BoolSetting.Builder()
        .name("skip-gliding")
        .description("Skip players using elytra and target grounded players instead.")
        .defaultValue(true)
        .build());

    // Render
    private final Setting<Boolean> showRange = sgRender.add(new BoolSetting.Builder()
        .name("show-range")
        .description("Render the search range as a box.")
        .defaultValue(false)
        .build());

    private final Setting<SettingColor> targetColor = sgRender.add(new ColorSetting.Builder()
        .name("target-color")
        .description("Color of the target bounding box.")
        .defaultValue(new SettingColor(255, 50, 50, 180))
        .build());

    private final Setting<SettingColor> rangeColor = sgRender.add(new ColorSetting.Builder()
        .name("range-color")
        .description("Color of the search range box.")
        .defaultValue(new SettingColor(180, 0, 255, 40))
        .build());

    // ── Constants ─────────────────────────────────────────────────────────────

    /** Teleport threshold: position delta larger than this in one tick = pearl landed. */
    private static final double TELEPORT_THRESHOLD = 2.0;
    /** Give up waiting for the pearl after this many ticks (~10 seconds). */
    private static final int    MAX_WAIT_TICKS     = 200;
    /**
     * Target horizontal speed (blocks/tick) below which we treat them as "standing still".
     * Walk ≈ 0.13 b/t, sprint ≈ 0.26 b/t — 0.1 is safely below walk speed.
     */
    private static final double STILL_THRESHOLD    = 0.1;
    /**
     * How many consecutive ticks we must detect the player inside a solid block
     * before starting to break it. Filters out 1-tick false positives from block
     * edges or transitions.
     */
    private static final int    STUCK_CONFIRM_TICKS = 2;

    // ── State ─────────────────────────────────────────────────────────────────

    /** Currently locked target. Cleared only when the target becomes invalid. */
    private PlayerEntity target;
    private int          tickTimer;

    private boolean waitingForTeleport;
    private Vec3d   posBeforeThrow;
    private int     waitTicks;

    /** Previous target reference — used to detect a target switch. */
    private PlayerEntity prevTarget;
    /** Target position last tick — used to compute horizontal movement speed. */
    private Vec3d        prevTargetPos;
    /** Computed horizontal speed of the current target (blocks/tick). */
    private double       targetHorizSpeed;

    /**
     * Consecutive ticks the player has been detected inside a solid block.
     * Reaches STUCK_CONFIRM_TICKS before we start breaking.
     */
    private int stuckTicks;

    public AutoPearl() {
        super(AddonTemplate.CATEGORY, "auto-pearl",
            "Locks onto a target and chases with ender pearls. Throws only when the target is standing still. Breaks free from stuck blocks with a pickaxe.");
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    public void onActivate() {
        target             = null;
        tickTimer          = 0;
        waitingForTeleport = false;
        posBeforeThrow     = null;
        waitTicks          = 0;
        prevTarget         = null;
        prevTargetPos      = null;
        targetHorizSpeed   = 0;
        stuckTicks         = 0;
    }

    @Override
    public void onDeactivate() {
        target             = null;
        waitingForTeleport = false;
        posBeforeThrow     = null;
        prevTarget         = null;
        prevTargetPos      = null;
        stuckTicks         = 0;
    }

    // ── Tick: all game logic ──────────────────────────────────────────────────

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;

        // ── Priority: if inside a solid block, break free with pickaxe ────────
        if (tryBreakFree()) return;

        // ── Phase A: waiting for the pearl to land ────────────────────────────
        if (waitingForTeleport) {
            waitTicks++;

            if (posBeforeThrow != null) {
                double moved = mc.player.getPos().distanceTo(posBeforeThrow);
                if (moved > TELEPORT_THRESHOLD) {
                    waitingForTeleport = false;
                    posBeforeThrow     = null;
                    waitTicks          = 0;
                    tickTimer          = 0;
                    return;
                }
            }

            if (waitTicks >= MAX_WAIT_TICKS) {
                waitingForTeleport = false;
                posBeforeThrow     = null;
                waitTicks          = 0;
                tickTimer          = 0;
            }
            return;
        }

        // ── Phase B: normal operation ─────────────────────────────────────────

        int pearlSlot = findPearlSlot();
        if (pearlSlot == -1) {
            if (autoDisable.get()) {
                warning("No ender pearls in hotbar.");
                toggle();
            }
            return;
        }

        updateTarget();
        if (target == null) return;

        // ── Compute target horizontal speed from position delta ───────────────
        if (target == prevTarget && prevTargetPos != null) {
            double dx = target.getX() - prevTargetPos.x;
            double dz = target.getZ() - prevTargetPos.z;
            targetHorizSpeed = Math.sqrt(dx * dx + dz * dz);
        } else {
            targetHorizSpeed = 0; // new target — assume still until we have a second sample
        }
        prevTarget    = target;
        prevTargetPos = target.getPos();

        double distToTarget = mc.player.distanceTo(target);

        // Within sword range: keep timer ready so we throw the instant the target
        // steps outside stop-range without any extra delay.
        if (distToTarget <= stopRange.get()) {
            tickTimer = delay.get();
            return;
        }

        // Target is moving — reset delay so we count from the moment they stop.
        if (targetHorizSpeed > STILL_THRESHOLD) {
            tickTimer = 0;
            return;
        }

        // Target is standing still — count delay ticks, then throw.
        tickTimer++;
        if (tickTimer < delay.get()) return;

        float yaw   = (float) Rotations.getYaw(target);
        float pitch = calculatePitch(yaw);

        final int slot = pearlSlot;

        posBeforeThrow     = mc.player.getPos();
        waitingForTeleport = true;
        waitTicks          = 0;
        tickTimer          = 0;

        if (rotate.get()) {
            Rotations.rotate(yaw, pitch, () -> doThrow(slot));
        } else {
            doThrow(slot);
        }
    }

    // ── Render ────────────────────────────────────────────────────────────────

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (mc.player == null) return;

        if (showRange.get()) {
            double r = range.get();
            Vec3d  p = mc.player.getPos();
            Box    b = new Box(p.x - r, p.y - r, p.z - r, p.x + r, p.y + r, p.z + r);
            event.renderer.box(b, rangeColor.get(), rangeColor.get(), ShapeMode.Lines, 0);
        }

        if (target != null) {
            event.renderer.box(
                target.getBoundingBox(),
                targetColor.get(), targetColor.get(),
                ShapeMode.Both, 0
            );
        }
    }

    // ── Target selection (with lock) ──────────────────────────────────────────

    /**
     * Returns true if {@code p} is a valid target: alive, in range, not a friend,
     * not gliding (if skip-gliding on), not too far above us.
     */
    private boolean isValidTarget(PlayerEntity p) {
        if (p == mc.player)                                    return false;
        if (p.isDead() || p.getHealth() <= 0)                  return false;
        if (ignoreFriends.get() && Friends.get().isFriend(p))  return false;
        if (skipGliding.get() && p.isGliding())                return false;
        if (p.getY() - mc.player.getY() > maxHeightDiff.get()) return false;
        return mc.player.distanceTo(p) <= range.get();
    }

    /**
     * Keeps the current locked target as long as it remains valid.
     * Only picks a new target when the locked target dies, leaves range,
     * starts gliding, etc. — prevents switching to a player who momentarily
     * steps 1-2 blocks closer mid-chase.
     */
    private void updateTarget() {
        if (target != null && isValidTarget(target)) return; // stay locked
        // Locked target is gone — find a new one
        target = (PlayerEntity) TargetUtils.get(
            entity -> entity instanceof PlayerEntity p && isValidTarget(p),
            priority.get()
        );
    }

    // ── Pitch calculation via trajectory simulation ───────────────────────────

    /**
     * Scans pitch from -5° (flat) to -80° (steep) and picks the angle that
     * minimises the minimum 3D distance to the target's centre during flight.
     * We only throw when the target is standing still, so no movement
     * prediction is needed — the target centre is treated as fixed.
     */
    private float calculatePitch(float yaw) {
        if (target == null) return -30f;

        Vec3d eye     = mc.player.getEyePos();
        Vec3d tCentre = target.getPos().add(0, target.getHeight() * 0.5, 0);

        float  bestPitch = -30f;
        double bestScore = Double.MAX_VALUE;

        for (int deg = -5; deg >= -80; deg--) {
            double score = scorePitch(eye, yaw, deg, tCentre);
            if (score < bestScore) {
                bestScore = score;
                bestPitch = deg;
            }
        }
        return bestPitch;
    }

    /**
     * Simulates the pearl trajectory and returns the minimum 3D distance to
     * {@code tCentre} at any point during flight (mid-air passes count too).
     * Returns {@link Double#MAX_VALUE} if the pearl exits world bounds.
     *
     * Physics: gravity 0.03 b/t², drag 0.99/t, initial speed 1.5 b/t.
     * Lava is NOT checked — pearls travel through lava unimpeded
     * (lava is replaceable in MC) and following a target into lava is intended.
     */
    private double scorePitch(Vec3d eyePos, float yawDeg, float pitchDeg, Vec3d tCentre) {
        double yaw      = Math.toRadians(yawDeg);
        double pitch    = Math.toRadians(pitchDeg);
        double cosPitch = Math.cos(pitch);
        double sinPitch = Math.sin(pitch);

        double velX = -Math.sin(yaw) * cosPitch * 1.5;
        double velY = -sinPitch                  * 1.5;
        double velZ =  Math.cos(yaw) * cosPitch * 1.5;

        double x = eyePos.x, y = eyePos.y, z = eyePos.z;
        double minDist = Double.MAX_VALUE;

        for (int tick = 0; tick < 200; tick++) {
            velY -= 0.03;
            velX *= 0.99;
            velY *= 0.99;
            velZ *= 0.99;

            x += velX;
            y += velY;
            z += velZ;

            if (y < mc.world.getBottomY()) break;

            BlockPos   bp    = BlockPos.ofFloored(x, y, z);
            BlockState state = mc.world.getBlockState(bp);

            if (!state.isAir() && !state.isReplaceable()) {
                // Pearl lands here — score the landing spot and stop
                Vec3d land = new Vec3d(x, y + 1.0, z);
                double d = land.distanceTo(tCentre);
                if (d < minDist) minDist = d;
                break;
            }

            // Score mid-air position (catches close targets where pearl
            // passes through the hitbox without hitting a block first)
            double dx = x - tCentre.x, dy = y - tCentre.y, dz = z - tCentre.z;
            double d  = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (d < minDist) minDist = d;
        }

        return minDist;
    }

    // ── Stuck detection: break free from solid blocks ─────────────────────────

    /**
     * Checks three points along the player's body for solid blocks:
     * <ul>
     *   <li>+0.05 above feet — catches feet-level blocks (obsidian, bedrock)</li>
     *   <li>+0.9 body centre</li>
     *   <li>+1.62 eye level</li>
     * </ul>
     *
     * <p>False-positive filter: if ONLY the feet level is solid AND the player
     * reports being on the ground, they're standing on a partial block (slab,
     * stair) — not stuck. We skip breaking in that case.</p>
     *
     * <p>Confirmation: we require {@link #STUCK_CONFIRM_TICKS} consecutive
     * detections before starting to break, to filter one-tick edge artefacts.</p>
     *
     * @return true if stuck — caller must skip all other logic this tick.
     */
    private boolean tryBreakFree() {
        if (mc.player == null || mc.world == null || mc.interactionManager == null) return false;

        Vec3d pos = mc.player.getPos();

        BlockPos feet = BlockPos.ofFloored(pos.x, pos.y + 0.05, pos.z);
        BlockPos body = BlockPos.ofFloored(pos.x, pos.y + 0.9,  pos.z);
        BlockPos eyes = BlockPos.ofFloored(pos.x, pos.y + 1.62, pos.z);

        boolean feetSolid = isSolid(feet);
        boolean bodySolid = isSolid(body);
        boolean eyesSolid = isSolid(eyes);

        // Standing on a partial block (slab, stair): only feet level is solid
        // AND the engine reports we are on the ground. Skip — not stuck.
        if (feetSolid && !bodySolid && !eyesSolid && mc.player.isOnGround()) {
            stuckTicks = 0;
            return false;
        }

        // Determine which block to target (highest first gives the most space)
        BlockPos stuckPos = null;
        if      (eyesSolid) stuckPos = eyes;
        else if (bodySolid) stuckPos = body;
        else if (feetSolid) stuckPos = feet;

        if (stuckPos == null) {
            stuckTicks = 0;
            return false;
        }

        stuckTicks++;
        if (stuckTicks >= STUCK_CONFIRM_TICKS) {
            breakFreeFromBlock(stuckPos);
        }
        return true; // suspend normal logic while stuck (even during confirmation)
    }

    /**
     * Returns true if {@code pos} contains a solid, non-replaceable, non-fluid block.
     * Lava and water return false (they are replaceable / fluid and do not trap players
     * the same way solid blocks do).
     */
    private boolean isSolid(BlockPos pos) {
        BlockState state = mc.world.getBlockState(pos);
        return !state.isAir()
            && !state.isReplaceable()
            && mc.world.getFluidState(pos).isEmpty();
    }

    /**
     * Swings a pickaxe at {@code pos} to start/continue breaking the block.
     * No-op for unbreakable blocks (hardness &lt; 0, e.g. bedrock).
     * Equips the first pickaxe found in the hotbar, attacks, then swaps back.
     */
    private void breakFreeFromBlock(BlockPos pos) {
        BlockState state = mc.world.getBlockState(pos);
        if (state.getHardness(mc.world, pos) < 0) return; // bedrock — can't break

        int pickSlot = findPickaxeSlot();
        if (pickSlot != -1) InvUtils.swap(pickSlot, false);
        mc.interactionManager.attackBlock(pos, Direction.UP);
        if (pickSlot != -1) InvUtils.swapBack();
    }

    // ── Pearl throw ───────────────────────────────────────────────────────────

    private void doThrow(int pearlSlot) {
        if (mc.player == null || mc.interactionManager == null) return;

        InvUtils.swap(pearlSlot, false);
        mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
        InvUtils.swapBack();
    }

    // ── Utility ───────────────────────────────────────────────────────────────

    private int findPearlSlot() {
        if (mc.player == null) return -1;
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getStack(i).getItem() == Items.ENDER_PEARL) return i;
        }
        return -1;
    }

    /** Returns the hotbar slot of the first pickaxe found, or -1 if none. */
    private int findPickaxeSlot() {
        if (mc.player == null) return -1;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (!stack.isEmpty() && stack.isIn(ItemTags.PICKAXES)) return i;
        }
        return -1;
    }
}
