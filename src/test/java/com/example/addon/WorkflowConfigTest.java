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
        assertTrue(workflow.contains("tags-ignore:"));
        assertFalse(workflow.contains("tag_name: snapshot"));
        assertFalse(workflow.contains("softprops/action-gh-release"));
    }

    @Test
    void releaseAutoPatchWorkflowHasConcurrencyAndRetry() throws IOException {
        String workflow = readWorkflow("release-auto-patch.yml");
        assertTrue(workflow.contains("name: Auto Patch Tag"));
        assertTrue(workflow.contains("branches:"));
        assertTrue(workflow.contains("- main"));
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
        assertTrue(workflow.contains("tags:"));
        assertTrue(workflow.contains("- \"v*\""));
        assertTrue(workflow.contains("workflow_dispatch"));
        assertTrue(workflow.contains("RELEASE_TAG"));
        assertTrue(workflow.contains("APP_VERSION=${RELEASE_TAG#v}"));
        assertTrue(workflow.contains("softprops/action-gh-release@v2"));
        assertTrue(workflow.contains("generate_release_notes: true"));
        assertTrue(workflow.contains("files: build/libs/*.jar"));
    }

    @Test
    void manualTagWorkflowExistsAndValidatesSemverFormat() throws IOException {
        String workflow = readWorkflow("release-manual-tag.yml");
        assertTrue(workflow.contains("name: Manual Release Tag"));
        assertTrue(workflow.contains("workflow_dispatch"));
        assertTrue(workflow.contains("format vX.Y.Z"));
        assertTrue(workflow.contains("^v[0-9]+\\.[0-9]+\\.[0-9]+$"));
        assertTrue(workflow.contains("group: release-tags"));
        assertTrue(workflow.contains("actions: write"));
        assertTrue(workflow.contains("github.token"));
        assertTrue(workflow.contains("Trigger Release Workflow"));
    }

    private String readWorkflow(String fileName) throws IOException {
        return Files.readString(Path.of(".github", "workflows", fileName));
    }
}
