package com.example.addon.modules.stashmover;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StashMoverMessageMatcherTest {
    @Test
    void matchesRussianWhisperWithTimestampAndRhLoadTokens() {
        assertTrue(StashMoverInteraction.matchesInboundPartnerMessage(
            "<22:47> AdminDupeBot_1 \u0448\u0435\u043f\u0447\u0435\u0442 \u0432\u0430\u043c: 08a5LOAD PEARL 93e5",
            "AdminDupeBot_1",
            "LOAD PEARL"
        ));
    }

    @Test
    void matchesRussianWhisperWithAckTokens() {
        assertTrue(StashMoverInteraction.matchesInboundPartnerMessage(
            "<22:47> AdminDupeBot_1 \u0448\u0435\u043f\u0447\u0435\u0442 \u0432\u0430\u043c: 41f0 RECEIVED MESSAGE b9cc",
            "AdminDupeBot_1",
            "RECEIVED MESSAGE"
        ));
    }

    @Test
    void rejectsPublicChatWithoutWhisperMarkersOrRhTokenWrapping() {
        assertFalse(StashMoverInteraction.matchesInboundPartnerMessage(
            "<22:47> AdminDupeBot_1: LOAD PEARL",
            "AdminDupeBot_1",
            "LOAD PEARL"
        ));
    }

    @Test
    void rejectsWrongSenderEvenWhenPayloadMatches() {
        assertFalse(StashMoverInteraction.matchesInboundPartnerMessage(
            "<22:47> AnotherBot \u0448\u0435\u043f\u0447\u0435\u0442 \u0432\u0430\u043c: 08a5LOAD PEARL 93e5",
            "AdminDupeBot_1",
            "LOAD PEARL"
        ));
    }

    @Test
    void rejectsSimilarSenderNameThatOnlySharesPrefix() {
        assertFalse(StashMoverInteraction.matchesInboundPartnerMessage(
            "<22:47> AdminDupeBot_10 \u0448\u0435\u043f\u0447\u0435\u0442 \u0432\u0430\u043c: 08a5LOAD PEARL 93e5",
            "AdminDupeBot_1",
            "LOAD PEARL"
        ));
    }
}
