package xaero.lib.mixin;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.network.packet.s2c.play.WorldBorderInitializeS2CPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.At.Shift;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import xaero.lib.XaeroLib;
import xaero.lib.client.level.ClientLevelData;

@Mixin(ClientPlayNetworkHandler.class)
public class MixinClientPacketListener {
    @Inject(
        at = @At(
            value = "INVOKE",
            shift = Shift.AFTER,
            target = "Lnet/minecraft/network/NetworkThreadUtils;forceMainThread(Lnet/minecraft/network/packet/Packet;Lnet/minecraft/network/listener/PacketListener;Lnet/minecraft/network/PacketApplyBatcher;)V"
        ),
        method = "onWorldBorderInitialize(Lnet/minecraft/network/packet/s2c/play/WorldBorderInitializeS2CPacket;)V"
    )
    public void onHandleInitializeBorder(WorldBorderInitializeS2CPacket packet, CallbackInfo info) {
        if (!XaeroLib.isLoaded()) return;

        MinecraftClient.getInstance().send(() -> {
            ClientWorld world = MinecraftClient.getInstance().world;
            if (world == null) return;

            ClientLevelData clientLevelData = ClientLevelData.get(world);
            if (!clientLevelData.serverHasMod()) {
                XaeroLib.LOGGER.warn("Server side doesn't have XaeroLib installed! Resetting.");
                XaeroLib.INSTANCE.getClient().getConfigSynchronizer().reset();
            }
        });
    }
}
