package com.example.addon.modules;

import com.example.addon.AddonTemplate;
import com.example.addon.gui.screens.settings.AutoLoginEditScreen;
import com.example.addon.util.CrashGuard;
import meteordevelopment.meteorclient.events.game.GameJoinedEvent;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.game.ReceiveMessageEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.gui.widgets.containers.WContainer;
import meteordevelopment.meteorclient.gui.widgets.containers.WTable;
import meteordevelopment.meteorclient.gui.widgets.containers.WVerticalList;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.gui.widgets.pressable.WMinus;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.Settings;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.systems.accounts.Account;
import meteordevelopment.meteorclient.systems.accounts.Accounts;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.misc.ISerializable;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.client.option.ServerList;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.network.packet.c2s.play.CommandExecutionC2SPacket;
import net.minecraft.network.packet.s2c.play.ChatMessageS2CPacket;
import net.minecraft.network.packet.s2c.play.GameMessageS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerListS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerRemoveS2CPacket;
import net.minecraft.network.packet.s2c.play.ProfilelessChatMessageS2CPacket;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import static meteordevelopment.meteorclient.MeteorClient.mc;
import static meteordevelopment.meteorclient.gui.renderer.GuiRenderer.COPY;

public class AutoLogin extends Module {
    private static final String DEBUG_CHAT_MARKER = "AutoLogin DBG";
    private static final Pattern PLAYER_NAME_TOKEN = Pattern.compile("[a-z0-9_]{3,16}");
    private static final Pattern LOGIN_COMMAND_PATTERN = Pattern.compile("(?<![a-z0-9_])/login(?![a-z0-9_])");
    private static final Pattern REGISTER_COMMAND_PATTERN = Pattern.compile("(?<![a-z0-9_])/register(?![a-z0-9_])");
    private static final Pattern REG_COMMAND_PATTERN = Pattern.compile("(?<![a-z0-9_])/reg(?![a-z0-9_])");
    private static final Pattern LEGACY_COLOR_CODE_PATTERN = Pattern.compile("(?i)[§&][0-9A-FK-ORX]");
    private static final Pattern HEX_COLOR_CODE_PATTERN = Pattern.compile("(?i)&#[0-9a-f]{6}");
    private static final long RECEIVE_FALLBACK_WINDOW_MS = 120_000;
    private static final long RECEIVE_FALLBACK_ONLINE_WAIT_MS = 5_000;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> autoSave = sgGeneral.add(new BoolSetting.Builder().name("auto-save").description("Save credentials automatically when you manually use /login or /reg.").defaultValue(true).build());
    private final Setting<Integer> newEntryDelay = sgGeneral.add(new IntSetting.Builder().name("new-entry-delay").description("Default delay in ticks for newly saved entries.").defaultValue(40).min(0).sliderRange(0, 200).build());
    private final Setting<Boolean> onlyMultiplayer = sgGeneral.add(new BoolSetting.Builder().name("only-multiplayer").description("Only auto-login on multiplayer servers.").defaultValue(true).build());
    private final Setting<Boolean> debugClipboard = sgGeneral.add(new BoolSetting.Builder().name("debug-clipboard").description("Copies auth-like incoming message diagnostics to the clipboard.").defaultValue(false).build());

    private final List<AutoLoginProfile> profiles = new ArrayList<>();
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

    @Override
    public void onActivate() {
        resetJoinState();
        ensureJoinContextInitialized("activate");
        if (debugClipboard.get() && mc.player != null && mc.world != null) startDebugSession();
    }

    @Override
    public void onDeactivate() { resetJoinState(); }

    @Override
    public NbtCompound toTag() {
        NbtCompound tag = super.toTag();
        NbtList list = new NbtList();
        for (AutoLoginProfile profile : profiles) {
            NbtCompound entryTag = new NbtCompound();
            entryTag.put("profile", profile.toTag());
            list.add(entryTag);
        }
        tag.put("profiles", list);
        return tag;
    }

    @Override
    public Module fromTag(NbtCompound tag) {
        super.fromTag(tag);
        profiles.clear();
        NbtList list = tag.getListOrEmpty("profiles");
        for (NbtElement element : list) {
            if (!(element instanceof NbtCompound entryTag)) continue;
            if (!(entryTag.get("profile") instanceof NbtCompound profileTag)) continue;
            AutoLoginProfile profile = new AutoLoginProfile();
            profile.fromTag(profileTag);
            profiles.add(profile);
        }
        return this;
    }

    @Override
    public WWidget getWidget(GuiTheme theme) {
        WVerticalList list = theme.verticalList();
        fillWidget(theme, list);
        return list;
    }

