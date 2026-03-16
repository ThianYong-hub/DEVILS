package com.example.addon.modules.xaerosync;

import com.example.addon.AddonTemplate;
import com.example.addon.modules.XaeroSync;
import meteordevelopment.meteorclient.utils.misc.input.KeyAction;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.registry.RegistryKey;
import net.minecraft.util.Util;
import net.minecraft.world.World;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

public final class XaeroOverlayController {
    private final XaeroSync module;
    private final XaeroPresenceStore presenceStore;
    private final XaeroEntryFormatting entryFormatting;

    private boolean menuOpen;

    public XaeroOverlayController(XaeroSync module, XaeroPresenceStore presenceStore, XaeroEntryFormatting entryFormatting) {
        this.module = module;
        this.presenceStore = presenceStore;
        this.entryFormatting = entryFormatting;
    }

    public void clear() {
        menuOpen = false;
    }

    public void renderOverlay(meteordevelopment.meteorclient.events.render.Render2DEvent event) {
        renderOverlay(module.client().currentScreen, event.drawContext, scaledMouseX(), scaledMouseY(), Double.NaN, Double.NaN, Double.NaN);
    }

    public void renderOverlay(
        Screen screen,
        net.minecraft.client.gui.DrawContext drawContext,
        int mouseX,
        int mouseY,
        double forcedCameraX,
        double forcedCameraZ,
        double forcedScale
    ) {
        if (!isOverlayVisible(screen)) {
            menuOpen = false;
            return;
        }

        List<JumpEntry> rows = presenceStore.collectJumpEntries();
        OverlayLayout layout = computeLayout(rows.size());

        boolean buttonHovered = mouseX >= layout.buttonX()
            && mouseX <= layout.buttonX() + XaeroSyncConstants.BUTTON_W
            && mouseY >= layout.buttonY()
            && mouseY <= layout.buttonY() + XaeroSyncConstants.BUTTON_H;
        drawXaeroToolbarButton(drawContext, layout.buttonX(), layout.buttonY(), buttonHovered, menuOpen);

        if (buttonHovered) {
            int tipW = 74;
            int tipH = 12;
            int tipX = Math.max(4, layout.buttonX() - tipW - 4);
            int tipY = layout.buttonY() + 2;
            drawContext.fill(tipX, tipY, tipX + tipW, tipY + tipH, XaeroSyncConstants.DEVILS_TOOLTIP_BACKGROUND);
            XaeroUiReflectionUtils.drawBoxOutline(drawContext, tipX, tipY, tipW, tipH, XaeroSyncConstants.DEVILS_ACCENT_BORDER);
            drawContext.drawText(module.client().textRenderer, "Devils Sync", tipX + 4, tipY + 2, XaeroSyncConstants.DEVILS_TEXT_PRIMARY, false);
        }

        if (!menuOpen) return;

        drawContext.fill(
            layout.panelX(),
            layout.panelY(),
            layout.panelX() + XaeroSyncConstants.PANEL_W,
            layout.panelY() + layout.panelH(),
            XaeroSyncConstants.DEVILS_PANEL_BACKGROUND
        );
        XaeroUiReflectionUtils.drawBoxOutline(
            drawContext,
            layout.panelX(),
            layout.panelY(),
            XaeroSyncConstants.PANEL_W,
            layout.panelH(),
            XaeroSyncConstants.DEVILS_ACCENT_BORDER
        );

        if (rows.isEmpty()) {
            drawContext.drawText(module.client().textRenderer, "No synced players", layout.panelX() + 6, layout.panelY() + 4, XaeroSyncConstants.DEVILS_TEXT_MUTED, false);
            return;
        }

        int shownRows = Math.min(layout.shownRows(), rows.size());
        for (int i = 0; i < shownRows; i++) {
            JumpEntry entry = rows.get(i);
            int rowY = layout.panelY() + 2 + i * XaeroSyncConstants.ROW_H;
            boolean hovered = mouseX >= layout.panelX() + 1
                && mouseX <= layout.panelX() + XaeroSyncConstants.PANEL_W - 1
                && mouseY >= rowY
                && mouseY <= rowY + XaeroSyncConstants.ROW_H - 1;

            if (hovered) {
                drawContext.fill(
                    layout.panelX() + 1,
                    rowY,
                    layout.panelX() + XaeroSyncConstants.PANEL_W - 1,
                    rowY + XaeroSyncConstants.ROW_H - 1,
                    XaeroSyncConstants.DEVILS_ROW_HOVER
                );
            }

            int textX = layout.panelX() + 5;
            if (entry.type() == JumpType.PLAYER) {
                entryFormatting.drawPlayerHead(drawContext, entry, textX, rowY + 2);
                textX += 12;
            } else {
                entryFormatting.drawMarkerIcon(drawContext, textX, rowY + 2, 8, entryFormatting.resolveEntryIconPath(entry));
                textX += 12;
            }

            drawContext.drawText(
                module.client().textRenderer,
                entryFormatting.formatEntry(entry),
                textX,
                rowY + 3,
                hovered ? XaeroSyncConstants.DEVILS_TEXT_PRIMARY : XaeroSyncConstants.DEVILS_TEXT_SECONDARY,
                false
            );
        }
    }

