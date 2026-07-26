package com.devils.addon.modules;

import com.devils.addon.DevilsAddon;
import com.devils.addon.audio.JoinSoundPlayer;
import com.devils.addon.settings.TrackerPlayerRule;
import com.devils.addon.settings.TrackerPlayerRule.TrackEventMode;
import com.devils.addon.util.CrashGuard;
import meteordevelopment.meteorclient.events.game.GameJoinedEvent;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.packet.s2c.play.DeathMessageS2CPacket;
import net.minecraft.network.packet.s2c.play.GameJoinS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerListS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerRemoveS2CPacket;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class JoinWatcher extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgSoundDefaults = settings.createGroup("Sound Defaults");
    private final SettingGroup sgAutomation = settings.createGroup("Automation");

    private final Setting<List<TrackerPlayerRule>> trackerPlayers = sgGeneral.add(new TrackerPlayerRule.SettingBuilder()
        .name("tracker-players")
        .description("Per-player tracking rules: event, sound, send command, and sound source.")
        .build()
    );

    private final Setting<String> defaultSound = sgSoundDefaults.add(new StringSetting.Builder()
        .name("default-sound")
        .description("Fallback sound id or .ogg path in <gameDir>/devils-addon/sounds.")
        .defaultValue("minecraft:entity.experience_orb.pickup")
        .build()
    );

    private final Setting<Boolean> autoDisableSendAfterChat = sgAutomation.add(new BoolSetting.Builder()
        .name("auto-disable-send-after-chat")
        .description("After a successful send, disables 'send' only for the triggered player rule.")
        .defaultValue(true)
        .build()
    );

    private static final long INITIAL_JOIN_SUPPRESSION_NANOS = TimeUnit.MILLISECONDS.toNanos(3000L);
    private static final int SESSION_START_PLAYER_AGE_TICKS = 60;

    private static final ScheduledExecutorService CHAT_SEND_EXECUTOR = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "Devils-TrackerPlayer-ChatSend");
        thread.setDaemon(true);
        return thread;
    });

    private volatile long initialJoinSuppressedUntilNanos;
    private volatile boolean initialJoinSuppressionArmed;
    private final Map<UUID, String> knownPlayers = new ConcurrentHashMap<>();
    private final List<ScheduledFuture<?>> pendingDelayedSends = Collections.synchronizedList(new ArrayList<>());

    public JoinWatcher() {
        super(
            DevilsAddon.CATEGORY,
            "tracker-player",
            "Universal per-player tracker with join/leave rules, sound playback and optional chat send.",
            "join-watcher"
        );
    }

    @Override
    public void onActivate() {
        // Meteor calls this both when a play session starts (it subscribes modules on GameJoinedEvent) and when the
        // user toggles the module mid-session. Only the former should swallow the initial player-list burst.
        if (mc.player == null || mc.player.age <= SESSION_START_PLAYER_AGE_TICKS) armInitialJoinSuppression();
        else initialJoinSuppressionArmed = false;
        knownPlayers.clear();
        cancelPendingDelayedSends();
    }

    @Override
    public void onDeactivate() {
        initialJoinSuppressionArmed = false;
        knownPlayers.clear();
        cancelPendingDelayedSends();
    }

    @EventHandler
    private void onGameJoined(GameJoinedEvent event) {
        CrashGuard.run(this, "onGameJoined", () -> onGameJoinedSafe(event));
    }

    private void onGameJoinedSafe(GameJoinedEvent event) {
        // Meteor only subscribes non-main-menu modules once this event fires, so the GameJoinS2CPacket that starts a
        // session is normally received while we are still unsubscribed. Arm the window here (and in onActivate) so the
        // initial ADD_PLAYER batches are covered no matter how many packets the server splits them across.
        armInitialJoinSuppression();
        knownPlayers.clear();
        cancelPendingDelayedSends();
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        CrashGuard.run(this, "onGameLeft", () -> onGameLeftSafe(event));
    }

    private void onGameLeftSafe(GameLeftEvent event) {
        knownPlayers.clear();
        cancelPendingDelayedSends();
    }

    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        CrashGuard.run(this, "onPacketReceive", () -> onPacketReceiveSafe(event));
    }

    private void onPacketReceiveSafe(PacketEvent.Receive event) {
        if (event.packet instanceof GameJoinS2CPacket) {
            armInitialJoinSuppression();
            return;
        }

        if (event.packet instanceof PlayerListS2CPacket packet) {
            handleJoinPacket(packet);
            return;
        }

        if (event.packet instanceof PlayerRemoveS2CPacket packet) {
            handleLeavePacket(packet);
            return;
        }

        if (event.packet instanceof DeathMessageS2CPacket packet) {
            handleDeathPacket(packet);
        }
    }

    private void handleJoinPacket(PlayerListS2CPacket packet) {
        if (!packet.getActions().contains(PlayerListS2CPacket.Action.ADD_PLAYER)) return;

        for (PlayerListS2CPacket.Entry entry : packet.getPlayerAdditionEntries()) {
            if (entry.profile() == null) continue;
            knownPlayers.put(entry.profile().id(), entry.profile().name());
        }

        if (isInitialJoinSuppressed()) return;

        for (PlayerListS2CPacket.Entry entry : packet.getPlayerAdditionEntries()) {
            if (entry.profile() == null) continue;
            processRules(entry.profile().name(), RuleTrigger.Join);
        }
    }

    private void handleLeavePacket(PlayerRemoveS2CPacket packet) {
        for (UUID playerId : packet.profileIds()) {
            String playerName = knownPlayers.remove(playerId);

            if (playerName == null && mc.getNetworkHandler() != null) {
                var entry = mc.getNetworkHandler().getPlayerListEntry(playerId);
                if (entry != null && entry.getProfile() != null) playerName = entry.getProfile().name();
            }

            if (playerName != null) processRules(playerName, RuleTrigger.Leave);
        }
    }

    private void handleDeathPacket(DeathMessageS2CPacket packet) {
        if (mc.world == null) return;

        // The world entity index is client-thread state, so resolve the dead player there instead of on the netty read thread.
        mc.execute(() -> CrashGuard.run(this, "handleDeathPacket", () -> handleDeathPacketOnClientThread(packet)));
    }

    private void handleDeathPacketOnClientThread(DeathMessageS2CPacket packet) {
        if (mc.world == null) return;

        Entity entity = mc.world.getEntityById(packet.playerId());
        if (!(entity instanceof PlayerEntity player)) return;

        String playerName = player.getName().getString();
        if (!playerName.isBlank()) {
            processRules(playerName, RuleTrigger.Death);
        }
    }

    private void armInitialJoinSuppression() {
        // Monotonic: a wall-clock step backwards must not leave the window stuck open and swallow every join.
        initialJoinSuppressedUntilNanos = System.nanoTime() + INITIAL_JOIN_SUPPRESSION_NANOS;
        initialJoinSuppressionArmed = true;
    }

    private boolean isInitialJoinSuppressed() {
        // Servers may split the initial player list over several ADD_PLAYER packets and are not required to put the
        // local player in the first one, so suppress every addition for a short window after the play session starts.
        if (!initialJoinSuppressionArmed) return false;

        if (System.nanoTime() - initialJoinSuppressedUntilNanos >= 0L) {
            initialJoinSuppressionArmed = false;
            return false;
        }

        return true;
    }

    private void processRules(String playerName, RuleTrigger trigger) {
        if (trackerPlayers.get().isEmpty()) return;

        ArrayList<TrackerPlayerRule> updatedRules = new ArrayList<>(trackerPlayers.get());
        boolean changed = false;

        for (int i = 0; i < updatedRules.size(); i++) {
            TrackerPlayerRule rule = updatedRules.get(i);

            if (!playerName.equals(rule.playerName())) continue;
            if (!matchesEvent(rule.eventMode(), trigger)) continue;

            if (rule.soundEnabled()) {
                String soundValue = rule.soundValueFor(toRuleTrigger(trigger));
                JoinSoundPlayer.play(rule.soundSource(), soundValue, defaultSound.get(), rule.oggVolumePercent());
            }

            if (rule.sendEnabled()) {
                String command = rule.commandText().trim();
                if (!command.isEmpty() && mc.player != null && mc.player.networkHandler != null) {
                    // Route the immediate case through the same bookkeeping as the delayed one (the executor
                    // runs a non-positive delay right away): a bare mc.execute task cannot be cancelled, so a
                    // world change between queue and drain would still fire the command on the new connection.
                    int delayMs = rule.chatDelayMs();
                    queueDelayedChatSend(i, rule, command, delayMs);
                }
            }
        }

        if (changed) trackerPlayers.set(updatedRules);
    }

    private void queueDelayedChatSend(int ruleIndex, TrackerPlayerRule ruleSnapshot, String command, int delayMs) {
        ScheduledFuture<?> future = CHAT_SEND_EXECUTOR.schedule(
            () -> mc.execute(() -> executeDelayedChatSend(ruleIndex, ruleSnapshot, command)),
            delayMs,
            TimeUnit.MILLISECONDS
        );

        pendingDelayedSends.add(future);
        cleanupCompletedDelayedSends();
    }

    private void executeDelayedChatSend(int ruleIndex, TrackerPlayerRule ruleSnapshot, String command) {
        cleanupCompletedDelayedSends();

        if (!isActive()) return;
        if (command.isBlank()) return;
        if (mc.player == null || mc.player.networkHandler == null) return;

        ArrayList<TrackerPlayerRule> currentRules = new ArrayList<>(trackerPlayers.get());
        int targetIndex = findRuleIndexForDelayedSend(currentRules, ruleIndex, ruleSnapshot, command);
        if (targetIndex < 0) return;

        TrackerPlayerRule currentRule = currentRules.get(targetIndex);
        if (!currentRule.sendEnabled()) return;

        ChatUtils.sendPlayerMsg(command);
        if (!autoDisableSendAfterChat.get()) return;

        currentRules.set(targetIndex, currentRule.withSendEnabled(false));
        trackerPlayers.set(currentRules);
    }

    private int findRuleIndexForDelayedSend(List<TrackerPlayerRule> rules, int preferredIndex, TrackerPlayerRule snapshot, String command) {
        if (preferredIndex >= 0 && preferredIndex < rules.size()) {
            TrackerPlayerRule candidate = rules.get(preferredIndex);
            if (isSameDelayedSendRule(candidate, snapshot, command)) return preferredIndex;
        }

        for (int i = 0; i < rules.size(); i++) {
            if (isSameDelayedSendRule(rules.get(i), snapshot, command)) return i;
        }

        return -1;
    }

    private boolean isSameDelayedSendRule(TrackerPlayerRule candidate, TrackerPlayerRule snapshot, String command) {
        return candidate.playerName().equals(snapshot.playerName())
            && candidate.eventMode() == snapshot.eventMode()
            && candidate.chatDelayMs() == snapshot.chatDelayMs()
            && candidate.commandText().trim().equals(command)
            && candidate.sendEnabled();
    }

    private void cleanupCompletedDelayedSends() {
        synchronized (pendingDelayedSends) {
            pendingDelayedSends.removeIf(future -> future.isDone() || future.isCancelled());
        }
    }

    private void cancelPendingDelayedSends() {
        synchronized (pendingDelayedSends) {
            for (ScheduledFuture<?> future : pendingDelayedSends) {
                future.cancel(false);
            }
            pendingDelayedSends.clear();
        }
    }

    private boolean matchesEvent(TrackEventMode mode, RuleTrigger trigger) {
        return switch (mode) {
            case Join -> trigger == RuleTrigger.Join;
            case Leave -> trigger == RuleTrigger.Leave;
            case Both -> trigger == RuleTrigger.Join || trigger == RuleTrigger.Leave;
            case Death -> trigger == RuleTrigger.Death;
        };
    }

    private TrackerPlayerRule.Trigger toRuleTrigger(RuleTrigger trigger) {
        return switch (trigger) {
            case Join -> TrackerPlayerRule.Trigger.Join;
            case Leave -> TrackerPlayerRule.Trigger.Leave;
            case Death -> TrackerPlayerRule.Trigger.Death;
        };
    }

    private enum RuleTrigger {
        Join,
        Leave,
        Death
    }
}