    private void fillWidget(GuiTheme theme, WVerticalList list) {
        list.clear();
        WTable table = list.add(theme.table()).expandX().widget();
        for (AutoLoginProfile profile : profiles) {
            String username = profile.username.get().isBlank() ? "(username not set)" : profile.username.get();
            String server = profile.server.get().isBlank() ? "(server not set)" : profile.server.get();
            String status = profile.enabled.get() ? "[on]" : "[off]";
            table.add(theme.label(status + " " + username)).expandX();
            table.add(theme.label(server)).expandX();
            WButton edit = table.add(theme.button("Edit")).widget();
            edit.action = () -> {
                AutoLoginEditScreen screen = new AutoLoginEditScreen(theme, profile, this::currentUsername, this::currentServerKey);
                screen.onClosed(() -> fillWidget(theme, list));
                mc.setScreen(screen);
            };
            WButton copy = table.add(theme.button(COPY)).widget();
            copy.action = () -> { profiles.add(profiles.indexOf(profile), profile.copy()); fillWidget(theme, list); };
            WMinus remove = table.add(theme.minus()).widget();
            remove.action = () -> { profiles.remove(profile); fillWidget(theme, list); };
            table.row();
        }
        if (!profiles.isEmpty()) list.add(theme.horizontalSeparator()).expandX();
        WContainer controls = list.add(theme.horizontalList()).expandX().widget();
        WButton add = controls.add(theme.button("New Entry")).expandX().widget();
        add.action = () -> {
            AutoLoginProfile profile = new AutoLoginProfile();
            profile.delay.set(newEntryDelay.get());
            String username = currentUsername();
            String server = currentServerKey();
            if (!username.isBlank()) profile.username.set(username);
            if (!server.isBlank()) profile.server.set(server);
            profiles.add(profile);
            fillWidget(theme, list);
        };
        WButton removeAll = controls.add(theme.button("Remove All")).expandX().widget();
        removeAll.action = () -> { profiles.clear(); fillWidget(theme, list); };
    }

    @EventHandler private void onGameJoined(GameJoinedEvent event) { CrashGuard.run(this, "onGameJoined", () -> { resetJoinState(); joinTimeMs = System.currentTimeMillis(); startDebugSession(); }); }
    @EventHandler private void onGameLeft(GameLeftEvent event) { CrashGuard.run(this, "onGameLeft", () -> { appendDebugLine("session-end"); resetJoinState(); }); }
    @EventHandler private void onTick(TickEvent.Post event) { CrashGuard.run(this, "onTickPost", this::handleAutoLoginTick); }
    @EventHandler private void onPacketSend(PacketEvent.Send event) { CrashGuard.run(this, "onPacketSend", () -> handlePacketSend(event)); }
    @EventHandler private void onReceiveMessage(ReceiveMessageEvent event) { CrashGuard.run(this, "onReceiveMessage", () -> handleReceiveMessage(event)); }
    @EventHandler private void onPacketReceive(PacketEvent.Receive event) { CrashGuard.run(this, "onPacketReceive", () -> handlePacketReceive(event)); }

    private void handleAutoLoginTick() {
        ensureJoinContextInitialized("tick");
        if (!sentThisJoin) processPendingReceiveFallback();
        if (sentThisJoin || pendingProfile == null || mc.player == null || mc.world == null || mc.player.networkHandler == null) return;
        if (promptWorldTime == -1) promptWorldTime = mc.world.getTime();
        if (mc.world.getTime() < promptWorldTime + pendingProfile.delay.get()) return;
        appendDebugState("before-send");
        appendDebugLine("auto-send command=" + quoteForDebug(composeCommand(pendingProfile.mode.get(), pendingProfile.password.get())));
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
        if (debugClipboard.get() && looksLikeAuthPrompt(message)) {
            emitLoginDebugToChat(event, message, snapshot);
            finishDebugCapture(snapshot != null && snapshot.trustedAuthOrigin() ? "trusted-auth-message" : "auth-message");
        }
    }

    private void tryScheduleAutoLogin(String message) {
        String username = currentUsername();
        String server = currentServerKey();
        AutoLoginProfile profile = findMatchingProfile(username, server);
        if (sentThisJoin || mc.player == null || mc.world == null || mc.player.networkHandler == null) return;
        if (onlyMultiplayer.get() && mc.isInSingleplayer()) return;
        if (username.isBlank() || server.isBlank() || pendingProfile != null || profile == null) return;
        if (message == null || message.isBlank() || !isAuthRequest(message, profile.mode.get())) return;
        pendingProfile = profile;
        promptWorldTime = mc.world.getTime();
        appendDebugLine("schedule-auth message=" + quoteForDebug(message));
        appendProfileSnapshot("scheduled-profile", profile);
        appendDebugState("scheduled");
    }

