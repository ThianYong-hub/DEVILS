package com.example.addon.modules.modupdater;

import com.example.addon.shared.sync.SyncJsonUtils;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.metadata.version.VersionPredicate;

import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static com.example.addon.modules.modupdater.ModAutoUpdaterText.compileRegex;
import static com.example.addon.modules.modupdater.ModAutoUpdaterText.normalizeKey;
import static com.example.addon.modules.modupdater.ModAutoUpdaterText.normalizeVersion;
import static com.example.addon.modules.modupdater.ModAutoUpdaterText.parseCurseForgeProject;
import static com.example.addon.modules.modupdater.ModAutoUpdaterText.parseGitHubRepo;
import static com.example.addon.modules.modupdater.ModAutoUpdaterText.parseModrinthProject;
import static com.example.addon.modules.modupdater.ModAutoUpdaterText.rootMessage;
import static com.example.addon.modules.modupdater.ModAutoUpdaterText.safe;
import static com.example.addon.modules.modupdater.ModAutoUpdaterText.stripJarSuffix;

final class ModAutoUpdaterEngine {
    private static final String USER_AGENT = "Devils-ModAutoUpdater/1.0";
    private static final String SOURCES_SCHEMA = "devils-mod-auto-updater-v1";
    private static final Pattern MC_MARKED_VERSION = Pattern.compile("(?i)(?:mc|minecraft)[-_ ]?(\\d+\\.\\d+(?:\\.\\d+)?)");
    private static final Pattern SIMPLE_MC_VERSION = Pattern.compile("\\d+\\.\\d+(?:\\.\\d+)?");

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
                if (!sourceEqualsTarget && request.copyFallbackMods) {
                    if (copySourceJarToTarget(mod, request.targetModsDir, backupDir)) {
                        progress.record(entries, UpdateEntry.copied(mod, "copied-non-fabric"));
                    } else {
                        progress.record(entries, UpdateEntry.excluded(mod, "non-fabric"));
                    }
                } else {
                    progress.record(entries, UpdateEntry.excluded(mod, "non-fabric"));
                }
                continue;
            }

            SourceSpec source = resolveSource(mod, overrides);
            RemoteRelease latest = null;
            if (source == null) {
                AutoResolvedSource auto = tryAutoResolveSource(mod, request, modrinthAutoCache, modrinthSearchCache);
                if (auto != null) {
                    source = auto.source;
                    latest = auto.release;
                }
            }
            if (source == null) {
                AutoResolvedSource githubAuto = tryAutoResolveGithub(
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
                if (request.copyFallbackMods && !sourceEqualsTarget) {
                    if (copySourceJarToTarget(mod, request.targetModsDir, backupDir)) progress.record(entries, UpdateEntry.copied(mod, "copied-no-source"));
                    else progress.record(entries, UpdateEntry.noSource(mod, describeNoSourceReason(mod)));
                } else progress.record(entries, UpdateEntry.noSource(mod, describeNoSourceReason(mod)));
                continue;
            }

            if (latest == null && source.provider == SourceProvider.GITHUB) {
                AutoResolvedSource modrinthFirst = tryAutoResolveSource(mod, request, modrinthAutoCache, modrinthSearchCache);
                if (modrinthFirst != null) {
                    source = modrinthFirst.source;
                    latest = modrinthFirst.release;
                }
            }

            if (latest == null) {
                RemoteRelease special = tryResolveSpecialRelease(mod, request);
                if (special != null) {
                    latest = special;
                    source = new SourceSpec(special.provider, special.sourceId, null);
                }
            }

            if (latest == null) {
                try {
                    latest = fetchLatest(source, request);
                } catch (Exception e) {
                    if (source.provider == SourceProvider.GITHUB) {
                        AutoResolvedSource modrinthFallback = tryAutoResolveSource(mod, request, modrinthAutoCache, modrinthSearchCache);
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
                        AutoResolvedSource githubFallback = tryAutoResolveGithub(
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
                    if (request.copyFallbackMods && !sourceEqualsTarget) {
                        if (copySourceJarToTarget(mod, request.targetModsDir, backupDir)) progress.record(entries, UpdateEntry.copied(mod, "copied-after-lookup-error"));
                        else progress.record(entries, UpdateEntry.error(mod, source.provider.name().toLowerCase(Locale.ROOT), rootMessage(e)));
                    } else progress.record(entries, UpdateEntry.error(mod, source.provider.name().toLowerCase(Locale.ROOT), rootMessage(e)));
                    if (latest == null) continue;
                }
            }

            if (latest == null) {
                if (source.provider == SourceProvider.GITHUB) {
                    AutoResolvedSource modrinthFallback = tryAutoResolveSource(mod, request, modrinthAutoCache, modrinthSearchCache);
                    if (modrinthFallback != null) {
                        source = modrinthFallback.source;
                        latest = modrinthFallback.release;
                    }
                } else if (source.provider == SourceProvider.MODRINTH) {
                    AutoResolvedSource githubFallback = tryAutoResolveGithub(
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
                AutoResolvedSource preferred = tryPreferModrinthOverGithub(mod, request, modrinthAutoCache, modrinthSearchCache, latest);
                if (preferred != null) {
                    source = preferred.source;
                    latest = preferred.release;
                }
            }

            if (latest == null) {
                if (tryUseCompatibleSourceJar(mod, request, sourceEqualsTarget, entries, progress, "compatible-source-no-release")) {
                    continue;
                }
                if (request.copyFallbackMods && !sourceEqualsTarget) {
                    if (copySourceJarToTarget(mod, request.targetModsDir, backupDir)) progress.record(entries, UpdateEntry.copied(mod, "copied-no-release"));
                    else progress.record(entries, UpdateEntry.noRelease(
                        mod,
                        source.provider.name().toLowerCase(Locale.ROOT),
                        buildNoReleaseDetail(source, request)
                    ));
                } else progress.record(entries, UpdateEntry.noRelease(
                    mod,
                    source.provider.name().toLowerCase(Locale.ROOT),
                    buildNoReleaseDetail(source, request)
                ));
                continue;
            }

            if (isRemoteOlderThanInstalled(mod, latest)) {
                if (tryUseCompatibleSourceJar(mod, request, sourceEqualsTarget, entries, progress, "compatible-source-installed-newer")) {
                    continue;
                }
                progress.record(entries, UpdateEntry.noRelease(
                    mod,
                    latest.provider.name().toLowerCase(Locale.ROOT),
                    buildOlderReleaseDetail(mod, latest)
                ));
                continue;
            }

            String releaseMismatch = detectIncompatibleTargetHint(latest, request.targetGameVersion);
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

            if (!sourceEqualsTarget && targetAlreadyHasLatest(mod, latest, request.targetModsDir, request.targetGameVersion, request.loader)) {
                progress.record(entries, UpdateEntry.upToDate(mod, latest));
                continue;
            }

            if (!needsUpdate(mod, latest)) {
                if (!sourceEqualsTarget && request.copyFallbackMods) {
                    if (copySourceJarToTarget(mod, request.targetModsDir, backupDir)) progress.record(entries, UpdateEntry.copied(mod, "copied-up-to-date"));
                    else progress.record(entries, UpdateEntry.upToDate(mod, latest));
                } else progress.record(entries, UpdateEntry.upToDate(mod, latest));
                continue;
            }

            if (request.dryRun) {
                progress.record(entries, UpdateEntry.available(mod, latest));
                continue;
            }

            try {
                applyUpdate(mod, latest, request, backupDir);
                progress.record(entries, UpdateEntry.updated(mod, latest));
            } catch (IncompatibleReleaseException e) {
                if (source.provider == SourceProvider.GITHUB) {
                    AutoResolvedSource modrinthFallback = tryAutoResolveSource(mod, request, modrinthAutoCache, modrinthSearchCache);
                    if (modrinthFallback != null && modrinthFallback.release != null) {
                        RemoteRelease fallbackRelease = modrinthFallback.release;
                        if (!isRemoteOlderThanInstalled(mod, fallbackRelease)
                            && detectIncompatibleTargetHint(fallbackRelease, request.targetGameVersion).isBlank()) {
                            try {
                                applyUpdate(mod, fallbackRelease, request, backupDir);
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
                if (request.copyFallbackMods && !sourceEqualsTarget) {
                    if (copySourceJarToTarget(mod, request.targetModsDir, backupDir)) progress.record(entries, UpdateEntry.copied(mod, "copied-after-update-error"));
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
            entries.add(UpdateEntry.system("Cannot list mods dir: " + rootMessage(e)));
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
            entries.add(UpdateEntry.system("Cannot parse sources.json: " + rootMessage(e)));
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

    private SourceSpec resolveSource(InstalledMod mod, Map<String, SourceSpec> overrides) {
        SourceSpec fromOverrides = overrides.get(normalizeKey(mod.modId));
        if (fromOverrides != null) return fromOverrides;
        SourceSpec builtinOverride = resolveBuiltInSourceOverride(mod);
        if (builtinOverride != null) return builtinOverride;
        if (mod.declaredSource != null) return mod.declaredSource;

        for (String url : mod.urls) {
            String project = parseModrinthProject(url);
            if (project != null) return new SourceSpec(SourceProvider.MODRINTH, project, null);
        }
        for (String url : mod.urls) {
            String repo = parseGitHubRepo(url);
            if (repo != null) return new SourceSpec(SourceProvider.GITHUB, repo, null);
        }
        return null;
    }

    private static SourceSpec resolveBuiltInSourceOverride(InstalledMod mod) {
        if (mod == null) return null;
        String modId = normalizeKey(mod.modId);
        if (modId.isBlank()) return null;

        if ("modernfix".equals(modId)) {
            return new SourceSpec(SourceProvider.MODRINTH, "modernfix-mvus", null);
        }
        if ("forgeconfigapiport".equals(modId)) {
            return new SourceSpec(SourceProvider.MODRINTH, "forge-config-api-port", null);
        }
        if ("sspb".equals(modId)) {
            return new SourceSpec(SourceProvider.MODRINTH, "sodium-shadowy-path-blocks", null);
        }
        if ("yet_another_config_lib_v3".equals(modId)) {
            return new SourceSpec(SourceProvider.MODRINTH, "yacl", null);
        }
        if ("placeholder-api".equals(modId)) {
            return new SourceSpec(SourceProvider.MODRINTH, "placeholder-api", null);
        }
        if ("worldtools".equals(modId)) {
            return new SourceSpec(SourceProvider.GITHUB, "SKevo18/VibedWorldTools", null);
        }
        if ("jefffmod".equals(modId)) {
            return new SourceSpec(SourceProvider.GITHUB, "miles352/meteor-stashhunting-addon", null);
        }
        return null;
    }

    private static String describeNoSourceReason(InstalledMod mod) {
        if (mod == null) return "no-provider-source";
        if (!mod.fabricMetadata) return "no-fabric.mod.json";
        if (mod.urls == null || mod.urls.isEmpty()) return "no-contact-links-in-fabric.mod.json";
        return "contact-links-present-but-not-modrinth-or-github";
    }

    private static String buildNoReleaseDetail(SourceSpec source, UpdateRequest request) {
        if (source == null || request == null) return "no-compatible-release";
        String provider = source.provider == null ? "unknown" : source.provider.name().toLowerCase(Locale.ROOT);
        String sourceId = safe(source.id);
        return "no-compatible-release for " + request.loader + " " + request.targetGameVersion
            + " via " + provider + ":" + sourceId;
    }

    private AutoResolvedSource tryAutoResolveSource(
        InstalledMod mod,
        UpdateRequest request,
        Map<String, RemoteRelease> modrinthAutoCache,
        Map<String, List<String>> modrinthSearchCache
    ) {
        for (String candidate : buildModrinthSlugCandidates(mod)) {
            String key = normalizeKey(candidate);
            if (key.isBlank()) continue;

            RemoteRelease cached = modrinthAutoCache.get(key);
            if (cached != null || modrinthAutoCache.containsKey(key)) {
                if (cached != null) return new AutoResolvedSource(new SourceSpec(SourceProvider.MODRINTH, key, null), cached);
                continue;
            }

            RemoteRelease resolved = null;
            try {
                resolved = fetchModrinth(key, request.loader, request.targetGameVersion);
            } catch (Exception ignored) {
            }
            modrinthAutoCache.put(key, resolved);
            if (resolved != null
                && !isRemoteOlderThanInstalled(mod, resolved)
                && detectIncompatibleTargetHint(resolved, request.targetGameVersion).isBlank()) {
                return new AutoResolvedSource(new SourceSpec(SourceProvider.MODRINTH, key, null), resolved);
            }
        }

        if (!hasTrustedSourceHints(mod)) return null;

        for (String query : buildModrinthSearchCandidates(mod)) {
            String normalizedQuery = normalizeKey(query);
            if (normalizedQuery.isBlank()) continue;

            List<String> slugs = modrinthSearchCache.get(normalizedQuery);
            if (slugs == null) {
                try {
                    slugs = searchModrinthProjectSlugs(normalizedQuery);
                } catch (Exception ignored) {
                    slugs = List.of();
                }
                modrinthSearchCache.put(normalizedQuery, slugs);
            }

            for (String slug : slugs) {
                String key = normalizeKey(slug);
                if (key.isBlank()) continue;
                if (!looksLikeModrinthProjectMatch(mod, key)) continue;

                RemoteRelease cached = modrinthAutoCache.get(key);
                if (cached != null || modrinthAutoCache.containsKey(key)) {
                    if (cached != null
                        && !isRemoteOlderThanInstalled(mod, cached)
                        && detectIncompatibleTargetHint(cached, request.targetGameVersion).isBlank()) {
                        return new AutoResolvedSource(new SourceSpec(SourceProvider.MODRINTH, key, null), cached);
                    }
                    continue;
                }

                RemoteRelease resolved = null;
                try {
                    resolved = fetchModrinth(key, request.loader, request.targetGameVersion);
                } catch (Exception ignored) {
                }
                modrinthAutoCache.put(key, resolved);
                if (resolved != null
                    && !isRemoteOlderThanInstalled(mod, resolved)
                    && detectIncompatibleTargetHint(resolved, request.targetGameVersion).isBlank()) {
                    return new AutoResolvedSource(new SourceSpec(SourceProvider.MODRINTH, key, null), resolved);
                }
            }
        }
        return null;
    }

    private static boolean hasTrustedSourceHints(InstalledMod mod) {
        if (mod == null) return false;
        if (mod.declaredSource != null) return true;
        for (String url : mod.urls) {
            if (parseModrinthProject(url) != null) return true;
            if (parseGitHubRepo(url) != null) return true;
            if (parseCurseForgeProject(url) != null) return true;
        }
        return false;
    }

    private static boolean looksLikeModrinthProjectMatch(InstalledMod mod, String slug) {
        if (mod == null) return false;
        String normalizedSlug = normalizeKey(slug).replace('_', '-');
        if (normalizedSlug.isBlank()) return false;

        String modId = normalizeKey(mod.modId).replace('_', '-');
        if (!modId.isBlank()) {
            if (normalizedSlug.equals(modId)) return true;
            if (normalizedSlug.contains(modId) || modId.contains(normalizedSlug)) return true;
        }

        Set<String> identityTokens = buildIdentityTokens(mod);
        if (identityTokens.isEmpty()) return true;

        Set<String> slugTokens = splitProjectTokens(normalizedSlug);
        int overlap = 0;
        for (String token : slugTokens) {
            if (identityTokens.contains(token)) overlap++;
        }

        if (identityTokens.size() >= 3) return overlap >= 2;
        return overlap >= 1;
    }

    private static Set<String> buildIdentityTokens(InstalledMod mod) {
        LinkedHashSet<String> tokens = new LinkedHashSet<>();
        addTokens(tokens, mod.modId);
        addTokens(tokens, stripJarSuffix(mod.fileName));
        addTokens(tokens, stripTrailingVersionLike(stripJarSuffix(mod.fileName)));

        for (String url : mod.urls) {
            String modrinth = parseModrinthProject(url);
            if (modrinth != null) addTokens(tokens, modrinth);

            String curse = parseCurseForgeProject(url);
            if (curse != null) addTokens(tokens, curse);

            String repo = parseGitHubRepo(url);
            if (repo != null && repo.contains("/")) {
                String repoName = repo.substring(repo.lastIndexOf('/') + 1);
                addTokens(tokens, repoName);
            }
        }

        return tokens;
    }

    private static Set<String> splitProjectTokens(String value) {
        LinkedHashSet<String> tokens = new LinkedHashSet<>();
        addTokens(tokens, value);
        return tokens;
    }

    private static void addTokens(Set<String> sink, String value) {
        String normalized = normalizeKey(value).replaceAll("[^a-z0-9]+", " ").trim();
        if (normalized.isBlank()) return;

        for (String token : normalized.split("\\s+")) {
            if (token.length() < 3) continue;
            if (token.equals("mod") || token.equals("mods")
                || token.equals("api") || token.equals("mc")
                || token.equals("minecraft") || token.equals("fabric")
                || token.equals("forge") || token.equals("neoforge")
                || token.equals("quilt") || token.equals("client")) {
                continue;
            }
            sink.add(token);
        }
    }

    private AutoResolvedSource tryAutoResolveGithub(
        InstalledMod mod,
        UpdateRequest request,
        Map<String, String> searchRepoCache,
        Map<String, RemoteRelease> releaseCache,
        int[] searchBudget
    ) {
        for (String query : buildGithubQueryCandidates(mod)) {
            String key = normalizeKey(query);
            if (key.isBlank()) continue;

            String repo = searchRepoCache.get(key);
            if (repo == null && !searchRepoCache.containsKey(key)) {
                if (searchBudget[0] <= 0) break;
                searchBudget[0]--;

                try {
                    repo = searchGitHubRepoByQuery(query, request.githubToken);
                } catch (Exception ignored) {
                    repo = "";
                }
                searchRepoCache.put(key, repo == null ? "" : repo);
            }

            if (repo == null || repo.isBlank()) continue;

            RemoteRelease release = releaseCache.get(repo);
            if (release == null && !releaseCache.containsKey(repo)) {
                try {
                    release = fetchGitHub(
                        new SourceSpec(SourceProvider.GITHUB, repo, null),
                        request.loader,
                        request.targetGameVersion,
                        request.includeGithubPreReleases,
                        request.githubToken
                    );
                } catch (Exception ignored) {
                    release = null;
                }
                releaseCache.put(repo, release);
            }

            if (release != null) {
                return new AutoResolvedSource(new SourceSpec(SourceProvider.GITHUB, repo, null), release);
            }
        }
        return null;
    }

    private String searchGitHubRepoByQuery(String query, String githubToken) throws Exception {
        String q = URLEncoder.encode(query + " minecraft fabric mod", StandardCharsets.UTF_8);
        URI uri = URI.create("https://api.github.com/search/repositories?q=" + q + "&sort=stars&order=desc&per_page=5");

        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(30))
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", USER_AGENT)
            .GET();
        if (!safe(githubToken).isBlank()) builder.header("Authorization", "Bearer " + githubToken.trim());

        HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() == 404) return null;
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("GitHub search: " + SyncJsonUtils.parseHttpError(response));
        }

        JsonElement root = JsonParser.parseString(response.body());
        if (!root.isJsonObject()) return null;
        JsonArray items = SyncJsonUtils.readArray(root.getAsJsonObject(), "items");
        if (items == null || items.isEmpty()) return null;

        for (JsonElement itemElement : items) {
            if (!itemElement.isJsonObject()) continue;
            JsonObject item = itemElement.getAsJsonObject();
            if (SyncJsonUtils.readBoolean(item, "archived", false)) continue;
            if (SyncJsonUtils.readBoolean(item, "disabled", false)) continue;

            String fullName = SyncJsonUtils.readString(item, "full_name", "").trim();
            if (fullName.isBlank() || !fullName.contains("/")) continue;
            return fullName;
        }

        return null;
    }

    private static Set<String> buildGithubQueryCandidates(InstalledMod mod) {
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        addCandidate(candidates, mod.modId);

        String file = stripJarSuffix(mod.fileName);
        addCandidate(candidates, stripTrailingVersionLike(file));
        addCandidate(candidates, firstToken(file));
        addCandidate(candidates, normalizeSlugHint(mod.modId));
        return candidates;
    }

    private AutoResolvedSource tryPreferModrinthOverGithub(
        InstalledMod mod,
        UpdateRequest request,
        Map<String, RemoteRelease> modrinthAutoCache,
        Map<String, List<String>> modrinthSearchCache,
        RemoteRelease githubRelease
    ) {
        if (mod == null || request == null || githubRelease == null) return null;

        boolean githubLooksBad = isRemoteOlderThanInstalled(mod, githubRelease)
            || !detectIncompatibleTargetHint(githubRelease, request.targetGameVersion).isBlank();
        if (!githubLooksBad) return null;

        AutoResolvedSource fallback = tryAutoResolveSource(mod, request, modrinthAutoCache, modrinthSearchCache);
        if (fallback == null || fallback.release == null) return null;
        if (isRemoteOlderThanInstalled(mod, fallback.release)) return null;
        if (!detectIncompatibleTargetHint(fallback.release, request.targetGameVersion).isBlank()) return null;
        return fallback;
    }

    private List<String> searchModrinthProjectSlugs(String query) throws Exception {
        String q = URLEncoder.encode(safe(query), StandardCharsets.UTF_8);
        String facets = URLEncoder.encode("[[\"project_type:mod\"]]", StandardCharsets.UTF_8);
        URI uri = URI.create("https://api.modrinth.com/v2/search?query=" + q + "&limit=8&index=relevance&facets=" + facets);

        HttpRequest request = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(30))
            .header("Accept", "application/json")
            .header("User-Agent", USER_AGENT)
            .GET()
            .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() == 404) return List.of();
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Modrinth search: " + SyncJsonUtils.parseHttpError(response));
        }

        JsonElement root = JsonParser.parseString(response.body());
        if (!root.isJsonObject()) return List.of();
        JsonArray hits = SyncJsonUtils.readArray(root.getAsJsonObject(), "hits");
        if (hits == null || hits.isEmpty()) return List.of();

        LinkedHashSet<String> slugs = new LinkedHashSet<>();
        for (JsonElement hitElement : hits) {
            if (!hitElement.isJsonObject()) continue;
            JsonObject hit = hitElement.getAsJsonObject();
            String slug = SyncJsonUtils.readString(hit, "slug", "").trim();
            if (!slug.isBlank()) slugs.add(slug);
            if (slugs.size() >= 8) break;
        }
        return new ArrayList<>(slugs);
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

    private static Set<String> buildModrinthSlugCandidates(InstalledMod mod) {
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        addCandidate(candidates, mod.modId);
        addCandidate(candidates, splitCamelToKebab(mod.modId));

        String file = stripJarSuffix(mod.fileName);
        addCandidate(candidates, file);
        String strippedFile = stripTrailingVersionLike(file);
        addCandidate(candidates, strippedFile);
        addCandidate(candidates, splitCamelToKebab(strippedFile));
        addCandidate(candidates, firstToken(file));
        addCandidate(candidates, normalizeSlugHint(mod.modId));
        addCandidate(candidates, normalizeSlugHint(strippedFile));

        for (String url : mod.urls) {
            String project = parseModrinthProject(url);
            if (project != null) addCandidate(candidates, project);

            String curse = parseCurseForgeProject(url);
            if (curse != null) addCandidate(candidates, curse);

            String repo = parseGitHubRepo(url);
            if (repo != null) {
                String repoName = repo.substring(repo.lastIndexOf('/') + 1);
                addCandidate(candidates, repoName);
                addCandidate(candidates, splitCamelToKebab(repoName));
            }
        }
        return candidates;
    }

    private static Set<String> buildModrinthSearchCandidates(InstalledMod mod) {
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        addCandidate(candidates, mod.modId);
        addCandidate(candidates, splitCamelToKebab(mod.modId));

        String file = stripJarSuffix(mod.fileName);
        String strippedFile = stripTrailingVersionLike(file);
        addCandidate(candidates, strippedFile);
        addCandidate(candidates, splitCamelToKebab(strippedFile));
        addCandidate(candidates, firstToken(file));

        for (String url : mod.urls) {
            String project = parseModrinthProject(url);
            if (project != null) addCandidate(candidates, project);

            String curse = parseCurseForgeProject(url);
            if (curse != null) addCandidate(candidates, curse);

            String repo = parseGitHubRepo(url);
            if (repo != null) {
                String repoName = repo.substring(repo.lastIndexOf('/') + 1);
                addCandidate(candidates, repoName);
                addCandidate(candidates, splitCamelToKebab(repoName));
                addCandidate(candidates, repoName.replace('-', ' '));
            }
        }

        return candidates;
    }

    private static String stripTrailingVersionLike(String input) {
        String value = safe(input).trim();
        if (value.isBlank()) return value;

        value = value.replace('\\', '-').replace('_', '-').replace('+', '-');
        int cut = value.length();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!Character.isDigit(c)) continue;
            if (i <= 0) continue;
            char prev = value.charAt(i - 1);
            if (prev == '-' || prev == 'v' || prev == 'V') {
                cut = i - 1;
                break;
            }
        }
        if (cut > 2) value = value.substring(0, cut);
        return value;
    }

    private static String firstToken(String input) {
        String value = safe(input).trim();
        if (value.isBlank()) return value;
        int idx = value.indexOf('-');
        return idx > 0 ? value.substring(0, idx) : value;
    }

    private static String normalizeSlugHint(String value) {
        String slug = safe(value).trim().toLowerCase(Locale.ROOT);
        if (slug.isBlank()) return slug;
        slug = slug.replace('_', '-').replace(' ', '-');
        while (slug.contains("--")) slug = slug.replace("--", "-");
        while (slug.startsWith("-")) slug = slug.substring(1);
        while (slug.endsWith("-")) slug = slug.substring(0, slug.length() - 1);
        return slug;
    }

    private static String splitCamelToKebab(String value) {
        String raw = safe(value).trim();
        if (raw.isBlank()) return raw;
        String withDelimiters = raw
            .replaceAll("([a-z0-9])([A-Z])", "$1-$2")
            .replaceAll("([A-Z])([A-Z][a-z])", "$1-$2");
        return withDelimiters.toLowerCase(Locale.ROOT);
    }

    private static void addCandidate(Set<String> candidates, String value) {
        String candidate = normalizeSlugHint(value);
        if (candidate.length() < 3) return;
        candidates.add(candidate);
    }

    private RemoteRelease tryResolveSpecialRelease(InstalledMod mod, UpdateRequest request) {
        String modId = normalizeKey(mod.modId);
        if (!"baritone".equals(modId)) return null;
        try {
            return fetchMeteorBaritone(request.targetGameVersion);
        } catch (Exception ignored) {
            return null;
        }
    }

    private RemoteRelease fetchMeteorBaritone(String targetVersion) throws Exception {
        URI uri = URI.create("https://meteorclient.com/api/downloadBaritone");
        HttpRequest request = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(30))
            .header("Accept", "application/java-archive")
            .header("User-Agent", USER_AGENT)
            .GET()
            .build();
        HttpResponse<Void> response = http.send(request, HttpResponse.BodyHandlers.discarding());
        if (response.statusCode() < 200 || response.statusCode() >= 300) return null;

        String contentDisposition = safe(response.headers().firstValue("content-disposition").orElse(""));
        String fileName = extractFileNameFromContentDisposition(contentDisposition);
        if (fileName.isBlank()) fileName = "baritone-meteor-" + safe(targetVersion).trim() + ".jar";
        if (!fileName.toLowerCase(Locale.ROOT).endsWith(".jar")) return null;

        String lowerName = fileName.toLowerCase(Locale.ROOT);
        String target = safe(targetVersion).trim().toLowerCase(Locale.ROOT);
        if (!target.isBlank() && !lowerName.contains(target)) return null;

        String version = target;
        return new RemoteRelease(SourceProvider.GITHUB, "meteorclient/baritone-api", version, fileName, uri.toString());
    }

    private static String extractFileNameFromContentDisposition(String value) {
        String text = safe(value).trim();
        if (text.isBlank()) return "";
        String lower = text.toLowerCase(Locale.ROOT);
        int idx = lower.indexOf("filename=");
        if (idx < 0) return "";
        String raw = text.substring(idx + "filename=".length()).trim();
        if (raw.startsWith("\"") && raw.endsWith("\"") && raw.length() >= 2) {
            raw = raw.substring(1, raw.length() - 1);
        }
        return raw.trim();
    }

    private RemoteRelease fetchLatest(SourceSpec source, UpdateRequest request) throws Exception {
        return switch (source.provider) {
            case MODRINTH -> fetchModrinth(source.id, request.loader, request.targetGameVersion);
            case GITHUB -> fetchGitHub(source, request.loader, request.targetGameVersion, request.includeGithubPreReleases, request.githubToken);
        };
    }

    private RemoteRelease fetchModrinth(String project, String loader, String targetVersion) throws Exception {
        String encodedProject = URLEncoder.encode(project, StandardCharsets.UTF_8);
        String encodedLoader = URLEncoder.encode("[\"" + safe(loader).toLowerCase(Locale.ROOT) + "\"]", StandardCharsets.UTF_8);
        List<String> versionCandidates = buildModrinthGameVersionCandidates(targetVersion);
        String encodedTarget = URLEncoder.encode(toJsonArray(versionCandidates), StandardCharsets.UTF_8);
        URI uri = URI.create(
            "https://api.modrinth.com/v2/project/" + encodedProject + "/version?loaders=" + encodedLoader + "&game_versions=" + encodedTarget
        );

        HttpRequest req = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(30))
            .header("Accept", "application/json")
            .header("User-Agent", USER_AGENT)
            .GET()
            .build();
        HttpResponse<String> response = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() == 404) return null;
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Modrinth: " + SyncJsonUtils.parseHttpError(response));
        }

        JsonElement root = JsonParser.parseString(response.body());
        if (!root.isJsonArray()) return null;
        JsonObject bestVersion = null;
        JsonObject bestFile = null;
        int bestScore = Integer.MIN_VALUE;
        String target = safe(targetVersion).trim().toLowerCase(Locale.ROOT);
        String targetSeries = extractVersionSeries(target);

        for (JsonElement versionElement : root.getAsJsonArray()) {
            if (!versionElement.isJsonObject()) continue;
            JsonObject version = versionElement.getAsJsonObject();
            if (!isModrinthVersionCompatible(version, target, targetSeries)) continue;
            JsonObject file = pickModrinthFile(SyncJsonUtils.readArray(version, "files"));
            if (file == null) continue;

            int score = scoreModrinthVersion(version, target, targetSeries);
            if (score > bestScore) {
                bestScore = score;
                bestVersion = version;
                bestFile = file;
            }
        }

        if (bestVersion == null || bestFile == null) return null;
        String url = SyncJsonUtils.readString(bestFile, "url", "");
        String fileName = SyncJsonUtils.readString(bestFile, "filename", "");
        String releaseVersion = SyncJsonUtils.readString(bestVersion, "version_number", SyncJsonUtils.readString(bestVersion, "name", ""));
        if (url.isBlank() || fileName.isBlank()) return null;
        return new RemoteRelease(SourceProvider.MODRINTH, project, releaseVersion, fileName, url);
    }

    private static JsonObject pickModrinthFile(JsonArray files) {
        if (files == null || files.isEmpty()) return null;
        JsonObject fallback = null;
        for (JsonElement fileElement : files) {
            if (!fileElement.isJsonObject()) continue;
            JsonObject file = fileElement.getAsJsonObject();
            String fileName = SyncJsonUtils.readString(file, "filename", "");
            if (!fileName.toLowerCase(Locale.ROOT).endsWith(".jar")) continue;
            if (SyncJsonUtils.readBoolean(file, "primary", false)) return file;
            if (fallback == null) fallback = file;
        }
        return fallback;
    }

    private static List<String> buildModrinthGameVersionCandidates(String targetVersion) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        String target = safe(targetVersion).trim();
        if (!target.isBlank()) values.add(target);
        if (values.isEmpty()) values.add("1.21.11");
        return new ArrayList<>(values);
    }

    private static String extractVersionSeries(String version) {
        String value = safe(version).trim();
        if (value.isBlank()) return "";
        String[] parts = value.split("\\.");
        if (parts.length < 2) return value;
        return parts[0] + "." + parts[1];
    }

    private static String toJsonArray(List<String> values) {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (String value : values) {
            String v = safe(value).trim();
            if (v.isBlank()) continue;
            if (!first) sb.append(',');
            first = false;
            sb.append('"').append(v.replace("\"", "\\\"")).append('"');
        }
        return sb.append(']').toString();
    }

    private static int scoreModrinthVersion(JsonObject version, String target, String series) {
        int score = 0;
        JsonArray gameVersions = SyncJsonUtils.readArray(version, "game_versions");
        if (jsonArrayContains(gameVersions, target)) score += 300;
        if (!series.isBlank() && jsonArrayContains(gameVersions, series)) score += 160;

        String versionNumber = SyncJsonUtils.readString(version, "version_number", "").toLowerCase(Locale.ROOT);
        if (!target.isBlank() && versionNumber.contains(target)) score += 80;
        if (!series.isBlank() && versionNumber.contains(series)) score += 50;
        if (SyncJsonUtils.readBoolean(version, "featured", false)) score += 10;
        return score;
    }

    private static boolean isModrinthVersionCompatible(JsonObject version, String target, String targetSeries) {
        String targetValue = safe(target).trim().toLowerCase(Locale.ROOT);
        if (targetValue.isBlank()) return true;

        JsonArray gameVersions = SyncJsonUtils.readArray(version, "game_versions");
        if (gameVersions == null || gameVersions.isEmpty()) return false;

        for (JsonElement element : gameVersions) {
            if (!element.isJsonPrimitive()) continue;
            String candidate = safe(element.getAsString()).trim().toLowerCase(Locale.ROOT);
            if (candidate.isBlank()) continue;
            if (candidate.equals(targetValue)) return true;
            if (!targetSeries.isBlank() && candidate.equals(targetSeries) && targetValue.equals(targetSeries)) return true;
            if (isMinecraftVersionRangeContaining(candidate, targetValue)) return true;
        }
        return false;
    }

    private static boolean isMinecraftVersionRangeContaining(String candidate, String target) {
        String value = safe(candidate).trim().toLowerCase(Locale.ROOT);
        int dash = value.indexOf('-');
        if (dash <= 0 || dash >= value.length() - 1) return false;

        String min = value.substring(0, dash).trim();
        String max = value.substring(dash + 1).trim();
        if (!SIMPLE_MC_VERSION.matcher(min).matches()) return false;
        if (!SIMPLE_MC_VERSION.matcher(max).matches()) return false;
        if (!SIMPLE_MC_VERSION.matcher(target).matches()) return false;

        return compareVersionScore(target, min) >= 0 && compareVersionScore(target, max) <= 0;
    }

    private static boolean jsonArrayContains(JsonArray array, String value) {
        String needle = safe(value).trim().toLowerCase(Locale.ROOT);
        if (array == null || array.isEmpty() || needle.isBlank()) return false;
        for (JsonElement element : array) {
            if (!element.isJsonPrimitive()) continue;
            String candidate = safe(element.getAsString()).trim().toLowerCase(Locale.ROOT);
            if (candidate.equals(needle)) return true;
        }
        return false;
    }

    private RemoteRelease fetchGitHub(
        SourceSpec source,
        String loader,
        String targetVersion,
        boolean includePreReleases,
        String githubToken
    ) throws Exception {
        URI uri = URI.create("https://api.github.com/repos/" + source.id + "/releases?per_page=20");
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(30))
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", USER_AGENT)
            .GET();
        if (!safe(githubToken).isBlank()) builder.header("Authorization", "Bearer " + githubToken.trim());
        HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() == 404) return null;
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("GitHub: " + SyncJsonUtils.parseHttpError(response));
        }

        JsonElement root = JsonParser.parseString(response.body());
        if (!root.isJsonArray()) return null;

        RemoteRelease bestRelease = null;
        int bestScore = Integer.MIN_VALUE;
        String target = safe(targetVersion).toLowerCase(Locale.ROOT);
        String series = extractVersionSeries(target);

        for (JsonElement releaseElement : root.getAsJsonArray()) {
            if (!releaseElement.isJsonObject()) continue;
            JsonObject release = releaseElement.getAsJsonObject();
            if (SyncJsonUtils.readBoolean(release, "draft", false)) continue;
            boolean preRelease = SyncJsonUtils.readBoolean(release, "prerelease", false);
            if (!includePreReleases && preRelease) continue;

            JsonObject asset = pickGitHubAsset(SyncJsonUtils.readArray(release, "assets"), loader, targetVersion, source.assetRegex);
            if (asset == null) continue;
            String url = SyncJsonUtils.readString(asset, "browser_download_url", "");
            String fileName = SyncJsonUtils.readString(asset, "name", "");
            String releaseVersion = SyncJsonUtils.readString(release, "tag_name", SyncJsonUtils.readString(release, "name", ""));
            if (url.isBlank() || fileName.isBlank()) continue;

            int score = scoreGitHubReleaseCandidate(fileName, releaseVersion, loader, target, series, preRelease);
            if (score > bestScore) {
                bestScore = score;
                bestRelease = new RemoteRelease(SourceProvider.GITHUB, source.id, releaseVersion, fileName, url);
            }
        }
        return bestRelease;
    }

    private static int scoreGitHubReleaseCandidate(
        String fileName,
        String releaseVersion,
        String loader,
        String targetVersion,
        String targetSeries,
        boolean preRelease
    ) {
        String file = safe(fileName).toLowerCase(Locale.ROOT);
        String release = safe(releaseVersion).toLowerCase(Locale.ROOT);
        String load = safe(loader).toLowerCase(Locale.ROOT);
        String target = safe(targetVersion).toLowerCase(Locale.ROOT);
        String compact = target.replace(".", "");
        String series = safe(targetSeries).toLowerCase(Locale.ROOT);

        int score = 0;
        if (!target.isBlank() && (file.contains(target) || release.contains(target))) score += 280;
        if (!compact.isBlank() && file.contains(compact)) score += 60;
        if (!series.isBlank() && (file.contains(series) || release.contains(series))) score += 80;
        if (!load.isBlank() && file.contains(load)) score += 40;
        if (file.contains("fabric") || release.contains("fabric")) score += 35;
        if (preRelease) score -= 8;
        if (file.contains("sources")) score -= 40;
        if (file.contains("dev")) score -= 10;
        if (file.contains("forge") || file.contains("neoforge") || file.contains("quilt")) score -= 120;
        if (release.contains("forge") || release.contains("neoforge") || release.contains("quilt")) score -= 90;

        return score;
    }

    private static JsonObject pickGitHubAsset(JsonArray assets, String loader, String targetVersion, Pattern regex) {
        if (assets == null || assets.isEmpty()) return null;
        JsonObject best = null;
        JsonObject firstJar = null;
        int bestScore = Integer.MIN_VALUE;

        String load = safe(loader).toLowerCase(Locale.ROOT);
        String target = safe(targetVersion).toLowerCase(Locale.ROOT);
        String targetCompact = target.replace(".", "");
        boolean wantFabric = load.isBlank() || load.contains("fabric");

        for (JsonElement assetElement : assets) {
            if (!assetElement.isJsonObject()) continue;
            JsonObject asset = assetElement.getAsJsonObject();
            String name = SyncJsonUtils.readString(asset, "name", "");
            String lower = name.toLowerCase(Locale.ROOT);
            if (!lower.endsWith(".jar")) continue;
            if (firstJar == null) firstJar = asset;

            if (regex != null && !regex.matcher(name).find()) continue;

            boolean hasForge = lower.contains("forge") || lower.contains("neoforge");
            boolean hasQuilt = lower.contains("quilt");
            boolean hasFabric = lower.contains("fabric");
            if (wantFabric && (hasForge || hasQuilt) && !hasFabric) continue;

            int score = 0;
            if (!target.isBlank() && lower.contains(target)) score += 120;
            if (!targetCompact.isBlank() && lower.contains(targetCompact)) score += 30;
            if (!load.isBlank() && lower.contains(load)) score += 40;
            if (lower.contains("fabric")) score += 30;
            if (lower.contains("forge") || lower.contains("neoforge") || lower.contains("quilt")) score -= 120;
            if (lower.contains("sources")) score -= 30;
            if (lower.contains("-api")) score -= 15;

            if (score > bestScore) {
                bestScore = score;
                best = asset;
            }
        }

        if (best != null) return best;
        if (regex != null) return null;
        if (wantFabric) {
            return null;
        }
        return firstJar;
    }

    private static boolean needsUpdate(InstalledMod mod, RemoteRelease release) {
        String currentVersion = normalizeVersion(mod.version);
        String remoteVersion = normalizeVersion(release.version);
        String currentFile = safe(mod.fileName).toLowerCase(Locale.ROOT);
        String remoteFile = safe(release.fileName).toLowerCase(Locale.ROOT);
        if (!currentVersion.isBlank() && !remoteVersion.isBlank()) {
            int cmp = compareVersionScore(mod.version, release.version);
            if (cmp > 0) return false; // Current is newer; do not downgrade.
            if (cmp == 0) return !currentFile.equals(remoteFile);
            if (currentVersion.equals(remoteVersion)) return !currentFile.equals(remoteFile);
            if (currentVersion.contains(remoteVersion) || remoteVersion.contains(currentVersion)) return !currentFile.equals(remoteFile);
            return true;
        }
        return !currentFile.equals(remoteFile);
    }

    private static boolean isRemoteOlderThanInstalled(InstalledMod mod, RemoteRelease release) {
        if (mod == null || release == null) return false;
        if (safe(mod.version).isBlank() || safe(release.version).isBlank()) return false;
        return compareVersionScore(mod.version, release.version) > 0;
    }

    private static String buildOlderReleaseDetail(InstalledMod mod, RemoteRelease release) {
        String current = safe(mod == null ? "" : mod.version).trim();
        String remote = safe(release == null ? "" : release.version).trim();
        if (current.isBlank() && remote.isBlank()) return "available-release-older-than-installed";
        return "available-release-older-than-installed current=" + current + " remote=" + remote;
    }

    private static String detectIncompatibleTargetHint(RemoteRelease release, String targetGameVersion) {
        if (release == null) return "";
        String target = safe(targetGameVersion).trim().toLowerCase(Locale.ROOT);
        if (target.isBlank()) return "";

        String targetSeries = extractVersionSeries(target);
        if (targetSeries.isBlank()) return "";

        int targetPatch = parseVersionPatch(target);
        LinkedHashSet<String> hints = new LinkedHashSet<>();
        collectMinecraftVersionHints(hints, safe(release.fileName), targetSeries);
        collectMinecraftVersionHints(hints, safe(release.version), targetSeries);
        if (hints.isEmpty()) return "";
        if (hints.contains(target) || hints.contains(targetSeries)) return "";

        for (String hint : hints) {
            if (hint.isBlank()) continue;
            if (!hint.startsWith(targetSeries)) {
                return hint;
            }

            if (targetPatch >= 0 && hint.startsWith(targetSeries + ".")) {
                int hintPatch = parseVersionPatch(hint);
                if (hintPatch >= 0 && hintPatch > targetPatch) {
                    return hint;
                }
            }
        }

        return "";
    }

    private static void collectMinecraftVersionHints(Set<String> sink, String text, String targetSeries) {
        if (sink == null) return;
        String value = safe(text).toLowerCase(Locale.ROOT);
        if (value.isBlank()) return;

        var markedMatcher = MC_MARKED_VERSION.matcher(value);
        while (markedMatcher.find()) {
            String hint = safe(markedMatcher.group(1)).trim().toLowerCase(Locale.ROOT);
            if (!hint.isBlank()) sink.add(hint);
        }

        if (targetSeries.isBlank()) return;
        Pattern seriesPattern = Pattern.compile("(?<!\\d)" + Pattern.quote(targetSeries) + "(?:\\.\\d+)?(?!\\d)");
        var seriesMatcher = seriesPattern.matcher(value);
        while (seriesMatcher.find()) {
            String hint = safe(seriesMatcher.group()).trim().toLowerCase(Locale.ROOT);
            if (!hint.isBlank()) sink.add(hint);
        }
    }

    private static int parseVersionPatch(String version) {
        String value = safe(version).trim();
        if (value.isBlank()) return -1;
        String[] parts = value.split("\\.");
        if (parts.length < 3) return -1;
        try {
            return Integer.parseInt(parts[2]);
        } catch (Exception ignored) {
            return -1;
        }
    }

    private static boolean targetAlreadyHasLatest(InstalledMod mod, RemoteRelease latest, Path targetModsDir, String targetGameVersion, String loader) {
        if (mod == null || latest == null || targetModsDir == null) return false;
        try {
            Path source = mod.jarPath.toAbsolutePath().normalize();
            Path destination = targetModsDir.resolve(latest.fileName).toAbsolutePath().normalize();
            if (source.equals(destination)) return false;
            if (!Files.exists(destination)) return false;
            if (!validateJarCompatibility(destination, targetGameVersion, loader).isBlank()) return false;

            long size = Files.size(destination);
            return size > 0L;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static int compareVersionScore(String currentRaw, String remoteRaw) {
        List<Integer> current = extractVersionNumbers(currentRaw);
        List<Integer> remote = extractVersionNumbers(remoteRaw);
        int max = Math.max(current.size(), remote.size());
        for (int i = 0; i < max; i++) {
            int a = i < current.size() ? current.get(i) : 0;
            int b = i < remote.size() ? remote.get(i) : 0;
            if (a == b) continue;
            return Integer.compare(a, b);
        }
        return 0;
    }

    private static List<Integer> extractVersionNumbers(String raw) {
        String bestToken = selectComparableVersionToken(raw);
        if (!bestToken.isBlank()) {
            List<Integer> picked = extractAllIntegers(bestToken);
            if (!picked.isEmpty()) return picked;
        }
        return extractAllIntegers(safe(raw));
    }

    private static String selectComparableVersionToken(String raw) {
        String value = safe(raw).trim().toLowerCase(Locale.ROOT);
        if (value.isBlank()) return "";

        String normalized = value
            .replace('/', '-')
            .replace('_', '-')
            .replace('+', '-')
            .replaceAll("[^a-z0-9.\\-]", "-");

        String[] tokens = normalized.split("-+");
        String best = "";
        int bestScore = Integer.MIN_VALUE;
        for (String token : tokens) {
            String candidate = safe(token).trim();
            if (candidate.isBlank()) continue;

            int score = scoreVersionToken(candidate);
            if (score > bestScore) {
                bestScore = score;
                best = candidate;
            }
        }

        if (bestScore < 10) return "";
        return best;
    }

    private static int scoreVersionToken(String token) {
        if (token.isBlank()) return Integer.MIN_VALUE;

        boolean hasDigit = false;
        for (int i = 0; i < token.length(); i++) {
            if (Character.isDigit(token.charAt(i))) {
                hasDigit = true;
                break;
            }
        }
        if (!hasDigit) return Integer.MIN_VALUE;

        int score = 0;
        if (token.matches("v?\\d+(?:\\.\\d+){1,4}[a-z0-9.]*")) score += 130;
        else if (token.matches("v?\\d+[a-z0-9.]*")) score += 75;
        else score += 25;

        if (token.startsWith("v")) score += 10;
        if (token.startsWith("mc") || token.startsWith("minecraft")) score -= 220;
        if (token.contains("fabric") || token.contains("forge") || token.contains("neoforge") || token.contains("quilt")) score -= 90;
        if (token.contains("alpha") || token.contains("beta") || token.contains("snapshot")) score -= 8;

        // Most MC versions in modern packs start with 1.14+; penalize to avoid
        // preferring embedded game-version chunks like "1.21.11" over real mod version.
        if (token.matches("1\\.(1[4-9]|2\\d)(?:\\.\\d+)?[a-z0-9.]*")) score -= 70;

        return score;
    }

    private static List<Integer> extractAllIntegers(String raw) {
        ArrayList<Integer> numbers = new ArrayList<>();
        String value = safe(raw);
        if (value.isBlank()) return numbers;

        StringBuilder token = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isDigit(c)) {
                token.append(c);
                continue;
            }

            if (token.length() > 0) {
                try {
                    numbers.add(Integer.parseInt(token.toString()));
                } catch (Exception ignored) {
                }
                token.setLength(0);
            }
        }

        if (token.length() > 0) {
            try {
                numbers.add(Integer.parseInt(token.toString()));
            } catch (Exception ignored) {
            }
        }

        return numbers;
    }

    private void applyUpdate(InstalledMod mod, RemoteRelease release, UpdateRequest request, Path backupDir) throws Exception {
        Path temp = Files.createTempFile(request.workspaceDir, "download-", ".jar");
        try {
            downloadRelease(release, request.githubToken, temp);
            String compatibilityIssue = validateJarCompatibility(temp, request.targetGameVersion, request.loader);
            if (!compatibilityIssue.isBlank()) {
                throw new IncompatibleReleaseException(compatibilityIssue);
            }

            Path source = mod.jarPath;
            Path targetModsDir = request.targetModsDir;
            Path destination = targetModsDir.resolve(release.fileName);
            Path currentTargetVersion = targetModsDir.resolve(mod.fileName);
            Path sourceParent = source.toAbsolutePath().normalize().getParent();
            Path normalizedTargetDir = targetModsDir.toAbsolutePath().normalize();
            boolean sourceEqualsTarget = sourceParent != null && sourceParent.equals(normalizedTargetDir);
            if (source.equals(destination)) {
                moveToBackupOrDelete(source, backupDir);
                moveWithReplace(temp, destination);
                return;
            }

            if (Files.exists(currentTargetVersion) && !currentTargetVersion.equals(destination)) {
                moveToBackupOrDelete(currentTargetVersion, backupDir);
            }
            if (Files.exists(destination)) moveToBackupOrDelete(destination, backupDir);
            if (sourceEqualsTarget && Files.exists(source)) moveToBackupOrDelete(source, backupDir);
            moveWithReplace(temp, destination);
        } finally {
            tryDelete(temp);
        }
    }

    private static String validateJarCompatibility(Path jar, String targetGameVersion, String loader) {
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

    private boolean copySourceJarToTarget(InstalledMod mod, Path targetModsDir, Path backupDir) {
        try {
            Path source = mod.jarPath.toAbsolutePath().normalize();
            Path destination = targetModsDir.resolve(mod.fileName).toAbsolutePath().normalize();
            if (source.equals(destination)) return false;

            if (destination.getParent() != null) Files.createDirectories(destination.getParent());
            if (Files.exists(destination)) {
                long sourceSize = safeSize(source);
                long destinationSize = safeSize(destination);
                if (sourceSize > 0 && sourceSize == destinationSize) return true;
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
            .header("User-Agent", USER_AGENT)
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

        Files.createDirectories(backupDir);
        Path target = backupDir.resolve(file.getFileName().toString());
        int index = 1;
        while (Files.exists(target)) {
            target = backupDir.resolve(file.getFileName().toString() + "." + index + ".bak");
            index++;
        }
        Files.move(file, target, StandardCopyOption.REPLACE_EXISTING);
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

    private static long safeSize(Path file) {
        try {
            return Files.exists(file) ? Files.size(file) : -1L;
        } catch (Exception ignored) {
            return -1L;
        }
    }

    private boolean tryUseCompatibleSourceJar(
        InstalledMod mod,
        UpdateRequest request,
        boolean sourceEqualsTarget,
        List<UpdateEntry> entries,
        ProgressState progress,
        String detail
    ) {
        // Keep local fallback only for rerun mode (source == target).
        // During migration from older versions this can re-copy incompatible jars.
        if (mod == null || request == null || progress == null || entries == null) return false;
        if (!sourceEqualsTarget) return false;
        String sourceCompatibility = validateJarCompatibility(mod.jarPath, request.targetGameVersion, request.loader);
        if (!sourceCompatibility.isBlank()) return false;

        progress.record(entries, UpdateEntry.upToDateLocal(mod, safe(detail)));
        return true;
    }

    private static final class AutoResolvedSource {
        final SourceSpec source;
        final RemoteRelease release;

        AutoResolvedSource(SourceSpec source, RemoteRelease release) {
            this.source = source;
            this.release = release;
        }
    }

    private static final class IncompatibleReleaseException extends Exception {
        IncompatibleReleaseException(String message) {
            super(safe(message));
        }
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
                case ERROR, SYSTEM -> errors++;
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
