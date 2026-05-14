package com.example.addon.util.smoke;

import com.example.addon.commands.SessionCommand;
import com.example.addon.modules.stashmover.StashMover;
import com.example.addon.util.runtime.StrictRuntimeLogger;
import it.unimi.dsi.fastutil.booleans.BooleanConsumer;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.time.Instant;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.block.Blocks;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.TrapdoorBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.block.enums.ChestType;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.Element;
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.world.CreateWorldScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.input.MouseInput;
import net.minecraft.entity.projectile.thrown.EnderPearlEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
import net.minecraft.world.rule.GameRules;
import net.minecraft.world.World;
import net.minecraft.world.WorldProperties;

public final class StashMoverLiveRuntimeValidation {
    private static final String ENABLE_PROPERTY = "devils.stashmover.live.runtime";
    private static final String ROLE_PROPERTY = "devils.stashmover.live.role";
    private static final String USER_WORLD_PROPERTY = "devils.stashmover.live.userWorld";
    private static final String USER_WORLD_NAME_PROPERTY = "devils.stashmover.live.worldName";
    private static final String REALISTIC_PROPERTY = "devils.stashmover.live.realistic";
    private static final String DEFAULT_USER_WORLD_NAME = "Новый мир";
    private static final int START_DELAY_TICKS = 60;
    private static final int WORLD_LOAD_TIMEOUT_TICKS = 1_800;
    private static final int GUEST_JOIN_TIMEOUT_TICKS = 4_800;
    private static final int RUN_TIMEOUT_TICKS = 1_500;
    private static final int MONITOR_TIMEOUT_TICKS = 48_000;
    private static final int LAN_PORT = 25_570;
    private static final int REQUIRED_RUNS = 20;
    private static final int INITIAL_PEARL_COUNT = 16;
    private static final int SOURCE_STACKS = 54;
    private static final int USER_WORLD_SOURCE_RESET_ITEMS = 54;
    private static final int EXPECTED_TRANSFERRED_ITEMS = 36 * 64;
    private static final int EXPECTED_SOURCE_REMAINING_ITEMS = (SOURCE_STACKS - 36) * 64;
    private static final String MOVER_NAME = "RuntimeMover";
    private static final String LOADER_NAME = "RuntimeLoader";
    private static final String SERVER_ADDRESS = "127.0.0.1:" + LAN_PORT;

    private static final BlockPos WATER_POS = new BlockPos(0, 64, 0);
    private static final BlockPos CHAMBER_BLOCK_POS = new BlockPos(0, 65, 0);
    private static final Vec3d CHAMBER_LOOK_POS = new Vec3d(0.5, 65.0, 0.5);
    private static final BlockPos PEARL_CHEST_POS = new BlockPos(2, 64, 0);
    private static final BlockPos SOURCE_CHEST_LEFT = new BlockPos(-2, 64, 1);
    private static final BlockPos SOURCE_CHEST_RIGHT = new BlockPos(-1, 64, 1);
    private static final BlockPos LOOT_CHEST_LEFT = new BlockPos(1, 64, 2);
    private static final BlockPos LOOT_CHEST_RIGHT = new BlockPos(2, 64, 2);
    private static final Vec3d MOVER_POS = new Vec3d(0.5, 64.0, 0.5);
    private static final Vec3d LOADER_POS = new Vec3d(0.5, 64.0, -2.5);
    private static final BlockPos USER_LOADER_STATION = new BlockPos(-5, -51, 32);
    private static final BlockPos USER_MOVER_STATION = new BlockPos(11, -60, -2152);
    private static final int USER_LOADER_SCAN_RADIUS = 24;
    private static final int USER_MOVER_SCAN_RADIUS = 32;
    private static final double CONTAINER_REACH_SQ = 4.5 * 4.5;
    private static final int REALISTIC_RUN_TIMEOUT_TICKS = 2_400;
    private static final int REALISTIC_DISTURBANCE_START_TICKS = 90;
    private static final double REALISTIC_MOVER_PUSH_X = 0.38;
    private static final double REALISTIC_MOVER_PUSH_Z = -0.24;
    private static final double REALISTIC_LOADER_PUSH_X = -0.31;
    private static final double REALISTIC_LOADER_PUSH_Z = 0.22;

    private static boolean installed;
    private static boolean completed;
    private static boolean worldRequested;
    private static boolean worldCreationSubmitted;
    private static boolean reconnectQueued;
    private static boolean lanOpened;
    private static boolean loaderConfigured;
    private static boolean sawSendPhase;
    private static boolean sawWaitPhase;
    private static boolean sawThrowPhase;
    private static boolean sawPutBackPhase;
    private static boolean sawWalkingPhase;
    private static boolean sawConnectedWorld;
    private static boolean firstRunBootstrapApplied;
    private static boolean bootstrapWarmupCompleted;
    private static boolean moverDisturbanceApplied;
    private static boolean loaderDisturbanceApplied;
    private static DetectedScenario detectedScenario;
    private static int runIndex;
    private static Stage stage = Stage.STARTUP_DELAY;
    private static int stageTicks;
    private static String lastHostWaitScreenSignature = "";
    private static int lastHostWaitScreenInteractionTick;

    private StashMoverLiveRuntimeValidation() {
    }

    public static void install() {
        if (installed || !Boolean.getBoolean(ENABLE_PROPERTY)) return;
        installed = true;

        ClientLifecycleEvents.CLIENT_STARTED.register(client -> {
            if (role() == Role.HOST) StrictRuntimeLogger.resetAll();
            completed = false;
            worldRequested = false;
            worldCreationSubmitted = false;
            reconnectQueued = false;
            lanOpened = false;
            loaderConfigured = false;
            sawConnectedWorld = false;
            firstRunBootstrapApplied = false;
            bootstrapWarmupCompleted = false;
            detectedScenario = null;
            runIndex = 0;
            resetRunFlags();
            stage = Stage.STARTUP_DELAY;
            stageTicks = 0;
            lastHostWaitScreenSignature = "";
            lastHostWaitScreenInteractionTick = 0;
            StrictRuntimeLogger.logHarness(
                "STASHMOVER",
                "SUMMARY live-runtime started=" + Instant.now()
                    + " role=" + role().name().toLowerCase()
                    + " realistic=" + realisticMode()
                    + " userWorld=" + useUserWorld()
                    + " worldName=" + userWorldName()
            );
        });

        ClientTickEvents.END_CLIENT_TICK.register(StashMoverLiveRuntimeValidation::tick);
    }

    private static void tick(MinecraftClient client) {
        if (!installed || completed) return;
        stageTicks++;

        if (role() == Role.HOST) tickHost(client);
        else tickGuest(client);
    }

    private static void tickHost(MinecraftClient client) {
        switch (stage) {
            case STARTUP_DELAY -> tickHostStartupDelay(client);
            case PREPARE_SESSION -> tickPrepareSession(client, MOVER_NAME);
            case OPEN_TEST_WORLD -> tickHostOpenTestWorld(client);
            case WAIT_FOR_WORLD -> tickHostWaitForWorld(client);
            case OPEN_LAN -> tickHostOpenLan(client);
            case WAIT_FOR_GUEST -> tickHostWaitForGuest(client);
            case PREPARE_RUN -> tickHostPrepareRun(client);
            case WAIT_FOR_SYNC -> tickHostWaitForSync(client);
            case START_RUN -> tickHostStartRun(client);
            case RUN_SCENARIO -> tickHostRunScenario(client);
            case MONITOR, FINISHED, FAILED -> {
            }
        }
    }

    private static void tickGuest(MinecraftClient client) {
        switch (stage) {
            case STARTUP_DELAY -> tickGuestStartupDelay(client);
            case PREPARE_SESSION -> tickPrepareSession(client, LOADER_NAME);
            case OPEN_TEST_WORLD -> {
            }
            case WAIT_FOR_WORLD -> tickGuestWaitForWorld(client);
            case WAIT_FOR_SYNC -> tickGuestWaitForSync(client);
            case START_RUN -> tickGuestStartLoader(client);
            case MONITOR -> tickGuestMonitor(client);
            case OPEN_LAN, WAIT_FOR_GUEST, PREPARE_RUN, RUN_SCENARIO, FINISHED, FAILED -> {
            }
        }
    }

    private static void tickHostStartupDelay(MinecraftClient client) {
        if (stageTicks < START_DELAY_TICKS) return;
        advance(Stage.PREPARE_SESSION);
    }

    private static void tickGuestStartupDelay(MinecraftClient client) {
        if (stageTicks < START_DELAY_TICKS) return;
        advance(Stage.PREPARE_SESSION);
    }

    private static void tickPrepareSession(MinecraftClient client, String username) {
        if (stageTicks == 1) {
            deactivateStashMoverForHarness();
            boolean switched = username.equalsIgnoreCase(currentUsername(client)) || SessionCommand.switchToCrackedSession(username);
            StrictRuntimeLogger.logHarness("STASHMOVER", "TRACE session role=" + role().name().toLowerCase() + " username=" + username + " switched=" + switched);
            if (!switched) {
                fail(client, "Failed to switch session to " + username + '.');
                return;
            }
        }

        if (role() == Role.HOST) advance(Stage.OPEN_TEST_WORLD);
        else {
            queueReconnect();
            advance(Stage.WAIT_FOR_WORLD);
        }
    }

    private static void tickHostOpenTestWorld(MinecraftClient client) {
        if (useUserWorld()) {
            if (!worldRequested) {
                worldRequested = true;
                StrictRuntimeLogger.logHarness(
                    "STASHMOVER",
                    "STAGE opening-existing-user-world worldName=" + userWorldName()
                        + " loaderStation=" + formatBlockPos(USER_LOADER_STATION)
                        + " moverStation=" + formatBlockPos(USER_MOVER_STATION)
                );
                client.execute(() -> openExistingUserWorld(client));
            }
            advance(Stage.WAIT_FOR_WORLD);
            return;
        }

        if (!worldRequested) {
            worldRequested = true;
            StrictRuntimeLogger.logHarness("STASHMOVER", "STAGE opening-live-test-world");
            client.execute(() -> CreateWorldScreen.showTestWorld(client, () -> {
            }));
        }

        advance(Stage.WAIT_FOR_WORLD);
    }

    private static void tickHostWaitForWorld(MinecraftClient client) {
        if (client.world != null && client.player != null && client.getServer() != null) {
            client.setScreen(null);
            deactivateStashMoverForHarness();
            StrictRuntimeLogger.logHarness("STASHMOVER", "STAGE world-loaded role=host");
            advance(Stage.OPEN_LAN);
            return;
        }

        if (useUserWorld()) {
            maybeHandleUserWorldOpenScreens(client);
            if (stageTicks == 1 || stageTicks % 100 == 0) {
                String screenName = client.currentScreen == null ? "<none>" : client.currentScreen.getClass().getSimpleName();
                StrictRuntimeLogger.logHarness(
                    "STASHMOVER",
                    "TRACE host-wait-for-world screen=" + screenName + " stageTicks=" + stageTicks
                );
            }
            boolean loadingWorld = client.currentScreen != null
                && ("MessageScreen".equals(client.currentScreen.getClass().getSimpleName())
                || "LevelLoadingScreen".equals(client.currentScreen.getClass().getSimpleName()));
            if (!loadingWorld && stageTicks > 1_200 && stageTicks % 200 == 0) {
                StrictRuntimeLogger.logHarness(
                    "STASHMOVER",
                    "TRACE reopen-existing-world-attempt worldName=" + userWorldName() + " stageTicks=" + stageTicks
                );
                client.execute(() -> openExistingUserWorld(client));
            }
        }

        if (!worldCreationSubmitted && SmokeCreateWorldHelper.submitCreateWorldIfPresent(client)) {
            worldCreationSubmitted = true;
            StrictRuntimeLogger.logHarness("STASHMOVER", "STAGE create-world-submitted role=host");
        }

        if (stageTicks > WORLD_LOAD_TIMEOUT_TICKS) fail(client, "Timed out while waiting for host world.");
    }

