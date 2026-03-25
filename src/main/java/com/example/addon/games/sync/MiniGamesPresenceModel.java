package com.example.addon.games.sync;

import static com.example.addon.games.sync.MiniGamesRuntimeSupport.safe;

import com.example.addon.games.MiniGamesContracts.GameType;
import com.example.addon.modules.sync.SyncJsonUtils;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

final class PresenceRecord {
    String deviceId = "";
    String displayName = "";
    String serverKey = "";
    long lastSeenMs;
    Invite outgoingInvite;
    InviteResponse inviteResponse;
    HostedSession hostedSession;
    GuestSession guestSession;
    GuestMove guestMove;

    String displayNameOrDevice() {
        String name = safe(displayName).trim();
        if (!name.isBlank()) return name;
        return safe(deviceId).isBlank() ? "<unknown>" : deviceId;
    }

    PresenceRecord copy() {
        PresenceRecord copy = new PresenceRecord();
        copy.deviceId = deviceId;
        copy.displayName = displayName;
        copy.serverKey = serverKey;
        copy.lastSeenMs = lastSeenMs;
        copy.outgoingInvite = outgoingInvite == null ? null : outgoingInvite.copy();
        copy.inviteResponse = inviteResponse == null ? null : inviteResponse.copy();
        copy.hostedSession = hostedSession == null ? null : hostedSession.copy();
        copy.guestSession = guestSession == null ? null : guestSession.copy();
        copy.guestMove = guestMove == null ? null : guestMove.copy();
        return copy;
    }

    MiniGamesSyncCodec.SyncRow toSyncRow() {
        JsonObject payload = new JsonObject();
        payload.addProperty("v", 1);
        payload.addProperty("deviceId", safe(deviceId));
        payload.addProperty("name", safe(displayName));
        payload.addProperty("server", safe(serverKey));
        payload.addProperty("lastSeen", Math.max(0, lastSeenMs));

        if (outgoingInvite != null) payload.add("invite", outgoingInvite.toJson());
        if (inviteResponse != null) payload.add("inviteResponse", inviteResponse.toJson());
        if (hostedSession != null) payload.add("hostSession", hostedSession.toJson());
        if (guestSession != null) payload.add("guestSession", guestSession.toJson());
        if (guestMove != null) payload.add("guestMove", guestMove.toJson());

        return new MiniGamesSyncCodec.SyncRow(
            true,
            safe(deviceId),
            safe(serverKey),
            "LOGIN",
            payload.toString(),
            (int) Math.max(0, Math.min(Integer.MAX_VALUE, lastSeenMs / 1000))
        );
    }

    static PresenceRecord fromSyncRow(MiniGamesSyncCodec.SyncRow row) {
        PresenceRecord record = new PresenceRecord();
        if (row == null) return record;

        record.deviceId = safe(row.username()).trim();
        record.serverKey = safe(row.server()).trim();
        record.lastSeenMs = Math.max(0, row.delay()) * 1000L;

        String payloadRaw = safe(row.payload());
        if (!payloadRaw.isBlank()) {
            try {
                if (JsonParser.parseString(payloadRaw).isJsonObject()) {
                    JsonObject json = JsonParser.parseString(payloadRaw).getAsJsonObject();
                    record.deviceId = safe(SyncJsonUtils.readString(json, "deviceId", record.deviceId)).trim();
                    record.displayName = safe(SyncJsonUtils.readString(json, "name", ""));
                    record.serverKey = safe(SyncJsonUtils.readString(json, "server", record.serverKey)).trim();
                    record.lastSeenMs = SyncJsonUtils.readLong(json, "lastSeen", record.lastSeenMs);
                    if (json.has("invite") && json.get("invite").isJsonObject()) {
                        record.outgoingInvite = Invite.fromJson(json.getAsJsonObject("invite"));
                    }
                    if (json.has("inviteResponse") && json.get("inviteResponse").isJsonObject()) {
                        record.inviteResponse = InviteResponse.fromJson(json.getAsJsonObject("inviteResponse"));
                    }
                    if (json.has("hostSession") && json.get("hostSession").isJsonObject()) {
                        record.hostedSession = HostedSession.fromJson(json.getAsJsonObject("hostSession"));
                    }
                    if (json.has("guestSession") && json.get("guestSession").isJsonObject()) {
                        record.guestSession = GuestSession.fromJson(json.getAsJsonObject("guestSession"));
                    }
                    if (json.has("guestMove") && json.get("guestMove").isJsonObject()) {
                        record.guestMove = GuestMove.fromJson(json.getAsJsonObject("guestMove"));
                    }
                }
            } catch (Exception ignored) {
            }
        }

        return record;
    }
}

