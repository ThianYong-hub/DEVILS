package red.jackf.jackfredlib.client.api.gps;

import com.mojang.datafixers.util.Pair;
import java.util.List;
import java.util.Optional;
import net.minecraft.text.Text;

public interface ScoreboardSnapshot {
   static Optional<ScoreboardSnapshot> take() {
      return Optional.empty();
   }

   Text title();

   List<Pair<Text, Text>> entries();

   default Optional<Pair<Text, Text>> entryFromBottom(int rowsFromBottom) {
      int index = this.entries().size() - 1 - rowsFromBottom;
      return index >= 0 && index < this.entries().size() ? Optional.of(this.entries().get(index)) : Optional.empty();
   }
}
