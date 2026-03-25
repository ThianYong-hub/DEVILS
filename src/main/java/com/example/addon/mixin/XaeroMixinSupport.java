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
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import org.lwjgl.BufferUtils;
import org.lwjgl.stb.STBImage;
import org.lwjgl.system.MemoryStack;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import xaero.common.minimap.render.MinimapRendererHelper;
import xaero.common.minimap.waypoints.Waypoint;
import xaero.hud.minimap.element.render.MinimapElementGraphics;
import xaero.hud.minimap.element.render.MinimapElementRenderInfo;
import xaero.map.element.MapElementGraphics;
import xaero.map.element.render.ElementRenderInfo;
import xaero.map.graphics.renderer.multitexture.MultiTextureRenderTypeRendererProvider;

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
    private static Texture devilsGameIcon;

    @Unique
    private static boolean devilsLoadFailed = false;
    @Unique
    private static boolean devilsGameLoadFailed = false;

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
        } else if (widget instanceof WWindow ww && "Devils-Game".equals(ww.id)) {
            Texture icon = devils$getGameIcon();
            if (icon != null) {
                ww.beforeHeaderInit = header -> header.add(GuiThemes.get().texture(32, 32, 0, icon)).pad(2);
            }
        }

        return original.call(instance, widget);
    }

    @Unique
    private static Texture devils$getIcon() {
        if (devilsIcon != null) return devilsIcon;
        if (devilsLoadFailed) return null;
        devilsIcon = devils$loadIcon("category_icon.png", "Devils", () -> devilsLoadFailed = true);
        return devilsIcon;
    }

    @Unique
    private static Texture devils$getGameIcon() {
        if (devilsGameIcon != null) return devilsGameIcon;
        if (devilsGameLoadFailed) return null;
        devilsGameIcon = devils$loadIcon("Devils-Game.png", "Devils-Game", () -> devilsGameLoadFailed = true);
        return devilsGameIcon;
    }

    @Unique
    private static Texture devils$loadIcon(String fileName, String label, Runnable onFail) {
        try {
            Identifier id = Identifier.of("devils-addon", fileName);
            var resource = MinecraftClient.getInstance().getResourceManager().getResource(id);
            if (resource.isEmpty()) {
                AddonTemplate.LOG.error("[Devils] {} icon '{}' not found in resources", label, fileName);
                onFail.run();
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
                    AddonTemplate.LOG.error("[Devils] {} icon decode failed: {}", label, STBImage.stbi_failure_reason());
                    onFail.run();
                    return null;
                }

                Texture texture = new Texture(w.get(0), h.get(0), TextureFormat.RGBA8, FilterMode.LINEAR, FilterMode.LINEAR);
                texture.upload(pixels);
                STBImage.stbi_image_free(pixels);
                AddonTemplate.LOG.info("[Devils] {} icon loaded ({}x{})", label, texture.getWidth(), texture.getHeight());
                return texture;
            }
        } catch (Exception e) {
            AddonTemplate.LOG.error("[Devils] Failed to load {} icon '{}'", label, fileName, e);
            onFail.run();
            return null;
        }
    }
}

final class XaeroManagedWaypointIconSupport {
    private XaeroManagedWaypointIconSupport() {
    }

    static MapIconManager.IconSprite resolveManagedIconSprite(Object waypoint) {
        if (!XaeroSyncWaypoints.isDevilsManagedWaypoint(waypoint)) return null;

        String iconPath = MapIconManager.normalizeIconPath(XaeroSyncWaypoints.resolveManagedWaypointIconPath(waypoint));
        if (iconPath.isBlank()) return null;

        return MapIconManager.resolveIconSprite(iconPath);
    }

    static void blitIcon(MinimapElementGraphics guiGraphics, MapIconManager.IconSprite sprite, int x, int y, int size) {
        if (guiGraphics == null || sprite == null) return;
        int u1 = sprite.u();
        int v1 = sprite.v();
        int u2 = sprite.u() + Math.max(1, sprite.regionWidth());
        int v2 = sprite.v() + Math.max(1, sprite.regionHeight());
        int textureSize = Math.max(1, Math.max(sprite.textureWidth(), sprite.textureHeight()));
        guiGraphics.blit(sprite.id(), x, y, u1, v1, size, size, u2, v2, textureSize, RenderPipelines.GUI_TEXTURED);
    }

