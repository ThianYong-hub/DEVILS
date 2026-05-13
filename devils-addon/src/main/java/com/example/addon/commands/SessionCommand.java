package com.example.addon.commands;

import com.example.addon.modules.AutoLogin;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.commands.Command;
import meteordevelopment.meteorclient.systems.accounts.Accounts;
import meteordevelopment.meteorclient.systems.accounts.types.CrackedAccount;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.misc.AutoReconnect;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.command.CommandSource;
import net.minecraft.util.StringHelper;

public class SessionCommand extends Command {
    private static final SessionReconnectController RECONNECT_CONTROLLER = new SessionReconnectController();
    private static boolean reconnectControllerInitialized;

    public SessionCommand() {
        super("session", "Switches to a cracked session for the current server and optionally saves AutoLogin password.");
        initReconnectController();
    }

    @Override
    public void build(LiteralArgumentBuilder<CommandSource> builder) {
        builder.then(argument("nick", StringArgumentType.word())
            .executes(ctx -> run(
                StringArgumentType.getString(ctx, "nick"),
                null
            ))
            .then(argument("password", StringArgumentType.greedyString())
                .executes(ctx -> run(
                    StringArgumentType.getString(ctx, "nick"),
                    StringArgumentType.getString(ctx, "password")
                ))
            )
        );
    }

    private int run(String rawNick, String rawPassword) {
        String nick = rawNick == null ? "" : rawNick.trim();
        String password = rawPassword == null ? null : rawPassword.trim();
        clearSessionCommandHistory(nick, password);

        if (!StringHelper.isValidPlayerName(nick)) {
            error("Invalid nickname. Use a standard Minecraft username.");
            return SINGLE_SUCCESS;
        }

        CrackedAccount account = findCrackedAccount(nick);
        boolean created = false;
        if (account == null) {
            account = new CrackedAccount(nick);
            created = true;
        }

        if (!account.fetchInfo()) {
            error("Failed to prepare cracked account for %s.", nick);
            return SINGLE_SUCCESS;
        }

        if (created) Accounts.get().add(account);

        String serverKey = resolveCurrentServerKey();
        if (password != null && !password.isEmpty()) saveAutoLoginProfile(nick, serverKey, password);

        if (!account.login()) {
            error("Failed to switch session to %s.", nick);
            return SINGLE_SUCCESS;
        }

        ReconnectTarget reconnectTarget = resolveReconnectTarget();
        if (reconnectTarget != null) {
            RECONNECT_CONTROLLER.schedule(reconnectTarget);
            if (password != null && !password.isEmpty()) {
                info("Switched to %s, saved AutoLogin password for %s and queued reconnect to %s.", nick, nick, reconnectTarget.info.address);
            } else {
                info("Switched to %s and queued reconnect to %s.", nick, reconnectTarget.info.address);
            }
            return SINGLE_SUCCESS;
        }

        if (password != null && !password.isEmpty()) {
            info("Switched to %s and saved AutoLogin password, but no reconnect target is available.", nick);
        } else {
            info("Switched to %s. No reconnect target is available.", nick);
        }

        return SINGLE_SUCCESS;
    }

    public static boolean switchToCrackedSession(String rawNick) {
        String nick = rawNick == null ? "" : rawNick.trim();
        if (!StringHelper.isValidPlayerName(nick)) return false;

        CrackedAccount account = findCrackedAccount(nick);
        boolean created = false;
        if (account == null) {
            account = new CrackedAccount(nick);
            created = true;
        }

        if (!account.fetchInfo()) return false;
        if (created) Accounts.get().add(account);
        return account.login();
    }

    public static boolean scheduleReconnect(String rawServerAddress) {
        String serverAddress = rawServerAddress == null ? "" : rawServerAddress.trim();
        if (serverAddress.isBlank()) return false;

        ServerAddress address;
        try {
            address = ServerAddress.parse(serverAddress);
        } catch (IllegalArgumentException ignored) {
            return false;
        }

        initReconnectController();
        RECONNECT_CONTROLLER.schedule(new ReconnectTarget(
            address,
            new ServerInfo(serverAddress, serverAddress, ServerInfo.ServerType.OTHER)
        ));
        return true;
    }

    private void saveAutoLoginProfile(String nick, String serverKey, String password) {
        if (serverKey.isBlank()) {
            warning("Cracked session prepared, but AutoLogin profile was not saved because no multiplayer server is selected.");
            return;
        }

        AutoLogin autoLogin = Modules.get().get(AutoLogin.class);
        if (autoLogin == null) {
            warning("AutoLogin module is not available, skipping password save.");
            return;
        }

        if (!autoLogin.saveLoginProfile(nick, serverKey, password)) {
            warning("AutoLogin password was not saved for %s.", nick);
            return;
        }

        Modules.get().save();
    }

