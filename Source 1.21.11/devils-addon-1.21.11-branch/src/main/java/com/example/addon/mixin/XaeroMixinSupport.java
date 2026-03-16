package com.example.addon.mixin;

import com.example.addon.AddonTemplate;
import com.example.addon.util.MapIconManager;
import com.example.addon.util.XaeroSyncWaypoints;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.TextureFormat;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.IntBuffer;
import java.security.CodeSource;
import java.util.List;
import java.util.Optional;
import meteordevelopment.meteorclient.gui.GuiThemes;
import meteordevelopment.meteorclient.gui.screens.ModulesScreen;
import meteordevelopment.meteorclient.gui.utils.Cell;
import meteordevelopment.meteorclient.gui.widgets.containers.WContainer;
import meteordevelopment.meteorclient.gui.widgets.containers.WWindow;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.renderer.Texture;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.metadata.ModOrigin;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.Identifier;
import org.lwjgl.BufferUtils;
import org.lwjgl.stb.STBImage;
import org.lwjgl.system.MemoryStack;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;

@Pseudo
@Mixin(targets = "xaero.common.HudMod", remap = false)
abstract class HudModSafeModFileMixin {
    private static final String DEVILS_ADDON_ID = "devils-addon";

    @Redirect(
        method = "loadClient",
        at = @At(
            value = "INVOKE",
            target = "Lxaero/common/platform/services/IPlatformHelper;getModFile(Ljava/lang/String;)Ljava/nio/file/Path;"
        ),
        require = 0
    )
    private Path devilsAddon$safeGetModFile(@Coerce Object platformHelper, String modId) {
        Path modFile = null;

        if (platformHelper != null) {
            try {
                Object value = platformHelper.getClass().getMethod("getModFile", String.class).invoke(platformHelper, modId);
                if (value instanceof Path path && path.getFileName() != null) modFile = path;
            } catch (Throwable ignored) {
            }
        }

        if (modFile == null) modFile = resolveModOriginPath(modId);
        if (modFile == null) modFile = resolveModOriginPath(DEVILS_ADDON_ID);
        if (modFile == null) modFile = resolveCodeSourcePath();
        if (modFile == null) modFile = FabricLoader.getInstance().getGameDir().resolve("mods").resolve(modId + "-embedded.jar");
        return modFile;
    }

