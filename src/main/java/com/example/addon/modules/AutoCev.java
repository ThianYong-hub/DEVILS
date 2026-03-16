package com.example.addon.modules;

import com.example.addon.AddonTemplate;
import com.example.addon.modules.autocev.AutoCevActionExecutor;
import com.example.addon.modules.autocev.AutoCevPlanner;
import com.example.addon.util.CrashGuard;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayDeque;
import java.util.Deque;

public class AutoCev extends Module {
    private static final int DEBUG_LOG_LIMIT = 40;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Double> targetRange = sgGeneral.add(new DoubleSetting.Builder()
        .name("target-range")
        .description("Target search range.")
        .defaultValue(5.0)
        .min(1.0)
        .max(7.0)
        .sliderRange(2.0, 6.0)
        .build()
    );

    private final Setting<Double> safe = sgGeneral.add(new DoubleSetting.Builder()
        .name("safe")
        .description("Minimum HP+absorption left after crystal damage.")
        .defaultValue(6.0)
        .min(0.0)
        .max(20.0)
        .sliderRange(0.0, 20.0)
        .build()
    );

    private final Setting<Boolean> rotate = sgGeneral.add(new BoolSetting.Builder()
        .name("rotate")
        .description("Rotate before place, mine and attack.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> swingHand = sgGeneral.add(new BoolSetting.Builder()
        .name("swing-hand")
        .description("Swing hand while placing or attacking.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> pauseOnEat = sgGeneral.add(new BoolSetting.Builder()
        .name("pause-on-eat")
        .description("Pause while eating, drinking or using items.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> pauseOnSword = sgGeneral.add(new BoolSetting.Builder()
        .name("pause-on-sword")
        .description("Pause while holding a sword.")
        .defaultValue(false)
        .build()
    );

    private final Setting<MineMode> mineMode = sgGeneral.add(new EnumSetting.Builder<MineMode>()
        .name("mine-mode")
        .description("Vanilla mines the block itself. Insta only starts mining for an external instamine.")
        .defaultValue(MineMode.Insta)
        .build()
    );

    private final Setting<SwapMode> swapMode = sgGeneral.add(new EnumSetting.Builder<SwapMode>()
        .name("swap-mode")
        .description("Item switch mode.")
        .defaultValue(SwapMode.Silent)
        .build()
    );

    private final Setting<Boolean> debugClipboard = sgGeneral.add(new BoolSetting.Builder()
        .name("debug-clipboard")
        .description("Copies important AutoCev actions to the clipboard.")
        .defaultValue(false)
        .build()
    );

    private final Deque<String> debugLines = new ArrayDeque<>();
    private final AutoCevPlanner planner = new AutoCevPlanner(this);
    private final AutoCevActionExecutor executor = new AutoCevActionExecutor(this, planner);

    private PlayerEntity target;
    private String targetId;
    private BlockPos activeBase;
    private PlanType activeType;
    private BlockPos lockedFaceBase;
    private BlockPos lockedHeadMineBase;
    private BlockPos instaMinePos;
    private int debugCounter;

    public AutoCev() {
        super(AddonTemplate.CATEGORY, "auto-cev", "Places obsidian and crystals for a simple, aggressive cev cycle.");
    }

    @Override
    public void onActivate() {
        resetState();
    }

    @Override
    public void onDeactivate() {
        resetState();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        CrashGuard.run(this, "onTickPre", this::tickSafe);
    }

    private void tickSafe() {
        if (mc.player == null || mc.world == null || mc.interactionManager == null) return;
        if (PlayerUtils.shouldPause(false, pauseOnEat.get(), pauseOnEat.get())) return;
        if (pauseOnSword.get() && mc.player.getMainHandStack().isIn(ItemTags.SWORDS)) return;

        target = planner.selectTarget();
        if (target == null) {
            debug("no target -> reset");
            resetState();
            return;
        }

        String id = target.getUuidAsString();
        if (!id.equals(targetId)) {
            debug("target changed -> " + target.getName().getString());
            targetId = id;
            clearPlanState();
        }

        CyclePlan head = planner.chooseHeadPlan(target);
        CyclePlan plan = planner.choosePlan(target);
        if (plan == null) {
            if (isHeadType(activeType) && lockedHeadMineBase != null) {
                debug("no plan -> keep head lock " + formatPos(lockedHeadMineBase));
                return;
            }
            debug("no plan for " + target.getName().getString() + " head=" + planSummary(head));
            clearPlanState();
            return;
        }

        if ((plan.type() == PlanType.FACE || plan.type() == PlanType.FACE_BLOCKER) && head != null && head.type() == PlanType.HEAD_BLOCKER) {
            if (lockedFaceBase == null || !lockedFaceBase.equals(plan.pos())) instaMinePos = null;
            lockedFaceBase = plan.pos().toImmutable();
            debug("lock face base -> " + formatPos(lockedFaceBase) + " head=" + planSummary(head));
        } else if (plan.type() == PlanType.HEAD) {
            if (lockedFaceBase != null) debug("unlock face base -> head open");
            lockedFaceBase = null;
        }

        boolean keepsHeadMineLock = isHeadType(plan.type()) && lockedHeadMineBase != null && lockedHeadMineBase.equals(plan.pos());
        if (!keepsHeadMineLock) {
            if (lockedHeadMineBase != null) debug("unlock head mine -> " + formatPos(lockedHeadMineBase));
            lockedHeadMineBase = null;
        }

        if (activeBase == null || !activeBase.equals(plan.pos())) {
            if (instaMinePos != null) {
                debug("clear insta pos -> active base changed from " + formatPos(activeBase) + " to " + formatPos(plan.pos()));
            }
            instaMinePos = null;
        }

        activeBase = plan.pos();
        activeType = plan.type();
        debug("plan -> " + planSummary(plan));
        executor.executePlan(plan);
    }

    public MinecraftClient client() {
        return mc;
    }

    public AutoCevPlanner planner() {
        return planner;
    }

    public AutoCevActionExecutor executor() {
        return executor;
    }

    public PlayerEntity getTarget() {
        return target;
    }

    public String getTargetId() {
        return targetId;
    }

    public void setTargetId(String targetId) {
        this.targetId = targetId;
    }

    public BlockPos getActiveBase() {
        return activeBase;
    }

    public void setActiveBase(BlockPos activeBase) {
        this.activeBase = activeBase;
    }

    public PlanType getActiveType() {
        return activeType;
    }

    public void setActiveType(PlanType activeType) {
        this.activeType = activeType;
    }

    public BlockPos getLockedFaceBase() {
        return lockedFaceBase;
    }

    public void setLockedFaceBase(BlockPos lockedFaceBase) {
        this.lockedFaceBase = lockedFaceBase;
    }

    public BlockPos getLockedHeadMineBase() {
        return lockedHeadMineBase;
    }

    public void setLockedHeadMineBase(BlockPos lockedHeadMineBase) {
        this.lockedHeadMineBase = lockedHeadMineBase;
    }

    public BlockPos getInstaMinePos() {
        return instaMinePos;
    }

    public void setInstaMinePos(BlockPos instaMinePos) {
        this.instaMinePos = instaMinePos;
    }

    public double getTargetRange() {
        return targetRange.get();
    }

    public double getSafeHealth() {
        return safe.get();
    }

    public boolean shouldRotate() {
        return rotate.get();
    }

    public boolean shouldSwingHand() {
        return swingHand.get();
    }

    public MineMode getMineMode() {
        return mineMode.get();
    }

    public SwapMode getSwapMode() {
        return swapMode.get();
    }

    public boolean isHeadType(PlanType type) {
        return type == PlanType.HEAD || type == PlanType.HEAD_CLEAR || type == PlanType.HEAD_BLOCKER;
    }

    public void disableWithError(String message) {
        error(message);
        toggle();
    }

    public void debug(String message) {
        if (!debugClipboard.get() || mc == null || mc.keyboard == null) return;

        debugCounter++;
        debugLines.addLast(String.format("%03d %s | target=%s active=%s/%s insta=%s faceLock=%s headLock=%s",
            debugCounter,
            message,
            target == null ? "-" : target.getName().getString(),
            activeType == null ? "-" : activeType.name(),
            formatPos(activeBase),
            formatPos(instaMinePos),
            formatPos(lockedFaceBase),
            formatPos(lockedHeadMineBase)
        ));

        while (debugLines.size() > DEBUG_LOG_LIMIT) debugLines.removeFirst();
        mc.keyboard.setClipboard(String.join(System.lineSeparator(), debugLines));
    }

    public String formatPos(BlockPos pos) {
        if (pos == null) return "-";
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    @Override
    public String getInfoString() {
        if (target == null) return null;
        if (activeBase == null) return target.getName().getString();
        return target.getName().getString() + ((activeType == PlanType.FACE || activeType == PlanType.FACE_BLOCKER) ? " F" : " T");
    }

    private void resetState() {
        target = null;
        targetId = null;
        clearPlanState();
        clearDebug();
    }

    private void clearPlanState() {
        activeBase = null;
        activeType = null;
        lockedFaceBase = null;
        lockedHeadMineBase = null;
        instaMinePos = null;
    }

    private void clearDebug() {
        debugLines.clear();
        debugCounter = 0;
    }

    private String planSummary(CyclePlan plan) {
        if (plan == null) return "null";
        return plan.type().name() + "@" + formatPos(plan.pos());
    }

    public record CyclePlan(BlockPos pos, PlanType type, double score) {
    }

    public enum SwapMode {
        Normal,
        Silent
    }

    public enum MineMode {
        Vanilla,
        Insta
    }

    public enum SpaceState {
        OPEN,
        OBSIDIAN_BLOCKER,
        ITEM_BLOCKER,
        OTHER_BLOCKER,
        INVALID
    }

    public enum PlanType {
        HEAD,
        HEAD_CLEAR,
        FACE,
        FACE_BLOCKER,
        HEAD_BLOCKER
    }
}


