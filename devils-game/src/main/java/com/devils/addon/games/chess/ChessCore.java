package com.devils.addon.games.chess;

import java.util.ArrayList;
import java.util.List;

final class ChessCore {
    private ChessCore() {
    }

    static ChessState parseFenOrInitial(String fen, String initialFen) {
        try {
            return parseFen(fen == null || fen.isBlank() ? initialFen : fen.trim());
        } catch (Exception ignored) {
            return parseFen(initialFen);
        }
    }

    static ChessState parseFen(String fen) {
        String[] parts = fen.split("\\s+");
        if (parts.length < 4) throw new IllegalArgumentException("bad-fen");

        ChessState state = new ChessState();
        String[] rows = parts[0].split("/");
        if (rows.length != 8) throw new IllegalArgumentException("bad-board");
        for (int y = 0; y < 8; y++) {
            int x = 0;
            for (int i = 0; i < rows[y].length(); i++) {
                char ch = rows[y].charAt(i);
                if (Character.isDigit(ch)) {
                    int count = ch - '0';
                    for (int k = 0; k < count; k++) state.board[y][x++] = '.';
                } else {
                    state.board[y][x++] = ch;
                }
            }
            if (x != 8) throw new IllegalArgumentException("bad-rank");
        }

        state.whiteToMove = "w".equalsIgnoreCase(parts[1]);
        state.castling = "-".equals(parts[2]) ? "" : parts[2];
        if ("-".equals(parts[3])) {
            state.epX = -1;
            state.epY = -1;
        } else {
            state.epX = fileToX(parts[3].charAt(0));
            state.epY = rankToY(parts[3].charAt(1));
        }
        state.halfmoveClock = parts.length > 4 ? parseInt(parts[4], 0) : 0;
        state.fullmoveNumber = parts.length > 5 ? Math.max(1, parseInt(parts[5], 1)) : 1;
        return state;
    }

    static String toFen(ChessState state) {
        StringBuilder boardPart = new StringBuilder();
        for (int y = 0; y < 8; y++) {
            if (y > 0) boardPart.append('/');
            int empty = 0;
            for (int x = 0; x < 8; x++) {
                char piece = state.board[y][x];
                if (piece == '.') {
                    empty++;
                    continue;
                }
                if (empty > 0) {
                    boardPart.append(empty);
                    empty = 0;
                }
                boardPart.append(piece);
            }
            if (empty > 0) boardPart.append(empty);
        }

        String castling = state.castling.isBlank() ? "-" : state.castling;
        String ep = state.epX >= 0 && state.epY >= 0 ? square(state.epX, state.epY) : "-";
        return boardPart
            + " "
            + (state.whiteToMove ? "w" : "b")
            + " "
            + castling
            + " "
            + ep
            + " "
            + Math.max(0, state.halfmoveClock)
            + " "
            + Math.max(1, state.fullmoveNumber);
    }

    static char[][] boardCopy(ChessState state) {
        char[][] copy = new char[8][8];
        for (int y = 0; y < 8; y++) {
            System.arraycopy(state.board[y], 0, copy[y], 0, 8);
        }
        return copy;
    }

    static List<ChessLogic.Move> generateLegalMoves(ChessState state) {
        ArrayList<ChessLogic.Move> pseudo = generatePseudoMoves(state);
        ArrayList<ChessLogic.Move> legal = new ArrayList<>();
        boolean movingWhite = state.whiteToMove;
        for (ChessLogic.Move move : pseudo) {
            ChessState copy = state.copy();
            applyUnchecked(copy, move);
            if (!isInCheck(copy, movingWhite)) legal.add(move);
        }
        return legal;
    }

