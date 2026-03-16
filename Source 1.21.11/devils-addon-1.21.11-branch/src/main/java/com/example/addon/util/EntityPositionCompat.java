package com.example.addon.util;

import net.minecraft.entity.Entity;
import net.minecraft.util.math.Vec3d;

public final class EntityPositionCompat {
    private EntityPositionCompat() {
    }

    public static Vec3d pos(Entity entity) {
        if (entity == null) return Vec3d.ZERO;
        return new Vec3d(entity.getX(), entity.getY(), entity.getZ());
    }
}