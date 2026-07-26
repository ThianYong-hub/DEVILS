package com.devils.addon;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectStructureSuiteTest {
    @Test
    void DevilsAddonContainsExpectedBootstrapWiring() throws IOException {
        String source = readMainJava("com", "devils", "addon", "DevilsAddon.java");

        assertTrue(source.contains("class DevilsAddon extends MeteorAddon"));
        assertTrue(source.contains("CrashGuard.installLogFilters();"));
        assertTrue(source.contains("AddonModulesConfig.init();"));
        assertTrue(source.contains("registerTrackerPlayersSettingFactory();"));
        assertTrue(source.contains("registerModules();"));
        assertTrue(source.contains("registerCommands();"));
        assertTrue(source.contains("class AutoAnvilRenameCommand extends Command"));
        assertTrue(source.contains("return \"com.devils.addon\";"));
        assertTrue(source.contains("new GithubRepo(\"ThianYong-hub\", \"DEVILS\")"));
        assertFalse(source.contains("GamesModule"));
        assertFalse(source.contains("GAMES_CATEGORY"));
    }

    @Test
    void DevilsAddonRegistersCoreModulesCommandsAndHud() throws IOException {
        String source = readMainJava("com", "devils", "addon", "DevilsAddon.java");

        assertTrue(source.contains("modules.add(new AutoPearl());"));
        assertTrue(source.contains("modules.add(new AutoLogin());"));
        assertTrue(source.contains("modules.add(new Ping());"));
        assertTrue(source.contains("modules.add(new JoinWatcher());"));
        assertTrue(source.contains("modules.add(new HighwayBuilder());"));
        assertTrue(source.contains("modules.add(new StashMover());"));
        assertTrue(source.contains("Commands.add(new AutoAnvilRenameCommand());"));
        assertTrue(source.contains("Commands.add(new SessionCommand());"));
        assertTrue(source.contains("Commands.add(new StashMoverCommand());"));
        assertFalse(source.contains("modules.add(new GamesModule());"));
    }

    @Test
    void projectContainsCurrentCoreSourceFiles() {
        List<Path> files = List.of(
            mainJava("com", "devils", "addon", "DevilsAddon.java"),
            mainJava("com", "devils", "addon", "gui", "screens", "settings", "SelectionScreens.java"),
            mainJava("com", "devils", "addon", "mixin", "ClientPlayerInteractionManagerInvoker.java"),
            mainJava("com", "devils", "addon", "modules", "Ping.java"),
            mainJava("com", "devils", "addon", "modules", "stashmover", "StashMover.java"),
            mainJava("com", "devils", "addon", "modules", "stashmover", "StashMoverSupport.java"),
            mainJava("com", "devils", "addon", "modules", "stashmover", "StashMoverInteraction.java"),
            mainJava("com", "devils", "addon", "modules", "stashmover", "StashMoverRuntime.java"),
            mainJava("com", "devils", "addon", "modules", "stashmover", "StashMoverCommand.java"),
            mainJava("com", "devils", "addon", "modules", "ClipModules.java"),
            mainJava("com", "devils", "addon", "modules", "stashmover", "StashMoverSlotPolicy.java"),
            mainJava("com", "devils", "addon", "modules", "stashmover", "StashMoverPearlApproach.java"),
            mainJava("com", "devils", "addon", "modules", "stashmover", "StashMoverOwnPearlTracker.java")
        );
        for (Path file : files) {
            assertTrue(Files.exists(file), "Expected source file missing: " + file);
        }
    }

    @Test
    void sourceFilesStillDeclareExpectedNestedAndFacadeTypes() throws IOException {
        assertSourceContains(
            mainJava("com", "devils", "addon", "modules", "AutoPearl.java"),
            "class AutoPearl extends Module"
        );
        assertSourceContains(
            mainJava("com", "devils", "addon", "modules", "AutoLogin.java"),
            "class AutoLogin extends Module"
        );
        assertSourceContains(
            mainJava("com", "devils", "addon", "modules", "SyncHub.java"),
            "class SyncHub extends AbstractSyncConfigModule"
        );
        assertSourceContains(
            mainJava("com", "devils", "addon", "gui", "screens", "settings", "SelectionScreens.java"),
            "class OnlinePlayerSelectScreen extends WindowScreen"
        );
        assertSourceContains(
            mainJava("com", "devils", "addon", "DevilsAddon.java"),
            "class AutoAnvilRenameCommand extends Command"
        );
    }

    @Test
    void joinWatcherSourcePreservesRuleTargetingAndDelayedSendLogic() throws IOException {
        String source = readMainJava("com", "devils", "addon", "modules", "JoinWatcher.java");

        assertTrue(source.contains("handleDeathPacket"));
        assertTrue(source.contains("case Death -> trigger == RuleTrigger.Death;"));
        assertTrue(source.contains("case Both -> trigger == RuleTrigger.Join || trigger == RuleTrigger.Leave;"));
        assertTrue(source.contains("rule.soundValueFor(toRuleTrigger(trigger))"));
        assertTrue(source.contains("int delayMs = rule.chatDelayMs();"));
        assertTrue(source.contains("queueDelayedChatSend(i, rule, command, delayMs);"));
        assertTrue(source.contains("CHAT_SEND_EXECUTOR.schedule("));
    }

    @Test
    void repositoryContainsDedicatedGameCompanionProject() {
        assertTrue(Files.exists(repoPath("devils-game", "src", "main", "java", "com", "devils", "addon", "games", "DevilsGameAddon.java")));
        assertTrue(Files.exists(repoPath("devils-game", "src", "main", "resources", "fabric.mod.json")));
        assertTrue(Files.exists(repoPath("devils-game", "src", "main", "java", "com", "devils", "addon", "modules", "games", "GameSyncHub.java")));
        assertTrue(Files.exists(repoPath("devils-shared", "src", "main", "java", "com", "devils", "addon", "shared", "sync", "AbstractSyncConfigModule.java")));
    }

    private static void assertSourceContains(Path file, String expectedText) throws IOException {
        String source = Files.readString(file);
        assertTrue(source.contains(expectedText), "Expected text not found in " + file + ": " + expectedText);
    }

    private static String readMainJava(String... parts) throws IOException {
        return Files.readString(mainJava(parts));
    }

    private static Path mainJava(String... parts) {
        Path path = Path.of("src", "main", "java");
        for (String part : parts) path = path.resolve(part);
        return path;
    }

    private static Path repoPath(String... parts) {
        Path path = Path.of("..").normalize();
        for (String part : parts) path = path.resolve(part);
        return path;
    }
}
