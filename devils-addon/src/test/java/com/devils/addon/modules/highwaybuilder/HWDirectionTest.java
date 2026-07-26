package com.devils.addon.modules.highwaybuilder;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3i;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HWDirectionTest {
    private static final BlockPos ORIGIN = new BlockPos(100, 64, -50);

    /** Minecraft yaw for a HWDirection, i.e. the value that may be handed to Rotations/setYaw. */
    private static float minecraftYawOf(HWDirection direction) {
        return Math.floorMod(direction.yaw + 180, 360);
    }

    @Test
    void compassYawsAreClockwiseFromNorthInFortyFiveDegreeSteps() {
        assertEquals(0, HWDirection.NORTH.yaw);
        assertEquals(45, HWDirection.NORTH_EAST.yaw);
        assertEquals(90, HWDirection.EAST.yaw);
        assertEquals(135, HWDirection.SOUTH_EAST.yaw);
        assertEquals(180, HWDirection.SOUTH.yaw);
        assertEquals(225, HWDirection.SOUTH_WEST.yaw);
        assertEquals(270, HWDirection.WEST.yaw);
        assertEquals(315, HWDirection.NORTH_WEST.yaw);
    }

    @Test
    void compassYawIsHalfATurnAwayFromMinecraftYaw() {
        // Minecraft: SOUTH=0, WEST=90, NORTH=180, EAST=270 (== -90).
        assertEquals(0.0f, Direction.SOUTH.getPositiveHorizontalDegrees(), 0.0f);
        assertEquals(90.0f, Direction.WEST.getPositiveHorizontalDegrees(), 0.0f);
        assertEquals(180.0f, Direction.NORTH.getPositiveHorizontalDegrees(), 0.0f);
        assertEquals(270.0f, Direction.EAST.getPositiveHorizontalDegrees(), 0.0f);

        assertEquals(Direction.NORTH.getPositiveHorizontalDegrees(), minecraftYawOf(HWDirection.NORTH), 0.0f);
        assertEquals(Direction.EAST.getPositiveHorizontalDegrees(), minecraftYawOf(HWDirection.EAST), 0.0f);
        assertEquals(Direction.SOUTH.getPositiveHorizontalDegrees(), minecraftYawOf(HWDirection.SOUTH), 0.0f);
        assertEquals(Direction.WEST.getPositiveHorizontalDegrees(), minecraftYawOf(HWDirection.WEST), 0.0f);

        // The raw field alone is NOT a Minecraft yaw: callers must add the half turn.
        assertNotEquals(Direction.NORTH.getPositiveHorizontalDegrees(), (float) HWDirection.NORTH.yaw);
    }

    @Test
    void minecraftYawResolvesBackToTheMatchingCardinal() {
        assertEquals(HWDirection.SOUTH, HWDirection.fromYaw(Direction.SOUTH.getPositiveHorizontalDegrees()));
        assertEquals(HWDirection.WEST, HWDirection.fromYaw(Direction.WEST.getPositiveHorizontalDegrees()));
        assertEquals(HWDirection.NORTH, HWDirection.fromYaw(Direction.NORTH.getPositiveHorizontalDegrees()));
        assertEquals(HWDirection.EAST, HWDirection.fromYaw(Direction.EAST.getPositiveHorizontalDegrees()));

        // Players report yaw in (-180, 180]; east is -90 there.
        assertEquals(HWDirection.EAST, HWDirection.fromYaw(-90.0f));
        assertEquals(HWDirection.NORTH, HWDirection.fromYaw(-180.0f));
        assertEquals(HWDirection.SOUTH, HWDirection.fromYaw(720.0f));
        assertEquals(HWDirection.WEST, HWDirection.fromYaw(-630.0f));
    }

    @Test
    void everyDirectionRoundTripsThroughItsMinecraftYaw() {
        for (HWDirection direction : HWDirection.values()) {
            assertEquals(direction, HWDirection.fromYaw(minecraftYawOf(direction)), "round trip for " + direction);
        }
    }

    @Test
    void fromYawSnapsOnFortyFiveDegreeSectorBoundaries() {
        assertEquals(HWDirection.SOUTH, HWDirection.fromYaw(22.4f));
        assertEquals(HWDirection.SOUTH_WEST, HWDirection.fromYaw(22.5f));
        assertEquals(HWDirection.SOUTH_WEST, HWDirection.fromYaw(67.4f));
        assertEquals(HWDirection.WEST, HWDirection.fromYaw(67.5f));
        assertEquals(HWDirection.SOUTH_EAST, HWDirection.fromYaw(337.4f));
        assertEquals(HWDirection.SOUTH, HWDirection.fromYaw(337.5f));
    }

    @Test
    void fromYawCardinalNeverReturnsADiagonal() {
        assertEquals(HWDirection.SOUTH, HWDirection.fromYawCardinal(44.9f));
        assertEquals(HWDirection.WEST, HWDirection.fromYawCardinal(45.0f));
        assertEquals(HWDirection.WEST, HWDirection.fromYawCardinal(134.9f));
        assertEquals(HWDirection.NORTH, HWDirection.fromYawCardinal(135.0f));
        assertEquals(HWDirection.EAST, HWDirection.fromYawCardinal(-90.0f));
        assertEquals(HWDirection.SOUTH, HWDirection.fromYawCardinal(315.0f));

        for (int yaw = -720; yaw <= 720; yaw += 7) {
            assertFalse(HWDirection.fromYawCardinal(yaw).isDiagonal, "cardinal snap for yaw " + yaw);
        }
    }

    @Test
    void directionVectorsMatchMinecraftCardinalsAndTheirDiagonalSums() {
        assertEquals(Direction.NORTH.getVector(), HWDirection.NORTH.directionVec);
        assertEquals(Direction.EAST.getVector(), HWDirection.EAST.directionVec);
        assertEquals(Direction.SOUTH.getVector(), HWDirection.SOUTH.directionVec);
        assertEquals(Direction.WEST.getVector(), HWDirection.WEST.directionVec);

        assertEquals(new Vec3i(1, 0, -1), HWDirection.NORTH_EAST.directionVec);
        assertEquals(new Vec3i(1, 0, 1), HWDirection.SOUTH_EAST.directionVec);
        assertEquals(new Vec3i(-1, 0, 1), HWDirection.SOUTH_WEST.directionVec);
        assertEquals(new Vec3i(-1, 0, -1), HWDirection.NORTH_WEST.directionVec);

        for (HWDirection direction : HWDirection.values()) {
            assertEquals(0, direction.directionVec.getY(), direction + " must stay horizontal");
            assertEquals(direction.ordinal() % 2 == 1, direction.isDiagonal, direction + " diagonal flag");
        }
    }

    @Test
    void oppositeIsFourStepsAwayAndNegatesTheVector() {
        for (HWDirection direction : HWDirection.values()) {
            HWDirection opposite = direction.clockwise(4);
            assertEquals(-direction.directionVec.getX(), opposite.directionVec.getX(), direction + " opposite x");
            assertEquals(-direction.directionVec.getZ(), opposite.directionVec.getZ(), direction + " opposite z");
            assertEquals(direction, direction.clockwise(8));
            assertEquals(direction, direction.clockwise(0));
        }
    }

    @Test
    void clockwiseAndCounterClockwiseAreInverses() {
        for (HWDirection direction : HWDirection.values()) {
            for (int steps = 0; steps <= 8; steps++) {
                assertEquals(direction, direction.clockwise(steps).counterClockwise(steps));
                assertEquals(direction.clockwise(steps), direction.counterClockwise(8 - steps));
            }
        }
    }

    @Test
    void bothRotationsNormaliseNegativeSteps() {
        assertEquals(HWDirection.EAST, HWDirection.NORTH.counterClockwise(-2));
        assertEquals(HWDirection.NORTH.clockwise(2), HWDirection.NORTH.counterClockwise(-2));

        assertEquals(HWDirection.NORTH_WEST, HWDirection.NORTH.clockwise(-1));
        assertEquals(HWDirection.NORTH.counterClockwise(3), HWDirection.NORTH.clockwise(-3));
        for (HWDirection direction : HWDirection.values()) {
            assertEquals(direction, direction.clockwise(-8), direction + " full negative turn");
        }
    }

    @Test
    void lateralDirectionTurnsNinetyForCardinalsAndFortyFiveForDiagonals() {
        assertEquals(HWDirection.EAST, HWDirection.NORTH.lateralDirection());
        assertEquals(HWDirection.SOUTH, HWDirection.EAST.lateralDirection());
        assertEquals(HWDirection.WEST, HWDirection.SOUTH.lateralDirection());
        assertEquals(HWDirection.NORTH, HWDirection.WEST.lateralDirection());

        assertEquals(HWDirection.EAST, HWDirection.NORTH_EAST.lateralDirection());
        assertEquals(HWDirection.SOUTH, HWDirection.SOUTH_EAST.lateralDirection());

        for (HWDirection direction : HWDirection.values()) {
            int turn = Math.floorMod(direction.lateralDirection().yaw - direction.yaw, 360);
            assertEquals(direction.isDiagonal ? 45 : 90, turn, direction + " lateral turn");
        }
    }

    @Test
    void forwardProgressCountsBlocksAlongACardinalAxis() {
        assertEquals(5.0, HWDirection.NORTH.forwardProgress(ORIGIN, ORIGIN.add(0, 0, -5)), 1.0E-9);
        assertEquals(-5.0, HWDirection.NORTH.forwardProgress(ORIGIN, ORIGIN.add(0, 0, 5)), 1.0E-9);
        assertEquals(5.0, HWDirection.NORTH.forwardProgress(ORIGIN, ORIGIN.add(7, 0, -5)), 1.0E-9);
        assertEquals(0.0, HWDirection.NORTH.forwardProgress(ORIGIN, ORIGIN.add(7, 30, 0)), 1.0E-9);

        assertEquals(5.0, HWDirection.EAST.forwardProgress(ORIGIN, ORIGIN.add(5, 0, 0)), 1.0E-9);
        assertEquals(5.0, HWDirection.SOUTH.forwardProgress(ORIGIN, ORIGIN.add(0, 0, 5)), 1.0E-9);
        assertEquals(5.0, HWDirection.WEST.forwardProgress(ORIGIN, ORIGIN.add(-5, 0, 0)), 1.0E-9);
    }

    @Test
    void forwardProgressCountsDiagonalStepsNotBlocksTravelled() {
        // Five diagonal steps move five blocks on each axis but count as five, not ten.
        assertEquals(5.0, HWDirection.NORTH_EAST.forwardProgress(ORIGIN, ORIGIN.add(5, 0, -5)), 1.0E-9);
        assertEquals(-3.0, HWDirection.NORTH_EAST.forwardProgress(ORIGIN, ORIGIN.add(-3, 0, 3)), 1.0E-9);
        assertEquals(2.0, HWDirection.NORTH_EAST.forwardProgress(ORIGIN, ORIGIN.add(3, 0, -1)), 1.0E-9);
        assertEquals(0.0, HWDirection.NORTH_EAST.forwardProgress(ORIGIN, ORIGIN.add(1, 0, 1)), 1.0E-9);

        assertEquals(4.0, HWDirection.SOUTH_WEST.forwardProgress(ORIGIN, ORIGIN.add(-4, 0, 4)), 1.0E-9);
        assertEquals(0.5, HWDirection.SOUTH_EAST.forwardProgress(ORIGIN, ORIGIN.add(1, 0, 0)), 1.0E-9);
    }

    @Test
    void lateralOffsetIsSignedBlockDistanceFromTheCardinalCentreLine() {
        assertEquals(3.0, HWDirection.NORTH.lateralOffset(ORIGIN, ORIGIN.add(3, 0, -10)), 1.0E-9);
        assertEquals(-3.0, HWDirection.NORTH.lateralOffset(ORIGIN, ORIGIN.add(-3, 0, -10)), 1.0E-9);
        assertEquals(0.0, HWDirection.NORTH.lateralOffset(ORIGIN, ORIGIN.add(0, 0, -10)), 1.0E-9);

        assertEquals(4.0, HWDirection.EAST.lateralOffset(ORIGIN, ORIGIN.add(10, 0, 4)), 1.0E-9);
        assertEquals(-4.0, HWDirection.WEST.lateralOffset(ORIGIN, ORIGIN.add(-10, 0, 4)), 1.0E-9);
    }

    @Test
    void diagonalLateralOffsetUsesThePerpendicularCrossProduct() {
        // straight ahead has no lateral component; a quarter turn clockwise (south-east) is positive
        assertEquals(0.0, HWDirection.NORTH_EAST.lateralOffset(ORIGIN, ORIGIN.add(5, 0, -5)), 1.0E-9);
        assertEquals(1.0, HWDirection.NORTH_EAST.lateralOffset(ORIGIN, ORIGIN.add(1, 0, 1)), 1.0E-9);
        assertEquals(-1.0, HWDirection.NORTH_EAST.lateralOffset(ORIGIN, ORIGIN.add(-1, 0, -1)), 1.0E-9);
        assertEquals(0.5, HWDirection.NORTH_EAST.lateralOffset(ORIGIN, ORIGIN.add(1, 0, 0)), 1.0E-9);
    }

    @Test
    void lateralOffsetUsesTheSameHandednessForCardinalsAndDiagonals() {
        BlockPos cardinalRight = ORIGIN.add(HWDirection.NORTH.clockwise(2).directionVec);
        BlockPos diagonalRight = ORIGIN.add(HWDirection.NORTH_EAST.clockwise(2).directionVec);

        // A block a quarter turn clockwise of the build direction reads positive for both kinds.
        assertEquals(1.0, HWDirection.NORTH.lateralOffset(ORIGIN, cardinalRight), 1.0E-9);
        assertEquals(1.0, HWDirection.NORTH_EAST.lateralOffset(ORIGIN, diagonalRight), 1.0E-9);
    }

    @Test
    void forwardAndLateralComponentsReconstructTheOriginalOffset() {
        for (HWDirection direction : HWDirection.values()) {
            HWDirection lateralBasis = direction.clockwise(2);

            for (int dx = -3; dx <= 3; dx++) {
                for (int dz = -3; dz <= 3; dz++) {
                    BlockPos pos = ORIGIN.add(dx, 0, dz);
                    double forward = direction.forwardProgress(ORIGIN, pos);
                    double lateral = direction.lateralOffset(ORIGIN, pos);

                    double rebuiltX = forward * direction.directionVec.getX() + lateral * lateralBasis.directionVec.getX();
                    double rebuiltZ = forward * direction.directionVec.getZ() + lateral * lateralBasis.directionVec.getZ();

                    assertEquals(dx, rebuiltX, 1.0E-9, direction + " x at (" + dx + "," + dz + ")");
                    assertEquals(dz, rebuiltZ, 1.0E-9, direction + " z at (" + dx + "," + dz + ")");
                }
            }
        }
    }

    @Test
    void offsetWalksTheDirectionVectorFromAPosition() {
        BlockPos start = new BlockPos(1, 2, 3);

        assertEquals(new BlockPos(1, 2, -1), HWDirection.NORTH.offset(start, 4));
        assertEquals(new BlockPos(1, 2, 7), HWDirection.NORTH.offset(start, -4));
        assertEquals(new BlockPos(4, 2, 0), HWDirection.NORTH_EAST.offset(start, 3));
        assertEquals(start, HWDirection.SOUTH_WEST.offset(start, 0));
    }

    @Test
    void multiplyScalesTheGivenVectorAndIgnoresTheReceiver() {
        Vec3i vec = new Vec3i(1, 2, 3);

        assertEquals(new BlockPos(3, 6, 9), HWDirection.NORTH.multiply(vec, 3));
        assertEquals(HWDirection.NORTH.multiply(vec, 3), HWDirection.WEST.multiply(vec, 3));
        assertEquals(new BlockPos(0, 0, 0), HWDirection.EAST.multiply(vec, 0));
    }

    @Test
    void displayNameCapitalisesAndHyphenates() {
        assertEquals("North", HWDirection.NORTH.getDisplayName());
        assertEquals("South", HWDirection.SOUTH.getDisplayName());
        assertEquals("North-east", HWDirection.NORTH_EAST.getDisplayName());
        assertEquals("South-west", HWDirection.SOUTH_WEST.getDisplayName());

        for (HWDirection direction : HWDirection.values()) {
            String name = direction.getDisplayName();
            assertFalse(name.contains("_"), direction + " must not leak underscores");
            assertTrue(Character.isUpperCase(name.charAt(0)), direction + " must be capitalised");
        }
    }
}
