package com.example.addon.modules.games;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.meteor.KeyEvent;
import meteordevelopment.meteorclient.gui.WidgetScreen;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.misc.input.KeyAction;
import meteordevelopment.orbit.EventHandler;
import org.lwjgl.glfw.GLFW;

public final class GamesModule extends Module {
    public enum QuickGame {
        CHESS("Chess"),
        CHECKERS("Checkers"),
        BLACKJACK("Blackjack"),
        SLOT_MACHINE("One-Armed Bandit"),
        RUSSIAN_ROULETTE("Russian Roulette"),
        DOOM("DevilsDoom");

        private final String title;

        QuickGame(String title) {
            this.title = title;
        }

        public String title() {
            return title;
        }

        public QuickGame next() {
            QuickGame[] values = values();
            return values[(ordinal() + 1) % values.length];
        }
    }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final Setting<QuickGame> selected = sgGeneral.add(new EnumSetting.Builder<QuickGame>()
        .name("selected-game")
        .description("Currently selected game for Games module.")
        .defaultValue(QuickGame.CHECKERS)
        .visible(() -> false)
        .build()
    );

    public GamesModule() {
        super(AddonTemplate.CATEGORY, "games", "Devils-Game launcher.");
        runInMainMenu = true;
    }

    @Override
    public void onActivate() {
        launchSelectedGame();
    }

    @Override
    public void onDeactivate() {
        deactivateAllGames();
    }

    @EventHandler
    private void onKey(KeyEvent event) {
        if (!isActive() || event.action != KeyAction.Press) return;
        if (mc != null && mc.currentScreen instanceof WidgetScreen) return;
        int key = event.key();
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            closeAllFromAny();
        }
    }

    public static void markActive(QuickGame game) {
        GamesModule module = instance();
        if (module == null || game == null) return;
        if (module.selected.get() != game) module.selected.set(game);
    }

    public static void cycleFrom(QuickGame current) {
        GamesModule module = instance();
        if (module == null || current == null) return;
        module.selected.set(current.next());
        if (!module.isActive()) module.toggle();
        else module.launchSelectedGame();
    }

    public static void closeAllFromAny() {
        GamesModule module = instance();
        if (module != null) {
            if (module.isActive()) module.toggle();
            else module.deactivateAllGames();
            return;
        }
        deactivateAllStatic();
    }

    private static GamesModule instance() {
        Modules modules = Modules.get();
        return modules == null ? null : modules.get(GamesModule.class);
    }

    private static void deactivateAllStatic() {
        Modules modules = Modules.get();
        if (modules == null) return;
        disable(modules, ChessOverlay.class);
        disable(modules, DevilsGameOverlay.class);
        disable(modules, BlackjackOverlay.class);
        disable(modules, SlotMachineOverlay.class);
        disable(modules, RussianRouletteOverlay.class);
        disable(modules, DoomOverlay.class);
    }

    private void launchSelectedGame() {
        deactivateAllGames();
        switch (selected.get()) {
            case CHESS -> launch(ChessOverlay.class);
            case CHECKERS -> launch(DevilsGameOverlay.class);
            case BLACKJACK -> launch(BlackjackOverlay.class);
            case SLOT_MACHINE -> launch(SlotMachineOverlay.class);
            case RUSSIAN_ROULETTE -> launch(RussianRouletteOverlay.class);
            case DOOM -> launch(DoomOverlay.class);
        }
    }

    private void launch(Class<? extends Module> moduleClass) {
        Modules modules = Modules.get();
        if (modules == null) return;

        Module module = modules.get(moduleClass);
        if (module == null) return;

        if (module instanceof ChessOverlay chess) chess.launchFromGames();
        else if (module instanceof DevilsGameOverlay checkers) checkers.launchFromGames();
        else if (module instanceof BlackjackOverlay blackjack) blackjack.launchFromGames();
        else if (module instanceof SlotMachineOverlay slot) slot.launchFromGames();
        else if (module instanceof RussianRouletteOverlay roulette) roulette.launchFromGames();
        else if (module instanceof DoomOverlay doom) doom.launchFromGames();
    }

    private void deactivateAllGames() {
        Modules modules = Modules.get();
        if (modules == null) return;
        disable(modules, ChessOverlay.class);
        disable(modules, DevilsGameOverlay.class);
        disable(modules, BlackjackOverlay.class);
        disable(modules, SlotMachineOverlay.class);
        disable(modules, RussianRouletteOverlay.class);
        disable(modules, DoomOverlay.class);
    }

    private static void disable(Modules modules, Class<? extends Module> klass) {
        Module module = modules.get(klass);
        if (module != null && module.isActive()) module.toggle();
    }
}
