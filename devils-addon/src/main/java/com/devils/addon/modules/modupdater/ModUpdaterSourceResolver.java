package com.devils.addon.modules.modupdater;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static com.devils.addon.modules.modupdater.ModAutoUpdaterText.normalizeKey;
import static com.devils.addon.modules.modupdater.ModAutoUpdaterText.parseCurseForgeProject;
import static com.devils.addon.modules.modupdater.ModAutoUpdaterText.parseGitHubRepo;
import static com.devils.addon.modules.modupdater.ModAutoUpdaterText.parseModrinthProject;
import static com.devils.addon.modules.modupdater.ModAutoUpdaterText.safe;
import static com.devils.addon.modules.modupdater.ModAutoUpdaterText.stripJarSuffix;

final class ModUpdaterSourceResolver {
    private final ModUpdaterReleaseFetcher fetcher;

    ModUpdaterSourceResolver(ModUpdaterReleaseFetcher fetcher) {
        this.fetcher = fetcher;
    }

    SourceSpec resolveSource(InstalledMod mod, Map<String, SourceSpec> overrides) {
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

    static String describeNoSourceReason(InstalledMod mod) {
        if (mod == null) return "no-provider-source";
        if (!mod.fabricMetadata) return "no-fabric.mod.json";
        if (mod.urls == null || mod.urls.isEmpty()) return "no-contact-links-in-fabric.mod.json";
        return "contact-links-present-but-not-modrinth-or-github";
    }

    static String buildNoReleaseDetail(SourceSpec source, UpdateRequest request) {
        if (source == null || request == null) return "no-compatible-release";
        String provider = source.provider == null ? "unknown" : source.provider.name().toLowerCase(Locale.ROOT);
        String sourceId = safe(source.id);
        return "no-compatible-release for " + request.loader + " " + request.targetGameVersion
            + " via " + provider + ":" + sourceId;
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

    AutoResolvedSource tryAutoResolveSource(
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
                resolved = fetcher.fetchModrinth(key, request.loader, request.targetGameVersion);
            } catch (Exception ignored) {
            }
            modrinthAutoCache.put(key, resolved);
            if (resolved != null
                && !ModUpdaterVersioning.isRemoteOlderThanInstalled(mod, resolved)
                && ModUpdaterVersioning.detectIncompatibleTargetHint(resolved, request.targetGameVersion).isBlank()) {
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
                    slugs = fetcher.searchModrinthProjectSlugs(normalizedQuery);
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
                        && !ModUpdaterVersioning.isRemoteOlderThanInstalled(mod, cached)
                        && ModUpdaterVersioning.detectIncompatibleTargetHint(cached, request.targetGameVersion).isBlank()) {
                        return new AutoResolvedSource(new SourceSpec(SourceProvider.MODRINTH, key, null), cached);
                    }
                    continue;
                }

                RemoteRelease resolved = null;
                try {
                    resolved = fetcher.fetchModrinth(key, request.loader, request.targetGameVersion);
                } catch (Exception ignored) {
                }
                modrinthAutoCache.put(key, resolved);
                if (resolved != null
                    && !ModUpdaterVersioning.isRemoteOlderThanInstalled(mod, resolved)
                    && ModUpdaterVersioning.detectIncompatibleTargetHint(resolved, request.targetGameVersion).isBlank()) {
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

    AutoResolvedSource tryAutoResolveGithub(
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
                    repo = fetcher.searchGitHubRepoByQuery(query, request.githubToken);
                } catch (Exception ignored) {
                    repo = "";
                }
                searchRepoCache.put(key, repo == null ? "" : repo);
            }

            if (repo == null || repo.isBlank()) continue;

            RemoteRelease release = releaseCache.get(repo);
            if (release == null && !releaseCache.containsKey(repo)) {
                try {
                    release = fetcher.fetchGitHub(
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

    private static Set<String> buildGithubQueryCandidates(InstalledMod mod) {
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        addCandidate(candidates, mod.modId);

        String file = stripJarSuffix(mod.fileName);
        addCandidate(candidates, stripTrailingVersionLike(file));
        addCandidate(candidates, firstToken(file));
        addCandidate(candidates, normalizeSlugHint(mod.modId));
        return candidates;
    }

    AutoResolvedSource tryPreferModrinthOverGithub(
        InstalledMod mod,
        UpdateRequest request,
        Map<String, RemoteRelease> modrinthAutoCache,
        Map<String, List<String>> modrinthSearchCache,
        RemoteRelease githubRelease
    ) {
        if (mod == null || request == null || githubRelease == null) return null;

        boolean githubLooksBad = ModUpdaterVersioning.isRemoteOlderThanInstalled(mod, githubRelease)
            || !ModUpdaterVersioning.detectIncompatibleTargetHint(githubRelease, request.targetGameVersion).isBlank();
        if (!githubLooksBad) return null;

        AutoResolvedSource fallback = tryAutoResolveSource(mod, request, modrinthAutoCache, modrinthSearchCache);
        if (fallback == null || fallback.release == null) return null;
        if (ModUpdaterVersioning.isRemoteOlderThanInstalled(mod, fallback.release)) return null;
        if (!ModUpdaterVersioning.detectIncompatibleTargetHint(fallback.release, request.targetGameVersion).isBlank()) return null;
        return fallback;
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
}

final class AutoResolvedSource {
    final SourceSpec source;
    final RemoteRelease release;

    AutoResolvedSource(SourceSpec source, RemoteRelease release) {
        this.source = source;
        this.release = release;
    }
}
