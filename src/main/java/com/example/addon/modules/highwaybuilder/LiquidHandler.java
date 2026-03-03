package com.example.addon.modules.highwaybuilder;

import net.minecraft.client.MinecraftClient;
import net.minecraft.fluid.FluidState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

public class LiquidHandler {
    private static final MinecraftClient mc = MinecraftClient.getInstance();

    private final HighwayBuilder module;

    public LiquidHandler(HighwayBuilder module) {
        this.module = module;
    }

    /**
     * Checks for liquid blocks adjacent to the given task's block position.
     * Creates filler placement tasks to patch any found liquids.
     * Returns true if liquid was found.
     */
    public boolean handleLiquid(BlockTask blockTask) {
        if (mc.world == null || mc.player == null) return false;

        boolean foundLiquid = false;
        double maxReach = module.maxReach.get();

        for (Direction side : Direction.values()) {
            if (side == Direction.DOWN) continue;

            BlockPos neighbourPos = blockTask.blockPos.offset(side);
            FluidState fluidState = mc.world.getFluidState(neighbourPos);

            if (fluidState.isEmpty()) continue;

            Vec3d eyePos = mc.player.getEyePos();
            double dist = eyePos.distanceTo(Vec3d.ofCenter(neighbourPos));

            if (dist > maxReach
                || HWUtils.getNeighbourSequence(neighbourPos, module.placementSearch.get(),
                    maxReach, !module.illegalPlacements.get()).isEmpty()) {
                blockTask.updateState(TaskState.DONE);
                return true;
            }

            foundLiquid = true;

            BlockTask existing = module.taskManager.getTasks().get(neighbourPos);
            if (existing != null) {
                updateLiquidTask(existing);
            } else {
                // Do not create permanent filler tasks in blueprint area.
                // Blueprint regeneration will create a proper task for this cell.
                if (module.blueprintGenerator != null
                    && module.blueprintGenerator.isInsideBlueprint(neighbourPos)) {
                    continue;
                }

                BlockTask newTask = new BlockTask(neighbourPos, TaskState.LIQUID, module.getFillerMat());
                BlueprintTask blueprintTask = new BlueprintTask(module.getFillerMat(), true, false);
                module.taskManager.addTask(newTask, blueprintTask);
            }
        }

        return foundLiquid;
    }

    public void updateLiquidTask(BlockTask blockTask) {
        blockTask.updateState(TaskState.LIQUID);
    }
}
