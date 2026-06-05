package com.devils.addon;

import com.devils.addon.config.AddonModulesConfig;
import com.devils.addon.commands.SessionCommand;
import com.devils.addon.modules.AntiWasp;
import com.devils.addon.modules.AutoAnvilRename;
import com.devils.addon.modules.AutoCraft;
import com.devils.addon.modules.AutoCev;
import com.devils.addon.modules.AutoLogin;
import com.devils.addon.modules.AutoPearl;
import com.devils.addon.modules.AutoWasp;
import com.devils.addon.modules.ChestTrackerModule;
import com.devils.addon.modules.ClipModules;
import com.devils.addon.modules.DiscordRPC;
import com.devils.addon.modules.JoinWatcher;
import com.devils.addon.modules.LavaBucket;
import com.devils.addon.modules.MaceSpoof;
import com.devils.addon.modules.NukerPlus;
import com.devils.addon.modules.Ping;
import com.devils.addon.modules.SpearSpoof;
import com.devils.addon.modules.SyncHub;
import com.devils.addon.modules.TnTBomber;
import com.devils.addon.modules.highwaybuilder.HighwayBuilder;
import com.devils.addon.modules.modupdater.ModAutoUpdater;
import com.devils.addon.modules.stashmover.StashMoverCommand;
import com.devils.addon.modules.stashmover.StashMover;
import com.devils.addon.settings.TrackerPlayersSetting;
import com.devils.addon.util.CrashGuard;
import com.devils.addon.util.smoke.AssimilatedQualitySmoke;
import com.devils.addon.util.smoke.AutoWaspRuntimeValidation;
import com.devils.addon.util.smoke.InputRuntimeValidation;
import com.devils.addon.util.smoke.NukerPlusDamageTimeRuntimeValidation;
import com.devils.addon.util.smoke.StashMoverLiveRuntimeValidation;
import com.devils.addon.util.smoke.StashMoverStrictRuntimeValidation;
import com.devils.addon.util.smoke.StashMoverTargetedRuntimeValidation;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.logging.LogUtils;
import java.util.ArrayList;
import meteordevelopment.meteorclient.addons.GithubRepo;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.commands.Command;
import meteordevelopment.meteorclient.commands.Commands;
import meteordevelopment.meteorclient.gui.utils.SettingsWidgetFactory;
import meteordevelopment.meteorclient.gui.widgets.containers.WTable;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.command.CommandSource;
import net.minecraft.item.Items;
import org.slf4j.Logger;

public class DevilsAddon extends MeteorAddon {
    public static final Logger LOG = LogUtils.getLogger();
    public static final Category CATEGORY = new Category("Devils", Items.NETHER_STAR.getDefaultStack());

    @Override
    public void onInitialize() {
        LOG.info("Initializing Devils Addon");
        CrashGuard.installLogFilters();
        AssimilatedQualitySmoke.install();
        InputRuntimeValidation.install();
        AutoWaspRuntimeValidation.install();
        NukerPlusDamageTimeRuntimeValidation.install();
        StashMoverLiveRuntimeValidation.install();
        StashMoverStrictRuntimeValidation.install();
        StashMoverTargetedRuntimeValidation.install();
        AddonModulesConfig.init();
        CrashGuard.logXaeroState();
        registerTrackerPlayersSettingFactory();
        registerModules();
        registerCommands();
    }

    private void registerTrackerPlayersSettingFactory() {
        SettingsWidgetFactory.registerCustomFactory(TrackerPlayersSetting.class, theme -> (table, setting) -> {
            WTable rulesTable = table.add(theme.table()).expandX().widget();
            TrackerPlayersSetting.fillTable(theme, rulesTable, (TrackerPlayersSetting) setting);
        });
    }

    private void registerModules() {
        Modules modules = Modules.get();
        modules.add(new AutoPearl());
        modules.add(new AutoAnvilRename());
        modules.add(new AutoCraft());
        modules.add(new SyncHub());
        modules.add(new ModAutoUpdater());
        modules.add(new AutoLogin());
        modules.add(new Ping());
        modules.add(new AntiWasp());
        modules.add(new AutoWasp());
        modules.add(new DiscordRPC());
        modules.add(new ClipModules.HClip());
        modules.add(new AutoCev());
        modules.add(new JoinWatcher());
        modules.add(new LavaBucket());
        modules.add(new TnTBomber());
        modules.add(new ClipModules.VClip());
        modules.add(new HighwayBuilder());
        modules.add(new MaceSpoof());
        modules.add(new SpearSpoof());
        modules.add(new NukerPlus());
        modules.add(new ChestTrackerModule());
        modules.add(new StashMover());
    }

    private void registerCommands() {
        Commands.add(new AutoAnvilRenameCommand());
        Commands.add(new SessionCommand());
        Commands.add(new StashMoverCommand());
    }

    @Override
    public void onRegisterCategories() {
        Modules.registerCategory(CATEGORY);
    }

    @Override
    public String getPackage() {
        return "com.devils.addon";
    }

    @Override
    public GithubRepo getRepo() {
        return new GithubRepo("ThianYong-hub", "DEVILS");
    }

    public static class AutoAnvilRenameCommand extends Command {
        public AutoAnvilRenameCommand() {
            super("autoraname", "Sets AutoAnvilRename options");
        }

        @Override
        public void build(LiteralArgumentBuilder<CommandSource> builder) {
            builder.then(literal("setname").then(argument("name", StringArgumentType.greedyString()).executes(ctx -> {
                String name = StringArgumentType.getString(ctx, "name");
                AutoAnvilRename module = Modules.get().get(AutoAnvilRename.class);
                if (module != null) {
                    module.setRenameText(name);
                    info("Set rename text to: " + name);
                } else info("AutoAnvilRename module not found");
                return SINGLE_SUCCESS;
            })));
            builder.then(literal("clearitems").executes(ctx -> {
                AutoAnvilRename module = Modules.get().get(AutoAnvilRename.class);
                if (module != null) {
                    module.getItemsSetting().set(new ArrayList<>());
                    info("Cleared item filter list.");
                } else info("AutoAnvilRename module not found");
                return SINGLE_SUCCESS;
            }));
        }
    }
}

