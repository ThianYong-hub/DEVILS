package com.example.addon;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectFilesTest {
    @Test
    void allCoreJavaFilesExist() {
        List<Path> files = List.of(
            Path.of("src", "main", "java", "com", "example", "addon", "AddonTemplate.java"),
            Path.of("src", "main", "java", "com", "example", "addon", "commands", "CommandExample.java"),
            Path.of("src", "main", "java", "com", "example", "addon", "commands", "AutoAnvilRenameCommand.java"),
            Path.of("src", "main", "java", "com", "example", "addon", "hud", "HudExample.java"),
            Path.of("src", "main", "java", "com", "example", "addon", "modules", "AutoPearl.java"),
            Path.of("src", "main", "java", "com", "example", "addon", "modules", "AutoAnvilRename.java"),
            Path.of("src", "main", "java", "com", "example", "addon", "mixin", "ExampleMixin.java"),
            Path.of("src", "main", "java", "com", "example", "addon", "mixin", "AnvilScreenHandlerAccessor.java"),
            Path.of("src", "main", "java", "com", "example", "addon", "mixin", "ItemListSettingScreenMixin.java")
        );

        for (Path file : files) {
            assertTrue(Files.exists(file), "Missing file: " + file);
        }
    }

    @Test
    void gradleWrapperFilesExist() {
        assertTrue(Files.exists(Path.of("gradlew")));
        assertTrue(Files.exists(Path.of("gradlew.bat")));
        assertTrue(Files.exists(Path.of("gradle", "wrapper", "gradle-wrapper.jar")));
        assertTrue(Files.exists(Path.of("gradle", "wrapper", "gradle-wrapper.properties")));
    }

    @Test
    void workflowsDirectoryIsNotEmpty() throws Exception {
        Path workflows = Path.of(".github", "workflows");
        assertTrue(Files.exists(workflows));
        try (var stream = Files.list(workflows)) {
            assertFalse(stream.findAny().isEmpty());
        }
    }
}
