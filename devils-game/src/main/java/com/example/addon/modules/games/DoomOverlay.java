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
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.util.PlayerInput;

public final class DoomOverlay extends Module {
    private static final int MIN_W = 420;
    private static final int MIN_H = 280;
    private static final int MAX_W = 980;
    private static final int MAX_H = 760;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final Setting<Boolean> pinned = sgGeneral.add(new BoolSetting.Builder()
        .name("pinned")
        .description("Keep overlay visible over chat/other screens.")
        .defaultValue(true)
        .visible(() -> false)
        .build()
    );
    private final Setting<Integer> startX = sgGeneral.add(new IntSetting.Builder()
        .name("start-x")
        .description("Initial X position.")
        .defaultValue(42)
        .min(0)
        .sliderRange(0, 1200)
        .visible(() -> false)
        .build()
    );
    private final Setting<Integer> startY = sgGeneral.add(new IntSetting.Builder()
        .name("start-y")
        .description("Initial Y position.")
        .defaultValue(26)
        .min(0)
        .sliderRange(0, 900)
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
        .defaultValue(430)
        .min(MIN_H)
        .sliderRange(MIN_H, MAX_H)
        .visible(() -> false)
        .build()
    );

    private final DoomWindow window = new DoomWindow(MIN_W, MIN_H, MAX_W, MAX_H);
    private boolean windowInitialized;

    public DoomOverlay() {
        super(DevilsGameAddon.GAMES_CATEGORY, "doom", "Standalone Doom launcher.");
        runInMainMenu = true;
    }

    @Override
    public void onActivate() {
        ensureWindowInitialized();
        GameLaunchCoordinator.activateExclusive(DoomOverlay.class);
        window.onActivate();
        GamesCursorController.acquire(client());
    }

    @Override
    public void onDeactivate() {
        window.shutdown(client());
        GamesCursorController.release(client());
    }

    @EventHandler
    private void onTickPre(TickEvent.Pre event) {
        GameCrashGuard.run(this, "devilsDoomTickPre", () -> {
            if (!isActive()) return;
            if (!window.isInputFocused()) return;
            if (mc == null || mc.player == null || mc.player.input == null) return;
            mc.player.input.playerInput = new PlayerInput(false, false, false, false, false, false, false);
            mc.player.setSprinting(false);
        });
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        GameCrashGuard.run(this, "devilsDoomTick", () -> {
            if (!isActive()) return;
            window.onTick();
            if (window.isInputFocused() && mc.currentScreen instanceof ChatScreen) {
                window.releaseInputFocus();
            }
            GamesCursorController.update(client());
        });
    }

    @EventHandler
    private void onKey(KeyEvent event) {
        GameCrashGuard.run(this, "devilsDoomKey", () -> {
            if (!isActive()) return;
            if (mc.currentScreen instanceof WidgetScreen) return;
            boolean consumed = window.onKey(event, client(), pinned.get(), GameLaunchCoordinator::closeAll);
            if (consumed) event.setCancelled(true);
        });
    }

    @EventHandler
    private void onRender2D(Render2DEvent event) {
        GameCrashGuard.run(this, "devilsDoomRender", () -> {
            if (!isActive()) return;
            window.render(event.drawContext, client(), pinned.get());
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST + 1)
    private void onMouse(MouseClickEvent event) {
        GameCrashGuard.run(this, "devilsDoomMouse", () -> {
            if (!isActive()) return;
            if (mc.currentScreen instanceof WidgetScreen) return;
            boolean consumed = window.onMouse(
                event,
                client(),
                pinned.get(),
                this::setPinned,
                () -> GameLaunchCoordinator.launchNext(GameLaunchCoordinator.Entry.DOOM),
                GameLaunchCoordinator::closeAll
            );
            if (consumed) event.setCancelled(true);
        });
    }

    private void setPinned(boolean value) {
        pinned.set(value);
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

    public static boolean isDoomInputCaptured() {
        Modules modules = Modules.get();
        if (modules == null) return false;
        Module module = modules.get(DoomOverlay.class);
        if (!(module instanceof DoomOverlay overlay) || !overlay.isActive()) return false;
        return overlay.window.isInputFocused();
    }
}

