package com.example.addon.mixin;

import com.example.addon.modules.MultiTask;
import net.minecraft.client.network.ClientPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ClientPlayerEntity.class)
public abstract class ClientPlayerEntityMioMultiTaskMixin {
    private static final StackWalker DEVILS$STACK_WALKER = StackWalker.getInstance();
    private static final String DEVILS$MIO_PACKAGE_PREFIX = "me.mioclient.";

    @Inject(method = "isUsingItem", at = @At("HEAD"), cancellable = true)
    private void devils$overrideIsUsingItemForMio(CallbackInfoReturnable<Boolean> cir) {
        if (!MultiTask.shouldSpoofMioUsingItem()) return;
        if (!devils$isMioCaller()) return;

        cir.setReturnValue(false);
    }

    private static boolean devils$isMioCaller() {
        return DEVILS$STACK_WALKER.walk(stream -> stream
            .skip(2)
            .anyMatch(frame -> frame.getClassName().startsWith(DEVILS$MIO_PACKAGE_PREFIX)));
    }
}
