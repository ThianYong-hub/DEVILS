package com.devils.addon.modules.highwaybuilder;

import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.HashMap;
import java.util.Map;

public class BlueprintGenerator {
    private final Map<BlockPos, BlueprintTask> blueprint = new HashMap<>();

    // References set by HighwayBuilder before generation
    private HighwayBuilder module;

    public BlueprintGenerator(HighwayBuilder module) {
        this.module = module;
    }

    public Map<BlockPos, BlueprintTask> getBlueprint() {
        return blueprint;
    }

    public void generateBlueprint(BlockPos currentBlockPos, HWDirection startingDirection) {
        blueprint.clear();
        BlockPos basePos = currentBlockPos.down();

        Structure mode = module.mode.get();
        int width = module.width.get();
        int height = module.height.get();
        int clearHeight = mode == Structure.HIGHWAY
            ? Math.max(height, module.miningRangeUp.get() + 2)
            : height;
        boolean clearSpace = module.clearSpace.get();
        boolean backfill = module.backfill.get();
        boolean cleanFloor = module.cleanFloor.get();
        boolean cleanRightWall = module.cleanRightWall.get();
        boolean cleanLeftWall = module.cleanLeftWall.get();
        boolean cleanRoof = module.cleanRoof.get();
        boolean cleanCorner = module.cleanCorner.get();
        boolean cornerBlock = module.cornerBlock.get();
        boolean railing = module.railing.get();
        double maxReach = module.maxReach.get();
        Block material = module.getMaterial();
        Block fillerMat = module.getFillerMat();

        if (mode == Structure.FLAT) {
            generateFlat(basePos, width, height, clearSpace, material);
            return;
        }

        HWDirection zDirection = startingDirection;
        HWDirection xDirection = zDirection.clockwise(zDirection.isDiagonal ? 1 : 2);

        double dx = startingDirection.directionVec.getX();
        double dz = startingDirection.directionVec.getZ();
        double directionStepLen = Math.max(1.0, Math.sqrt(dx * dx + dz * dz));

        double effectiveBreakReach = module.taskManager != null
            ? module.taskManager.getEffectiveMiningReach()
            : Math.min(module.miningReach.get(), module.maxReach.get());
        double generationReach = Math.max(effectiveBreakReach, maxReach);

        int forwardSpan = Math.max(
            (int) Math.ceil((generationReach + 1.0) / directionStepLen),
            4
        );
        int backwardSpan = mode == Structure.TUNNEL && backfill ? 1 : 0;
        int minX = -backwardSpan;
        int maxX = forwardSpan;

        for (int x = minX; x <= maxX; x++) {
            BlockPos thisPos = basePos.add(
                zDirection.directionVec.getX() * x,
                zDirection.directionVec.getY() * x,
                zDirection.directionVec.getZ() * x
            );

            if (clearSpace) {
                generateClear(thisPos, xDirection, width, clearHeight, mode, railing, cornerBlock, material, fillerMat);
            }

            if (mode == Structure.TUNNEL) {
                if (backfill) {
                    generateBackfill(thisPos, xDirection, width, height, fillerMat,
                        module.pathfinder.startingBlockPos, currentBlockPos);
                } else {
                    if (cleanFloor) generateFloor(thisPos, xDirection, width, cornerBlock, fillerMat);
                    if (cleanRightWall || cleanLeftWall) generateWalls(thisPos, xDirection, width, height, cornerBlock, cleanRightWall, cleanLeftWall, fillerMat);
                    if (cleanRoof) generateRoof(thisPos, xDirection, width, height, fillerMat);
                    if (cleanCorner && !cornerBlock && width > 2) generateCorner(thisPos, xDirection, width, fillerMat);
                }
            } else {
                generateBase(thisPos, xDirection, width, mode, railing, cornerBlock,
                    module.railingHeight.get(), material, fillerMat, startingDirection);
            }
        }

        // Floor line for tunnel mode
        if (mode == Structure.TUNNEL && (!cleanFloor || backfill)) {
            if (startingDirection.isDiagonal) {
                for (int x = 0; x <= (int) Math.floor(maxReach); x++) {
                    BlockPos pos = basePos.add(
                        zDirection.directionVec.getX() * x,
                        0,
                        zDirection.directionVec.getZ() * x
                    );
                    blueprint.put(pos, new BlueprintTask(fillerMat, true, false));
                    HWDirection diag = startingDirection.clockwise(7);
                    blueprint.put(pos.add(diag.directionVec.getX(), 0, diag.directionVec.getZ()),
                        new BlueprintTask(fillerMat, true, false));
                }
            } else {
                for (int x = 0; x <= (int) Math.floor(maxReach); x++) {
                    BlockPos pos = basePos.add(
                        zDirection.directionVec.getX() * x,
                        0,
                        zDirection.directionVec.getZ() * x
                    );
                    blueprint.put(pos, new BlueprintTask(fillerMat, true, false));
                }
            }
        }
    }

