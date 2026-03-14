package com.example.addon.gui.screens.settings;

import com.example.addon.util.MapIconManager;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.WindowScreen;
import meteordevelopment.meteorclient.gui.widgets.containers.WHorizontalList;
import meteordevelopment.meteorclient.gui.widgets.containers.WTable;
import meteordevelopment.meteorclient.gui.widgets.input.WTextBox;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import net.minecraft.util.Util;

import javax.swing.JFileChooser;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.stream.Stream;

public class LocalIconSelectScreen extends WindowScreen {
    private final Consumer<String> onSelect;

    private WTable table;
    private Path iconsRoot;
    private String filterText = "";

    public LocalIconSelectScreen(GuiTheme theme, Consumer<String> onSelect) {
        super(theme, "Select Local Icon");
        this.onSelect = onSelect;
    }

    @Override
    public void initWidgets() {
        iconsRoot = MapIconManager.ensureIconsDirectory();

        WHorizontalList controls = add(theme.horizontalList()).expandX().widget();

        WButton openFolder = controls.add(theme.button("Open Folder")).widget();
        openFolder.action = () -> Util.getOperatingSystem().open(iconsRoot.toUri().toString());
        openFolder.tooltip = "Open devils-addon/icons.";

        WButton refresh = controls.add(theme.button("Refresh")).widget();
        refresh.action = () -> {
            table.clear();
            fillTable();
        };
        refresh.tooltip = "Rescan local .png/.jpg files.";

        WButton importFile = controls.add(theme.button("Pick File...")).widget();
        importFile.action = () -> {
            Path selected = openNativePicker();
            if (selected == null) return;

            String imported = importIcon(selected);
            if (imported.isBlank()) return;

            onSelect.accept(imported);
            close();
        };
        importFile.tooltip = "Choose .png/.jpg anywhere and copy into devils-addon/icons.";

        WButton clear = controls.add(theme.button("Use Default")).widget();
        clear.action = () -> {
            onSelect.accept("");
            close();
        };
        clear.tooltip = "Use built-in Devils icon.";

        WTextBox filter = add(theme.textBox("")).minWidth(420).expandX().widget();
        filter.setFocused(true);
        filter.action = () -> {
            filterText = filter.get().trim().toLowerCase(Locale.ROOT);
            table.clear();
            fillTable();
        };

        table = add(theme.table()).expandX().widget();
        fillTable();
    }

    private void fillTable() {
        List<String> files = listLocalIconFiles(iconsRoot);
        if (files.isEmpty()) {
            table.add(theme.label("No .png/.jpg files in devils-addon/icons.")).expandX();
            table.row();
            return;
        }

        for (String file : files) {
            String value = file == null ? "" : file.trim();
            if (value.isBlank()) continue;
            if (!filterText.isBlank() && !value.toLowerCase(Locale.ROOT).contains(filterText)) continue;

            table.add(theme.label(value)).expandX();

            WButton select = table.add(theme.button("Select")).widget();
            select.action = () -> {
                onSelect.accept(value);
                close();
            };

            table.row();
        }
    }

    public static List<String> listLocalIconFiles(Path iconsRoot) {
        ArrayList<String> files = new ArrayList<>();
        if (iconsRoot == null || !Files.isDirectory(iconsRoot)) return files;

        try (Stream<Path> stream = Files.walk(iconsRoot)) {
            stream.filter(Files::isRegularFile)
                .forEach(path -> {
                    String fileName = path.getFileName() == null ? "" : path.getFileName().toString();
                    String ext = extension(fileName);
                    if (!ext.equals("png") && !ext.equals("jpg") && !ext.equals("jpeg")) return;
                    files.add(iconsRoot.relativize(path).toString().replace('\\', '/'));
                });
        } catch (Exception ignored) {
        }

        files.sort(Comparator.comparing(String::toLowerCase));
        return files;
    }

    private Path openNativePicker() {
        try {
            JFileChooser chooser = new JFileChooser();
            chooser.setDialogTitle("Select map icon");
            chooser.setFileFilter(new FileNameExtensionFilter("Image Files (.png, .jpg, .jpeg)", "png", "jpg", "jpeg"));
            chooser.setAcceptAllFileFilterUsed(false);
            chooser.setMultiSelectionEnabled(false);

            int result = chooser.showOpenDialog(null);
            if (result != JFileChooser.APPROVE_OPTION) return null;

            java.io.File selected = chooser.getSelectedFile();
            if (selected == null) return null;
            return selected.toPath();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private String importIcon(Path sourcePath) {
        if (sourcePath == null || !Files.isRegularFile(sourcePath)) return "";

        String ext = extension(sourcePath.getFileName() == null ? "" : sourcePath.getFileName().toString());
        if (!ext.equals("png") && !ext.equals("jpg") && !ext.equals("jpeg")) return "";

        Path root = MapIconManager.ensureIconsDirectory();
        String baseName = sourcePath.getFileName() == null ? "icon" : sourcePath.getFileName().toString();

        int dot = baseName.lastIndexOf('.');
        if (dot > 0) baseName = baseName.substring(0, dot);
        baseName = baseName.replaceAll("[^a-zA-Z0-9._-]", "_");
        if (baseName.isBlank()) baseName = "icon";

        Path target = root.resolve(baseName + "." + ext).normalize();
        int suffix = 1;
        while (Files.exists(target)) {
            target = root.resolve(baseName + "_" + suffix + "." + ext).normalize();
            suffix++;
        }

        try {
            try {
                Files.copy(sourcePath, target, StandardCopyOption.COPY_ATTRIBUTES);
            } catch (Exception ignored) {
                Files.copy(sourcePath, target);
            }
            return root.relativize(target).toString().replace('\\', '/');
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String extension(String fileName) {
        if (fileName == null) return "";
        int idx = fileName.lastIndexOf('.');
        if (idx < 0 || idx == fileName.length() - 1) return "";
        return fileName.substring(idx + 1).toLowerCase(Locale.ROOT);
    }
}
