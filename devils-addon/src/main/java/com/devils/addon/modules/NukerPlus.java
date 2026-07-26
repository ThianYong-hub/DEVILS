package com.devils.addon.modules;

import com.devils.addon.DevilsAddon;
import com.devils.addon.modules.nukerplus.NukerPlusBaritoneSelection;
import com.devils.addon.modules.nukerplus.NukerPlusMiningStrategy;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.entity.player.BlockBreakingCooldownEvent;
import meteordevelopment.meteorclient.events.meteor.KeyEvent;
import meteordevelopment.meteorclient.events.meteor.MouseClickEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.BlockListSetting;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.KeybindSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.meteorclient.utils.misc.Names;
import meteordevelopment.meteorclient.utils.misc.input.KeyAction;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.render.RenderUtils;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.meteorclient.utils.world.BlockIterator;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

public class NukerPlus extends Module {
    private static final double CUBE_VERTICAL_SNAP = 0.125D;
    public static final double DAMAGE_MIN = 0.60D;
    private static final double DAMAGE_DEFAULT = 0.60D;
    public static final double DAMAGE_MAX = 1.00D;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgAcceleration = settings.createGroup("Acceleration");
    private final SettingGroup sgWhitelist = settings.createGroup("Whitelist");
    private final SettingGroup sgRender = settings.createGroup("Render");
    private final SettingGroup sgDiagnostics = settings.createGroup("Acceleration Diagnostics");

    private final Setting<Shape> shape = sgGeneral.add(new EnumSetting.Builder<Shape>()
        .name("shape")
        .description("The shape of nuking algorithm.")
        .defaultValue(Shape.Sphere)
        .build()
    );

    private final Setting<Mode> mode = sgGeneral.add(new EnumSetting.Builder<Mode>()
        .name("mode")
        .description("The way the blocks are broken.")
        .defaultValue(Mode.Flatten)
        .build()
    );

    private final Setting<Double> range = sgGeneral.add(new DoubleSetting.Builder()
        .name("range")
        .description("The break range.")
        .defaultValue(4)
        .min(0)
        .visible(() -> shape.get() != Shape.Cube)
        .build()
    );

    private final Setting<Integer> rangeUp = sgGeneral.add(new IntSetting.Builder()
        .name("up")
        .description("The break range.")
        .defaultValue(1)
        .min(0)
        .visible(() -> shape.get() == Shape.Cube)
        .build()
    );

    private final Setting<Integer> rangeDown = sgGeneral.add(new IntSetting.Builder()
        .name("down")
        .description("The break range.")
        .defaultValue(1)
        .min(0)
        .visible(() -> shape.get() == Shape.Cube)
        .build()
    );

    private final Setting<Integer> rangeLeft = sgGeneral.add(new IntSetting.Builder()
        .name("left")
        .description("The break range.")
        .defaultValue(1)
        .min(0)
        .visible(() -> shape.get() == Shape.Cube)
        .build()
    );

    private final Setting<Integer> rangeRight = sgGeneral.add(new IntSetting.Builder()
        .name("right")
        .description("The break range.")
        .defaultValue(1)
        .min(0)
        .visible(() -> shape.get() == Shape.Cube)
        .build()
    );

    private final Setting<Integer> rangeForward = sgGeneral.add(new IntSetting.Builder()
        .name("forward")
        .description("The break range.")
        .defaultValue(1)
        .min(0)
        .visible(() -> shape.get() == Shape.Cube)
        .build()
    );

    private final Setting<Integer> rangeBack = sgGeneral.add(new IntSetting.Builder()
        .name("back")
        .description("The break range.")
        .defaultValue(1)
        .min(0)
        .visible(() -> shape.get() == Shape.Cube)
        .build()
    );

    private final Setting<Double> wallsRange = sgGeneral.add(new DoubleSetting.Builder()
        .name("walls-range")
        .description("Range in which to break when behind blocks.")
        .defaultValue(4.0)
        .min(0)
        .sliderMax(6)
        .build()
    );

