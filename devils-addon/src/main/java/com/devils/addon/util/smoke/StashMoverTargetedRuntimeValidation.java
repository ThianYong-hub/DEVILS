package com.devils.addon.util.smoke;

import com.devils.addon.modules.stashmover.StashMover;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.block.Blocks;
import net.minecraft.block.TrapdoorBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.world.CreateWorldScreen;
import net.minecraft.entity.projectile.thrown.EnderPearlEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class StashMoverTargetedRuntimeValidation {
    private static final Logger LOG = LoggerFactory.getLogger("Devils/StashMoverTargetedRuntime");
    private static final String ENABLE_PROPERTY = "devils.stashmover.targeted.runtime";
    private static final String OUTPUT_PATH_PROPERTY = "devils.stashmover.targeted.runtime.path";
    private static final int START_DELAY_TICKS = 60;
    private static final int WORLD_LOAD_TIMEOUT_TICKS = 500;
    private static final int OFF_ANCHOR_OBSERVE_TICKS = 40;
    private static final int POST_ANCHOR_TIMEOUT_TICKS = 220;

    private static final BlockPos WATER_POS = new BlockPos(0, 64, 0);
    private static final BlockPos PEARL_CHEST_POS = new BlockPos(1, 64, 0);
    private static final BlockPos LOOT_CHEST_POS = new BlockPos(8, 64, 0);
    private static final BlockPos WALL_POS = new BlockPos(0, 65, 1);
    private static final Vec3d START_POS = new Vec3d(0.5, 64.0, 2.6);
    private static final Vec3d ANCHOR_POS = new Vec3d(0.5, 64.0, 0.5);
    private static final Vec3d CHAMBER_POS = new Vec3d(0.5, 65.0, 0.5);

    private static boolean installed;
    private static boolean completed;
    private static boolean worldRequested;
    private static boolean worldCreationSubmitted;
    private static Stage stage = Stage.STARTUP_DELAY;
    private static int stageTicks;
    private static Path outputPath;

    private static boolean sawPearlBeforeAnchor;
    private static boolean sawThrowAfterAnchor;
    private static boolean sawPutBackPhase;
    private static boolean sawWalkingPhase;
    private static boolean sawWaitForPearlAfterThrow;
    private static boolean sawGoalToLootChest;
    private static boolean sawPearlRemovalAfterThrow;
    private static int previousPearlCount;

    private StashMoverTargetedRuntimeValidation() {
    }

    public static void install() {
        if (installed || !Boolean.getBoolean(ENABLE_PROPERTY)) return;
        installed = true;
        outputPath = resolveOutputPath();

        ClientLifecycleEvents.CLIENT_STARTED.register(client -> {
            completed = false;
            worldRequested = false;
            worldCreationSubmitted = false;
            stage = Stage.STARTUP_DELAY;
            stageTicks = 0;
            previousPearlCount = 0;
            sawPearlBeforeAnchor = false;
            sawThrowAfterAnchor = false;
            sawPutBackPhase = false;
            sawWalkingPhase = false;
            sawWaitForPearlAfterThrow = false;
            sawGoalToLootChest = false;
            sawPearlRemovalAfterThrow = false;
            resetLog(outputPath);
            appendLine("SUMMARY stashmover-targeted-runtime started=" + Instant.now());
        });

        ClientTickEvents.END_CLIENT_TICK.register(StashMoverTargetedRuntimeValidation::tick);
    }

    private static void tick(MinecraftClient client) {
        if (!installed || completed) return;

        stageTicks++;
        switch (stage) {
            case STARTUP_DELAY -> tickStartupDelay(client);
            case WAIT_FOR_WORLD -> tickWaitForWorld(client);
            case SETUP_SCENARIO -> tickSetupScenario(client);
            case PRIME_MODULE -> tickPrimeModule(client);
            case OBSERVE_OFF_ANCHOR -> tickObserveOffAnchor(client);
            case TELEPORT_TO_ANCHOR -> tickTeleportToAnchor(client);
            case OBSERVE_POST_ANCHOR -> tickObservePostAnchor(client);
            case FINISHED, FAILED -> {
            }
        }
    }

    private static void tickStartupDelay(MinecraftClient client) {
        if (stageTicks < START_DELAY_TICKS) return;

        if (!worldRequested) {
            worldRequested = true;
            appendLine("STAGE opening-test-world");
            client.execute(() -> CreateWorldScreen.showTestWorld(client, () -> {
            }));
        }

        advance(Stage.WAIT_FOR_WORLD);
    }

    private static void tickWaitForWorld(MinecraftClient client) {
        if (client.world != null && client.player != null && client.getServer() != null) {
            appendLine("STAGE world-loaded screen=" + safeClassName(client.currentScreen));
            advance(Stage.SETUP_SCENARIO);
            return;
        }

        if (!worldCreationSubmitted && SmokeCreateWorldHelper.submitCreateWorldIfPresent(client)) {
            worldCreationSubmitted = true;
            appendLine("STAGE create-world-submitted");
        }

        if (stageTicks > WORLD_LOAD_TIMEOUT_TICKS) {
            fail(
                client,
                "Timed out while waiting for targeted runtime world load."
                    + " screen=" + safeClassName(client.currentScreen)
                    + " createSubmitted=" + worldCreationSubmitted
            );
        }
    }

    private static void tickSetupScenario(MinecraftClient client) {
        MinecraftServer server = client.getServer();
        if (server == null || client.player == null) return;

        server.executeSync(() -> setupScenario(server, client));
        appendLine("STAGE scenario-setup-complete");
        advance(Stage.PRIME_MODULE);
    }

    private static void tickPrimeModule(MinecraftClient client) {
        StashMover module = Modules.get().get(StashMover.class);
        if (module == null || client.player == null) {
            fail(client, "StashMover module not available during targeted runtime validation.");
            return;
        }

        module.debugConfigureForHarness(PEARL_CHEST_POS, LOOT_CHEST_POS, WATER_POS, CHAMBER_POS, null);
        module.debugEnableLoggingForHarness(true);

        if (module.isActive()) module.toggle();
        module.toggle();
        module.debugForceMoverPhaseForHarness("THROWING_PEARL");

        appendLine("STAGE module-primed " + module.runtimeStatusSummary());
        previousPearlCount = countOwnPearls(client);
        advance(Stage.OBSERVE_OFF_ANCHOR);
    }

    private static void tickObserveOffAnchor(MinecraftClient client) {
        StashMover module = Modules.get().get(StashMover.class);
        if (module == null) {
            fail(client, "StashMover module disappeared during off-anchor observation.");
            return;
        }

        int pearlCount = countOwnPearls(client);
        if (pearlCount > 0 || module.ownPearlAwaiting() || module.ownPearlTracked()) {
            sawPearlBeforeAnchor = true;
        }

        if (stageTicks == 1 || stageTicks % 10 == 0) {
            appendLine("TRACE off-anchor tick=" + stageTicks + " " + module.runtimeStatusSummary());
        }

        if (stageTicks >= OFF_ANCHOR_OBSERVE_TICKS) {
            advance(Stage.TELEPORT_TO_ANCHOR);
        }
    }

    private static void tickTeleportToAnchor(MinecraftClient client) {
        MinecraftServer server = client.getServer();
        if (server == null || client.player == null) {
            fail(client, "Integrated server was unavailable while teleporting to the water anchor.");
            return;
        }

        server.executeSync(() -> {
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(client.player.getUuid());
            if (player == null) return;
            player.requestTeleport(ANCHOR_POS.x, ANCHOR_POS.y, ANCHOR_POS.z);
            player.setVelocity(0.0, 0.0, 0.0);
        });

        appendLine("STAGE teleported-to-anchor pos=" + formatVec(ANCHOR_POS));
        advance(Stage.OBSERVE_POST_ANCHOR);
    }

    private static void tickObservePostAnchor(MinecraftClient client) {
        StashMover module = Modules.get().get(StashMover.class);
        if (module == null) {
            fail(client, "StashMover module disappeared during post-anchor observation.");
            return;
        }

        int pearlCount = countOwnPearls(client);
        if (pearlCount > 0 || module.ownPearlAwaiting() || module.ownPearlTracked()) sawThrowAfterAnchor = true;
        if (previousPearlCount > 0 && pearlCount == 0) sawPearlRemovalAfterThrow = true;
        previousPearlCount = pearlCount;

        if ("PUT_BACK_PEARLS".equals(module.moverPhaseName())) sawPutBackPhase = true;
        if ("WALKING_TO_CHEST".equals(module.moverPhaseName())) sawWalkingPhase = true;
        if ("LOOT_CHEST".equals(module.activeGoalName())) sawGoalToLootChest = true;
        if (sawThrowAfterAnchor && "WAIT_FOR_PEARL".equals(module.moverPhaseName())) sawWaitForPearlAfterThrow = true;

        if (stageTicks == 1 || stageTicks % 10 == 0) {
            appendLine("TRACE post-anchor tick=" + stageTicks + " pearls=" + pearlCount + " " + module.runtimeStatusSummary());
        }

        if (sawPearlBeforeAnchor) {
            fail(client, "Pearl was thrown before the player reached the throw anchor.");
            return;
        }

        if (sawThrowAfterAnchor && sawWalkingPhase && sawGoalToLootChest && !sawWaitForPearlAfterThrow) {
            appendLine("RESULT PASS offAnchorGuard=true throwObserved=true putBack=" + sawPutBackPhase + " walking=" + sawWalkingPhase
                + " pearlRemovalObserved=" + sawPearlRemovalAfterThrow + " lootGoal=" + sawGoalToLootChest);
            appendLine("SUMMARY stashmover-targeted-runtime finished=" + Instant.now());
            complete(client);
            return;
        }

        if (stageTicks > POST_ANCHOR_TIMEOUT_TICKS) {
            fail(
                client,
                "Timed out waiting for post-pearl continuation. throwObserved=" + sawThrowAfterAnchor
                    + " putBack=" + sawPutBackPhase
                    + " walking=" + sawWalkingPhase
                    + " lootGoal=" + sawGoalToLootChest
                    + " waitAfterThrow=" + sawWaitForPearlAfterThrow
                    + " pearlRemovalObserved=" + sawPearlRemovalAfterThrow
            );
        }
    }

    private static void setupScenario(MinecraftServer server, MinecraftClient client) {
        ServerWorld world = server.getOverworld();
        ServerPlayerEntity player = server.getPlayerManager().getPlayer(client.player.getUuid());
        if (player == null) return;

        for (int x = -2; x <= 10; x++) {
            for (int z = -1; z <= 4; z++) {
                world.setBlockState(new BlockPos(x, 63, z), Blocks.STONE.getDefaultState());
                world.setBlockState(new BlockPos(x, 64, z), Blocks.AIR.getDefaultState());
                world.setBlockState(new BlockPos(x, 65, z), Blocks.AIR.getDefaultState());
                world.setBlockState(new BlockPos(x, 66, z), Blocks.AIR.getDefaultState());
            }
        }

        world.setBlockState(WATER_POS, Blocks.WATER.getDefaultState());
        world.setBlockState(BlockPos.ofFloored(CHAMBER_POS), Blocks.OAK_TRAPDOOR.getDefaultState().with(TrapdoorBlock.OPEN, true));
        world.setBlockState(WALL_POS, Blocks.STONE.getDefaultState());
        world.setBlockState(PEARL_CHEST_POS, Blocks.CHEST.getDefaultState());
        world.setBlockState(LOOT_CHEST_POS, Blocks.CHEST.getDefaultState());

        BlockEntity pearlChestEntity = world.getBlockEntity(PEARL_CHEST_POS);
        if (pearlChestEntity instanceof ChestBlockEntity pearlChest) {
            pearlChest.clear();
            pearlChest.markDirty();
        }
        BlockEntity lootChestEntity = world.getBlockEntity(LOOT_CHEST_POS);
        if (lootChestEntity instanceof ChestBlockEntity lootChest) {
            lootChest.clear();
            lootChest.markDirty();
        }

        player.changeGameMode(net.minecraft.world.GameMode.SURVIVAL);
        player.getInventory().clear();
        player.getInventory().setStack(0, new ItemStack(Items.ENDER_PEARL, 2));
        player.getInventory().setStack(1, new ItemStack(Items.STONE, 8));
        player.getInventory().setSelectedSlot(0);
        player.requestTeleport(START_POS.x, START_POS.y, START_POS.z);
        player.setVelocity(0.0, 0.0, 0.0);
        player.playerScreenHandler.sendContentUpdates();
        player.closeHandledScreen();
    }

    private static int countOwnPearls(MinecraftClient client) {
        if (client.world == null || client.player == null) return 0;
        return client.world.getEntitiesByClass(
            EnderPearlEntity.class,
            client.player.getBoundingBox().expand(16.0),
            pearl -> pearl.getOwner() == client.player
        ).size();
    }

    private static void fail(MinecraftClient client, String detail) {
        appendLine("RESULT FAIL " + detail);
        appendLine("SUMMARY stashmover-targeted-runtime finished=" + Instant.now());
        completed = true;
        stage = Stage.FAILED;
        try {
            client.scheduleStop();
        } catch (Throwable t) {
            LOG.warn("Failed to stop targeted runtime client after failure.", t);
        }
    }

    private static void complete(MinecraftClient client) {
        completed = true;
        stage = Stage.FINISHED;
        try {
            client.scheduleStop();
        } catch (Throwable t) {
            LOG.warn("Failed to stop targeted runtime client after success.", t);
        }
    }

    private static void advance(Stage next) {
        stage = next;
        stageTicks = 0;
    }

    private static Path resolveOutputPath() {
        String configured = System.getProperty(OUTPUT_PATH_PROPERTY, "").trim();
        if (!configured.isBlank()) return Path.of(configured).toAbsolutePath().normalize();
        return Path.of("stashmover-targeted-runtime.log").toAbsolutePath().normalize();
    }

    private static void resetLog(Path path) {
        try {
            Path parent = path.getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.writeString(path, "", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            LOG.warn("Failed to reset StashMover targeted runtime log at {}", path, e);
        }
    }

    private static void appendLine(String line) {
        if (outputPath == null || line == null) return;
        try {
            Path parent = outputPath.getParent();
            if (parent != null) Files.createDirectories(parent);
            try (BufferedWriter writer = Files.newBufferedWriter(
                outputPath,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND
            )) {
                writer.write(line);
                writer.newLine();
            }
        } catch (IOException e) {
            LOG.warn("Failed to append StashMover targeted runtime line.", e);
        }
    }

    private static String safeClassName(Object value) {
        return value == null ? "null" : value.getClass().getName();
    }

    private static String formatVec(Vec3d vec) {
        return String.format(java.util.Locale.ROOT, "%.2f,%.2f,%.2f", vec.x, vec.y, vec.z);
    }

    private enum Stage {
        STARTUP_DELAY,
        WAIT_FOR_WORLD,
        SETUP_SCENARIO,
        PRIME_MODULE,
        OBSERVE_OFF_ANCHOR,
        TELEPORT_TO_ANCHOR,
        OBSERVE_POST_ANCHOR,
        FINISHED,
        FAILED
    }
}
