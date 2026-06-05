/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  net.minecraft.client.MinecraftClient
 *  net.minecraft.client.font.TextRenderer
 *  net.minecraft.client.render.VertexConsumerProvider
 *  net.minecraft.client.render.VertexConsumerProvider$Immediate
 *  net.minecraft.client.texture.TextureManager
 *  net.minecraft.client.util.math.MatrixStack
 *  xaero.lib.XaeroLib
 *  xaero.lib.client.config.ClientConfigManager
 *  xaero.lib.client.graphics.XaeroBufferProvider
 *  xaero.lib.common.config.option.ConfigOption
 *  xaero.lib.common.config.single.SingleConfigManager
 */
package xaero.map.mods.gui;

import java.lang.reflect.Method;
import java.util.Locale;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.texture.AbstractTexture;
import net.minecraft.client.texture.TextureManager;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import xaero.lib.XaeroLib;
import xaero.lib.client.config.ClientConfigManager;
import xaero.lib.client.graphics.XaeroBufferProvider;
import xaero.lib.common.config.option.ConfigOption;
import xaero.lib.common.config.single.SingleConfigManager;
import xaero.map.WorldMap;
import xaero.map.animation.SlowingAnimation;
import xaero.map.common.config.option.WorldMapProfiledConfigOptions;
import xaero.map.config.primary.option.WorldMapPrimaryClientConfigOptions;
import xaero.map.element.MapElementGraphics;
import xaero.map.element.render.ElementRenderInfo;
import xaero.map.element.render.ElementRenderLocation;
import xaero.map.element.render.ElementRenderer;
import xaero.map.graphics.CustomRenderTypes;
import xaero.map.graphics.MapRenderHelper;
import xaero.map.graphics.renderer.multitexture.MultiTextureRenderTypeRendererProvider;
import xaero.map.gui.GuiMap;
import xaero.map.icon.XaeroIcon;
import xaero.map.misc.Misc;
import xaero.map.mods.SupportXaeroMinimap;

