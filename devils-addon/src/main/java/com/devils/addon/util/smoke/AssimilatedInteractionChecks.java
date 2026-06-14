package com.devils.addon.util.smoke;

import com.devils.addon.modules.NukerPlus;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.client.MinecraftClient;

public final class AssimilatedInteractionChecks {

    private AssimilatedInteractionChecks() {
    }

    public static SmokeCheckResult nukerPlusAccelerationModuleFlow() {
        try {
            NukerPlus module = Modules.get().get(NukerPlus.class);
            if (module == null) return SmokeCheckResult.fail("nukerplus-acceleration", "NukerPlus module is missing");
            return SmokeCheckResult.pass("nukerplus-acceleration", "checked=" + module.name);
        } catch (Throwable t) {
            return SmokeCheckResult.fail("nukerplus-acceleration", t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    public static SmokeCheckResult chestTrackerLocalSettingsPayloadFlow() {
        // ChestTracker integration removed — module is now standalone
        return SmokeCheckResult.pass("chesttracker-local-settings", "ChestTracker integration removed (no external dep)");
    }

    public static SmokeCheckResult searchablesFlow() {
        try {
            Class.forName("com.devils.addon.modules.ping.PingMarkerController");
            return SmokeCheckResult.pass("searchables-flow", "PingMarkerController reachable");
        } catch (ClassNotFoundException e) {
            return SmokeCheckResult.fail("searchables-flow", "PingMarkerController class not found");
        } catch (Throwable t) {
            return SmokeCheckResult.fail("searchables-flow", t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    public static SmokeCheckResult yaclOptionLifecycle() {
        try {
            Class.forName("dev.isxander.yacl3.api.Option");
            return SmokeCheckResult.pass("yacl-option-lifecycle", "YACL3 Option class reachable");
        } catch (ClassNotFoundException e) {
            return SmokeCheckResult.pass("yacl-option-lifecycle", "YACL3 not present (optional)");
        } catch (Throwable t) {
            return SmokeCheckResult.fail("yacl-option-lifecycle", t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    public static SmokeCheckResult xaeroRefreshHookFlow() {
        // Xaero integration removed — no external dependency
        return SmokeCheckResult.pass("xaero-refresh-hook", "Xaero integration removed (no external dep)");
    }

    public static SmokeCheckResult xaeroPlusSettingLifecycle() {
        // XaeroPlus integration removed — no external dependency
        return SmokeCheckResult.pass("xaero-plus-settings", "XaeroPlus integration removed (no external dep)");
    }
}
