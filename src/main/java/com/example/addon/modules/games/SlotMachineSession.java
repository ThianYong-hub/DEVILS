package com.example.addon.modules.games;

import java.util.Random;
import net.minecraft.util.Identifier;

final class SlotMachineSession {
    enum Stage {
        IDLE,
        SPINNING,
        RESULT
    }

    static final class Symbol {
        private final String key;
        private final Identifier texture;
        private final int textureW;
        private final int textureH;
        private final int weight;
        private final int tripleMultiplier;
        private final int pairMultiplier;

        Symbol(String key, String texturePath, int textureW, int textureH, int weight, int tripleMultiplier, int pairMultiplier) {
            this.key = key;
            this.texture = Identifier.of("devils-addon", texturePath);
            this.textureW = textureW;
            this.textureH = textureH;
            this.weight = weight;
            this.tripleMultiplier = tripleMultiplier;
            this.pairMultiplier = pairMultiplier;
        }

        String key() { return key; }
        Identifier texture() { return texture; }
        int textureW() { return textureW; }
        int textureH() { return textureH; }
        int weight() { return weight; }
        int tripleMultiplier() { return tripleMultiplier; }
        int pairMultiplier() { return pairMultiplier; }
    }

    private static final Symbol[] SYMBOLS = {
        new Symbol("Bar1", "textures/games/slot/Bar1.png", 51, 24, 16, 10, 2),
        new Symbol("Bar2", "textures/games/slot/Bar2.png", 51, 43, 14, 14, 3),
        new Symbol("Bar3", "textures/games/slot/Bar3.png", 51, 62, 12, 20, 4),
        new Symbol("Bell", "textures/games/slot/bell.png", 56, 56, 10, 16, 3),
        new Symbol("Cherry", "textures/games/slot/cherries.png", 63, 66, 22, 15, 4),
        new Symbol("Clover", "textures/games/slot/clover.png", 45, 54, 10, 18, 3),
        new Symbol("Heart", "textures/games/slot/heart.png", 53, 57, 9, 18, 3),
        new Symbol("Horse", "textures/games/slot/horseshoe.png", 64, 62, 8, 22, 4),
        new Symbol("Lemon", "textures/games/slot/lemon.png", 65, 48, 16, 8, 2),
        new Symbol("Lucky7", "textures/games/slot/Lucky7_rainbow.png", 40, 53, 5, 50, 8),
        new Symbol("Melon", "textures/games/slot/melon.png", 66, 54, 13, 12, 2)
    };

    private static final int CHERRY_INDEX = 4;
    private static final int LUCKY7_INDEX = 9;

    private final Random random = new Random();

    private int credits;
    private int refillCap;
    private int bet;
    private int jackpot;
    private int totalSpent;
    private int totalWon;
    private int lastWin;

    private final int[] reel = {0, 1, 2};
    private final int[] target = new int[3];
    private final boolean[] locked = new boolean[3];
    private final long[] stopAtMs = new long[3];
    private final long[] nextStepAtMs = new long[3];

    private Stage stage;
    private long spinStartedAtMs;
    private String status;

    SlotMachineSession() {
        reset();
    }

    void reset() {
        refillCap = 999;
        credits = refillCap;
        bet = 5;
        jackpot = 500;
        totalSpent = 0;
        totalWon = 0;
        lastWin = 0;
        reel[0] = 0;
        reel[1] = 1;
        reel[2] = 2;
        stage = Stage.IDLE;
        status = "Ready. Set bet and spin.";
        for (int i = 0; i < 3; i++) {
            target[i] = reel[i];
            locked[i] = true;
            stopAtMs[i] = 0L;
            nextStepAtMs[i] = 0L;
        }
    }

    void onTick() {
        if (stage != Stage.SPINNING) return;
        long now = System.currentTimeMillis();
        boolean anyRunning = false;
        for (int i = 0; i < 3; i++) {
            if (locked[i]) continue;
            if (now >= stopAtMs[i]) {
                reel[i] = target[i];
                locked[i] = true;
                continue;
            }
            anyRunning = true;
            if (now >= nextStepAtMs[i]) {
                reel[i] = wrap(reel[i] + 1);
                long elapsed = now - spinStartedAtMs;
                long step = Math.max(18L, 62L - (elapsed / 35L) + (i * 4L));
                nextStepAtMs[i] = now + step;
            }
        }
        if (!anyRunning) settleSpin();
    }

    boolean spin() {
        if (stage == Stage.SPINNING) {
            status = "Reels are spinning...";
            return false;
        }
        maybeRefillCredits();
        if (credits < bet) {
            status = "Not enough credits for bet " + bet + ".";
            return false;
        }

        credits -= bet;
        totalSpent += bet;
        jackpot = Math.min(25000, jackpot + Math.max(1, bet / 2));
        lastWin = 0;

        long now = System.currentTimeMillis();
        spinStartedAtMs = now;
        stage = Stage.SPINNING;
        status = "Spinning...";

        for (int i = 0; i < 3; i++) {
            target[i] = rollWeightedSymbol();
            locked[i] = false;
            stopAtMs[i] = now + 900L + i * 360L + random.nextInt(150);
            nextStepAtMs[i] = now + 24L + i * 14L;
        }
        return true;
    }

