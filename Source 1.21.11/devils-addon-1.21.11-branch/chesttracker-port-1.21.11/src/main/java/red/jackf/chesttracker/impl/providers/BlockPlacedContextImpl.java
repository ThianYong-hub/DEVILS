package com.example.addon.chesttracker.impl.providers;

import net.minecraft.world.item.ItemStack;
import com.example.addon.chesttracker.api.ClientBlockSource;
import com.example.addon.chesttracker.api.providers.context.BlockPlacedContext;

public record BlockPlacedContextImpl(ClientBlockSource cbs, ItemStack placementStack) implements BlockPlacedContext {
    @Override
    public ClientBlockSource getBlockSource() {
        return this.cbs;
    }

    @Override
    public ItemStack getPlacementStack() {
        return this.placementStack;
    }
}
