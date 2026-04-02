package com.example.addon.modules.games;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import meteordevelopment.meteorclient.systems.modules.Module;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class GameCrashGuard {
    private static final Logger LOG = LoggerFactory.getLogger("DevilsGame/CrashGuard");
    private static final long LOG_COOLDOWN_MS = 5_000L;
    private static final Map<String, Long> lastLogByContext = new ConcurrentHashMap<>();

    private GameCrashGuard() {
    }

    static void run(Module module, String context, Runnable action) {
        try {
            action.run();
        } catch (Throwable t) {
            log(module, context, t);
        }
    }

    private static void log(Module module, String context, Throwable t) {
        String moduleName = module != null ? module.name : "unknown-module";
        String key = moduleName + "#" + context;
        long now = System.currentTimeMillis();
        Long last = lastLogByContext.get(key);
        if (last == null || now - last >= LOG_COOLDOWN_MS) {
            lastLogByContext.put(key, now);
            LOG.error("[DevilsGame][{}] Unhandled exception in {}.", moduleName, context, t);
        }
    }
}