    private static void maybeHandleUserWorldOpenScreens(MinecraftClient client) {
        if (!(client.currentScreen instanceof Screen screen)) return;

        String screenName = screen.getClass().getSimpleName();
        if ("TitleScreen".equals(screenName) || "<none>".equals(screenName)) return;

        List<ButtonWidget> buttons = new ArrayList<>();
        for (Element element : screen.children()) {
            if (element instanceof ButtonWidget button && button.active && button.visible) buttons.add(button);
        }

        StringBuilder labels = new StringBuilder();
        for (ButtonWidget button : buttons) {
            if (!labels.isEmpty()) labels.append(" | ");
            labels.append(button.getMessage().getString());
        }

        String signature = screenName + " :: " + labels;
        if (!signature.equals(lastHostWaitScreenSignature) || stageTicks % 100 == 0) {
            lastHostWaitScreenSignature = signature;
            StrictRuntimeLogger.logHarness(
                "STASHMOVER",
                "TRACE host-world-screen screen=" + screenName + " buttons=" + (labels.isEmpty() ? "<none>" : labels)
            );
        }

        if ("RecoverWorldScreen".equals(screenName) && stageTicks - lastHostWaitScreenInteractionTick >= 20) {
            if (tryBypassRecoverWorldScreen(client, screen)) {
                lastHostWaitScreenInteractionTick = stageTicks;
                return;
            }
        }

        if (stageTicks - lastHostWaitScreenInteractionTick < 20) return;

        ButtonWidget preferred = chooseWorldOpenButton(screenName, buttons);
        if (preferred == null) return;

        preferred.onClick(new Click(preferred.getX() + 1.0, preferred.getY() + 1.0, new MouseInput(0, 0)), false);
        lastHostWaitScreenInteractionTick = stageTicks;
        StrictRuntimeLogger.logHarness(
            "STASHMOVER",
            "TRACE host-world-screen-click screen=" + screenName + " label=" + preferred.getMessage().getString()
        );
    }

    private static ButtonWidget chooseWorldOpenButton(String screenName, List<ButtonWidget> buttons) {
        if (buttons == null || buttons.isEmpty()) return null;
        String normalizedScreen = screenName == null ? "" : screenName;

        for (ButtonWidget button : buttons) {
            String label = button.getMessage().getString().toLowerCase(Locale.ROOT);
            boolean negativeLabel = label.contains("bug")
                || label.contains("browser")
                || label.contains("copy")
                || label.contains("clipboard")
                || label.contains("cancel")
                || label.contains("back")
                || label.contains("report")
                || label.contains("later")
                || label.contains("ошиб")
                || label.contains("брауз")
                || label.contains("копир")
                || label.contains("отмен")
                || label.contains("назад");
            if (negativeLabel) continue;
            boolean positiveLabel = label.contains("recover")
                || label.contains("continue")
                || label.contains("open")
                || label.contains("update")
                || label.contains("yes")
                || label.contains("play")
                || label.contains("proceed")
                || label.contains("восстанов")
                || label.contains("продолж")
                || label.contains("откры")
                || label.contains("обнов")
                || label.contains("да")
                || label.contains("играть");
            if (positiveLabel) return button;
        }

        if ("GuiUpdateAll".equals(normalizedScreen)) return buttons.get(0);
        return null;
    }

    private static boolean tryBypassRecoverWorldScreen(MinecraftClient client, Screen screen) {
        if (screen == null || !"RecoverWorldScreen".equals(screen.getClass().getSimpleName())) return false;

        try {
            java.lang.reflect.Method restoreMethod = screen.getClass().getDeclaredMethod("tryRestore", MinecraftClient.class);
            restoreMethod.setAccessible(true);
            restoreMethod.invoke(screen, client);
            StrictRuntimeLogger.logHarness(
                "STASHMOVER",
                "TRACE host-recover-screen-bypass action=try-restore"
            );
            return true;
        } catch (Throwable throwable) {
            try {
                java.lang.reflect.Field callbackField = screen.getClass().getDeclaredField("callback");
                callbackField.setAccessible(true);
                Object callback = callbackField.get(screen);
                if (!(callback instanceof BooleanConsumer consumer)) return false;
                consumer.accept(true);
                StrictRuntimeLogger.logHarness(
                    "STASHMOVER",
                    "TRACE host-recover-screen-bypass action=callback-accept-true fallback=true"
                );
                return true;
            } catch (Throwable fallbackThrowable) {
                StrictRuntimeLogger.logHarness(
                    "STASHMOVER",
                    "TRACE host-recover-screen-bypass-failed error=" + fallbackThrowable.getClass().getSimpleName()
                        + " message=" + String.valueOf(fallbackThrowable.getMessage())
                        + " primary=" + throwable.getClass().getSimpleName()
                );
                return false;
            }
        }
    }

    private static void tickHostOpenLan(MinecraftClient client) {
        deactivateStashMoverForHarness();
        if (!(client.getServer() instanceof IntegratedServer integratedServer)) {
            fail(client, "Integrated server was unavailable while opening LAN.");
            return;
        }

        if (!lanOpened) {
            lanOpened = integratedServer.openToLan(GameMode.SURVIVAL, true, LAN_PORT);
            integratedServer.setOnlineMode(false);
            integratedServer.setPreventProxyConnections(false);
            StrictRuntimeLogger.logHarness("STASHMOVER", "STAGE lan-opened success=" + lanOpened + " address=" + SERVER_ADDRESS);
            if (!lanOpened) {
                fail(client, "Failed to open live runtime world to LAN on " + SERVER_ADDRESS + '.');
                return;
            }
        }

        advance(Stage.WAIT_FOR_GUEST);
    }

    private static void tickHostWaitForGuest(MinecraftClient client) {
        IntegratedServer integratedServer = client.getServer();
        if (integratedServer == null) {
            fail(client, "Host integrated server disappeared while waiting for guest.");
            return;
        }

        if (findServerPlayer(integratedServer, MOVER_NAME) != null && findServerPlayer(integratedServer, LOADER_NAME) != null) {
            StrictRuntimeLogger.logHarness("STASHMOVER", "STAGE guest-joined address=" + SERVER_ADDRESS);
            advance(Stage.PREPARE_RUN);
            return;
        }

        if (stageTicks > GUEST_JOIN_TIMEOUT_TICKS) fail(client, "Timed out while waiting for guest account to join LAN.");
    }

    private static void tickHostPrepareRun(MinecraftClient client) {
        IntegratedServer integratedServer = client.getServer();
        StashMover module = Modules.get().get(StashMover.class);
        if (integratedServer == null || client.player == null || module == null) {
            fail(client, "Host prerequisites disappeared while preparing run " + (runIndex + 1) + '.');
            return;
        }

        resetRunFlags();
        if (module.isActive()) module.toggle();
        module.debugEnableLoggingForHarness(true);
        try {
            integratedServer.submit(() -> prepareRun(integratedServer)).join();
        } catch (Throwable throwable) {
            fail(client, "Could not prepare run " + (runIndex + 1) + ": " + throwable.getClass().getSimpleName() + ' ' + throwable.getMessage());
            return;
        }
        if (useUserWorld() && detectedScenario == null) {
            fail(client, "User-world structure detection failed before run " + (runIndex + 1) + '.');
            return;
        }
        StrictRuntimeLogger.logHarness("STASHMOVER", "STAGE run-prepared index=" + (runIndex + 1));
        advance(Stage.WAIT_FOR_SYNC);
    }

    private static void tickHostWaitForSync(MinecraftClient client) {
        IntegratedServer integratedServer = client.getServer();
        boolean guestOnServer = integratedServer != null && findServerPlayer(integratedServer, LOADER_NAME) != null;
        if (!guestOnServer) {
            if (stageTicks > 1_200) fail(client, "Host guest account never synced into the live StashMover world.");
            return;
        }

        if (useUserWorld()) {
            if (client.world == null || client.player == null) {
                if (stageTicks > 1_200) fail(client, "Host client lost the live StashMover world before run start.");
                return;
            }

            double dx = client.player.getX() - activeMoverPos().x;
            double dy = client.player.getY() - activeMoverPos().y;
            double dz = client.player.getZ() - activeMoverPos().z;
            if (dx * dx + dy * dy + dz * dz > 6.0 * 6.0) {
                if (stageTicks % 100 == 0) nudgeHostToMoverStation(client);
                if (stageTicks > 1_200) fail(client, "Host client never reached the mover source station before run start.");
                return;
            }

            // On the user world the server-side scenario detection is already authoritative and the guest
            // validates the loader-side structure separately. Keep a deterministic warm-up delay here so
            // the host client can settle at the mover/source station before the module starts.
            if (stageTicks < 200) return;
            advance(Stage.START_RUN);
            return;
        }

        if (!clientHasExpectedStructure(client)) {
            if (stageTicks > 1_200) fail(client, "Host client never synced the live StashMover structure.");
            return;
        }

        if (stageTicks < 60) return;
        advance(Stage.START_RUN);
    }

    private static void nudgeHostToMoverStation(MinecraftClient client) {
        if (client == null || client.player == null || client.player.networkHandler == null) return;

        Vec3d moverPos = activeMoverPos();
        String command = String.format(Locale.ROOT, "tp %s %.2f %.2f %.2f", MOVER_NAME, moverPos.x, moverPos.y, moverPos.z);
        StrictRuntimeLogger.logHarness("STASHMOVER", "TRACE host-sync-nudge command=" + command);
        client.player.networkHandler.sendChatCommand(command);
    }

    private static void tickHostStartRun(MinecraftClient client) {
        StashMover module = Modules.get().get(StashMover.class);
        IntegratedServer integratedServer = client.getServer();
        if (module == null) {
            fail(client, "Host mover module vanished before run start.");
            return;
        }
        if (integratedServer == null) {
            fail(client, "Host integrated server vanished before run start.");
            return;
        }

        DetectedScenario scenario = activeScenario(client);
        if (scenario == null) {
            fail(client, "Could not resolve StashMover scenario before mover activation.");
            return;
        }
        boolean realisticBootstrapWarmup = useUserWorld() && realisticMode() && runIndex == 0 && !bootstrapWarmupCompleted;
        if (realisticBootstrapWarmup) {
            if (!firstRunBootstrapApplied) {
                if (!prepareRealisticBootstrapThrow(integratedServer, scenario)) {
                    fail(client, "Could not prepare the real first-run mover bootstrap throw.");
                    return;
                }
                firstRunBootstrapApplied = true;
                return;
            }
            if (!hostReadyForBootstrapThrow(client, scenario)) {
                if (stageTicks > 200) {
                    fail(client, "Timed out while waiting for the host client to sync the bootstrap pearl throw state.");
                }
                return;
            }
        } else if (useUserWorld() && runIndex == 0 && !bootstrapWarmupCompleted) {
            if (!ensureInitialStasisPearl(integratedServer)) {
                fail(client, "Could not stage the initial mover pearl after the loader chunk became live.");
                return;
            }
        }

        module.debugEnableLoggingForHarness(true);
        module.debugConfigureForStrictRuntime(
            StashMover.Mode.MOVER,
            LOADER_NAME,
            false,
            false,
            scenario.pearlChest(),
            scenario.lootChest(),
            scenario.water(),
            scenario.chamber(),
            scenario.pearlTarget()
        );
        module.debugSetLocalThrowSnapForHarness(!realisticMode());
        if (realisticMode()) {
            module.debugSetReturnCommandForHarness("kill");
        } else {
            Vec3d returnPos = activeMoverPos();
            module.debugSetReturnCommandForHarness(String.format(
                Locale.ROOT,
                "tp %s %.2f %.2f %.2f",
                MOVER_NAME,
                returnPos.x,
                returnPos.y,
                returnPos.z
            ));
        }
        if (module.isActive()) module.toggle();
        module.toggle();
        if (realisticBootstrapWarmup) module.debugPrepareBootstrapReturnForHarness();
        StrictRuntimeLogger.logHarness(
            "STASHMOVER",
            "STAGE run-start index=" + (runIndex + 1)
                + " moverPos=" + formatVec(activeMoverPos())
                + " loaderPos=" + formatVec(activeLoaderPos())
                + ' ' + scenario.summary()
        );
        advance(Stage.RUN_SCENARIO);
    }

