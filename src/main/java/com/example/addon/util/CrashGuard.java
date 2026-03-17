package com.example.addon.util;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.systems.modules.Module;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.filter.AbstractFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

public final class CrashGuard implements PreLaunchEntrypoint {
    private static final Logger LOG = LoggerFactory.getLogger("Devils/CrashGuard");
    private static final long LOG_COOLDOWN_MS = 5_000L;
    private static final Map<String, Long> lastLogByContext = new ConcurrentHashMap<>();
    private static final Map<String, String> EXPECTED_XAERO_VERSIONS = new LinkedHashMap<>();
    private static volatile boolean logFiltersInstalled = false;

    static {
        EXPECTED_XAERO_VERSIONS.put("xaerominimap", "25.3.10");
        EXPECTED_XAERO_VERSIONS.put("xaeroworldmap", "1.40.11");
        EXPECTED_XAERO_VERSIONS.put("xaeroplus", "2.30.9");
    }

    public CrashGuard() {
    }

    @Override
    public void onPreLaunch() {
        EarlyLogSpamFilter.install();
    }

    public static void installLogFilters() {
        if (logFiltersInstalled) return;

        synchronized (CrashGuard.class) {
            if (logFiltersInstalled) return;
            try {
                EarlyLogSpamFilter.install();
                logFiltersInstalled = true;
                LOG.info("[Devils][CrashGuard] Log spam filters enabled.");
            } catch (Throwable t) {
                LOG.warn("[Devils][CrashGuard] Failed to install log spam filters.", t);
            }
        }
    }

    public static void run(Module module, String context, Runnable action) {
        try {
            action.run();
        } catch (Throwable t) {
            log(module, context, t);
        }
    }

    public static void logXaeroState() {
        logXaeroMod("xaerominimap", "Xaero Minimap");
        logXaeroMod("xaeroworldmap", "Xaero WorldMap");
        logXaeroMod("xaeroplus", "XaeroPlus");
    }

    private static void log(Module module, String context, Throwable t) {
        String moduleName = module != null ? module.name : "unknown-module";
        String key = moduleName + "#" + context;
        long now = System.currentTimeMillis();
        Long last = lastLogByContext.get(key);

        if (last == null || now - last >= LOG_COOLDOWN_MS) {
            lastLogByContext.put(key, now);
            LOG.error("[Devils][{}] Unhandled exception in {}.", moduleName, context, t);
        }
    }

    private static void logXaeroMod(String modId, String display) {
        Optional<ModContainer> optional = FabricLoader.getInstance().getModContainer(modId);
        if (optional.isEmpty()) {
            AddonTemplate.LOG.warn("[Devils/Xaero] {} is not loaded.", display);
            return;
        }

        ModContainer container = optional.get();
        String version = container.getMetadata().getVersion().getFriendlyString();
        String origins = stringifyOrigins(container);
        boolean embedded = isEmbeddedMod(container) || isEmbeddedOrigin(origins);
        String expected = EXPECTED_XAERO_VERSIONS.getOrDefault(modId, "");

        AddonTemplate.LOG.info(
            "[Devils/Xaero] {} loaded: version={} source={} origin={}",
            display,
            version,
            embedded ? "embedded" : "external",
            origins
        );

        if (!expected.isBlank() && !version.startsWith(expected)) {
            AddonTemplate.LOG.warn(
                "[Devils/Xaero] {} version mismatch. Loaded={}, expected={} for Devils sync bridge.",
                display,
                version,
                expected
            );
        }

        if (!embedded) {
            AddonTemplate.LOG.warn(
                "[Devils/Xaero] External {} detected. Fabric resolves root mods before nested jars; remove standalone {} jar to force embedded Devils stack.",
                display,
                display
            );
        }
    }

    private static String stringifyOrigins(ModContainer container) {
        try {
            List<Path> paths = container.getOrigin().getPaths();
            if (paths == null || paths.isEmpty()) return "unknown";
            return paths.stream()
                .map(path -> path == null ? "" : path.toAbsolutePath().normalize().toString())
                .collect(Collectors.joining(";"));
        } catch (Throwable ignored) {
            return "unknown";
        }
    }

    private static boolean isEmbeddedOrigin(String origins) {
        String value = origins == null ? "" : origins.toLowerCase(Locale.ROOT);
        return value.contains("meta-inf/jars") || value.contains("meta-inf\\jars");
    }

    private static boolean isEmbeddedMod(ModContainer container) {
        if (container == null) return false;
        try {
            Optional<ModContainer> parent = container.getContainingMod();
            if (parent.isEmpty()) return false;
            String parentId = parent.get().getMetadata().getId();
            return "devils-addon".equalsIgnoreCase(parentId);
        } catch (Throwable ignored) {
            return false;
        }
    }
}

final class EarlyLogSpamFilter {
    private static final String FONT_WARN_PREFIX = "Failed to load system font ";
    private static final String ENTITY_CLASS_WARN_PREFIX = "Unable to get entity class for \"";
    private static final long SUMMARY_COOLDOWN_MS = 10_000L;
    private static final AtomicLong suppressedFontWarns = new AtomicLong();
    private static final AtomicLong suppressedEntityWarns = new AtomicLong();
    private static final Object summaryLock = new Object();
    private static volatile long lastSummaryMs;
    private static volatile boolean installed;

    private EarlyLogSpamFilter() {
    }

    static void install() {
        if (installed) return;

        synchronized (EarlyLogSpamFilter.class) {
            if (installed) return;
            try {
                LoggerContext context = (LoggerContext) LogManager.getContext(false);
                Configuration configuration = context.getConfiguration();
                LoggerConfig root = configuration.getRootLogger();
                root.addFilter(new SpamFilter());
                context.updateLoggers();
                installed = true;
            } catch (Throwable ignored) {
            }
        }
    }

    private static void maybeLogSummary(long nowMs) {
        synchronized (summaryLock) {
            if (nowMs - lastSummaryMs < SUMMARY_COOLDOWN_MS) return;
            long fonts = suppressedFontWarns.getAndSet(0L);
            long entities = suppressedEntityWarns.getAndSet(0L);
            if (fonts <= 0L && entities <= 0L) return;

            lastSummaryMs = nowMs;
            LoggerContext context = (LoggerContext) LogManager.getContext(false);
            context.getLogger("Devils/CrashGuard").warn(
                "[Devils][CrashGuard] Suppressed repeated warnings: fonts={}, entityClassLookup={}.",
                fonts,
                entities
            );
        }
    }

    private static final class SpamFilter extends AbstractFilter {
        @Override
        public Result filter(LogEvent event) {
            if (event == null || event.getMessage() == null) return Result.NEUTRAL;

            String message = event.getMessage().getFormattedMessage();
            long now = System.currentTimeMillis();

            if (message != null && message.startsWith(FONT_WARN_PREFIX)) {
                suppressedFontWarns.incrementAndGet();
                maybeLogSummary(now);
                return Filter.Result.DENY;
            }

            if (message != null && message.startsWith(ENTITY_CLASS_WARN_PREFIX)) {
                suppressedEntityWarns.incrementAndGet();
                maybeLogSummary(now);
                return Filter.Result.DENY;
            }

            maybeLogSummary(now);
            return Filter.Result.NEUTRAL;
        }
    }
}

