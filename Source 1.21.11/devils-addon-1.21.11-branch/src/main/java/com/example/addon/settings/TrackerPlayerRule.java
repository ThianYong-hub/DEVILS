package com.example.addon.settings;

import meteordevelopment.meteorclient.settings.Setting;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public record TrackerPlayerRule(
    String playerName,
    TrackEventMode eventMode,
    boolean soundEnabled,
    boolean sendEnabled,
    String commandText,
    SoundSourceMode soundSource,
    String joinSoundValue,
    String leaveSoundValue,
    String deathSoundValue,
    int oggVolumePercent,
    int chatDelayMs
) {
    public static final int MIN_OGG_VOLUME_PERCENT = 0;
    public static final int MAX_OGG_VOLUME_PERCENT = 200;
    public static final int DEFAULT_OGG_VOLUME_PERCENT = 100;
    public static final int MIN_CHAT_DELAY_MS = 0;
    public static final int MAX_CHAT_DELAY_MS = 3_600_000;
    public static final int DEFAULT_CHAT_DELAY_MS = 0;

    public TrackerPlayerRule {
        playerName = playerName == null ? "" : playerName;
        eventMode = eventMode == null ? TrackEventMode.Join : eventMode;
        commandText = commandText == null ? "" : commandText;
        soundSource = soundSource == null ? SoundSourceMode.LocalFolder : soundSource;
        joinSoundValue = joinSoundValue == null ? "" : joinSoundValue;
        leaveSoundValue = leaveSoundValue == null ? "" : leaveSoundValue;
        deathSoundValue = deathSoundValue == null ? "" : deathSoundValue;
        oggVolumePercent = clampOggVolumePercent(oggVolumePercent);
        chatDelayMs = clampChatDelayMs(chatDelayMs);
    }

    public TrackerPlayerRule withPlayerName(String value) {
        return new TrackerPlayerRule(value, eventMode, soundEnabled, sendEnabled, commandText, soundSource, joinSoundValue, leaveSoundValue, deathSoundValue, oggVolumePercent, chatDelayMs);
    }

    public TrackerPlayerRule withEventMode(TrackEventMode value) {
        return new TrackerPlayerRule(playerName, value, soundEnabled, sendEnabled, commandText, soundSource, joinSoundValue, leaveSoundValue, deathSoundValue, oggVolumePercent, chatDelayMs);
    }

    public TrackerPlayerRule withSoundEnabled(boolean value) {
        return new TrackerPlayerRule(playerName, eventMode, value, sendEnabled, commandText, soundSource, joinSoundValue, leaveSoundValue, deathSoundValue, oggVolumePercent, chatDelayMs);
    }

    public TrackerPlayerRule withSendEnabled(boolean value) {
        return new TrackerPlayerRule(playerName, eventMode, soundEnabled, value, commandText, soundSource, joinSoundValue, leaveSoundValue, deathSoundValue, oggVolumePercent, chatDelayMs);
    }

    public TrackerPlayerRule withCommandText(String value) {
        return new TrackerPlayerRule(playerName, eventMode, soundEnabled, sendEnabled, value, soundSource, joinSoundValue, leaveSoundValue, deathSoundValue, oggVolumePercent, chatDelayMs);
    }

    public TrackerPlayerRule withSoundSource(SoundSourceMode value) {
        return new TrackerPlayerRule(playerName, eventMode, soundEnabled, sendEnabled, commandText, value, joinSoundValue, leaveSoundValue, deathSoundValue, oggVolumePercent, chatDelayMs);
    }

    public TrackerPlayerRule withJoinSoundValue(String value) {
        return new TrackerPlayerRule(playerName, eventMode, soundEnabled, sendEnabled, commandText, soundSource, value, leaveSoundValue, deathSoundValue, oggVolumePercent, chatDelayMs);
    }

    public TrackerPlayerRule withLeaveSoundValue(String value) {
        return new TrackerPlayerRule(playerName, eventMode, soundEnabled, sendEnabled, commandText, soundSource, joinSoundValue, value, deathSoundValue, oggVolumePercent, chatDelayMs);
    }

    public TrackerPlayerRule withDeathSoundValue(String value) {
        return new TrackerPlayerRule(playerName, eventMode, soundEnabled, sendEnabled, commandText, soundSource, joinSoundValue, leaveSoundValue, value, oggVolumePercent, chatDelayMs);
    }

    public TrackerPlayerRule withOggVolumePercent(int value) {
        return new TrackerPlayerRule(playerName, eventMode, soundEnabled, sendEnabled, commandText, soundSource, joinSoundValue, leaveSoundValue, deathSoundValue, value, chatDelayMs);
    }

    public TrackerPlayerRule withChatDelayMs(int value) {
        return new TrackerPlayerRule(playerName, eventMode, soundEnabled, sendEnabled, commandText, soundSource, joinSoundValue, leaveSoundValue, deathSoundValue, oggVolumePercent, value);
    }

    public String soundValueFor(Trigger trigger) {
        if (trigger == null) return joinSoundValue;

        return switch (trigger) {
            case Join -> joinSoundValue;
            case Leave -> leaveSoundValue;
            case Death -> deathSoundValue;
        };
    }

    public static int clampOggVolumePercent(int value) {
        if (value < MIN_OGG_VOLUME_PERCENT) return MIN_OGG_VOLUME_PERCENT;
        if (value > MAX_OGG_VOLUME_PERCENT) return MAX_OGG_VOLUME_PERCENT;
        return value;
    }

    public static int clampChatDelayMs(int value) {
        if (value < MIN_CHAT_DELAY_MS) return MIN_CHAT_DELAY_MS;
        if (value > MAX_CHAT_DELAY_MS) return MAX_CHAT_DELAY_MS;
        return value;
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
                ruleTag.getInt("ogg-volume-percent", DEFAULT_OGG_VOLUME_PERCENT),
                ruleTag.getInt("chat-delay-ms", DEFAULT_CHAT_DELAY_MS)
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

    public enum Trigger {
        Join,
        Leave,
        Death
    }

    public enum TrackEventMode {
        Join("Join"),
        Leave("Leave"),
        Both("Both"),
        Death("Death");

        private final String title;

        TrackEventMode(String title) {
            this.title = title;
        }

        @Override
        public String toString() {
            return title;
        }
    }

    public enum SoundSourceMode {
        LocalFolder("Local folder"),
        GameRegistry("Game sound"),
        ManualId("Manual ID");

        private final String title;

        SoundSourceMode(String title) {
            this.title = title;
        }

        @Override
        public String toString() {
            return title;
        }
    }

    enum TrackerEventSoundSlot {
        Join("J", "Join sound."),
        Leave("L", "Leave sound."),
        Death("D", "Death sound.");

        final String shortLabel;
        final String tooltip;

        TrackerEventSoundSlot(String shortLabel, String tooltip) {
            this.shortLabel = shortLabel;
            this.tooltip = tooltip;
        }
    }

    public static class SettingBuilder extends Setting.SettingBuilder<SettingBuilder, List<TrackerPlayerRule>, TrackerPlayersSetting> {
        public SettingBuilder() {
            super(new ArrayList<>(0));
        }

        public SettingBuilder defaultValue(TrackerPlayerRule... defaults) {
            return defaultValue(defaults != null ? Arrays.asList(defaults) : new ArrayList<>());
        }

        @Override
        public TrackerPlayersSetting build() {
            return new TrackerPlayersSetting(name, description, defaultValue, onChanged, onModuleActivated, visible);
        }
    }
}


