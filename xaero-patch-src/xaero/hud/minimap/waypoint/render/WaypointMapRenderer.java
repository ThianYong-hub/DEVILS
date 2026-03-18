/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  net.minecraft.client.MinecraftClient
 *  net.minecraft.client.gl.RenderPipelines
 *  net.minecraft.client.gui.DrawContext
 *  net.minecraft.client.render.VertexConsumer
 *  net.minecraft.client.render.VertexConsumerProvider
 *  net.minecraft.client.render.VertexConsumerProvider$Immediate
 *  net.minecraft.client.resource.language.I18n
 *  net.minecraft.client.util.Window
 *  net.minecraft.client.util.math.MatrixStack
 *  xaero.lib.XaeroLib
 *  xaero.lib.client.config.ClientConfigManager
 *  xaero.lib.client.graphics.XaeroBufferProvider
 *  xaero.lib.common.config.option.ConfigOption
 *  xaero.lib.common.config.option.IndexedConfigOption
 */
package xaero.hud.minimap.waypoint.render;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.resource.language.I18n;
import net.minecraft.client.util.Window;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import xaero.common.HudMod;
import xaero.common.IXaeroMinimap;
import xaero.common.effect.Effects;
import xaero.common.graphics.CustomRenderTypes;
import xaero.common.graphics.renderer.multitexture.MultiTextureRenderTypeRendererProvider;
import xaero.common.minimap.render.MinimapRendererHelper;
import xaero.common.minimap.waypoints.Waypoint;
import xaero.common.minimap.waypoints.WaypointUtil;
import xaero.common.misc.Misc;
import xaero.hud.minimap.BuiltInHudModules;
import xaero.hud.minimap.common.config.option.MinimapProfiledConfigOptions;
import xaero.hud.minimap.config.util.MinimapConfigClientUtils;
import xaero.hud.minimap.element.render.MinimapElementGraphics;
import xaero.hud.minimap.element.render.MinimapElementRenderInfo;
import xaero.hud.minimap.element.render.MinimapElementRenderLocation;
import xaero.hud.minimap.element.render.MinimapElementRenderer;
import xaero.hud.minimap.module.MinimapSession;
import xaero.hud.minimap.waypoint.WaypointPurpose;
import xaero.hud.minimap.waypoint.WaypointSession;
import xaero.hud.minimap.world.MinimapWorld;
import xaero.hud.render.TextureLocations;
import xaero.hud.render.util.RenderBufferUtil;
import xaero.lib.XaeroLib;
import xaero.lib.client.config.ClientConfigManager;
import xaero.lib.client.graphics.XaeroBufferProvider;
import xaero.lib.common.config.option.ConfigOption;
import xaero.lib.common.config.option.IndexedConfigOption;

