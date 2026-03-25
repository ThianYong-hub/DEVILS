package com.example.addon.games.sync;
import static com.example.addon.games.sync.MiniGamesRuntimeSupport.applyGameMove;
import static com.example.addon.games.sync.MiniGamesRuntimeSupport.currentServerKey;
import static com.example.addon.games.sync.MiniGamesRuntimeSupport.currentUsername;
import static com.example.addon.games.sync.MiniGamesRuntimeSupport.isWhiteTurn;
import static com.example.addon.games.sync.MiniGamesRuntimeSupport.normalizeWinner;
import static com.example.addon.games.sync.MiniGamesRuntimeSupport.sameServer;
import static com.example.addon.games.sync.MiniGamesRuntimeSupport.sameText;
import static com.example.addon.games.sync.MiniGamesRuntimeSupport.safe;
import com.example.addon.games.MiniGamesContracts.ActivePeer;
import com.example.addon.games.MiniGamesContracts.GameType;
import com.example.addon.games.MiniGamesContracts.IncomingInvite;
import com.example.addon.games.MiniGamesContracts.MoveSubmitResult;
import com.example.addon.games.MiniGamesContracts.OutgoingInvite;
import com.example.addon.games.MiniGamesContracts.SessionView;
import com.example.addon.games.checkers.CheckersLogic;
import com.example.addon.games.chess.ChessLogic;
import com.example.addon.modules.SyncHub;
import com.example.addon.modules.sync.SyncJsonUtils;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.client.MinecraftClient;
public final class MiniGamesSyncRuntime {
    private static final MiniGamesSyncRuntime INSTANCE = new MiniGamesSyncRuntime();
    private static final long ACTIVE_PEER_TIMEOUT_MS = 25_000;
    private static final long KEEP_ROW_TIMEOUT_MS = 5 * 60_000;
    private static final long INVITE_TIMEOUT_MS = 70_000;
    private static final long SYNC_INTERVAL_MS = 1_200;
    private static final int SYNC_ERROR_DETAIL_MAX = 120;
    private final MiniGamesSyncCodec codec = new MiniGamesSyncCodec(SYNC_ERROR_DETAIL_MAX);
    private final HashMap<String, PresenceRecord> rowsByDevice = new HashMap<>();
    private PresenceRecord local = new PresenceRecord();
    private boolean dirtyLocal = true;
    private boolean syncInFlight;
    private long lastKnownRevision = -1;
    private long lastSyncAttemptMs;
    private String lastFingerprint = "";
    private String lastStatus = "idle";
    private MiniGamesSyncRuntime() {
    }
    public static MiniGamesSyncRuntime get() {
        return INSTANCE;
    }
    public void tick() {
        long now = System.currentTimeMillis();
        SyncRuntimeConfig cfg = resolveSyncRuntimeConfig();
        if (cfg == null) {
            lastStatus = "disabled";
            return;
        }
        updateLocalPresence(cfg, now);
        processProtocol(now);
        pruneRows(now, cfg.deviceId());
        rowsByDevice.put(local.deviceId, local.copy());
        if (syncInFlight) return;
        if (!dirtyLocal && now - lastSyncAttemptMs < SYNC_INTERVAL_MS) return;
        syncInFlight = true;
        lastSyncAttemptMs = now;
        runSyncCycleAsync(cfg);
    }
    public String status() {
        return lastStatus;
    }
    public boolean isEnabled() {
        return resolveSyncRuntimeConfig() != null;
    }
    public List<ActivePeer> activePeers() {
        long now = System.currentTimeMillis();
        String serverKey = local.serverKey;
        ArrayList<ActivePeer> peers = new ArrayList<>();
        for (PresenceRecord row : rowsByDevice.values()) {
            if (row == null || row.deviceId.isBlank()) continue;
            if (row.deviceId.equals(local.deviceId)) continue;
            if (!sameServer(serverKey, row.serverKey)) continue;
            if ((now - row.lastSeenMs) > ACTIVE_PEER_TIMEOUT_MS) continue;
            boolean busy = (row.hostedSession != null && row.hostedSession.isActive()) || row.guestSession != null;
            peers.add(new ActivePeer(row.deviceId, row.displayNameOrDevice(), row.lastSeenMs, busy));
        }
        peers.sort(Comparator.comparing(ActivePeer::name, String.CASE_INSENSITIVE_ORDER));
        return peers;
    }
    public List<IncomingInvite> incomingInvites() {
        long now = System.currentTimeMillis();
        ArrayList<IncomingInvite> invites = new ArrayList<>();
        for (PresenceRecord row : rowsByDevice.values()) {
            if (row == null || row.deviceId.equals(local.deviceId)) continue;
            if (!sameServer(local.serverKey, row.serverKey)) continue;
            if ((now - row.lastSeenMs) > ACTIVE_PEER_TIMEOUT_MS) continue;
            Invite invite = row.outgoingInvite;
            if (invite == null || !invite.pending(now)) continue;
            if (!invite.toDeviceId.equals(local.deviceId)) continue;
            GameType type = GameType.fromId(invite.gameId);
            if (type == null) continue;
            invites.add(new IncomingInvite(invite.id, type, row.deviceId, row.displayNameOrDevice(), invite.createdAtMs, invite.expiresAtMs));
        }
        invites.sort(Comparator.comparingLong(IncomingInvite::createdAtMs).reversed());
        return invites;
    }
    public OutgoingInvite outgoingInvite() {
        Invite invite = local.outgoingInvite;
        if (invite == null) return null;
        GameType game = GameType.fromId(invite.gameId);
        if (game == null) return null;
        return new OutgoingInvite(invite.id, game, invite.toDeviceId, invite.toName, invite.createdAtMs, invite.expiresAtMs, invite.status);
    }
    public void invitePlayer(GameType game, String targetDeviceId, String targetName) {
        if (game == null || targetDeviceId == null || targetDeviceId.isBlank()) return;
        if (hasAnyActiveSession()) return;
        long now = System.currentTimeMillis();
        local.outgoingInvite = new Invite(
            UUID.randomUUID().toString(),
            game.id(),
            targetDeviceId.trim(),
            safe(targetName),
            now,
            now + INVITE_TIMEOUT_MS,
            "pending"
        );
        dirtyLocal = true;
    }
    public void cancelOutgoingInvite() {
        if (local.outgoingInvite == null) return;
        local.outgoingInvite = null;
        dirtyLocal = true;
    }
    public void acceptInvite(String inviteId, String fromDeviceId, GameType game) {
        if (inviteId == null || inviteId.isBlank() || fromDeviceId == null || fromDeviceId.isBlank() || game == null) return;
        long now = System.currentTimeMillis();
        local.inviteResponse = new InviteResponse(inviteId, fromDeviceId, game.id(), "accepted", now);
        local.guestSession = new GuestSession(inviteId, fromDeviceId, game.id(), now);
        local.guestMove = null;
        dirtyLocal = true;
    }
    public void declineInvite(String inviteId, String fromDeviceId, GameType game) {
        if (inviteId == null || inviteId.isBlank() || fromDeviceId == null || fromDeviceId.isBlank() || game == null) return;
        local.inviteResponse = new InviteResponse(inviteId, fromDeviceId, game.id(), "declined", System.currentTimeMillis());
        dirtyLocal = true;
    }
    public SessionView sessionView(GameType game) {
        if (game == null) return SessionView.inactive(game, "bad-game");
        if (local.hostedSession != null && local.hostedSession.isFor(game) && local.hostedSession.isActive()) {
            boolean whiteTurn = isWhiteTurn(game, local.hostedSession.boardState);
            return new SessionView(
                game,
                true,
                true,
                true,
                whiteTurn,
                local.hostedSession.sessionId,
                local.hostedSession.boardState,
                local.hostedSession.status,
                local.hostedSession.winner,
                local.hostedSession.guestName,
                local.hostedSession.moveNo,
                ""
            );
        }
        if (local.guestSession != null && local.guestSession.isFor(game)) {
            PresenceRecord host = rowsByDevice.get(local.guestSession.hostDeviceId);
            HostedSession hostSession = host == null ? null : host.hostedSession;
            if (hostSession == null || !hostSession.sessionId.equals(local.guestSession.sessionId) || !hostSession.isFor(game)) {
                return SessionView.inactive(game, "host-session-missing");
            }
            boolean whiteTurn = isWhiteTurn(game, hostSession.boardState);
            return new SessionView(
                game,
                true,
                false,
                false,
                !whiteTurn && hostSession.isActive(),
                hostSession.sessionId,
                hostSession.boardState,
                hostSession.status,
                hostSession.winner,
                host.displayNameOrDevice(),
                hostSession.moveNo,
                ""
            );
        }
        return SessionView.inactive(game, "");
    }
    public MoveSubmitResult submitMove(GameType game, String moveText) {
        SessionView session = sessionView(game);
        if (!session.active()) return MoveSubmitResult.fail("session-not-active");
        if (!session.localTurn()) return MoveSubmitResult.fail("not-your-turn");
        if (session.localHost()) {
            HostedSession host = local.hostedSession;
            if (host == null || !host.isFor(game)) return MoveSubmitResult.fail("host-session-missing");
            MoveApplyResult applied = applyGameMove(game, host.boardState, moveText);
            if (!applied.ok()) return MoveSubmitResult.fail(applied.error());
            host.boardState = applied.nextState();
            host.moveNo++;
            host.updatedAtMs = System.currentTimeMillis();
            if (!"ongoing".equals(applied.status()) && !"active".equals(applied.status())) {
                host.status = "finished";
                host.winner = normalizeWinner(applied.winner());
            } else {
                host.status = "active";
                host.winner = "";
            }
            dirtyLocal = true;
            return MoveSubmitResult.success();
        }
        if (local.guestSession == null || !local.guestSession.isFor(game)) {
            return MoveSubmitResult.fail("guest-session-missing");
        }
        int nextSeq = local.guestMove == null ? 1 : local.guestMove.seq + 1;
        local.guestMove = new GuestMove(local.guestSession.sessionId, safe(moveText).trim(), nextSeq, System.currentTimeMillis());
        dirtyLocal = true;
        return MoveSubmitResult.success();
    }
    public void leaveSession(GameType game) {
        if (game == null) return;
        if (local.hostedSession != null && local.hostedSession.isFor(game)) local.hostedSession = null;
        if (local.guestSession != null && local.guestSession.isFor(game)) {
            local.guestSession = null;
            local.guestMove = null;
        }
        if (local.outgoingInvite != null && game.id().equals(local.outgoingInvite.gameId)) local.outgoingInvite = null;
        dirtyLocal = true;
    }
    public boolean hasAnyActiveSession() {
        if (local.hostedSession != null && local.hostedSession.isActive()) return true;
        return local.guestSession != null;
    }
    public String localName() {
        return local.displayNameOrDevice();
    }
    private void updateLocalPresence(SyncRuntimeConfig cfg, long now) {
        String username = currentUsername();
        String server = currentServerKey();
        if (!cfg.deviceId().equals(local.deviceId)) {
            local.deviceId = cfg.deviceId();
            dirtyLocal = true;
        }
        if (!sameText(local.displayName, username)) {
            local.displayName = username;
            dirtyLocal = true;
        }
        if (!sameText(local.serverKey, server)) {
            local.serverKey = server;
            dirtyLocal = true;
        }
        if (now - local.lastSeenMs >= 2_000) {
            local.lastSeenMs = now;
            dirtyLocal = true;
        }
    }
    private void processProtocol(long now) {
        if (local.outgoingInvite != null && !local.outgoingInvite.pending(now)) {
            local.outgoingInvite = null;
            dirtyLocal = true;
        }
        if (local.outgoingInvite != null) {
            PresenceRecord target = rowsByDevice.get(local.outgoingInvite.toDeviceId);
            InviteResponse response = target == null ? null : target.inviteResponse;
            if (response != null && local.outgoingInvite.id.equals(response.inviteId) && "pending".equals(local.outgoingInvite.status)) {
                if ("accepted".equals(response.decision)) {
                    startHostedSession(local.outgoingInvite, target, now);
                    local.outgoingInvite.status = "accepted";
                    dirtyLocal = true;
                } else if ("declined".equals(response.decision)) {
                    local.outgoingInvite = null;
                    dirtyLocal = true;
                }
            }
        }
        if (local.hostedSession != null && local.hostedSession.isActive()) {
            PresenceRecord guest = rowsByDevice.get(local.hostedSession.guestDeviceId);
            GuestMove move = guest == null ? null : guest.guestMove;
            if (move != null
                && local.hostedSession.sessionId.equals(move.sessionId)
                && move.seq > local.hostedSession.processedGuestSeq
                && !isWhiteTurn(local.hostedSession.gameType(), local.hostedSession.boardState)
            ) {
                MoveApplyResult applied = applyGameMove(local.hostedSession.gameType(), local.hostedSession.boardState, move.move);
                local.hostedSession.processedGuestSeq = move.seq;
                local.hostedSession.updatedAtMs = now;
                if (applied.ok()) {
                    local.hostedSession.boardState = applied.nextState();
                    local.hostedSession.moveNo++;
                    if (!"ongoing".equals(applied.status()) && !"active".equals(applied.status())) {
                        local.hostedSession.status = "finished";
                        local.hostedSession.winner = normalizeWinner(applied.winner());
                    }
                }
                dirtyLocal = true;
            }
        }
        if (local.guestSession != null) {
            PresenceRecord host = rowsByDevice.get(local.guestSession.hostDeviceId);
            HostedSession hostSession = host == null ? null : host.hostedSession;
            if (hostSession == null || !hostSession.sessionId.equals(local.guestSession.sessionId)) {
                if (now - local.guestSession.joinedAtMs > 20_000) {
                    local.guestSession = null;
                    local.guestMove = null;
                    dirtyLocal = true;
                }
            } else if (local.guestMove != null && hostSession.processedGuestSeq >= local.guestMove.seq) {
                local.guestMove = null;
                dirtyLocal = true;
            }
        }
        if (local.inviteResponse != null && (now - local.inviteResponse.respondedAtMs) > 60_000) {
            local.inviteResponse = null;
            dirtyLocal = true;
        }
    }
    private void startHostedSession(Invite invite, PresenceRecord guest, long now) {
        GameType game = GameType.fromId(invite.gameId);
        if (game == null) return;
        String initialBoard = switch (game) {
            case CHESS -> ChessLogic.initialFen();
            case CHECKERS -> CheckersLogic.initialState();
        };
        local.hostedSession = new HostedSession(
            invite.id,
            game.id(),
            local.deviceId,
            guest.deviceId,
            local.displayNameOrDevice(),
            guest.displayNameOrDevice(),
            initialBoard,
            "active",
            "",
            0,
            0,
            now,
            now
        );
    }
    private void pruneRows(long now, String currentDeviceId) {
        ArrayList<String> remove = new ArrayList<>();
        for (Map.Entry<String, PresenceRecord> entry : rowsByDevice.entrySet()) {
            PresenceRecord row = entry.getValue();
            if (row == null) {
                remove.add(entry.getKey());
                continue;
            }
            if (entry.getKey().equals(currentDeviceId)) continue;
            if ((now - row.lastSeenMs) > KEEP_ROW_TIMEOUT_MS) remove.add(entry.getKey());
        }
        for (String key : remove) rowsByDevice.remove(key);
    }
    private void runSyncCycleAsync(SyncRuntimeConfig cfg) {
        HashMap<String, PresenceRecord> snapshot = new HashMap<>(rowsByDevice);
        snapshot.put(local.deviceId, local.copy());
        CompletableFuture.runAsync(() -> {
            SyncCycleResult result;
            try {
                MiniGamesSyncCodec.PullResult pull = codec.sendPullRequest(
                    cfg.baseUrl(),
                    cfg.deviceId(),
                    cfg.token(),
                    cfg.signingKey(),
                    cfg.timeoutSec(),
                    cfg.encryptionKey(),
                    lastKnownRevision
                );
                if (!pull.ok()) {
                    result = SyncCycleResult.error("pull-rejected:" + safe(pull.error()));
                } else {
                    HashMap<String, PresenceRecord> mergedRows = decodeRows(pull.rows());
                    mergedRows.put(local.deviceId, local.copy());
                    List<MiniGamesSyncCodec.SyncRow> payloadRows = encodeRows(mergedRows);
                    String fingerprint = codec.computeFingerprint(payloadRows);
                    boolean shouldPush = dirtyLocal || !fingerprint.equals(lastFingerprint);
                    if (!shouldPush) {
                        result = SyncCycleResult.noop(mergedRows, pull.revision(), fingerprint);
                    } else {
                        long baseRevision = pull.revision() >= 0 ? pull.revision() : lastKnownRevision;
                        MiniGamesSyncCodec.PushResult push = codec.sendPushRequest(
                            cfg.baseUrl(),
                            cfg.deviceId(),
                            cfg.token(),
                            cfg.signingKey(),
                            cfg.timeoutSec(),
                            cfg.encryptionKey(),
                            baseRevision,
                            payloadRows
                        );
                        if (!push.ok()) {
                            if (push.conflict()) {
                                HashMap<String, PresenceRecord> conflictRows = push.rows().isEmpty() ? mergedRows : decodeRows(push.rows());
                                conflictRows.put(local.deviceId, local.copy());
                                result = SyncCycleResult.conflict(conflictRows, push.revision(), fingerprint, safe(push.error()));
                            } else {
                                result = SyncCycleResult.error("push-rejected:" + safe(push.error()));
                            }
                        } else {
                            HashMap<String, PresenceRecord> appliedRows = push.rows().isEmpty() ? mergedRows : decodeRows(push.rows());
                            appliedRows.put(local.deviceId, local.copy());
                            result = SyncCycleResult.ok(appliedRows, push.revision(), fingerprint);
                        }
                    }
                }
            } catch (Throwable t) {
                result = SyncCycleResult.error(SyncJsonUtils.formatSyncException("sync-error", t, SYNC_ERROR_DETAIL_MAX));
            }
            SyncCycleResult finalResult = result;
            MinecraftClient.getInstance().execute(() -> finishSyncCycle(finalResult));
        });
    }
    private void finishSyncCycle(SyncCycleResult result) {
        syncInFlight = false;
        if (result.error() != null && !result.error().isBlank()) {
            lastStatus = result.error();
            return;
        }
        if (result.rows() != null) {
            rowsByDevice.clear();
            rowsByDevice.putAll(result.rows());
            rowsByDevice.put(local.deviceId, local.copy());
        }
        if (result.revision() >= 0) lastKnownRevision = Math.max(lastKnownRevision, result.revision());
        if (result.fingerprint() != null && !result.fingerprint().isBlank()) lastFingerprint = result.fingerprint();
        dirtyLocal = false;
        lastStatus = result.conflict() ? "push-conflict" : result.status();
    }
    private HashMap<String, PresenceRecord> decodeRows(List<MiniGamesSyncCodec.SyncRow> rows) {
        HashMap<String, PresenceRecord> decoded = new HashMap<>();
        if (rows == null) return decoded;
        for (MiniGamesSyncCodec.SyncRow row : rows) {
            PresenceRecord record = PresenceRecord.fromSyncRow(row);
            if (record.deviceId.isBlank()) continue;
            PresenceRecord existing = decoded.get(record.deviceId);
            if (existing == null || record.lastSeenMs >= existing.lastSeenMs) decoded.put(record.deviceId, record);
        }
        return decoded;
    }
    private List<MiniGamesSyncCodec.SyncRow> encodeRows(Map<String, PresenceRecord> map) {
        ArrayList<MiniGamesSyncCodec.SyncRow> rows = new ArrayList<>();
        for (PresenceRecord record : map.values()) {
            if (record == null || record.deviceId.isBlank()) continue;
            rows.add(record.toSyncRow());
        }
        rows.sort(Comparator.comparing(MiniGamesSyncCodec.SyncRow::username, String.CASE_INSENSITIVE_ORDER));
        return rows;
    }
    private SyncRuntimeConfig resolveSyncRuntimeConfig() {
        Modules modules = Modules.get();
        if (modules == null) return null;
        SyncHub syncHub = modules.get(SyncHub.class);
        if (syncHub == null) return null;
        if (!syncHub.isFeatureEnabled(SyncHub.SyncFeature.GAMES)) return null;
        String deviceId = syncHub.getOrCreateDeviceId();
        if (deviceId.isBlank()) return null;
        String encryptionKey = syncHub.getEncryptionKeyMaterial();
        if (encryptionKey.isBlank()) return null;
        String signingKey = syncHub.getRequestSigningKey();
        if (signingKey.isBlank()) return null;
        String baseUrl = codec.normalizeSyncBaseUrl(syncHub.getBaseUrl());
        if (baseUrl.isBlank()) return null;
        String baseError = codec.validateSyncBaseUrl(baseUrl);
        if (baseError != null) return null;
        if (!syncHub.allowHttp() && baseUrl.startsWith("http://")) return null;
        return new SyncRuntimeConfig(
            baseUrl,
            syncHub.getToken(),
            deviceId,
            Math.max(3, syncHub.requestTimeoutSec()),
            encryptionKey,
            signingKey
        );
    }
}