    private static void tickHostRunScenario(MinecraftClient client) {
        StashMover module = Modules.get().get(StashMover.class);
        IntegratedServer integratedServer = client.getServer();
        if (module == null || integratedServer == null) {
            fail(client, "Host runtime dependencies disappeared during run " + (runIndex + 1) + '.');
            return;
        }

        String phase = module.moverPhaseName();
        if ("SEND_LOAD_PEARL_MSG".equals(phase)) sawSendPhase = true;
        if ("WAIT_FOR_PEARL".equals(phase)) sawWaitPhase = true;
        if ("THROWING_PEARL".equals(phase)) sawThrowPhase = true;
        if ("PUT_BACK_PEARLS".equals(phase)) sawPutBackPhase = true;
        if ("WALKING_TO_CHEST".equals(phase)) sawWalkingPhase = true;

        RunSnapshot snapshot = captureSnapshot(integratedServer);
        if (snapshot == null) {
            if (stageTicks > runTimeoutTicks()) fail(client, "Run " + (runIndex + 1) + " could not capture a live snapshot.");
            return;
        }

        if (stageTicks == 1 || stageTicks % 80 == 0) {
            StrictRuntimeLogger.logHarness(
                "STASHMOVER",
                "TRACE run=" + (runIndex + 1) + " tick=" + stageTicks + ' ' + module.runtimeStatusSummary()
                    + " stagedPearls=" + describeMoverPearls(integratedServer)
            );
        }

        maybeBootstrapInitialReturn(integratedServer, module, snapshot);
        maybeInjectRealisticDisturbance(integratedServer, module, snapshot);

        boolean pass = isRunPass(snapshot, module);
        boolean warmupPass = isBootstrapWarmupPass(snapshot, module);

        if (warmupPass) {
            if (module.isActive()) module.toggle();
            bootstrapWarmupCompleted = true;
            StrictRuntimeLogger.logHarness(
                "STASHMOVER",
                "WARMUP PASS moverPos=" + snapshot.moverPos
                    + " loaderPos=" + snapshot.loaderPos
                    + " lootItems=" + snapshot.lootChestItemCount
                    + " sourceItems=" + snapshot.sourceChestItemCount
                    + " pearlChest=" + snapshot.pearlChestCount
                    + " moverInv=" + snapshot.moverInventoryItemCount
                    + " moverAtSource=" + snapshot.moverAtSource
            );
            advance(Stage.PREPARE_RUN);
            return;
        }

        if (pass) {
            if (module.isActive()) module.toggle();
            StrictRuntimeLogger.logHarness(
                "STASHMOVER",
                "RUN PASS index=" + (runIndex + 1)
                    + " moverPos=" + snapshot.moverPos
                    + " loaderPos=" + snapshot.loaderPos
                    + " lootItems=" + snapshot.lootChestItemCount
                    + " sourceItems=" + snapshot.sourceChestItemCount
                    + " pearlChest=" + snapshot.pearlChestCount
                    + " moverInv=" + snapshot.moverInventoryItemCount
                    + " moverAtSource=" + snapshot.moverAtSource
            );
            runIndex++;
            if (runIndex >= REQUIRED_RUNS) {
                StrictRuntimeLogger.logHarness("STASHMOVER", "RESULT PASS runs=" + REQUIRED_RUNS + " address=" + SERVER_ADDRESS);
                finish(client, true);
            } else {
                advance(Stage.PREPARE_RUN);
            }
            return;
        }

        if (stageTicks > runTimeoutTicks()) {
            fail(
                client,
                "Run " + (runIndex + 1) + " timed out. "
                    + "send=" + sawSendPhase
                    + " wait=" + sawWaitPhase
                    + " throw=" + sawThrowPhase
                    + " putBack=" + sawPutBackPhase
                    + " walking=" + sawWalkingPhase
                    + " lootItems=" + snapshot.lootChestItemCount
                    + " sourceItems=" + snapshot.sourceChestItemCount
                    + " pearlChest=" + snapshot.pearlChestCount
                    + " moverInv=" + snapshot.moverInventoryItemCount
                    + " moverAtSource=" + snapshot.moverAtSource
                    + " phase=" + module.moverPhaseName()
                    + " failure=" + module.lastPearlFailureReasonValue()
            );
        }
    }

    private static void tickGuestWaitForWorld(MinecraftClient client) {
        if (client.world != null && client.player != null) {
            sawConnectedWorld = true;
            client.setScreen(null);
            deactivateStashMoverForHarness();
            StrictRuntimeLogger.logHarness("STASHMOVER", "STAGE world-loaded role=guest");
            advance(Stage.WAIT_FOR_SYNC);
            return;
        }

        if (shouldQueueGuestReconnect(client)) queueReconnect();
        if (stageTicks > WORLD_LOAD_TIMEOUT_TICKS) fail(client, "Timed out while waiting for guest connection to " + SERVER_ADDRESS + '.');
    }

    private static void tickGuestWaitForSync(MinecraftClient client) {
        if (!clientHasExpectedStructure(client)) {
            if (shouldQueueGuestReconnect(client) && stageTicks % 120 == 0) queueReconnect();
            if (stageTicks > 1_200) fail(client, "Guest client never synced the live StashMover structure.");
            return;
        }

        if (stageTicks < 40) return;
        advance(Stage.START_RUN);
    }

    private static void tickGuestStartLoader(MinecraftClient client) {
        StashMover module = Modules.get().get(StashMover.class);
        if (module == null) {
            fail(client, "Guest loader module vanished before activation.");
            return;
        }

        if (!loaderConfigured) {
            DetectedScenario scenario = activeScenario(client);
            if (scenario == null) {
                fail(client, "Could not resolve StashMover scenario before loader activation.");
                return;
            }

            loaderConfigured = true;
            module.debugEnableLoggingForHarness(true);
            module.debugConfigureForStrictRuntime(
                StashMover.Mode.LOADER,
                MOVER_NAME,
                false,
                false,
                scenario.pearlChest(),
                scenario.lootChest(),
                scenario.water(),
                scenario.chamber(),
                scenario.pearlTarget()
            );
            if (module.isActive()) module.toggle();
            module.toggle();
            StrictRuntimeLogger.logHarness(
                "STASHMOVER",
                "STAGE loader-ready address=" + SERVER_ADDRESS
                    + " loaderPos=" + formatVec(activeLoaderPos())
                    + ' ' + scenario.summary()
            );
        }

        advance(Stage.MONITOR);
    }

    private static void tickGuestMonitor(MinecraftClient client) {
        StashMover module = Modules.get().get(StashMover.class);
        if (stageTicks == 1 || stageTicks % 120 == 0) {
            StrictRuntimeLogger.logHarness(
                "STASHMOVER",
                "TRACE guest tick=" + stageTicks + " connected=" + (client.world != null && client.player != null)
                    + " module=" + (module == null ? "<missing>" : module.runtimeStatusSummary())
            );
        }

        if (stageTicks == 1 || stageTicks % 20 == 0) {
            if (runtimeLogContains("RESULT PASS runs=" + REQUIRED_RUNS)) {
                finish(client, true);
                return;
            }
            if (runtimeLogContains("RESULT FAIL role=host")) {
                fail(client, "Host reported runtime failure before completing " + REQUIRED_RUNS + " runs.");
                return;
            }
        }

        if (sawConnectedWorld && client.world == null && client.player == null) {
            if (shouldQueueGuestReconnect(client)) queueReconnect();
            if (stageTicks % 120 == 0) {
                StrictRuntimeLogger.logHarness(
                    "STASHMOVER",
                    "TRACE guest-disconnected-waiting-for-host-result tick=" + stageTicks + " address=" + SERVER_ADDRESS
                );
            }
            return;
        }

        if (stageTicks > MONITOR_TIMEOUT_TICKS) fail(client, "Guest monitor timed out while waiting for host completion.");
    }

    private static boolean runtimeLogContains(String needle) {
        String dir = System.getProperty("devils.strict.runtime.dir", "");
        if (dir.isBlank()) return false;
        Path path = Path.of(dir).resolve("stashmover-runtime.log");
        try {
            return Files.isRegularFile(path) && Files.readString(path).contains(needle);
        } catch (Exception ignored) {
            return false;
        }
    }

    private static void queueReconnect() {
        reconnectQueued = SessionCommand.scheduleReconnect(SERVER_ADDRESS);
        StrictRuntimeLogger.logHarness("STASHMOVER", "TRACE guest-connect-queued address=" + SERVER_ADDRESS + " success=" + reconnectQueued);
    }

    private static boolean shouldQueueGuestReconnect(MinecraftClient client) {
        if (client == null) return false;
        if (!reconnectQueued) return true;
        if (hasLiveClientNetworkState(client)) return false;
        if (client.currentScreen instanceof ConnectScreen) return false;
        return stageTicks % 120 == 0;
    }

    private static boolean hasLiveClientNetworkState(MinecraftClient client) {
        return client != null
            && (client.world != null
            || client.player != null
            || (client.getNetworkHandler() != null && client.getNetworkHandler().getConnection() != null));
    }

    private static boolean clientHasExpectedStructure(MinecraftClient client) {
        if (client == null || client.world == null) return false;
        if (useUserWorld()) {
            if (role() == Role.HOST) return clientHostHasExpectedUserWorldState(client);
            DetectedScenario scenario = role() == Role.GUEST ? detectLoaderScenario(client.world) : detectScenario(client.world);
            if (scenario == null) return false;
            detectedScenario = scenario;
            return true;
        }
        return client.world.getBlockState(WATER_POS).isOf(Blocks.WATER)
            && client.world.getBlockState(CHAMBER_BLOCK_POS).isOf(Blocks.OAK_TRAPDOOR)
            && client.world.getBlockState(PEARL_CHEST_POS).isOf(Blocks.CHEST)
            && client.world.getBlockState(SOURCE_CHEST_LEFT).isOf(Blocks.CHEST)
            && client.world.getBlockState(SOURCE_CHEST_RIGHT).isOf(Blocks.CHEST)
            && client.world.getBlockState(LOOT_CHEST_LEFT).isOf(Blocks.CHEST)
            && client.world.getBlockState(LOOT_CHEST_RIGHT).isOf(Blocks.CHEST);
    }