    static void blitIcon(MapElementGraphics guiGraphics, MapIconManager.IconSprite sprite, int x, int y, int size) {
        if (guiGraphics == null || sprite == null) return;
        int u1 = sprite.u();
        int v1 = sprite.v();
        int u2 = sprite.u() + Math.max(1, sprite.regionWidth());
        int v2 = sprite.v() + Math.max(1, sprite.regionHeight());
        int textureSize = Math.max(1, Math.max(sprite.textureWidth(), sprite.textureHeight()));
        guiGraphics.blit(sprite.id(), x, y, u1, v1, size, size, u2, v2, textureSize, RenderPipelines.GUI_TEXTURED);
    }
}

@Pseudo
@Mixin(targets = "xaero.map.mods.gui.WaypointRenderer", remap = false)
abstract class WaypointRendererManagedMixin {
    @Unique
    private static final ThreadLocal<MapElementGraphics> devilsAddon$currentGuiGraphics = new ThreadLocal<>();
    @Unique
    private static final ThreadLocal<xaero.map.mods.gui.Waypoint> devilsAddon$currentWaypoint = new ThreadLocal<>();

    @ModifyExpressionValue(
        method = "renderElement",
        at = @At(value = "INVOKE", target = "Lxaero/map/mods/gui/Waypoint;getName()Ljava/lang/String;"),
        require = 0
    )
    private String devilsAddon$formatManagedWaypointLabel(
        String originalName,
        xaero.map.mods.gui.Waypoint waypoint
    ) {
        return XaeroSyncWaypoints.resolveManagedWaypointRenderName(waypoint, originalName);
    }

    @Inject(method = "renderElement", at = @At("HEAD"), require = 0)
    private void devilsAddon$captureMapGraphics(
        xaero.map.mods.gui.Waypoint waypoint,
        boolean hovered,
        double optionalDepth,
        float optionalScale,
        double partialX,
        double partialY,
        ElementRenderInfo renderInfo,
        MapElementGraphics guiGraphics,
        VertexConsumerProvider.Immediate vanillaBufferSource,
        MultiTextureRenderTypeRendererProvider rendererProvider,
        CallbackInfoReturnable<Boolean> cir
    ) {
        devilsAddon$currentWaypoint.set(waypoint);
        devilsAddon$currentGuiGraphics.set(guiGraphics);
    }

    @Inject(method = "renderElement", at = @At("RETURN"), require = 0)
    private void devilsAddon$clearMapGraphics(CallbackInfoReturnable<Boolean> cir) {
        devilsAddon$currentWaypoint.remove();
        devilsAddon$currentGuiGraphics.remove();
    }

    @WrapOperation(
        method = "renderElement",
        at = @At(
            value = "INVOKE",
            target = "Lxaero/map/graphics/MapRenderHelper;blitIntoMultiTextureRenderer(Lorg/joml/Matrix4fc;Lxaero/map/graphics/renderer/multitexture/MultiTextureRenderTypeRenderer;FFFFIIFFFFIILcom/mojang/blaze3d/textures/GpuTexture;)V",
            ordinal = 1
        ),
        require = 0
    )
    private void devilsAddon$replaceManagedWaypointSymbolBlit(
        @Coerce Object matrix,
        @Coerce Object multiTextureRenderer,
        float x,
        float y,
        int u1,
        int v1,
        int width,
        int height,
        float red,
        float green,
        float blue,
        float alpha,
        int textureWidth,
        int textureHeight,
        @Coerce Object textureId,
        Operation<Void> original
    ) {
        xaero.map.mods.gui.Waypoint waypoint = devilsAddon$currentWaypoint.get();
        MapIconManager.IconSprite sprite = XaeroManagedWaypointIconSupport.resolveManagedIconSprite(waypoint);
        if (sprite == null) {
            original.call(
                matrix,
                multiTextureRenderer,
                x,
                y,
                u1,
                v1,
                width,
                height,
                red,
                green,
                blue,
                alpha,
                textureWidth,
                textureHeight,
                textureId
            );
            return;
        }

        MapElementGraphics guiGraphics = devilsAddon$currentGuiGraphics.get();
        if (guiGraphics == null) {
            original.call(
                matrix,
                multiTextureRenderer,
                x,
                y,
                u1,
                v1,
                width,
                height,
                red,
                green,
                blue,
                alpha,
                textureWidth,
                textureHeight,
                textureId
            );
            return;
        }

        int size = Math.max(10, Math.min(width, 28));
        int drawX = Math.round(x + (width - size) * 0.5f);
        int drawY = Math.round(y + (height - size) * 0.5f);
        XaeroManagedWaypointIconSupport.blitIcon(guiGraphics, sprite, drawX, drawY, size);
    }
}

