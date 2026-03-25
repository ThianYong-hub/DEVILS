package com.example.addon.games.chess;

final class ChessScriptHeuristics {
    private ChessScriptHeuristics() {
    }

    static int evaluateForSideToMove(ChessCore.ChessState state, int mobilityWeight) {
        int white = evaluateWhite(state, mobilityWeight);
        return state.whiteToMove ? white : -white;
    }

    static int pieceValue(char piece) {
        return switch (Character.toLowerCase(piece)) {
            case 'p' -> 100;
            case 'n' -> 330;
            case 'b' -> 350;
            case 'r' -> 530;
            case 'q' -> 980;
            case 'k' -> 20_000;
            default -> 0;
        };
    }

    static boolean isTactical(ChessCore.ChessState state, ChessLogic.Move move) {
        if (move.promotion() != 0) return true;
        char target = state.board[move.toY()][move.toX()];
        if (target != '.') return true;
        char p = state.board[move.fromY()][move.fromX()];
        return Character.toLowerCase(p) == 'p' && move.fromX() != move.toX() && move.toX() == state.epX && move.toY() == state.epY;
    }

    static boolean insufficientMaterial(ChessCore.ChessState state) {
        int whiteMinor = 0;
        int blackMinor = 0;
        int whiteBishopColor = -1;
        int blackBishopColor = -1;
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                char p = state.board[y][x];
                char t = Character.toLowerCase(p);
                if (t == '.' || t == 'k') continue;
                if (t == 'q' || t == 'r' || t == 'p') return false;
                if (Character.isUpperCase(p)) {
                    whiteMinor++;
                    if (t == 'b') whiteBishopColor = (x + y) & 1;
                } else {
                    blackMinor++;
                    if (t == 'b') blackBishopColor = (x + y) & 1;
                }
            }
        }
        if (whiteMinor <= 1 && blackMinor <= 1) return true;
        return whiteMinor == 1 && blackMinor == 1 && whiteBishopColor >= 0 && whiteBishopColor == blackBishopColor;
    }

    static long stateHash(ChessCore.ChessState state) {
        long hash = 0xcbf29ce484222325L;
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                hash ^= state.board[y][x];
                hash *= 0x100000001b3L;
            }
        }
        hash ^= state.whiteToMove ? 0x9E3779B97F4A7C15L : 0xC2B2AE3D27D4EB4FL;
        hash *= 0x100000001b3L;
        for (int i = 0; i < state.castling.length(); i++) {
            hash ^= state.castling.charAt(i);
            hash *= 0x100000001b3L;
        }
        hash ^= (state.epX + 2) * 33L + (state.epY + 2);
        return hash;
    }

    private static int evaluateWhite(ChessCore.ChessState state, int mobilityWeight) {
        char[][] board = state.board;
        int score = 0;

        int[] whitePawns = new int[8];
        int[] blackPawns = new int[8];
        int whiteBishops = 0;
        int blackBishops = 0;
        int whiteKingX = -1;
        int whiteKingY = -1;
        int blackKingX = -1;
        int blackKingY = -1;

        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                char p = board[y][x];
                if (p == '.') continue;
                boolean white = Character.isUpperCase(p);
                char t = Character.toLowerCase(p);

                int v = pieceValue(p) + pieceSquareBonus(p, x, y);
                score += white ? v : -v;

                if (t == 'p') {
                    if (white) whitePawns[x]++;
                    else blackPawns[x]++;
                } else if (t == 'b') {
                    if (white) whiteBishops++;
                    else blackBishops++;
                } else if (t == 'k') {
                    if (white) {
                        whiteKingX = x;
                        whiteKingY = y;
                    } else {
                        blackKingX = x;
                        blackKingY = y;
                    }
                }
            }
        }

        score += pawnStructure(board, whitePawns, true);
        score -= pawnStructure(board, blackPawns, false);
        if (whiteBishops >= 2) score += 36;
        if (blackBishops >= 2) score -= 36;
        score += rookFileBonus(board, true);
        score -= rookFileBonus(board, false);
        score += kingSafety(board, whiteKingX, whiteKingY, true);
        score -= kingSafety(board, blackKingX, blackKingY, false);

        if (mobilityWeight > 0) {
            score += mobilityEstimate(board, true) * mobilityWeight;
            score -= mobilityEstimate(board, false) * mobilityWeight;
        }
        return score;
    }

    private static int pawnStructure(char[][] board, int[] pawnsByFile, boolean white) {
        int score = 0;
        for (int file = 0; file < 8; file++) {
            int count = pawnsByFile[file];
            if (count <= 0) continue;
            if (count > 1) score -= (count - 1) * 14;
            boolean left = file > 0 && pawnsByFile[file - 1] > 0;
            boolean right = file < 7 && pawnsByFile[file + 1] > 0;
            if (!left && !right) score -= 10;
        }

        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                char p = board[y][x];
                if (white && p != 'P') continue;
                if (!white && p != 'p') continue;
                if (isPassedPawn(board, x, y, white)) {
                    int advance = white ? (6 - y) : (y - 1);
                    score += 16 + Math.max(0, advance) * 8;
                }
            }
        }
        return score;
    }

    private static boolean isPassedPawn(char[][] board, int x, int y, boolean white) {
        int step = white ? -1 : 1;
        for (int fx = Math.max(0, x - 1); fx <= Math.min(7, x + 1); fx++) {
            for (int ny = y + step; ny >= 0 && ny < 8; ny += step) {
                char p = board[ny][fx];
                if (white && p == 'p') return false;
                if (!white && p == 'P') return false;
            }
        }
        return true;
    }

    private static int rookFileBonus(char[][] board, boolean white) {
        char rook = white ? 'R' : 'r';
        char myPawn = white ? 'P' : 'p';
        char enemyPawn = white ? 'p' : 'P';
        int score = 0;
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                if (board[y][x] != rook) continue;
                boolean hasMyPawn = false;
                boolean hasEnemyPawn = false;
                for (int yy = 0; yy < 8; yy++) {
                    if (board[yy][x] == myPawn) hasMyPawn = true;
                    else if (board[yy][x] == enemyPawn) hasEnemyPawn = true;
                }
                if (!hasMyPawn && !hasEnemyPawn) score += 20;
                else if (!hasMyPawn) score += 10;
            }
        }
        return score;
    }

    private static int kingSafety(char[][] board, int kx, int ky, boolean white) {
        if (kx < 0 || ky < 0) return -1_200;
        int score = 0;

        if (white && ky >= 6 && (kx <= 2 || kx >= 6)) score += 24;
        if (!white && ky <= 1 && (kx <= 2 || kx >= 6)) score += 24;

        int frontY = white ? ky - 1 : ky + 1;
        if (frontY >= 0 && frontY < 8) {
            for (int x = Math.max(0, kx - 1); x <= Math.min(7, kx + 1); x++) {
                char p = board[frontY][x];
                if (white && p == 'P') score += 8;
                if (!white && p == 'p') score += 8;
            }
        }

        for (int y = Math.max(0, ky - 2); y <= Math.min(7, ky + 2); y++) {
            for (int x = Math.max(0, kx - 2); x <= Math.min(7, kx + 2); x++) {
                char p = board[y][x];
                if (p == '.') continue;
                boolean enemy = Character.isUpperCase(p) != white;
                if (!enemy) continue;
                int dist = Math.abs(x - kx) + Math.abs(y - ky);
                score -= Math.max(0, 14 - dist * 4);
            }
        }
        return score;
    }

    private static int mobilityEstimate(char[][] board, boolean white) {
        int score = 0;
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                char p = board[y][x];
                if (p == '.' || Character.isUpperCase(p) != white) continue;
                score += switch (Character.toLowerCase(p)) {
                    case 'n' -> knightMobility(board, x, y, white);
                    case 'b' -> sliderMobility(board, x, y, white, true, false);
                    case 'r' -> sliderMobility(board, x, y, white, false, true);
                    case 'q' -> sliderMobility(board, x, y, white, true, true);
                    default -> 0;
                };
            }
        }
        return score;
    }

    private static int knightMobility(char[][] board, int x, int y, boolean white) {
        int[][] d = {{1, 2}, {2, 1}, {-1, 2}, {-2, 1}, {1, -2}, {2, -1}, {-1, -2}, {-2, -1}};
        int m = 0;
        for (int[] step : d) {
            int nx = x + step[0];
            int ny = y + step[1];
            if (!ChessCore.inside(nx, ny)) continue;
            char t = board[ny][nx];
            if (t == '.' || Character.isUpperCase(t) != white) m++;
        }
        return m;
    }

    private static int sliderMobility(char[][] board, int x, int y, boolean white, boolean diag, boolean ortho) {
        int score = 0;
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {-1, 1}, {1, -1}, {-1, -1}};
        for (int[] d : dirs) {
            if (!diag && d[0] != 0 && d[1] != 0) continue;
            if (!ortho && (d[0] == 0 || d[1] == 0)) continue;
            int nx = x + d[0];
            int ny = y + d[1];
            while (ChessCore.inside(nx, ny)) {
                char t = board[ny][nx];
                if (t == '.') score++;
                else {
                    if (Character.isUpperCase(t) != white) score++;
                    break;
                }
                nx += d[0];
                ny += d[1];
            }
        }
        return score;
    }

    private static int pieceSquareBonus(char piece, int x, int y) {
        boolean white = Character.isUpperCase(piece);
        int py = white ? y : 7 - y;
        int centerDist = Math.abs(x - 3) + Math.abs(py - 3);
        return switch (Character.toLowerCase(piece)) {
            case 'p' -> (6 - py) * 7 + (x >= 2 && x <= 5 ? 4 : 0);
            case 'n' -> 26 - centerDist * 5;
            case 'b' -> 20 - centerDist * 4;
            case 'r' -> (py <= 1 ? 14 : 0) + ((x == 0 || x == 7) ? -3 : 3);
            case 'q' -> 10 - centerDist * 2;
            case 'k' -> py >= 6 ? 16 - centerDist : 24 - centerDist * 3;
            default -> 0;
        };
    }
}
