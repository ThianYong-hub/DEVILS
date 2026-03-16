package com.example.addon.settings;

import com.example.addon.audio.JoinSoundPlayer;
import com.example.addon.gui.screens.settings.SelectionScreens.GameSoundSelectScreen;
import com.example.addon.modules.JoinWatcher;
import com.example.addon.settings.TrackerPlayerRule.SoundSourceMode;
import com.example.addon.settings.TrackerPlayerRule.TrackEventMode;
import com.example.addon.settings.TrackerPlayerRule.TrackerEventSoundSlot;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.renderer.GuiRenderer;
import meteordevelopment.meteorclient.gui.widgets.containers.WHorizontalList;
import meteordevelopment.meteorclient.gui.widgets.containers.WTable;
import meteordevelopment.meteorclient.gui.widgets.input.WDropdown;
import meteordevelopment.meteorclient.gui.widgets.input.WTextBox;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.gui.widgets.pressable.WCheckbox;
import meteordevelopment.meteorclient.gui.widgets.pressable.WMinus;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import net.minecraft.util.Util;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static meteordevelopment.meteorclient.MeteorClient.mc;

final class TrackerPlayersSettingUi {
    private TrackerPlayersSettingUi() {}

    static void addEventSoundEditors(
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

        addEventSoundEditor(theme, rootTable, soundControl, setting, rules, ruleIndex, localSounds, TrackerEventSoundSlot.Join, rule.soundSource(), uiScale);
        addEventSoundEditor(theme, rootTable, soundControl, setting, rules, ruleIndex, localSounds, TrackerEventSoundSlot.Leave, rule.soundSource(), uiScale);
        addEventSoundEditor(theme, rootTable, soundControl, setting, rules, ruleIndex, localSounds, TrackerEventSoundSlot.Death, rule.soundSource(), uiScale);
    }

