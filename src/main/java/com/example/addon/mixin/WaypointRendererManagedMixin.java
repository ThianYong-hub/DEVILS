package com.example.addon.mixin;

import com.example.addon.util.MapIconManager;
import com.example.addon.util.XaeroSyncWaypoints;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.reflect.Method;

@Pseudo
@Mixin(targets = "xaero.map.mods.gui.WaypointRenderer", remap = false)
public abstract class WaypointRendererManagedMixin {
    @ModifyExpressionValue(
        method = "renderElement",
        at = @At(value = "INVOKE", target = "Lxaero/map/mods/gui/Waypoint;getName()Ljava/lang/String;"),
        require = 0
    )
    private String devilsAddon$formatManagedWaypointLabel(String originalName, Object waypoint) {
        return XaeroSyncWaypoints.resolveManagedWaypointRenderName(waypoint, originalName);
    }

    @Inject(method = "renderElement", at = @At("TAIL"), require = 0)
    private void devilsAddon$renderManagedWaypointIcon(
        Object waypoint,
        boolean hovered,
        double optionalDepth,
        float optionalScale,
        double partialX,
        double partialY,
        Object renderInfo,
        Object guiGraphics,
        Object vanillaBufferSource,
        Object rendererProvider,
        CallbackInfoReturnable<Boolean> cir
    ) {
        if (!XaeroSyncWaypoints.isDevilsManagedWaypoint(waypoint)) return;

        String iconPath = MapIconManager.normalizeIconPath(XaeroSyncWaypoints.resolveManagedWaypointIconPath(waypoint));
        if (iconPath.isBlank()) return;

        MapIconManager.IconSprite sprite = MapIconManager.resolveIconSprite(iconPath);
        if (sprite == null) return;

        int size = Math.max(8, Math.round(10.0f * Math.max(0.75f, optionalScale)));
        int x = (int) Math.round(partialX) - size / 2;
        int y = (int) Math.round(partialY) - size / 2;
        blitIcon(guiGraphics, sprite, x, y, size);
    }

    private static void blitIcon(Object guiGraphics, MapIconManager.IconSprite sprite, int x, int y, int size) {
        if (guiGraphics == null || sprite == null) return;

        int u1 = sprite.u();
        int v1 = sprite.v();
        int u2 = sprite.u() + Math.max(1, sprite.regionWidth());
        int v2 = sprite.v() + Math.max(1, sprite.regionHeight());
        int textureSize = Math.max(1, Math.max(sprite.textureWidth(), sprite.textureHeight()));
        Object pipeline = RenderPipelines.GUI_TEXTURED;

        try {
            Method elevenArg = findBlitMethod(guiGraphics.getClass(), 11);
            if (elevenArg != null) {
                elevenArg.invoke(guiGraphics, sprite.id(), x, y, u1, v1, size, size, u2, v2, textureSize, pipeline);
                return;
            }
        } catch (Throwable ignored) {
        }

        try {
            Method nineArg = findBlitMethod(guiGraphics.getClass(), 9);
            if (nineArg != null) {
                nineArg.invoke(guiGraphics, sprite.id(), x, y, u1, v1, size, size, textureSize, pipeline);
            }
        } catch (Throwable ignored) {
        }
    }

    private static Method findBlitMethod(Class<?> ownerClass, int argCount) {
        if (ownerClass == null) return null;
        for (Method method : ownerClass.getMethods()) {
            if (!"blit".equals(method.getName())) continue;
            Class<?>[] params = method.getParameterTypes();
            if (params.length != argCount) continue;
            if (!params[0].isAssignableFrom(Identifier.class)) continue;
            method.setAccessible(true);
            return method;
        }
        return null;
    }
}
