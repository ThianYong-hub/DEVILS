package com.example.addon.modules;

import com.example.addon.AddonTemplate;
import com.example.addon.util.CrashGuard;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.packet.s2c.play.BlockBreakingProgressS2CPacket;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

public final class ClipModules {
    private ClipModules() {
    }

    public static class HClip extends Module {
        private static final double OFFSET = 0.20000000009497754;

        public HClip() {
            super(AddonTemplate.CATEGORY, "h-clip", "Shifts into block corners when surround is mined to block crystal placement.");
        }

        @EventHandler
        private void onPacketReceive(PacketEvent.Receive event) {
            CrashGuard.run(this, "onPacketReceive", () -> onPacketReceiveSafe(event));
        }

        private void onPacketReceiveSafe(PacketEvent.Receive event) {
            if (mc.player == null || mc.world == null) return;
            if (!(event.packet instanceof BlockBreakingProgressS2CPacket packet)) return;
            if (packet.getProgress() != 0) return;

            BlockPos pos = packet.getPos();
            BlockPos playerBlock = mc.player.getBlockPos();
            int dx = pos.getX() - playerBlock.getX();
            int dy = pos.getY() - playerBlock.getY();
            int dz = pos.getZ() - playerBlock.getZ();

            if (dy != 0) return;
            if (!((dx == 0 && Math.abs(dz) == 1) || (dz == 0 && Math.abs(dx) == 1))) return;

            Entity entity = mc.world.getEntityById(packet.getEntityId());
            if (!(entity instanceof PlayerEntity miner)) return;
            if (miner == mc.player) return;
            if (Friends.get().isFriend(miner)) return;

            Vec3d center = playerBlock.toCenterPos();
            double shiftX;
            double shiftZ;
            if (dx != 0) {
                shiftX = OFFSET * dx;
                shiftZ = OFFSET * ((mc.player.getZ() - center.z) >= 0 ? 1 : -1);
            } else {
                shiftZ = OFFSET * dz;
                shiftX = OFFSET * ((mc.player.getX() - center.x) >= 0 ? 1 : -1);
            }

            mc.player.setPosition(center.x + shiftX, mc.player.getY(), center.z + shiftZ);
        }
    }

    public static class VClip extends Module {
        private final SettingGroup sgGeneral = settings.getDefaultGroup();
        private final Setting<Double> distance = sgGeneral.add(new DoubleSetting.Builder()
            .name("distance")
            .description("Vertical distance to clip. Negative = down.")
            .defaultValue(-5.0)
            .min(-100.0)
            .max(100.0)
            .sliderRange(-20.0, 20.0)
            .build()
        );

        public VClip() {
            super(AddonTemplate.CATEGORY, "v-clip", "Instantly clips you vertically by a configured distance.");
        }

        @Override
        public void onActivate() {
            if (mc.player == null) {
                toggle();
                return;
            }

            mc.player.setPosition(mc.player.getX(), mc.player.getY() + distance.get(), mc.player.getZ());
            toggle();
        }
    }
}

final class AntiWaspObstacleAvoidance {
    private static final net.minecraft.client.MinecraftClient mc = net.minecraft.client.MinecraftClient.getInstance();
    private final AntiWasp module;

    AntiWaspObstacleAvoidance(AntiWasp module) {
        this.module = module;
    }

