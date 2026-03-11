package com.example.addon.gui.screens.settings;

import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.WindowScreen;
import meteordevelopment.meteorclient.gui.widgets.containers.WTable;
import meteordevelopment.meteorclient.gui.widgets.input.WTextBox;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

public class StringSelectScreen extends WindowScreen {
    private final List<String> values;
    private final Consumer<String> onSelect;

    private WTable table;
    private String filterText = "";

    public StringSelectScreen(GuiTheme theme, String title, List<String> values, Consumer<String> onSelect) {
        super(theme, title);
        this.values = new ArrayList<>(values);
        this.onSelect = onSelect;
    }

    @Override
    public void initWidgets() {
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
        boolean added = false;

        for (String entry : values) {
            String value = entry == null ? "" : entry.trim();
            if (!filterText.isEmpty() && !value.toLowerCase(Locale.ROOT).contains(filterText)) continue;

            table.add(theme.label(value.isEmpty() ? "(empty)" : value)).expandX();

            WButton select = table.add(theme.button("Select")).widget();
            select.action = () -> {
                onSelect.accept(value);
                close();
            };

            table.row();
            added = true;
        }

        if (!added) {
            table.add(theme.label("Nothing found.")).expandX();
            table.row();
        }
    }
}
