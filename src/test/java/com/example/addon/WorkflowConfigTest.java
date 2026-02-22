package com.example.addon;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkflowConfigTest {
    @Test
    void pullRequestWorkflowRunsTests() throws IOException {
        String workflow = readWorkflow("pull_request.yml");
        assertTrue(workflow.contains("name: Build Pull Request Artifacts"));
        assertTrue(workflow.contains("java-version: 21"));
        assertTrue(workflow.contains("./gradlew test"));
    }

    @Test
    void devBuildWorkflowRunsTests() throws IOException {
        String workflow = readWorkflow("dev_build.yml");
        assertTrue(workflow.contains("name: Publish Development Build"));
        assertTrue(workflow.contains("java-version: 21"));
        assertTrue(workflow.contains("./gradlew test"));
    }

    private String readWorkflow(String fileName) throws IOException {
        return Files.readString(Path.of(".github", "workflows", fileName));
    }
}
