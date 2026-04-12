package com.example.addon.modules;

import com.example.addon.AddonTemplate;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
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
import net.minecraft.network.packet.c2s.play.HandSwingC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.lang.reflect.Method;
import java.util.Set;

public class NukerPlus extends Module {
    private static final double CUBE_VERTICAL_SNAP = 0.125D;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgWhitelist = settings.createGroup("Whitelist");
    private final SettingGroup sgRender = settings.createGroup("Render");

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

    private final Setting<Integer> maxBlocksPerTick = sgGeneral.add(new IntSetting.Builder()
        .name("max-blocks-per-tick")
        .description("Maximum blocks to try to break per tick. Useful when insta mining.")
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

    private final Setting<Boolean> packetMine = sgGeneral.add(new BoolSetting.Builder()
        .name("packet-mine")
        .description("Attempt to instamine everything at once.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> suitableTools = sgGeneral.add(new BoolSetting.Builder()
        .name("only-suitable-tools")
        .description("Only mines when using an appropriate for the block.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> interact = sgGeneral.add(new BoolSetting.Builder()
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

    private final Setting<Boolean> swing = sgRender.add(new BoolSetting.Builder()
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

    private final List<BlockPos> blocks = new ArrayList<>();
    private final Set<BlockPos> interacted = new ObjectOpenHashSet<>();

    private boolean firstBlock;
    private final BlockPos.Mutable lastBlockPos = new BlockPos.Mutable();

    private int timer;
    private int noBlockTimer;

    private final BlockPos.Mutable pos1 = new BlockPos.Mutable();
    private final BlockPos.Mutable pos2 = new BlockPos.Mutable();
    private int maxh;
    private int maxv;
    private final BaritoneSelectionBridge baritoneSelectionBridge = new BaritoneSelectionBridge();
    private boolean warnedBaritoneUnavailable;
    private boolean warnedBaritoneSelectionMissing;

    public NukerPlus() {
        super(AddonTemplate.CATEGORY, "nuker-plus", "Breaks blocks around you with stable Cube bounds.");
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
        if (mc.player == null || mc.world == null) return;

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
        BaritoneSelectionSnapshot selectionSnapshot = baritoneArea.get()
            ? baritoneSelectionBridge.snapshot()
            : BaritoneSelectionSnapshot.disabled();
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

            if (blocks.isEmpty()) {
                interacted.clear();
                if (noBlockTimer++ >= delay.get()) firstBlock = true;
                return;
            } else {
                noBlockTimer = 0;
            }

            if (!firstBlock && !lastBlockPos.equals(blocks.getFirst())) {
                timer = delay.get();
                firstBlock = false;
                lastBlockPos.set(blocks.getFirst());
                if (timer > 0) return;
            }

            int count = 0;
            for (BlockPos block : blocks) {
                if (count >= maxBlocksPerTick.get()) break;

                boolean canInstaMine = BlockUtils.canInstaBreak(block);

                if (rotate.get()) Rotations.rotate(Rotations.getYaw(block), Rotations.getPitch(block), () -> breakBlock(block));
                else breakBlock(block);

                if (enableRenderBreaking.get()) {
                    RenderUtils.renderTickingBlock(block, sideColor.get(), lineColor.get(), shapeModeBreak.get(), 0, 8, true, false);
                }

                lastBlockPos.set(block);
                count++;
                if (!canInstaMine && !packetMine.get()) break;
            }

            firstBlock = false;
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

    private void publishBaritoneAreaWarnings(BaritoneSelectionSnapshot snapshot) {
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

    private void breakBlock(BlockPos blockPos) {
        if (interact.get()) {
            BlockUtils.interact(new BlockHitResult(blockPos.toCenterPos(), BlockUtils.getDirection(blockPos), blockPos, true), Hand.MAIN_HAND, swing.get());
            interacted.add(blockPos);
        } else if (packetMine.get()) {
            mc.getNetworkHandler().sendPacket(
                new PlayerActionC2SPacket(PlayerActionC2SPacket.Action.START_DESTROY_BLOCK, blockPos, BlockUtils.getDirection(blockPos))
            );

            if (swing.get()) mc.player.swingHand(Hand.MAIN_HAND);
            else mc.getNetworkHandler().sendPacket(new HandSwingC2SPacket(Hand.MAIN_HAND));

            mc.getNetworkHandler().sendPacket(
                new PlayerActionC2SPacket(PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK, blockPos, BlockUtils.getDirection(blockPos))
            );
        } else {
            BlockUtils.breakBlock(blockPos, swing.get());
        }
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

    static int resolveCubeBaseY(double playerY) {
        return (int) Math.floor(playerY + CUBE_VERTICAL_SNAP);
    }

    private static final class BaritoneSelectionBridge {
        private final boolean available;
        private final Object primaryBaritone;
        private final Method getSelectionManagerMethod;
        private final Method getSelectionsMethod;
        private final Method minMethod;
        private final Method maxMethod;

        private BaritoneSelectionBridge() {
            Object resolvedBaritone = null;
            Method resolvedSelectionManager = null;
            Method resolvedSelections = null;
            Method resolvedMin = null;
            Method resolvedMax = null;
            boolean resolvedAvailable = false;

            try {
                Class<?> apiClass = Class.forName("baritone.api.BaritoneAPI");
                Object provider = apiClass.getMethod("getProvider").invoke(null);
                resolvedBaritone = provider.getClass().getMethod("getPrimaryBaritone").invoke(provider);
                resolvedSelectionManager = resolvedBaritone.getClass().getMethod("getSelectionManager");
                Object manager = resolvedSelectionManager.invoke(resolvedBaritone);
                resolvedSelections = manager.getClass().getMethod("getSelections");

                Class<?> selectionClass = Class.forName("baritone.api.selection.ISelection");
                resolvedMin = selectionClass.getMethod("min");
                resolvedMax = selectionClass.getMethod("max");
                resolvedAvailable = true;
            } catch (Throwable ignored) {
            }

            available = resolvedAvailable;
            primaryBaritone = resolvedBaritone;
            getSelectionManagerMethod = resolvedSelectionManager;
            getSelectionsMethod = resolvedSelections;
            minMethod = resolvedMin;
            maxMethod = resolvedMax;
        }

        private BaritoneSelectionSnapshot snapshot() {
            if (!available || primaryBaritone == null) return BaritoneSelectionSnapshot.unavailable();

            try {
                Object manager = getSelectionManagerMethod.invoke(primaryBaritone);
                Object rawSelections = getSelectionsMethod.invoke(manager);
                if (!(rawSelections instanceof Object[] selections) || selections.length == 0) {
                    return BaritoneSelectionSnapshot.empty();
                }

                List<BlockBounds> bounds = new ArrayList<>(selections.length);
                for (Object selection : selections) {
                    if (selection == null) continue;

                    Object min = minMethod.invoke(selection);
                    Object max = maxMethod.invoke(selection);
                    BlockBounds bound = BlockBounds.of(min, max);
                    if (bound != null) bounds.add(bound);
                }

                if (bounds.isEmpty()) return BaritoneSelectionSnapshot.empty();
                return BaritoneSelectionSnapshot.of(bounds);
            } catch (Throwable ignored) {
                return BaritoneSelectionSnapshot.unavailable();
            }
        }
    }

    private record BaritoneSelectionSnapshot(boolean available, List<BlockBounds> bounds) {
        private static final BaritoneSelectionSnapshot DISABLED = new BaritoneSelectionSnapshot(true, Collections.emptyList());
        private static final BaritoneSelectionSnapshot UNAVAILABLE = new BaritoneSelectionSnapshot(false, Collections.emptyList());

        private static BaritoneSelectionSnapshot disabled() {
            return DISABLED;
        }

        private static BaritoneSelectionSnapshot unavailable() {
            return UNAVAILABLE;
        }

        private static BaritoneSelectionSnapshot empty() {
            return new BaritoneSelectionSnapshot(true, Collections.emptyList());
        }

        private static BaritoneSelectionSnapshot of(List<BlockBounds> bounds) {
            return new BaritoneSelectionSnapshot(true, List.copyOf(bounds));
        }

        private boolean hasSelections() {
            return !bounds.isEmpty();
        }

        private boolean allows(BlockPos pos) {
            if (this == DISABLED) return true;
            if (!available || bounds.isEmpty()) return false;

            for (BlockBounds bound : bounds) {
                if (bound.contains(pos)) return true;
            }

            return false;
        }
    }

    private record BlockBounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        private static BlockBounds of(Object min, Object max) {
            if (!(min instanceof BlockPos minPos) || !(max instanceof BlockPos maxPos)) return null;
            return new BlockBounds(
                Math.min(minPos.getX(), maxPos.getX()),
                Math.min(minPos.getY(), maxPos.getY()),
                Math.min(minPos.getZ(), maxPos.getZ()),
                Math.max(minPos.getX(), maxPos.getX()),
                Math.max(minPos.getY(), maxPos.getY()),
                Math.max(minPos.getZ(), maxPos.getZ())
            );
        }

        private boolean contains(BlockPos pos) {
            return pos.getX() >= minX && pos.getX() <= maxX
                && pos.getY() >= minY && pos.getY() <= maxY
                && pos.getZ() >= minZ && pos.getZ() <= maxZ;
        }
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

    public static int chebyshevDist(int x1, int y1, int z1, int x2, int y2, int z2) {
        int dX = Math.abs(x2 - x1);
        int dY = Math.abs(y2 - y1);
        int dZ = Math.abs(z2 - z1);
        return Math.max(Math.max(dX, dY), dZ);
    }
}
