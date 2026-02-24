package com.example.addon;

import com.example.addon.commands.CommandExample;
import com.example.addon.commands.AutoAnvilRenameCommand;
import com.example.addon.hud.HudExample;
import com.example.addon.modules.AutoPearl;
import com.example.addon.modules.AutoAnvilRename;
import com.example.addon.modules.AntiWasp;
import com.example.addon.modules.AutoWasp;
import com.mojang.logging.LogUtils;
import meteordevelopment.meteorclient.addons.GithubRepo;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.commands.Commands;
import meteordevelopment.meteorclient.systems.hud.Hud;
import meteordevelopment.meteorclient.systems.hud.HudGroup;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Modules;
import org.slf4j.Logger;

public class AddonTemplate extends MeteorAddon {
    public static final Logger LOG = LogUtils.getLogger();
    public static final Category CATEGORY = new Category("Devils");
    public static final HudGroup HUD_GROUP = new HudGroup("Devils");

    @Override
    public void onInitialize() {
        LOG.info("Initializing Devils Addon");

        // Modules
        Modules.get().add(new AutoPearl());
        Modules.get().add(new AutoAnvilRename());
        Modules.get().add(new AntiWasp());
        Modules.get().add(new AutoWasp());

        // Commands
        Commands.add(new CommandExample());
        Commands.add(new AutoAnvilRenameCommand());

        // HUD
        Hud.get().register(HudExample.INFO);
    }

    @Override
    public void onRegisterCategories() {
        Modules.registerCategory(CATEGORY);
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
