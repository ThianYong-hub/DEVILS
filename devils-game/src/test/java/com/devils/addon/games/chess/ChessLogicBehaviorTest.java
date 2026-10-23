package com.devils.addon.games.chess;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChessLogicBehaviorTest {

    @Test
    void initialPositionHasExactlyTwentyLegalMoves() {
        List<ChessLogic.Move> moves = ChessLogic.legalMoves(ChessLogic.initialFen());

        assertEquals(20, moves.size());
        assertTrue(ChessLogic.isWhiteTurn(ChessLogic.initialFen()));
    }

    @Test
    void applyingALegalMoveChangesThePosition() {
        String initial = ChessLogic.initialFen();

        ChessLogic.ApplyResult result = ChessLogic.applyMove(initial, "e2e4");

        assertTrue(result.ok());
        assertNotEquals(initial, result.fen());
        assertFalse(ChessLogic.isWhiteTurn(result.fen()));
        assertTrue(result.winner().isEmpty());
    }

    @Test
    void rejectingAnIllegalMove() {
        // Pawn cannot move three squares.
        ChessLogic.ApplyResult result = ChessLogic.applyMove(ChessLogic.initialFen(), "e2e5");

        assertFalse(result.ok());
        assertFalse(result.error().isEmpty());
    }

    @Test
    void rejectingAMoveForTheWrongSide() {
        String afterPawn = ChessLogic.applyMove(ChessLogic.initialFen(), "e2e4").fen();

        // White cannot move again right away.
        ChessLogic.ApplyResult result = ChessLogic.applyMove(afterPawn, "d2d4");

        assertFalse(result.ok());
    }

    @Test
    void foolsMateDetectsBlackWinner() {
        String fen = ChessLogic.initialFen();
        for (String uci : new String[] {"f2f3", "e7e5", "g2g4", "d8h4"}) {
            ChessLogic.ApplyResult result = ChessLogic.applyMove(fen, uci);
            assertTrue(result.ok(), "move " + uci + " must be legal, error: " + result.error());
            fen = result.fen();
            if (!result.winner().isEmpty()) {
                assertEquals("black", result.winner());
                assertEquals("checkmate", result.status());
                return;
            }
        }
        throw new AssertionError("fool's mate sequence did not end the game");
    }

    @Test
    void legalMoveListNeverContainsMovesIntoCheck() {
        // Black king on e8 in check from the e2 queen (not mate): every legal move
        // must be a king evasion, and each must leave black's king capturable never.
        String inCheck = "4k3/8/8/8/8/8/4Q3/4K3 b - - 0 1";
        List<ChessLogic.Move> moves = ChessLogic.legalMoves(inCheck);

        assertFalse(moves.isEmpty());
        for (ChessLogic.Move move : moves) {
            ChessLogic.ApplyResult result = ChessLogic.applyMove(inCheck, move.toUci());
            assertTrue(result.ok(), "listed move " + move.toUci() + " failed to apply");
            assertTrue(result.winner().isEmpty() || !"white".equals(result.winner()),
                "listed move " + move.toUci() + " still loses the king");
        }
    }
}
