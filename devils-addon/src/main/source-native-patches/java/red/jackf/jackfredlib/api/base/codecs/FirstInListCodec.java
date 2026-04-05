package red.jackf.jackfredlib.api.base.codecs;

import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import java.util.Arrays;
import java.util.Objects;

final class FirstInListCodec<T> implements Codec<T> {
   private static final DataResult<?> NO_CODECS = DataResult.error(() -> "No codecs");
   private final Codec<T>[] codecs;

   @SafeVarargs
   FirstInListCodec(Codec<T>... codecs) {
      this.codecs = codecs;
   }

   @SuppressWarnings("unchecked")
   private static <X> DataResult<X> noCodecsError() {
      return (DataResult<X>)NO_CODECS;
   }

   @Override
   public <A> DataResult<Pair<T, A>> decode(DynamicOps<A> ops, A input) {
      DataResult<Pair<T, A>> data = noCodecsError();

      for (Codec<T> codec : this.codecs) {
         data = codec.decode(ops, input);
         if (data.result().isPresent()) {
            return data;
         }
      }

      return data;
   }

   @Override
   public <A> DataResult<A> encode(T input, DynamicOps<A> ops, A prefix) {
      DataResult<A> data = noCodecsError();

      for (Codec<T> codec : this.codecs) {
         data = codec.encode(input, ops, prefix);
         if (data.result().isPresent()) {
            return data;
         }
      }

      return data;
   }

   @Override
   public boolean equals(Object o) {
      if (this == o) {
         return true;
      }

      if (o == null || this.getClass() != o.getClass()) {
         return false;
      }

      FirstInListCodec<?> that = (FirstInListCodec<?>)o;
      return Arrays.equals(this.codecs, that.codecs);
   }

   @Override
   public int hashCode() {
      return Objects.hash((Object)this.codecs);
   }

   @Override
   public String toString() {
      return "FirstInListCodec" + Arrays.toString(this.codecs);
   }
}
