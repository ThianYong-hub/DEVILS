package com.example.addon;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assumptions.assumeTrue;
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
        assertTrue(addonJson.contains("\"META-INF/devils-addon/mixins/addon-template.mixins.json\""));
        assertTrue(addonJson.contains("\"java\": \">=21\""));
        assertTrue(addonJson.contains("\"icon\": \"assets/devils-addon/icon.png\""));
        assertTrue(addonJson.contains("\"accessWidener\": \"META-INF/devils-addon/accesswidener/devils-addon.assimilated.accesswidener\""));

        assertTrue(gameJson.contains("\"id\": \"devils-game\""));
        assertTrue(gameJson.contains("\"com.example.addon.games.DevilsGameAddon\""));
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
            "example",
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
            "example",
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
            "example",
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
        assertTrue(readme.contains("If `SYNC_REQUIRE_REQUEST_SIGNING=true`, the request also needs the normal `X-Devils-*` signing headers"));

        assertTrue(envExample.contains("Required auth + transport signing"));
        assertTrue(envExample.contains("Compatibility window only. Prefer the names above."));
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
    void codexArtifactIndexListsCanonicalEvidenceSet() throws IOException {
        Path artifactDir = Path.of("..", "codex log").normalize();
        assumeTrue(Files.isDirectory(artifactDir), "Optional local artifact directory is absent in a clean checkout.");
        Path indexPath = artifactDir.resolve("ARTIFACT_INDEX.md");
        assertTrue(Files.exists(indexPath), "codex log must contain ARTIFACT_INDEX.md when the artifact directory is present.");
        String index = Files.readString(indexPath);

        assertTrue(index.contains("STASHMOVER_MIGRATION_AUDIT.md"));
        assertTrue(index.contains("STASHMOVER_ASSIMILATION_PLAN.md"));
        assertTrue(index.contains("STASHMOVER_PEARL_ROTATION_FIX_REPORT.md"));
        assertTrue(index.contains("STASHMOVER_OWN_PEARL_TRACKING_REPORT.md"));
        assertTrue(index.contains("STASHMOVER_SLOT_POLICY_REPORT.md"));
        assertTrue(index.contains("STASHMOVER_BARITONE_INTEGRATION_REPORT.md"));
        assertTrue(index.contains("STASHMOVER_CONTAINER_AUDIT_REPORT.md"));
        assertTrue(index.contains("STASHMOVER_STATE_MACHINE_REPORT.md"));
        assertTrue(index.contains("STASHMOVER_LEGACY_CLEANUP_REPORT.md"));
        assertTrue(index.contains("STASHMOVER_SETTINGS_INTEGRATION_REPORT.md"));
        assertTrue(index.contains("STASHMOVER_LIVE_REPRO_REPORT.md"));
        assertTrue(index.contains("STASHMOVER_PEARL_TRAJECTORY_ROOT_CAUSE_REPORT.md"));
        assertTrue(index.contains("STASHMOVER_PEARL_TARGETING_FIX_REPORT.md"));
        assertTrue(index.contains("STASHMOVER_POST_PEARL_PHASE_EXIT_REPORT.md"));
        assertTrue(index.contains("STASHMOVER_MOVER_CONTINUATION_FIX_REPORT.md"));
        assertTrue(index.contains("STASHMOVER_BARITONE_HANDOFF_REPORT.md"));
        assertTrue(index.contains("STASHMOVER_STALL_RECOVERY_REPORT.md"));
        assertTrue(index.contains("STASHMOVER_RUNTIME_DIAGNOSTICS_REPORT.md"));
        assertTrue(index.contains("STASHMOVER_TARGETED_RUNTIME_VALIDATION_REPORT.md"));
        assertTrue(index.contains("STASHMOVER_FINAL_EXECUTION_REPORT.md"));
        assertTrue(index.contains("STASHMOVER_BUILD.log"));
        assertTrue(index.contains("runtime-smoke.log"));
        assertTrue(index.contains("stashmover-live-repro.log"));
        assertTrue(index.contains("stashmover-targeted-runtime.log"));
        assertTrue(index.contains("ARTIFACT_INDEX.md"));
        assertTrue(index.contains("overwrite"));
        assertTrue(index.contains("No version suffixes"));
    }

    @Test
    void codexLogDirectoryRemainsSmallAndWhitelistedWhenPresent() throws IOException {
        Path artifactDir = Path.of("..", "codex log").normalize();
        assumeTrue(Files.isDirectory(artifactDir), "Optional local artifact directory is absent in a clean checkout.");

        List<String> files;
        try (var stream = Files.list(artifactDir)) {
            files = stream
                .filter(Files::isRegularFile)
                .map(path -> path.getFileName().toString())
                .sorted()
                .toList();
        }

        Set<String> allowed = Set.of(
            "ARTIFACT_INDEX.md",
            "STASHMOVER_MIGRATION_AUDIT.md",
            "STASHMOVER_ASSIMILATION_PLAN.md",
            "STASHMOVER_PEARL_ROTATION_FIX_REPORT.md",
            "STASHMOVER_OWN_PEARL_TRACKING_REPORT.md",
            "STASHMOVER_SLOT_POLICY_REPORT.md",
            "STASHMOVER_BARITONE_INTEGRATION_REPORT.md",
            "STASHMOVER_CONTAINER_AUDIT_REPORT.md",
            "STASHMOVER_STATE_MACHINE_REPORT.md",
            "STASHMOVER_LEGACY_CLEANUP_REPORT.md",
            "STASHMOVER_SETTINGS_INTEGRATION_REPORT.md",
            "STASHMOVER_LIVE_REPRO_REPORT.md",
            "STASHMOVER_PEARL_TRAJECTORY_ROOT_CAUSE_REPORT.md",
            "STASHMOVER_PEARL_TARGETING_FIX_REPORT.md",
            "STASHMOVER_POST_PEARL_PHASE_EXIT_REPORT.md",
            "STASHMOVER_MOVER_CONTINUATION_FIX_REPORT.md",
            "STASHMOVER_BARITONE_HANDOFF_REPORT.md",
            "STASHMOVER_STALL_RECOVERY_REPORT.md",
            "STASHMOVER_RUNTIME_DIAGNOSTICS_REPORT.md",
            "STASHMOVER_TARGETED_RUNTIME_VALIDATION_REPORT.md",
            "STASHMOVER_FINAL_EXECUTION_REPORT.md",
            "STASHMOVER_BUILD.log",
            "runtime-smoke.log",
            "stashmover-live-repro.log",
            "stashmover-targeted-runtime.log",
            "STASHMOVER_ARTIFACT_INSPECTION_NOTE.md"
        );

        assertTrue(files.size() <= 26, "codex log must stay small and canonical: " + files);
        for (String file : files) {
            assertTrue(allowed.contains(file), "Unexpected file in codex log: " + file);
        }
        assertFalse(
            files.stream().anyMatch(name -> name.endsWith(".log") && !Set.of("STASHMOVER_BUILD.log", "runtime-smoke.log", "stashmover-live-repro.log", "stashmover-targeted-runtime.log").contains(name)),
            "Only canonical StashMover raw logs may remain in codex log: " + files
        );
        assertTrue(files.contains("ARTIFACT_INDEX.md"), "ARTIFACT_INDEX.md must be present when codex log contains artifacts.");
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
        assertTrue(releaseOnTag.contains("id: verify_assets"));
        assertTrue(releaseOnTag.contains("addon_asset=${addon_asset}"));
        assertTrue(releaseOnTag.contains("game_asset=${game_asset}"));
        assertTrue(releaseOnTag.contains("Generate release notes"));
        assertTrue(releaseOnTag.contains("softprops/action-gh-release@v2"));
        assertTrue(releaseOnTag.contains("body_path: RELEASE_NOTES.md"));
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
