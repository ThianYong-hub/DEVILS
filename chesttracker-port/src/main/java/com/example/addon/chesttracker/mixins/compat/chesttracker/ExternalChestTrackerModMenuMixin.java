package com.example.addon.chesttracker.mixins.compat.chesttracker;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "red.jackf.chesttracker.impl.compat.mods.ChestTrackerModMenu", remap = false)
public class ExternalChestTrackerModMenuMixin {
    @Inject(method = "getModConfigScreenFactory", at = @At("HEAD"), cancellable = true, remap = false)
    private void devilsAddon$disableExternalModMenu(CallbackInfoReturnable<Object> cir) {
        cir.setReturnValue(null);
    }
}
