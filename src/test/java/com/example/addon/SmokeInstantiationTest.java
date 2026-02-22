package com.example.addon;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SmokeInstantiationTest {
    @Test
    void coreSourceFilesDeclareExpectedClasses() throws IOException {
        assertSourceContains(
            Path.of("src", "main", "java", "com", "example", "addon", "modules", "AutoPearl.java"),
            "class AutoPearl extends Module"
        );
        assertSourceContains(
            Path.of("src", "main", "java", "com", "example", "addon", "modules", "AutoAnvilRename.java"),
            "class AutoAnvilRename extends Module"
        );
        assertSourceContains(
            Path.of("src", "main", "java", "com", "example", "addon", "modules", "PearlHelper.java"),
            "class PearlHelper extends Module"
        );
        assertSourceContains(
            Path.of("src", "main", "java", "com", "example", "addon", "commands", "CommandExample.java"),
            "class CommandExample extends Command"
        );
        assertSourceContains(
            Path.of("src", "main", "java", "com", "example", "addon", "commands", "AutoAnvilRenameCommand.java"),
            "class AutoAnvilRenameCommand extends Command"
        );
    }

    private void assertSourceContains(Path file, String expectedText) throws IOException {
        String source = Files.readString(file);
        assertTrue(source.contains(expectedText), "Expected text not found in " + file + ": " + expectedText);
    }
}
