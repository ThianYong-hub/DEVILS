package com.example.addon;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class BuildVersionConfigTest {
    @Test
    void buildGradleUsesTagDrivenVersionFallbackChain() throws IOException {
        String source = Files.readString(Path.of("build.gradle.kts"));

        assertTrue(source.contains("System.getenv(\"APP_VERSION\")"));
        assertTrue(source.contains("findProperty(\"app_version\")"));
        assertTrue(source.contains("properties[\"mod_version\"] as String"));
        assertTrue(source.contains("version = resolvedAppVersion"));
    }
}
