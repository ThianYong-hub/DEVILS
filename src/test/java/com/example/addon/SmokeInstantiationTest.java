package com.example.addon;

import com.example.addon.commands.AutoAnvilRenameCommand;
import com.example.addon.commands.CommandExample;
import com.example.addon.hud.HudExample;
import com.example.addon.modules.AutoAnvilRename;
import com.example.addon.modules.AutoPearl;
import com.example.addon.modules.PearlHelper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class SmokeInstantiationTest {
    @Test
    void coreClassesInstantiate() {
        assertDoesNotThrow(CommandExample::new);
        assertDoesNotThrow(AutoAnvilRenameCommand::new);
        assertDoesNotThrow(HudExample::new);
        assertDoesNotThrow(AutoPearl::new);
        assertDoesNotThrow(AutoAnvilRename::new);
        assertDoesNotThrow(PearlHelper::new);
    }
}