    void increaseBet() {
        if (stage == Stage.SPINNING) return;
        bet = Math.min(maxBetValue(), bet + 1);
        status = "Bet set to " + bet + ".";
    }

    void decreaseBet() {
        if (stage == Stage.SPINNING) return;
        bet = Math.max(1, bet - 1);
        status = "Bet set to " + bet + ".";
    }

    void maxBet() {
        if (stage == Stage.SPINNING) return;
        bet = maxBetValue();
        status = "Bet set to max (" + bet + ").";
    }

    boolean spinning() { return stage == Stage.SPINNING; }
    int credits() { return credits; }
    int bet() { return bet; }
    int jackpot() { return jackpot; }
    int totalSpent() { return totalSpent; }
    int totalWon() { return totalWon; }
    int lastWin() { return lastWin; }
    String status() { return status; }

    Symbol symbolAt(int reelIndex, int rowOffset) {
        int index = wrap(reel[reelIndex] + rowOffset);
        return SYMBOLS[index];
    }

    Symbol centerSymbol(int reelIndex) {
        return symbolAt(reelIndex, 0);
    }

    String[] paytableLines() {
        return new String[] {
            "Lucky7 x3 = 50x + jackpot",
            "Bar3 x3 = 20x",
            "Horse x3 = 22x",
            "Cherry x3 = 15x",
            "Any pair = symbol pair payout",
            "Cherry x1 on reel1 = 1x"
        };
    }

    private void settleSpin() {
        stage = Stage.RESULT;
        int payout = calculatePayout(reel[0], reel[1], reel[2]);
        if (payout > 0) {
            credits += payout;
            totalWon += payout;
            lastWin = payout;
            status = "WIN +" + payout + " | " + centerSymbol(0).key() + " - " + centerSymbol(1).key() + " - " + centerSymbol(2).key();
        } else {
            status = "No win | " + centerSymbol(0).key() + " - " + centerSymbol(1).key() + " - " + centerSymbol(2).key();
        }
        int before = credits;
        maybeRefillCredits();
        if (before == 0 && credits > 0) status = status + " | Bank reset: " + credits + ".";
    }

    private int calculatePayout(int a, int b, int c) {
        Symbol sa = SYMBOLS[a];
        Symbol sb = SYMBOLS[b];
        Symbol sc = SYMBOLS[c];

        if (a == b && b == c) {
            int payout = bet * sa.tripleMultiplier();
            if (a == LUCKY7_INDEX) {
                payout += jackpot;
                jackpot = 500;
            }
            return payout;
        }

        int cherryCount = 0;
        if (a == CHERRY_INDEX) cherryCount++;
        if (b == CHERRY_INDEX) cherryCount++;
        if (c == CHERRY_INDEX) cherryCount++;

        if (cherryCount == 2) return bet * 4;
        if (cherryCount == 1 && a == CHERRY_INDEX) return bet;

        int pairMultiplier = 0;
        if (a == b) pairMultiplier = Math.max(pairMultiplier, sa.pairMultiplier());
        if (b == c) pairMultiplier = Math.max(pairMultiplier, sb.pairMultiplier());
        if (a == c) pairMultiplier = Math.max(pairMultiplier, sa.pairMultiplier());
        if (pairMultiplier > 0) return bet * pairMultiplier;

        boolean barsOnly = isBar(a) && isBar(b) && isBar(c);
        if (barsOnly) return bet * 5;

        if (a == LUCKY7_INDEX || b == LUCKY7_INDEX || c == LUCKY7_INDEX) return bet * 2;
        return 0;
    }

    private static boolean isBar(int index) {
        return index >= 0 && index <= 2;
    }

    private int maxBetValue() {
        return Math.max(1, Math.min(30, Math.max(1, credits)));
    }

    private void maybeRefillCredits() {
        if (credits > 0 || refillCap <= 0) return;
        refillCap = Math.max(0, refillCap - 1);
        credits = refillCap;
        if (credits > 0) {
            bet = Math.min(Math.max(1, bet), credits);
        } else {
            bet = 1;
        }
    }

    private int rollWeightedSymbol() {
        int total = 0;
        for (Symbol symbol : SYMBOLS) total += symbol.weight();
        int roll = random.nextInt(total);
        int cursor = 0;
        for (int i = 0; i < SYMBOLS.length; i++) {
            cursor += SYMBOLS[i].weight();
            if (roll < cursor) return i;
        }
        return SYMBOLS.length - 1;
    }

    private static int wrap(int value) {
        int n = SYMBOLS.length;
        int result = value % n;
        return result < 0 ? result + n : result;
    }
}
