package com.example.addon.modules;

import com.example.addon.AddonTemplate;
import com.example.addon.audio.JoinSoundPlayer;
import com.example.addon.settings.TrackerPlayerRule;
import com.example.addon.settings.TrackerPlayersSetting;
import com.example.addon.settings.TrackerPlayerRule.TrackEventMode;
import com.example.addon.util.CrashGuard;
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
import net.minecraft.network.packet.s2c.play.PlayerListS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerRemoveS2CPacket;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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

    private static final ScheduledExecutorService CHAT_SEND_EXECUTOR = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "Devils-TrackerPlayer-ChatSend");
        thread.setDaemon(true);
        return thread;
    });

    private boolean waitingInitialJoinPacket = true;
    private final Map<UUID, String> knownPlayers = new HashMap<>();
    private final List<ScheduledFuture<?>> pendingDelayedSends = Collections.synchronizedList(new ArrayList<>());

    public JoinWatcher() {
        super(
            AddonTemplate.CATEGORY,
            "tracker-player",
            "Universal per-player tracker with join/leave rules, sound playback and optional chat send.",
            "join-watcher"
        );
    }

    @Override
    public void onActivate() {
        waitingInitialJoinPacket = true;
        knownPlayers.clear();
        cancelPendingDelayedSends();
    }

    @Override
    public void onDeactivate() {
        waitingInitialJoinPacket = true;
        knownPlayers.clear();
        cancelPendingDelayedSends();
    }

    @EventHandler
    private void onGameJoined(GameJoinedEvent event) {
        CrashGuard.run(this, "onGameJoined", () -> onGameJoinedSafe(event));
    }

    private void onGameJoinedSafe(GameJoinedEvent event) {
        waitingInitialJoinPacket = true;
        knownPlayers.clear();
        cancelPendingDelayedSends();
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        CrashGuard.run(this, "onGameLeft", () -> onGameLeftSafe(event));
    }

    private void onGameLeftSafe(GameLeftEvent event) {
        waitingInitialJoinPacket = true;
        knownPlayers.clear();
        cancelPendingDelayedSends();
    }

    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        CrashGuard.run(this, "onPacketReceive", () -> onPacketReceiveSafe(event));
    }

    private void onPacketReceiveSafe(PacketEvent.Receive event) {
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

        if (shouldIgnoreInitialPacket(packet)) return;

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

        Entity entity = mc.world.getEntityById(packet.playerId());
        if (!(entity instanceof PlayerEntity player)) return;

        String playerName = player.getName().getString();
        if (!playerName.isBlank()) {
            processRules(playerName, RuleTrigger.Death);
        }
    }

    private boolean shouldIgnoreInitialPacket(PlayerListS2CPacket packet) {
        if (!waitingInitialJoinPacket) return false;
        waitingInitialJoinPacket = false;

        if (mc.player == null) return false;
        UUID selfId = mc.player.getUuid();

        for (PlayerListS2CPacket.Entry entry : packet.getPlayerAdditionEntries()) {
            if (entry.profile() == null) continue;
            if (selfId.equals(entry.profile().id())) return true;
        }

        return false;
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
                    int delayMs = rule.chatDelayMs();
                    if (delayMs <= 0) {
                        ChatUtils.sendPlayerMsg(command);

                        if (autoDisableSendAfterChat.get()) {
                            updatedRules.set(i, rule.withSendEnabled(false));
                            changed = true;
                        }
                    } else {
                        queueDelayedChatSend(i, rule, command, delayMs);
                    }
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

        ChatUtils.sendPlayerMsg(command);
        if (!autoDisableSendAfterChat.get()) return;

        ArrayList<TrackerPlayerRule> currentRules = new ArrayList<>(trackerPlayers.get());
        int targetIndex = findRuleIndexForDisable(currentRules, ruleIndex, ruleSnapshot);
        if (targetIndex < 0) return;

        TrackerPlayerRule currentRule = currentRules.get(targetIndex);
        if (!currentRule.sendEnabled()) return;

        currentRules.set(targetIndex, currentRule.withSendEnabled(false));
        trackerPlayers.set(currentRules);
    }

    private int findRuleIndexForDisable(List<TrackerPlayerRule> rules, int preferredIndex, TrackerPlayerRule snapshot) {
        if (preferredIndex >= 0 && preferredIndex < rules.size()) {
            TrackerPlayerRule candidate = rules.get(preferredIndex);
            if (candidate.playerName().equals(snapshot.playerName())) return preferredIndex;
        }

        for (int i = 0; i < rules.size(); i++) {
            TrackerPlayerRule candidate = rules.get(i);
            if (candidate.playerName().equals(snapshot.playerName())
                && candidate.eventMode() == snapshot.eventMode()
                && candidate.commandText().equals(snapshot.commandText())
                && candidate.chatDelayMs() == snapshot.chatDelayMs()
                && candidate.sendEnabled()) {
                return i;
            }
        }

        return -1;
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


