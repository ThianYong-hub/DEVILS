package red.jackf.jackfredlib.client.api.toasts;

import java.util.List;
import net.minecraft.text.Text;

public interface CustomToast {
   Text getTitle();

   void setTitle(Text title);

   List<Text> getMessage();

   void setMessage(List<Text> message);

   float getProgress();

   void setProgress(float progress);
}
