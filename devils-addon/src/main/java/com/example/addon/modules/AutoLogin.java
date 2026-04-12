package com.example.addon.modules;

import com.example.addon.AddonTemplate;
import com.example.addon.modules.autologin.*;
import com.example.addon.modules.autologin.AutoLoginProfile.DebugChatPacketSnapshot;
import com.example.addon.util.CrashGuard;
import meteordevelopment.meteorclient.events.game.GameJoinedEvent;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.game.ReceiveMessageEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.packet.c2s.play.CommandExecutionC2SPacket;
import net.minecraft.network.packet.s2c.play.ChatMessageS2CPacket;
import net.minecraft.network.packet.s2c.play.GameMessageS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerListS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerRemoveS2CPacket;
import net.minecraft.network.packet.s2c.play.ProfilelessChatMessageS2CPacket;

import java.util.*;

public class AutoLogin extends Module {
    private static final String DEBUG_CHAT_MARKER = "AutoLogin DBG";
    private static final long RECEIVE_FALLBACK_WINDOW_MS = 120_000;
    private static final long RECEIVE_FALLBACK_ONLINE_WAIT_MS = 5_000;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final Setting<Boolean> autoSave = sgGeneral.add(new BoolSetting.Builder().name("auto-save").description("Save credentials automatically when you manually use /login or /reg.").defaultValue(true).build());
    private final Setting<Integer> newEntryDelay = sgGeneral.add(new IntSetting.Builder().name("new-entry-delay").description("Default delay in ticks for newly saved entries.").defaultValue(40).min(0).sliderRange(0, 200).build());
    private final Setting<Boolean> onlyMultiplayer = sgGeneral.add(new BoolSetting.Builder().name("only-multiplayer").description("Only auto-login on multiplayer servers.").defaultValue(true).build());
    private final Setting<Boolean> debugClipboard = sgGeneral.add(new BoolSetting.Builder().name("debug-clipboard").description("Copies auth-like incoming message diagnostics to the clipboard.").defaultValue(false).build());
    private final Setting<Boolean> syncVerbose = sgGeneral.add(new BoolSetting.Builder().name("sync-verbose-log").description("Show AutoLogin sync lifecycle messages in chat.").defaultValue(false).build());

    private final AutoLoginProfileStore profileStore = new AutoLoginProfileStore();
    private final AutoLoginSyncController syncController = new AutoLoginSyncController(profileStore, () -> syncVerbose.get(), () -> newEntryDelay.get(), this::info);
    private final ArrayDeque<DebugChatPacketSnapshot> recentChatPackets = new ArrayDeque<>();
    private final Map<UUID, String> knownPlayers = new HashMap<>();
    private final List<String> debugCaptureLines = new ArrayList<>();

    private boolean sentThisJoin;
    private boolean sendingAutoCommand;
    private long promptWorldTime = -1;
    private AutoLoginProfile pendingProfile;
    private boolean debugSessionActive;
    private boolean debugDumpCopied;
    private int debugEventCounter;
    private String lastDebugChatSignature;
    private String lastReceiveFallbackDecision = "none";
    private long joinTimeMs;
    private String pendingFallbackMessage;
    private long pendingFallbackCapturedAtMs;

    public AutoLogin() {
        super(AddonTemplate.CATEGORY, "auto-login", "Automatically sends saved /login or /reg commands matched by current username and server.");
    }

    @Override public void onActivate() { resetJoinState(); syncController.onActivate(); ensureJoinContextInitialized("activate"); if (debugClipboard.get() && mc.player != null && mc.world != null) startDebugSession(); }
    @Override public void onDeactivate() { resetJoinState(); syncController.onDeactivate(); }
    @Override public NbtCompound toTag() { return profileStore.writeToTag(super.toTag()); }
    @Override public Module fromTag(NbtCompound tag) { super.fromTag(tag); profileStore.loadFromTag(tag); return this; }
    @Override public WWidget getWidget(GuiTheme theme) { return profileStore.createWidget(theme, this::currentUsername, this::currentServerKey, () -> newEntryDelay.get()); }

