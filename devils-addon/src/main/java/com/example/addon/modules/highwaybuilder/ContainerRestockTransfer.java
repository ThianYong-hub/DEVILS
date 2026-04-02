package com.example.addon.modules.highwaybuilder;

import meteordevelopment.meteorclient.utils.player.InvUtils;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;

final class ContainerRestockTransfer {
    private static final MinecraftClient mc = MinecraftClient.getInstance();
    private static final int MAX_EXACT_TRANSFER_PER_TICK = 8;
    private static final int ENDER_CHEST_RESERVE = 16;

    private final ContainerHandler handler;

    ContainerRestockTransfer(ContainerHandler handler) {
        this.handler = handler;
    }

    void doRestock() {
        if (mc.player == null || mc.interactionManager == null) return;

        if (handler.waitingForScreenClose) {
            if (mc.player.currentScreenHandler == null || mc.player.currentScreenHandler.syncId == 0) {
                handler.waitingForScreenClose = false;
                handler.waitingForScreenCloseTicks = 0;
                handler.beginContainerBreak();
                return;
            }

            handler.waitingForScreenCloseTicks++;
            if (handler.waitingForScreenCloseTicks % 6 == 0) {
                mc.player.closeHandledScreen();
            }
            return;
        }

        if (!handler.containerTask.isLoaded) {
            handler.containerTask.onStuck();
            return;
        }

        if (handler.transferDelay > 0) {
            handler.transferDelay--;
            return;
        }

        ScreenHandler screenHandler = mc.player.currentScreenHandler;
        if (screenHandler == null || screenHandler.syncId == 0) {
            handler.containerTask.isOpen = false;
            handler.containerTask.isLoaded = false;
            handler.containerTask.updateState(TaskState.OPEN_CONTAINER);
            return;
        }

        if (handler.restockingFortunePickaxe) {
            doFortunePickaxeRestock(screenHandler);
            return;
        }
        if (handler.targetItem == Items.ENDER_CHEST) {
            doEnderChestRestock(screenHandler);
            return;
        }

        if (handler.targetItem != null && !canTakeAnotherStack(handler.targetItem)) {
            handler.closeAndBreak();
            return;
        }

        boolean found = false;
        for (int i = 0; i < 27; i++) {
            ItemStack slotStack = screenHandler.getSlot(i).getStack();
            if (slotStack.isEmpty()) continue;
            if (handler.targetItem != null && slotStack.getItem() != handler.targetItem) continue;

            mc.interactionManager.clickSlot(screenHandler.syncId, i, 0, SlotActionType.QUICK_MOVE, mc.player);
            handler.transferDelay = 2;
            found = true;
            break;
        }

        if (!found) {
            handler.closeAndBreak();
        }
    }

    boolean ensureReservedPickupSlot() {
        if (handler.inventorySupport.hasSpaceForContainerDrop()) return true;
        if (tryStoreDisposableStackInOpenContainer()) return handler.inventorySupport.hasSpaceForContainerDrop();
        if (!dropSmallestDisposableStack()) return false;
        return handler.inventorySupport.hasSpaceForContainerDrop();
    }

    private boolean dropSmallestDisposableStack() {
        if (mc.player == null || mc.interactionManager == null) return false;

        ScreenHandler screenHandler = mc.player.currentScreenHandler;
        if (screenHandler == null) return false;

        int smallestSlot = findSmallestDisposablePlayerSlot(screenHandler);
        if (smallestSlot == -1) return false;

        mc.interactionManager.clickSlot(screenHandler.syncId, smallestSlot, 1, SlotActionType.THROW, mc.player);
        return true;
    }

