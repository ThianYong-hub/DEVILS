package com.example.addon.settings;

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