    private final Setting<Integer> delay = sgGeneral.add(new IntSetting.Builder()
        .name("delay")
        .description("Delay in ticks between breaking blocks.")
        .defaultValue(0)
        .build()
    );

    public final Setting<Integer> maxBlocksPerTick = sgGeneral.add(new IntSetting.Builder()
        .name("max-blocks-per-tick")
        .description("Maximum blocks to try to break per tick. Also caps SpeedMineDamage charged burst finishes.")
        .defaultValue(1)
        .min(1)
        .build()
    );

    private final Setting<SortMode> sortMode = sgGeneral.add(new EnumSetting.Builder<SortMode>()
        .name("sort-mode")
        .description("The blocks you want to mine first.")
        .defaultValue(SortMode.Closest)
        .build()
    );

    private final Setting<Boolean> suitableTools = sgGeneral.add(new BoolSetting.Builder()
        .name("only-suitable-tools")
        .description("Only mines when using an appropriate for the block.")
        .defaultValue(false)
        .build()
    );

    public final Setting<Boolean> interact = sgGeneral.add(new BoolSetting.Builder()
        .name("interact")
        .description("Interacts with the block instead of mining.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> rotate = sgGeneral.add(new BoolSetting.Builder()
        .name("rotate")
        .description("Rotates server-side to the block being mined.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> baritoneArea = sgGeneral.add(new BoolSetting.Builder()
        .name("baritone-area")
        .description("Only mine blocks inside the current Baritone #sel selection area.")
        .defaultValue(false)
        .build()
    );

    public final Setting<MiningAccelerationMode> accelerationMode = sgAcceleration.add(new EnumSetting.Builder<MiningAccelerationMode>()
        .name("mining-acceleration-mode")
        .description("Controls whether NukerPlus uses baseline mining, damage-based SpeedMine timing, or direct instant packet mining.")
        .defaultValue(MiningAccelerationMode.Off)
        .build()
    );

    public final Setting<Double> damage = sgAcceleration.add(new DoubleSetting.Builder()
        .name("damage")
        .description("Mio-style break progress seed. 0.6 starts at 60% progress and forces the remaining break through the real mining path.")
        .defaultValue(DAMAGE_DEFAULT)
        .range(DAMAGE_MIN, DAMAGE_MAX)
        .sliderRange(DAMAGE_MIN, DAMAGE_MAX)
        .visible(this::usesSpeedMineDamageAcceleration)
        .build()
    );

    public final Setting<Boolean> speedMineAutoSwap = sgAcceleration.add(new BoolSetting.Builder()
        .name("speedmine-auto-swap")
        .description("Uses the fastest hotbar tool for SpeedMineDamage, matching Mio AutoSwap behavior.")
        .defaultValue(true)
        .visible(this::usesSpeedMineDamageAcceleration)
        .build()
    );

    public final Setting<Boolean> grimBypass = sgAcceleration.add(new BoolSetting.Builder()
        .name("grim-bypass")
        .description("Sends an additional abort packet after the instant stop packet.")
        .defaultValue(false)
        .visible(this::usesInstaAcceleration)
        .build()
    );

    private final Setting<ListMode> listMode = sgWhitelist.add(new EnumSetting.Builder<ListMode>()
        .name("list-mode")
        .description("Selection mode.")
        .defaultValue(ListMode.Blacklist)
        .build()
    );

    private final Setting<List<Block>> blacklist = sgWhitelist.add(new BlockListSetting.Builder()
        .name("blacklist")
        .description("The blocks you don't want to mine.")
        .visible(() -> listMode.get() == ListMode.Blacklist)
        .build()
    );

    private final Setting<List<Block>> whitelist = sgWhitelist.add(new BlockListSetting.Builder()
        .name("whitelist")
        .description("The blocks you want to mine.")
        .visible(() -> listMode.get() == ListMode.Whitelist)
        .build()
    );

    private final Setting<Keybind> selectBlockBind = sgWhitelist.add(new KeybindSetting.Builder()
        .name("select-block-bind")
        .description("Adds targeted block to list when this button is pressed.")
        .defaultValue(Keybind.none())
        .build()
    );

    public final Setting<Boolean> swing = sgRender.add(new BoolSetting.Builder()
        .name("swing")
        .description("Whether to swing hand client-side.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> enableRenderBounding = sgRender.add(new BoolSetting.Builder()
        .name("bounding-box")
        .description("Enable rendering bounding box for Cube and Uniform Cube.")
        .defaultValue(true)
        .build()
    );

    private final Setting<ShapeMode> shapeModeBox = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("nuke-box-mode")
        .description("How the shape for the bounding box is rendered.")
        .defaultValue(ShapeMode.Both)
        .build()
    );

    private final Setting<SettingColor> sideColorBox = sgRender.add(new ColorSetting.Builder()
        .name("side-color")
        .description("The side color of the bounding box.")
        .defaultValue(new SettingColor(16, 106, 144, 100))
        .build()
    );

    private final Setting<SettingColor> lineColorBox = sgRender.add(new ColorSetting.Builder()
        .name("line-color")
        .description("The line color of the bounding box.")
        .defaultValue(new SettingColor(16, 106, 144, 255))
        .build()
    );

    private final Setting<Boolean> enableRenderBreaking = sgRender.add(new BoolSetting.Builder()
        .name("broken-blocks")
        .description("Enable rendering bounding box for Cube and Uniform Cube.")
        .defaultValue(true)
        .build()
    );

    private final Setting<ShapeMode> shapeModeBreak = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("nuke-block-mode")
        .description("How the shapes for broken blocks are rendered.")
        .defaultValue(ShapeMode.Both)
        .visible(enableRenderBreaking::get)
        .build()
    );

    private final Setting<SettingColor> sideColor = sgRender.add(new ColorSetting.Builder()
        .name("side-color")
        .description("The side color of the target block rendering.")
        .defaultValue(new SettingColor(255, 0, 0, 80))
        .visible(enableRenderBreaking::get)
        .build()
    );

    private final Setting<SettingColor> lineColor = sgRender.add(new ColorSetting.Builder()
        .name("line-color")
        .description("The line color of the target block rendering.")
        .defaultValue(new SettingColor(255, 0, 0, 255))
        .visible(enableRenderBreaking::get)
        .build()
    );

    private final Setting<Boolean> debugAcceleration = sgDiagnostics.add(new BoolSetting.Builder()
        .name("debug-acceleration")
        .description("Print concise acceleration state changes, damage timing, and insta decisions to chat.")
        .defaultValue(false)
        .visible(() -> accelerationMode.get() != MiningAccelerationMode.Off)
        .build()
    );

    private final List<BlockPos> blocks = new ArrayList<>();
    public final Set<BlockPos> interacted = new ObjectOpenHashSet<>();

    private boolean firstBlock;
    private final BlockPos.Mutable lastBlockPos = new BlockPos.Mutable();

    private int timer;
    private int noBlockTimer;

    private final BlockPos.Mutable pos1 = new BlockPos.Mutable();
    private final BlockPos.Mutable pos2 = new BlockPos.Mutable();
    private int maxh;
    private int maxv;
    private final NukerPlusBaritoneSelection baritoneSelectionBridge = new NukerPlusBaritoneSelection();
    public final NukerPlusMiningStrategy.DamageBreakState damageBreakState = new NukerPlusMiningStrategy.DamageBreakState();
    private final NukerPlusMiningStrategy miningStrategy = new NukerPlusMiningStrategy(this);
    private boolean warnedBaritoneUnavailable;
    private boolean warnedBaritoneSelectionMissing;
    private boolean warnedExternalSpeedMineConflict;
    private String lastAccelerationSuppressionReason;
    private String lastAccelerationDebugMessage;
    private long lastAccelerationDebugTick = Long.MIN_VALUE;
    private MiningAccelerationMode lastAccelerationMode = MiningAccelerationMode.Off;
    public long damageForcedFinishCount;
    public long damageRetryCount;
    public long damageAutoSwapSelectCount;
    private long damageAutoSwapHeldResetCount;
    public int damageLastAutoSwapFromSlot = -1;
    public int damageLastAutoSwapToSlot = -1;
    public long damageBurstChainTick = Long.MIN_VALUE;
    public int damageSwapBackSlot = -1;
    public long damageToolSyncTick = Long.MIN_VALUE;
    public int damageToolSyncSlot = -1;

    public NukerPlus() {
        super(DevilsAddon.CATEGORY, "nuker-plus", "Breaks blocks around you with stable Cube bounds.");
    }

    @Override
    public void onActivate() {
        firstBlock = true;
        timer = 0;
        noBlockTimer = 0;
        blocks.clear();
        interacted.clear();
        warnedBaritoneUnavailable = false;
        warnedBaritoneSelectionMissing = false;
        warnedExternalSpeedMineConflict = false;
        lastAccelerationSuppressionReason = null;
        lastAccelerationDebugMessage = null;
        lastAccelerationDebugTick = Long.MIN_VALUE;
        lastAccelerationMode = accelerationMode.get();
        damageForcedFinishCount = 0L;
        damageRetryCount = 0L;
        damageAutoSwapSelectCount = 0L;
        damageAutoSwapHeldResetCount = 0L;
        damageLastAutoSwapFromSlot = -1;
        damageLastAutoSwapToSlot = -1;
        damageBurstChainTick = Long.MIN_VALUE;
        damageSwapBackSlot = -1;
        damageToolSyncTick = Long.MIN_VALUE;
        damageToolSyncSlot = -1;
        damageBreakState.clear();
    }

    @Override
    public void onDeactivate() {
        blocks.clear();
        interacted.clear();
        warnedExternalSpeedMineConflict = false;
        lastAccelerationSuppressionReason = null;
        lastAccelerationDebugMessage = null;
        lastAccelerationDebugTick = Long.MIN_VALUE;
        damageBurstChainTick = Long.MIN_VALUE;
        damageToolSyncTick = Long.MIN_VALUE;
        damageToolSyncSlot = -1;
        miningStrategy.restoreDamageAutoSwap();
        miningStrategy.resetDamageBreakState("module-disabled");
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (enableRenderBounding.get() && shape.get() != Shape.Sphere && mode.get() != Mode.Smash) {
            int minX = Math.min(pos1.getX(), pos2.getX());
            int minY = Math.min(pos1.getY(), pos2.getY());
            int minZ = Math.min(pos1.getZ(), pos2.getZ());
            int maxX = Math.max(pos1.getX(), pos2.getX());
            int maxY = Math.max(pos1.getY(), pos2.getY());
            int maxZ = Math.max(pos1.getZ(), pos2.getZ());
            event.renderer.box(minX, minY, minZ, maxX, maxY, maxZ, sideColorBox.get(), lineColorBox.get(), shapeModeBox.get(), 0);
        }
    }

    @EventHandler
    private void onMouseClick(MouseClickEvent event) {
        if (event.action == KeyAction.Press) addTargetedBlockToList();
    }

    @EventHandler
    private void onKey(KeyEvent event) {
        if (event.action == KeyAction.Press) addTargetedBlockToList();
    }

    @EventHandler
    private void onTickPre(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) {
            miningStrategy.resetDamageBreakState("world-unavailable");
            lastAccelerationSuppressionReason = null;
            return;
        }

        if (!mc.player.isAlive()) {
            miningStrategy.resetDamageBreakState("player-dead");
            lastAccelerationSuppressionReason = null;
            return;
        }

        if (lastAccelerationMode != accelerationMode.get()) {
            miningStrategy.resetDamageBreakState("mode-switch");
            lastAccelerationMode = accelerationMode.get();
        }

        String accelerationSuppressionReason = miningStrategy.resolveAccelerationSuppressionReason();
        publishAccelerationSuppressionState(accelerationSuppressionReason);

        if (accelerationSuppressionReason != null || !usesSpeedMineDamageAcceleration()) {
            miningStrategy.resetDamageBreakState(accelerationSuppressionReason != null ? accelerationSuppressionReason : "damage-mode-inactive");
        }

        if (timer > 0) {
            timer--;
            return;
        }

        double pX = mc.player.getX();
        double pY = mc.player.getY();
        double pZ = mc.player.getZ();
        double rangeSq = Math.pow(range.get(), 2);
        BlockPos playerBlockPos = mc.player.getBlockPos();

        if (shape.get() == Shape.UniformCube) range.set((double) Math.round(range.get()));

        double pX_ = pX;
        double pZ_ = pZ;
        int r = (int) Math.round(range.get());

        if (shape.get() == Shape.UniformCube) {
            pX_ += 1;
            pos1.set(pX_ - r, pY - r + 1, pZ - r + 1);
            pos2.set(pX_ + r - 1, pY + r, pZ + r);
            maxh = 0;
            maxv = 0;
        } else if (shape.get() == Shape.Cube) {
            updateCubeBounds(pX_, pY, pZ_);
        } else {
            pos1.set(0, 0, 0);
            pos2.set(0, 0, 0);
            maxh = 0;
            maxv = 0;
        }

        if (mode.get() == Mode.Flatten) pos1.setY((int) Math.floor(pY + 0.5));

        Box box = new Box(pos1.toCenterPos(), pos2.toCenterPos());
        NukerPlusBaritoneSelection.Snapshot selectionSnapshot = baritoneArea.get()
            ? baritoneSelectionBridge.snapshot()
            : NukerPlusBaritoneSelection.Snapshot.disabled();
        publishBaritoneAreaWarnings(selectionSnapshot);

        BlockIterator.register(Math.max((int) Math.ceil(range.get() + 1), maxh), Math.max((int) Math.ceil(range.get()), maxv), (blockPos, blockState) -> {
            Vec3d center = blockPos.toCenterPos();
            switch (shape.get()) {
                case Sphere -> {
                    if (Utils.squaredDistance(pX, pY, pZ, center.getX(), center.getY(), center.getZ()) > rangeSq) return;
                }
                case UniformCube -> {
                    if (chebyshevDist(playerBlockPos.getX(), playerBlockPos.getY(), playerBlockPos.getZ(), blockPos.getX(), blockPos.getY(), blockPos.getZ()) >= range.get()) return;
                }
                case Cube -> {
                    if (!box.contains(center)) return;
                }
            }

            if (!selectionSnapshot.allows(blockPos)) return;
            if (mode.get() == Mode.Flatten && blockPos.getY() + 0.5 < pY) return;
            if (mode.get() == Mode.Smash && blockState.getHardness(mc.world, blockPos) != 0) return;
            if (suitableTools.get() && !interact.get() && !mc.player.getMainHandStack().isSuitableFor(blockState)) return;
            if (!BlockUtils.canBreak(blockPos, blockState) && !interact.get()) return;
            if (isOutOfRange(blockPos)) return;
            if (listMode.get() == ListMode.Whitelist && !whitelist.get().contains(blockState.getBlock())) return;
            if (listMode.get() == ListMode.Blacklist && blacklist.get().contains(blockState.getBlock())) return;
            if (interact.get() && interacted.contains(blockPos)) return;

            blocks.add(blockPos.toImmutable());
        });

        BlockIterator.after(() -> {
            if (sortMode.get() == SortMode.TopDown) {
                blocks.sort(Comparator.comparingDouble(value -> -value.getY()));
            } else if (sortMode.get() != SortMode.None) {
                blocks.sort(Comparator.comparingDouble(value -> Utils.squaredDistance(
                    pX, pY, pZ,
                    value.getX() + 0.5, value.getY() + 0.5, value.getZ() + 0.5
                ) * (sortMode.get() == SortMode.Closest ? 1 : -1)));
            }

            handleBreakCandidates(accelerationSuppressionReason);

            blocks.clear();
        });
    }

