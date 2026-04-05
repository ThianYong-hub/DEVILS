package com.example.addon.util.smoke;

import com.example.addon.shared.sync.SyncJsonUtils;
import com.example.addon.modules.chesttracker.ChestTrackerBridge;
import com.example.addon.modules.chesttracker.ChestTrackerSettingsManager;
import com.example.addon.util.xaerosync.XaeroWaypointManagedWaypoints;
import com.blamejared.searchables.api.SearchableComponent;
import com.blamejared.searchables.api.SearchableType;
import com.blamejared.searchables.api.TokenRange;
import com.blamejared.searchables.api.autcomplete.CompletionSuggestion;
import dev.isxander.yacl3.api.Binding;
import dev.isxander.yacl3.api.Controller;
import dev.isxander.yacl3.api.Option;
import dev.isxander.yacl3.api.OptionDescription;
import dev.isxander.yacl3.api.utils.Dimension;
import dev.isxander.yacl3.gui.AbstractWidget;
import dev.isxander.yacl3.gui.YACLScreen;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.Element;
import net.minecraft.client.resource.language.I18n;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.input.MouseInput;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.entity.EntityEquipment;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import red.jackf.chesttracker.impl.gui.screen.ChestTrackerScreen;
import red.jackf.chesttracker.impl.gui.screen.EditMemoryBankScreen;
import red.jackf.chesttracker.impl.memory.MemoryBankAccessImpl;
import red.jackf.chesttracker.impl.memory.MemoryBankImpl;
import red.jackf.chesttracker.impl.memory.key.ManualMode;
import red.jackf.chesttracker.impl.memory.key.OverrideInfo;
import red.jackf.chesttracker.impl.memory.metadata.Metadata;
import red.jackf.chesttracker.impl.storage.Storage;
import red.jackf.whereisit.api.SearchRequest;
import red.jackf.whereisit.api.SearchResult;
import red.jackf.whereisit.api.criteria.builtin.NameCriterion;
import red.jackf.whereisit.client.WhereIsItClient;
import red.jackf.whereisit.client.api.events.SearchInvoker;
import red.jackf.whereisit.client.render.Rendering;
import red.jackf.whereisit.config.WhereIsItConfig;
import xaero.common.HudMod;
import xaero.common.gui.GuiAddWaypoint;
import xaero.common.minimap.waypoints.Waypoint;
import xaero.hud.minimap.BuiltInHudModules;
import xaero.hud.minimap.module.MinimapSession;
import xaero.hud.minimap.waypoint.WaypointColor;
import xaero.hud.minimap.waypoint.WaypointPurpose;
import xaero.hud.minimap.world.MinimapWorld;
import xaero.hud.path.XaeroPath;
import xaero.map.gui.IRightClickableElement;
import xaero.map.gui.dropdown.rightclick.RightClickOption;
import xaero.map.mods.gui.WaypointReader;
import xaero.map.mods.SupportMods;
import xaero.map.mods.SupportXaeroMinimap;
import xaeroplus.Globals;
import xaeroplus.module.ModuleManager;
import xaeroplus.module.impl.Portals;
import xaeroplus.module.impl.TeleportFailNotifier;
import xaeroplus.settings.BooleanSetting;
import xaeroplus.settings.Settings;

public final class AssimilatedInteractionChecks {
    private static final AtomicBoolean WHEREISIT_SYNTHETIC_LISTENER_INSTALLED = new AtomicBoolean();
    private static final AtomicBoolean WHEREISIT_SYNTHETIC_SEARCH_ENABLED = new AtomicBoolean(false);
    private static final BlockPos WHEREISIT_SYNTHETIC_RESULT_POS = new BlockPos(18, 70, -12);
    private static final Text WHEREISIT_SYNTHETIC_RESULT_NAME = Text.literal("Quality Smoke Result");

    private AssimilatedInteractionChecks() {
    }

