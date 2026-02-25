package com.example.addon;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class AddonTemplateTest {
    @Test
    void addonTemplateContainsExpectedPackageAndRepo() throws IOException {
        String source = Files.readString(Path.of(
            "src", "main", "java", "com", "example", "addon", "AddonTemplate.java"
        ));

        assertTrue(source.contains("class AddonTemplate extends MeteorAddon"));
        assertTrue(source.contains("return \"com.example.addon\";"));
        assertTrue(source.contains("new GithubRepo(\"ThianYong-hub\", \"DEVILS\")"));
        assertTrue(source.contains("SettingsWidgetFactory.registerCustomFactory(TrackerPlayersSetting.class"));
    }

    @Test
    void addonRegistersCoreModulesAndCommands() throws IOException {
        String source = Files.readString(Path.of(
            "src", "main", "java", "com", "example", "addon", "AddonTemplate.java"
        ));

        assertTrue(source.contains("Modules.get().add(new AutoPearl());"));
        assertTrue(source.contains("Modules.get().add(new AutoAnvilRename());"));
        assertTrue(source.contains("Modules.get().add(new JoinWatcher());"));
        assertTrue(source.contains("Commands.add(new CommandExample());"));
        assertTrue(source.contains("Commands.add(new AutoAnvilRenameCommand());"));
    }
}