    private void clearSessionCommandHistory(String nick, String password) {
        if (mc == null || mc.inGameHud == null) return;

        String commandLine = password == null || password.isBlank()
            ? toString(nick)
            : toString(nick, password);

        mc.inGameHud.getChatHud().getMessageHistory().removeLastOccurrence(commandLine);
        mc.inGameHud.getChatHud().discardDraft();
    }

    private static void initReconnectController() {
        if (reconnectControllerInitialized) return;
        reconnectControllerInitialized = true;
        MeteorClient.EVENT_BUS.subscribe(RECONNECT_CONTROLLER);
    }

    private static Screen createReconnectParent() {
        return new MultiplayerScreen(new TitleScreen());
    }

    private ReconnectTarget resolveReconnectTarget() {
        ServerInfo currentServer = mc.getCurrentServerEntry();
        if (currentServer != null && currentServer.address != null && !currentServer.address.isBlank()) {
            return new ReconnectTarget(ServerAddress.parse(currentServer.address), copyServerInfo(currentServer));
        }

        AutoReconnect autoReconnect = Modules.get().get(AutoReconnect.class);
        if (autoReconnect != null && autoReconnect.lastServerConnection != null && autoReconnect.lastServerConnection.left() != null && autoReconnect.lastServerConnection.right() != null) {
            return new ReconnectTarget(autoReconnect.lastServerConnection.left(), copyServerInfo(autoReconnect.lastServerConnection.right()));
        }

        return null;
    }

    private static ServerInfo copyServerInfo(ServerInfo source) {
        ServerInfo copy = new ServerInfo(source.name, source.address, source.getServerType());
        copy.copyFrom(source);
        return copy;
    }

    private static String resolveCurrentServerKey() {
        if (mc.getCurrentServerEntry() != null && mc.getCurrentServerEntry().address != null) {
            String address = mc.getCurrentServerEntry().address.trim();
            if (!address.isEmpty()) return address;
        }

        String worldName = Utils.getWorldName();
        return worldName == null ? "" : worldName.trim();
    }

    private static CrackedAccount findCrackedAccount(String username) {
        for (var account : Accounts.get()) {
            if (account instanceof CrackedAccount cracked && cracked.getUsername().equalsIgnoreCase(username)) return cracked;
        }
        return null;
    }

    private record ReconnectTarget(ServerAddress address, ServerInfo info) {
    }

    private static final class SessionReconnectController {
        private static final int CONNECT_DELAY_TICKS = 2;

        private ReconnectTarget pendingTarget;
        private int pendingDelayTicks;
        private boolean waitingForDisconnect;

        void schedule(ReconnectTarget reconnectTarget) {
            pendingTarget = reconnectTarget;
            pendingDelayTicks = CONNECT_DELAY_TICKS;
            waitingForDisconnect = hasLiveSession();

            if (waitingForDisconnect) {
                Screen parent = createReconnectParent();
                SessionCommand.mc.disconnect(parent, false);
            }
        }

        @EventHandler
        private void onGameLeft(GameLeftEvent event) {
            if (pendingTarget == null) return;
            waitingForDisconnect = false;
            if (pendingDelayTicks < CONNECT_DELAY_TICKS) pendingDelayTicks = CONNECT_DELAY_TICKS;
        }

        @EventHandler
        private void onTick(TickEvent.Post event) {
            ReconnectTarget reconnectTarget = pendingTarget;
            if (reconnectTarget == null) return;

            if (waitingForDisconnect) {
                if (hasLiveSession()) return;
                waitingForDisconnect = false;
                if (pendingDelayTicks < CONNECT_DELAY_TICKS) pendingDelayTicks = CONNECT_DELAY_TICKS;
            }

            if (hasLiveSession()) return;

            if (pendingDelayTicks > 0) {
                pendingDelayTicks--;
                return;
            }

            pendingTarget = null;
            pendingDelayTicks = 0;
            waitingForDisconnect = false;

            Screen parent = createReconnectParent();
            ConnectScreen.connect(parent, SessionCommand.mc, reconnectTarget.address, reconnectTarget.info, false, null);
        }

        private static boolean hasLiveSession() {
            return SessionCommand.mc.world != null
                || SessionCommand.mc.player != null
                || (SessionCommand.mc.getNetworkHandler() != null && SessionCommand.mc.getNetworkHandler().getConnection() != null);
        }
    }
}