    @EventHandler private void onGameJoined(GameJoinedEvent event) { CrashGuard.run(this, "onGameJoined", () -> { resetJoinState(); joinTimeMs = System.currentTimeMillis(); startDebugSession(); }); }
    @EventHandler private void onGameLeft(GameLeftEvent event) { CrashGuard.run(this, "onGameLeft", () -> { appendDebugLine("session-end"); resetJoinState(); }); }
    @EventHandler private void onTick(TickEvent.Post event) { CrashGuard.run(this, "onTickPost", this::handleAutoLoginTick); }
    @EventHandler private void onPacketSend(PacketEvent.Send event) { CrashGuard.run(this, "onPacketSend", () -> handlePacketSend(event)); }
    @EventHandler private void onReceiveMessage(ReceiveMessageEvent event) { CrashGuard.run(this, "onReceiveMessage", () -> handleReceiveMessage(event)); }
    @EventHandler private void onPacketReceive(PacketEvent.Receive event) { CrashGuard.run(this, "onPacketReceive", () -> handlePacketReceive(event)); }

    private void handleAutoLoginTick() {
        syncController.onTick();
        ensureJoinContextInitialized("tick");
        if (!sentThisJoin) processPendingReceiveFallback();
        if (sentThisJoin || pendingProfile == null || mc.player == null || mc.world == null || mc.player.networkHandler == null) return;
        if (promptWorldTime == -1) promptWorldTime = mc.world.getTime();
        if (mc.world.getTime() < promptWorldTime + pendingProfile.delay.get()) return;
        appendDebugState("before-send");
        appendDebugLine("auto-send command=" + quoteForDebug(AutoLoginTextRules.composeCommand(pendingProfile.mode.get(), pendingProfile.password.get())));
        sendProfileCommand(pendingProfile);
        pendingProfile = null;
        promptWorldTime = -1;
        sentThisJoin = true;
    }

    private void handlePacketReceive(PacketEvent.Receive event) {
        trackKnownPlayers(event);
        DebugChatPacketSnapshot snapshot = captureChatPacketSnapshot(event);
        if (snapshot != null && snapshot.trustedAuthOrigin()) tryScheduleAutoLogin(snapshot.message());
    }

    private void handleReceiveMessage(ReceiveMessageEvent event) {
        ensureDebugSessionStarted();
        String message = event.getMessage() == null ? null : event.getMessage().getString();
        if (isOwnDebugMessage(message)) return;
        DebugChatPacketSnapshot snapshot = findMatchingPacketSnapshot(message);
        captureDebugMessage(event, message, snapshot);
        processReceiveFallbackCandidate(message);
        if (debugClipboard.get() && AutoLoginTextRules.looksLikeAuthPrompt(message)) {
            emitLoginDebugToChat(event, message, snapshot);
            finishDebugCapture(snapshot != null && snapshot.trustedAuthOrigin() ? "trusted-auth-message" : "auth-message");
        }
    }

    private void tryScheduleAutoLogin(String message) {
        String username = currentUsername();
        String server = currentServerKey();
        AutoLoginProfile profile = profileStore.findMatchingProfile(username, server);
        if (sentThisJoin || mc.player == null || mc.world == null || mc.player.networkHandler == null) return;
        if (onlyMultiplayer.get() && mc.isInSingleplayer()) return;
        if (username.isBlank() || server.isBlank() || pendingProfile != null || profile == null) return;
        if (message == null || message.isBlank() || !AutoLoginTextRules.isAuthRequest(message, profile.mode.get())) return;
        pendingProfile = profile;
        promptWorldTime = mc.world.getTime();
        appendDebugLine("schedule-auth message=" + quoteForDebug(message));
        appendProfileSnapshot("scheduled-profile", profile);
        appendDebugState("scheduled");
    }

    private void handlePacketSend(PacketEvent.Send event) {
        if (!autoSave.get() || sendingAutoCommand) return;
        if (!(event.packet instanceof CommandExecutionC2SPacket(String command))) return;
        ParsedCommand parsed = AutoLoginTextRules.parseCredentialCommand(command);
        if (parsed == null) return;
        String username = currentUsername();
        String server = currentServerKey();
        if (username.isBlank() || server.isBlank()) return;
        profileStore.upsertProfile(username, server, parsed, newEntryDelay.get());
    }

