package com.example.addon.chesttracker.mixins.compat.chesttracker;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "red.jackf.chesttracker.impl.compat.mods.ChestTrackerWhereIsItPlugin", remap = false)
public class ExternalChestTrackerWhereIsItPluginMixin {
    @Inject(method = "load", at = @At("HEAD"), cancellable = true, remap = false)
    private void devilsAddon$disableExternalWhereIsIt(CallbackInfo ci) {
        ci.cancel();
    }
}
