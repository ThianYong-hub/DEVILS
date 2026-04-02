package com.example.addon.modules.autocraft;

import com.example.addon.modules.autocraft.AutoCraftModels.IngredientSpec;
import com.example.addon.modules.autocraft.AutoCraftModels.InventorySnapshot;
import com.example.addon.modules.autocraft.AutoCraftModels.Plan;
import com.example.addon.modules.autocraft.AutoCraftModels.PlanStep;
import com.example.addon.modules.autocraft.AutoCraftModels.PlannerInput;
import com.example.addon.modules.autocraft.AutoCraftModels.PlannerResult;
import com.example.addon.modules.autocraft.AutoCraftModels.RecipeBook;
import com.example.addon.modules.autocraft.AutoCraftModels.RecipeDefinition;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class AutoCraftPlanner {
    private static final int MAX_BATCH_SEARCH = 4096;

    public PlannerResult plan(PlannerInput input) {
        if (input.targetItemIds().isEmpty()) {
            return PlannerResult.failure("Items list is empty.");
        }

        String firstFailure = null;

        for (String targetItemId : input.targetItemIds()) {
            if (targetItemId == null || targetItemId.isBlank()) continue;

            PlannerResult result = planTarget(targetItemId.trim(), input);
            if (result.success()) return result;

            if (firstFailure == null) firstFailure = result.failureReason();
        }

        return PlannerResult.failure(firstFailure != null ? firstFailure : "No reachable target item.");
    }

    private PlannerResult planTarget(String targetItemId, PlannerInput input) {
        if (input.blacklistedOutputIds().contains(targetItemId)) {
            return PlannerResult.failure("Target " + targetItemId + " is blocked by recipe blacklist.");
        }

        List<RecipeDefinition> rootRecipes = allowedRecipes(input.recipeBook(), targetItemId, input);
        if (rootRecipes.isEmpty()) {
            return PlannerResult.failure("No allowed crafting recipe for " + targetItemId + ".");
        }

        Plan bestPlan = null;
        String failure = null;

        for (RecipeDefinition rootRecipe : rootRecipes) {
            Plan plan = input.craftAll()
                ? planBestBatchRange(targetItemId, rootRecipe, input)
                : tryPlanRoot(targetItemId, rootRecipe, 1, input);

            if (plan == null) {
                if (failure == null) {
                    int maxBatchesForLimit = AutoCraftPolicies.maxBatchesForLimit(
                        input.remainingLimit(),
                        0,
                        rootRecipe.outputCount()
                    );

                    failure = maxBatchesForLimit == 0
                        ? "Remaining limit is below the smallest recipe batch for " + targetItemId + "."
                        : "Unable to build a reachable path for " + targetItemId + ".";
                }
                continue;
            }

            if (bestPlan == null || isBetterPlan(plan, bestPlan)) bestPlan = plan;
        }

        return bestPlan != null
            ? PlannerResult.success(bestPlan)
            : PlannerResult.failure(failure != null ? failure : "Unable to craft " + targetItemId + ".");
    }

    private Plan planBestBatchRange(String targetItemId, RecipeDefinition rootRecipe, PlannerInput input) {
        int limitBatches = AutoCraftPolicies.maxBatchesForLimit(input.remainingLimit(), 0, rootRecipe.outputCount());
        if (limitBatches == 0) return null;

        int maxBatches = limitBatches == Integer.MAX_VALUE
            ? MAX_BATCH_SEARCH
            : Math.min(limitBatches, MAX_BATCH_SEARCH);

        int lastSuccess = 0;
        int firstFailure = -1;
        Plan bestPlan = null;
        int probe = 1;

        while (probe <= maxBatches) {
            Plan plan = tryPlanRoot(targetItemId, rootRecipe, probe, input);
            if (plan == null) {
                firstFailure = probe;
                break;
            }

            bestPlan = plan;
            lastSuccess = probe;

            if (probe == maxBatches) return bestPlan;
            probe = Math.min(maxBatches, probe * 2);
        }

        if (bestPlan == null) return null;
        if (firstFailure == -1) return bestPlan;

        int low = lastSuccess + 1;
        int high = firstFailure - 1;

        while (low <= high) {
            int mid = low + (high - low) / 2;
            Plan plan = tryPlanRoot(targetItemId, rootRecipe, mid, input);

            if (plan != null) {
                bestPlan = plan;
                low = mid + 1;
            } else {
                high = mid - 1;
            }
        }

        return bestPlan;
    }

    private Plan tryPlanRoot(String targetItemId, RecipeDefinition rootRecipe, int batches, PlannerInput input) {
        if (batches <= 0) return null;

        int producedCount = rootRecipe.outputCount() * batches;
        if (input.remainingLimit() > 0 && producedCount > input.remainingLimit()) return null;

        WorkingState state = new WorkingState(input.inventory());
        if (!craftRecipe(rootRecipe, batches, true, state, input, new LinkedHashSet<>())) return null;

        return new Plan(
            targetItemId,
            producedCount,
            batches,
            List.copyOf(state.steps),
            state.requiresThreeByThree
        );
    }

    private boolean craftRecipe(
        RecipeDefinition recipe,
        int batches,
        boolean finalStep,
        WorkingState state,
        PlannerInput input,
        Set<String> activeOutputs
    ) {
        for (Map.Entry<IngredientSpec, Integer> entry : recipe.ingredientDemands(batches).entrySet()) {
            if (!resolveIngredient(entry.getKey(), entry.getValue(), state, input, activeOutputs)) return false;
        }

        int producedCount = recipe.outputCount() * batches;
        boolean keepOutput = !(finalStep && input.dropFinalOutput());

        if (keepOutput && !state.canStore(recipe.outputItemId(), producedCount)) {
            state.failureReason = "Inventory cannot hold " + producedCount + "x " + recipe.outputItemId() + ".";
            return false;
        }

        if (keepOutput) state.add(recipe.outputItemId(), producedCount);
        state.steps.add(new PlanStep(recipe, batches, finalStep));
        state.requiresThreeByThree |= recipe.requiresThreeByThree();
        return true;
    }

    private boolean resolveIngredient(
        IngredientSpec ingredient,
        int requiredCount,
        WorkingState state,
        PlannerInput input,
        Set<String> activeOutputs
    ) {
        WorkingState consumedExisting = state.copy();
        int remaining = requiredCount - consumedExisting.consumeMatching(ingredient, requiredCount);
        if (remaining <= 0) {
            state.copyFrom(consumedExisting);
            return true;
        }

        List<String> candidates = candidateItemIds(ingredient, input.recipeBook(), consumedExisting);
        if (candidates.isEmpty()) {
            state.failureReason = "Ingredient " + ingredient.display() + " is unavailable.";
            return false;
        }

        for (String candidateItemId : candidates) {
            WorkingState branch = consumedExisting.copy();
            if (!reserveItem(candidateItemId, remaining, branch, input, activeOutputs)) continue;
            state.copyFrom(branch);
            return true;
        }

        state.failureReason = "Unable to source " + requiredCount + "x " + ingredient.display() + ".";
        return false;
    }

    private boolean reserveItem(
        String itemId,
        int requiredCount,
        WorkingState state,
        PlannerInput input,
        Set<String> activeOutputs
    ) {
        int remaining = requiredCount - state.consume(itemId, requiredCount);
        if (remaining <= 0) return true;

        if (!activeOutputs.add(itemId)) {
            state.failureReason = "Detected recipe cycle while resolving " + itemId + ".";
            return false;
        }

        try {
            List<RecipeDefinition> recipes = allowedRecipes(input.recipeBook(), itemId, input);
            if (recipes.isEmpty()) {
                state.failureReason = "No recipe path produces " + itemId + ".";
                return false;
            }

            for (RecipeDefinition recipe : recipes) {
                WorkingState branch = state.copy();
                int batches = AutoCraftModels.ceilDiv(remaining, recipe.outputCount());
                if (!craftRecipe(recipe, batches, false, branch, input, activeOutputs)) continue;

                if (branch.consume(itemId, remaining) < remaining) continue;
                state.copyFrom(branch);
                return true;
            }

            state.failureReason = "Unable to craft enough " + itemId + ".";
            return false;
        } finally {
            activeOutputs.remove(itemId);
        }
    }

    private List<RecipeDefinition> allowedRecipes(RecipeBook recipeBook, String outputItemId, PlannerInput input) {
        List<RecipeDefinition> allowed = new ArrayList<>();

        for (RecipeDefinition recipe : recipeBook.recipesFor(outputItemId)) {
            if (input.blacklistedOutputIds().contains(recipe.outputItemId())) continue;
            allowed.add(recipe);
        }

        allowed.sort(Comparator.comparing(RecipeDefinition::recipeId));
        return allowed;
    }

    private List<String> candidateItemIds(IngredientSpec ingredient, RecipeBook recipeBook, WorkingState state) {
        ArrayList<String> candidates = new ArrayList<>();
        for (String itemId : ingredient.matchingItemIds()) {
            if (state.count(itemId) > 0 || recipeBook.hasRecipesFor(itemId)) candidates.add(itemId);
        }

        candidates.sort(Comparator
            .comparingInt((String itemId) -> state.count(itemId)).reversed()
            .thenComparing(itemId -> recipeBook.recipesFor(itemId).size())
            .thenComparing(Comparator.naturalOrder()));

        return candidates;
    }

    private boolean isBetterPlan(Plan candidate, Plan current) {
        if (candidate.craftedItemCount() != current.craftedItemCount()) {
            return candidate.craftedItemCount() > current.craftedItemCount();
        }

        if (candidate.steps().size() != current.steps().size()) {
            return candidate.steps().size() < current.steps().size();
        }

        return candidate.targetItemId().compareTo(current.targetItemId()) < 0;
    }

    private static final class WorkingState {
        private final LinkedHashMap<String, Integer> counts = new LinkedHashMap<>();
        private final LinkedHashMap<String, Integer> stackSizes = new LinkedHashMap<>();
        private final int totalSlots;
        private final ArrayList<PlanStep> steps = new ArrayList<>();
        private boolean requiresThreeByThree;
        private String failureReason;

        private WorkingState(InventorySnapshot snapshot) {
            counts.putAll(snapshot.itemCounts());
            stackSizes.putAll(snapshot.maxStackSizes());
            totalSlots = snapshot.totalSlots();
        }

        private WorkingState(WorkingState other) {
            counts.putAll(other.counts);
            stackSizes.putAll(other.stackSizes);
            totalSlots = other.totalSlots;
            steps.addAll(other.steps);
            requiresThreeByThree = other.requiresThreeByThree;
            failureReason = other.failureReason;
        }

        private WorkingState copy() {
            return new WorkingState(this);
        }

        private void copyFrom(WorkingState other) {
            counts.clear();
            counts.putAll(other.counts);
            stackSizes.clear();
            stackSizes.putAll(other.stackSizes);
            steps.clear();
            steps.addAll(other.steps);
            requiresThreeByThree = other.requiresThreeByThree;
            failureReason = other.failureReason;
        }

        private int count(String itemId) {
            return counts.getOrDefault(itemId, 0);
        }

        private int consume(String itemId, int requiredCount) {
            if (requiredCount <= 0) return 0;

            int available = counts.getOrDefault(itemId, 0);
            int consumed = Math.min(requiredCount, available);
            if (consumed <= 0) return 0;

            int remaining = available - consumed;
            if (remaining > 0) counts.put(itemId, remaining);
            else counts.remove(itemId);

            return consumed;
        }

        private int consumeMatching(IngredientSpec ingredient, int requiredCount) {
            int consumed = 0;

            ArrayList<String> ordered = new ArrayList<>(ingredient.matchingItemIds());
            ordered.sort(Comparator.comparingInt(this::count).reversed().thenComparing(Comparator.naturalOrder()));

            for (String itemId : ordered) {
                int delta = consume(itemId, requiredCount - consumed);
                consumed += delta;
                if (consumed >= requiredCount) break;
            }

            return consumed;
        }

        private void add(String itemId, int count) {
            if (count <= 0) return;
            counts.merge(itemId, count, Integer::sum);
        }

        private boolean canStore(String itemId, int deltaCount) {
            if (deltaCount <= 0) return true;

            int before = usedSlots();
            int currentCount = counts.getOrDefault(itemId, 0);
            int stackSize = Math.max(1, stackSizes.getOrDefault(itemId, 64));
            int after = before
                - AutoCraftModels.ceilDiv(currentCount, stackSize)
                + AutoCraftModels.ceilDiv(currentCount + deltaCount, stackSize);

            return after <= totalSlots;
        }

        private int usedSlots() {
            int used = 0;
            for (Map.Entry<String, Integer> entry : counts.entrySet()) {
                int stackSize = Math.max(1, stackSizes.getOrDefault(entry.getKey(), 64));
                used += AutoCraftModels.ceilDiv(entry.getValue(), stackSize);
            }
            return used;
        }
    }
}