    public boolean handleOverlayClick(KeyAction action, int button, Object eventRef) {
        if (action != KeyAction.Press || button != 0) return false;
        boolean handled = handleOverlayClick(module.client().currentScreen, button, scaledMouseX(), scaledMouseY());
        if (handled) XaeroUiReflectionUtils.cancelInputEvent(eventRef);
        return handled;
    }

    public boolean handleOverlayClick(Screen screen, int button, double mouseXRaw, double mouseYRaw) {
        if (button != 0 || !isOverlayVisible(screen)) return false;

        List<JumpEntry> rows = presenceStore.collectJumpEntries();
        OverlayLayout layout = computeLayout(rows.size());
        int mouseX = (int) Math.round(mouseXRaw);
        int mouseY = (int) Math.round(mouseYRaw);

        boolean inButton = mouseX >= layout.buttonX()
            && mouseX <= layout.buttonX() + XaeroSyncConstants.BUTTON_W
            && mouseY >= layout.buttonY()
            && mouseY <= layout.buttonY() + XaeroSyncConstants.BUTTON_H;
        if (inButton) {
            menuOpen = !menuOpen;
            return true;
        }

        if (!menuOpen) return false;

        boolean inPanel = mouseX >= layout.panelX()
            && mouseX <= layout.panelX() + XaeroSyncConstants.PANEL_W
            && mouseY >= layout.panelY()
            && mouseY <= layout.panelY() + layout.panelH();
        if (!inPanel) {
            menuOpen = false;
            return false;
        }

        int shownRows = Math.min(layout.shownRows(), rows.size());
        for (int i = 0; i < shownRows; i++) {
            int rowY = layout.panelY() + 2 + i * XaeroSyncConstants.ROW_H;
            if (mouseY < rowY || mouseY > rowY + XaeroSyncConstants.ROW_H - 1) continue;

            jumpToEntry(rows.get(i));
            menuOpen = false;
            return true;
        }

        return true;
    }

    private void drawXaeroToolbarButton(net.minecraft.client.gui.DrawContext drawContext, int x, int y, boolean hovered, boolean pressed) {
        int background = pressed
            ? XaeroSyncConstants.DEVILS_BUTTON_BACKGROUND_ACTIVE
            : hovered ? XaeroSyncConstants.DEVILS_BUTTON_BACKGROUND_HOVER : XaeroSyncConstants.DEVILS_BUTTON_BACKGROUND;
        drawContext.fill(x, y, x + XaeroSyncConstants.BUTTON_W, y + XaeroSyncConstants.BUTTON_H, background);
        XaeroUiReflectionUtils.drawBoxOutline(drawContext, x, y, XaeroSyncConstants.BUTTON_W, XaeroSyncConstants.BUTTON_H, XaeroSyncConstants.DEVILS_ACCENT_BORDER);

        int iconX = x + (XaeroSyncConstants.BUTTON_W - 16) / 2;
        int iconY = y + (XaeroSyncConstants.BUTTON_H - 16) / 2;
        if (pressed) iconY -= 1;
        if (hovered) drawContext.fill(iconX - 1, iconY - 1, iconX + 17, iconY + 17, 0x33FFFFFF);

        int tint = pressed ? 0xFFE6E6E6 : 0xFFFCFCFC;
        drawContext.drawTexture(
            RenderPipelines.GUI_TEXTURED,
            XaeroSyncConstants.XAERO_SYNC_ICON_TEXTURE,
            iconX,
            iconY,
            XaeroSyncConstants.DEVILS_MAP_ICON_U,
            XaeroSyncConstants.DEVILS_MAP_ICON_V,
            16,
            16,
            XaeroSyncConstants.DEVILS_MAP_ICON_REGION_W,
            XaeroSyncConstants.DEVILS_MAP_ICON_REGION_H,
            XaeroSyncConstants.DEVILS_MAP_ICON_SOURCE_SIZE,
            XaeroSyncConstants.DEVILS_MAP_ICON_SOURCE_SIZE,
            tint
        );
    }

