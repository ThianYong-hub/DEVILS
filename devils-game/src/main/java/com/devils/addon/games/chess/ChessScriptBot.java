package com.devils.addon.games.chess;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

final class ChessScriptBot {
    private static final int MATE_SCORE = 2_000_000;
    private static final int FLAG_EXACT = 0;
    private static final int FLAG_LOWER = 1;
    private static final int FLAG_UPPER = 2;

    private ChessScriptBot() {
    }

    static String chooseMove(String fen, Random random, int rawLevel) {
        int level = clamp(rawLevel, 1, 7);

        Profile profile = Profile.forLevel(level);

        ChessCore.ChessState root = ChessCore.parseFenOrInitial(fen, ChessLogic.INITIAL_FEN);
        List<ChessLogic.Move> legal = ChessCore.generateLegalMoves(root);
        if (legal.isEmpty()) return "";
        if (legal.size() == 1) return legal.get(0).toUci();

        SearchContext ctx = new SearchContext(profile);
        Map<Integer, Integer> previousRootScores = new HashMap<>();
        RootSearchResult lastCompleted = null;
        int guess = 0;
        int pvKey = 0;

        for (int depth = 1; depth <= profile.maxDepth; depth++) {
            RootSearchResult current;
            if (depth >= 5 && profile.useTable) {
                current = aspirationSearch(root, legal, depth, guess, pvKey, previousRootScores, profile, ctx);
            } else {
                current = searchRoot(root, legal, depth, guess, pvKey, previousRootScores, profile, ctx);
            }
            if (current == null || current.ranked.isEmpty()) break;
            lastCompleted = current;
            guess = current.bestScore;
            pvKey = moveKey(current.bestMove);
            previousRootScores = current.rootScores;
            if (ctx.stop()) break;
        }

        if (lastCompleted == null || lastCompleted.ranked.isEmpty()) {
            return legal.get(random.nextInt(legal.size())).toUci();
        }
        int pick = pickIndex(lastCompleted.ranked.size(), profile, random);
        return lastCompleted.ranked.get(pick).move.toUci();
    }

    private static RootSearchResult aspirationSearch(
        ChessCore.ChessState root,
        List<ChessLogic.Move> legal,
        int depth,
        int guess,
        int pv,
        Map<Integer, Integer> prevScores,
        Profile profile,
        SearchContext ctx
    ) {
        int delta = 45;
        int alpha = guess - delta;
        int beta = guess + delta;

        RootSearchResult result = null;
        for (int attempt = 0; attempt < 5; attempt++) {
            result = searchRoot(root, legal, depth, alpha, beta, pv, prevScores, profile, ctx);
            if (result == null || result.ranked.isEmpty()) return result;

            int score = result.bestScore;
            if (score <= alpha) {
                alpha = Math.max(score - delta, -MATE_SCORE);
                beta = (alpha + beta) / 2;
            } else if (score >= beta) {
                beta = Math.min(score + delta, MATE_SCORE);
                alpha = (alpha + beta) / 2;
            } else {
                return result;
            }
            delta += delta + delta / 2;
        }
        return result;
    }

