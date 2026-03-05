package com.example.addon;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkflowConfigTest {
    @Test
    void pullRequestWorkflowRunsTests() throws IOException {
        String workflow = readWorkflow("pull_request.yml");
        assertTrue(workflow.contains("name: Build Pull Request Artifacts"));
        assertTrue(workflow.contains("java-version: 21"));
        assertTrue(workflow.contains("./gradlew --no-daemon test"));
        assertTrue(workflow.contains("name: Upload Artifact"));
        assertFalse(workflow.contains("softprops/action-gh-release"));
    }

    @Test
    void devBuildWorkflowIsArtifactOnly() throws IOException {
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
    void releaseAutoPatchWorkflowRunsOnlyForMergedPrCommits() throws IOException {
        String workflow = readWorkflow("release-auto-patch.yml");
        assertTrue(workflow.contains("name: Auto Patch Tag"));
        assertTrue(workflow.contains("workflow_dispatch"));
        assertFalse(workflow.contains("push:"));
        assertTrue(workflow.contains("group: release-tags"));
        assertTrue(workflow.contains("calc_next_patch_tag"));
        assertTrue(workflow.contains("attempts=10"));
        assertTrue(workflow.contains("actions: write"));
        assertTrue(workflow.contains("github.token"));
        assertTrue(workflow.contains("x-access-token:${GITHUB_TOKEN}@github.com/${GITHUB_REPOSITORY}.git"));
        assertTrue(workflow.contains("Trigger Release Workflow"));
        assertTrue(workflow.contains("workflows/release-on-tag.yml/dispatches"));
    }

    @Test
    void releaseOnTagWorkflowPublishesStableRelease() throws IOException {
        String workflow = readWorkflow("release-on-tag.yml");
        assertTrue(workflow.contains("name: Release From Tag"));
        assertTrue(workflow.contains("workflow_dispatch"));
        assertTrue(workflow.contains("inputs:"));
        assertTrue(workflow.contains("tag:"));
        assertFalse(workflow.contains("push:"));
        assertTrue(workflow.contains("ref: ${{ inputs.tag }}"));
        assertTrue(workflow.contains("id: resolve_tag"));
        assertTrue(workflow.contains("id: app_version"));
        assertTrue(workflow.contains("APP_VERSION=${app_version}"));
        assertTrue(workflow.contains("Prepare release artifact name"));
        assertTrue(workflow.contains("id: prepare_asset"));
        assertTrue(workflow.contains("devils-addon-${{ steps.app_version.outputs.app_version }}.jar"));
        assertTrue(workflow.contains("Generate release notes"));
        assertTrue(workflow.contains("RELEASE_NOTES.md"));
        assertTrue(workflow.contains("softprops/action-gh-release@v2"));
        assertTrue(workflow.contains("body_path: RELEASE_NOTES.md"));
        assertTrue(workflow.contains("files: ${{ steps.prepare_asset.outputs.release_asset }}"));
    }

    @Test
    void manualTagWorkflowExistsAndValidatesSemverFormat() throws IOException {
        String workflow = readWorkflow("release-manual-tag.yml");
        assertTrue(workflow.contains("name: Manual Release Tag"));
        assertTrue(workflow.contains("workflow_dispatch"));
        assertTrue(workflow.contains("Optional explicit release tag vX.Y.Z"));
        assertTrue(workflow.contains("required: false"));
        assertTrue(workflow.contains("^v[0-9]+\\.[0-9]+\\.[0-9]+$"));
        assertTrue(workflow.contains("Auto PATCH mode selected"));
        assertTrue(workflow.contains("calc_next_patch_tag"));
        assertTrue(workflow.contains("group: release-tags"));
        assertTrue(workflow.contains("actions: write"));
        assertTrue(workflow.contains("github.token"));
        assertTrue(workflow.contains("Trigger Release Workflow"));
    }

    private String readWorkflow(String fileName) throws IOException {
        return Files.readString(Path.of(".github", "workflows", fileName));
    }
}
