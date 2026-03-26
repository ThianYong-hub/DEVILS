package com.example.addon.modules.games;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import net.minecraft.util.Identifier;

final class BlackjackSession {
    enum Stage {
        BETTING,
        PLAYER_TURN,
        DEALER_TURN,
        ROUND_OVER
    }

    enum Suit {
        CLUB("club"),
        DIAMOND("diamond"),
        HEART("heart"),
        SPADE("spade");

        private final String key;

        Suit(String key) {
            this.key = key;
        }

        String key() {
            return key;
        }
    }

    enum Rank {
        TWO("2", 2),
        THREE("3", 3),
        FOUR("4", 4),
        FIVE("5", 5),
        SIX("6", 6),
        SEVEN("7", 7),
        EIGHT("8", 8),
        NINE("9", 9),
        TEN("10", 10),
        JACK("j", 10),
        QUEEN("q", 10),
        KING("k", 10),
        ACE("a", 11);

        private final String key;
        private final int baseValue;

        Rank(String key, int baseValue) {
            this.key = key;
            this.baseValue = baseValue;
        }

        String key() {
            return key;
        }

        int baseValue() {
            return baseValue;
        }
    }

    static final class Card {
        private final Suit suit;
        private final Rank rank;
        private final Identifier texture;

        Card(Suit suit, Rank rank) {
            this.suit = suit;
            this.rank = rank;
            this.texture = Identifier.of("devils-addon", "textures/games/blackjack/cards/" + suit.key() + "_" + rank.key() + ".png");
        }

        Suit suit() {
            return suit;
        }

        Rank rank() {
            return rank;
        }

        Identifier texture() {
            return texture;
        }
    }

    static final class HandScore {
        private final int total;
        private final boolean soft;
        private final boolean blackjack;
        private final boolean bust;

        HandScore(int total, boolean soft, boolean blackjack, boolean bust) {
            this.total = total;
            this.soft = soft;
            this.blackjack = blackjack;
            this.bust = bust;
        }

        int total() {
            return total;
        }

        boolean soft() {
            return soft;
        }

        boolean blackjack() {
            return blackjack;
        }

        boolean bust() {
            return bust;
        }
    }

    private static final int DEFAULT_BANKROLL = 1000;
    private static final int DEFAULT_BET = 25;
    private static final int MIN_BET = 1;
    private static final int MAX_BET = 500;
    private static final int SHOE_DECKS = 4;
    private static final long DEALER_DRAW_DELAY_MS = 440L;

    private final Random random = new Random();
    private final List<Card> shoe = new ArrayList<>();
    private final List<Card> dealer = new ArrayList<>();
    private final List<Card> player = new ArrayList<>();

    private int bankroll;
    private int baseBet;
    private int roundBet;
    private int handsPlayed;
    private int wins;
    private int losses;
    private int pushes;
    private long nextDealerDrawAtMs;

    private Stage stage;
    private boolean dealerHoleHidden;
    private String status;

    BlackjackSession() {
        resetAll();
    }

    void resetAll() {
        bankroll = DEFAULT_BANKROLL;
        baseBet = DEFAULT_BET;
        roundBet = 0;
        handsPlayed = 0;
        wins = 0;
        losses = 0;
        pushes = 0;
        stage = Stage.BETTING;
        dealerHoleHidden = false;
        status = "Set bet and press Deal.";
        dealer.clear();
        player.clear();
        refillShoe(true);
    }

    void onTick() {
        if (stage != Stage.DEALER_TURN) return;
        long now = System.currentTimeMillis();
        if (now < nextDealerDrawAtMs) return;

        HandScore dealerScore = score(dealer);
        if (dealerScore.total() < 17) {
            dealer.add(drawCard());
            nextDealerDrawAtMs = now + DEALER_DRAW_DELAY_MS;
            status = "Dealer draws...";
            return;
        }

        settleDealerOutcome();
    }

    boolean deal() {
        if (!canDeal()) {
            if (bankroll < baseBet) status = "Not enough chips for bet " + baseBet + ".";
            return false;
        }

        ensureShoe();
        dealer.clear();
        player.clear();
        dealerHoleHidden = true;
        handsPlayed++;
        bankroll -= baseBet;
        roundBet = baseBet;

        player.add(drawCard());
        dealer.add(drawCard());
        player.add(drawCard());
        dealer.add(drawCard());

        stage = Stage.PLAYER_TURN;
        status = "Your turn: Hit / Stand / Double.";
        settleNaturals();
        return true;
    }

    boolean hit() {
        if (!canHit()) return false;
        player.add(drawCard());
        HandScore playerScore = score(player);
        if (playerScore.bust()) {
            stage = Stage.ROUND_OVER;
            dealerHoleHidden = false;
            losses++;
            status = "Bust. You lose " + roundBet + ".";
            roundBet = 0;
        } else {
            status = "Player " + playerScore.total() + ". Hit / Stand.";
        }
        return true;
    }

    boolean stand() {
        if (!canStand()) return false;
        startDealerTurn();
        return true;
    }

    boolean doubleDown() {
        if (!canDouble()) return false;
        bankroll -= roundBet;
        roundBet *= 2;
        player.add(drawCard());

        HandScore playerScore = score(player);
        if (playerScore.bust()) {
            stage = Stage.ROUND_OVER;
            dealerHoleHidden = false;
            losses++;
            status = "Double bust. You lose " + roundBet + ".";
            roundBet = 0;
            return true;
        }

        startDealerTurn();
        return true;
    }

