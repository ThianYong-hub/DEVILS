package com.devils.addon.shared.sync;

import meteordevelopment.meteorclient.gui.renderer.GuiRenderer;
import meteordevelopment.meteorclient.gui.widgets.input.WTextBox;
import meteordevelopment.meteorclient.utils.render.color.Color;

/**
 * Draws text box content as same-length asterisks so shared sync secrets
 * (auth token, signing key, E2E secret) stay unreadable on screen and in
 * screenshots. The underlying value and config storage are unaffected.
 */
public final class MaskedTextBoxRenderer implements WTextBox.Renderer {
    @Override
    public void render(GuiRenderer renderer, double x, double y, String text, Color color) {
        renderer.text(mask(text), x, y, color, false);
    }

    static String mask(String text) {
        if (text == null || text.isEmpty()) return "";
        return "*".repeat(text.length());
    }
}