    private void handlePacketSend(PacketEvent.Send event) {
        if (!autoSave.get() || sendingAutoCommand) return;
        if (!(event.packet instanceof CommandExecutionC2SPacket(String command))) return;
        ParsedCommand parsed = parseCredentialCommand(command);
        if (parsed == null) return;
        String username = currentUsername();
        String server = currentServerKey();
        if (username.isBlank() || server.isBlank()) return;
        upsertProfile(username, server, parsed);
    }

    private void sendProfileCommand(AutoLoginProfile profile) {
        String command = composeCommand(profile.mode.get(), profile.password.get());
        if (command == null || command.isBlank() || mc.player == null || mc.player.networkHandler == null) return;
        sendingAutoCommand = true;
        try { mc.player.networkHandler.sendChatCommand(command.substring(1)); }
        finally { sendingAutoCommand = false; }
    }

    private DebugChatPacketSnapshot captureChatPacketSnapshot(PacketEvent.Receive event) {
        ensureDebugSessionStarted();
        Set<String> onlinePlayerNames = getKnownOnlinePlayerNamesNormalized();
        if (event.packet instanceof GameMessageS2CPacket packet) {
            if (packet.overlay()) return null;
            return recordChatPacket(new DebugChatPacketSnapshot(event.packet.getClass().getSimpleName(), packet.content().getString(), null, null, packet.overlay(), isTrustedAuthPacketMessage(packet.content().getString(), onlinePlayerNames), null));
        }
        if (event.packet instanceof ProfilelessChatMessageS2CPacket packet) {
            return recordChatPacket(new DebugChatPacketSnapshot(event.packet.getClass().getSimpleName(), packet.message().getString(), null, null, null, isTrustedAuthPacketMessage(packet.message().getString(), onlinePlayerNames), String.valueOf(packet.chatType())));
        }
        if (event.packet instanceof ChatMessageS2CPacket packet) {
            String packetMessage = packet.unsignedContent() != null && !packet.unsignedContent().getString().isBlank() ? packet.unsignedContent().getString() : packet.body().content();
            boolean senderInTab = isSenderInTabList(packet);
            return recordChatPacket(new DebugChatPacketSnapshot(event.packet.getClass().getSimpleName(), packetMessage, getChatPacketSenderName(packet) + " [" + packet.sender() + "]", senderInTab, null, false, String.valueOf(packet.serializedParameters())));
        }
        return null;
    }

    private DebugChatPacketSnapshot recordChatPacket(DebugChatPacketSnapshot snapshot) {
        addRecentChatPacket(snapshot);
        captureDebugPacket(snapshot);
        return snapshot;
    }

    private void trackKnownPlayers(PacketEvent.Receive event) {
        if (event.packet instanceof PlayerListS2CPacket packet) {
            if (!packet.getActions().contains(PlayerListS2CPacket.Action.ADD_PLAYER)) return;
            for (PlayerListS2CPacket.Entry entry : packet.getPlayerAdditionEntries()) {
                if (entry.profile() == null || entry.profile().getName() == null) continue;
                knownPlayers.put(entry.profile().getId(), entry.profile().getName());
            }
            return;
        }
        if (event.packet instanceof PlayerRemoveS2CPacket packet) {
            for (UUID playerId : packet.profileIds()) knownPlayers.remove(playerId);
        }
    }

    private String getChatPacketSenderName(ChatMessageS2CPacket packet) {
        if (packet.serializedParameters() != null && packet.serializedParameters().name() != null) {
            String value = packet.serializedParameters().name().getString();
            if (value != null && !value.isBlank()) return value;
        }
        if (mc.player != null && mc.player.networkHandler != null) {
            var entry = mc.player.networkHandler.getPlayerListEntry(packet.sender());
            if (entry != null && entry.getProfile() != null && entry.getProfile().getName() != null) return entry.getProfile().getName();
        }
        return "";
    }

    private boolean isSenderInTabList(ChatMessageS2CPacket packet) {
        return mc.player != null && mc.player.networkHandler != null && mc.player.networkHandler.getPlayerListEntry(packet.sender()) != null;
    }

    private void upsertProfile(String username, String server, ParsedCommand parsed) {
        AutoLoginProfile existing = findMatchingProfile(username, server);
        if (existing == null) {
            AutoLoginProfile profile = new AutoLoginProfile();
            profile.enabled.set(true);
            profile.username.set(username);
            profile.server.set(server);
            profile.mode.set(parsed.mode());
            profile.password.set(parsed.password());
            profile.delay.set(newEntryDelay.get());
            profiles.add(profile);
            return;
        }
        existing.mode.set(parsed.mode());
        existing.password.set(parsed.password());
        existing.enabled.set(true);
    }

    private AutoLoginProfile findMatchingProfile(String username, String server) {
        for (AutoLoginProfile profile : profiles) {
            if (!profile.enabled.get()) continue;
            if (matchesKey(profile.username.get(), profile.server.get(), username, server)) return profile;
        }
        return null;
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
        appendProfileSnapshot("matched-profile-on-join", findMatchingProfile(currentUsername(), currentServerKey()));
    }

