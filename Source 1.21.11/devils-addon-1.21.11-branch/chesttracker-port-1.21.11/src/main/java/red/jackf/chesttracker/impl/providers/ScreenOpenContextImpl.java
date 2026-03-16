package com.example.addon.chesttracker.impl.providers;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;
import com.example.addon.chesttracker.api.providers.MemoryLocation;
import com.example.addon.chesttracker.api.providers.context.ScreenOpenContext;

public final class ScreenOpenContextImpl implements ScreenOpenContext {
    private final AbstractContainerScreen<?> screen;
    private @Nullable MemoryLocation target = null;

    public ScreenOpenContextImpl(AbstractContainerScreen<?> screen) {
        this.screen = screen;
    }

    @ApiStatus.Internal
    public static ScreenOpenContextImpl createFor(AbstractContainerScreen<?> screen) {
        return new ScreenOpenContextImpl(screen);
    }

    @Override
    public AbstractContainerScreen<?> getScreen() {
        return screen;
    }

    @Override
    public void setMemoryLocation(MemoryLocation memoryLocation) {
        this.target = memoryLocation;
    }

    public @Nullable MemoryLocation getTarget() {
        return target;
    }
}
