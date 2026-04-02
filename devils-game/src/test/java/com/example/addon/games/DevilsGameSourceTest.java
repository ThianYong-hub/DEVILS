package com.example.addon.games;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DevilsGameSourceTest {
    @Test
    void gameAddonRegistersDedicatedLaunchersAndOwnSyncModule() throws IOException {
        String source = Files.readString(Path.of("src", "main", "java", "com", "example", "addon", "games", "DevilsGameAddon.java"));

        assertTrue(source.contains("class DevilsGameAddon extends MeteorAddon"));
        assertTrue(source.contains("modules.add(new GameSyncHub());"));
        assertTrue(source.contains("modules.add(new CheckersOverlay());"));
        assertTrue(source.contains("modules.add(new ChessOverlay());"));
        assertTrue(source.contains("modules.add(new SlotMachineOverlay());"));
        assertTrue(source.contains("modules.add(new BlackjackOverlay());"));
        assertTrue(source.contains("modules.add(new DoomOverlay());"));
        assertFalse(source.contains("modules.add(new GamesModule());"));
        assertFalse(source.contains("hiddenModules"));
        assertFalse(source.contains("new AutoPearl()"));
        assertFalse(source.contains("new SyncHub()"));
    }

    @Test
    void gameSourcesUseSharedSyncAndIndependentMetadata() throws IOException {
        String source = Files.readString(Path.of("src", "main", "java", "com", "example", "addon", "games", "sync", "MiniGamesSyncRuntime.java"))
            + Files.readString(Path.of("src", "main", "java", "com", "example", "addon", "games", "sync", "MiniGamesSyncCodec.java"))
            + Files.readString(Path.of("src", "main", "java", "com", "example", "addon", "modules", "games", "GameSyncHub.java"));
        String metadata = Files.readString(Path.of("src", "main", "resources", "fabric.mod.json"));

        assertTrue(source.contains("GameSyncHub"));
        assertTrue(source.contains("com.example.addon.shared.sync.SyncJsonUtils"));
        assertTrue(source.contains("com.example.addon.shared.sync.SyncCrypto"));
        assertFalse(source.contains("com.example.addon.modules.SyncHub"));
        assertFalse(source.contains("com.example.addon.util.CrashGuard"));

        assertTrue(metadata.contains("\"id\": \"devils-game\""));
        assertTrue(metadata.contains("\"com.example.addon.games.DevilsGameAddon\""));
        assertTrue(metadata.contains("\"meteor-client\": \"*\""));
        assertFalse(metadata.contains("\"devils-addon\": \"*\""));
    }

    @Test
    void gamesCategoryNoLongerExposesMonolithicGamesLauncher() throws IOException {
        String checkersSource = Files.readString(Path.of("src", "main", "java", "com", "example", "addon", "modules", "games", "CheckersOverlay.java"));
        String chessSource = Files.readString(Path.of("src", "main", "java", "com", "example", "addon", "modules", "games", "ChessOverlay.java"));
        String coordinatorSource = Files.readString(Path.of("src", "main", "java", "com", "example", "addon", "modules", "games", "GameLaunchCoordinator.java"));

        assertTrue(checkersSource.contains("super(DevilsGameAddon.GAMES_CATEGORY, \"checkers\""));
        assertTrue(chessSource.contains("super(DevilsGameAddon.GAMES_CATEGORY, \"chess\""));
        assertTrue(coordinatorSource.contains("enum Entry"));
        assertFalse(Files.exists(Path.of("src", "main", "java", "com", "example", "addon", "modules", "games", "GamesModule.java")));
        assertFalse(Files.exists(Path.of("src", "main", "java", "com", "example", "addon", "gui", "screens", "games", "MiniGamesHubScreen.java")));
    }
}
