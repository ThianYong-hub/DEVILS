package com.devils.addon.modules.games;

import com.devils.addon.games.DevilsGameAddon;
import java.util.Random;
import net.minecraft.client.MinecraftClient;

final class RussianRouletteSession {
    static final int MAX_LIVES = 3;
    static final int CYLINDER_SIZE = 6;

    private static final long SPIN_MS_BASE = 900L;
    private static final long SPIN_STEP_MS = 42L;
    private static final int LOSS_ANIM_FRAMES = 5;
    private static final long LOSS_ANIM_FRAME_MS = 220L;
    private static final int FINAL_COUNTDOWN_SECONDS = 5;
    private static final long FINAL_COUNTDOWN_MS = FINAL_COUNTDOWN_SECONDS * 1000L;

    private static final String LOSS_ASCII = String.join(
        System.lineSeparator(),
        "======================================================================",
        "                       DEVILS-GAME: YOU LOSE",
        "======================================================================",
        "               _______                        _______",
        "            .-'       `-.                  .-'       `-.",
        "          .'   .---.     `.              .'   .---.     `.",
        "         /   .'  _  `.     \\            /   .'  _  `.     \\",
        "        /   /   ( )   \\     \\          /   /   ( )   \\     \\",
        "       ;   ;    /_\\    ;     ;        ;   ;    /_\\    ;     ;",
        "       |   |   .---.   |     |        |   |   .---.   |     |",
        "       ;   ;  (_____)  ;     ;        ;   ;  (_____)  ;     ;",
        "        \\   \\         /     /          \\   \\         /     /",
        "         `.  `-.___.-'    .'            `.  `-.___.-'    .'",
        "           `-._       _.-'                `-._       _.-'",
        "               `-----'                        `-----'",
        "                    NO LIVES LEFT. FINAL ROUTINE ARMED.",
        "======================================================================"
    );

    enum Stage {
        NEEDS_SPIN,
        SPINNING,
        READY_TO_FIRE,
        LOSS_ANIMATION,
        ROUND_OVER
    }

    enum LossOutcome {
        CRASH_GAME,
        KILL_COMMAND
    }

    private final Random random = new Random();

    private final boolean[] spentChambers = new boolean[CYLINDER_SIZE];
    private int lives;
    private int bulletChamber;
    private int currentChamber;
    private int shotsSinceSpin;

    private Stage stage;
    private LossOutcome lossOutcome;
    private boolean lossActionDone;
    private boolean closeAllRequested;

    private long spinUntilMs;
    private long nextSpinStepAtMs;
    private long lossAnimationStartMs;
    private long lossActionAtMs;

    private String status;

    RussianRouletteSession() {
        reset();
    }

    void reset() {
        lives = MAX_LIVES;
        bulletChamber = -1;
        currentChamber = random.nextInt(CYLINDER_SIZE);
        shotsSinceSpin = 0;
        stage = Stage.NEEDS_SPIN;
        lossOutcome = null;
        lossActionDone = false;
        closeAllRequested = false;
        spinUntilMs = 0L;
        nextSpinStepAtMs = 0L;
        lossAnimationStartMs = 0L;
        lossActionAtMs = 0L;
        clearSpent();
        status = "Spin cylinder, then pull trigger.";
    }

    void onTick(MinecraftClient mc) {
        long now = System.currentTimeMillis();

        if (stage == Stage.SPINNING) {
            if (now >= nextSpinStepAtMs) {
                currentChamber = (currentChamber + 1 + random.nextInt(3)) % CYLINDER_SIZE;
                nextSpinStepAtMs = now + SPIN_STEP_MS;
            }
            if (now >= spinUntilMs) {
                bulletChamber = random.nextInt(CYLINDER_SIZE);
                currentChamber = random.nextInt(CYLINDER_SIZE);
                shotsSinceSpin = 0;
                clearSpent();
                stage = Stage.READY_TO_FIRE;
                status = "Cylinder locked. Pull trigger.";
            }
            return;
        }

        // Full-loss outcome runs in detached countdown sequence after all games close.
    }

    void startSpin() {
        if (stage == Stage.SPINNING) {
            status = "Already spinning...";
            return;
        }
        if (stage == Stage.LOSS_ANIMATION || stage == Stage.ROUND_OVER) {
            status = "Round ended. Press Restart.";
            return;
        }

        long now = System.currentTimeMillis();
        stage = Stage.SPINNING;
        spinUntilMs = now + SPIN_MS_BASE + random.nextInt(260);
        nextSpinStepAtMs = now + 20;
        status = "Spinning cylinder...";
    }

