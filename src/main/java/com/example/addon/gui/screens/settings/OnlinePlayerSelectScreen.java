package com.example.addon.gui.screens.settings;

import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.WindowScreen;
import meteordevelopment.meteorclient.gui.widgets.containers.WTable;
import meteordevelopment.meteorclient.gui.widgets.input.WTextBox;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import net.minecraft.client.network.PlayerListEntry;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public class OnlinePlayerSelectScreen extends WindowScreen {
    private final Consumer<String> onSelect;
    private WTable table;
    private String filterText = "";

    public OnlinePlayerSelectScreen(GuiTheme theme, Consumer<String> onSelect) {
        super(theme, "Select Online Player");
        this.onSelect = onSelect;
    }

    @Override
    public void initWidgets() {
        WTextBox filter = add(theme.textBox("")).minWidth(420).expandX().widget();
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
        List<String> onlinePlayers = getOnlinePlayers();

        if (onlinePlayers.isEmpty()) {
            table.add(theme.label("No online players found.")).expandX();
            table.row();
            return;
        }

        for (String player : onlinePlayers) {
            String value = player.trim();
            if (value.isEmpty()) continue;
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

    private List<String> getOnlinePlayers() {
        if (mc.getNetworkHandler() == null) return List.of();

        ArrayList<String> players = new ArrayList<>();
        for (PlayerListEntry entry : mc.getNetworkHandler().getPlayerList()) {
            if (entry == null || entry.getProfile() == null) continue;
            String name = entry.getProfile().getName();
            if (name == null || name.isBlank()) continue;
            players.add(name);
        }

        players.sort(Comparator.comparing(String::toLowerCase));
        return players;
    }
}