    private void generateClear(BlockPos basePos, HWDirection xDirection, int width, int height,
                                Structure mode, boolean railing, boolean cornerBlock,
                                Block material, Block fillerMat) {
        for (int w = 0; w < width; w++) {
            for (int h = 0; h < height; h++) {
                int x = w - width / 2;
                BlockPos pos = basePos.add(
                    xDirection.directionVec.getX() * x,
                    0,
                    xDirection.directionVec.getZ() * x
                ).up(h);

                if (mode == Structure.HIGHWAY && h == 0 && isRail(w, width, railing)) {
                    continue;
                }

                if (mode == Structure.HIGHWAY) {
                    blueprint.put(pos, new BlueprintTask(Blocks.AIR));
                } else {
                    if (!(isRail(w, width, railing) && h == 0 && !cornerBlock && width > 2)) {
                        blueprint.put(pos.up(), new BlueprintTask(Blocks.AIR));
                    }
                }
            }
        }
    }

    private void generateBase(BlockPos basePos, HWDirection xDirection, int width,
                               Structure mode, boolean railing, boolean cornerBlock,
                               int railingHeight, Block material, Block fillerMat,
                               HWDirection startingDirection) {
        for (int w = 0; w < width; w++) {
            int x = w - width / 2;
            BlockPos pos = basePos.add(
                xDirection.directionVec.getX() * x,
                0,
                xDirection.directionVec.getZ() * x
            );

            if (mode == Structure.HIGHWAY && isRail(w, width, railing)) {
                if (!cornerBlock && width > 2 && startingDirection.isDiagonal) {
                    blueprint.put(pos, new BlueprintTask(fillerMat, false, true));
                }
                int startHeight = (cornerBlock && width > 2) ? 0 : 1;
                for (int y = startHeight; y <= railingHeight; y++) {
                    blueprint.put(pos.up(y), new BlueprintTask(material));
                }
            } else {
                blueprint.put(pos, new BlueprintTask(material));
            }
        }
    }

    private void generateFloor(BlockPos basePos, HWDirection xDirection, int width,
                                boolean cornerBlock, Block fillerMat) {
        int wid = (cornerBlock && width > 2) ? width : width - 2;
        for (int w = 0; w < wid; w++) {
            int x = w - wid / 2;
            BlockPos pos = basePos.add(
                xDirection.directionVec.getX() * x,
                0,
                xDirection.directionVec.getZ() * x
            );
            blueprint.put(pos, new BlueprintTask(fillerMat, true, false));
        }
    }