    private static RootSearchResult searchRoot(
        ChessCore.ChessState root,
        List<ChessLogic.Move> legal,
        int depth,
        int alpha,
        int beta,
        int pv,
        Map<Integer, Integer> prevScores,
        Profile profile,
        SearchContext ctx
    ) {
        int pvKey = pv;
        int guess = alpha;

        List<ChessLogic.Move> ordered = orderRootMoves(legal, prevScores, pvKey);
        if (ordered.isEmpty()) return null;

        int bestScore = -MATE_SCORE;
        ChessLogic.Move bestMove = ordered.get(0);
        int currentAlpha = alpha;
        int moveIndex = 0;

        ArrayList<ScoredMove> scored = new ArrayList<>(ordered.size());
        for (ChessLogic.Move move : ordered) {
            if (ctx.stop()) break;
            moveIndex++;
            ChessCore.ChessState next = root.copy();
            ChessCore.applyUnchecked(next, move);

            int score;
            if (moveIndex == 1) {
                score = -search(next, depth - 1, -beta, -currentAlpha, 1, profile, ctx);
            } else {
                score = -search(next, depth - 1, -currentAlpha - 1, -currentAlpha, 1, profile, ctx);
                if (score > currentAlpha && score < beta) {
                    score = -search(next, depth - 1, -beta, -currentAlpha, 1, profile, ctx);
                }
            }

            scored.add(new ScoredMove(move, score, score));
            if (score > bestScore) {
                bestScore = score;
                bestMove = move;
            }
            if (score > currentAlpha) currentAlpha = score;
            if (currentAlpha >= beta) break;
        }

        if (scored.isEmpty()) return null;
        scored.sort((a, b) -> Integer.compare(b.raw, a.raw));
        HashMap<Integer, Integer> map = new HashMap<>(scored.size() * 2);
        for (ScoredMove sm : scored) map.put(moveKey(sm.move), sm.raw);
        return new RootSearchResult(bestMove, bestScore, scored, map);
    }

    private static RootSearchResult searchRoot(
        ChessCore.ChessState root,
        List<ChessLogic.Move> legal,
        int depth,
        int guess,
        int pv,
        Map<Integer, Integer> prevScores,
        Profile profile,
        SearchContext ctx
    ) {
        return searchRoot(root, legal, depth, -MATE_SCORE, MATE_SCORE, pv, prevScores, profile, ctx);
    }