    void restartRound() {
        if (stage == Stage.BETTING) return;
        roundBet = 0;
        stage = Stage.BETTING;
        dealerHoleHidden = false;
        dealer.clear();
        player.clear();
        status = "Round cleared. Set bet and Deal.";
    }

    void increaseBet() {
        if (stage == Stage.PLAYER_TURN || stage == Stage.DEALER_TURN) return;
        baseBet = Math.min(maxAllowedBet(), baseBet + 5);
        status = "Bet set to " + baseBet + ".";
    }

    void decreaseBet() {
        if (stage == Stage.PLAYER_TURN || stage == Stage.DEALER_TURN) return;
        baseBet = Math.max(MIN_BET, baseBet - 5);
        status = "Bet set to " + baseBet + ".";
    }

    void maxBet() {
        if (stage == Stage.PLAYER_TURN || stage == Stage.DEALER_TURN) return;
        baseBet = maxAllowedBet();
        status = "Bet set to max (" + baseBet + ").";
    }

    Stage stage() {
        return stage;
    }

    int bankroll() {
        return bankroll;
    }

    int baseBet() {
        return baseBet;
    }

    int roundBet() {
        return roundBet;
    }

    int handsPlayed() {
        return handsPlayed;
    }

    int wins() {
        return wins;
    }

    int losses() {
        return losses;
    }

    int pushes() {
        return pushes;
    }

    String status() {
        return status;
    }

    boolean dealerHoleHidden() {
        return dealerHoleHidden && dealer.size() > 1;
    }

    List<Card> playerCards() {
        return Collections.unmodifiableList(player);
    }

    List<Card> dealerCards() {
        return Collections.unmodifiableList(dealer);
    }

    HandScore playerScore() {
        return score(player);
    }

    HandScore dealerScoreVisible() {
        if (!dealerHoleHidden() || dealer.isEmpty()) return score(dealer);
        return score(List.of(dealer.get(0)));
    }

    HandScore dealerScoreFull() {
        return score(dealer);
    }

    boolean canDeal() {
        return (stage == Stage.BETTING || stage == Stage.ROUND_OVER) && bankroll >= baseBet;
    }

    boolean canHit() {
        return stage == Stage.PLAYER_TURN;
    }

    boolean canStand() {
        return stage == Stage.PLAYER_TURN;
    }

    boolean canDouble() {
        return stage == Stage.PLAYER_TURN && player.size() == 2 && bankroll >= roundBet;
    }

    private void settleNaturals() {
        HandScore p = score(player);
        HandScore d = score(dealer);
        if (!p.blackjack() && !d.blackjack()) return;

        dealerHoleHidden = false;
        stage = Stage.ROUND_OVER;

        if (p.blackjack() && d.blackjack()) {
            bankroll += roundBet;
            pushes++;
            status = "Both blackjack. Push.";
            roundBet = 0;
            return;
        }

        if (p.blackjack()) {
            int payout = (roundBet * 5) / 2;
            bankroll += payout;
            wins++;
            status = "Blackjack! Paid +" + (payout - roundBet) + ".";
            roundBet = 0;
            return;
        }

        losses++;
        status = "Dealer blackjack. You lose " + roundBet + ".";
        roundBet = 0;
    }

    private void startDealerTurn() {
        stage = Stage.DEALER_TURN;
        dealerHoleHidden = false;
        nextDealerDrawAtMs = System.currentTimeMillis() + DEALER_DRAW_DELAY_MS;
        status = "Dealer turn...";
    }

    private void settleDealerOutcome() {
        HandScore p = score(player);
        HandScore d = score(dealer);
        stage = Stage.ROUND_OVER;

        if (d.bust()) {
            bankroll += roundBet * 2;
            wins++;
            status = "Dealer bust " + d.total() + ". Win +" + roundBet + ".";
            roundBet = 0;
            return;
        }

        if (p.total() > d.total()) {
            bankroll += roundBet * 2;
            wins++;
            status = "You win " + p.total() + " vs " + d.total() + " (+" + roundBet + ").";
            roundBet = 0;
            return;
        }

        if (p.total() < d.total()) {
            losses++;
            status = "Dealer wins " + d.total() + " vs " + p.total() + ".";
            roundBet = 0;
            return;
        }

        bankroll += roundBet;
        pushes++;
        status = "Push at " + p.total() + ". Bet returned.";
        roundBet = 0;
    }

    private HandScore score(List<Card> cards) {
        int total = 0;
        int aces = 0;
        for (Card card : cards) {
            total += card.rank().baseValue();
            if (card.rank() == Rank.ACE) aces++;
        }
        while (total > 21 && aces > 0) {
            total -= 10;
            aces--;
        }
        boolean soft = aces > 0;
        boolean blackjack = cards.size() == 2 && total == 21;
        boolean bust = total > 21;
        return new HandScore(total, soft, blackjack, bust);
    }

    private Card drawCard() {
        ensureShoe();
        int index = random.nextInt(shoe.size());
        return shoe.remove(index);
    }

    private void ensureShoe() {
        if (shoe.size() >= 20) return;
        refillShoe(false);
    }

    private void refillShoe(boolean fullReset) {
        if (fullReset) shoe.clear();
        List<Card> refill = new ArrayList<>(52 * SHOE_DECKS);
        for (int deck = 0; deck < SHOE_DECKS; deck++) {
            for (Suit suit : Suit.values()) {
                for (Rank rank : Rank.values()) {
                    refill.add(new Card(suit, rank));
                }
            }
        }
        shoe.addAll(refill);
    }

    private int maxAllowedBet() {
        int cap = Math.min(MAX_BET, bankroll);
        return Math.max(MIN_BET, cap);
    }
}
