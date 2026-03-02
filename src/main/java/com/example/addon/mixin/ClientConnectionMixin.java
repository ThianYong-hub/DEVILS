package com.example.addon.mixin;

import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.network.ClientConnection;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Fix Meteor's ClientConnectionMixin NPE on disconnect.
 * Meteor's mixin does: Modules.get().get(HighwayBuilder.class).isActive()
 * If their HighwayBuilder module isn't registered, .get() returns null → NPE crash.
 * We inject before their mixin and register a dummy module to prevent the null.
 */
@Mixin(value = ClientConnection.class, priority = 500)
public abstract class ClientConnectionMixin {
    @Inject(method = "disconnect(Lnet/minecraft/text/Text;)V", at = @At("HEAD"))
    private void fixHighwayBuilderNPE(Text reason, CallbackInfo ci) {
        try {
            @SuppressWarnings("unchecked")
            Class<? extends Module> meteorHW = (Class<? extends Module>)
                Class.forName("meteordevelopment.meteorclient.systems.modules.world.HighwayBuilder");
            if (Modules.get() != null && Modules.get().get(meteorHW) == null) {
                Modules.get().add(meteorHW.getDeclaredConstructor().newInstance());
            }
        } catch (Exception ignored) {}
    }
}
