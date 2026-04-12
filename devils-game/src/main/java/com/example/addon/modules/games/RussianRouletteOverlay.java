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
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

public final class RussianRouletteOverlay extends Module {
    private static final int MIN_W = 320;
    private static final int MIN_H = 210;
    private static final int MAX_W = 620;
    private static final int MAX_H = 520;
    private static final long FINAL_COUNTDOWN_MS = 5000L;
    private static final long DEATH_FRAME_MS = 180L;
    private static final int DEATH_BG = 0xB0000000;
    private static final int DEATH_PANEL_BG = 0xCC2B0008;
    private static final int DEATH_PANEL_BORDER = 0xFF9B2634;
    private static final int TIMER_TEX = 1024;
    private static final int REAPER_W = 318;
    private static final int REAPER_H = 349;
    private static final Identifier[] TIMER_FRAMES = {
        Identifier.of("devils-game", "textures/games/roulette/timer_5.png"),
        Identifier.of("devils-game", "textures/games/roulette/timer_4.png"),
        Identifier.of("devils-game", "textures/games/roulette/timer_3.png"),
        Identifier.of("devils-game", "textures/games/roulette/timer_2.png"),
        Identifier.of("devils-game", "textures/games/roulette/timer_1.png")
    };
    private static final Identifier[] REAPER_FRAMES = {
        Identifier.of("devils-game", "textures/games/roulette/reaper_1.png"),
        Identifier.of("devils-game", "textures/games/roulette/reaper_2.png"),
        Identifier.of("devils-game", "textures/games/roulette/reaper_3.png"),
        Identifier.of("devils-game", "textures/games/roulette/reaper_4.png"),
        Identifier.of("devils-game", "textures/games/roulette/reaper_5.png")
    };

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
        .defaultValue(400)
        .min(MIN_W)
        .sliderRange(MIN_W, MAX_W)
        .visible(() -> false)
        .build()
    );
    private final Setting<Integer> startH = sgGeneral.add(new IntSetting.Builder()
        .name("start-height")
        .description("Initial height.")
        .defaultValue(250)
        .min(MIN_H)
        .sliderRange(MIN_H, MAX_H)
        .visible(() -> false)
        .build()
    );

    private final RussianRouletteWindow window = new RussianRouletteWindow(MIN_W, MIN_H, MAX_W, MAX_H);
    private boolean windowInitialized;
    private boolean gameVisible = true;
    private boolean deathSequenceActive;
    private long deathStartedAtMs;
    private long deathEndsAtMs;
    private RussianRouletteSession.LossOutcome deathOutcome = RussianRouletteSession.LossOutcome.CRASH_GAME;

    public RussianRouletteOverlay() {
        super(DevilsGameAddon.GAMES_CATEGORY, "russian-roulette", "Russian roulette mini-game in movable overlay.");
        runInMainMenu = true;
    }

    @Override
    public void onActivate() {
        ensureWindowInitialized();
        gameVisible = true;
        deathSequenceActive = false;
        GameLaunchCoordinator.activateExclusive(RussianRouletteOverlay.class);
        GamesCursorController.acquire(client());
    }

    @Override
    public void onDeactivate() {
        window.stopInteraction();
        gameVisible = true;
        deathSequenceActive = false;
        GamesCursorController.release(client());
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        GameCrashGuard.run(this, "rouletteTick", () -> {
            if (!isActive()) return;
            if (gameVisible) {
                window.onTick(client());
                if (window.consumeCloseAllRequested()) beginDeathSequence();
            }
            if (deathSequenceActive && System.currentTimeMillis() >= deathEndsAtMs) applyDeathOutcome();
            GamesCursorController.update(client());
        });
    }

    @EventHandler
    private void onKey(KeyEvent event) {
        if (!isActive() || event.action != meteordevelopment.meteorclient.utils.misc.input.KeyAction.Press) return;
        if (mc.currentScreen instanceof WidgetScreen) return;
        if (deathSequenceActive) return;
        int key = event.key();
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            GameLaunchCoordinator.closeAll();
        }
    }

    @EventHandler
    private void onRender2D(Render2DEvent event) {
        GameCrashGuard.run(this, "rouletteRender", () -> {
            if (!isActive()) return;
            if (gameVisible) window.render(event.drawContext, client(), pinned.get());
            if (deathSequenceActive) renderDeathOverlay(event.drawContext, client());
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST + 1)
    private void onMouse(MouseClickEvent event) {
        GameCrashGuard.run(this, "rouletteMouse", () -> {
            if (!isActive()) return;
            if (mc.currentScreen instanceof WidgetScreen) return;
            if (!gameVisible || deathSequenceActive) return;
            boolean consumed = window.onMouse(
                event,
                client(),
                pinned.get(),
                this::setPinned,
                () -> GameLaunchCoordinator.launchNext(GameLaunchCoordinator.Entry.RUSSIAN_ROULETTE),
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

    private void beginDeathSequence() {
        gameVisible = false;
        window.stopInteraction();
        deathSequenceActive = true;
        deathStartedAtMs = System.currentTimeMillis();
        deathEndsAtMs = deathStartedAtMs + FINAL_COUNTDOWN_MS;
        RussianRouletteSession.LossOutcome outcome = window.lossOutcome();
        deathOutcome = outcome == null ? RussianRouletteSession.LossOutcome.CRASH_GAME : outcome;
        DevilsGameAddon.LOG.error("[Roulette] Death sequence started. Outcome={}", deathOutcome);
    }

    private void applyDeathOutcome() {
        deathSequenceActive = false;
        if (deathOutcome == RussianRouletteSession.LossOutcome.KILL_COMMAND) {
            window.executeKill(client());
            window.resetSessionState();
            gameVisible = true;
            GameLaunchCoordinator.closeAll();
            return;
        }

        window.executeCrashNow();
    }

    private void renderDeathOverlay(DrawContext context, MinecraftClient mc) {
        if (mc == null || mc.getWindow() == null) return;
        int sw = mc.getWindow().getScaledWidth();
        int sh = mc.getWindow().getScaledHeight();
        context.fill(0, 0, sw, sh, DEATH_BG);

        int panelW = Math.min(420, Math.max(280, sw - 60));
        int panelH = Math.min(210, Math.max(150, sh - 80));
        int px = (sw - panelW) / 2;
        int py = (sh - panelH) / 2;

        context.fill(px, py, px + panelW, py + panelH, DEATH_PANEL_BG);
        context.fill(px, py, px + panelW, py + 1, DEATH_PANEL_BORDER);
        context.fill(px, py + panelH - 1, px + panelW, py + panelH, DEATH_PANEL_BORDER);
        context.fill(px, py, px + 1, py + panelH, DEATH_PANEL_BORDER);
        context.fill(px + panelW - 1, py, px + panelW, py + panelH, DEATH_PANEL_BORDER);

        TextRenderer tr = mc.textRenderer;
        String title = "DEVILS-GAME: YOU LOSE";
        context.drawTextWithShadow(tr, title, px + (panelW - tr.getWidth(title)) / 2, py + 10, 0xFFFFD0D0);

        int sec = countdownValue();
        String outcomeText = deathOutcome == RussianRouletteSession.LossOutcome.CRASH_GAME
            ? "Outcome: CRASH"
            : "Outcome: /kill";
        String timerText = "Punishment in: " + sec;
        context.drawTextWithShadow(tr, outcomeText, px + 16, py + panelH - 36, 0xFFFFE8A0);
        context.drawTextWithShadow(tr, timerText, px + 16, py + panelH - 22, 0xFFFFE8A0);

        int reaperIndex = (int) (((System.currentTimeMillis() - deathStartedAtMs) / DEATH_FRAME_MS) % REAPER_FRAMES.length);
        int rw = Math.max(96, panelW / 3);
        int rh = Math.max(108, (rw * REAPER_H) / REAPER_W);
        int rx = px + panelW - rw - 18;
        int ry = py + panelH - rh - 16;
        context.drawTexture(RenderPipelines.GUI_TEXTURED, REAPER_FRAMES[reaperIndex], rx, ry, 0, 0, rw, rh, REAPER_W, REAPER_H, REAPER_W, REAPER_H, 0xFFFFFFFF);

        if (sec >= 1 && sec <= 5) {
            int tw = Math.max(66, panelW / 5);
            int tx = px + 18;
            int ty = py + 42;
            context.drawTexture(RenderPipelines.GUI_TEXTURED, TIMER_FRAMES[5 - sec], tx, ty, 0, 0, tw, tw, TIMER_TEX, TIMER_TEX, TIMER_TEX, TIMER_TEX, 0xFFFFFFFF);
        } else {
            String zero = "0";
            context.drawTextWithShadow(tr, zero, px + 34, py + 76, 0xFFFFFFFF);
        }
    }

    private int countdownValue() {
        long remaining = Math.max(0L, deathEndsAtMs - System.currentTimeMillis());
        if (remaining == 0L) return 0;
        return (int) Math.ceil(remaining / 1000.0);
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

