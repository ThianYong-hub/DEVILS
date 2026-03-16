package com.example.addon.chesttracker.impl.compat;

import net.fabricmc.loader.api.FabricLoader;

public interface Compatibility {
    boolean SEARCHABLES = FabricLoader.getInstance().isModLoaded("searchables");
}
