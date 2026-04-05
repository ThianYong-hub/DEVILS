package red.jackf.jackfredlib.api.base;

import java.util.function.Supplier;

public final class Memoizer<T> implements Supplier<T> {
   private final Supplier<T> factory;
   private boolean initialized;
   private T value;

   private Memoizer(Supplier<T> factory) {
      this.factory = factory;
   }

   public static <T> Memoizer<T> of(Supplier<T> factory) {
      return new Memoizer<>(factory);
   }

   @Override
   public T get() {
      if (!this.initialized) {
         this.value = this.factory.get();
         this.initialized = true;
      }

      return this.value;
   }
}
