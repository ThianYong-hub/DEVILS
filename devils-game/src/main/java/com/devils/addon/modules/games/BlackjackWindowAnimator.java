package com.devils.addon.modules.games;
import static com.devils.addon.modules.games.BlackjackWindowLayout.clamp;
import static com.devils.addon.modules.games.BlackjackWindowRenderer.BET_CHIPS;
import static com.devils.addon.modules.games.BlackjackWindowRenderer.CARD_BACK;
import static com.devils.addon.modules.games.BlackjackWindowRenderer.CARD_BACK_H;
import static com.devils.addon.modules.games.BlackjackWindowRenderer.CARD_BACK_W;
import static com.devils.addon.modules.games.BlackjackWindowRenderer.drawCard;
import static com.devils.addon.modules.games.BlackjackWindowRenderer.drawChip;
import static com.devils.addon.modules.games.BlackjackWindowRenderer.splitAmount;
import com.devils.addon.modules.games.BlackjackWindowLayout.CardPlacement;
import com.devils.addon.modules.games.BlackjackWindowLayout.Layout;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
final class BlackjackWindowAnimator {
    private static final long CARD_FLY_MS = 420L;
    private static final long CARD_RETURN_MS = 360L;
    private static final long CHIP_FLY_MS = 560L;
    private static final long BET_TWEEN_MS = 420L;
    private static final long SHUFFLE_MS = 1200L;
    private static final long CARD_DEAL_STAGGER_MS = 150L;
    private final BlackjackSession session;
    private final List<CardFlight> cardFlights = new ArrayList<>();
    private final List<CardGhost> cardGhosts = new ArrayList<>();
    private final List<ChipFlight> chipFlights = new ArrayList<>();
    private final IdentityHashMap<BlackjackSession.Card, CardPose> previousCardPoses = new IdentityHashMap<>();
    private List<BlackjackSession.Card> lastDealerCards = List.of();
    private List<BlackjackSession.Card> lastPlayerCards = List.of();
    private BlackjackSession.Stage lastStage = BlackjackSession.Stage.BETTING;
    private int lastRoundBet;
    private int betTweenFrom = 25;
    private int betTweenTo = 25;
    private long betTweenStartMs;
    private long shuffleAnimUntilMs;
    BlackjackWindowAnimator(BlackjackSession session) {
        this.session = session;
    }
    void reset() {
        cardFlights.clear();
        cardGhosts.clear();
        chipFlights.clear();
        previousCardPoses.clear();
        lastDealerCards = List.of();
        lastPlayerCards = List.of();
        lastStage = BlackjackSession.Stage.BETTING;
        lastRoundBet = 0;
        betTweenFrom = session.baseBet();
        betTweenTo = session.baseBet();
        betTweenStartMs = System.currentTimeMillis();
        shuffleAnimUntilMs = 0L;
    }
    void drawStaticCards(DrawContext context, List<CardPlacement> placements, Layout l, long now) {
        cardFlights.removeIf(flight -> now >= flight.endMs);
        Set<BlackjackSession.Card> active = new HashSet<>();
        for (CardFlight flight : cardFlights) active.add(flight.card);
        for (CardPlacement placement : placements) {
            if (active.contains(placement.card())) continue;
            boolean hidden = placement.dealerHand() && isDealerHoleCard(placement.card());
            drawCard(context, placement.card(), placement.x(), placement.y(), l.cardW(), l.cardH(), hidden, 0xFFFFFFFF);
        }
    }
    void drawCardFlights(DrawContext context, long now, Layout l) {
        Iterator<CardFlight> it = cardFlights.iterator();
        while (it.hasNext()) {
            CardFlight flight = it.next();
            if (now < flight.startMs) continue;
            double t = (now - flight.startMs) / (double) Math.max(1L, flight.endMs - flight.startMs);
            if (t >= 1.0) {
                it.remove();
                continue;
            }
            double eased = easeOutCubic(clamp01(t));
            int x = (int) Math.round(lerp(flight.fromX, flight.toX, eased));
            int y = (int) Math.round(lerp(flight.fromY, flight.toY, eased) - Math.sin(Math.PI * eased) * 8.0);
            boolean hidden = flight.dealerHand && isDealerHoleCard(flight.card);
            drawCard(context, flight.card, x, y, l.cardW(), l.cardH(), hidden, 0xFFFFFFFF);
        }
    }
    void drawCardGhosts(DrawContext context, long now, Layout l) {
        Iterator<CardGhost> it = cardGhosts.iterator();
        while (it.hasNext()) {
            CardGhost ghost = it.next();
            double t = (now - ghost.startMs) / (double) Math.max(1L, ghost.endMs - ghost.startMs);
            if (t >= 1.0) {
                it.remove();
                continue;
            }
            double eased = easeInOut(clamp01(t));
            int x = (int) Math.round(lerp(ghost.fromX, ghost.toX, eased));
            int y = (int) Math.round(lerp(ghost.fromY, ghost.toY, eased));
            int alpha = clamp((int) Math.round((1.0 - eased) * 255.0), 0, 255);
            int tint = (alpha << 24) | 0x00FFFFFF;
            drawCard(context, ghost.card, x, y, l.cardW(), l.cardH(), ghost.dealerHand && isDealerHoleCard(ghost.card), tint);
        }
    }
    void drawChipFlights(DrawContext context, long now, Layout l) {
        int size = Math.max(16, l.chipSize() - 4);
        Iterator<ChipFlight> it = chipFlights.iterator();
        while (it.hasNext()) {
            ChipFlight flight = it.next();
            double t = (now - flight.startMs) / (double) Math.max(1L, flight.endMs - flight.startMs);
            if (t >= 1.0) {
                it.remove();
                continue;
            }
            double eased = easeInOut(clamp01(t));
            int cx = (int) Math.round(lerp(flight.fromX, flight.toX, eased));
            int cy = (int) Math.round(lerp(flight.fromY, flight.toY, eased) - Math.sin(Math.PI * eased) * 14.0);
            int[] split = splitAmount(flight.amount);
            int drawn = 0;
            for (int i = 0; i < split.length && drawn < 6; i++) {
                for (int c = 0; c < split[i] && drawn < 6; c++) {
                    drawChip(context, BET_CHIPS[i], cx - size / 2 + ((drawn & 1) == 0 ? 0 : 1), cy - drawn * 2, size);
                    drawn++;
                }
            }
        }
    }
    void drawDeckShoe(DrawContext context, Layout l, long now) {
        boolean shuffling = now < shuffleAnimUntilMs;
        int jitterX = 0;
        int jitterY = 0;
        if (shuffling) {
            double phase = 1.0 - ((shuffleAnimUntilMs - now) / (double) SHUFFLE_MS);
            double envelope = Math.sin(clamp01(phase) * Math.PI);
            jitterX = (int) Math.round(Math.sin(now * 0.026) * 1.6 * envelope);
            jitterY = (int) Math.round(Math.cos(now * 0.023) * 1.2 * envelope);
        }
        for (int i = 0; i < 3; i++) {
            int dx = l.deckX() - i + (shuffling ? ((i & 1) == 0 ? jitterX : -jitterX) : 0);
            int dy = l.deckY() - i + jitterY;
            int tint = shuffling && i > 0 ? 0xF2FFFFFF : 0xFFFFFFFF;
            context.drawTexture(RenderPipelines.GUI_TEXTURED, CARD_BACK, dx, dy, 0, 0, l.deckW(), l.deckH(), CARD_BACK_W, CARD_BACK_H, CARD_BACK_W, CARD_BACK_H, tint);
        }
    }
    void syncAnimationState(Layout l, List<CardPlacement> dealerPlacements, List<CardPlacement> playerPlacements, long now) {
        List<CardPlacement> all = new ArrayList<>(dealerPlacements.size() + playerPlacements.size());
        all.addAll(dealerPlacements);
        all.addAll(playerPlacements);
        Set<BlackjackSession.Card> currentCards = new HashSet<>();
        Set<BlackjackSession.Card> previousCards = new HashSet<>();
        for (CardPlacement placement : all) currentCards.add(placement.card());
        previousCards.addAll(lastDealerCards);
        previousCards.addAll(lastPlayerCards);
        int deckCardX = l.deckX() + l.deckW() / 2 - l.cardW() / 2;
        int deckCardY = l.deckY() + l.deckH() / 2 - l.cardH() / 2;
        List<CardPlacement> newlyAppeared = new ArrayList<>();
        for (CardPlacement placement : all) {
            if (!previousCards.contains(placement.card())) {
                newlyAppeared.add(placement);
            } else {
                for (CardFlight flight : cardFlights) {
                    if (flight.card == placement.card()) {
                        flight.toX = placement.x();
                        flight.toY = placement.y();
                        flight.dealerHand = placement.dealerHand();
                    }
                }
            }
        }
        List<CardPlacement> orderedNew = orderNewCardPlacements(newlyAppeared, dealerPlacements, playerPlacements, previousCards);
        for (int i = 0; i < orderedNew.size(); i++) {
            CardPlacement placement = orderedNew.get(i);
            long start = now + i * CARD_DEAL_STAGGER_MS;
            long end = start + CARD_FLY_MS;
            cardFlights.add(new CardFlight(placement.card(), placement.dealerHand(), deckCardX, deckCardY, placement.x(), placement.y(), start, end));
            shuffleAnimUntilMs = Math.max(shuffleAnimUntilMs, end + SHUFFLE_MS / 3);
        }
        for (BlackjackSession.Card card : previousCards) {
            if (currentCards.contains(card)) continue;
            CardPose pose = previousCardPoses.get(card);
            if (pose != null) {
                cardGhosts.add(new CardGhost(card, pose.dealerHand(), pose.x(), pose.y(), deckCardX, deckCardY, now, now + CARD_RETURN_MS));
            }
        }
        cardFlights.removeIf(flight -> !currentCards.contains(flight.card) && now > flight.startMs + 50L);
        BlackjackSession.Stage stage = session.stage();
        if (stage != lastStage) {
            if ((lastStage == BlackjackSession.Stage.BETTING || lastStage == BlackjackSession.Stage.ROUND_OVER) && stage == BlackjackSession.Stage.PLAYER_TURN) {
                shuffleAnimUntilMs = Math.max(shuffleAnimUntilMs, now + SHUFFLE_MS);
                spawnChipFlight(l.bankChipX(), l.bankChipY(), l.betCenterX(), l.betCenterY(), Math.max(1, session.roundBet()), now);
            } else if ((lastStage == BlackjackSession.Stage.PLAYER_TURN || lastStage == BlackjackSession.Stage.DEALER_TURN) && stage == BlackjackSession.Stage.ROUND_OVER && lastRoundBet > 0) {
                String status = session.status().toLowerCase();
                boolean toBank = status.contains("win") || status.contains("push") || status.contains("blackjack");
                int tx = toBank ? l.bankChipX() : l.houseChipX();
                int ty = toBank ? l.bankChipY() : l.houseChipY();
                spawnChipFlight(l.betCenterX(), l.betCenterY(), tx, ty, Math.max(1, lastRoundBet), now);
            }
        }
        previousCardPoses.clear();
        for (CardPlacement placement : all) {
            previousCardPoses.put(placement.card(), new CardPose(placement.x(), placement.y(), placement.dealerHand()));
        }
        lastDealerCards = new ArrayList<>(session.dealerCards());
        lastPlayerCards = new ArrayList<>(session.playerCards());
        lastStage = stage;
        lastRoundBet = session.roundBet();
    }
    private List<CardPlacement> orderNewCardPlacements(
        List<CardPlacement> newlyAppeared,
        List<CardPlacement> dealerPlacements,
        List<CardPlacement> playerPlacements,
        Set<BlackjackSession.Card> previousCards
    ) {
        if (newlyAppeared.isEmpty()) return List.of();
        Set<BlackjackSession.Card> fresh = new HashSet<>();
        for (CardPlacement placement : newlyAppeared) fresh.add(placement.card());
        List<CardPlacement> ordered = new ArrayList<>(newlyAppeared.size());
        if (previousCards.isEmpty() && dealerPlacements.size() >= 2 && playerPlacements.size() >= 2) {
            CardPlacement p0 = playerPlacements.get(0);
            CardPlacement d0 = dealerPlacements.get(0);
            CardPlacement p1 = playerPlacements.get(1);
            CardPlacement d1 = dealerPlacements.get(1);
            if (fresh.contains(p0.card())) ordered.add(p0);
            if (fresh.contains(d0.card())) ordered.add(d0);
            if (fresh.contains(p1.card())) ordered.add(p1);
            if (fresh.contains(d1.card())) ordered.add(d1);
        }
        for (CardPlacement placement : playerPlacements) {
            if (fresh.contains(placement.card()) && !ordered.contains(placement)) ordered.add(placement);
        }
        for (CardPlacement placement : dealerPlacements) {
            if (fresh.contains(placement.card()) && !ordered.contains(placement)) ordered.add(placement);
        }
        return ordered;
    }
    int updateBetTween(int targetAmount, long now) {
        if (targetAmount != betTweenTo) {
            betTweenFrom = currentBetTweenAmount(now);
            betTweenTo = targetAmount;
            betTweenStartMs = now;
        }
        return currentBetTweenAmount(now);
    }
    private int currentBetTweenAmount(long now) {
        if (betTweenFrom == betTweenTo) return betTweenTo;
        double t = (now - betTweenStartMs) / (double) BET_TWEEN_MS;
        if (t >= 1.0) return betTweenTo;
        double eased = easeOutCubic(clamp01(t));
        return Math.max(1, (int) Math.round(lerp(betTweenFrom, betTweenTo, eased)));
    }
    private boolean isDealerHoleCard(BlackjackSession.Card card) {
        List<BlackjackSession.Card> dealer = session.dealerCards();
        return session.dealerHoleHidden() && dealer.size() > 1 && dealer.get(1) == card;
    }
    private void spawnChipFlight(int fromX, int fromY, int toX, int toY, int amount, long now) {
        chipFlights.add(new ChipFlight(fromX, fromY, toX, toY, Math.max(1, amount), now, now + CHIP_FLY_MS));
    }
    private static double clamp01(double value) {
        if (value <= 0.0) return 0.0;
        if (value >= 1.0) return 1.0;
        return value;
    }
    private static double lerp(double from, double to, double t) {
        return from + (to - from) * t;
    }
    private static double easeOutCubic(double t) {
        double inv = 1.0 - t;
        return 1.0 - inv * inv * inv;
    }
    private static double easeInOut(double t) {
        return t < 0.5 ? 4.0 * t * t * t : 1.0 - Math.pow(-2.0 * t + 2.0, 3.0) / 2.0;
    }
    private record CardPose(int x, int y, boolean dealerHand) {
    }
    private static final class CardFlight {
        private final BlackjackSession.Card card;
        private boolean dealerHand;
        private final int fromX;
        private final int fromY;
        private int toX;
        private int toY;
        private final long startMs;
        private final long endMs;
        private CardFlight(
            BlackjackSession.Card card,
            boolean dealerHand,
            int fromX,
            int fromY,
            int toX,
            int toY,
            long startMs,
            long endMs
        ) {
            this.card = card;
            this.dealerHand = dealerHand;
            this.fromX = fromX;
            this.fromY = fromY;
            this.toX = toX;
            this.toY = toY;
            this.startMs = startMs;
            this.endMs = endMs;
        }
    }
    private static final class CardGhost {
        private final BlackjackSession.Card card;
        private final boolean dealerHand;
        private final int fromX;
        private final int fromY;
        private final int toX;
        private final int toY;
        private final long startMs;
        private final long endMs;
        private CardGhost(
            BlackjackSession.Card card,
            boolean dealerHand,
            int fromX,
            int fromY,
            int toX,
            int toY,
            long startMs,
            long endMs
        ) {
            this.card = card;
            this.dealerHand = dealerHand;
            this.fromX = fromX;
            this.fromY = fromY;
            this.toX = toX;
            this.toY = toY;
            this.startMs = startMs;
            this.endMs = endMs;
        }
    }
    private static final class ChipFlight {
        private final int fromX;
        private final int fromY;
        private final int toX;
        private final int toY;
        private final int amount;
        private final long startMs;
        private final long endMs;
        private ChipFlight(int fromX, int fromY, int toX, int toY, int amount, long startMs, long endMs) {
            this.fromX = fromX;
            this.fromY = fromY;
            this.toX = toX;
            this.toY = toY;
            this.amount = amount;
            this.startMs = startMs;
            this.endMs = endMs;
        }
    }
}
