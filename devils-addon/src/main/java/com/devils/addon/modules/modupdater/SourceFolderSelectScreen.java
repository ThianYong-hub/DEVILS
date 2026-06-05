package com.devils.addon.modules.modupdater;

import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.WindowScreen;
import meteordevelopment.meteorclient.gui.widgets.containers.WHorizontalList;
import meteordevelopment.meteorclient.gui.widgets.containers.WTable;
import meteordevelopment.meteorclient.gui.widgets.input.WTextBox;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.stream.Stream;

final class SourceFolderSelectScreen extends WindowScreen {
    private final Consumer<Path> onSelect;
    private final Path appDataDir;
    private final Path modsDir;

    private Path currentDir;
    private WTable table;
    private String filterText = "";

    SourceFolderSelectScreen(
        GuiTheme theme,
        Path startDir,
        Path appDataDir,
        Path modsDir,
        Consumer<Path> onSelect
    ) {
        super(theme, "Select Source Folder");
        this.onSelect = onSelect;

        Path homeFallback = fallbackDirectory();
        this.appDataDir = sanitizeDirectory(appDataDir, homeFallback);
        this.modsDir = sanitizeDirectory(modsDir, this.appDataDir);
        this.currentDir = sanitizeDirectory(startDir, this.modsDir);
    }

    @Override
    public void initWidgets() {
        WHorizontalList controls = add(theme.horizontalList()).expandX().widget();

        WButton useCurrent = controls.add(theme.button("Use Current Folder")).expandX().widget();
        useCurrent.action = () -> applyDirectory(currentDir);

        WButton up = controls.add(theme.button("Up")).widget();
        up.action = () -> {
            Path parent = currentDir == null ? null : currentDir.getParent();
            if (parent == null) return;
            currentDir = parent;
            rebuildTable();
        };

        WButton jumpAppData = controls.add(theme.button("AppData")).widget();
        jumpAppData.action = () -> {
            currentDir = appDataDir;
            rebuildTable();
        };

        WButton jumpMods = controls.add(theme.button("Game Mods")).widget();
        jumpMods.action = () -> {
            currentDir = modsDir;
            rebuildTable();
        };

        WTextBox filter = add(theme.textBox("")).minWidth(460).expandX().widget();
        filter.setFocused(true);
        filter.action = () -> {
            filterText = filter.get().trim().toLowerCase(Locale.ROOT);
            rebuildTable();
        };

        table = add(theme.table()).expandX().widget();

        WHorizontalList footer = add(theme.horizontalList()).expandX().widget();
        WButton cancel = footer.add(theme.button("Cancel")).expandX().widget();
        cancel.action = this::close;

        rebuildTable();
    }

    private void rebuildTable() {
        table.clear();
        Path safeCurrent = sanitizeDirectory(currentDir, modsDir);
        currentDir = safeCurrent;

        table.add(theme.label("Current: " + safeCurrent)).expandX();
        table.row();

        int jarCount = countJarFiles(safeCurrent);
        table.add(theme.label("Detected .jar files here: " + jarCount)).expandX();
        table.row();

        List<Path> directories = listDirectories(safeCurrent, filterText);
        if (directories.isEmpty()) {
            table.add(theme.label("No subfolders found. Use 'Use Current Folder' if this is your old mods dir.")).expandX();
            table.row();
            return;
        }

        for (Path directory : directories) {
            String name = directory.getFileName() == null ? directory.toString() : directory.getFileName().toString();
            table.add(theme.label(name)).expandX();

            WButton open = table.add(theme.button("Open")).widget();
            open.action = () -> {
                currentDir = directory;
                rebuildTable();
            };

            WButton use = table.add(theme.button("Use")).widget();
            use.action = () -> applyDirectory(directory);

            table.row();
        }
    }

    private void applyDirectory(Path directory) {
        Path normalized = sanitizeDirectory(directory, null);
        if (normalized == null) return;
        onSelect.accept(normalized);
        close();
    }

    private static Path sanitizeDirectory(Path candidate, Path fallback) {
        if (candidate != null) {
            try {
                Path normalized = candidate.toAbsolutePath().normalize();
                if (Files.isDirectory(normalized)) return normalized;
            } catch (Exception ignored) {
            }
        }
        return fallback;
    }

    private static Path fallbackDirectory() {
        try {
            Path home = Path.of(System.getProperty("user.home", ".")).toAbsolutePath().normalize();
            if (Files.isDirectory(home)) return home;
        } catch (Exception ignored) {
        }
        return Path.of(".").toAbsolutePath().normalize();
    }

    private static List<Path> listDirectories(Path baseDir, String filterText) {
        ArrayList<Path> directories = new ArrayList<>();
        if (baseDir == null || !Files.isDirectory(baseDir)) return directories;

        String filter = filterText == null ? "" : filterText.trim().toLowerCase(Locale.ROOT);
        try (Stream<Path> stream = Files.list(baseDir)) {
            stream.filter(Files::isDirectory)
                .sorted(Comparator.comparing(path -> {
                    Path name = path.getFileName();
                    return (name == null ? path.toString() : name.toString()).toLowerCase(Locale.ROOT);
                }))
                .forEach(path -> {
                    if (filter.isBlank()) {
                        directories.add(path);
                        return;
                    }

                    Path name = path.getFileName();
                    String value = (name == null ? path.toString() : name.toString()).toLowerCase(Locale.ROOT);
                    if (value.contains(filter)) directories.add(path);
                });
        } catch (Exception ignored) {
        }

        return directories;
    }

    private static int countJarFiles(Path baseDir) {
        if (baseDir == null || !Files.isDirectory(baseDir)) return 0;

        try (Stream<Path> stream = Files.list(baseDir)) {
            return (int) stream
                .filter(Files::isRegularFile)
                .filter(path -> {
                    String name = path.getFileName() == null ? "" : path.getFileName().toString().toLowerCase(Locale.ROOT);
                    return name.endsWith(".jar");
                })
                .count();
        } catch (Exception ignored) {
            return 0;
        }
    }
}
