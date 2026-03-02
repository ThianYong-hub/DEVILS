package com.example.addon.modules.highwaybuilder;

import java.awt.Color;

public enum TaskState {
    BROKEN(1000, 1000, new Color(111, 0, 0)),
    PLACED(1000, 1000, new Color(53, 222, 66)),
    LIQUID(100, 100, new Color(114, 27, 255)),
    PICKUP(500, 500, new Color(252, 3, 207)),
    RESTOCK(500, 500, new Color(252, 3, 207)),
    OPEN_CONTAINER(500, 500, new Color(252, 3, 207)),
    BREAKING(100, 100, new Color(240, 222, 60)),
    BREAK(20, 20, new Color(222, 0, 0)),
    PLACE(20, 20, new Color(35, 188, 254)),
    PENDING_BREAK(20, 20, new Color(0, 0, 0)),
    PENDING_PLACE(20, 20, new Color(0, 0, 0)),
    IMPOSSIBLE_PLACE(100, 100, new Color(16, 74, 94)),
    DONE(69420, 34, new Color(50, 50, 50));

    public final int stuckThreshold;
    public final int stuckTimeout;
    public final Color color;

    TaskState(int stuckThreshold, int stuckTimeout, Color color) {
        this.stuckThreshold = stuckThreshold;
        this.stuckTimeout = stuckTimeout;
        this.color = color;
    }
}
