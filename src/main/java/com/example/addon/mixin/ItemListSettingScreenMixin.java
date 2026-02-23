package com.example.addon.mixin;

import meteordevelopment.meteorclient.gui.GuiThemes;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.gui.screens.settings.ItemListSettingScreen;
import meteordevelopment.meteorclient.utils.misc.Names;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ItemListSettingScreen.class)
public abstract class ItemListSettingScreenMixin {
    @Inject(method = "getValueWidget", at = @At("HEAD"), cancellable = true)
    private void paradise$renderItemIcon(Item value, CallbackInfoReturnable<WWidget> cir) {
        cir.setReturnValue(GuiThemes.get().itemWithLabel(new ItemStack(value), Names.get(value)));
    }
}
