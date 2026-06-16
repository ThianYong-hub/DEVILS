package com.devils.addon.games.chess;

final class ChessScriptHeuristics {
    private ChessScriptHeuristics() {
    }

    // =====================================================
    //  Piece-square tables (from white's perspective, rank 0 = rank 8 = top)
    //  Values in centipawns, added to base piece value.
    //  Source: simplified Stockfish PST concept.
    // =====================================================

    // Pawn PST — midgame
    private static final int[] PST_P_MG = {
         0,  0,  0,  0,  0,  0,  0,  0,
        50, 50, 50, 50, 50, 50, 50, 50,
        10, 10, 20, 30, 30, 20, 10, 10,
         5,  5, 10, 25, 25, 10,  5,  5,
         0,  0,  0, 20, 20,  0,  0,  0,
         5, -5,-10,  0,  0,-10, -5,  5,
         5, 10, 10,-20,-20, 10, 10,  5,
         0,  0,  0,  0,  0,  0,  0,  0
    };

    // Pawn PST — endgame
    private static final int[] PST_P_EG = {
         0,  0,  0,  0,  0,  0,  0,  0,
        90, 90, 90, 90, 90, 90, 90, 90,
        60, 60, 60, 60, 60, 60, 60, 60,
        40, 40, 40, 40, 40, 40, 40, 40,
        20, 20, 20, 20, 20, 20, 20, 20,
        10, 10, 10, 10, 10, 10, 10, 10,
         0,  0,  0,  0,  0,  0,  0,  0,
         0,  0,  0,  0,  0,  0,  0,  0
    };

    // Knight PST — midgame
    private static final int[] PST_N_MG = {
        -50,-40,-30,-30,-30,-30,-40,-50,
        -40,-20,  0,  0,  0,  0,-20,-40,
        -30,  0, 10, 15, 15, 10,  0,-30,
        -30,  5, 15, 20, 20, 15,  5,-30,
        -30,  0, 15, 20, 20, 15,  0,-30,
        -30,  5, 10, 15, 15, 10,  5,-30,
        -40,-20,  0,  5,  5,  0,-20,-40,
        -50,-40,-30,-30,-30,-30,-40,-50
    };

    // Knight PST — endgame
    private static final int[] PST_N_EG = {
        -50,-40,-30,-30,-30,-30,-40,-50,
        -40,-20,  0,  0,  0,  0,-20,-40,
        -30,  0, 10, 15, 15, 10,  0,-30,
        -30,  5, 15, 20, 20, 15,  5,-30,
        -30,  0, 15, 20, 20, 15,  0,-30,
        -30,  5, 10, 15, 15, 10,  5,-30,
        -40,-20,  0,  5,  5,  0,-20,-40,
        -50,-40,-30,-30,-30,-30,-40,-50
    };

    // Bishop PST — midgame
    private static final int[] PST_B_MG = {
        -20,-10,-10,-10,-10,-10,-10,-20,
        -10,  0,  0,  0,  0,  0,  0,-10,
        -10,  0,  5, 10, 10,  5,  0,-10,
        -10,  5,  5, 10, 10,  5,  5,-10,
        -10,  0, 10, 10, 10, 10,  0,-10,
        -10, 10, 10, 10, 10, 10, 10,-10,
        -10,  5,  0,  0,  0,  0,  5,-10,
        -20,-10,-10,-10,-10,-10,-10,-20
    };

    // Bishop PST — endgame
    private static final int[] PST_B_EG = {
        -20,-10,-10,-10,-10,-10,-10,-20,
        -10,  0,  0,  0,  0,  0,  0,-10,
        -10,  0, 10, 10, 10, 10,  0,-10,
        -10,  0, 10, 10, 10, 10,  0,-10,
        -10,  0, 10, 10, 10, 10,  0,-10,
        -10,  0, 10, 10, 10, 10,  0,-10,
        -10,  0,  0,  0,  0,  0,  0,-10,
        -20,-10,-10,-10,-10,-10,-10,-20
    };

