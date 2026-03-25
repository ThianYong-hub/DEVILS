package com.example.addon.games.checkers;

import com.example.addon.games.checkers.CheckersLogic.Coord;
import com.example.addon.games.checkers.CheckersLogic.Move;

final class CheckersScriptHeuristics {
    private static final int[][] KING_DIRS = {
        {1, 1}, {-1, 1}, {1, -1}, {-1, -1}
    };

    private CheckersScriptHeuristics() {
    }

    static int evaluateForSideToMove(String state, int mobilityWeight) {
        int white = evaluateWhite(state, mobilityWeight);
        return CheckersLogic.isWhiteTurn(state) ? white : -white;
    }

    static boolean isPromotionMove(String state, Move move) {
        if (move.path() == null || move.path().size() < 2) return false;
        Coord from = move.path().get(0);
        Coord to = move.path().get(move.path().size() - 1);
        char[][] board = CheckersLogic.board(state);
        char p = board[from.y()][from.x()];
        return (p == 'w' && to.y() == 0) || (p == 'b' && to.y() == 7);
    }

    static long stateHash(String state) {
        long hash = 0xcbf29ce484222325L;
        for (int i = 0; i < state.length(); i++) {
            hash ^= state.charAt(i);
            hash *= 0x100000001b3L;
        }
        return hash;
    }

    static int moveKey(Move move) {
        if (move == null || move.path() == null || move.path().isEmpty()) return 0;
        Coord from = move.path().get(0);
        Coord to = move.path().get(move.path().size() - 1);
        int fromIdx = from.y() * 8 + from.x();
        int toIdx = to.y() * 8 + to.x();
        int type = move.capture() ? 1 : 0;
        int len = Math.min(15, move.path().size());
        return (fromIdx << 12) | (toIdx << 4) | (type << 3) | len;
    }

    private static int evaluateWhite(String state, int mobilityWeight) {
        char[][] board = CheckersLogic.board(state);

        int score = 0;
        int whiteMen = 0;
        int blackMen = 0;
        int whiteKings = 0;
        int blackKings = 0;

        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                char p = board[y][x];
                if (p == '.') continue;

                int v;
                switch (p) {
                    case 'w' -> {
                        whiteMen++;
                        v = 120 + (7 - y) * 9;
                        if (x >= 2 && x <= 5 && y >= 2 && y <= 5) v += 16;
                        if (y == 7) v += 12;
                        if (x == 0 || x == 7) v -= 8;
                        if (isSupported(board, x, y, true)) v += 10;
                    }
                    case 'b' -> {
                        blackMen++;
                        v = -(120 + y * 9);
                        if (x >= 2 && x <= 5 && y >= 2 && y <= 5) v -= 16;
                        if (y == 0) v -= 12;
                        if (x == 0 || x == 7) v += 8;
                        if (isSupported(board, x, y, false)) v -= 10;
                    }
                    case 'W' -> {
                        whiteKings++;
                        int distCenter = Math.abs(x - 3) + Math.abs(y - 3);
                        v = 320 + (24 - distCenter * 4);
                    }
                    case 'B' -> {
                        blackKings++;
                        int distCenter = Math.abs(x - 3) + Math.abs(y - 3);
                        v = -(320 + (24 - distCenter * 4));
                    }
                    default -> v = 0;
                }
                score += v;
            }
        }

        score += whiteMen * 6 + whiteKings * 14;
        score -= blackMen * 6 + blackKings * 14;
        score += promotionThreat(board, true);
        score -= promotionThreat(board, false);

        if (mobilityWeight > 0) {
            score += mobilityPotential(board, true) * mobilityWeight;
            score -= mobilityPotential(board, false) * mobilityWeight;
        }

        int endgameWeight = Math.max(0, 24 - (whiteMen + blackMen + whiteKings + blackKings));
        score += endgameWeight * (whiteKings - blackKings) * 3;
        return score;
    }

    private static int promotionThreat(char[][] board, boolean white) {
        int score = 0;
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                char p = board[y][x];
                if (white && p != 'w') continue;
                if (!white && p != 'b') continue;
                int dist = white ? y : (7 - y);
                if (dist <= 2) score += (3 - dist) * 14;
            }
        }
        return score;
    }

    private static int mobilityPotential(char[][] board, boolean white) {
        int score = 0;
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                char p = board[y][x];
                if (p == '.') continue;
                if (white != (p == 'w' || p == 'W')) continue;

                if (p == 'w' || p == 'b') {
                    int dir = (p == 'w') ? -1 : 1;
                    for (int dx : new int[] {-1, 1}) {
                        int nx = x + dx;
                        int ny = y + dir;
                        if (inside(nx, ny) && board[ny][nx] == '.') score += 2;
                    }
                    for (int[] d : KING_DIRS) {
                        int mx = x + d[0];
                        int my = y + d[1];
                        int lx = x + d[0] * 2;
                        int ly = y + d[1] * 2;
                        if (!inside(mx, my) || !inside(lx, ly)) continue;
                        char middle = board[my][mx];
                        if (middle == '.') continue;
                        boolean enemy = white != (middle == 'w' || middle == 'W');
                        if (enemy && board[ly][lx] == '.') score += 5;
                    }
                } else {
                    for (int[] d : KING_DIRS) {
                        int nx = x + d[0];
                        int ny = y + d[1];
                        while (inside(nx, ny) && board[ny][nx] == '.') {
                            score += 1;
                            nx += d[0];
                            ny += d[1];
                        }
                    }
                }
            }
        }
        return score;
    }

    private static boolean isSupported(char[][] board, int x, int y, boolean white) {
        int back = white ? 1 : -1;
        int sy = y + back;
        if (sy < 0 || sy > 7) return false;
        char left = inside(x - 1, sy) ? board[sy][x - 1] : '.';
        char right = inside(x + 1, sy) ? board[sy][x + 1] : '.';
        if (white) return left == 'w' || left == 'W' || right == 'w' || right == 'W';
        return left == 'b' || left == 'B' || right == 'b' || right == 'B';
    }

    private static boolean inside(int x, int y) {
        return x >= 0 && x < 8 && y >= 0 && y < 8;
    }
}
