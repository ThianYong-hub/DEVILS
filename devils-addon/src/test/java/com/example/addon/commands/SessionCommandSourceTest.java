package com.example.addon.commands;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionCommandSourceTest {
    @Test
    void sessionCommandUsesDeferredReconnectController() throws IOException {
        String source = Files.readString(Path.of(
            "src", "main", "java", "com", "example", "addon", "commands", "SessionCommand.java"
        ));

        assertTrue(source.contains("MeteorClient.EVENT_BUS.subscribe(RECONNECT_CONTROLLER);"));
        assertTrue(source.contains("private static final SessionReconnectController RECONNECT_CONTROLLER = new SessionReconnectController();"));
        assertTrue(source.contains("waitingForDisconnect"));
        assertTrue(source.contains("pendingDelayTicks = CONNECT_DELAY_TICKS;"));
        assertTrue(source.contains("waitingForDisconnect = hasLiveSession();"));
        assertTrue(source.contains("private static boolean hasLiveSession()"));
        assertTrue(source.contains("mc.disconnect(parent, false);"));
        assertTrue(source.contains("ConnectScreen.connect(parent, SessionCommand.mc, reconnectTarget.address, reconnectTarget.info, false, null);"));
        assertTrue(source.contains("getMessageHistory().removeLastOccurrence(commandLine);"));
        assertTrue(source.contains("public static boolean scheduleReconnect(String rawServerAddress)"));
        assertTrue(source.contains("public static boolean switchToCrackedSession(String rawNick)"));
        assertTrue(source.contains("@EventHandler"));
        assertTrue(source.contains("private static final class SessionReconnectController"));
    }
}
