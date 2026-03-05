package com.example.addon.mixin;

import com.example.addon.modules.MultiTask;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(KeyBinding.class)
public abstract class KeyBindingMioMultiTaskMixin {
    private static final StackWalker DEVILS$STACK_WALKER = StackWalker.getInstance();
    private static final String DEVILS$MIO_PACKAGE_PREFIX = "me.mioclient.";

    @Inject(method = "isPressed", at = @At("HEAD"), cancellable = true)
    private void devils$overrideUseKeyPressedForMio(CallbackInfoReturnable<Boolean> cir) {
        if (!MultiTask.shouldSpoofMioUseKey()) return;
        if (!devils$isMioCaller()) return;

        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.options == null) return;
        if ((Object) this != mc.options.useKey) return;

        cir.setReturnValue(false);
    }

    private static boolean devils$isMioCaller() {
        return DEVILS$STACK_WALKER.walk(stream -> stream
            .skip(2)
            .anyMatch(frame -> frame.getClassName().startsWith(DEVILS$MIO_PACKAGE_PREFIX)));
    }
}
