package com.example.addon.games;

import com.example.addon.modules.games.BlackjackOverlay;
import com.example.addon.modules.games.CheckersOverlay;
import com.example.addon.modules.games.ChessOverlay;
import com.example.addon.modules.games.DoomOverlay;
import com.example.addon.modules.games.GameSyncHub;
import com.example.addon.modules.games.SlotMachineOverlay;
import com.mojang.logging.LogUtils;
import meteordevelopment.meteorclient.addons.GithubRepo;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.item.Items;
import org.slf4j.Logger;

public class DevilsGameAddon extends MeteorAddon {
    public static final Logger LOG = LogUtils.getLogger();
    public static final Category GAMES_CATEGORY = new Category("Devils-Game", Items.DIAMOND.getDefaultStack());

    @Override
    public void onInitialize() {
        LOG.info("Initializing Devils Game");
        registerModules();
    }

    private void registerModules() {
        Modules modules = Modules.get();
        modules.add(new GameSyncHub());
        modules.add(new CheckersOverlay());
        modules.add(new ChessOverlay());
        modules.add(new SlotMachineOverlay());
        modules.add(new BlackjackOverlay());
        modules.add(new DoomOverlay());
    }

    @Override
    public void onRegisterCategories() {
        Modules.registerCategory(GAMES_CATEGORY);
    }

    @Override
    public String getPackage() {
        return "com.example.addon";
    }

    @Override
    public GithubRepo getRepo() {
        return new GithubRepo("ThianYong-hub", "DEVILS");
    }
}
