package com.devils.addon.modules.highwaybuilder;

import java.awt.Color;
import java.util.concurrent.ConcurrentLinkedDeque;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import net.minecraft.block.Block;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

class BlueprintTask {
    public final Block targetBlock;
    public final boolean isFiller;
    public final boolean isSupport;

    public BlueprintTask(Block targetBlock) {
        this(targetBlock, false, false);
    }

    public BlueprintTask(Block targetBlock, boolean isFiller, boolean isSupport) {
        this.targetBlock = targetBlock;
        this.isFiller = isFiller;
        this.isSupport = isSupport;
    }
}

enum EChestMineMode {
    Normal,
    Insta
}

enum EChestSwapMode {
    Normal,
    Silent
}

enum MovementState {
    RUNNING,
    PICKUP,
    BRIDGE,
    RESTOCK
}

record PlaceInfo(BlockPos pos, Direction side) {
}

enum Structure {
    HIGHWAY,
    TUNNEL,
    FLAT
}

enum TaskState {
    BROKEN(1000, 1000, new Color(111, 0, 0)),
    PLACED(1000, 1000, new Color(53, 222, 66)),
    LIQUID(100, 100, new Color(114, 27, 255)),
    PICKUP(500, 500, new Color(252, 3, 207)),
    RESTOCK(500, 500, new Color(252, 3, 207)),
    OPEN_CONTAINER(500, 500, new Color(252, 3, 207)),
    BREAKING(100, 100, new Color(240, 222, 60)),
    BREAK(20, 20, new Color(222, 0, 0)),
    PLACE(20, 20, new Color(35, 188, 254)),
    PENDING_BREAK(20, 20, new Color(0, 0, 0)),
    PENDING_PLACE(20, 20, new Color(0, 0, 0)),
    IMPOSSIBLE_PLACE(100, 100, new Color(16, 74, 94)),
    DONE(69420, 34, new Color(50, 50, 50));

    public final int stuckThreshold;
    public final int stuckTimeout;
    public final Color color;

    TaskState(int stuckThreshold, int stuckTimeout, Color color) {
        this.stuckThreshold = stuckThreshold;
        this.stuckTimeout = stuckTimeout;
        this.color = color;
    }
}

enum ToolSwapMode {
    Vanilla,
    Silent
}

class HighwayRenderer {
    private final HighwayBuilder module;

    HighwayRenderer(HighwayBuilder module) {
        this.module = module;
    }

    void render(Render3DEvent event) {
        if (module.taskManager == null) return;

        boolean filled = module.filled.get();
        boolean outline = module.outline.get();
        int aFilled = module.aFilled.get();
        int aOutline = module.aOutline.get();

        ShapeMode shapeMode;
        if (filled && outline) shapeMode = ShapeMode.Both;
        else if (filled) shapeMode = ShapeMode.Sides;
        else if (outline) shapeMode = ShapeMode.Lines;
        else return;

        for (BlockTask task : module.taskManager.getTasks().values()) {
            if (task.taskState == TaskState.DONE) continue;

            Color c = task.taskState.color;
            BlockPos pos = task.blockPos;

            double shrink = 0.0;
            if (module.popUp.get()) {
                long elapsed = System.currentTimeMillis() - task.timestamp;
                int speed = module.popUpSpeed.get();
                if (elapsed < speed) shrink = 0.5 * (1.0 - (double) elapsed / speed);
            }

            double x1 = pos.getX() + shrink;
            double y1 = pos.getY() + shrink;
            double z1 = pos.getZ() + shrink;
            double x2 = pos.getX() + 1 - shrink;
            double y2 = pos.getY() + 1 - shrink;
            double z2 = pos.getZ() + 1 - shrink;

            event.renderer.box(
                x1, y1, z1, x2, y2, z2,
                new meteordevelopment.meteorclient.utils.render.color.Color(c.getRed(), c.getGreen(), c.getBlue(), aFilled),
                new meteordevelopment.meteorclient.utils.render.color.Color(c.getRed(), c.getGreen(), c.getBlue(), aOutline),
                shapeMode, 0
            );
        }

        if (module.showCurrentPos.get()) {
            BlockPos cur = module.pathfinder.currentBlockPos;
            event.renderer.box(
                cur.getX() + 0.1, cur.getY() + 0.1, cur.getZ() + 0.1,
                cur.getX() + 0.9, cur.getY() + 0.9, cur.getZ() + 0.9,
                new meteordevelopment.meteorclient.utils.render.color.Color(255, 255, 255, 30),
                new meteordevelopment.meteorclient.utils.render.color.Color(255, 255, 255, 100),
                ShapeMode.Both, 0
            );
        }
    }
}

