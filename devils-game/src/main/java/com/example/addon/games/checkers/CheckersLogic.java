package com.example.addon.games.checkers;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;

public final class CheckersLogic {
    public static final String INITIAL_STATE = "b.b.b.b./.b.b.b.b/b.b.b.b./......../......../.w.w.w.w/w.w.w.w./.w.w.w.w w";

    private CheckersLogic() {
    }

    public record Coord(int x, int y) {
    }

    public record Move(List<Coord> path, boolean capture) {
        public String encode() {
            if (path == null || path.isEmpty()) return "";
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < path.size(); i++) {
                if (i > 0) sb.append(capture ? ':' : '-');
                Coord c = path.get(i);
                sb.append(square(c.x, c.y));
            }
            return sb.toString();
        }
    }

    public record ApplyResult(boolean ok, String state, String status, String winner, String error) {
    }

    public static String initialState() {
        return INITIAL_STATE;
    }

    public static boolean isWhiteTurn(String state) {
        Position position = parseOrInitial(state);
        return position.whiteTurn;
    }

    public static char[][] board(String state) {
        Position position = parseOrInitial(state);
        char[][] copy = new char[8][8];
        for (int y = 0; y < 8; y++) {
            System.arraycopy(position.board[y], 0, copy[y], 0, 8);
        }
        return copy;
    }

    public static List<Move> legalMoves(String state) {
        Position position = parseOrInitial(state);
        return generateLegalMoves(position);
    }

    public static String randomScriptMove(String state, Random random) {
        return scriptMove(state, random, 2);
    }

    public static String scriptMove(String state, Random random, int level) {
        return CheckersScriptBot.chooseMove(state, random, level);
    }

    public static ApplyResult applyMove(String state, String moveText) {
        Position position = parseOrInitial(state);
        Move requested = parseMove(moveText);
        if (requested == null) return new ApplyResult(false, toState(position), "invalid", "", "bad-move-format");

        List<Move> legal = generateLegalMoves(position);
        Move selected = null;
        for (Move move : legal) {
            if (samePath(move.path, requested.path)) {
                selected = move;
                break;
            }
        }
        if (selected == null) return new ApplyResult(false, toState(position), "invalid", "", "illegal-move");

        Position next = position.copy();
        applyUnchecked(next, selected);

        String status = "ongoing";
        String winner = "";
        boolean enemyWhite = next.whiteTurn;
        int enemyPieces = countPieces(next, enemyWhite);
        if (enemyPieces == 0 || generateLegalMoves(next).isEmpty()) {
            status = "finished";
            winner = enemyWhite ? "black" : "white";
        }

        return new ApplyResult(true, toState(next), status, winner, "");
    }

    public static Move parseMove(String raw) {
        if (raw == null) return null;
        String value = raw.trim().toLowerCase(Locale.ROOT);
        if (value.isBlank()) return null;

        boolean capture = value.contains(":") || value.contains("x");
        String normalized = value.replace('x', ':');
        String[] tokens = normalized.split(capture ? ":" : "-");
        if (tokens.length < 2) return null;

        ArrayList<Coord> path = new ArrayList<>();
        for (String token : tokens) {
            String cell = token.trim();
            if (cell.length() != 2) return null;
            int x = cell.charAt(0) - 'a';
            int y = 8 - (cell.charAt(1) - '0');
            if (!inside(x, y)) return null;
            path.add(new Coord(x, y));
        }
        return new Move(path, capture);
    }

    private static Position parseOrInitial(String state) {
        try {
            return parseState(state == null || state.isBlank() ? INITIAL_STATE : state.trim());
        } catch (Exception ignored) {
            return parseState(INITIAL_STATE);
        }
    }

    private static Position parseState(String raw) {
        String[] parts = raw.split("\\s+");
        if (parts.length < 2) throw new IllegalArgumentException("bad-state");
        String[] rows = parts[0].split("/");
        if (rows.length != 8) throw new IllegalArgumentException("bad-board");

        Position position = new Position();
        for (int y = 0; y < 8; y++) {
            if (rows[y].length() != 8) throw new IllegalArgumentException("bad-rank");
            for (int x = 0; x < 8; x++) {
                char piece = rows[y].charAt(x);
                if (piece != '.' && piece != 'w' && piece != 'W' && piece != 'b' && piece != 'B') {
                    throw new IllegalArgumentException("bad-piece");
                }
                position.board[y][x] = piece;
            }
        }
        position.whiteTurn = parts[1].equalsIgnoreCase("w");
        return position;
    }

    private static String toState(Position position) {
        StringBuilder boardPart = new StringBuilder();
        for (int y = 0; y < 8; y++) {
            if (y > 0) boardPart.append('/');
            for (int x = 0; x < 8; x++) boardPart.append(position.board[y][x]);
        }
        boardPart.append(' ').append(position.whiteTurn ? 'w' : 'b');
        return boardPart.toString();
    }

    private static List<Move> generateLegalMoves(Position position) {
        ArrayList<Move> captures = new ArrayList<>();
        ArrayList<Move> normal = new ArrayList<>();
        boolean white = position.whiteTurn;

        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                char piece = position.board[y][x];
                if (piece == '.') continue;
                if (white != isWhite(piece)) continue;

                ArrayList<Coord> startPath = new ArrayList<>();
                startPath.add(new Coord(x, y));
                collectCaptures(position, x, y, piece, startPath, captures);
            }
        }
        if (!captures.isEmpty()) return captures;

        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                char piece = position.board[y][x];
                if (piece == '.') continue;
                if (white != isWhite(piece)) continue;
                collectNormalMoves(position, x, y, piece, normal);
            }
        }

        return normal;
    }

    private static void collectNormalMoves(Position position, int x, int y, char piece, List<Move> out) {
        if (isKing(piece)) {
            for (int[] dir : KING_DIRS) {
                int nx = x + dir[0];
                int ny = y + dir[1];
                while (inside(nx, ny) && position.board[ny][nx] == '.') {
                    out.add(new Move(List.of(new Coord(x, y), new Coord(nx, ny)), false));
                    nx += dir[0];
                    ny += dir[1];
                }
            }
            return;
        }

        int stepY = isWhite(piece) ? -1 : 1;
        for (int dx : new int[] {-1, 1}) {
            int nx = x + dx;
            int ny = y + stepY;
            if (!inside(nx, ny) || position.board[ny][nx] != '.') continue;
            out.add(new Move(List.of(new Coord(x, y), new Coord(nx, ny)), false));
        }
    }

    private static void collectCaptures(Position position, int x, int y, char piece, List<Coord> path, List<Move> out) {
        if (isKing(piece)) collectKingCaptures(position, x, y, piece, path, out);
        else collectManCaptures(position, x, y, piece, path, out);
    }

    private static void collectManCaptures(Position position, int x, int y, char piece, List<Coord> path, List<Move> out) {
        boolean found = false;
        for (int[] dir : KING_DIRS) {
            int mx = x + dir[0];
            int my = y + dir[1];
            int lx = x + dir[0] * 2;
            int ly = y + dir[1] * 2;
            if (!inside(mx, my) || !inside(lx, ly)) continue;

            char middle = position.board[my][mx];
            if (middle == '.' || isWhite(middle) == isWhite(piece)) continue;
            if (position.board[ly][lx] != '.') continue;

            found = true;
            Position next = position.copy();
            next.board[y][x] = '.';
            next.board[my][mx] = '.';
            char movedPiece = piece;
            if (piece == 'w' && ly == 0) movedPiece = 'W';
            if (piece == 'b' && ly == 7) movedPiece = 'B';
            next.board[ly][lx] = movedPiece;

            ArrayList<Coord> nextPath = new ArrayList<>(path);
            nextPath.add(new Coord(lx, ly));
            collectCaptures(next, lx, ly, movedPiece, nextPath, out);
        }

        if (!found && path.size() > 1) out.add(new Move(new ArrayList<>(path), true));
    }

    private static void collectKingCaptures(Position position, int x, int y, char piece, List<Coord> path, List<Move> out) {
        boolean found = false;
        for (int[] dir : KING_DIRS) {
            int nx = x + dir[0];
            int ny = y + dir[1];

            while (inside(nx, ny) && position.board[ny][nx] == '.') {
                nx += dir[0];
                ny += dir[1];
            }
            if (!inside(nx, ny)) continue;
            if (isWhite(position.board[ny][nx]) == isWhite(piece)) continue;

            int landingX = nx + dir[0];
            int landingY = ny + dir[1];
            while (inside(landingX, landingY) && position.board[landingY][landingX] == '.') {
                found = true;
                Position next = position.copy();
                next.board[y][x] = '.';
                next.board[ny][nx] = '.';
                next.board[landingY][landingX] = piece;

                ArrayList<Coord> nextPath = new ArrayList<>(path);
                nextPath.add(new Coord(landingX, landingY));
                collectCaptures(next, landingX, landingY, piece, nextPath, out);

                landingX += dir[0];
                landingY += dir[1];
            }
        }

        if (!found && path.size() > 1) out.add(new Move(new ArrayList<>(path), true));
    }

    private static void applyUnchecked(Position position, Move move) {
        List<Coord> path = move.path == null ? Collections.emptyList() : move.path;
        if (path.size() < 2) return;

        Coord start = path.get(0);
        char piece = position.board[start.y][start.x];
        position.board[start.y][start.x] = '.';

        for (int i = 1; i < path.size(); i++) {
            Coord from = path.get(i - 1);
            Coord to = path.get(i);
            int dx = Integer.compare(to.x, from.x);
            int dy = Integer.compare(to.y, from.y);
            int cx = from.x + dx;
            int cy = from.y + dy;
            while (cx != to.x || cy != to.y) {
                if (position.board[cy][cx] != '.') {
                    position.board[cy][cx] = '.';
                    break;
                }
                cx += dx;
                cy += dy;
            }
        }

        Coord end = path.get(path.size() - 1);
        if (piece == 'w' && end.y == 0) piece = 'W';
        else if (piece == 'b' && end.y == 7) piece = 'B';
        position.board[end.y][end.x] = piece;
        position.whiteTurn = !position.whiteTurn;
    }

    private static boolean samePath(List<Coord> a, List<Coord> b) {
        if (a == null || b == null) return false;
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            Coord ca = a.get(i);
            Coord cb = b.get(i);
            if (ca.x != cb.x || ca.y != cb.y) return false;
        }
        return true;
    }

    private static int countPieces(Position position, boolean white) {
        int count = 0;
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                char piece = position.board[y][x];
                if (piece == '.') continue;
                if (isWhite(piece) == white) count++;
            }
        }
        return count;
    }

    private static boolean isWhite(char piece) {
        return piece == 'w' || piece == 'W';
    }

    private static boolean isKing(char piece) {
        return piece == 'W' || piece == 'B';
    }

    private static boolean inside(int x, int y) {
        return x >= 0 && x < 8 && y >= 0 && y < 8;
    }

    private static String square(int x, int y) {
        return "" + (char) ('a' + x) + (char) ('8' - y);
    }

    private static final int[][] KING_DIRS = {
        {1, 1}, {-1, 1}, {1, -1}, {-1, -1}
    };

    private static final class Position {
        private final char[][] board = new char[8][8];
        private boolean whiteTurn = true;

        private Position copy() {
            Position copy = new Position();
            for (int y = 0; y < 8; y++) System.arraycopy(board[y], 0, copy.board[y], 0, 8);
            copy.whiteTurn = whiteTurn;
            return copy;
        }
    }
}

