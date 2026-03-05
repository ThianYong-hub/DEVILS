package com.example.addon.mixin;

import com.example.addon.modules.MultiTask;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Allows breaking blocks while using items (eating, drinking, blocking, etc.)
 * by redirecting the isUsingItem() check in handleBlockBreaking.
 *
 * NCP's blockbreak and item-use systems are completely isolated (MovingData vs BlockBreakData),
 * so simultaneous use + mine is not cross-checked and won't trigger violations.
 */
@Mixin(MinecraftClient.class)
public abstract class MultiTaskMixin {

    @Redirect(
        method = "handleBlockBreaking",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/network/ClientPlayerEntity;isUsingItem()Z")
    )
    private boolean redirectIsUsingItem(ClientPlayerEntity player) {
        if (Modules.get().isActive(MultiTask.class)) return false;
        return player.isUsingItem();
    }
}
