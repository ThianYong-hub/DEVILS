package com.devils.addon.modules.games;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlackjackSessionTest {

    private static BlackjackSession.Card card(BlackjackSession.Suit suit, BlackjackSession.Rank rank) {
        return new BlackjackSession.Card(suit, rank);
    }

    @Test
    void blackjackPaysThreeToTwoWithHalfUpRounding() {
        BlackjackSession session = new BlackjackSession();
        int bankrollBefore = session.bankroll();

        session.player.add(card(BlackjackSession.Suit.SPADE, BlackjackSession.Rank.ACE));
        session.player.add(card(BlackjackSession.Suit.HEART, BlackjackSession.Rank.KING));
        session.dealer.add(card(BlackjackSession.Suit.CLUB, BlackjackSession.Rank.TWO));
        session.dealer.add(card(BlackjackSession.Suit.DIAMOND, BlackjackSession.Rank.THREE));
        setField(session, "roundBet", 5);
        setField(session, "stage", BlackjackSession.Stage.PLAYER_TURN);

        session.settleNaturals();

        // Bet 5 pays 2.5x = 12.5, rounded half-up to 13.
        assertEquals(bankrollBefore + 13, session.bankroll());
        assertTrue(session.status().startsWith("Blackjack! Paid +8"));
    }

    @Test
    void evenBetBlackjackPaysExactly() {
        BlackjackSession session = new BlackjackSession();
        int bankrollBefore = session.bankroll();

        session.player.add(card(BlackjackSession.Suit.SPADE, BlackjackSession.Rank.ACE));
        session.player.add(card(BlackjackSession.Suit.CLUB, BlackjackSession.Rank.QUEEN));
        session.dealer.add(card(BlackjackSession.Suit.HEART, BlackjackSession.Rank.FIVE));
        session.dealer.add(card(BlackjackSession.Suit.DIAMOND, BlackjackSession.Rank.SEVEN));
        setField(session, "roundBet", 10);
        setField(session, "stage", BlackjackSession.Stage.PLAYER_TURN);

        session.settleNaturals();

        // Bet 10 pays exactly 25.
        assertEquals(bankrollBefore + 25, session.bankroll());
    }

    @Test
    void bothBlackjackIsAPush() {
        BlackjackSession session = new BlackjackSession();
        int bankrollBefore = session.bankroll();

        session.player.add(card(BlackjackSession.Suit.SPADE, BlackjackSession.Rank.ACE));
        session.player.add(card(BlackjackSession.Suit.HEART, BlackjackSession.Rank.KING));
        session.dealer.add(card(BlackjackSession.Suit.CLUB, BlackjackSession.Rank.ACE));
        session.dealer.add(card(BlackjackSession.Suit.DIAMOND, BlackjackSession.Rank.JACK));
        setField(session, "roundBet", 10);
        setField(session, "stage", BlackjackSession.Stage.PLAYER_TURN);

        session.settleNaturals();

        assertEquals(bankrollBefore + 10, session.bankroll());
        assertTrue(session.status().contains("Push"));
    }

    @Test
    void dealerBlackjackLosesTheBet() {
        BlackjackSession session = new BlackjackSession();
        int bankrollBefore = session.bankroll();

        session.player.add(card(BlackjackSession.Suit.SPADE, BlackjackSession.Rank.TWO));
        session.player.add(card(BlackjackSession.Suit.HEART, BlackjackSession.Rank.THREE));
        session.dealer.add(card(BlackjackSession.Suit.CLUB, BlackjackSession.Rank.ACE));
        session.dealer.add(card(BlackjackSession.Suit.DIAMOND, BlackjackSession.Rank.TEN));
        setField(session, "roundBet", 10);
        setField(session, "stage", BlackjackSession.Stage.PLAYER_TURN);

        session.settleNaturals();

        // Bet was already deducted; the round ends with nothing returned.
        assertEquals(bankrollBefore, session.bankroll());
    }

    @Test
    void lowShoeReshufflesIntoFreshDuplicateFreeShoe() {
        BlackjackSession session = new BlackjackSession();

        // Drain the shoe almost completely.
        while (session.shoe.size() >= 20) {
            session.drawCard();
        }

        session.ensureShoe();

        // A fresh 4-deck shoe has exactly 208 cards.
        assertEquals(208, session.shoe.size());

        // No card may appear more often than its 4 deck copies.
        Map<String, Integer> counts = new HashMap<>();
        for (BlackjackSession.Card card : session.shoe) {
            String key = card.rank().name() + "|" + card.suit().name();
            counts.merge(key, 1, Integer::sum);
        }
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            assertEquals(4, entry.getValue(), "card " + entry.getKey() + " appeared " + entry.getValue() + " times");
        }
    }

    @Test
    void dealDeductsTheBetAndDealsTwoCardsEach() {
        BlackjackSession session = new BlackjackSession();
        int bankrollBefore = session.bankroll();
        int expectedBet = session.baseBet();

        assertTrue(session.deal());

        assertEquals(bankrollBefore - expectedBet, session.bankroll());
        assertEquals(2, session.player.size());
        assertEquals(2, session.dealer.size());
        assertFalse(session.status().isEmpty());
    }

    private static void setField(BlackjackSession session, String name, Object value) {
        try {
            java.lang.reflect.Field field = BlackjackSession.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(session, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
