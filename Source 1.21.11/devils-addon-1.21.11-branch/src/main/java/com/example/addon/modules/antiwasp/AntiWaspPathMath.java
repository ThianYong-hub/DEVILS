package com.example.addon.modules.antiwasp;

import net.minecraft.util.math.Vec3d;

public final class AntiWaspPathMath {
    private AntiWaspPathMath() {}

    public static Vec3d getPathPoint(
        Vec3d origin,
        FlightFigure figure,
        double phase01Raw,
        double figureSize,
        double altitudeOffset
    ) {
        double p = wrap01(phase01Raw);
        Vec3d offset = getOffset(figure, p, figureSize);
        return new Vec3d(origin.x + offset.x, origin.y + altitudeOffset, origin.z + offset.z);
    }

    public static FlightFigure nextFigure(FlightFigure current) {
        FlightFigure[] values = FlightFigure.values();
        return values[(current.ordinal() + 1) % values.length];
    }

    public static double wrap01(double value) {
        double wrapped = value % 1.0;
        return wrapped < 0.0 ? wrapped + 1.0 : wrapped;
    }

    public static double axisSpeed(double dist, double maxSpeed) {
        double abs = Math.abs(dist);
        if (abs < 1.0E-5) return 0.0;
        return abs < maxSpeed ? dist : maxSpeed * Math.signum(dist);
    }

    public static Vec3d distributePlanarSpeed(double xDist, double zDist, double maxSpeed) {
        double xVel = axisSpeed(xDist, maxSpeed);
        double zVel = axisSpeed(zDist, maxSpeed);

        double absX = Math.abs(xDist);
        double absZ = Math.abs(zDist);
        if (absX > 1.0E-5 && absZ > 1.0E-5) {
            double diag = 1.0 / Math.sqrt(absX * absX + absZ * absZ);
            xVel *= absX * diag;
            zVel *= absZ * diag;
        }

        return new Vec3d(xVel, 0.0, zVel);
    }

    public static double horizontalDistanceSq(Vec3d a, Vec3d b) {
        double dx = a.x - b.x;
        double dz = a.z - b.z;
        return dx * dx + dz * dz;
    }

    public static double getPerimeter(FlightFigure type, double size) {
        return switch (type) {
            case Circle -> Math.PI * size;
            case Square -> size * 4.0;
            case Triangle -> size * 3.0;
        };
    }

    private static Vec3d getOffset(FlightFigure type, double p, double size) {
        return switch (type) {
            case Circle -> {
                double r = size * 0.5;
                double a = p * Math.PI * 2.0;
                yield new Vec3d(r * Math.cos(a), 0.0, r * Math.sin(a));
            }
            case Square -> {
                double h = size * 0.5;
                Vec3d[] v = new Vec3d[] {
                    new Vec3d(h, 0.0, h),
                    new Vec3d(-h, 0.0, h),
                    new Vec3d(-h, 0.0, -h),
                    new Vec3d(h, 0.0, -h)
                };
                yield pointOnPolygon(v, p);
            }
            case Triangle -> {
                double circumRadius = size / Math.sqrt(3.0);
                double a0 = Math.PI * 0.5;
                double a1 = a0 + Math.PI * 2.0 / 3.0;
                double a2 = a0 + Math.PI * 4.0 / 3.0;
                Vec3d[] v = new Vec3d[] {
                    new Vec3d(circumRadius * Math.cos(a0), 0.0, circumRadius * Math.sin(a0)),
                    new Vec3d(circumRadius * Math.cos(a1), 0.0, circumRadius * Math.sin(a1)),
                    new Vec3d(circumRadius * Math.cos(a2), 0.0, circumRadius * Math.sin(a2))
                };
                yield pointOnPolygon(v, p);
            }
        };
    }

    private static Vec3d pointOnPolygon(Vec3d[] vertices, double p) {
        int n = vertices.length;
        double segPos = p * n;
        int i = (int) Math.floor(segPos) % n;
        double t = segPos - Math.floor(segPos);
        Vec3d a = vertices[i];
        Vec3d b = vertices[(i + 1) % n];
        return new Vec3d(
            a.x + (b.x - a.x) * t,
            0.0,
            a.z + (b.z - a.z) * t
        );
    }

    public enum FlightFigure {
        Circle,
        Square,
        Triangle
    }
}


