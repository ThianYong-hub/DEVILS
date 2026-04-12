package com.example.addon.modules.games;

import com.example.addon.games.DevilsGameAddon;
import meteordevelopment.meteorclient.events.meteor.KeyEvent;
import meteordevelopment.meteorclient.events.meteor.MouseClickEvent;
import meteordevelopment.meteorclient.events.render.Render2DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.gui.WidgetScreen;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.client.MinecraftClient;
import org.lwjgl.glfw.GLFW;

public final class BlackjackOverlay extends Module {
    private static final int MIN_W = 520;
    private static final int MIN_H = 360;
    private static final int MAX_W = 880;
    private static final int MAX_H = 620;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
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
        .defaultValue(30)
        .min(0)
        .sliderRange(0, 1000)
        .visible(() -> false)
        .build()
    );
    private final Setting<Integer> startY = sgGeneral.add(new IntSetting.Builder()
        .name("start-y")
        .description("Initial Y position.")
        .defaultValue(24)
        .min(0)
        .sliderRange(0, 700)
        .visible(() -> false)
        .build()
    );
    private final Setting<Integer> startW = sgGeneral.add(new IntSetting.Builder()
        .name("start-width")
        .description("Initial width.")
        .defaultValue(640)
        .min(MIN_W)
        .sliderRange(MIN_W, MAX_W)
        .visible(() -> false)
        .build()
    );
    private final Setting<Integer> startH = sgGeneral.add(new IntSetting.Builder()
        .name("start-height")
        .description("Initial height.")
        .defaultValue(460)
        .min(MIN_H)
        .sliderRange(MIN_H, MAX_H)
        .visible(() -> false)
        .build()
    );

    private final BlackjackWindow window = new BlackjackWindow(MIN_W, MIN_H, MAX_W, MAX_H);
    private boolean windowInitialized;

    public BlackjackOverlay() {
        super(DevilsGameAddon.GAMES_CATEGORY, "blackjack", "Standalone blackjack launcher.");
        runInMainMenu = true;
    }

    @Override
    public void onActivate() {
        ensureWindowInitialized();
        GameLaunchCoordinator.activateExclusive(BlackjackOverlay.class);
        GamesCursorController.acquire(client());
    }

    @Override
    public void onDeactivate() {
        window.stopInteraction();
        GamesCursorController.release(client());
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        GameCrashGuard.run(this, "blackjackTick", () -> {
            if (!isActive()) return;
            window.onTick();
            GamesCursorController.update(client());
        });
    }

    @EventHandler
    private void onKey(KeyEvent event) {
        if (!isActive() || event.action != meteordevelopment.meteorclient.utils.misc.input.KeyAction.Press) return;
        if (mc.currentScreen instanceof WidgetScreen) return;
        if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
            GameLaunchCoordinator.closeAll();
        }
    }

    @EventHandler
    private void onRender2D(Render2DEvent event) {
        GameCrashGuard.run(this, "blackjackRender", () -> {
            if (!isActive()) return;
            window.render(event.drawContext, client(), pinned.get());
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST + 1)
    private void onMouse(MouseClickEvent event) {
        GameCrashGuard.run(this, "blackjackMouse", () -> {
            if (!isActive()) return;
            if (mc.currentScreen instanceof WidgetScreen) return;
            boolean consumed = window.onMouse(
                event,
                client(),
                pinned.get(),
                this::setPinned,
                () -> GameLaunchCoordinator.launchNext(GameLaunchCoordinator.Entry.BLACKJACK),
                GameLaunchCoordinator::closeAll
            );
            if (consumed) event.setCancelled(true);
        });
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

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private MinecraftClient client() {
        return mc;
    }
}

