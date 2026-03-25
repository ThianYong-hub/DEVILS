package com.example.addon.games.chess;

import java.util.List;
import java.util.Locale;
import java.util.Random;

public final class ChessLogic {
    public static final String INITIAL_FEN = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";

    private ChessLogic() {
    }

    public record Move(int fromX, int fromY, int toX, int toY, char promotion) {
        public String toUci() {
            String base = ChessCore.square(fromX, fromY) + ChessCore.square(toX, toY);
            if (promotion == 0) return base;
            return base + Character.toLowerCase(promotion);
        }
    }

    public record ApplyResult(boolean ok, String fen, String status, String winner, String error) {
    }

    public static String initialFen() {
        return INITIAL_FEN;
    }

    public static boolean isWhiteTurn(String fen) {
        ChessCore.ChessState state = ChessCore.parseFenOrInitial(fen, INITIAL_FEN);
        return state.whiteToMove;
    }

    public static char[][] board(String fen) {
        ChessCore.ChessState state = ChessCore.parseFenOrInitial(fen, INITIAL_FEN);
        return ChessCore.boardCopy(state);
    }

    public static List<Move> legalMoves(String fen) {
        ChessCore.ChessState state = ChessCore.parseFenOrInitial(fen, INITIAL_FEN);
        return ChessCore.generateLegalMoves(state);
    }

    public static String randomScriptMove(String fen, Random random) {
        return scriptMove(fen, random, 2);
    }

    public static String scriptMove(String fen, Random random, int level) {
        return ChessScriptBot.chooseMove(fen, random, level);
    }

    public static ApplyResult applyMove(String fen, String uciMove) {
        ChessCore.ChessState state = ChessCore.parseFenOrInitial(fen, INITIAL_FEN);
        Move requested = parseUciMove(uciMove);
        if (requested == null) {
            return new ApplyResult(false, ChessCore.toFen(state), "invalid", "", "bad-move-format");
        }

        List<Move> legal = ChessCore.generateLegalMoves(state);
        Move selected = null;
        for (Move candidate : legal) {
            if (sameMove(candidate, requested)) {
                selected = candidate;
                break;
            }
        }

        if (selected == null) {
            return new ApplyResult(false, ChessCore.toFen(state), "invalid", "", "illegal-move");
        }

        ChessCore.ChessState next = state.copy();
        ChessCore.applyUnchecked(next, selected);
        String winner = "";
        String status = "ongoing";

        List<Move> replies = ChessCore.generateLegalMoves(next);
        if (replies.isEmpty()) {
            if (ChessCore.isInCheck(next, next.whiteToMove)) {
                status = "checkmate";
                winner = next.whiteToMove ? "black" : "white";
            } else {
                status = "stalemate";
                winner = "draw";
            }
        } else if (next.halfmoveClock >= 100 || insufficientMaterial(ChessCore.boardCopy(next))) {
            status = "draw";
            winner = "draw";
        } else if (!ChessCore.hasKing(next, true)) {
            status = "checkmate";
            winner = "black";
        } else if (!ChessCore.hasKing(next, false)) {
            status = "checkmate";
            winner = "white";
        }

        return new ApplyResult(true, ChessCore.toFen(next), status, winner, "");
    }

    public static Move parseUciMove(String value) {
        if (value == null) return null;
        String move = value.trim().toLowerCase(Locale.ROOT);
        if (move.length() < 4) return null;

        int fromX = ChessCore.fileToX(move.charAt(0));
        int fromY = ChessCore.rankToY(move.charAt(1));
        int toX = ChessCore.fileToX(move.charAt(2));
        int toY = ChessCore.rankToY(move.charAt(3));
        if (!ChessCore.inside(fromX, fromY) || !ChessCore.inside(toX, toY)) return null;

        char promotion = 0;
        if (move.length() >= 5) {
            char p = move.charAt(4);
            if ("qrbn".indexOf(p) < 0) return null;
            promotion = Character.toUpperCase(p);
        }
        return new Move(fromX, fromY, toX, toY, promotion);
    }

    private static boolean sameMove(Move a, Move b) {
        if (a.fromX != b.fromX || a.fromY != b.fromY || a.toX != b.toX || a.toY != b.toY) return false;
        if (a.promotion == 0 || b.promotion == 0) return true;
        return Character.toLowerCase(a.promotion) == Character.toLowerCase(b.promotion);
    }

    private static boolean insufficientMaterial(char[][] board) {
        int whiteMinor = 0;
        int blackMinor = 0;
        int whiteBishopColor = -1;
        int blackBishopColor = -1;
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                char piece = board[y][x];
                char type = Character.toLowerCase(piece);
                if (type == '.' || type == 'k') continue;
                if (type == 'p' || type == 'r' || type == 'q') return false;
                if (Character.isUpperCase(piece)) {
                    whiteMinor++;
                    if (type == 'b') whiteBishopColor = (x + y) & 1;
                } else {
                    blackMinor++;
                    if (type == 'b') blackBishopColor = (x + y) & 1;
                }
            }
        }
        if (whiteMinor <= 1 && blackMinor <= 1) return true;
        return whiteMinor == 1 && blackMinor == 1 && whiteBishopColor >= 0 && whiteBishopColor == blackBishopColor;
    }
}
