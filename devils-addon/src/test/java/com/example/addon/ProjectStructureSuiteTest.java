package com.example.addon;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectStructureSuiteTest {
    @Test
    void addonTemplateContainsExpectedBootstrapWiring() throws IOException {
        String source = readMainJava("com", "example", "addon", "AddonTemplate.java");

        assertTrue(source.contains("class AddonTemplate extends MeteorAddon"));
        assertTrue(source.contains("CrashGuard.installLogFilters();"));
        assertTrue(source.contains("CrashGuard.logXaeroState();"));
        assertTrue(source.contains("AddonModulesConfig.init();"));
        assertTrue(source.contains("registerTrackerPlayersSettingFactory();"));
        assertTrue(source.contains("registerModules();"));
        assertTrue(source.contains("registerCommands();"));
        assertTrue(source.contains("registerHudElements();"));
        assertTrue(source.contains("AssimilatedQualitySmoke.install();"));
        assertTrue(source.contains("StashMoverTargetedRuntimeValidation.install();"));
        assertTrue(source.contains("class CommandExample extends Command"));
        assertTrue(source.contains("class AutoAnvilRenameCommand extends Command"));
        assertTrue(source.contains("class HudExample extends HudElement"));
        assertTrue(source.contains("return \"com.example.addon\";"));
        assertTrue(source.contains("new GithubRepo(\"ThianYong-hub\", \"DEVILS\")"));
        assertFalse(source.contains("GamesModule"));
        assertFalse(source.contains("GAMES_CATEGORY"));
    }

    @Test
    void addonTemplateRegistersCoreModulesCommandsAndHud() throws IOException {
        String source = readMainJava("com", "example", "addon", "AddonTemplate.java");

        assertTrue(source.contains("modules.add(new AutoPearl());"));
        assertTrue(source.contains("modules.add(new AutoLogin());"));
        assertTrue(source.contains("modules.add(new Ping());"));
        assertTrue(source.contains("modules.add(new JoinWatcher());"));
        assertTrue(source.contains("modules.add(new HighwayBuilder());"));
        assertTrue(source.contains("modules.add(new ChestTrackerModule());"));
        assertTrue(source.contains("modules.add(new StashMover());"));
        assertTrue(source.contains("Commands.add(new CommandExample());"));
        assertTrue(source.contains("Commands.add(new AutoAnvilRenameCommand());"));
        assertTrue(source.contains("Commands.add(new StashMoverCommand());"));
        assertTrue(source.contains("Hud.get().register(HudExample.INFO);"));
        assertFalse(source.contains("modules.add(new GamesModule());"));
    }

    @Test
    void projectContainsCurrentCoreSourceFiles() {
        List<Path> files = List.of(
            mainJava("com", "example", "addon", "AddonTemplate.java"),
            mainJava("com", "example", "addon", "gui", "screens", "settings", "SelectionScreens.java"),
            mainJava("com", "example", "addon", "mixin", "ClientPlayerInteractionManagerInvoker.java"),
            mainJava("com", "example", "addon", "mixin", "XaeroMixinSupport.java"),
            mainJava("com", "example", "addon", "modules", "Ping.java"),
            mainJava("com", "example", "addon", "modules", "stashmover", "StashMover.java"),
            mainJava("com", "example", "addon", "modules", "stashmover", "StashMoverSupport.java"),
            mainJava("com", "example", "addon", "modules", "stashmover", "StashMoverInteraction.java"),
            mainJava("com", "example", "addon", "modules", "stashmover", "StashMoverRuntime.java"),
            mainJava("com", "example", "addon", "modules", "XaeroSync.java"),
            mainJava("com", "example", "addon", "modules", "ClipModules.java"),
            mainJava("com", "example", "addon", "modules", "stashmover", "StashMoverCommand.java"),
            mainJava("com", "example", "addon", "modules", "stashmover", "StashMoverOwnPearlTracker.java"),
            mainJava("com", "example", "addon", "modules", "stashmover", "StashMoverSlotPolicy.java"),
            mainJava("com", "example", "addon", "modules", "autologin", "AutoLoginSyncController.java"),
            mainJava("com", "example", "addon", "modules", "autologin", "AutoLoginSyncDiagnostics.java"),
            mainJava("com", "example", "addon", "modules", "highwaybuilder", "HighwayBuilderTypes.java"),
            mainJava("com", "example", "addon", "modules", "highwaybuilder", "EChestMinerSupport.java"),
            mainJava("com", "example", "addon", "util", "XaeroSyncWaypoints.java"),
            mainJava("com", "example", "addon", "util", "smoke", "StashMoverTargetedRuntimeValidation.java"),
            mainJava("com", "example", "addon", "util", "xaerosync", "XaeroWaypointManagedWaypoints.java")
        );

        for (Path file : files) {
            assertTrue(Files.exists(file), "Missing file: " + file);
        }
    }

    @Test
    void gradleWrapperAndWorkflowDirectoriesExist() throws IOException {
        assertTrue(Files.exists(repoPath("gradlew")));
        assertTrue(Files.exists(repoPath("gradlew.bat")));
        assertTrue(Files.exists(repoPath("gradle", "wrapper", "gradle-wrapper.jar")));
        assertTrue(Files.exists(repoPath("gradle", "wrapper", "gradle-wrapper.properties")));

        Path workflows = repoPath(".github", "workflows");
        assertTrue(Files.exists(workflows));
        try (var stream = Files.list(workflows)) {
            assertFalse(stream.findAny().isEmpty());
        }
    }

    @Test
    void repositoryContainsDedicatedGameCompanionProject() {
        assertTrue(Files.exists(repoPath("devils-game", "src", "main", "java", "com", "example", "addon", "games", "DevilsGameAddon.java")));
        assertTrue(Files.exists(repoPath("devils-game", "src", "main", "resources", "fabric.mod.json")));
        assertTrue(Files.exists(repoPath("devils-game", "src", "main", "java", "com", "example", "addon", "modules", "games", "GameSyncHub.java")));
        assertTrue(Files.exists(repoPath("devils-shared", "src", "main", "java", "com", "example", "addon", "shared", "sync", "AbstractSyncConfigModule.java")));
    }

    @Test
    void sourceFilesStillDeclareExpectedNestedAndFacadeTypes() throws IOException {
        assertSourceContains(
            mainJava("com", "example", "addon", "modules", "AutoPearl.java"),
            "class AutoPearl extends Module"
        );
        assertSourceContains(
            mainJava("com", "example", "addon", "modules", "AutoLogin.java"),
            "class AutoLogin extends Module"
        );
        assertSourceContains(
            mainJava("com", "example", "addon", "modules", "SyncHub.java"),
            "class SyncHub extends AbstractSyncConfigModule"
        );
        assertSourceContains(
            mainJava("com", "example", "addon", "modules", "XaeroSync.java"),
            "class XaeroSync extends Module"
        );
        assertSourceContains(
            mainJava("com", "example", "addon", "gui", "screens", "settings", "SelectionScreens.java"),
            "class OnlinePlayerSelectScreen extends WindowScreen"
        );
        assertSourceContains(
            mainJava("com", "example", "addon", "AddonTemplate.java"),
            "class CommandExample extends Command"
        );
        assertSourceContains(
            mainJava("com", "example", "addon", "AddonTemplate.java"),
            "class AutoAnvilRenameCommand extends Command"
        );
    }

    @Test
    void joinWatcherSourcePreservesRuleTargetingAndDelayedSendLogic() throws IOException {
        String source = readMainJava("com", "example", "addon", "modules", "JoinWatcher.java");

        assertTrue(source.contains("updatedRules.set(i, rule.withSendEnabled(false));"));
        assertTrue(source.contains("if (autoDisableSendAfterChat.get())"));
        assertTrue(source.contains("if (changed) trackerPlayers.set(updatedRules);"));
        assertTrue(source.contains("DeathMessageS2CPacket"));
        assertTrue(source.contains("handleDeathPacket"));
        assertTrue(source.contains("case Death -> trigger == RuleTrigger.Death;"));
        assertTrue(source.contains("case Both -> trigger == RuleTrigger.Join || trigger == RuleTrigger.Leave;"));
        assertTrue(source.contains("rule.soundValueFor(toRuleTrigger(trigger))"));
        assertTrue(source.contains("int delayMs = rule.chatDelayMs();"));
        assertTrue(source.contains("queueDelayedChatSend(i, rule, command, delayMs);"));
        assertTrue(source.contains("CHAT_SEND_EXECUTOR.schedule("));
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