    public static SmokeCheckResult chestTrackerManualOverrideFlow() {
        try {
            Identifier keyId = Identifier.of("devils", "quality_smoke");
            BlockPos rootPos = new BlockPos(12, 64, -8);
            MemoryBankImpl bank = new MemoryBankImpl(Metadata.blankWithName("Quality Smoke"), new HashMap<>());
            bank.setId("quality-smoke");
            bank.setManualModeOverride(keyId, rootPos, ManualMode.REMEMBER);
            bank.setNameOverride(keyId, rootPos, "Vault Stash");

            var key = bank.getKeyInternal(keyId).orElse(null);
            if (key == null) return SmokeCheckResult.fail("chesttracker-override", "memory key was not created");

            OverrideInfo overrideInfo = key.overrides().get(rootPos);
            if (overrideInfo == null) return SmokeCheckResult.fail("chesttracker-override", "override info missing");
            if (overrideInfo.getManualMode() != ManualMode.REMEMBER) {
                return SmokeCheckResult.fail("chesttracker-override", "manual mode override mismatch");
            }
            if (!"Vault Stash".equals(overrideInfo.getCustomName())) {
                return SmokeCheckResult.fail("chesttracker-override", "custom name override mismatch");
            }

            return SmokeCheckResult.pass("chesttracker-override", "key=" + keyId + " manual=" + overrideInfo.getManualMode() + " customName=" + overrideInfo.getCustomName());
        } catch (Throwable t) {
            return SmokeCheckResult.fail("chesttracker-override", t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    public static SmokeCheckResult whereIsItFocusedSlotUiFlow(MinecraftClient client) {
        Screen previousScreen = client.currentScreen;
        try {
            SmokeScreenHandler handler = new SmokeScreenHandler(new ItemStack(Items.DIAMOND, 1));
            SmokeHandledScreen screen = new SmokeHandledScreen(handler, Text.literal("WhereIsIt Quality Smoke"));
            client.setScreen(screen);
            setFieldValue(HandledScreen.class, screen, "focusedSlot", handler.smokeSlot);

            Method createRequest = WhereIsItClient.class.getDeclaredMethod("createRequest", MinecraftClient.class, Screen.class);
            createRequest.setAccessible(true);
            SearchRequest request = (SearchRequest) createRequest.invoke(null, client, screen);
            if (request == null || !request.hasCriteria()) {
                return SmokeCheckResult.fail("whereisit-ui-search", "focused slot did not produce a search request");
            }

            String requestString = request.toString();
            String tagString = request.toTag().toString();
            if (!requestString.toLowerCase().contains("diamond") && !tagString.toLowerCase().contains("diamond")) {
                return SmokeCheckResult.fail(
                    "whereisit-ui-search",
                    "request missing diamond criterion request=" + requestString + " tag=" + tagString
                );
            }

            return SmokeCheckResult.pass(
                "whereisit-ui-search",
                "focusedSlot=diamond request=" + requestString + " tag=" + tagString
            );
        } catch (Throwable t) {
            return SmokeCheckResult.fail("whereisit-ui-search", t.getClass().getSimpleName() + ": " + t.getMessage());
        } finally {
            client.setScreen(previousScreen);
        }
    }

    public static SmokeCheckResult whereIsItRequestFlow() {
        try {
            SearchRequest request = new SearchRequest();
            request.accept(new NameCriterion("stash"));

            String requestString = request.toString();
            if (!requestString.toLowerCase().contains("stash")) {
                return SmokeCheckResult.fail("whereisit-request", "request builder lost criterion payload");
            }

            return SmokeCheckResult.pass("whereisit-request", "criteria=" + requestString + " hasCriteria=" + request.hasCriteria());
        } catch (Throwable t) {
            return SmokeCheckResult.fail("whereisit-request", t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    public static SmokeCheckResult chestTrackerGuiEditFlow(MinecraftClient client) {
        Screen previousScreen = client.currentScreen;
        String bankId = "quality-ui-" + Long.toUnsignedString(System.nanoTime(), 36);
        try {
            MemoryBankImpl bank = new MemoryBankImpl(Metadata.blankWithName("Quality Smoke Original"), new HashMap<>());
            bank.setId(bankId);
            Storage.save(bank);

            Constructor<EditMemoryBankScreen> constructor = EditMemoryBankScreen.class.getDeclaredConstructor(Screen.class, Runnable.class, String.class);
            constructor.setAccessible(true);
            EditMemoryBankScreen screen = constructor.newInstance(previousScreen, (Runnable) () -> { }, bankId);
            client.setScreen(screen);

            TextFieldWidget nameEditBox = (TextFieldWidget) getFieldValue(EditMemoryBankScreen.class, screen, "nameEditBox");
            if (nameEditBox == null) {
                return SmokeCheckResult.fail("chesttracker-ui-edit", "nameEditBox was not initialized");
            }

            String updatedName = "Quality Smoke Renamed";
            nameEditBox.setText(updatedName);
            Method save = EditMemoryBankScreen.class.getDeclaredMethod("save", ButtonWidget.class);
            save.setAccessible(true);
            save.invoke(screen, new Object[]{null});

            MemoryBankImpl loaded = Storage.load(bankId).orElse(null);
            if (loaded == null) {
                return SmokeCheckResult.fail("chesttracker-ui-edit", "saved bank could not be reloaded");
            }

            String loadedName = loaded.getMetadata().getName();
            if (!updatedName.equals(loadedName)) {
                return SmokeCheckResult.fail("chesttracker-ui-edit", "reloaded name mismatch: " + loadedName);
            }

            return SmokeCheckResult.pass("chesttracker-ui-edit", "bankId=" + bankId + " name=" + loadedName);
        } catch (Throwable t) {
            return SmokeCheckResult.fail("chesttracker-ui-edit", t.getClass().getSimpleName() + ": " + t.getMessage());
        } finally {
            try {
                Storage.delete(bankId);
            } catch (Throwable ignored) {
            }
            client.setScreen(previousScreen);
        }
    }

    public static SmokeCheckResult chestTrackerPointerDrivenSettingsFlow(MinecraftClient client) {
        Screen previousScreen = client.currentScreen;
        String bankId = "quality-screen-" + Long.toUnsignedString(System.nanoTime(), 36);
        try {
            if (!MemoryBankAccessImpl.INSTANCE.loadOrCreate(bankId, "Quality Screen Original")) {
                return SmokeCheckResult.fail("chesttracker-ui-deep", "loadOrCreate failed for " + bankId);
            }

            ChestTrackerScreen screen = new ChestTrackerScreen(previousScreen);
            client.setScreen(screen);
            if (!(client.currentScreen instanceof ChestTrackerScreen liveScreen)) {
                return SmokeCheckResult.fail("chesttracker-ui-deep", "client did not open ChestTrackerScreen");
            }

            int left = (int) getFieldValue(ChestTrackerScreen.class, liveScreen, "left");
            int top = (int) getFieldValue(ChestTrackerScreen.class, liveScreen, "top");
            int menuWidth = (int) getFieldValue(ChestTrackerScreen.class, liveScreen, "menuWidth");
            double settingsX = left + menuWidth - 68 + 7;
            double settingsY = top + 5 + 7;
            boolean settingsClicked = liveScreen.mouseClicked(primaryClick(settingsX, settingsY), false);
            if (!settingsClicked) {
                return SmokeCheckResult.fail("chesttracker-ui-deep", "memory-bank settings button did not handle pointer click");
            }
            if (!(client.currentScreen instanceof EditMemoryBankScreen editScreen)) {
                return SmokeCheckResult.fail("chesttracker-ui-deep", "settings button did not open EditMemoryBankScreen");
            }

            TextFieldWidget nameEditBox = (TextFieldWidget) getFieldValue(EditMemoryBankScreen.class, editScreen, "nameEditBox");
            if (nameEditBox == null) {
                return SmokeCheckResult.fail("chesttracker-ui-deep", "edit screen nameEditBox was not initialized");
            }

            String updatedName = "Quality Screen Updated";
            nameEditBox.setText(updatedName);
            String saveLabel = I18n.translate("selectWorld.edit.save");
            ButtonWidget saveButton = findButtonByMessage(editScreen, saveLabel);
            if (saveButton == null) {
                return SmokeCheckResult.fail("chesttracker-ui-deep", "save button was not found on EditMemoryBankScreen");
            }

            boolean saveClicked = editScreen.mouseClicked(primaryClick(saveButton.getX() + 1, saveButton.getY() + 1), false);
            if (!saveClicked) {
                return SmokeCheckResult.fail("chesttracker-ui-deep", "save button did not handle pointer click");
            }
            if (!(client.currentScreen instanceof ChestTrackerScreen)) {
                return SmokeCheckResult.fail("chesttracker-ui-deep", "save action did not return to ChestTrackerScreen");
            }

            MemoryBankImpl reloaded = Storage.load(bankId).orElse(null);
            if (reloaded == null) {
                return SmokeCheckResult.fail("chesttracker-ui-deep", "saved bank could not be reloaded after pointer-driven flow");
            }
            if (!updatedName.equals(reloaded.getMetadata().getName())) {
                return SmokeCheckResult.fail("chesttracker-ui-deep", "persisted name mismatch after pointer-driven flow: " + reloaded.getMetadata().getName());
            }

            return SmokeCheckResult.pass(
                "chesttracker-ui-deep",
                "screenFlow=ChestTrackerScreen->EditMemoryBankScreen->ChestTrackerScreen bankId=" + bankId + " name=" + reloaded.getMetadata().getName()
            );
        } catch (Throwable t) {
            return SmokeCheckResult.fail("chesttracker-ui-deep", t.getClass().getSimpleName() + ": " + t.getMessage());
        } finally {
            try {
                MemoryBankAccessImpl.INSTANCE.unload();
            } catch (Throwable ignored) {
            }
            try {
                Storage.delete(bankId);
            } catch (Throwable ignored) {
            }
            client.setScreen(previousScreen);
        }
    }

    public static SmokeCheckResult searchablesFlow() {
        try {
            record Entry(String name, String tag) {
            }

            List<Entry> entries = List.of(
                new Entry("Alpha Crate", "mineral"),
                new Entry("Beta Chest", "farming"),
                new Entry("Gamma Locker", "alchemy")
            );

            SearchableType<Entry> searchableType = new SearchableType.Builder<Entry>()
                .defaultComponent("name", SearchableComponent.create("name", entry -> Optional.of(entry.name())))
                .component("tag", SearchableComponent.create("tag", entry -> Optional.of(entry.tag())))
                .build();

            List<Entry> filtered = searchableType.filterEntries(entries, "tag:mineral");
            if (filtered.size() != 1 || !"Alpha Crate".equals(filtered.get(0).name())) {
                return SmokeCheckResult.fail("searchables-flow", "component filter did not isolate Alpha Crate");
            }

            List<CompletionSuggestion> componentSuggestions = searchableType.getSuggestionsForComponent("na", TokenRange.between(0, 2));
            List<CompletionSuggestion> termSuggestions = searchableType.getSuggestionsForTerm(entries, "tag", "mi", TokenRange.between(0, 2));
            if (componentSuggestions.stream().noneMatch(suggestion -> "name:".equals(suggestion.toInsert()))) {
                return SmokeCheckResult.fail("searchables-flow", "missing component autocomplete for name");
            }
            if (termSuggestions.stream().noneMatch(suggestion -> suggestion.toInsert().startsWith("tag:mineral"))) {
                return SmokeCheckResult.fail("searchables-flow", "missing term autocomplete for mineral");
            }

            return SmokeCheckResult.pass(
                "searchables-flow",
                "filtered=" + filtered.get(0).name() + " componentSuggestions=" + componentSuggestions.size() + " termSuggestions=" + termSuggestions.size()
            );
        } catch (Throwable t) {
            return SmokeCheckResult.fail("searchables-flow", t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    public static SmokeCheckResult yaclOptionLifecycle() {
        try {
            AtomicBoolean storage = new AtomicBoolean(true);
            Option<Boolean> option = Option.<Boolean>createBuilder()
                .name(Text.literal("Quality Smoke Toggle"))
                .description(OptionDescription.of(Text.literal("Quality smoke option lifecycle check")))
                .binding(Binding.generic(true, storage::get, storage::set))
                .customController(opt -> new Controller<>() {
                    @Override
                    public Option<Boolean> option() {
                        return opt;
                    }

                    @Override
                    public Text formatValue() {
                        return Text.literal(Boolean.toString(opt.pendingValue()));
                    }

                    @Override
                    public AbstractWidget provideWidget(YACLScreen screen, Dimension<Integer> widgetDimension) {
                        return null;
                    }
                })
                .build();

            option.requestSet(false);
            if (!option.changed() || option.pendingValue()) {
                return SmokeCheckResult.fail("yacl-option", "requestSet did not change pending state");
            }

            boolean applied = option.applyValue();
            if (!applied || storage.get()) {
                return SmokeCheckResult.fail("yacl-option", "applyValue did not persist updated binding");
            }

            option.requestSetDefault();
            if (!option.isPendingValueDefault() || !option.pendingValue()) {
                return SmokeCheckResult.fail("yacl-option", "requestSetDefault did not restore pending default");
            }

            option.forgetPendingValue();
            if (option.changed() || storage.get()) {
                return SmokeCheckResult.fail("yacl-option", "forgetPendingValue should keep applied value while syncing pending state");
            }

            return SmokeCheckResult.pass("yacl-option", "applied=false defaultPending=true storage=" + storage.get());
        } catch (Throwable t) {
            return SmokeCheckResult.fail("yacl-option", t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    public static SmokeCheckResult xaeroRefreshHookFlow() {
        try {
            Class<?> supportClass = SupportXaeroMinimap.class;
            supportClass.getDeclaredMethod("requestWaypointsRefresh");
            supportClass.getDeclaredField("refreshWaypoints");
            SupportMods.class.getDeclaredField("xaeroMinimap");
            XaeroWaypointManagedWaypoints.class.getDeclaredMethod("requestWaypointsRefresh");

            return SmokeCheckResult.pass(
                "xaero-refresh-hook",
                "supportClass=" + supportClass.getName() + " method=requestWaypointsRefresh field=refreshWaypoints helper=" + XaeroWaypointManagedWaypoints.class.getName()
            );
        } catch (Throwable t) {
            return SmokeCheckResult.fail("xaero-refresh-hook", t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    public static SmokeCheckResult xaeroWaypointGuiCreateFlow(MinecraftClient client) {
        Screen previousScreen = client.currentScreen;
        MinimapSession session = null;
        boolean syntheticSession = false;
        XaeroPath previousAutoWorldPath = null;
        XaeroPath previousCustomWorldPath = null;
        Waypoint createdWaypoint = null;
        MinimapWorld smokeWorld = null;
        try {
            session = BuiltInHudModules.MINIMAP.getCurrentSession();
            if (session == null) {
                session = createSyntheticMinimapSession();
                syntheticSession = session != null;
            }
            if (session == null) return SmokeCheckResult.fail("xaero-waypoint-ui", "minimap session unavailable");

            XaeroPath rootPath = session.getWorldState().getAutoRootContainerPath();
            if (rootPath == null) return SmokeCheckResult.fail("xaero-waypoint-ui", "auto root container path unavailable");

            previousAutoWorldPath = session.getWorldState().getAutoWorldPath();
            previousCustomWorldPath = session.getWorldState().getCustomWorldPath();
            XaeroPath smokeWorldPath = rootPath.resolve("quality_smoke_ui");
            session.getWorldState().setAutoWorldPath(smokeWorldPath);
            session.getWorldState().setCustomWorldPath(null);

            smokeWorld = session.getWorldManager().getWorld(smokeWorldPath);
            if (smokeWorld == null || smokeWorld.getCurrentWaypointSet() == null) {
                return SmokeCheckResult.fail("xaero-waypoint-ui", "synthetic waypoint world could not be created");
            }

            GuiAddWaypoint screen = new GuiAddWaypoint(
                HudMod.INSTANCE,
                session,
                previousScreen,
                null,
                new ArrayList<>(),
                rootPath,
                smokeWorld,
                smokeWorld.getCurrentWaypointSetId(),
                true,
                true,
                21,
                70,
                -14,
                1.0,
                smokeWorld
            );
            screen.init(client.getWindow().getScaledWidth(), client.getWindow().getScaledHeight());

            TextFieldWidget nameTextField = (TextFieldWidget) getFieldValue(GuiAddWaypoint.class, screen, "nameTextField");
            TextFieldWidget xTextField = (TextFieldWidget) getFieldValue(GuiAddWaypoint.class, screen, "xTextField");
            TextFieldWidget yTextField = (TextFieldWidget) getFieldValue(GuiAddWaypoint.class, screen, "yTextField");
            TextFieldWidget zTextField = (TextFieldWidget) getFieldValue(GuiAddWaypoint.class, screen, "zTextField");
            TextFieldWidget initialTextField = (TextFieldWidget) getFieldValue(GuiAddWaypoint.class, screen, "initialTextField");
            ButtonWidget confirmButton = (ButtonWidget) getFieldValue(GuiAddWaypoint.class, screen, "confirmButton");
            if (nameTextField == null || xTextField == null || yTextField == null || zTextField == null || initialTextField == null || confirmButton == null) {
                return SmokeCheckResult.fail("xaero-waypoint-ui", "waypoint editor widgets were not initialized");
            }

            nameTextField.setText("Quality Smoke Waypoint");
            xTextField.setText("21");
            yTextField.setText("70");
            zTextField.setText("-14");
            initialTextField.setText("Q");
            Method checkFields = GuiAddWaypoint.class.getDeclaredMethod("checkFields", Element.class);
            checkFields.setAccessible(true);
            checkFields.invoke(screen, nameTextField);
            Method updateConfirmButton = GuiAddWaypoint.class.getDeclaredMethod("updateConfirmButton");
            updateConfirmButton.setAccessible(true);
            updateConfirmButton.invoke(screen);
            if (!confirmButton.active) {
                return SmokeCheckResult.fail("xaero-waypoint-ui", "confirm button did not activate after field edits");
            }

            confirmButton.onClick(new Click(0.0, 0.0, new MouseInput(0, 0)), false);
            for (Waypoint waypoint : smokeWorld.getCurrentWaypointSet().getWaypoints()) {
                if ("Quality Smoke Waypoint".equals(waypoint.getName())) {
                    createdWaypoint = waypoint;
                    break;
                }
            }

            if (createdWaypoint == null) {
                return SmokeCheckResult.fail("xaero-waypoint-ui", "waypoint was not created in the current set");
            }
            if (createdWaypoint.getX() != 21 || createdWaypoint.getY() != 70 || createdWaypoint.getZ() != -14) {
                return SmokeCheckResult.fail(
                    "xaero-waypoint-ui",
                    "created waypoint coords mismatch: " + createdWaypoint.getX() + "," + createdWaypoint.getY() + "," + createdWaypoint.getZ()
                );
            }

            return SmokeCheckResult.pass(
                "xaero-waypoint-ui",
                "world=" + smokeWorld.getFullPath() + " name=" + createdWaypoint.getName() + " initials=" + createdWaypoint.getInitials()
            );
        } catch (Throwable t) {
            return SmokeCheckResult.fail("xaero-waypoint-ui", t.getClass().getSimpleName() + ": " + t.getMessage());
        } finally {
            if (smokeWorld != null && createdWaypoint != null) {
                try {
                    smokeWorld.getCurrentWaypointSet().remove(createdWaypoint);
                    session.getWorldManagerIO().saveWorld(smokeWorld);
                } catch (Throwable ignored) {
                }
            }
            if (session != null) {
                session.getWorldState().setAutoWorldPath(previousAutoWorldPath);
                session.getWorldState().setCustomWorldPath(previousCustomWorldPath);
                if (syntheticSession) {
                    try {
                        session.close();
                    } catch (Throwable ignored) {
                    }
                }
            }
            client.setScreen(previousScreen);
        }
    }

    public static SmokeCheckResult xaeroWaypointListEditFlow(MinecraftClient client) {
        Screen previousScreen = client.currentScreen;
        MinimapSession session = null;
        boolean syntheticSession = false;
        XaeroPath previousAutoWorldPath = null;
        XaeroPath previousCustomWorldPath = null;
        MinimapWorld smokeWorld = null;
        Waypoint waypoint = null;
        try {
            session = BuiltInHudModules.MINIMAP.getCurrentSession();
            if (session == null) {
                session = createSyntheticMinimapSession();
                syntheticSession = session != null;
            }
            if (session == null) return SmokeCheckResult.fail("xaero-waypoint-ui-deep", "minimap session unavailable");

            XaeroPath rootPath = session.getWorldState().getAutoRootContainerPath();
            if (rootPath == null) return SmokeCheckResult.fail("xaero-waypoint-ui-deep", "auto root container path unavailable");

            previousAutoWorldPath = session.getWorldState().getAutoWorldPath();
            previousCustomWorldPath = session.getWorldState().getCustomWorldPath();
            XaeroPath smokeWorldPath = rootPath.resolve("quality_smoke_ui_edit");
            session.getWorldState().setAutoWorldPath(smokeWorldPath);
            session.getWorldState().setCustomWorldPath(null);

            smokeWorld = session.getWorldManager().getWorld(smokeWorldPath);
            if (smokeWorld == null || smokeWorld.getCurrentWaypointSet() == null) {
                return SmokeCheckResult.fail("xaero-waypoint-ui-deep", "synthetic waypoint world could not be created");
            }

            waypoint = new Waypoint(21, 70, -14, "Quality Smoke Waypoint", "Q", WaypointColor.PURPLE, WaypointPurpose.NORMAL, false, true);
            smokeWorld.getCurrentWaypointSet().add(waypoint);

            GuiAddWaypoint screen = new GuiAddWaypoint(
                HudMod.INSTANCE,
                session,
                previousScreen,
                null,
                new ArrayList<>(List.of(waypoint)),
                rootPath,
                smokeWorld,
                smokeWorld.getCurrentWaypointSetId(),
                false,
                true,
                21,
                70,
                -14,
                1.0,
                smokeWorld
            );
            screen.init(client.getWindow().getScaledWidth(), client.getWindow().getScaledHeight());
            GuiAddWaypoint editScreen = screen;

            TextFieldWidget nameTextField = (TextFieldWidget) getFieldValue(GuiAddWaypoint.class, editScreen, "nameTextField");
            ButtonWidget confirmButton = (ButtonWidget) getFieldValue(GuiAddWaypoint.class, editScreen, "confirmButton");
            if (nameTextField == null || confirmButton == null) {
                return SmokeCheckResult.fail("xaero-waypoint-ui-deep", "edit dialog widgets were not initialized");
            }

            String editedName = "Quality Smoke Waypoint Edited";
            nameTextField.setText(editedName);
            Method checkFields = GuiAddWaypoint.class.getDeclaredMethod("checkFields", Element.class);
            checkFields.setAccessible(true);
            checkFields.invoke(editScreen, nameTextField);
            Method updateConfirmButton = GuiAddWaypoint.class.getDeclaredMethod("updateConfirmButton");
            updateConfirmButton.setAccessible(true);
            updateConfirmButton.invoke(editScreen);
            if (!confirmButton.active) {
                return SmokeCheckResult.fail("xaero-waypoint-ui-deep", "confirm button did not activate for waypoint edit");
            }

            boolean confirmClicked = editScreen.mouseClicked(primaryClick(confirmButton.getX() + 1, confirmButton.getY() + 1), false);
            if (!confirmClicked) {
                return SmokeCheckResult.fail("xaero-waypoint-ui-deep", "confirm button did not handle pointer click");
            }
            if (!editedName.equals(waypoint.getName())) {
                return SmokeCheckResult.fail("xaero-waypoint-ui-deep", "waypoint name did not update after edit flow: " + waypoint.getName());
            }

            return SmokeCheckResult.pass(
                "xaero-waypoint-ui-deep",
                "screenFlow=GuiAddWaypoint(existing local edit) world=" + smokeWorld.getFullPath() + " name=" + waypoint.getName()
            );
        } catch (Throwable t) {
            StackTraceElement top = t.getStackTrace().length > 0 ? t.getStackTrace()[0] : null;
            String location = top == null ? "unknown" : top.getClassName() + ":" + top.getLineNumber();
            return SmokeCheckResult.fail("xaero-waypoint-ui-deep", t.getClass().getSimpleName() + ": " + t.getMessage() + " @" + location);
        } finally {
            if (smokeWorld != null && waypoint != null) {
                try {
                    smokeWorld.getCurrentWaypointSet().remove(waypoint);
                    session.getWorldManagerIO().saveWorld(smokeWorld);
                } catch (Throwable ignored) {
                }
            }
            if (session != null) {
                session.getWorldState().setAutoWorldPath(previousAutoWorldPath);
                session.getWorldState().setCustomWorldPath(previousCustomWorldPath);
                if (syntheticSession) {
                    try {
                        session.close();
                    } catch (Throwable ignored) {
                    }
                }
            }
            client.setScreen(previousScreen);
        }
    }

    public static SmokeCheckResult xaeroPlusSettingLifecycle() {
        try {
            BooleanSetting setting = BooleanSetting.create("Quality Smoke Toggle", "xaeroplus.setting.quality_smoke", false);
            boolean original = setting.get();
            boolean toggled = !original;

            setting.setValue(toggled);
            String serialized = setting.getSerializedValue();
            if (!Boolean.toString(toggled).equals(serialized)) {
                return SmokeCheckResult.fail("xaeroplus-setting", "serialized value mismatch after toggle");
            }

            setting.deserializeValue(Boolean.toString(original));
            if (setting.get() != original) {
                return SmokeCheckResult.fail("xaeroplus-setting", "deserialize did not restore original value");
            }

            return SmokeCheckResult.pass(
                "xaeroplus-setting",
                "setting=" + setting.getSettingName() + " toggled=" + toggled + " restored=" + original
            );
        } catch (Throwable t) {
            return SmokeCheckResult.fail("xaeroplus-setting", t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    public static SmokeCheckResult xaeroPlusCopyCoordinatesHookFlow(MinecraftClient client) {
        BooleanSetting setting = Settings.REGISTRY.teleportFailNotifier;
        TeleportFailNotifier module = ModuleManager.getModule(TeleportFailNotifier.class);
        boolean original = setting.get();
        try {
            if (module == null) {
                return SmokeCheckResult.fail("xaeroplus-module-hook", "TeleportFailNotifier module is unavailable");
            }
            boolean toggled = !original;
            setting.setValue(toggled);
            if (module.isEnabled() != toggled) {
                return SmokeCheckResult.fail("xaeroplus-module-hook", "module state did not follow toggled setting");
            }
            setting.setValue(original);
            if (module.isEnabled() != original) {
                return SmokeCheckResult.fail("xaeroplus-module-hook", "module state did not restore original setting");
            }

            return SmokeCheckResult.pass(
                "xaeroplus-module-hook",
                "setting=" + setting.getSettingName() + " toggled=" + toggled + " restored=" + original + " module=" + module.getClass().getSimpleName()
            );
        } catch (Throwable t) {
            return SmokeCheckResult.fail("xaeroplus-module-hook", t.getClass().getSimpleName() + ": " + t.getMessage());
        } finally {
            try {
                setting.setValue(original);
            } catch (Throwable ignored) {
            }
        }
    }

    public static SmokeCheckResult xaeroPlusPortalsFeatureFlow() {
        BooleanSetting enabledSetting = Settings.REGISTRY.portalsEnabledSetting;
        var alphaSetting = Settings.REGISTRY.portalsAlphaSetting;
        Portals module = ModuleManager.getModule(Portals.class);
        boolean originalEnabledSetting = enabledSetting.get();
        boolean originalEnabled = module != null && module.isEnabled();
        double originalAlpha = alphaSetting.get();
        try {
            if (module == null) {
                return SmokeCheckResult.fail("xaeroplus-portals-hook", "Portals module is unavailable");
            }

            enabledSetting.setValue(true);
            if (!module.isEnabled()) {
                return SmokeCheckResult.fail("xaeroplus-portals-hook", "Portals module did not enable through setting hook");
            }

            List<String> featureIds = new ArrayList<>();
            Globals.drawManager.registry().forEach(feature -> featureIds.add(feature.id()));
            if (featureIds.stream().noneMatch("Portals"::equals)) {
                return SmokeCheckResult.fail("xaeroplus-portals-hook", "draw feature registry did not expose Portals hook");
            }

            int colorBeforeAlphaChange = module.getPortalsColor();
            double toggledAlpha = originalAlpha == 100.0 ? 140.0 : 100.0;
            alphaSetting.setValue(toggledAlpha);
            int colorAfterAlphaChange = module.getPortalsColor();
            if (colorBeforeAlphaChange == colorAfterAlphaChange) {
                return SmokeCheckResult.fail("xaeroplus-portals-hook", "portal color did not react to alpha setting callback");
            }

            enabledSetting.setValue(false);
            if (module.isEnabled()) {
                return SmokeCheckResult.fail("xaeroplus-portals-hook", "Portals module did not disable through setting hook");
            }

            List<String> featureIdsAfterDisable = new ArrayList<>();
            Globals.drawManager.registry().forEach(feature -> featureIdsAfterDisable.add(feature.id()));
            if (featureIdsAfterDisable.stream().anyMatch("Portals"::equals)) {
                return SmokeCheckResult.fail("xaeroplus-portals-hook", "Portals draw feature stayed registered after disable");
            }

            return SmokeCheckResult.pass(
                "xaeroplus-portals-hook",
                "enabledViaSetting=true alpha=" + toggledAlpha + " featureIds=" + featureIds
            );
        } catch (Throwable t) {
            return SmokeCheckResult.fail("xaeroplus-portals-hook", t.getClass().getSimpleName() + ": " + t.getMessage());
        } finally {
            try {
                alphaSetting.setValue(originalAlpha);
            } catch (Throwable ignored) {
            }
            try {
                enabledSetting.setValue(originalEnabledSetting);
                if (module != null && module.isEnabled() != originalEnabled) {
                    module.setEnabled(originalEnabled);
                }
            } catch (Throwable ignored) {
            }
        }
    }

    public static SmokeCheckResult chestTrackerModuleSettingsRoundTripFlow() {
        Path tempDir = null;
        try {
            tempDir = Files.createTempDirectory("devils-chesttracker-smoke");

            Setting<Boolean> inventoryButton = new BoolSetting.Builder().name("inventory-button").defaultValue(true).build();
            Setting<Boolean> devilsTheme = new BoolSetting.Builder().name("devils-theme").defaultValue(true).build();
            Setting<SettingColor> accentColor = new ColorSetting.Builder().name("accent-color").defaultValue(new SettingColor(92, 0, 0, 255)).build();
            Setting<Integer> overlayAlpha = new IntSetting.Builder().name("overlay-alpha").defaultValue(96).range(0, 255).build();
            Setting<Boolean> asyncSaving = new BoolSetting.Builder().name("async-saving").defaultValue(false).build();
            Setting<Boolean> syncVerbose = new BoolSetting.Builder().name("sync-verbose").defaultValue(false).build();

            ChestTrackerSettingsManager manager = new ChestTrackerSettingsManager(
                new ChestTrackerBridge("missing.RuntimeState", "missing.Config", "missing.BackendType"),
                () -> true,
                inventoryButton,
                devilsTheme,
                accentColor,
                overlayAlpha,
                asyncSaving,
                syncVerbose
            );

            inventoryButton.set(false);
            devilsTheme.set(true);
            accentColor.set(new SettingColor(92, 0, 0, 255));
            overlayAlpha.set(111);
            asyncSaving.set(true);
            syncVerbose.set(true);
            manager.saveLocalSettings(tempDir);

            inventoryButton.set(true);
            devilsTheme.set(false);
            accentColor.set(new SettingColor(1, 2, 3, 4));
            overlayAlpha.set(5);
            asyncSaving.set(false);
            syncVerbose.set(false);
            manager.loadLocalSettings(tempDir);

            Path savedFile = tempDir.resolve("module-settings.json");
            if (!Files.isRegularFile(savedFile)) {
                return SmokeCheckResult.fail("chesttracker-module-settings", "module-settings.json was not written");
            }
            if (inventoryButton.get()) return SmokeCheckResult.fail("chesttracker-module-settings", "inventoryButton was not restored");
            if (!devilsTheme.get()) return SmokeCheckResult.fail("chesttracker-module-settings", "devilsTheme was not restored");
            if (overlayAlpha.get() != 111) return SmokeCheckResult.fail("chesttracker-module-settings", "overlayAlpha mismatch");
            if (!asyncSaving.get()) return SmokeCheckResult.fail("chesttracker-module-settings", "asyncSaving mismatch");
            if (!syncVerbose.get()) return SmokeCheckResult.fail("chesttracker-module-settings", "syncVerbose mismatch");

            SettingColor restoredColor = accentColor.get();
            if (restoredColor.r != 92 || restoredColor.g != 0 || restoredColor.b != 0 || restoredColor.a != 255) {
                return SmokeCheckResult.fail("chesttracker-module-settings", "accentColor mismatch");
            }

            return SmokeCheckResult.pass(
                "chesttracker-module-settings",
                "path=" + savedFile + " overlayAlpha=" + overlayAlpha.get() + " asyncSaving=" + asyncSaving.get()
            );
        } catch (Throwable t) {
            return SmokeCheckResult.fail("chesttracker-module-settings", t.getClass().getSimpleName() + ": " + t.getMessage());
        } finally {
            if (tempDir != null) {
                try {
                    Files.walk(tempDir)
                        .sorted((left, right) -> right.getNameCount() - left.getNameCount())
                        .forEach(path -> {
                            try {
                                Files.deleteIfExists(path);
                            } catch (Exception ignored) {
                            }
                        });
                } catch (Exception ignored) {
                }
            }
        }
    }

    public static SmokeCheckResult chestTrackerLocalSettingsPayloadFlow() {
        Path tempDir = null;
        try {
            tempDir = Files.createTempDirectory("devils-chesttracker-payload");
            Path payloadPath = tempDir.resolve("module-settings.json");

            String payload = """
                {
                  "version": 1,
                  "inventoryButton": false,
                  "devilsTheme": true,
                  "accentR": 92,
                  "accentG": 0,
                  "accentB": 0,
                  "accentA": 255,
                  "overlayAlpha": 111,
                  "asyncSaving": true,
                  "syncVerbose": true
                }
                """;
            Files.writeString(payloadPath, payload, java.nio.charset.StandardCharsets.UTF_8);

            var json = SyncJsonUtils.parseJsonObject(Files.readString(payloadPath, java.nio.charset.StandardCharsets.UTF_8));
            if (json == null) return SmokeCheckResult.fail("chesttracker-local-settings", "payload did not parse");
            if (SyncJsonUtils.readBoolean(json, "inventoryButton", true)) {
                return SmokeCheckResult.fail("chesttracker-local-settings", "inventoryButton roundtrip mismatch");
            }
            if (!SyncJsonUtils.readBoolean(json, "devilsTheme", false)) {
                return SmokeCheckResult.fail("chesttracker-local-settings", "devilsTheme roundtrip mismatch");
            }
            if (SyncJsonUtils.readInt(json, "overlayAlpha", -1) != 111) {
                return SmokeCheckResult.fail("chesttracker-local-settings", "overlayAlpha roundtrip mismatch");
            }
            if (!SyncJsonUtils.readBoolean(json, "asyncSaving", false) || !SyncJsonUtils.readBoolean(json, "syncVerbose", false)) {
                return SmokeCheckResult.fail("chesttracker-local-settings", "storage booleans roundtrip mismatch");
            }

            return SmokeCheckResult.pass(
                "chesttracker-local-settings",
                "path=" + payloadPath + " overlayAlpha=" + SyncJsonUtils.readInt(json, "overlayAlpha", -1) + " asyncSaving=" + SyncJsonUtils.readBoolean(json, "asyncSaving", false)
            );
        } catch (Throwable t) {
            return SmokeCheckResult.fail("chesttracker-local-settings", t.getClass().getSimpleName() + ": " + t.getMessage());
        } finally {
            if (tempDir != null) {
                try {
                    Files.walk(tempDir)
                        .sorted((left, right) -> right.getNameCount() - left.getNameCount())
                        .forEach(path -> {
                            try {
                                Files.deleteIfExists(path);
                            } catch (Exception ignored) {
                            }
                        });
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static Object getFieldValue(Class<?> owner, Object instance, String name) throws ReflectiveOperationException {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(instance);
    }

    private static void setFieldValue(Class<?> owner, Object instance, String name, Object value) throws ReflectiveOperationException {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(instance, value);
    }

    private static ButtonWidget findButtonByMessage(Screen screen, String message) {
        for (Element element : screen.children()) {
            if (element instanceof ButtonWidget button && message.equals(button.getMessage().getString())) {
                return button;
            }
        }
        return null;
    }

    private static Click primaryClick(double x, double y) {
        return new Click(x, y, new MouseInput(0, 0));
    }

    private static void ensureWhereIsItSyntheticListener() {
        if (WHEREISIT_SYNTHETIC_LISTENER_INSTALLED.compareAndSet(false, true)) {
            SearchInvoker.EVENT.register((request, resultConsumer) -> {
                if (!WHEREISIT_SYNTHETIC_SEARCH_ENABLED.get()) {
                    return false;
                }

                resultConsumer.accept(List.of(
                    SearchResult.builder(WHEREISIT_SYNTHETIC_RESULT_POS)
                        .item(new ItemStack(Items.DIAMOND, 1))
                        .name(WHEREISIT_SYNTHETIC_RESULT_NAME, new Vec3d(0.0, 1.0, 0.0))
                        .build()
                ));
                return true;
            });
        }
    }

    public static SmokeCheckResult whereIsItRenderedSearchFlow(MinecraftClient client) {
        Screen previousScreen = client.currentScreen;
        boolean previousCloseGuiOnFoundResults = false;
        try {
            ensureWhereIsItSyntheticListener();

            WhereIsItConfig config = (WhereIsItConfig) WhereIsItConfig.INSTANCE.instance();
            previousCloseGuiOnFoundResults = config.getClient().closeGuiOnFoundResults;
            config.getClient().closeGuiOnFoundResults = false;

            SmokeScreenHandler handler = new SmokeScreenHandler(new ItemStack(Items.DIAMOND, 1));
            SmokeHandledScreen screen = new SmokeHandledScreen(handler, Text.literal("WhereIsIt Deep Smoke"));
            client.setScreen(screen);
            setFieldValue(HandledScreen.class, screen, "focusedSlot", handler.smokeSlot);

            Method createRequest = WhereIsItClient.class.getDeclaredMethod("createRequest", MinecraftClient.class, Screen.class);
            createRequest.setAccessible(true);
            SearchRequest request = (SearchRequest) createRequest.invoke(null, client, screen);
            if (request == null || !request.hasCriteria()) {
                return SmokeCheckResult.fail("whereisit-ui-deep", "focused slot did not produce a search request");
            }

            WHEREISIT_SYNTHETIC_SEARCH_ENABLED.set(true);
            boolean searchSucceeded = WhereIsItClient.doSearch(request);
            if (!searchSucceeded) {
                return SmokeCheckResult.fail("whereisit-ui-deep", "WhereIsItClient.doSearch returned false");
            }

            SearchResult renderedResult = Rendering.getResults().get(WHEREISIT_SYNTHETIC_RESULT_POS);
            if (renderedResult == null) {
                return SmokeCheckResult.fail("whereisit-ui-deep", "rendering results did not capture the synthetic search result");
            }
            SearchResult namedResult = Rendering.getNamedResults().get(WHEREISIT_SYNTHETIC_RESULT_POS);
            if (namedResult == null || namedResult.name() == null || !WHEREISIT_SYNTHETIC_RESULT_NAME.getString().equals(namedResult.name().getString())) {
                return SmokeCheckResult.fail("whereisit-ui-deep", "named rendering results did not capture the synthetic search label");
            }

            return SmokeCheckResult.pass(
                "whereisit-ui-deep",
                "request=" + request + " renderedResults=" + Rendering.getResults().size() + " namedResult=" + namedResult.name().getString()
            );
        } catch (Throwable t) {
            return SmokeCheckResult.fail("whereisit-ui-deep", t.getClass().getSimpleName() + ": " + t.getMessage());
        } finally {
            WHEREISIT_SYNTHETIC_SEARCH_ENABLED.set(false);
            Rendering.clearResults();
            Rendering.setLastRequest(null);
            try {
                WhereIsItConfig config = (WhereIsItConfig) WhereIsItConfig.INSTANCE.instance();
                config.getClient().closeGuiOnFoundResults = previousCloseGuiOnFoundResults;
            } catch (Throwable ignored) {
            }
            client.setScreen(previousScreen);
        }
    }

    private static MinimapSession createSyntheticMinimapSession() throws ReflectiveOperationException {
        ClientPlayNetworkHandler connection = allocateWithoutConstructor(ClientPlayNetworkHandler.class);
        return new MinimapSession(HudMod.INSTANCE, BuiltInHudModules.MINIMAP, connection);
    }

    @SuppressWarnings("unchecked")
    private static <T> T allocateWithoutConstructor(Class<T> type) throws ReflectiveOperationException {
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        Field unsafeField = unsafeClass.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        Object unsafe = unsafeField.get(null);
        Method allocateInstance = unsafeClass.getMethod("allocateInstance", Class.class);
        return (T) allocateInstance.invoke(unsafe, type);
    }

    private static final class SmokeScreenHandler extends ScreenHandler {
        private final Slot smokeSlot;

        private SmokeScreenHandler(ItemStack stack) {
            super(null, 0);
            this.smokeSlot = this.addSlot(new Slot(new SimpleInventory(stack), 0, 0, 0));
        }

        @Override
        public ItemStack quickMove(PlayerEntity player, int slot) {
            return ItemStack.EMPTY;
        }

        @Override
        public boolean canUse(PlayerEntity player) {
            return true;
        }
    }

    private static final class SmokeHandledScreen extends HandledScreen<SmokeScreenHandler> {
        private SmokeHandledScreen(SmokeScreenHandler handler, Text title) {
            super(handler, new PlayerInventory(null, new EntityEquipment()), title);
        }

        @Override
        protected void drawBackground(DrawContext context, float delta, int mouseX, int mouseY) {
        }
    }

    private enum SmokeRightClickableElement implements IRightClickableElement {
        INSTANCE;

        @Override
        public ArrayList<RightClickOption> getRightClickOptions() {
            return new ArrayList<>();
        }

        @Override
        public boolean isRightClickValid() {
            return true;
        }
    }
}
