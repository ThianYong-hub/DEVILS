package com.example.addon;

import com.example.addon.commands.AutoAnvilRenameCommand;
import com.example.addon.commands.CommandExample;
import com.example.addon.hud.HudExample;
import com.example.addon.modules.AntiWasp;
import com.example.addon.modules.AutoAnvilRename;
import com.example.addon.modules.AutoPearl;
import com.example.addon.modules.AutoWasp;
import com.example.addon.modules.DiscordRPC;
import com.example.addon.modules.HClip;
import com.example.addon.modules.AutoCev;
import com.example.addon.modules.JoinWatcher;
import com.example.addon.modules.LavaBucket;
import com.example.addon.modules.TnTBomber;
import com.example.addon.modules.MaceSpoof;
import com.example.addon.modules.MultiTask;
import com.example.addon.modules.VClip;
import com.example.addon.modules.highwaybuilder.HighwayBuilder;
import com.example.addon.settings.TrackerPlayersSetting;
import com.mojang.logging.LogUtils;
import meteordevelopment.meteorclient.addons.GithubRepo;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.commands.Commands;
import meteordevelopment.meteorclient.gui.utils.SettingsWidgetFactory;
import meteordevelopment.meteorclient.gui.widgets.containers.WTable;
import meteordevelopment.meteorclient.systems.hud.Hud;
import meteordevelopment.meteorclient.systems.hud.HudGroup;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.item.Items;
import org.slf4j.Logger;

public class AddonTemplate extends MeteorAddon {
    public static final Logger LOG = LogUtils.getLogger();
    public static final Category CATEGORY = new Category("Devils", Items.NETHER_STAR.getDefaultStack());
    public static final HudGroup HUD_GROUP = new HudGroup("Devils");

    @Override
    public void onInitialize() {
        LOG.info("Initializing Devils Addon");

        SettingsWidgetFactory.registerCustomFactory(TrackerPlayersSetting.class, theme -> (table, setting) -> {
            WTable rulesTable = table.add(theme.table()).expandX().widget();
            TrackerPlayersSetting.fillTable(theme, rulesTable, (TrackerPlayersSetting) setting);
        });

        Modules.get().add(new AutoPearl());
        Modules.get().add(new AutoAnvilRename());
        Modules.get().add(new AntiWasp());
        Modules.get().add(new AutoWasp());
        Modules.get().add(new DiscordRPC());
        Modules.get().add(new HClip());
        Modules.get().add(new AutoCev());
        Modules.get().add(new JoinWatcher());
        Modules.get().add(new LavaBucket());
        Modules.get().add(new TnTBomber());
        Modules.get().add(new VClip());
        Modules.get().add(new HighwayBuilder());
        Modules.get().add(new MaceSpoof());
        Modules.get().add(new MultiTask());

        Commands.add(new CommandExample());
        Commands.add(new AutoAnvilRenameCommand());

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
