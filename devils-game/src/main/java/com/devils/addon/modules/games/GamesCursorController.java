package com.devils.addon.modules.games;

import net.minecraft.client.MinecraftClient;

final class GamesCursorController {
    private static int activeOverlays;
    private static boolean cursorUnlockedByController;

    private GamesCursorController() {
    }

    static synchronized void acquire(MinecraftClient mc) {
        activeOverlays++;
        update(mc);
    }

    static synchronized void release(MinecraftClient mc) {
        if (activeOverlays > 0) activeOverlays--;
        update(mc);
    }

    static synchronized void update(MinecraftClient mc) {
        if (mc == null || mc.mouse == null) return;
        boolean shouldUnlock = activeOverlays > 0 && mc.currentScreen == null;

        if (shouldUnlock) {
            if (!cursorUnlockedByController) {
                try {
                    mc.mouse.unlockCursor();
                    cursorUnlockedByController = true;
                } catch (Throwable ignored) {
                }
            }
            return;
        }

        if (!cursorUnlockedByController) return;
        if (mc.currentScreen == null) {
            try {
                mc.mouse.lockCursor();
            } catch (Throwable ignored) {
            }
        }
        cursorUnlockedByController = false;
    }
}

