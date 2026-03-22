package com.example.addon.util;

import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

public final class PrismLauncherControl {
    private static final String KEY_CLOSE_AFTER_LAUNCH = "CloseAfterLaunch";
    private static final String KEY_QUIT_AFTER_GAME_STOP = "QuitAfterGameStop";

    private PrismLauncherControl() {
    }

    public record ApplyResult(boolean ok, Path prismRoot, Path configPath, String message) {
    }

    public record RestartResult(boolean ok, Path prismRoot, String message) {
    }

    public static ApplyResult ensureMultiSessionFlags(Path minecraftRunDir) {
        Path prismRoot = detectPrismRoot(minecraftRunDir);
        if (prismRoot == null) {
            return new ApplyResult(false, null, null, "Prism root not found.");
        }

        Path configPath = prismRoot.resolve("prismlauncher.cfg");
        try {
            boolean globalChanged = applyFlagsToConfig(configPath);
            int changedInstances = applyFlagsToInstances(prismRoot);
            String message = "Prism multi-session flags ready. globalChanged="
                + globalChanged
                + " instanceCfgUpdated="
                + changedInstances;
            return new ApplyResult(true, prismRoot, configPath, message);
        } catch (Exception e) {
            return new ApplyResult(false, prismRoot, configPath, "Failed to update Prism config: " + e.getMessage());
        }
    }

    public static RestartResult restartLauncherForParallelSameInstance(Path minecraftRunDir) {
        Path prismRoot = detectPrismRoot(minecraftRunDir);
        if (prismRoot == null) {
            return new RestartResult(false, null, "Prism root not found.");
        }

        String executable = resolvePrismExecutable(prismRoot);
        try {
            Process kill = new ProcessBuilder("cmd", "/c", "taskkill /F /IM prismlauncher.exe").start();
            kill.waitFor(5, TimeUnit.SECONDS);
            Thread.sleep(350L);

            if (!startPrismInBackground(executable, prismRoot)) {
                return new RestartResult(false, prismRoot, "Failed to start Prism in background.");
            }
            return new RestartResult(true, prismRoot, "Prism launcher restarted in background for parallel same-instance launch.");
        } catch (Exception e) {
            return new RestartResult(false, prismRoot, "Failed to restart Prism: " + e.getMessage());
        }
    }

    private static Path detectPrismRoot(Path minecraftRunDir) {
        Path byRunDir = detectByRunDirectory(minecraftRunDir);
        if (byRunDir != null) return byRunDir;

        String appData = System.getenv("APPDATA");
        if (appData != null && !appData.isBlank()) {
            Path path = Path.of(appData).resolve("PrismLauncher");
            if (Files.exists(path)) return path;
        }

        String userHome = System.getProperty("user.home");
        if (userHome != null && !userHome.isBlank()) {
            Path path = Path.of(userHome, "AppData", "Roaming", "PrismLauncher");
            if (Files.exists(path)) return path;
        }

        return null;
    }

    private static Path detectByRunDirectory(Path minecraftRunDir) {
        if (minecraftRunDir == null) return null;
        Path run = minecraftRunDir.toAbsolutePath().normalize();

        Path current = run;
        if (current.getFileName() != null && current.getFileName().toString().equalsIgnoreCase("minecraft")) {
            current = current.getParent();
        }
        if (current == null) return null;

        Path instancesDir = current.getParent();
        if (instancesDir == null || instancesDir.getFileName() == null) return null;
        if (!instancesDir.getFileName().toString().equalsIgnoreCase("instances")) return null;

        Path prismRoot = instancesDir.getParent();
        if (prismRoot == null) return null;
        if (Files.exists(prismRoot.resolve("prismlauncher.cfg"))) return prismRoot;
        if (Files.exists(prismRoot.resolve("instances"))) return prismRoot;
        return prismRoot;
    }

    private static boolean applyFlagsToConfig(Path configPath) throws Exception {
        List<String> lines = Files.exists(configPath)
            ? Files.readAllLines(configPath, StandardCharsets.UTF_8)
            : new ArrayList<>();

        boolean changed = false;
        changed |= upsertBoolean(lines, KEY_CLOSE_AFTER_LAUNCH, false);
        changed |= upsertBoolean(lines, KEY_QUIT_AFTER_GAME_STOP, false);

        if (!Files.exists(configPath.getParent())) Files.createDirectories(configPath.getParent());
        Files.write(configPath, lines, StandardCharsets.UTF_8);
        return changed;
    }