    private void updateCubeBounds(double pX, double pY, double pZ) {
        int baseY = resolveCubeBaseY(pY);
        int minY = baseY - rangeDown.get();
        int maxYExclusive = baseY + rangeUp.get() + 1;

        Direction direction = mc.player.getHorizontalFacing();
        switch (direction) {
            case SOUTH -> {
                pZ += 1;
                pX += 1;
                pos1.set(pX - (rangeRight.get() + 1), minY, pZ - (rangeBack.get() + 1));
                pos2.set(pX + rangeLeft.get(), maxYExclusive, pZ + rangeForward.get());
            }
            case WEST -> {
                pos1.set(pX - rangeForward.get(), minY, pZ - rangeRight.get());
                pos2.set(pX + rangeBack.get() + 1, maxYExclusive, pZ + rangeLeft.get() + 1);
            }
            case NORTH -> {
                pX += 1;
                pZ += 1;
                pos1.set(pX - (rangeLeft.get() + 1), minY, pZ - (rangeForward.get() + 1));
                pos2.set(pX + rangeRight.get(), maxYExclusive, pZ + rangeBack.get());
            }
            case EAST -> {
                pX += 1;
                pos1.set(pX - (rangeBack.get() + 1), minY, pZ - rangeLeft.get());
                pos2.set(pX + rangeForward.get(), maxYExclusive, pZ + rangeRight.get() + 1);
            }
        }

        maxh = 1 + Math.max(Math.max(Math.max(rangeBack.get(), rangeRight.get()), rangeForward.get()), rangeLeft.get());
        maxv = 1 + Math.max(rangeUp.get(), rangeDown.get());
    }