class HighwayStatistics {
    public int totalBlocksBroken;
    public int totalBlocksPlaced;
    public int durabilityUsages;

    public final ConcurrentLinkedDeque<Long> simpleMovingAverageBreaks = new ConcurrentLinkedDeque<>();
    public final ConcurrentLinkedDeque<Long> simpleMovingAveragePlaces = new ConcurrentLinkedDeque<>();
    public final ConcurrentLinkedDeque<Long> simpleMovingAverageDistance = new ConcurrentLinkedDeque<>();

    private static final long WINDOW_MS = 60_000L;

    void update() {
        long now = System.currentTimeMillis();
        cleanDeque(simpleMovingAverageBreaks, now);
        cleanDeque(simpleMovingAveragePlaces, now);
        cleanDeque(simpleMovingAverageDistance, now);
    }

    double getBreaksPerSecond() {
        return simpleMovingAverageBreaks.size() / (WINDOW_MS / 1000.0);
    }

    double getPlacesPerSecond() {
        return simpleMovingAveragePlaces.size() / (WINDOW_MS / 1000.0);
    }

    double getDistancePerHour() {
        return simpleMovingAverageDistance.size() * (3600_000.0 / WINDOW_MS);
    }

    void reset() {
        totalBlocksBroken = 0;
        totalBlocksPlaced = 0;
        durabilityUsages = 0;
        simpleMovingAverageBreaks.clear();
        simpleMovingAveragePlaces.clear();
        simpleMovingAverageDistance.clear();
    }

    String getInfoString() {
        return String.format("B:%.1f/s P:%.1f/s D:%.0f/h",
            getBreaksPerSecond(), getPlacesPerSecond(), getDistancePerHour());
    }

    private void cleanDeque(ConcurrentLinkedDeque<Long> deque, long now) {
        while (!deque.isEmpty() && now - deque.peekFirst() > WINDOW_MS) {
            deque.pollFirst();
        }
    }
}

final class TaskSpatialRules {
    private static final double BACKWARD_TASK_PADDING = 0.10;

    private TaskSpatialRules() {
    }

    static boolean startPadding(HighwayBuilder module, BlockPos checkPos) {
        HWDirection dir = module.pathfinder.startingDirection;
        BlockPos origin = module.pathfinder.currentBlockPos;
        return dir.forwardProgress(origin, checkPos) < -BACKWARD_TASK_PADDING;
    }

    static boolean isBehindPos(HighwayBuilder module, BlockPos origin, BlockPos check) {
        HWDirection dir = module.pathfinder.startingDirection;
        int width = module.width.get();

        HWDirection ccw = dir.counterClockwise(2);
        HWDirection cw = dir.clockwise(2);

        BlockPos a = origin.add(
            ccw.directionVec.getX() * width, 0, ccw.directionVec.getZ() * width
        );
        BlockPos b = origin.add(
            cw.directionVec.getX() * width, 0, cw.directionVec.getZ() * width
        );

        return ((b.getX() - a.getX()) * (check.getZ() - a.getZ())
            - (b.getZ() - a.getZ()) * (check.getX() - a.getX())) > 0;
    }

    static boolean shouldBeIgnored(HighwayBuilder module, BlockPos blockPos, net.minecraft.block.BlockState currentState) {
        String regName = net.minecraft.registry.Registries.BLOCK.getId(currentState.getBlock()).toString();
        return module.getIgnoreBlocks().contains(regName)
            && !module.blueprintGenerator.isInsideBlueprintBuild(blockPos)
            && !module.pathfinder.currentBlockPos.add(module.pathfinder.startingDirection.directionVec).equals(blockPos);
    }
}

