package com.example.addon.modules;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.entity.player.PlayerMoveEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.mixininterface.IVec3d;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.BlockState;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.shape.VoxelShape;

public class PhaseLimiter extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Double> maxPenetration = sgGeneral.add(new DoubleSetting.Builder()
        .name("max-penetration")
        .description("Maximum penetration into a block in blocks.")
        .defaultValue(0.20)
        .min(0.01)
        .max(0.49)
        .sliderRange(0.05, 0.30)
        .build()
    );

    private final Setting<Boolean> cancelMovePackets = sgGeneral.add(new BoolSetting.Builder()
        .name("cancel-move-packets")
        .description("Cancels movement packets while inside block and movement keys are pressed.")
        .defaultValue(true)
        .build()
    );

    private static final double PLAYER_HEIGHT = 1.8;
    private static final double EPSILON = 1e-6;

    public PhaseLimiter() {
        super(AddonTemplate.CATEGORY, "phase-limiter", "Limits how far the player can clip into blocks during pearl phase to avoid server kicks.");
    }

    @EventHandler
    private void onPlayerMove(PlayerMoveEvent event) {
        if (mc.player == null || mc.world == null) return;

        double curX = mc.player.getX();
        double curY = mc.player.getY();
        double curZ = mc.player.getZ();

        double newX = curX + event.movement.x;
        double newY = curY + event.movement.y;
        double newZ = curZ + event.movement.z;

        if (!isPlayerInsideBlock(newX, newY, newZ)) return;

        double clampedX = clampPenetration(newX, newY, newZ, curX, 0);
        double clampedZ = clampPenetration(clampedX, newY, newZ, curZ, 2);
        boolean exceededLimit = Math.abs(clampedX - newX) > EPSILON || Math.abs(clampedZ - newZ) > EPSILON;

        double moveX = clampedX - curX;
        double moveZ = clampedZ - curZ;

        if (exceededLimit) {
            moveX = 0.0;
            moveZ = 0.0;
        }

        // Vertical movement is always blocked while phasing in a block.
        ((IVec3d) event.movement).meteor$set(moveX, 0.0, moveZ);
        mc.player.setVelocity(0.0, 0.0, 0.0);
    }

    @EventHandler
    private void onPacketSend(PacketEvent.Send event) {
        if (!cancelMovePackets.get() || mc.player == null || mc.world == null) return;
        if (!(event.packet instanceof PlayerMoveC2SPacket)) return;
        if (!movementKeysPressed()) return;

        if (isPlayerInsideBlock(mc.player.getX(), mc.player.getY(), mc.player.getZ())) {
            event.setCancelled(true);
        }
    }

    private boolean isPlayerInsideBlock(double x, double y, double z) {
        int bx = MathHelper.floor(x);
        int bz = MathHelper.floor(z);
        int yMin = MathHelper.floor(y);
        int yMax = MathHelper.floor(y + PLAYER_HEIGHT);

        for (int by = yMin; by <= yMax; by++) {
            BlockPos pos = new BlockPos(bx, by, bz);
            if (isSolidAt(pos)) return true;
        }
        return false;
    }

    private double clampPenetration(double newX, double newY, double newZ, double curAxisVal, int axis) {
        double max = maxPenetration.get();
        double axisVal = (axis == 0) ? newX : newZ;

        int yMin = MathHelper.floor(newY);
        int yMax = MathHelper.floor(newY + PLAYER_HEIGHT);

        for (int by = yMin; by <= yMax; by++) {
            int blockCoord = MathHelper.floor(axisVal);

            BlockPos checkPos = (axis == 0)
                ? new BlockPos(blockCoord, by, MathHelper.floor(newZ))
                : new BlockPos(MathHelper.floor(newX), by, blockCoord);

            if (!isSolidAt(checkPos)) continue;

            double blockMin = blockCoord;
            double blockMax = blockCoord + 1.0;

            if (curAxisVal < blockMin + 0.5) {
                double penetration = axisVal - blockMin;
                if (penetration > max) {
                    axisVal = blockMin + max;
                }
            } else {
                double penetration = blockMax - axisVal;
                if (penetration > max) {
                    axisVal = blockMax - max;
                }
            }
        }

        return axisVal;
    }

    private boolean isSolidAt(BlockPos pos) {
        BlockState state = mc.world.getBlockState(pos);
        if (state.isAir()) return false;
        VoxelShape shape = state.getCollisionShape(mc.world, pos);
        return !shape.isEmpty();
    }

    private boolean movementKeysPressed() {
        return mc.options.forwardKey.isPressed()
            || mc.options.backKey.isPressed()
            || mc.options.leftKey.isPressed()
            || mc.options.rightKey.isPressed()
            || mc.options.jumpKey.isPressed()
            || mc.options.sneakKey.isPressed();
    }

    @Override
    public String getInfoString() {
        return String.format("%.0f%%", maxPenetration.get() * 100);
    }
}