    Vec3d apply(Vec3d desired) {
        if (!module.avoidObstacles.get()) return desired;
        if (mc.player == null || mc.world == null) return desired;

        Vec3d horizontal = new Vec3d(desired.x, 0.0, desired.z);
        double speed = horizontal.length();
        if (speed < 1.0E-4) return desired;

        Vec3d forward = normalizeOrZero(horizontal);
        double look = module.obstacleLookAhead.get();
        double forwardClear = getClearDistance(forward, look);
        if (forwardClear >= look * 0.90) return desired;

        module.avoidTicks = module.obstacleMemoryTicks.get();

        Vec3d left = new Vec3d(-forward.z, 0.0, forward.x);
        if (module.avoidStrafeDir == 0) {
            Vec3d leftProbe = normalizeOrZero(forward.multiply(0.60).add(left.multiply(0.75)));
            Vec3d rightProbe = normalizeOrZero(forward.multiply(0.60).add(left.multiply(-0.75)));
            double leftClear = getClearDistance(leftProbe, look * 0.95);
            double rightClear = getClearDistance(rightProbe, look * 0.95);
            module.avoidStrafeDir = leftClear >= rightClear ? 1 : -1;
        }

        double y = desired.y;
        Vec3d adjustedHorizontal = horizontal;
        double climbRise = module.obstacleClimbHeight.get();
        Vec3d climbDir = normalizeOrZero(new Vec3d(forward.x, climbRise / Math.max(look, 0.1), forward.z));
        double climbClear = getClearDistance(climbDir, look * 0.9);
        double overheadClear = getClearDistance(new Vec3d(0.0, 1.0, 0.0), Math.max(climbRise, 0.8));
        boolean canClimb = climbClear >= look * 0.7 && overheadClear >= climbRise * 0.7;

        if (canClimb) {
            y = Math.max(y, module.obstacleClimbSpeed.get());
            adjustedHorizontal = horizontal.multiply(0.9);
        } else {
            Vec3d side = left.multiply(module.avoidStrafeDir);
            Vec3d blended = normalizeOrZero(forward.multiply(0.65).add(side.multiply(module.obstacleSideBias.get())));
            adjustedHorizontal = blended.multiply(speed);
            y = Math.max(y, module.obstacleClimbSpeed.get() * 0.35);

            double sideClear = getClearDistance(blended, look * 0.8);
            if (sideClear < look * 0.30) {
                module.avoidStrafeDir = -module.avoidStrafeDir;
                Vec3d otherSide = left.multiply(module.avoidStrafeDir);
                Vec3d fallback = normalizeOrZero(forward.multiply(0.65).add(otherSide.multiply(module.obstacleSideBias.get())));
                adjustedHorizontal = fallback.multiply(speed);
            }
        }

        Vec3d adjustedDir = normalizeOrZero(new Vec3d(adjustedHorizontal.x, 0.0, adjustedHorizontal.z));
        if (adjustedDir.lengthSquared() > 1.0E-6) {
            double clear = getClearDistance(adjustedDir, look * 0.75);
            if (clear < look * 0.18) {
                adjustedHorizontal = adjustedHorizontal.multiply(0.35);
                y = Math.max(y, module.obstacleClimbSpeed.get());
            }
        }

        return new Vec3d(adjustedHorizontal.x, y, adjustedHorizontal.z);
    }

    Vec3d normalizeOrZero(Vec3d vec) {
        if (vec == null || vec.lengthSquared() < 1.0E-10) return Vec3d.ZERO;
        return vec.normalize();
    }

    private double getClearDistance(Vec3d direction, double maxDistance) {
        if (mc.player == null || mc.world == null) return maxDistance;
        if (maxDistance <= 0) return 0;

        Vec3d dir = normalizeOrZero(direction);
        if (dir.lengthSquared() < 1.0E-8) return maxDistance;

        double min = maxDistance;
        double[] heights = { 0.25, 0.95, 1.60 };
        Vec3d base = com.example.addon.util.EntityPositionCompat.pos(mc.player);

        for (double h : heights) {
            Vec3d start = base.add(0.0, h, 0.0);
            Vec3d end = start.add(dir.multiply(maxDistance));
            HitResult hit = mc.world.raycast(new RaycastContext(
                start,
                end,
                RaycastContext.ShapeType.COLLIDER,
                RaycastContext.FluidHandling.NONE,
                mc.player
            ));

            if (hit.getType() == HitResult.Type.BLOCK) {
                min = Math.min(min, start.distanceTo(hit.getPos()));
            }
        }

        return min;
    }
}

