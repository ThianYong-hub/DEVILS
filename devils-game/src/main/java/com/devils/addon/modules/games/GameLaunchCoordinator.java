package com.devils.addon.modules.games;

import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;

final class GameLaunchCoordinator {
    enum Entry {
        CHECKERS(CheckersOverlay.class),
        CHESS(ChessOverlay.class),
        SLOT_MACHINE(SlotMachineOverlay.class),
        BLACKJACK(BlackjackOverlay.class),
        RUSSIAN_ROULETTE(RussianRouletteOverlay.class),
        DOOM(DoomOverlay.class);

        private final Class<? extends Module> moduleClass;

        Entry(Class<? extends Module> moduleClass) {
            this.moduleClass = moduleClass;
        }

        Class<? extends Module> moduleClass() {
            return moduleClass;
        }

        Entry next() {
            Entry[] values = values();
            return values[(ordinal() + 1) % values.length];
        }
    }

    private GameLaunchCoordinator() {
    }

    static void activateExclusive(Class<? extends Module> activeClass) {
        Modules modules = Modules.get();
        if (modules == null || activeClass == null) return;
        for (Entry entry : Entry.values()) {
            if (entry.moduleClass() == activeClass) continue;
            disable(modules, entry.moduleClass());
        }
    }

    static void launch(Entry entry) {
        Modules modules = Modules.get();
        if (modules == null || entry == null) return;
        closeAll();
        Module module = modules.get(entry.moduleClass());
        if (module != null && !module.isActive()) module.toggle();
    }

    static void launchNext(Entry currentEntry) {
        if (currentEntry == null) return;
        launch(currentEntry.next());
    }

    static void closeAll() {
        Modules modules = Modules.get();
        if (modules == null) return;
        for (Entry entry : Entry.values()) disable(modules, entry.moduleClass());
    }

    private static void disable(Modules modules, Class<? extends Module> klass) {
        Module module = modules.get(klass);
        if (module != null && module.isActive()) module.toggle();
    }
}
