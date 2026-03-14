package com.example.addon.util;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.filter.AbstractFilter;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Minimal prelaunch-safe log filter.
 * Must not reference Minecraft, Meteor or addon bootstrap classes.
 */
public final class EarlyLogSpamFilter {
    private static final String FONT_WARN_PREFIX = "Failed to load system font ";
    private static final String ENTITY_CLASS_WARN_PREFIX = "Unable to get entity class for \"";
    private static final long SUMMARY_COOLDOWN_MS = 10_000L;
    private static final AtomicLong suppressedFontWarns = new AtomicLong();
    private static final AtomicLong suppressedEntityWarns = new AtomicLong();
    private static final Object summaryLock = new Object();
    private static volatile long lastSummaryMs = 0L;
    private static volatile boolean installed = false;

    private EarlyLogSpamFilter() {
    }

    public static void install() {
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
                // Never crash prelaunch because of logging filters.
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
                fonts, entities
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
