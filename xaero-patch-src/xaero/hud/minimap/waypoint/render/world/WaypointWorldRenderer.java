/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  net.minecraft.client.MinecraftClient
 *  net.minecraft.client.font.TextRenderer
 *  net.minecraft.client.render.Camera
 *  net.minecraft.client.render.VertexConsumer
 *  net.minecraft.client.render.VertexConsumerProvider
 *  net.minecraft.client.render.VertexConsumerProvider$Immediate
 *  net.minecraft.client.util.math.MatrixStack
 *  net.minecraft.entity.player.PlayerEntity
 *  net.minecraft.util.math.MathHelper
 *  org.joml.Matrix4f
 *  org.joml.Matrix4fc
 *  org.joml.Vector3f
 *  xaero.lib.XaeroLib
 *  xaero.lib.client.config.ClientConfigManager
 *  xaero.lib.client.graphics.XaeroBufferProvider
 *  xaero.lib.common.config.option.ConfigOption
 *  xaero.lib.common.config.option.IndexedConfigOption
 */
package xaero.hud.minimap.waypoint.render.world;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.Identifier;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import xaero.common.HudMod;
import xaero.common.effect.Effects;
import xaero.common.graphics.CustomRenderTypes;
import xaero.common.graphics.renderer.multitexture.MultiTextureRenderTypeRendererProvider;
import xaero.common.gui.GuiMisc;
import xaero.common.minimap.render.MinimapRendererHelper;
import xaero.common.minimap.waypoints.Waypoint;
import xaero.common.minimap.waypoints.WaypointUtil;
import xaero.common.misc.Misc;
import xaero.common.misc.OptimizedMath;
import xaero.hud.minimap.BuiltInHudModules;
import xaero.hud.minimap.common.config.option.MinimapProfiledConfigOptions;
import xaero.hud.minimap.config.util.MinimapConfigClientUtils;
import xaero.hud.minimap.element.render.MinimapElementGraphics;
import xaero.hud.minimap.element.render.MinimapElementReader;
import xaero.hud.minimap.element.render.MinimapElementRenderInfo;
import xaero.hud.minimap.element.render.MinimapElementRenderLocation;
import xaero.hud.minimap.element.render.MinimapElementRenderer;
import xaero.hud.minimap.module.MinimapSession;
import xaero.hud.minimap.waypoint.WaypointPurpose;
import xaero.hud.minimap.waypoint.render.world.WaypointWorldRenderContext;
import xaero.hud.minimap.waypoint.render.world.WaypointWorldRenderProvider;
import xaero.hud.minimap.waypoint.render.world.WaypointWorldRenderReader;
import xaero.hud.minimap.world.MinimapWorld;
import xaero.hud.minimap.world.MinimapWorldManager;
import xaero.hud.render.util.RenderBufferUtil;
import xaero.lib.XaeroLib;
import xaero.lib.client.config.ClientConfigManager;
import xaero.lib.client.graphics.XaeroBufferProvider;
import xaero.lib.common.config.option.ConfigOption;
import xaero.lib.common.config.option.IndexedConfigOption;

