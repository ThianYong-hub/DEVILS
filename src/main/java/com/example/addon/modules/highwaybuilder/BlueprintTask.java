package com.example.addon.modules.highwaybuilder;

import net.minecraft.block.Block;

public class BlueprintTask {
    public final Block targetBlock;
    public final boolean isFiller;
    public final boolean isSupport;

    public BlueprintTask(Block targetBlock) {
        this(targetBlock, false, false);
    }

    public BlueprintTask(Block targetBlock, boolean isFiller, boolean isSupport) {
        this.targetBlock = targetBlock;
        this.isFiller = isFiller;
        this.isSupport = isSupport;
    }
}
