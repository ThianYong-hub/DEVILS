package com.example.addon.util.smoke;

import com.example.addon.modules.NukerPlus;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.player.SpeedMine;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.world.CreateWorldScreen;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class NukerPlusDamageTimeRuntimeValidation {
    private static final Logger LOG = LoggerFactory.getLogger("Devils/NukerPlusDamageRuntime");
    private static final String ENABLE_PROPERTY = "devils.nukerplus.damage.runtime";
    private static final String OUTPUT_DIR_PROPERTY = "devils.nukerplus.damage.runtime.dir";
    private static final int START_DELAY_TICKS = 60;
    private static final int WORLD_LOAD_TIMEOUT_TICKS = 1200;
    private static final int CLIENT_SYNC_TICKS = 5;
    private static final int SCENARIO_READY_TIMEOUT_TICKS = 240;
    private static final int SCENARIO_STABLE_TICKS = 5;
    private static final int SCENARIO_REFRESH_INTERVAL_TICKS = 40;
    private static final int SINGLE_TRIALS = 3;
    private static final int SINGLE_TRIAL_TIMEOUT_TICKS = 240;
    private static final int WINDOW_TICKS = 120;
    private static final int WINDOW_BLOCK_COUNT = 6;
    private static final int INSTA_TIMEOUT_TICKS = 40;
    private static final int TARGET_SWITCH_TIMEOUT_TICKS = 80;
    private static final int CLEANUP_DISABLE_TICK = 2;
    private static final int CLEANUP_TIMEOUT_TICKS = 30;

    private static final Vec3d PLAYER_POS = new Vec3d(0.5, 64.0, 0.5);
    private static final BlockPos SINGLE_TARGET_POS = new BlockPos(2, 64, 0);
    private static final List<BlockPos> WINDOW_TARGETS = createWindowTargets();

    private static boolean installed;
    private static boolean completed;
    private static boolean worldRequested;
    private static Stage stage = Stage.STARTUP_DELAY;
    private static int stageTicks;
    private static Path outputDir;

    private static final List<BenchmarkCase> benchmarkCases = createBenchmarkCases();
    private static final List<BenchmarkRecord> benchmarkRecords = new ArrayList<>();
    private static final List<SmokeAssertion> smokeAssertions = new ArrayList<>();
    private static final List<String> runtimeNotes = new ArrayList<>();

    private static int caseIndex;
    private static int trialIndex;
    private static int scenarioReadyStreak;
    private static BenchmarkRecord activeRecord;

    private static int previousWindowBrokenCount;
    private static boolean instaCompatObserved;
    private static int instaCompatBreakTick = -1;
    private static int targetSwitchFirstBreakTick = -1;
    private static int targetSwitchSecondBreakTick = -1;
    private static int targetSwitchExpectedTicks;
    private static int damageBurstFirstBreakTick = -1;
    private static int damageBurstAllBreakTick = -1;
    private static boolean cleanupDisabled;
    private static boolean cleanupValidated;

    private NukerPlusDamageTimeRuntimeValidation() {
    }

    public static void install() {
        if (installed || !Boolean.getBoolean(ENABLE_PROPERTY)) return;
        installed = true;
        outputDir = resolveOutputDir();

        ClientLifecycleEvents.CLIENT_STARTED.register(client -> {
            completed = false;
            worldRequested = false;
            stage = Stage.STARTUP_DELAY;
            stageTicks = 0;
            caseIndex = 0;
            trialIndex = 0;
            activeRecord = null;
            previousWindowBrokenCount = 0;
            instaCompatObserved = false;
            instaCompatBreakTick = -1;
            targetSwitchFirstBreakTick = -1;
            targetSwitchSecondBreakTick = -1;
            targetSwitchExpectedTicks = 0;
            damageBurstFirstBreakTick = -1;
            damageBurstAllBreakTick = -1;
            cleanupDisabled = false;
            cleanupValidated = false;
            benchmarkRecords.clear();
            smokeAssertions.clear();
            runtimeNotes.clear();
            prepareOutputDir(outputDir);
            disablePauseOnLostFocus(client);
            note("SUMMARY nukerplus-damage-runtime started=" + Instant.now());
        });

        ClientTickEvents.END_CLIENT_TICK.register(NukerPlusDamageTimeRuntimeValidation::tick);
    }

    private static void tick(MinecraftClient client) {
        if (!installed || completed) return;

        stageTicks++;
        switch (stage) {
            case STARTUP_DELAY -> tickStartupDelay(client);
            case WAIT_FOR_WORLD -> tickWaitForWorld(client);
            case PREPARE_SINGLE_TRIAL -> tickPrepareSingleTrial(client);
            case RUN_SINGLE_TRIAL -> tickRunSingleTrial(client);
            case PREPARE_WINDOW -> tickPrepareWindow(client);
            case RUN_WINDOW -> tickRunWindow(client);
            case PREPARE_INSTA_COMPAT -> tickPrepareInstaCompat(client);
            case RUN_INSTA_COMPAT -> tickRunInstaCompat(client);
            case PREPARE_TARGET_SWITCH -> tickPrepareTargetSwitch(client);
            case RUN_TARGET_SWITCH -> tickRunTargetSwitch(client);
            case PREPARE_DAMAGE_BURST -> tickPrepareDamageBurst(client);
            case RUN_DAMAGE_BURST -> tickRunDamageBurst(client);
            case PREPARE_CLEANUP -> tickPrepareCleanup(client);
            case RUN_CLEANUP -> tickRunCleanup(client);
            case FINISHED, FAILED -> {
            }
        }
    }

    private static void tickStartupDelay(MinecraftClient client) {
        if (stageTicks < START_DELAY_TICKS) return;

        if (!worldRequested) {
            worldRequested = true;
            note("STAGE opening-test-world");
            client.execute(() -> CreateWorldScreen.showTestWorld(client, () -> {
            }));
        }

        advance(Stage.WAIT_FOR_WORLD);
    }

    private static void tickWaitForWorld(MinecraftClient client) {
        if (client.world != null && client.player != null && client.getServer() != null) {
            client.setScreen(null);
            note("STAGE world-loaded screen=" + safeClassName(client.currentScreen));
            advance(Stage.PREPARE_SINGLE_TRIAL);
            return;
        }

        if (stageTicks > WORLD_LOAD_TIMEOUT_TICKS) {
            fail(client, "Timed out while waiting for NukerPlus benchmark world load.");
        }
    }

    private static void tickPrepareSingleTrial(MinecraftClient client) {
        if (caseIndex >= benchmarkCases.size()) {
            advance(Stage.PREPARE_INSTA_COMPAT);
            return;
        }

        BenchmarkCase benchmarkCase = benchmarkCases.get(caseIndex);
        if (stageTicks == 1) {
            scenarioReadyStreak = 0;
            if (trialIndex == 0) activeRecord = new BenchmarkRecord(benchmarkCase);
            if (!prepareScenario(client, benchmarkCase, List.of(SINGLE_TARGET_POS))) return;
            note("STAGE single-setup case=" + benchmarkCase.id() + " trial=" + (trialIndex + 1));
        }

        if (!awaitStableScenario(client, benchmarkCase, List.of(SINGLE_TARGET_POS), "Scenario did not become ready for " + benchmarkCase.id())) return;

        if (!captureMechanicsSnapshot(client, activeRecord, SINGLE_TARGET_POS)) {
            fail(client, "Failed to capture mechanics snapshot for " + benchmarkCase.id());
            return;
        }

        advance(Stage.RUN_SINGLE_TRIAL);
    }

    private static void tickRunSingleTrial(MinecraftClient client) {
        BenchmarkCase benchmarkCase = benchmarkCases.get(caseIndex);
        NukerPlus module = requireNukerPlus(client);
        if (module == null) return;

        if (stageTicks == 1) {
            activateModule(module);
            note("TRACE single-start case=" + benchmarkCase.id() + " trial=" + (trialIndex + 1));
        }

        if (stageTicks % 20 == 0) {
            note("TRACE single-state case=" + benchmarkCase.id()
                + " trial=" + (trialIndex + 1)
                + " tick=" + stageTicks
                + " breaking=" + (client.interactionManager != null && client.interactionManager.isBreakingBlock())
                + " state=" + module.debugDamageStateSummary()
                + " forced=" + module.debugDamageForcedFinishCount()
                + " retries=" + module.debugDamageRetryCount());
        }

        if (client.world != null && client.world.getBlockState(SINGLE_TARGET_POS).isAir()) {
            activeRecord.singleTrialTicks.add(stageTicks);
            activeRecord.singleTrialForcedFinishCounts.add(module.debugDamageForcedFinishCount());
            activeRecord.singleTrialRetryCounts.add(module.debugDamageRetryCount());
            deactivateModule(module);
            note("TRACE single-finish case=" + benchmarkCase.id() + " trial=" + (trialIndex + 1) + " ticks=" + stageTicks);

            trialIndex++;
            if (trialIndex < SINGLE_TRIALS) {
                advance(Stage.PREPARE_SINGLE_TRIAL);
            } else {
                trialIndex = 0;
                advance(Stage.PREPARE_WINDOW);
            }
            return;
        }

        if (stageTicks > SINGLE_TRIAL_TIMEOUT_TICKS) {
            fail(client, "Single-break trial timed out for " + benchmarkCase.id()
                + " at " + stageTicks
                + " ticks. state=" + module.debugDamageStateSummary()
                + " forced=" + module.debugDamageForcedFinishCount()
                + " retries=" + module.debugDamageRetryCount()
                + " breaking=" + (client.interactionManager != null && client.interactionManager.isBreakingBlock()));
        }
    }

    private static void tickPrepareWindow(MinecraftClient client) {
        BenchmarkCase benchmarkCase = benchmarkCases.get(caseIndex);
        if (stageTicks == 1) {
            scenarioReadyStreak = 0;
            if (!prepareScenario(client, benchmarkCase, WINDOW_TARGETS)) return;
            previousWindowBrokenCount = 0;
            activeRecord.windowFirstBreakTick = -1;
            note("STAGE window-setup case=" + benchmarkCase.id());
        }

        if (!awaitStableScenario(client, benchmarkCase, WINDOW_TARGETS, "Window scenario did not become ready for " + benchmarkCase.id())) return;
        advance(Stage.RUN_WINDOW);
    }

    private static void tickRunWindow(MinecraftClient client) {
        BenchmarkCase benchmarkCase = benchmarkCases.get(caseIndex);
        NukerPlus module = requireNukerPlus(client);
        if (module == null) return;

        if (stageTicks == 1) {
            activateModule(module);
            note("TRACE window-start case=" + benchmarkCase.id());
        }

        int brokenCount = countBrokenTargets(client, WINDOW_TARGETS);
        if (brokenCount > previousWindowBrokenCount && activeRecord.windowFirstBreakTick < 0) {
            activeRecord.windowFirstBreakTick = stageTicks;
        }
        previousWindowBrokenCount = brokenCount;

        if (stageTicks >= WINDOW_TICKS || brokenCount >= WINDOW_TARGETS.size()) {
            activeRecord.windowBlocksBroken = brokenCount;
            activeRecord.windowForcedFinishCount = module.debugDamageForcedFinishCount();
            activeRecord.windowRetryCount = module.debugDamageRetryCount();
            benchmarkRecords.add(activeRecord);
            deactivateModule(module);
            note("TRACE window-finish case=" + benchmarkCase.id() + " broken=" + brokenCount + " firstBreakTick=" + activeRecord.windowFirstBreakTick);

            caseIndex++;
            activeRecord = null;
            advance(Stage.PREPARE_SINGLE_TRIAL);
        }
    }

    private static void tickPrepareInstaCompat(MinecraftClient client) {
        BenchmarkCase benchmarkCase = new BenchmarkCase("insta-compat", "netherrack", Blocks.NETHERRACK, NukerPlus.MiningAccelerationMode.Insta, 1.0);
        if (stageTicks == 1) {
            scenarioReadyStreak = 0;
            if (!prepareScenario(client, benchmarkCase, List.of(SINGLE_TARGET_POS))) return;
            note("STAGE insta-compat-setup");
        }

        if (!awaitStableScenario(client, benchmarkCase, List.of(SINGLE_TARGET_POS), "Insta compatibility scenario did not become ready.")) return;
        advance(Stage.RUN_INSTA_COMPAT);
    }

    private static void tickRunInstaCompat(MinecraftClient client) {
        NukerPlus module = requireNukerPlus(client);
        if (module == null) return;

        if (stageTicks == 1) activateModule(module);

        if (client.world != null && client.world.getBlockState(SINGLE_TARGET_POS).isAir()) {
            instaCompatObserved = true;
            instaCompatBreakTick = stageTicks;
            deactivateModule(module);
            note("TRACE insta-compat-finish tick=" + stageTicks);
            advance(Stage.PREPARE_TARGET_SWITCH);
            return;
        }

        if (stageTicks > INSTA_TIMEOUT_TICKS) {
            deactivateModule(module);
            note("TRACE insta-compat-timeout tick=" + stageTicks);
            advance(Stage.PREPARE_TARGET_SWITCH);
        }
    }

    private static void tickPrepareTargetSwitch(MinecraftClient client) {
        BenchmarkCase benchmarkCase = new BenchmarkCase("target-switch", "stone", Blocks.STONE, NukerPlus.MiningAccelerationMode.SpeedMineDamage, 0.60);
        if (stageTicks == 1) {
            scenarioReadyStreak = 0;
            if (!prepareScenario(client, benchmarkCase, List.of(SINGLE_TARGET_POS, SINGLE_TARGET_POS.east()))) return;
            targetSwitchFirstBreakTick = -1;
            targetSwitchSecondBreakTick = -1;
            note("STAGE target-switch-setup");
        }

        if (!awaitStableScenario(client, benchmarkCase, List.of(SINGLE_TARGET_POS, SINGLE_TARGET_POS.east()), "Target-switch scenario did not become ready.")) return;

        if (targetSwitchExpectedTicks <= 0 && client.world != null && client.player != null) {
            float delta = client.world.getBlockState(SINGLE_TARGET_POS).calcBlockBreakingDelta(client.player, client.world, SINGLE_TARGET_POS);
            targetSwitchExpectedTicks = NukerPlus.calculateTargetBreakTicks(NukerPlus.calculateVanillaBreakTicks(delta), 0.60, delta);
            note("TRACE target-switch-mechanics expectedTicks=" + targetSwitchExpectedTicks + " delta=" + formatFloat(delta));
        }
        advance(Stage.RUN_TARGET_SWITCH);
    }

    private static void tickRunTargetSwitch(MinecraftClient client) {
        NukerPlus module = requireNukerPlus(client);
        if (module == null) return;

        BlockPos secondPos = SINGLE_TARGET_POS.east();
        if (stageTicks == 1) activateModule(module);

        boolean firstBroken = client.world != null && client.world.getBlockState(SINGLE_TARGET_POS).isAir();
        boolean secondBroken = client.world != null && client.world.getBlockState(secondPos).isAir();

        if (firstBroken && targetSwitchFirstBreakTick < 0) targetSwitchFirstBreakTick = stageTicks;
        if (secondBroken && targetSwitchSecondBreakTick < 0) targetSwitchSecondBreakTick = stageTicks;

        if (firstBroken && secondBroken) {
            deactivateModule(module);
            int gap = targetSwitchSecondBreakTick - targetSwitchFirstBreakTick;
            boolean pass = targetSwitchFirstBreakTick > 0
                && targetSwitchSecondBreakTick > targetSwitchFirstBreakTick
                && gap >= Math.max(1, targetSwitchExpectedTicks - 1);
            smokeAssertions.add(new SmokeAssertion(
                "SMOKE-10 TARGET SWITCH / RESET",
                pass,
                "firstBreakTick=" + targetSwitchFirstBreakTick + " secondBreakTick=" + targetSwitchSecondBreakTick + " gap=" + gap + " expectedTicks=" + targetSwitchExpectedTicks
            ));
            note("TRACE target-switch-finish gap=" + gap + " pass=" + pass);
            advance(Stage.PREPARE_DAMAGE_BURST);
            return;
        }

        if (stageTicks > TARGET_SWITCH_TIMEOUT_TICKS) {
            deactivateModule(module);
            smokeAssertions.add(new SmokeAssertion(
                "SMOKE-10 TARGET SWITCH / RESET",
                false,
                "Timed out before both blocks broke. firstBreakTick=" + targetSwitchFirstBreakTick + " secondBreakTick=" + targetSwitchSecondBreakTick
            ));
            advance(Stage.PREPARE_DAMAGE_BURST);
        }
    }

    private static void tickPrepareDamageBurst(MinecraftClient client) {
        BenchmarkCase benchmarkCase = new BenchmarkCase("damage-burst", "stone", Blocks.STONE, NukerPlus.MiningAccelerationMode.SpeedMineDamage, 0.60, 4);
        List<BlockPos> targets = WINDOW_TARGETS.subList(0, 4);
        if (stageTicks == 1) {
            scenarioReadyStreak = 0;
            if (!prepareScenario(client, benchmarkCase, targets)) return;
            damageBurstFirstBreakTick = -1;
            damageBurstAllBreakTick = -1;
            note("STAGE damage-burst-setup");
        }

        if (!awaitStableScenario(client, benchmarkCase, targets, "Damage burst scenario did not become ready.")) return;
        advance(Stage.RUN_DAMAGE_BURST);
    }

    private static void tickRunDamageBurst(MinecraftClient client) {
        NukerPlus module = requireNukerPlus(client);
        if (module == null) return;

        List<BlockPos> targets = WINDOW_TARGETS.subList(0, 4);
        if (stageTicks == 1) activateModule(module);

        int broken = countBrokenTargets(client, targets);
        if (broken > 0 && damageBurstFirstBreakTick < 0) damageBurstFirstBreakTick = stageTicks;
        if (broken >= targets.size()) {
            damageBurstAllBreakTick = stageTicks;
            deactivateModule(module);
            int spread = damageBurstAllBreakTick - damageBurstFirstBreakTick;
            boolean pass = damageBurstFirstBreakTick > 0 && spread <= 1;
            smokeAssertions.add(new SmokeAssertion(
                "SMOKE-12 DAMAGE BURST CHAIN",
                pass,
                "firstBreakTick=" + damageBurstFirstBreakTick
                    + " allBreakTick=" + damageBurstAllBreakTick
                    + " spread=" + spread
                    + " maxBlocksPerTick=4 forced=" + module.debugDamageForcedFinishCount()
            ));
            note("TRACE damage-burst-finish first=" + damageBurstFirstBreakTick + " all=" + damageBurstAllBreakTick + " spread=" + spread + " pass=" + pass);
            advance(Stage.PREPARE_CLEANUP);
            return;
        }

        if (stageTicks > TARGET_SWITCH_TIMEOUT_TICKS) {
            deactivateModule(module);
            smokeAssertions.add(new SmokeAssertion(
                "SMOKE-12 DAMAGE BURST CHAIN",
                false,
                "Timed out. broken=" + broken + " firstBreakTick=" + damageBurstFirstBreakTick + " forced=" + module.debugDamageForcedFinishCount()
            ));
            advance(Stage.PREPARE_CLEANUP);
        }
    }

    private static void tickPrepareCleanup(MinecraftClient client) {
        BenchmarkCase benchmarkCase = new BenchmarkCase("cleanup", "stone", Blocks.STONE, NukerPlus.MiningAccelerationMode.SpeedMineDamage, 0.60);
        if (stageTicks == 1) {
            scenarioReadyStreak = 0;
            if (!prepareScenario(client, benchmarkCase, List.of(SINGLE_TARGET_POS))) return;
            cleanupDisabled = false;
            cleanupValidated = false;
            note("STAGE cleanup-setup");
        }

        if (!awaitStableScenario(client, benchmarkCase, List.of(SINGLE_TARGET_POS), "Cleanup scenario did not become ready.")) return;
        advance(Stage.RUN_CLEANUP);
    }

    private static void tickRunCleanup(MinecraftClient client) {
        NukerPlus module = requireNukerPlus(client);
        if (module == null) return;

        if (stageTicks == 1) activateModule(module);
        if (stageTicks == CLEANUP_DISABLE_TICK && module.isActive()) {
            module.toggle();
            cleanupDisabled = true;
        }

        if (cleanupDisabled) {
            boolean breaking = client.interactionManager != null && client.interactionManager.isBreakingBlock();
            boolean pass = !breaking && "idle".equals(module.debugDamageStateSummary());
            if (pass) {
                cleanupValidated = true;
                smokeAssertions.add(new SmokeAssertion(
                    "SMOKE-11 CLEANUP",
                    true,
                    "breaking=" + breaking + " state=" + module.debugDamageStateSummary()
                ));
                finish(client, true, null);
                return;
            }
        }

        if (stageTicks > CLEANUP_TIMEOUT_TICKS) {
            smokeAssertions.add(new SmokeAssertion(
                "SMOKE-11 CLEANUP",
                cleanupValidated,
                "cleanupDisabled=" + cleanupDisabled + " state=" + module.debugDamageStateSummary()
            ));
            finish(client, cleanupValidated, cleanupValidated ? null : "Cleanup state did not settle to idle.");
        }
    }

    private static boolean prepareScenario(MinecraftClient client, BenchmarkCase benchmarkCase, List<BlockPos> targets) {
        MinecraftServer server = client.getServer();
        if (server == null || client.player == null) {
            fail(client, "Integrated server or player unavailable while preparing " + benchmarkCase.id());
            return false;
        }

        server.executeSync(() -> setupScenario(server, client, benchmarkCase.block, targets));

        SpeedMine speedMine = Modules.get().get(SpeedMine.class);
        if (speedMine != null && speedMine.isActive()) speedMine.toggle();

        NukerPlus module = Modules.get().get(NukerPlus.class);
        if (module == null) {
            fail(client, "NukerPlus module not registered.");
            return false;
        }

        deactivateModule(module);
        module.debugResetDamageHarnessState();
        module.debugConfigureDamageHarness(benchmarkCase.mode, benchmarkCase.damageMultiplier, benchmarkCase.block, benchmarkCase.maxBlocksPerTick);
        client.setScreen(null);
        return true;
    }

    private static void setupScenario(MinecraftServer server, MinecraftClient client, Block block, List<BlockPos> targets) {
        ServerWorld world = server.getOverworld();
        ServerPlayerEntity player = server.getPlayerManager().getPlayer(client.player.getUuid());
        if (player == null) return;

        for (int x = -4; x <= 16; x++) {
            for (int z = -3; z <= 3; z++) {
                world.setBlockState(new BlockPos(x, 63, z), Blocks.OBSIDIAN.getDefaultState());
                world.setBlockState(new BlockPos(x, 64, z), Blocks.AIR.getDefaultState());
                world.setBlockState(new BlockPos(x, 65, z), Blocks.AIR.getDefaultState());
                world.setBlockState(new BlockPos(x, 66, z), Blocks.AIR.getDefaultState());
            }
        }

        for (BlockPos pos : targets) {
            world.setBlockState(pos, block.getDefaultState());
        }

        player.changeGameMode(net.minecraft.world.GameMode.SURVIVAL);
        player.getInventory().clear();
        player.getInventory().setStack(0, new ItemStack(Items.IRON_PICKAXE));
        player.getInventory().setSelectedSlot(0);
        player.requestTeleport(PLAYER_POS.x, PLAYER_POS.y, PLAYER_POS.z);
        player.setVelocity(0.0, 0.0, 0.0);
        player.setYaw(-90.0f);
        player.setPitch(0.0f);
        player.setHealth(player.getMaxHealth());
        player.getHungerManager().setFoodLevel(20);
        player.getHungerManager().setSaturationLevel(20.0f);
        for (StatusEffectInstance effect : List.copyOf(player.getStatusEffects())) {
            player.removeStatusEffect(effect.getEffectType());
        }
        player.playerScreenHandler.sendContentUpdates();
        player.closeHandledScreen();
    }

    private static boolean captureMechanicsSnapshot(MinecraftClient client, BenchmarkRecord record, BlockPos targetPos) {
        if (client.world == null || client.player == null || record == null) return false;

        BlockState state = client.world.getBlockState(targetPos);
        if (state.isAir()) return false;

        float delta = state.calcBlockBreakingDelta(client.player, client.world, targetPos);
        record.delta = delta;
        record.vanillaBreakTicks = NukerPlus.calculateVanillaBreakTicks(delta);
        record.targetBreakTicks = record.benchmarkCase.mode == NukerPlus.MiningAccelerationMode.SpeedMineDamage
            ? NukerPlus.calculateTargetBreakTicks(record.vanillaBreakTicks, record.benchmarkCase.damageMultiplier, delta)
            : record.vanillaBreakTicks;
        return true;
    }

    private static void activateModule(NukerPlus module) {
        if (module != null && !module.isActive()) module.toggle();
    }

    private static void deactivateModule(NukerPlus module) {
        if (module != null && module.isActive()) module.toggle();
        if (module != null) module.debugResetDamageHarnessState();
    }

    private static NukerPlus requireNukerPlus(MinecraftClient client) {
        NukerPlus module = Modules.get().get(NukerPlus.class);
        if (module == null) {
            fail(client, "NukerPlus module became unavailable during runtime validation.");
            return null;
        }
        return module;
    }

    private static int countBrokenTargets(MinecraftClient client, List<BlockPos> targets) {
        if (client.world == null) return 0;
        int broken = 0;
        for (BlockPos pos : targets) {
            if (client.world.getBlockState(pos).isAir()) broken++;
        }
        return broken;
    }

    private static boolean isScenarioReady(MinecraftClient client, Block expectedBlock, List<BlockPos> targets) {
        if (stageTicks < CLIENT_SYNC_TICKS) return false;
        if (client.world == null || client.player == null || expectedBlock == null) return false;
        if ("net.minecraft.client.gui.screen.world.LevelLoadingScreen".equals(safeClassName(client.currentScreen))) return false;

        for (BlockPos pos : targets) {
            BlockState state = client.world.getBlockState(pos);
            if (!state.isOf(expectedBlock)) return false;
        }

        return true;
    }

    private static boolean awaitStableScenario(MinecraftClient client, BenchmarkCase benchmarkCase, List<BlockPos> targets, String failureDetail) {
        if (isScenarioReady(client, benchmarkCase.block, targets)) {
            scenarioReadyStreak++;
            return scenarioReadyStreak >= SCENARIO_STABLE_TICKS;
        }

        scenarioReadyStreak = 0;

        if (stageTicks > 0 && stageTicks % SCENARIO_REFRESH_INTERVAL_TICKS == 0) {
            if (!prepareScenario(client, benchmarkCase, targets)) return false;
            note("TRACE scenario-refresh case=" + benchmarkCase.id() + " tick=" + stageTicks);
        }

        if (stageTicks > SCENARIO_READY_TIMEOUT_TICKS) {
            fail(client, failureDetail + " screen=" + safeClassName(client.currentScreen) + " targetState=" + summarizeTargetStates(client, targets));
        }

        return false;
    }

    private static String summarizeTargetStates(MinecraftClient client, List<BlockPos> targets) {
        if (client == null || client.world == null || targets == null || targets.isEmpty()) return "world-unavailable";

        StringBuilder out = new StringBuilder();
        for (int i = 0; i < targets.size(); i++) {
            if (i > 0) out.append("; ");
            BlockPos pos = targets.get(i);
            out.append(pos).append('=').append(client.world.getBlockState(pos).getBlock().getName().getString());
        }
        return out.toString();
    }

    private static void finish(MinecraftClient client, boolean cleanupPass, String failureDetail) {
        smokeAssertions.addAll(buildCoreSmokeAssertions());
        writeReports(failureDetail == null && cleanupPass);

        boolean success = failureDetail == null
            && cleanupPass
            && benchmarkRecords.size() == benchmarkCases.size()
            && smokeAssertions.stream().allMatch(SmokeAssertion::passed);

        note("RESULT " + (success ? "PASS" : "FAIL") + " records=" + benchmarkRecords.size() + " smoke=" + smokeAssertions.size());
        note("SUMMARY nukerplus-damage-runtime finished=" + Instant.now());

        completed = true;
        stage = success ? Stage.FINISHED : Stage.FAILED;
        try {
            client.scheduleStop();
        } catch (Throwable t) {
            LOG.warn("Failed to stop NukerPlus damage runtime client.", t);
        }
    }

    private static void fail(MinecraftClient client, String detail) {
        runtimeNotes.add("FAIL " + detail);
        writeReports(false);
        completed = true;
        stage = Stage.FAILED;
        try {
            client.scheduleStop();
        } catch (Throwable t) {
            LOG.warn("Failed to stop NukerPlus damage runtime client after failure.", t);
        }
    }

    private static void writeReports(boolean success) {
        try {
            Files.createDirectories(outputDir);
            Files.writeString(outputDir.resolve("nukerplus-damage-time-mechanics.md"), buildMechanicsReport(), StandardCharsets.UTF_8);
            Files.writeString(outputDir.resolve("nukerplus-damage-time-benchmark.md"), buildBenchmarkReport(), StandardCharsets.UTF_8);
            Files.writeString(outputDir.resolve("nukerplus-damage-time-smoke.md"), buildSmokeReport(success), StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOG.warn("Failed to write NukerPlus runtime reports into {}", outputDir, e);
        }
    }

    private static String buildMechanicsReport() {
        StringBuilder out = new StringBuilder();
        out.append("# NukerPlus Damage-Time Mechanics Validation\n\n");
        out.append("Confirmed Mio-style formula: `targetBreakTicks = ceil((1 - damage) / delta)`, clamped to `[1, vanillaBreakTicks]`, where `vanillaBreakTicks = ceil(1 / delta)`. Runtime also seeds the client break progress to the configured `damage` value before forcing finish.\n\n");

        benchmarkRecords.stream()
            .filter(record -> record.benchmarkCase.mode == NukerPlus.MiningAccelerationMode.SpeedMineDamage)
            .collect(java.util.stream.Collectors.groupingBy(record -> record.benchmarkCase.blockName))
            .forEach((blockName, records) -> {
                records.sort(Comparator.comparingDouble(record -> -record.benchmarkCase.damageMultiplier));
                out.append("## ").append(blockName).append("\n\n");
                out.append("| Block | Tool | Delta | Vanilla Break Ticks | Damage | Target Break Ticks |\n");
                out.append("| --- | --- | ---: | ---: | ---: | ---: |\n");
                for (BenchmarkRecord record : records) {
                    out.append("| ")
                        .append(blockName)
                        .append(" | iron_pickaxe | ")
                        .append(formatFloat(record.delta))
                        .append(" | ")
                        .append(record.vanillaBreakTicks)
                        .append(" | ")
                        .append(formatDamage(record.benchmarkCase.damageMultiplier))
                        .append(" | ")
                        .append(record.targetBreakTicks)
                        .append(" |\n");
                }
                out.append('\n');
            });

        out.append("RESULT ").append(benchmarkRecords.isEmpty() ? "FAIL" : "PASS").append('\n');
        return out.toString();
    }

    private static String buildBenchmarkReport() {
        StringBuilder out = new StringBuilder();
        out.append("# NukerPlus Damage-Time Benchmark\n\n");
        out.append("| Block | Mode | Damage | Delta | Vanilla Ticks | Target Ticks | Avg Ticks To Break | First Break Tick | Blocks Broken / ")
            .append(WINDOW_TICKS)
            .append("t | Retry / Rebreak | Forced Finish |\n");
        out.append("| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |\n");

        benchmarkRecords.stream()
            .sorted(Comparator.comparing((BenchmarkRecord record) -> record.benchmarkCase.blockName)
                .thenComparing(record -> record.benchmarkCase.mode.name())
                .thenComparingDouble(record -> -record.benchmarkCase.damageMultiplier))
            .forEach(record -> out.append("| ")
                .append(record.benchmarkCase.blockName)
                .append(" | ")
                .append(record.modeLabel())
                .append(" | ")
                .append(formatDamage(record.benchmarkCase.damageMultiplier))
                .append(" | ")
                .append(formatFloat(record.delta))
                .append(" | ")
                .append(record.vanillaBreakTicks)
                .append(" | ")
                .append(record.targetBreakTicks)
                .append(" | ")
                .append(formatFloat(record.averageTicksToBreak()))
                .append(" | ")
                .append(record.windowFirstBreakTick)
                .append(" | ")
                .append(record.windowBlocksBroken)
                .append(" | ")
                .append(record.windowRetryCount)
                .append(" | ")
                .append(record.windowForcedFinishCount)
                .append(" |\n"));

        out.append("\nRESULT ").append(benchmarkRecords.size() == benchmarkCases.size() ? "PASS" : "FAIL").append('\n');
        return out.toString();
    }

    private static String buildSmokeReport(boolean success) {
        StringBuilder out = new StringBuilder();
        out.append("# NukerPlus Damage-Time Runtime Smoke\n\n");

        for (SmokeAssertion assertion : smokeAssertions) {
            out.append("- ")
                .append(assertion.id)
                .append(": ")
                .append(assertion.passed ? "PASS" : "FAIL")
                .append(" - ")
                .append(assertion.detail)
                .append('\n');
        }

        if (!runtimeNotes.isEmpty()) {
            out.append("\n## Runtime Notes\n\n");
            for (String runtimeNote : runtimeNotes) {
                out.append("- ").append(runtimeNote).append('\n');
            }
        }

        out.append("\nRESULT ").append(success && smokeAssertions.stream().allMatch(SmokeAssertion::passed) ? "PASS" : "FAIL").append('\n');
        return out.toString();
    }

    private static List<SmokeAssertion> buildCoreSmokeAssertions() {
        List<SmokeAssertion> assertions = new ArrayList<>();
        assertions.add(new SmokeAssertion(
            "SMOKE-01 OFF REGRESSION",
            benchmarkRecords.stream().anyMatch(record -> record.benchmarkCase.mode == NukerPlus.MiningAccelerationMode.Off && record.averageTicksToBreak() > 0.0),
            summarizeMode("stone", NukerPlus.MiningAccelerationMode.Off)
        ));
        assertions.add(new SmokeAssertion(
            "SMOKE-02 HASTE REMOVED",
            Arrays.stream(NukerPlus.MiningAccelerationMode.values()).noneMatch(mode -> "Haste".equals(mode.name()))
                && Arrays.stream(NukerPlus.MiningAccelerationMode.values()).anyMatch(mode -> mode == NukerPlus.MiningAccelerationMode.SpeedMineDamage),
            "modes=" + Arrays.toString(NukerPlus.MiningAccelerationMode.values())
        ));

        for (double damage : List.of(1.00, 0.90, 0.80, 0.70, 0.60)) {
            BenchmarkRecord record = findRecord("stone", NukerPlus.MiningAccelerationMode.SpeedMineDamage, damage);
            boolean pass = record != null && record.averageTicksToBreak() > 0.0;
            assertions.add(new SmokeAssertion(
                "SMOKE-" + switch ((int) Math.round((1.0 - damage) * 10.0)) {
                    case 0 -> "03 DAMAGE 1.00";
                    case 1 -> "04 DAMAGE 0.90";
                    case 2 -> "05 DAMAGE 0.80";
                    case 3 -> "06 DAMAGE 0.70";
                    default -> "07 DAMAGE 0.60";
                },
                pass,
                record == null ? "missing benchmark record" : "avgTicks=" + formatFloat(record.averageTicksToBreak()) + " targetTicks=" + record.targetBreakTicks
            ));
        }

        assertions.add(new SmokeAssertion(
            "SMOKE-08 INSTA COMPAT",
            instaCompatObserved && instaCompatBreakTick > 0 && instaCompatBreakTick <= 5,
            "observed=" + instaCompatObserved + " breakTick=" + instaCompatBreakTick
        ));

        BenchmarkRecord offStone = findRecord("stone", NukerPlus.MiningAccelerationMode.Off, 1.0);
        BenchmarkRecord instaStone = findRecord("stone", NukerPlus.MiningAccelerationMode.Insta, 1.0);
        BenchmarkRecord damageStone = findRecord("stone", NukerPlus.MiningAccelerationMode.SpeedMineDamage, 0.60);
        boolean nonInstaPass = offStone != null && damageStone != null && instaStone != null
            && damageStone.averageTicksToBreak() < offStone.averageTicksToBreak()
            && damageStone.averageTicksToBreak() <= instaStone.averageTicksToBreak();
        assertions.add(new SmokeAssertion(
            "SMOKE-09 NON-INSTA BLOCKS",
            nonInstaPass,
            "off=" + summarizeRecord(offStone) + " insta=" + summarizeRecord(instaStone) + " damage60=" + summarizeRecord(damageStone)
        ));

        return assertions;
    }

    private static BenchmarkRecord findRecord(String blockName, NukerPlus.MiningAccelerationMode mode, double damage) {
        return benchmarkRecords.stream()
            .filter(record -> record.benchmarkCase.blockName.equals(blockName))
            .filter(record -> record.benchmarkCase.mode == mode)
            .filter(record -> Math.abs(record.benchmarkCase.damageMultiplier - damage) < 1.0E-6)
            .findFirst()
            .orElse(null);
    }

    private static String summarizeMode(String blockName, NukerPlus.MiningAccelerationMode mode) {
        return benchmarkRecords.stream()
            .filter(record -> record.benchmarkCase.blockName.equals(blockName) && record.benchmarkCase.mode == mode)
            .findFirst()
            .map(NukerPlusDamageTimeRuntimeValidation::summarizeRecord)
            .orElse("missing");
    }

    private static String summarizeRecord(BenchmarkRecord record) {
        if (record == null) return "missing";
        return "avg=" + formatFloat(record.averageTicksToBreak())
            + " first=" + record.windowFirstBreakTick
            + " broken=" + record.windowBlocksBroken
            + " retries=" + record.windowRetryCount
            + " forced=" + record.windowForcedFinishCount;
    }

    private static List<BlockPos> createWindowTargets() {
        List<BlockPos> targets = new ArrayList<>(WINDOW_BLOCK_COUNT);
        for (int i = 0; i < WINDOW_BLOCK_COUNT; i++) {
            targets.add(SINGLE_TARGET_POS.east(i));
        }
        return List.copyOf(targets);
    }

    private static List<BenchmarkCase> createBenchmarkCases() {
        List<BenchmarkCase> cases = new ArrayList<>();
        List<BlockSpec> blocks = List.of(
            new BlockSpec("stone", Blocks.STONE),
            new BlockSpec("cobbled_deepslate", Blocks.COBBLED_DEEPSLATE),
            new BlockSpec("dirt", Blocks.DIRT)
        );

        for (BlockSpec spec : blocks) {
            cases.add(new BenchmarkCase(spec.name, spec.name, spec.block, NukerPlus.MiningAccelerationMode.Off, 1.0));
            cases.add(new BenchmarkCase(spec.name + "-insta", spec.name, spec.block, NukerPlus.MiningAccelerationMode.Insta, 1.0));
            for (double damage : List.of(1.00, 0.90, 0.80, 0.70, 0.60)) {
                cases.add(new BenchmarkCase(spec.name + "-damage-" + formatDamage(damage), spec.name, spec.block, NukerPlus.MiningAccelerationMode.SpeedMineDamage, damage));
            }
        }

        return List.copyOf(cases);
    }

    private static Path resolveOutputDir() {
        String configured = System.getProperty(OUTPUT_DIR_PROPERTY, "").trim();
        if (!configured.isBlank()) return Path.of(configured).toAbsolutePath().normalize();
        return Path.of("codex log").toAbsolutePath().normalize();
    }

    private static void prepareOutputDir(Path directory) {
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            LOG.warn("Failed to prepare NukerPlus runtime output directory {}", directory, e);
        }
    }

    private static void note(String line) {
        if (line == null || line.isBlank()) return;
        runtimeNotes.add(line);
        LOG.info(line);
    }

    private static void disablePauseOnLostFocus(MinecraftClient client) {
        if (client == null || client.options == null) return;
        try {
            java.lang.reflect.Field field = client.options.getClass().getDeclaredField("pauseOnLostFocus");
            field.setAccessible(true);
            field.setBoolean(client.options, false);
            note("CONFIG pauseOnLostFocus=false");
        } catch (Throwable t) {
            note("CONFIG pauseOnLostFocus-unavailable=" + t.getClass().getSimpleName());
        }
    }

    private static void advance(Stage next) {
        stage = next;
        stageTicks = 0;
    }

    private static String safeClassName(Object value) {
        return value == null ? "null" : value.getClass().getName();
    }

    private static String formatFloat(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private static String formatDamage(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private enum Stage {
        STARTUP_DELAY,
        WAIT_FOR_WORLD,
        PREPARE_SINGLE_TRIAL,
        RUN_SINGLE_TRIAL,
        PREPARE_WINDOW,
        RUN_WINDOW,
        PREPARE_INSTA_COMPAT,
        RUN_INSTA_COMPAT,
        PREPARE_TARGET_SWITCH,
        RUN_TARGET_SWITCH,
        PREPARE_DAMAGE_BURST,
        RUN_DAMAGE_BURST,
        PREPARE_CLEANUP,
        RUN_CLEANUP,
        FINISHED,
        FAILED
    }

    private record BlockSpec(String name, Block block) {
    }

    private record BenchmarkCase(String id, String blockName, Block block, NukerPlus.MiningAccelerationMode mode, double damageMultiplier, int maxBlocksPerTick) {
        private BenchmarkCase(String id, String blockName, Block block, NukerPlus.MiningAccelerationMode mode, double damageMultiplier) {
            this(id, blockName, block, mode, damageMultiplier, 1);
        }
    }

    private record SmokeAssertion(String id, boolean passed, String detail) {
    }

    private static final class BenchmarkRecord {
        private final BenchmarkCase benchmarkCase;
        private final List<Integer> singleTrialTicks = new ArrayList<>();
        private final List<Long> singleTrialForcedFinishCounts = new ArrayList<>();
        private final List<Long> singleTrialRetryCounts = new ArrayList<>();

        private float delta;
        private int vanillaBreakTicks;
        private int targetBreakTicks;
        private int windowBlocksBroken;
        private int windowFirstBreakTick = -1;
        private long windowForcedFinishCount;
        private long windowRetryCount;

        private BenchmarkRecord(BenchmarkCase benchmarkCase) {
            this.benchmarkCase = benchmarkCase;
        }

        private double averageTicksToBreak() {
            if (singleTrialTicks.isEmpty()) return 0.0;
            return singleTrialTicks.stream().mapToInt(Integer::intValue).average().orElse(0.0);
        }

        private String modeLabel() {
            return switch (benchmarkCase.mode) {
                case Off -> "Off";
                case Insta -> "Insta";
                case SpeedMineDamage -> "SpeedMineDamage";
            };
        }
    }
}
