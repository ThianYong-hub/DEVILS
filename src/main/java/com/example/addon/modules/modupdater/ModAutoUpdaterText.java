package com.example.addon.modules.modupdater;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ModAutoUpdaterText {
    private static final Pattern MODRINTH_URL = Pattern.compile(
        "https?://(?:www\\.)?modrinth\\.com/(?:mod|project|plugin)/([A-Za-z0-9._-]+)",
        Pattern.CASE_INSENSITIVE
    );
    private static final Pattern GITHUB_URL = Pattern.compile(
        "https?://(?:www\\.)?github\\.com/([^/\\s]+)/([^/\\s#?]+)",
        Pattern.CASE_INSENSITIVE
    );
    private static final Pattern CURSEFORGE_URL = Pattern.compile(
        "https?://(?:www\\.)?curseforge\\.com/minecraft/(?:mc-)?mods/([A-Za-z0-9._-]+)",
        Pattern.CASE_INSENSITIVE
    );
    private static final Pattern NON_ALNUM = Pattern.compile("[^a-z0-9]+");

    private ModAutoUpdaterText() {
    }

    static String parseModrinthProject(String url) {
        if (url == null || url.isBlank()) return null;
        Matcher matcher = MODRINTH_URL.matcher(url.trim());
        if (!matcher.find()) return null;
        String project = safe(matcher.group(1)).trim();
        return project.isBlank() ? null : project;
    }

    static String parseGitHubRepo(String url) {
        if (url == null || url.isBlank()) return null;
        Matcher matcher = GITHUB_URL.matcher(url.trim());
        if (!matcher.find()) return null;
        String owner = safe(matcher.group(1)).trim();
        String repo = safe(matcher.group(2)).trim();
        if (repo.endsWith(".git")) repo = repo.substring(0, repo.length() - 4);
        if (owner.isBlank() || repo.isBlank()) return null;
        return owner + "/" + repo;
    }

    static String parseCurseForgeProject(String url) {
        if (url == null || url.isBlank()) return null;
        Matcher matcher = CURSEFORGE_URL.matcher(url.trim());
        if (!matcher.find()) return null;
        String project = safe(matcher.group(1)).trim();
        return project.isBlank() ? null : project;
    }

    static Pattern compileRegex(String raw) {
        String value = safe(raw).trim();
        if (value.isBlank()) return null;
        try {
            return Pattern.compile(value, Pattern.CASE_INSENSITIVE);
        } catch (Exception ignored) {
            return null;
        }
    }

    static String stripJarSuffix(String value) {
        String file = safe(value);
        return file.toLowerCase(Locale.ROOT).endsWith(".jar") ? file.substring(0, file.length() - 4) : file;
    }

    static String normalizeKey(String value) {
        return safe(value).trim().toLowerCase(Locale.ROOT);
    }

    static String normalizeVersion(String value) {
        return NON_ALNUM.matcher(normalizeKey(value).replace("v", " ")).replaceAll("");
    }

    static String safe(String value) {
        return value == null ? "" : value;
    }

    static String rootMessage(Throwable throwable) {
        if (throwable == null) return "unknown";
        Throwable root = throwable;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        String message = safe(root.getMessage()).trim();
        return message.isBlank() ? root.getClass().getSimpleName() : message;
    }
}
