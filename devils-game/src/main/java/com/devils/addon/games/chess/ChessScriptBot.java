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
            RootSearchResult current = searchRoot(root, legal, depth, guess, pvKey, previousRootScores, profile, ctx);
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

    private static RootSearchResult searchRoot(
        ChessCore.ChessState root,
        List<ChessLogic.Move> legal,
        int depth,
        int guess,
        int pvKey,
        Map<Integer, Integer> previousRootScores,
        Profile profile,
        SearchContext ctx
    ) {
        if (profile.useAspiration && depth >= 3) {
            int delta = 36 + depth * 10;
            int alpha = Math.max(-MATE_SCORE, guess - delta);
            int beta = Math.min(MATE_SCORE, guess + delta);
            int loops = 0;
            while (loops++ < 5 && !ctx.stop()) {
                RootSearchResult attempt = rootWindow(root, legal, depth, alpha, beta, pvKey, previousRootScores, profile, ctx);
                if (attempt == null) return null;
                if (attempt.bestScore <= alpha) {
                    alpha = Math.max(-MATE_SCORE, alpha - delta * 2);
                    delta *= 2;
                    continue;
                }
                if (attempt.bestScore >= beta) {
                    beta = Math.min(MATE_SCORE, beta + delta * 2);
                    delta *= 2;
                    continue;
                }
                return attempt;
            }
        }
        return rootWindow(root, legal, depth, -MATE_SCORE, MATE_SCORE, pvKey, previousRootScores, profile, ctx);
    }

    private static RootSearchResult rootWindow(
        ChessCore.ChessState root,
        List<ChessLogic.Move> legal,
        int depth,
        int alpha,
        int beta,
        int pvKey,
        Map<Integer, Integer> previousRootScores,
        Profile profile,
        SearchContext ctx
    ) {
        long hash = ChessScriptHeuristics.stateHash(root);
        TTEntry tt = profile.useTable ? ctx.table.get(hash) : null;
        int ttKey = tt == null ? 0 : tt.bestMoveKey;

        ArrayList<ChessLogic.Move> ordered = new ArrayList<>(legal);
        ordered.sort((a, b) -> Integer.compare(
            rootOrderScore(root, b, ttKey, pvKey, previousRootScores, ctx),
            rootOrderScore(root, a, ttKey, pvKey, previousRootScores, ctx)
        ));

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
            else beta = Math.min(beta, cached.score);
            if (alpha >= beta) return cached.score;
        }

        if (profile.useNullMove && searchDepth >= 4 && !inCheck && hasNonPawnMaterial(state, state.whiteToMove)) {
            ChessCore.ChessState nullState = state.copy();
            if (!nullState.whiteToMove) nullState.fullmoveNumber++;
            nullState.whiteToMove = !nullState.whiteToMove;
            nullState.epX = -1;
            nullState.epY = -1;
            nullState.halfmoveClock++;
            int reduction = 2 + (searchDepth / 4);
            int nullScore = -search(nullState, searchDepth - 1 - reduction, -beta, -beta + 1, ply + 1, profile, ctx);
            if (nullScore >= beta) return nullScore;
        }

        List<ChessLogic.Move> legal = ChessCore.generateLegalMoves(state);
        if (legal.isEmpty()) return inCheck ? (-MATE_SCORE + ply) : 0;

        orderMoves(state, legal, ply, ttMove, ctx);
        int originalAlpha = alpha;
        int best = -MATE_SCORE;
        ChessLogic.Move bestMove = legal.get(0);
        int moveIndex = 0;

        for (ChessLogic.Move move : legal) {
            if (ctx.stop()) break;
            moveIndex++;

            ChessCore.ChessState next = state.copy();
            ChessCore.applyUnchecked(next, move);

            boolean tactical = ChessScriptHeuristics.isTactical(state, move);
            int reduction = 0;
            if (profile.useLmr && searchDepth >= 3 && moveIndex > 3 && !inCheck && !tactical) {
                reduction = 1;
                if (searchDepth >= 6 && moveIndex > 8) reduction++;
            }

            int score;
            if (moveIndex == 1) {
                score = -search(next, searchDepth - 1, -beta, -alpha, ply + 1, profile, ctx);
            } else {
                score = -search(next, searchDepth - 1 - reduction, -alpha - 1, -alpha, ply + 1, profile, ctx);
                if (reduction > 0 && score > alpha) {
                    score = -search(next, searchDepth - 1, -alpha - 1, -alpha, ply + 1, profile, ctx);
                }
                if (score > alpha && score < beta) {
                    score = -search(next, searchDepth - 1, -beta, -alpha, ply + 1, profile, ctx);
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
        int standPat = ChessScriptHeuristics.evaluateForSideToMove(state, profile.mobilityWeight);
        if (standPat >= beta) return standPat;
        if (standPat > alpha) alpha = standPat;
        if (qDepth <= 0) return alpha;
        if (ctx.hitNode(profile)) return alpha;

        List<ChessLogic.Move> legal = ChessCore.generateLegalMoves(state);
        if (legal.isEmpty()) return inCheck ? -MATE_SCORE + 1 : 0;

        orderMoves(state, legal, 0, 0, ctx);
        for (ChessLogic.Move move : legal) {
            if (!inCheck && !ChessScriptHeuristics.isTactical(state, move)) continue;
            ChessCore.ChessState next = state.copy();
            ChessCore.applyUnchecked(next, move);
            int score = -quiescence(next, -beta, -alpha, qDepth - 1, profile, ctx, ChessCore.isInCheck(next, next.whiteToMove));
            if (score >= beta) return score;
            if (score > alpha) alpha = score;
        }
        return alpha;
    }

    private static boolean hasNonPawnMaterial(ChessCore.ChessState state, boolean white) {
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                char p = state.board[y][x];
                if (p == '.' || Character.isUpperCase(p) != white) continue;
                char t = Character.toLowerCase(p);
                if (t != 'k' && t != 'p') return true;
            }
        }
        return false;
    }

    private static void orderMoves(ChessCore.ChessState state, List<ChessLogic.Move> moves, int ply, int ttMoveKey, SearchContext ctx) {
        moves.sort((a, b) -> Integer.compare(
            moveOrderScore(state, b, ply, ttMoveKey, ctx),
            moveOrderScore(state, a, ply, ttMoveKey, ctx)
        ));
    }

    private static int rootOrderScore(
        ChessCore.ChessState state,
        ChessLogic.Move move,
        int ttMoveKey,
        int pvKey,
        Map<Integer, Integer> previousRootScores,
        SearchContext ctx
    ) {
        int key = moveKey(move);
        int score = moveOrderScore(state, move, 0, ttMoveKey, ctx);
        if (key == pvKey) score += 1_500_000;
        Integer prev = previousRootScores.get(key);
        if (prev != null) score += prev;
        return score;
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
        private final int[][] killers = new int[96][2];
        private final int[][][] history = new int[2][64][64];
        private final Map<Long, TTEntry> table;
        private final long[] pathHashes = new long[256];
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

        private void recordCutoff(boolean whiteToMove, int ply, ChessLogic.Move move, int depth) {
            int key = moveKey(move);
            if (ply < killers.length && key != killers[ply][0]) {
                killers[ply][1] = killers[ply][0];
                killers[ply][0] = key;
            }
            int from = move.fromY() * 8 + move.fromX();
            int to = move.toY() * 8 + move.toX();
            int side = whiteToMove ? 0 : 1;
            int bonus = depth * depth * 3;
            history[side][from][to] = Math.min(60_000, history[side][from][to] + bonus);
        }

        private void push(long hash, int ply) {
            if (ply >= 0 && ply < pathHashes.length) pathHashes[ply] = hash;
        }

        private boolean isRepetition(long hash, int ply, int halfmoveClock) {
            if (ply < 2) return false;
            int minPly = Math.max(0, ply - Math.max(4, halfmoveClock));
            for (int i = ply - 2; i >= minPly; i -= 2) {
                if (pathHashes[i] == hash) return true;
            }
            return false;
        }
    }

    private record Profile(
        int maxDepth,
        int maxNodes,
        int maxMillis,
        int qDepth,
        int topChoices,
        double exploreChance,
        double blunderChance,
        boolean useTable,
        boolean useLmr,
        boolean useAspiration,
        boolean checkExtension,
        boolean useNullMove,
        int mobilityWeight
    ) {
        private static Profile forLevel(int level) {
            return switch (level) {
                case 1 -> new Profile(1, 1_000, 120, 0, 10, 0.96, 0.62, false, false, false, false, false, 0);
                case 2 -> new Profile(2, 6_000, 260, 1, 8, 0.70, 0.34, false, false, false, false, false, 0);
                case 3 -> new Profile(3, 30_000, 560, 2, 5, 0.34, 0.15, false, false, true, false, false, 1);
                case 4 -> new Profile(5, 130_000, 1_100, 3, 3, 0.10, 0.05, true, true, true, true, false, 1);
                case 5 -> new Profile(7, 420_000, 2_300, 4, 2, 0.03, 0.01, true, true, true, true, true, 2);
                case 6 -> new Profile(9, 1_500_000, 4_000, 5, 1, 0.0, 0.0, true, true, true, true, true, 2);
                default -> new Profile(11, 4_500_000, 6_800, 6, 1, 0.0, 0.0, true, true, true, true, true, 3);
            };
        }
    }
}

