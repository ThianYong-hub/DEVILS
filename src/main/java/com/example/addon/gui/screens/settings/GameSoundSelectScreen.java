package com.example.addon.gui.screens.settings;

import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.WindowScreen;
import meteordevelopment.meteorclient.gui.widgets.containers.WTable;
import meteordevelopment.meteorclient.gui.widgets.input.WTextBox;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;

public class GameSoundSelectScreen extends WindowScreen {
    private final Consumer<String> onSelect;
    private WTable table;
    private String filterText = "";

    public GameSoundSelectScreen(GuiTheme theme, Consumer<String> onSelect) {
        super(theme, "Select Game Sound");
        this.onSelect = onSelect;
    }

    @Override
    public void initWidgets() {
        WTextBox filter = add(theme.textBox("")).minWidth(450).expandX().widget();
        filter.setFocused(true);
        filter.action = () -> {
            filterText = filter.get().trim().toLowerCase();
            table.clear();
            fillTable();
        };

        table = add(theme.table()).expandX().widget();
        fillTable();
    }

    private void fillTable() {
        List<Identifier> ids = new ArrayList<>(Registries.SOUND_EVENT.getIds());
        ids.sort(Comparator.comparing(Identifier::toString));

        for (Identifier id : ids) {
            String value = id.toString();
            if (!filterText.isEmpty() && !value.toLowerCase().contains(filterText)) continue;

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
