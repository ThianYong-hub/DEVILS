package com.example.addon.settings;

import com.example.addon.audio.JoinSoundPlayer;
import com.example.addon.gui.screens.settings.GameSoundSelectScreen;
import com.example.addon.gui.screens.settings.OnlinePlayerSelectScreen;
import com.example.addon.modules.JoinWatcher;
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
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.util.Util;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public class TrackerPlayersSetting extends Setting<List<TrackerPlayerRule>> {
    private static final String NO_LOCAL_SOUNDS = "(no local .ogg)";
    private static final String SOUNDS_FOLDER_LABEL = "devils-addon/sounds";

    private static final double COL_SELECTOR = 84;
    private static final double COL_PLAYER = 230;
    private static final double COL_EVENT = 84;
    private static final double COL_SOUND = 58;
    private static final double COL_SEND = 58;
    private static final double COL_COMMAND = 230;
    private static final double COL_DELAY = 92;
    private static final double COL_SOURCE = 118;
    private static final double COL_SOUND_VALUE = 392;
    private static final double COL_VOLUME = 64;
    private static final double COL_TEST = 54;
    private static final double COL_DELETE = 42;
    private static final int GAME_SOUND_SLOT_TEXT_MAX = 8;
    private static final double EVENT_DROPDOWN_WIDTH = 78;
    private static final double SOURCE_DROPDOWN_WIDTH = 112;
    private static final double SOUND_SLOT_WIDTH = 126;
    private static final double SOUND_SLOT_INPUT_WIDTH = 108;
    private static final double SOUND_SLOT_SELECT_WIDTH = 46;
    private static final double RULE_TABLE_BASE_WIDTH =
        COL_SELECTOR
            + COL_PLAYER
            + COL_EVENT
            + COL_SOUND
            + COL_SEND
            + COL_COMMAND
            + COL_DELAY
            + COL_SOURCE
            + COL_SOUND_VALUE
            + COL_VOLUME
            + COL_TEST
            + COL_DELETE
            + (11 * 4); // column spacing
    private static final double RULE_TABLE_MARGIN = 96;
    private static final double MIN_UI_SCALE = 0.45;

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
            rules.add(createDefaultRule(player, ""));
        }
        return rules;
    }

    @Override
    protected boolean isValueValid(List<TrackerPlayerRule> value) {
        return value != null;
    }

    @Override
    protected NbtCompound save(NbtCompound tag) {
        tag.put("value", rulesToNbt(get()));
        return tag;
    }

    @Override
    protected List<TrackerPlayerRule> load(NbtCompound tag) {
        get().clear();
        get().addAll(rulesFromNbt(tag.getListOrEmpty("value")));
        return get();
    }

    @Override
    public void resetImpl() {
        value = new ArrayList<>(defaultValue);
    }

    public static void fillTable(GuiTheme theme, WTable table, TrackerPlayersSetting setting) {
        table.clear();
        double uiScale = computeUiScale();

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

        addHeader(rulesTable, theme, "Online", "Pick a player currently online.", scaleWidth(COL_SELECTOR, uiScale, 30));
        addHeader(rulesTable, theme, "Player", "Exact player name. Case-sensitive.", scaleWidth(COL_PLAYER, uiScale, 60));
        addHeader(rulesTable, theme, "Event", "Join / Leave / Both (Join+Leave) / Death.", scaleWidth(COL_EVENT, uiScale, 36));
        addHeader(rulesTable, theme, "Sound", "Enable sound playback.", scaleWidth(COL_SOUND, uiScale, 24));
        addHeader(rulesTable, theme, "Send", "Enable chat send.", scaleWidth(COL_SEND, uiScale, 24));
        addHeader(rulesTable, theme, "Command", "Text or command sent to chat as-is.", scaleWidth(COL_COMMAND, uiScale, 70));
        addHeader(rulesTable, theme, "Delay ms", "Delay before chat send in milliseconds (0-3600000).", scaleWidth(COL_DELAY, uiScale, 36));
        addHeader(rulesTable, theme, "Source", "Sound source mode.", scaleWidth(COL_SOURCE, uiScale, 50));
        addHeader(rulesTable, theme, "Sounds (J/L/D)", "Separate sound values for Join / Leave / Death.", scaleWidth(COL_SOUND_VALUE, uiScale, 90));
        addHeader(rulesTable, theme, "Vol%", "Volume for local .ogg only (0-200).", scaleWidth(COL_VOLUME, uiScale, 28));
        addHeader(rulesTable, theme, "Test", "Test this row sound.", scaleWidth(COL_TEST, uiScale, 24));
        addHeader(rulesTable, theme, "Delete", "Delete this rule.", scaleWidth(COL_DELETE, uiScale, 22));
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
            rules.add(createDefaultRule("", defaultLocalSound));
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

        WHorizontalList onlineCell = addColumnCell(theme, rulesTable, scaleWidth(COL_SELECTOR, uiScale, 30));
        WButton onlineButton = onlineCell.add(theme.button("Online")).expandCellX().centerX().widget();
        onlineButton.tooltip = "Select from current online players.";
        if (mc.getNetworkHandler() == null) {
            onlineButton.tooltip = "Not connected to a server.";
            onlineButton.action = () -> {};
        } else {
            onlineButton.action = () -> mc.setScreen(new OnlinePlayerSelectScreen(theme, selectedPlayer -> {
                updateRule(setting, rules, ruleIndex, rules.get(ruleIndex).withPlayerName(selectedPlayer));
                fillTable(theme, rootTable, setting);
            }));
        }

        WHorizontalList playerCell = addColumnCell(theme, rulesTable, scaleWidth(COL_PLAYER, uiScale, 60));
        WTextBox playerTextBox = playerCell.add(theme.textBox(rule.playerName())).expandX().widget();
        playerTextBox.tooltip = "Player name to match.";
        playerTextBox.action = () -> rules.set(ruleIndex, rules.get(ruleIndex).withPlayerName(playerTextBox.get()));
        playerTextBox.actionOnUnfocused = () -> setting.set(new ArrayList<>(rules));

        WHorizontalList eventCell = addColumnCell(theme, rulesTable, scaleWidth(COL_EVENT, uiScale, 36));
        WDropdown<TrackEventMode> eventDropdown = eventCell.add(theme.dropdown(TrackEventMode.values(), rule.eventMode())).minWidth(EVENT_DROPDOWN_WIDTH).widget();
        eventDropdown.tooltip = "Rule trigger event. Both = Join+Leave.";
        eventDropdown.action = () -> updateRule(setting, rules, ruleIndex, rules.get(ruleIndex).withEventMode(eventDropdown.get()));

        WHorizontalList soundCell = addColumnCell(theme, rulesTable, scaleWidth(COL_SOUND, uiScale, 24));
        WCheckbox soundCheckbox = soundCell.add(theme.checkbox(rule.soundEnabled())).expandCellX().centerX().widget();
        soundCheckbox.tooltip = "Enable sound for this rule.";
        soundCheckbox.action = () -> updateRule(setting, rules, ruleIndex, rules.get(ruleIndex).withSoundEnabled(soundCheckbox.checked));

        WHorizontalList sendCell = addColumnCell(theme, rulesTable, scaleWidth(COL_SEND, uiScale, 24));
        WCheckbox sendCheckbox = sendCell.add(theme.checkbox(rule.sendEnabled())).expandCellX().centerX().widget();
        sendCheckbox.tooltip = "Enable chat send for this rule.";
        sendCheckbox.action = () -> updateRule(setting, rules, ruleIndex, rules.get(ruleIndex).withSendEnabled(sendCheckbox.checked));

        WHorizontalList commandCell = addColumnCell(theme, rulesTable, scaleWidth(COL_COMMAND, uiScale, 70));
        WTextBox commandTextBox = commandCell.add(theme.textBox(rule.commandText())).expandX().widget();
        commandTextBox.tooltip = "Command or text sent to chat.";
        commandTextBox.action = () -> rules.set(ruleIndex, rules.get(ruleIndex).withCommandText(commandTextBox.get()));
        commandTextBox.actionOnUnfocused = () -> setting.set(new ArrayList<>(rules));

        WHorizontalList delayCell = addColumnCell(theme, rulesTable, scaleWidth(COL_DELAY, uiScale, 36));
        addChatDelayEditor(theme, delayCell, setting, rules, ruleIndex);

        WHorizontalList sourceCell = addColumnCell(theme, rulesTable, scaleWidth(COL_SOURCE, uiScale, 50));
        WDropdown<SoundSourceMode> sourceDropdown = sourceCell.add(theme.dropdown(SoundSourceMode.values(), rule.soundSource())).minWidth(SOURCE_DROPDOWN_WIDTH).widget();
        sourceDropdown.tooltip = "Select sound source.";
        sourceDropdown.action = () -> {
            updateRule(setting, rules, ruleIndex, rules.get(ruleIndex).withSoundSource(sourceDropdown.get()));
            fillTable(theme, rootTable, setting);
        };

        WHorizontalList soundValueCell = addColumnCell(theme, rulesTable, scaleWidth(COL_SOUND_VALUE, uiScale, 90));
        addEventSoundEditors(theme, rootTable, soundValueCell, setting, rules, ruleIndex, localSounds, uiScale);

        WHorizontalList volumeCell = addColumnCell(theme, rulesTable, scaleWidth(COL_VOLUME, uiScale, 28));
        addLocalVolumeEditor(theme, volumeCell, setting, rules, ruleIndex);

        WHorizontalList testCell = addColumnCell(theme, rulesTable, scaleWidth(COL_TEST, uiScale, 24));
        WButton testSound = testCell.add(theme.button("Test")).expandCellX().centerX().widget();
        testSound.tooltip = "Play this rule sound now with diagnostics.";
        testSound.action = () -> {
            TrackerPlayerRule current = rules.get(ruleIndex);
            TrackerPlayerRule.Trigger trigger = getTestTrigger(current.eventMode());
            JoinSoundPlayer.PlaybackDiagnosticResult result = JoinSoundPlayer.testPlay(
                current.soundSource(),
                current.soundValueFor(trigger),
                getModuleDefaultSoundSpec(),
                current.oggVolumePercent()
            );

            if (!result.isOk()) {
                ChatUtils.warning("Tracker sound test: " + result.message());
            }
        };

        WHorizontalList deleteCell = addColumnCell(theme, rulesTable, scaleWidth(COL_DELETE, uiScale, 22));
        WMinus delete = deleteCell.add(theme.minus()).expandCellX().centerX().widget();
        delete.tooltip = "Delete this rule.";
        delete.action = () -> {
            rules.remove(ruleIndex);
            setting.set(new ArrayList<>(rules));
            fillTable(theme, rootTable, setting);
        };

        rulesTable.row();
    }

    private static void addEventSoundEditors(
        GuiTheme theme,
        WTable rootTable,
        WHorizontalList soundControl,
        TrackerPlayersSetting setting,
        ArrayList<TrackerPlayerRule> rules,
        int ruleIndex,
        List<String> localSounds,
        double uiScale
    ) {
        TrackerPlayerRule rule = rules.get(ruleIndex);

        if (rule.soundSource() == SoundSourceMode.LocalFolder) {
            TrackerPlayerRule normalizedRule = normalizeRuleLocalSoundValues(rule, localSounds);
            if (!normalizedRule.equals(rule)) {
                updateRule(setting, rules, ruleIndex, normalizedRule);
                rule = normalizedRule;
            }
        }

        addEventSoundEditor(theme, rootTable, soundControl, setting, rules, ruleIndex, localSounds, EventSoundSlot.Join, rule.soundSource(), uiScale);
        addEventSoundEditor(theme, rootTable, soundControl, setting, rules, ruleIndex, localSounds, EventSoundSlot.Leave, rule.soundSource(), uiScale);
        addEventSoundEditor(theme, rootTable, soundControl, setting, rules, ruleIndex, localSounds, EventSoundSlot.Death, rule.soundSource(), uiScale);
    }

    private static void addEventSoundEditor(
        GuiTheme theme,
        WTable rootTable,
        WHorizontalList soundControl,
        TrackerPlayersSetting setting,
        ArrayList<TrackerPlayerRule> rules,
        int ruleIndex,
        List<String> localSounds,
        EventSoundSlot slot,
        SoundSourceMode sourceMode,
        double uiScale
    ) {
        double slotWidth = scaleWidth(SOUND_SLOT_WIDTH, uiScale, 52);
        double slotInputWidth = scaleWidth(SOUND_SLOT_INPUT_WIDTH, uiScale, 38);
        double slotSelectWidth = scaleWidth(SOUND_SLOT_SELECT_WIDTH, uiScale, 22);
        WHorizontalList slotControl = soundControl.add(theme.horizontalList()).minWidth(slotWidth).centerY().widget();
        slotControl.add(theme.label(slot.shortLabel)).widget().tooltip = slot.tooltip;

        switch (sourceMode) {
            case LocalFolder -> {
                String[] values = localSounds.isEmpty()
                    ? new String[] { NO_LOCAL_SOUNDS }
                    : localSounds.toArray(String[]::new);

                TrackerPlayerRule currentRule = rules.get(ruleIndex);
                String selectedValue = soundValueForSlot(currentRule, slot);
                String selected = localSounds.isEmpty() ? NO_LOCAL_SOUNDS : selectedValue;

                WDropdown<String> localDropdown = slotControl.add(theme.dropdown(values, selected)).minWidth(slotInputWidth).widget();
                localDropdown.tooltip = slot.tooltip + " Local .ogg from " + SOUNDS_FOLDER_LABEL + ".";
                localDropdown.action = () -> {
                    String value = localDropdown.get();
                    if (NO_LOCAL_SOUNDS.equals(value)) return;
                    updateRule(setting, rules, ruleIndex, withSoundValueForSlot(rules.get(ruleIndex), slot, value));
                };
            }
            case GameRegistry -> {
                TrackerPlayerRule currentRule = rules.get(ruleIndex);
                String full = soundValueForSlot(currentRule, slot);
                String display = full.isBlank() ? "(none)" : compactSoundValue(full, GAME_SOUND_SLOT_TEXT_MAX);

                slotControl.add(theme.label(display)).minWidth(Math.max(20, slotInputWidth - slotSelectWidth - 4)).centerY().widget().tooltip = full.isBlank() ? "(none)" : full;

                WButton selectGameSound = slotControl.add(theme.button("...")).minWidth(slotSelectWidth).widget();
                selectGameSound.tooltip = slot.tooltip + " Select a sound id from game registry.";
                selectGameSound.action = () -> mc.setScreen(new GameSoundSelectScreen(theme, selectedSound -> {
                    updateRule(setting, rules, ruleIndex, withSoundValueForSlot(rules.get(ruleIndex), slot, selectedSound));
                    fillTable(theme, rootTable, setting);
                }));
            }
            case ManualId -> {
                TrackerPlayerRule currentRule = rules.get(ruleIndex);
                WTextBox manualTextBox = slotControl.add(theme.textBox(soundValueForSlot(currentRule, slot))).minWidth(slotInputWidth).widget();
                manualTextBox.tooltip = slot.tooltip + " Manual sound id, ex: minecraft:block.note_block.bell";
                manualTextBox.action = () -> rules.set(ruleIndex, withSoundValueForSlot(rules.get(ruleIndex), slot, manualTextBox.get()));
                manualTextBox.actionOnUnfocused = () -> setting.set(new ArrayList<>(rules));
            }
        }
    }

    private static void addLocalVolumeEditor(
        GuiTheme theme,
        WHorizontalList volumeControl,
        TrackerPlayersSetting setting,
        ArrayList<TrackerPlayerRule> rules,
        int ruleIndex
    ) {
        TrackerPlayerRule rule = rules.get(ruleIndex);

        if (rule.soundSource() != SoundSourceMode.LocalFolder) {
            volumeControl.add(theme.label("n/a")).expandCellX().centerX().widget().tooltip =
                "Volume applies only to local .ogg sounds.";
            return;
        }

        WTextBox volumeTextBox = volumeControl.add(theme.textBox(
            Integer.toString(rule.oggVolumePercent()),
            (text, c) -> Character.isDigit(c)
        )).expandX().widget();
        volumeTextBox.tooltip = "Local .ogg volume percent (0-200).";

        volumeTextBox.action = () -> {
            Integer parsed = parseVolumePercent(volumeTextBox.get());
            if (parsed == null) return;
            rules.set(ruleIndex, rules.get(ruleIndex).withOggVolumePercent(parsed));
        };

        volumeTextBox.actionOnUnfocused = () -> {
            int fallback = rules.get(ruleIndex).oggVolumePercent();
            int normalized = normalizeVolumePercent(volumeTextBox.get(), fallback);
            updateRule(setting, rules, ruleIndex, rules.get(ruleIndex).withOggVolumePercent(normalized));
            volumeTextBox.set(Integer.toString(normalized));
        };
    }

    private static void addChatDelayEditor(
        GuiTheme theme,
        WHorizontalList delayControl,
        TrackerPlayersSetting setting,
        ArrayList<TrackerPlayerRule> rules,
        int ruleIndex
    ) {
        TrackerPlayerRule rule = rules.get(ruleIndex);

        WTextBox delayTextBox = delayControl.add(theme.textBox(
            Integer.toString(rule.chatDelayMs()),
            (text, c) -> Character.isDigit(c)
        )).expandX().widget();
        delayTextBox.tooltip = "Delay before chat send in milliseconds (0-3600000).";

        delayTextBox.action = () -> {
            Integer parsed = parseChatDelayMs(delayTextBox.get());
            if (parsed == null) return;
            rules.set(ruleIndex, rules.get(ruleIndex).withChatDelayMs(parsed));
        };

        delayTextBox.actionOnUnfocused = () -> {
            int fallback = rules.get(ruleIndex).chatDelayMs();
            int normalized = normalizeChatDelayMs(delayTextBox.get(), fallback);
            updateRule(setting, rules, ruleIndex, rules.get(ruleIndex).withChatDelayMs(normalized));
            delayTextBox.set(Integer.toString(normalized));
        };
    }

    public static String normalizeLocalSoundValue(String currentValue, List<String> localSounds) {
        if (localSounds == null || localSounds.isEmpty()) return "";

        String normalized = currentValue == null ? "" : currentValue.trim();
        if (normalized.isEmpty() || !localSounds.contains(normalized)) return localSounds.getFirst();

        return normalized;
    }

    public static TrackerPlayerRule normalizeRuleLocalSoundValues(TrackerPlayerRule rule, List<String> localSounds) {
        if (rule == null) return null;
        if (localSounds == null || localSounds.isEmpty()) {
            return rule
                .withJoinSoundValue("")
                .withLeaveSoundValue("")
                .withDeathSoundValue("");
        }

        return rule
            .withJoinSoundValue(normalizeLocalSoundValue(rule.joinSoundValue(), localSounds))
            .withLeaveSoundValue(normalizeLocalSoundValue(rule.leaveSoundValue(), localSounds))
            .withDeathSoundValue(normalizeLocalSoundValue(rule.deathSoundValue(), localSounds));
    }

    private static TrackerPlayerRule.Trigger getTestTrigger(TrackEventMode eventMode) {
        return switch (eventMode) {
            case Leave -> TrackerPlayerRule.Trigger.Leave;
            case Death -> TrackerPlayerRule.Trigger.Death;
            case Join, Both -> TrackerPlayerRule.Trigger.Join;
        };
    }

    private static String soundValueForSlot(TrackerPlayerRule rule, EventSoundSlot slot) {
        return switch (slot) {
            case Join -> rule.joinSoundValue();
            case Leave -> rule.leaveSoundValue();
            case Death -> rule.deathSoundValue();
        };
    }

    private static TrackerPlayerRule withSoundValueForSlot(TrackerPlayerRule rule, EventSoundSlot slot, String value) {
        return switch (slot) {
            case Join -> rule.withJoinSoundValue(value);
            case Leave -> rule.withLeaveSoundValue(value);
            case Death -> rule.withDeathSoundValue(value);
        };
    }

    private static void addHeader(WTable table, GuiTheme theme, String title, String tooltip, double width) {
        table.add(theme.label(title)).minWidth(width).expandX().center().widget().tooltip = tooltip;
    }

    private static WHorizontalList addColumnCell(GuiTheme theme, WTable table, double width) {
        return table.add(theme.horizontalList())
            .minWidth(width)
            .expandX()
            .centerY()
            .widget();
    }

    private static double computeUiScale() {
        if (mc == null || mc.getWindow() == null) return 1.0;

        double availableWidth = Math.max(640, mc.getWindow().getScaledWidth() - RULE_TABLE_MARGIN);
        double scale = availableWidth / RULE_TABLE_BASE_WIDTH;
        if (scale >= 1.0) return 1.0;
        return Math.max(MIN_UI_SCALE, scale);
    }

    private static double scaleWidth(double baseWidth, double uiScale, double minWidth) {
        return Math.max(minWidth, Math.round(baseWidth * uiScale));
    }

    private static String compactSoundValue(String value, int maxChars) {
        if (value == null) return "";
        if (maxChars < 4 || value.length() <= maxChars) return value;
        return value.substring(0, maxChars - 3) + "...";
    }

    private enum EventSoundSlot {
        Join("J", "Join sound."),
        Leave("L", "Leave sound."),
        Death("D", "Death sound.");

        private final String shortLabel;
        private final String tooltip;

        EventSoundSlot(String shortLabel, String tooltip) {
            this.shortLabel = shortLabel;
            this.tooltip = tooltip;
        }
    }

    private static Integer parseVolumePercent(String value) {
        if (value == null || value.isBlank()) return null;

        try {
            int parsed = Integer.parseInt(value.trim());
            return TrackerPlayerRule.clampOggVolumePercent(parsed);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static int normalizeVolumePercent(String rawValue, int fallback) {
        Integer parsed = parseVolumePercent(rawValue);
        if (parsed != null) return parsed;
        return TrackerPlayerRule.clampOggVolumePercent(fallback);
    }

    private static Integer parseChatDelayMs(String value) {
        if (value == null || value.isBlank()) return null;

        try {
            int parsed = Integer.parseInt(value.trim());
            return TrackerPlayerRule.clampChatDelayMs(parsed);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static int normalizeChatDelayMs(String rawValue, int fallback) {
        Integer parsed = parseChatDelayMs(rawValue);
        if (parsed != null) return parsed;
        return TrackerPlayerRule.clampChatDelayMs(fallback);
    }

    private static String getModuleDefaultSoundSpec() {
        try {
            JoinWatcher module = Modules.get().get(JoinWatcher.class);
            if (module == null) return "";

            Setting<?> defaultSoundSetting = module.settings.get("default-sound");
            if (defaultSoundSetting == null) return "";

            Object value = defaultSoundSetting.get();
            return value instanceof String ? (String) value : "";
        } catch (Exception ignored) {
            return "";
        }
    }

    private static TrackerPlayerRule createDefaultRule(String playerName, String localSound) {
        return new TrackerPlayerRule(
            playerName,
            TrackEventMode.Join,
            true,
            false,
            "",
            SoundSourceMode.LocalFolder,
            localSound,
            localSound,
            localSound,
            TrackerPlayerRule.DEFAULT_OGG_VOLUME_PERCENT,
            TrackerPlayerRule.DEFAULT_CHAT_DELAY_MS
        );
    }

    private static void updateRule(
        TrackerPlayersSetting setting,
        ArrayList<TrackerPlayerRule> rules,
        int ruleIndex,
        TrackerPlayerRule updatedRule
    ) {
        rules.set(ruleIndex, updatedRule);
        setting.set(new ArrayList<>(rules));
    }

    public static NbtList rulesToNbt(List<TrackerPlayerRule> rules) {
        NbtList valueTag = new NbtList();
        for (TrackerPlayerRule rule : rules) {
            NbtCompound ruleTag = new NbtCompound();
            ruleTag.putString("player", rule.playerName());
            ruleTag.putString("event-mode", rule.eventMode().name());
            ruleTag.putBoolean("sound-enabled", rule.soundEnabled());
            ruleTag.putBoolean("send-enabled", rule.sendEnabled());
            ruleTag.putString("command-text", rule.commandText());
            ruleTag.putString("sound-source", rule.soundSource().name());
            ruleTag.putString("join-sound-value", rule.joinSoundValue());
            ruleTag.putString("leave-sound-value", rule.leaveSoundValue());
            ruleTag.putString("death-sound-value", rule.deathSoundValue());
            ruleTag.putInt("ogg-volume-percent", rule.oggVolumePercent());
            ruleTag.putInt("chat-delay-ms", rule.chatDelayMs());
            valueTag.add(ruleTag);
        }
        return valueTag;
    }

    public static List<TrackerPlayerRule> rulesFromNbt(NbtList valueTag) {
        List<TrackerPlayerRule> rules = new ArrayList<>();
        for (NbtElement nbtElement : valueTag) {
            if (!(nbtElement instanceof NbtCompound ruleTag)) continue;

            TrackEventMode eventMode = parseEnum(ruleTag.getString("event-mode", ""), TrackEventMode.Join, TrackEventMode.values());
            SoundSourceMode soundSource = parseEnum(ruleTag.getString("sound-source", ""), SoundSourceMode.LocalFolder, SoundSourceMode.values());
            String joinSoundValue = ruleTag.getString("join-sound-value", "");
            String leaveSoundValue = ruleTag.getString("leave-sound-value", "");
            String deathSoundValue = ruleTag.getString("death-sound-value", "");

            if (joinSoundValue.isBlank() && leaveSoundValue.isBlank() && deathSoundValue.isBlank()) {
                String legacySoundValue = ruleTag.getString("sound-value", "");
                joinSoundValue = legacySoundValue;
                leaveSoundValue = legacySoundValue;
                deathSoundValue = legacySoundValue;
            }

            rules.add(new TrackerPlayerRule(
                ruleTag.getString("player", ""),
                eventMode,
                ruleTag.getBoolean("sound-enabled", true),
                ruleTag.getBoolean("send-enabled", false),
                ruleTag.getString("command-text", ""),
                soundSource,
                joinSoundValue,
                leaveSoundValue,
                deathSoundValue,
                ruleTag.getInt("ogg-volume-percent", TrackerPlayerRule.DEFAULT_OGG_VOLUME_PERCENT),
                ruleTag.getInt("chat-delay-ms", TrackerPlayerRule.DEFAULT_CHAT_DELAY_MS)
            ));
        }
        return rules;
    }

    private static <E extends Enum<E>> E parseEnum(String value, E fallback, E[] constants) {
        for (E constant : constants) {
            if (constant.name().equalsIgnoreCase(value)) return constant;
        }
        return fallback;
    }

    public static class Builder extends SettingBuilder<Builder, List<TrackerPlayerRule>, TrackerPlayersSetting> {
        public Builder() {
            super(new ArrayList<>(0));
        }

        public Builder defaultValue(TrackerPlayerRule... defaults) {
            return defaultValue(defaults != null ? Arrays.asList(defaults) : new ArrayList<>());
        }

        @Override
        public TrackerPlayersSetting build() {
            return new TrackerPlayersSetting(name, description, defaultValue, onChanged, onModuleActivated, visible);
        }
    }
}
