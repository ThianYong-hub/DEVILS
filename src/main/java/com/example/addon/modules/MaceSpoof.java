package com.example.addon.modules;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.mixininterface.IPlayerInteractEntityC2SPacket;
import meteordevelopment.meteorclient.mixininterface.IPlayerMoveC2SPacket;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Blocks;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.PlayerInteractEntityC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

public class MaceSpoof extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> maxPower = sgGeneral.add(new BoolSetting.Builder()
        .name("max-power")
        .description("Automatically find the highest safe air gap above the player (up to 170 blocks).")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> fallHeight = sgGeneral.add(new IntSetting.Builder()
        .name("fall-height")
        .description("Simulated fall height in blocks. Values above 22 only work on Paper/Spigot servers.")
        .defaultValue(22)
        .min(1)
        .max(170)
        .sliderRange(1, 170)
        .visible(() -> !maxPower.get())
        .build()
    );

    private final Setting<Boolean> preventFallDamage = sgGeneral.add(new BoolSetting.Builder()
        .name("prevent-fall-damage")
        .description("Reset fall distance after spoofing to avoid self-damage.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> disableWhenBlocked = sgGeneral.add(new BoolSetting.Builder()
        .name("disable-when-blocked")
        .description("Do not send spoof packets if the target is blocking, invulnerable, or in creative.")
        .defaultValue(true)
        .build()
    );

    private Vec3d previousPos;

    public MaceSpoof() {
        super(AddonTemplate.CATEGORY, "mace-spoof", "Spoofs fall distance to amplify mace damage.");
    }

    @EventHandler
    private void onSendPacket(PacketEvent.Send event) {
        if (mc.player == null || mc.world == null) return;
        if (mc.player.getMainHandStack().getItem() != Items.MACE) return;
        if (!(event.packet instanceof IPlayerInteractEntityC2SPacket packet)) return;
        if (packet.meteor$getType() != PlayerInteractEntityC2SPacket.InteractType.ATTACK) return;

        if (!(packet.meteor$getEntity() instanceof LivingEntity target)) return;
        if (disableWhenBlocked.get() && (target.isBlocking() || target.isInvulnerable() || target.isInCreativeMode()))
            return;

        previousPos = mc.player.getPos();
        int blocks = getMaxHeightAbovePlayer();
        if (blocks <= 0) return;

        BlockPos checkPos1 = mc.player.getBlockPos().add(0, blocks, 0);
        BlockPos checkPos2 = mc.player.getBlockPos().add(0, blocks + 1, 0);
        if (!isSafeBlock(checkPos1) || !isSafeBlock(checkPos2)) return;

        int packetsRequired = (int) Math.ceil(Math.abs(blocks / 10.0));
        if (packetsRequired > 20) packetsRequired = 1;

        boolean hc = mc.player.horizontalCollision;

        if (blocks <= 22) {
            for (int i = 0; i < 4; i++) {
                mc.player.networkHandler.sendPacket(new PlayerMoveC2SPacket.OnGroundOnly(false, hc));
            }
            double heightY = Math.min(mc.player.getY() + 22, mc.player.getY() + blocks);
            doTeleports(heightY, hc);
        } else {
            for (int i = 0; i < packetsRequired - 1; i++) {
                mc.player.networkHandler.sendPacket(new PlayerMoveC2SPacket.OnGroundOnly(false, hc));
            }
            double heightY = mc.player.getY() + blocks;
            doTeleports(heightY, hc);
        }
    }

    private void doTeleports(double height, boolean hc) {
        PlayerMoveC2SPacket moveUp = new PlayerMoveC2SPacket.PositionAndOnGround(
            mc.player.getX(), height, mc.player.getZ(), false, hc
        );

        PlayerMoveC2SPacket moveBack;
        if (preventFallDamage.get()) {
            moveBack = new PlayerMoveC2SPacket.PositionAndOnGround(
                previousPos.getX(), previousPos.getY() + 0.25, previousPos.getZ(), false, hc
            );
        } else {
            moveBack = new PlayerMoveC2SPacket.PositionAndOnGround(
                previousPos.getX(), previousPos.getY(), previousPos.getZ(), false, hc
            );
        }

        ((IPlayerMoveC2SPacket) moveUp).meteor$setTag(1337);
        ((IPlayerMoveC2SPacket) moveBack).meteor$setTag(1337);

        mc.player.networkHandler.sendPacket(moveUp);
        mc.player.networkHandler.sendPacket(moveBack);

        if (preventFallDamage.get()) {
            mc.player.setVelocity(mc.player.getVelocity().x, 0.1, mc.player.getVelocity().z);
            mc.player.fallDistance = 0;
        }
    }

    private int getMaxHeightAbovePlayer() {
        BlockPos playerPos = mc.player.getBlockPos();
        int maxHeight = playerPos.getY() + (maxPower.get() ? 170 : fallHeight.get());

        for (int i = maxHeight; i > playerPos.getY(); i--) {
            BlockPos up1 = new BlockPos(playerPos.getX(), i, playerPos.getZ());
            BlockPos up2 = up1.up();
            if (isSafeBlock(up1) && isSafeBlock(up2)) return i - playerPos.getY();
        }

        return 0;
    }

    private boolean isSafeBlock(BlockPos pos) {
        return mc.world.getBlockState(pos).isReplaceable()
            && mc.world.getFluidState(pos).isEmpty()
            && !mc.world.getBlockState(pos).isOf(Blocks.POWDER_SNOW);
    }
}