    private void sendProfileCommand(AutoLoginProfile profile) {
        String command = AutoLoginTextRules.composeCommand(profile.mode.get(), profile.password.get());
        if (command == null || command.isBlank() || mc.player == null || mc.player.networkHandler == null) return;
        sendingAutoCommand = true;
        try { mc.player.networkHandler.sendChatCommand(command.substring(1)); }
        finally { sendingAutoCommand = false; }
    }
    private DebugChatPacketSnapshot captureChatPacketSnapshot(PacketEvent.Receive event) {
        ensureDebugSessionStarted();
        if (event.packet instanceof GameMessageS2CPacket packet) {
            if (packet.overlay()) return null;
            return recordChatPacket(new DebugChatPacketSnapshot(event.packet.getClass().getSimpleName(), packet.content().getString(), null, null, packet.overlay(), AutoLoginTextRules.isTrustedSystemAuthPacketMessage(packet.content().getString()), null));
        }
        if (event.packet instanceof ProfilelessChatMessageS2CPacket packet) {
            return recordChatPacket(new DebugChatPacketSnapshot(event.packet.getClass().getSimpleName(), packet.message().getString(), null, null, null, AutoLoginTextRules.isTrustedSystemAuthPacketMessage(packet.message().getString()), String.valueOf(packet.chatType())));
        }
        if (event.packet instanceof ChatMessageS2CPacket packet) {
            String packetMessage = packet.unsignedContent() != null && !packet.unsignedContent().getString().isBlank() ? packet.unsignedContent().getString() : packet.body().content();
            return recordChatPacket(new DebugChatPacketSnapshot(event.packet.getClass().getSimpleName(), packetMessage, getChatPacketSenderName(packet) + " [" + packet.sender() + "]", isSenderInTabList(packet), null, false, String.valueOf(packet.serializedParameters())));
        }
        return null;
    }

    private DebugChatPacketSnapshot recordChatPacket(DebugChatPacketSnapshot snapshot) { addRecentChatPacket(snapshot); captureDebugPacket(snapshot); return snapshot; }

    private void trackKnownPlayers(PacketEvent.Receive event) {
        if (event.packet instanceof PlayerListS2CPacket packet) {
            if (!packet.getActions().contains(PlayerListS2CPacket.Action.ADD_PLAYER)) return;
            for (PlayerListS2CPacket.Entry entry : packet.getPlayerAdditionEntries()) {
                if (entry.profile() == null || entry.profile().name() == null) continue;
                knownPlayers.put(entry.profile().id(), entry.profile().name());
            }
            return;
        }
        if (event.packet instanceof PlayerRemoveS2CPacket packet) for (UUID playerId : packet.profileIds()) knownPlayers.remove(playerId);
    }

    private String getChatPacketSenderName(ChatMessageS2CPacket packet) {
        if (packet.serializedParameters() != null && packet.serializedParameters().name() != null) {
            String value = packet.serializedParameters().name().getString();
            if (value != null && !value.isBlank()) return value;
        }
        if (mc.player != null && mc.player.networkHandler != null) {
            var entry = mc.player.networkHandler.getPlayerListEntry(packet.sender());
            if (entry != null && entry.getProfile() != null && entry.getProfile().name() != null) return entry.getProfile().name();
        }
        return "";
    }

    private boolean isSenderInTabList(ChatMessageS2CPacket packet) {
        return mc.player != null && mc.player.networkHandler != null && mc.player.networkHandler.getPlayerListEntry(packet.sender()) != null;
    }

    private void resetJoinState() {
        sentThisJoin = false;
        sendingAutoCommand = false;
        promptWorldTime = -1;
        pendingProfile = null;
        joinTimeMs = 0;
        debugCaptureLines.clear();
        debugEventCounter = 0;
        debugSessionActive = false;
        debugDumpCopied = false;
        recentChatPackets.clear();
        lastDebugChatSignature = null;
        lastReceiveFallbackDecision = "none";
        knownPlayers.clear();
        pendingFallbackMessage = null;
        pendingFallbackCapturedAtMs = 0;
    }