@SuppressWarnings({"rawtypes", "unchecked", "unused"})
public final class WaypointRenderer
extends ElementRenderer<Waypoint, WaypointRenderContext, WaypointRenderer> {
    private static final String DEVILS_MANAGED_SUFFIX = "\u2063\u2063";
    private static final String DEVILS_MANAGED_SUFFIX_LEGACY_VISIBLE = " [Devils Sync]";
    private static final String DEVILS_DEFAULT_ICON_PATH = "assets/devils-addon/textures/gui/devils_ping_icon_white.png";
    private static final Identifier DEVILS_EMBEDDED_ICON_TEXTURE = Identifier.of("devils-addon", "textures/gui/devils_ping_icon_white.png");
    private static final int DEVILS_EMBEDDED_ICON_TEXTURE_SIZE = 1024;
    private static final int DEVILS_EMBEDDED_ICON_U = 256;
    private static final int DEVILS_EMBEDDED_ICON_V = 167;
    private static final int DEVILS_EMBEDDED_ICON_W = 525;
    private static final int DEVILS_EMBEDDED_ICON_H = 612;
    private static final int DEVILS_MANAGED_ICON_SIZE = 30;
    private static final int DEVILS_MANAGED_ICON_Y = -43;
    private final SupportXaeroMinimap minimap;
    private final WaypointSymbolCreator symbolCreator;
    private ElementRenderInfo compatibleRenderInfo;

    private WaypointRenderer(WaypointRenderContext context, WaypointRenderProvider provider, WaypointReader reader, SupportXaeroMinimap minimap, WaypointSymbolCreator symbolCreator) {
        super(context, provider, reader);
        this.minimap = minimap;
        this.symbolCreator = symbolCreator;
    }

    public WaypointSymbolCreator getSymbolCreator() {
        return this.symbolCreator;
    }

    @Override
    public void renderElementShadow(Waypoint w, boolean hovered, float optionalScale, double partialX, double partialY, ElementRenderInfo renderInfo, MapElementGraphics guiGraphics, VertexConsumerProvider.Immediate vanillaBufferSource, MultiTextureRenderTypeRendererProvider rendererProvider) {
        String waypointName = w.getName();
        String managedIconPath = this.resolveDevilsManagedIconPath(w);
        if (managedIconPath == null || managedIconPath.isBlank()) {
            managedIconPath = WaypointRenderer.parseLegacyManagedIconPath(waypointName);
        }
        String normalizedName = WaypointRenderer.normalizeDevilsWaypointName(waypointName);
        if ((managedIconPath == null || managedIconPath.isBlank()) && WaypointRenderer.looksLikePingWaypoint(normalizedName)) {
            managedIconPath = DEVILS_DEFAULT_ICON_PATH;
        }
        if (managedIconPath != null && !managedIconPath.isBlank()) {
            return;
        }
        MatrixStack matrixStack = guiGraphics.pose();
        matrixStack.translate(partialX, partialY, 0.0);
        matrixStack.scale(optionalScale * ((WaypointRenderContext)this.context).worldmapWaypointsScale, optionalScale * ((WaypointRenderContext)this.context).worldmapWaypointsScale, 1.0f);
        float visibilityAlpha = w.isDisabled() ? 0.3f : 1.0f;
        matrixStack.translate(-14.0f, -41.0f, 0.0f);
        MapRenderHelper.blitIntoExistingBuffer(matrixStack.peek().getPositionMatrix(), ((WaypointRenderContext)this.context).regularUIObjectConsumer, 0, 19, 0, 117, 41, 22, 0.0f, 0.0f, 0.0f, renderInfo.brightness * visibilityAlpha / ((WaypointRenderContext)this.context).worldmapWaypointsScale);
    }

    @Override
    public boolean shouldRender(ElementRenderLocation location, boolean shadow) {
        ClientConfigManager configManager = WorldMap.INSTANCE.getConfigs().getClientConfigManager();
        boolean waypointBackgroundsConfig = (Boolean)configManager.getEffective((ConfigOption)WorldMapProfiledConfigOptions.WAYPOINT_BACKGROUNDS);
        boolean renderWaypoints = (Boolean)configManager.getEffective((ConfigOption)WorldMapProfiledConfigOptions.RENDER_WAYPOINTS);
        return renderWaypoints && (!shadow || waypointBackgroundsConfig);
    }

    @Override
    public boolean renderElement(Waypoint w, boolean hovered, double optionalDepth, float optionalScale, double partialX, double partialY, ElementRenderInfo renderInfo, MapElementGraphics guiGraphics, VertexConsumerProvider.Immediate vanillaBufferSource, MultiTextureRenderTypeRendererProvider rendererProvider) {
        int symbolFrameWidth;
        MatrixStack matrixStack = guiGraphics.pose();
        boolean renderBackground = hovered || ((WaypointRenderContext)this.context).waypointBackgrounds;
        matrixStack.translate(partialX, partialY, 0.0);
        matrixStack.scale(optionalScale * ((WaypointRenderContext)this.context).worldmapWaypointsScale, optionalScale * ((WaypointRenderContext)this.context).worldmapWaypointsScale, 1.0f);
        matrixStack.push();
        float visibilityAlpha = w.isDisabled() ? 0.3f : 1.0f;
        int color = w.getColor();
        String symbol = w.getSymbol();
        int type = w.getType();
        String waypointName = w.getName();
        String managedIconPath = this.resolveDevilsManagedIconPath(w);
        if (managedIconPath == null || managedIconPath.isBlank()) {
            managedIconPath = WaypointRenderer.parseLegacyManagedIconPath(waypointName);
        }
        String normalizedName = WaypointRenderer.normalizeDevilsWaypointName(waypointName);
        if ((managedIconPath == null || managedIconPath.isBlank()) && WaypointRenderer.looksLikePingWaypoint(normalizedName)) {
            managedIconPath = DEVILS_DEFAULT_ICON_PATH;
        }
        boolean managedIconPresent = managedIconPath != null && !managedIconPath.isBlank();
        if (managedIconPresent && !hovered) {
            renderBackground = false;
        }
        float red = (float)(color >> 16 & 0xFF) / 255.0f;
        float green = (float)(color >> 8 & 0xFF) / 255.0f;
        float blue = (float)(color & 0xFF) / 255.0f;
        int flagU = 35;
        int flagV = 34;
        int flagW = 30;
        int flagH = 43;
        if (symbol.length() > 1) {
            flagU += 35;
            flagW += 13;
        }
        if (w.isTemporary()) {
            flagU += 83;
        }
        matrixStack.translate((float)(-flagW) / 2.0f, (float)(-flagH + 1), 0.0f);
        if (renderBackground) {
            TextureManager textureManager = MinecraftClient.getInstance().getTextureManager();
            MapRenderHelper.blitIntoMultiTextureRenderer(matrixStack.peek().getPositionMatrix(), ((WaypointRenderContext)this.context).uniqueTextureUIObjectRenderer, 0.0f, 0.0f, flagU, flagV, flagW, flagH, red * visibilityAlpha, green * visibilityAlpha, blue * visibilityAlpha, visibilityAlpha, textureManager.getTexture(WorldMap.guiTextures).getGlTexture());
        }
        matrixStack.pop();
        float oldDestAlpha = w.getDestAlpha();
        if (hovered) {
            w.setDestAlpha(255.0f);
        } else {
            w.setDestAlpha(0.0f);
        }
        if (oldDestAlpha != w.getDestAlpha()) {
            w.setAlphaAnim(new SlowingAnimation(w.getAlpha(), w.getDestAlpha(), 0.8, 1.0));
        }
        if (w.getAlphaAnim() != null) {
            w.setAlpha((float)w.getAlphaAnim().getCurrent());
        }
        float alpha = w.getAlpha();
        XaeroIcon symbolIcon = null;
        int symbolVerticalOffset = 0;
        int symbolWidth = 0;
        TextRenderer fontRenderer = MinecraftClient.getInstance().textRenderer;
        int stringWidth = fontRenderer.getWidth(symbol);
        int n = symbolFrameWidth = stringWidth / 2 > 4 ? 62 : 32;
        if (type != 1 && alpha < 200.0f) {
            symbolVerticalOffset = 5;
            symbolWidth = (stringWidth - 1) * 3;
            symbolIcon = this.symbolCreator.getSymbolTexture(guiGraphics, symbol);
        } else if (type == 1) {
            symbolVerticalOffset = 3;
            symbolWidth = 27;
            symbolIcon = this.symbolCreator.getDeathSymbolTexture(guiGraphics);
        }
        if (managedIconPresent) {
            int iconSize = DEVILS_MANAGED_ICON_SIZE;
            matrixStack.push();
            int iconX = -iconSize / 2;
            int iconY = DEVILS_MANAGED_ICON_Y;
            int outline = (int)(Math.max(0.0f, Math.min(1.0f, 0.7f * visibilityAlpha)) * 255.0f) << 24;
            int backdrop = (int)(Math.max(0.0f, Math.min(1.0f, 0.9f * visibilityAlpha)) * 255.0f) << 24;
            guiGraphics.fill(net.minecraft.client.gl.RenderPipelines.GUI, iconX - 1, iconY - 1, iconX + iconSize + 1, iconY + iconSize + 1, outline);
            guiGraphics.fill(net.minecraft.client.gl.RenderPipelines.GUI, iconX, iconY, iconX + iconSize, iconY + iconSize, backdrop);
            this.renderDevilsCustomIcon(guiGraphics, managedIconPath, iconX, iconY, iconSize);
            matrixStack.pop();
        } else if (symbolIcon != null) {
            matrixStack.push();
            matrixStack.translate(-1.0f - (float)symbolWidth / 2.0f, (float)(62 + (renderBackground ? -43 + symbolVerticalOffset - 1 : -12)), 0.0f);
            matrixStack.scale(1.0f, -1.0f, 1.0f);
            MapRenderHelper.blitIntoMultiTextureRenderer(matrixStack.peek().getPositionMatrix(), ((WaypointRenderContext)this.context).uniqueTextureUIObjectRenderer, 0.0f, 0.0f, symbolIcon.getOffsetX() + 1, symbolIcon.getOffsetY() + 1, symbolFrameWidth, 62, visibilityAlpha, visibilityAlpha, visibilityAlpha, visibilityAlpha, symbolIcon.getTextureAtlas().getWidth(), symbolIcon.getTextureAtlas().getWidth(), symbolIcon.getTextureAtlas().getTextureId());
            matrixStack.pop();
        }
        if ((int)alpha > 0) {
            int tc = (int)alpha << 24 | 0xFFFFFF;
            String name = normalizedName;
            int len = fontRenderer.getWidth(name);
            matrixStack.translate(0.0f, (float)(renderBackground ? -38 : -11), 0.0f);
            matrixStack.scale(3.0f, 3.0f, 1.0f);
            int bgLen = Math.max(len + 2, 10);
            MapRenderHelper.fillIntoExistingBuffer(matrixStack.peek().getPositionMatrix(), ((WaypointRenderContext)this.context).textBGConsumer, -bgLen / 2, -1, bgLen / 2, 9, red, green, blue, alpha / 255.0f);
            MapRenderHelper.fillIntoExistingBuffer(matrixStack.peek().getPositionMatrix(), ((WaypointRenderContext)this.context).textBGConsumer, -bgLen / 2, -1, bgLen / 2, 8, 0.0f, 0.0f, 0.0f, alpha / 255.0f * 200.0f / 255.0f);
            if ((int)alpha > 3) {
                matrixStack.translate(0.0f, 0.0f, 1.0f);
                Misc.drawNormalText(matrixStack, name, (float)(-(len - 1)) / 2.0f, 0.0f, tc, false, (VertexConsumerProvider)vanillaBufferSource);
            }
        }
        return false;
    }

    private static String normalizeDevilsWaypointName(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value;
        int legacyTag = WaypointRenderer.findLegacyIconTagIndex(normalized);
        if (legacyTag >= 0) {
            normalized = normalized.substring(0, legacyTag);
        }
        if (normalized.endsWith(DEVILS_MANAGED_SUFFIX)) {
            normalized = normalized.substring(0, normalized.length() - DEVILS_MANAGED_SUFFIX.length());
        }
        if (normalized.endsWith(DEVILS_MANAGED_SUFFIX_LEGACY_VISIBLE)) {
            normalized = normalized.substring(0, normalized.length() - DEVILS_MANAGED_SUFFIX_LEGACY_VISIBLE.length());
        }
        while (!normalized.isEmpty() && normalized.charAt(normalized.length() - 1) == '\u2063') {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private static boolean looksLikePingWaypoint(String value) {
        if (value == null) {
            return false;
        }
        return value.trim().toLowerCase(Locale.ROOT).startsWith("[ping]");
    }

    private static int findLegacyIconTagIndex(String value) {
        if (value == null || value.isBlank()) {
            return -1;
        }
        int hiddenIndex = value.indexOf("\u2063icon=");
        if (hiddenIndex >= 0) {
            return hiddenIndex;
        }
        String lowered = value.toLowerCase(Locale.ROOT);
        int plainIndex = lowered.indexOf(" icon=");
        if (plainIndex >= 0 && lowered.contains("assets/")) {
            return plainIndex;
        }
        return -1;
    }

    private static String parseLegacyManagedIconPath(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        int hiddenIndex = value.indexOf("\u2063icon=");
        if (hiddenIndex >= 0) {
            String parsed = value.substring(hiddenIndex + 6).trim();
            int separator = parsed.indexOf('\u2063');
            if (separator >= 0) {
                parsed = parsed.substring(0, separator).trim();
            }
            return parsed;
        }
        String lowered = value.toLowerCase(Locale.ROOT);
        int plainIndex = lowered.indexOf("icon=");
        if (plainIndex >= 0) {
            String parsed = value.substring(plainIndex + 5).trim();
            int separator = parsed.indexOf(' ');
            if (separator >= 0) {
                parsed = parsed.substring(0, separator).trim();
            }
            return parsed;
        }
        return "";
    }

    private String resolveDevilsManagedIconPath(Waypoint waypoint) {
        if (waypoint == null) {
            return "";
        }
        try {
            Class<?> syncClass = Class.forName("com.devils.addon.util.XaeroSyncWaypoints");
            Method resolver = syncClass.getMethod("resolveManagedWaypointIconPath", Object.class);
            Object resolved = resolver.invoke(null, waypoint.getOriginal());
            return resolved instanceof String ? (String)resolved : "";
        } catch (Throwable ignored) {
            return "";
        }
    }

    private void renderDevilsCustomIcon(MapElementGraphics guiGraphics, String iconPath, int x, int y, int size) {
        if (guiGraphics == null || iconPath == null || iconPath.isBlank()) {
            return;
        }
        boolean drawn = false;
        try {
            Class<?> iconManagerClass = Class.forName("com.devils.addon.util.MapIconManager");
            Method resolveIconSprite = iconManagerClass.getMethod("resolveIconSprite", String.class);
            Object iconSprite = resolveIconSprite.invoke(null, iconPath);
            if (iconSprite == null) {
                return;
            }
            Method idMethod = iconSprite.getClass().getMethod("id");
            Method uMethod = iconSprite.getClass().getMethod("u");
            Method vMethod = iconSprite.getClass().getMethod("v");
            Method regionWidthMethod = iconSprite.getClass().getMethod("regionWidth");
            Method regionHeightMethod = iconSprite.getClass().getMethod("regionHeight");
            Method textureWidthMethod = iconSprite.getClass().getMethod("textureWidth");
            Method textureHeightMethod = iconSprite.getClass().getMethod("textureHeight");
            Identifier id = (Identifier)idMethod.invoke(iconSprite);
            int u = ((Number)uMethod.invoke(iconSprite)).intValue();
            int v = ((Number)vMethod.invoke(iconSprite)).intValue();
            int regionWidth = Math.max(1, ((Number)regionWidthMethod.invoke(iconSprite)).intValue());
            int regionHeight = Math.max(1, ((Number)regionHeightMethod.invoke(iconSprite)).intValue());
            int textureWidth = Math.max(1, ((Number)textureWidthMethod.invoke(iconSprite)).intValue());
            int textureHeight = Math.max(1, ((Number)textureHeightMethod.invoke(iconSprite)).intValue());

            TextureManager textureManager = MinecraftClient.getInstance().getTextureManager();
            AbstractTexture texture = textureManager.getTexture(id);
            if (texture != null) {
                MapRenderHelper.blitIntoMultiTextureRenderer(
                    guiGraphics.pose().peek().getPositionMatrix(),
                    ((WaypointRenderContext)this.context).uniqueTextureUIObjectRenderer,
                    x,
                    y,
                    u,
                    v,
                    size,
                    size,
                    regionWidth,
                    regionHeight,
                    1.0f,
                    1.0f,
                    1.0f,
                    1.0f,
                    textureWidth,
                    textureHeight,
                    texture.getGlTexture()
                );
                drawn = true;
            }
        } catch (Throwable ignored) {
        }
        if (!drawn) {
            this.renderDevilsEmbeddedIcon(guiGraphics, x, y, size);
        }
    }

    private void renderDevilsEmbeddedIcon(MapElementGraphics guiGraphics, int x, int y, int size) {
        if (guiGraphics == null) {
            return;
        }
        try {
            TextureManager textureManager = MinecraftClient.getInstance().getTextureManager();
            AbstractTexture texture = textureManager.getTexture(DEVILS_EMBEDDED_ICON_TEXTURE);
            if (texture == null) {
                return;
            }
            MapRenderHelper.blitIntoMultiTextureRenderer(
                guiGraphics.pose().peek().getPositionMatrix(),
                ((WaypointRenderContext)this.context).uniqueTextureUIObjectRenderer,
                x,
                y,
                DEVILS_EMBEDDED_ICON_U,
                DEVILS_EMBEDDED_ICON_V,
                size,
                size,
                DEVILS_EMBEDDED_ICON_W,
                DEVILS_EMBEDDED_ICON_H,
                1.0f,
                1.0f,
                1.0f,
                1.0f,
                DEVILS_EMBEDDED_ICON_TEXTURE_SIZE,
                DEVILS_EMBEDDED_ICON_TEXTURE_SIZE,
                texture.getGlTexture()
            );
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void preRender(ElementRenderInfo renderInfo, VertexConsumerProvider.Immediate vanillaBufferSource, MultiTextureRenderTypeRendererProvider rendererProvider, boolean shadow) {
        XaeroBufferProvider renderTypeBuffers = XaeroLib.INSTANCE.getClient().getBufferProvider();
        ((WaypointRenderContext)this.context).regularUIObjectConsumer = renderTypeBuffers.getBuffer(CustomRenderTypes.GUI_BILINEAR);
        ((WaypointRenderContext)this.context).textBGConsumer = renderTypeBuffers.getBuffer(CustomRenderTypes.MAP_ELEMENT_TEXT_BG);
        ((WaypointRenderContext)this.context).uniqueTextureUIObjectRenderer = rendererProvider.getRenderer(MultiTextureRenderTypeRendererProvider::defaultTextureBind, CustomRenderTypes.GUI_BILINEAR_PRE);
        ((WaypointRenderContext)this.context).deathpoints = this.minimap.getDeathpoints();
        MinecraftClient mc = MinecraftClient.getInstance();
        ((WaypointRenderContext)this.context).userScale = mc.currentScreen != null && mc.currentScreen instanceof GuiMap ? ((GuiMap)mc.currentScreen).getUserScale() : 1.0;
        ClientConfigManager configManager = WorldMap.INSTANCE.getConfigs().getClientConfigManager();
        ((WaypointRenderContext)this.context).waypointBackgrounds = (Boolean)configManager.getEffective((ConfigOption)WorldMapProfiledConfigOptions.WAYPOINT_BACKGROUNDS);
        ((WaypointRenderContext)this.context).minZoomForLocalWaypoints = (Double)configManager.getEffective((ConfigOption)WorldMapProfiledConfigOptions.MIN_ZOOM_LOCAL_WAYPOINTS);
        SingleConfigManager primaryConfigManager = configManager.getPrimaryConfigManager();
        ((WaypointRenderContext)this.context).showDisabledWaypoints = (Boolean)primaryConfigManager.getEffective((ConfigOption)WorldMapPrimaryClientConfigOptions.DISPLAY_DISABLED_WAYPOINTS);
    }

    @Override
    public void postRender(ElementRenderInfo renderInfo, VertexConsumerProvider.Immediate vanillaBufferSource, MultiTextureRenderTypeRendererProvider rendererProvider, boolean shadow) {
        XaeroBufferProvider renderTypeBuffers = XaeroLib.INSTANCE.getClient().getBufferProvider();
        rendererProvider.draw(((WaypointRenderContext)this.context).uniqueTextureUIObjectRenderer);
        renderTypeBuffers.endBatch();
    }

    @Override
    public int getOrder() {
        return 200;
    }

    @Override
    public boolean shouldBeDimScaled() {
        return false;
    }

    public static final class Builder {
        private SupportXaeroMinimap minimap;
        private WaypointSymbolCreator symbolCreator;

        private Builder() {
        }

        private Builder setDefault() {
            this.setMinimap(null);
            this.setSymbolCreator(null);
            return this;
        }

        public Builder setMinimap(SupportXaeroMinimap minimap) {
            this.minimap = minimap;
            return this;
        }

        public Builder setSymbolCreator(WaypointSymbolCreator symbolCreator) {
            this.symbolCreator = symbolCreator;
            return this;
        }

        public WaypointRenderer build() {
            if (this.minimap == null || this.symbolCreator == null) {
                throw new IllegalStateException();
            }
            return new WaypointRenderer(new WaypointRenderContext(), new WaypointRenderProvider(this.minimap), new WaypointReader(), this.minimap, this.symbolCreator);
        }

        public static Builder begin() {
            return new Builder().setDefault();
        }
    }
}
