package com.example.addon.modules.ping;

import com.example.addon.modules.Ping;
import com.example.addon.modules.XaeroSync;
import com.example.addon.util.MapIconManager;
import meteordevelopment.meteorclient.events.render.Render2DEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.renderer.Renderer2D;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.utils.render.NametagUtils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import net.minecraft.client.gl.RenderPipelines;
import org.joml.Vector3d;

public final class PingRenderController {
    private static volatile Boolean xaeroWaypointRendererPresent;

    private final Ping module;
    private final PingMarkerController markerController;

    public PingRenderController(Ping module, PingMarkerController markerController) {
        this.module = module;
        this.markerController = markerController;
    }

    public void renderMarkers3D(Render3DEvent event) {
        if (module.client().player == null || module.client().world == null) return;

        Color baseMarker = module.markerColorValue();
        long now = System.currentTimeMillis();
        for (PingMarker markerData : markerController.collectVisibleMarkers()) {
            if (!markerController.isMarkerAlive(markerData, now)) continue;

            Color markerLines = pulseColor(baseMarker, markerData, now, 0.62, 1.0);
            Color markerFill = new Color(markerLines.r, markerLines.g, markerLines.b, Math.max(20, markerLines.a / 3));
            double columnHalf = 0.12;
            event.renderer.box(
                markerData.x() - columnHalf,
                PingConstants.WORLD_MIN_Y,
                markerData.z() - columnHalf,
                markerData.x() + columnHalf,
                PingConstants.WORLD_MAX_Y,
                markerData.z() + columnHalf,
                markerFill,
                markerLines,
                ShapeMode.Both,
                0
            );

            double half = 0.22;
            event.renderer.box(
                markerData.x() - half,
                markerData.y() - 0.05,
                markerData.z() - half,
                markerData.x() + half,
                markerData.y() + 0.05,
                markerData.z() + half,
                markerFill,
                markerLines,
                ShapeMode.Both,
                0
            );
        }
    }

    public void renderMarkers2D(Render2DEvent event) {
        if (module.client().player == null || module.client().world == null || module.client().currentScreen != null) return;
        if (shouldUseXaeroWaypointLabelsOnly()) return;

        Vector3d pos = new Vector3d();
        long now = System.currentTimeMillis();
        for (PingMarker marker : markerController.collectVisibleMarkers()) {
            if (!markerController.isMarkerAlive(marker, now)) continue;
            pos.set(marker.x(), marker.y() + 0.35, marker.z());
            if (!NametagUtils.to2D(pos, PingConstants.STATIC_LABEL_SCALE, false)) continue;

            String label = buildInfoText(marker);
            boolean showIcon = module.iconEnabled() && marker.icon();
            if (label.isBlank() && !showIcon) continue;

            NametagUtils.begin(pos, event.drawContext);
            int iconSize = Math.max(8, module.client().textRenderer.fontHeight);
            double iconWidth = showIcon ? iconSize : 0;
            double textWidth = label.isBlank() ? 0 : module.client().textRenderer.getWidth(label);
            double spacing = showIcon && !label.isBlank() ? 2 : 0;
            double width = iconWidth + spacing + textWidth;
            double height = module.client().textRenderer.fontHeight;
            double x = -width / 2;
            double y = -height - 1.5;

            Renderer2D.COLOR.begin();
            Renderer2D.COLOR.quad(x - 2, y - 1, width + 4, height + 2, module.textBackgroundColorValue());
            Renderer2D.COLOR.render();

            double cursorX = x;
            if (showIcon) {
                int iconX = (int) Math.round(cursorX);
                int iconY = (int) Math.round(y - 1);
                boolean drawnCustom = MapIconManager.drawCustomIcon(event.drawContext, marker.iconPath(), iconX, iconY, iconSize, 0xFFFFFFFF);
                if (!drawnCustom) {
                    event.drawContext.drawTexture(
                        RenderPipelines.GUI_TEXTURED,
                        PingConstants.PING_MARKER_ICON_TEXTURE,
                        iconX,
                        iconY,
                        PingConstants.DEVILS_MAP_ICON_U,
                        PingConstants.DEVILS_MAP_ICON_V,
                        iconSize,
                        iconSize,
                        PingConstants.DEVILS_MAP_ICON_REGION_W,
                        PingConstants.DEVILS_MAP_ICON_REGION_H,
                        PingConstants.DEVILS_MAP_ICON_SOURCE_SIZE,
                        PingConstants.DEVILS_MAP_ICON_SOURCE_SIZE,
                        0xFFFFFFFF
                    );
                }
                cursorX += iconWidth + spacing;
            }

            if (!label.isBlank()) {
                event.drawContext.drawText(
                    module.client().textRenderer,
                    label,
                    (int) Math.round(cursorX),
                    (int) Math.round(y),
                    toArgb(labelColor(marker, now)),
                    false
                );
            }
            NametagUtils.end(event.drawContext);
        }
    }

    private String buildInfoText(PingMarker marker) {
        String sender = PingFormattingUtils.normalizeSender(marker.sender());
        String pingPrefix = "[PING] " + sender;
        String coords = PingFormattingUtils.formatCoords(marker.x(), marker.y(), marker.z());
        return switch (module.infoModeValue()) {
            case Distance -> pingPrefix;
            case Coords -> pingPrefix + " | " + coords;
        };
    }

    private boolean shouldUseXaeroWaypointLabelsOnly() {
        if (XaeroSync.isWaypointIntegrationRunning()) return true;
        Boolean cached = xaeroWaypointRendererPresent;
        if (cached != null) return cached;

        boolean present;
        try {
            Class.forName("xaero.map.mods.gui.WaypointRenderer", false, Ping.class.getClassLoader());
            present = true;
        } catch (Throwable ignored) {
            present = false;
        }
        xaeroWaypointRendererPresent = present;
        return present;
    }

    private Color pulseColor(Color base, PingMarker marker, long nowMs, double minMul, double maxMul) {
        double phase = pulsePhase(marker, nowMs);
        double factor = minMul + (maxMul - minMul) * phase;
        return new Color(scaleColorChannel(base.r, factor), scaleColorChannel(base.g, factor), scaleColorChannel(base.b, factor), base.a);
    }

    private Color labelColor(PingMarker marker, long nowMs) {
        return pulseColor(module.textColorValue(), marker, nowMs, 0.72, 1.0);
    }

    private double pulsePhase(PingMarker marker, long nowMs) {
        long age = Math.max(0, nowMs - marker.createdAtMs());
        double radians = (age % PingConstants.MARKER_PULSE_PERIOD_MS) * (Math.PI * 2.0 / PingConstants.MARKER_PULSE_PERIOD_MS);
        return (Math.sin(radians) + 1.0) * 0.5;
    }

    private int scaleColorChannel(int channel, double factor) {
        int value = (int) Math.round(channel * factor);
        if (value < 0) return 0;
        return Math.min(value, 255);
    }

    private int toArgb(Color color) {
        int a = color.a & 0xFF;
        int r = color.r & 0xFF;
        int g = color.g & 0xFF;
        int b = color.b & 0xFF;
        return (a << 24) | (r << 16) | (g << 8) | b;
    }
}