    private void startDebugSession() {
        if (!debugClipboard.get()) return;
        debugCaptureLines.clear();
        debugEventCounter = 0;
        debugSessionActive = true;
        debugDumpCopied = false;
        recentChatPackets.clear();
        appendDebugLine("session-start");
        appendDebugLine("username=" + quoteForDebug(currentUsername()));
        appendDebugLine("server=" + quoteForDebug(currentServerKey()));
        appendDebugLine("only-multiplayer=" + onlyMultiplayer.get());
        appendDebugLine("new-entry-delay=" + newEntryDelay.get());
        appendProfileSnapshot("matched-profile-on-join", profileStore.findMatchingProfile(currentUsername(), currentServerKey()));
    }

    private void captureDebugPacket(DebugChatPacketSnapshot snapshot) {
        if (!debugSessionActive || debugDumpCopied) return;
        appendDebugLine("packet#" + (++debugEventCounter) + " type=" + snapshot.packetType());
        appendDebugLine("packet-message=" + quoteForDebug(snapshot.message()));
        appendDebugLine("packet-normalized=" + quoteForDebug(AutoLoginTextRules.normalizeMessage(snapshot.message())));
        if (snapshot.sender() != null) appendDebugLine("packet-sender=" + snapshot.sender());
        if (snapshot.senderInTab() != null) appendDebugLine("packet-sender-in-tab=" + snapshot.senderInTab());
        appendDebugLine("packet-trusted-auth-origin=" + snapshot.trustedAuthOrigin());
        if (snapshot.overlay() != null) appendDebugLine("packet-overlay=" + snapshot.overlay());
        if (snapshot.extra() != null && !snapshot.extra().isBlank()) appendDebugLine("packet-extra=" + snapshot.extra());
    }

    private void captureDebugMessage(ReceiveMessageEvent event, String message, DebugChatPacketSnapshot snapshot) {
        if (!debugSessionActive || debugDumpCopied) return;
        appendDebugLine("chat#" + (++debugEventCounter) + " message=" + quoteForDebug(message));
        appendDebugLine("chat-normalized=" + quoteForDebug(AutoLoginTextRules.normalizeMessage(message)));
        appendDebugLine("chat-indicator=" + String.valueOf(event.getIndicator()));
        appendDebugLine("chat-id=" + event.id);
        if (snapshot != null) {
            appendDebugLine("chat-packet-type=" + snapshot.packetType());
            appendDebugLine("chat-packet-message=" + quoteForDebug(snapshot.message()));
            appendDebugLine("chat-packet-normalized=" + quoteForDebug(AutoLoginTextRules.normalizeMessage(snapshot.message())));
            if (snapshot.sender() != null) appendDebugLine("chat-packet-sender=" + snapshot.sender());
            if (snapshot.senderInTab() != null) appendDebugLine("chat-packet-sender-in-tab=" + snapshot.senderInTab());
            appendDebugLine("chat-packet-trusted-auth-origin=" + snapshot.trustedAuthOrigin());
            if (snapshot.overlay() != null) appendDebugLine("chat-packet-overlay=" + snapshot.overlay());
            if (snapshot.extra() != null && !snapshot.extra().isBlank()) appendDebugLine("chat-packet-extra=" + snapshot.extra());
        }
        AutoLoginProfile profile = profileStore.findMatchingProfile(currentUsername(), currentServerKey());
        appendDebugLine("profile-found=" + (profile != null));
        appendProfileSnapshot("matched-profile-at-message", profile);
        if (profile != null) appendDebugLine("auth-match=" + AutoLoginTextRules.isAuthRequest(message, profile.mode.get()));
        appendDebugState("message");
    }

    private void finishDebugCapture(String reason) {
        if (!debugSessionActive || debugDumpCopied || mc.keyboard == null) return;
        appendDebugLine("finish-reason=" + reason);
        appendDebugState("finish");
        mc.keyboard.setClipboard(String.join(System.lineSeparator(), debugCaptureLines));
        debugDumpCopied = true;
    }

