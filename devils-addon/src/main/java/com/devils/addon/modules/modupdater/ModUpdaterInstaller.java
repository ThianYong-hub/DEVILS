package com.devils.addon.modules.modupdater;

import com.devils.addon.shared.sync.SyncJsonUtils;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.metadata.version.VersionPredicate;

import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static com.devils.addon.modules.modupdater.ModAutoUpdaterText.safe;

final class ModUpdaterInstaller {
    private final HttpClient http;

    ModUpdaterInstaller(HttpClient http) {
        this.http = http;
    }

    static boolean targetAlreadyHasLatest(InstalledMod mod, RemoteRelease latest, Path targetModsDir, String targetGameVersion, String loader) {
        if (mod == null || latest == null || targetModsDir == null) return false;
        try {
            Path source = mod.jarPath.toAbsolutePath().normalize();
            Path destination = targetModsDir.resolve(safeReleaseFileName(latest.fileName)).toAbsolutePath().normalize();
            if (source.equals(destination)) return false;
            if (!Files.exists(destination)) return false;
            if (!validateJarCompatibility(destination, targetGameVersion, loader).isBlank()) return false;

            long size = Files.size(destination);
            return size > 0L;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static String safeReleaseFileName(String rawName) throws IOException {
        String trimmed = safe(rawName).trim();
        String bare;
        try {
            Path namePart = trimmed.isBlank() ? null : Path.of(trimmed).getFileName();
            bare = namePart == null ? "" : namePart.toString();
        } catch (InvalidPathException e) {
            throw new IOException("unsafe-release-filename:" + trimmed);
        }
        if (bare.isBlank() || bare.equals(".") || bare.equals("..")
                || bare.indexOf('/') >= 0 || bare.indexOf('\\') >= 0) {
            throw new IOException("unsafe-release-filename:" + trimmed);
        }
        return bare;
    }

    void applyUpdate(InstalledMod mod, RemoteRelease release, UpdateRequest request, Path backupDir) throws Exception {
        Path temp = Files.createTempFile(request.workspaceDir, "download-", ".jar");
        try {
            downloadRelease(release, request.githubToken, temp);
            String compatibilityIssue = validateJarCompatibility(temp, request.targetGameVersion, request.loader);
            if (!compatibilityIssue.isBlank()) {
                throw new IncompatibleReleaseException(compatibilityIssue);
            }

            Path source = mod.jarPath;
            Path targetModsDir = request.targetModsDir;
            Path destination = targetModsDir.resolve(safeReleaseFileName(release.fileName));
            Path normalizedDest = destination.toAbsolutePath().normalize();
            if (!normalizedDest.startsWith(targetModsDir.toAbsolutePath().normalize())) {
                throw new IOException("release-escapes-mods-dir");
            }
            Path currentTargetVersion = targetModsDir.resolve(mod.fileName);
            Path sourceParent = source.toAbsolutePath().normalize().getParent();
            Path normalizedTargetDir = targetModsDir.toAbsolutePath().normalize();
            boolean sourceEqualsTarget = sourceParent != null && sourceParent.equals(normalizedTargetDir);
            LinkedHashMap<Path, Path> rollbackFiles = new LinkedHashMap<>();

            try {
                if (source.equals(destination)) {
                    moveToBackupForRollback(source, backupDir, request.workspaceDir, rollbackFiles);
                    moveWithReplace(temp, destination);
                    return;
                }

                if (Files.exists(currentTargetVersion) && !currentTargetVersion.equals(destination)) {
                    moveToBackupForRollback(currentTargetVersion, backupDir, request.workspaceDir, rollbackFiles);
                }
                if (Files.exists(destination)) moveToBackupForRollback(destination, backupDir, request.workspaceDir, rollbackFiles);
                if (sourceEqualsTarget && Files.exists(source)) moveToBackupForRollback(source, backupDir, request.workspaceDir, rollbackFiles);
                moveWithReplace(temp, destination);
            } catch (Exception e) {
                rollbackBackups(rollbackFiles);
                throw e;
            }
        } finally {
            tryDelete(temp);
        }
    }

    static String validateJarCompatibility(Path jar, String targetGameVersion, String loader) {
        String target = safe(targetGameVersion).trim();
        if (target.isBlank()) return "";
        String normalizedLoader = safe(loader).trim().toLowerCase(Locale.ROOT);
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry entry = zip.getEntry("fabric.mod.json");
            if (entry == null) {
                if (normalizedLoader.isBlank() || normalizedLoader.contains("fabric")) {
                    return "release-has-no-fabric.mod.json";
                }
                return "";
            }

            JsonObject root;
            try (InputStreamReader reader = new InputStreamReader(zip.getInputStream(entry), StandardCharsets.UTF_8)) {
                root = JsonParser.parseReader(reader).getAsJsonObject();
            }

            if (root == null || !root.has("depends") || !root.get("depends").isJsonObject()) return "";
            JsonObject depends = root.getAsJsonObject("depends");
            if (!depends.has("minecraft")) return "";

            ArrayList<String> predicates = new ArrayList<>();
            collectDependencyPredicates(depends.get("minecraft"), predicates);
            if (predicates.isEmpty()) return "";

            Version current = Version.parse(target);
            for (String rawPredicate : predicates) {
                String predicate = safe(rawPredicate).trim();
                if (predicate.isBlank()) continue;
                try {
                    if (VersionPredicate.parse(predicate).test(current)) return "";
                } catch (Exception ignored) {
                }
            }

            return "release-minecraft-range-incompatible (" + String.join(" || ", predicates) + " vs " + target + ")";
        } catch (Exception ignored) {
            return "";
        }
    }

    private static void collectDependencyPredicates(JsonElement element, List<String> out) {
        if (element == null || out == null || element.isJsonNull()) return;
        if (element.isJsonPrimitive()) {
            String value = safe(element.getAsString()).trim();
            if (!value.isBlank()) out.add(value);
            return;
        }

        if (element.isJsonArray()) {
            for (JsonElement part : element.getAsJsonArray()) {
                collectDependencyPredicates(part, out);
            }
            return;
        }

        if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            if (object.has("version")) {
                collectDependencyPredicates(object.get("version"), out);
                return;
            }
            if (object.has("versions")) {
                collectDependencyPredicates(object.get("versions"), out);
            }
        }
    }

