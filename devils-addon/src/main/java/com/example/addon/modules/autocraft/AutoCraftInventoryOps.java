package com.example.addon.modules.autocraft;

import com.example.addon.modules.autocraft.AutoCraftModels.IngredientSpec;
import com.example.addon.modules.autocraft.AutoCraftModels.InventorySnapshot;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.screen.AbstractCraftingScreenHandler;
import net.minecraft.screen.CraftingScreenHandler;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;

public final class AutoCraftInventoryOps {
    public static final int OUTSIDE_SLOT_ID = -999;

    private final MinecraftClient mc = MinecraftClient.getInstance();

    public InventorySnapshot createPlannerSnapshot() {
        LinkedHashMap<String, Integer> counts = new LinkedHashMap<>();
        LinkedHashMap<String, Integer> stackSizes = new LinkedHashMap<>();

        if (mc.player == null) {
            return new InventorySnapshot(counts, stackSizes, AutoCraftModels.PLAYER_INVENTORY_SLOTS);
        }

        PlayerInventory inventory = mc.player.getInventory();
        for (int slot = 0; slot < AutoCraftModels.PLAYER_INVENTORY_SLOTS; slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (stack.isEmpty()) continue;

            String itemId = itemId(stack);
            counts.merge(itemId, stack.getCount(), Integer::sum);
            stackSizes.put(itemId, stack.getMaxCount());
        }

        return new InventorySnapshot(counts, stackSizes, AutoCraftModels.PLAYER_INVENTORY_SLOTS);
    }

    public AbstractCraftingScreenHandler currentCraftingHandler() {
        if (mc.player == null) return null;
        return mc.player.currentScreenHandler instanceof AbstractCraftingScreenHandler handler ? handler : null;
    }

    public boolean isCraftingTableContext() {
        return mc.player != null && mc.player.currentScreenHandler instanceof CraftingScreenHandler;
    }

    public boolean isPlayerCraftingContext() {
        return mc.player != null && mc.player.currentScreenHandler instanceof PlayerScreenHandler;
    }

    public int outputSlotId(AbstractCraftingScreenHandler handler) {
        return handler.getOutputSlot().id;
    }

    public List<Slot> gridSlots(AbstractCraftingScreenHandler handler) {
        return new ArrayList<>(handler.getInputSlots());
    }

    public int findFirstDirtyGridSlotId(AbstractCraftingScreenHandler handler) {
        for (Slot slot : handler.getInputSlots()) {
            if (slot.hasStack()) return slot.id;
        }

        return -1;
    }

    public boolean stackMatches(ItemStack stack, IngredientSpec ingredient) {
        return stack != null && !stack.isEmpty() && ingredient != null && ingredient.matches(itemId(stack));
    }

    public int findBestMatchingSourceSlotId(ScreenHandler handler, IngredientSpec ingredient) {
        int bestSlotId = -1;
        int bestCount = -1;

        for (Slot slot : playerStorageSlots(handler)) {
            ItemStack stack = slot.getStack();
            if (!stackMatches(stack, ingredient)) continue;

            if (stack.getCount() > bestCount) {
                bestCount = stack.getCount();
                bestSlotId = slot.id;
            }
        }

        return bestSlotId;
    }

    public int findDepositSlotId(ScreenHandler handler, ItemStack stack) {
        if (stack == null || stack.isEmpty()) return -1;

        for (Slot slot : playerStorageSlots(handler)) {
            ItemStack slotStack = slot.getStack();
            if (slotStack.isEmpty()) continue;
            if (!ItemStack.areItemsAndComponentsEqual(slotStack, stack)) continue;
            if (slotStack.getCount() >= slotStack.getMaxCount()) continue;
            return slot.id;
        }

        for (Slot slot : playerStorageSlots(handler)) {
            if (!slot.hasStack()) return slot.id;
        }

        return -1;
    }

    private List<Slot> playerStorageSlots(ScreenHandler handler) {
        ArrayList<Slot> slots = new ArrayList<>();
        for (Slot slot : handler.slots) {
            if (slot.inventory instanceof PlayerInventory && slot.getIndex() >= 0 && slot.getIndex() < AutoCraftModels.PLAYER_INVENTORY_SLOTS) {
                slots.add(slot);
            }
        }
        return slots;
    }

    public static String itemId(ItemStack stack) {
        return stack == null || stack.isEmpty() ? "" : itemId(stack.getItem());
    }

    public static String itemId(Item item) {
        return Registries.ITEM.getId(item).toString();
    }
}
