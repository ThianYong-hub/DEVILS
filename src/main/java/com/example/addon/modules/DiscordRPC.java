package com.example.addon.modules;

import com.example.addon.AddonTemplate;
import meteordevelopment.discordipc.DiscordIPC;
import meteordevelopment.discordipc.RichPresence;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.misc.DiscordPresence;
import meteordevelopment.orbit.EventHandler;

public class DiscordRPC extends Module {
    private static final long APP_ID = 1475814462278860810L;
    private static final String LINE_1 = "Devils Addon";
    private static final String LINE_2 = "github.com/ThianYong-hub/DEVILS";

    private final RichPresence rpc = new RichPresence();
    private int tickCounter;

    public DiscordRPC() {
        super(AddonTemplate.CATEGORY, "discord-rpc", "Shows Devils Addon branding in Discord status.");
    }

    @Override
    public void onActivate() {
        DiscordPresence meteorPresence = Modules.get().get(DiscordPresence.class);
        if (meteorPresence != null && meteorPresence.isActive()) {
            meteorPresence.toggle();
        }

        DiscordIPC.start(APP_ID, null);

        rpc.setLargeImage("devils", "Devils Addon");
        rpc.setStart(System.currentTimeMillis() / 1000L);
        rpc.setDetails(LINE_1);
        rpc.setState(LINE_2);
        DiscordIPC.setActivity(rpc);

        tickCounter = 0;
    }

    @Override
    public void onDeactivate() {
        DiscordIPC.stop();
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        tickCounter++;
        if (tickCounter >= 200) {
            tickCounter = 0;
            DiscordIPC.setActivity(rpc);
        }
    }
}
