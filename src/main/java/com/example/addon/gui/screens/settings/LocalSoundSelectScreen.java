package com.example.addon.gui.screens.settings;

import com.example.addon.audio.JoinSoundPlayer;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.WindowScreen;
import meteordevelopment.meteorclient.gui.widgets.containers.WHorizontalList;
import meteordevelopment.meteorclient.gui.widgets.containers.WTable;
import meteordevelopment.meteorclient.gui.widgets.input.WTextBox;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import net.minecraft.util.Util;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

public class LocalSoundSelectScreen extends WindowScreen {
    private final Consumer<String> onSelect;
    private WTable table;
    private Path soundsRoot;
    private String filterText = "";

    public LocalSoundSelectScreen(GuiTheme theme, Consumer<String> onSelect) {
        super(theme, "Select Local Ping Sound");
        this.onSelect = onSelect;
    }

    @Override
    public void initWidgets() {
        soundsRoot = JoinSoundPlayer.ensureSoundsDirectory();

        WHorizontalList controls = add(theme.horizontalList()).expandX().widget();

        WButton openFolder = controls.add(theme.button("Open Folder")).widget();
        openFolder.action = () -> Util.getOperatingSystem().open(soundsRoot.toUri().toString());
        openFolder.tooltip = "Open devils-addon/sounds.";

        WButton refresh = controls.add(theme.button("Refresh")).widget();
        refresh.action = () -> {
            table.clear();
            fillTable();
        };
        refresh.tooltip = "Rescan local .ogg files.";

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
        List<String> files = JoinSoundPlayer.listLocalSoundFiles(soundsRoot);
        if (files.isEmpty()) {
            table.add(theme.label("No .ogg files in devils-addon/sounds.")).expandX();
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
}
