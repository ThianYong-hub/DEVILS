package com.example.addon.games;

import java.util.Locale;

public final class MiniGamesContracts {
    private MiniGamesContracts() {
    }

    public enum GameType {
        CHESS("chess", "Chess"),
        CHECKERS("checkers", "Checkers");

        private final String id;
        private final String title;

        GameType(String id, String title) {
            this.id = id;
            this.title = title;
        }

        public String id() {
            return id;
        }

        public String title() {
            return title;
        }

        public static GameType fromId(String value) {
            if (value == null) return null;
            String normalized = value.trim().toLowerCase(Locale.ROOT);
            for (GameType type : values()) {
                if (type.id.equals(normalized)) return type;
            }
            return null;
        }
    }

    public record ActivePeer(String deviceId, String name, long lastSeenMs, boolean busy) {
        public String displayName() {
            if (name == null || name.isBlank()) return deviceId == null ? "<unknown>" : deviceId;
            return name;
        }
    }

    public record IncomingInvite(
        String inviteId,
        GameType game,
        String fromDeviceId,
        String fromName,
        long createdAtMs,
        long expiresAtMs
    ) {
        public long remainingMs(long now) {
            return Math.max(0, expiresAtMs - Math.max(0, now));
        }
    }

    public record OutgoingInvite(
        String inviteId,
        GameType game,
        String toDeviceId,
        String toName,
        long createdAtMs,
        long expiresAtMs,
        String status
    ) {
        public long remainingMs(long now) {
            return Math.max(0, expiresAtMs - Math.max(0, now));
        }
    }

    public record SessionView(
        GameType game,
        boolean active,
        boolean localHost,
        boolean localWhite,
        boolean localTurn,
        String sessionId,
        String boardState,
        String status,
        String winner,
        String opponentName,
        int moveNo,
        String error
    ) {
        public static SessionView inactive(GameType game, String error) {
            return new SessionView(
                game,
                false,
                false,
                true,
                false,
                "",
                "",
                "inactive",
                "",
                "",
                0,
                safe(error)
            );
        }
    }

    public record MoveSubmitResult(boolean ok, String error) {
        public static MoveSubmitResult success() {
            return new MoveSubmitResult(true, "");
        }

        public static MoveSubmitResult fail(String error) {
            return new MoveSubmitResult(false, safe(error));
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}