@Pseudo
@Mixin(targets = "xaero.hud.minimap.waypoint.render.WaypointMapRenderer", remap = false)
abstract class WaypointMapRendererManagedMixin {
    @Inject(
        method = "drawIcon",
        at = @At("TAIL"),
        require = 0
    )
    private void devilsAddon$renderManagedWaypointIconOnMinimap(
        MinimapElementGraphics guiGraphics,
        MinimapRendererHelper rendererHelper,
        Waypoint waypoint,
        int drawX,
        int drawY,
        int opacity,
        @Coerce Object renderTypeBuffer,
        VertexConsumer waypointBackgroundConsumer,
        VertexConsumer texturedIconConsumer,
        CallbackInfo ci
    ) {
        MapIconManager.IconSprite sprite = XaeroManagedWaypointIconSupport.resolveManagedIconSprite(waypoint);
        if (sprite == null) return;

        int size = 9;
        XaeroManagedWaypointIconSupport.blitIcon(guiGraphics, sprite, drawX - size / 2, drawY - size / 2, size);
    }
}

@Pseudo
@Mixin(targets = "xaero.hud.minimap.waypoint.render.world.WaypointWorldRenderer", remap = false)
abstract class WaypointWorldRendererManagedMixin {
    @Unique
    private static final ThreadLocal<MinimapElementGraphics> devilsAddon$currentGuiGraphics = new ThreadLocal<>();

    @Inject(method = "renderElement", at = @At("HEAD"), require = 0)
    private void devilsAddon$captureGuiGraphics(
        Waypoint waypoint,
        boolean highlighted,
        boolean outOfBounds,
        double optionalDepth,
        float optionalScale,
        double partialX,
        double partialY,
        MinimapElementRenderInfo renderInfo,
        MinimapElementGraphics guiGraphics,
        VertexConsumerProvider.Immediate vanillaBufferSource,
        CallbackInfoReturnable<Boolean> cir
    ) {
        devilsAddon$currentGuiGraphics.set(guiGraphics);
    }

    @Inject(method = "renderElement", at = @At("RETURN"), require = 0)
    private void devilsAddon$clearGuiGraphics(CallbackInfoReturnable<Boolean> cir) {
        devilsAddon$currentGuiGraphics.remove();
    }

    @Inject(method = "renderIcon", at = @At("TAIL"), require = 0)
    private void devilsAddon$renderManagedWaypointIconInWorld(
        Waypoint waypoint,
        boolean highlighted,
        MatrixStack matrixStack,
        TextRenderer fontRenderer,
        @Coerce Object bufferSource,
        CallbackInfo ci
    ) {
        MapIconManager.IconSprite sprite = XaeroManagedWaypointIconSupport.resolveManagedIconSprite(waypoint);
        if (sprite == null) return;

        MinimapElementGraphics guiGraphics = devilsAddon$currentGuiGraphics.get();
        if (guiGraphics == null) return;

        XaeroManagedWaypointIconSupport.blitIcon(guiGraphics, sprite, -4, -9, 9);
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


