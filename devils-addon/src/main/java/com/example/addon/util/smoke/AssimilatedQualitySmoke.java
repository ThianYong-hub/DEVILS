package com.example.addon.util.smoke;

import com.example.addon.AddonTemplate;
import com.example.addon.modules.stashmover.StashMover;
import com.example.addon.util.xaerosync.XaeroWaypointContext;
import com.example.addon.util.xaerosync.XaeroWaypointManagedWaypoints;
import com.example.addon.util.xaerosync.XaeroWaypointReflection;
import com.example.addon.util.xaerosync.XaeroWaypointVisibility;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import red.jackf.chesttracker.api.memory.Memory;
import red.jackf.chesttracker.impl.ChestTracker;
import red.jackf.chesttracker.impl.config.ChestTrackerConfig;
import red.jackf.chesttracker.impl.memory.MemoryBankImpl;
import red.jackf.chesttracker.impl.memory.MemoryKeyImpl;
import red.jackf.chesttracker.impl.memory.metadata.Metadata;
import red.jackf.chesttracker.impl.storage.backend.JsonBackend;
import xaeroplus.XaeroPlus;
import xaeroplus.settings.BooleanSetting;
import xaeroplus.settings.SettingHooks;
import xaeroplus.settings.Settings;

public final class AssimilatedQualitySmoke {
    private static final Logger LOG = LoggerFactory.getLogger("Devils/QualitySmoke");
    private static final String ENABLE_PROPERTY = "devils.assimilated.quality.smoke";
    private static final String OUTPUT_PATH_PROPERTY = "devils.runtime.smoke.path";
    private static final int START_DELAY_TICKS = 60;

    private static boolean installed;
    private static boolean completed;
    private static int ticksRemaining;
    private static Path outputPath;

    private AssimilatedQualitySmoke() {
    }

    public static void install() {
        if (installed || !Boolean.getBoolean(ENABLE_PROPERTY)) return;
        installed = true;
        outputPath = resolveOutputPath();

        ClientLifecycleEvents.CLIENT_STARTED.register(client -> {
            ticksRemaining = START_DELAY_TICKS;
            resetLog(outputPath);
        });
        ClientTickEvents.END_CLIENT_TICK.register(AssimilatedQualitySmoke::tick);
    }

    private static void tick(MinecraftClient client) {
        if (!installed || completed) return;
        if (ticksRemaining > 0) {
            ticksRemaining--;
            return;
        }

        completed = true;
        client.execute(() -> runChecks(client));
    }

    private static void runChecks(MinecraftClient client) {
        List<SmokeCheckResult> results = new ArrayList<>();
        appendLine("SUMMARY quality-smoke started=" + Instant.now());
        appendLine("RUNTIME screen=" + safeClassName(client.currentScreen) + " worldLoaded=" + (client.world != null));

        runAndRecord(results, AssimilatedInteractionChecks.chestTrackerModuleSettingsRoundTripFlow());
        runAndRecord(results, chestTrackerBackendRoundTrip());
        runAndRecord(results, AssimilatedInteractionChecks.chestTrackerGuiEditFlow(client));
        runAndRecord(results, AssimilatedInteractionChecks.chestTrackerPointerDrivenSettingsFlow(client));
        runAndRecord(results, AssimilatedInteractionChecks.whereIsItFocusedSlotUiFlow(client));
        runAndRecord(results, AssimilatedInteractionChecks.whereIsItRenderedSearchFlow(client));
        runAndRecord(results, AssimilatedInteractionChecks.searchablesFlow());
        runAndRecord(results, AssimilatedInteractionChecks.yaclOptionLifecycle());
        runAndRecord(results, xaeroPlusSettingsRoundTrip());
        runAndRecord(results, xaeroVisibilityAndRefreshFlow());
        runAndRecord(results, AssimilatedInteractionChecks.xaeroWaypointGuiCreateFlow(client));
        runAndRecord(results, AssimilatedInteractionChecks.xaeroWaypointListEditFlow(client));
        runAndRecord(results, AssimilatedInteractionChecks.xaeroPlusCopyCoordinatesHookFlow(client));
        runAndRecord(results, AssimilatedInteractionChecks.xaeroPlusPortalsFeatureFlow());
        runAndRecord(results, stashMoverNativeIdleFlow());

        boolean success = results.stream().allMatch(SmokeCheckResult::success);
        appendLine("RESULT " + (success ? "PASS" : "FAIL") + " checks=" + results.size());
        appendLine("SUMMARY quality-smoke finished=" + Instant.now());

        try {
            client.scheduleStop();
        } catch (Throwable t) {
            LOG.warn("Failed to schedule smoke client stop.", t);
        }
    }