    private void publishBaritoneAreaWarnings(NukerPlusBaritoneSelection.Snapshot snapshot) {
        if (!baritoneArea.get()) {
            warnedBaritoneUnavailable = false;
            warnedBaritoneSelectionMissing = false;
            return;
        }

        if (!snapshot.available()) {
            warnedBaritoneSelectionMissing = false;
            if (!warnedBaritoneUnavailable) {
                warning("Baritone Area is enabled, but Baritone is not available.");
                warnedBaritoneUnavailable = true;
            }
            return;
        }

        warnedBaritoneUnavailable = false;

        if (!snapshot.hasSelections()) {
            if (!warnedBaritoneSelectionMissing) {
                warning("Baritone Area is enabled, but no Baritone #sel selection is active.");
                warnedBaritoneSelectionMissing = true;
            }
            return;
        }

        warnedBaritoneSelectionMissing = false;
    }

    private void handleBreakCandidates(String accelerationSuppressionReason) {
        if (blocks.isEmpty()) {
            miningStrategy.resetDamageBreakState("no-targets");
            interacted.clear();
            if (noBlockTimer++ >= delay.get()) firstBlock = true;
            return;
        }

        noBlockTimer = 0;

        if (!firstBlock && !lastBlockPos.equals(blocks.getFirst())) {
            timer = delay.get();
            firstBlock = false;
            lastBlockPos.set(blocks.getFirst());
            if (timer > 0) return;
        }

        int count = 0;
        for (BlockPos block : blocks) {
            if (count >= maxBlocksPerTick.get()) break;

            NukerPlusMiningStrategy.BreakAttemptResult[] breakResult = { NukerPlusMiningStrategy.BreakAttemptResult.stop() };
            Runnable breakAction = () -> breakResult[0] = miningStrategy.dispatchBreakAttempt(block, accelerationSuppressionReason);
            if (rotate.get()) Rotations.rotate(Rotations.getYaw(block), Rotations.getPitch(block), breakAction);
            else breakAction.run();

            if (enableRenderBreaking.get()) {
                RenderUtils.renderTickingBlock(block, sideColor.get(), lineColor.get(), shapeModeBreak.get(), 0, 8, true, false);
            }

            lastBlockPos.set(block);
            count++;
            if (!breakResult[0].continueLoop()) break;
        }

        firstBlock = false;
    }

