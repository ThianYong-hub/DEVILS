package com.example.addon.mixin;

import net.minecraft.screen.AnvilScreenHandler;
import net.minecraft.screen.Property;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(AnvilScreenHandler.class)
public interface AnvilScreenHandlerAccessor {

    /** Exposes the private levelCost Property so modules can read the XP cost. */
    @Accessor("levelCost")
    Property getLevelCost();
}
