package com.example.addon;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ResourcesConfigTest {
    @Test
    void fabricModJsonContainsExpectedMetadata() throws IOException {
        String json = readFile(Path.of("src", "main", "resources", "fabric.mod.json"));

        assertTrue(json.contains("\"id\": \"paradise\""));
        assertTrue(json.contains("\"com.example.addon.AddonTemplate\""));
        assertTrue(json.contains("\"addon-template.mixins.json\""));
        assertTrue(json.contains("\"java\": \">=21\""));
    }

    @Test
    void mixinConfigMatchesExistingMixinClasses() throws IOException {
        String mixinJson = readFile(Path.of("src", "main", "resources", "addon-template.mixins.json"));

        assertTrue(mixinJson.contains("\"package\": \"com.example.addon.mixin\""));
        assertTrue(mixinJson.contains("\"ExampleMixin\""));
        assertTrue(mixinJson.contains("\"AnvilScreenHandlerAccessor\""));
        assertTrue(mixinJson.contains("\"ItemListSettingScreenMixin\""));
        assertTrue(Files.exists(Path.of("src", "main", "java", "com", "example", "addon", "mixin", "ExampleMixin.java")));
        assertTrue(Files.exists(Path.of("src", "main", "java", "com", "example", "addon", "mixin", "AnvilScreenHandlerAccessor.java")));
        assertTrue(Files.exists(Path.of("src", "main", "java", "com", "example", "addon", "mixin", "ItemListSettingScreenMixin.java")));
    }

    private String readFile(Path path) throws IOException {
        return Files.readString(path);
    }
}