    private static boolean clientHostHasExpectedUserWorldState(MinecraftClient client) {
        if (client == null || client.world == null || client.player == null) return false;
        double dx = client.player.getX() - activeMoverPos().x;
        double dy = client.player.getY() - activeMoverPos().y;
        double dz = client.player.getZ() - activeMoverPos().z;
        if (dx * dx + dy * dy + dz * dz > 6.0 * 6.0) return false;

        List<BlockPos> sourceChests = scanChests(client.world, USER_MOVER_STATION, USER_MOVER_SCAN_RADIUS);
        // The host stands at the mover/source station, not at the loader station. On the user world those
        // stations are thousands of blocks apart, so the host client cannot be required to see loader-side
        // water/trapdoor/chests at the same time. Loader-side sync is validated by the guest runtime.
        //
        // Client-side chest inventories can also still be unsynced here even when the source station itself
        // is loaded. For the host wait gate we only need the mover to be at the source station and the source
        // chests to exist around it.
        boolean sourceReady = sourceChests.size() >= 2;

        return sourceReady;
    }

    private static boolean clientHasPlayer(MinecraftClient client, String username) {
        if (client == null || client.world == null || username == null || username.isBlank()) return false;
        return client.world.getPlayers().stream().anyMatch(player -> username.equalsIgnoreCase(player.getGameProfile().name()));
    }

    private static void prepareRun(MinecraftServer server) {
        ServerWorld world = server.getOverworld();
        if (world == null) return;

        ServerPlayerEntity mover = findServerPlayer(server, MOVER_NAME);
        ServerPlayerEntity loader = findServerPlayer(server, LOADER_NAME);
        if (mover == null || loader == null) return;
        ensureStasisPearlsSurviveMoverDeath(server, world);

        if (useUserWorld()) {
            List<BlockPos> sourceResetTargets = scanChests(world, USER_MOVER_STATION, USER_MOVER_SCAN_RADIUS);
            if (!sourceResetTargets.isEmpty()) {
                fillChestList(world, sourceResetTargets, new ItemStack(Items.WHITE_SHULKER_BOX), USER_WORLD_SOURCE_RESET_ITEMS);
                StrictRuntimeLogger.logHarness(
                    "STASHMOVER",
                    "TRACE harness-reset-source chests=" + sourceResetTargets.size()
                        + " items=" + USER_WORLD_SOURCE_RESET_ITEMS
                        + " item=white_shulker_box"
                        + " reason=independent-run-source-reset"
                );
            }
            detectedScenario = detectScenario(world);
            if (detectedScenario != null) {
                if (runIndex == 0 && !bootstrapWarmupCompleted) {
                    int removed = discardMoverPearlsNearScenario(world, mover, detectedScenario);
                    refillPearlChest(world, detectedScenario.pearlChest());
                    resetUserWorldChamber(world, detectedScenario);
                    StrictRuntimeLogger.logHarness(
                        "STASHMOVER",
                        "TRACE harness-session-baseline pearlChest=" + formatBlockPos(detectedScenario.pearlChest())
                            + " removedPearls=" + removed
                            + " reason=first-run-clean-start"
                    );
                    detectedScenario = detectScenario(world);
                    if (detectedScenario == null) return;
                }
                boolean preserveExistingPearl = (runIndex > 0 || bootstrapWarmupCompleted)
                    && hasMoverOwnedPearlNearScenario(world, mover, detectedScenario);
                if (preserveExistingPearl) {
                    StrictRuntimeLogger.logHarness(
                        "STASHMOVER",
                        "TRACE harness-preserve-chamber chamber=" + formatBlockPos(BlockPos.ofFloored(detectedScenario.chamber()))
                            + " reason=existing-bot-thrown-stasis-pearl-detected"
                    );
                } else {
                    resetUserWorldChamber(world, detectedScenario);
                }
                clearConnectedChest(world, detectedScenario.lootChest());
                StrictRuntimeLogger.logHarness(
                    "STASHMOVER",
                    "TRACE harness-cleared-destination chest=" + formatBlockPos(detectedScenario.lootChest())
                        + " reason=independent-run-capacity-reset"
                );
                detectedScenario = detectScenario(world);
            }
            if (detectedScenario != null) {
                boolean needsTeleportSetup = !realisticMode() || (runIndex == 0 && !bootstrapWarmupCompleted);
                if (needsTeleportSetup) {
                    preparePlayer(world, mover, userMoverPos(), true);
                    preparePlayer(world, loader, detectedScenario.loaderStand(), false);
                } else {
                    preserveRealisticPlayerState(world, mover, userMoverPos(), true);
                    preserveRealisticPlayerState(world, loader, detectedScenario.loaderStand(), false);
                }
                if (runIndex == 0 && !bootstrapWarmupCompleted) {
                    if (realisticMode()) {
                        StrictRuntimeLogger.logHarness(
                            "STASHMOVER",
                            "STAGE bootstrap-warmup-pending index=" + (runIndex + 1)
                                + " reason=real-player-thrown-initial-pearl-required"
                        );
                    } else {
                        StrictRuntimeLogger.logHarness(
                            "STASHMOVER",
                            "STAGE initial-seed-deferred index=" + (runIndex + 1)
                                + " reason=await-loader-chunk-load"
                        );
                    }
                } else {
                    StrictRuntimeLogger.logHarness(
                        "STASHMOVER",
                        "STAGE initial-seed-skipped index=" + (runIndex + 1)
                            + " reason=bot-thrown-stasis-pearl-from-previous-cycle-required"
                    );
                }
                StrictRuntimeLogger.logHarness("STASHMOVER", "STAGE user-world-detected " + detectedScenario.summary());
            } else {
                StrictRuntimeLogger.logHarness(
                    "STASHMOVER",
                    "RESULT FAIL role=host Could not detect required user-world StashMover structure around loader="
                        + formatBlockPos(USER_LOADER_STATION) + " mover=" + formatBlockPos(USER_MOVER_STATION)
                );
            }
            return;
        }

        clearArea(world);
        buildScenario(world);
        fillSingleChest(world.getBlockEntity(PEARL_CHEST_POS), new ItemStack(Items.ENDER_PEARL, INITIAL_PEARL_COUNT));
        fillDoubleChest(world, SOURCE_CHEST_LEFT, SOURCE_CHEST_RIGHT, new ItemStack(Items.STONE, 64), SOURCE_STACKS);
        clearDoubleChest(world, LOOT_CHEST_LEFT, LOOT_CHEST_RIGHT);
        preparePlayer(world, mover, MOVER_POS, true);
        preparePlayer(world, loader, LOADER_POS, false);
    }

    private static EnderPearlEntity seedMoverStasisPearl(ServerWorld world, ServerPlayerEntity mover, DetectedScenario scenario) {
        if (world == null || mover == null || scenario == null || scenario.water() == null) return null;

        discardMoverPearlsNearScenario(world, mover, scenario);

        Vec3d pearlPos = scenario.pearlTarget() == null ? Vec3d.ofCenter(scenario.water()) : scenario.pearlTarget();
        EnderPearlEntity pearl = new EnderPearlEntity(world, mover, new ItemStack(Items.ENDER_PEARL));
        pearl.refreshPositionAndAngles(pearlPos.x, pearlPos.y, pearlPos.z, mover.getYaw(), 0.0f);
        pearl.setVelocity(0.0, 0.0, 0.0);
        boolean spawned = world.spawnEntity(pearl);
        StrictRuntimeLogger.logHarness(
            "STASHMOVER",
            "STAGE seeded-mover-pearl entityId=" + pearl.getId()
                + " owner=" + mover.getGameProfile().name()
                + " pos=" + formatVec(pearlPos)
                + " water=" + formatBlockPos(scenario.water())
                + " loaderStand=" + formatVec(scenario.loaderStand())
                + " spawned=" + spawned
        );
        return spawned ? pearl : null;
    }