    // Rook PST — midgame
    private static final int[] PST_R_MG = {
         0,  0,  0,  0,  0,  0,  0,  0,
         5, 10, 10, 10, 10, 10, 10,  5,
        -5,  0,  0,  0,  0,  0,  0, -5,
        -5,  0,  0,  0,  0,  0,  0, -5,
        -5,  0,  0,  0,  0,  0,  0, -5,
        -5,  0,  0,  0,  0,  0,  0, -5,
        -5,  0,  0,  0,  0,  0,  0, -5,
         0,  0,  0,  5,  5,  0,  0,  0
    };

    // Rook PST — endgame
    private static final int[] PST_R_EG = {
         0,  0,  0,  0,  0,  0,  0,  0,
         5, 10, 10, 10, 10, 10, 10,  5,
         0,  0,  0,  0,  0,  0,  0,  0,
         0,  0,  0,  0,  0,  0,  0,  0,
         0,  0,  0,  0,  0,  0,  0,  0,
         0,  0,  0,  0,  0,  0,  0,  0,
         0,  0,  0,  0,  0,  0,  0,  0,
         0,  0,  0,  5,  5,  0,  0,  0
    };

    // Queen PST — midgame
    private static final int[] PST_Q_MG = {
        -20,-10,-10, -5, -5,-10,-10,-20,
        -10,  0,  0,  0,  0,  0,  0,-10,
        -10,  0,  5,  5,  5,  5,  0,-10,
         -5,  0,  5,  5,  5,  5,  0, -5,
          0,  0,  5,  5,  5,  5,  0, -5,
        -10,  5,  5,  5,  5,  5,  0,-10,
        -10,  0,  5,  0,  0,  0,  0,-10,
        -20,-10,-10, -5, -5,-10,-10,-20
    };

    // Queen PST — endgame
    private static final int[] PST_Q_EG = {
        -20,-10,-10, -5, -5,-10,-10,-20,
        -10,  0,  0,  0,  0,  0,  0,-10,
        -10,  0,  5,  5,  5,  5,  0,-10,
         -5,  0,  5, 10, 10,  5,  0, -5,
         -5,  0,  5, 10, 10,  5,  0, -5,
        -10,  0,  5,  5,  5,  5,  0,-10,
        -10,  0,  0,  0,  0,  0,  0,-10,
        -20,-10,-10, -5, -5,-10,-10,-20
    };

    // King PST — midgame (safety: stay castled)
    private static final int[] PST_K_MG = {
        -30,-40,-40,-50,-50,-40,-40,-30,
        -30,-40,-40,-50,-50,-40,-40,-30,
        -30,-40,-40,-50,-50,-40,-40,-30,
        -30,-40,-40,-50,-50,-40,-40,-30,
        -20,-30,-30,-40,-40,-30,-30,-20,
        -10,-20,-20,-20,-20,-20,-20,-10,
         20, 20,  0,  0,  0,  0, 20, 20,
         20, 30, 10,  0,  0, 10, 30, 20
    };

    // King PST — endgame (activity: centralize)
    private static final int[] PST_K_EG = {
        -50,-40,-30,-20,-20,-30,-40,-50,
        -30,-20,-10,  0,  0,-10,-20,-30,
        -30,-10, 20, 30, 30, 20,-10,-30,
        -30,-10, 30, 40, 40, 30,-10,-30,
        -30,-10, 30, 40, 40, 30,-10,-30,
        -30,-10, 20, 30, 30, 20,-10,-30,
        -30,-30,  0,  0,  0,  0,-30,-30,
        -50,-30,-30,-30,-30,-30,-30,-50
    };

    // Base piece values for midgame and endgame
    private static final int[] BASE_MG = {0, 82, 337, 365, 477, 1025, 0};  // _, P, N, B, R, Q, K
    private static final int[] BASE_EG = {0, 94, 281, 297, 512, 936, 0};

