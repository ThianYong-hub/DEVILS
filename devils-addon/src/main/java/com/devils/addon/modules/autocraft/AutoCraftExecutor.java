package com.devils.addon.modules.autocraft;

import com.devils.addon.modules.autocraft.AutoCraftModels.IngredientSpec;
import com.devils.addon.modules.autocraft.AutoCraftModels.PlanStep;
import com.devils.addon.modules.autocraft.AutoCraftModels.RecipeDefinition;
import com.devils.addon.modules.autocraft.AutoCraftModels.RecipeKind;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.AbstractCraftingScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;

public final class AutoCraftExecutor {
    public enum Outcome {
        WAITING,
        ACTION,
        COMPLETED,
        BLOCKED,
        INVALIDATED
    }

    public record TickResult(Outcome outcome, String reason) {
        public static TickResult waiting(String reason) {
            return new TickResult(Outcome.WAITING, reason);
        }

        public static TickResult action(String reason) {
            return new TickResult(Outcome.ACTION, reason);
        }

        public static TickResult completed(String reason) {
            return new TickResult(Outcome.COMPLETED, reason);
        }

        public static TickResult blocked(String reason) {
            return new TickResult(Outcome.BLOCKED, reason);
        }

        public static TickResult invalidated(String reason) {
            return new TickResult(Outcome.INVALIDATED, reason);
        }
    }

    private enum Phase {
        IDLE,
        CLEANUP_PRE,
        PLACE_PREPARE,
        PLACE_PICK_SOURCE,
        PLACE_APPLY_CURSOR,
        PLACE_RETURN_CURSOR,
        OUTPUT_PICKUP,
        OUTPUT_STORE_CURSOR,
        CLEANUP_POST,
        COMPLETED
    }

    private record Placement(int targetSlotId, IngredientSpec ingredient, int requiredCount) {}

    private final MinecraftClient mc = MinecraftClient.getInstance();
    private final AutoCraftInventoryOps inventoryOps;

    private Phase phase = Phase.IDLE;
    private boolean cleanupOnly;
    private boolean dropFinalOutput;
    private int expectedSyncId = -1;
    private int outputSlotId = -1;
    private int outputRemainingBatches;
    private int placementIndex;
    private int currentTargetSlotId = -1;
    private int currentTargetRemaining;
    private int sourceReturnSlotId = -1;
    private List<Placement> placements = List.of();

    public AutoCraftExecutor(AutoCraftInventoryOps inventoryOps) {
        this.inventoryOps = inventoryOps;
    }

    public void reset() {
        phase = Phase.IDLE;
        cleanupOnly = false;
        dropFinalOutput = false;
        expectedSyncId = -1;
        outputSlotId = -1;
        outputRemainingBatches = 0;
        placementIndex = 0;
        currentTargetSlotId = -1;
        currentTargetRemaining = 0;
        sourceReturnSlotId = -1;
        placements = List.of();
    }

    public boolean isRunning() {
        return phase != Phase.IDLE && phase != Phase.COMPLETED;
    }

    public boolean isCleanupOnly() {
        return cleanupOnly;
    }

    public void startCleanup(AbstractCraftingScreenHandler handler) {
        reset();
        cleanupOnly = true;
        expectedSyncId = handler.syncId;
        phase = Phase.CLEANUP_PRE;
    }

    public void startStep(AbstractCraftingScreenHandler handler, PlanStep step, boolean dropFinalOutput) {
        reset();
        this.dropFinalOutput = dropFinalOutput;
        this.expectedSyncId = handler.syncId;
        this.outputSlotId = inventoryOps.outputSlotId(handler);
        this.outputRemainingBatches = step.batches();
        this.placements = buildPlacements(handler, step.recipe(), step.batches());
        this.phase = Phase.CLEANUP_PRE;
    }

