package com.devils.addon.modules.stashmover;

import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.ScreenHandler;

/**
 * Pure slot decisions for the pearl chest storage side. Clicking stays with the runtime so packet
 * ordering is untouched.
 */
final class StashMoverPearlChestPolicy {
    private StashMoverPearlChestPolicy() {
    }

    static boolean canStorageAcceptEnderPearls(ScreenHandler handler, int storageSlots) {
        for (int i = 0; i < storageSlots; i++) {
            ItemStack stack = handler.getSlot(i).getStack();
            if (stack.isEmpty()) return true;
            if (stack.isOf(Items.ENDER_PEARL) && stack.getCount() < stack.getMaxCount()) return true;
        }
        return false;
    }

    static int resolvePearlChestReturnSlot(ScreenHandler handler, int storageSlots, int borrowedPearlChestSlot) {
        if (borrowedPearlChestSlot >= 0 && borrowedPearlChestSlot < storageSlots) {
            ItemStack tracked = handler.getSlot(borrowedPearlChestSlot).getStack();
            if (tracked.isEmpty() || tracked.isOf(Items.ENDER_PEARL)) return borrowedPearlChestSlot;
        }
        for (int i = 0; i < storageSlots; i++) {
            if (handler.getSlot(i).getStack().isOf(Items.ENDER_PEARL)) return i;
        }
        for (int i = 0; i < storageSlots; i++) {
            if (handler.getSlot(i).getStack().isEmpty()) return i;
        }
        return -1;
    }
}
