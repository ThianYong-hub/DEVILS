package com.example.addon.mixin;

import com.example.addon.modules.XaeroSync;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;

@Pseudo
@Mixin(targets = "xaero.map.gui.GuiMap", remap = false)
public abstract class GuiMapXaeroSyncMixin {
    @Inject(method = "method_25394", at = @At("TAIL"), require = 0)
    private void devilsAddon$xaeroSyncGuiMapRenderHook(DrawContext drawContext, int mouseX, int mouseY, float tickDelta, CallbackInfo ci) {
        Screen self = (Screen) (Object) this;
        double cameraX = readDouble(self, "cameraX");
        double cameraZ = readDouble(self, "cameraZ");
        double scale = readDouble(self, "scale");
        XaeroSync.onXaeroMapRenderProjectedHook(self, drawContext, mouseX, mouseY, tickDelta, cameraX, cameraZ, scale);
    }

    private static double readDouble(Object owner, String fieldName) {
        if (owner == null || fieldName == null || fieldName.isBlank()) return Double.NaN;
        Class<?> cursor = owner.getClass();
        while (cursor != null) {
            try {
                Field field = cursor.getDeclaredField(fieldName);
                field.setAccessible(true);
                Object value = field.get(owner);
                if (value instanceof Number number) return number.doubleValue();
                return Double.NaN;
            } catch (NoSuchFieldException ignored) {
                cursor = cursor.getSuperclass();
            } catch (Throwable ignored) {
                return Double.NaN;
            }
        }
        return Double.NaN;
    }
}
