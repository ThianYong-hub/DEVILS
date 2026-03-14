package com.example.addon.mixin;

import com.example.addon.modules.XaeroSync;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Screen.class)
public abstract class ScreenXaeroSyncMixin {
    @Inject(method = "render(Lnet/minecraft/client/gui/DrawContext;IIF)V", at = @At("TAIL"), require = 0)
    private void devilsAddon$xaeroSyncRenderHook(DrawContext drawContext, int mouseX, int mouseY, float tickDelta, CallbackInfo ci) {
        Screen self = (Screen) (Object) this;
        if ("xaero.map.gui.GuiMap".equals(self.getClass().getName())) return;
        XaeroSync.onXaeroMapRenderHook(self, drawContext, mouseX, mouseY, tickDelta);
    }
}
