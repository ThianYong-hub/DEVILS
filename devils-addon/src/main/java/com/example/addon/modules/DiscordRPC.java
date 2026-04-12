package com.example.addon.modules;

import com.example.addon.AddonTemplate;
import com.example.addon.util.CrashGuard;
import meteordevelopment.discordipc.DiscordIPC;
import meteordevelopment.discordipc.RichPresence;
import meteordevelopment.meteorclient.events.game.GameJoinedEvent;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.game.OpenScreenEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.misc.DiscordPresence;
import net.fabricmc.loader.api.FabricLoader;
import meteordevelopment.orbit.EventHandler;

public class DiscordRPC extends Module {
    private static final long APP_ID = 1475814462278860810L;
    private static final int PRESENCE_REFRESH_TICKS = 100;
    private static final int FORCE_RECONNECT_TICKS = 600;
    private static final String VERSION = FabricLoader.getInstance()
        .getModContainer("devils-addon")
        .map(c -> c.getMetadata().getVersion().getFriendlyString())
        .orElse("unknown");

    private final RichPresence rpc = new RichPresence();
    private int tickCounter;
    private int reconnectCounter;
    private boolean lastWasInMainMenu;
    private boolean restoreMeteorPresenceOnDeactivate;

    public DiscordRPC() {
        super(AddonTemplate.CATEGORY, "discord-rpc", "Shows Devils Addon branding in Discord status.");
        runInMainMenu = true;
    }

    @Override
    public void onActivate() {
        rpc.setLargeImage("devils", "v." + VERSION);
        rpc.setStart(System.currentTimeMillis() / 1000L);
        tickCounter = 0;
        reconnectCounter = 0;
        lastWasInMainMenu = true;
        restoreMeteorPresenceOnDeactivate = false;

        forceReconnect();
    }

    @Override
    public void onDeactivate() {
        DiscordIPC.stop();
        restoreMeteorPresenceIfNeeded();
    }

    @EventHandler
    private void onGameJoined(GameJoinedEvent event) {
        CrashGuard.run(this, "onGameJoined", () -> onGameJoinedSafe(event));
    }

    private void onGameJoinedSafe(GameJoinedEvent event) {
        if (!DiscordIPC.isConnected()) forceReconnect();
        else updatePresence();
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        CrashGuard.run(this, "onGameLeft", () -> onGameLeftSafe(event));
    }

    private void onGameLeftSafe(GameLeftEvent event) {
        lastWasInMainMenu = false;
        if (!DiscordIPC.isConnected()) forceReconnect();
        else updatePresence();
    }

    @EventHandler
    private void onOpenScreen(OpenScreenEvent event) {
        CrashGuard.run(this, "onOpenScreen", () -> onOpenScreenSafe(event));
    }

    private void onOpenScreenSafe(OpenScreenEvent event) {
        if (!DiscordIPC.isConnected()) {
            forceReconnect();
            return;
        }
        if (mc.player == null) {
            lastWasInMainMenu = false;
            updatePresence();
        }
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        CrashGuard.run(this, "onTickPost", () -> onTickSafe(event));
    }

    private void onTickSafe(TickEvent.Post event) {
        tickCounter++;
        reconnectCounter++;

        if (ensureMeteorPresenceDisabled()) {
            tickCounter = 0;
            reconnectCounter = 0;
            forceReconnect();
            return;
        }

        if (reconnectCounter >= FORCE_RECONNECT_TICKS) {
            reconnectCounter = 0;
            forceReconnect();
            return;
        }

        if (tickCounter >= PRESENCE_REFRESH_TICKS) {
            tickCounter = 0;
            if (!DiscordIPC.isConnected()) forceReconnect();
            else updatePresence();
        }
    }

    private boolean ensureMeteorPresenceDisabled() {
        DiscordPresence meteorPresence = Modules.get().get(DiscordPresence.class);
        if (meteorPresence != null && meteorPresence.isActive()) {
            restoreMeteorPresenceOnDeactivate = true;
            meteorPresence.toggle();
            return true;
        }

        return false;
    }

    private void restoreMeteorPresenceIfNeeded() {
        if (!restoreMeteorPresenceOnDeactivate) return;

        DiscordPresence meteorPresence = Modules.get().get(DiscordPresence.class);
        if (meteorPresence != null && !meteorPresence.isActive()) {
            meteorPresence.toggle();
        }
        restoreMeteorPresenceOnDeactivate = false;
    }

    private void forceReconnect() {
        ensureMeteorPresenceDisabled();
        DiscordIPC.stop();

        DiscordIPC.start(APP_ID, () -> {
            lastWasInMainMenu = false;
            updatePresence();
        });

        updatePresence();
    }

    private void updatePresence() {
        if (mc.player == null) {
            if (lastWasInMainMenu) return;
            lastWasInMainMenu = true;
            rpc.setDetails("In Main Menu");
            rpc.setState(null);
        } else {
            lastWasInMainMenu = false;
            String server = mc.isInSingleplayer()
                ? "Singleplayer"
                : mc.getCurrentServerEntry() != null ? mc.getCurrentServerEntry().address : "Unknown";
            rpc.setDetails("Playing on " + server);

            int playerCount = mc.getNetworkHandler() != null
                ? mc.getNetworkHandler().getPlayerList().size()
                : 0;
            rpc.setState(playerCount + " Players online");
        }
        DiscordIPC.setActivity(rpc);
    }
}