final class Invite {
    final String id;
    final String gameId;
    final String toDeviceId;
    final String toName;
    final long createdAtMs;
    final long expiresAtMs;
    String status;

    Invite(String id, String gameId, String toDeviceId, String toName, long createdAtMs, long expiresAtMs, String status) {
        this.id = safe(id);
        this.gameId = safe(gameId);
        this.toDeviceId = safe(toDeviceId);
        this.toName = safe(toName);
        this.createdAtMs = createdAtMs;
        this.expiresAtMs = expiresAtMs;
        this.status = safe(status).isBlank() ? "pending" : status;
    }

    Invite copy() {
        return new Invite(id, gameId, toDeviceId, toName, createdAtMs, expiresAtMs, status);
    }

    boolean pending(long now) {
        return "pending".equals(status) && now <= expiresAtMs;
    }

    JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("id", id);
        json.addProperty("game", gameId);
        json.addProperty("to", toDeviceId);
        json.addProperty("toName", toName);
        json.addProperty("createdAt", createdAtMs);
        json.addProperty("expiresAt", expiresAtMs);
        json.addProperty("status", status);
        return json;
    }

    static Invite fromJson(JsonObject json) {
        return new Invite(
            SyncJsonUtils.readString(json, "id", ""),
            SyncJsonUtils.readString(json, "game", ""),
            SyncJsonUtils.readString(json, "to", ""),
            SyncJsonUtils.readString(json, "toName", ""),
            SyncJsonUtils.readLong(json, "createdAt", 0),
            SyncJsonUtils.readLong(json, "expiresAt", 0),
            SyncJsonUtils.readString(json, "status", "pending")
        );
    }
}

final class InviteResponse {
    final String inviteId;
    final String toDeviceId;
    final String gameId;
    final String decision;
    final long respondedAtMs;

    InviteResponse(String inviteId, String toDeviceId, String gameId, String decision, long respondedAtMs) {
        this.inviteId = safe(inviteId);
        this.toDeviceId = safe(toDeviceId);
        this.gameId = safe(gameId);
        this.decision = safe(decision);
        this.respondedAtMs = respondedAtMs;
    }

    InviteResponse copy() {
        return new InviteResponse(inviteId, toDeviceId, gameId, decision, respondedAtMs);
    }

    JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("inviteId", inviteId);
        json.addProperty("to", toDeviceId);
        json.addProperty("game", gameId);
        json.addProperty("decision", decision);
        json.addProperty("at", respondedAtMs);
        return json;
    }

    static InviteResponse fromJson(JsonObject json) {
        return new InviteResponse(
            SyncJsonUtils.readString(json, "inviteId", ""),
            SyncJsonUtils.readString(json, "to", ""),
            SyncJsonUtils.readString(json, "game", ""),
            SyncJsonUtils.readString(json, "decision", ""),
            SyncJsonUtils.readLong(json, "at", 0)
        );
    }
}

final class HostedSession {
    final String sessionId;
    final String gameId;
    final String hostDeviceId;
    final String guestDeviceId;
    final String hostName;
    final String guestName;
    String boardState;
    String status;
    String winner;
    int moveNo;
    int processedGuestSeq;
    final long startedAtMs;
    long updatedAtMs;

    HostedSession(
        String sessionId,
        String gameId,
        String hostDeviceId,
        String guestDeviceId,
        String hostName,
        String guestName,
        String boardState,
        String status,
        String winner,
        int moveNo,
        int processedGuestSeq,
        long startedAtMs,
        long updatedAtMs
    ) {
        this.sessionId = safe(sessionId);
        this.gameId = safe(gameId);
        this.hostDeviceId = safe(hostDeviceId);
        this.guestDeviceId = safe(guestDeviceId);
        this.hostName = safe(hostName);
        this.guestName = safe(guestName);
        this.boardState = safe(boardState);
        this.status = safe(status).isBlank() ? "active" : status;
        this.winner = safe(winner);
        this.moveNo = Math.max(0, moveNo);
        this.processedGuestSeq = Math.max(0, processedGuestSeq);
        this.startedAtMs = startedAtMs;
        this.updatedAtMs = updatedAtMs;
    }

