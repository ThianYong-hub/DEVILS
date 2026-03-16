package com.example.addon.gui.screens.settings;

import com.example.addon.audio.JoinSoundPlayer;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.WindowScreen;
import meteordevelopment.meteorclient.gui.widgets.containers.WHorizontalList;
import meteordevelopment.meteorclient.gui.widgets.containers.WTable;
import meteordevelopment.meteorclient.gui.widgets.input.WTextBox;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public final class SelectionScreens {
    private SelectionScreens() {
    }

    public static class GameSoundSelectScreen extends WindowScreen {
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
                filterText = filter.get().trim().toLowerCase(Locale.ROOT);
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
                if (!filterText.isEmpty() && !value.toLowerCase(Locale.ROOT).contains(filterText)) continue;

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

    public static class StringSelectScreen extends WindowScreen {
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

    public static class OnlinePlayerSelectScreen extends WindowScreen {
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
                filterText = filter.get().trim().toLowerCase(Locale.ROOT);
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
                if (!filterText.isEmpty() && !value.toLowerCase(Locale.ROOT).contains(filterText)) continue;

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

    public static class LocalSoundSelectScreen extends WindowScreen {
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
}

