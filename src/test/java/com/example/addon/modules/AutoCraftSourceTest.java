package com.example.addon.modules;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutoCraftSourceTest {
    @Test
    void autoCraftModuleExposesExactlyNineUserFacingSettings() throws IOException {
        String source = Files.readString(Path.of("src", "main", "java", "com", "example", "addon", "modules", "AutoCraft.java"));

        assertEquals(9, countOccurrences(source, ".name(\""));
        assertTrue(source.contains(".name(\"items\")"));
        assertTrue(source.contains(".name(\"recipe-blacklist\")"));
        assertTrue(source.contains(".name(\"delay\")"));
        assertTrue(source.contains(".name(\"frequency\")"));
        assertTrue(source.contains(".name(\"drop\")"));
        assertTrue(source.contains(".name(\"craft-all\")"));
        assertTrue(source.contains(".name(\"fast-close\")"));
        assertTrue(source.contains(".name(\"auto-open\")"));
        assertTrue(source.contains(".name(\"limit\")"));
    }

    @Test
    void autoCraftUsesItemListSettingsAndKeepsDelayFrequencySeparate() throws IOException {
        String moduleSource = Files.readString(Path.of("src", "main", "java", "com", "example", "addon", "modules", "AutoCraft.java"));
        String sessionSource = Files.readString(Path.of("src", "main", "java", "com", "example", "addon", "modules", "autocraft", "AutoCraftSessionController.java"));

        assertTrue(moduleSource.contains("private final Setting<List<Item>> items = sgGeneral.add(new ItemListSetting.Builder()"));
        assertTrue(moduleSource.contains("private final Setting<List<Item>> recipeBlacklist = sgGeneral.add(new ItemListSetting.Builder()"));
        assertTrue(moduleSource.contains("Ticks between low-level click, place, and take actions."));
        assertTrue(moduleSource.contains("Ticks between logical craft cycles and step hand-offs."));
        assertTrue(sessionSource.contains("nextActionTick = tickCounter + module.delay();"));
        assertTrue(sessionSource.contains("nextCycleTick = tickCounter + module.frequency();"));
    }

    @Test
    void addonRegistersModuleWithoutLegacyCommandOrRecipeBookPlacement() throws IOException {
        String addonSource = Files.readString(Path.of("src", "main", "java", "com", "example", "addon", "AddonTemplate.java"));
        String plannerSource = Files.readString(Path.of("src", "main", "java", "com", "example", "addon", "modules", "autocraft", "AutoCraftPlanner.java"));
        String runtimeSource = Files.readString(Path.of("src", "main", "java", "com", "example", "addon", "modules", "autocraft", "AutoCraftSessionController.java"))
            + Files.readString(Path.of("src", "main", "java", "com", "example", "addon", "modules", "autocraft", "AutoCraftExecutor.java"))
            + Files.readString(Path.of("src", "main", "java", "com", "example", "addon", "modules", "autocraft", "AutoCraftRecipeCatalogue.java"));

        assertTrue(addonSource.contains("modules.add(new AutoCraft());"));
        assertFalse(addonSource.contains("Commands.add(new AutoCraft"));
        assertFalse(runtimeSource.contains("handlePlaceRecipe"));
        assertFalse(runtimeSource.contains("getSelectedRecipes("));
        assertFalse(runtimeSource.contains("clickRecipe("));
        assertTrue(plannerSource.contains("for (String targetItemId : input.targetItemIds())"));
    }

    private static int countOccurrences(String source, String needle) {
        int count = 0;
        int index = 0;
        while ((index = source.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }
}
