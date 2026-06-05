package com.devils.addon.modules;

import com.devils.addon.DevilsAddon;
import com.devils.addon.modules.autopearl.AutoPearlTrajectory;
import com.devils.addon.util.CrashGuard;
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
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.entity.SortPriority;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.BlockState;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

public class AutoPearl extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgTargeting = settings.createGroup("Targeting");
    private final SettingGroup sgOrbit = settings.createGroup("Orbit");
    private final SettingGroup sgRemote = settings.createGroup("Remote Command");
    private final SettingGroup sgRender = settings.createGroup("Render");

    private final Setting<Double> range = sgGeneral.add(new DoubleSetting.Builder().name("range").description("Maximum distance to search for targets.").defaultValue(30.0).min(5.0).max(100.0).sliderRange(5.0, 100.0).build());
    private final Setting<Integer> delay = sgGeneral.add(new IntSetting.Builder().name("delay").description("Ticks to wait (while target is standing still) before throwing.").defaultValue(10).min(1).sliderMax(60).build());
    private final Setting<Double> stopRange = sgGeneral.add(new DoubleSetting.Builder().name("stop-range").description("Sword range вЂ” stop throwing while within this distance. Resume when target moves away.").defaultValue(4.5).min(1.0).max(20.0).sliderRange(1.0, 10.0).build());
    private final Setting<Boolean> autoDisable = sgGeneral.add(new BoolSetting.Builder().name("auto-disable").description("Automatically disable when no ender pearls remain in hotbar.").defaultValue(true).build());
    private final Setting<Boolean> rotate = sgGeneral.add(new BoolSetting.Builder().name("rotate").description("Send look packets to match the calculated throw angle.").defaultValue(true).build());
    private final Setting<SortPriority> priority = sgTargeting.add(new EnumSetting.Builder<SortPriority>().name("priority").description("Which player to target first when no target is locked.").defaultValue(SortPriority.LowestDistance).build());
    private final Setting<Boolean> ignoreFriends = sgTargeting.add(new BoolSetting.Builder().name("ignore-friends").description("Never throw at players marked as friends.").defaultValue(true).build());
    private final Setting<Double> maxHeightDiff = sgTargeting.add(new DoubleSetting.Builder().name("max-height-diff").description("Skip targets more than this many blocks above the player.").defaultValue(5.0).min(0.0).max(30.0).sliderRange(0.0, 15.0).build());
    private final Setting<Boolean> skipGliding = sgTargeting.add(new BoolSetting.Builder().name("skip-gliding").description("Skip players using elytra and target grounded players instead.").defaultValue(true).build());
    private final Setting<Integer> botIndex = sgOrbit.add(new IntSetting.Builder().name("bot-index").description("Index of this bot (0-3). Each bot approaches the target from a different side: 0=front, 1=right, 2=back, 3=left. Set a unique index per bot.").defaultValue(0).min(0).max(3).sliderRange(0, 3).build());
    private final Setting<Double> orbitRadius = sgOrbit.add(new DoubleSetting.Builder().name("orbit-radius").description("Ideal distance from target for orbit positioning. Pearls aim at this offset instead of directly at the target.").defaultValue(5.0).min(2.0).max(15.0).sliderRange(2.0, 15.0).build());
    private final Setting<Boolean> enableDescent = sgGeneral.add(new BoolSetting.Builder().name("enable-descent").description("When target is below, throw pearls downward to descend from platforms step by step.").defaultValue(true).build());
    private final Setting<Double> descentStepMax = sgGeneral.add(new DoubleSetting.Builder().name("descent-max-drop").description("Maximum vertical drop per descent pearl (blocks). Larger = more fall damage risk.").defaultValue(10.0).min(3.0).max(30.0).sliderRange(3.0, 30.0).build());
    public final Setting<String> commandPrefix = sgRemote.add(new StringSetting.Builder().name("command-prefix").description("Chat command that all bots listen to. Usage: !pearl <nick> | !pearl auto | !pearl on | !pearl off").defaultValue("!pearl").build());
    public final Setting<String> commandPlayer = sgRemote.add(new StringSetting.Builder().name("command-player").description("Only obey commands from this player (your main account nick). Empty = obey anyone.").defaultValue("").build());
    private final Setting<Boolean> showRange = sgRender.add(new BoolSetting.Builder().name("show-range").description("Render the search range as a box.").defaultValue(false).build());
    private final Setting<SettingColor> targetColor = sgRender.add(new ColorSetting.Builder().name("target-color").description("Color of the target bounding box.").defaultValue(new SettingColor(255, 50, 50, 180)).build());
    private final Setting<SettingColor> rangeColor = sgRender.add(new ColorSetting.Builder().name("range-color").description("Color of the search range box.").defaultValue(new SettingColor(180, 0, 255, 40)).build());

    private static final double TELEPORT_THRESHOLD = 2.0;
    private static final int MAX_WAIT_TICKS = 200;
    private static final double STILL_THRESHOLD = 0.1;
    public static final int STUCK_CONFIRM_TICKS = 2;

    public final AutoPearlTrajectory.RemoteControl remoteControl = new AutoPearlTrajectory.RemoteControl(this);
    public final AutoPearlTrajectory.EscapeHelper escapeHelper = new AutoPearlTrajectory.EscapeHelper(this);

    public PlayerEntity target;
    private int tickTimer;
    private boolean waitingForTeleport;
    private Vec3d posBeforeThrow;
    private int waitTicks;
    private PlayerEntity prevTarget;
    private Vec3d prevTargetPos;
    private double targetHorizSpeed;
    public int stuckTicks;
    public String forcedTargetName;

    public AutoPearl() {
        super(DevilsAddon.CATEGORY, "auto-pearl", "Locks onto a target and chases with ender pearls. Supports remote commands via chat (!pearl <nick>).");
    }

    @Override
    public void onActivate() {
        target = null;
        tickTimer = 0;
        waitingForTeleport = false;
        posBeforeThrow = null;
        waitTicks = 0;
        prevTarget = null;
        prevTargetPos = null;
        targetHorizSpeed = 0;
        stuckTicks = 0;
    }

    @Override
    public void onDeactivate() {
        target = null;
        waitingForTeleport = false;
        posBeforeThrow = null;
        prevTarget = null;
        prevTargetPos = null;
        stuckTicks = 0;
    }

    @EventHandler
    private void onChatMessage(ReceiveMessageEvent event) {
        CrashGuard.run(this, "onChatMessage", () -> onChatMessageSafe(event));
    }

    private void onChatMessageSafe(ReceiveMessageEvent event) {
        remoteControl.handleChat(event);
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        CrashGuard.run(this, "onTickPre", () -> onTickSafe(event));
    }

    private void onTickSafe(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;
        if (escapeHelper.tryBreakFree()) return;

        if (waitingForTeleport) {
            waitTicks++;
            if (posBeforeThrow != null && mc.player.getEntityPos().distanceTo(posBeforeThrow) > TELEPORT_THRESHOLD) {
                waitingForTeleport = false;
                posBeforeThrow = null;
                waitTicks = 0;
                tickTimer = 0;
                return;
            }
            if (waitTicks >= MAX_WAIT_TICKS) {
                waitingForTeleport = false;
                posBeforeThrow = null;
                waitTicks = 0;
                tickTimer = 0;
            }
            return;
        }

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

        if (target == prevTarget && prevTargetPos != null) {
            double dx = target.getX() - prevTargetPos.x;
            double dz = target.getZ() - prevTargetPos.z;
            targetHorizSpeed = Math.sqrt(dx * dx + dz * dz);
        } else {
            targetHorizSpeed = 0;
        }
        prevTarget = target;
        prevTargetPos = target.getEntityPos();

        double distToTarget = mc.player.distanceTo(target);
        if (distToTarget > range.get()) return;

        double heightDiff = mc.player.getY() - target.getY();
        if (enableDescent.get() && heightDiff > 3.0 && distToTarget > stopRange.get() && tryDescentPearl(pearlSlot)) return;
        if (distToTarget <= stopRange.get()) {
            tickTimer = delay.get();
            return;
        }
        if (targetHorizSpeed > STILL_THRESHOLD) {
            tickTimer = 0;
            return;
        }

        tickTimer++;
        if (tickTimer < delay.get()) return;

        float baseYaw = (float) Rotations.getYaw(target);
        float yaw = applyOrbitOffset(baseYaw, distToTarget);
        float pitch = AutoPearlTrajectory.pitchToTarget(mc, target, yaw, -30f);

        posBeforeThrow = mc.player.getEntityPos();
        waitingForTeleport = true;
        waitTicks = 0;
        tickTimer = 0;

        if (rotate.get()) Rotations.rotate(yaw, pitch, () -> doThrow(pearlSlot));
        else doThrow(pearlSlot);
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        CrashGuard.run(this, "onRender3D", () -> onRender3DSafe(event));
    }

    private void onRender3DSafe(Render3DEvent event) {
        if (mc.player == null) return;

        if (showRange.get()) {
            double r = range.get();
            Vec3d p = mc.player.getEntityPos();
            Box b = new Box(p.x - r, p.y - r, p.z - r, p.x + r, p.y + r, p.z + r);
            event.renderer.box(b, rangeColor.get(), rangeColor.get(), ShapeMode.Lines, 0);
        }

        if (target != null) {
            event.renderer.box(target.getBoundingBox(), targetColor.get(), targetColor.get(), ShapeMode.Both, 0);
        }
    }

    private void updateTarget() {
        target = AutoPearlTrajectory.Targeting.resolveTarget(
            mc,
            target,
            forcedTargetName,
            priority.get(),
            player -> AutoPearlTrajectory.Targeting.isValidTarget(
                mc,
                player,
                ignoreFriends.get(),
                skipGliding.get(),
                maxHeightDiff.get(),
                range.get()
            )
        );
    }

    private boolean tryDescentPearl(int pearlSlot) {
        if (mc.player == null || mc.world == null || target == null) return false;

        Vec3d playerPos = mc.player.getEntityPos();
        double targetY = target.getY();
        double maxDrop = descentStepMax.get();

        double dx = target.getX() - playerPos.x;
        double dz = target.getZ() - playerPos.z;
        double hDist = Math.sqrt(dx * dx + dz * dz);
        if (hDist < 0.01) return false;
        double dirX = dx / hDist;
        double dirZ = dz / hDist;

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
        if (edgePos == null) return false;

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
        if (playerPos.y - landY < 2.0) return false;

        Vec3d landSpot = new Vec3d(edgePos.x, landY, edgePos.z);
        double ldx = landSpot.x - playerPos.x;
        double ldz = landSpot.z - playerPos.z;
        float yaw = (float) Math.toDegrees(-Math.atan2(ldx, ldz));
        float pitch = AutoPearlTrajectory.pitchToPoint(mc, landSpot, yaw, -30f);

        posBeforeThrow = mc.player.getEntityPos();
        waitingForTeleport = true;
        waitTicks = 0;
        tickTimer = 0;

        if (rotate.get()) Rotations.rotate(yaw, pitch, () -> doThrow(pearlSlot));
        else doThrow(pearlSlot);
        return true;
    }

    private float applyOrbitOffset(float baseYaw, double distToTarget) {
        int index = botIndex.get();
        if (index == 0) return baseYaw;

        double radius = orbitRadius.get();
        double fadeStart = radius * 2.0;
        double fadeEnd = stopRange.get();
        double fadeRange = fadeStart - fadeEnd;
        if (fadeRange <= 0) return baseYaw;

        double factor = 1.0;
        if (distToTarget < fadeStart) {
            factor = Math.max(0, Math.min(1, (distToTarget - fadeEnd) / fadeRange));
        }

        float offsetDeg = index * 90.0f * (float) factor;
        return baseYaw + offsetDeg;
    }

    private void doThrow(int pearlSlot) {
        if (mc.player == null || mc.interactionManager == null) return;
        InvUtils.swap(pearlSlot, false);
        mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
        InvUtils.swapBack();
    }

    private int findPearlSlot() {
        if (mc.player == null) return -1;
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getStack(i).getItem() == Items.ENDER_PEARL) return i;
        }
        return -1;
    }
}