    private boolean isOutOfRange(BlockPos blockPos) {
        Vec3d pos = blockPos.toCenterPos();
        RaycastContext raycastContext = new RaycastContext(
            mc.player.getEyePos(),
            pos,
            RaycastContext.ShapeType.COLLIDER,
            RaycastContext.FluidHandling.NONE,
            mc.player
        );
        BlockHitResult result = mc.world.raycast(raycastContext);
        if (result == null || !result.getBlockPos().equals(blockPos)) return !PlayerUtils.isWithin(pos, wallsRange.get());
        return false;
    }

    private void addTargetedBlockToList() {
        if (!selectBlockBind.get().isPressed() || mc.currentScreen != null) return;

        HitResult hitResult = mc.crosshairTarget;
        if (hitResult == null || hitResult.getType() != HitResult.Type.BLOCK) return;

        BlockPos pos = ((BlockHitResult) hitResult).getBlockPos();
        Block targetBlock = mc.world.getBlockState(pos).getBlock();

        List<Block> list = listMode.get() == ListMode.Whitelist ? whitelist.get() : blacklist.get();
        String modeName = listMode.get().name();

        if (list.contains(targetBlock)) {
            list.remove(targetBlock);
            info("Removed " + Names.get(targetBlock) + " from " + modeName);
        } else {
            list.add(targetBlock);
            info("Added " + Names.get(targetBlock) + " to " + modeName);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    private void onBlockBreakingCooldown(BlockBreakingCooldownEvent event) {
        event.cooldown = 0;
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        miningStrategy.resetDamageBreakState("game-left");
        lastAccelerationSuppressionReason = null;
    }

    static int resolveCubeBaseY(double playerY) {
        return (int) Math.floor(playerY + CUBE_VERTICAL_SNAP);
    }

    static boolean isInstaChainEligible(float blockBreakingDelta) {
        return NukerPlusMiningStrategy.isInstaChainEligible(blockBreakingDelta);
    }

    public boolean usesSpeedMineDamageAcceleration() {
        return accelerationMode.get() == MiningAccelerationMode.SpeedMineDamage;
    }

    public boolean usesInstaAcceleration() {
        return accelerationMode.get() == MiningAccelerationMode.Insta;
    }

    private void publishAccelerationSuppressionState(String reason) {
        if ((reason == null && lastAccelerationSuppressionReason == null) || (reason != null && reason.equals(lastAccelerationSuppressionReason))) return;
        lastAccelerationSuppressionReason = reason;

        if ("meteor-speedmine".equals(reason) && !warnedExternalSpeedMineConflict) {
            warning("NukerPlus acceleration is paused while Meteor SpeedMine is active.");
            warnedExternalSpeedMineConflict = true;
        } else if (!"meteor-speedmine".equals(reason)) {
            warnedExternalSpeedMineConflict = false;
        }

        if (accelerationMode.get() == MiningAccelerationMode.Off) return;
        if (reason == null) debugAcceleration("acceleration active " + accelerationMode.get().name());
        else debugAcceleration("acceleration suppressed " + reason);
    }

    public void debugAcceleration(String message) {
        if (!debugAcceleration.get() || mc == null || mc.world == null || message == null || message.isBlank()) return;

        long tick = mc.world.getTime();
        if (message.equals(lastAccelerationDebugMessage) && tick == lastAccelerationDebugTick) return;

        lastAccelerationDebugMessage = message;
        lastAccelerationDebugTick = tick;
        info("[NukerAccel] " + message);
    }

    public static int calculateVanillaBreakTicks(float blockBreakingDelta) {
        return NukerPlusMiningStrategy.calculateVanillaBreakTicks(blockBreakingDelta);
    }

    public static int calculateTargetBreakTicks(int vanillaBreakTicks, double damageMultiplier) {
        return NukerPlusMiningStrategy.calculateTargetBreakTicks(vanillaBreakTicks, damageMultiplier);
    }

    public static int calculateTargetBreakTicks(int vanillaBreakTicks, double damageMultiplier, float blockBreakingDelta) {
        return NukerPlusMiningStrategy.calculateTargetBreakTicks(vanillaBreakTicks, damageMultiplier, blockBreakingDelta);
    }

    public long debugDamageForcedFinishCount() {
        return damageForcedFinishCount;
    }

    public long debugDamageRetryCount() {
        return damageRetryCount;
    }

    public long debugDamageAutoSwapSelectCount() {
        return damageAutoSwapSelectCount;
    }

    public long debugDamageAutoSwapHeldResetCount() {
        return damageAutoSwapHeldResetCount;
    }

    public int debugDamageLastAutoSwapFromSlot() {
        return damageLastAutoSwapFromSlot;
    }

    public int debugDamageLastAutoSwapToSlot() {
        return damageLastAutoSwapToSlot;
    }

    public String debugDamageStateSummary() {
        return damageBreakState.summary();
    }

    public int debugCurrentVanillaBreakTicks() {
        return damageBreakState.vanillaBreakTicks;
    }

    public int debugCurrentTargetBreakTicks() {
        return damageBreakState.targetBreakTicks;
    }

    public float debugCurrentBreakDelta() {
        return damageBreakState.lastDelta;
    }

    public void debugConfigureDamageHarness(MiningAccelerationMode accelerationMode, double damageMultiplier, Block targetBlock) {
        debugConfigureDamageHarness(accelerationMode, damageMultiplier, targetBlock, 1);
    }

    public void debugConfigureDamageHarness(MiningAccelerationMode accelerationMode, double damageMultiplier, Block targetBlock, int maxBlocksPerTick) {
        debugConfigureDamageHarness(accelerationMode, damageMultiplier, targetBlock, maxBlocksPerTick, true);
    }

    public void debugConfigureDamageHarness(MiningAccelerationMode accelerationMode, double damageMultiplier, Block targetBlock, int maxBlocksPerTick, boolean speedMineAutoSwap) {
        this.accelerationMode.set(accelerationMode);
        this.damage.set(MathHelper.clamp(damageMultiplier, DAMAGE_MIN, DAMAGE_MAX));
        this.debugAcceleration.set(false);
        this.shape.set(Shape.Sphere);
        this.range.set(8.0);
        this.wallsRange.set(8.0);
        this.mode.set(Mode.All);
        this.delay.set(0);
        this.maxBlocksPerTick.set(Math.max(1, maxBlocksPerTick));
        this.sortMode.set(SortMode.Closest);
        this.suitableTools.set(false);
        this.interact.set(false);
        this.rotate.set(false);
        this.baritoneArea.set(false);
        this.speedMineAutoSwap.set(speedMineAutoSwap);
        this.grimBypass.set(false);
        this.listMode.set(ListMode.Whitelist);
        whitelist.get().clear();
        blacklist.get().clear();
        if (targetBlock != null) whitelist.get().add(targetBlock);
        miningStrategy.resetDamageBreakState("harness-config");
    }

    public void debugResetDamageHarnessState() {
        miningStrategy.resetDamageBreakState("harness-reset");
        blocks.clear();
        interacted.clear();
        firstBlock = true;
        timer = 0;
        noBlockTimer = 0;
        damageForcedFinishCount = 0L;
        damageRetryCount = 0L;
        damageAutoSwapSelectCount = 0L;
        damageAutoSwapHeldResetCount = 0L;
        damageLastAutoSwapFromSlot = -1;
        damageLastAutoSwapToSlot = -1;
    }

    public enum ListMode {
        Whitelist,
        Blacklist
    }

    public enum Mode {
        All,
        Flatten,
        Smash
    }

    public enum SortMode {
        None,
        Closest,
        Furthest,
        TopDown
    }

    public enum Shape {
        Cube,
        UniformCube,
        Sphere
    }

    public enum MiningAccelerationMode {
        Off,
        SpeedMineDamage,
        Insta
    }

    public static int chebyshevDist(int x1, int y1, int z1, int x2, int y2, int z2) {
        int dX = Math.abs(x2 - x1);
        int dY = Math.abs(y2 - y1);
        int dZ = Math.abs(z2 - z1);
        return Math.max(Math.max(dX, dY), dZ);
    }
}
