package com.devils.addon.util.smoke;

import com.devils.addon.DevilsAddon;
import com.devils.addon.modules.stashmover.StashMover;
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

        runAndRecord(results, AssimilatedInteractionChecks.nukerPlusAccelerationModuleFlow());
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
