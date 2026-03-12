package com.example.addon.chesttracker.impl.gui.invbutton;

import org.jetbrains.annotations.Nullable;
import com.example.addon.chesttracker.impl.gui.invbutton.ui.InventoryButton;
import com.example.addon.chesttracker.impl.providers.ScreenOpenContextImpl;

/**
 * Applied to AbstractContainerScreen to get the menu positions, and to add the button with a back reference to the screen.
 */
public interface CTButtonScreenDuck {
    int devilsct$getLeft();

    int devilsct$getTop();

    int devilsct$getWidth();

    int devilsct$getHeight();

    void devilsct$setButton(InventoryButton button);

    void devilsct$setContext(ScreenOpenContextImpl openContext);

    @Nullable
    ScreenOpenContextImpl devilsct$getContext();
}


