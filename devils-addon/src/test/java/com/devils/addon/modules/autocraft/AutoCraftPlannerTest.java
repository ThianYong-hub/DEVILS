package com.devils.addon.modules.autocraft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.devils.addon.modules.autocraft.AutoCraftModels.IngredientSpec;
import com.devils.addon.modules.autocraft.AutoCraftModels.InventorySnapshot;
import com.devils.addon.modules.autocraft.AutoCraftModels.Plan;
import com.devils.addon.modules.autocraft.AutoCraftModels.PlannerInput;
import com.devils.addon.modules.autocraft.AutoCraftModels.PlannerResult;
import com.devils.addon.modules.autocraft.AutoCraftModels.RecipeBook;
import com.devils.addon.modules.autocraft.AutoCraftModels.RecipeDefinition;
import com.devils.addon.modules.autocraft.AutoCraftModels.RecipeKind;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AutoCraftPlannerTest {
    private final AutoCraftPlanner planner = new AutoCraftPlanner();

    @Test
    void selectsNextReachableTargetWhenFirstFails() {
        RecipeDefinition planks = recipe("oak_planks", "minecraft:oak_planks", 4, 1, 1, RecipeKind.SHAPED, spec("minecraft:oak_log"));
        RecipeDefinition sticks = recipe("sticks", "minecraft:stick", 4, 1, 2, RecipeKind.SHAPED, spec("minecraft:oak_planks"), spec("minecraft:oak_planks"));

        PlannerResult result = planner.plan(input(
            List.of("minecraft:oak_slab", "minecraft:stick"),
            Set.of(),
            false,
            AutoCraftPolicies.remainingLimit(0, 0),
            false,
            true,
            inventory(36, Map.of("minecraft:oak_log", 1), planks, sticks),
            planks,
            sticks
        ));

        assertTrue(result.success());
        assertEquals("minecraft:stick", result.plan().targetItemId());
    }

    @Test
    void buildsLogsToPlanksToSlabsChain() {
        RecipeDefinition planks = recipe("oak_planks", "minecraft:oak_planks", 4, 1, 1, RecipeKind.SHAPED, spec("minecraft:oak_log"));
        RecipeDefinition slabs = recipe(
            "oak_slab",
            "minecraft:oak_slab",
            6,
            3,
            1,
            RecipeKind.SHAPED,
            spec("minecraft:oak_planks"),
            spec("minecraft:oak_planks"),
            spec("minecraft:oak_planks")
        );

        PlannerResult result = planner.plan(input(
            List.of("minecraft:oak_slab"),
            Set.of(),
            false,
            AutoCraftPolicies.remainingLimit(0, 0),
            false,
            true,
            inventory(36, Map.of("minecraft:oak_log", 1), planks, slabs),
            planks,
            slabs
        ));

        assertTrue(result.success());
        Plan plan = result.plan();
        assertEquals(6, plan.craftedItemCount());
        assertEquals(2, plan.steps().size());
        assertEquals("minecraft:oak_planks", plan.steps().get(0).outputItemId());
        assertEquals("minecraft:oak_slab", plan.steps().get(1).outputItemId());
    }

    @Test
    void plansThreeByThreeChainEvenWhenTableContextIsNotOpenYet() {
        RecipeDefinition planks = recipe("oak_planks", "minecraft:oak_planks", 4, 1, 1, RecipeKind.SHAPED, spec("minecraft:oak_log"));
        RecipeDefinition chest = recipe(
            "chest",
            "minecraft:chest",
            1,
            3,
            3,
            RecipeKind.SHAPED,
            spec("minecraft:oak_planks"),
            spec("minecraft:oak_planks"),
            spec("minecraft:oak_planks"),
            spec("minecraft:oak_planks"),
            spec(),
            spec("minecraft:oak_planks"),
            spec("minecraft:oak_planks"),
            spec("minecraft:oak_planks"),
            spec("minecraft:oak_planks")
        );

        PlannerResult result = planner.plan(input(
            List.of("minecraft:chest"),
            Set.of(),
            false,
            AutoCraftPolicies.remainingLimit(0, 0),
            false,
            false,
            inventory(36, Map.of("minecraft:oak_log", 2), planks, chest),
            planks,
            chest
        ));

        assertTrue(result.success());
        assertTrue(result.plan().requiresThreeByThree());
        assertEquals(2, result.plan().steps().size());
        assertEquals("minecraft:oak_planks", result.plan().steps().get(0).outputItemId());
        assertEquals("minecraft:chest", result.plan().steps().get(1).outputItemId());
    }

    @Test
    void usesExistingIntermediatesBeforeCraftingMore() {
        RecipeDefinition planks = recipe("oak_planks", "minecraft:oak_planks", 4, 1, 1, RecipeKind.SHAPED, spec("minecraft:oak_log"));
        RecipeDefinition slabs = recipe(
            "oak_slab",
            "minecraft:oak_slab",
            6,
            3,
            1,
            RecipeKind.SHAPED,
            spec("minecraft:oak_planks"),
            spec("minecraft:oak_planks"),
            spec("minecraft:oak_planks")
        );

        PlannerResult result = planner.plan(input(
            List.of("minecraft:oak_slab"),
            Set.of(),
            false,
            AutoCraftPolicies.remainingLimit(0, 0),
            false,
            true,
            inventory(36, Map.of("minecraft:oak_planks", 3, "minecraft:oak_log", 1), planks, slabs),
            planks,
            slabs
        ));

        assertTrue(result.success());
        assertEquals(1, result.plan().steps().size());
        assertEquals("minecraft:oak_slab", result.plan().steps().get(0).outputItemId());
    }

    @Test
    void blacklistReroutesAroundBlacklistedIntermediateOutput() {
        RecipeDefinition blackIngot = recipe("black_ingot", "devils:black_ingot", 1, 1, 1, RecipeKind.SHAPED, spec("devils:black_ore"));
        RecipeDefinition cleanIngot = recipe("clean_ingot", "devils:clean_ingot", 1, 1, 1, RecipeKind.SHAPED, spec("devils:clean_ore"));
        RecipeDefinition finalViaBlack = recipe("a_final_via_black", "devils:final_tool", 1, 1, 1, RecipeKind.SHAPED, spec("devils:black_ingot"));
        RecipeDefinition finalViaClean = recipe("b_final_via_clean", "devils:final_tool", 1, 1, 1, RecipeKind.SHAPED, spec("devils:clean_ingot"));

        PlannerResult result = planner.plan(input(
            List.of("devils:final_tool"),
            Set.of("devils:black_ingot"),
            false,
            AutoCraftPolicies.remainingLimit(0, 0),
            false,
            true,
            inventory(36, Map.of("devils:black_ore", 1, "devils:clean_ore", 1), blackIngot, cleanIngot, finalViaBlack, finalViaClean),
            blackIngot,
            cleanIngot,
            finalViaBlack,
            finalViaClean
        ));

        assertTrue(result.success());
        assertTrue(result.plan().steps().stream().noneMatch(step -> step.outputItemId().equals("devils:black_ingot")));
        assertTrue(result.plan().steps().stream().anyMatch(step -> step.outputItemId().equals("devils:clean_ingot")));
    }

    @Test
    void craftAllHonorsLimit() {
        RecipeDefinition sticks = recipe(
            "sticks",
            "minecraft:stick",
            4,
            1,
            2,
            RecipeKind.SHAPED,
            spec("minecraft:oak_planks"),
            spec("minecraft:oak_planks")
        );

        PlannerResult result = planner.plan(input(
            List.of("minecraft:stick"),
            Set.of(),
            true,
            8,
            false,
            true,
            inventory(36, Map.of("minecraft:oak_planks", 8), sticks),
            sticks
        ));

        assertTrue(result.success());
        assertEquals(8, result.plan().craftedItemCount());
        assertEquals(2, result.plan().targetBatches());
    }

    @Test
    void rejectsWhenLimitBelowSingleBatch() {
        RecipeDefinition sticks = recipe(
            "sticks",
            "minecraft:stick",
            4,
            1,
            2,
            RecipeKind.SHAPED,
            spec("minecraft:oak_planks"),
            spec("minecraft:oak_planks")
        );

        PlannerResult result = planner.plan(input(
            List.of("minecraft:stick"),
            Set.of(),
            true,
            3,
            false,
            true,
            inventory(36, Map.of("minecraft:oak_planks", 8), sticks),
            sticks
        ));

        assertFalse(result.success());
        assertTrue(result.failureReason().contains("Remaining limit"));
    }

    @Test
    void detectsRecipeCycles() {
        RecipeDefinition a = recipe("a_from_b", "devils:a", 1, 1, 1, RecipeKind.SHAPED, spec("devils:b"));
        RecipeDefinition b = recipe("b_from_a", "devils:b", 1, 1, 1, RecipeKind.SHAPED, spec("devils:a"));

        PlannerResult result = planner.plan(input(
            List.of("devils:a"),
            Set.of(),
            false,
            AutoCraftPolicies.remainingLimit(0, 0),
            false,
            true,
            inventory(36, Map.of(), a, b),
            a,
            b
        ));

        assertFalse(result.success());
        assertTrue(result.failureReason().contains("Unable") || result.failureReason().contains("cycle"));
    }

    @Test
    void inventoryPressureBlocksStoredOutputButDropAllowsIt() {
        RecipeDefinition stone = recipe("stone", "minecraft:stone", 1, 1, 1, RecipeKind.SHAPED, spec("minecraft:cobblestone"));

        PlannerResult blocked = planner.plan(input(
            List.of("minecraft:stone"),
            Set.of(),
            false,
            AutoCraftPolicies.remainingLimit(0, 0),
            false,
            true,
            inventory(1, Map.of("minecraft:cobblestone", 64), stone),
            stone
        ));

        PlannerResult allowed = planner.plan(input(
            List.of("minecraft:stone"),
            Set.of(),
            false,
            AutoCraftPolicies.remainingLimit(0, 0),
            true,
            true,
            inventory(1, Map.of("minecraft:cobblestone", 64), stone),
            stone
        ));

        assertFalse(blocked.success());
        assertTrue(allowed.success());
        assertEquals(1, allowed.plan().craftedItemCount());
    }

    private static PlannerInput input(
        List<String> targets,
        Set<String> blacklist,
        boolean craftAll,
        int remainingLimit,
        boolean dropFinalOutput,
        boolean allowThreeByThree,
        InventorySnapshot inventory,
        RecipeDefinition... recipes
    ) {
        return new PlannerInput(
            targets,
            blacklist,
            craftAll,
            remainingLimit,
            dropFinalOutput,
            allowThreeByThree,
            inventory,
            new RecipeBook(List.of(recipes))
        );
    }

    private static InventorySnapshot inventory(int totalSlots, Map<String, Integer> counts, RecipeDefinition... recipes) {
        Map<String, Integer> stackSizes = new HashMap<>();
        counts.keySet().forEach(itemId -> stackSizes.put(itemId, 64));
        for (RecipeDefinition recipe : recipes) stackSizes.put(recipe.outputItemId(), 64);
        return new InventorySnapshot(counts, stackSizes, totalSlots);
    }

    private static RecipeDefinition recipe(
        String recipeId,
        String outputItemId,
        int outputCount,
        int width,
        int height,
        RecipeKind kind,
        IngredientSpec... ingredients
    ) {
        return new RecipeDefinition(recipeId, outputItemId, outputCount, width, height, kind, List.of(ingredients));
    }

    private static IngredientSpec spec(String... itemIds) {
        return new IngredientSpec(List.of(itemIds));
    }
}
