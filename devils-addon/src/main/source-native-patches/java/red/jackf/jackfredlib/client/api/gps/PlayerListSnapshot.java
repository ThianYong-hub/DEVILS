package red.jackf.jackfredlib.client.api.gps;

import java.util.List;
import java.util.Optional;
import net.minecraft.text.Text;

public interface PlayerListSnapshot {
   static PlayerListSnapshot take() {
      return Empty.INSTANCE;
   }

   Optional<Text> header();

   Optional<Text> footer();

   List<Text> names();

   default Optional<Text> nameWithPrefix(String prefix) {
      return this.names().stream().filter(name -> name.getString().startsWith(prefix)).findFirst();
   }

   default Optional<String> nameWithPrefixStripped(String prefix) {
      return this.nameWithPrefix(prefix).map(Text::getString).map(name -> name.substring(prefix.length()).trim());
   }

   enum Empty implements PlayerListSnapshot {
      INSTANCE;

      @Override
      public Optional<Text> header() {
         return Optional.empty();
      }

      @Override
      public Optional<Text> footer() {
         return Optional.empty();
      }

      @Override
      public List<Text> names() {
         return List.of();
      }
   }
}
