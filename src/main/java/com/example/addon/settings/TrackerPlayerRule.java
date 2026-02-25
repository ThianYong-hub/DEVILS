package com.example.addon.settings;

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

    public enum Trigger {
        Join,
        Leave,
        Death
    }
}