    HostedSession copy() {
        return new HostedSession(
            sessionId,
            gameId,
            hostDeviceId,
            guestDeviceId,
            hostName,
            guestName,
            boardState,
            status,
            winner,
            moveNo,
            processedGuestSeq,
            startedAtMs,
            updatedAtMs
        );
    }

    boolean isActive() {
        return "active".equalsIgnoreCase(status);
    }

    boolean isFor(GameType game) {
        return game != null && game.id().equalsIgnoreCase(gameId);
    }

    GameType gameType() {
        GameType type = GameType.fromId(gameId);
        return type == null ? GameType.CHESS : type;
    }

    JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("id", sessionId);
        json.addProperty("game", gameId);
        json.addProperty("host", hostDeviceId);
        json.addProperty("guest", guestDeviceId);
        json.addProperty("hostName", hostName);
        json.addProperty("guestName", guestName);
        json.addProperty("board", boardState);
        json.addProperty("status", status);
        json.addProperty("winner", winner);
        json.addProperty("moveNo", moveNo);
        json.addProperty("processedGuestSeq", processedGuestSeq);
        json.addProperty("startedAt", startedAtMs);
        json.addProperty("updatedAt", updatedAtMs);
        return json;
    }

    static HostedSession fromJson(JsonObject json) {
        return new HostedSession(
            SyncJsonUtils.readString(json, "id", ""),
            SyncJsonUtils.readString(json, "game", ""),
            SyncJsonUtils.readString(json, "host", ""),
            SyncJsonUtils.readString(json, "guest", ""),
            SyncJsonUtils.readString(json, "hostName", ""),
            SyncJsonUtils.readString(json, "guestName", ""),
            SyncJsonUtils.readString(json, "board", ""),
            SyncJsonUtils.readString(json, "status", "active"),
            SyncJsonUtils.readString(json, "winner", ""),
            SyncJsonUtils.readInt(json, "moveNo", 0),
            SyncJsonUtils.readInt(json, "processedGuestSeq", 0),
            SyncJsonUtils.readLong(json, "startedAt", 0),
            SyncJsonUtils.readLong(json, "updatedAt", 0)
        );
    }
}

final class GuestSession {
    final String sessionId;
    final String hostDeviceId;
    final String gameId;
    final long joinedAtMs;

    GuestSession(String sessionId, String hostDeviceId, String gameId, long joinedAtMs) {
        this.sessionId = safe(sessionId);
        this.hostDeviceId = safe(hostDeviceId);
        this.gameId = safe(gameId);
        this.joinedAtMs = joinedAtMs;
    }

    GuestSession copy() {
        return new GuestSession(sessionId, hostDeviceId, gameId, joinedAtMs);
    }

    boolean isFor(GameType game) {
        return game != null && game.id().equalsIgnoreCase(gameId);
    }

    JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("id", sessionId);
        json.addProperty("host", hostDeviceId);
        json.addProperty("game", gameId);
        json.addProperty("joinedAt", joinedAtMs);
        return json;
    }

    static GuestSession fromJson(JsonObject json) {
        return new GuestSession(
            SyncJsonUtils.readString(json, "id", ""),
            SyncJsonUtils.readString(json, "host", ""),
            SyncJsonUtils.readString(json, "game", ""),
            SyncJsonUtils.readLong(json, "joinedAt", 0)
        );
    }
}

final class GuestMove {
    final String sessionId;
    final String move;
    final int seq;
    final long atMs;

    GuestMove(String sessionId, String move, int seq, long atMs) {
        this.sessionId = safe(sessionId);
        this.move = safe(move);
        this.seq = Math.max(0, seq);
        this.atMs = atMs;
    }

    GuestMove copy() {
        return new GuestMove(sessionId, move, seq, atMs);
    }

    JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("sessionId", sessionId);
        json.addProperty("move", move);
        json.addProperty("seq", seq);
        json.addProperty("at", atMs);
        return json;
    }

    static GuestMove fromJson(JsonObject json) {
        return new GuestMove(
            SyncJsonUtils.readString(json, "sessionId", ""),
            SyncJsonUtils.readString(json, "move", ""),
            SyncJsonUtils.readInt(json, "seq", 0),
            SyncJsonUtils.readLong(json, "at", 0)
        );
    }
}