    private static boolean ensureInitialStasisPearl(IntegratedServer server) {
        if (!useUserWorld() || server == null) return true;

        try {
            return Boolean.TRUE.equals(server.submit(() -> ensureInitialStasisPearlOnServer(server)).join());
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean ensureInitialStasisPearlOnServer(IntegratedServer server) {
        if (server == null) return false;

        ServerWorld world = server.getOverworld();
        ServerPlayerEntity mover = findServerPlayer(server, MOVER_NAME);
        DetectedScenario scenario = detectedScenario == null ? detectScenario(world) : detectedScenario;
        if (world == null || mover == null || scenario == null) return false;

        if (hasMoverOwnedPearlNearScenario(world, mover, scenario)) {
            StrictRuntimeLogger.logHarness(
                "STASHMOVER",
                "STAGE initial-seed-ready result=already-present stagedPearls=" + describeMoverPearlsOnServer(server)
            );
            return true;
        }

        EnderPearlEntity seededPearl = seedMoverStasisPearl(world, mover, scenario);
        boolean present = hasMoverOwnedPearlNearScenario(world, mover, scenario);
        boolean alive = seededPearl != null && seededPearl.isAlive();
        boolean ready = present || alive;
        StrictRuntimeLogger.logHarness(
            "STASHMOVER",
            "STAGE initial-seed-ready result=" + ready
                + " queryPresent=" + present
                + " seededAlive=" + alive
                + " entityId=" + (seededPearl == null ? -1 : seededPearl.getId())
                + " stagedPearls=" + describeMoverPearlsOnServer(server)
        );
        return ready;
    }

    private static boolean prepareRealisticBootstrapThrow(IntegratedServer server, DetectedScenario scenario) {
        if (server == null || scenario == null) return false;

        try {
            return Boolean.TRUE.equals(server.submit(() -> prepareRealisticBootstrapThrowOnServer(server, scenario)).join());
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean prepareRealisticBootstrapThrowOnServer(IntegratedServer server, DetectedScenario scenario) {
        if (server == null || scenario == null) return false;

        ServerWorld world = server.getOverworld();
        ServerPlayerEntity mover = findServerPlayer(server, MOVER_NAME);
        if (world == null || mover == null) return false;
        ensureStasisPearlsSurviveMoverDeath(server, world);

        discardMoverPearlsNearScenario(world, mover, scenario);
        resetUserWorldChamber(world, scenario);
        refillPearlChest(world, scenario.pearlChest());

        Vec3d spawnPos = userMoverPos();
        Vec3d throwPos = resolveBootstrapThrowPos(scenario);
        if (throwPos == null) return false;
        mover.changeGameMode(GameMode.SURVIVAL);
        mover.getInventory().clear();
        mover.closeHandledScreen();
        mover.requestTeleport(throwPos.x, throwPos.y, throwPos.z);
        mover.setVelocity(0.0, 0.0, 0.0);
        mover.setHealth(mover.getMaxHealth());
        mover.setSpawnPoint(
            new ServerPlayerEntity.Respawn(
                WorldProperties.SpawnPoint.create(world.getRegistryKey(), BlockPos.ofFloored(spawnPos), mover.getYaw(), mover.getPitch()),
                true
            ),
            false
        );
        int selectedSlot = mover.getInventory().getSelectedSlot();
        mover.getInventory().setStack(selectedSlot, new ItemStack(Items.ENDER_PEARL, 1));
        mover.playerScreenHandler.sendContentUpdates();
        StrictRuntimeLogger.logHarness(
            "STASHMOVER",
            "STAGE bootstrap-throw-prepared moverThrowPos=" + formatVec(throwPos)
                + " moverRespawn=" + formatVec(spawnPos)
                + " chamber=" + formatVec(scenario.chamber())
                + " water=" + formatBlockPos(scenario.water())
        );
        return true;
    }

    private static void ensureStasisPearlsSurviveMoverDeath(MinecraftServer server, ServerWorld world) {
        if (server == null || world == null) return;

        Boolean current = world.getGameRules().getValue(GameRules.ENDER_PEARLS_VANISH_ON_DEATH);
        if (Boolean.TRUE.equals(current)) {
            world.getGameRules().setValue(GameRules.ENDER_PEARLS_VANISH_ON_DEATH, false, server);
            StrictRuntimeLogger.logHarness(
                "STASHMOVER",
                "TRACE gamerule-set enderPearlsVanishOnDeath=false reason=real-death-stasis-cycle-requires-surviving-pearl"
            );
        } else {
            StrictRuntimeLogger.logHarness(
                "STASHMOVER",
                "TRACE gamerule-confirmed enderPearlsVanishOnDeath=false reason=real-death-stasis-cycle"
            );
        }
    }

    private static boolean hostReadyForBootstrapThrow(MinecraftClient client, DetectedScenario scenario) {
        if (client == null || client.player == null || scenario == null) return false;

        Vec3d throwPos = resolveBootstrapThrowPos(scenario);
        if (throwPos == null) return false;

        double dx = client.player.getX() - throwPos.x;
        double dy = client.player.getY() - throwPos.y;
        double dz = client.player.getZ() - throwPos.z;
        boolean nearThrowPos = dx * dx + dy * dy + dz * dz <= 3.0 * 3.0;
        if (!nearThrowPos) return false;

        for (int slot = 0; slot < 9; slot++) {
            if (client.player.getInventory().getStack(slot).isOf(Items.ENDER_PEARL)) return true;
        }

        return false;
    }

    private static Vec3d resolveBootstrapThrowPos(DetectedScenario scenario) {
        if (scenario == null) return null;
        if (scenario.chamber() != null) {
            BlockPos chamberBlock = BlockPos.ofFloored(scenario.chamber().x, scenario.chamber().y, scenario.chamber().z);
            return new Vec3d(chamberBlock.getX() + 0.5, chamberBlock.getY(), chamberBlock.getZ() + 0.5);
        }
        if (scenario.water() != null) {
            return new Vec3d(scenario.water().getX() + 0.5, scenario.water().getY() + 1.0, scenario.water().getZ() + 0.5);
        }
        return scenario.loaderStand();
    }

    private static void clearArea(ServerWorld world) {
        for (int x = -5; x <= 4; x++) {
            for (int y = 63; y <= 67; y++) {
                for (int z = -4; z <= 4; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (y == 63) world.setBlockState(pos, Blocks.WHITE_CONCRETE.getDefaultState());
                    else world.setBlockState(pos, Blocks.AIR.getDefaultState());
                }
            }
        }
    }

    private static void buildScenario(ServerWorld world) {
        for (int x = -3; x <= 3; x++) {
            for (int z = -3; z <= 3; z++) {
                world.setBlockState(new BlockPos(x, 63, z), Blocks.WHITE_CONCRETE.getDefaultState());
            }
        }

        world.setBlockState(new BlockPos(-3, 64, -3), Blocks.WHITE_CONCRETE.getDefaultState());
        world.setBlockState(new BlockPos(-3, 65, -3), Blocks.WHITE_CONCRETE.getDefaultState());
        world.setBlockState(new BlockPos(3, 64, -3), Blocks.WHITE_CONCRETE.getDefaultState());
        world.setBlockState(new BlockPos(3, 65, -3), Blocks.WHITE_CONCRETE.getDefaultState());
        world.setBlockState(new BlockPos(-3, 64, 3), Blocks.WHITE_CONCRETE.getDefaultState());
        world.setBlockState(new BlockPos(3, 64, 3), Blocks.WHITE_CONCRETE.getDefaultState());

        world.setBlockState(WATER_POS, Blocks.WATER.getDefaultState());
        world.setBlockState(CHAMBER_BLOCK_POS, Blocks.OAK_TRAPDOOR.getDefaultState().with(TrapdoorBlock.OPEN, false));
        world.setBlockState(PEARL_CHEST_POS, Blocks.CHEST.getDefaultState());
        placeDoubleChest(world, SOURCE_CHEST_LEFT, SOURCE_CHEST_RIGHT, Direction.SOUTH);
        placeDoubleChest(world, LOOT_CHEST_LEFT, LOOT_CHEST_RIGHT, Direction.SOUTH);
    }

    private static void placeDoubleChest(ServerWorld world, BlockPos leftPos, BlockPos rightPos, Direction facing) {
        world.setBlockState(
            leftPos,
            Blocks.CHEST.getDefaultState().with(ChestBlock.FACING, facing).with(ChestBlock.CHEST_TYPE, ChestType.LEFT)
        );
        world.setBlockState(
            rightPos,
            Blocks.CHEST.getDefaultState().with(ChestBlock.FACING, facing).with(ChestBlock.CHEST_TYPE, ChestType.RIGHT)
        );
    }

    private static void preparePlayer(ServerWorld world, ServerPlayerEntity player, Vec3d pos, boolean setSpawnPoint) {
        player.changeGameMode(GameMode.SURVIVAL);
        player.getInventory().clear();
        player.closeHandledScreen();
        player.requestTeleport(pos.x, pos.y, pos.z);
        player.setVelocity(0.0, 0.0, 0.0);
        player.setHealth(player.getMaxHealth());
        if (setSpawnPoint) {
            BlockPos spawn = BlockPos.ofFloored(pos);
            player.setSpawnPoint(
                new ServerPlayerEntity.Respawn(
                    WorldProperties.SpawnPoint.create(world.getRegistryKey(), spawn, player.getYaw(), player.getPitch()),
                    true
                ),
                false
            );
            StrictRuntimeLogger.logHarness(
                "STASHMOVER",
                "TRACE spawnpoint-set player=" + player.getGameProfile().name() + " pos=" + formatBlockPos(spawn)
            );
        }
        player.playerScreenHandler.sendContentUpdates();
    }

    private static void preserveRealisticPlayerState(ServerWorld world, ServerPlayerEntity player, Vec3d anchor, boolean setSpawnPoint) {
        if (world == null || player == null || anchor == null) return;

        player.changeGameMode(GameMode.SURVIVAL);
        player.closeHandledScreen();
        player.setVelocity(0.0, 0.0, 0.0);
        player.setHealth(player.getMaxHealth());
        if (setSpawnPoint) {
            BlockPos spawn = BlockPos.ofFloored(anchor);
            player.setSpawnPoint(
                new ServerPlayerEntity.Respawn(
                    WorldProperties.SpawnPoint.create(world.getRegistryKey(), spawn, player.getYaw(), player.getPitch()),
                    true
                ),
                false
            );
        }
        player.playerScreenHandler.sendContentUpdates();
        StrictRuntimeLogger.logHarness(
            "STASHMOVER",
            "TRACE realistic-preserve-player player=" + player.getGameProfile().name()
                + " pos=" + formatVec(new Vec3d(player.getX(), player.getY(), player.getZ()))
                + " anchor=" + formatVec(anchor)
                + " spawnpointUpdated=" + setSpawnPoint
        );
    }

    private static void fillSingleChest(BlockEntity blockEntity, ItemStack stack) {
        if (!(blockEntity instanceof ChestBlockEntity chest)) return;
        chest.clear();
        chest.setStack(0, stack.copy());
        chest.markDirty();
    }

    private static void fillDoubleChest(ServerWorld world, BlockPos leftPos, BlockPos rightPos, ItemStack stack, int stackCount) {
        clearDoubleChest(world, leftPos, rightPos);
        ChestBlockEntity left = chest(world, leftPos);
        ChestBlockEntity right = chest(world, rightPos);
        if (left == null || right == null) return;

        int remaining = Math.max(0, stackCount);
        for (int slot = 0; slot < 27 && remaining > 0; slot++, remaining--) left.setStack(slot, stack.copy());
        for (int slot = 0; slot < 27 && remaining > 0; slot++, remaining--) right.setStack(slot, stack.copy());
        left.markDirty();
        right.markDirty();
    }

    private static void fillChestList(World world, List<BlockPos> positions, ItemStack stack, int itemCount) {
        if (world == null || positions == null || positions.isEmpty() || stack == null || stack.isEmpty()) return;

        for (BlockPos pos : positions) {
            ChestBlockEntity chest = chest(world, pos);
            if (chest != null) {
                chest.clear();
                chest.markDirty();
            }
        }

        int remaining = Math.max(0, itemCount);
        for (BlockPos pos : positions) {
            ChestBlockEntity chest = chest(world, pos);
            if (chest == null) continue;

            for (int slot = 0; slot < chest.size() && remaining > 0; slot++, remaining--) {
                chest.setStack(slot, stack.copy());
            }
            chest.markDirty();
            if (remaining <= 0) break;
        }
    }

    private static void clearDoubleChest(ServerWorld world, BlockPos leftPos, BlockPos rightPos) {
        ChestBlockEntity left = chest(world, leftPos);
        ChestBlockEntity right = chest(world, rightPos);
        if (left != null) {
            left.clear();
            left.markDirty();
        }
        if (right != null) {
            right.clear();
            right.markDirty();
        }
    }

    private static void clearConnectedChest(World world, BlockPos pos) {
        ChestBlockEntity chest = chest(world, pos);
        if (chest != null) {
            chest.clear();
            chest.markDirty();
        }

        BlockPos connected = connectedChestHalf(world, pos);
        if (connected == null) return;
        ChestBlockEntity connectedChest = chest(world, connected);
        if (connectedChest != null) {
            connectedChest.clear();
            connectedChest.markDirty();
        }
    }

    private static void resetUserWorldChamber(ServerWorld world, DetectedScenario scenario) {
        if (world == null || scenario == null || scenario.chamber() == null) return;

        BlockPos chamberBlock = BlockPos.ofFloored(scenario.chamber());
        if (!(world.getBlockState(chamberBlock).getBlock() instanceof TrapdoorBlock)) return;
        if (!world.getBlockState(chamberBlock).contains(TrapdoorBlock.OPEN)) return;

        world.setBlockState(chamberBlock, world.getBlockState(chamberBlock).with(TrapdoorBlock.OPEN, false));
        StrictRuntimeLogger.logHarness(
            "STASHMOVER",
            "TRACE harness-reset-chamber chamber=" + formatBlockPos(chamberBlock)
                + " reason=independent-run-chamber-reset"
        );
    }

    private static boolean hasMoverOwnedPearlNearScenario(ServerWorld world, ServerPlayerEntity mover, DetectedScenario scenario) {
        if (world == null || mover == null || scenario == null) return false;

        Box searchBox;
        if (scenario.water() != null) searchBox = new Box(scenario.water()).expand(4.0);
        else if (scenario.chamber() != null) searchBox = new Box(BlockPos.ofFloored(scenario.chamber())).expand(4.0);
        else return false;

        return !world.getEntitiesByClass(EnderPearlEntity.class, searchBox, pearl -> pearl.getOwner() == mover).isEmpty();
    }

    private static int discardMoverPearlsNearScenario(ServerWorld world, ServerPlayerEntity mover, DetectedScenario scenario) {
        if (world == null || mover == null || scenario == null) return 0;

        Box searchBox;
        if (scenario.water() != null) searchBox = new Box(scenario.water()).expand(4.0);
        else if (scenario.chamber() != null) searchBox = new Box(BlockPos.ofFloored(scenario.chamber())).expand(4.0);
        else return 0;

        int removed = 0;
        for (EnderPearlEntity pearl : world.getEntitiesByClass(EnderPearlEntity.class, searchBox, entity -> entity.getOwner() == mover)) {
            pearl.discard();
            removed++;
        }
        return removed;
    }

    private static void refillPearlChest(ServerWorld world, BlockPos pearlChestPos) {
        ChestBlockEntity chest = chest(world, pearlChestPos);
        if (chest == null) return;

        chest.clear();
        for (int slot = 0; slot < chest.size(); slot++) {
            chest.setStack(slot, new ItemStack(Items.ENDER_PEARL, 16));
        }
        chest.markDirty();
        StrictRuntimeLogger.logHarness(
            "STASHMOVER",
            "TRACE harness-refilled-pearl-chest chest=" + formatBlockPos(pearlChestPos)
                + " items=" + countChestItems(world, pearlChestPos)
                + " reason=session-baseline-reset"
        );
    }

    private static ChestBlockEntity chest(World world, BlockPos pos) {
        return world.getBlockEntity(pos) instanceof ChestBlockEntity chest ? chest : null;
    }

    private static ServerPlayerEntity findServerPlayer(MinecraftServer server, String username) {
        if (server == null || username == null || username.isBlank()) return null;
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            if (username.equalsIgnoreCase(player.getGameProfile().name())) return player;
        }
        return null;
    }

    private static RunSnapshot captureSnapshot(IntegratedServer server) {
        try {
            return server.submit(() -> captureSnapshotOnServer(server)).join();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String describeMoverPearls(IntegratedServer server) {
        try {
            return server.submit(() -> describeMoverPearlsOnServer(server)).join();
        } catch (Throwable ignored) {
            return "unavailable";
        }
    }

    private static RunSnapshot captureSnapshotOnServer(IntegratedServer server) {
        ServerWorld world = server.getOverworld();
        ServerPlayerEntity mover = findServerPlayer(server, MOVER_NAME);
        ServerPlayerEntity loader = findServerPlayer(server, LOADER_NAME);
        if (world == null || mover == null || loader == null) return null;

        if (useUserWorld()) {
            DetectedScenario scenario = detectedScenario == null ? detectScenario(world) : detectedScenario;
            if (scenario == null) return null;
            return new RunSnapshot(
                countChestItems(world, scenario.pearlChest()),
                countChestItems(world, scenario.sourceChests()),
                countConnectedChestItems(world, scenario.lootChest()),
                countInventoryItems(mover),
                formatVec(new Vec3d(mover.getX(), mover.getY(), mover.getZ())),
                formatVec(new Vec3d(loader.getX(), loader.getY(), loader.getZ())),
                isNear(mover, userMoverPos(), 4.0)
            );
        }

        return new RunSnapshot(
            countChestItems(world, PEARL_CHEST_POS),
            countDoubleChestItems(world, SOURCE_CHEST_LEFT, SOURCE_CHEST_RIGHT),
            countDoubleChestItems(world, LOOT_CHEST_LEFT, LOOT_CHEST_RIGHT),
            countInventoryItems(mover),
            formatVec(new Vec3d(mover.getX(), mover.getY(), mover.getZ())),
            formatVec(new Vec3d(loader.getX(), loader.getY(), loader.getZ())),
            isNear(mover, MOVER_POS, 4.0)
        );
    }

    private static String describeMoverPearlsOnServer(IntegratedServer server) {
        ServerWorld world = server == null ? null : server.getOverworld();
        ServerPlayerEntity mover = findServerPlayer(server, MOVER_NAME);
        DetectedScenario scenario = detectedScenario == null ? detectScenario(world) : detectedScenario;
        if (world == null || mover == null || scenario == null) return "missing-context";

        Box searchBox;
        if (scenario.water() != null) searchBox = new Box(scenario.water()).expand(4.0);
        else if (scenario.chamber() != null) searchBox = new Box(BlockPos.ofFloored(scenario.chamber())).expand(4.0);
        else return "missing-target";

        BlockPos chamberBlock = scenario.chamber() == null ? null : BlockPos.ofFloored(scenario.chamber());
        String chamberOpen = "n/a";
        if (chamberBlock != null
            && world.getBlockState(chamberBlock).getBlock() instanceof TrapdoorBlock
            && world.getBlockState(chamberBlock).contains(TrapdoorBlock.OPEN)) {
            chamberOpen = Boolean.toString(world.getBlockState(chamberBlock).get(TrapdoorBlock.OPEN));
        }

        List<EnderPearlEntity> pearls = world.getEntitiesByClass(
            EnderPearlEntity.class,
            searchBox,
            pearl -> pearl.getOwner() == mover
        );
        if (pearls.isEmpty()) return "count=0 chamberOpen=" + chamberOpen;

        StringBuilder builder = new StringBuilder();
        builder.append("count=").append(pearls.size()).append(" chamberOpen=").append(chamberOpen).append(" [");
        for (int i = 0; i < pearls.size(); i++) {
            EnderPearlEntity pearl = pearls.get(i);
            if (i > 0) builder.append("; ");
            builder.append("id=").append(pearl.getId())
                .append(" pos=").append(formatVec(pearl.getEntityPos()))
                .append(" vel=").append(formatVec(pearl.getVelocity()));
        }
        builder.append(']');
        return builder.toString();
    }

    private static int countChestItems(World world, BlockPos pos) {
        ChestBlockEntity chest = chest(world, pos);
        if (chest == null) return 0;
        int total = 0;
        for (int slot = 0; slot < chest.size(); slot++) total += chest.getStack(slot).getCount();
        return total;
    }

    private static int countDoubleChestItems(ServerWorld world, BlockPos leftPos, BlockPos rightPos) {
        return countChestItems(world, leftPos) + countChestItems(world, rightPos);
    }

    private static int countConnectedChestItems(World world, BlockPos pos) {
        int total = countChestItems(world, pos);
        BlockPos connected = connectedChestHalf(world, pos);
        if (connected != null) total += countChestItems(world, connected);
        return total;
    }

    private static int countConnectedChestSlots(World world, BlockPos pos) {
        ChestBlockEntity chest = chest(world, pos);
        int total = chest == null ? 0 : chest.size();
        BlockPos connected = connectedChestHalf(world, pos);
        if (connected != null) {
            ChestBlockEntity connectedChest = chest(world, connected);
            if (connectedChest != null) total += connectedChest.size();
        }
        return total;
    }

    private static int countConnectedChestOccupiedSlots(World world, BlockPos pos) {
        int total = countChestOccupiedSlots(world, pos);
        BlockPos connected = connectedChestHalf(world, pos);
        if (connected != null) total += countChestOccupiedSlots(world, connected);
        return total;
    }

    private static int countChestOccupiedSlots(World world, BlockPos pos) {
        ChestBlockEntity chest = chest(world, pos);
        if (chest == null) return 0;
        int occupied = 0;
        for (int slot = 0; slot < chest.size(); slot++) {
            if (!chest.getStack(slot).isEmpty()) occupied++;
        }
        return occupied;
    }

    private static BlockPos connectedChestHalf(World world, BlockPos pos) {
        if (world == null || pos == null) return null;
        if (!(world.getBlockState(pos).getBlock() instanceof ChestBlock)) return null;
        if (!world.getBlockState(pos).contains(ChestBlock.CHEST_TYPE)) return null;
        ChestType type = world.getBlockState(pos).get(ChestBlock.CHEST_TYPE);
        if (type == ChestType.SINGLE) return null;
        ChestType expectedNeighborType = type == ChestType.LEFT ? ChestType.RIGHT : ChestType.LEFT;

        for (Direction direction : Direction.Type.HORIZONTAL) {
            BlockPos neighbor = pos.offset(direction);
            if (!(world.getBlockState(neighbor).getBlock() instanceof ChestBlock)) continue;
            if (!world.getBlockState(neighbor).contains(ChestBlock.CHEST_TYPE)) continue;
            if (world.getBlockState(neighbor).get(ChestBlock.CHEST_TYPE) != expectedNeighborType) continue;
            if (world.getBlockState(pos).contains(ChestBlock.FACING)
                && world.getBlockState(neighbor).contains(ChestBlock.FACING)
                && world.getBlockState(pos).get(ChestBlock.FACING) != world.getBlockState(neighbor).get(ChestBlock.FACING)) {
                continue;
            }
            return neighbor.toImmutable();
        }

        return null;
    }

    private static int countChestItems(World world, List<BlockPos> positions) {
        if (positions == null || positions.isEmpty()) return 0;
        int total = 0;
        for (BlockPos pos : positions) total += countChestItems(world, pos);
        return total;
    }

    private static int countInventoryItems(ServerPlayerEntity player) {
        int total = 0;
        for (int slot = 0; slot < player.getInventory().size(); slot++) total += player.getInventory().getStack(slot).getCount();
        return total;
    }

    private static boolean isRunPass(RunSnapshot snapshot, StashMover module) {
        if (!sawSendPhase || !sawWaitPhase || !sawThrowPhase || !sawWalkingPhase) return false;
        if (requiresPutBackPhaseForPass() && !sawPutBackPhase) return false;
        if (!"none".equals(module.lastPearlFailureReasonValue())) return false;
        if (snapshot.moverInventoryItemCount != 0) return false;
        if (!snapshot.moverAtSource) return false;
        if (!"LOOT".equals(module.moverPhaseName())) return false;

        if (!useUserWorld()) {
            return snapshot.lootChestItemCount >= EXPECTED_TRANSFERRED_ITEMS
                && snapshot.sourceChestItemCount <= EXPECTED_SOURCE_REMAINING_ITEMS
                && snapshot.pearlChestCount >= INITIAL_PEARL_COUNT;
        }

        DetectedScenario scenario = detectedScenario;
        return scenario != null
            && snapshot.lootChestItemCount > scenario.initialLootItems()
            && snapshot.sourceChestItemCount < scenario.initialSourceItems()
            && snapshot.pearlChestCount > 0;
    }

    private static boolean isBootstrapWarmupPass(RunSnapshot snapshot, StashMover module) {
        if (!useUserWorld() || bootstrapWarmupCompleted || !firstRunBootstrapApplied || runIndex != 0) return false;
        if (!sawSendPhase || !sawWaitPhase || !sawThrowPhase || !sawWalkingPhase) return false;
        if (requiresPutBackPhaseForPass() && !sawPutBackPhase) return false;
        if (!"none".equals(module.lastPearlFailureReasonValue())) return false;
        if (snapshot.moverInventoryItemCount != 0) return false;
        if (!snapshot.moverAtSource) return false;
        if (!"LOOT".equals(module.moverPhaseName())) return false;

        DetectedScenario scenario = detectedScenario;
        return scenario != null
            && snapshot.lootChestItemCount > scenario.initialLootItems()
            && snapshot.sourceChestItemCount < scenario.initialSourceItems()
            && snapshot.pearlChestCount > 0;
    }

    private static boolean requiresPutBackPhaseForPass() {
        return !useUserWorld() || !realisticMode();
    }

    private static void maybeBootstrapInitialReturn(IntegratedServer server, StashMover module, RunSnapshot snapshot) {
        if (realisticMode()) return;
        if (!useUserWorld() || server == null || module == null || snapshot == null) return;
        if (firstRunBootstrapApplied || bootstrapWarmupCompleted || runIndex != 0) return;
        if (!sawWaitPhase || sawThrowPhase) return;
        if (stageTicks < 140) return;
        if (!snapshot.moverAtSource) return;
        if (!"WAIT_FOR_PEARL".equals(module.moverPhaseName())) return;

        try {
            Boolean applied = server.submit(() -> bootstrapInitialReturnOnServer(server)).join();
            if (Boolean.TRUE.equals(applied)) firstRunBootstrapApplied = true;
        } catch (Throwable ignored) {
        }
    }

    private static boolean bootstrapInitialReturnOnServer(IntegratedServer server) {
        if (server == null) return false;

        ServerWorld world = server.getOverworld();
        ServerPlayerEntity mover = findServerPlayer(server, MOVER_NAME);
        DetectedScenario scenario = detectedScenario == null ? detectScenario(world) : detectedScenario;
        Vec3d target = resolveBootstrapReturnPos(world, scenario);
        if (world == null || mover == null || scenario == null || target == null) return false;

        Vec3d from = new Vec3d(mover.getX(), mover.getY(), mover.getZ());
        int clearedPearls = discardMoverPearlsNearScenario(world, mover, scenario);
        mover.closeHandledScreen();
        mover.requestTeleport(target.x, target.y, target.z);
        mover.setVelocity(0.0, 0.0, 0.0);
        StrictRuntimeLogger.logHarness(
            "STASHMOVER",
            "STAGE first-run-bootstrap-return moverFrom=" + formatVec(from)
                + " moverTo=" + formatVec(target)
                + " lootChest=" + formatBlockPos(scenario.lootChest())
                + " clearedPearls=" + clearedPearls
                + " reason=seeded-stasis-did-not-release"
        );
        return true;
    }

    private static Vec3d resolveBootstrapReturnPos(World world, DetectedScenario scenario) {
        if (world == null || scenario == null || scenario.lootChest() == null) return null;

        Vec3d chestCenter = Vec3d.ofCenter(scenario.lootChest());
        if (scenario.water() != null) {
            Vec3d waterCenter = Vec3d.ofCenter(scenario.water());
            if (waterCenter.squaredDistanceTo(chestCenter) <= CONTAINER_REACH_SQ) return waterCenter;
        }
        if (scenario.pearlTarget() != null && scenario.pearlTarget().squaredDistanceTo(chestCenter) <= CONTAINER_REACH_SQ) {
            return scenario.pearlTarget();
        }
        if (scenario.loaderStand() != null && scenario.loaderStand().squaredDistanceTo(chestCenter) <= 4.0 * 4.0) {
            return scenario.loaderStand();
        }

        BlockPos chest = scenario.lootChest();
        for (int radius = 1; radius <= 2; radius++) {
            for (Direction direction : Direction.Type.HORIZONTAL) {
                BlockPos candidate = chest.offset(direction, radius);
                if (!canStandAt(world, candidate)) continue;
                Vec3d stand = bottomCenter(candidate);
                if (stand.squaredDistanceTo(chestCenter) <= CONTAINER_REACH_SQ) return stand;
            }
        }

        return scenario.loaderStand();
    }

    private static void maybeInjectRealisticDisturbance(IntegratedServer server, StashMover module, RunSnapshot snapshot) {
        if (!realisticMode() || server == null || module == null || snapshot == null) return;
        if (stageTicks < REALISTIC_DISTURBANCE_START_TICKS) return;

        // Disturb only after the mover has actually reached the loader station.
        boolean moverReachedLoaderStation = !snapshot.moverAtSource;
        boolean preThrowWindow = moverReachedLoaderStation
            && sawWaitPhase
            && !sawThrowPhase
            && ("WAIT_FOR_PEARL".equals(module.moverPhaseName()) || "WALKING_TO_CHEST".equals(module.moverPhaseName()));

        if (!moverDisturbanceApplied && preThrowWindow) {
            moverDisturbanceApplied = submitRealisticNudge(server, MOVER_NAME, REALISTIC_MOVER_PUSH_X, REALISTIC_MOVER_PUSH_Z, "mover-pre-throw");
        }

        if (!loaderDisturbanceApplied && preThrowWindow) {
            loaderDisturbanceApplied = submitRealisticNudge(server, LOADER_NAME, REALISTIC_LOADER_PUSH_X, REALISTIC_LOADER_PUSH_Z, "loader-post-load");
        }
    }

    private static boolean submitRealisticNudge(IntegratedServer server, String username, double velocityX, double velocityZ, String reason) {
        if (server == null || username == null || username.isBlank()) return false;

        try {
            return Boolean.TRUE.equals(server.submit(() -> applyRealisticNudge(server, username, velocityX, velocityZ, reason)).join());
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean applyRealisticNudge(MinecraftServer server, String username, double velocityX, double velocityZ, String reason) {
        ServerPlayerEntity player = findServerPlayer(server, username);
        if (player == null) return false;

        Vec3d before = new Vec3d(player.getX(), player.getY(), player.getZ());
        player.addVelocity(velocityX, 0.0, velocityZ);
        Vec3d afterVelocity = player.getVelocity();
        StrictRuntimeLogger.logHarness(
            "STASHMOVER",
            "TRACE realistic-disturbance player=" + username
                + " reason=" + reason
                + " pos=" + formatVec(before)
                + " push=" + formatVec(new Vec3d(velocityX, 0.0, velocityZ))
                + " resultingVelocity=" + formatVec(afterVelocity)
        );
        return true;
    }

    private static DetectedScenario activeScenario(MinecraftClient client) {
        if (!useUserWorld()) {
            return new DetectedScenario(
                PEARL_CHEST_POS,
                LOOT_CHEST_LEFT,
                WATER_POS,
                CHAMBER_LOOK_POS,
                null,
                List.of(SOURCE_CHEST_LEFT, SOURCE_CHEST_RIGHT),
                INITIAL_PEARL_COUNT,
                SOURCE_STACKS * 64,
                0,
                LOADER_POS
            );
        }

        if (detectedScenario != null) return detectedScenario;
        if (client != null && client.world != null) {
            detectedScenario = role() == Role.GUEST ? detectLoaderScenario(client.world) : detectScenario(client.world);
        }
        return detectedScenario;
    }

    private static DetectedScenario detectLoaderScenario(World world) {
        if (world == null) return null;

        List<BlockPos> loaderChests = scanChests(world, USER_LOADER_STATION, USER_LOADER_SCAN_RADIUS);
        BlockPos water = scanCube(USER_LOADER_STATION, USER_LOADER_SCAN_RADIUS).stream()
            .filter(pos -> world.getFluidState(pos).isIn(FluidTags.WATER))
            .min(Comparator.comparingDouble(pos -> squaredDistance(pos, USER_LOADER_STATION)))
            .orElse(null);
        BlockPos pearlChest = loaderChests.stream()
            .filter(pos -> countChestItem(world, pos, Items.ENDER_PEARL) > 0)
            .max(Comparator.comparingInt(pos -> countChestItem(world, pos, Items.ENDER_PEARL)))
            .orElse(null);
        if (pearlChest == null) {
            BlockPos pearlReference = water == null ? USER_LOADER_STATION : water;
            pearlChest = loaderChests.stream()
                .min(Comparator.comparingDouble(pos -> squaredDistance(pos, pearlReference)))
                .orElse(null);
        }
        BlockPos resolvedPearlChest = pearlChest;
        BlockPos lootChest = loaderChests.stream()
            .filter(pos -> !pos.equals(resolvedPearlChest))
            .min(Comparator
                .comparingInt((BlockPos pos) -> countChestItems(world, pos) == 0 ? 0 : 1)
                .thenComparingDouble(pos -> squaredDistance(pos, USER_LOADER_STATION)))
            .orElse(null);
        BlockPos chamberBlock = scanCube(water == null ? USER_LOADER_STATION : water, USER_LOADER_SCAN_RADIUS).stream()
            .filter(pos -> world.getBlockState(pos).getBlock() instanceof TrapdoorBlock)
            .min(Comparator.comparingDouble(pos -> squaredDistance(pos, water == null ? USER_LOADER_STATION : water)))
            .orElse(null);

        if (pearlChest == null || lootChest == null || water == null || chamberBlock == null) {
            StrictRuntimeLogger.logHarness(
                "STASHMOVER",
                "TRACE user-loader-detect-failed loaderChests=" + loaderChests
                    + " pearlChest=" + formatBlockPos(pearlChest)
                    + " lootChest=" + formatBlockPos(lootChest)
                    + " water=" + formatBlockPos(water)
                    + " chamber=" + formatBlockPos(chamberBlock)
            );
            return null;
        }

        Vec3d chamber = Vec3d.ofCenter(chamberBlock);
        Vec3d loaderStand = resolveLoaderStand(world, water, chamberBlock);
        StrictRuntimeLogger.logHarness(
            "STASHMOVER",
            "TRACE user-loader-chests " + describeChests(world, loaderChests)
        );
        return new DetectedScenario(
            pearlChest.toImmutable(),
            lootChest.toImmutable(),
            water.toImmutable(),
            chamber,
            null,
            List.of(),
            countChestItems(world, pearlChest),
            0,
            countConnectedChestItems(world, lootChest),
            loaderStand
        );
    }

    private static DetectedScenario detectScenario(World world) {
        if (world == null) return null;

        List<BlockPos> loaderChests = scanChests(world, USER_LOADER_STATION, USER_LOADER_SCAN_RADIUS);
        List<BlockPos> sourceChests = scanChests(world, USER_MOVER_STATION, USER_MOVER_SCAN_RADIUS);
        BlockPos pearlChest = loaderChests.stream()
            .filter(pos -> countChestItem(world, pos, Items.ENDER_PEARL) > 0)
            .max(Comparator.comparingInt(pos -> countChestItem(world, pos, Items.ENDER_PEARL)))
            .orElse(null);
        BlockPos lootChest = loaderChests.stream()
            .filter(pos -> !pos.equals(pearlChest))
            .min(Comparator
                .comparingInt((BlockPos pos) -> countChestItems(world, pos) == 0 ? 0 : 1)
                .thenComparingDouble(pos -> squaredDistance(pos, USER_LOADER_STATION)))
            .orElse(null);
        BlockPos water = scanCube(USER_LOADER_STATION, USER_LOADER_SCAN_RADIUS).stream()
            .filter(pos -> world.getFluidState(pos).isIn(FluidTags.WATER))
            .min(Comparator.comparingDouble(pos -> squaredDistance(pos, USER_LOADER_STATION)))
            .orElse(null);
        BlockPos chamberBlock = scanCube(water == null ? USER_LOADER_STATION : water, USER_LOADER_SCAN_RADIUS).stream()
            .filter(pos -> world.getBlockState(pos).getBlock() instanceof TrapdoorBlock)
            .min(Comparator.comparingDouble(pos -> squaredDistance(pos, water == null ? USER_LOADER_STATION : water)))
            .orElse(null);

        List<BlockPos> filteredSourceChests = sourceChests.stream()
            .filter(pos -> !pos.equals(pearlChest))
            .filter(pos -> !pos.equals(lootChest))
            .filter(pos -> countChestItems(world, pos) > 0)
            .toList();

        if (pearlChest == null || lootChest == null || water == null || chamberBlock == null || filteredSourceChests.isEmpty()) {
            StrictRuntimeLogger.logHarness(
                "STASHMOVER",
                "TRACE user-world-detect-failed loaderChests=" + loaderChests
                    + " sourceChests=" + sourceChests
                    + " pearlChest=" + formatBlockPos(pearlChest)
                    + " lootChest=" + formatBlockPos(lootChest)
                    + " water=" + formatBlockPos(water)
                    + " chamber=" + formatBlockPos(chamberBlock)
            );
            return null;
        }

        Vec3d chamber = Vec3d.ofCenter(chamberBlock);
        Vec3d loaderStand = resolveLoaderStand(world, water, chamberBlock);
        StrictRuntimeLogger.logHarness(
            "STASHMOVER",
            "TRACE user-world-chests loader=" + describeChests(world, loaderChests)
                + " source=" + describeChests(world, filteredSourceChests)
        );
        return new DetectedScenario(
            pearlChest.toImmutable(),
            lootChest.toImmutable(),
            water.toImmutable(),
            chamber,
            null,
            List.copyOf(filteredSourceChests),
            countChestItems(world, pearlChest),
            countChestItems(world, filteredSourceChests),
            countConnectedChestItems(world, lootChest),
            loaderStand
        );
    }

    private static Vec3d resolveLoaderStand(World world, BlockPos water, BlockPos chamberBlock) {
        BlockPos center = chamberBlock == null ? (water == null ? USER_LOADER_STATION : water) : chamberBlock;
        if (chamberBlock != null) {
            Direction[] preferred = {Direction.EAST, Direction.NORTH, Direction.SOUTH, Direction.WEST};
            for (Direction direction : preferred) {
                BlockPos candidate = chamberBlock.offset(direction);
                if (!candidate.equals(water) && canStandAt(world, candidate)) return bottomCenter(candidate);
            }
            for (Direction direction : preferred) {
                BlockPos candidate = chamberBlock.offset(direction, 2);
                if (!candidate.equals(water) && canStandAt(world, candidate)) return bottomCenter(candidate);
            }
        }

        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        for (int y = center.getY() - 1; y <= center.getY() + 1; y++) {
            for (int x = center.getX() - 3; x <= center.getX() + 3; x++) {
                for (int z = center.getZ() - 3; z <= center.getZ() + 3; z++) {
                    BlockPos candidate = new BlockPos(x, y, z);
                    if (candidate.equals(water) || candidate.equals(chamberBlock)) continue;
                    if (!canStandAt(world, candidate)) continue;
                    double score = squaredDistance(candidate, center) * 4.0 + squaredDistance(candidate, USER_LOADER_STATION);
                    if (score < bestScore) {
                        bestScore = score;
                        best = candidate.toImmutable();
                    }
                }
            }
        }
        return best == null ? userLoaderPos() : bottomCenter(best);
    }

    private static boolean canStandAt(World world, BlockPos pos) {
        if (world == null || pos == null) return false;
        if (!world.getFluidState(pos).isEmpty() || !world.getFluidState(pos.up()).isEmpty()) return false;
        if (!world.getBlockState(pos).isAir() || !world.getBlockState(pos.up()).isAir()) return false;
        if (world.getBlockState(pos.down()).isAir()) return false;
        return !world.getFluidState(pos.down()).isIn(FluidTags.WATER);
    }

    private static List<BlockPos> scanChests(World world, BlockPos center, int radius) {
        List<BlockPos> chests = new ArrayList<>();
        for (BlockPos pos : scanCube(center, radius)) {
            if (!(world.getBlockState(pos).getBlock() instanceof ChestBlock)) continue;
            if (!(world.getBlockEntity(pos) instanceof ChestBlockEntity)) continue;
            chests.add(pos.toImmutable());
        }
        chests.sort(Comparator.comparingDouble(pos -> squaredDistance(pos, center)));
        return chests;
    }

    private static String describeChests(World world, List<BlockPos> chests) {
        if (chests == null || chests.isEmpty()) return "[]";
        StringBuilder builder = new StringBuilder("[");
        int emitted = 0;
        for (BlockPos pos : chests) {
            if (emitted > 0) builder.append("; ");
            builder.append(formatBlockPos(pos))
                .append(" items=").append(countChestItems(world, pos))
                .append(" pearls=").append(countChestItem(world, pos, Items.ENDER_PEARL))
                .append(" occupied=").append(countChestOccupiedSlots(world, pos))
                .append('/').append(countConnectedChestSlots(world, pos));
            BlockPos connected = connectedChestHalf(world, pos);
            if (connected != null) builder.append(" connected=").append(formatBlockPos(connected));
            emitted++;
            if (emitted >= 16 && chests.size() > emitted) {
                builder.append("; ... total=").append(chests.size());
                break;
            }
        }
        builder.append(']');
        return builder.toString();
    }

    private static List<BlockPos> scanCube(BlockPos center, int radius) {
        List<BlockPos> positions = new ArrayList<>();
        if (center == null) return positions;
        for (int x = center.getX() - radius; x <= center.getX() + radius; x++) {
            for (int y = center.getY() - radius; y <= center.getY() + radius; y++) {
                for (int z = center.getZ() - radius; z <= center.getZ() + radius; z++) {
                    positions.add(new BlockPos(x, y, z));
                }
            }
        }
        return positions;
    }

    private static int countChestItem(World world, BlockPos pos, net.minecraft.item.Item item) {
        ChestBlockEntity chest = chest(world, pos);
        if (chest == null) return 0;
        int total = 0;
        for (int slot = 0; slot < chest.size(); slot++) {
            ItemStack stack = chest.getStack(slot);
            if (stack.isOf(item)) total += stack.getCount();
        }
        return total;
    }

    private static double squaredDistance(BlockPos a, BlockPos b) {
        if (a == null || b == null) return Double.MAX_VALUE;
        double dx = a.getX() - b.getX();
        double dy = a.getY() - b.getY();
        double dz = a.getZ() - b.getZ();
        return dx * dx + dy * dy + dz * dz;
    }

    private static Vec3d activeMoverPos() {
        return useUserWorld() ? userMoverPos() : MOVER_POS;
    }

    private static Vec3d activeLoaderPos() {
        if (useUserWorld() && detectedScenario != null && detectedScenario.loaderStand() != null) {
            return detectedScenario.loaderStand();
        }
        return useUserWorld() ? userLoaderPos() : LOADER_POS;
    }

    private static Vec3d userMoverPos() {
        return bottomCenter(USER_MOVER_STATION);
    }

    private static Vec3d userLoaderPos() {
        return bottomCenter(USER_LOADER_STATION);
    }

    private static Vec3d bottomCenter(BlockPos pos) {
        return new Vec3d(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
    }

    private static boolean isNear(ServerPlayerEntity player, Vec3d target, double maxDistance) {
        if (player == null || target == null) return false;
        double dx = player.getX() - target.x;
        double dy = player.getY() - target.y;
        double dz = player.getZ() - target.z;
        return dx * dx + dy * dy + dz * dz <= maxDistance * maxDistance;
    }

    private static boolean useUserWorld() {
        return Boolean.getBoolean(USER_WORLD_PROPERTY);
    }

    private static void deactivateStashMoverForHarness() {
        try {
            StashMover module = Modules.get().get(StashMover.class);
            if (module != null && module.isActive()) {
                module.toggle();
                StrictRuntimeLogger.logHarness("STASHMOVER", "TRACE deactivated-preexisting-module role=" + role().name().toLowerCase());
            }
        } catch (Throwable ignored) {
        }
    }

    private static String userWorldName() {
        return System.getProperty(USER_WORLD_NAME_PROPERTY, DEFAULT_USER_WORLD_NAME);
    }

    private static void openExistingUserWorld(MinecraftClient client) {
        try {
            cleanupUserWorldSessionArtifacts(client);
            Object serverLoader = MinecraftClient.class.getMethod("createIntegratedServerLoader").invoke(client);
            serverLoader.getClass()
                .getMethod("start", String.class, Runnable.class)
                .invoke(serverLoader, userWorldName(), (Runnable) () -> {
                });
            StrictRuntimeLogger.logHarness(
                "STASHMOVER",
                "TRACE open-existing-world-requested worldName=" + userWorldName() + " strategy=integrated-server-loader"
            );
        } catch (Throwable throwable) {
            StrictRuntimeLogger.logHarness(
                "STASHMOVER",
                "RESULT FAIL role=host open-existing-world failed=" + throwable.getClass().getName()
                    + " message=" + String.valueOf(throwable.getMessage())
            );
        }
    }

    private static void cleanupUserWorldSessionArtifacts(MinecraftClient client) {
        try {
            if (client == null) return;
            File runDirectory = client.runDirectory;
            if (runDirectory == null) return;

            Path worldDir = runDirectory.toPath().resolve("saves").resolve(userWorldName());
            Path sessionLock = worldDir.resolve("session.lock");

            boolean deletedLock = Files.deleteIfExists(sessionLock);
            if (deletedLock) {
                StrictRuntimeLogger.logHarness(
                    "STASHMOVER",
                    "TRACE user-world-cleanup worldName=" + userWorldName()
                        + " deletedSessionLock=" + deletedLock
                );
            }
        } catch (Throwable throwable) {
            StrictRuntimeLogger.logHarness(
                "STASHMOVER",
                "TRACE user-world-cleanup-failed worldName=" + userWorldName()
                    + " error=" + throwable.getClass().getSimpleName()
                    + " message=" + String.valueOf(throwable.getMessage())
            );
        }
    }

    private static String currentUsername(MinecraftClient client) {
        if (client == null || client.getSession() == null) return "";
        return client.getSession().getUsername();
    }

    private static String formatVec(Vec3d pos) {
        return pos == null ? "<unset>" : String.format(java.util.Locale.ROOT, "%.2f, %.2f, %.2f", pos.x, pos.y, pos.z);
    }

    private static String formatBlockPos(BlockPos pos) {
        return pos == null ? "<unset>" : pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
    }

    private static Role role() {
        String value = System.getProperty(ROLE_PROPERTY, "host").trim();
        return "guest".equalsIgnoreCase(value) ? Role.GUEST : Role.HOST;
    }

    private static boolean realisticMode() {
        return Boolean.getBoolean(REALISTIC_PROPERTY);
    }

    private static int runTimeoutTicks() {
        return realisticMode() ? REALISTIC_RUN_TIMEOUT_TICKS : RUN_TIMEOUT_TICKS;
    }

    private static void resetRunFlags() {
        sawSendPhase = false;
        sawWaitPhase = false;
        sawThrowPhase = false;
        sawPutBackPhase = false;
        sawWalkingPhase = false;
        moverDisturbanceApplied = false;
        loaderDisturbanceApplied = false;
    }

    private static void advance(Stage next) {
        stage = next;
        stageTicks = 0;
    }

    private static void fail(MinecraftClient client, String detail) {
        StrictRuntimeLogger.logHarness("STASHMOVER", "RESULT FAIL role=" + role().name().toLowerCase() + ' ' + detail);
        finish(client, false);
    }

    private static void finish(MinecraftClient client, boolean success) {
        completed = true;
        stage = success ? Stage.FINISHED : Stage.FAILED;
        StrictRuntimeLogger.logHarness("STASHMOVER", "SUMMARY live-runtime finished=" + Instant.now() + " role=" + role().name().toLowerCase());
        try {
            client.scheduleStop();
        } catch (Throwable ignored) {
        }
    }

    private enum Role {
        HOST,
        GUEST
    }

    private enum Stage {
        STARTUP_DELAY,
        PREPARE_SESSION,
        OPEN_TEST_WORLD,
        WAIT_FOR_WORLD,
        OPEN_LAN,
        WAIT_FOR_GUEST,
        PREPARE_RUN,
        WAIT_FOR_SYNC,
        START_RUN,
        RUN_SCENARIO,
        MONITOR,
        FINISHED,
        FAILED
    }

    private record RunSnapshot(
        int pearlChestCount,
        int sourceChestItemCount,
        int lootChestItemCount,
        int moverInventoryItemCount,
        String moverPos,
        String loaderPos,
        boolean moverAtSource
    ) {
    }

    private record DetectedScenario(
        BlockPos pearlChest,
        BlockPos lootChest,
        BlockPos water,
        Vec3d chamber,
        Vec3d pearlTarget,
        List<BlockPos> sourceChests,
        int initialPearlItems,
        int initialSourceItems,
        int initialLootItems,
        Vec3d loaderStand
    ) {
        String summary() {
            return "pearlChest=" + formatBlockPos(pearlChest)
                + " lootChest=" + formatBlockPos(lootChest)
                + " water=" + formatBlockPos(water)
                + " chamber=" + formatVec(chamber)
                + " pearlTarget=" + formatVec(pearlTarget)
                + " loaderStand=" + formatVec(loaderStand)
                + " sourceChests=" + sourceChests.size()
                + " initialPearlItems=" + initialPearlItems
                + " initialSourceItems=" + initialSourceItems
                + " initialLootItems=" + initialLootItems;
        }
    }
}
