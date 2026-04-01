package com.example.addon.modules.autocraft;

import com.example.addon.modules.autocraft.AutoCraftModels.IngredientSpec;
import com.example.addon.modules.autocraft.AutoCraftModels.RecipeBook;
import com.example.addon.modules.autocraft.AutoCraftModels.RecipeDefinition;
import com.example.addon.modules.autocraft.AutoCraftModels.RecipeKind;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import net.fabricmc.fabric.api.recipe.v1.FabricRecipeManager;
import net.fabricmc.fabric.api.recipe.v1.sync.SynchronizedRecipes;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.item.ItemStack;
import net.minecraft.recipe.CraftingRecipe;
import net.minecraft.recipe.Ingredient;
import net.minecraft.recipe.RecipeEntry;
import net.minecraft.recipe.RecipeManager;
import net.minecraft.recipe.RecipeType;
import net.minecraft.recipe.ServerRecipeManager;
import net.minecraft.recipe.ShapedRecipe;
import net.minecraft.recipe.ShapelessRecipe;
import net.minecraft.recipe.input.CraftingRecipeInput;
import net.minecraft.registry.Registries;

public final class AutoCraftRecipeCatalogue {
    private Object cachedSource;
    private SynchronizedRecipes cachedRecipes;
    private RecipeBook cachedBook = new RecipeBook(List.of());

    public RecipeBook snapshot(MinecraftClient mc) {
        if (mc == null || mc.world == null) return new RecipeBook(List.of());

        ServerRecipeManager serverRecipeManager = resolveServerRecipeManager(mc);
        if (serverRecipeManager != null) {
            if (serverRecipeManager == cachedSource) return cachedBook;

            ArrayList<RecipeDefinition> definitions = new ArrayList<>();
            for (RecipeEntry<?> entry : serverRecipeManager.values()) {
                if (!(entry.value() instanceof CraftingRecipe)) continue;
                RecipeDefinition definition = toDefinition(castCraftingEntry(entry), mc);
                if (definition != null) definitions.add(definition);
            }

            cachedSource = serverRecipeManager;
            cachedRecipes = null;
            cachedBook = new RecipeBook(definitions);
            return cachedBook;
        }

        RecipeManager recipeManager = resolveRecipeManager(mc);
        if (!(recipeManager instanceof FabricRecipeManager fabricRecipeManager)) return new RecipeBook(List.of());

        SynchronizedRecipes synchronizedRecipes = fabricRecipeManager.getSynchronizedRecipes();
        if (synchronizedRecipes == null) return new RecipeBook(List.of());
        if (synchronizedRecipes == cachedRecipes && synchronizedRecipes == cachedSource) return cachedBook;

        ArrayList<RecipeDefinition> definitions = new ArrayList<>();
        for (RecipeEntry<CraftingRecipe> entry : synchronizedRecipes.getAllOfType(RecipeType.CRAFTING)) {
            RecipeDefinition definition = toDefinition(entry, mc);
            if (definition != null) definitions.add(definition);
        }

        cachedSource = synchronizedRecipes;
        cachedRecipes = synchronizedRecipes;
        cachedBook = new RecipeBook(definitions);
        return cachedBook;
    }

    private RecipeManager resolveRecipeManager(MinecraftClient mc) {
        ClientPlayNetworkHandler networkHandler = mc.getNetworkHandler();
        if (networkHandler != null) {
            RecipeManager networkRecipes = networkHandler.getRecipeManager();
            if (networkRecipes != null) return networkRecipes;
        }

        return mc.world.getRecipeManager();
    }

    private ServerRecipeManager resolveServerRecipeManager(MinecraftClient mc) {
        if (!mc.isIntegratedServerRunning()) return null;
        return mc.getServer() != null ? mc.getServer().getRecipeManager() : null;
    }

    @SuppressWarnings("unchecked")
    private RecipeEntry<CraftingRecipe> castCraftingEntry(RecipeEntry<?> entry) {
        return (RecipeEntry<CraftingRecipe>) entry;
    }

