package com.devils.addon.modules.antiwasp;

import com.devils.addon.modules.antiwasp.AntiWaspPathMath.FlightFigure;
import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AntiWaspPathMathTest {
    private static final Vec3d ORIGIN = new Vec3d(10.0, 70.0, -4.0);
    private static final double SIZE = 8.0;
    private static final double ALTITUDE = 3.5;
    private static final double EPS = 1.0E-9;

    private static Vec3d point(FlightFigure figure, double phase) {
        return AntiWaspPathMath.getPathPoint(ORIGIN, figure, phase, SIZE, ALTITUDE);
    }

    private static double horizontalRadius(Vec3d point) {
        double dx = point.x - ORIGIN.x;
        double dz = point.z - ORIGIN.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    private static double sampledPathLength(FlightFigure figure, int samples) {
        double length = 0.0;
        Vec3d previous = point(figure, 0.0);
        for (int i = 1; i <= samples; i++) {
            Vec3d next = point(figure, (double) i / samples);
            length += next.distanceTo(previous);
            previous = next;
        }
        return length;
    }

    @Test
    void wrap01FoldsAnyPhaseIntoTheUnitInterval() {
        assertEquals(0.0, AntiWaspPathMath.wrap01(0.0), EPS);
        assertEquals(0.25, AntiWaspPathMath.wrap01(0.25), EPS);
        assertEquals(0.0, AntiWaspPathMath.wrap01(1.0), EPS);
        assertEquals(0.25, AntiWaspPathMath.wrap01(1.25), EPS);
        assertEquals(0.5, AntiWaspPathMath.wrap01(12.5), EPS);
        assertEquals(0.75, AntiWaspPathMath.wrap01(-0.25), EPS);
        assertEquals(0.75, AntiWaspPathMath.wrap01(-1.25), EPS);
        assertEquals(0.0, AntiWaspPathMath.wrap01(-1.0), EPS);

        for (int step = -300; step <= 300; step++) {
            double raw = step / 100.0;
            double wrapped = AntiWaspPathMath.wrap01(raw);
            assertTrue(wrapped >= 0.0 && wrapped < 1.0, "wrap01(" + raw + ") = " + wrapped);
        }
    }

    @Test
    void axisSpeedClampsToMaxAndSnapsTinyDistancesToZero() {
        assertEquals(0.0, AntiWaspPathMath.axisSpeed(0.0, 0.3), EPS);
        assertEquals(0.0, AntiWaspPathMath.axisSpeed(9.0E-6, 0.3), EPS);
        assertEquals(0.0, AntiWaspPathMath.axisSpeed(-9.0E-6, 0.3), EPS);

        // Inside the step budget we move exactly the remaining distance (no overshoot).
        assertEquals(0.2, AntiWaspPathMath.axisSpeed(0.2, 0.3), EPS);
        assertEquals(-0.2, AntiWaspPathMath.axisSpeed(-0.2, 0.3), EPS);

        assertEquals(0.3, AntiWaspPathMath.axisSpeed(5.0, 0.3), EPS);
        assertEquals(-0.3, AntiWaspPathMath.axisSpeed(-5.0, 0.3), EPS);
        assertEquals(0.3, AntiWaspPathMath.axisSpeed(0.3, 0.3), EPS);
    }

    @Test
    void diagonalTravelIsNormalisedToTheMaxSpeed() {
        Vec3d velocity = AntiWaspPathMath.distributePlanarSpeed(10.0, 10.0, 0.3);

        assertEquals(0.3, velocity.length(), 1.0E-9);
        assertEquals(velocity.x, velocity.z, EPS);
        assertTrue(velocity.x > 0.0);
        assertEquals(0.0, velocity.y, EPS);

        Vec3d mirrored = AntiWaspPathMath.distributePlanarSpeed(-10.0, 10.0, 0.3);
        assertEquals(-velocity.x, mirrored.x, EPS);
        assertEquals(velocity.z, mirrored.z, EPS);
        assertEquals(0.3, mirrored.length(), 1.0E-9);
    }

    @Test
    void singleAxisTravelKeepsTheFullAxisSpeed() {
        Vec3d eastward = AntiWaspPathMath.distributePlanarSpeed(10.0, 0.0, 0.3);
        assertEquals(0.3, eastward.x, EPS);
        assertEquals(0.0, eastward.y, EPS);
        assertEquals(0.0, eastward.z, EPS);

        Vec3d northward = AntiWaspPathMath.distributePlanarSpeed(0.0, -10.0, 0.3);
        assertEquals(0.0, northward.x, EPS);
        assertEquals(-0.3, northward.z, EPS);

        Vec3d parked = AntiWaspPathMath.distributePlanarSpeed(0.0, 0.0, 0.3);
        assertEquals(0.0, parked.length(), EPS);
    }

    @Test
    void planarSpeedNeverExceedsTheMaxOnAnySampledHeading() {
        for (int degrees = 0; degrees < 360; degrees += 5) {
            double radians = Math.toRadians(degrees);
            Vec3d velocity = AntiWaspPathMath.distributePlanarSpeed(
                Math.cos(radians) * 40.0,
                Math.sin(radians) * 40.0,
                0.3
            );

            assertTrue(velocity.length() <= 0.3 + 1.0E-9, "heading " + degrees + " -> " + velocity.length());
            assertEquals(0.0, velocity.y, EPS, "heading " + degrees + " must stay planar");
        }
    }

    @Test
    void horizontalDistanceSqIgnoresAltitude() {
        Vec3d a = new Vec3d(0.0, 0.0, 0.0);
        Vec3d b = new Vec3d(3.0, 100.0, 4.0);

        assertEquals(25.0, AntiWaspPathMath.horizontalDistanceSq(a, b), EPS);
        assertEquals(25.0, AntiWaspPathMath.horizontalDistanceSq(b, a), EPS);
        assertEquals(0.0, AntiWaspPathMath.horizontalDistanceSq(a, new Vec3d(0.0, -50.0, 0.0)), EPS);
    }

    @Test
    void nextFigureCyclesThroughEveryFigure() {
        assertEquals(FlightFigure.Square, AntiWaspPathMath.nextFigure(FlightFigure.Circle));
        assertEquals(FlightFigure.Triangle, AntiWaspPathMath.nextFigure(FlightFigure.Square));
        assertEquals(FlightFigure.Circle, AntiWaspPathMath.nextFigure(FlightFigure.Triangle));

        FlightFigure figure = FlightFigure.Circle;
        for (int i = 0; i < FlightFigure.values().length; i++) figure = AntiWaspPathMath.nextFigure(figure);
        assertEquals(FlightFigure.Circle, figure);
    }

    @Test
    void pathAltitudeIsFlatAndIndependentOfPhase() {
        for (FlightFigure figure : FlightFigure.values()) {
            for (double phase = -1.5; phase <= 2.5; phase += 0.05) {
                assertEquals(ORIGIN.y + ALTITUDE, point(figure, phase).y, EPS, figure + " at " + phase);
            }
        }
    }

    @Test
    void circleKeepsAConstantRadiusOfHalfTheFigureSize() {
        for (double phase = 0.0; phase < 1.0; phase += 0.01) {
            assertEquals(SIZE * 0.5, horizontalRadius(point(FlightFigure.Circle, phase)), 1.0E-9);
        }

        Vec3d start = point(FlightFigure.Circle, 0.0);
        Vec3d quarter = point(FlightFigure.Circle, 0.25);
        Vec3d half = point(FlightFigure.Circle, 0.5);
        Vec3d threeQuarters = point(FlightFigure.Circle, 0.75);

        assertEquals(ORIGIN.x + 4.0, start.x, EPS);
        assertEquals(ORIGIN.z, start.z, EPS);
        assertEquals(ORIGIN.x, quarter.x, EPS);
        assertEquals(ORIGIN.z + 4.0, quarter.z, EPS);
        assertEquals(ORIGIN.x - 4.0, half.x, EPS);
        assertEquals(ORIGIN.z, half.z, EPS);
        assertEquals(ORIGIN.x, threeQuarters.x, EPS);
        assertEquals(ORIGIN.z - 4.0, threeQuarters.z, EPS);
    }

    @Test
    void squareTracesAxisAlignedCornersOfSideLengthSize() {
        Vec3d c0 = point(FlightFigure.Square, 0.0);
        Vec3d c1 = point(FlightFigure.Square, 0.25);
        Vec3d c2 = point(FlightFigure.Square, 0.5);
        Vec3d c3 = point(FlightFigure.Square, 0.75);

        assertEquals(new Vec3d(ORIGIN.x + 4.0, ORIGIN.y + ALTITUDE, ORIGIN.z + 4.0), c0);
        assertEquals(new Vec3d(ORIGIN.x - 4.0, ORIGIN.y + ALTITUDE, ORIGIN.z + 4.0), c1);
        assertEquals(new Vec3d(ORIGIN.x - 4.0, ORIGIN.y + ALTITUDE, ORIGIN.z - 4.0), c2);
        assertEquals(new Vec3d(ORIGIN.x + 4.0, ORIGIN.y + ALTITUDE, ORIGIN.z - 4.0), c3);

        assertEquals(SIZE, c0.distanceTo(c1), 1.0E-9);
        assertEquals(SIZE, c1.distanceTo(c2), 1.0E-9);
        assertEquals(SIZE, c2.distanceTo(c3), 1.0E-9);
        assertEquals(SIZE, c3.distanceTo(c0), 1.0E-9);

        // Half-way along the first edge.
        Vec3d edgeMid = point(FlightFigure.Square, 0.125);
        assertEquals(ORIGIN.x, edgeMid.x, EPS);
        assertEquals(ORIGIN.z + 4.0, edgeMid.z, EPS);

        // Every sampled point sits on the square boundary (Chebyshev radius == half size).
        for (double phase = 0.0; phase < 1.0; phase += 0.005) {
            Vec3d p = point(FlightFigure.Square, phase);
            double chebyshev = Math.max(Math.abs(p.x - ORIGIN.x), Math.abs(p.z - ORIGIN.z));
            assertEquals(SIZE * 0.5, chebyshev, 1.0E-9, "phase " + phase);
        }
    }

    @Test
    void triangleTracesAnEquilateralLoopWithSideLengthSize() {
        double circumRadius = SIZE / Math.sqrt(3.0);

        Vec3d v0 = point(FlightFigure.Triangle, 0.0);
        Vec3d v1 = point(FlightFigure.Triangle, 1.0 / 3.0);
        Vec3d v2 = point(FlightFigure.Triangle, 2.0 / 3.0);

        assertEquals(ORIGIN.x, v0.x, 1.0E-9);
        assertEquals(ORIGIN.z + circumRadius, v0.z, 1.0E-9);
        assertEquals(ORIGIN.x - SIZE * 0.5, v1.x, 1.0E-9);
        assertEquals(ORIGIN.z - circumRadius * 0.5, v1.z, 1.0E-9);
        assertEquals(ORIGIN.x + SIZE * 0.5, v2.x, 1.0E-9);
        assertEquals(ORIGIN.z - circumRadius * 0.5, v2.z, 1.0E-9);

        assertEquals(SIZE, v0.distanceTo(v1), 1.0E-9);
        assertEquals(SIZE, v1.distanceTo(v2), 1.0E-9);
        assertEquals(SIZE, v2.distanceTo(v0), 1.0E-9);

        // Every sampled point lies between the inradius and the circumradius.
        for (double phase = 0.0; phase < 1.0; phase += 0.005) {
            double radius = horizontalRadius(point(FlightFigure.Triangle, phase));
            assertTrue(radius <= circumRadius + 1.0E-9, "phase " + phase + " radius " + radius);
            assertTrue(radius >= circumRadius * 0.5 - 1.0E-9, "phase " + phase + " radius " + radius);
        }
    }

    @Test
    void declaredPerimeterMatchesTheTracedPathLength() {
        assertEquals(Math.PI * SIZE, AntiWaspPathMath.getPerimeter(FlightFigure.Circle, SIZE), EPS);
        assertEquals(4.0 * SIZE, AntiWaspPathMath.getPerimeter(FlightFigure.Square, SIZE), EPS);
        assertEquals(3.0 * SIZE, AntiWaspPathMath.getPerimeter(FlightFigure.Triangle, SIZE), EPS);

        assertEquals(
            AntiWaspPathMath.getPerimeter(FlightFigure.Square, SIZE),
            sampledPathLength(FlightFigure.Square, 4000),
            1.0E-6
        );
        assertEquals(
            AntiWaspPathMath.getPerimeter(FlightFigure.Triangle, SIZE),
            sampledPathLength(FlightFigure.Triangle, 3000),
            1.0E-6
        );
        // Chord sampling slightly under-measures a circle, so allow a small tolerance.
        assertEquals(
            AntiWaspPathMath.getPerimeter(FlightFigure.Circle, SIZE),
            sampledPathLength(FlightFigure.Circle, 4000),
            1.0E-4
        );
    }

    @Test
    void everyFigureClosesAtFullPhaseAndRepeatsForever() {
        for (FlightFigure figure : FlightFigure.values()) {
            Vec3d start = point(figure, 0.0);
            assertEquals(start.x, point(figure, 1.0).x, EPS, figure + " closes on x");
            assertEquals(start.z, point(figure, 1.0).z, EPS, figure + " closes on z");

            for (double phase : new double[] {0.0, 0.13, 0.5, 0.87, 0.99}) {
                Vec3d base = point(figure, phase);
                for (double lap : new double[] {-3.0, -1.0, 1.0, 2.0, 7.0}) {
                    Vec3d shifted = point(figure, phase + lap);
                    assertEquals(base.x, shifted.x, 1.0E-9, figure + " phase " + phase + " lap " + lap);
                    assertEquals(base.z, shifted.z, 1.0E-9, figure + " phase " + phase + " lap " + lap);
                }
            }
        }
    }

    @Test
    void figureScalesLinearlyWithSize() {
        for (FlightFigure figure : FlightFigure.values()) {
            for (double phase = 0.0; phase < 1.0; phase += 0.05) {
                Vec3d small = AntiWaspPathMath.getPathPoint(Vec3d.ZERO, figure, phase, 4.0, 0.0);
                Vec3d large = AntiWaspPathMath.getPathPoint(Vec3d.ZERO, figure, phase, 12.0, 0.0);

                assertEquals(small.x * 3.0, large.x, 1.0E-9, figure + " x at " + phase);
                assertEquals(small.z * 3.0, large.z, 1.0E-9, figure + " z at " + phase);
            }
        }
    }
}