    static void applyUnchecked(ChessState state, ChessLogic.Move move) {
        char piece = state.board[move.fromY()][move.fromX()];
        boolean white = isWhite(piece);
        char target = state.board[move.toY()][move.toX()];
        boolean pawnMove = Character.toLowerCase(piece) == 'p';
        boolean capture = target != '.';

        if (pawnMove && move.toX() == state.epX && move.toY() == state.epY && target == '.') {
            int capturedY = white ? move.toY() + 1 : move.toY() - 1;
            if (inside(move.toX(), capturedY)) {
                state.board[capturedY][move.toX()] = '.';
                capture = true;
            }
        }

        state.board[move.fromY()][move.fromX()] = '.';

        if (Character.toLowerCase(piece) == 'k' && Math.abs(move.toX() - move.fromX()) == 2) {
            if (move.toX() == 6) {
                state.board[move.toY()][5] = state.board[move.toY()][7];
                state.board[move.toY()][7] = '.';
            } else if (move.toX() == 2) {
                state.board[move.toY()][3] = state.board[move.toY()][0];
                state.board[move.toY()][0] = '.';
            }
        }

        char placed = piece;
        if (pawnMove && (move.toY() == 0 || move.toY() == 7)) {
            char promotion = move.promotion() == 0 ? 'Q' : Character.toUpperCase(move.promotion());
            placed = white ? promotion : Character.toLowerCase(promotion);
        }
        state.board[move.toY()][move.toX()] = placed;

        updateCastlingRightsOnMove(state, piece, move.fromX(), move.fromY());
        updateCastlingRightsOnCapture(state, move.toX(), move.toY(), target);

        state.epX = -1;
        state.epY = -1;
        if (pawnMove && Math.abs(move.toY() - move.fromY()) == 2) {
            state.epX = move.fromX();
            state.epY = (move.fromY() + move.toY()) / 2;
        }

        if (pawnMove || capture) state.halfmoveClock = 0;
        else state.halfmoveClock++;

        if (!state.whiteToMove) state.fullmoveNumber++;
        state.whiteToMove = !state.whiteToMove;
    }

