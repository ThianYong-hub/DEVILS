package com.example.addon.mixin;

import com.example.addon.AddonTemplate;
import com.example.addon.gui.DevilsCategoryIconWidget;
import meteordevelopment.meteorclient.gui.screens.ModulesScreen;
import meteordevelopment.meteorclient.gui.widgets.containers.WContainer;
import meteordevelopment.meteorclient.gui.widgets.containers.WWindow;
import meteordevelopment.meteorclient.systems.modules.Category;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

@Mixin(ModulesScreen.class)
public abstract class ModulesScreenMixin {
    @Inject(method = "createCategory", at = @At("RETURN"), remap = false)
    private void devils$replaceCategoryIcon(WContainer c, Category category, List moduleList, CallbackInfoReturnable cir) {
        if (!AddonTemplate.CATEGORY.name.equals(category.name)) return;

        WWindow window = (WWindow) cir.getReturnValue();
        if (window == null) return;
        if (window.beforeHeaderInit == null) return;

        window.beforeHeaderInit = DevilsCategoryIconWidget::addToHeader;
    }
}
