package com.example.addon.modules;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.game.ReceiveMessageEvent;
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
import meteordevelopment.meteorclient.settings.StringSetting;
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
    private final SettingGroup sgOrbit    = settings.createGroup("Orbit");
    private final SettingGroup sgRemote    = settings.createGroup("Remote Command");
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

    // Orbit
    private final Setting<Integer> botIndex = sgOrbit.add(new IntSetting.Builder()
        .name("bot-index")
        .description("Index of this bot (0-3). Each bot approaches the target from a different side: 0=front, 1=right, 2=back, 3=left. Set a unique index per bot.")
        .defaultValue(0).min(0).max(3).sliderRange(0, 3)
        .build());

    private final Setting<Double> orbitRadius = sgOrbit.add(new DoubleSetting.Builder()
        .name("orbit-radius")
        .description("Ideal distance from target for orbit positioning. Pearls aim at this offset instead of directly at the target.")
        .defaultValue(5.0).min(2.0).max(15.0).sliderRange(2.0, 15.0)
        .build());

    private final Setting<Boolean> enableDescent = sgGeneral.add(new BoolSetting.Builder()
        .name("enable-descent")
        .description("When target is below, throw pearls downward to descend from platforms step by step.")
        .defaultValue(true)
        .build());

    private final Setting<Double> descentStepMax = sgGeneral.add(new DoubleSetting.Builder()
        .name("descent-max-drop")
        .description("Maximum vertical drop per descent pearl (blocks). Larger = more fall damage risk.")
        .defaultValue(10.0).min(3.0).max(30.0).sliderRange(3.0, 30.0)
        .build());

    // Remote Command
    private final Setting<String> commandPrefix = sgRemote.add(new StringSetting.Builder()
        .name("command-prefix")
        .description("Chat command that all bots listen to. Usage: !pearl <nick> | !pearl auto | !pearl on | !pearl off")
        .defaultValue("!pearl")
        .build());

    private final Setting<String> commandPlayer = sgRemote.add(new StringSetting.Builder()
        .name("command-player")
        .description("Only obey commands from this player (your main account nick). Empty = obey anyone.")
        .defaultValue("")
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

    private static final double TELEPORT_THRESHOLD  = 2.0;
    private static final int    MAX_WAIT_TICKS      = 200;
    private static final double STILL_THRESHOLD     = 0.1;
    private static final int    STUCK_CONFIRM_TICKS = 2;

    // ── State ─────────────────────────────────────────────────────────────────

    private PlayerEntity target;
    private int          tickTimer;

    private boolean waitingForTeleport;
    private Vec3d   posBeforeThrow;
    private int     waitTicks;

    private PlayerEntity prevTarget;
    private Vec3d        prevTargetPos;
    private double       targetHorizSpeed;

    private int stuckTicks;

    /** true while performing a multi-step descent (target is below). */
    private boolean descending;

    /**
     * When set via chat command ({@code !pearl Nick}), overrides auto-targeting.
     * All filters (height, gliding, friends) are ignored — the bots chase this
     * player unconditionally. {@code null} = auto-targeting mode.
     */
    private String forcedTargetName;

    public AutoPearl() {
        super(AddonTemplate.CATEGORY, "auto-pearl",
            "Locks onto a target and chases with ender pearls. Supports remote commands via chat (!pearl <nick>).");
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
        descending         = false;
        // forcedTargetName is NOT reset — persists across toggle so you
        // can do !pearl off / !pearl on without losing the forced target.
    }

    @Override
    public void onDeactivate() {
        target             = null;
        waitingForTeleport = false;
        posBeforeThrow     = null;
        prevTarget         = null;
        prevTargetPos      = null;
        stuckTicks         = 0;
        descending         = false;
    }

    // ── Chat command listener ─────────────────────────────────────────────────

    /**
     * Listens to ALL incoming chat messages (even while the module is off)
     * and parses commands like:
     * <ul>
     *   <li>{@code !pearl Bandit}  — force all bots to chase "Bandit"</li>
     *   <li>{@code !pearl auto}    — clear forced target, return to auto-select</li>
     *   <li>{@code !pearl on}      — enable the module</li>
     *   <li>{@code !pearl off}     — disable the module</li>
     * </ul>
     *
     * The sender must match the {@code command-player} setting (if set).
     * Chat format is irrelevant — we search for the prefix anywhere in the message.
     */
    @EventHandler
    private void onChatMessage(ReceiveMessageEvent event) {
        if (mc.player == null) return;

        String msg    = event.getMessage().getString();
        String prefix = commandPrefix.get().trim();
        if (prefix.isEmpty()) return;

        int cmdIdx = msg.indexOf(prefix);
        if (cmdIdx == -1) return;

        // ── Sender check ─────────────────────────────────────────────────────
        String auth = commandPlayer.get().trim();
        if (!auth.isEmpty()) {
            // The authorized player's name must appear before the command.
            // Covers formats like "<Nick> !pearl X", "[Admin] Nick: !pearl X", etc.
            String beforeCmd = msg.substring(0, cmdIdx);
            if (!beforeCmd.toLowerCase().contains(auth.toLowerCase())) return;
        }

        // ── Extract argument ─────────────────────────────────────────────────
        String afterCmd = msg.substring(cmdIdx + prefix.length()).trim();
        if (afterCmd.isEmpty()) return;

        String arg = afterCmd.split("\\s+")[0];

        // ── Dispatch ─────────────────────────────────────────────────────────
        switch (arg.toLowerCase()) {
            case "auto", "reset" -> {
                forcedTargetName = null;
                target           = null;
                info("Target reset to auto-select.");
            }
            case "on" -> {
                if (!isActive()) toggle();
                info("Module enabled via chat command.");
            }
            case "off" -> {
                if (isActive()) toggle();
                info("Module disabled via chat command.");
            }
            default -> {
                // Treat as player name
                forcedTargetName = arg;
                target           = null; // force re-evaluation next tick
                info("Target forced to: §e" + arg);
            }
        }
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
            targetHorizSpeed = 0;
        }
        prevTarget    = target;
        prevTargetPos = target.getPos();

        double distToTarget = mc.player.distanceTo(target);

        // Don't waste a pearl if the target is beyond throw range
        if (distToTarget > range.get()) return;

        // ── Descent: if target is significantly below us, pearl down first ────
        double heightDiff = mc.player.getY() - target.getY();
        if (enableDescent.get() && heightDiff > 3.0 && distToTarget > stopRange.get()) {
            descending = true;
            if (tryDescentPearl(pearlSlot)) return;
        } else {
            descending = false;
        }

        // Within sword range: keep timer ready for instant throw when they leave
        if (distToTarget <= stopRange.get()) {
            tickTimer = delay.get();
            return;
        }

        // Target is moving — reset delay, wait for them to stop
        if (targetHorizSpeed > STILL_THRESHOLD) {
            tickTimer = 0;
            return;
        }

        // Target is standing still — count delay ticks, then throw
        tickTimer++;
        if (tickTimer < delay.get()) return;

        // ── Apply orbit offset to yaw ──────────────────────────────────────────
        float baseYaw = (float) Rotations.getYaw(target);
        float yaw     = applyOrbitOffset(baseYaw, distToTarget);
        float pitch   = calculatePitch(yaw);

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

    // ── Target selection ──────────────────────────────────────────────────────

    /** Auto-target filter: alive, in range, not a friend, not gliding, not too high. */
    private boolean isValidTarget(PlayerEntity p) {
        if (p == mc.player)                                    return false;
        if (p.isDead() || p.getHealth() <= 0)                  return false;
        if (ignoreFriends.get() && Friends.get().isFriend(p))  return false;
        if (skipGliding.get() && p.isGliding())                return false;
        if (p.getY() - mc.player.getY() > maxHeightDiff.get()) return false;
        return mc.player.distanceTo(p) <= range.get();
    }

    /**
     * Resolves the current target:
     * <ol>
     *   <li><b>Forced target</b> ({@code !pearl Nick}) — find player by name,
     *       no filters applied except alive + not self. All bots chase the same
     *       player regardless of range, height, friends, etc.</li>
     *   <li><b>Auto-target with lock</b> — keep current target if still valid,
     *       otherwise pick a new one by priority.</li>
     * </ol>
     */
    private void updateTarget() {
        // ── Forced target mode ───────────────────────────────────────────────
        if (forcedTargetName != null) {
            PlayerEntity forced = findPlayerByName(forcedTargetName);
            if (forced != null && forced != mc.player
                && !forced.isDead() && forced.getHealth() > 0) {
                target = forced;
                return;
            }
            // Not in render distance or dead — keep waiting, don't fall back
            target = null;
            return;
        }

        // ── Auto-target with lock ────────────────────────────────────────────
        if (target != null && isValidTarget(target)) return;
        target = (PlayerEntity) TargetUtils.get(
            entity -> entity instanceof PlayerEntity p && isValidTarget(p),
            priority.get()
        );
    }

    /**
     * Finds a player in the current world by exact name (case-insensitive).
     * Returns null if the player is not loaded (not in render distance).
     */
    private PlayerEntity findPlayerByName(String name) {
        if (mc.world == null || name == null) return null;
        for (PlayerEntity p : mc.world.getPlayers()) {
            if (p.getGameProfile().getName().equalsIgnoreCase(name)) return p;
        }
        return null;
    }

    // ── Pitch calculation via trajectory simulation ───────────────────────────

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
                Vec3d land = new Vec3d(x, y + 1.0, z);
                double d = land.distanceTo(tCentre);
                if (d < minDist) minDist = d;
                break;
            }

            double dx = x - tCentre.x, dy = y - tCentre.y, dz = z - tCentre.z;
            double d  = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (d < minDist) minDist = d;
        }

        return minDist;
    }

    // ── Vertical descent ───────────────────────────────────────────────────────

    /**
     * Attempts to throw a pearl downward to descend from a platform.
     * Strategy: scan outward from the player for a platform edge, then throw
     * a pearl at the ground below it (limited by descentStepMax).
     * Returns true if a descent pearl was thrown (caller should return).
     */
    private boolean tryDescentPearl(int pearlSlot) {
        if (mc.player == null || mc.world == null || target == null) return false;

        Vec3d playerPos = mc.player.getPos();
        double targetY  = target.getY();
        double maxDrop  = descentStepMax.get();

        // Direction toward target (horizontal)
        double dx = target.getX() - playerPos.x;
        double dz = target.getZ() - playerPos.z;
        double hDist = Math.sqrt(dx * dx + dz * dz);
        if (hDist < 0.01) return false;
        double dirX = dx / hDist;
        double dirZ = dz / hDist;

        // Scan outward up to 5 blocks for an edge (air below feet level)
        Vec3d edgePos = null;
        for (int i = 1; i <= 5; i++) {
            double sx = playerPos.x + dirX * i;
            double sz = playerPos.z + dirZ * i;
            BlockPos below = BlockPos.ofFloored(sx, playerPos.y - 1, sz);
            if (mc.world.getBlockState(below).isAir() || mc.world.getBlockState(below).isReplaceable()) {
                edgePos = new Vec3d(sx, playerPos.y, sz);
                break;
            }
        }

        // No edge found — platform extends, try throwing at target directly
        if (edgePos == null) return false;

        // Find landing Y: scan downward from edge for a solid block
        double landY = playerPos.y;
        for (int dy = 1; dy <= (int) maxDrop; dy++) {
            BlockPos check = BlockPos.ofFloored(edgePos.x, playerPos.y - dy, edgePos.z);
            BlockState state = mc.world.getBlockState(check);
            if (!state.isAir() && !state.isReplaceable()) {
                landY = playerPos.y - dy + 1;
                break;
            }
            if (playerPos.y - dy <= targetY) {
                landY = targetY;
                break;
            }
        }

        // Don't bother descending if we'd only drop 1-2 blocks
        if (playerPos.y - landY < 2.0) return false;

        // Calculate yaw/pitch toward the landing spot
        Vec3d landSpot = new Vec3d(edgePos.x, landY, edgePos.z);
        double ldx = landSpot.x - playerPos.x;
        double ldz = landSpot.z - playerPos.z;
        float yaw = (float) Math.toDegrees(-Math.atan2(ldx, ldz));
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
        return true;
    }

    // ── Pearl orbit offset ───────────────────────────────────────────────────────

    /**
     * Applies an angular offset to the yaw based on bot-index.
     * When bots are far from the target, they approach from different angles
     * (0°, 90°, 180°, 270°) creating a surrounding formation.
     * As the bot gets closer than orbit-radius, the offset decreases to zero
     * so the bot converges on the actual target position for combat.
     */
    private float applyOrbitOffset(float baseYaw, double distToTarget) {
        int index = botIndex.get();
        if (index == 0) return baseYaw; // bot 0 goes straight at target

        double radius = orbitRadius.get();

        // Smoothly reduce offset as bot approaches: full offset at 2×radius, zero at stopRange
        double fadeStart = radius * 2.0;
        double fadeEnd   = stopRange.get();
        double factor    = 1.0;
        if (distToTarget < fadeStart) {
            factor = Math.max(0, (distToTarget - fadeEnd) / (fadeStart - fadeEnd));
        }

        float offsetDeg = index * 90.0f * (float) factor;
        return baseYaw + offsetDeg;
    }

    // ── Stuck detection: break free from solid blocks ─────────────────────────

    private boolean tryBreakFree() {
        if (mc.player == null || mc.world == null || mc.interactionManager == null) return false;

        Vec3d pos = mc.player.getPos();

        BlockPos feet = BlockPos.ofFloored(pos.x, pos.y + 0.05, pos.z);
        BlockPos body = BlockPos.ofFloored(pos.x, pos.y + 0.9,  pos.z);
        BlockPos eyes = BlockPos.ofFloored(pos.x, pos.y + 1.62, pos.z);

        boolean feetSolid = isSolid(feet);
        boolean bodySolid = isSolid(body);
        boolean eyesSolid = isSolid(eyes);

        if (feetSolid && !bodySolid && !eyesSolid && mc.player.isOnGround()) {
            stuckTicks = 0;
            return false;
        }

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
        return true;
    }

    private boolean isSolid(BlockPos pos) {
        BlockState state = mc.world.getBlockState(pos);
        return !state.isAir()
            && !state.isReplaceable()
            && mc.world.getFluidState(pos).isEmpty();
    }

    private void breakFreeFromBlock(BlockPos pos) {
        BlockState state = mc.world.getBlockState(pos);
        if (state.getHardness(mc.world, pos) < 0) return;

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

    private int findPickaxeSlot() {
        if (mc.player == null) return -1;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (!stack.isEmpty() && stack.isIn(ItemTags.PICKAXES)) return i;
        }
        return -1;
    }
}
