package com.devils.addon.games;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DevilsGameSourceTest {
    @Test
    void gameAddonRegistersDedicatedLaunchersAndOwnSyncModule() throws IOException {
        String source = Files.readString(Path.of("src", "main", "java", "com", "devils", "addon", "games", "DevilsGameAddon.java"));

        assertTrue(source.contains("class DevilsGameAddon extends MeteorAddon"));
        assertTrue(source.contains("modules.add(new GameSyncHub());"));
        assertTrue(source.contains("modules.add(new CheckersOverlay());"));
        assertTrue(source.contains("modules.add(new ChessOverlay());"));
        assertTrue(source.contains("modules.add(new SlotMachineOverlay());"));
        assertTrue(source.contains("modules.add(new BlackjackOverlay());"));
        assertTrue(source.contains("modules.add(new RussianRouletteOverlay());"));
        assertTrue(source.contains("modules.add(new DoomOverlay());"));
        assertFalse(source.contains("modules.add(new GamesModule());"));
        assertFalse(source.contains("hiddenModules"));
        assertFalse(source.contains("new AutoPearl()"));
        assertFalse(source.contains("new SyncHub()"));
    }

    @Test
    void gameSourcesUseSharedSyncAndIndependentMetadata() throws IOException {
        String source = Files.readString(Path.of("src", "main", "java", "com", "devils", "addon", "games", "sync", "MiniGamesSyncRuntime.java"))
            + Files.readString(Path.of("src", "main", "java", "com", "devils", "addon", "games", "sync", "MiniGamesSyncCodec.java"))
            + Files.readString(Path.of("src", "main", "java", "com", "devils", "addon", "modules", "games", "GameSyncHub.java"));
        String metadata = Files.readString(Path.of("src", "main", "resources", "fabric.mod.json"));

        assertTrue(source.contains("GameSyncHub"));
        assertTrue(source.contains("com.devils.addon.shared.sync.SyncJsonUtils"));
        assertTrue(source.contains("com.devils.addon.shared.sync.SyncCrypto"));
        assertFalse(source.contains("com.devils.addon.modules.SyncHub"));
        assertFalse(source.contains("com.devils.addon.util.CrashGuard"));

        assertTrue(metadata.contains("\"id\": \"devils-game\""));
        assertTrue(metadata.contains("\"com.devils.addon.games.DevilsGameAddon\""));
        assertTrue(metadata.contains("\"meteor-client\": \"*\""));
        assertFalse(metadata.contains("\"devils-addon\": \"*\""));
    }

    @Test
    void gamesCategoryNoLongerExposesMonolithicGamesLauncher() throws IOException {
        String checkersSource = Files.readString(Path.of("src", "main", "java", "com", "devils", "addon", "modules", "games", "CheckersOverlay.java"));
        String chessSource = Files.readString(Path.of("src", "main", "java", "com", "devils", "addon", "modules", "games", "ChessOverlay.java"));
        String rouletteSource = Files.readString(Path.of("src", "main", "java", "com", "devils", "addon", "modules", "games", "RussianRouletteOverlay.java"));
        String coordinatorSource = Files.readString(Path.of("src", "main", "java", "com", "devils", "addon", "modules", "games", "GameLaunchCoordinator.java"));

        assertTrue(checkersSource.contains("super(DevilsGameAddon.GAMES_CATEGORY, \"checkers\""));
        assertTrue(chessSource.contains("super(DevilsGameAddon.GAMES_CATEGORY, \"chess\""));
        assertTrue(rouletteSource.contains("super(DevilsGameAddon.GAMES_CATEGORY, \"russian-roulette\""));
        assertTrue(coordinatorSource.contains("enum Entry"));
        assertTrue(coordinatorSource.contains("RUSSIAN_ROULETTE(RussianRouletteOverlay.class)"));
        assertTrue(rouletteSource.contains("GameLaunchCoordinator.launchNext(GameLaunchCoordinator.Entry.RUSSIAN_ROULETTE)"));
        assertFalse(Files.exists(Path.of("src", "main", "java", "com", "devils", "addon", "modules", "games", "GamesModule.java")));
        assertFalse(Files.exists(Path.of("src", "main", "java", "com", "devils", "addon", "gui", "screens", "games", "MiniGamesHubScreen.java")));
    }
}
