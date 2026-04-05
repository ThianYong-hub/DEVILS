package red.jackf.jackfredlib.client.api.gps;

import java.util.Locale;
import java.util.Optional;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.WorldSavePath;

public interface Coordinate {
   static Optional<Coordinate> getCurrent() {
      MinecraftClient mc = MinecraftClient.getInstance();
      if (mc == null || mc.world == null || mc.player == null) {
         return Optional.empty();
      }

      if (mc.isInSingleplayer()) {
         String worldName = mc.getServer() != null
            ? mc.getServer().getSavePath(WorldSavePath.ROOT).getParent().getFileName().toString()
            : "singleplayer";
         String dimensionId = mc.world.getRegistryKey().getValue().toString();
         return Optional.of(new Singleplayer(worldName, dimensionId));
      }

      if (mc.getCurrentServerEntry() != null && mc.getCurrentServerEntry().address != null) {
         String address = mc.getCurrentServerEntry().address.trim();
         if (!address.isEmpty()) {
            return Optional.of(new Multiplayer(address));
         }
      }

      return Optional.empty();
   }

   String id();

   String userFriendlyName();

   record Singleplayer(String worldName, String dimensionId) implements Coordinate {
      @Override
      public String id() {
         return sanitize(this.worldName + "-" + this.dimensionId);
      }

      @Override
      public String userFriendlyName() {
         return this.worldName;
      }
   }

   record Multiplayer(String address) implements Coordinate {
      @Override
      public String id() {
         return sanitize(this.address);
      }

      @Override
      public String userFriendlyName() {
         return this.address;
      }
   }

   private static String sanitize(String raw) {
      return raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]+", "_");
   }
}