    private void captureDebugPacket(DebugChatPacketSnapshot snapshot) {
        if (!debugSessionActive || debugDumpCopied) return;
        appendDebugLine("packet#" + (++debugEventCounter) + " type=" + snapshot.packetType());
        appendDebugLine("packet-message=" + quoteForDebug(snapshot.message()));
        appendDebugLine("packet-normalized=" + quoteForDebug(normalizeMessage(snapshot.message())));
        if (snapshot.sender() != null) appendDebugLine("packet-sender=" + snapshot.sender());
        if (snapshot.senderInTab() != null) appendDebugLine("packet-sender-in-tab=" + snapshot.senderInTab());
        appendDebugLine("packet-trusted-auth-origin=" + snapshot.trustedAuthOrigin());
        if (snapshot.overlay() != null) appendDebugLine("packet-overlay=" + snapshot.overlay());
        if (snapshot.extra() != null && !snapshot.extra().isBlank()) appendDebugLine("packet-extra=" + snapshot.extra());
    }

    private void captureDebugMessage(ReceiveMessageEvent event, String message, DebugChatPacketSnapshot snapshot) {
        if (!debugSessionActive || debugDumpCopied) return;
        appendDebugLine("chat#" + (++debugEventCounter) + " message=" + quoteForDebug(message));
        appendDebugLine("chat-normalized=" + quoteForDebug(normalizeMessage(message)));
        appendDebugLine("chat-indicator=" + String.valueOf(event.getIndicator()));
        appendDebugLine("chat-id=" + event.id);
        if (snapshot != null) {
            appendDebugLine("chat-packet-type=" + snapshot.packetType());
            appendDebugLine("chat-packet-message=" + quoteForDebug(snapshot.message()));
            appendDebugLine("chat-packet-normalized=" + quoteForDebug(normalizeMessage(snapshot.message())));
            if (snapshot.sender() != null) appendDebugLine("chat-packet-sender=" + snapshot.sender());
            if (snapshot.senderInTab() != null) appendDebugLine("chat-packet-sender-in-tab=" + snapshot.senderInTab());
            appendDebugLine("chat-packet-trusted-auth-origin=" + snapshot.trustedAuthOrigin());
            if (snapshot.overlay() != null) appendDebugLine("chat-packet-overlay=" + snapshot.overlay());
            if (snapshot.extra() != null && !snapshot.extra().isBlank()) appendDebugLine("chat-packet-extra=" + snapshot.extra());
        }
        AutoLoginProfile profile = findMatchingProfile(currentUsername(), currentServerKey());
        appendDebugLine("profile-found=" + (profile != null));
        appendProfileSnapshot("matched-profile-at-message", profile);
        if (profile != null) appendDebugLine("auth-match=" + isAuthRequest(message, profile.mode.get()));
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
        if (profile == null) {
            appendDebugLine(prefix + "=<null>");
            return;
        }
        appendDebugLine(prefix + "-enabled=" + profile.enabled.get());
        appendDebugLine(prefix + "-username=" + quoteForDebug(profile.username.get()));
        appendDebugLine(prefix + "-server=" + quoteForDebug(profile.server.get()));
        appendDebugLine(prefix + "-mode=" + profile.mode.get());
        appendDebugLine(prefix + "-delay=" + profile.delay.get());
    }

    private void appendDebugLine(String line) {
        if (!debugSessionActive || debugDumpCopied) return;
        debugCaptureLines.add(line);
        while (debugCaptureLines.size() > 400) debugCaptureLines.remove(0);
    }

