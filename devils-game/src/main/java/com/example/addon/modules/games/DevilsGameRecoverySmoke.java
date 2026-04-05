package com.example.addon.modules.games;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class DevilsGameRecoverySmoke {
    private static final Logger LOG = LoggerFactory.getLogger("DevilsGame/RecoverySmoke");
    private static final String ENABLE_PROPERTY = "devils.game.recovery.smoke";
    private static final String OUTPUT_PATH_PROPERTY = "devils.game.recovery.smoke.path";
    private static final int START_DELAY_TICKS = 60;

    private static boolean installed;
    private static boolean completed;
    private static int ticksRemaining;
    private static Path outputPath;

    private DevilsGameRecoverySmoke() {
    }

    public static void install() {
        if (installed || !Boolean.getBoolean(ENABLE_PROPERTY)) return;
        installed = true;
        outputPath = resolveOutputPath();

        ClientLifecycleEvents.CLIENT_STARTED.register(client -> {
            ticksRemaining = START_DELAY_TICKS;
            resetLog(outputPath);
            appendLine("SUMMARY devils-game-recovery-smoke started=" + Instant.now());
        });
        ClientTickEvents.END_CLIENT_TICK.register(DevilsGameRecoverySmoke::tick);
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
        List<String> failures = new ArrayList<>();
        Modules modules = Modules.get();
        appendLine("RUNTIME screen=" + safeClassName(client.currentScreen) + " worldLoaded=" + (client.world != null));

        if (modules == null) {
            failures.add("modules registry unavailable");
        } else {
            verifyPresent(modules, CheckersOverlay.class, failures);
            verifyPresent(modules, ChessOverlay.class, failures);
            verifyPresent(modules, SlotMachineOverlay.class, failures);
            verifyPresent(modules, BlackjackOverlay.class, failures);
            verifyPresent(modules, RussianRouletteOverlay.class, failures);
            verifyPresent(modules, DoomOverlay.class, failures);

            Module rouletteModule = modules.get(RussianRouletteOverlay.class);
            if (rouletteModule != null) {
                GameLaunchCoordinator.launch(GameLaunchCoordinator.Entry.RUSSIAN_ROULETTE);
                boolean rouletteActive = rouletteModule.isActive();
                appendLine((rouletteActive ? "PASS " : "FAIL ") + "russian-roulette-launch active=" + rouletteActive);
                if (!rouletteActive) failures.add("russian roulette launch path did not activate module");

                GameLaunchCoordinator.launch(GameLaunchCoordinator.Entry.CHECKERS);
                Module checkersModule = modules.get(CheckersOverlay.class);
                boolean checkersActive = checkersModule != null && checkersModule.isActive();
                boolean rouletteClosed = !rouletteModule.isActive();
                appendLine(((checkersActive && rouletteClosed) ? "PASS " : "FAIL ")
                    + "game-regression-guard checkersActive=" + checkersActive + " rouletteClosed=" + rouletteClosed);
                if (!checkersActive || !rouletteClosed) {
                    failures.add("coordinator regression guard failed after switching from roulette to checkers");
                }

                GameLaunchCoordinator.closeAll();
                boolean closeAllCleared = !rouletteModule.isActive() && (checkersModule == null || !checkersModule.isActive());
                appendLine((closeAllCleared ? "PASS " : "FAIL ") + "game-close-all closeAllCleared=" + closeAllCleared);
                if (!closeAllCleared) failures.add("closeAll did not shut down active game overlays");
            }
        }

        boolean success = failures.isEmpty();
        if (!success) {
            failures.forEach(failure -> appendLine("FAIL detail=" + failure));
        }
        appendLine("RESULT " + (success ? "PASS" : "FAIL") + " checks=" + (success ? 9 : 9 - failures.size()) + " failures=" + failures.size());
        appendLine("SUMMARY devils-game-recovery-smoke finished=" + Instant.now());

        try {
            client.scheduleStop();
        } catch (Throwable t) {
            LOG.warn("Failed to schedule devils-game recovery smoke stop.", t);
        }
    }

    private static void verifyPresent(Modules modules, Class<? extends Module> moduleClass, List<String> failures) {
        Module module = modules.get(moduleClass);
        boolean present = module != null;
        appendLine((present ? "PASS " : "FAIL ") + "registry-" + moduleClass.getSimpleName() + " present=" + present);
        if (!present) failures.add("missing registered module " + moduleClass.getSimpleName());
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
            LOG.warn("Failed to reset devils-game recovery smoke log at {}", path, e);
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
            LOG.warn("Failed to append devils-game recovery smoke evidence.", e);
        }
    }

    private static String safeClassName(Object value) {
        return value == null ? "null" : value.getClass().getName();
    }
}
