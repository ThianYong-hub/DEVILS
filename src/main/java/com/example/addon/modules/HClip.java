package com.example.addon.modules;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.packet.s2c.play.BlockBreakingProgressS2CPacket;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

public class HClip extends Module {
    private static final double OFFSET = 0.20000000009497754;

    public HClip() {
        super(AddonTemplate.CATEGORY, "h-clip", "Automatically clips to block corners to prevent crystal placement.");
    }

    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        if (mc.player == null || mc.world == null) return;
        if (!(event.packet instanceof BlockBreakingProgressS2CPacket packet)) return;

        // Only react to break start
        if (packet.getProgress() != 0) return;

        BlockPos pos = packet.getPos();
        BlockPos playerBlock = mc.player.getBlockPos();
        int dx = pos.getX() - playerBlock.getX();
        int dy = pos.getY() - playerBlock.getY();
        int dz = pos.getZ() - playerBlock.getZ();

        // Check it's a surround block (cardinal, foot level)
        if (dy != 0) return;
        if (!((dx == 0 && Math.abs(dz) == 1) || (dz == 0 && Math.abs(dx) == 1))) return;

        // Check miner is enemy
        Entity entity = mc.world.getEntityById(packet.getEntityId());
        if (!(entity instanceof PlayerEntity miner)) return;
        if (miner == mc.player) return;
        if (Friends.get().isFriend(miner)) return;

        // Clip once: shift to 45-degree corner toward the mined block
        Vec3d center = playerBlock.toCenterPos();

        double shiftX, shiftZ;
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
