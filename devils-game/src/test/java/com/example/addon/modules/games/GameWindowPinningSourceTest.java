package com.example.addon.modules.games;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GameWindowPinningSourceTest {
    @Test
    void overlaysRestoreDefaultBoundsWhenUnpinned() throws IOException {
        String overlaySources = read("BlackjackOverlay.java")
            + read("CheckersOverlay.java")
            + read("ChessOverlay.java")
            + read("DoomOverlay.java")
            + read("RussianRouletteOverlay.java")
            + read("SlotMachineOverlay.java");

        assertTrue(overlaySources.contains("window.restoreBounds("));
        assertTrue(overlaySources.contains("if (value) window.stopInteraction();"));
        assertTrue(overlaySources.contains("Fix the window in place. Unpin returns it to the start position."));
    }

    @Test
    void windowSourcesUsePinForLockingInsteadOfVisibility() throws IOException {
        String windowSources = read("BlackjackWindow.java")
            + read("CheckersOverlayWindow.java")
            + read("ChessOverlayWindow.java")
            + read("DoomWindow.java")
            + read("RussianRouletteWindow.java")
            + read("SlotMachineWindow.java");

        assertTrue(windowSources.contains("void restoreBounds(int x, int y, int w, int h)"));
        assertTrue(windowSources.contains("if (!pinned && inside(mouseX, mouseY, l.resizeX, l.resizeY, l.resizeSize, l.resizeSize))"));
        assertTrue(windowSources.contains("if (!pinned && inside(mouseX, mouseY, l.x, l.y, l.w, l.headerH))"));
        assertTrue(windowSources.contains("return mc != null && mc.player != null;"));
        assertFalse(windowSources.contains("return pinned || screen == null;"));
        assertFalse(windowSources.contains("return pinned || current == null;"));
    }

    @Test
    void slotMachineFooterKeepsBetButtonsAwayFromText() throws IOException {
        String slotSource = read("SlotMachineWindow.java");

        assertTrue(slotSource.contains("rightColumnMinX"));
        assertTrue(slotSource.contains("betTextW"));
        assertTrue(slotSource.contains("Won: \" + session.totalWon() + \"  Spent: \" + session.totalSpent()"));
    }

    private static String read(String fileName) throws IOException {
        return Files.readString(Path.of("src", "main", "java", "com", "example", "addon", "modules", "games", fileName));
    }
}