    private void appendDebugState(String source) {
        if (!debugSessionActive || debugDumpCopied) return;
        appendDebugLine("state-source=" + source);
        appendDebugLine("state-sent-this-join=" + sentThisJoin);
        appendDebugLine("state-pending-profile=" + (pendingProfile != null));
        if (pendingProfile != null) appendProfileSnapshot("state-pending", pendingProfile);
        appendDebugLine("state-prompt-world-time=" + promptWorldTime);
        appendDebugLine("state-world-time=" + (mc.world == null ? -1 : mc.world.getTime()));
    }
    private void appendProfileSnapshot(String prefix, AutoLoginProfile profile) {
        if (!debugSessionActive || debugDumpCopied) return;
        if (profile == null) { appendDebugLine(prefix + "=<null>"); return; }
        appendDebugLine(prefix + "-enabled=" + profile.enabled.get());
        appendDebugLine(prefix + "-username=" + quoteForDebug(profile.username.get()));
        appendDebugLine(prefix + "-server=" + quoteForDebug(profile.server.get()));
        appendDebugLine(prefix + "-mode=" + profile.mode.get());
        appendDebugLine(prefix + "-delay=" + profile.delay.get());
    }

    private void appendDebugLine(String line) { if (!debugSessionActive || debugDumpCopied) return; debugCaptureLines.add(line); while (debugCaptureLines.size() > 400) debugCaptureLines.remove(0); }

    private void emitLoginDebugToChat(ReceiveMessageEvent event, String message, DebugChatPacketSnapshot snapshot) {
        if (debugDumpCopied) return;
        String signature = AutoLoginTextRules.stripLeadingAngleTags(AutoLoginTextRules.normalizeMessage(message));
        if (signature.equals(lastDebugChatSignature)) return;
        lastDebugChatSignature = signature;

        Set<String> onlineNames = getKnownOnlinePlayerNamesNormalized();
        AutoLoginProfile profile = profileStore.findMatchingProfile(currentUsername(), currentServerKey());
        boolean authMatch = profile != null && AutoLoginTextRules.isAuthRequest(message, profile.mode.get());
        long now = System.currentTimeMillis();
        long joinAgeMs = joinTimeMs <= 0 ? -1 : now - joinTimeMs;
        long pendingAgeMs = pendingFallbackCapturedAtMs <= 0 ? -1 : now - pendingFallbackCapturedAtMs;

        String summary = "AutoLogin DBG"
            + " eventId=" + event.id
            + " indicator=" + (event.getIndicator() == null ? "<null>" : event.getIndicator())
            + " msg=" + inlineValue(message, 160)
            + " norm=" + inlineValue(AutoLoginTextRules.normalizeMessage(message), 160)
            + " packetType=" + (snapshot == null ? "<none>" : snapshot.packetType())
            + " packetTrusted=" + (snapshot != null && snapshot.trustedAuthOrigin())
            + " packetSender=" + inlineValue(snapshot == null ? "<none>" : snapshot.sender(), 80)
            + " packetSenderInTab=" + (snapshot == null || snapshot.senderInTab() == null ? "<null>" : snapshot.senderInTab())
            + " packetExtra=" + inlineValue(snapshot == null ? "<none>" : snapshot.extra(), 80)
            + " onlineCount=" + onlineNames.size()
            + " fallbackDecision=" + lastReceiveFallbackDecision
            + " profile=" + (profile != null)
            + " profileMode=" + (profile == null ? "<none>" : profile.mode.get())
            + " profileDelay=" + (profile == null ? -1 : profile.delay.get())
            + " authMatch=" + authMatch
            + " sentThisJoin=" + sentThisJoin
            + " pendingProfile=" + (pendingProfile != null)
            + " pendingReceive=" + (pendingFallbackMessage != null)
            + " pendingReceiveAgeMs=" + pendingAgeMs
            + " promptWorldTime=" + promptWorldTime
            + " worldTime=" + (mc.world == null ? -1 : mc.world.getTime())
            + " joinAgeMs=" + joinAgeMs
            + " syncEnabled=" + syncController.isEnabled()
            + " syncInFlight=" + syncController.isInFlight()
            + " syncStreamConnected=" + syncController.isStreamConnected()
            + " syncStreamConnecting=" + syncController.isStreamConnecting()
            + " syncStreamPending=" + syncController.isStreamPending()
            + " syncStreamRev=" + syncController.streamPendingRevision()
            + " syncStatus=" + inlineValue(syncController.status(), 60)
            + " syncRev=" + syncController.knownRevision()
            + " clipboardWillCopy=true";

        info("%s", summary);
    }

