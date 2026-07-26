package com.devils.addon;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
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
        assertTrue(rootBuild.contains("DEVILS_ADDON_VERSION"));
        assertTrue(rootBuild.contains("DEVILS_GAME_VERSION"));
        assertTrue(rootBuild.contains("addon_version_override"));
        assertTrue(rootBuild.contains("game_version_override"));
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
        assertTrue(addonJson.contains("\"com.devils.addon.DevilsAddon\""));
        assertTrue(addonJson.contains("\"META-INF/devils-addon/mixins/devils-addon.mixins.json\""));
        assertTrue(addonJson.contains("\"java\": \">=21\""));
        assertTrue(addonJson.contains("\"icon\": \"assets/devils-addon/icon.png\""));
        assertTrue(addonJson.contains("\"accessWidener\": \"META-INF/devils-addon/accesswidener/devils-addon.assimilated.accesswidener\""));

        assertTrue(gameJson.contains("\"id\": \"devils-game\""));
        assertTrue(gameJson.contains("\"com.devils.addon.games.DevilsGameAddon\""));
        assertTrue(gameJson.contains("\"meteor-client\": \"*\""));
        assertFalse(gameJson.contains("\"devils-addon\": \"*\""));
        assertTrue(gameJson.contains("\"icon\": \"assets/devils-game/icon.png\""));
    }

    @Test
    void sharedSyncConfigExposesClearPreferredNamesAndKeepsLegacyFallbacks() throws IOException {
        String source = readRepoFile(
            "devils-shared",
            "src",
            "main",
            "java",
            "com",
            "devils",
            "addon",
            "shared",
            "sync",
            "AbstractSyncConfigModule.java"
        );

        assertTrue(source.contains(".name(\"auth-token\")"));
        assertTrue(source.contains(".name(\"transport-signing-key\")"));
        assertTrue(source.contains(".name(\"e2e-secret\")"));
        assertTrue(source.contains("inspectSyncConfig(boolean requireE2e, boolean allowUnsignedRequests)"));
        assertTrue(source.contains("emitSyncConfigDiagnostics(String consumerName, SyncConfigDiagnostics.Audit audit)"));
        assertTrue(source.contains("clearDuplicateLegacyValue("));
        assertTrue(source.contains("refreshLegacyMigration()"));
        assertTrue(source.contains("public NbtCompound toTag()"));
        assertTrue(source.contains("public Module fromTag(NbtCompound tag)"));
        assertTrue(source.contains(".name(\"token\")"));
        assertTrue(source.contains(".name(\"request-signing-key\")"));
        assertTrue(source.contains(".name(\"encryption-key\")"));
        assertTrue(source.contains("firstNonBlank(authToken.get(), legacyToken.get())"));
        assertTrue(source.contains("firstNonBlank(transportSigningKey.get(), legacyRequestSigningKey.get())"));
        assertTrue(source.contains("firstNonBlank(e2eSecret.get(), legacyEncryptionKey.get())"));

        String diagnosticsSource = readRepoFile(
            "devils-shared",
            "src",
            "main",
            "java",
            "com",
            "devils",
            "addon",
            "shared",
            "sync",
            "SyncConfigDiagnostics.java"
        );
        assertTrue(diagnosticsSource.contains("class SyncConfigDiagnostics"));
        assertTrue(diagnosticsSource.contains("sync-http-disabled"));
        assertTrue(diagnosticsSource.contains("sync-auth-token-empty"));
        assertTrue(diagnosticsSource.contains("sync-e2e-secret-empty"));
        assertTrue(diagnosticsSource.contains("sync-config-legacy-mode"));
        assertTrue(diagnosticsSource.contains("sync-config-conflicting-mode"));
        assertTrue(diagnosticsSource.contains("enum Mode"));
        assertTrue(diagnosticsSource.contains("overallMode("));
    }

    @Test
    void syncDocsAndTemplatesPreferNewNamesAndDemoteLegacyToCompatibility() throws IOException {
        String readme = readRepoFile("README.md");
        String envExample = readRepoFile("SyncHub", ".env.example");
        String backendSource = readRepoFile("SyncHub", "sync_backend.py");
        String dockerCompose = readRepoFile("SyncHub", "docker-compose.yml");
        String adminProbe = readRepoFile("SyncHub", "tests", "admin_config_runtime_probe.py");
        String migrationRuntimeTest = readRepoFile(
            "devils-addon",
            "src",
            "test",
            "java",
            "com",
            "devils",
            "addon",
            "shared",
            "sync",
            "SyncConfigMigrationRuntimeTest.java"
        );

        assertTrue(readme.contains("SYNC_AUTH_TOKEN"));
        assertTrue(readme.contains("SYNC_REQUEST_SIGNING_KEY"));
        assertTrue(readme.contains("SYNC_E2E_SECRET"));
        assertTrue(readme.contains("Resolve order and migration behavior:"));
        assertTrue(readme.contains("preferred names win over legacy aliases"));
        assertTrue(readme.contains("simple encryption so admins cant sniff coords"));
        assertTrue(readme.contains("If `SYNC_REQUIRE_REQUEST_SIGNING=true`, the request also needs the normal `X-Devils-*` signing headers"));

        assertTrue(envExample.contains("basic auth + signing"));
        assertTrue(envExample.contains("old names still work, but dont use them in new configs"));
        assertTrue(envExample.contains("SYNC_ADMIN_AUTH_TOKEN"));
        assertTrue(envExample.contains("SYNC_E2E_SECRET=replace_me"));

        assertTrue(backendSource.contains("path == '/v1/admin/config'"));
        assertTrue(backendSource.contains("config-mode"));
        assertTrue(backendSource.contains("BACKEND_DEPRECATION_STATUS"));
        assertTrue(dockerCompose.contains("SYNC_REQUIRE_REQUEST_SIGNING"));
        assertFalse(dockerCompose.contains("SYNC_REQUIRE_SIGNED:"));
        assertTrue(adminProbe.contains("ARTIFACT_DIR = REPO_ROOT / \"build\" / \"test-artifacts\""));
        assertTrue(adminProbe.contains("admin-config-runtime-probe.json"));
        assertTrue(migrationRuntimeTest.contains("Path.of(\"build\", \"test-artifacts\", \"sync-config-migration-runtime.json\")"));
    }

    @Test
    void embeddedGuiIconsKeepTransparentBackgrounds() throws IOException {
        assertIconHasTransparentBackground(Path.of("src", "main", "resources", "assets", "devils-addon", "textures", "gui", "devils_ping_icon_white.png"));
        assertIconHasTransparentBackground(Path.of("src", "main", "resources", "assets", "devils-addon", "textures", "gui", "devils_map_icon.png"));

        String mapIconManager = readRepoFile("devils-addon", "src", "main", "java", "com", "devils", "addon", "util", "MapIconManager.java");
        String pingConstants = readRepoFile("devils-addon", "src", "main", "java", "com", "devils", "addon", "modules", "ping", "PingConstants.java");

        assertTrue(mapIconManager.contains("DEFAULT_PING_ICON_PATH"));
        assertTrue(mapIconManager.contains("DEFAULT_MAP_ICON_PATH"));
        assertTrue(pingConstants.contains("MapIconManager.DEFAULT_PING_ICON_PATH"));
    }

    @Test
    void mixinConfigReferencesCurrentMixinEntryPoints() throws IOException {
        String mixinJson = readFile(Path.of("src", "main", "resources", "devils-addon.mixins.json"));

        assertTrue(mixinJson.contains("\"package\": \"com.devils.addon.mixin\""));
        assertTrue(mixinJson.contains("\"ClientPlayerInteractionManagerInvoker\""));
        assertTrue(mixinJson.contains("\"ClientPlayerInteractionManagerInvoker\""));
        assertTrue(Files.exists(mainJava("com", "devils", "addon", "mixin", "ClientPlayerInteractionManagerInvoker.java")));
    }

    @Test
    void pullRequestWorkflowRunsTestsAndPublishesArtifactOnly() throws IOException {
        String workflow = readWorkflow("pull_request.yml");
        assertTrue(workflow.contains("name: Build Pull Request Artifacts"));
        assertTrue(workflow.contains("java-version: 21"));
        assertTrue(workflow.contains("./gradlew --no-daemon test"));
        assertTrue(workflow.contains("python3 -m unittest SyncHub.tests.test_sync_backend"));
        assertTrue(workflow.contains("Check addon jar contents"));
        assertTrue(workflow.contains("name: Upload Artifact"));
        assertFalse(workflow.contains("softprops/action-gh-release"));
    }

    @Test
    void devBuildWorkflowRemainsArtifactOnly() throws IOException {
        String workflow = readWorkflow("dev_build.yml");
        assertTrue(workflow.contains("name: Publish Development Build"));
        assertTrue(workflow.contains("java-version: 21"));
        assertTrue(workflow.contains("./gradlew --no-daemon test"));
        assertTrue(workflow.contains("python3 -m unittest SyncHub.tests.test_sync_backend"));
        assertTrue(workflow.contains("Check addon jar contents"));
        assertTrue(workflow.contains("branches-ignore:"));
        assertTrue(workflow.contains("- main"));
        assertTrue(workflow.contains("tags-ignore:"));
        assertFalse(workflow.contains("tag_name: snapshot"));
        assertFalse(workflow.contains("softprops/action-gh-release"));
    }

    @Test
    void releaseAutomationWorkflowsKeepTagPushFlow() throws IOException {
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
        assertTrue(autoPatch.contains("game_version={version}"));
        assertTrue(autoPatch.contains("Current game build (`{version}`)"));
        assertTrue(autoPatch.contains("chore(release): prepare"));
        assertFalse(autoPatch.contains("Trigger Release Workflow"));

        assertTrue(releaseOnTag.contains("name: Release From Tag"));
        assertTrue(releaseOnTag.contains("push:"));
        assertTrue(releaseOnTag.contains("tags:"));
        assertTrue(releaseOnTag.contains("- \"v*\""));
        assertTrue(releaseOnTag.contains("workflow_dispatch"));
        assertTrue(releaseOnTag.contains("inputs:"));
        assertTrue(releaseOnTag.contains("tag:"));
        assertTrue(releaseOnTag.contains("ref: ${{ inputs.tag || github.ref_name }}"));
        assertTrue(releaseOnTag.contains("id: module_versions"));
        assertTrue(releaseOnTag.contains("does not match game_version"));
        assertTrue(releaseOnTag.contains("id: verify_assets"));
        assertTrue(releaseOnTag.contains("addon_asset=${addon_asset}"));
        assertTrue(releaseOnTag.contains("game_asset=${game_asset}"));
        assertTrue(releaseOnTag.contains("Generate release notes"));
        assertTrue(releaseOnTag.contains("softprops/action-gh-release@v2"));
        assertTrue(releaseOnTag.contains("body_path: RELEASE_NOTES.md"));
        assertTrue(releaseOnTag.contains("python3 -m unittest SyncHub.tests.test_sync_backend"));
        assertTrue(releaseOnTag.contains("steps.verify_assets.outputs.addon_asset"));
        assertTrue(releaseOnTag.contains("steps.verify_assets.outputs.game_asset"));

        assertTrue(manualTag.contains("name: Manual Release Tag"));
        assertTrue(manualTag.contains("workflow_dispatch"));
        assertTrue(manualTag.contains("Optional explicit release tag vX.Y.Z"));
        assertTrue(manualTag.contains("required: false"));
        assertTrue(manualTag.contains("^v[0-9]+\\.[0-9]+\\.[0-9]+$"));
        assertTrue(manualTag.contains("Auto PATCH mode selected"));
        assertTrue(manualTag.contains("group: release-tags"));
        assertTrue(manualTag.contains("actions: write"));
        assertTrue(manualTag.contains("game_version={version}"));
        assertTrue(manualTag.contains("Current game build (`{version}`)"));
        assertTrue(manualTag.contains("chore(release): prepare"));
        assertFalse(manualTag.contains("Trigger Release Workflow"));
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

    private static void assertIconHasTransparentBackground(Path path) throws IOException {
        assertTrue(Files.exists(path), "Missing icon: " + path);
        BufferedImage image = ImageIO.read(path.toFile());
        assertTrue(image != null, "Unreadable icon: " + path);

        int transparent = 0;
        int opaque = 0;
        int opaqueBlack = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int argb = image.getRGB(x, y);
                int alpha = (argb >>> 24) & 0xFF;
                if (alpha == 0) {
                    transparent++;
                    continue;
                }

                opaque++;
                int red = (argb >>> 16) & 0xFF;
                int green = (argb >>> 8) & 0xFF;
                int blue = argb & 0xFF;
                if (red <= 8 && green <= 8 && blue <= 8) opaqueBlack++;
            }
        }

        assertTrue(transparent > 0, "Icon background is not transparent: " + path);
        assertTrue(opaque > 0, "Icon has no visible pixels: " + path);
        assertTrue(opaqueBlack == 0, "Icon still has opaque black pixels: " + path + " count=" + opaqueBlack);
    }
}
