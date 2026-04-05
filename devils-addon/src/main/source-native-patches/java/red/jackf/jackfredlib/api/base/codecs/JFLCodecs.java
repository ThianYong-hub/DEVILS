package red.jackf.jackfredlib.api.base.codecs;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

public final class JFLCodecs {
   private JFLCodecs() {
   }

   @SafeVarargs
   public static <T> Codec<T> firstInList(Codec<T>... codecs) {
      return new FirstInListCodec<>(codecs);
   }

   public static <E extends Enum<E>> Codec<E> forEnum(Class<E> enumClass) {
      return Codec.STRING.comapFlatMap(str -> {
         try {
            return DataResult.success(Enum.valueOf(enumClass, str));
         } catch (IllegalArgumentException ignored) {
            return DataResult.error(() -> "Unknown enum constant for " + enumClass.getSimpleName() + ": " + str);
         }
      }, Enum::name);
   }

   public static <T> Codec<T> filtering(Codec<T> codec, Predicate<T> predicate) {
      return codec.comapFlatMap(
         value -> predicate.test(value) ? DataResult.success(value) : DataResult.error(() -> "Disallowed value: " + value),
         Function.identity()
      );
   }

   public static <T> Codec<T> oneOf(Codec<T> codec, Collection<T> allowedValues) {
      Set<T> copy = Set.copyOf(allowedValues);
      return filtering(codec, copy::contains);
   }

   public static <T> Codec<List<T>> mutableList(Codec<List<T>> codec) {
      return codec.xmap(ArrayList::new, Function.identity());
   }

   public static <T> Codec<Set<T>> mutableSet(Codec<Set<T>> codec) {
      return codec.xmap(HashSet::new, Function.identity());
   }

   public static <T, U> Codec<Map<T, U>> mutableMap(Codec<Map<T, U>> codec) {
      return codec.xmap(HashMap::new, Function.identity());
   }
}