    private String inlineValue(String value, int maxLength) {
        if (value == null) return "<null>";
        String compact = value.replace('\n', ' ').replace('\r', ' ').trim();
        if (compact.isEmpty()) return "\"\"";
        if (compact.length() > maxLength) compact = compact.substring(0, Math.max(3, maxLength - 3)) + "...";
        return "\"" + compact + "\"";
    }

    private void ensureDebugSessionStarted() { if (debugClipboard.get() && !debugSessionActive) startDebugSession(); }
    private void addRecentChatPacket(DebugChatPacketSnapshot snapshot) { recentChatPackets.addLast(snapshot); while (recentChatPackets.size() > 32) recentChatPackets.removeFirst(); }

    private DebugChatPacketSnapshot findMatchingPacketSnapshot(String message) {
        if (AutoLoginTextRules.normalizeMessage(message).isEmpty()) return null;
        Iterator<DebugChatPacketSnapshot> iterator = recentChatPackets.descendingIterator();
        while (iterator.hasNext()) {
            DebugChatPacketSnapshot snapshot = iterator.next();
            if (AutoLoginTextRules.debugMessagesMatch(message, snapshot.message())) return snapshot;
        }
        return null;
    }

    private String quoteForDebug(String value) { return value == null ? "<null>" : "\"" + value.replace("\n", "\\n").replace("\r", "\\r") + "\""; }
    private String currentUsername() { return mc.getSession() == null || mc.getSession().getUsername() == null ? "" : mc.getSession().getUsername().trim(); }
    private String currentServerKey() {
        if (mc.getCurrentServerEntry() != null && mc.getCurrentServerEntry().address != null) {
            String address = mc.getCurrentServerEntry().address.trim();
            if (!address.isEmpty()) return address;
        }
        String worldName = Utils.getWorldName();
        return worldName == null ? "" : worldName.trim();
    }

    private Set<String> getKnownOnlinePlayerNamesNormalized() {
        Set<String> names = new HashSet<>();
        for (String name : knownPlayers.values()) if (name != null && !name.isBlank()) names.add(AutoLoginTextRules.normalizeKey(name));
        if (mc.player == null || mc.player.networkHandler == null) return names;
        for (var entry : mc.player.networkHandler.getPlayerList()) {
            if (entry == null || entry.getProfile() == null || entry.getProfile().name() == null) continue;
            names.add(AutoLoginTextRules.normalizeKey(entry.getProfile().name()));
        }
        return names;
    }

    public static String[] getAccountOptions() { return AutoLoginTextRules.getAccountOptions(); }
    public static String[] getServerOptions(String currentServer, String selectedServer) { return AutoLoginTextRules.getServerOptions(currentServer, selectedServer); }
    private boolean isOwnDebugMessage(String message) { return AutoLoginTextRules.normalizeMessage(message).contains(AutoLoginTextRules.normalizeKey(DEBUG_CHAT_MARKER)); }

