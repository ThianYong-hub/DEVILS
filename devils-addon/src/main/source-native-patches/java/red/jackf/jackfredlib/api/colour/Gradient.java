package red.jackf.jackfredlib.api.colour;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.minecraft.util.math.MathHelper;

public interface Gradient {
   static Gradient of(Colour... colours) {
      if (colours.length == 0) {
         throw new IllegalArgumentException("Gradient requires at least one colour");
      }

      if (colours.length == 1) {
         return colours[0];
      }

      return new SimpleGradient(List.copyOf(Arrays.asList(colours)));
   }

   Colour sample(float factor);

   default Gradient repeat(int times) {
      if (times <= 1) {
         return this;
      }

      List<Colour> repeated = new ArrayList<>();
      for (int i = 0; i < times; i++) {
         repeated.add(this.sample((float)i / times));
      }
      repeated.add(this.sample(1.0F));
      return new SimpleGradient(repeated);
   }

   default Gradient squish(float edgeFraction) {
      float clamped = MathHelper.clamp(edgeFraction, 0.0F, 0.49F);
      if (clamped == 0.0F) {
         return this;
      }

      return factor -> {
         float adjusted = MathHelper.clamp((factor - clamped) / (1.0F - clamped * 2.0F), 0.0F, 1.0F);
         return this.sample(adjusted);
      };
   }

   record SimpleGradient(List<Colour> colours) implements Gradient {
      @Override
      public Colour sample(float factor) {
         if (this.colours.size() == 1) {
            return this.colours.getFirst();
         }

         float clamped = MathHelper.clamp(factor, 0.0F, 1.0F);
         float scaled = clamped * (this.colours.size() - 1);
         int lowerIndex = MathHelper.floor(scaled);
         int upperIndex = Math.min(this.colours.size() - 1, lowerIndex + 1);
         float delta = scaled - lowerIndex;
         return this.colours.get(lowerIndex).interpolate(this.colours.get(upperIndex), delta);
      }
   }
}
