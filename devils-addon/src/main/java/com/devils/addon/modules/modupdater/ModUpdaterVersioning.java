package com.devils.addon.modules.modupdater;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import static com.devils.addon.modules.modupdater.ModAutoUpdaterText.normalizeVersion;
import static com.devils.addon.modules.modupdater.ModAutoUpdaterText.safe;

final class ModUpdaterVersioning {
    private static final Pattern MC_MARKED_VERSION = Pattern.compile("(?i)(?:mc|minecraft)[-_ ]?(\\d+\\.\\d+(?:\\.\\d+)?)");
    private static final Pattern SIMPLE_MC_VERSION = Pattern.compile("\\d+\\.\\d+(?:\\.\\d+)?");

    private ModUpdaterVersioning() {
    }

    static boolean needsUpdate(InstalledMod mod, RemoteRelease release) {
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

    static boolean isRemoteOlderThanInstalled(InstalledMod mod, RemoteRelease release) {
        if (mod == null || release == null) return false;
        if (safe(mod.version).isBlank() || safe(release.version).isBlank()) return false;
        return compareVersionScore(mod.version, release.version) > 0;
    }

    static String buildOlderReleaseDetail(InstalledMod mod, RemoteRelease release) {
        String current = safe(mod == null ? "" : mod.version).trim();
        String remote = safe(release == null ? "" : release.version).trim();
        if (current.isBlank() && remote.isBlank()) return "available-release-older-than-installed";
        return "available-release-older-than-installed current=" + current + " remote=" + remote;
    }

    static String detectIncompatibleTargetHint(RemoteRelease release, String targetGameVersion) {
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

    static String extractVersionSeries(String version) {
        String value = safe(version).trim();
        if (value.isBlank()) return "";
        String[] parts = value.split("\\.");
        if (parts.length < 2) return value;
        return parts[0] + "." + parts[1];
    }

    static boolean isMinecraftVersionRangeContaining(String candidate, String target) {
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
}