    private void processReceiveFallbackCandidate(String message) {
        if (message == null || message.isBlank()) { lastReceiveFallbackDecision = "skip:message-empty"; return; }
        if (!AutoLoginTextRules.looksLikeAuthPrompt(message)) { lastReceiveFallbackDecision = "skip:not-auth-prompt"; return; }
        ensureJoinContextInitialized("receive");
        if (joinTimeMs <= 0 || System.currentTimeMillis() - joinTimeMs > RECEIVE_FALLBACK_WINDOW_MS) { lastReceiveFallbackDecision = "skip:outside-receive-window"; return; }
        Set<String> onlineNames = getKnownOnlinePlayerNamesNormalized();
        if (onlineNames.isEmpty()) {
            pendingFallbackMessage = message;
            pendingFallbackCapturedAtMs = System.currentTimeMillis();
            appendDebugLine("receive-fallback-pending=online-list-not-ready");
            lastReceiveFallbackDecision = "pending:online-list-not-ready";
            return;
        }
        boolean trusted = AutoLoginTextRules.isReceiveFallbackMessageTrusted(message, onlineNames);
        appendDebugLine("receive-fallback-trusted=" + trusted);
        if (trusted) { tryScheduleAutoLogin(message); lastReceiveFallbackDecision = "trusted:scheduled-or-attempted"; }
        else lastReceiveFallbackDecision = "rejected:untrusted";
        clearPendingReceiveFallback();
    }

    private void processPendingReceiveFallback() {
        if (pendingFallbackMessage == null || pendingFallbackMessage.isBlank()) return;
        long now = System.currentTimeMillis();
        if (pendingFallbackCapturedAtMs <= 0 || now - pendingFallbackCapturedAtMs > RECEIVE_FALLBACK_ONLINE_WAIT_MS) {
            appendDebugLine("receive-fallback-pending-expired=true");
            lastReceiveFallbackDecision = "pending:expired";
            clearPendingReceiveFallback();
            return;
        }
        Set<String> onlineNames = getKnownOnlinePlayerNamesNormalized();
        if (onlineNames.isEmpty()) { lastReceiveFallbackDecision = "pending:waiting-online-list"; return; }
        boolean trusted = AutoLoginTextRules.isReceiveFallbackMessageTrusted(pendingFallbackMessage, onlineNames);
        appendDebugLine("receive-fallback-pending-resolved=" + trusted);
        if (trusted) { tryScheduleAutoLogin(pendingFallbackMessage); lastReceiveFallbackDecision = "pending:resolved-trusted"; }
        else lastReceiveFallbackDecision = "pending:resolved-untrusted";
        clearPendingReceiveFallback();
    }

    private void clearPendingReceiveFallback() { pendingFallbackMessage = null; pendingFallbackCapturedAtMs = 0; }
    private void ensureJoinContextInitialized(String source) { if (joinTimeMs > 0 || mc.player == null || mc.world == null) return; joinTimeMs = System.currentTimeMillis(); appendDebugLine("join-time-initialized source=" + source + " world-time=" + mc.world.getTime()); }

    public boolean saveLoginProfile(String username, String server, String password) {
        if (username == null || username.isBlank() || server == null || server.isBlank() || password == null || password.isBlank()) return false;
        profileStore.upsertProfile(username, server, LoginMode.LOGIN, password, newEntryDelay.get());
        return true;
    }

    public static ParsedCommand parseCredentialCommand(String rawCommand) { return AutoLoginTextRules.parseCredentialCommand(rawCommand); }
    public static boolean matchesKey(String storedUsername, String storedServer, String currentUsername, String currentServer) { return AutoLoginTextRules.matchesKey(storedUsername, storedServer, currentUsername, currentServer); }
    public static boolean isAuthRequest(String message, LoginMode mode) { return AutoLoginTextRules.isAuthRequest(message, mode); }
    public static boolean debugMessagesMatch(String chatMessage, String packetMessage) { return AutoLoginTextRules.debugMessagesMatch(chatMessage, packetMessage); }
    public static boolean looksLikeAuthPrompt(String message) { return AutoLoginTextRules.looksLikeAuthPrompt(message); }
    public static boolean isTrustedAuthPacketMessage(String message, Set<String> onlinePlayerNames) { return AutoLoginTextRules.isTrustedAuthPacketMessage(message, onlinePlayerNames); }
    public static boolean isReceiveFallbackMessageTrusted(String message, Set<String> onlinePlayerNames) { return AutoLoginTextRules.isReceiveFallbackMessageTrusted(message, onlinePlayerNames); }
    public static String composeCommand(LoginMode mode, String password) { return AutoLoginTextRules.composeCommand(mode, password); }

    public record ParsedCommand(LoginMode mode, String password) {}
    public enum LoginMode { LOGIN, REGISTER }
}


