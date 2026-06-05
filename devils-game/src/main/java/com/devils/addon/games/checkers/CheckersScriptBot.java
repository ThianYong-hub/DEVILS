package com.devils.addon.games.checkers;

import com.devils.addon.games.checkers.CheckersLogic.Coord;
import com.devils.addon.games.checkers.CheckersLogic.Move;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

final class CheckersScriptBot {
    private static final int MATE_SCORE = 1_000_000;
    private static final int FLAG_EXACT = 0;
    private static final int FLAG_LOWER = 1;
    private static final int FLAG_UPPER = 2;

    private CheckersScriptBot() {
    }

    static String chooseMove(String state, Random random, int rawLevel) {
        int level = clamp(rawLevel, 1, 7);
        Profile profile = Profile.forLevel(level);

        List<Move> legal = CheckersLogic.legalMoves(state);
        if (legal.isEmpty()) return "";
        if (legal.size() == 1) return legal.get(0).encode();

        SearchContext ctx = new SearchContext(profile);
        Map<Integer, Integer> previousRootScores = new HashMap<>();
        RootSearchResult lastCompleted = null;
        int guess = 0;
        int pvKey = 0;

        for (int depth = 1; depth <= profile.maxDepth; depth++) {
            RootSearchResult current = searchRoot(state, legal, depth, guess, pvKey, previousRootScores, profile, ctx);
            if (current == null || current.ranked.isEmpty()) break;
            lastCompleted = current;
            guess = current.bestScore;
            pvKey = CheckersScriptHeuristics.moveKey(current.bestMove);
            previousRootScores = current.rootScores;
            if (ctx.stop()) break;
        }

        if (lastCompleted == null || lastCompleted.ranked.isEmpty()) {
            return legal.get(random.nextInt(legal.size())).encode();
        }

        int pick = pickIndex(lastCompleted.ranked.size(), profile, random);
        return lastCompleted.ranked.get(pick).move.encode();
    }

