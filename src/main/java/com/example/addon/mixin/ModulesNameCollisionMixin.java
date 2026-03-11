package com.example.addon.mixin;

import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = Modules.class, priority = 2000, remap = false)
public abstract class ModulesNameCollisionMixin {
    private static final String DEVILS_PACKAGE_PREFIX = "com.example.addon.";

    @Inject(method = "add", at = @At("HEAD"), cancellable = true, remap = false)
    private void keepDevilsModuleOnNameCollision(Module incoming, CallbackInfo ci) {
        if (incoming == null || incoming.name == null || incoming.name.isBlank()) return;

        Modules modules = Modules.get();
        if (modules == null) return;

        Module existing = modules.get(incoming.name);
        if (existing == null || existing == incoming) return;

        if (isDevilsModule(existing) && !isDevilsModule(incoming)) {
            // Our module with the same name is already registered, keep it.
            ci.cancel();
        }
    }

    private static boolean isDevilsModule(Module module) {
        return module != null && module.getClass().getName().startsWith(DEVILS_PACKAGE_PREFIX);
    }
}
