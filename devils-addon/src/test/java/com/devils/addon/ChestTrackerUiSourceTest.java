package com.devils.addon;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ChestTrackerUiSourceTest {
    @Test
    void mixinsKeepChestTrackerGuiClosedWhenModuleIsDisabled() throws IOException {
        String source = Files.readString(Path.of(
            "src", "main", "java", "com", "devils", "addon", "mixin", "ChestTrackerMixinSupport.java"
        ));

        assertTrue(source.contains("method = \"openInGame\""));
        assertTrue(source.contains("method = \"onScreenOpen\""));
        assertTrue(source.contains("ChestTrackerRuntimeState.isModuleEnabled()"));
        assertTrue(source.contains("ChestTrackerKeybindScreen"));
    }

    @Test
    void keybindScreenKeepsDirectKeyRebindingFlow() throws IOException {
        String source = Files.readString(Path.of(
            "src", "main", "java", "com", "devils", "addon", "modules", "chesttracker", "ChestTrackerKeybindScreen.java"
        ));

        assertTrue(source.contains("ChestTracker.OPEN_GUI.setBoundKey(key);"));
        assertTrue(source.contains("KeyBinding.updateKeysByCode();"));
        assertTrue(source.contains("this.client.options.write();"));
        assertTrue(source.contains("InputUtil.Type.MOUSE.createFromCode(event.button())"));
        assertTrue(source.contains("InputUtil.fromKeyCode(event)"));
    }
}