    private static int applyFlagsToInstances(Path prismRoot) {
        Path instancesRoot = prismRoot.resolve("instances");
        if (!Files.exists(instancesRoot) || !Files.isDirectory(instancesRoot)) return 0;

        int changedConfigs = 0;
        try (DirectoryStream<Path> dirs = Files.newDirectoryStream(instancesRoot)) {
            for (Path dir : dirs) {
                if (!Files.isDirectory(dir)) continue;
                Path instanceCfg = dir.resolve("instance.cfg");
                if (!Files.exists(instanceCfg) || !Files.isRegularFile(instanceCfg)) continue;

                try {
                    List<String> lines = Files.readAllLines(instanceCfg, StandardCharsets.UTF_8);
                    boolean changed = false;
                    changed |= upsertBoolean(lines, KEY_CLOSE_AFTER_LAUNCH, false);
                    changed |= upsertBoolean(lines, KEY_QUIT_AFTER_GAME_STOP, false);
                    if (changed) {
                        Files.write(instanceCfg, lines, StandardCharsets.UTF_8);
                        changedConfigs++;
                    }
                } catch (Exception ignored) {
                }
            }
        } catch (Exception ignored) {
        }
        return changedConfigs;
    }

    private static String resolvePrismExecutable(Path prismRoot) {
        String fromEnv = System.getenv("PRISM_LAUNCHER_EXE");
        if (fromEnv != null && !fromEnv.isBlank() && Files.exists(Path.of(fromEnv))) return fromEnv;

        List<Path> candidates = new ArrayList<>();
        candidates.add(prismRoot.resolve("prismlauncher.exe"));

        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData != null && !localAppData.isBlank()) {
            candidates.add(Path.of(localAppData, "Programs", "PrismLauncher", "prismlauncher.exe"));
        }

        String programFiles = System.getenv("ProgramFiles");
        if (programFiles != null && !programFiles.isBlank()) {
            candidates.add(Path.of(programFiles, "PrismLauncher", "prismlauncher.exe"));
        }

        String programFilesX86 = System.getenv("ProgramFiles(x86)");
        if (programFilesX86 != null && !programFilesX86.isBlank()) {
            candidates.add(Path.of(programFilesX86, "PrismLauncher", "prismlauncher.exe"));
        }

        for (Path candidate : candidates) {
            if (candidate != null && Files.exists(candidate)) return candidate.toString();
        }

        return "prismlauncher.exe";
    }

    private static boolean startPrismInBackground(String executable, Path prismRoot) {
        if (executable == null || executable.isBlank() || prismRoot == null) return false;

        if (!isWindows()) {
            try {
                new ProcessBuilder(executable, "--dir", prismRoot.toString()).start();
                return true;
            } catch (Exception ignored) {
                return false;
            }
        }

        String exeQuoted = powershellSingleQuoted(executable);
        String dirQuoted = powershellSingleQuoted(prismRoot.toString());
        String psScript = "$ErrorActionPreference='Stop'; "
            + "Start-Process -FilePath '" + exeQuoted + "' "
            + "-ArgumentList @('--dir','" + dirQuoted + "') "
            + "-WindowStyle Hidden";

        try {
            new ProcessBuilder("powershell", "-NoProfile", "-WindowStyle", "Hidden", "-Command", psScript).start();
            return true;
        } catch (Exception ignored) {
        }

        try {
            String cmd = "start \"\" /min \"" + executable + "\" --dir \"" + prismRoot + "\"";
            new ProcessBuilder("cmd", "/c", cmd).start();
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean isWindows() {
        String os = System.getProperty("os.name");
        return os != null && os.toLowerCase(Locale.ROOT).contains("win");
    }

    private static String powershellSingleQuoted(String value) {
        if (value == null) return "";
        return value.replace("'", "''");
    }

    private static boolean upsertBoolean(List<String> lines, String key, boolean value) {
        String desired = key + "=" + String.valueOf(value).toLowerCase(Locale.ROOT);
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line == null) continue;
            if (line.trim().startsWith(key + "=")) {
                if (!line.equals(desired)) {
                    lines.set(i, desired);
                    return true;
                }
                return false;
            }
        }

        lines.add(desired);
        return true;
    }
}
