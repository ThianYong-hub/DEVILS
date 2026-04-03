package com.example.addon.modules.modupdater;

import com.example.addon.AddonTemplate;
import com.example.addon.util.CrashGuard;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.game.OpenScreenEvent;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.widgets.WLabel;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.gui.widgets.containers.WHorizontalList;
import meteordevelopment.meteorclient.gui.widgets.containers.WVerticalList;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

public class ModAutoUpdater extends Module {
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final DateTimeFormatter LOG_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter LOG_FILE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final String RUNTIME_LOADER = "fabric";
    private static final String DEFAULT_EXCLUDED_IDS = "devils-addon,xaerominimap,xaeroworldmap,chesttracker,meteor-client,mioloader";
    private static final String DEFAULT_EXCLUDED_FILE_TOKENS = "devils-addon,xaerominimap,xaero-world-map,xaeroworldmap,chesttracker,meteor-client,mioloader";

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgFilters = settings.createGroup("Filters");

    private final Setting<Boolean> updateRerun = sgGeneral.add(new BoolSetting.Builder()
        .name("update-rerun")
        .description("If enabled, run automatic update scan once on every client launch.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> backupEnabled = sgGeneral.add(new BoolSetting.Builder()
        .name("backup-updated-mods")
        .description("Move old jars into devils-addon/mod-updater/backups before replacement.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> dryRun = sgGeneral.add(new BoolSetting.Builder()
        .name("dry-run")
        .description("Only report available updates without downloading/replacing files.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> includeGithubPreReleases = sgGeneral.add(new BoolSetting.Builder()
        .name("github-prereleases")
        .description("Allow GitHub prereleases while searching for update assets.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> copyFallbackMods = sgGeneral.add(new BoolSetting.Builder()
        .name("copy-fallback-mods")
        .description("If update lookup fails, copy source jar anyway (not recommended for cross-version migration).")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> useSelectionFilter = sgGeneral.add(new BoolSetting.Builder()
        .name("use-selection-filter")
        .description("Only process source mods selected in 'Select Mods'.")
        .defaultValue(false)
        .build()
    );

    private final Setting<String> sourceModsFolder = sgGeneral.add(new StringSetting.Builder()
        .name("source-mods-folder")
        .description("Folder with old mods to migrate. Empty = current mods folder.")
        .defaultValue("")
        .build()
    );

    private final Setting<String> selectedSourceModIds = sgGeneral.add(new StringSetting.Builder()
        .name("selected-source-mod-ids")
        .description("Internal list of selected source mod ids.")
        .defaultValue("")
        .visible(() -> false)
        .build()
    );

    private final Setting<String> excludedModIds = sgFilters.add(new StringSetting.Builder()
        .name("excluded-mod-ids")
        .description("Comma-separated mod ids to skip during update checks.")
        .defaultValue(DEFAULT_EXCLUDED_IDS)
        .build()
    );

    private final Setting<String> excludedFileTokens = sgFilters.add(new StringSetting.Builder()
        .name("excluded-file-tokens")
        .description("Comma-separated substrings in jar filenames to skip.")
        .defaultValue(DEFAULT_EXCLUDED_FILE_TOKENS)
        .build()
    );

    private final ModAutoUpdaterEngine engine = new ModAutoUpdaterEngine();
    private volatile String lastStatus = "idle";
    private volatile String lastFinishedAt = "never";
    private volatile String runProgressLine = "idle";
    private volatile int progressTotal;
    private volatile int progressDone;
    private volatile int progressUpdated;
    private volatile int progressCopied;
    private volatile int progressErrors;
    private volatile int progressUnresolved;
    private volatile int progressExcluded;
    private volatile int progressAvailable;
    private volatile int progressUpToDate;
    private volatile int lastAnnouncedDone = -1;
    private volatile WLabel sourceFolderLabel;
    private volatile WLabel selectionLabel;
    private volatile WLabel progressLabel;
    private volatile WLabel summaryLabel;
    private volatile WLabel finishedLabel;
    private boolean startupCheckDone;
    private RunMode lastRunMode = RunMode.MIGRATION;

    private enum RunMode {
        MIGRATION,
        RERUN
    }

    public ModAutoUpdater() {
        super(
            AddonTemplate.CATEGORY,
            "mod-auto-updater",
            "One-click migration helper for 1.21.11 updates. Supports Modrinth + GitHub release updates."
        );
        runInMainMenu = true;
        autoSubscribe = false;
        MeteorClient.EVENT_BUS.subscribe(this);
    }

    @Override
    public void onActivate() {
        toggleOnBindRelease = false;
        startupCheckDone = false;
    }

    @EventHandler
    private void onOpenScreen(OpenScreenEvent event) {
        CrashGuard.run(this, "onOpenScreen", () -> {
            if (startupCheckDone) return;
            if (!updateRerun.get()) return;
            if (event.screen == null) return;
            startupCheckDone = true;
            startUpdate(false, RunMode.RERUN);
        });
    }

    @Override
    public WWidget getWidget(GuiTheme theme) {
        WVerticalList list = theme.verticalList();
        WHorizontalList row = list.add(theme.horizontalList()).expandX().widget();

        WButton runNow = row.add(theme.button("Run Update Now")).expandX().widget();
        runNow.action = () -> startUpdate(true, RunMode.MIGRATION);

        WButton selectSource = row.add(theme.button("Select Source Folder")).expandX().widget();
        selectSource.action = () -> selectSourceModsFolder(theme);

        WButton selectMods = row.add(theme.button("Select Mods")).expandX().widget();
        selectMods.action = () -> {
            Set<String> currentSelected = splitToTokenSet(selectedSourceModIds.get());
            mc.setScreen(new SourceModsSelectScreen(theme, resolveSourceModsDir(), currentSelected, selected -> {
                selectedSourceModIds.set(joinTokens(selected));
                info("Selected source mods: %d", selected.size());
                refreshWidgetLabels();
            }));
        };

        list.add(theme.label("Detected game: " + resolveRuntimeGameVersion())).expandX();
        list.add(theme.label("Detected loader: " + RUNTIME_LOADER)).expandX();
        selectionLabel = list.add(theme.label("")).expandX().widget();
        sourceFolderLabel = list.add(theme.label("")).expandX().widget();
        list.add(theme.label("Target mods: " + resolveModsDir())).expandX();
        progressLabel = list.add(theme.label("")).expandX().widget();
        summaryLabel = list.add(theme.label("")).expandX().widget();
        finishedLabel = list.add(theme.label("")).expandX().widget();
        refreshWidgetLabels();
        return list;
    }

    private void startUpdate(boolean manual, RunMode runMode) {
        lastRunMode = runMode;
        Path runtimeModsDir = resolveModsDir();
        Path sourceDir = runMode == RunMode.RERUN ? runtimeModsDir : resolveSourceModsDir();
        Path targetDir = runtimeModsDir;

        Set<String> selectedIds = runMode == RunMode.RERUN ? Set.of() : splitToTokenSet(selectedSourceModIds.get());
        boolean selectionEnabled = runMode == RunMode.MIGRATION && useSelectionFilter.get() && !selectedIds.isEmpty();
        if (runMode == RunMode.MIGRATION && useSelectionFilter.get() && selectedIds.isEmpty()) {
            warning("Selection filter is ON but selection is empty. Fallback: processing all source mods.");
        }

        boolean sourceEqualsTarget = sourceDir.toAbsolutePath().normalize().equals(targetDir.toAbsolutePath().normalize());
        if (runMode == RunMode.MIGRATION && sourceEqualsTarget) {
            warning("Source and target folders are the same. Migration copy is skipped for same-path files.");
        }
        boolean allowCopyFallback = copyFallbackMods.get();
        if (runMode == RunMode.MIGRATION && !sourceEqualsTarget && allowCopyFallback) {
            warning("copy-fallback-mods is disabled for cross-version migration to avoid copying incompatible old jars.");
            allowCopyFallback = false;
        }

        Set<String> excludedIds = splitToTokenSet(excludedModIds.get());
        Set<String> excludedTokens = splitToTokenSet(excludedFileTokens.get());
        excludedIds.add("meteor-client");
        excludedTokens.add("meteor-client");
        excludedIds.add("mioloader");
        excludedTokens.add("mioloader");
        if (runMode == RunMode.MIGRATION) {
            excludedIds.add("fabric-api");
            excludedTokens.add("fabric-api");
        }

        int sourceJarCount = countJarFiles(sourceDir);
        if (runMode == RunMode.RERUN) {
            info("Update-Rerun mode: scanning runtime mods folder %s", sourceDir);
        } else {
            info("Migration mode: source=%s -> target=%s", sourceDir, targetDir);
        }
        info("Source scan: %d jar(s) in %s", sourceJarCount, sourceDir);
        if (sourceJarCount == 0) {
            warning("No .jar files found in source folder. Check selected source path.");
        }

        resetProgress();
        runProgressLine = "starting";
        lastStatus = "running...";

        UpdateRequest request = new UpdateRequest(
            sourceDir,
            targetDir,
            resolveSourcesFile(),
            resolveWorkspaceRoot(),
            resolveBackupsRoot(),
            RUNTIME_LOADER,
            resolveRuntimeGameVersion(),
            excludedIds,
            excludedTokens,
            includeGithubPreReleases.get(),
            backupEnabled.get(),
            allowCopyFallback,
            selectionEnabled,
            selectedIds,
            dryRun.get(),
            ""
        );

        boolean started = engine.runAsync(
            request,
            progress -> MinecraftClient.getInstance().execute(() -> onProgress(progress)),
            report -> MinecraftClient.getInstance().execute(() -> finishUpdate(report, manual))
        );
        if (!started && manual) {
            warning("Updater is already running.");
        } else if (!started) {
            warning("Update-Rerun skipped: updater is already running.");
        }
        refreshWidgetLabels();
    }

    private void onProgress(UpdateProgress progress) {
        progressTotal = progress.total;
        progressDone = progress.done;
        progressUpdated = progress.updated;
        progressCopied = progress.copied;
        progressAvailable = progress.updateAvailable;
        progressUpToDate = progress.upToDate;
        progressExcluded = progress.excluded;
        progressUnresolved = progress.unresolved;
        progressErrors = progress.errors;

        runProgressLine = "done=" + progress.done + "/" + progress.total + " left=" + progress.remaining();
        lastStatus = "running: " + runProgressLine;
        refreshWidgetLabels();

        if (progress.done <= 0 || progress.done == lastAnnouncedDone) return;
        boolean milestone = progress.done == progress.total || progress.done == 1 || progress.done % 10 == 0;
        if (!milestone) return;
        lastAnnouncedDone = progress.done;

        String modName = progress.lastFileName.isBlank() ? progress.lastModId : progress.lastFileName;
        if (modName.isBlank()) modName = "-";
        info(
            "[mod-updater] %d/%d left=%d updated=%d copied=%d unresolved=%d errors=%d | %s -> %s",
            progress.done,
            progress.total,
            progress.remaining(),
            progress.updated,
            progress.copied,
            progress.unresolved,
            progress.errors,
            modName,
            progress.lastDetail
        );
    }

    private void finishUpdate(UpdateReport report, boolean manual) {
        lastStatus = report.summary + " (" + report.durationMs + " ms)";
        lastFinishedAt = TIME_FORMAT.format(LocalTime.now());
        runProgressLine = "done=" + report.scanned + "/" + report.scanned + " left=0";

        if (report.ok) info(report.summary);
        else error(report.summary);

        info(
            "[mod-updater] Final: scanned=%d updated=%d copied=%d available=%d up-to-date=%d excluded=%d unresolved=%d errors=%d",
            report.scanned,
            report.updated,
            report.copied,
            report.updateAvailable,
            report.upToDate,
            report.excluded,
            report.unresolved,
            report.errors
        );
        refreshWidgetLabels();

        Path updateLogPath = writeUpdateLog(report);
        if (updateLogPath != null) {
            info("[mod-updater] Details log: %s", updateLogPath);
        }

        if (report.backupDir != null && (report.updated > 0 || report.copied > 0)) {
            info("Backups saved to %s", report.backupDir);
        }

        if (report.errors > 0 || report.unresolved > 0) {
            info("[mod-updater] Issues:");
            for (UpdateEntry entry : report.entries) {
                if (entry.status != EntryStatus.ERROR
                    && entry.status != EntryStatus.NO_SOURCE
                    && entry.status != EntryStatus.NO_RELEASE
                    && entry.status != EntryStatus.SYSTEM) {
                    continue;
                }

                String name = entry.fileName == null || entry.fileName.isBlank() ? entry.modId : entry.fileName;
                String reason = entry.detail == null || entry.detail.isBlank() ? entry.status.name().toLowerCase(Locale.ROOT) : entry.detail;
                warning("[mod-updater] %s: %s (%s)", name, reason, entry.status.name().toLowerCase(Locale.ROOT));
            }
        }
    }

    private void resetProgress() {
        progressTotal = 0;
        progressDone = 0;
        progressUpdated = 0;
        progressCopied = 0;
        progressErrors = 0;
        progressUnresolved = 0;
        progressExcluded = 0;
        progressAvailable = 0;
        progressUpToDate = 0;
        runProgressLine = "idle";
        lastAnnouncedDone = -1;
    }

    private Path resolveWorkspaceRoot() {
        return FabricLoader.getInstance().getGameDir().resolve("devils-addon").resolve("mod-updater");
    }

    private Path resolveBackupsRoot() {
        return resolveWorkspaceRoot().resolve("backups");
    }

    private Path resolveSourcesFile() {
        return resolveWorkspaceRoot().resolve("sources.json");
    }

    private Path resolveModsDir() {
        return FabricLoader.getInstance().getGameDir().resolve("mods");
    }

    private Path resolveSourceModsDir() {
        String raw = sourceModsFolder.get();
        if (raw == null || raw.trim().isBlank()) return resolveModsDir();
        try {
            return Path.of(raw.trim());
        } catch (Exception ignored) {
            return resolveModsDir();
        }
    }

    private Path resolveAppDataDir() {
        String appData = System.getenv("APPDATA");
        if (appData != null && !appData.isBlank()) return Path.of(appData);
        return FabricLoader.getInstance().getGameDir();
    }

    private String resolveRuntimeGameVersion() {
        try {
            return FabricLoader.getInstance()
                .getModContainer("minecraft")
                .map(container -> container.getMetadata().getVersion().getFriendlyString())
                .orElse("1.21.11");
        } catch (Exception ignored) {
            return "1.21.11";
        }
    }

    private static Set<String> splitToTokenSet(String raw) {
        LinkedHashSet<String> tokens = new LinkedHashSet<>();
        if (raw == null || raw.isBlank()) return tokens;

        String normalized = raw.replace(';', ',').replace('\n', ',').replace('\r', ',');
        for (String part : normalized.split(",")) {
            String token = part == null ? "" : part.trim().toLowerCase(Locale.ROOT);
            if (!token.isBlank()) tokens.add(token);
        }
        return tokens;
    }

    private static String joinTokens(Set<String> tokens) {
        if (tokens == null || tokens.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (String token : tokens) {
            String value = token == null ? "" : token.trim().toLowerCase(Locale.ROOT);
            if (value.isBlank()) continue;
            if (!sb.isEmpty()) sb.append(',');
            sb.append(value);
        }
        return sb.toString();
    }

    private void selectSourceModsFolder(GuiTheme theme) {
        Path selected = chooseNativeFolder();
        if (selected != null) {
            applySelectedSourceFolder(selected);
            return;
        }

        mc.setScreen(new SourceFolderSelectScreen(
            theme,
            resolveSourceModsDir(),
            resolveAppDataDir(),
            resolveModsDir(),
            this::applySelectedSourceFolder
        ));
    }

    private Path chooseNativeFolder() {
        try {
            Path start = firstExistingDirectory(
                resolveSourceModsDir(),
                resolveAppDataDir(),
                resolveModsDir(),
                Path.of(System.getProperty("user.home", "."))
            );
            String selected = TinyFileDialogs.tinyfd_selectFolderDialog(
                "Select old mods folder",
                start == null ? null : start.toString()
            );
            if (selected == null || selected.isBlank()) return null;
            return Path.of(selected).toAbsolutePath().normalize();
        } catch (Throwable t) {
            AddonTemplate.LOG.warn("[Devils/ModAutoUpdater] Native source folder chooser unavailable, fallback to in-game screen.", t);
            return null;
        }
    }

    private void applySelectedSourceFolder(Path selected) {
        if (selected == null) return;
        sourceModsFolder.set(selected.toAbsolutePath().normalize().toString());
        selectedSourceModIds.set("");
        info("Source mods folder: %s", selected);
        info("Source mod selection cleared, re-open 'Select Mods' to choose list.");
        refreshWidgetLabels();
    }

    private static Path firstExistingDirectory(Path... candidates) {
        if (candidates == null) return null;
        for (Path candidate : candidates) {
            if (candidate == null) continue;
            try {
                Path normalized = candidate.toAbsolutePath().normalize();
                if (java.nio.file.Files.isDirectory(normalized)) return normalized;
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private static int countJarFiles(Path directory) {
        if (directory == null || !java.nio.file.Files.isDirectory(directory)) return 0;
        try (Stream<Path> stream = java.nio.file.Files.list(directory)) {
            return (int) stream
                .filter(java.nio.file.Files::isRegularFile)
                .filter(path -> {
                    String name = path.getFileName() == null ? "" : path.getFileName().toString().toLowerCase(Locale.ROOT);
                    return name.endsWith(".jar");
                })
                .count();
        } catch (Exception ignored) {
            return 0;
        }
    }

    private void refreshWidgetLabels() {
        WLabel source = sourceFolderLabel;
        if (source != null) source.set("Source folder: " + resolveSourceModsDir());

        WLabel selection = selectionLabel;
        if (selection != null) {
            selection.set("Selection filter: " + (useSelectionFilter.get() ? "ON" : "OFF")
                + " (selected: " + splitToTokenSet(selectedSourceModIds.get()).size() + ")");
        }

        WLabel progress = progressLabel;
        if (progress != null) {
            progress.set("Processed: " + progressDone + "/" + progressTotal + " | " + runProgressLine);
        }

        WLabel summary = summaryLabel;
        if (summary != null) {
            summary.set("Updated/Copied/Available/Up-to-date/Excluded/Unresolved/Errors: "
                + progressUpdated
                + "/" + progressCopied
                + "/" + progressAvailable
                + "/" + progressUpToDate
                + "/" + progressExcluded
                + "/" + progressUnresolved
                + "/" + progressErrors);
        }

        WLabel finished = finishedLabel;
        if (finished != null) {
            finished.set("Last finished: " + lastFinishedAt
                + " | mode=" + lastRunMode.name().toLowerCase(Locale.ROOT)
                + " | status=" + lastStatus);
        }
    }

    private Path writeUpdateLog(UpdateReport report) {
        try {
            Path workspace = resolveWorkspaceRoot();
            Files.createDirectories(workspace);

            Path sourceForRun = lastRunMode == RunMode.RERUN ? resolveModsDir() : resolveSourceModsDir();
            Path targetForRun = resolveModsDir();

            StringBuilder sb = new StringBuilder(8192);
            sb.append("Devils Mod Auto Updater Log\n");
            sb.append("time=").append(LOG_TIME_FORMAT.format(java.time.LocalDateTime.now())).append('\n');
            sb.append("mode=").append(lastRunMode.name().toLowerCase(Locale.ROOT)).append('\n');
            sb.append("game=").append(resolveRuntimeGameVersion()).append('\n');
            sb.append("loader=").append(RUNTIME_LOADER).append('\n');
            sb.append("source=").append(sourceForRun).append('\n');
            sb.append("target=").append(targetForRun).append('\n');
            sb.append("summary=").append(report.summary).append('\n');
            sb.append("durationMs=").append(report.durationMs).append('\n');
            sb.append("scanned=").append(report.scanned).append('\n');
            sb.append("updated=").append(report.updated).append('\n');
            sb.append("copied=").append(report.copied).append('\n');
            sb.append("upToDate=").append(report.upToDate).append('\n');
            sb.append("unresolved=").append(report.unresolved).append('\n');
            sb.append("excluded=").append(report.excluded).append('\n');
            sb.append("errors=").append(report.errors).append('\n');
            sb.append("backupDir=").append(report.backupDir == null ? "" : report.backupDir).append('\n');
            sb.append('\n');
            sb.append("entries:\n");

            int index = 1;
            for (UpdateEntry entry : report.entries) {
                String name = entry.fileName == null || entry.fileName.isBlank() ? entry.modId : entry.fileName;
                String provider = entry.provider == null ? "" : entry.provider;
                String currentVersion = entry.currentVersion == null ? "" : entry.currentVersion;
                String targetVersion = entry.targetVersion == null ? "" : entry.targetVersion;
                String detail = entry.detail == null ? "" : entry.detail;

                sb.append(index++)
                    .append(". status=").append(entry.status.name().toLowerCase(Locale.ROOT))
                    .append(" modId=").append(entry.modId == null ? "" : entry.modId)
                    .append(" file=").append(name)
                    .append(" provider=").append(provider)
                    .append(" current=").append(currentVersion)
                    .append(" target=").append(targetVersion)
                    .append(" detail=").append(detail)
                    .append('\n');
            }

            Path mainLog = workspace.resolve("loge-update.txt");
            Files.writeString(
                mainLog,
                sb.toString(),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
            );

            Path historyDir = workspace.resolve("logs");
            Files.createDirectories(historyDir);
            Path historyLog = historyDir.resolve("update-" + LOG_FILE_FORMAT.format(java.time.LocalDateTime.now()) + ".txt");
            Files.writeString(
                historyLog,
                sb.toString(),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
            );
            return mainLog;
        } catch (Exception e) {
            warning("[mod-updater] Cannot write loge-update.txt: %s", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            return null;
        }
    }

}