    private static Path resolveModOriginPath(String modId) {
        try {
            ModContainer container = FabricLoader.getInstance().getModContainer(modId).orElse(null);
            if (container == null) return null;

            ModOrigin origin = container.getOrigin();
            if (origin == null) return null;

            List<Path> paths = origin.getPaths();
            if (paths == null || paths.isEmpty()) return null;

            for (Path path : paths) {
                if (path != null && path.getFileName() != null) return path;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static Path resolveCodeSourcePath() {
        try {
            CodeSource source = HudModSafeModFileMixin.class.getProtectionDomain().getCodeSource();
            if (source == null || source.getLocation() == null) return null;
            URI uri = source.getLocation().toURI();
            if (!"file".equalsIgnoreCase(uri.getScheme())) return null;
            Path path = Paths.get(uri).toAbsolutePath().normalize();
            return path.getFileName() == null ? null : path;
        } catch (Throwable ignored) {
            return null;
        }
    }
}

@Mixin(ModulesScreen.class)
abstract class ModulesScreenIconsMixin {
    @Unique
    private static Texture devilsIcon;

    @Unique
    private static boolean loadFailed = false;

    /**
     * Wraps the c.add(w) call inside createCategory.
     * c.add(w) triggers WWindow.init() which consumes beforeHeaderInit,
     * so we MUST replace it BEFORE the add() call, not after RETURN.
     */
    @WrapOperation(
        method = "createCategory",
        at = @At(value = "INVOKE",
            target = "Lmeteordevelopment/meteorclient/gui/widgets/containers/WContainer;add(Lmeteordevelopment/meteorclient/gui/widgets/WWidget;)Lmeteordevelopment/meteorclient/gui/utils/Cell;"),
        remap = false
    )
    private Cell<?> devils$beforeWindowInit(WContainer instance, WWidget widget, Operation<Cell<?>> original) {
        if (widget instanceof WWindow ww && "Devils".equals(ww.id)) {
            Texture icon = devils$getIcon();
            if (icon != null) {
                ww.beforeHeaderInit = header -> header.add(GuiThemes.get().texture(32, 32, 0, icon)).pad(2);
            }
        }

        return original.call(instance, widget);
    }

    @Unique
    private static Texture devils$getIcon() {
        if (devilsIcon != null) return devilsIcon;
        if (loadFailed) return null;

        try {
            Identifier id = Identifier.of("devils-addon", "category_icon.png");
            var resource = MinecraftClient.getInstance().getResourceManager().getResource(id);

            if (resource.isEmpty()) {
                AddonTemplate.LOG.error("[Devils] category_icon.png not found in resources");
                loadFailed = true;
                return null;
            }

            byte[] bytes;
            try (InputStream in = resource.get().getInputStream()) {
                bytes = in.readAllBytes();
            }

            ByteBuffer rawBuffer = BufferUtils.createByteBuffer(bytes.length);
            rawBuffer.put(bytes);
            rawBuffer.flip();

            try (MemoryStack stack = MemoryStack.stackPush()) {
                IntBuffer w = stack.mallocInt(1);
                IntBuffer h = stack.mallocInt(1);
                IntBuffer ch = stack.mallocInt(1);

                ByteBuffer pixels = STBImage.stbi_load_from_memory(rawBuffer, w, h, ch, 4);
                if (pixels == null) {
                    AddonTemplate.LOG.error("[Devils] STBImage failed: {}", STBImage.stbi_failure_reason());
                    loadFailed = true;
                    return null;
                }

                devilsIcon = new Texture(w.get(0), h.get(0), TextureFormat.RGBA8, FilterMode.LINEAR, FilterMode.LINEAR);
                devilsIcon.upload(pixels);

                STBImage.stbi_image_free(pixels);
            }

            AddonTemplate.LOG.info("[Devils] Category icon loaded ({}x{})", devilsIcon.getWidth(), devilsIcon.getHeight());
        } catch (Exception e) {
            AddonTemplate.LOG.error("[Devils] Failed to load category icon", e);
            loadFailed = true;
        }

        return devilsIcon;
    }
}

@Pseudo
@Mixin(targets = "xaero.map.mods.gui.WaypointRenderer", remap = false)
abstract class WaypointRendererManagedMixin {
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

@Pseudo
@Mixin(targets = "xaero.map.WorldMapFabric", remap = false)
abstract class WorldMapFabricSafeModFileMixin {
    private static final String XAERO_WORLDMAP_ID = "xaeroworldmap";
    private static final String DEVILS_ADDON_ID = "devils-addon";
    private static final String FALLBACK_XAERO_WORLDMAP_VERSION = "1.40.11";

    @Inject(method = "fetchModFile", at = @At("HEAD"), cancellable = true, require = 0)
    private void devilsAddon$fetchModFileSafe(CallbackInfoReturnable<Path> cir) {
        Path resolved = resolveModOriginPath(XAERO_WORLDMAP_ID);
        if (resolved == null) resolved = resolveModOriginPath(DEVILS_ADDON_ID);
        if (resolved == null) resolved = resolveCodeSourcePath();
        if (resolved == null) resolved = FabricLoader.getInstance().getGameDir().resolve("mods").resolve("xaeroworldmap-embedded.jar");
        cir.setReturnValue(resolved);
    }

    @Inject(method = "getModInfoVersion", at = @At("HEAD"), cancellable = true, require = 0)
    private void devilsAddon$getModInfoVersionSafe(CallbackInfoReturnable<String> cir) {
        try {
            ModContainer modContainer = FabricLoader.getInstance().getModContainer(XAERO_WORLDMAP_ID).orElse(null);
            if (modContainer != null) {
                String version = modContainer.getMetadata().getVersion().getFriendlyString();
                if (version != null && !version.isBlank()) {
                    cir.setReturnValue(version + "_fabric");
                    return;
                }
            }
        } catch (Throwable ignored) {
        }
        cir.setReturnValue(FALLBACK_XAERO_WORLDMAP_VERSION + "_fabric");
    }

    private static Path resolveModOriginPath(String modId) {
        try {
            ModContainer modContainer = FabricLoader.getInstance().getModContainer(modId).orElse(null);
            if (modContainer == null) return null;

            ModOrigin origin = modContainer.getOrigin();
            if (origin == null) return null;

            List<Path> paths = origin.getPaths();
            if (paths == null || paths.isEmpty()) return null;

            for (Path path : paths) {
                if (path != null && path.getFileName() != null) return path;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static Path resolveCodeSourcePath() {
        try {
            CodeSource source = WorldMapFabricSafeModFileMixin.class.getProtectionDomain().getCodeSource();
            if (source == null || source.getLocation() == null) return null;
            URI uri = source.getLocation().toURI();
            if (!"file".equalsIgnoreCase(uri.getScheme())) return null;
            Path path = Paths.get(uri).toAbsolutePath().normalize();
            return path.getFileName() == null ? null : path;
        } catch (Throwable ignored) {
            return null;
        }
    }
}

@Pseudo
@Mixin(targets = "xaero.common.platform.services.FabricPlatformHelper", remap = false)
abstract class XaeroCommonFabricPlatformHelperMixin {
    private static final String DEVILS_ADDON_ID = "devils-addon";

    @Inject(method = "getModFile", at = @At("RETURN"), cancellable = true, require = 0)
    private void devilsAddon$getModFileSafe(String modId, CallbackInfoReturnable<Path> cir) {
        Path current = cir.getReturnValue();
        if (isUsablePath(current)) return;

        Path resolved = resolveModOriginPath(modId);
        if (!isUsablePath(resolved)) resolved = resolveModOriginPath(DEVILS_ADDON_ID);
        if (!isUsablePath(resolved)) resolved = resolveCodeSourcePath();
        if (!isUsablePath(resolved)) {
            resolved = FabricLoader.getInstance().getGameDir().resolve("mods").resolve(modId + "-embedded.jar");
        }
        cir.setReturnValue(resolved);
    }

    private static boolean isUsablePath(Path path) {
        return path != null && path.getFileName() != null;
    }

    private static Path resolveModOriginPath(String modId) {
        try {
            Optional<ModContainer> optional = FabricLoader.getInstance().getModContainer(modId);
            if (optional.isEmpty()) return null;

            ModOrigin origin = optional.get().getOrigin();
            if (origin == null) return null;

            List<Path> paths = origin.getPaths();
            if (paths == null || paths.isEmpty()) return null;

            for (Path path : paths) {
                if (isUsablePath(path)) return path;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static Path resolveCodeSourcePath() {
        try {
            CodeSource source = XaeroCommonFabricPlatformHelperMixin.class.getProtectionDomain().getCodeSource();
            if (source == null || source.getLocation() == null) return null;
            URI uri = source.getLocation().toURI();
            if (!"file".equalsIgnoreCase(uri.getScheme())) return null;
            Path path = Paths.get(uri).toAbsolutePath().normalize();
            return isUsablePath(path) ? path : null;
        } catch (Throwable ignored) {
            return null;
        }
    }
}


