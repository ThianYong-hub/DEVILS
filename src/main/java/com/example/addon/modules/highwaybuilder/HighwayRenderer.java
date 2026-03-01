package com.example.addon.modules.highwaybuilder;

import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import net.minecraft.util.math.BlockPos;

public class HighwayRenderer {
    private final HighwayBuilder module;

    public HighwayRenderer(HighwayBuilder module) {
        this.module = module;
    }

    public void render(Render3DEvent event) {
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

            java.awt.Color c = task.taskState.color;
            BlockPos pos = task.blockPos;

            double shrink = 0;
            if (module.popUp.get()) {
                long elapsed = System.currentTimeMillis() - task.timestamp;
                int speed = module.popUpSpeed.get();
                if (elapsed < speed) {
                    shrink = 0.5 * (1.0 - (double) elapsed / speed);
                }
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

        // Render current position marker
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
