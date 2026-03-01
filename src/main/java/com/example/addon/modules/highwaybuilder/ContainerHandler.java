package com.example.addon.modules.highwaybuilder;

import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

public class ContainerHandler {
    private static final MinecraftClient mc = MinecraftClient.getInstance();

    private final HighwayBuilder module;
    public BlockTask containerTask;
    public int grindCycles = 0;

    private Item targetItem = null;
    private int transferDelay = 0;
    private int openAttempts = 0;

    public ContainerHandler(HighwayBuilder module) {
        this.module = module;
        this.containerTask = new BlockTask(BlockPos.ORIGIN, TaskState.DONE, Blocks.AIR);
    }

    /**
     * Start a restock cycle: find a shulker with the target item, place it, open, take items, break, collect.
     */
    public void handleRestock(Item item) {
        if (!module.storageManagement.get()) {
            module.disableWithError("No usable material/tool found and storage management is disabled.");
            return;
        }

        if (containerTask.taskState != TaskState.DONE) return;

        if (mc.player == null) return;

        // Find a shulker box in inventory containing the target item
        int shulkerSlot = findShulkerWithItem(item);
        if (shulkerSlot == -1) {
            module.disableWithError("No shulker box found containing " + item.getName().getString());
            return;
        }

        // Get the shulker block type from the item
        ItemStack shulkerStack = mc.player.getInventory().getStack(shulkerSlot);
        Block shulkerBlock = ((BlockItem) shulkerStack.getItem()).getBlock();

        // Move shulker to hotbar slot 0 if it's in main inventory
        if (shulkerSlot >= 9) {
            InvUtils.move().from(shulkerSlot).toHotbar(0);
        }

        // Find a valid position to place the shulker
        BlockPos containerPos = getRemotePos();
        if (containerPos == null) {
            module.disableWithError("No valid position found for container placement.");
            return;
        }

        containerTask = new BlockTask(containerPos, TaskState.PLACE, shulkerBlock);
        containerTask.item = item;
        containerTask.collect = true;
        containerTask.destroy = false; // open first, then destroy after taking items

        targetItem = item;
        transferDelay = 0;
        openAttempts = 0;

        module.pathfinder.moveState = MovementState.RESTOCK;
    }

    /**
     * Open the placed shulker box by sending an interact packet.
     */
    public void doOpenContainer() {
        if (mc.player == null || mc.getNetworkHandler() == null) return;

        module.pathfinder.moveState = MovementState.RESTOCK;

        // If already open, transition to RESTOCK to take items
        if (containerTask.isOpen) {
            containerTask.updateState(TaskState.RESTOCK);
            return;
        }

        // Check reach distance
        double dist = mc.player.getEyePos().distanceTo(Vec3d.ofCenter(containerTask.blockPos));
        if (dist > module.maxReach.get()) {
            // Wait for pathfinder to bring us closer
            return;
        }

        // Verify the shulker is still there
        if (mc.world != null) {
            Block currentBlock = mc.world.getBlockState(containerTask.blockPos).getBlock();
            if (!(currentBlock instanceof ShulkerBoxBlock)) {
                // Shulker is gone — abort
                containerTask.updateState(TaskState.DONE);
                module.pathfinder.moveState = MovementState.RUNNING;
                return;
            }
        }

        // Send interact packet to open the shulker
        Vec3d hitVec = Vec3d.ofCenter(containerTask.blockPos);
        BlockHitResult hitResult = new BlockHitResult(hitVec, Direction.UP, containerTask.blockPos, false);
        mc.getNetworkHandler().sendPacket(new PlayerInteractBlockC2SPacket(Hand.MAIN_HAND, hitResult, 0));
        mc.player.swingHand(Hand.MAIN_HAND);

        openAttempts++;
        if (openAttempts > 40) {
            // Stuck trying to open — break and abort
            containerTask.destroy = true;
            containerTask.collect = true;
            containerTask.updateState(TaskState.BREAK);
            openAttempts = 0;
        }
    }

