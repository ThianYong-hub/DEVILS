package com.example.addon.settings;

import com.example.addon.audio.JoinSoundPlayer;
import com.example.addon.gui.screens.settings.SelectionScreens.OnlinePlayerSelectScreen;
import com.example.addon.settings.TrackerPlayerRule.SoundSourceMode;
import com.example.addon.settings.TrackerPlayerRule.TrackEventMode;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.renderer.GuiRenderer;
import meteordevelopment.meteorclient.gui.widgets.containers.WHorizontalList;
import meteordevelopment.meteorclient.gui.widgets.containers.WTable;
import meteordevelopment.meteorclient.gui.widgets.containers.WVerticalList;
import meteordevelopment.meteorclient.gui.widgets.input.WDropdown;
import meteordevelopment.meteorclient.gui.widgets.input.WTextBox;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.gui.widgets.pressable.WCheckbox;
import meteordevelopment.meteorclient.gui.widgets.pressable.WMinus;
import meteordevelopment.meteorclient.settings.IVisible;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.Util;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public class TrackerPlayersSetting extends Setting<List<TrackerPlayerRule>> {
    static final String NO_LOCAL_SOUNDS = "(no local .ogg)";
    static final String SOUNDS_FOLDER_LABEL = "devils-addon/sounds";
    static final double COL_SELECTOR = 84, COL_PLAYER = 230;
    static final double COL_EVENT = 84;
    static final double COL_SOUND = 58;
    static final double COL_SEND = 58;
    static final double COL_COMMAND = 230;
    static final double COL_DELAY = 92;
    static final double COL_SOURCE = 118;
    static final double COL_SOUND_VALUE = 392;
    static final double COL_VOLUME = 64;
    static final double COL_TEST = 54;
    static final double COL_DELETE = 42;
    static final int GAME_SOUND_SLOT_TEXT_MAX = 8;
    static final double EVENT_DROPDOWN_WIDTH = 78, SOURCE_DROPDOWN_WIDTH = 112;
    static final double SOUND_SLOT_WIDTH = 126;
    static final double SOUND_SLOT_INPUT_WIDTH = 108;
    static final double SOUND_SLOT_SELECT_WIDTH = 46;
    static final double RULE_TABLE_BASE_WIDTH =
        COL_SELECTOR + COL_PLAYER + COL_EVENT + COL_SOUND + COL_SEND + COL_COMMAND + COL_DELAY
            + COL_SOURCE + COL_SOUND_VALUE + COL_VOLUME + COL_TEST + COL_DELETE + (11 * 4);
    static final double RULE_TABLE_MARGIN = 96;
    static final double MIN_UI_SCALE = 0.45;

    public TrackerPlayersSetting(
        String name,
        String description,
        List<TrackerPlayerRule> defaultValue,
        Consumer<List<TrackerPlayerRule>> onChanged,
        Consumer<Setting<List<TrackerPlayerRule>>> onModuleActivated,
        IVisible visible
    ) {
        super(name, description, defaultValue, onChanged, onModuleActivated, visible);
    }

    @Override
    protected List<TrackerPlayerRule> parseImpl(String str) {
        List<TrackerPlayerRule> rules = new ArrayList<>();
        for (String token : str.split(",")) {
            String player = token.trim();
            if (player.isEmpty()) continue;
            rules.add(TrackerPlayersSettingUi.createDefaultRule(player, ""));
        }
        return rules;
    }

    @Override
    protected boolean isValueValid(List<TrackerPlayerRule> value) {
        return value != null;
    }

    @Override
    protected NbtCompound save(NbtCompound tag) {
        tag.put("value", TrackerPlayerRule.rulesToNbt(get()));
        return tag;
    }

    @Override
    protected List<TrackerPlayerRule> load(NbtCompound tag) {
        get().clear();
        get().addAll(TrackerPlayerRule.rulesFromNbt(tag.getListOrEmpty("value")));
        return get();
    }

    @Override
    public void resetImpl() {
        value = new ArrayList<>(defaultValue);
    }

    public static void fillTable(GuiTheme theme, WTable table, TrackerPlayersSetting setting) {
        table.clear();
        double uiScale = TrackerPlayersSettingUi.computeUiScale();

        ArrayList<TrackerPlayerRule> rules = new ArrayList<>(setting.get());
        Path soundsRoot = JoinSoundPlayer.ensureSoundsDirectory();
        List<String> localSounds = JoinSoundPlayer.listLocalSoundFiles(soundsRoot);

        WVerticalList root = table.add(theme.verticalList()).expandX().widget();
        table.row();

        WHorizontalList controls = root.add(theme.horizontalList()).expandX().widget();
        WButton openFolder = controls.add(theme.button("Open Folder")).widget();
        openFolder.action = () -> Util.getOperatingSystem().open(soundsRoot.toUri().toString());
        openFolder.tooltip = "Open local sounds folder.";

        WButton refreshSounds = controls.add(theme.button("Refresh Sounds")).widget();
        refreshSounds.action = () -> fillTable(theme, table, setting);
        refreshSounds.tooltip = "Rescan local .ogg files.";
        controls.add(theme.label("Sounds: " + SOUNDS_FOLDER_LABEL)).expandX().widget().tooltip =
            "Local .ogg files are loaded from this folder only.";

        root.add(theme.horizontalSeparator()).expandX();

        WTable rulesTable = root.add(theme.table()).expandX().widget();
        rulesTable.horizontalSpacing = uiScale < 0.75 ? 2 : 4;
        rulesTable.verticalSpacing = 2;

        TrackerPlayersSettingUi.addHeader(rulesTable, theme, "Online", "Pick a player currently online.", TrackerPlayersSettingUi.scaleWidth(COL_SELECTOR, uiScale, 30));
        TrackerPlayersSettingUi.addHeader(rulesTable, theme, "Player", "Exact player name. Case-sensitive.", TrackerPlayersSettingUi.scaleWidth(COL_PLAYER, uiScale, 60));
        TrackerPlayersSettingUi.addHeader(rulesTable, theme, "Event", "Join / Leave / Both (Join+Leave) / Death.", TrackerPlayersSettingUi.scaleWidth(COL_EVENT, uiScale, 36));
        TrackerPlayersSettingUi.addHeader(rulesTable, theme, "Sound", "Enable sound playback.", TrackerPlayersSettingUi.scaleWidth(COL_SOUND, uiScale, 24));
        TrackerPlayersSettingUi.addHeader(rulesTable, theme, "Send", "Enable chat send.", TrackerPlayersSettingUi.scaleWidth(COL_SEND, uiScale, 24));
        TrackerPlayersSettingUi.addHeader(rulesTable, theme, "Command", "Text or command sent to chat as-is.", TrackerPlayersSettingUi.scaleWidth(COL_COMMAND, uiScale, 70));
        TrackerPlayersSettingUi.addHeader(rulesTable, theme, "Delay ms", "Delay before chat send in milliseconds (0-3600000).", TrackerPlayersSettingUi.scaleWidth(COL_DELAY, uiScale, 36));
        TrackerPlayersSettingUi.addHeader(rulesTable, theme, "Source", "Sound source mode.", TrackerPlayersSettingUi.scaleWidth(COL_SOURCE, uiScale, 50));
        TrackerPlayersSettingUi.addHeader(rulesTable, theme, "Sounds (J/L/D)", "Separate sound values for Join / Leave / Death.", TrackerPlayersSettingUi.scaleWidth(COL_SOUND_VALUE, uiScale, 90));
        TrackerPlayersSettingUi.addHeader(rulesTable, theme, "Vol%", "Volume for local .ogg only (0-200).", TrackerPlayersSettingUi.scaleWidth(COL_VOLUME, uiScale, 28));
        TrackerPlayersSettingUi.addHeader(rulesTable, theme, "Test", "Test this row sound.", TrackerPlayersSettingUi.scaleWidth(COL_TEST, uiScale, 24));
        TrackerPlayersSettingUi.addHeader(rulesTable, theme, "Delete", "Delete this rule.", TrackerPlayersSettingUi.scaleWidth(COL_DELETE, uiScale, 22));
        rulesTable.row();

        for (int i = 0; i < rules.size(); i++) {
            addRuleRow(theme, table, rulesTable, setting, rules, i, localSounds, uiScale);
        }

        root.add(theme.horizontalSeparator()).expandX();

        WHorizontalList actions = root.add(theme.horizontalList()).expandX().widget();
        WButton add = actions.add(theme.button("Add")).widget();
        add.tooltip = "Add a new player rule.";
        add.action = () -> {
            String defaultLocalSound = localSounds.isEmpty() ? "" : localSounds.getFirst();
            rules.add(TrackerPlayersSettingUi.createDefaultRule("", defaultLocalSound));
            setting.set(new ArrayList<>(rules));
            fillTable(theme, table, setting);
        };

        WButton reset = actions.add(theme.button(GuiRenderer.RESET)).widget();
        reset.action = () -> {
            setting.reset();
            fillTable(theme, table, setting);
        };
        reset.tooltip = "Reset rules to defaults.";
    }

    private static void addRuleRow(
        GuiTheme theme,
        WTable rootTable,
        WTable rulesTable,
        TrackerPlayersSetting setting,
        ArrayList<TrackerPlayerRule> rules,
        int ruleIndex,
        List<String> localSounds,
        double uiScale
    ) {
        TrackerPlayerRule rule = rules.get(ruleIndex);

        WHorizontalList onlineCell = TrackerPlayersSettingUi.addColumnCell(theme, rulesTable, TrackerPlayersSettingUi.scaleWidth(COL_SELECTOR, uiScale, 30));
        WButton onlineButton = onlineCell.add(theme.button("Online")).expandCellX().centerX().widget();
        onlineButton.tooltip = "Select from current online players.";
        if (mc.getNetworkHandler() == null) {
            onlineButton.tooltip = "Not connected to a server.";
            onlineButton.action = () -> {};
        } else {
            onlineButton.action = () -> mc.setScreen(new OnlinePlayerSelectScreen(theme, selectedPlayer -> {
                TrackerPlayersSettingUi.updateRule(setting, rules, ruleIndex, rules.get(ruleIndex).withPlayerName(selectedPlayer));
                fillTable(theme, rootTable, setting);
            }));
        }

        WHorizontalList playerCell = TrackerPlayersSettingUi.addColumnCell(theme, rulesTable, TrackerPlayersSettingUi.scaleWidth(COL_PLAYER, uiScale, 60));
        WTextBox playerTextBox = playerCell.add(theme.textBox(rule.playerName())).expandX().widget();
        playerTextBox.tooltip = "Player name to match.";
        playerTextBox.action = () -> rules.set(ruleIndex, rules.get(ruleIndex).withPlayerName(playerTextBox.get()));
        playerTextBox.actionOnUnfocused = () -> setting.set(new ArrayList<>(rules));

        WHorizontalList eventCell = TrackerPlayersSettingUi.addColumnCell(theme, rulesTable, TrackerPlayersSettingUi.scaleWidth(COL_EVENT, uiScale, 36));
        WDropdown<TrackEventMode> eventDropdown = eventCell.add(theme.dropdown(TrackEventMode.values(), rule.eventMode())).minWidth(EVENT_DROPDOWN_WIDTH).widget();
        eventDropdown.tooltip = "Rule trigger event. Both = Join+Leave.";
        eventDropdown.action = () -> TrackerPlayersSettingUi.updateRule(setting, rules, ruleIndex, rules.get(ruleIndex).withEventMode(eventDropdown.get()));

        WHorizontalList soundCell = TrackerPlayersSettingUi.addColumnCell(theme, rulesTable, TrackerPlayersSettingUi.scaleWidth(COL_SOUND, uiScale, 24));
        WCheckbox soundCheckbox = soundCell.add(theme.checkbox(rule.soundEnabled())).expandCellX().centerX().widget();
        soundCheckbox.tooltip = "Enable sound for this rule.";
        soundCheckbox.action = () -> TrackerPlayersSettingUi.updateRule(setting, rules, ruleIndex, rules.get(ruleIndex).withSoundEnabled(soundCheckbox.checked));

        WHorizontalList sendCell = TrackerPlayersSettingUi.addColumnCell(theme, rulesTable, TrackerPlayersSettingUi.scaleWidth(COL_SEND, uiScale, 24));
        WCheckbox sendCheckbox = sendCell.add(theme.checkbox(rule.sendEnabled())).expandCellX().centerX().widget();
        sendCheckbox.tooltip = "Enable chat send for this rule.";
        sendCheckbox.action = () -> TrackerPlayersSettingUi.updateRule(setting, rules, ruleIndex, rules.get(ruleIndex).withSendEnabled(sendCheckbox.checked));

        WHorizontalList commandCell = TrackerPlayersSettingUi.addColumnCell(theme, rulesTable, TrackerPlayersSettingUi.scaleWidth(COL_COMMAND, uiScale, 70));
        WTextBox commandTextBox = commandCell.add(theme.textBox(rule.commandText())).expandX().widget();
        commandTextBox.tooltip = "Command or text sent to chat.";
        commandTextBox.action = () -> rules.set(ruleIndex, rules.get(ruleIndex).withCommandText(commandTextBox.get()));
        commandTextBox.actionOnUnfocused = () -> setting.set(new ArrayList<>(rules));

        WHorizontalList delayCell = TrackerPlayersSettingUi.addColumnCell(theme, rulesTable, TrackerPlayersSettingUi.scaleWidth(COL_DELAY, uiScale, 36));
        TrackerPlayersSettingUi.addChatDelayEditor(theme, delayCell, setting, rules, ruleIndex);

        WHorizontalList sourceCell = TrackerPlayersSettingUi.addColumnCell(theme, rulesTable, TrackerPlayersSettingUi.scaleWidth(COL_SOURCE, uiScale, 50));
        WDropdown<SoundSourceMode> sourceDropdown = sourceCell.add(theme.dropdown(SoundSourceMode.values(), rule.soundSource())).minWidth(SOURCE_DROPDOWN_WIDTH).widget();
        sourceDropdown.tooltip = "Select sound source.";
        sourceDropdown.action = () -> {
            TrackerPlayersSettingUi.updateRule(setting, rules, ruleIndex, rules.get(ruleIndex).withSoundSource(sourceDropdown.get()));
            fillTable(theme, rootTable, setting);
        };

        WHorizontalList soundValueCell = TrackerPlayersSettingUi.addColumnCell(theme, rulesTable, TrackerPlayersSettingUi.scaleWidth(COL_SOUND_VALUE, uiScale, 90));
        TrackerPlayersSettingUi.addEventSoundEditors(theme, rootTable, soundValueCell, setting, rules, ruleIndex, localSounds, uiScale);

        WHorizontalList volumeCell = TrackerPlayersSettingUi.addColumnCell(theme, rulesTable, TrackerPlayersSettingUi.scaleWidth(COL_VOLUME, uiScale, 28));
        TrackerPlayersSettingUi.addLocalVolumeEditor(theme, volumeCell, setting, rules, ruleIndex);

        WHorizontalList testCell = TrackerPlayersSettingUi.addColumnCell(theme, rulesTable, TrackerPlayersSettingUi.scaleWidth(COL_TEST, uiScale, 24));
        WButton testSound = testCell.add(theme.button("Test")).expandCellX().centerX().widget();
        testSound.tooltip = "Play this rule sound now with diagnostics.";
        testSound.action = () -> {
            TrackerPlayerRule current = rules.get(ruleIndex);
            TrackerPlayerRule.Trigger trigger = TrackerPlayersSettingUi.getTestTrigger(current.eventMode());
            JoinSoundPlayer.PlaybackDiagnosticResult result = JoinSoundPlayer.testPlay(
                current.soundSource(),
                current.soundValueFor(trigger),
                TrackerPlayersSettingUi.getModuleDefaultSoundSpec(),
                current.oggVolumePercent()
            );

            if (!result.isOk()) {
                ChatUtils.warning("Tracker sound test: " + result.message());
            }
        };

        WHorizontalList deleteCell = TrackerPlayersSettingUi.addColumnCell(theme, rulesTable, TrackerPlayersSettingUi.scaleWidth(COL_DELETE, uiScale, 22));
        WMinus delete = deleteCell.add(theme.minus()).expandCellX().centerX().widget();
        delete.tooltip = "Delete this rule.";
        delete.action = () -> {
            rules.remove(ruleIndex);
            setting.set(new ArrayList<>(rules));
            fillTable(theme, rootTable, setting);
        };

        rulesTable.row();
    }

    public static net.minecraft.nbt.NbtList rulesToNbt(List<TrackerPlayerRule> rules) {
        return TrackerPlayerRule.rulesToNbt(rules);
    }

    public static List<TrackerPlayerRule> rulesFromNbt(net.minecraft.nbt.NbtList valueTag) {
        return TrackerPlayerRule.rulesFromNbt(valueTag);
    }

    public static String normalizeLocalSoundValue(String currentValue, List<String> localSounds) {
        return TrackerPlayersSettingUi.normalizeLocalSoundValue(currentValue, localSounds);
    }

    public static TrackerPlayerRule normalizeRuleLocalSoundValues(TrackerPlayerRule rule, List<String> localSounds) {
        return TrackerPlayersSettingUi.normalizeRuleLocalSoundValues(rule, localSounds);
    }
}