    private static int search(
        ChessCore.ChessState state,
        int depth,
        int alpha,
        int beta,
        int ply,
        Profile profile,
        SearchContext ctx
    ) {
        long hash = ChessScriptHeuristics.stateHash(state);
        ctx.push(hash, ply);
        if (ctx.isRepetition(hash, ply, state.halfmoveClock)) return 0;
        if (ctx.hitNode(profile)) return ChessScriptHeuristics.evaluateForSideToMove(state, profile.mobilityWeight);
        if (state.halfmoveClock >= 100 || ChessScriptHeuristics.insufficientMaterial(state)) return 0;

        boolean inCheck = ChessCore.isInCheck(state, state.whiteToMove);
        int searchDepth = depth + (profile.checkExtension && inCheck ? 1 : 0);
        if (searchDepth <= 0) return quiescence(state, alpha, beta, profile.qDepth, profile, ctx, inCheck);

        TTEntry cached = profile.useTable ? ctx.table.get(hash) : null;
        int ttMove = cached == null ? 0 : cached.bestMoveKey;
        if (cached != null && cached.depth >= searchDepth) {
            if (cached.flag == FLAG_EXACT) return cached.score;
            if (cached.flag == FLAG_LOWER) alpha = Math.max(alpha, cached.score);
            else if (cached.flag == FLAG_UPPER) beta = Math.min(beta, cached.score);
            if (alpha >= beta) return cached.score;
        }

        boolean pvNode = beta - alpha > 1;

        // Null move pruning
        if (profile.nullMove && !inCheck && !pvNode && depth >= 3) {
            int nonPawn = countNonPawnPieces(state);
            if (nonPawn > 1) {
                int R = 3 + depth / 6;
                ChessCore.ChessState next = state.copy();
                next.whiteToMove = !next.whiteToMove;
                next.epX = -1;
                next.epY = -1;
                int score = -search(next, depth - R - 1, -beta, -beta + 1, ply + 1, profile, ctx);
                if (score >= beta) return beta;
            }
        }

        // Razoring — at low depth, if static eval is far below alpha, go to quiescence
        if (profile.futilityPruning && !inCheck && !pvNode && depth <= 3) {
            int staticEval = ChessScriptHeuristics.evaluateForSideToMove(state, profile.mobilityWeight);
            int margin = 120 * depth;
            if (staticEval + margin <= alpha) {
                int qScore = quiescence(state, alpha, beta, 0, profile, ctx, inCheck);
                if (qScore <= alpha) return qScore;
            }
        }

        List<ChessLogic.Move> moves = ChessCore.generateLegalMoves(state);
        if (moves.isEmpty()) return inCheck ? -MATE_SCORE + ply : 0;

        List<ChessLogic.Move> ordered = orderMoves(state, moves, ply, ttMove, ctx);
        int best = -MATE_SCORE - 1;
        ChessLogic.Move bestMove = ordered.get(0);
        int originalAlpha = alpha;
        int moveIndex = 0;
        boolean improving = !inCheck && depth >= 2 && ply >= 2 &&
                ChessScriptHeuristics.evaluateForSideToMove(state, profile.mobilityWeight) >=
                ctx.evalStack[ply - 2];
        ctx.evalStack[ply] = ChessScriptHeuristics.evaluateForSideToMove(state, profile.mobilityWeight);

        for (ChessLogic.Move move : ordered) {
            if (ctx.hitNode(profile)) break;
            moveIndex++;
            boolean tactical = ChessScriptHeuristics.isTactical(state, move);
            char piece = state.board[move.fromY()][move.fromX()];
            boolean givesCheck = moveGivesCheck(state, move);

            // Futility pruning
            if (profile.futilityPruning && !inCheck && !tactical && !givesCheck && !pvNode && depth <= 6 && moveIndex > 1) {
                int staticEval = ctx.evalStack[ply];
                int fMargin = 50 + 70 * depth;
                if (!improving) fMargin += 30;
                if (staticEval + fMargin <= alpha) {
                    continue;
                }
            }

            // Late move pruning
            if (!inCheck && !tactical && !pvNode && depth <= 4 && moveIndex > 3 + depth * depth) {
                continue;
            }

            // SEE pruning at low depth
            if (!inCheck && depth <= 2 && !tactical && moveIndex > 4) {
                continue;
            }

            ChessCore.ChessState next = state.copy();
            ChessCore.applyUnchecked(next, move);

            // Late Move Reductions
            int reduction = 0;
            if (depth >= 3 && moveIndex > 1 && !tactical) {
                reduction = (int) (0.77 + Math.log(depth) * Math.log(moveIndex) * 0.52);
                if (!improving) reduction++;
                if (inCheck) reduction--;
                if (reduction < 0) reduction = 0;
                if (searchDepth >= 6 && moveIndex > 8) reduction++;
            }

            int score;
            if (moveIndex == 1) {
                score = -search(next, depth - 1, -beta, -alpha, ply + 1, profile, ctx);
            } else {
                score = -search(next, depth - 1 - reduction, -alpha - 1, -alpha, ply + 1, profile, ctx);
                if (reduction > 0 && score > alpha) {
                    score = -search(next, depth - 1, -alpha - 1, -alpha, ply + 1, profile, ctx);
                }
                if (score > alpha && score < beta) {
                    score = -search(next, depth - 1, -beta, -alpha, ply + 1, profile, ctx);
                }
            }

            if (score > best) {
                best = score;
                bestMove = move;
            }
            if (score > alpha) alpha = score;

            if (alpha >= beta) {
                if (!tactical) ctx.recordCutoff(state.whiteToMove, ply, move, depth);
                break;
            }
        }

        if (profile.useTable) {
            int flag = FLAG_EXACT;
            if (best <= originalAlpha) flag = FLAG_UPPER;
            else if (best >= beta) flag = FLAG_LOWER;
            ctx.table.put(hash, new TTEntry(searchDepth, best, flag, moveKey(bestMove)));
        }
        return best;
    }

