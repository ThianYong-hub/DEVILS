package com.example.addon.mixin;

import com.example.addon.modules.XaeroSync;
import com.mojang.authlib.GameProfile;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Pseudo
@Mixin(targets = "xaero.map.radar.tracker.PlayerTrackerMapElementRenderer", remap = false)
public abstract class PlayerTrackerPingNameMixin {
    @Redirect(
        method = "renderElement",
        at = @At(value = "INVOKE", target = "Lcom/mojang/authlib/GameProfile;getName()Ljava/lang/String;"),
        require = 0
    )
    private String devilsAddon$renderPingName(GameProfile profile) {
        if (profile == null) return "";
        return XaeroSync.resolveTrackedPingDisplayName(profile.getId(), profile.getName());
    }
}
