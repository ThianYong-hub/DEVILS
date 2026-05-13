package xaero.lib.common.packet;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.network.PacketByteBuf;
import xaero.lib.XaeroLib;
import xaero.lib.client.level.ClientLevelData;
import xaero.lib.platform.Services;

import java.util.function.Consumer;

public class ClientboundDimensionHandshakePacket {
    public static ClientboundDimensionHandshakePacket read(PacketByteBuf buf) {
        try {
            buf.readByte();
            return new ClientboundDimensionHandshakePacket();
        } catch (Throwable t) {
            if (Services.PLATFORM.isDevelopmentEnvironment() || !Services.PLATFORM.isDedicatedServer()) {
                XaeroLib.LOGGER.error("packet error", t);
            }

            return null;
        }
    }

    public void write(PacketByteBuf buf) {
        buf.writeByte(1);
    }

    public static final class ClientHandler implements Consumer<ClientboundDimensionHandshakePacket> {
        @Override
        public void accept(ClientboundDimensionHandshakePacket packet) {
            try {
                ClientWorld world = MinecraftClient.getInstance().world;
                if (world == null) return;

                ClientLevelData.get(world).setServerHasMod();
            } catch (Throwable t) {
                XaeroLib.LOGGER.error("packet error", t);
            }
        }
    }
}