    private void emitLoginDebugToChat(ReceiveMessageEvent event, String message, DebugChatPacketSnapshot snapshot) {
        if (debugDumpCopied) return;

        String signature = stripLeadingAngleTags(normalizeMessage(message));
        if (signature.equals(lastDebugChatSignature)) return;
        lastDebugChatSignature = signature;

        String normalized = normalizeMessage(message);
        Set<String> onlineNames = getKnownOnlinePlayerNamesNormalized();
        AutoLoginProfile profile = findMatchingProfile(currentUsername(), currentServerKey());
        boolean authMatch = profile != null && isAuthRequest(message, profile.mode.get());
        String scheduleGate = evaluateScheduleGate(message, profile);
        String fallbackTrust = onlineNames.isEmpty() ? "online-empty" : String.valueOf(isReceiveFallbackMessageTrusted(message, onlineNames));
        long now = System.currentTimeMillis();
        long joinAgeMs = joinTimeMs <= 0 ? -1 : now - joinTimeMs;
        long pendingAgeMs = pendingFallbackCapturedAtMs <= 0 ? -1 : now - pendingFallbackCapturedAtMs;

        String summary = "AutoLogin DBG"
            + " eventId=" + event.id
            + " indicator=" + (event.getIndicator() == null ? "<null>" : event.getIndicator())
            + " msg=" + inlineValue(message, 160)
            + " norm=" + inlineValue(normalized, 160)
            + " packetType=" + (snapshot == null ? "<none>" : snapshot.packetType())
            + " packetTrusted=" + (snapshot != null && snapshot.trustedAuthOrigin())
            + " packetSender=" + inlineValue(snapshot == null ? "<none>" : snapshot.sender(), 80)
            + " packetSenderInTab=" + (snapshot == null || snapshot.senderInTab() == null ? "<null>" : snapshot.senderInTab())
            + " packetExtra=" + inlineValue(snapshot == null ? "<none>" : snapshot.extra(), 80)
            + " onlineCount=" + onlineNames.size()
            + " fallbackDecision=" + lastReceiveFallbackDecision
            + " fallbackTrustedNow=" + fallbackTrust
            + " profile=" + (profile != null)
            + " profileMode=" + (profile == null ? "<none>" : profile.mode.get())
            + " profileDelay=" + (profile == null ? -1 : profile.delay.get())
            + " authMatch=" + authMatch
            + " scheduleGate=" + scheduleGate
            + " sentThisJoin=" + sentThisJoin
            + " pendingProfile=" + (pendingProfile != null)
            + " pendingReceive=" + (pendingFallbackMessage != null)
            + " pendingReceiveAgeMs=" + pendingAgeMs
            + " promptWorldTime=" + promptWorldTime
            + " worldTime=" + (mc.world == null ? -1 : mc.world.getTime())
            + " joinAgeMs=" + joinAgeMs
            + " clipboardWillCopy=true";

        info("%s", summary);
    }

    private String evaluateScheduleGate(String message, AutoLoginProfile profile) {
        if (sentThisJoin) return "blocked:sent-this-join";
        if (mc.player == null || mc.world == null || mc.player.networkHandler == null) return "blocked:world-or-network-null";
        if (onlyMultiplayer.get() && mc.isInSingleplayer()) return "blocked:singleplayer";

        String username = currentUsername();
        String server = currentServerKey();
        if (username.isBlank()) return "blocked:username-empty";
        if (server.isBlank()) return "blocked:server-empty";
        if (pendingProfile != null) return "blocked:pending-profile-already-set";
        if (profile == null) return "blocked:no-matching-profile";
        if (message == null || message.isBlank()) return "blocked:message-empty";
        if (!isAuthRequest(message, profile.mode.get())) return "blocked:auth-command-mismatch";
        return "ready";
    }

    private String inlineValue(String value, int maxLength) {
        if (value == null) return "<null>";
        String compact = value.replace('\n', ' ').replace('\r', ' ').trim();
        if (compact.isEmpty()) return "\"\"";
        if (compact.length() > maxLength) compact = compact.substring(0, Math.max(3, maxLength - 3)) + "...";
        return "\"" + compact + "\"";
    }

    private void ensureDebugSessionStarted() {
        if (debugClipboard.get() && !debugSessionActive) startDebugSession();
    }

    private void addRecentChatPacket(DebugChatPacketSnapshot snapshot) {
        recentChatPackets.addLast(snapshot);
        while (recentChatPackets.size() > 32) recentChatPackets.removeFirst();
    }

    private DebugChatPacketSnapshot findMatchingPacketSnapshot(String message) {
        if (normalizeMessage(message).isEmpty()) return null;
        Iterator<DebugChatPacketSnapshot> iterator = recentChatPackets.descendingIterator();
        while (iterator.hasNext()) {
            DebugChatPacketSnapshot snapshot = iterator.next();
            if (debugMessagesMatch(message, snapshot.message())) return snapshot;
        }
        return null;
    }

    private String quoteForDebug(String value) {
        return value == null ? "<null>" : "\"" + value.replace("\n", "\\n").replace("\r", "\\r") + "\"";
    }

