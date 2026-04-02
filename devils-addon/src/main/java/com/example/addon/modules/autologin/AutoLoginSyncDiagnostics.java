package com.example.addon.modules.autologin;

import com.example.addon.modules.autologin.AutoLoginProfile.SyncProfileData;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

final class AutoLoginSyncDiagnostics {
    private static final long SYNC_PROBLEM_LOG_COOLDOWN_MS = 20_000;
    private static final long SYNC_AUTH_BACKOFF_MS = 30_000;
    private static final long SYNC_NETWORK_BACKOFF_MS = 10_000;
    private static final long SYNC_CONFIG_BACKOFF_MS = 30_000;
    private static final long SYNC_DELTA_LOG_WINDOW_MS = 1_200;

    private final BooleanSupplier verboseSupplier;
    private final Consumer<String> infoLogger;

    private String lastProblemSignature = "";
    private long lastProblemLogMs;
    private int lastProblemSuppressed;
    private long backoffUntilMs;

    private boolean deltaPending;
    private long deltaLastUpdateMs;
    private long deltaLastRevision = -1;
    private String deltaWriter = "";
    private int deltaAddedCount;
    private int deltaRemovedCount;
    private int deltaChangedCount;
    private final LinkedHashSet<String> deltaAddedUsers = new LinkedHashSet<>();
    private final LinkedHashSet<String> deltaRemovedUsers = new LinkedHashSet<>();
    private final LinkedHashSet<String> deltaChangedRefs = new LinkedHashSet<>();

    AutoLoginSyncDiagnostics(BooleanSupplier verboseSupplier, Consumer<String> infoLogger) {
        this.verboseSupplier = verboseSupplier;
        this.infoLogger = infoLogger;
    }

    long backoffUntilMs() {
        return backoffUntilMs;
    }

    void queueSyncDelta(String writer, long revision, List<SyncProfileData> added, List<SyncProfileData> removed, List<String> changed) {
        if (!verboseSupplier.getAsBoolean()) return;

        long now = System.currentTimeMillis();
        boolean sameWriter = stringsEqual(deltaWriter, writer);
        boolean sameWindow = deltaPending && sameWriter && now - deltaLastUpdateMs <= SYNC_DELTA_LOG_WINDOW_MS;
        if (!sameWindow) {
            flushPendingDelta(true);
            clearDeltaState();
            deltaPending = true;
            deltaWriter = writer == null ? "" : writer;
        }

        deltaLastRevision = Math.max(deltaLastRevision, revision);
        deltaLastUpdateMs = now;
        deltaAddedCount += added.size();
        deltaRemovedCount += removed.size();
        deltaChangedCount += changed.size();
        for (SyncProfileData data : added) deltaAddedUsers.add(displayValue(data.username(), "<empty-user>"));
        for (SyncProfileData data : removed) deltaRemovedUsers.add(displayValue(data.username(), "<empty-user>"));
        deltaChangedRefs.addAll(changed);
    }

    void flushPendingDelta(boolean force) {
        if (!deltaPending) return;
        if (!force && System.currentTimeMillis() - deltaLastUpdateMs < SYNC_DELTA_LOG_WINDOW_MS) return;

        logSync(
            "AutoLogin sync delta (rev=%d, by=%s): +%d [%s] -%d [%s] ~%d [%s].",
            deltaLastRevision,
            displayValue(deltaWriter, "<remote>"),
            deltaAddedCount,
            deltaAddedCount > 0 ? formatTextList(new ArrayList<>(deltaAddedUsers), 8) : "<none>",
            deltaRemovedCount,
            deltaRemovedCount > 0 ? formatTextList(new ArrayList<>(deltaRemovedUsers), 8) : "<none>",
            deltaChangedCount,
            deltaChangedCount > 0 ? formatTextList(new ArrayList<>(deltaChangedRefs), 5) : "<none>"
        );
        clearDeltaState();
    }