    private void generateWalls(BlockPos basePos, HWDirection xDirection, int width, int height,
                                boolean cornerBlock, boolean cleanRight, boolean cleanLeft,
                                Block fillerMat) {
        int cb = (!cornerBlock && width > 2) ? 1 : 0;
        for (int h = cb; h < height; h++) {
            if (cleanRight) {
                blueprint.put(basePos.add(
                    xDirection.directionVec.getX() * (width - width / 2),
                    h + 1,
                    xDirection.directionVec.getZ() * (width - width / 2)
                ), new BlueprintTask(fillerMat, true, false));
            }
            if (cleanLeft) {
                blueprint.put(basePos.add(
                    xDirection.directionVec.getX() * (-1 - width / 2),
                    h + 1,
                    xDirection.directionVec.getZ() * (-1 - width / 2)
                ), new BlueprintTask(fillerMat, true, false));
            }
        }
    }

    private void generateRoof(BlockPos basePos, HWDirection xDirection, int width, int height,
                               Block fillerMat) {
        for (int w = 0; w < width; w++) {
            int x = w - width / 2;
            BlockPos pos = basePos.add(
                xDirection.directionVec.getX() * x,
                height + 1,
                xDirection.directionVec.getZ() * x
            );
            blueprint.put(pos, new BlueprintTask(fillerMat, true, false));
        }
    }

    private void generateCorner(BlockPos basePos, HWDirection xDirection, int width, Block fillerMat) {
        blueprint.put(basePos.add(
            xDirection.directionVec.getX() * (-1 - width / 2 + 1),
            1,
            xDirection.directionVec.getZ() * (-1 - width / 2 + 1)
        ), new BlueprintTask(fillerMat, true, false));

        blueprint.put(basePos.add(
            xDirection.directionVec.getX() * (width - width / 2 - 1),
            1,
            xDirection.directionVec.getZ() * (width - width / 2 - 1)
        ), new BlueprintTask(fillerMat, true, false));
    }

    private void generateBackfill(BlockPos basePos, HWDirection xDirection, int width, int height,
                                   Block fillerMat, BlockPos startingBlockPos, BlockPos currentBlockPos) {
        Vec3d startCenter = Vec3d.ofCenter(startingBlockPos);
        Vec3d currentCenter = Vec3d.ofCenter(currentBlockPos);
        double distStartToCurrent = startCenter.distanceTo(currentCenter);

        for (int w = 0; w < width; w++) {
            for (int h = 0; h < height; h++) {
                int x = w - width / 2;
                BlockPos pos = basePos.add(
                    xDirection.directionVec.getX() * x,
                    h + 1,
                    xDirection.directionVec.getZ() * x
                );

                double distToPos = startCenter.distanceTo(Vec3d.ofCenter(pos));
                if (distToPos + 1 < distStartToCurrent) {
                    blueprint.put(pos, new BlueprintTask(fillerMat, true, false));
                }
            }
        }
    }

    private void generateFlat(BlockPos basePos, int width, int height, boolean clearSpace, Block material) {
        for (int w1 = 0; w1 < width; w1++) {
            for (int w2 = 0; w2 < width; w2++) {
                int x = w1 - width / 2;
                int z = w2 - width / 2;
                blueprint.put(basePos.add(x, 0, z), new BlueprintTask(material));
            }
        }

        if (!clearSpace) return;
        for (int w1 = -width; w1 <= width; w1++) {
            for (int w2 = -width; w2 <= width; w2++) {
                for (int y = 1; y < height; y++) {
                    int x = w1 - width / 2;
                    int z = w2 - width / 2;
                    blueprint.put(basePos.add(x, y, z), new BlueprintTask(Blocks.AIR));
                }
            }
        }
    }

    private boolean isRail(int w, int width, boolean railing) {
        return railing && (w < 1 || w >= width - 1);
    }

    public boolean isInsideBlueprint(BlockPos pos) {
        return blueprint.containsKey(pos);
    }

    public boolean isInsideBlueprintBuild(BlockPos pos) {
        BlueprintTask task = blueprint.get(pos);
        return task != null && task.targetBlock == module.getMaterial();
    }
}