    private String currentUsername() {
        if (mc.getSession() == null || mc.getSession().getUsername() == null) return "";
        return mc.getSession().getUsername().trim();
    }

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
        for (String name : knownPlayers.values()) if (name != null && !name.isBlank()) names.add(normalizeKey(name));
        if (mc.player == null || mc.player.networkHandler == null) return names;
        for (var entry : mc.player.networkHandler.getPlayerList()) {
            if (entry == null || entry.getProfile() == null || entry.getProfile().getName() == null) continue;
            names.add(normalizeKey(entry.getProfile().getName()));
        }
        return names;
    }

    static ParsedCommand parseCredentialCommand(String rawCommand) {
        if (rawCommand == null) return null;
        String trimmed = rawCommand.trim();
        if (trimmed.isEmpty()) return null;
        if (trimmed.startsWith("/")) trimmed = trimmed.substring(1).trim();
        if (trimmed.isEmpty()) return null;
        String[] args = trimmed.split("\\s+");
        if (args.length < 2) return null;
        String command = args[0].toLowerCase(Locale.ROOT);
        return switch (command) {
            case "login" -> args.length == 2 ? new ParsedCommand(LoginMode.LOGIN, args[1]) : null;
            case "reg", "register" -> args.length == 3 && args[1].equals(args[2]) ? new ParsedCommand(LoginMode.REGISTER, args[1]) : null;
            default -> null;
        };
    }

    static boolean matchesKey(String storedUsername, String storedServer, String currentUsername, String currentServer) {
        return normalizeKey(storedUsername).equals(normalizeKey(currentUsername)) && normalizeServerKey(storedServer).equals(normalizeServerKey(currentServer));
    }

    static boolean isAuthRequest(String message, LoginMode mode) {
        if (message == null) return false;
        String normalized = normalizeMessage(message);
        if (normalized.isEmpty()) return false;
        return switch (mode) {
            case LOGIN -> containsLoginPrompt(normalized);
            case REGISTER -> containsRegisterPrompt(normalized);
        };
    }

    static boolean looksLikeAuthPrompt(String message) {
        String normalized = normalizeMessage(message);
        return containsLoginPrompt(normalized) || containsRegisterPrompt(normalized);
    }

    static boolean debugMessagesMatch(String chatMessage, String packetMessage) {
        String normalizedChat = normalizeMessage(chatMessage);
        String normalizedPacket = normalizeMessage(packetMessage);
        if (normalizedChat.isEmpty() || normalizedPacket.isEmpty()) return false;
        if (normalizedChat.equals(normalizedPacket)) return true;
        return stripLeadingAngleTags(normalizedChat).equals(stripLeadingAngleTags(normalizedPacket));
    }

    static boolean isTrustedAuthPacketMessage(String message, Set<String> onlinePlayerNames) {
        if (!looksLikeAuthPrompt(message)) return false;
        String normalized = normalizeMessage(message);
        String stripped = stripLeadingAngleTags(normalized);
        if (stripped.isEmpty() || !containsAuthContextNormalized(stripped)) return false;
        Set<String> onlineNames = onlinePlayerNames == null ? Set.of() : onlinePlayerNames;
        if (containsOnlinePlayerTokenBeforeAuthCommand(normalized, onlineNames)) return false;
        for (String tag : extractLeadingAngleTags(normalized)) {
            if (isLikelyPlayerNameToken(tag) && onlineNames.contains(normalizeKey(tag))) return false;
        }
        String relayPrefix = extractRelayPrefix(stripped);
        if (relayPrefix != null && onlineNames.contains(normalizeKey(relayPrefix))) return false;
        return true;
    }

    public static String[] getAccountOptions() {
        List<String> values = new ArrayList<>();
        for (Account<?> account : Accounts.get()) {
            String username = account.getUsername();
            if (username == null || username.isBlank()) continue;
            if (values.stream().noneMatch(existing -> existing.equalsIgnoreCase(username))) values.add(username);
        }
        return values.toArray(String[]::new);
    }

    public static String[] getServerOptions(String currentServer, String selectedServer) {
        Set<String> values = new LinkedHashSet<>();
        if (currentServer != null && !currentServer.isBlank()) values.add(currentServer.trim());
        try {
            ServerList serverList = new ServerList(meteordevelopment.meteorclient.MeteorClient.mc);
            serverList.loadFile();
            for (int i = 0; i < serverList.size(); i++) {
                ServerInfo info = serverList.get(i);
                if (info == null || info.address == null || info.address.isBlank()) continue;
                values.add(info.address.trim());
            }
        } catch (Throwable ignored) {
        }
        if (selectedServer != null && !selectedServer.isBlank()) values.add(selectedServer.trim());
        if (values.isEmpty()) values.add("");
        return values.toArray(String[]::new);
    }

    private boolean isOwnDebugMessage(String message) {
        return normalizeMessage(message).contains(normalizeKey(DEBUG_CHAT_MARKER));
    }

    private static String normalizeKey(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String normalizeServerKey(String value) {
        String normalized = normalizeKey(value);
        return normalized.endsWith(":25565") ? normalized.substring(0, normalized.length() - 6) : normalized;
    }

    private static String normalizeMessage(String value) {
        if (value == null) return "";
        String flattened = value.replace('\n', ' ').replace('\r', ' ');
        String noHex = HEX_COLOR_CODE_PATTERN.matcher(flattened).replaceAll("");
        String noLegacy = LEGACY_COLOR_CODE_PATTERN.matcher(noHex).replaceAll("");
        return noLegacy.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean containsLoginPrompt(String message) {
        return containsCommandToken(message, LOGIN_COMMAND_PATTERN);
    }

    private static boolean containsRegisterPrompt(String message) {
        return containsCommandToken(message, REGISTER_COMMAND_PATTERN) || containsCommandToken(message, REG_COMMAND_PATTERN);
    }

    private static boolean containsCommandToken(String message, Pattern pattern) {
        return message != null && !message.isEmpty() && pattern.matcher(message).find();
    }

    private static List<String> extractLeadingAngleTags(String message) {
        ArrayList<String> tags = new ArrayList<>();
        if (message == null || message.isBlank()) return tags;
        String remaining = message.trim();
        while (remaining.startsWith("<")) {
            int end = remaining.indexOf('>');
            if (end <= 1) break;
            tags.add(remaining.substring(1, end).trim());
            remaining = remaining.substring(end + 1).trim();
        }
        return tags;
    }

    private static String stripLeadingAngleTags(String message) {
        if (message == null || message.isBlank()) return "";
        String remaining = message.trim();
        while (remaining.startsWith("<")) {
            int end = remaining.indexOf('>');
            if (end <= 1) break;
            remaining = remaining.substring(end + 1).trim();
        }
        return remaining;
    }

    private static boolean isLikelyPlayerNameToken(String token) {
        return token != null && PLAYER_NAME_TOKEN.matcher(token.trim().toLowerCase(Locale.ROOT)).matches();
    }

    private static String extractRelayPrefix(String message) {
        if (message == null || message.isBlank()) return null;
        int arrowIndex = message.indexOf(">>");
        if (arrowIndex <= 0) return null;
        String prefix = message.substring(0, arrowIndex).trim();
        return isLikelyPlayerNameToken(prefix) ? prefix : null;
    }

    private void processReceiveFallbackCandidate(String message) {
        if (message == null || message.isBlank()) {
            lastReceiveFallbackDecision = "skip:message-empty";
            return;
        }
        if (!looksLikeAuthPrompt(message)) {
            lastReceiveFallbackDecision = "skip:not-auth-prompt";
            return;
        }
        ensureJoinContextInitialized("receive");
        if (joinTimeMs <= 0 || System.currentTimeMillis() - joinTimeMs > RECEIVE_FALLBACK_WINDOW_MS) {
            lastReceiveFallbackDecision = "skip:outside-receive-window";
            return;
        }

        Set<String> onlineNames = getKnownOnlinePlayerNamesNormalized();
        if (onlineNames.isEmpty()) {
            pendingFallbackMessage = message;
            pendingFallbackCapturedAtMs = System.currentTimeMillis();
            appendDebugLine("receive-fallback-pending=online-list-not-ready");
            lastReceiveFallbackDecision = "pending:online-list-not-ready";
            return;
        }

        boolean trusted = isReceiveFallbackMessageTrusted(message, onlineNames);
        appendDebugLine("receive-fallback-trusted=" + trusted);
        if (trusted) {
            tryScheduleAutoLogin(message);
            lastReceiveFallbackDecision = "trusted:scheduled-or-attempted";
        } else {
            lastReceiveFallbackDecision = "rejected:untrusted";
        }
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
        if (onlineNames.isEmpty()) {
            lastReceiveFallbackDecision = "pending:waiting-online-list";
            return;
        }

        boolean trusted = isReceiveFallbackMessageTrusted(pendingFallbackMessage, onlineNames);
        appendDebugLine("receive-fallback-pending-resolved=" + trusted);
        if (trusted) {
            tryScheduleAutoLogin(pendingFallbackMessage);
            lastReceiveFallbackDecision = "pending:resolved-trusted";
        } else {
            lastReceiveFallbackDecision = "pending:resolved-untrusted";
        }
        clearPendingReceiveFallback();
    }

    private void clearPendingReceiveFallback() {
        pendingFallbackMessage = null;
        pendingFallbackCapturedAtMs = 0;
    }

    private void ensureJoinContextInitialized(String source) {
        if (joinTimeMs > 0) return;
        if (mc.player == null || mc.world == null) return;
        joinTimeMs = System.currentTimeMillis();
        appendDebugLine("join-time-initialized source=" + source + " world-time=" + mc.world.getTime());
    }

    static boolean isReceiveFallbackMessageTrusted(String message, Set<String> onlinePlayerNames) {
        if (message == null || message.isBlank()) return false;
        if (onlinePlayerNames == null || onlinePlayerNames.isEmpty()) return false;

        String normalized = normalizeMessage(message);
        if (containsOnlinePlayerTokenBeforeAuthCommand(normalized, onlinePlayerNames)) return false;

        String stripped = stripLeadingAngleTags(normalized);
        if (stripped.isEmpty() || !looksLikeAuthPrompt(stripped) || !containsAuthContextNormalized(stripped)) return false;

        for (String tag : extractLeadingAngleTags(normalized)) {
            if (isLikelyPlayerNameToken(tag) && onlinePlayerNames.contains(normalizeKey(tag))) return false;
        }

        String relayPrefix = extractRelayPrefix(stripped);
        if (onlinePlayerNames.contains(normalizeKey(relayPrefix))) return false;
        return true;
    }

    private static boolean containsOnlinePlayerTokenBeforeAuthCommand(String normalizedMessage, Set<String> onlinePlayerNames) {
        if (normalizedMessage == null || normalizedMessage.isBlank() || onlinePlayerNames == null || onlinePlayerNames.isEmpty()) return false;

        int commandIndex = firstAuthCommandIndex(normalizedMessage);
        String prefix = commandIndex > 0 ? normalizedMessage.substring(0, commandIndex) : normalizedMessage;
        if (prefix.isBlank()) return false;

        String[] tokens = prefix.split("[^a-z0-9_]+");
        for (String token : tokens) {
            if (token.length() < 3) continue;
            if (onlinePlayerNames.contains(token)) return true;
        }

        return false;
    }

    private static int firstAuthCommandIndex(String message) {
        int index = -1;
        String[] commands = new String[] { "/login", "/register", "/reg" };
        for (String command : commands) {
            int candidate = message.indexOf(command);
            if (candidate == -1) continue;
            if (index == -1 || candidate < index) index = candidate;
        }
        return index;
    }

    private static boolean containsAuthContextNormalized(String message) {
        if (message == null || message.isBlank()) return false;
        return message.contains("login")
            || message.contains("register")
            || message.contains("log in")
            || message.contains("sign in")
            || message.contains("authoriz")
            || message.contains("auth")
            || message.contains("attempt")
            || message.contains("password")
            || message.contains("\u0430\u0432\u0442\u043e\u0440\u0438\u0437")
            || message.contains("\u0437\u0430\u0440\u0435\u0433\u0438\u0441\u0442\u0440")
            || message.contains("\u0432\u043e\u0439\u0434")
            || message.contains("\u043f\u0430\u0440\u043e\u043b")
            || message.contains("\u043f\u043e\u043f\u044b\u0442");
    }

    static String composeCommand(LoginMode mode, String password) {
        if (password == null) return null;
        String trimmedPassword = password.trim();
        if (trimmedPassword.isEmpty()) return null;
        return switch (mode) {
            case LOGIN -> "/login " + trimmedPassword;
            case REGISTER -> "/reg " + trimmedPassword + " " + trimmedPassword;
        };
    }

    private record DebugChatPacketSnapshot(String packetType, String message, String sender, Boolean senderInTab, Boolean overlay, boolean trustedAuthOrigin, String extra) {}
    public record ParsedCommand(LoginMode mode, String password) {}
    public enum LoginMode { LOGIN, REGISTER }

    public static final class AutoLoginProfile implements ISerializable<AutoLoginProfile> {
        private final Settings settings = new Settings();
        private final SettingGroup sgProfile = settings.getDefaultGroup();
        public final Setting<Boolean> enabled = sgProfile.add(new BoolSetting.Builder().name("enabled").description("Whether this entry can be used.").defaultValue(true).build());
        public final Setting<String> username = sgProfile.add(new StringSetting.Builder().name("username").description("Minecraft username this entry is bound to.").defaultValue("").build());
        public final Setting<String> server = sgProfile.add(new StringSetting.Builder().name("server").description("Server address this entry is bound to.").defaultValue("").build());
        public final Setting<LoginMode> mode = sgProfile.add(new EnumSetting.Builder<LoginMode>().name("mode").description("Which command should be sent.").defaultValue(LoginMode.LOGIN).build());
        public final Setting<String> password = sgProfile.add(new StringSetting.Builder().name("password").description("Password used for /login or /reg.").defaultValue("").build());
        public final Setting<Integer> delay = sgProfile.add(new IntSetting.Builder().name("delay").description("Delay in ticks after join before sending the command.").defaultValue(40).min(0).sliderRange(0, 200).build());
        @Override public NbtCompound toTag() { NbtCompound tag = new NbtCompound(); tag.put("settings", settings.toTag()); return tag; }
        @Override public AutoLoginProfile fromTag(NbtCompound tag) { NbtCompound settingsTag = (NbtCompound) tag.get("settings"); if (settingsTag != null) settings.fromTag(settingsTag); return this; }
        public AutoLoginProfile copy() { return new AutoLoginProfile().fromTag(toTag()); }
        public void copyFrom(AutoLoginProfile other) { enabled.set(other.enabled.get()); username.set(other.username.get()); server.set(other.server.get()); mode.set(other.mode.get()); password.set(other.password.get()); delay.set(other.delay.get()); }
    }
}