    void logProblem(String context, String error) {
        String safeError = error == null ? "unknown" : error;
        String signature = (context + "|" + safeError).toLowerCase(Locale.ROOT);
        long now = System.currentTimeMillis();

        if (signature.equals(lastProblemSignature) && (now - lastProblemLogMs) < SYNC_PROBLEM_LOG_COOLDOWN_MS) {
            lastProblemSuppressed++;
            return;
        }

        if (signature.equals(lastProblemSignature) && lastProblemSuppressed > 0) {
            logSync("AutoLogin sync note: same error repeated %d times.", lastProblemSuppressed);
        }

        lastProblemSignature = signature;
        lastProblemLogMs = now;
        lastProblemSuppressed = 0;
        logSync("AutoLogin sync %s: %s", context, safeError);

        String normalized = safeError.toLowerCase(Locale.ROOT);
        if (isLikelyAuthError(normalized)) {
            backoffUntilMs = Math.max(backoffUntilMs, now + SYNC_AUTH_BACKOFF_MS);
            logSync("AutoLogin sync hint: check auth-token and transport-signing-key. If one is intentionally blank, the backend must allow anonymous/unsigned sync. Preferred envs: SYNC_AUTH_TOKEN and SYNC_REQUEST_SIGNING_KEY.");
            return;
        }
        if (isLikelyBaseUrlError(normalized)) {
            backoffUntilMs = Math.max(backoffUntilMs, now + SYNC_CONFIG_BACKOFF_MS);
            logSync("AutoLogin sync hint: base-url must include scheme, e.g. http://host:7878 or https://host.");
            return;
        }
        if (isLikelyTlsCertError(normalized)) {
            backoffUntilMs = Math.max(backoffUntilMs, now + SYNC_CONFIG_BACKOFF_MS);
            logSync("AutoLogin sync hint: HTTPS certificate doesn't match URL. Use domain cert or switch to http + allow-http.");
            return;
        }
        if (isLikelyNetworkError(normalized)) {
            backoffUntilMs = Math.max(backoffUntilMs, now + SYNC_NETWORK_BACKOFF_MS);
            logSync("AutoLogin sync hint: check base-url, port/firewall and server reachability.");
        }
    }

    void clearProblemTracking() {
        lastProblemSignature = "";
        lastProblemLogMs = 0;
        lastProblemSuppressed = 0;
        backoffUntilMs = 0;
    }

    void reset() {
        flushPendingDelta(true);
        clearDeltaState();
        clearProblemTracking();
    }

    private void clearDeltaState() {
        deltaPending = false;
        deltaLastUpdateMs = 0;
        deltaLastRevision = -1;
        deltaWriter = "";
        deltaAddedCount = 0;
        deltaRemovedCount = 0;
        deltaChangedCount = 0;
        deltaAddedUsers.clear();
        deltaRemovedUsers.clear();
        deltaChangedRefs.clear();
    }

    private void logSync(String format, Object... args) {
        if (!verboseSupplier.getAsBoolean()) return;
        infoLogger.accept(args.length == 0 ? format : String.format(Locale.ROOT, format, args));
    }

    private static String displayValue(String value, String fallback) {
        if (value == null) return fallback;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? fallback : trimmed;
    }

    private static String formatTextList(List<String> values, int limit) {
        if (values.isEmpty()) return "<none>";
        int take = Math.min(Math.max(1, limit), values.size());
        String joined = String.join(", ", values.subList(0, take));
        int hidden = values.size() - take;
        return hidden > 0 ? joined + " +" + hidden + " more" : joined;
    }

    private static boolean stringsEqual(String a, String b) {
        if (a == null) return b == null;
        return a.equals(b);
    }

    private static boolean isLikelyAuthError(String normalized) {
        return normalized.contains("401") || normalized.contains("unauthorized") || normalized.contains("forbidden");
    }

    private static boolean isLikelyBaseUrlError(String normalized) {
        return normalized.contains("undefined scheme") || normalized.contains("bad-base-url")
            || normalized.contains("unsupported scheme") || normalized.contains("uri with undefined host")
            || normalized.contains("404") || normalized.contains("not-found") || normalized.contains("not_found");
    }

    private static boolean isLikelyTlsCertError(String normalized) {
        return normalized.contains("certificateexception") || normalized.contains("sslhandshakeexception")
            || normalized.contains("pkix") || normalized.contains("subject alternative names");
    }

    private static boolean isLikelyNetworkError(String normalized) {
        return normalized.contains("timeout") || normalized.contains("connect") || normalized.contains("ioexception")
            || normalized.contains("eofexception") || normalized.contains("eof reached while reading")
            || normalized.contains("unexpected end of file") || normalized.contains("connection")
            || normalized.contains("refused") || normalized.contains("reset") || normalized.contains("unreachable")
            || normalized.contains("unknownhost") || normalized.contains("noroutetohost");
    }
}
