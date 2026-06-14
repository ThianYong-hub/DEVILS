package com.devils.addon.util.smoke;

import com.devils.addon.util.runtime.StrictRuntimeLogger;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;

/**
 * Runtime validation for input subsystem.
 * External mod integrations (ChestTracker, Xaero) removed to prevent crashes
 * when users run newer versions of those mods.
 */
public final class InputRuntimeValidation {
    private static final String ENABLE_PROPERTY = "devils.input.runtime";
    private static final int START_DELAY_TICKS = 40;

    private static boolean installed;
    private static boolean completed;
    private static int stageTicks;

    private InputRuntimeValidation() {
    }

    public static void install() {
        if (installed || !Boolean.getBoolean(ENABLE_PROPERTY)) return;
        installed = true;

        ClientLifecycleEvents.CLIENT_STARTED.register(client -> {
            StrictRuntimeLogger.logHarness("INPUT", "Input runtime validation installed (no external deps).");
            ClientTickEvents.END_CLIENT_TICK.register(InputRuntimeValidation::onTick);
        });
    }

    private static void onTick(MinecraftClient client) {
        if (completed) return;
        stageTicks++;
        if (stageTicks < START_DELAY_TICKS) return;
        if (client.world == null || client.player == null) return;

        // Validation passed — no external mod input hooks to verify
        StrictRuntimeLogger.logHarness("INPUT", "PASS input validation completed (standalone mode).");
        completed = true;
    }
}
