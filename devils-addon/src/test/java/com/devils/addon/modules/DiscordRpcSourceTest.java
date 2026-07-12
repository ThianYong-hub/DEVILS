package com.devils.addon.modules;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class DiscordRpcSourceTest {
    @Test
    void discordRpcSourceKeepsMeteorExclusionAndReconnectLogic() throws IOException {
        String source = Files.readString(Path.of(
            "src", "main", "java", "com", "devils", "addon", "modules", "DiscordRPC.java"
        ));

        assertTrue(source.contains("FORCE_RECONNECT_TICKS = 600"));
        assertTrue(source.contains("restoreMeteorPresenceOnDeactivate"));
        assertTrue(source.contains("ensureMeteorPresenceDisabled(boolean rememberForRestore)"));
        assertTrue(source.contains("restoreMeteorPresenceIfNeeded()"));
        assertTrue(source.contains("ensureMeteorPresenceDisabled(true)"));
        assertTrue(source.contains("ensureMeteorPresenceDisabled(false)"));
        assertTrue(source.contains("DiscordIPC.stop();"));
        assertTrue(source.contains("DiscordIPC.start(APP_ID"));
        assertTrue(source.contains("if (ensureMeteorPresenceDisabled(false))"));
        assertTrue(source.contains("if (reconnectCounter >= FORCE_RECONNECT_TICKS)"));
    }
}