    private static SmokeCheckResult chestTrackerBackendRoundTrip() {
        String bankId = "quality-smoke-" + UUID.randomUUID().toString().replace("-", "");
        JsonBackend backend = new JsonBackend();
        ChestTrackerConfig config = null;
        Boolean previousAsyncSaving = null;

        try {
            if (ChestTrackerConfig.INSTANCE != null && ChestTrackerConfig.INSTANCE.instance() instanceof ChestTrackerConfig liveConfig) {
                config = liveConfig;
                previousAsyncSaving = liveConfig.storage.AsyncSaving;
                liveConfig.storage.AsyncSaving = false;
            }

            Memory memory = new Memory(
                List.of(new ItemStack(Items.ENDER_CHEST, 1)),
                Text.literal("Backend Smoke"),
                List.of(),
                Optional.empty(),
                3L,
                7L,
                Instant.EPOCH,
                null,
                null
            );
            MemoryKeyImpl key = new MemoryKeyImpl(Map.of(new BlockPos(4, 64, 4), memory), Map.of());
            MemoryBankImpl bank = new MemoryBankImpl(Metadata.blankWithName("Backend Smoke"), new java.util.HashMap<>(Map.of(Identifier.of("devils", "backend_smoke"), key)));
            bank.setId(bankId);

            if (!backend.save(bank, null)) return SmokeCheckResult.fail("chesttracker-backend", "save returned false");
            MemoryBankImpl loaded = backend.load(bankId, null);
            if (loaded == null) return SmokeCheckResult.fail("chesttracker-backend", "load returned null");

            Memory loadedMemory = loaded.getMemories().get(Identifier.of("devils", "backend_smoke")).get(new BlockPos(4, 64, 4)).orElse(null);
            if (loadedMemory == null || loadedMemory.items().isEmpty() || !loadedMemory.items().get(0).isOf(Items.ENDER_CHEST)) {
                return SmokeCheckResult.fail("chesttracker-backend", "loaded backend payload mismatch");
            }

            return SmokeCheckResult.pass(
                "chesttracker-backend",
                "storageDir=" + red.jackf.chesttracker.impl.util.Constants.STORAGE_DIR + " loadedItems=" + loadedMemory.items().size() + " logger=" + ChestTracker.LOGGER.getName()
            );
        } catch (Throwable t) {
            return SmokeCheckResult.fail("chesttracker-backend", t.getClass().getSimpleName() + ": " + t.getMessage());
        } finally {
            try {
                backend.delete(bankId);
            } catch (Throwable ignored) {
            }
            if (config != null && previousAsyncSaving != null) {
                config.storage.AsyncSaving = previousAsyncSaving;
            }
        }
    }