@SuppressWarnings({"rawtypes", "unchecked", "unused"})
public final class WaypointMapRenderer
extends MinimapElementRenderer<Waypoint, WaypointMapRenderContext> {
    private static final Identifier DEVILS_EMBEDDED_ICON_TEXTURE = Identifier.of("devils-addon", "textures/gui/devils_ping_icon_white.png");
    private static final int DEVILS_EMBEDDED_ICON_TEXTURE_SIZE = 1024;
    private static final int DEVILS_EMBEDDED_ICON_U = 256;
    private static final int DEVILS_EMBEDDED_ICON_V = 167;
    private static final int DEVILS_EMBEDDED_ICON_W = 525;
    private static final int DEVILS_EMBEDDED_ICON_H = 612;
    private static final int DEVILS_MANAGED_ICON_SIZE = 9;
    private MinimapRendererHelper helper;
    private int scale;
    private boolean temporaryWaypointsGlobal;
    private double waypointsDistance;
    private boolean dimensionScaleDistance;
    private int opacity;
    private XaeroBufferProvider minimapBufferSource;
    private VertexConsumer texturedIconConsumer;
    private VertexConsumer waypointBackgroundConsumer;

    private WaypointMapRenderer(WaypointMapRenderReader elementReader, WaypointMapRenderProvider provider, WaypointMapRenderContext context) {
        super(elementReader, provider, context);
    }

    @Override
    public boolean renderElement(Waypoint w, boolean highlighted, boolean outOfBounds, double optionalDepth, float optionalScale, double partialX, double partialY, MinimapElementRenderInfo renderInfo, MinimapElementGraphics guiGraphics, VertexConsumerProvider.Immediate vanillaBufferSource) {
        double waypointPosDivider = renderInfo.backgroundCoordinateScale / ((WaypointMapRenderContext)this.context).dimCoordinateScale;
        double wX = (double)w.getX(waypointPosDivider) + 0.5;
        double wZ = (double)w.getZ(waypointPosDivider) + 0.5;
        double offX = wX - renderInfo.renderPos.x;
        double offZ = wZ - renderInfo.renderPos.z;
        double distance2D = Math.sqrt(offX * offX + offZ * offZ);
        double distanceScale = this.dimensionScaleDistance ? renderInfo.backgroundCoordinateScale : 1.0;
        double scaledDistance2D = distance2D * distanceScale;
        if (!(w.isDestination() || w.getPurpose() == WaypointPurpose.DEATH || w.isGlobal() || w.isTemporary() && this.temporaryWaypointsGlobal || this.waypointsDistance == 0.0 || !(scaledDistance2D > this.waypointsDistance))) {
            return false;
        }
        MatrixStack matrixStack = guiGraphics.pose();
        MinimapElementRenderLocation location = renderInfo.location;
        matrixStack.translate(-1.0, -1.0, optionalDepth);
        if (this.scale <= 0 || location != MinimapElementRenderLocation.OVER_MINIMAP) {
            matrixStack.scale(optionalScale, optionalScale, 1.0f);
        } else {
            matrixStack.scale((float)this.scale, (float)this.scale, 1.0f);
        }
        this.drawIcon(guiGraphics, this.helper, w, 0, 0, this.opacity, this.minimapBufferSource, this.waypointBackgroundConsumer, this.texturedIconConsumer);
        return true;
    }

    @Override
    public void preRender(MinimapElementRenderInfo renderInfo, VertexConsumerProvider.Immediate vanillaBufferSource, MultiTextureRenderTypeRendererProvider multiTextureRenderTypeRenderers) {
        vanillaBufferSource.draw();
        this.minimapBufferSource = XaeroLib.INSTANCE.getClient().getBufferProvider();
        this.waypointBackgroundConsumer = this.minimapBufferSource.getBuffer(CustomRenderTypes.COLORED_WAYPOINTS_BGS);
        this.texturedIconConsumer = this.minimapBufferSource.getBuffer(CustomRenderTypes.GUI_NEAREST);
        this.helper = HudMod.INSTANCE.getMinimap().getMinimapFBORenderer().getHelper();
        MinimapSession session = BuiltInHudModules.MINIMAP.getCurrentSession();
        MinimapWorld currentWorld = session.getWorldManager().getCurrentWorld();
        ((WaypointMapRenderContext)this.context).dimCoordinateScale = session.getDimensionHelper().getDimCoordinateScale(currentWorld);
        ClientConfigManager configManager = HudMod.INSTANCE.getHudConfigs().getClientConfigManager();
        this.scale = (Integer)configManager.getEffective((ConfigOption)MinimapProfiledConfigOptions.WAYPOINT_ICON_SCALE_ON_MINIMAP);
        if (this.scale > 0) {
            this.scale = (int)MinimapConfigClientUtils.getUIScale(configManager, (IndexedConfigOption<Integer>)MinimapProfiledConfigOptions.WAYPOINT_ICON_SCALE_ON_MINIMAP);
        }
        this.temporaryWaypointsGlobal = (Boolean)configManager.getEffective((ConfigOption)MinimapProfiledConfigOptions.TEMPORARY_WAYPOINTS_GLOBAL);
        this.waypointsDistance = ((Integer)configManager.getEffective(MinimapProfiledConfigOptions.WAYPOINT_MAX_DISTANCE)).intValue();
        this.dimensionScaleDistance = (Boolean)configManager.getEffective((ConfigOption)MinimapProfiledConfigOptions.WAYPOINT_MAX_DISTANCE_DIMENSION_SCALE);
        this.opacity = (Integer)configManager.getEffective((ConfigOption)MinimapProfiledConfigOptions.WAYPOINT_OPACITY_ON_MINIMAP);
    }

    @Override
    public void postRender(MinimapElementRenderInfo renderInfo, VertexConsumerProvider.Immediate vanillaBufferSource, MultiTextureRenderTypeRendererProvider multiTextureRenderTypeRenderers) {
        this.minimapBufferSource.endBatch();
        this.waypointBackgroundConsumer = null;
    }

    public void drawIcon(MinimapElementGraphics guiGraphics, MinimapRendererHelper rendererHelper, Waypoint w, int drawX, int drawY, int opacity, XaeroBufferProvider renderTypeBuffer, VertexConsumer waypointBackgroundConsumer, VertexConsumer texturedIconConsumer) {
        int color = w.getWaypointColor().getHex();
        int r = color >> 16 & 0xFF;
        int g = color >> 8 & 0xFF;
        int b = color & 0xFF;
        float a = (float)opacity / 100.0f;
        int initialsWidth = w.getPurpose() == WaypointPurpose.DEATH ? 7 : MinecraftClient.getInstance().textRenderer.getWidth(w.getInitials());
        int addedFrame = WaypointUtil.getAddedMinimapIconFrame(initialsWidth);
        int rectX1 = drawX - 4 - addedFrame;
        int rectY1 = drawY - 4;
        int rectX2 = drawX + 5 + addedFrame;
        int rectY2 = drawY + 5;
        this.drawIcon(guiGraphics, w, drawX, drawY, rectX1, rectY1, rectX2, rectY2, r, g, b, a, initialsWidth, renderTypeBuffer, waypointBackgroundConsumer, texturedIconConsumer);
    }

    public void drawIconGUI(DrawContext guiGraphics, Waypoint w, int drawX, int drawY, int opacity) {
        int color = w.getWaypointColor().getHex();
        int r = color >> 16 & 0xFF;
        int g = color >> 8 & 0xFF;
        int b = color & 0xFF;
        float a = (float)opacity / 100.0f;
        int initialsWidth = w.getPurpose() == WaypointPurpose.DEATH ? 7 : MinecraftClient.getInstance().textRenderer.getWidth(w.getInitials());
        int addedFrame = WaypointUtil.getAddedMinimapIconFrame(initialsWidth);
        int rectX1 = drawX - 4 - addedFrame;
        int rectY1 = drawY - 4;
        int rectX2 = drawX + 5 + addedFrame;
        int rectY2 = drawY + 5;
        this.drawIconGUI(guiGraphics, w, drawX, drawY, rectX1, rectY1, rectX2, rectY2, r, g, b, a, initialsWidth);
    }

    private void drawIcon(MinimapElementGraphics guiGraphics, Waypoint w, int drawX, int drawY, int rectX1, int rectY1, int rectX2, int rectY2, int r, int g, int b, float a, int initialsWidth, XaeroBufferProvider renderTypeBuffer, VertexConsumer waypointBackgroundConsumer, VertexConsumer texturedIconConsumer) {
        MatrixStack matrixStack = guiGraphics.pose();
        RenderBufferUtil.addColoredRect(matrixStack.peek().getPositionMatrix(), waypointBackgroundConsumer, rectX1, rectY1, rectX2 - rectX1, rectY2 - rectY1, (float)r / 255.0f, (float)g / 255.0f, (float)b / 255.0f, a);
        if (w.getPurpose() == WaypointPurpose.DEATH) {
            RenderBufferUtil.addTexturedColoredRect(matrixStack.peek().getPositionMatrix(), texturedIconConsumer, rectX1 + 1, rectY1 + 1, 0, 87, 9, 9, 9, -9, 0.2431f, 0.2431f, 0.2431f, 1.0f, 256.0f);
            RenderBufferUtil.addTexturedColoredRect(matrixStack.peek().getPositionMatrix(), texturedIconConsumer, rectX1, rectY1, 0, 87, 9, 9, 9, -9, 0.9882f, 0.9882f, 0.9882f, 1.0f, 256.0f);
            return;
        }
        String managedIconPath = this.resolveManagedIconPath(w);
        if (!managedIconPath.isBlank()) {
            int iconSize = DEVILS_MANAGED_ICON_SIZE;
            int iconX = drawX - 4;
            int iconY = drawY - 4;
            if (this.minimapBufferSource != null) {
                this.minimapBufferSource.endBatch();
                this.waypointBackgroundConsumer = this.minimapBufferSource.getBuffer(CustomRenderTypes.COLORED_WAYPOINTS_BGS);
                this.texturedIconConsumer = this.minimapBufferSource.getBuffer(CustomRenderTypes.GUI_NEAREST);
            }
            this.drawManagedIconBackdrop(guiGraphics, iconX, iconY, iconSize, a);
            if (this.drawManagedIcon(guiGraphics, managedIconPath, iconX, iconY, iconSize)) {
                return;
            }
        }
        Misc.drawNormalText(matrixStack, w.getInitials(), (float)(drawX + 1 - initialsWidth / 2), (float)(drawY - 3), -1, true, (VertexConsumerProvider)renderTypeBuffer);
    }

    private void drawIconGUI(DrawContext guiGraphics, Waypoint w, int drawX, int drawY, int rectX1, int rectY1, int rectX2, int rectY2, int r, int g, int b, float a, int initialsWidth) {
        int aByte = (int)(a * 255.0f);
        int color = aByte << 24 | r << 16 | g << 8 | b;
        guiGraphics.fill(rectX1, rectY1, rectX2, rectY2, color);
        if (w.getPurpose() == WaypointPurpose.DEATH) {
            int shadowColor = -12698050;
            int skullColor = -197380;
            guiGraphics.drawTexture(RenderPipelines.GUI_TEXTURED, TextureLocations.GUI_TEXTURES, rectX1 + 1, rectY1 + 1, 0.0f, 78.0f, 9, 9, 9, 9, 256, 256, shadowColor);
            guiGraphics.drawTexture(RenderPipelines.GUI_TEXTURED, TextureLocations.GUI_TEXTURES, rectX1, rectY1, 0.0f, 78.0f, 9, 9, 9, 9, 256, 256, skullColor);
            return;
        }
        String managedIconPath = this.resolveManagedIconPath(w);
        if (!managedIconPath.isBlank()) {
            int iconSize = DEVILS_MANAGED_ICON_SIZE;
            int iconX = drawX - 4;
            int iconY = drawY - 4;
            this.drawManagedIconBackdrop(guiGraphics, iconX, iconY, iconSize, a);
            if (this.drawManagedIconGUI(guiGraphics, managedIconPath, iconX, iconY, iconSize)) {
                return;
            }
        }
        guiGraphics.drawText(MinecraftClient.getInstance().textRenderer, w.getInitials(), drawX + 1 - initialsWidth / 2, drawY - 3, -1, true);
    }

    private boolean drawManagedIcon(MinimapElementGraphics guiGraphics, String iconPath, int x, int y, int size) {
        try {
            Class<?> iconManagerClass = Class.forName("com.example.addon.util.MapIconManager");
            Object iconSprite = iconManagerClass.getMethod("resolveIconSprite", String.class).invoke(null, iconPath);
            if (iconSprite != null) {
                Identifier id = (Identifier)iconSprite.getClass().getMethod("id").invoke(iconSprite);
                int u = ((Number)iconSprite.getClass().getMethod("u").invoke(iconSprite)).intValue();
                int v = ((Number)iconSprite.getClass().getMethod("v").invoke(iconSprite)).intValue();
                int regionWidth = Math.max(1, ((Number)iconSprite.getClass().getMethod("regionWidth").invoke(iconSprite)).intValue());
                int regionHeight = Math.max(1, ((Number)iconSprite.getClass().getMethod("regionHeight").invoke(iconSprite)).intValue());
                int textureWidth = Math.max(1, ((Number)iconSprite.getClass().getMethod("textureWidth").invoke(iconSprite)).intValue());
                int textureHeight = Math.max(1, ((Number)iconSprite.getClass().getMethod("textureHeight").invoke(iconSprite)).intValue());
                int textureSize = Math.max(textureWidth, textureHeight);
                guiGraphics.blit(id, x, y, u, v, size, size, u + regionWidth, v + regionHeight, textureSize, RenderPipelines.GUI_TEXTURED);
                return true;
            }
        } catch (Throwable ignored) {
        }
        return this.drawEmbeddedManagedIcon(guiGraphics, x, y, size);
    }

    private boolean drawManagedIconGUI(DrawContext guiGraphics, String managedIconPath, int x, int y, int size) {
        try {
            if (managedIconPath.isBlank()) {
                return false;
            }
            Class<?> iconManagerClass = Class.forName("com.example.addon.util.MapIconManager");
            Object result = iconManagerClass
                .getMethod("drawCustomIcon", DrawContext.class, String.class, Integer.TYPE, Integer.TYPE, Integer.TYPE, Integer.TYPE)
                .invoke(null, guiGraphics, managedIconPath, x, y, size, -1);
            return result instanceof Boolean && (Boolean)result;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void drawManagedIconBackdrop(MinimapElementGraphics guiGraphics, int x, int y, int size, float alpha) {
        if (guiGraphics == null) return;
        int backdropAlpha = Math.max(176, Math.min(255, (int)(alpha * 255.0f)));
        int backdropColor = (backdropAlpha << 24);
        guiGraphics.fill(x, y, x + size, y + size, backdropColor);
    }

    private void drawManagedIconBackdrop(DrawContext guiGraphics, int x, int y, int size, float alpha) {
        if (guiGraphics == null) return;
        int backdropAlpha = Math.max(176, Math.min(255, (int)(alpha * 255.0f)));
        int backdropColor = (backdropAlpha << 24);
        guiGraphics.fill(x, y, x + size, y + size, backdropColor);
    }

    private boolean drawEmbeddedManagedIcon(MinimapElementGraphics guiGraphics, int x, int y, int size) {
        try {
            guiGraphics.blit(
                DEVILS_EMBEDDED_ICON_TEXTURE,
                x,
                y,
                DEVILS_EMBEDDED_ICON_U,
                DEVILS_EMBEDDED_ICON_V,
                size,
                size,
                DEVILS_EMBEDDED_ICON_U + DEVILS_EMBEDDED_ICON_W,
                DEVILS_EMBEDDED_ICON_V + DEVILS_EMBEDDED_ICON_H,
                DEVILS_EMBEDDED_ICON_TEXTURE_SIZE,
                RenderPipelines.GUI_TEXTURED
            );
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private String resolveManagedIconPath(Waypoint waypoint) {
        try {
            Class<?> syncClass = Class.forName("com.example.addon.util.XaeroSyncWaypoints");
            Object resolved = syncClass.getMethod("resolveManagedWaypointIconPath", Object.class).invoke(null, waypoint);
            return resolved instanceof String ? ((String)resolved).trim() : "";
        } catch (Throwable ignored) {
            return "";
        }
    }

    public void drawSetChange(MinimapSession session, DrawContext guiGraphics, Window res) {
        MinimapWorld minimapWorld = session.getWorldManager().getCurrentWorld();
        if (minimapWorld == null) {
            return;
        }
        WaypointSession waypointSession = session.getWaypointSession();
        if (waypointSession.getSetChangedTime() == 0L) {
            return;
        }
        int passed = (int)(System.currentTimeMillis() - waypointSession.getSetChangedTime());
        if (passed >= 1500) {
            waypointSession.setSetChangedTime(0L);
            return;
        }
        int fadeTime = 300;
        boolean fading = passed > 1500 - fadeTime;
        float fadeFactor = fading ? (float)(1500 - passed) / (float)fadeTime : 1.0f;
        int alpha = 3 + (int)(252.0f * fadeFactor);
        int c = 0xFFFFFF | alpha << 24;
        guiGraphics.drawCenteredTextWithShadow(MinecraftClient.getInstance().textRenderer, I18n.translate((String)minimapWorld.getCurrentWaypointSet().getName(), (Object[])new Object[0]), res.getScaledWidth() / 2, res.getScaledHeight() / 2 + 50, c);
    }

    @Override
    public boolean shouldRender(MinimapElementRenderLocation location) {
        ClientConfigManager configManager = HudMod.INSTANCE.getHudConfigs().getClientConfigManager();
        if (!(location != MinimapElementRenderLocation.OVER_MINIMAP && location != MinimapElementRenderLocation.IN_MINIMAP || ((Boolean)configManager.getEffective((ConfigOption)MinimapProfiledConfigOptions.WAYPOINTS_ON_MINIMAP)).booleanValue())) {
            return false;
        }
        return !Misc.hasEffect(Effects.NO_WAYPOINTS) && !Misc.hasEffect(Effects.NO_WAYPOINTS_HARMFUL);
    }

    @Override
    public int getOrder() {
        return 100;
    }

    public static final class Builder {
        private WaypointDeleter waypointDeleter;
        private final IXaeroMinimap modMain;

        private Builder(IXaeroMinimap modMain) {
            this.modMain = modMain;
        }

        private Builder setDefault() {
            this.setWaypointDeleter(null);
            return this;
        }

        public Builder setWaypointDeleter(WaypointDeleter waypointDeleter) {
            this.waypointDeleter = waypointDeleter;
            return this;
        }

        public WaypointMapRenderer build() {
            if (this.waypointDeleter == null) {
                throw new IllegalStateException();
            }
            WaypointMapRenderContext context = new WaypointMapRenderContext();
            return new WaypointMapRenderer(new WaypointMapRenderReader(), new WaypointMapRenderProvider(), context);
        }

        public static Builder begin(IXaeroMinimap modMain) {
            return new Builder(modMain).setDefault();
        }
    }
}
