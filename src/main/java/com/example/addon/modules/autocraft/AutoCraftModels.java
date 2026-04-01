package com.example.addon.modules.autocraft;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class AutoCraftModels {
    public static final int PLAYER_GRID_WIDTH = 2;
    public static final int TABLE_GRID_WIDTH = 3;
    public static final int PLAYER_INVENTORY_SLOTS = 36;

    private AutoCraftModels() {}

    public enum RecipeKind {
        SHAPED,
        SHAPELESS
    }

    public record IngredientSpec(List<String> matchingItemIds) {
        public IngredientSpec {
            LinkedHashSet<String> normalized = new LinkedHashSet<>();
            if (matchingItemIds != null) {
                for (String itemId : matchingItemIds) {
                    if (itemId == null) continue;
                    String trimmed = itemId.trim();
                    if (!trimmed.isEmpty()) normalized.add(trimmed);
                }
            }

            matchingItemIds = List.copyOf(normalized);
        }

        public boolean isEmpty() {
            return matchingItemIds.isEmpty();
        }

        public boolean matches(String itemId) {
            return itemId != null && matchingItemIds.contains(itemId);
        }

        public String display() {
            return matchingItemIds.isEmpty() ? "<empty>" : matchingItemIds.get(0);
        }
    }

    public record RecipeDefinition(
        String recipeId,
        String outputItemId,
        int outputCount,
        int width,
        int height,
        RecipeKind kind,
        List<IngredientSpec> ingredients
    ) {
        public RecipeDefinition {
            recipeId = requireId(recipeId, "recipeId");
            outputItemId = requireId(outputItemId, "outputItemId");
            if (outputCount <= 0) throw new IllegalArgumentException("outputCount must be > 0");
            if (width <= 0) throw new IllegalArgumentException("width must be > 0");
            if (height <= 0) throw new IllegalArgumentException("height must be > 0");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(ingredients, "ingredients");
            ingredients = List.copyOf(ingredients);
        }

        public int occupiedIngredientCount() {
            int count = 0;
            for (IngredientSpec ingredient : ingredients) {
                if (ingredient != null && !ingredient.isEmpty()) count++;
            }
            return count;
        }

        public boolean requiresThreeByThree() {
            return width > PLAYER_GRID_WIDTH
                || height > PLAYER_GRID_WIDTH
                || occupiedIngredientCount() > PLAYER_GRID_WIDTH * PLAYER_GRID_WIDTH;
        }

        public Map<IngredientSpec, Integer> ingredientDemands(int batches) {
            LinkedHashMap<IngredientSpec, Integer> demands = new LinkedHashMap<>();
            if (batches <= 0) return demands;

            for (IngredientSpec ingredient : ingredients) {
                if (ingredient == null || ingredient.isEmpty()) continue;
                demands.merge(ingredient, batches, Integer::sum);
            }

            return demands;
        }
    }

    public static final class RecipeBook {
        private final List<RecipeDefinition> allRecipes;
        private final Map<String, List<RecipeDefinition>> recipesByOutput;

        public RecipeBook(Collection<RecipeDefinition> recipes) {
            ArrayList<RecipeDefinition> normalized = new ArrayList<>();
            if (recipes != null) {
                for (RecipeDefinition recipe : recipes) {
                    if (recipe != null) normalized.add(recipe);
                }
            }

            normalized.sort(Comparator.comparing(RecipeDefinition::recipeId));
            this.allRecipes = List.copyOf(normalized);

            LinkedHashMap<String, List<RecipeDefinition>> byOutput = new LinkedHashMap<>();
            for (RecipeDefinition recipe : allRecipes) {
                byOutput.computeIfAbsent(recipe.outputItemId(), key -> new ArrayList<>()).add(recipe);
            }

            LinkedHashMap<String, List<RecipeDefinition>> frozen = new LinkedHashMap<>();
            for (Map.Entry<String, List<RecipeDefinition>> entry : byOutput.entrySet()) {
                frozen.put(entry.getKey(), List.copyOf(entry.getValue()));
            }
            this.recipesByOutput = Map.copyOf(frozen);
        }

        public List<RecipeDefinition> recipesFor(String outputItemId) {
            if (outputItemId == null) return List.of();
            return recipesByOutput.getOrDefault(outputItemId, List.of());
        }

        public boolean hasRecipesFor(String outputItemId) {
            return !recipesFor(outputItemId).isEmpty();
        }

        public List<RecipeDefinition> allRecipes() {
            return allRecipes;
        }
    }

    public record InventorySnapshot(
        Map<String, Integer> itemCounts,
        Map<String, Integer> maxStackSizes,
        int totalSlots
    ) {
        public InventorySnapshot {
            itemCounts = normalizeCountMap(itemCounts);
            maxStackSizes = normalizeStackMap(maxStackSizes);
            totalSlots = Math.max(0, totalSlots);
        }

        public int count(String itemId) {
            if (itemId == null) return 0;
            return itemCounts.getOrDefault(itemId, 0);
        }

        public int maxStackSize(String itemId) {
            if (itemId == null) return 64;
            return Math.max(1, maxStackSizes.getOrDefault(itemId, 64));
        }

        public int usedSlots() {
            int used = 0;
            for (Map.Entry<String, Integer> entry : itemCounts.entrySet()) {
                used += ceilDiv(entry.getValue(), maxStackSize(entry.getKey()));
            }
            return used;
        }

        public int freeSlots() {
            return Math.max(0, totalSlots - usedSlots());
        }
    }

    public record PlannerInput(
        List<String> targetItemIds,
        Set<String> blacklistedOutputIds,
        boolean craftAll,
        int remainingLimit,
        boolean dropFinalOutput,
        boolean allowThreeByThree,
        InventorySnapshot inventory,
        RecipeBook recipeBook
    ) {
        public PlannerInput {
            targetItemIds = targetItemIds == null ? List.of() : List.copyOf(targetItemIds);
            blacklistedOutputIds = blacklistedOutputIds == null ? Set.of() : Set.copyOf(blacklistedOutputIds);
            remainingLimit = Math.max(0, remainingLimit);
            Objects.requireNonNull(inventory, "inventory");
            Objects.requireNonNull(recipeBook, "recipeBook");
        }
    }

    public record PlanStep(RecipeDefinition recipe, int batches, boolean finalStep) {
        public PlanStep {
            Objects.requireNonNull(recipe, "recipe");
            if (batches <= 0) throw new IllegalArgumentException("batches must be > 0");
        }

        public String outputItemId() {
            return recipe.outputItemId();
        }

        public int producedItemCount() {
            return recipe.outputCount() * batches;
        }

        public boolean requiresThreeByThree() {
            return recipe.requiresThreeByThree();
        }
    }

    public record Plan(
        String targetItemId,
        int craftedItemCount,
        int targetBatches,
        List<PlanStep> steps,
        boolean requiresThreeByThree
    ) {
        public Plan {
            targetItemId = requireId(targetItemId, "targetItemId");
            if (craftedItemCount <= 0) throw new IllegalArgumentException("craftedItemCount must be > 0");
            if (targetBatches <= 0) throw new IllegalArgumentException("targetBatches must be > 0");
            Objects.requireNonNull(steps, "steps");
            steps = List.copyOf(steps);
            if (steps.isEmpty()) throw new IllegalArgumentException("steps must not be empty");
        }
    }

    public record PlannerResult(Plan plan, String failureReason) {
        public static PlannerResult success(Plan plan) {
            return new PlannerResult(Objects.requireNonNull(plan, "plan"), null);
        }

        public static PlannerResult failure(String failureReason) {
            String reason = failureReason == null || failureReason.isBlank()
                ? "No reachable crafting path."
                : failureReason;
            return new PlannerResult(null, reason);
        }

        public boolean success() {
            return plan != null;
        }
    }

    private static String requireId(String value, String name) {
        if (value == null) throw new IllegalArgumentException(name + " must not be null");
        String trimmed = value.trim();
        if (trimmed.isEmpty()) throw new IllegalArgumentException(name + " must not be blank");
        return trimmed;
    }

    private static Map<String, Integer> normalizeCountMap(Map<String, Integer> source) {
        LinkedHashMap<String, Integer> normalized = new LinkedHashMap<>();
        if (source != null) {
            source.forEach((itemId, count) -> {
                if (itemId == null || count == null || count <= 0) return;
                String trimmed = itemId.trim();
                if (!trimmed.isEmpty()) normalized.put(trimmed, count);
            });
        }
        return Map.copyOf(normalized);
    }

    private static Map<String, Integer> normalizeStackMap(Map<String, Integer> source) {
        LinkedHashMap<String, Integer> normalized = new LinkedHashMap<>();
        if (source != null) {
            source.forEach((itemId, stackSize) -> {
                if (itemId == null || stackSize == null || stackSize <= 0) return;
                String trimmed = itemId.trim();
                if (!trimmed.isEmpty()) normalized.put(trimmed, stackSize);
            });
        }
        return Map.copyOf(normalized);
    }

    public static int ceilDiv(int value, int divisor) {
        if (divisor <= 0) throw new IllegalArgumentException("divisor must be > 0");
        if (value <= 0) return 0;
        return (value + divisor - 1) / divisor;
    }
}