    public TickResult tick(AbstractCraftingScreenHandler handler) {
        if (mc.player == null || mc.interactionManager == null) {
            return TickResult.invalidated("Player context is unavailable.");
        }

        if (phase == Phase.IDLE) return TickResult.waiting("Executor is idle.");
        if (handler.syncId != expectedSyncId) return TickResult.invalidated("Crafting screen context changed.");

        for (int safety = 0; safety < 32; safety++) {
            ItemStack cursor = handler.getCursorStack();

            switch (phase) {
                case CLEANUP_PRE -> {
                    int dirtyGridSlotId = inventoryOps.findFirstDirtyGridSlotId(handler);
                    if (dirtyGridSlotId >= 0) {
                        click(handler.syncId, dirtyGridSlotId, 0, SlotActionType.QUICK_MOVE);
                        return TickResult.action("Clearing crafting grid.");
                    }

                    phase = cleanupOnly ? Phase.COMPLETED : Phase.PLACE_PREPARE;
                }
                case PLACE_PREPARE -> {
                    if (placementIndex >= placements.size()) {
                        phase = Phase.OUTPUT_PICKUP;
                        continue;
                    }

                    Placement placement = placements.get(placementIndex);
                    Slot slot = handler.getSlot(placement.targetSlotId());
                    ItemStack targetStack = slot.getStack();
                    if (!targetStack.isEmpty() && !inventoryOps.stackMatches(targetStack, placement.ingredient())) {
                        return TickResult.invalidated("Crafting grid contains an unexpected item.");
                    }

                    currentTargetSlotId = placement.targetSlotId();
                    currentTargetRemaining = Math.max(0, placement.requiredCount() - targetStack.getCount());
                    if (currentTargetRemaining == 0) {
                        placementIndex++;
                        continue;
                    }

                    if (!cursor.isEmpty()) {
                        return TickResult.blocked("Cursor stack must be empty before ingredient placement.");
                    }

                    phase = Phase.PLACE_PICK_SOURCE;
                }
                case PLACE_PICK_SOURCE -> {
                    if (!cursor.isEmpty()) {
                        return TickResult.blocked("Cursor stack must be empty before source pickup.");
                    }

                    Placement placement = placements.get(placementIndex);
                    int sourceSlotId = inventoryOps.findBestMatchingSourceSlotId(handler, placement.ingredient());
                    if (sourceSlotId < 0) {
                        return TickResult.blocked("Missing ingredient for " + placement.ingredient().display() + ".");
                    }

                    sourceReturnSlotId = sourceSlotId;
                    click(handler.syncId, sourceSlotId, 0, SlotActionType.PICKUP);
                    phase = Phase.PLACE_APPLY_CURSOR;
                    return TickResult.action("Picking up ingredient stack.");
                }
                case PLACE_APPLY_CURSOR -> {
                    if (currentTargetRemaining <= 0) {
                        phase = Phase.PLACE_RETURN_CURSOR;
                        continue;
                    }

                    if (cursor.isEmpty()) {
                        phase = Phase.PLACE_PICK_SOURCE;
                        continue;
                    }

                    click(handler.syncId, currentTargetSlotId, 1, SlotActionType.PICKUP);
                    currentTargetRemaining--;
                    if (currentTargetRemaining <= 0) phase = Phase.PLACE_RETURN_CURSOR;
                    return TickResult.action("Placing ingredient into crafting grid.");
                }
                case PLACE_RETURN_CURSOR -> {
                    if (cursor.isEmpty()) {
                        placementIndex++;
                        phase = Phase.PLACE_PREPARE;
                        continue;
                    }

                    click(handler.syncId, sourceReturnSlotId, 0, SlotActionType.PICKUP);
                    return TickResult.action("Returning remaining ingredient stack.");
                }
                case OUTPUT_PICKUP -> {
                    if (!cursor.isEmpty()) {
                        phase = Phase.OUTPUT_STORE_CURSOR;
                        continue;
                    }

                    if (outputRemainingBatches <= 0) {
                        phase = Phase.CLEANUP_POST;
                        continue;
                    }

                    ItemStack outputStack = handler.getSlot(outputSlotId).getStack();
                    if (outputStack.isEmpty()) {
                        return TickResult.blocked("Crafting result is not available yet.");
                    }

                    click(handler.syncId, outputSlotId, 0, SlotActionType.PICKUP);
                    phase = Phase.OUTPUT_STORE_CURSOR;
                    return TickResult.action("Taking crafted output.");
                }
                case OUTPUT_STORE_CURSOR -> {
                    if (cursor.isEmpty()) {
                        outputRemainingBatches--;
                        phase = Phase.OUTPUT_PICKUP;
                        continue;
                    }

                    if (dropFinalOutput) {
                        click(handler.syncId, AutoCraftInventoryOps.OUTSIDE_SLOT_ID, 0, SlotActionType.PICKUP);
                        return TickResult.action("Dropping final crafted output.");
                    }

                    int depositSlotId = inventoryOps.findDepositSlotId(handler, cursor);
                    if (depositSlotId < 0) {
                        return TickResult.blocked("No inventory space for crafted output.");
                    }

                    click(handler.syncId, depositSlotId, 0, SlotActionType.PICKUP);
                    return TickResult.action("Moving crafted output into inventory.");
                }
                case CLEANUP_POST -> {
                    int dirtyGridSlotId = inventoryOps.findFirstDirtyGridSlotId(handler);
                    if (dirtyGridSlotId >= 0) {
                        click(handler.syncId, dirtyGridSlotId, 0, SlotActionType.QUICK_MOVE);
                        return TickResult.action("Collecting crafting remainders.");
                    }

                    phase = Phase.COMPLETED;
                }
                case COMPLETED -> {
                    return TickResult.completed(cleanupOnly ? "Grid cleanup completed." : "Craft step completed.");
                }
                default -> {
                    return TickResult.invalidated("Executor reached an unknown state.");
                }
            }
        }

        return TickResult.invalidated("Executor exceeded its internal state transition budget.");
    }

    private List<Placement> buildPlacements(AbstractCraftingScreenHandler handler, RecipeDefinition recipe, int batches) {
        List<Slot> gridSlots = inventoryOps.gridSlots(handler);
        ArrayList<Placement> planned = new ArrayList<>();

        if (recipe.kind() == RecipeKind.SHAPED) {
            for (int row = 0; row < recipe.height(); row++) {
                for (int col = 0; col < recipe.width(); col++) {
                    int ingredientIndex = row * recipe.width() + col;
                    IngredientSpec ingredient = recipe.ingredients().get(ingredientIndex);
                    if (ingredient == null || ingredient.isEmpty()) continue;

                    int gridIndex = row * handler.getWidth() + col;
                    planned.add(new Placement(gridSlots.get(gridIndex).id, ingredient, batches));
                }
            }
            return planned;
        }

        int nextGridIndex = 0;
        for (IngredientSpec ingredient : recipe.ingredients()) {
            if (ingredient == null || ingredient.isEmpty()) continue;
            planned.add(new Placement(gridSlots.get(nextGridIndex).id, ingredient, batches));
            nextGridIndex++;
        }

        return planned;
    }

    private void click(int syncId, int slotId, int button, SlotActionType actionType) {
        mc.interactionManager.clickSlot(syncId, slotId, button, actionType, mc.player);
    }
}
