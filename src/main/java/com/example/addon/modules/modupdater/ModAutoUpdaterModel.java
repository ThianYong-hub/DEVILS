package com.example.addon.modules.modupdater;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

enum SourceProvider {
    MODRINTH,
    GITHUB
}

final class SourceSpec {
    final SourceProvider provider;
    final String id;
    final Pattern assetRegex;

    SourceSpec(SourceProvider provider, String id, Pattern assetRegex) {
        this.provider = provider;
        this.id = id;
        this.assetRegex = assetRegex;
    }
}

final class InstalledMod {
    final Path jarPath;
    final String fileName;
    final String modId;
    final String version;
    final List<String> urls;
    final boolean fabricMetadata;
    final SourceSpec declaredSource;

    InstalledMod(Path jarPath, String fileName, String modId, String version, List<String> urls, boolean fabricMetadata, SourceSpec declaredSource) {
        this.jarPath = jarPath;
        this.fileName = fileName;
        this.modId = modId;
        this.version = version;
        this.urls = urls;
        this.fabricMetadata = fabricMetadata;
        this.declaredSource = declaredSource;
    }
}

final class RemoteRelease {
    final SourceProvider provider;
    final String sourceId;
    final String version;
    final String fileName;
    final String downloadUrl;

    RemoteRelease(SourceProvider provider, String sourceId, String version, String fileName, String downloadUrl) {
        this.provider = provider;
        this.sourceId = sourceId;
        this.version = version;
        this.fileName = fileName;
        this.downloadUrl = downloadUrl;
    }
}

enum EntryStatus {
    UPDATED,
    COPIED,
    UPDATE_AVAILABLE,
    UP_TO_DATE,
    EXCLUDED,
    NO_SOURCE,
    NO_RELEASE,
    NON_FABRIC,
    ERROR,
    SYSTEM
}

final class UpdateEntry {
    final String modId;
    final String fileName;
    final String provider;
    final EntryStatus status;
    final String detail;
    final String currentVersion;
    final String targetVersion;

    UpdateEntry(String modId, String fileName, String provider, EntryStatus status, String detail, String currentVersion, String targetVersion) {
        this.modId = modId;
        this.fileName = fileName;
        this.provider = provider;
        this.status = status;
        this.detail = detail;
        this.currentVersion = currentVersion;
        this.targetVersion = targetVersion;
    }

    static UpdateEntry updated(InstalledMod mod, RemoteRelease release) {
        return new UpdateEntry(mod.modId, mod.fileName, release.provider.name().toLowerCase(Locale.ROOT), EntryStatus.UPDATED, "updated", mod.version, release.version);
    }

    static UpdateEntry copied(InstalledMod mod, String detail) {
        return new UpdateEntry(mod.modId, mod.fileName, "local", EntryStatus.COPIED, safe(detail), mod.version, mod.version);
    }

    static UpdateEntry available(InstalledMod mod, RemoteRelease release) {
        return new UpdateEntry(mod.modId, mod.fileName, release.provider.name().toLowerCase(Locale.ROOT), EntryStatus.UPDATE_AVAILABLE, "update-available", mod.version, release.version);
    }

    static UpdateEntry upToDate(InstalledMod mod, RemoteRelease release) {
        return new UpdateEntry(mod.modId, mod.fileName, release.provider.name().toLowerCase(Locale.ROOT), EntryStatus.UP_TO_DATE, "up-to-date", mod.version, release.version);
    }

    static UpdateEntry upToDateLocal(InstalledMod mod, String detail) {
        return new UpdateEntry(mod.modId, mod.fileName, "local", EntryStatus.UP_TO_DATE, safe(detail), mod.version, mod.version);
    }

    static UpdateEntry excluded(InstalledMod mod, String reason) {
        return new UpdateEntry(mod.modId, mod.fileName, "", EntryStatus.EXCLUDED, reason, mod.version, "");
    }

    static UpdateEntry noSource(InstalledMod mod) {
        return noSource(mod, "no-provider-source");
    }

    static UpdateEntry noSource(InstalledMod mod, String detail) {
        return new UpdateEntry(mod.modId, mod.fileName, "", EntryStatus.NO_SOURCE, safe(detail), mod.version, "");
    }

    static UpdateEntry noRelease(InstalledMod mod, String provider) {
        return noRelease(mod, provider, "no-compatible-release");
    }

    static UpdateEntry noRelease(InstalledMod mod, String provider, String detail) {
        return new UpdateEntry(mod.modId, mod.fileName, provider, EntryStatus.NO_RELEASE, safe(detail), mod.version, "");
    }

    static UpdateEntry nonFabric(String fileName) {
        return new UpdateEntry("", fileName, "", EntryStatus.NON_FABRIC, "no-fabric.mod.json", "", "");
    }

    static UpdateEntry error(InstalledMod mod, String provider, String detail) {
        return new UpdateEntry(mod.modId, mod.fileName, provider, EntryStatus.ERROR, safe(detail), mod.version, "");
    }

    static UpdateEntry system(String detail) {
        return new UpdateEntry("", "", "", EntryStatus.SYSTEM, safe(detail), "", "");
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}

final class UpdateReport {
    final boolean ok;
    final String summary;
    final long durationMs;
    final Path backupDir;
    final int scanned;
    final int updated;
    final int copied;
    final int updateAvailable;
    final int upToDate;
    final int excluded;
    final int unresolved;
    final int errors;
    final List<UpdateEntry> entries;

