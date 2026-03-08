package com.example.addon.mixin;

import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ClientPlayerInteractionManager.class)
public interface ClientPlayerInteractionManagerAccessor {
    @Accessor("breakingBlock")
    boolean isBreakingBlock();

    @Accessor("breakingBlock")
    void setBreakingBlock(boolean breakingBlock);

    @Accessor("currentBreakingPos")
    BlockPos getCurrentBreakingPos();

    @Accessor("currentBreakingPos")
    void setCurrentBreakingPos(BlockPos pos);

    @Accessor("currentBreakingProgress")
    float getCurrentBreakingProgress();

    @Accessor("currentBreakingProgress")
    void setCurrentBreakingProgress(float progress);
}