    private RecipeDefinition toDefinition(RecipeEntry<CraftingRecipe> entry, MinecraftClient mc) {
        CraftingRecipe recipe = entry.value();
        String recipeId = entry.id().getValue().toString();

        if (recipe instanceof ShapedRecipe shaped) {
            List<IngredientSpec> ingredients = convertOptionals(shaped.getIngredients());
            if (ingredients == null) return null;

            ItemStack result = previewResult(shaped, shaped.getWidth(), shaped.getHeight(), mc);
            if (result.isEmpty()) return null;

            return new RecipeDefinition(
                recipeId,
                Registries.ITEM.getId(result.getItem()).toString(),
                result.getCount(),
                shaped.getWidth(),
                shaped.getHeight(),
                RecipeKind.SHAPED,
                ingredients
            );
        }

        if (recipe instanceof ShapelessRecipe shapeless) {
            List<IngredientSpec> ingredients = convertIngredients(shapeless.getIngredientPlacement().getIngredients());
            if (ingredients == null) return null;

            int occupied = 0;
            for (IngredientSpec ingredient : ingredients) {
                if (!ingredient.isEmpty()) occupied++;
            }

            int[] layout = shapelessLayout(occupied);
            int width = layout[0];
            int height = layout[1];

            ItemStack result = previewResult(shapeless, width, height, mc);
            if (result.isEmpty()) return null;

            return new RecipeDefinition(
                recipeId,
                Registries.ITEM.getId(result.getItem()).toString(),
                result.getCount(),
                width,
                height,
                RecipeKind.SHAPELESS,
                ingredients
            );
        }

        return null;
    }

    private ItemStack previewResult(
        CraftingRecipe recipe,
        int width,
        int height,
        MinecraftClient mc
    ) {
        if (mc.world == null || width <= 0 || height <= 0) return ItemStack.EMPTY;

        ArrayList<ItemStack> stacks = new ArrayList<>(Collections.nCopies(width * height, ItemStack.EMPTY));

        try {
            return recipe.craft(CraftingRecipeInput.create(width, height, stacks), mc.world.getRegistryManager());
        } catch (RuntimeException ignored) {
            return ItemStack.EMPTY;
        }
    }

    private int[] shapelessLayout(int occupied) {
        if (occupied <= 1) return new int[] { 1, 1 };
        if (occupied == 2) return new int[] { 2, 1 };
        if (occupied <= 4) return new int[] { 2, 2 };
        if (occupied <= 6) return new int[] { 3, 2 };
        return new int[] { 3, 3 };
    }

    @SuppressWarnings("deprecation")
    private List<IngredientSpec> convertOptionals(List<Optional<Ingredient>> ingredients) {
        ArrayList<IngredientSpec> converted = new ArrayList<>();

        for (Optional<Ingredient> ingredient : ingredients) {
            if (ingredient == null || ingredient.isEmpty()) {
                converted.add(new IngredientSpec(List.of()));
                continue;
            }

            Ingredient value = ingredient.get();
            if (value.isEmpty()) {
                converted.add(new IngredientSpec(List.of()));
                continue;
            }

            LinkedHashSet<String> matching = new LinkedHashSet<>();
            value.getMatchingItems().forEach(itemEntry -> matching.add(Registries.ITEM.getId(itemEntry.value()).toString()));
            if (matching.isEmpty()) return null;

            converted.add(new IngredientSpec(List.copyOf(matching)));
        }

        return converted;
    }

    @SuppressWarnings("deprecation")
    private List<IngredientSpec> convertIngredients(List<Ingredient> ingredients) {
        ArrayList<IngredientSpec> converted = new ArrayList<>();

        for (Ingredient ingredient : ingredients) {
            if (ingredient == null || ingredient.isEmpty()) {
                converted.add(new IngredientSpec(List.of()));
                continue;
            }

            LinkedHashSet<String> matching = new LinkedHashSet<>();
            ingredient.getMatchingItems().forEach(itemEntry -> matching.add(Registries.ITEM.getId(itemEntry.value()).toString()));
            if (matching.isEmpty()) return null;

            converted.add(new IngredientSpec(List.copyOf(matching)));
        }

        return converted;
    }
}