    /**
     * Take target items from the open shulker, then close and break it.
     */
    public void doRestock() {
        if (mc.player == null || mc.interactionManager == null) return;

        // Wait for container contents to load
        if (!containerTask.isLoaded) {
            containerTask.onStuck();
            return;
        }

        // Delay between transfers
        if (transferDelay > 0) {
            transferDelay--;
            return;
        }

        var handler = mc.player.currentScreenHandler;
        if (handler == null || handler.syncId == 0) {
            // Screen closed unexpectedly — break and collect
            containerTask.isOpen = false;
            containerTask.isLoaded = false;
            containerTask.destroy = true;
            containerTask.collect = true;
            containerTask.updateState(TaskState.BREAK);
            module.pathfinder.moveState = MovementState.RUNNING;
            return;
        }

        // Search shulker slots (0-26) for the target item
        boolean found = false;
        for (int i = 0; i < 27; i++) {
            ItemStack slotStack = handler.getSlot(i).getStack();
            if (slotStack.isEmpty()) continue;
            if (targetItem != null && slotStack.getItem() != targetItem) continue;

            // Quick-move this stack to player inventory
            mc.interactionManager.clickSlot(handler.syncId, i, 0, SlotActionType.QUICK_MOVE, mc.player);
            transferDelay = 2; // small delay between transfers
            found = true;
            break;
        }

        if (!found) {
            // No more target items in shulker — close, break, collect
            closeAndBreak();
        }
    }

    /**
     * Wait for the shulker item to be picked up after breaking.
     */
    public void doPickup() {
        if (getCollectingPosition() == null) {
            containerTask.updateState(TaskState.DONE);
            module.pathfinder.moveState = MovementState.RUNNING;
            grindCycles++;
            return;
        }
        containerTask.onStuck();
    }

    /**
     * Close the shulker screen and transition to BREAK state.
     */
    private void closeAndBreak() {
        if (mc.player != null) {
            mc.player.closeHandledScreen();
        }
        containerTask.isOpen = false;
        containerTask.isLoaded = false;
        containerTask.destroy = true;
        containerTask.collect = true;
        containerTask.updateState(TaskState.BREAK);
    }

    // ── Shulker scanning ─────────────────────────────────────────────────

    /**
     * Find an inventory slot containing a shulker box that holds the given item.
     * Returns -1 if none found.
     */
    public int findShulkerWithItem(Item item) {
        if (mc.player == null) return -1;

        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.isEmpty()) continue;
            if (!(stack.getItem() instanceof BlockItem bi)) continue;
            if (!(bi.getBlock() instanceof ShulkerBoxBlock)) continue;

            // Check shulker contents via DataComponents
            ContainerComponent container = stack.get(DataComponentTypes.CONTAINER);
            if (container == null) continue;

            for (ItemStack contained : container.iterateNonEmpty()) {
                if (contained.getItem() == item) {
                    return i;
                }
            }
        }
        return -1;
    }

    // ── Position helpers ─────────────────────────────────────────────────

    /**
     * Find a valid position near the player to place a container.
     * Outside the blueprint, on solid ground, within reach.
     */
    private BlockPos getRemotePos() {
        if (mc.player == null || mc.world == null) return null;

        BlockPos playerPos = mc.player.getBlockPos();
        double maxReach = module.maxReach.get();
        double minDist = module.minDistance.get();
        BlockPos best = null;
        int bestScore = -1;

        for (int x = (int) -maxReach; x <= (int) maxReach; x++) {
            for (int y = -1; y <= 1; y++) {
                for (int z = (int) -maxReach; z <= (int) maxReach; z++) {
                    BlockPos pos = playerPos.add(x, y, z);

                    if (pos.equals(playerPos)) continue;
                    if (!mc.world.getBlockState(pos).isAir()) continue;

                    // Must have ground below
                    if (mc.world.getBlockState(pos.down()).isAir()) continue;

                    double dist = mc.player.getPos().distanceTo(Vec3d.ofCenter(pos));
                    if (dist < minDist || dist > maxReach) continue;

                    // Not inside blueprint
                    if (module.blueprintGenerator != null && module.blueprintGenerator.isInsideBlueprint(pos)) continue;

                    // Score by surrounding support
                    int score = 0;
                    for (Direction dir : Direction.values()) {
                        if (!mc.world.getBlockState(pos.offset(dir)).isAir()) score++;
                    }

                    if (score > bestScore) {
                        bestScore = score;
                        best = pos;
                    }
                }
            }
        }

        return best;
    }

    public BlockPos getCollectingPosition() {
        if (mc.world == null) return null;
        if (containerTask.taskState == TaskState.PICKUP) {
            return containerTask.blockPos;
        }
        return null;
    }
}