    static void addLocalVolumeEditor(
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

    static void addChatDelayEditor(
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

    static String normalizeLocalSoundValue(String currentValue, List<String> localSounds) {
        if (localSounds == null || localSounds.isEmpty()) return "";
        String normalized = currentValue == null ? "" : currentValue.trim();
        if (normalized.isEmpty() || !localSounds.contains(normalized)) return localSounds.getFirst();
        return normalized;
    }

    static TrackerPlayerRule normalizeRuleLocalSoundValues(TrackerPlayerRule rule, List<String> localSounds) {
        if (rule == null) return null;
        if (localSounds == null || localSounds.isEmpty()) {
            return rule.withJoinSoundValue("").withLeaveSoundValue("").withDeathSoundValue("");
        }

        return rule
            .withJoinSoundValue(normalizeLocalSoundValue(rule.joinSoundValue(), localSounds))
            .withLeaveSoundValue(normalizeLocalSoundValue(rule.leaveSoundValue(), localSounds))
            .withDeathSoundValue(normalizeLocalSoundValue(rule.deathSoundValue(), localSounds));
    }

    static TrackerPlayerRule.Trigger getTestTrigger(TrackEventMode eventMode) {
        return switch (eventMode) {
            case Leave -> TrackerPlayerRule.Trigger.Leave;
            case Death -> TrackerPlayerRule.Trigger.Death;
            case Join, Both -> TrackerPlayerRule.Trigger.Join;
        };
    }

    static String soundValueForSlot(TrackerPlayerRule rule, TrackerEventSoundSlot slot) {
        return switch (slot) {
            case Join -> rule.joinSoundValue();
            case Leave -> rule.leaveSoundValue();
            case Death -> rule.deathSoundValue();
        };
    }

    static TrackerPlayerRule withSoundValueForSlot(TrackerPlayerRule rule, TrackerEventSoundSlot slot, String value) {
        return switch (slot) {
            case Join -> rule.withJoinSoundValue(value);
            case Leave -> rule.withLeaveSoundValue(value);
            case Death -> rule.withDeathSoundValue(value);
        };
    }

    static void addHeader(WTable table, GuiTheme theme, String title, String tooltip, double width) {
        table.add(theme.label(title)).minWidth(width).expandX().center().widget().tooltip = tooltip;
    }

    static WHorizontalList addColumnCell(GuiTheme theme, WTable table, double width) {
        return table.add(theme.horizontalList()).minWidth(width).expandX().centerY().widget();
    }

    static double computeUiScale() {
        if (mc == null || mc.getWindow() == null) return 1.0;

        double availableWidth = Math.max(640, mc.getWindow().getScaledWidth() - TrackerPlayersSetting.RULE_TABLE_MARGIN);
        double scale = availableWidth / TrackerPlayersSetting.RULE_TABLE_BASE_WIDTH;
        if (scale >= 1.0) return 1.0;
        return Math.max(TrackerPlayersSetting.MIN_UI_SCALE, scale);
    }

    static double scaleWidth(double baseWidth, double uiScale, double minWidth) {
        return Math.max(minWidth, Math.round(baseWidth * uiScale));
    }

    static TrackerPlayerRule createDefaultRule(String playerName, String localSound) {
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

    static void updateRule(
        TrackerPlayersSetting setting,
        ArrayList<TrackerPlayerRule> rules,
        int ruleIndex,
        TrackerPlayerRule updatedRule
    ) {
        rules.set(ruleIndex, updatedRule);
        setting.set(new ArrayList<>(rules));
    }

    private static void addEventSoundEditor(
        GuiTheme theme,
        WTable rootTable,
        WHorizontalList soundControl,
        TrackerPlayersSetting setting,
        ArrayList<TrackerPlayerRule> rules,
        int ruleIndex,
        List<String> localSounds,
        TrackerEventSoundSlot slot,
        SoundSourceMode sourceMode,
        double uiScale
    ) {
        double slotWidth = scaleWidth(TrackerPlayersSetting.SOUND_SLOT_WIDTH, uiScale, 52);
        double slotInputWidth = scaleWidth(TrackerPlayersSetting.SOUND_SLOT_INPUT_WIDTH, uiScale, 38);
        double slotSelectWidth = scaleWidth(TrackerPlayersSetting.SOUND_SLOT_SELECT_WIDTH, uiScale, 22);
        WHorizontalList slotControl = soundControl.add(theme.horizontalList()).minWidth(slotWidth).centerY().widget();
        slotControl.add(theme.label(slot.shortLabel)).widget().tooltip = slot.tooltip;

        switch (sourceMode) {
            case LocalFolder -> {
                String[] values = localSounds.isEmpty()
                    ? new String[] { TrackerPlayersSetting.NO_LOCAL_SOUNDS }
                    : localSounds.toArray(String[]::new);

                TrackerPlayerRule currentRule = rules.get(ruleIndex);
                String selectedValue = soundValueForSlot(currentRule, slot);
                String selected = localSounds.isEmpty() ? TrackerPlayersSetting.NO_LOCAL_SOUNDS : selectedValue;

                WDropdown<String> localDropdown = slotControl.add(theme.dropdown(values, selected)).minWidth(slotInputWidth).widget();
                localDropdown.tooltip = slot.tooltip + " Local .ogg from " + TrackerPlayersSetting.SOUNDS_FOLDER_LABEL + ".";
                localDropdown.action = () -> {
                    String value = localDropdown.get();
                    if (TrackerPlayersSetting.NO_LOCAL_SOUNDS.equals(value)) return;
                    updateRule(setting, rules, ruleIndex, withSoundValueForSlot(rules.get(ruleIndex), slot, value));
                };
            }
            case GameRegistry -> {
                TrackerPlayerRule currentRule = rules.get(ruleIndex);
                String full = soundValueForSlot(currentRule, slot);
                String display = full.isBlank() ? "(none)" : compactSoundValue(full, TrackerPlayersSetting.GAME_SOUND_SLOT_TEXT_MAX);

                slotControl.add(theme.label(display)).minWidth(Math.max(20, slotInputWidth - slotSelectWidth - 4)).centerY().widget().tooltip =
                    full.isBlank() ? "(none)" : full;

                WButton selectGameSound = slotControl.add(theme.button("...")).minWidth(slotSelectWidth).widget();
                selectGameSound.tooltip = slot.tooltip + " Select a sound id from game registry.";
                selectGameSound.action = () -> mc.setScreen(new GameSoundSelectScreen(theme, selectedSound -> {
                    updateRule(setting, rules, ruleIndex, withSoundValueForSlot(rules.get(ruleIndex), slot, selectedSound));
                    TrackerPlayersSetting.fillTable(theme, rootTable, setting);
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

    private static String compactSoundValue(String value, int maxChars) {
        if (value == null) return "";
        if (maxChars < 4 || value.length() <= maxChars) return value;
        return value.substring(0, maxChars - 3) + "...";
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

    static String getModuleDefaultSoundSpec() {
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
}




