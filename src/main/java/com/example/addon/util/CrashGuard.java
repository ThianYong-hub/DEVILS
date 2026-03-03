package com.example.addon.util;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.systems.modules.Module;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class CrashGuard {
    private static final long LOG_COOLDOWN_MS = 5_000L;
    private static final Map<String, Long> lastLogByContext = new ConcurrentHashMap<>();

    private CrashGuard() {
    }

    public static void run(Module module, String context, Runnable action) {
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
            AddonTemplate.LOG.error("[Devils][{}] Unhandled exception in {}.", moduleName, context, t);
        }
    }
}