    private boolean canTakeAnotherStack(Item item) {
        if (mc.player == null) return false;

        int emptySlots = 0;
        boolean hasPartialTargetStack = false;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.isEmpty()) {
                emptySlots++;
                continue;
            }
            if (stack.getItem() == item && stack.getCount() < stack.getMaxCount()) {
                hasPartialTargetStack = true;
            }
        }

        if (hasPartialTargetStack) return true;
        return emptySlots > 1;
    }

    private void doEnderChestRestock(ScreenHandler screenHandler) {
        if (mc.player == null || mc.interactionManager == null) return;

        if (tryMoveObsidianFromContainer(screenHandler)) {
            handler.transferDelay = 2;
            return;
        }

        int desiredTotal = getDesiredEnderChestCount();
        int currentCount = handler.inventorySupport.countInventoryItem(Items.ENDER_CHEST);
        int needed = desiredTotal - currentCount;

        if (desiredTotal <= 0 || needed <= 0) {
            handler.closeAndBreak();
            return;
        }

        if (findPlayerSlotForSingleInsert(screenHandler, Items.ENDER_CHEST) == -1) {
            handler.closeAndBreak();
            return;
        }

        if (!screenHandler.getCursorStack().isEmpty()) {
            handler.containerTask.onStuck();
            return;
        }

        for (int i = 0; i < 27; i++) {
            ItemStack slotStack = screenHandler.getSlot(i).getStack();
            if (slotStack.isEmpty() || slotStack.getItem() != Items.ENDER_CHEST) continue;

            int toMove = Math.min(needed, slotStack.getCount());
            if (toMove == slotStack.getCount()) {
                mc.interactionManager.clickSlot(screenHandler.syncId, i, 0, SlotActionType.QUICK_MOVE, mc.player);
                handler.transferDelay = 2;
                return;
            }

            int moved = moveExactItemsFromContainer(
                screenHandler,
                i,
                Items.ENDER_CHEST,
                Math.min(toMove, MAX_EXACT_TRANSFER_PER_TICK)
            );
            if (moved > 0) {
                handler.transferDelay = 2;
            } else {
                handler.containerTask.onStuck();
            }
            return;
        }

        handler.closeAndBreak();
    }

    private void doFortunePickaxeRestock(ScreenHandler screenHandler) {
        if (mc.player == null || mc.interactionManager == null) return;

        if (!screenHandler.getCursorStack().isEmpty()) {
            handler.containerTask.onStuck();
            return;
        }

        if (!handler.tookReplacementPickaxe) {
            int replacementSlot = findBestFortunePickaxeSlotInContainer(screenHandler);
            if (replacementSlot == -1) {
                if (handler.pickaxeSearchMissTicks < 20) {
                    handler.pickaxeSearchMissTicks++;
                    handler.transferDelay = 1;
                    handler.containerTask.resetStuck();
                    return;
                }
                handler.closeAndBreak();
                return;
            }
            handler.pickaxeSearchMissTicks = 0;

            if (swapContainerSlotToHotbar(screenHandler, replacementSlot)) {
                handler.tookReplacementPickaxe = true;
                handler.pickaxeConfirmWaitTicks = 8;
                handler.transferDelay = 2;
            } else if (handler.inventorySupport.hasSpaceForContainerDrop() && quickMoveSlot(screenHandler, replacementSlot)) {
                handler.tookReplacementPickaxe = true;
                handler.pickaxeConfirmWaitTicks = 8;
                handler.transferDelay = 2;
            } else if (tryStoreDisposableStackInOpenContainer()) {
                handler.transferDelay = 2;
            } else if (dropSmallestDisposableStack()) {
                handler.transferDelay = 2;
            } else {
                handler.containerTask.onStuck();
            }
            return;
        }

        if (handler.inventorySupport.findBestFortunePickaxeSlotInInventory() == -1) {
            if (handler.pickaxeConfirmWaitTicks > 0) {
                handler.pickaxeConfirmWaitTicks--;
                handler.containerTask.resetStuck();
                return;
            }

            handler.tookReplacementPickaxe = false;
            handler.transferDelay = 1;
            handler.containerTask.resetStuck();
            return;
        }

        handler.closeAndBreak();
    }

    private boolean swapContainerSlotToHotbar(ScreenHandler screenHandler, int containerSlot) {
        if (mc.player == null || mc.interactionManager == null) return false;
        if (containerSlot < 0 || containerSlot >= 27) return false;

        int hotbarSlot = findFortunePickaxeReceiveHotbarSlot();
        if (hotbarSlot == -1) return false;

        mc.interactionManager.clickSlot(screenHandler.syncId, containerSlot, hotbarSlot, SlotActionType.SWAP, mc.player);

        if (mc.player.getInventory().getSelectedSlot() != hotbarSlot) {
            InvUtils.swap(hotbarSlot, false);
        }

        return true;
    }

    private int findFortunePickaxeReceiveHotbarSlot() {
        if (mc.player == null) return -1;

        int selected = mc.player.getInventory().getSelectedSlot();
        ItemStack selectedStack = mc.player.getInventory().getStack(selected);
        if (isSafeFortuneSwapTarget(selectedStack)) return selected;

        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.isEmpty()) return i;
        }

        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (handler.inventorySupport.isDisposableForSlotReserve(stack) && isSafeFortuneSwapTarget(stack)) return i;
        }

        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (isSafeFortuneSwapTarget(stack) && !stack.isIn(ItemTags.PICKAXES)) return i;
        }

        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (isSafeFortuneSwapTarget(stack)) return i;
        }

        return -1;
    }

    private boolean isSafeFortuneSwapTarget(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return true;
        if (handler.activeContainerItem != null && stack.getItem() == handler.activeContainerItem) return false;
        if (stack.getItem() instanceof BlockItem bi && bi.getBlock() instanceof ShulkerBoxBlock) return false;
        return true;
    }

    private int findBestFortunePickaxeSlotInContainer(ScreenHandler screenHandler) {
        int bestSlot = -1;
        int bestRemaining = -1;
        for (int i = 0; i < 27; i++) {
            ItemStack stack = screenHandler.getSlot(i).getStack();
            if (!handler.inventorySupport.isFortuneThreePickaxeNoSilk(stack)) continue;

            int remaining = stack.isDamageable()
                ? stack.getMaxDamage() - stack.getDamage()
                : Integer.MAX_VALUE;
            if (remaining > bestRemaining) {
                bestRemaining = remaining;
                bestSlot = i;
            }
        }
        return bestSlot;
    }

    private boolean quickMoveSlot(ScreenHandler screenHandler, int slot) {
        if (mc.player == null || mc.interactionManager == null) return false;
        if (slot < 0 || slot >= screenHandler.slots.size()) return false;

        ItemStack before = screenHandler.getSlot(slot).getStack();
        if (before.isEmpty()) return false;

        mc.interactionManager.clickSlot(screenHandler.syncId, slot, 0, SlotActionType.QUICK_MOVE, mc.player);
        return true;
    }

    private boolean tryStoreDisposableStackInOpenContainer() {
        if (mc.player == null || mc.interactionManager == null) return false;

        ScreenHandler screenHandler = mc.player.currentScreenHandler;
        if (screenHandler == null || screenHandler.syncId == 0) return false;
        if (handler.inventorySupport.hasSpaceForContainerDrop()) return true;

        int playerSlot = findSmallestDisposablePlayerSlot(screenHandler);
        if (playerSlot == -1) return false;
        ItemStack stackToMove = screenHandler.getSlot(playerSlot).getStack();
        if (stackToMove.isEmpty()) return false;
        if (!hasContainerRoomForStack(screenHandler, stackToMove)) return false;

        return quickMoveSlot(screenHandler, playerSlot);
    }

    private boolean hasContainerRoomForStack(ScreenHandler screenHandler, ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;

        for (int i = 0; i < 27; i++) {
            ItemStack slotStack = screenHandler.getSlot(i).getStack();
            if (slotStack.isEmpty()) return true;
            if (ItemStack.areItemsAndComponentsEqual(slotStack, stack)
                && slotStack.getCount() < slotStack.getMaxCount()) {
                return true;
            }
        }

        return false;
    }

    private int findSmallestDisposablePlayerSlot(ScreenHandler screenHandler) {
        int playerStart = Math.max(0, screenHandler.slots.size() - 36);
        int playerEnd = screenHandler.slots.size();

        int smallestSlot = -1;
        int smallestCount = Integer.MAX_VALUE;

        for (int i = playerStart; i < playerEnd; i++) {
            ItemStack stack = screenHandler.getSlot(i).getStack();
            if (!handler.inventorySupport.isDisposableForSlotReserve(stack)) continue;
            if (stack.getCount() < smallestCount) {
                smallestCount = stack.getCount();
                smallestSlot = i;
            }
        }

        return smallestSlot;
    }

    private boolean tryMoveObsidianFromContainer(ScreenHandler screenHandler) {
        if (mc.player == null || mc.interactionManager == null) return false;
        if (!canTakeAnotherStack(Items.OBSIDIAN)) return false;

        for (int i = 0; i < 27; i++) {
            ItemStack slotStack = screenHandler.getSlot(i).getStack();
            if (slotStack.isEmpty() || slotStack.getItem() != Items.OBSIDIAN) continue;

            mc.interactionManager.clickSlot(screenHandler.syncId, i, 0, SlotActionType.QUICK_MOVE, mc.player);
            return true;
        }

        return false;
    }

    private int moveExactItemsFromContainer(ScreenHandler screenHandler, int sourceSlot, Item item, int amount) {
        if (mc.player == null || mc.interactionManager == null || amount <= 0) return 0;

        ItemStack sourceStack = screenHandler.getSlot(sourceSlot).getStack();
        if (sourceStack.isEmpty() || sourceStack.getItem() != item) return 0;

        mc.interactionManager.clickSlot(screenHandler.syncId, sourceSlot, 0, SlotActionType.PICKUP, mc.player);

        int moved = 0;
        for (int i = 0; i < amount; i++) {
            int playerSlot = findPlayerSlotForSingleInsert(screenHandler, item);
            if (playerSlot == -1) break;

            mc.interactionManager.clickSlot(screenHandler.syncId, playerSlot, 1, SlotActionType.PICKUP, mc.player);
            moved++;
        }

        if (!screenHandler.getCursorStack().isEmpty()) {
            mc.interactionManager.clickSlot(screenHandler.syncId, sourceSlot, 0, SlotActionType.PICKUP, mc.player);
        }

        return moved;
    }

    private int findPlayerSlotForSingleInsert(ScreenHandler screenHandler, Item item) {
        int playerStart = Math.max(0, screenHandler.slots.size() - 36);
        int playerEnd = screenHandler.slots.size();

        for (int i = playerStart; i < playerEnd; i++) {
            ItemStack stack = screenHandler.getSlot(i).getStack();
            if (stack.isEmpty()) continue;
            if (stack.getItem() == item && stack.getCount() < stack.getMaxCount()) {
                return i;
            }
        }

        for (int i = playerStart; i < playerEnd; i++) {
            if (screenHandler.getSlot(i).getStack().isEmpty()) return i;
        }

        return -1;
    }

    int getDesiredEnderChestCount() {
        int capacity = countObsidianCapacityWithReserve();
        int forObsidian = capacity < 8 ? 0 : capacity / 8;
        return ENDER_CHEST_RESERVE + forObsidian;
    }

    private int countObsidianCapacityWithReserve() {
        if (mc.player == null) return 0;

        int emptySlots = 0;
        int partialObsidianSpace = 0;

        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.isEmpty()) {
                emptySlots++;
                continue;
            }
            if (stack.getItem() == Items.OBSIDIAN && stack.getCount() < stack.getMaxCount()) {
                partialObsidianSpace += stack.getMaxCount() - stack.getCount();
            }
        }

        if (emptySlots > 0) emptySlots--;
        return partialObsidianSpace + emptySlots * 64;
    }
}


