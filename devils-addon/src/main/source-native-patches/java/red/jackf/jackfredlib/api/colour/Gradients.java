package red.jackf.jackfredlib.api.colour;

public final class Gradients {
   public static final Gradient RAINBOW = Gradient.of(
      Colours.RED, Colours.ORANGE, Colours.YELLOW, Colours.GREEN, Colours.LIGHT_BLUE, Colours.BLUE, Colours.PURPLE
   );
   public static final Gradient GAY = Gradient.of(
      Colour.fromInt(0xFF078D70), Colour.fromInt(0xFF26CEAA), Colour.fromInt(0xFF98E8C1), Colours.WHITE, Colour.fromInt(0xFF7BADE2), Colour.fromInt(0xFF5049CC)
   );
   public static final Gradient LESBIAN = Gradient.of(
      Colour.fromInt(0xFFD52D00), Colour.fromInt(0xFFEF7627), Colour.fromInt(0xFFFF9A56), Colours.WHITE, Colour.fromInt(0xFFD162A4), Colour.fromInt(0xFFB55690)
   );
   public static final Gradient BISEXUAL = Gradient.of(
      Colour.fromInt(0xFFD60270), Colour.fromInt(0xFFD60270), Colour.fromInt(0xFF9B4F96), Colour.fromInt(0xFF0038A8), Colour.fromInt(0xFF0038A8)
   );
   public static final Gradient PANSEXUAL = Gradient.of(
      Colour.fromInt(0xFFFF1B8D), Colour.fromInt(0xFFFFD900), Colour.fromInt(0xFF1BB3FF)
   );
   public static final Gradient INTERSEX_SMOOTH = Gradient.of(
      Colour.fromInt(0xFFFFD800), Colour.fromInt(0xFF7902AA), Colour.fromInt(0xFFFFD800)
   );
   public static final Gradient NONBINARY = Gradient.of(
      Colour.fromInt(0xFFFFF430), Colours.WHITE, Colour.fromInt(0xFF9C59D1), Colours.BLACK
   );
   public static final Gradient TRANS = Gradient.of(
      Colour.fromInt(0xFF5BCEFA), Colour.fromInt(0xFFF5A9B8), Colours.WHITE, Colour.fromInt(0xFFF5A9B8), Colour.fromInt(0xFF5BCEFA)
   );
   public static final Gradient ACE = Gradient.of(
      Colours.BLACK, Colour.fromInt(0xFFA3A3A3), Colours.WHITE, Colour.fromInt(0xFF800080)
   );
   public static final Gradient ARO = Gradient.of(
      Colour.fromInt(0xFF3DA542), Colour.fromInt(0xFFA8D47A), Colours.WHITE, Colour.fromInt(0xFFA9A9A9), Colours.BLACK
   );

   private Gradients() {
   }
}
