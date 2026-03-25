package com.example.addon.games.sync;

import com.example.addon.games.MiniGamesContracts.GameType;
import com.example.addon.games.checkers.CheckersLogic;
import com.example.addon.games.chess.ChessLogic;
import java.util.HashMap;
import java.util.Locale;
import meteordevelopment.meteorclient.utils.Utils;
import net.minecraft.client.MinecraftClient;

record SyncRuntimeConfig(
    String baseUrl,
    String token,
    String deviceId,
    int timeoutSec,
    String encryptionKey,
    String signingKey
) {
}

record MoveApplyResult(boolean ok, String nextState, String status, String winner, String error) {
}

record SyncCycleResult(
    String status,
    boolean conflict,
    HashMap<String, PresenceRecord> rows,
    long revision,
    String fingerprint,
    String error
) {
    static SyncCycleResult ok(HashMap<String, PresenceRecord> rows, long revision, String fingerprint) {
        return new SyncCycleResult("push-ok", false, rows, revision, fingerprint, "");
    }

    static SyncCycleResult noop(HashMap<String, PresenceRecord> rows, long revision, String fingerprint) {
        return new SyncCycleResult("noop", false, rows, revision, fingerprint, "");
    }

    static SyncCycleResult conflict(HashMap<String, PresenceRecord> rows, long revision, String fingerprint, String error) {
        return new SyncCycleResult("push-conflict", true, rows, revision, fingerprint, MiniGamesRuntimeSupport.safe(error));
    }

    static SyncCycleResult error(String error) {
        return new SyncCycleResult("error", false, null, -1, "", MiniGamesRuntimeSupport.safe(error));
    }
}

final class MiniGamesRuntimeSupport {
    private MiniGamesRuntimeSupport() {
    }

    static boolean sameServer(String a, String b) {
        return normalizeServer(a).equals(normalizeServer(b));
    }

    static String normalizeServer(String value) {
        String normalized = safe(value).trim().toLowerCase(Locale.ROOT);
        if (normalized.endsWith(":25565")) normalized = normalized.substring(0, normalized.length() - 6);
        return normalized;
    }

    static String currentUsername() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.getSession() == null || mc.getSession().getUsername() == null) return "";
        return mc.getSession().getUsername().trim();
    }

    static String currentServerKey() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.getCurrentServerEntry() != null && mc.getCurrentServerEntry().address != null) {
            String address = mc.getCurrentServerEntry().address.trim();
            if (!address.isEmpty()) return address;
        }
        String world = Utils.getWorldName();
        return world == null ? "" : world.trim();
    }

    static boolean isWhiteTurn(GameType game, String state) {
        return switch (game) {
            case CHESS -> ChessLogic.isWhiteTurn(state);
            case CHECKERS -> CheckersLogic.isWhiteTurn(state);
        };
    }

    static MoveApplyResult applyGameMove(GameType game, String state, String moveText) {
        return switch (game) {
            case CHESS -> {
                ChessLogic.ApplyResult applied = ChessLogic.applyMove(state, moveText);
                yield new MoveApplyResult(applied.ok(), applied.fen(), normalizeStatus(applied.status()), applied.winner(), applied.error());
            }
            case CHECKERS -> {
                CheckersLogic.ApplyResult applied = CheckersLogic.applyMove(state, moveText);
                yield new MoveApplyResult(applied.ok(), applied.state(), normalizeStatus(applied.status()), applied.winner(), applied.error());
            }
        };
    }

    static String normalizeStatus(String raw) {
        String value = safe(raw).trim().toLowerCase(Locale.ROOT);
        if (value.isBlank()) return "ongoing";
        return value;
    }

    static String normalizeWinner(String raw) {
        String value = safe(raw).trim().toLowerCase(Locale.ROOT);
        if (value.isBlank()) return "";
        if (value.equals("draw")) return "draw";
        if (value.equals("white") || value.equals("black")) return value;
        return "";
    }

    static boolean sameText(String a, String b) {
        return safe(a).equals(safe(b));
    }

    static String safe(String value) {
        return value == null ? "" : value;
    }
}