    void pullTrigger(MinecraftClient mc) {
        if (stage == Stage.SPINNING) {
            status = "Wait for spin to finish.";
            return;
        }
        if (stage != Stage.READY_TO_FIRE) {
            status = "Spin first.";
            return;
        }

        boolean liveRound = currentChamber == bulletChamber;
        if (liveRound) {
            lives--;
            if (lives <= 0) {
                lives = 0;
                stage = Stage.LOSS_ANIMATION;
                lossOutcome = random.nextBoolean() ? LossOutcome.CRASH_GAME : LossOutcome.KILL_COMMAND;
                lossAnimationStartMs = System.currentTimeMillis();
                lossActionAtMs = lossAnimationStartMs + FINAL_COUNTDOWN_MS;
                closeAllRequested = true;
                lossActionDone = false;
                status = "BANG! Last life lost.";
                return;
            }

            stage = Stage.NEEDS_SPIN;
            status = "BANG! Life lost: " + lives + "/" + MAX_LIVES + ". Spin again.";
            return;
        }

        spentChambers[currentChamber] = true;
        shotsSinceSpin++;
        currentChamber = (currentChamber + 1) % CYLINDER_SIZE;

        if (shotsSinceSpin >= CYLINDER_SIZE) {
            stage = Stage.NEEDS_SPIN;
            status = "Cylinder exhausted. Spin again.";
            return;
        }

        status = "Click... safe. Fired: " + shotsSinceSpin + "/" + CYLINDER_SIZE;
    }

    int lives() { return lives; }
    int currentChamber() { return currentChamber; }
    int chambersUsed() { return shotsSinceSpin; }
    boolean spinning() { return stage == Stage.SPINNING; }
    boolean gameOver() { return stage == Stage.LOSS_ANIMATION || stage == Stage.ROUND_OVER; }
    boolean lossActionDone() { return lossActionDone; }
    LossOutcome lossOutcome() { return lossOutcome; }
    Stage stage() { return stage; }
    String status() { return status; }

    boolean canSpin() {
        return stage == Stage.NEEDS_SPIN || stage == Stage.READY_TO_FIRE;
    }

    boolean canTrigger() {
        return stage == Stage.READY_TO_FIRE;
    }

    int deathFrameIndex() {
        if (stage != Stage.LOSS_ANIMATION && stage != Stage.ROUND_OVER) return 0;
        if (lossAnimationStartMs <= 0L) return LOSS_ANIM_FRAMES - 1;
        long elapsed = Math.max(0L, System.currentTimeMillis() - lossAnimationStartMs);
        int frame = (int) (elapsed / LOSS_ANIM_FRAME_MS);
        if (frame >= LOSS_ANIM_FRAMES) return LOSS_ANIM_FRAMES - 1;
        return frame;
    }

    long msUntilOutcome() {
        if (stage != Stage.LOSS_ANIMATION) return 0L;
        return Math.max(0L, lossActionAtMs - System.currentTimeMillis());
    }

    int countdownValue() {
        if (stage != Stage.LOSS_ANIMATION && stage != Stage.ROUND_OVER) return -1;
        long remaining = lossActionAtMs - System.currentTimeMillis();
        if (remaining <= 0L) return 0;
        return (int) Math.ceil(remaining / 1000.0);
    }

    boolean isSpent(int chamber) {
        if (chamber < 0 || chamber >= CYLINDER_SIZE) return false;
        return spentChambers[chamber];
    }

    boolean consumeCloseAllRequested() {
        boolean requested = closeAllRequested;
        closeAllRequested = false;
        return requested;
    }

    void executeKill(MinecraftClient mc) {
        lossActionDone = true;
        stage = Stage.ROUND_OVER;
        try {
            if (mc != null && mc.player != null && mc.player.networkHandler != null) {
                mc.player.networkHandler.sendChatCommand("kill");
                status = "Sent /kill. Press Restart.";
            } else {
                status = "No active player connection. Press Restart.";
            }
        } catch (Throwable t) {
            DevilsGameAddon.LOG.error("[Roulette] Failed to send /kill.", t);
            status = "Failed to execute /kill. Press Restart.";
        }
    }

    void executeCrashNow() {
        lossActionDone = true;
        stage = Stage.ROUND_OVER;
        DevilsGameAddon.LOG.error("\n{}", LOSS_ASCII);
        DevilsGameAddon.LOG.error("[Roulette] Outcome=CRASH_GAME; forcing client halt.");
        Runtime.getRuntime().halt(137);
    }

    private void clearSpent() {
        for (int i = 0; i < spentChambers.length; i++) spentChambers[i] = false;
    }
}

