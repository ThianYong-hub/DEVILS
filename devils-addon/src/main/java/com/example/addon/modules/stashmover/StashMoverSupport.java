package com.example.addon.modules.stashmover;

import com.example.addon.AddonTemplate;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

abstract class StashMoverSupport extends Module {
    protected static final int LOGIC_INTERVAL_TICKS = 2;
    protected static final int ACTION_ROTATION_PRIORITY = 75;
    protected static final int OWN_PEARL_SPAWN_TIMEOUT_TICKS = 8;
    protected static final int LOAD_MESSAGE_RESEND_TICKS = 40;
    protected static final int RECONNECT_NUDGE_DELAY_TICKS = 30;
    protected static final int STATIONARY_NUDGE_TICKS = 200;
    protected static final double CONTAINER_REACH = 4.5;
    protected static final double DESTINATION_CLOSE_SQ = 9.0;
    protected static final SettingColor DEFAULT_RENDER_COLOR = new SettingColor(255, 140, 0, 70);

    protected final SettingGroup sgGeneral = settings.getDefaultGroup();
    protected final SettingGroup sgRender = settings.createGroup("Render");
    protected final SettingGroup sgDiagnostics = settings.createGroup("Diagnostics");

    protected final Setting<Mode> mode = sgGeneral.add(new EnumSetting.Builder<Mode>()
        .name("mode")
        .description("Mover steals stash contents and cycles pearls. Loader only re-loads the pearl chamber.")
        .defaultValue(Mode.MOVER)
        .build());

    protected final Setting<Integer> chestDelay = sgGeneral.add(new IntSetting.Builder()
        .name("chest-delay")
        .description("Ticks between chest slot clicks.")
        .defaultValue(1)
        .min(0)
        .sliderRange(0, 10)
        .visible(() -> mode.get() == Mode.MOVER)
        .build());

    protected final Setting<Integer> sourceLootDelay = sgGeneral.add(new IntSetting.Builder()
        .name("source-loot-delay")
        .description("Ticks between quick-move actions while collecting shulkers/items from source chests.")
        .defaultValue(1)
        .min(0)
        .sliderRange(0, 10)
        .visible(() -> mode.get() == Mode.MOVER)
        .build());

    protected final Setting<Integer> scanDistance = sgGeneral.add(new IntSetting.Builder()
        .name("scan-distance")
        .description("Maximum distance for source chest scanning.")
        .defaultValue(100)
        .min(10)
        .sliderRange(10, 256)
        .visible(() -> mode.get() == Mode.MOVER)
        .build());