    static boolean isInCheck(ChessState state, boolean whiteKing) {
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                char piece = state.board[y][x];
                if (piece == (whiteKing ? 'K' : 'k')) {
                    return isSquareAttacked(state, x, y, !whiteKing);
                }
            }
        }
        return true;
    }

    static boolean hasKing(ChessState state, boolean whiteKing) {
        char wanted = whiteKing ? 'K' : 'k';
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                if (state.board[y][x] == wanted) return true;
            }
        }
        return false;
    }

    static int fileToX(char file) {
        return file - 'a';
    }

    static int rankToY(char rank) {
        return 8 - (rank - '0');
    }

    static String square(int x, int y) {
        char file = (char) ('a' + x);
        char rank = (char) ('8' - y);
        return "" + file + rank;
    }

    static boolean inside(int x, int y) {
        return x >= 0 && x < 8 && y >= 0 && y < 8;
    }

    private static ArrayList<ChessLogic.Move> generatePseudoMoves(ChessState state) {
        ArrayList<ChessLogic.Move> moves = new ArrayList<>();
        boolean white = state.whiteToMove;
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                char piece = state.board[y][x];
                if (piece == '.') continue;
                if (white != isWhite(piece)) continue;

                switch (Character.toLowerCase(piece)) {
                    case 'p' -> addPawnMoves(state, moves, x, y, white);
                    case 'n' -> addKnightMoves(state, moves, x, y, white);
                    case 'b' -> addSlidingMoves(state, moves, x, y, white, new int[][] {{1, 1}, {1, -1}, {-1, 1}, {-1, -1}});
                    case 'r' -> addSlidingMoves(state, moves, x, y, white, new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}});
                    case 'q' -> addSlidingMoves(state, moves, x, y, white, new int[][] {
                        {1, 1}, {1, -1}, {-1, 1}, {-1, -1}, {1, 0}, {-1, 0}, {0, 1}, {0, -1}
                    });
                    case 'k' -> addKingMoves(state, moves, x, y, white);
                    default -> {
                    }
                }
            }
        }
        return moves;
    }

    private static void addPawnMoves(ChessState state, List<ChessLogic.Move> moves, int x, int y, boolean white) {
        int dir = white ? -1 : 1;
        int startRow = white ? 6 : 1;
        int promotionRow = white ? 0 : 7;

        int oneStepY = y + dir;
        if (inside(x, oneStepY) && state.board[oneStepY][x] == '.') {
            addPawnAdvance(moves, x, y, x, oneStepY, promotionRow);
            int twoStepY = y + (dir * 2);
            if (y == startRow && inside(x, twoStepY) && state.board[twoStepY][x] == '.') {
                moves.add(new ChessLogic.Move(x, y, x, twoStepY, (char) 0));
            }
        }

        for (int dx : new int[] {-1, 1}) {
            int tx = x + dx;
            int ty = y + dir;
            if (!inside(tx, ty)) continue;

            char target = state.board[ty][tx];
            if (target != '.' && isWhite(target) != white) {
                addPawnAdvance(moves, x, y, tx, ty, promotionRow);
                continue;
            }

            if (state.epX == tx && state.epY == ty) {
                moves.add(new ChessLogic.Move(x, y, tx, ty, (char) 0));
            }
        }
    }

    private static void addPawnAdvance(List<ChessLogic.Move> moves, int fromX, int fromY, int toX, int toY, int promotionRow) {
        if (toY != promotionRow) {
            moves.add(new ChessLogic.Move(fromX, fromY, toX, toY, (char) 0));
            return;
        }

        moves.add(new ChessLogic.Move(fromX, fromY, toX, toY, 'Q'));
        moves.add(new ChessLogic.Move(fromX, fromY, toX, toY, 'R'));
        moves.add(new ChessLogic.Move(fromX, fromY, toX, toY, 'B'));
        moves.add(new ChessLogic.Move(fromX, fromY, toX, toY, 'N'));
    }

    private static void addKnightMoves(ChessState state, List<ChessLogic.Move> moves, int x, int y, boolean white) {
        int[][] deltas = {
            {1, 2}, {2, 1}, {-1, 2}, {-2, 1},
            {1, -2}, {2, -1}, {-1, -2}, {-2, -1}
        };
        for (int[] d : deltas) {
            int tx = x + d[0];
            int ty = y + d[1];
            if (!inside(tx, ty)) continue;
            char target = state.board[ty][tx];
            if (target == '.' || isWhite(target) != white) moves.add(new ChessLogic.Move(x, y, tx, ty, (char) 0));
        }
    }

    private static void addSlidingMoves(ChessState state, List<ChessLogic.Move> moves, int x, int y, boolean white, int[][] dirs) {
        for (int[] d : dirs) {
            int tx = x + d[0];
            int ty = y + d[1];
            while (inside(tx, ty)) {
                char target = state.board[ty][tx];
                if (target == '.') {
                    moves.add(new ChessLogic.Move(x, y, tx, ty, (char) 0));
                } else {
                    if (isWhite(target) != white) moves.add(new ChessLogic.Move(x, y, tx, ty, (char) 0));
                    break;
                }
                tx += d[0];
                ty += d[1];
            }
        }
    }

    private static void addKingMoves(ChessState state, List<ChessLogic.Move> moves, int x, int y, boolean white) {
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                if (dx == 0 && dy == 0) continue;
                int tx = x + dx;
                int ty = y + dy;
                if (!inside(tx, ty)) continue;
                char target = state.board[ty][tx];
                if (target == '.' || isWhite(target) != white) moves.add(new ChessLogic.Move(x, y, tx, ty, (char) 0));
            }
        }

        if (white && x == 4 && y == 7) {
            if (hasCastling(state, 'K')
                && state.board[7][5] == '.'
                && state.board[7][6] == '.'
                && !isSquareAttacked(state, 4, 7, false)
                && !isSquareAttacked(state, 5, 7, false)
                && !isSquareAttacked(state, 6, 7, false)
                && state.board[7][7] == 'R'
            ) {
                moves.add(new ChessLogic.Move(4, 7, 6, 7, (char) 0));
            }
            if (hasCastling(state, 'Q')
                && state.board[7][1] == '.'
                && state.board[7][2] == '.'
                && state.board[7][3] == '.'
                && !isSquareAttacked(state, 4, 7, false)
                && !isSquareAttacked(state, 3, 7, false)
                && !isSquareAttacked(state, 2, 7, false)
                && state.board[7][0] == 'R'
            ) {
                moves.add(new ChessLogic.Move(4, 7, 2, 7, (char) 0));
            }
        } else if (!white && x == 4 && y == 0) {
            if (hasCastling(state, 'k')
                && state.board[0][5] == '.'
                && state.board[0][6] == '.'
                && !isSquareAttacked(state, 4, 0, true)
                && !isSquareAttacked(state, 5, 0, true)
                && !isSquareAttacked(state, 6, 0, true)
                && state.board[0][7] == 'r'
            ) {
                moves.add(new ChessLogic.Move(4, 0, 6, 0, (char) 0));
            }
            if (hasCastling(state, 'q')
                && state.board[0][1] == '.'
                && state.board[0][2] == '.'
                && state.board[0][3] == '.'
                && !isSquareAttacked(state, 4, 0, true)
                && !isSquareAttacked(state, 3, 0, true)
                && !isSquareAttacked(state, 2, 0, true)
                && state.board[0][0] == 'r'
            ) {
                moves.add(new ChessLogic.Move(4, 0, 2, 0, (char) 0));
            }
        }
    }

    private static boolean hasCastling(ChessState state, char right) {
        return state.castling.indexOf(right) >= 0;
    }

    private static void updateCastlingRightsOnMove(ChessState state, char piece, int fromX, int fromY) {
        switch (piece) {
            case 'K' -> state.castling = removeCastling(state.castling, 'K', 'Q');
            case 'k' -> state.castling = removeCastling(state.castling, 'k', 'q');
            case 'R' -> {
                if (fromX == 0 && fromY == 7) state.castling = removeCastling(state.castling, 'Q');
                if (fromX == 7 && fromY == 7) state.castling = removeCastling(state.castling, 'K');
            }
            case 'r' -> {
                if (fromX == 0 && fromY == 0) state.castling = removeCastling(state.castling, 'q');
                if (fromX == 7 && fromY == 0) state.castling = removeCastling(state.castling, 'k');
            }
            default -> {
            }
        }
    }

    private static void updateCastlingRightsOnCapture(ChessState state, int toX, int toY, char target) {
        if (target == 'R') {
            if (toX == 0 && toY == 7) state.castling = removeCastling(state.castling, 'Q');
            if (toX == 7 && toY == 7) state.castling = removeCastling(state.castling, 'K');
        } else if (target == 'r') {
            if (toX == 0 && toY == 0) state.castling = removeCastling(state.castling, 'q');
            if (toX == 7 && toY == 0) state.castling = removeCastling(state.castling, 'k');
        }
    }

    private static String removeCastling(String castling, char... rights) {
        String result = castling;
        for (char right : rights) result = result.replace(String.valueOf(right), "");
        return result;
    }

    private static boolean isSquareAttacked(ChessState state, int targetX, int targetY, boolean byWhite) {
        int pawnDir = byWhite ? -1 : 1;
        int pawnY = targetY - pawnDir;
        if (inside(targetX - 1, pawnY)) {
            char p = state.board[pawnY][targetX - 1];
            if (p == (byWhite ? 'P' : 'p')) return true;
        }
        if (inside(targetX + 1, pawnY)) {
            char p = state.board[pawnY][targetX + 1];
            if (p == (byWhite ? 'P' : 'p')) return true;
        }

        int[][] knight = {
            {1, 2}, {2, 1}, {-1, 2}, {-2, 1},
            {1, -2}, {2, -1}, {-1, -2}, {-2, -1}
        };
        for (int[] d : knight) {
            int x = targetX + d[0];
            int y = targetY + d[1];
            if (!inside(x, y)) continue;
            char p = state.board[y][x];
            if (p == (byWhite ? 'N' : 'n')) return true;
        }

        int[][] bishopDirs = {{1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
        for (int[] d : bishopDirs) {
            int x = targetX + d[0];
            int y = targetY + d[1];
            while (inside(x, y)) {
                char p = state.board[y][x];
                if (p != '.') {
                    if (isWhite(p) == byWhite && (Character.toLowerCase(p) == 'b' || Character.toLowerCase(p) == 'q')) return true;
                    break;
                }
                x += d[0];
                y += d[1];
            }
        }

        int[][] rookDirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] d : rookDirs) {
            int x = targetX + d[0];
            int y = targetY + d[1];
            while (inside(x, y)) {
                char p = state.board[y][x];
                if (p != '.') {
                    if (isWhite(p) == byWhite && (Character.toLowerCase(p) == 'r' || Character.toLowerCase(p) == 'q')) return true;
                    break;
                }
                x += d[0];
                y += d[1];
            }
        }

        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                if (dx == 0 && dy == 0) continue;
                int x = targetX + dx;
                int y = targetY + dy;
                if (!inside(x, y)) continue;
                char p = state.board[y][x];
                if (p == (byWhite ? 'K' : 'k')) return true;
            }
        }
        return false;
    }

    private static boolean isWhite(char piece) {
        return Character.isUpperCase(piece);
    }

    private static int parseInt(String raw, int fallback) {
        try {
            return Integer.parseInt(raw);
        } catch (Exception ignored) {
            return fallback;
        }
    }

    static final class ChessState {
        final char[][] board = new char[8][8];
        boolean whiteToMove = true;
        String castling = "KQkq";
        int epX = -1;
        int epY = -1;
        int halfmoveClock = 0;
        int fullmoveNumber = 1;

        ChessState copy() {
            ChessState copy = new ChessState();
            for (int y = 0; y < 8; y++) {
                System.arraycopy(board[y], 0, copy.board[y], 0, 8);
            }
            copy.whiteToMove = whiteToMove;
            copy.castling = castling;
            copy.epX = epX;
            copy.epY = epY;
            copy.halfmoveClock = halfmoveClock;
            copy.fullmoveNumber = fullmoveNumber;
            return copy;
        }
    }
}

