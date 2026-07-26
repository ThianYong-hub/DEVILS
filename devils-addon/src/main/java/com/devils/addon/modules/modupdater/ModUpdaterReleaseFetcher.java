package com.devils.addon.modules.modupdater;

import com.devils.addon.shared.sync.SyncJsonUtils;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import static com.devils.addon.modules.modupdater.ModAutoUpdaterText.normalizeKey;
import static com.devils.addon.modules.modupdater.ModAutoUpdaterText.safe;

final class ModUpdaterReleaseFetcher {
    private final HttpClient http;

    ModUpdaterReleaseFetcher(HttpClient http) {
        this.http = http;
    }

    RemoteRelease tryResolveSpecialRelease(InstalledMod mod, UpdateRequest request) {
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
            .header("User-Agent", ModAutoUpdaterEngine.USER_AGENT)
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

    RemoteRelease fetchLatest(SourceSpec source, UpdateRequest request) throws Exception {
        return switch (source.provider) {
            case MODRINTH -> fetchModrinth(source.id, request.loader, request.targetGameVersion);
            case GITHUB -> fetchGitHub(source, request.loader, request.targetGameVersion, request.includeGithubPreReleases, request.githubToken);
        };
    }

    RemoteRelease fetchModrinth(String project, String loader, String targetVersion) throws Exception {
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
            .header("User-Agent", ModAutoUpdaterEngine.USER_AGENT)
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
        String targetSeries = ModUpdaterVersioning.extractVersionSeries(target);

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
            if (ModUpdaterVersioning.isMinecraftVersionRangeContaining(candidate, targetValue)) return true;
        }
        return false;
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

    RemoteRelease fetchGitHub(
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
            .header("User-Agent", ModAutoUpdaterEngine.USER_AGENT)
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
        String series = ModUpdaterVersioning.extractVersionSeries(target);

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

    String searchGitHubRepoByQuery(String query, String githubToken) throws Exception {
        String q = URLEncoder.encode(query + " minecraft fabric mod", StandardCharsets.UTF_8);
        URI uri = URI.create("https://api.github.com/search/repositories?q=" + q + "&sort=stars&order=desc&per_page=5");

        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(30))
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", ModAutoUpdaterEngine.USER_AGENT)
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

    List<String> searchModrinthProjectSlugs(String query) throws Exception {
        String q = URLEncoder.encode(safe(query), StandardCharsets.UTF_8);
        String facets = URLEncoder.encode("[[\"project_type:mod\"]]", StandardCharsets.UTF_8);
        URI uri = URI.create("https://api.modrinth.com/v2/search?query=" + q + "&limit=8&index=relevance&facets=" + facets);

        HttpRequest request = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(30))
            .header("Accept", "application/json")
            .header("User-Agent", ModAutoUpdaterEngine.USER_AGENT)
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
}