    // PST lookup tables: [pieceType][square] — white perspective, flip for black
    private static final int[][] PST_MG;
    private static final int[][] PST_EG;

    static {
        PST_MG = new int[7][64];
        PST_EG = new int[7][64];
        // Pawn=1, Knight=2, Bishop=3, Rook=4, Queen=5, King=6
        for (int sq = 0; sq < 64; sq++) {
            PST_MG[1][sq] = BASE_MG[1] + PST_P_MG[sq];
            PST_MG[2][sq] = BASE_MG[2] + PST_N_MG[sq];
            PST_MG[3][sq] = BASE_MG[3] + PST_B_MG[sq];
            PST_MG[4][sq] = BASE_MG[4] + PST_R_MG[sq];
            PST_MG[5][sq] = BASE_MG[5] + PST_Q_MG[sq];
            PST_MG[6][sq] = BASE_MG[6] + PST_K_MG[sq];

            PST_EG[1][sq] = BASE_EG[1] + PST_P_EG[sq];
            PST_EG[2][sq] = BASE_EG[2] + PST_N_EG[sq];
            PST_EG[3][sq] = BASE_EG[3] + PST_B_EG[sq];
            PST_EG[4][sq] = BASE_EG[4] + PST_R_EG[sq];
            PST_EG[5][sq] = BASE_EG[5] + PST_Q_EG[sq];
            PST_EG[6][sq] = BASE_EG[6] + PST_K_EG[sq];
        }
    }

    private static int pieceType(char piece) {
        return switch (Character.toLowerCase(piece)) {
            case 'p' -> 1;
            case 'n' -> 2;
            case 'b' -> 3;
            case 'r' -> 4;
            case 'q' -> 5;
            case 'k' -> 6;
            default -> 0;
        };
    }

