package com.devils.addon.modules.modupdater;

import com.devils.addon.shared.sync.SyncJsonUtils;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStreamReader;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static com.devils.addon.modules.modupdater.ModAutoUpdaterText.compileRegex;
import static com.devils.addon.modules.modupdater.ModAutoUpdaterText.normalizeKey;
import static com.devils.addon.modules.modupdater.ModAutoUpdaterText.parseGitHubRepo;
import static com.devils.addon.modules.modupdater.ModAutoUpdaterText.parseModrinthProject;
import static com.devils.addon.modules.modupdater.ModAutoUpdaterText.rootMessage;
import static com.devils.addon.modules.modupdater.ModAutoUpdaterText.safe;
import static com.devils.addon.modules.modupdater.ModAutoUpdaterText.stripJarSuffix;

final class ModAutoUpdaterEngine {
    static final String USER_AGENT = "Devils-ModAutoUpdater/1.0";
    private static final String SOURCES_SCHEMA = "devils-mod-auto-updater-v1";

    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Devils-ModAutoUpdater");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final ModUpdaterReleaseFetcher fetcher = new ModUpdaterReleaseFetcher(http);
    private final ModUpdaterInstaller installer = new ModUpdaterInstaller(http);
    private final ModUpdaterSourceResolver resolver = new ModUpdaterSourceResolver(fetcher);

    public boolean runAsync(UpdateRequest request, Consumer<UpdateProgress> progressCallback, Consumer<UpdateReport> callback) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(progressCallback, "progressCallback");
        Objects.requireNonNull(callback, "callback");
        if (!running.compareAndSet(false, true)) return false;

