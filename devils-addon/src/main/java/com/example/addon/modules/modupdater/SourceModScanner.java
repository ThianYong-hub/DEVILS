package com.example.addon.modules.modupdater;

import com.example.addon.shared.sync.SyncJsonUtils;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static com.example.addon.modules.modupdater.ModAutoUpdaterText.normalizeKey;
import static com.example.addon.modules.modupdater.ModAutoUpdaterText.stripJarSuffix;

final class SourceModScanner {
    private SourceModScanner() {
    }

    static List<SourceModEntry> scan(Path sourceModsDir) {
        LinkedHashMap<String, SourceModEntry> byModId = new LinkedHashMap<>();
        if (sourceModsDir == null || !Files.isDirectory(sourceModsDir)) return List.of();

        ArrayList<Path> jars = new ArrayList<>();
        try (var stream = Files.list(sourceModsDir)) {
            stream
                .filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                .forEach(jars::add);
        } catch (Exception ignored) {
            return List.of();
        }

        jars.sort(Comparator.comparing(path -> path.getFileName().toString(), String.CASE_INSENSITIVE_ORDER));
        for (Path jar : jars) {
            SourceModEntry parsed = parseJar(jar);
            if (parsed == null) continue;

            SourceModEntry existing = byModId.get(parsed.modId);
            if (existing == null) {
                byModId.put(parsed.modId, parsed);
                continue;
            }

            existing.fileCount++;
            if (existing.version.isBlank() && !parsed.version.isBlank()) existing.version = parsed.version;
            if (existing.displayName.equals(existing.modId) && !parsed.displayName.equals(parsed.modId)) {
                existing.displayName = parsed.displayName;
            }
            existing.hasFabricMetadata = existing.hasFabricMetadata || parsed.hasFabricMetadata;
        }

        ArrayList<SourceModEntry> result = new ArrayList<>(byModId.values());
        result.sort(Comparator
            .comparing((SourceModEntry entry) -> entry.displayName.toLowerCase(Locale.ROOT))
            .thenComparing(entry -> entry.modId)
        );
        return result;
    }

    private static SourceModEntry parseJar(Path jar) {
        String fileName = jar.getFileName() == null ? "" : jar.getFileName().toString();
        String fallbackId = normalizeKey(stripJarSuffix(fileName));

        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry modJson = zip.getEntry("fabric.mod.json");
            if (modJson == null) {
                return new SourceModEntry(fallbackId, fallbackId, "", fileName, false);
            }

            JsonObject root;
            try (InputStreamReader reader = new InputStreamReader(zip.getInputStream(modJson), StandardCharsets.UTF_8)) {
                root = JsonParser.parseReader(reader).getAsJsonObject();
            }

            String modId = normalizeKey(SyncJsonUtils.readString(root, "id", fallbackId));
            if (modId.isBlank()) modId = fallbackId;
            String version = SyncJsonUtils.readString(root, "version", "");
            String name = SyncJsonUtils.readString(root, "name", modId);
            if (name == null || name.isBlank()) name = modId;
            return new SourceModEntry(modId, name.trim(), version == null ? "" : version.trim(), fileName, true);
        } catch (Exception ignored) {
            return new SourceModEntry(fallbackId, fallbackId, "", fileName, false);
        }
    }

    static final class SourceModEntry {
        final String modId;
        String displayName;
        String version;
        final String sampleFileName;
        int fileCount;
        boolean hasFabricMetadata;

        SourceModEntry(String modId, String displayName, String version, String sampleFileName, boolean hasFabricMetadata) {
            this.modId = modId == null ? "" : modId;
            this.displayName = displayName == null ? this.modId : displayName;
            this.version = version == null ? "" : version;
            this.sampleFileName = sampleFileName == null ? "" : sampleFileName;
            this.fileCount = 1;
            this.hasFabricMetadata = hasFabricMetadata;
        }
    }
}
