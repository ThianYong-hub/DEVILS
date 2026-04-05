package red.jackf.jackfredlib.api.base;

import java.util.Optional;

public final class ResultHolder<T> {
   private static final ResultHolder<?> EMPTY = new ResultHolder<>(State.EMPTY, null);
   private static final ResultHolder<?> PASS = new ResultHolder<>(State.PASS, null);
   private final State state;
   private final T value;

   private ResultHolder(State state, T value) {
      this.state = state;
      this.value = value;
   }

   public static <T> ResultHolder<T> value(T value) {
      if (value == null) {
         throw new IllegalArgumentException("Tried to create a VALUE result with a null value");
      }

      return new ResultHolder<>(State.VALUE, value);
   }

   @SuppressWarnings("unchecked")
   public static <T> ResultHolder<T> empty() {
      return (ResultHolder<T>)EMPTY;
   }

   @SuppressWarnings("unchecked")
   public static <T> ResultHolder<T> pass() {
      return (ResultHolder<T>)PASS;
   }

   public boolean hasValue() {
      return this.state == State.VALUE;
   }

   public boolean shouldTerminate() {
      return this.state != State.PASS;
   }

   public T get() {
      if (!this.hasValue()) {
         throw new IllegalArgumentException("Tried to get value from a non-VALUE result");
      }

      return this.value;
   }

   public Optional<T> asOptional() {
      return Optional.ofNullable(this.getNullable());
   }

   public T getNullable() {
      return switch (this.state) {
         case VALUE -> this.value;
         case EMPTY -> null;
         case PASS -> throw new IllegalArgumentException("Tried to get an output from a PASS result");
      };
   }

   private enum State {
      VALUE,
      EMPTY,
      PASS
   }
}