    protected final Setting<Boolean> autoDisable = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-disable")
        .description("Legacy compatibility toggle. Destination chest full now stays open and waits for free slots.")
        .defaultValue(true)
        .visible(() -> mode.get() == Mode.MOVER)
        .build());

    protected final Setting<Boolean> useEChest = sgGeneral.add(new BoolSetting.Builder()
        .name("use-ender-chest")
        .description("Use a nearby ender chest as a buffer between source and destination loops.")
        .defaultValue(true)
        .visible(() -> mode.get() == Mode.MOVER)
        .build());

    protected final Setting<Boolean> ignoreSingleChest = sgGeneral.add(new BoolSetting.Builder()
        .name("ignore-single-chest")
        .description("Ignore non-double source chests while scanning stash containers.")
        .defaultValue(true)
        .visible(() -> mode.get() == Mode.MOVER)
        .build());

    protected final Setting<Boolean> onlyShulkers = sgGeneral.add(new BoolSetting.Builder()
        .name("only-shulkers")
        .description("Only take shulker boxes from source chests.")
        .defaultValue(false)
        .visible(() -> mode.get() == Mode.MOVER)
        .build());

    protected final Setting<String> partnerName = sgGeneral.add(new StringSetting.Builder()
        .name("partner-name")
        .description("Other account name used for load-message coordination.")
        .defaultValue("partner")
        .build());

    protected final Setting<String> loadMessage = sgGeneral.add(new StringSetting.Builder()
        .name("load-message")
        .description("Whisper payload both accounts use to coordinate the pearl chamber.")
        .defaultValue("LOAD PEARL")
        .build());

    protected final Setting<Boolean> censorCoords = sgGeneral.add(new BoolSetting.Builder()
        .name("censor-coords")
        .description("Hide coordinates in command feedback.")
        .defaultValue(false)
        .build());

    protected final Setting<String> pearlChestSetting = sgGeneral.add(new StringSetting.Builder()
        .name("pearl-chest")
        .description("Stored pearl chest block position. Use `.stashmover pearlchest` to capture from crosshair.")
        .defaultValue("")
        .visible(() -> false)
        .build());

    protected final Setting<String> lootChestSetting = sgGeneral.add(new StringSetting.Builder()
        .name("loot-chest")
        .description("Stored destination loot chest block position. Use `.stashmover lootchest` to capture from crosshair.")
        .defaultValue("")
        .visible(() -> false)
        .build());

    protected final Setting<String> waterSetting = sgGeneral.add(new StringSetting.Builder()
        .name("water")
        .description("Stored water block position. Use `.stashmover water` while standing in water.")
        .defaultValue("")
        .visible(() -> false)
        .build());

    protected final Setting<String> chamberSetting = sgGeneral.add(new StringSetting.Builder()
        .name("chamber")
        .description("Stored chamber hit position. Use `.stashmover chamber` while looking at the chamber trapdoor.")
        .defaultValue("")
        .visible(() -> false)
        .build());

    protected final Setting<String> pearlTargetSetting = sgGeneral.add(new StringSetting.Builder()
        .name("pearl-target")
        .description("Optional precise pearl aim point. Use `.stashmover pearltarget` while looking at the intended entry point.")
        .defaultValue("")
        .visible(() -> false)
        .build());

    protected final Setting<Boolean> renderSourceChests = sgRender.add(new BoolSetting.Builder()
        .name("render-source-chests")
        .description("Render nearby candidate source chests while the module scans them.")
        .defaultValue(true)
        .visible(() -> mode.get() == Mode.MOVER)
        .build());

    protected final Setting<Double> renderDistance = sgRender.add(new DoubleSetting.Builder()
        .name("render-distance")
        .description("Maximum render distance for source chest overlays.")
        .defaultValue(14.0)
        .min(4.0)
        .sliderRange(4.0, 32.0)
        .visible(() -> mode.get() == Mode.MOVER && renderSourceChests.get())
        .build());

    protected final Setting<SettingColor> renderColor = sgRender.add(new ColorSetting.Builder()
        .name("render-color")
        .description("Color used for source chest overlays.")
        .defaultValue(DEFAULT_RENDER_COLOR)
        .visible(() -> mode.get() == Mode.MOVER && renderSourceChests.get())
        .build());

    protected final Setting<Boolean> debugLogging = sgDiagnostics.add(new BoolSetting.Builder()
        .name("debug-logging")
        .description("Emit StashMover phase and runtime diagnostics to chat/logs.")
        .defaultValue(false)
        .build());

    protected final Setting<Integer> stallTimeoutTicks = sgDiagnostics.add(new IntSetting.Builder()
        .name("stall-timeout-ticks")
        .description("Ticks before water-related phases are treated as stalled.")
        .defaultValue(120)
        .min(20)
        .sliderRange(20, 400)
        .visible(() -> mode.get() == Mode.MOVER)
        .build());

    protected final Setting<Integer> maxThrowFailures = sgDiagnostics.add(new IntSetting.Builder()
        .name("max-throw-failures")
        .description("How many failed pearl attempts are tolerated before recovery policy fires.")
        .defaultValue(3)
        .min(1)
        .sliderRange(1, 10)
        .visible(() -> mode.get() == Mode.MOVER)
        .build());

    protected final Setting<StallAction> stallAction = sgDiagnostics.add(new EnumSetting.Builder<StallAction>()
        .name("stall-action")
        .description("Recovery policy when the water/pearl loop stops making progress.")
        .defaultValue(StallAction.RESET_PHASE)
        .visible(() -> mode.get() == Mode.MOVER)
        .build());

    protected final StashMoverBaritoneBridge baritone = new StashMoverBaritoneBridge();
    protected final StashMoverOwnPearlTracker ownPearlTracker = new StashMoverOwnPearlTracker();
    protected final Set<BlockPos> blacklistedSourceChests = new LinkedHashSet<>();
    protected final Set<BlockPos> renderedSourceChests = new LinkedHashSet<>();

    protected MoverPhase moverPhase = MoverPhase.LOOT;
    protected LoaderPhase loaderPhase = LoaderPhase.WAITING;
    protected BlockPos currentLootSourceChest;
    protected BlockPos openedContainerTarget;
    protected int borrowedPearlHotbarSlot = -1;
    protected int borrowedPearlChestSlot = -1;
    protected boolean pearlChestSwapPending;
    protected boolean loadAckReceived;
    protected boolean echestBufferFilled;
    protected boolean disableAfterPartnerSeen;
    protected boolean waitingForDestinationSpace;
    protected boolean reconnectNudgeAfterJoin;
    protected int actionCooldownTicks;
    protected int chestActionCooldownTicks;
    protected int logicPulseTicks;
    protected int phaseAgeTicks;
    protected int reconnectNudgeTicks;
    protected int resendLoadMessageTicks;
    protected int stationaryTicks;
    protected float movedStacks;
    protected long lastPacketReceivedAtMs;
    protected GoalKind activeGoalKind = GoalKind.NONE;
    protected BlockPos activeGoalPos;
    protected String lastGoalReason = "none";
    protected String lastPhaseReason = "startup";
    protected String lastPearlFailureReason = "none";
    protected String lastStallReason = "none";
    protected String lastRecoveryAction = "none";
    protected Vec3d lastPearlTarget;
    protected float lastPearlThrowYaw;
    protected float lastPearlThrowPitch;
    protected int failedPearlThrows;
    protected int stallRecoveryCount;
    protected long lastProgressAtMs = System.currentTimeMillis();

    protected StashMoverSupport() {
        super(AddonTemplate.CATEGORY, "stash-mover", "Moves stash contents with pearl stasis coordination and container automation.");
    }

    protected void resetRuntime(boolean resetReconnectState) {
        moverPhase = MoverPhase.LOOT;
        loaderPhase = LoaderPhase.WAITING;
        currentLootSourceChest = null;
        openedContainerTarget = null;
        loadAckReceived = false;
        echestBufferFilled = false;
        disableAfterPartnerSeen = false;
        waitingForDestinationSpace = false;
        actionCooldownTicks = 0;
        chestActionCooldownTicks = 0;
        logicPulseTicks = 0;
        phaseAgeTicks = 0;
        reconnectNudgeTicks = 0;
        resendLoadMessageTicks = 0;
        stationaryTicks = 0;
        movedStacks = 0.0f;
        lastPacketReceivedAtMs = System.currentTimeMillis();
        lastProgressAtMs = System.currentTimeMillis();
        ownPearlTracker.reset();
        clearPearlChestBorrowState();
        cancelGoal("runtime-reset");
        resetDiagnostics();
        renderedSourceChests.clear();
        if (resetReconnectState) reconnectNudgeAfterJoin = false;
    }

    protected void clearPearlChestBorrowState() {
        borrowedPearlHotbarSlot = -1;
        borrowedPearlChestSlot = -1;
        pearlChestSwapPending = false;
    }

    protected void resetDiagnostics() {
        activeGoalKind = GoalKind.NONE;
        activeGoalPos = null;
        lastGoalReason = "none";
        lastPhaseReason = "runtime-reset";
        lastPearlFailureReason = "none";
        lastStallReason = "none";
        lastRecoveryAction = "none";
        lastPearlTarget = null;
        lastPearlThrowYaw = 0.0f;
        lastPearlThrowPitch = 0.0f;
        failedPearlThrows = 0;
        stallRecoveryCount = 0;
    }

    protected BlockPos pearlChestPos() {
        return StashMoverConfigCodec.decodeBlockPos(pearlChestSetting.get());
    }

    protected BlockPos lootChestPos() {
        return StashMoverConfigCodec.decodeBlockPos(lootChestSetting.get());
    }

    protected BlockPos waterPos() {
        return StashMoverConfigCodec.decodeBlockPos(waterSetting.get());
    }

    protected Vec3d chamberLookPos() {
        return StashMoverConfigCodec.decodeVec3d(chamberSetting.get());
    }

    protected Vec3d pearlTargetPos() {
        return StashMoverConfigCodec.decodeVec3d(pearlTargetSetting.get());
    }

    protected String formatValue(BlockPos pos) {
        return pos == null ? "<unset>" : formatBlockPosForFeedback(pos);
    }

    protected String formatValue(Vec3d pos) {
        return pos == null ? "<unset>" : formatVecForFeedback(pos);
    }

    protected String formatBlockPosForFeedback(BlockPos pos) {
        if (pos == null) return "<unset>";
        if (censorCoords.get()) return "<censored>";
        return pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
    }

    protected String formatVecForFeedback(Vec3d pos) {
        if (pos == null) return "<unset>";
        if (censorCoords.get()) return "<censored>";
        return String.format(Locale.ROOT, "%.2f, %.2f, %.2f", pos.x, pos.y, pos.z);
    }

    public String positionStatusSummary() {
        return "pearlchest=" + formatValue(pearlChestPos())
            + " lootchest=" + formatValue(lootChestPos())
            + " water=" + formatValue(waterPos())
            + " pearltarget=" + formatValue(pearlTargetPos())
            + " chamber=" + formatValue(chamberLookPos())
            + " mode=" + mode.get();
    }

    public String runtimeStatusSummary() {
        return positionStatusSummary()
            + " moverPhase=" + moverPhase
            + " loaderPhase=" + loaderPhase
            + " phaseReason=" + lastPhaseReason
            + " goal=" + activeGoalKind
            + " goalPos=" + formatValue(activeGoalPos)
            + " goalReason=" + lastGoalReason
            + " trackerAwaiting=" + ownPearlTracker.isAwaitingSpawn()
            + " trackerEntity=" + ownPearlTracker.trackedEntityId()
            + " lastPearlTarget=" + formatValue(lastPearlTarget)
            + " lastPearlYaw=" + String.format(Locale.ROOT, "%.2f", lastPearlThrowYaw)
            + " lastPearlPitch=" + String.format(Locale.ROOT, "%.2f", lastPearlThrowPitch)
            + " pearlFailure=" + lastPearlFailureReason
            + " pearlBorrowHotbar=" + borrowedPearlHotbarSlot
            + " pearlBorrowChest=" + borrowedPearlChestSlot
            + " pearlSwapPending=" + pearlChestSwapPending
            + " failures=" + failedPearlThrows
            + " waitingDestinationSpace=" + waitingForDestinationSpace
            + " stallReason=" + lastStallReason
            + " recovery=" + lastRecoveryAction
            + " stallRecoveries=" + stallRecoveryCount;
    }

    public Mode modeValue() {
        return mode.get();
    }

    public String moverPhaseName() {
        return moverPhase.name();
    }

    public String activeGoalName() {
        return activeGoalKind.name();
    }

    public boolean ownPearlAwaiting() {
        return ownPearlTracker.isAwaitingSpawn();
    }

    public boolean ownPearlTracked() {
        return ownPearlTracker.hasTrackedPearl();
    }

    public String lastPearlFailureReasonValue() {
        return lastPearlFailureReason;
    }

    public String lastRecoveryActionValue() {
        return lastRecoveryAction;
    }

    protected abstract void cancelGoal(String reason);

    protected abstract void setMoverPhase(MoverPhase next, String reason);

    public enum Mode {
        MOVER,
        LOADER
    }

    protected enum GoalKind {
        NONE,
        WATER,
        PEARL_CHEST,
        LOOT_CHEST,
        SOURCE_CHEST,
        CHAMBER,
        CONTAINER_INTERACT,
        RANDOM_NUDGE,
        RECONNECT_NUDGE
    }

    public enum StallAction {
        WARN_ONLY,
        RESET_PHASE,
        DISABLE_MODULE
    }

    protected enum MoverPhase {
        LOOT,
        SEND_LOAD_PEARL_MSG,
        WAIT_FOR_PEARL,
        WALKING_TO_CHEST,
        THROWING_PEARL,
        PUT_BACK_PEARLS,
        ECHEST_LOOT,
        ECHEST_FILL
    }

    protected enum LoaderPhase {
        LOAD_PEARL,
        WAITING
    }

    protected enum PearlTakeResult {
        SUCCESS,
        NO_CONTAINER,
        NO_PEARL_IN_CONTAINER,
        NO_SAFE_SLOT
    }
}
