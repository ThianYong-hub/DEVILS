package red.jackf.jackfredlib.client.api.toasts;

import net.minecraft.item.ItemStack;

public interface ToastIcon {
   static ToastIcon modIcon(String modId) {
      return new SimpleToastIcon(modId, ItemStack.EMPTY);
   }

   static ToastIcon item(ItemStack stack) {
      return new SimpleToastIcon(null, stack.copy());
   }

   record SimpleToastIcon(String modId, ItemStack stack) implements ToastIcon {
   }
}
