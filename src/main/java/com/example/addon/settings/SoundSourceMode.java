package com.example.addon.settings;

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
