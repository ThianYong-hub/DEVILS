package com.example.addon.mixin;

import com.example.addon.modules.chesttracker.ChestTrackerKeybindScreen;
import dev.isxander.yacl3.api.YetAnotherConfigLib;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.ScreenRect;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import red.jackf.chesttracker.impl.ChestTracker;
import red.jackf.chesttracker.impl.gui.invbutton.InventoryButtonFeature;
import red.jackf.chesttracker.impl.gui.util.ChestTrackerRuntimeState;

@Mixin(ChestTracker.class)
abstract class ChestTrackerOpenGuardMixin {
    @Inject(method = "openInGame", at = @At("HEAD"), cancellable = true)
    private static void devilsAddon$blockOpenWhenModuleDisabled(MinecraftClient client, @Nullable Screen parent, CallbackInfo ci) {
        if (!ChestTrackerRuntimeState.isModuleEnabled()) ci.cancel();
    }
}

@Mixin(InventoryButtonFeature.class)
abstract class ChestTrackerInventoryButtonGuardMixin {
    @Inject(method = "onScreenOpen", at = @At("HEAD"), cancellable = true)
    private static void devilsAddon$blockInventoryButtonWhenModuleDisabled(
        MinecraftClient client,
        Screen screen,
        int scaledWidth,
        int scaledHeight,
        CallbackInfo ci
    ) {
        if (!ChestTrackerRuntimeState.isModuleEnabled()) ci.cancel();
    }
}

@Mixin(targets = "dev.isxander.yacl3.gui.YACLScreen", remap = false)
abstract class YaclChestTrackerConfigMixin extends Screen {
    @Shadow
    @Final
    public YetAnotherConfigLib config;

    @Shadow
    public ScreenRect tabArea;

    @Unique
    private ButtonWidget devilsAddon$ctKeybindButton;

    protected YaclChestTrackerConfigMixin(Text title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"), require = 0)
    private void devilsAddon$addChestTrackerKeybindButton(CallbackInfo ci) {
        if (!devilsAddon$isChestTrackerConfig()) return;

        int buttonWidth = 150;
        int buttonHeight = 20;
        int x = Math.max(8, (this.tabArea != null ? this.tabArea.getRight() : this.width) - buttonWidth);
        int y = this.tabArea != null ? Math.max(6, this.tabArea.getTop() - 24) : 6;

        this.devilsAddon$ctKeybindButton = this.addDrawableChild(
            ButtonWidget.builder(devilsAddon$keybindButtonText(), button -> {
                MinecraftClient client = MinecraftClient.getInstance();
                client.setScreen(new ChestTrackerKeybindScreen((Screen) (Object) this, this::devilsAddon$refreshKeybindButtonMessage));
            }).dimensions(x, y, buttonWidth, buttonHeight).build()
        );
    }

    @Unique
    private boolean devilsAddon$isChestTrackerConfig() {
        if (this.config == null) return false;
        return this.config.title().getString().equals(Text.translatable("chesttracker.title").getString());
    }

    @Unique
    private Text devilsAddon$keybindButtonText() {
        return Text.translatable("devils-addon.chesttracker.keybind.button", ChestTracker.OPEN_GUI.getBoundKeyLocalizedText());
    }

    @Unique
    private void devilsAddon$refreshKeybindButtonMessage() {
        if (this.devilsAddon$ctKeybindButton != null) {
            this.devilsAddon$ctKeybindButton.setMessage(devilsAddon$keybindButtonText());
        }
    }
}
