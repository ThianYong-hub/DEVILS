package com.example.addon;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectConfigSuiteTest {
    @Test
    void multiArtifactBuildIsWiredForAddonAndGameOutputs() throws IOException {
        String rootBuild = readRepoFile("build.gradle.kts");
        String addonBuild = readRepoFile("devils-addon", "build.gradle.kts");
        String gameBuild = readRepoFile("devils-game", "build.gradle.kts");
        String sharedBuild = readRepoFile("devils-shared", "build.gradle.kts");

        assertTrue(rootBuild.contains("include(\"devils-addon\")") || readRepoFile("settings.gradle.kts").contains("include(\"devils-addon\")"));
        assertTrue(rootBuild.contains("include(\"devils-game\")") || readRepoFile("settings.gradle.kts").contains("include(\"devils-game\")"));
        assertTrue(rootBuild.contains("include(\"devils-shared\")") || readRepoFile("settings.gradle.kts").contains("include(\"devils-shared\")"));
        assertTrue(rootBuild.contains("collectReleaseArtifacts"));
        assertTrue(rootBuild.contains("from(project(\":devils-addon\").layout.buildDirectory.dir(\"libs\"))"));
        assertTrue(rootBuild.contains("from(project(\":devils-game\").layout.buildDirectory.dir(\"libs\"))"));
        assertTrue(rootBuild.contains(":devils-shared:build"));

        assertTrue(addonBuild.contains("System.getenv(\"DEVILS_ADDON_VERSION\")"));
        assertTrue(addonBuild.contains("properties[\"addon_version\"] as String"));
        assertTrue(addonBuild.contains("properties[\"addon_archives_base_name\"] as String"));
        assertTrue(addonBuild.contains("implementation(project(\":devils-shared\"))"));
        assertTrue(addonBuild.contains("from(sharedMainOutput)"));

        assertTrue(gameBuild.contains("System.getenv(\"DEVILS_GAME_VERSION\")"));
        assertTrue(gameBuild.contains("properties[\"game_version\"] as String"));
        assertTrue(gameBuild.contains("properties[\"game_archives_base_name\"] as String"));
        assertTrue(gameBuild.contains("implementation(project(\":devils-shared\"))"));
        assertTrue(gameBuild.contains("from(sharedMainOutput)"));
        assertFalse(gameBuild.contains("compileOnly(project(\":devils-addon\"))"));
        assertFalse(gameBuild.contains("modLocalRuntime(project(\":devils-addon\"))"));
        assertFalse(gameBuild.contains("register(\"devils-addon\")"));

        assertTrue(sharedBuild.contains("id(\"fabric-loom\")"));
        assertTrue(sharedBuild.contains("archivesName = \"devils-shared-internal\""));
    }

    @Test
    void addonAndGameMetadataDescribeSeparateArtifacts() throws IOException {
        String addonJson = readFile(Path.of("src", "main", "resources", "fabric.mod.json"));
        String gameJson = readRepoFile("devils-game", "src", "main", "resources", "fabric.mod.json");

        assertTrue(addonJson.contains("\"id\": \"devils-addon\""));
        assertTrue(addonJson.contains("\"com.example.addon.AddonTemplate\""));
        assertTrue(addonJson.contains("\"addon-template.mixins.json\""));
        assertTrue(addonJson.contains("\"java\": \">=21\""));
        assertFalse(addonJson.contains("\"icon\":"));

        assertTrue(gameJson.contains("\"id\": \"devils-game\""));
        assertTrue(gameJson.contains("\"com.example.addon.games.DevilsGameAddon\""));
        assertTrue(gameJson.contains("\"meteor-client\": \"*\""));
        assertFalse(gameJson.contains("\"devils-addon\": \"*\""));
        assertFalse(gameJson.contains("\"icon\":"));
    }

    @Test
    void mixinConfigReferencesCurrentMixinEntryPoints() throws IOException {
        String mixinJson = readFile(Path.of("src", "main", "resources", "addon-template.mixins.json"));

        assertTrue(mixinJson.contains("\"package\": \"com.example.addon.mixin\""));
        assertTrue(mixinJson.contains("\"ClientPlayerInteractionManagerInvoker\""));
        assertTrue(mixinJson.contains("\"ExampleMixin\""));
        assertTrue(mixinJson.contains("\"GuiMapXaeroSyncMixin\""));
        assertTrue(mixinJson.contains("\"ItemListSettingScreenMixin\""));
        assertTrue(Files.exists(mainJava("com", "example", "addon", "mixin", "ClientPlayerInteractionManagerInvoker.java")));
        assertTrue(Files.exists(mainJava("com", "example", "addon", "mixin", "XaeroMixinSupport.java")));
    }

    @Test
    void pullRequestWorkflowRunsTestsAndPublishesArtifactOnly() throws IOException {
        String workflow = readWorkflow("pull_request.yml");
        assertTrue(workflow.contains("name: Build Pull Request Artifacts"));
        assertTrue(workflow.contains("java-version: 21"));
        assertTrue(workflow.contains("./gradlew --no-daemon test"));
        assertTrue(workflow.contains("name: Upload Artifact"));
        assertFalse(workflow.contains("softprops/action-gh-release"));
    }

    @Test
    void devBuildWorkflowRemainsArtifactOnly() throws IOException {
        String workflow = readWorkflow("dev_build.yml");
        assertTrue(workflow.contains("name: Publish Development Build"));
        assertTrue(workflow.contains("java-version: 21"));
        assertTrue(workflow.contains("./gradlew --no-daemon test"));
        assertTrue(workflow.contains("branches-ignore:"));
        assertTrue(workflow.contains("- main"));
        assertTrue(workflow.contains("tags-ignore:"));
        assertFalse(workflow.contains("tag_name: snapshot"));
        assertFalse(workflow.contains("softprops/action-gh-release"));
    }

    @Test
    void releaseAutomationWorkflowsKeepDispatchOnlyFlow() throws IOException {
        String autoPatch = readWorkflow("release-auto-patch.yml");
        String releaseOnTag = readWorkflow("release-on-tag.yml");
        String manualTag = readWorkflow("release-manual-tag.yml");

        assertTrue(autoPatch.contains("name: Auto Patch Tag"));
        assertTrue(autoPatch.contains("workflow_dispatch"));
        assertFalse(autoPatch.contains("push:"));
        assertTrue(autoPatch.contains("group: release-tags"));
        assertTrue(autoPatch.contains("calc_next_patch_tag"));
        assertTrue(autoPatch.contains("attempts=10"));
        assertTrue(autoPatch.contains("actions: write"));
        assertTrue(autoPatch.contains("github.token"));
        assertTrue(autoPatch.contains("Trigger Release Workflow"));

        assertTrue(releaseOnTag.contains("name: Release From Tag"));
        assertTrue(releaseOnTag.contains("workflow_dispatch"));
        assertTrue(releaseOnTag.contains("inputs:"));
        assertTrue(releaseOnTag.contains("tag:"));
        assertFalse(releaseOnTag.contains("push:"));
        assertTrue(releaseOnTag.contains("ref: ${{ inputs.tag }}"));
        assertTrue(releaseOnTag.contains("id: module_versions"));
        assertTrue(releaseOnTag.contains("Generate release notes"));
        assertTrue(releaseOnTag.contains("softprops/action-gh-release@v2"));
        assertTrue(releaseOnTag.contains("body_path: RELEASE_NOTES.md"));
        assertTrue(releaseOnTag.contains("build/libs/*.jar"));
        assertTrue(releaseOnTag.contains("devils-game-"));

        assertTrue(manualTag.contains("name: Manual Release Tag"));
        assertTrue(manualTag.contains("workflow_dispatch"));
        assertTrue(manualTag.contains("Optional explicit release tag vX.Y.Z"));
        assertTrue(manualTag.contains("required: false"));
        assertTrue(manualTag.contains("^v[0-9]+\\.[0-9]+\\.[0-9]+$"));
        assertTrue(manualTag.contains("Auto PATCH mode selected"));
        assertTrue(manualTag.contains("group: release-tags"));
        assertTrue(manualTag.contains("actions: write"));
        assertTrue(manualTag.contains("Trigger Release Workflow"));
    }

    private static String readWorkflow(String fileName) throws IOException {
        return readRepoFile(".github", "workflows", fileName);
    }

    private static String readFile(Path path) throws IOException {
        return Files.readString(path);
    }

    private static String readRepoFile(String... parts) throws IOException {
        Path path = Path.of("..").normalize();
        for (String part : parts) path = path.resolve(part);
        return Files.readString(path);
    }

    private static Path mainJava(String... parts) {
        Path path = Path.of("src", "main", "java");
        for (String part : parts) path = path.resolve(part);
        return path;
    }
}
