package com.example.addon.modules.highwaybuilder;

import meteordevelopment.meteorclient.utils.player.InvUtils;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.Set;

public class BlockPlacer {
    private static final MinecraftClient mc = MinecraftClient.getInstance();

    private final HighwayBuilder module;
    public int extraPlaceDelay = 0;

    // Blocks that require sneaking to place against
    private static final Set<Block> BLACKLIST_BLOCKS = Set.of(
        Blocks.CRAFTING_TABLE, Blocks.CHEST, Blocks.TRAPPED_CHEST,
        Blocks.ENDER_CHEST, Blocks.ANVIL, Blocks.CHIPPED_ANVIL,
        Blocks.DAMAGED_ANVIL, Blocks.ENCHANTING_TABLE, Blocks.FURNACE,
        Blocks.BLAST_FURNACE, Blocks.SMOKER, Blocks.BREWING_STAND,
        Blocks.HOPPER, Blocks.DROPPER, Blocks.DISPENSER,
        Blocks.BARREL, Blocks.LOOM, Blocks.CARTOGRAPHY_TABLE,
        Blocks.GRINDSTONE, Blocks.SMITHING_TABLE, Blocks.STONECUTTER,
        Blocks.LECTERN, Blocks.BEACON, Blocks.BELL,
        Blocks.NOTE_BLOCK, Blocks.JUKEBOX
    );

    public BlockPlacer(HighwayBuilder module) {
        this.module = module;
    }

    public void placeBlock(BlockTask blockTask) {
        if (mc.player == null || mc.getNetworkHandler() == null) return;

        if (blockTask.sequence.isEmpty()) {
            blockTask.onStuck(21);
            blockTask.updateState(TaskState.DONE);
            return;
        }

        PlaceInfo last = blockTask.sequence.get(blockTask.sequence.size() - 1);
        module.inventoryHandler.lastHitVec = HWUtils.getHitVec(last.pos(), last.side());
        placeBlockNormal(blockTask, last.pos(), last.side());
    }

    private void placeBlockNormal(BlockTask blockTask, BlockPos placePos, Direction side) {
        if (mc.world == null || mc.getNetworkHandler() == null || mc.player == null) return;

        BlockState currentBlock = mc.world.getBlockState(placePos);

        int delay = module.dynamicDelay.get()
            ? module.placeDelay.get() + extraPlaceDelay
            : module.placeDelay.get();
        module.inventoryHandler.waitTicks = delay;
        blockTask.updateState(TaskState.PENDING_PLACE);

        boolean needSneak = BLACKLIST_BLOCKS.contains(currentBlock.getBlock());

        boolean wasSneaking = mc.player.isSneaking();
        if (needSneak && !wasSneaking) {
            mc.player.setSneaking(true);
        }

        // Send place packet
        Vec3d hitVec = HWUtils.getHitVec(placePos, side);
        BlockHitResult hitResult = new BlockHitResult(hitVec, side, placePos, false);
        mc.getNetworkHandler().sendPacket(new PlayerInteractBlockC2SPacket(Hand.MAIN_HAND, hitResult, 0));
        mc.player.swingHand(Hand.MAIN_HAND);

        if (needSneak && !wasSneaking) {
            mc.player.setSneaking(false);
        }

        // Silent swap: restore previous slot after placing
        if (module.inventoryHandler.swapBackSlot >= 0) {
            InvUtils.swap(module.inventoryHandler.swapBackSlot, false);
            module.inventoryHandler.swapBackSlot = -1;
        }
    }
}