        CompletableFuture
            .supplyAsync(() -> runInternal(request, progressCallback), executor)
            .exceptionally(e -> UpdateReport.failed("Updater failed: " + rootMessage(e)))
            .thenAccept(report -> {
                running.set(false);
                callback.accept(report);
            });
        return true;
    }

    public void ensureSourcesFile(Path sourcesFile) throws IOException {
        Path parent = sourcesFile.getParent();
        if (parent != null) Files.createDirectories(parent);
        if (Files.exists(sourcesFile)) return;

        String template = """
            {
              "schema": "%s",
              "_comment": "Map unknown mods to provider ids. provider=modrinth uses project slug/id. provider=github uses owner/repo.",
              "mods": {
                "_example_modrinth_mod_id": {
                  "provider": "modrinth",
                  "project": "project-slug-or-id"
                },
                "_example_github_mod_id": {
                  "provider": "github",
                  "repo": "owner/repo",
                  "assetRegex": ".*fabric.*1\\\\.21\\\\.11.*\\\\.jar"
                }
              }
            }
            """.formatted(SOURCES_SCHEMA);
        Files.writeString(sourcesFile, template, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }

    private UpdateReport runInternal(UpdateRequest request, Consumer<UpdateProgress> progressCallback) {
        long started = System.currentTimeMillis();
        List<UpdateEntry> entries = new ArrayList<>();
        Path backupDir = null;
        boolean sourceEqualsTarget;

        try {
            ensureSourcesFile(request.sourcesFile);
            Files.createDirectories(request.sourceModsDir);
            Files.createDirectories(request.targetModsDir);
            Files.createDirectories(request.workspaceDir);
        } catch (Exception e) {
            return UpdateReport.failed("Setup error: " + rootMessage(e));
        }
        sourceEqualsTarget = request.sourceModsDir.toAbsolutePath().normalize().equals(request.targetModsDir.toAbsolutePath().normalize());
        // Copying an old jar over the target is a real write, so a dry run must never take these fallbacks:
        // the dedicated dryRun gate below only guards the download path, which several of them run before.
        boolean allowCopyFallback = request.copyFallbackMods && !sourceEqualsTarget && !request.dryRun;

        if (request.backupEnabled) {
            try {
                String stamp = createBackupStamp();
                backupDir = request.backupsRoot.resolve(stamp);
                Files.createDirectories(backupDir);
            } catch (Exception e) {
                backupDir = null;
                entries.add(UpdateEntry.system("Backup disabled for this run: " + rootMessage(e)));
            }
        }

        Map<String, SourceSpec> overrides = readSourceOverrides(request.sourcesFile, entries);
        List<Path> jars = listJarFiles(request.sourceModsDir, entries);
        jars.sort(Comparator.comparing(path -> path.getFileName().toString(), String.CASE_INSENSITIVE_ORDER));
        ProgressState progress = new ProgressState(jars.size(), progressCallback);
        progress.publishStart();
        HashMap<String, RemoteRelease> modrinthAutoCache = new HashMap<>();
        HashMap<String, List<String>> modrinthSearchCache = new HashMap<>();
        HashMap<String, String> githubSearchRepoCache = new HashMap<>();
        HashMap<String, RemoteRelease> githubReleaseCache = new HashMap<>();
        int[] githubSearchBudget = new int[] { 12 };

        if (jars.isEmpty()) {
            entries.add(UpdateEntry.system("No .jar files found in source folder: " + request.sourceModsDir));
            progress.publishSystem("empty-source-folder");
            return UpdateReport.from(entries, Math.max(0, System.currentTimeMillis() - started), backupDir, request.dryRun);
        }

        for (Path jar : jars) {
            InstalledMod mod = readInstalledMod(jar);

            if (request.useSelectionFilter && !request.selectedModIds.contains(normalizeKey(mod.modId))) {
                progress.record(entries, UpdateEntry.excluded(mod, "not-selected"));
                continue;
            }

            if (isExcluded(mod, request)) {
                progress.record(entries, UpdateEntry.excluded(mod, "excluded"));
                continue;
            }

            if (!mod.fabricMetadata) {
                if (allowCopyFallback) {
                    if (installer.copySourceJarToTarget(mod, request.targetModsDir, backupDir)) {
                        progress.record(entries, UpdateEntry.copied(mod, "copied-non-fabric"));
                    } else {
                        progress.record(entries, UpdateEntry.excluded(mod, "non-fabric"));
                    }
                } else {
                    progress.record(entries, UpdateEntry.excluded(mod, "non-fabric"));
                }
                continue;
            }

            SourceSpec source = resolver.resolveSource(mod, overrides);
            RemoteRelease latest = null;
            if (source == null) {
                AutoResolvedSource auto = resolver.tryAutoResolveSource(mod, request, modrinthAutoCache, modrinthSearchCache);
                if (auto != null) {
                    source = auto.source;
                    latest = auto.release;
                }
            }
            if (source == null) {
                AutoResolvedSource githubAuto = resolver.tryAutoResolveGithub(
                    mod,
                    request,
                    githubSearchRepoCache,
                    githubReleaseCache,
                    githubSearchBudget
                );
                if (githubAuto != null) {
                    source = githubAuto.source;
                    latest = githubAuto.release;
                }
            }

            if (source == null) {
                if (tryUseCompatibleSourceJar(mod, request, sourceEqualsTarget, entries, progress, "compatible-source-no-source")) {
                    continue;
                }
                if (allowCopyFallback) {
                    if (installer.copySourceJarToTarget(mod, request.targetModsDir, backupDir)) progress.record(entries, UpdateEntry.copied(mod, "copied-no-source"));
                    else progress.record(entries, UpdateEntry.noSource(mod, ModUpdaterSourceResolver.describeNoSourceReason(mod)));
                } else progress.record(entries, UpdateEntry.noSource(mod, ModUpdaterSourceResolver.describeNoSourceReason(mod)));
                continue;
            }

            if (latest == null && source.provider == SourceProvider.GITHUB) {
                AutoResolvedSource modrinthFirst = resolver.tryAutoResolveSource(mod, request, modrinthAutoCache, modrinthSearchCache);
                if (modrinthFirst != null) {
                    source = modrinthFirst.source;
                    latest = modrinthFirst.release;
                }
            }

            if (latest == null) {
                RemoteRelease special = fetcher.tryResolveSpecialRelease(mod, request);
                if (special != null) {
                    latest = special;
                    source = new SourceSpec(special.provider, special.sourceId, null);
                }
            }

            if (latest == null) {
                try {
                    latest = fetcher.fetchLatest(source, request);
                } catch (Exception e) {
                    if (source.provider == SourceProvider.GITHUB) {
                        AutoResolvedSource modrinthFallback = resolver.tryAutoResolveSource(mod, request, modrinthAutoCache, modrinthSearchCache);
                        if (modrinthFallback != null) {
                            source = modrinthFallback.source;
                            latest = modrinthFallback.release;
                        } else if (shouldDowngradeGithubFetchError(e)) {
                            progress.record(entries, UpdateEntry.noRelease(
                                mod,
                                "github",
                                "github-fetch-failed for " + source.id + " (" + request.loader + " " + request.targetGameVersion + ")"
                            ));
                            continue;
                        }
                    } else if (source.provider == SourceProvider.MODRINTH) {
                        AutoResolvedSource githubFallback = resolver.tryAutoResolveGithub(
                            mod,
                            request,
                            githubSearchRepoCache,
                            githubReleaseCache,
                            githubSearchBudget
                        );
                        if (githubFallback != null) {
                            source = githubFallback.source;
                            latest = githubFallback.release;
                        }
                    }
                    if (latest != null) {
                        // Resolved through fallback source after GitHub failure.
                        // Continue with fallback release flow.
                    } else
                    if (allowCopyFallback) {
                        if (installer.copySourceJarToTarget(mod, request.targetModsDir, backupDir)) progress.record(entries, UpdateEntry.copied(mod, "copied-after-lookup-error"));
                        else progress.record(entries, UpdateEntry.error(mod, source.provider.name().toLowerCase(Locale.ROOT), rootMessage(e)));
                    } else progress.record(entries, UpdateEntry.error(mod, source.provider.name().toLowerCase(Locale.ROOT), rootMessage(e)));
                    if (latest == null) continue;
                }
            }

            if (latest == null) {
                if (source.provider == SourceProvider.GITHUB) {
                    AutoResolvedSource modrinthFallback = resolver.tryAutoResolveSource(mod, request, modrinthAutoCache, modrinthSearchCache);
                    if (modrinthFallback != null) {
                        source = modrinthFallback.source;
                        latest = modrinthFallback.release;
                    }
                } else if (source.provider == SourceProvider.MODRINTH) {
                    AutoResolvedSource githubFallback = resolver.tryAutoResolveGithub(
                        mod,
                        request,
                        githubSearchRepoCache,
                        githubReleaseCache,
                        githubSearchBudget
                    );
                    if (githubFallback != null) {
                        source = githubFallback.source;
                        latest = githubFallback.release;
                    }
                }
            }

            if (latest != null && source.provider == SourceProvider.GITHUB) {
                AutoResolvedSource preferred = resolver.tryPreferModrinthOverGithub(mod, request, modrinthAutoCache, modrinthSearchCache, latest);
                if (preferred != null) {
                    source = preferred.source;
                    latest = preferred.release;
                }
            }

            if (latest == null) {
                if (tryUseCompatibleSourceJar(mod, request, sourceEqualsTarget, entries, progress, "compatible-source-no-release")) {
                    continue;
                }
                if (allowCopyFallback) {
                    if (installer.copySourceJarToTarget(mod, request.targetModsDir, backupDir)) progress.record(entries, UpdateEntry.copied(mod, "copied-no-release"));
                    else progress.record(entries, UpdateEntry.noRelease(
                        mod,
                        source.provider.name().toLowerCase(Locale.ROOT),
                        ModUpdaterSourceResolver.buildNoReleaseDetail(source, request)
                    ));
                } else progress.record(entries, UpdateEntry.noRelease(
                    mod,
                    source.provider.name().toLowerCase(Locale.ROOT),
                    ModUpdaterSourceResolver.buildNoReleaseDetail(source, request)
                ));
                continue;
            }

            if (ModUpdaterVersioning.isRemoteOlderThanInstalled(mod, latest)) {
                if (tryUseCompatibleSourceJar(mod, request, sourceEqualsTarget, entries, progress, "compatible-source-installed-newer")) {
                    continue;
                }
                progress.record(entries, UpdateEntry.noRelease(
                    mod,
                    latest.provider.name().toLowerCase(Locale.ROOT),
                    ModUpdaterVersioning.buildOlderReleaseDetail(mod, latest)
                ));
                continue;
            }

            String releaseMismatch = ModUpdaterVersioning.detectIncompatibleTargetHint(latest, request.targetGameVersion);
            if (!releaseMismatch.isBlank()) {
                if (tryUseCompatibleSourceJar(mod, request, sourceEqualsTarget, entries, progress, "compatible-source-remote-mismatch")) {
                    continue;
                }
                progress.record(entries, UpdateEntry.noRelease(
                    mod,
                    latest.provider.name().toLowerCase(Locale.ROOT),
                    "release-targets-minecraft-" + releaseMismatch + "-not-" + request.targetGameVersion
                ));
                continue;
            }

            if (!sourceEqualsTarget && ModUpdaterInstaller.targetAlreadyHasLatest(mod, latest, request.targetModsDir, request.targetGameVersion, request.loader)) {
                progress.record(entries, UpdateEntry.upToDate(mod, latest));
                continue;
            }

            if (!ModUpdaterVersioning.needsUpdate(mod, latest)) {
                if (allowCopyFallback) {
                    if (installer.copySourceJarToTarget(mod, request.targetModsDir, backupDir)) progress.record(entries, UpdateEntry.copied(mod, "copied-up-to-date"));
                    else progress.record(entries, UpdateEntry.upToDate(mod, latest));
                } else progress.record(entries, UpdateEntry.upToDate(mod, latest));
                continue;
            }

            if (request.dryRun) {
                progress.record(entries, UpdateEntry.available(mod, latest));
                continue;
            }

            try {
                installer.applyUpdate(mod, latest, request, backupDir);
                progress.record(entries, UpdateEntry.updated(mod, latest));
            } catch (IncompatibleReleaseException e) {
                if (source.provider == SourceProvider.GITHUB) {
                    AutoResolvedSource modrinthFallback = resolver.tryAutoResolveSource(mod, request, modrinthAutoCache, modrinthSearchCache);
                    if (modrinthFallback != null && modrinthFallback.release != null) {
                        RemoteRelease fallbackRelease = modrinthFallback.release;
                        if (!ModUpdaterVersioning.isRemoteOlderThanInstalled(mod, fallbackRelease)
                            && ModUpdaterVersioning.detectIncompatibleTargetHint(fallbackRelease, request.targetGameVersion).isBlank()) {
                            try {
                                installer.applyUpdate(mod, fallbackRelease, request, backupDir);
                                progress.record(entries, UpdateEntry.updated(mod, fallbackRelease));
                                continue;
                            } catch (Exception fallbackError) {
                                if (isFileInUseError(fallbackError)) {
                                    progress.record(entries, UpdateEntry.error(
                                        mod,
                                        fallbackRelease.provider.name().toLowerCase(Locale.ROOT),
                                        "target-file-locked-by-running-client; close target instance and rerun"
                                    ));
                                    continue;
                                }
                            }
                        }
                    }
                }
                progress.record(entries, UpdateEntry.noRelease(
                    mod,
                    latest.provider.name().toLowerCase(Locale.ROOT),
                    safe(e.getMessage())
                ));
                continue;
            } catch (Exception e) {
                if (isFileInUseError(e)) {
                    progress.record(entries, UpdateEntry.error(
                        mod,
                        latest.provider.name().toLowerCase(Locale.ROOT),
                        "target-file-locked-by-running-client; close target instance and rerun"
                    ));
                    continue;
                }
                if (allowCopyFallback) {
                    if (installer.copySourceJarToTarget(mod, request.targetModsDir, backupDir)) progress.record(entries, UpdateEntry.copied(mod, "copied-after-update-error"));
                    else progress.record(entries, UpdateEntry.error(mod, latest.provider.name().toLowerCase(Locale.ROOT), rootMessage(e)));
                } else progress.record(entries, UpdateEntry.error(mod, latest.provider.name().toLowerCase(Locale.ROOT), rootMessage(e)));
            }
        }

        return UpdateReport.from(entries, Math.max(0, System.currentTimeMillis() - started), backupDir, request.dryRun);
    }

    private static String createBackupStamp() {
        try {
            return DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT).format(LocalDateTime.now());
        } catch (Exception ignored) {
            return "backup-" + System.currentTimeMillis();
        }
    }

    private static List<Path> listJarFiles(Path modsDir, List<UpdateEntry> entries) {
        ArrayList<Path> jars = new ArrayList<>();
        try (var stream = Files.list(modsDir)) {
            stream
                .filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                .forEach(jars::add);
        } catch (Exception e) {
            entries.add(UpdateEntry.systemError("Cannot list mods dir: " + rootMessage(e)));
        }
        return jars;
    }

    private Map<String, SourceSpec> readSourceOverrides(Path sourcesFile, List<UpdateEntry> entries) {
        LinkedHashMap<String, SourceSpec> result = new LinkedHashMap<>();
        try {
            JsonObject root = SyncJsonUtils.parseJsonObject(Files.readString(sourcesFile, StandardCharsets.UTF_8));
            if (root == null) return result;

            if (root.has("mods") && root.get("mods").isJsonObject()) {
                JsonObject mods = root.getAsJsonObject("mods");

                for (Map.Entry<String, JsonElement> entry : mods.entrySet()) {
                    String modId = normalizeKey(entry.getKey());
                    if (modId.isBlank() || modId.startsWith("_")) continue;
                    if (!entry.getValue().isJsonObject()) continue;
                    SourceSpec spec = parseSourceSpec(entry.getValue().getAsJsonObject());
                    if (spec != null) result.put(modId, spec);
                }
            }

            if (root.has("sources") && root.get("sources").isJsonArray()) {
                JsonArray sources = root.getAsJsonArray("sources");
                for (JsonElement sourceElement : sources) {
                    if (!sourceElement.isJsonObject()) continue;
                    JsonObject item = sourceElement.getAsJsonObject();

                    String modId = normalizeKey(SyncJsonUtils.readString(item, "modId", SyncJsonUtils.readString(item, "id", "")));
                    if (modId.isBlank()) continue;

                    SourceSpec spec = parseSourceSpec(item);
                    if (spec != null) result.put(modId, spec);
                }
            }
        } catch (Exception e) {
            entries.add(UpdateEntry.systemError("Cannot parse sources.json: " + rootMessage(e)));
        }
        return result;
    }

    private SourceSpec parseSourceSpec(JsonObject json) {
        String providerRaw = SyncJsonUtils.readString(json, "provider", "").trim().toLowerCase(Locale.ROOT);
        SourceProvider provider = switch (providerRaw) {
            case "modrinth" -> SourceProvider.MODRINTH;
            case "github" -> SourceProvider.GITHUB;
            default -> null;
        };
        if (provider == null) return null;

        if (provider == SourceProvider.MODRINTH) {
            String project = SyncJsonUtils.readString(json, "project", SyncJsonUtils.readString(json, "projectId", "")).trim();
            if (project.isBlank()) return null;
            return new SourceSpec(provider, project, compileRegex(SyncJsonUtils.readString(json, "assetRegex", "")));
        }

        String repo = SyncJsonUtils.readString(json, "repo", "").trim();
        if (repo.isBlank()) return null;
        return new SourceSpec(provider, repo, compileRegex(SyncJsonUtils.readString(json, "assetRegex", "")));
    }

    private InstalledMod readInstalledMod(Path jar) {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry entry = zip.getEntry("fabric.mod.json");
            if (entry == null) {
                String fileName = jar.getFileName() == null ? "" : jar.getFileName().toString();
                String fallbackId = normalizeKey(stripJarSuffix(fileName));
                return new InstalledMod(jar, fileName, fallbackId, "", List.of(), false, null);
            }

            JsonObject root;
            try (InputStreamReader reader = new InputStreamReader(zip.getInputStream(entry), StandardCharsets.UTF_8)) {
                root = JsonParser.parseReader(reader).getAsJsonObject();
            }

            String modId = normalizeKey(SyncJsonUtils.readString(root, "id", ""));
            if (modId.isBlank()) modId = normalizeKey(stripJarSuffix(jar.getFileName().toString()));
            String version = SyncJsonUtils.readString(root, "version", "");
            List<String> urls = extractUrls(root);
            SourceSpec declaredSource = extractDeclaredSource(root);
            return new InstalledMod(jar, jar.getFileName().toString(), modId, version, urls, true, declaredSource);
        } catch (Exception e) {
            String fileName = jar.getFileName() == null ? "" : jar.getFileName().toString();
            String fallbackId = normalizeKey(stripJarSuffix(fileName));
            return new InstalledMod(jar, fileName, fallbackId, "", List.of(), false, null);
        }
    }

    private static List<String> extractUrls(JsonObject root) {
        ArrayList<String> urls = new ArrayList<>();
        if (root == null) return urls;

        if (root.has("contact") && root.get("contact").isJsonObject()) {
            JsonObject contact = root.getAsJsonObject("contact");
            for (Map.Entry<String, JsonElement> contactEntry : contact.entrySet()) {
                if (!contactEntry.getValue().isJsonPrimitive()) continue;
                String value = contactEntry.getValue().getAsString();
                if (!value.isBlank()) urls.add(value.trim());
            }
        }

        if (root.has("custom") && root.get("custom").isJsonObject()) {
            JsonObject custom = root.getAsJsonObject("custom");
            if (custom.has("modmenu") && custom.get("modmenu").isJsonObject()) {
                JsonObject modmenu = custom.getAsJsonObject("modmenu");
                if (modmenu.has("links") && modmenu.get("links").isJsonObject()) {
                    JsonObject links = modmenu.getAsJsonObject("links");
                    for (Map.Entry<String, JsonElement> linkEntry : links.entrySet()) {
                        if (!linkEntry.getValue().isJsonPrimitive()) continue;
                        String value = linkEntry.getValue().getAsString();
                        if (!value.isBlank()) urls.add(value.trim());
                    }
                }
            }
        }

        return urls;
    }

    private SourceSpec extractDeclaredSource(JsonObject root) {
        if (root == null || !root.has("contact") || !root.get("contact").isJsonObject()) return null;
        JsonObject contact = root.getAsJsonObject("contact");

        String homepage = SyncJsonUtils.readString(contact, "homepage", "").trim();
        String sources = SyncJsonUtils.readString(contact, "sources", SyncJsonUtils.readString(contact, "source", "")).trim();
        String issues = SyncJsonUtils.readString(contact, "issues", "").trim();

        String project = parseModrinthProject(homepage);
        if (project != null) return new SourceSpec(SourceProvider.MODRINTH, project, null);
        project = parseModrinthProject(sources);
        if (project != null) return new SourceSpec(SourceProvider.MODRINTH, project, null);
        project = parseModrinthProject(issues);
        if (project != null) return new SourceSpec(SourceProvider.MODRINTH, project, null);

        String repo = parseGitHubRepo(sources);
        if (repo != null) return new SourceSpec(SourceProvider.GITHUB, repo, null);
        repo = parseGitHubRepo(homepage);
        if (repo != null) return new SourceSpec(SourceProvider.GITHUB, repo, null);
        repo = parseGitHubRepo(issues);
        if (repo != null) return new SourceSpec(SourceProvider.GITHUB, repo, null);

        return null;
    }

    private static boolean isExcluded(InstalledMod mod, UpdateRequest request) {
        if (request.excludedModIds.contains(normalizeKey(mod.modId))) return true;
        String fileName = mod.fileName.toLowerCase(Locale.ROOT);
        for (String token : request.excludedFileTokens) {
            if (!token.isBlank() && fileName.contains(token)) return true;
        }
        return false;
    }

    private static boolean shouldDowngradeGithubFetchError(Throwable throwable) {
        String message = rootMessage(throwable).toLowerCase(Locale.ROOT);
        return message.contains("rate limit")
            || message.contains("api rate")
            || message.contains("403")
            || message.contains("429")
            || message.contains("timeout")
            || message.contains("timed out")
            || message.contains("connection reset")
            || message.contains("connect")
            || message.contains("tls");
    }

    private static boolean isFileInUseError(Throwable throwable) {
        String message = rootMessage(throwable).toLowerCase(Locale.ROOT);
        return message.contains("being used by another process")
            || message.contains("used by another process")
            || message.contains("занят другим процессом")
            || message.contains("cannot access the file")
            || message.contains("access is denied");
    }

    private boolean tryUseCompatibleSourceJar(
        InstalledMod mod,
        UpdateRequest request,
        boolean sourceEqualsTarget,
        List<UpdateEntry> entries,
        ProgressState progress,
        String detail
    ) {
        // This silent "already up to date" fallback stays rerun-only (source == target): during a migration the
        // source jar belongs to the old instance, so accepting it here would hide a genuinely missing update.
        // Copying such a jar across instances is a separate, explicitly opt-in path (copy-fallback-mods).
        if (mod == null || request == null || progress == null || entries == null) return false;
        if (!sourceEqualsTarget) return false;
        String sourceCompatibility = ModUpdaterInstaller.validateJarCompatibility(mod.jarPath, request.targetGameVersion, request.loader);
        if (!sourceCompatibility.isBlank()) return false;

        progress.record(entries, UpdateEntry.upToDateLocal(mod, safe(detail)));
        return true;
    }

    private static final class ProgressState {
        private final int total;
        private final Consumer<UpdateProgress> callback;

        private int done;
        private int updated;
        private int copied;
        private int updateAvailable;
        private int upToDate;
        private int excluded;
        private int unresolved;
        private int errors;

        ProgressState(int total, Consumer<UpdateProgress> callback) {
            this.total = Math.max(0, total);
            this.callback = callback;
        }

        void publishStart() {
            emit(EntryStatus.SYSTEM, "", "", "started");
        }

        void publishSystem(String detail) {
            emit(EntryStatus.SYSTEM, "", "", safe(detail));
        }

        void record(List<UpdateEntry> entries, UpdateEntry entry) {
            entries.add(entry);
            done++;
            switch (entry.status) {
                case UPDATED -> updated++;
                case COPIED -> copied++;
                case UPDATE_AVAILABLE -> updateAvailable++;
                case UP_TO_DATE -> upToDate++;
                case EXCLUDED, NON_FABRIC -> excluded++;
                case NO_SOURCE, NO_RELEASE -> unresolved++;
                case ERROR -> errors++;
                case SYSTEM -> { }
            }
            emit(entry.status, entry.modId, entry.fileName, entry.detail);
        }

        private void emit(EntryStatus status, String modId, String fileName, String detail) {
            callback.accept(new UpdateProgress(
                total,
                done,
                updated,
                copied,
                updateAvailable,
                upToDate,
                excluded,
                unresolved,
                errors,
                status,
                safe(modId),
                safe(fileName),
                safe(detail)
            ));
        }
    }

}
