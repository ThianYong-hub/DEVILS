package com.devils.addon.modules.modupdater;

import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.WindowScreen;
import meteordevelopment.meteorclient.gui.widgets.containers.WHorizontalList;
import meteordevelopment.meteorclient.gui.widgets.containers.WTable;
import meteordevelopment.meteorclient.gui.widgets.input.WTextBox;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.gui.widgets.pressable.WCheckbox;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;

final class SourceModsSelectScreen extends WindowScreen {
    private final Path sourceModsDir;
    private final Consumer<Set<String>> onApply;
    private final LinkedHashSet<String> selected;
    private boolean autoSelectOnFirstLoad;

    private final ArrayList<SourceModScanner.SourceModEntry> allEntries = new ArrayList<>();
    private WTable table;
    private String filterText = "";

    SourceModsSelectScreen(GuiTheme theme, Path sourceModsDir, Set<String> initiallySelected, Consumer<Set<String>> onApply) {
        super(theme, "Select Source Mods");
        this.sourceModsDir = sourceModsDir;
        this.onApply = onApply;
        this.selected = new LinkedHashSet<>();
        if (initiallySelected != null) this.selected.addAll(initiallySelected);
        this.autoSelectOnFirstLoad = this.selected.isEmpty();
    }

    @Override
    public void initWidgets() {
        WHorizontalList controls = add(theme.horizontalList()).expandX().widget();

        WButton refresh = controls.add(theme.button("Refresh")).widget();
        refresh.action = () -> {
            reloadEntries();
            rebuildTable();
        };

        WButton selectAll = controls.add(theme.button("Select All")).widget();
        selectAll.action = () -> {
            for (SourceModScanner.SourceModEntry entry : filteredEntries()) selected.add(entry.modId);
            rebuildTable();
        };

        WButton clearAll = controls.add(theme.button("Clear")).widget();
        clearAll.action = () -> {
            for (SourceModScanner.SourceModEntry entry : filteredEntries()) selected.remove(entry.modId);
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
        WButton apply = footer.add(theme.button("Apply")).expandX().widget();
        apply.action = () -> {
            onApply.accept(Set.copyOf(selected));
            close();
        };

        WButton cancel = footer.add(theme.button("Cancel")).expandX().widget();
        cancel.action = this::close;

        reloadEntries();
        rebuildTable();
    }

    private void reloadEntries() {
        allEntries.clear();
        allEntries.addAll(SourceModScanner.scan(sourceModsDir));
        if (autoSelectOnFirstLoad && selected.isEmpty()) {
            for (SourceModScanner.SourceModEntry entry : allEntries) selected.add(entry.modId);
            autoSelectOnFirstLoad = false;
        }
    }

    private List<SourceModScanner.SourceModEntry> filteredEntries() {
        if (filterText.isBlank()) return allEntries;

        ArrayList<SourceModScanner.SourceModEntry> filtered = new ArrayList<>();
        for (SourceModScanner.SourceModEntry entry : allEntries) {
            String hay = (entry.displayName + " " + entry.modId + " " + entry.sampleFileName).toLowerCase(Locale.ROOT);
            if (hay.contains(filterText)) filtered.add(entry);
        }
        return filtered;
    }

    private void rebuildTable() {
        table.clear();
        List<SourceModScanner.SourceModEntry> entries = filteredEntries();

        table.add(theme.label("Selected: " + selected.size() + " / " + allEntries.size())).expandX();
        table.row();

        if (entries.isEmpty()) {
            table.add(theme.label("No mods found for current source folder/filter.")).expandX();
            table.row();
            return;
        }

        for (SourceModScanner.SourceModEntry entry : entries) {
            boolean checked = selected.contains(entry.modId);
            WCheckbox checkbox = table.add(theme.checkbox(checked)).widget();
            checkbox.action = () -> {
                if (checkbox.checked) selected.add(entry.modId);
                else selected.remove(entry.modId);
            };

            String label = formatEntryLabel(entry);
            table.add(theme.label(label)).expandX();
            table.row();
        }
    }

    private static String formatEntryLabel(SourceModScanner.SourceModEntry entry) {
        StringBuilder sb = new StringBuilder();
        sb.append(entry.displayName).append(" [").append(entry.modId).append("]");
        if (!entry.version.isBlank()) sb.append(" v").append(entry.version);
        if (entry.fileCount > 1) sb.append(" (").append(entry.fileCount).append(" jars)");
        if (!entry.hasFabricMetadata) sb.append(" (fallback-id)");
        return sb.toString();
    }
}
