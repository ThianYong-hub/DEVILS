package com.example.addon;

import com.example.addon.config.AddonModulesConfig;
import com.example.addon.modules.AntiWasp;
import com.example.addon.modules.AutoAnvilRename;
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
import com.example.addon.modules.Ping;
import com.example.addon.modules.SpearSpoof;
import com.example.addon.modules.SyncHub;
import com.example.addon.modules.TnTBomber;
import com.example.addon.modules.games.ChessOverlay;
import com.example.addon.modules.games.DevilsGameOverlay;
import com.example.addon.modules.games.DoomOverlay;
import com.example.addon.modules.games.GamesModule;
import com.example.addon.modules.games.BlackjackOverlay;
import com.example.addon.modules.games.RussianRouletteOverlay;
import com.example.addon.modules.games.SlotMachineOverlay;
import com.example.addon.modules.highwaybuilder.HighwayBuilder;
import com.example.addon.modules.modupdater.ModAutoUpdater;
import com.example.addon.settings.TrackerPlayersSetting;
import com.example.addon.util.CrashGuard;
import com.example.addon.util.PrismLauncherControl;
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
    public static final Category GAMES_CATEGORY = new Category("Devils-Game", Items.DIAMOND.getDefaultStack());
    public static final HudGroup HUD_GROUP = new HudGroup("Devils");

    @Override
    public void onInitialize() {
        LOG.info("Initializing Devils Addon");
        CrashGuard.installLogFilters();
        AddonModulesConfig.init();
        CrashGuard.logXaeroState();
        applyPrismMultiSessionDefaults();
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
        modules.add(new ChestTrackerModule());
        modules.add(new GamesModule());
        ChessOverlay chessOverlay = new ChessOverlay();
        DevilsGameOverlay checkersOverlay = new DevilsGameOverlay();
        BlackjackOverlay blackjackOverlay = new BlackjackOverlay();
        SlotMachineOverlay slotMachineOverlay = new SlotMachineOverlay();
        RussianRouletteOverlay russianRouletteOverlay = new RussianRouletteOverlay();
        DoomOverlay doomOverlay = new DoomOverlay();
        modules.add(chessOverlay);
        modules.add(checkersOverlay);
        modules.add(blackjackOverlay);
        modules.add(slotMachineOverlay);
        modules.add(russianRouletteOverlay);
        modules.add(doomOverlay);
        hideInternalGameModules(chessOverlay, checkersOverlay, blackjackOverlay, slotMachineOverlay, russianRouletteOverlay, doomOverlay);
    }

    private static void hideInternalGameModules(meteordevelopment.meteorclient.systems.modules.Module... modules) {
        List<meteordevelopment.meteorclient.systems.modules.Module> hidden = Config.get().hiddenModules.get();
        boolean changed = false;
        for (meteordevelopment.meteorclient.systems.modules.Module module : modules) {
            if (module == null || hidden.contains(module)) continue;
            hidden.add(module);
            changed = true;
        }
        if (changed) Config.get().save();
    }

    private void registerCommands() {
        Commands.add(new CommandExample());
        Commands.add(new AutoAnvilRenameCommand());
    }

    private void applyPrismMultiSessionDefaults() {
        try {
            MinecraftClient mc = MinecraftClient.getInstance();
            var result = PrismLauncherControl.ensureMultiSessionFlags(mc != null ? mc.runDirectory.toPath() : null);
            if (result.ok()) {
                LOG.info("[PrismControl] {} root={} cfg={}", result.message(), result.prismRoot(), result.configPath());
            } else {
                LOG.warn("[PrismControl] {}", result.message());
            }

            var restart = PrismLauncherControl.restartLauncherForParallelSameInstance(mc != null ? mc.runDirectory.toPath() : null);
            if (restart.ok()) {
                LOG.info("[PrismControl] {}", restart.message());
            } else {
                LOG.warn("[PrismControl] {}", restart.message());
            }
        } catch (Exception e) {
            LOG.warn("[PrismControl] Failed to auto-apply Prism multi-session flags: {}", e.toString());
        }
    }

    private void registerHudElements() {
        Hud.get().register(HudExample.INFO);
    }

    @Override
    public void onRegisterCategories() {
        Modules.registerCategory(CATEGORY);
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