    UpdateReport(
        boolean ok,
        String summary,
        long durationMs,
        Path backupDir,
        int scanned,
        int updated,
        int copied,
        int updateAvailable,
        int upToDate,
        int excluded,
        int unresolved,
        int errors,
        List<UpdateEntry> entries
    ) {
        this.ok = ok;
        this.summary = summary;
        this.durationMs = durationMs;
        this.backupDir = backupDir;
        this.scanned = scanned;
        this.updated = updated;
        this.copied = copied;
        this.updateAvailable = updateAvailable;
        this.upToDate = upToDate;
        this.excluded = excluded;
        this.unresolved = unresolved;
        this.errors = errors;
        this.entries = entries;
    }

    static UpdateReport failed(String message) {
        return new UpdateReport(false, safe(message), 0, null, 0, 0, 0, 0, 0, 0, 0, 1, List.of(UpdateEntry.system(message)));
    }

    static UpdateReport from(List<UpdateEntry> entries, long durationMs, Path backupDir, boolean dryRun) {
        int scanned = 0;
        int updated = 0;
        int copied = 0;
        int available = 0;
        int upToDate = 0;
        int excluded = 0;
        int unresolved = 0;
        int errors = 0;

        for (UpdateEntry entry : entries) {
            switch (entry.status) {
                case UPDATED -> {
                    scanned++;
                    updated++;
                }
                case COPIED -> {
                    scanned++;
                    copied++;
                }
                case UPDATE_AVAILABLE -> {
                    scanned++;
                    available++;
                }
                case UP_TO_DATE -> {
                    scanned++;
                    upToDate++;
                }
                case EXCLUDED -> {
                    scanned++;
                    excluded++;
                }
                case NO_SOURCE, NO_RELEASE -> {
                    scanned++;
                    unresolved++;
                }
                case NON_FABRIC -> excluded++;
                case ERROR, SYSTEM -> {
                    scanned++;
                    errors++;
                }
            }
        }

        String summary = dryRun
            ? String.format(Locale.ROOT, "Dry-run: scanned=%d available=%d up-to-date=%d unresolved=%d excluded=%d errors=%d.", scanned, available, upToDate, unresolved, excluded, errors)
            : String.format(Locale.ROOT, "Updated: scanned=%d updated=%d copied=%d up-to-date=%d unresolved=%d excluded=%d errors=%d.", scanned, updated, copied, upToDate, unresolved, excluded, errors);

        return new UpdateReport(errors == 0, summary, durationMs, backupDir, scanned, updated, copied, available, upToDate, excluded, unresolved, errors, List.copyOf(entries));
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}

final class UpdateProgress {
    final int total;
    final int done;
    final int updated;
    final int copied;
    final int updateAvailable;
    final int upToDate;
    final int excluded;
    final int unresolved;
    final int errors;
    final EntryStatus lastStatus;
    final String lastModId;
    final String lastFileName;
    final String lastDetail;

    UpdateProgress(
        int total,
        int done,
        int updated,
        int copied,
        int updateAvailable,
        int upToDate,
        int excluded,
        int unresolved,
        int errors,
        EntryStatus lastStatus,
        String lastModId,
        String lastFileName,
        String lastDetail
    ) {
        this.total = total;
        this.done = done;
        this.updated = updated;
        this.copied = copied;
        this.updateAvailable = updateAvailable;
        this.upToDate = upToDate;
        this.excluded = excluded;
        this.unresolved = unresolved;
        this.errors = errors;
        this.lastStatus = lastStatus;
        this.lastModId = safe(lastModId);
        this.lastFileName = safe(lastFileName);
        this.lastDetail = safe(lastDetail);
    }

    int remaining() {
        return Math.max(0, total - done);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}

final class UpdateRequest {
    final Path sourceModsDir;
    final Path targetModsDir;
    final Path sourcesFile;
    final Path workspaceDir;
    final Path backupsRoot;
    final String loader;
    final String targetGameVersion;
    final Set<String> excludedModIds;
    final Set<String> excludedFileTokens;
    final boolean includeGithubPreReleases;
    final boolean backupEnabled;
    final boolean copyFallbackMods;
    final boolean useSelectionFilter;
    final Set<String> selectedModIds;
    final boolean dryRun;
    final String githubToken;

    UpdateRequest(
        Path sourceModsDir,
        Path targetModsDir,
        Path sourcesFile,
        Path workspaceDir,
        Path backupsRoot,
        String loader,
        String targetGameVersion,
        Set<String> excludedModIds,
        Set<String> excludedFileTokens,
        boolean includeGithubPreReleases,
        boolean backupEnabled,
        boolean copyFallbackMods,
        boolean useSelectionFilter,
        Set<String> selectedModIds,
        boolean dryRun,
        String githubToken
    ) {
        this.sourceModsDir = sourceModsDir;
        this.targetModsDir = targetModsDir;
        this.sourcesFile = sourcesFile;
        this.workspaceDir = workspaceDir;
        this.backupsRoot = backupsRoot;
        this.loader = loader;
        this.targetGameVersion = targetGameVersion;
        this.excludedModIds = excludedModIds;
        this.excludedFileTokens = excludedFileTokens;
        this.includeGithubPreReleases = includeGithubPreReleases;
        this.backupEnabled = backupEnabled;
        this.copyFallbackMods = copyFallbackMods;
        this.useSelectionFilter = useSelectionFilter;
        this.selectedModIds = selectedModIds;
        this.dryRun = dryRun;
        this.githubToken = githubToken;
    }
}