    private OverlayLayout computeLayout(int rowCount) {
        Screen screen = module.client().currentScreen;
        int width = screen == null ? (module.client().getWindow() == null ? 320 : module.client().getWindow().getScaledWidth()) : screen.width;
        int height = screen == null ? (module.client().getWindow() == null ? 240 : module.client().getWindow().getScaledHeight()) : screen.height;

        int buttonX = Math.max(4, width - XaeroSyncConstants.BUTTON_W - 6);
        int buttonY = 4;
        ToolbarAnchor anchor = findToolbarAnchor(width, height);
        if (anchor != null) {
            buttonX = anchor.x();
            buttonY = anchor.y();
        }

        buttonX = Math.max(4, Math.min(width - XaeroSyncConstants.BUTTON_W - 4, buttonX));
        buttonY = Math.max(4, Math.min(height - XaeroSyncConstants.BUTTON_H - 4, buttonY));

        int shownRows = Math.min(Math.max(1, module.maxRowsValue()), Math.max(1, rowCount));
        int panelH = shownRows * XaeroSyncConstants.ROW_H + 4;
        int panelX = Math.max(4, buttonX + XaeroSyncConstants.BUTTON_W - XaeroSyncConstants.PANEL_W);
        if (panelX + XaeroSyncConstants.PANEL_W > width - 4) panelX = Math.max(4, width - XaeroSyncConstants.PANEL_W - 4);

        int panelY = buttonY - panelH - 2;
        if (panelY < 4) panelY = Math.min(height - panelH - 4, buttonY + XaeroSyncConstants.BUTTON_H + 2);
        if (panelY < 4) panelY = 4;

        return new OverlayLayout(buttonX, buttonY, panelX, panelY, panelH, shownRows);
    }

    private ToolbarAnchor findToolbarAnchor(int width, int height) {
        if (module.client().currentScreen == null) return null;

        ToolbarAnchor knownAnchor = findAnchorFromKnownXaeroButtons(module.client().currentScreen, width, height);
        if (knownAnchor != null) return knownAnchor;

        Iterable<?> elements = tryGetScreenChildren(module.client().currentScreen);
        if (elements == null) return null;

        int bestX = -1;
        int bestW = XaeroSyncConstants.BUTTON_W;
        int topY = Integer.MAX_VALUE;
        int topH = 0;
        for (Object element : elements) {
            if (element == null) continue;
            Integer x = XaeroUiReflectionUtils.readWidgetX(element);
            Integer y = XaeroUiReflectionUtils.readWidgetY(element);
            Integer w = XaeroUiReflectionUtils.readWidgetWidth(element);
            Integer h = XaeroUiReflectionUtils.readWidgetHeight(element);
            if (x == null || y == null || w == null || h == null) continue;
            if (w < 12 || w > 30 || h < 12 || h > 30) continue;
            if (x < (width - 90)) continue;
            if (y < topY) {
                bestX = x;
                bestW = w;
                topY = y;
                topH = h;
            }
        }

        if (bestX < 0 || topY == Integer.MAX_VALUE) return null;

        int y = topY - XaeroSyncConstants.BUTTON_H - 2;
        if (y < 4) y = topY + topH + 2;
        if (y + XaeroSyncConstants.BUTTON_H > (height - 4)) y = Math.max(4, height - XaeroSyncConstants.BUTTON_H - 4);
        if (y < 4) y = 4;
        int x = Math.max(4, Math.min(width - XaeroSyncConstants.BUTTON_W - 4, bestX + Math.max(0, (bestW - XaeroSyncConstants.BUTTON_W) / 2)));
        return new ToolbarAnchor(x, y);
    }

