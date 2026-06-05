package com.devils.addon.shared.sync;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class SyncConfigDiagnostics {
    private SyncConfigDiagnostics() {
    }

    public static Audit inspect(
        String baseUrl,
        boolean allowHttp,
        String deviceId,
        FieldResolution authToken,
        FieldResolution transportSigningKey,
        FieldResolution e2eSecret,
        boolean requireE2e,
        boolean allowUnsignedRequests
    ) {
        ArrayList<Issue> issues = new ArrayList<>();
        String normalizedBaseUrl = safe(baseUrl).trim();
        String normalizedDeviceId = safe(deviceId).trim();
        Mode overallMode = overallMode(authToken, transportSigningKey, e2eSecret);

        inspectBaseUrl(normalizedBaseUrl, allowHttp, issues);
        if (normalizedDeviceId.isBlank()) {
            issues.add(Issue.error(
                "sync-device-id-empty",
                "Sync config is missing 'device-id'. Regenerate it in the sync module settings before syncing."
            ));
        }

        collectFieldIssues(authToken, issues, false, true);
        collectFieldIssues(transportSigningKey, issues, !allowUnsignedRequests, false);
        collectFieldIssues(e2eSecret, issues, requireE2e, false);
        addModeIssue(overallMode, issues);

        if (authToken.isBlank()) {
            issues.add(Issue.warning(
                "sync-auth-token-empty",
                "Sync config is missing 'auth-token'. Requests will only work if the backend allows anonymous sync. Preferred backend env: SYNC_AUTH_TOKEN."
            ));
        }
        if (transportSigningKey.isBlank()) {
            issues.add((allowUnsignedRequests ? Issue.warning(
                "sync-transport-signing-key-empty",
                "Sync config is missing 'transport-signing-key'. Requests will only work if the backend has request signing disabled. Preferred backend env: SYNC_REQUEST_SIGNING_KEY."
            ) : Issue.error(
                "sync-transport-signing-key-empty",
                "Sync config is missing 'transport-signing-key'. This sync path requires signed requests. Preferred backend env: SYNC_REQUEST_SIGNING_KEY."
            )));
        }
        if (requireE2e && e2eSecret.isBlank()) {
            issues.add(Issue.error(
                "sync-e2e-secret-empty",
                "Sync config is missing 'e2e-secret'. This sync path encrypts payloads client-side and cannot run without it. Preferred client env: SYNC_E2E_SECRET."
            ));
        }

        return new Audit(
            normalizedBaseUrl,
            allowHttp,
            normalizedDeviceId,
            authToken,
            transportSigningKey,
            e2eSecret,
            requireE2e,
            allowUnsignedRequests,
            overallMode,
            List.copyOf(issues)
        );
    }

    public static FieldResolution resolveField(
        String preferredName,
        String legacyName,
        String preferredEnvName,
        String legacyEnvName,
        String preferredValue,
        String legacyValue,
        boolean migratedFromLegacy,
        boolean duplicateLegacyCleared
    ) {
        String preferred = safe(preferredValue).trim();
        String legacy = safe(legacyValue).trim();
        boolean bothPresent = !preferred.isBlank() && !legacy.isBlank();
        boolean conflicting = bothPresent && !preferred.equals(legacy);
        String resolved = !preferred.isBlank() ? preferred : legacy;
        Mode mode = conflicting
            ? Mode.CONFLICTING
            : (!preferred.isBlank()
                ? (legacy.isBlank() ? Mode.PREFERRED_ONLY : Mode.MIXED)
                : (legacy.isBlank() ? Mode.DEFAULT : Mode.LEGACY_ONLY));
        return new FieldResolution(
            preferredName,
            legacyName,
            preferredEnvName,
            legacyEnvName,
            resolved,
            mode,
            migratedFromLegacy,
            duplicateLegacyCleared,
            bothPresent,
            conflicting,
            containsWhitespace(resolved),
            containsControlChars(resolved)
        );
    }

    private static void inspectBaseUrl(String baseUrl, boolean allowHttp, List<Issue> issues) {
        if (baseUrl.isBlank()) {
            issues.add(Issue.error(
                "sync-base-url-empty",
                "Sync config is missing 'base-url'. Use a full URL like https://sync.example.com or http://127.0.0.1:7878."
            ));
            return;
        }

        try {
            URI uri = URI.create(baseUrl);
            String scheme = safe(uri.getScheme()).trim().toLowerCase(Locale.ROOT);
            if (scheme.isBlank()) {
                issues.add(Issue.error(
                    "sync-base-url-missing-scheme",
                    "Sync config 'base-url' is missing a scheme. Use http://host:port or https://host."
                ));
                return;
            }
            if (!scheme.equals("http") && !scheme.equals("https")) {
                issues.add(Issue.error(
                    "sync-base-url-unsupported-scheme",
                    "Sync config 'base-url' must use http or https. Unsupported scheme: " + scheme + "."
                ));
                return;
            }
            if (safe(uri.getHost()).isBlank()) {
                issues.add(Issue.error(
                    "sync-base-url-host-missing",
                    "Sync config 'base-url' has no host. Use a value like https://sync.example.com."
                ));
                return;
            }
            if (!allowHttp && scheme.equals("http")) {
                issues.add(Issue.error(
                    "sync-http-disabled",
                    "Sync config blocks plain HTTP. Either switch 'base-url' to https://... or enable 'allow-http' intentionally."
                ));
            }
        } catch (Exception e) {
            issues.add(Issue.error(
                "sync-base-url-invalid",
                "Sync config 'base-url' is invalid: " + compactException(e)
            ));
        }
    }

    private static void addModeIssue(Mode overallMode, List<Issue> issues) {
        switch (overallMode) {
            case LEGACY_ONLY -> issues.add(Issue.warning(
                "sync-config-legacy-mode",
                "Sync config is running in legacy compatibility mode. Replace hidden legacy aliases with 'auth-token', 'transport-signing-key' and 'e2e-secret'."
            ));
            case MIXED -> issues.add(Issue.warning(
                "sync-config-mixed-mode",
                "Sync config is running in mixed preferred+legacy mode. Keep only the preferred names: 'auth-token', 'transport-signing-key' and 'e2e-secret'."
            ));
            case CONFLICTING -> issues.add(Issue.warning(
                "sync-config-conflicting-mode",
                "Sync config has conflicting preferred and legacy values. Preferred names win; remove the legacy aliases to make the config unambiguous."
            ));
            default -> {
            }
        }
    }

    private static Mode overallMode(FieldResolution... fields) {
        boolean hasPreferred = false;
        boolean hasLegacy = false;
        for (FieldResolution field : fields) {
            if (field == null) continue;
            if (field.mode() == Mode.CONFLICTING) return Mode.CONFLICTING;
            if (field.mode() == Mode.PREFERRED_ONLY) hasPreferred = true;
            if (field.mode() == Mode.LEGACY_ONLY) hasLegacy = true;
            if (field.mode() == Mode.MIXED) {
                hasPreferred = true;
                hasLegacy = true;
            }
        }
        if (hasPreferred && hasLegacy) return Mode.MIXED;
        if (hasLegacy) return Mode.LEGACY_ONLY;
        if (hasPreferred) return Mode.PREFERRED_ONLY;
        return Mode.DEFAULT;
    }

    private static void collectFieldIssues(FieldResolution field, List<Issue> issues, boolean required, boolean authField) {
        String preferredName = field.preferredName();
        String legacyName = field.legacyName();

        if (field.migratedFromLegacy()) {
            issues.add(Issue.warning(
                "sync-" + preferredName + "-legacy-migrated",
                "Legacy setting '" + legacyName + "' was migrated to '" + preferredName + "' for this session. Save config to persist the preferred name."
            ));
        }
        if (field.duplicateLegacyCleared()) {
            issues.add(Issue.warning(
                "sync-" + preferredName + "-legacy-duplicate-cleared",
                "Redundant legacy setting '" + legacyName + "' matched '" + preferredName + "' and was cleared automatically. Keep only '" + preferredName + "'."
            ));
        }
        if (field.conflictingAliases()) {
            issues.add(Issue.warning(
                "sync-" + preferredName + "-legacy-conflict",
                "Both '" + preferredName + "' and legacy '" + legacyName + "' are set with different values. '" + preferredName + "' wins; remove '" + legacyName + "' to avoid confusion."
            ));
        }
        if (field.containsControlChars()) {
            issues.add((required ? Issue.error(
                "sync-" + preferredName + "-control-chars",
                "'" + preferredName + "' contains control characters. Re-enter it as a single clean line."
            ) : Issue.warning(
                "sync-" + preferredName + "-control-chars",
                "'" + preferredName + "' contains control characters. This usually means a broken copy/paste."
            )));
        } else if (field.containsWhitespace()) {
            String tail = authField
                ? "Bearer auth tokens should normally not contain spaces."
                : "Keep it as one pasted secret string.";
            issues.add(Issue.warning(
                "sync-" + preferredName + "-whitespace",
                "'" + preferredName + "' contains whitespace. " + tail
            ));
        }
    }

    private static boolean containsWhitespace(String value) {
        if (value == null || value.isBlank()) return false;
        for (int i = 0; i < value.length(); i++) {
            if (Character.isWhitespace(value.charAt(i))) return true;
        }
        return false;
    }

    private static boolean containsControlChars(String value) {
        if (value == null || value.isBlank()) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isISOControl(c)) return true;
        }
        return false;
    }

    private static String compactException(Exception e) {
        if (e == null) return "unknown";
        String type = e.getClass().getSimpleName();
        String message = safe(e.getMessage()).replaceAll("\\s+", " ").trim();
        if (message.isBlank()) return type;
        if (message.length() > 96) message = message.substring(0, 96) + "...";
        return type + ": " + message;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    public enum Severity {
        INFO,
        WARNING,
        ERROR
    }

    public enum Mode {
        DEFAULT,
        PREFERRED_ONLY,
        LEGACY_ONLY,
        MIXED,
        CONFLICTING
    }

    public record Issue(String code, Severity severity, String message) {
        public static Issue warning(String code, String message) {
            return new Issue(code, Severity.WARNING, message);
        }

        public static Issue error(String code, String message) {
            return new Issue(code, Severity.ERROR, message);
        }
    }

    public record FieldResolution(
        String preferredName,
        String legacyName,
        String preferredEnvName,
        String legacyEnvName,
        String resolvedValue,
        Mode mode,
        boolean migratedFromLegacy,
        boolean duplicateLegacyCleared,
        boolean bothPresent,
        boolean conflictingAliases,
        boolean containsWhitespace,
        boolean containsControlChars
    ) {
        public boolean isBlank() {
            return resolvedValue == null || resolvedValue.isBlank();
        }
    }

    public record Audit(
        String baseUrl,
        boolean allowHttp,
        String deviceId,
        FieldResolution authToken,
        FieldResolution transportSigningKey,
        FieldResolution e2eSecret,
        boolean requireE2e,
        boolean allowUnsignedRequests,
        Mode overallMode,
        List<Issue> issues
    ) {
        public boolean hasErrors() {
            return issues.stream().anyMatch(issue -> issue.severity() == Severity.ERROR);
        }

        public String firstErrorCode() {
            for (Issue issue : issues) {
                if (issue.severity() == Severity.ERROR) return issue.code();
            }
            return "";
        }

        public String signature() {
            ArrayList<String> values = new ArrayList<>();
            for (Issue issue : issues) {
                values.add(issue.severity() + ":" + issue.code());
            }
            return String.join("|", values);
        }
    }
}
