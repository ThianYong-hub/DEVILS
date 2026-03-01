package com.example.addon.modules.highwaybuilder;

import java.util.concurrent.ConcurrentLinkedDeque;

public class HighwayStatistics {
    public int totalBlocksBroken = 0;
    public int totalBlocksPlaced = 0;
    public int durabilityUsages = 0;

    public final ConcurrentLinkedDeque<Long> simpleMovingAverageBreaks = new ConcurrentLinkedDeque<>();
    public final ConcurrentLinkedDeque<Long> simpleMovingAveragePlaces = new ConcurrentLinkedDeque<>();
    public final ConcurrentLinkedDeque<Long> simpleMovingAverageDistance = new ConcurrentLinkedDeque<>();

    private static final long WINDOW_MS = 60_000L; // 60 seconds

    public void update() {
        long now = System.currentTimeMillis();
        cleanDeque(simpleMovingAverageBreaks, now);
        cleanDeque(simpleMovingAveragePlaces, now);
        cleanDeque(simpleMovingAverageDistance, now);
    }

    private void cleanDeque(ConcurrentLinkedDeque<Long> deque, long now) {
        while (!deque.isEmpty() && now - deque.peekFirst() > WINDOW_MS) {
            deque.pollFirst();
        }
    }

    public double getBreaksPerSecond() {
        return simpleMovingAverageBreaks.size() / (WINDOW_MS / 1000.0);
    }

    public double getPlacesPerSecond() {
        return simpleMovingAveragePlaces.size() / (WINDOW_MS / 1000.0);
    }

    public double getDistancePerHour() {
        return simpleMovingAverageDistance.size() * (3600_000.0 / WINDOW_MS);
    }

    public void reset() {
        totalBlocksBroken = 0;
        totalBlocksPlaced = 0;
        durabilityUsages = 0;
        simpleMovingAverageBreaks.clear();
        simpleMovingAveragePlaces.clear();
        simpleMovingAverageDistance.clear();
    }

    public String getInfoString() {
        return String.format("B:%.1f/s P:%.1f/s D:%.0f/h",
            getBreaksPerSecond(), getPlacesPerSecond(), getDistancePerHour());
    }
}
