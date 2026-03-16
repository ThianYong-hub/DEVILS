package com.example.addon.modules.highwaybuilder;

import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.Vec3d;

final class ContainerPlacementRecovery {
    private static final MinecraftClient mc = MinecraftClient.getInstance();
    private static final int CONTAINER_STUCK_RELOCATE_THRESHOLD = 3;
    private static final int CONTAINER_STUCK_REPATH_THRESHOLD = 2;
    private static final double CONTAINER_NUDGE_MIN_SPEED = 0.22;
    private static final double CONTAINER_NUDGE_MAX_SPEED = 0.42;
    private static final double CONTAINER_ESCAPE_DISTANCE = 1.30;

    private ContainerPlacementRecovery() {}

    static void nudgeAwayFromPlacementBlock(HighwayBuilder module, BlockTask blockTask, boolean containerPlacement) {
        if (mc.player == null) return;
        Vec3d playerPos = com.example.addon.util.EntityPositionCompat.pos(mc.player);

        if (containerPlacement && module.containerHandler != null) {
            module.containerHandler.invalidateRestockStandTarget();
            Vec3d standTarget = module.containerHandler.getRestockStandPos();

            Vec3d containerCenter = Vec3d.ofCenter(blockTask.blockPos);
            double rx = playerPos.x - containerCenter.x;
            double rz = playerPos.z - containerCenter.z;
            if (rx * rx + rz * rz < 1.0e-4 && module.pathfinder != null) {
                rx = -module.pathfinder.startingDirection.directionVec.getX();
                rz = -module.pathfinder.startingDirection.directionVec.getZ();
            }
            if (rx * rx + rz * rz < 1.0e-4) {
                rx = 1.0;
                rz = 0.0;
            }
            double rLen = Math.sqrt(rx * rx + rz * rz);
            Vec3d escapeTarget = new Vec3d(
                containerCenter.x + (rx / rLen) * CONTAINER_ESCAPE_DISTANCE,
                playerPos.y,
                containerCenter.z + (rz / rLen) * CONTAINER_ESCAPE_DISTANCE
            );

            double standDistSq = horizontalDistanceSq(standTarget, containerCenter);
            double escapeDistSq = horizontalDistanceSq(escapeTarget, containerCenter);
            Vec3d chosen = standDistSq >= escapeDistSq ? standTarget : escapeTarget;

            double mx = chosen.x - playerPos.x;
            double mz = chosen.z - playerPos.z;
            Vec3d selfCenter = Vec3d.ofCenter(mc.player.getBlockPos());
            mx += (selfCenter.x - playerPos.x) * 0.90;
            mz += (selfCenter.z - playerPos.z) * 0.90;
            double mLenSq = mx * mx + mz * mz;
            if (mLenSq < 0.04) {
                mx = rx;
                mz = rz;
                mLenSq = mx * mx + mz * mz;
            }
            if (mLenSq > 1.0e-4) {
                double mLen = Math.sqrt(mLenSq);
                double speed = Math.max(
                    CONTAINER_NUDGE_MIN_SPEED,
                    Math.min(CONTAINER_NUDGE_MAX_SPEED, module.moveSpeed.get() + 0.10)
                );
                mc.player.setVelocity((mx / mLen) * speed, mc.player.getVelocity().y, (mz / mLen) * speed);
                module.pathfinder.moveState = MovementState.RESTOCK;
                return;
            }
        }

        Vec3d targetCenter = Vec3d.ofCenter(blockTask.blockPos);
        double dx = playerPos.x - targetCenter.x;
        double dz = playerPos.z - targetCenter.z;

        if (dx * dx + dz * dz < 1.0e-4 && module.pathfinder != null) {
            dx = -module.pathfinder.startingDirection.directionVec.getX();
            dz = -module.pathfinder.startingDirection.directionVec.getZ();
        }
        if (dx * dx + dz * dz < 1.0e-4) {
            dx = 1.0;
            dz = 0.0;
        }

        double len = Math.sqrt(dx * dx + dz * dz);
        dx /= len;
        dz /= len;

        Vec3d selfCenter = Vec3d.ofCenter(mc.player.getBlockPos());
        dx += (selfCenter.x - playerPos.x) * 0.85;
        dz += (selfCenter.z - playerPos.z) * 0.85;
        double correctedLen = Math.sqrt(dx * dx + dz * dz);
        if (correctedLen > 1.0e-4) {
            dx /= correctedLen;
            dz /= correctedLen;
        }

        double speed = Math.max(0.16, Math.min(0.34, module.moveSpeed.get() + 0.08));
        mc.player.setVelocity(dx * speed, mc.player.getVelocity().y, dz * speed);

        if (containerPlacement && module.containerHandler != null) {
            module.pathfinder.moveState = MovementState.RESTOCK;
        }
    }

    static boolean tryRecoverContainerPlacementStuck(HighwayBuilder module, BlockTask containerTask) {
        if (containerTask == null || module.containerHandler == null) return false;

        if (containerTask.getStuckTicks() >= CONTAINER_STUCK_REPATH_THRESHOLD) {
            module.containerHandler.invalidateRestockStandTarget();
            module.pathfinder.moveState = MovementState.RESTOCK;
        }

        if (containerTask.getStuckTicks() >= CONTAINER_STUCK_RELOCATE_THRESHOLD
            && module.containerHandler.tryRelocateContainerPlacement()) {
            containerTask.resetStuck();
            return true;
        }

        return false;
    }

    private static double horizontalDistanceSq(Vec3d a, Vec3d b) {
        double dx = a.x - b.x;
        double dz = a.z - b.z;
        return dx * dx + dz * dz;
    }
}