    boolean copySourceJarToTarget(InstalledMod mod, Path targetModsDir, Path backupDir) {
        try {
            Path source = mod.jarPath.toAbsolutePath().normalize();
            Path destination = targetModsDir.resolve(mod.fileName).toAbsolutePath().normalize();
            if (source.equals(destination)) return false;

            if (destination.getParent() != null) Files.createDirectories(destination.getParent());
            if (Files.exists(destination)) {
                if (filesHaveSameContent(source, destination)) return true;
                moveToBackupOrDelete(destination, backupDir);
            }

            Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private void downloadRelease(RemoteRelease release, String githubToken, Path destination) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(release.downloadUrl))
            .timeout(Duration.ofSeconds(90))
            .header("Accept", "application/octet-stream")
            .header("User-Agent", ModAutoUpdaterEngine.USER_AGENT)
            .GET();
        if (release.provider == SourceProvider.GITHUB && !safe(githubToken).isBlank()) {
            builder.header("Authorization", "Bearer " + githubToken.trim());
        }

        HttpResponse<Path> response = http.send(
            builder.build(),
            HttpResponse.BodyHandlers.ofFile(destination, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)
        );
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Download failed: " + SyncJsonUtils.parseHttpError(response.statusCode(), readBodyPreview(destination)));
        }
    }

    private static void moveWithReplace(Path source, Path destination) throws IOException {
        if (destination.getParent() != null) Files.createDirectories(destination.getParent());
        try {
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception ignored) {
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void moveToBackupOrDelete(Path file, Path backupDir) throws IOException {
        if (!Files.exists(file)) return;
        if (backupDir == null) {
            Files.deleteIfExists(file);
            return;
        }

        Files.move(file, nextBackupPath(file, backupDir), StandardCopyOption.REPLACE_EXISTING);
    }

    private static void moveToBackupForRollback(
        Path file,
        Path backupDir,
        Path workspaceDir,
        Map<Path, Path> rollbackFiles
    ) throws IOException {
        if (!Files.exists(file)) return;

        Path target;
        if (backupDir == null) {
            Files.createDirectories(workspaceDir);
            target = Files.createTempFile(workspaceDir, file.getFileName().toString() + "-rollback-", ".jar");
            Files.move(file, target, StandardCopyOption.REPLACE_EXISTING);
        } else {
            target = nextBackupPath(file, backupDir);
            Files.move(file, target, StandardCopyOption.REPLACE_EXISTING);
        }
        rollbackFiles.put(file, target);
    }

    private static Path nextBackupPath(Path file, Path backupDir) throws IOException {
        Files.createDirectories(backupDir);
        Path target = backupDir.resolve(file.getFileName().toString());
        int index = 1;
        while (Files.exists(target)) {
            target = backupDir.resolve(file.getFileName().toString() + "." + index + ".bak");
            index++;
        }
        return target;
    }

    private static void rollbackBackups(Map<Path, Path> rollbackFiles) {
        ArrayList<Map.Entry<Path, Path>> entries = new ArrayList<>(rollbackFiles.entrySet());
        for (int i = entries.size() - 1; i >= 0; i--) {
            Map.Entry<Path, Path> entry = entries.get(i);
            try {
                Path original = entry.getKey();
                Path backup = entry.getValue();
                if (!Files.exists(backup) || Files.exists(original)) continue;
                if (original.getParent() != null) Files.createDirectories(original.getParent());
                Files.move(backup, original, StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception ignored) {
            }
        }
    }

    private static boolean filesHaveSameContent(Path first, Path second) throws IOException {
        long firstSize = Files.size(first);
        long secondSize = Files.size(second);
        if (firstSize != secondSize) return false;
        return Files.mismatch(first, second) == -1L;
    }

    private static void tryDelete(Path path) {
        if (path == null) return;
        try {
            Files.deleteIfExists(path);
        } catch (Exception ignored) {
        }
    }

    private static String readBodyPreview(Path path) {
        try {
            if (!Files.exists(path)) return "";
            String text = Files.readString(path, StandardCharsets.UTF_8);
            if (text.length() > 160) return text.substring(0, 160);
            return text;
        } catch (Exception ignored) {
            return "";
        }
    }
}

final class IncompatibleReleaseException extends Exception {
    IncompatibleReleaseException(String message) {
        super(safe(message));
    }
}
