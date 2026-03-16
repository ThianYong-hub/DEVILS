package com.example.addon.chesttracker.mixins.compat.chesttracker;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "red.jackf.chesttracker.impl.compat.mods.shulkerboxtooltip.ChestTrackerShulkerBoxTooltip", remap = false)
public class ExternalChestTrackerShulkerTooltipMixin {
    @Inject(method = "registerProviders", at = @At("HEAD"), cancellable = true, remap = false)
    private void devilsAddon$disableExternalShulkerTooltip(CallbackInfo ci) {
        ci.cancel();
    }
}
