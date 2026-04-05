package red.jackf.jackfredlib.api.colour;

import java.awt.Color;
import net.minecraft.util.math.MathHelper;

public interface Colour extends Gradient {
   static Colour fromInt(int argb) {
      return new SimpleColour(argb);
   }

   static Colour fromHSV(float hue, float saturation, float value) {
      return fromInt(0xFF000000 | Color.HSBtoRGB(hue, saturation, value) & 0x00FFFFFF);
   }

   int toARGB();

   default int a() {
      return this.toARGB() >>> 24 & 255;
   }

   default int r() {
      return this.toARGB() >>> 16 & 255;
   }

   default int g() {
      return this.toARGB() >>> 8 & 255;
   }

   default int b() {
      return this.toARGB() & 255;
   }

   default Colour interpolate(Colour other, float delta) {
      float clamped = MathHelper.clamp(delta, 0.0F, 1.0F);
      int a = Math.round(MathHelper.lerp(clamped, this.a(), other.a()));
      int r = Math.round(MathHelper.lerp(clamped, this.r(), other.r()));
      int g = Math.round(MathHelper.lerp(clamped, this.g(), other.g()));
      int b = Math.round(MathHelper.lerp(clamped, this.b(), other.b()));
      return fromInt(a << 24 | r << 16 | g << 8 | b);
   }

   @Override
   default Colour sample(float factor) {
      return this;
   }

   record SimpleColour(int toARGB) implements Colour {
   }
}
