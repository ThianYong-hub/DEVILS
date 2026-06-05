package com.devils.addon.modules.stashmover;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StashMoverPearlApproachAnchorTest {
    @Test
    void centeredWaterAnchorAcceptsSmallOffsets() {
        BlockPos water = new BlockPos(0, 64, 0);

        assertTrue(StashMoverPearlApproach.isCenteredOverWater(new Vec3d(0.50, 64.0, 0.50), water));
        assertTrue(StashMoverPearlApproach.isCenteredOverWater(new Vec3d(0.72, 64.0, 0.50), water));
        assertTrue(StashMoverPearlApproach.isCenteredOverWater(new Vec3d(0.50, 64.0, 0.27), water));
    }

    @Test
    void centeredWaterAnchorRejectsEdgeOffsets() {
        BlockPos water = new BlockPos(0, 64, 0);

        assertFalse(StashMoverPearlApproach.isCenteredOverWater(new Vec3d(0.81, 64.0, 0.50), water));
        assertFalse(StashMoverPearlApproach.isCenteredOverWater(new Vec3d(0.50, 64.0, 0.18), water));
        assertFalse(StashMoverPearlApproach.isCenteredOverWater(new Vec3d(0.76, 64.0, 0.76), water));
    }
}
