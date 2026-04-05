package red.jackf.jackfredlib.client.api.toasts;

public record ToastFormat(int titleColour, int messageColour, int progressBarColour) {
   public static final ToastFormat DARK = new ToastFormat(0xFFFFFFFF, 0xFFE0E0E0, 0xFF4A90E2);
   public static final ToastFormat WHITE = new ToastFormat(0xFF000000, 0xFF202020, 0xFF4A90E2);
}