    private static int quiescence(
        ChessCore.ChessState state,
        int alpha,
        int beta,
        int qDepth,
        Profile profile,
        SearchContext ctx,
        boolean inCheck
    ) {
        if (ctx.hitNode(profile)) return ChessScriptHeuristics.evaluateForSideToMove(state, profile.mobilityWeight);

        if (qDepth <= 0 && !inCheck) {
            int standPat = ChessScriptHeuristics.evaluateForSideToMove(state, profile.mobilityWeight);
            if (standPat >= beta) return standPat;
            alpha = Math.max(alpha, standPat);
        }

        List<ChessLogic.Move> moves = ChessCore.generateLegalMoves(state);
        if (moves.isEmpty()) return inCheck ? -MATE_SCORE + 1 : 0;

        List<ChessLogic.Move> ordered;
        if (inCheck) {
            ordered = orderMoves(state, moves, 0, 0, ctx);
        } else {
            ordered = new ArrayList<>();
            for (ChessLogic.Move m : moves) {
                if (ChessScriptHeuristics.isTactical(state, m)) {
                    ordered.add(m);
                }
            }
            ordered.sort((a, b) -> {
                int sa = qCaptureScore(state, a);
                int sb = qCaptureScore(state, b);
                return Integer.compare(sb, sa);
            });
        }

        for (ChessLogic.Move move : ordered) {
            if (ctx.hitNode(profile)) break;
            if (!inCheck) {
                // Delta pruning
                int delta = ChessScriptHeuristics.pieceValue(state.board[move.toY()][move.toX()]) + 220;
                if (move.promotion() != 0) delta += 780;
                int stand = ChessScriptHeuristics.evaluateForSideToMove(state, profile.mobilityWeight);
                if (stand + delta <= alpha) continue;

                // SEE-based pruning for losing captures
                if (qDepth <= 0) {
                    int seeVal = estimateSEE(state, move);
                    if (seeVal < 0) continue;
                }
            }

            ChessCore.ChessState next = state.copy();
            ChessCore.applyUnchecked(next, move);
            int score = -quiescence(next, -beta, -alpha, qDepth - 1, profile, ctx, false);
            if (score >= beta) return score;
            alpha = Math.max(alpha, score);
        }
        return alpha;
    }

    private static int estimateSEE(ChessCore.ChessState state, ChessLogic.Move move) {
        char attacker = state.board[move.fromY()][move.fromX()];
        char victim = state.board[move.toY()][move.toX()];
        if (victim == '.') return 0;
        return ChessScriptHeuristics.pieceValue(victim) - ChessScriptHeuristics.pieceValue(attacker) / 2;
    }

    private static int qCaptureScore(ChessCore.ChessState state, ChessLogic.Move move) {
        char target = state.board[move.toY()][move.toX()];
        char piece = state.board[move.fromY()][move.fromX()];
        int score = 0;
        if (target != '.') score += 10 * ChessScriptHeuristics.pieceValue(target) - ChessScriptHeuristics.pieceValue(piece);
        if (move.promotion() != 0) score += ChessScriptHeuristics.pieceValue(move.promotion());
        return score;
    }

