package com.example.addon.chesttracker.mixins.compat.chesttracker;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "red.jackf.chesttracker.impl.compat.mods.jade.ChestTrackerJadePlugin", remap = false)
public class ExternalChestTrackerJadePluginMixin {
    @Inject(method = "registerClient", at = @At("HEAD"), cancellable = true, remap = false)
    private void devilsAddon$disableExternalJade(CallbackInfo ci) {
        ci.cancel();
    }
}