    /**
     * Game phase for tapered evaluation. 24 = opening, 0 = endgame.
     * Knight/Bishop = 1, Rook = 2, Queen = 4.
     */
    private static int gamePhase(ChessCore.ChessState state) {
        int phase = 0;
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                char p = Character.toLowerCase(state.board[y][x]);
                phase += switch (p) {
                    case 'n', 'b' -> 1;
                    case 'r' -> 2;
                    case 'q' -> 4;
                    default -> 0;
                };
            }
        }
        return Math.min(phase, 24);
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
                if (t == 'n' || t == 'b') {
                    boolean white = Character.isUpperCase(p);
                    int color = (x + y) & 1;
                    if (white) {
                        whiteMinor++;
                        if (t == 'b') {
                            if (whiteBishopColor < 0) whiteBishopColor = color;
                            else if (whiteBishopColor != color) whiteBishopColor = -2;
                        }
                    } else {
                        blackMinor++;
                        if (t == 'b') {
                            if (blackBishopColor < 0) blackBishopColor = color;
                            else if (blackBishopColor != color) blackBishopColor = -2;
                        }
                    }
                } else {
                    return false; // rook or queen present
                }
            }
        }
        if (whiteMinor == 0 && blackMinor == 0) return true;
        if (whiteMinor == 0 && blackMinor == 1) return true;
        if (blackMinor == 0 && whiteMinor == 1) return true;
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
        int phase = gamePhase(state);

        int mgScore = 0;
        int egScore = 0;

        int[] whitePawns = new int[8];
        int[] blackPawns = new int[8];
        int whiteBishops = 0;
        int blackBishops = 0;
        int whiteKingX = -1;
        int whiteKingY = -1;
        int blackKingX = -1;
        int blackKingY = -1;

        // Piece material + PST
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                char p = board[y][x];
                if (p == '.') continue;
                boolean white = Character.isUpperCase(p);
                char t = Character.toLowerCase(p);

                int pt = pieceType(p);
                int sq = white ? (y * 8 + x) : ((7 - y) * 8 + x);

                if (white) {
                    mgScore += PST_MG[pt][sq];
                    egScore += PST_EG[pt][sq];
                } else {
                    mgScore -= PST_MG[pt][sq];
                    egScore -= PST_EG[pt][sq];
                }

                if (t == 'p') {
                    if (white) whitePawns[x]++;
                    else blackPawns[x]++;
                } else if (t == 'b') {
                    if (white) whiteBishops++;
                    else blackBishops++;
                } else if (t == 'k') {
                    if (white) { whiteKingX = x; whiteKingY = y; }
                    else { blackKingX = x; blackKingY = y; }
                }
            }
        }

        // Bishop pair bonus
        if (whiteBishops >= 2) { mgScore += 30; egScore += 50; }
        if (blackBishops >= 2) { mgScore -= 30; egScore -= 50; }

        // Pawn structure
        mgScore += pawnStructure(board, whitePawns, true, false);
        egScore += pawnStructure(board, whitePawns, true, true);
        mgScore -= pawnStructure(board, blackPawns, false, false);
        egScore -= pawnStructure(board, blackPawns, false, true);

        // Rook bonuses
        mgScore += rookBonus(board, true, false);
        egScore += rookBonus(board, true, true);
        mgScore -= rookBonus(board, false, false);
        egScore -= rookBonus(board, false, true);

        // King safety (mainly midgame)
        mgScore += kingSafety(board, whiteKingX, whiteKingY, true);
        mgScore -= kingSafety(board, blackKingX, blackKingY, false);

        // King centralization in endgame
        egScore += kingCentralization(whiteKingX, whiteKingY, true);
        egScore -= kingCentralization(blackKingX, blackKingY, false);

        // Passed pawn + king proximity in endgame
        egScore += passedPawnEndgame(board, whitePawns, whiteKingY, whiteKingX, true);
        egScore -= passedPawnEndgame(board, blackPawns, blackKingY, blackKingX, false);

        // Mobility
        if (mobilityWeight > 0) {
            int mobWhite = mobilityEstimate(board, true);
            int mobBlack = mobilityEstimate(board, false);
            mgScore += mobWhite * mobilityWeight;
            egScore += mobWhite * mobilityWeight;
            mgScore -= mobBlack * mobilityWeight;
            egScore -= mobBlack * mobilityWeight;
        }

        // Threats — pieces hanging
        mgScore += threats(board, true);
        egScore += threats(board, true);
        mgScore -= threats(board, false);
        egScore -= threats(board, false);

        // Tapered evaluation
        int mgPhase = Math.min(phase, 24);
        int egPhase = 24 - mgPhase;
        return (mgScore * mgPhase + egScore * egPhase) / 24;
    }

    private static int threats(char[][] board, boolean white) {
        int score = 0;
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                char p = board[y][x];
                if (p == '.' || Character.isUpperCase(p) != white) continue;
                char t = Character.toLowerCase(p);
                if (t == 'p' || t == 'k') continue;

                // Count attacks on enemy squares near this piece
                int attacks = 0;
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        int ny = y + dy, nx = x + dx;
                        if (ny < 0 || ny > 7 || nx < 0 || nx > 7) continue;
                        char target = board[ny][nx];
                        if (target != '.' && Character.isUpperCase(target) != white) {
                            attacks++;
                        }
                    }
                }
                // Knight attacks
                if (t == 'n') {
                    int[][] knightMoves = {{-2,-1},{-2,1},{-1,-2},{-1,2},{1,-2},{1,2},{2,-1},{2,1}};
                    for (int[] km : knightMoves) {
                        int ny = y + km[0], nx = x + km[1];
                        if (ny >= 0 && ny <= 7 && nx >= 0 && nx <= 7) {
                            char target = board[ny][nx];
                            if (target != '.' && Character.isUpperCase(target) != white) {
                                attacks++;
                            }
                        }
                    }
                }
                score += attacks * 3;
            }
        }
        return score;
    }

    private static int pawnStructure(char[][] board, int[] pawnsByFile, boolean white, boolean endgame) {
        int score = 0;
        for (int file = 0; file < 8; file++) {
            int count = pawnsByFile[file];
            if (count <= 0) continue;
            // Doubled pawns penalty
            if (count > 1) score -= (count - 1) * (endgame ? 20 : 14);
            // Isolated pawns penalty
            boolean left = file > 0 && pawnsByFile[file - 1] > 0;
            boolean right = file < 7 && pawnsByFile[file + 1] > 0;
            if (!left && !right) score -= (endgame ? 15 : 10);
            // Connected pawns bonus (adjacent pawns support each other)
            if (left || right) score += (endgame ? 5 : 3);
        }

        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                char p = board[y][x];
                if (white && p != 'P') continue;
                if (!white && p != 'p') continue;
                if (isPassedPawn(board, x, y, white)) {
                    int advance = white ? (6 - y) : (y - 1);
                    int bonus = endgame ? (20 + Math.max(0, advance) * 14) : (10 + Math.max(0, advance) * 6);
                    score += bonus;
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

    private static int passedPawnEndgame(char[][] board, int[] pawnsByFile, int kingY, int kingX, boolean white) {
        int score = 0;
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                char p = board[y][x];
                if (white && p != 'P') continue;
                if (!white && p != 'p') continue;
                if (!isPassedPawn(board, x, y, white)) continue;

                int advance = white ? (6 - y) : (y - 1);
                if (advance <= 0) continue;

                // King proximity to passed pawn promotion square
                int promoY = white ? 0 : 7;
                int distFriendly = Math.max(Math.abs(kingY - y), Math.abs(kingX - x));
                score += (7 - distFriendly) * 2;
            }
        }
        return score;
    }

    private static int kingCentralization(int kx, int ky, boolean white) {
        // In endgame, king should be centralized
        int cx = Math.min(kx, 7 - kx);
        int cy = white ? Math.min(ky, 7 - ky) : Math.min(ky, 7 - ky);
        return (3 - cx) * 4 + (3 - cy) * 4;
    }

    private static int rookBonus(char[][] board, boolean white, boolean endgame) {
        int score = 0;
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                char p = board[y][x];
                if (white && p != 'R') continue;
                if (!white && p != 'r') continue;

                // Open file bonus
                boolean ownPawn = false;
                boolean enemyPawn = false;
                for (int ry = 0; ry < 8; ry++) {
                    char rp = board[ry][x];
                    if (rp == (white ? 'P' : 'p')) ownPawn = true;
                    if (rp == (white ? 'p' : 'P')) enemyPawn = true;
                }
                if (!ownPawn && !enemyPawn) score += (endgame ? 20 : 15); // open file
                else if (!ownPawn) score += (endgame ? 12 : 10); // semi-open

                // Rook on 7th rank bonus (attacking pawns)
                if (white && y == 1) score += (endgame ? 25 : 15);
                if (!white && y == 6) score += (endgame ? 25 : 15);

                // Connected rooks
                for (int rx = x + 1; rx < 8; rx++) {
                    char rp = board[y][rx];
                    if (rp == (white ? 'R' : 'r')) { score += 8; break; }
                    if (rp != '.') break;
                }
            }
        }
        return score;
    }

    private static int kingSafety(char[][] board, int kingX, int kingY, boolean white) {
        if (kingX < 0) return 0;
        int score = 0;

        // Pawn shield
        int shieldRank = white ? kingY - 1 : kingY + 1;
        if (shieldRank >= 0 && shieldRank <= 7) {
            for (int sx = Math.max(0, kingX - 1); sx <= Math.min(7, kingX + 1); sx++) {
                char sp = board[shieldRank][sx];
                if (sp == (white ? 'P' : 'p')) {
                    score += 12;
                } else {
                    score -= 8;
                }
            }
        }

        // Penalty for open files near king
        for (int fx = Math.max(0, kingX - 1); fx <= Math.min(7, kingX + 1); fx++) {
            boolean hasPawn = false;
            for (int fy = 0; fy < 8; fy++) {
                if (board[fy][fx] == (white ? 'P' : 'p')) { hasPawn = true; break; }
            }
            if (!hasPawn) score -= 20;
        }

        // Attacker count near king zone
        int attackScore = 0;
        for (int dy = -2; dy <= 2; dy++) {
            for (int dx = -2; dx <= 2; dx++) {
                int ny = kingY + dy, nx = kingX + dx;
                if (ny < 0 || ny > 7 || nx < 0 || nx > 7) continue;
                char ap = board[ny][nx];
                if (ap == '.' || Character.isUpperCase(ap) == white) continue;
                char at = Character.toLowerCase(ap);
                attackScore += switch (at) {
                    case 'q' -> 5;
                    case 'r' -> 3;
                    case 'n', 'b' -> 2;
                    case 'p' -> 1;
                    default -> 0;
                };
            }
        }
        score -= attackScore * 4;

        return score;
    }

    private static int mobilityEstimate(char[][] board, boolean white) {
        int mobility = 0;
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                char p = board[y][x];
                if (p == '.' || Character.isUpperCase(p) != white) continue;
                char t = Character.toLowerCase(p);

                switch (t) {
                    case 'n' -> {
                        int[][] knightMoves = {{-2,-1},{-2,1},{-1,-2},{-1,2},{1,-2},{1,2},{2,-1},{2,1}};
                        for (int[] km : knightMoves) {
                            int ny = y + km[0], nx = x + km[1];
                            if (ny >= 0 && ny <= 7 && nx >= 0 && nx <= 7) {
                                char target = board[ny][nx];
                                if (target == '.' || Character.isUpperCase(target) != white) mobility++;
                            }
                        }
                    }
                    case 'b' -> {
                        int[][] dirs = {{-1,-1},{-1,1},{1,-1},{1,1}};
                        for (int[] d : dirs) {
                            for (int i = 1; i < 8; i++) {
                                int ny = y + d[0] * i, nx = x + d[1] * i;
                                if (ny < 0 || ny > 7 || nx < 0 || nx > 7) break;
                                char target = board[ny][nx];
                                if (target == '.') mobility++;
                                else { if (Character.isUpperCase(target) != white) mobility++; break; }
                            }
                        }
                    }
                    case 'r' -> {
                        int[][] dirs = {{-1,0},{1,0},{0,-1},{0,1}};
                        for (int[] d : dirs) {
                            for (int i = 1; i < 8; i++) {
                                int ny = y + d[0] * i, nx = x + d[1] * i;
                                if (ny < 0 || ny > 7 || nx < 0 || nx > 7) break;
                                char target = board[ny][nx];
                                if (target == '.') mobility++;
                                else { if (Character.isUpperCase(target) != white) mobility++; break; }
                            }
                        }
                    }
                    case 'q' -> {
                        int[][] dirs = {{-1,-1},{-1,0},{-1,1},{0,-1},{0,1},{1,-1},{1,0},{1,1}};
                        for (int[] d : dirs) {
                            for (int i = 1; i < 8; i++) {
                                int ny = y + d[0] * i, nx = x + d[1] * i;
                                if (ny < 0 || ny > 7 || nx < 0 || nx > 7) break;
                                char target = board[ny][nx];
                                if (target == '.') mobility += 1;
                                else { if (Character.isUpperCase(target) != white) mobility += 1; break; }
                            }
                        }
                    }
                    case 'p' -> {
                        int step = white ? -1 : 1;
                        int ny = y + step;
                        if (ny >= 0 && ny <= 7 && board[ny][x] == '.') mobility++;
                    }
                    default -> {}
                }
            }
        }
        return mobility;
    }
}