    private static int countNonPawnPieces(ChessCore.ChessState state) {
        int count = 0;
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                char p = state.board[y][x];
                char t = Character.toLowerCase(p);
                if (t != '.' && t != 'p' && t != 'k') count++;
            }
        }
        return count;
    }

    private static boolean moveGivesCheck(ChessCore.ChessState state, ChessLogic.Move move) {
        ChessCore.ChessState next = state.copy();
        ChessCore.applyUnchecked(next, move);
        return ChessCore.isInCheck(next, next.whiteToMove);
    }

    private static List<ChessLogic.Move> orderMoves(
        ChessCore.ChessState state,
        List<ChessLogic.Move> moves,
        int ply,
        int ttMoveKey,
        SearchContext ctx
    ) {
        List<ChessLogic.Move> ordered = new ArrayList<>(moves);
        ordered.sort((a, b) -> {
            int sa = moveOrderScore(state, a, ply, ttMoveKey, ctx);
            int sb = moveOrderScore(state, b, ply, ttMoveKey, ctx);
            return Integer.compare(sb, sa);
        });
        return ordered;
    }

    private static List<ChessLogic.Move> orderRootMoves(
        List<ChessLogic.Move> moves,
        Map<Integer, Integer> prevScores,
        int pvKey
    ) {
        List<ChessLogic.Move> ordered = new ArrayList<>(moves);
        ordered.sort((a, b) -> {
            int ka = moveKey(a);
            int kb = moveKey(b);
            int sa = 0;
            int sb = 0;
            if (ka == pvKey) sa += 10_000_000;
            if (kb == pvKey) sb += 10_000_000;
            sa += prevScores.getOrDefault(ka, 0);
            sb += prevScores.getOrDefault(kb, 0);
            return Integer.compare(sb, sa);
        });
        return ordered;
    }

    private static int moveOrderScore(ChessCore.ChessState state, ChessLogic.Move move, int ply, int ttMoveKey, SearchContext ctx) {
        int key = moveKey(move);
        if (key == ttMoveKey) return 2_000_000;

        int score = 0;
        char piece = state.board[move.fromY()][move.fromX()];
        char target = state.board[move.toY()][move.toX()];

        if (target != '.') score += 20_000 + ChessScriptHeuristics.pieceValue(target) * 16 - ChessScriptHeuristics.pieceValue(piece);
        if (move.promotion() != 0) score += 16_000 + ChessScriptHeuristics.pieceValue(move.promotion()) * 8;
        if (Character.toLowerCase(piece) == 'k' && Math.abs(move.toX() - move.fromX()) == 2) score += 420;
        if (Character.toLowerCase(piece) == 'p' && move.fromX() != move.toX() && target == '.') score += 18_000;

        if (ply < ctx.killers.length) {
            if (key == ctx.killers[ply][0]) score += 1_600;
            else if (key == ctx.killers[ply][1]) score += 900;
        }

        // Counter-move heuristic
        int cmKey = ctx.counterMoveKey;
        if (cmKey != 0 && key == ctx.counterMoves.getOrDefault(cmKey, 0)) {
            score += 1_200;
        }

        int from = move.fromY() * 8 + move.fromX();
        int to = move.toY() * 8 + move.toX();
        score += ctx.history[state.whiteToMove ? 0 : 1][from][to];
        return score;
    }

    private static int moveKey(ChessLogic.Move move) {
        int promo = switch (Character.toLowerCase(move.promotion())) {
            case 'q' -> 1;
            case 'r' -> 2;
            case 'b' -> 3;
            case 'n' -> 4;
            default -> 0;
        };
        int from = move.fromY() * 8 + move.fromX();
        int to = move.toY() * 8 + move.toX();
        return (from << 10) | (to << 4) | promo;
    }

    private static int pickIndex(int size, Profile profile, Random random) {
        if (size <= 1) return 0;
        int window = Math.min(size, Math.max(1, profile.topChoices));
        if (window <= 1) return 0;
        if (profile.blunderChance > 0 && random.nextDouble() < profile.blunderChance) {
            int start = Math.min(window - 1, Math.max(1, window / 2));
            return start + random.nextInt(window - start);
        }
        if (profile.exploreChance > 0 && random.nextDouble() < profile.exploreChance) {
            return random.nextInt(window);
        }
        return 0;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private record ScoredMove(ChessLogic.Move move, int raw, int noisy) {
    }

    private record TTEntry(int depth, int score, int flag, int bestMoveKey) {
    }

    private record RootSearchResult(ChessLogic.Move bestMove, int bestScore, List<ScoredMove> ranked, Map<Integer, Integer> rootScores) {
    }

    private static final class SearchContext {
        private final long deadlineNs;
        private final int[][] killers = new int[256][2];
        private final int[][][] history = new int[2][64][64];
        private final Map<Long, TTEntry> table;
        private final int[] evalStack = new int[256];
        private final Map<Integer, Integer> counterMoves = new HashMap<>();
        private final long[] pathHashes = new long[256];
        private int counterMoveKey;
        private long nodes;
        private boolean outOfBudget;

        private SearchContext(Profile profile) {
            this.deadlineNs = System.nanoTime() + profile.maxMillis * 1_000_000L;
            this.table = profile.useTable ? new HashMap<>(65_536) : new HashMap<>(0);
        }

        private boolean hitNode(Profile profile) {
            nodes++;
            if (nodes >= profile.maxNodes) {
                outOfBudget = true;
                return true;
            }
            return stop();
        }

        private boolean stop() {
            if (outOfBudget) return true;
            if ((nodes & 1023L) == 0L && System.nanoTime() > deadlineNs) outOfBudget = true;
            return outOfBudget;
        }

        private void push(long hash, int ply) {
            pathHashes[ply] = hash;
        }

        private boolean isRepetition(long hash, int ply, int halfmoveClock) {
            if (halfmoveClock == 0) return false;
            int minPly = Math.max(0, ply - halfmoveClock);
            for (int i = ply - 2; i >= minPly; i -= 2) {
                if (pathHashes[i] == hash) return true;
            }
            return false;
        }

        private void recordCutoff(boolean whiteToMove, int ply, ChessLogic.Move move, int depth) {
            int key = moveKey(move);
            int side = whiteToMove ? 0 : 1;
            if (ply < killers.length) {
                if (key != killers[ply][0]) {
                    killers[ply][1] = killers[ply][0];
                    killers[ply][0] = key;
                }
            }
            int from = move.fromY() * 8 + move.fromX();
            int to = move.toY() * 8 + move.toX();
            history[side][from][to] += depth * depth;
            counterMoveKey = key;
        }
    }

    private static final class Profile {
        final int maxDepth;
        final int maxMillis;
        final long maxNodes;
        final int mobilityWeight;
        final boolean nullMove;
        final boolean checkExtension;
        final boolean futilityPruning;
        final boolean useTable;
        final int qDepth;
        final int topChoices;
        final double blunderChance;
        final double exploreChance;

        Profile(int maxDepth, int maxMillis, long maxNodes, int mobilityWeight,
                boolean nullMove, boolean checkExtension, boolean futilityPruning,
                boolean useTable, int qDepth, int topChoices,
                double blunderChance, double exploreChance) {
            this.maxDepth = maxDepth;
            this.maxMillis = maxMillis;
            this.maxNodes = maxNodes;
            this.mobilityWeight = mobilityWeight;
            this.nullMove = nullMove;
            this.checkExtension = checkExtension;
            this.futilityPruning = futilityPruning;
            this.useTable = useTable;
            this.qDepth = qDepth;
            this.topChoices = topChoices;
            this.blunderChance = blunderChance;
            this.exploreChance = exploreChance;
        }

        static Profile forLevel(int level) {
            return switch (level) {
                case 1 -> new Profile(1, 50, 2_000, 0,
                        false, false, false, false, 0,
                        6, 0.30, 0.20);
                case 2 -> new Profile(2, 100, 5_000, 1,
                        false, false, false, false, 0,
                        5, 0.15, 0.12);
                case 3 -> new Profile(3, 150, 25_000, 2,
                        false, false, false, false, 1,
                        4, 0.08, 0.06);
                case 4 -> new Profile(5, 250, 100_000, 2,
                        true, false, false, true, 2,
                        3, 0.04, 0.04);
                case 5 -> new Profile(7, 400, 300_000, 3,
                        true, true, true, true, 3,
                        2, 0.02, 0.02);
                case 6 -> new Profile(9, 700, 800_000, 3,
                        true, true, true, true, 4,
                        2, 0.01, 0.01);
                case 7 -> new Profile(11, 1500, 3_000_000, 4,
                        true, true, true, true, 5,
                        1, 0.0, 0.0);
                default -> forLevel(4);
            };
        }
    }
}