public final class WaypointWorldRenderer
extends MinimapElementRenderer<Waypoint, WaypointWorldRenderContext> {
    private static final Identifier DEVILS_EMBEDDED_ICON_TEXTURE = Identifier.of("devils-addon", "textures/gui/devils_ping_icon_white.png");
    private static final int DEVILS_EMBEDDED_ICON_TEXTURE_SIZE = 1024;
    private static final int DEVILS_EMBEDDED_ICON_U = 256;
    private static final int DEVILS_EMBEDDED_ICON_V = 167;
    private static final int DEVILS_EMBEDDED_ICON_W = 525;
    private static final int DEVILS_EMBEDDED_ICON_H = 612;
    private static final int DEVILS_MANAGED_ICON_SIZE = 9;
    private static final int DEVILS_MANAGED_ICON_X = -5;
    private static final int DEVILS_MANAGED_ICON_Y = -9;
    private Vector3f lookVector;
    private boolean temporaryWaypointsGlobal;
    private double waypointsDistance;
    private double waypointsDistanceMin;
    private int distanceSetting;
    private boolean displayShortDistances;
    private boolean dimensionScaleDistance;
    private double clampDepth;
    private int lookingAtAngle;
    private int lookingAtAngleVertical;
    private boolean keepWaypointNames;
    private int autoConvertWaypointDistanceToKmThreshold;
    private int waypointDistancePrecision;
    private float iconScale;
    private int distanceTextScale;
    private int nameScale;
    private int opacity;
    private float cameraAngleYaw;
    private float cameraAnglePitch;
    private String subWorldName;
    private MinimapRendererHelper helper;
    private TextRenderer fontRenderer;
    private XaeroBufferProvider minimapBufferSource;
    private VertexConsumer texturedIconConsumer;
    private VertexConsumer waypointBackgroundConsumer;
    private MinimapElementGraphics currentGuiGraphics;

    private WaypointWorldRenderer(MinimapElementReader<Waypoint, WaypointWorldRenderContext> elementReader, WaypointWorldRenderProvider provider, WaypointWorldRenderContext context) {
        super(elementReader, provider, context);
    }

    @Override
    public boolean renderElement(Waypoint w, boolean highlighted, boolean outOfBounds, double optionalDepth, float optionalScale, double partialX, double partialY, MinimapElementRenderInfo renderInfo, MinimapElementGraphics guiGraphics, VertexConsumerProvider.Immediate vanillaBufferSource) {
        this.currentGuiGraphics = guiGraphics;
        double zFromEntity;
        double distanceFromEntity;
        double waypointPosDivider = renderInfo.backgroundCoordinateScale / ((WaypointWorldRenderContext)this.context).dimCoordinateScale;
        double wX = (double)w.getX(waypointPosDivider) + 0.5;
        double wZ = (double)w.getZ(waypointPosDivider) + 0.5;
        double offX = wX - renderInfo.renderPos.x;
        double offY = (double)w.getY() + 1.0 - renderInfo.renderPos.y;
        if (!w.isYIncluded()) {
            offY = renderInfo.renderEntityPos.y + 1.0 - renderInfo.renderPos.y;
        }
        double offZ = wZ - renderInfo.renderPos.z;
        double distance2D = Math.sqrt(offX * offX + offZ * offZ);
        if (this.waypointsDistanceMin != 0.0 && distance2D < this.waypointsDistanceMin) {
            return false;
        }
        double distanceScale = this.dimensionScaleDistance ? renderInfo.backgroundCoordinateScale : 1.0;
        double scaledDistance2D = distance2D * distanceScale;
        if (!(w.isDestination() || w.getPurpose() == WaypointPurpose.DEATH || w.isGlobal() || w.isTemporary() && this.temporaryWaypointsGlobal || this.waypointsDistance == 0.0 || !(scaledDistance2D > this.waypointsDistance))) {
            return false;
        }
        Vector3f lookVector = this.lookVector;
        double depth = offX * (double)lookVector.x() + offY * (double)lookVector.y() + offZ * (double)lookVector.z();
        double xFromEntity = wX - renderInfo.renderEntityPos.x;
        double yFromEntity = (double)w.getY() - renderInfo.renderEntityPos.y;
        if (!w.isYIncluded()) {
            yFromEntity = 0.0;
        }
        boolean usingNearbyDisplay = (distanceFromEntity = Math.sqrt(xFromEntity * xFromEntity + yFromEntity * yFromEntity + (zFromEntity = wZ - renderInfo.renderEntityPos.z) * zFromEntity)) <= 20.0 && !this.displayShortDistances;
        boolean displayingDistance = !usingNearbyDisplay && highlighted;
        String distanceText = displayingDistance ? this.getDistanceText(distanceFromEntity) : null;
        String name = null;
        if (usingNearbyDisplay || displayingDistance && this.keepWaypointNames || !displayingDistance && w.getPurpose() == WaypointPurpose.DEATH) {
            name = w.getLocalizedName();
        }
        TextRenderer fontRenderer = this.fontRenderer;
        XaeroBufferProvider bufferSource = this.minimapBufferSource;
        float iconScale = this.iconScale;
        int nameScale = this.nameScale;
        int halfIconPixel = (int)iconScale / 2;
        MatrixStack matrixStack = guiGraphics.pose();
        if (renderInfo.location == MinimapElementRenderLocation.IN_WORLD && depth < this.clampDepth) {
            float scale = (float)(this.clampDepth / depth);
            matrixStack.scale(scale, scale, 1.0f);
        }
        matrixStack.translate((double)halfIconPixel, 0.0, optionalDepth);
        this.renderIconWithLabels(w, highlighted, name, distanceText, this.subWorldName, iconScale, nameScale, this.distanceTextScale, fontRenderer, halfIconPixel, matrixStack, bufferSource);
        return true;
    }

    @Override
    public void preRender(MinimapElementRenderInfo renderInfo, VertexConsumerProvider.Immediate vanillaBufferSource, MultiTextureRenderTypeRendererProvider multiTextureRenderTypeRenderers) {
        MinecraftClient mc = MinecraftClient.getInstance();
        Camera activeRender = mc.gameRenderer.getCamera();
        MinimapSession session = BuiltInHudModules.MINIMAP.getCurrentSession();
        MinimapWorldManager manager = session.getWorldManager();
        MinimapWorld currentWorld = manager.getCurrentWorld();
        ClientConfigManager configManager = HudMod.INSTANCE.getHudConfigs().getClientConfigManager();
        this.lookVector = activeRender.getHorizontalPlane().get(new Vector3f());
        this.cameraAngleYaw = activeRender.getYaw();
        this.cameraAnglePitch = activeRender.getPitch();
        double fov = ((Integer)mc.options.getFov().getValue()).doubleValue();
        int screenWidth = mc.getWindow().getFramebufferWidth();
        int screenHeight = mc.getWindow().getFramebufferHeight();
        this.subWorldName = null;
        if (currentWorld != null && manager.getAutoWorld() != currentWorld) {
            this.subWorldName = "(" + currentWorld.getContainer().getSubName() + ")";
        }
        ((WaypointWorldRenderContext)this.context).dimCoordinateScale = session.getDimensionHelper().getDimCoordinateScale(manager.getCurrentWorld());
        ((WaypointWorldRenderContext)this.context).renderEntityPos = renderInfo.renderEntityPos;
        int displayMultipleWaypointInfo = (Integer)configManager.getEffective((ConfigOption)MinimapProfiledConfigOptions.MULTIPLE_WAYPOINTS_INFO);
        ((WaypointWorldRenderContext)this.context).onlyMainInfo = displayMultipleWaypointInfo == 0 || displayMultipleWaypointInfo == 1 && !renderInfo.renderEntity.isSneaking();
        this.temporaryWaypointsGlobal = (Boolean)configManager.getEffective((ConfigOption)MinimapProfiledConfigOptions.TEMPORARY_WAYPOINTS_GLOBAL);
        this.waypointsDistance = ((Integer)configManager.getEffective(MinimapProfiledConfigOptions.WAYPOINT_MAX_DISTANCE)).intValue();
        this.waypointsDistanceMin = (Double)configManager.getEffective((ConfigOption)MinimapProfiledConfigOptions.WAYPOINT_MIN_DISTANCE_IN_WORLD);
        this.distanceSetting = (Integer)configManager.getEffective((ConfigOption)MinimapProfiledConfigOptions.WAYPOINT_DISTANCE_IN_WORLD);
        this.displayShortDistances = (Boolean)configManager.getEffective((ConfigOption)MinimapProfiledConfigOptions.WAYPOINT_SHORT_DISTANCE_IN_WORLD);
        this.dimensionScaleDistance = (Boolean)configManager.getEffective((ConfigOption)MinimapProfiledConfigOptions.WAYPOINT_MAX_DISTANCE_DIMENSION_SCALE);
        this.clampDepth = MinimapConfigClientUtils.getWaypointsClampDepth(configManager, fov, screenHeight);
        int horizontalPointingAngleConfig = (Integer)configManager.getEffective((ConfigOption)MinimapProfiledConfigOptions.WAYPOINT_HORIZONTAL_POINTING_ANGLE);
        this.lookingAtAngle = MathHelper.clamp((int)horizontalPointingAngleConfig, (int)0, (int)180);
        int verticalPointingAngleConfig = (Integer)configManager.getEffective((ConfigOption)MinimapProfiledConfigOptions.WAYPOINT_VERTICAL_POINTING_ANGLE);
        this.lookingAtAngleVertical = MathHelper.clamp((int)verticalPointingAngleConfig, (int)0, (int)180);
        this.keepWaypointNames = (Boolean)configManager.getEffective((ConfigOption)MinimapProfiledConfigOptions.WAYPOINT_NAME_IN_WORLD);
        this.autoConvertWaypointDistanceToKmThreshold = (Integer)configManager.getEffective(MinimapProfiledConfigOptions.WAYPOINT_CONVERT_DISTANCE_TO_KM_AT);
        this.waypointDistancePrecision = (Integer)configManager.getEffective((ConfigOption)MinimapProfiledConfigOptions.WAYPOINT_DISTANCE_PRECISION);
        this.iconScale = MinimapConfigClientUtils.getUIScale(configManager, (IndexedConfigOption<Integer>)MinimapProfiledConfigOptions.WAYPOINT_ICON_SCALE_IN_WORLD);
        this.distanceTextScale = (int)Math.ceil(MinimapConfigClientUtils.getUIScale(configManager, (IndexedConfigOption<Integer>)MinimapProfiledConfigOptions.WAYPOINT_DISTANCE_SCALE_IN_WORLD));
        this.nameScale = (int)MinimapConfigClientUtils.getUIScale(configManager, (IndexedConfigOption<Integer>)MinimapProfiledConfigOptions.WAYPOINT_NAME_SCALE_IN_WORLD, 0.5);
        this.opacity = (Integer)configManager.getEffective((ConfigOption)MinimapProfiledConfigOptions.WAYPOINT_OPACITY_IN_WORLD);
        ((WaypointWorldRenderContext)this.context).interactionBoxTop = this.distanceSetting == 0 || this.lookingAtAngleVertical == 0 ? 0 : (this.distanceSetting == 2 || this.lookingAtAngleVertical >= 90 ? -screenHeight : -OptimizedMath.myFloor((double)(screenHeight / 2) * Math.tan(Math.toRadians(this.lookingAtAngleVertical)) / Math.tan(Math.toRadians(fov / 2.0))));
        double horizontalTan = Math.tan(Math.toRadians(fov / 2.0)) * (double)screenWidth / (double)screenHeight;
        int n = this.distanceSetting == 0 || this.lookingAtAngle == 0 ? 0 : (((WaypointWorldRenderContext)this.context).interactionBoxLeft = this.distanceSetting == 2 || this.lookingAtAngle >= 90 ? -screenWidth : -OptimizedMath.myFloor((double)(screenWidth / 2) * Math.tan(Math.toRadians(this.lookingAtAngle)) / horizontalTan));
        if (MinecraftClient.getInstance().forcesUnicodeFont()) {
            this.iconScale = (float)(Math.ceil(this.iconScale / 2.0f) * 2.0);
            this.distanceTextScale = (this.distanceTextScale + 1) / 2 * 2;
            this.nameScale = (this.nameScale + 1) / 2 * 2;
        }
        this.helper = HudMod.INSTANCE.getMinimap().getMinimapFBORenderer().getHelper();
        this.fontRenderer = mc.textRenderer;
        vanillaBufferSource.draw();
        this.minimapBufferSource = XaeroLib.INSTANCE.getClient().getBufferProvider();
        this.waypointBackgroundConsumer = this.minimapBufferSource.getBuffer(CustomRenderTypes.COLORED_WAYPOINTS_BGS);
        this.texturedIconConsumer = this.minimapBufferSource.getBuffer(CustomRenderTypes.GUI_NEAREST);
    }

    @Override
    public void postRender(MinimapElementRenderInfo renderInfo, VertexConsumerProvider.Immediate vanillaBufferSource, MultiTextureRenderTypeRendererProvider multiTextureRenderTypeRenderers) {
        this.minimapBufferSource.endBatch();
        ((WaypointWorldRenderContext)this.context).onlyMainInfo = false;
        ((WaypointWorldRenderContext)this.context).renderEntityPos = null;
        this.fontRenderer = null;
        this.minimapBufferSource = null;
        this.waypointBackgroundConsumer = null;
        this.texturedIconConsumer = null;
        this.currentGuiGraphics = null;
    }

    private void renderIconWithLabels(Waypoint w, boolean highlit, String name, String distanceText, String subWorldName, float iconScale, int nameScale, int distanceTextScale, TextRenderer fontRenderer, int halfIconPixel, MatrixStack matrixStack, XaeroBufferProvider bufferSource) {
        String managedIconPath = this.resolveManagedIconPath(w);
        boolean managedIconPresent = this.currentGuiGraphics != null && !managedIconPath.isBlank() && w.getPurpose() != WaypointPurpose.DEATH;
        matrixStack.scale(iconScale, iconScale, 1.0f);
        this.renderIcon(w, highlit, matrixStack, fontRenderer, bufferSource);
        matrixStack.scale(1.0f / iconScale, 1.0f / iconScale, 1.0f);
        if (managedIconPresent) {
            if (this.minimapBufferSource != null) {
                this.minimapBufferSource.endBatch();
                this.waypointBackgroundConsumer = this.minimapBufferSource.getBuffer(CustomRenderTypes.COLORED_WAYPOINTS_BGS);
                this.texturedIconConsumer = this.minimapBufferSource.getBuffer(CustomRenderTypes.GUI_NEAREST);
            }
            matrixStack.push();
            matrixStack.scale(iconScale, iconScale, 1.0f);
            this.drawManagedIconBackdrop(this.currentGuiGraphics, DEVILS_MANAGED_ICON_X, DEVILS_MANAGED_ICON_Y, DEVILS_MANAGED_ICON_SIZE, 1.0f);
            this.drawManagedIcon(this.currentGuiGraphics, managedIconPath, DEVILS_MANAGED_ICON_X, DEVILS_MANAGED_ICON_Y, DEVILS_MANAGED_ICON_SIZE);
            matrixStack.pop();
        }
        matrixStack.translate((float)(-halfIconPixel), 0.0f, 0.0f);
        matrixStack.translate(0.0f, 2.0f, 0.0f);
        float labelAlpha = 0.3529412f;
        if ((distanceText != null || name != null) && subWorldName != null) {
            this.renderWaypointLabel(subWorldName, matrixStack, this.helper, fontRenderer, nameScale, labelAlpha);
            matrixStack.translate(0.0f, 2.0f, 0.0f);
        }
        if (name != null) {
            this.renderWaypointLabel(name, matrixStack, this.helper, fontRenderer, nameScale, labelAlpha);
        }
        matrixStack.translate(0.0f, 2.0f, 0.0f);
        if (distanceText != null) {
            this.renderWaypointLabel(distanceText, matrixStack, this.helper, fontRenderer, distanceTextScale, labelAlpha);
        }
    }

    private void renderIcon(Waypoint w, boolean highlit, MatrixStack matrixStack, TextRenderer fontRenderer, XaeroBufferProvider bufferSource) {
        int color = w.getWaypointColor().getHex();
        float red = (float)(color >> 16 & 0xFF) / 255.0f;
        float green = (float)(color >> 8 & 0xFF) / 255.0f;
        float blue = (float)(color & 0xFF) / 255.0f;
        float alpha = 0.52274513f * (float)this.opacity / 100.0f;
        if (highlit && ((WaypointWorldRenderContext)this.context).onlyMainInfo) {
            alpha = Math.min(1.0f, alpha * 1.5f);
        }
        String managedIconPath = this.resolveManagedIconPath(w);
        boolean managedIconPresent = !managedIconPath.isBlank();
        int initialsWidth = w.getPurpose() == WaypointPurpose.DEATH ? 7 : fontRenderer.getWidth(w.getInitials());
        int addedFrame = managedIconPresent ? 0 : WaypointUtil.getAddedMinimapIconFrame(initialsWidth);
        this.renderColorBackground(matrixStack, addedFrame, red, green, blue, alpha, this.waypointBackgroundConsumer);
        if (w.getPurpose() == WaypointPurpose.DEATH) {
            this.renderTexturedIcon(matrixStack, addedFrame, 0, 78, 0.9882f, 0.9882f, 0.9882f, 1.0f, this.texturedIconConsumer);
            return;
        }
        if (managedIconPresent) {
            return;
        }
        Misc.drawNormalText(matrixStack, w.getInitials(), (float)(-initialsWidth / 2), -8.0f, -1, false, (VertexConsumerProvider)bufferSource);
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
                guiGraphics.blit(id, x, y, u, v, size, size, u + regionWidth, v + regionHeight, textureSize, net.minecraft.client.gl.RenderPipelines.GUI_TEXTURED);
                return true;
            }
        } catch (Throwable ignored) {
        }
        return this.drawEmbeddedManagedIcon(guiGraphics, x, y, size);
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
                net.minecraft.client.gl.RenderPipelines.GUI_TEXTURED
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

    private void drawManagedIconBackdrop(MinimapElementGraphics guiGraphics, int x, int y, int size, float alpha) {
        if (guiGraphics == null) return;
        int backdropAlpha = Math.max(176, Math.min(255, (int)(alpha * 255.0f)));
        int backdropColor = backdropAlpha << 24;
        guiGraphics.fill(x, y, x + size, y + size, backdropColor);
    }

    private void renderColorBackground(MatrixStack matrixStack, int addedFrame, float r, float g, float b, float a, VertexConsumer waypointBackgroundConsumer) {
        Matrix4f matrix = matrixStack.peek().getPositionMatrix();
        waypointBackgroundConsumer.vertex((Matrix4fc)matrix, (float)(-5 - addedFrame), -9.0f, 0.0f).color(r, g, b, a);
        waypointBackgroundConsumer.vertex((Matrix4fc)matrix, (float)(-5 - addedFrame), 0.0f, 0.0f).color(r, g, b, a);
        waypointBackgroundConsumer.vertex((Matrix4fc)matrix, (float)(4 + addedFrame), 0.0f, 0.0f).color(r, g, b, a);
        waypointBackgroundConsumer.vertex((Matrix4fc)matrix, (float)(4 + addedFrame), -9.0f, 0.0f).color(r, g, b, a);
    }

    private void renderTexturedIcon(MatrixStack matrixStack, int addedFrame, int textureX, int textureY, float r, float g, float b, float a, VertexConsumer vertexBuffer) {
        float f = 0.00390625f;
        float f1 = 0.00390625f;
        Matrix4f matrix = matrixStack.peek().getPositionMatrix();
        vertexBuffer.vertex((Matrix4fc)matrix, (float)(-5 - addedFrame), (float)(-9 - addedFrame), 0.0f).color(r, g, b, a).texture((float)textureX * f, (float)textureY * f1);
        vertexBuffer.vertex((Matrix4fc)matrix, (float)(-5 - addedFrame), (float)addedFrame, 0.0f).color(r, g, b, a).texture((float)textureX * f, (float)(textureY + 9 + addedFrame * 2) * f1);
        vertexBuffer.vertex((Matrix4fc)matrix, (float)(4 + addedFrame), (float)addedFrame, 0.0f).color(r, g, b, a).texture((float)(textureX + 9 + addedFrame * 2) * f, (float)(textureY + 9 + addedFrame * 2) * f1);
        vertexBuffer.vertex((Matrix4fc)matrix, (float)(4 + addedFrame), (float)(-9 - addedFrame), 0.0f).color(r, g, b, a).texture((float)(textureX + 9 + addedFrame * 2) * f, (float)textureY * f1);
    }

    private void renderWaypointLabel(String label, MatrixStack matrixStack, MinimapRendererHelper helper, TextRenderer fontRenderer, int labelScale, float bgAlpha) {
        int nameWidth = fontRenderer.getWidth(label);
        int backgroundWidth = nameWidth + 3;
        int halfBackgroundWidth = backgroundWidth / 2;
        int halfPixel = 0;
        if ((backgroundWidth & 1) != 0) {
            halfPixel = labelScale - labelScale / 2;
            matrixStack.translate((float)(-halfPixel), 0.0f, 0.0f);
        }
        matrixStack.scale((float)labelScale, (float)labelScale, 1.0f);
        RenderBufferUtil.addColoredRect(matrixStack.peek().getPositionMatrix(), this.waypointBackgroundConsumer, -halfBackgroundWidth, 0.0f, backgroundWidth, 9, 0.0f, 0.0f, 0.0f, bgAlpha);
        Misc.drawNormalText(matrixStack, label, (float)(-halfBackgroundWidth + 2), 1.0f, -1, false, (VertexConsumerProvider)this.minimapBufferSource);
        matrixStack.translate(0.0f, 9.0f, 0.0f);
        matrixStack.scale(1.0f / (float)labelScale, 1.0f / (float)labelScale, 1.0f);
        if ((backgroundWidth & 1) != 0) {
            matrixStack.translate((float)halfPixel, 0.0f, 0.0f);
        }
    }

    private String getDistanceText(double distanceFromEntity) {
        if (this.autoConvertWaypointDistanceToKmThreshold != -1 && distanceFromEntity >= (double)this.autoConvertWaypointDistanceToKmThreshold) {
            return GuiMisc.getFormat(this.waypointDistancePrecision).format(distanceFromEntity / 1000.0) + "km";
        }
        return GuiMisc.getFormat(this.waypointDistancePrecision).format(distanceFromEntity) + "m";
    }

    @Override
    public boolean shouldRender(MinimapElementRenderLocation location) {
        ClientConfigManager configManager = HudMod.INSTANCE.getHudConfigs().getClientConfigManager();
        if (!((Boolean)configManager.getEffective((ConfigOption)MinimapProfiledConfigOptions.WAYPOINTS_IN_WORLD)).booleanValue()) {
            return false;
        }
        MinecraftClient mc = MinecraftClient.getInstance();
        return mc.player != null && !Misc.hasEffect((PlayerEntity)mc.player, Effects.NO_WAYPOINTS) && !Misc.hasEffect((PlayerEntity)mc.player, Effects.NO_WAYPOINTS_HARMFUL);
    }

    @Override
    public int getOrder() {
        return 100;
    }

    public static final class Builder {
        private Builder() {
        }

        private Builder setDefault() {
            return this;
        }

        public WaypointWorldRenderer build() {
            WaypointWorldRenderContext context = new WaypointWorldRenderContext();
            return new WaypointWorldRenderer(new WaypointWorldRenderReader(context), new WaypointWorldRenderProvider(), context);
        }

        public static Builder begin() {
            return new Builder().setDefault();
        }
    }
}
