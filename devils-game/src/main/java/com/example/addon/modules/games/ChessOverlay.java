package com.example.addon.modules.games;

import com.example.addon.games.DevilsGameAddon;
import meteordevelopment.meteorclient.events.meteor.KeyEvent;
import meteordevelopment.meteorclient.events.meteor.MouseClickEvent;
import meteordevelopment.meteorclient.events.render.Render2DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.gui.WidgetScreen;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.client.MinecraftClient;
import org.lwjgl.glfw.GLFW;

public final class ChessOverlay extends Module {
    public enum PlayMode {
        SCRIPT,
        SYNC
    }

    private static final int MIN_W = 250;
    private static final int MIN_H = 300;
    private static final int MAX_W = 620;
    private static final int MAX_H = 760;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final Setting<PlayMode> mode = sgGeneral.add(new EnumSetting.Builder<PlayMode>()
        .name("mode")
        .description("Play against script or Game Sync.")
        .defaultValue(PlayMode.SCRIPT)
        .visible(() -> false)
        .build()
    );
    private final Setting<Integer> scriptLevel = sgGeneral.add(new IntSetting.Builder()
        .name("script-level")
        .description("Script strength level (1 = easy, 7 = master).")
        .defaultValue(4)
        .min(1)
        .sliderRange(1, 7)
        .visible(() -> mode.get() == PlayMode.SCRIPT)
        .build()
    );
    private final Setting<Boolean> pinned = sgGeneral.add(new BoolSetting.Builder()
        .name("pinned")
        .description("Fix the window in place. Unpin returns it to the start position.")
        .defaultValue(true)
        .visible(() -> false)
        .build()
    );
    private final Setting<Integer> startX = sgGeneral.add(new IntSetting.Builder()
        .name("start-x")
        .description("Initial X position.")
        .defaultValue(28)
        .min(0)
        .sliderRange(0, 900)
        .visible(() -> false)
        .build()
    );
    private final Setting<Integer> startY = sgGeneral.add(new IntSetting.Builder()
        .name("start-y")
        .description("Initial Y position.")
        .defaultValue(22)
        .min(0)
        .sliderRange(0, 600)
        .visible(() -> false)
        .build()
    );
    private final Setting<Integer> startW = sgGeneral.add(new IntSetting.Builder()
        .name("start-width")
        .description("Initial width.")
        .defaultValue(340)
        .min(MIN_W)
        .sliderRange(MIN_W, MAX_W)
        .visible(() -> false)
        .build()
    );
    private final Setting<Integer> startH = sgGeneral.add(new IntSetting.Builder()
        .name("start-height")
        .description("Initial height.")
        .defaultValue(440)
        .min(MIN_H)
        .sliderRange(MIN_H, MAX_H)
        .visible(() -> false)
        .build()
    );

    private final ChessOverlaySession session = new ChessOverlaySession();
    private final ChessOverlayWindow window = new ChessOverlayWindow(MIN_W, MIN_H, MAX_W, MAX_H);
    private boolean windowInitialized;

    public ChessOverlay() {
        super(DevilsGameAddon.GAMES_CATEGORY, "chess", "Standalone chess launcher with script and game-sync modes.");
        runInMainMenu = true;
    }

    @Override
    public void onActivate() {
        ensureWindowInitialized();
        GameLaunchCoordinator.activateExclusive(ChessOverlay.class);
        session.setScriptLevel(scriptLevel.get());
        session.onActivate(mode.get());
        GamesCursorController.acquire(client());
    }

    @Override
    public void onDeactivate() {
        window.stopInteraction();
        GamesCursorController.release(client());
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        GameCrashGuard.run(this, "chessOverlayTick", () -> {
            if (!isActive()) return;
            session.setScriptLevel(scriptLevel.get());
            session.onTick(mode.get());
            GamesCursorController.update(client());
        });
    }

    @EventHandler
    private void onKey(KeyEvent event) {
        if (!isActive() || event.action != meteordevelopment.meteorclient.utils.misc.input.KeyAction.Press) return;
        if (mc.currentScreen instanceof WidgetScreen) return;
        int key = event.key();
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            GameLaunchCoordinator.closeAll();
        }
    }

    @EventHandler
    private void onRender2D(Render2DEvent event) {
        GameCrashGuard.run(this, "chessOverlayRender", () -> {
            if (!isActive()) return;
            window.render(event.drawContext, client(), mode.get(), pinned.get(), session, scriptLevel.get());
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST + 1)
    private void onMouse(MouseClickEvent event) {
        GameCrashGuard.run(this, "chessOverlayMouse", () -> {
            if (!isActive()) return;
            if (mc.currentScreen instanceof WidgetScreen) return;
            boolean consumed = window.onMouse(
                event,
                client(),
                mode.get(),
                pinned.get(),
                scriptLevel.get(),
                this::setPinned,
                this::setMode,
                this::setScriptLevel,
                () -> GameLaunchCoordinator.launchNext(GameLaunchCoordinator.Entry.CHESS),
                GameLaunchCoordinator::closeAll,
                session
            );
            if (consumed) event.setCancelled(true);
        });
    }

    private void ensureWindowInitialized() {
        if (windowInitialized) return;
        window.reset(
            startX.get(),
            startY.get(),
            clamp(startW.get(), MIN_W, MAX_W),
            clamp(startH.get(), MIN_H, MAX_H)
        );
        windowInitialized = true;
    }

    private void setPinned(boolean value) {
        pinned.set(value);
        if (value) window.stopInteraction();
        else {
            window.restoreBounds(
                startX.get(),
                startY.get(),
                clamp(startW.get(), MIN_W, MAX_W),
                clamp(startH.get(), MIN_H, MAX_H)
            );
        }
    }

    private void setMode(PlayMode value) {
        mode.set(value);
        session.setScriptLevel(scriptLevel.get());
    }

    private void setScriptLevel(int value) {
        scriptLevel.set(clamp(value, 1, 7));
        session.setScriptLevel(scriptLevel.get());
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private MinecraftClient client() {
        return mc;
    }
}