    private static SmokeCheckResult xaeroPlusSettingsRoundTrip() {
        try {
            if (!XaeroPlus.initialized.get()) return SmokeCheckResult.fail("xaeroplus-settings", "XaeroPlus not initialized");

            BooleanSetting setting = Settings.REGISTRY.transparentWorldmapBackgroundSetting;
            boolean original = setting.get();
            boolean toggled = !original;
            setting.setValue(toggled);
            SettingHooks.saveXPSettings();

            setting.setValue(original);
            SettingHooks.loadXPSettings();
            boolean reloaded = setting.get();

            setting.setValue(original);
            SettingHooks.saveXPSettings();

            if (reloaded != toggled) {
                return SmokeCheckResult.fail("xaeroplus-settings", "save/load roundtrip did not restore toggled value");
            }

            return SmokeCheckResult.pass(
                "xaeroplus-settings",
                "configFile=" + XaeroPlus.configFile.getAbsolutePath() + " toggled=" + toggled + " restored=" + original
            );
        } catch (Throwable t) {
            return SmokeCheckResult.fail("xaeroplus-settings", t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private static SmokeCheckResult xaeroVisibilityAndRefreshFlow() {
        try {
            Object worldMapInstance = XaeroWaypointReflection.readStaticField("xaero.map.WorldMap", "INSTANCE");
            Object hudInstance = XaeroWaypointReflection.readStaticField("xaero.common.HudMod", "INSTANCE");
            if (worldMapInstance == null) return SmokeCheckResult.fail("xaero-visibility", "world map instance unavailable");
            if (hudInstance == null) return SmokeCheckResult.fail("xaero-visibility", "minimap instance unavailable");

            XaeroWaypointContext context = new XaeroWaypointContext();
            List<String> debugLines = new ArrayList<>();
            XaeroWaypointContext.setListener(context, debugLines::add);
            XaeroWaypointVisibility.ensureWaypointsVisible(context);

            String refreshResult = XaeroWaypointManagedWaypoints.requestWaypointsRefresh();
            if (!context.waypointVisibilityEnforced) {
                return SmokeCheckResult.fail(
                    "xaero-visibility",
                    "visibility not enforced issue=" + context.lastWaypointVisibilityIssue + " refresh=" + refreshResult
                );
            }
            if (!(refreshResult.startsWith("method:") || refreshResult.startsWith("field:"))) {
                return SmokeCheckResult.fail("xaero-visibility", "refresh hook unavailable: " + refreshResult);
            }

            return SmokeCheckResult.pass(
                "xaero-visibility",
                String.format(
                    Locale.ROOT,
                    "worldMap=%s minimap=%s enforced=%s refresh=%s debug=%s",
                    safeClassName(worldMapInstance),
                    safeClassName(hudInstance),
                    context.waypointVisibilityEnforced,
                    refreshResult,
                    debugLines.isEmpty() ? "none" : String.join(" || ", debugLines)
                )
            );
        } catch (Throwable t) {
            return SmokeCheckResult.fail("xaero-visibility", t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private static void runAndRecord(List<SmokeCheckResult> results, SmokeCheckResult result) {
        results.add(result);
        appendLine((result.success() ? "PASS " : "FAIL ") + result.id() + " " + result.detail());
    }

    private static SmokeCheckResult stashMoverNativeIdleFlow() {
        try {
            StashMover module = Modules.get().get(StashMover.class);
            if (module == null) return SmokeCheckResult.fail("stashmover-native-idle", "module not registered");

            String modes = java.util.Arrays.toString(StashMover.Mode.values());
            boolean toggledActive;
            if (module.isActive()) {
                toggledActive = true;
                module.toggle();
            } else {
                module.toggle();
                toggledActive = module.isActive();
                module.toggle();
            }

            if (module.isActive()) {
                module.toggle();
                return SmokeCheckResult.fail("stashmover-native-idle", "module did not cleanly toggle back off");
            }

            return SmokeCheckResult.pass(
                "stashmover-native-idle",
                "registered=true toggleRoundTrip=" + toggledActive + " modes=" + modes + " modeNow=" + module.modeValue()
            );
        } catch (Throwable t) {
            return SmokeCheckResult.fail("stashmover-native-idle", t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private static Path resolveOutputPath() {
        String configured = System.getProperty(OUTPUT_PATH_PROPERTY, "").trim();
        if (!configured.isBlank()) return Path.of(configured).toAbsolutePath().normalize();
        return Path.of("runtime-smoke.log").toAbsolutePath().normalize();
    }

    private static void resetLog(Path path) {
        try {
            Path parent = path.getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.writeString(path, "", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            LOG.warn("Failed to reset runtime smoke log at {}", path, e);
        }
    }

    private static void appendLine(String line) {
        if (outputPath == null || line == null) return;
        try {
            Path parent = outputPath.getParent();
            if (parent != null) Files.createDirectories(parent);
            try (BufferedWriter writer = Files.newBufferedWriter(
                outputPath,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND
            )) {
                writer.write(line);
                writer.newLine();
            }
        } catch (IOException e) {
            LOG.warn("Failed to append runtime smoke evidence line.", e);
        }
    }

    private static String safeClassName(Object value) {
        return value == null ? "null" : value.getClass().getName();
    }
}
