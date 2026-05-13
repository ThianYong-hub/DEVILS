package com.example.addon.modules.chesttracker;

import com.example.addon.util.runtime.StrictRuntimeLogger;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.input.KeyInput;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.text.Text;
import red.jackf.chesttracker.impl.ChestTracker;

public final class ChestTrackerKeybindScreen extends Screen {
    private final Screen returnScreen;
    private final Runnable onChange;
    private ButtonWidget captureButton;
    private boolean listening;

    public ChestTrackerKeybindScreen(Screen returnScreen, Runnable onChange) {
        super(Text.translatable("devils-addon.chesttracker.keybind.title"));
        this.returnScreen = returnScreen;
        this.onChange = onChange == null ? () -> {} : onChange;
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int buttonTop = this.height / 2 - 10;

        this.captureButton = this.addDrawableChild(
            ButtonWidget.builder(this.captureButtonText(), button -> {
                listening = true;
                refreshCaptureButton();
            }).dimensions(centerX - 100, buttonTop, 200, 20).build()
        );

        this.addDrawableChild(
            ButtonWidget.builder(Text.translatable("devils-addon.chesttracker.keybind.reset"), button -> setBinding(ChestTracker.OPEN_GUI.getDefaultKey()))
                .dimensions(centerX - 100, buttonTop + 28, 98, 20)
                .build()
        );

        this.addDrawableChild(
            ButtonWidget.builder(ScreenTexts.DONE, button -> close())
                .dimensions(centerX + 2, buttonTop + 28, 98, 20)
                .build()
        );

        refreshCaptureButton();
    }

    @Override
    public boolean keyPressed(KeyInput event) {
        if (!listening) return super.keyPressed(event);

        StrictRuntimeLogger.logInput(
            "key-capture",
            "keycode=" + event.key()
                + " scancode=" + event.scancode()
                + " modifiers=" + event.modifiers()
                + " localized=" + InputUtil.fromKeyCode(event).getLocalizedText().getString()
        );

        int key = event.key();
        if (key == InputUtil.GLFW_KEY_ESCAPE) {
            listening = false;
            refreshCaptureButton();
            return true;
        }

        if (key == InputUtil.GLFW_KEY_DELETE || key == InputUtil.GLFW_KEY_BACKSPACE) {
            setBinding(InputUtil.UNKNOWN_KEY);
            return true;
        }

        setBinding(InputUtil.fromKeyCode(event));
        return true;
    }

    @Override
    public boolean mouseClicked(Click event, boolean isDoubleClick) {
        if (listening) {
            StrictRuntimeLogger.logInput(
                "mouse-capture",
                "button=" + event.button()
                    + " modifiers=" + event.modifiers()
                    + " localized=" + InputUtil.Type.MOUSE.createFromCode(event.button()).getLocalizedText().getString()
            );
            setBinding(InputUtil.Type.MOUSE.createFromCode(event.button()));
            return true;
        }

        return super.mouseClicked(event, isDoubleClick);
    }

    @Override
    public void close() {
        if (this.client != null) this.client.setScreen(returnScreen);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        this.renderBackground(context, mouseX, mouseY, delta);
        super.render(context, mouseX, mouseY, delta);

        int centerX = this.width / 2;
        context.drawCenteredTextWithShadow(this.textRenderer, this.title, centerX, this.height / 2 - 54, 0xFFFFFF);
        context.drawCenteredTextWithShadow(
            this.textRenderer,
            Text.translatable("devils-addon.chesttracker.keybind.instructions"),
            centerX,
            this.height / 2 - 38,
            0xA0A0A0
        );
        context.drawCenteredTextWithShadow(
            this.textRenderer,
            Text.translatable("devils-addon.chesttracker.keybind.current", ChestTracker.OPEN_GUI.getBoundKeyLocalizedText()),
            centerX,
            this.height / 2 - 24,
            0xD0D0D0
        );
    }

    private void setBinding(InputUtil.Key key) {
        ChestTracker.OPEN_GUI.setBoundKey(key);
        KeyBinding.updateKeysByCode();
        if (this.client != null) this.client.options.write();
        StrictRuntimeLogger.logInput(
            "bind-saved",
            "translationKey=" + ChestTracker.OPEN_GUI.getBoundKeyTranslationKey()
                + " localized=" + ChestTracker.OPEN_GUI.getBoundKeyLocalizedText().getString()
        );
        listening = false;
        refreshCaptureButton();
        onChange.run();
    }

    private void refreshCaptureButton() {
        if (captureButton != null) captureButton.setMessage(captureButtonText());
    }

    private Text captureButtonText() {
        if (listening) return Text.translatable("devils-addon.chesttracker.keybind.listening");
        return Text.translatable("devils-addon.chesttracker.keybind.button", ChestTracker.OPEN_GUI.getBoundKeyLocalizedText());
    }
}