    private ToolbarAnchor findAnchorFromKnownXaeroButtons(Screen screen, int width, int height) {
        if (screen == null) return null;

        String[] xaeroButtonFields = {
            "switchingButton",
            "switchToNetherButton",
            "switchToOverworldButton",
            "switchToEndButton",
            "coordinateGotoButton",
            "followButton",
            "startDrawingButton",
            "caveModeButton",
            "dimensionToggleButton",
            "zoomInButton",
            "zoomOutButton",
            "keybindingsButton",
            "exportButton",
            "claimsButton",
            "radarButton",
            "playersButton",
            "waypointsButton"
        };

        int bestX = -1;
        int bestW = XaeroSyncConstants.BUTTON_W;
        int topY = Integer.MAX_VALUE;
        int topH = 0;

        for (String fieldName : xaeroButtonFields) {
            Object button = XaeroUiReflectionUtils.tryReadFieldValue(screen, fieldName);
            if (button == null) continue;

            Integer x = XaeroUiReflectionUtils.readWidgetX(button);
            Integer y = XaeroUiReflectionUtils.readWidgetY(button);
            Integer w = XaeroUiReflectionUtils.readWidgetWidth(button);
            Integer h = XaeroUiReflectionUtils.readWidgetHeight(button);
            if (x == null || y == null || w == null || h == null) continue;
            if (w < 12 || w > 40 || h < 12 || h > 40) continue;
            if (x < (width - 90)) continue;

            if (y < topY || (y.equals(topY) && x > bestX)) {
                bestX = x;
                bestW = w;
                topY = y;
                topH = h;
            }
        }

        if (bestX < 0 || topY == Integer.MAX_VALUE) return null;

        int y = topY - XaeroSyncConstants.BUTTON_H - 2;
        if (y < 4) y = topY + topH + 2;
        if (y < 4) y = 4;
        if (y + XaeroSyncConstants.BUTTON_H > (height - 4)) y = Math.max(4, height - XaeroSyncConstants.BUTTON_H - 4);
        int x = Math.max(4, Math.min(width - XaeroSyncConstants.BUTTON_W - 4, bestX + Math.max(0, (bestW - XaeroSyncConstants.BUTTON_W) / 2)));
        return new ToolbarAnchor(x, y);
    }

    private boolean isOverlayVisible(Screen screen) {
        return isXaeroMapScreen(screen);
    }

    private boolean isXaeroMapScreen(Screen screen) {
        return screen != null && "xaero.map.gui.GuiMap".equals(screen.getClass().getName());
    }

    private void jumpToEntry(JumpEntry target) {
        if (target == null || module.client().currentScreen == null) return;
        if (!"xaero.map.gui.GuiMap".equals(module.client().currentScreen.getClass().getName())) return;

        try {
            Object guiMap = module.client().currentScreen;
            Object mapProcessor = XaeroUiReflectionUtils.invokeNoArg(guiMap, "getMapProcessor");
            Object mapWorld = mapProcessor == null ? null : XaeroUiReflectionUtils.invokeNoArg(mapProcessor, "getMapWorld");
            RegistryKey<World> targetDim = XaeroUiReflectionUtils.parseDimensionKey(target.dimension());

            if (mapWorld != null && targetDim != null) {
                XaeroUiReflectionUtils.invokeSingleArgIfPresent(mapWorld, "setCustomDimensionId", targetDim);
                XaeroUiReflectionUtils.invokeSingleArgIfPresent(mapWorld, "setFutureDimensionId", targetDim);
                XaeroUiReflectionUtils.invokeNoArgIfPresent(mapWorld, "switchToFutureUnsynced");
            }

            Field cameraDestination = XaeroUiReflectionUtils.findField(guiMap.getClass(), "cameraDestination");
            if (cameraDestination != null) {
                cameraDestination.setAccessible(true);
                cameraDestination.set(guiMap, new int[]{(int) Math.round(target.x()), (int) Math.round(target.z())});
            }

            Method resize = XaeroUiReflectionUtils.findResizeMethod(guiMap.getClass());
            if (resize != null) {
                resize.setAccessible(true);
                resize.invoke(guiMap, module.client(), module.client().getWindow().getScaledWidth(), module.client().getWindow().getScaledHeight());
            }
        } catch (Throwable throwable) {
            AddonTemplate.LOG.debug("[Devils/XaeroSync] Jump failed.", throwable);
            module.error("XaeroSync jump failed: %s", throwable.getClass().getSimpleName());
        }
    }

    private int scaledMouseX() {
        if (module.client().getWindow() == null || module.client().mouse == null) return 0;
        return (int) Math.round(
            module.client().mouse.getX() * module.client().getWindow().getScaledWidth() / (double) module.client().getWindow().getWidth()
        );
    }

    private int scaledMouseY() {
        if (module.client().getWindow() == null || module.client().mouse == null) return 0;
        return (int) Math.round(
            module.client().mouse.getY() * module.client().getWindow().getScaledHeight() / (double) module.client().getWindow().getHeight()
        );
    }

    private Iterable<?> tryGetScreenChildren(Screen screen) {
        if (screen == null) return null;
        try {
            Method children = screen.getClass().getMethod("children");
            Object value = children.invoke(screen);
            if (value instanceof Iterable<?> iterable) return iterable;
        } catch (Throwable ignored) {
        }
        return null;
    }
}