    private static RootSearchResult searchRoot(
        String state,
        List<Move> legal,
        int depth,
        int guess,
        int pvKey,
        Map<Integer, Integer> previousRootScores,
        Profile profile,
        SearchContext ctx
    ) {
        if (profile.useAspiration && depth >= 3) {
            int delta = 30 + depth * 8;
            int alpha = Math.max(-MATE_SCORE, guess - delta);
            int beta = Math.min(MATE_SCORE, guess + delta);
            int loops = 0;
            while (loops++ < 5 && !ctx.stop()) {
                RootSearchResult attempt = rootWindow(state, legal, depth, alpha, beta, pvKey, previousRootScores, profile, ctx);
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
        return rootWindow(state, legal, depth, -MATE_SCORE, MATE_SCORE, pvKey, previousRootScores, profile, ctx);
    }

    private static RootSearchResult rootWindow(
        String state,
        List<Move> legal,
        int depth,
        int alpha,
        int beta,
        int pvKey,
        Map<Integer, Integer> previousRootScores,
        Profile profile,
        SearchContext ctx
    ) {
        long hash = CheckersScriptHeuristics.stateHash(state);
        TTEntry tt = profile.useTable ? ctx.table.get(hash) : null;
        int ttKey = tt == null ? 0 : tt.bestMoveKey;

        ArrayList<Move> ordered = new ArrayList<>(legal);
        ordered.sort((a, b) -> Integer.compare(
            rootOrderScore(state, b, ttKey, pvKey, previousRootScores, ctx),
            rootOrderScore(state, a, ttKey, pvKey, previousRootScores, ctx)
        ));

        int bestScore = -MATE_SCORE;
        Move bestMove = ordered.get(0);
        int currentAlpha = alpha;
        int moveIndex = 0;

        ArrayList<ScoredMove> scored = new ArrayList<>(ordered.size());
        for (Move move : ordered) {
            if (ctx.stop()) break;
            moveIndex++;

            String next = apply(state, move);
            if (next.isBlank()) continue;

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
        for (ScoredMove sm : scored) map.put(CheckersScriptHeuristics.moveKey(sm.move), sm.raw);
        return new RootSearchResult(bestMove, bestScore, scored, map);
    }

    private static int search(
        String state,
        int depth,
        int alpha,
        int beta,
        int ply,
        Profile profile,
        SearchContext ctx
    ) {
        long hash = CheckersScriptHeuristics.stateHash(state);
        ctx.push(hash, ply);
        if (ctx.isRepetition(hash, ply)) return 0;
        if (ctx.hitNode(profile)) return CheckersScriptHeuristics.evaluateForSideToMove(state, profile.mobilityWeight);

        List<Move> legal = CheckersLogic.legalMoves(state);
        if (legal.isEmpty()) return -MATE_SCORE + ply;
        int searchDepth = depth + (profile.captureExtension && legal.get(0).capture() ? 1 : 0);
        if (searchDepth <= 0) return quiescence(state, alpha, beta, profile.qDepth, profile, ctx, legal);

        TTEntry cached = profile.useTable ? ctx.table.get(hash) : null;
        int ttMove = cached == null ? 0 : cached.bestMoveKey;
        if (cached != null && cached.depth >= searchDepth) {
            if (cached.flag == FLAG_EXACT) return cached.score;
            if (cached.flag == FLAG_LOWER) alpha = Math.max(alpha, cached.score);
            else beta = Math.min(beta, cached.score);
            if (alpha >= beta) return cached.score;
        }

        orderMoves(state, legal, ply, ttMove, ctx);
        int originalAlpha = alpha;
        int best = -MATE_SCORE;
        Move bestMove = legal.get(0);
        int moveIndex = 0;

        for (Move move : legal) {
            if (ctx.stop()) break;
            moveIndex++;

            String next = apply(state, move);
            if (next.isBlank()) continue;

            boolean tactical = move.capture();
            int reduction = 0;
            if (profile.useLmr && searchDepth >= 3 && moveIndex > 3 && !tactical) {
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
                if (!tactical) ctx.recordCutoff(CheckersLogic.isWhiteTurn(state), ply, move, depth);
                break;
            }
        }

        if (profile.useTable) {
            int flag = FLAG_EXACT;
            if (best <= originalAlpha) flag = FLAG_UPPER;
            else if (best >= beta) flag = FLAG_LOWER;
            ctx.table.put(hash, new TTEntry(searchDepth, best, flag, CheckersScriptHeuristics.moveKey(bestMove)));
        }

        return best;
    }

    private static int quiescence(
        String state,
        int alpha,
        int beta,
        int qDepth,
        Profile profile,
        SearchContext ctx,
        List<Move> legal
    ) {
        int standPat = CheckersScriptHeuristics.evaluateForSideToMove(state, profile.mobilityWeight);
        if (standPat >= beta) return standPat;
        if (standPat > alpha) alpha = standPat;
        if (qDepth <= 0) return alpha;
        if (ctx.hitNode(profile)) return alpha;

        boolean forcingCapture = !legal.isEmpty() && legal.get(0).capture();
        if (!forcingCapture) return alpha;

        orderMoves(state, legal, 0, 0, ctx);
        for (Move move : legal) {
            if (!move.capture()) continue;
            String next = apply(state, move);
            if (next.isBlank()) continue;
            List<Move> replies = CheckersLogic.legalMoves(next);
            int score = -quiescence(next, -beta, -alpha, qDepth - 1, profile, ctx, replies);
            if (score >= beta) return score;
            if (score > alpha) alpha = score;
        }
        return alpha;
    }

    private static void orderMoves(String state, List<Move> moves, int ply, int ttMove, SearchContext ctx) {
        moves.sort((a, b) -> Integer.compare(
            moveOrderScore(state, b, ply, ttMove, ctx),
            moveOrderScore(state, a, ply, ttMove, ctx)
        ));
    }

    private static int rootOrderScore(
        String state,
        Move move,
        int ttMove,
        int pvKey,
        Map<Integer, Integer> previousRootScores,
        SearchContext ctx
    ) {
        int key = CheckersScriptHeuristics.moveKey(move);
        int score = moveOrderScore(state, move, 0, ttMove, ctx);
        if (key == pvKey) score += 1_200_000;
        Integer prev = previousRootScores.get(key);
        if (prev != null) score += prev;
        return score;
    }

    private static int moveOrderScore(String state, Move move, int ply, int ttMove, SearchContext ctx) {
        int key = CheckersScriptHeuristics.moveKey(move);
        if (key == ttMove) return 2_000_000;

        int score = 0;
        if (move.capture()) {
            score += 30_000 + Math.max(1, move.path().size() - 1) * 2_400;
        }
        if (CheckersScriptHeuristics.isPromotionMove(state, move)) score += 2_400;

        if (ply < ctx.killers.length) {
            if (key == ctx.killers[ply][0]) score += 1_500;
            else if (key == ctx.killers[ply][1]) score += 800;
        }

        Coord from = move.path().get(0);
        Coord to = move.path().get(move.path().size() - 1);
        int fromIdx = from.y() * 8 + from.x();
        int toIdx = to.y() * 8 + to.x();
        int side = CheckersLogic.isWhiteTurn(state) ? 0 : 1;
        score += ctx.history[side][fromIdx][toIdx];

        if (to.x() >= 2 && to.x() <= 5 && to.y() >= 2 && to.y() <= 5) score += 80;
        return score;
    }

    private static String apply(String state, Move move) {
        CheckersLogic.ApplyResult applied = CheckersLogic.applyMove(state, move.encode());
        return applied.ok() ? applied.state() : "";
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

    private record ScoredMove(Move move, int raw, int noisy) {
    }

    private record TTEntry(int depth, int score, int flag, int bestMoveKey) {
    }

    private record RootSearchResult(Move bestMove, int bestScore, List<ScoredMove> ranked, Map<Integer, Integer> rootScores) {
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

        private void recordCutoff(boolean whiteTurn, int ply, Move move, int depth) {
            int key = CheckersScriptHeuristics.moveKey(move);
            if (ply < killers.length && key != killers[ply][0]) {
                killers[ply][1] = killers[ply][0];
                killers[ply][0] = key;
            }
            Coord from = move.path().get(0);
            Coord to = move.path().get(move.path().size() - 1);
            int fromIdx = from.y() * 8 + from.x();
            int toIdx = to.y() * 8 + to.x();
            int side = whiteTurn ? 0 : 1;
            int bonus = depth * depth * 3;
            history[side][fromIdx][toIdx] = Math.min(60_000, history[side][fromIdx][toIdx] + bonus);
        }

        private void push(long hash, int ply) {
            if (ply >= 0 && ply < pathHashes.length) pathHashes[ply] = hash;
        }

        private boolean isRepetition(long hash, int ply) {
            if (ply < 4) return false;
            int minPly = Math.max(0, ply - 40);
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
        boolean captureExtension,
        int mobilityWeight
    ) {
        private static Profile forLevel(int level) {
            return switch (level) {
                case 1 -> new Profile(1, 1_000, 120, 0, 8, 0.93, 0.50, false, false, false, false, 0);
                case 2 -> new Profile(2, 5_500, 250, 1, 6, 0.62, 0.30, false, false, false, false, 0);
                case 3 -> new Profile(4, 30_000, 560, 2, 4, 0.30, 0.14, false, false, true, true, 1);
                case 4 -> new Profile(6, 120_000, 1_100, 3, 3, 0.10, 0.05, true, true, true, true, 1);
                case 5 -> new Profile(8, 450_000, 2_300, 4, 2, 0.03, 0.01, true, true, true, true, 2);
                case 6 -> new Profile(10, 1_500_000, 4_000, 5, 1, 0.0, 0.0, true, true, true, true, 2);
                default -> new Profile(13, 4_500_000, 6_800, 6, 1, 0.0, 0.0, true, true, true, true, 3);
            };
        }
    }
}

