package red.jackf.jackfredlib.client.api.colour;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.math.MathHelper;
import red.jackf.jackfredlib.api.colour.Gradient;

public final class GradientUtils {
   private GradientUtils() {
   }

   public static void drawHorizontalGradient(DrawContext graphics, int x, int y, int width, int height, Gradient gradient, float gradientStart, float gradientEnd) {
      if (width <= 0 || height <= 0) {
         return;
      }

      for (int offset = 0; offset < width; offset++) {
         float factor = width == 1 ? gradientStart : MathHelper.lerp((float)offset / (width - 1), gradientStart, gradientEnd);
         graphics.fill(x + offset, y, x + offset + 1, y + height, gradient.sample(factor).toARGB());
      }
   }
}
