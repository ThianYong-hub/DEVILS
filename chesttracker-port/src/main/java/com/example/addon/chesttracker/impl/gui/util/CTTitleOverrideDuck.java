package com.example.addon.chesttracker.impl.gui.util;

import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.NotNull;

public interface CTTitleOverrideDuck {
    void devilsct$setTitleOverride(@NotNull Component title);

    void devilsct$clearTitleOverride();

    Component devilsct$getOriginalTitle();
}


