package red.jackf.jackfredlib.client.api.toasts;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import net.minecraft.text.Text;

public interface ToastBuilder {
   static ToastBuilder builder(ToastFormat format, Text title) {
      return new SimpleToastBuilder(format, title);
   }

   ToastBuilder addMessage(Text message);

   ToastBuilder withIcon(ToastIcon icon);

   ToastBuilder progressShowsVisibleTime();

   ToastBuilder expiresWhenProgressComplete(long millis);

   ToastBuilder progressPuller(Function<CustomToast, Optional<Float>> progressPuller);

   CustomToast build();

   final class SimpleToastBuilder implements ToastBuilder {
      private final ToastFormat format;
      private final Text title;
      private final List<Text> messages = new ArrayList<>();
      private ToastIcon icon;

      private SimpleToastBuilder(ToastFormat format, Text title) {
         this.format = format;
         this.title = title;
      }

      @Override
      public ToastBuilder addMessage(Text message) {
         this.messages.add(message);
         return this;
      }

      @Override
      public ToastBuilder withIcon(ToastIcon icon) {
         this.icon = icon;
         return this;
      }

      @Override
      public ToastBuilder progressShowsVisibleTime() {
         return this;
      }

      @Override
      public ToastBuilder expiresWhenProgressComplete(long millis) {
         return this;
      }

      @Override
      public ToastBuilder progressPuller(Function<CustomToast, Optional<Float>> progressPuller) {
         return this;
      }

      @Override
      public CustomToast build() {
         return new SimpleCustomToast(this.format, this.title, this.messages, this.icon);
      }
   }

   final class SimpleCustomToast implements CustomToast {
      private final ToastFormat format;
      private final ToastIcon icon;
      private Text title;
      private List<Text> message;
      private float progress;

      private SimpleCustomToast(ToastFormat format, Text title, List<Text> message, ToastIcon icon) {
         this.format = format;
         this.title = title;
         this.message = new ArrayList<>(message);
         this.icon = icon;
      }

      public ToastFormat format() {
         return this.format;
      }

      public ToastIcon icon() {
         return this.icon;
      }

      @Override
      public Text getTitle() {
         return this.title;
      }

      @Override
      public void setTitle(Text title) {
         this.title = title;
      }

      @Override
      public List<Text> getMessage() {
         return List.copyOf(this.message);
      }

      @Override
      public void setMessage(List<Text> message) {
         this.message = new ArrayList<>(message);
      }

      @Override
      public float getProgress() {
         return this.progress;
      }

      @Override
      public void setProgress(float progress) {
         this.progress = progress;
      }
   }
}
