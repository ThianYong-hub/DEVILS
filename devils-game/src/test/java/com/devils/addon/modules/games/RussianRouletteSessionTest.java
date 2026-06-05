package com.devils.addon.modules.games;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RussianRouletteSessionTest {
    @Test
    void resetStartsInNeedsSpinState() {
        RussianRouletteSession session = new RussianRouletteSession();

        assertEquals(RussianRouletteSession.Stage.NEEDS_SPIN, session.stage());
        assertEquals(RussianRouletteSession.MAX_LIVES, session.lives());
        assertTrue(session.canSpin());
        assertFalse(session.canTrigger());
        assertEquals("Spin cylinder, then pull trigger.", session.status());
    }

    @Test
    void pullTriggerBeforeSpinKeepsSessionBlocked() {
        RussianRouletteSession session = new RussianRouletteSession();

        session.pullTrigger(null);

        assertEquals(RussianRouletteSession.Stage.NEEDS_SPIN, session.stage());
        assertEquals("Spin first.", session.status());
        assertEquals(RussianRouletteSession.MAX_LIVES, session.lives());
    }

    @Test
    void startSpinMovesSessionIntoSpinningState() {
        RussianRouletteSession session = new RussianRouletteSession();

        session.startSpin();

        assertEquals(RussianRouletteSession.Stage.SPINNING, session.stage());
        assertTrue(session.spinning());
        assertEquals("Spinning cylinder...", session.status());
    }

    @Test
    void resetClearsDeferredCloseAllRequest() {
        RussianRouletteSession session = new RussianRouletteSession();

        assertFalse(session.consumeCloseAllRequested());
        session.reset();

        assertFalse(session.consumeCloseAllRequested());
        assertNotNull(session.status());
    }
}
