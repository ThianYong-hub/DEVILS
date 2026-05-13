package com.example.addon;

import com.example.addon.config.AddonModulesConfig;
import com.example.addon.commands.SessionCommand;
import com.example.addon.modules.AntiWasp;
import com.example.addon.modules.AutoAnvilRename;
import com.example.addon.modules.AutoCraft;
import com.example.addon.modules.AutoCev;
import com.example.addon.modules.AutoLogin;
import com.example.addon.modules.AutoPearl;
import com.example.addon.modules.AutoWasp;
import com.example.addon.modules.ChestTrackerModule;
import com.example.addon.modules.ClipModules;
import com.example.addon.modules.DiscordRPC;
import com.example.addon.modules.JoinWatcher;
import com.example.addon.modules.LavaBucket;
import com.example.addon.modules.MaceSpoof;
import com.example.addon.modules.NukerPlus;
import com.example.addon.modules.Ping;
import com.example.addon.modules.SpearSpoof;
import com.example.addon.modules.SyncHub;
import com.example.addon.modules.TnTBomber;
import com.example.addon.modules.highwaybuilder.HighwayBuilder;
import com.example.addon.modules.modupdater.ModAutoUpdater;
import com.example.addon.modules.stashmover.StashMoverCommand;
import com.example.addon.modules.stashmover.StashMover;
import com.example.addon.settings.TrackerPlayersSetting;
import com.example.addon.util.CrashGuard;
import com.example.addon.util.smoke.AssimilatedQualitySmoke;
import com.example.addon.util.smoke.AutoWaspRuntimeValidation;
import com.example.addon.util.smoke.InputRuntimeValidation;
import com.example.addon.util.smoke.NukerPlusDamageTimeRuntimeValidation;
import com.example.addon.util.smoke.StashMoverLiveRuntimeValidation;
import com.example.addon.util.smoke.StashMoverStrictRuntimeValidation;
import com.example.addon.util.smoke.StashMoverTargetedRuntimeValidation;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.logging.LogUtils;
import java.util.ArrayList;
import java.util.List;
import meteordevelopment.meteorclient.addons.GithubRepo;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.commands.Command;
import meteordevelopment.meteorclient.commands.Commands;
import meteordevelopment.meteorclient.gui.utils.SettingsWidgetFactory;
import meteordevelopment.meteorclient.gui.widgets.containers.WTable;
import meteordevelopment.meteorclient.systems.config.Config;
import meteordevelopment.meteorclient.systems.hud.Hud;
import meteordevelopment.meteorclient.systems.hud.HudElement;
import meteordevelopment.meteorclient.systems.hud.HudElementInfo;
import meteordevelopment.meteorclient.systems.hud.HudGroup;
import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.render.color.Color;
import net.minecraft.command.CommandSource;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.Items;
import org.slf4j.Logger;

public class AddonTemplate extends MeteorAddon {
    public static final Logger LOG = LogUtils.getLogger();
    public static final Category CATEGORY = new Category("Devils", Items.NETHER_STAR.getDefaultStack());
    public static final HudGroup HUD_GROUP = new HudGroup("Devils");

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
        registerHudElements();
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
        Commands.add(new CommandExample());
        Commands.add(new AutoAnvilRenameCommand());
        Commands.add(new SessionCommand());
        Commands.add(new StashMoverCommand());
    }

    private void registerHudElements() {
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

    public static class CommandExample extends Command {
        public CommandExample() {
            super("example", "Sends a message.");
        }

        @Override
        public void build(LiteralArgumentBuilder<CommandSource> builder) {
            builder.executes(context -> {
                info("hi");
                return SINGLE_SUCCESS;
            });

            builder.then(literal("name").then(argument("nameArgument", StringArgumentType.word()).executes(context -> {
                String argument = StringArgumentType.getString(context, "nameArgument");
                info("hi, " + argument);
                return SINGLE_SUCCESS;
            })));
        }
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

    public static class HudExample extends HudElement {
        public static final HudElementInfo<HudExample> INFO = new HudElementInfo<>(
            AddonTemplate.HUD_GROUP,
            "example",
            "HUD element example.",
            HudExample::new
        );

        public HudExample() {
            super(INFO);
        }

        @Override
        public void render(HudRenderer renderer) {
            setSize(renderer.textWidth("Example element", true), renderer.textHeight(true));
            renderer.quad(x, y, getWidth(), getHeight(), Color.LIGHT_GRAY);
            renderer.text("Example element", x, y, Color.WHITE, true);
        }
    }
}

