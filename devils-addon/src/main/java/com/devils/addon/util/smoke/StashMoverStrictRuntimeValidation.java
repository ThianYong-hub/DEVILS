package com.devils.addon.util.smoke;

import com.devils.addon.modules.stashmover.StashMover;
import com.devils.addon.util.runtime.StrictRuntimeLogger;
import java.time.Instant;
import java.util.UUID;
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

public final class StashMoverStrictRuntimeValidation {
    private static final String ENABLE_PROPERTY = "devils.stashmover.strict.runtime";
    private static final int START_DELAY_TICKS = 60;
    private static final int WORLD_LOAD_TIMEOUT_TICKS = 500;
    private static final int RUN_TIMEOUT_TICKS = 260;
    private static final int REQUIRED_RUNS = 5;
    private static final int PARTNER_ENTITY_ID = 46_201;
    private static final UUID PARTNER_UUID = UUID.fromString("00000000-0000-0000-0000-00000000b001");
    private static final String PARTNER_NAME = "RuntimePartner";
    private static final String ACK_MESSAGE = PARTNER_NAME + " whispers to you: abcd RECEIVED MESSAGE ef12";
    private static final int INITIAL_PEARL_COUNT = 16;

    private static final BlockPos WATER_POS = new BlockPos(0, 64, 0);
    private static final BlockPos PEARL_CHEST_POS = new BlockPos(1, 64, 0);
    private static final BlockPos LOOT_CHEST_POS = new BlockPos(3, 64, 0);
    private static final BlockPos LOOT_CHEST_OTHER_POS = LOOT_CHEST_POS.east();
    private static final BlockPos SOURCE_CHEST_POS = new BlockPos(-1, 64, 0);
    private static final Vec3d PLAYER_POS = new Vec3d(-0.5, 64.0, 0.5);
    private static final Vec3d PARTNER_POS = new Vec3d(0.5, 64.0, 2.0);
    private static final Vec3d CHAMBER_POS = new Vec3d(0.5, 65.0, 0.5);

    private static boolean installed;
    private static boolean completed;
    private static boolean worldRequested;
    private static boolean worldCreationSubmitted;
    private static Stage stage = Stage.STARTUP_DELAY;
    private static int stageTicks;
    private static int runIndex;
    private static boolean ackInjected;
    private static boolean sawSendPhase;
    private static boolean sawWaitPhase;
    private static boolean sawThrowPhase;
    private static boolean sawPutBackPhase;
    private static boolean sawWalkingPhase;
    private static String lastSnapshotIssue = "none";

    private StashMoverStrictRuntimeValidation() {
    }

    public static void install() {
        if (installed || !Boolean.getBoolean(ENABLE_PROPERTY)) return;
        installed = true;

        ClientLifecycleEvents.CLIENT_STARTED.register(client -> {
            completed = false;
            worldRequested = false;
            worldCreationSubmitted = false;
            stage = Stage.STARTUP_DELAY;
            stageTicks = 0;
            runIndex = 0;
            resetRunFlags();
            StrictRuntimeLogger.logHarness("STASHMOVER", "SUMMARY stashmover-runtime started=" + Instant.now());
        });

        ClientTickEvents.END_CLIENT_TICK.register(StashMoverStrictRuntimeValidation::tick);
    }

    private static void tick(MinecraftClient client) {
        if (!installed || completed) return;
        stageTicks++;

        switch (stage) {
            case STARTUP_DELAY -> tickStartupDelay(client);
            case WAIT_FOR_WORLD -> tickWaitForWorld(client);
            case PREPARE_RUN -> tickPrepareRun(client);
            case RUN_SCENARIO -> tickRunScenario(client);
            case FINISHED, FAILED -> {
            }
        }
    }

    private static void tickStartupDelay(MinecraftClient client) {
        if (stageTicks < START_DELAY_TICKS) return;

        if (!worldRequested) {
            worldRequested = true;
            StrictRuntimeLogger.logHarness("STASHMOVER", "STAGE opening-test-world");
            client.execute(() -> CreateWorldScreen.showTestWorld(client, () -> {
            }));
        }

        advance(Stage.WAIT_FOR_WORLD);
    }

    private static void tickWaitForWorld(MinecraftClient client) {
        if (client.world != null && client.player != null && client.getServer() != null) {
            client.setScreen(null);
            StrictRuntimeLogger.logHarness("STASHMOVER", "STAGE world-loaded");
            advance(Stage.PREPARE_RUN);
            return;
        }

        if (!worldCreationSubmitted && SmokeCreateWorldHelper.submitCreateWorldIfPresent(client)) {
            worldCreationSubmitted = true;
            StrictRuntimeLogger.logHarness("STASHMOVER", "STAGE create-world-submitted");
        }

        if (stageTicks > WORLD_LOAD_TIMEOUT_TICKS) {
            fail(client, "Timed out while waiting for StashMover runtime world.");
        }
    }

    private static void tickPrepareRun(MinecraftClient client) {
        MinecraftServer server = client.getServer();
        StashMover module = Modules.get().get(StashMover.class);
        if (server == null || client.player == null || module == null) {
            fail(client, "StashMover runtime prerequisites were unavailable.");
            return;
        }

        resetRunFlags();
        if (module.isActive()) module.toggle();
        server.executeSync(() -> prepareScenario(server, client));
        RuntimeClientPlayerHelper.ensureFakePlayer(client, PARTNER_ENTITY_ID, PARTNER_UUID, PARTNER_NAME, PARTNER_POS);
        module.debugEnableLoggingForHarness(true);
        module.debugConfigureForStrictRuntime(
            StashMover.Mode.MOVER,
            PARTNER_NAME,
            false,
            false,
            PEARL_CHEST_POS,
            LOOT_CHEST_POS,
            WATER_POS,
            CHAMBER_POS,
            null
        );
        module.toggle();
        StrictRuntimeLogger.logHarness("STASHMOVER", "STAGE run-start index=" + (runIndex + 1));
        advance(Stage.RUN_SCENARIO);
    }

    private static void tickRunScenario(MinecraftClient client) {
        StashMover module = Modules.get().get(StashMover.class);
        MinecraftServer server = client.getServer();
        if (module == null || server == null) {
            fail(client, "StashMover runtime dependencies disappeared during run " + (runIndex + 1) + '.');
            return;
        }

        String phase = module.moverPhaseName();
        if ("SEND_LOAD_PEARL_MSG".equals(phase)) sawSendPhase = true;
        if ("WAIT_FOR_PEARL".equals(phase)) sawWaitPhase = true;
        if ("THROWING_PEARL".equals(phase)) sawThrowPhase = true;
        if ("PUT_BACK_PEARLS".equals(phase)) sawPutBackPhase = true;
        if ("WALKING_TO_CHEST".equals(phase)) sawWalkingPhase = true;

        if (!ackInjected && sawSendPhase) {
            ackInjected = true;
            module.debugHandleReceivedChatMessageForHarness(ACK_MESSAGE);
            StrictRuntimeLogger.logHarness("STASHMOVER", "TRACE ack-injected run=" + (runIndex + 1) + " message=" + ACK_MESSAGE);
        }

        if (stageTicks == 1 || stageTicks % 20 == 0) {
            StrictRuntimeLogger.logHarness("STASHMOVER", "TRACE run=" + (runIndex + 1) + " tick=" + stageTicks + " " + module.runtimeStatusSummary());
        }

        RunSnapshot snapshot = captureSnapshot(server);
        if (snapshot == null) {
            if (stageTicks == 1 || stageTicks % 20 == 0) {
                StrictRuntimeLogger.logHarness(
                    "STASHMOVER",
                    "TRACE snapshot-unavailable run=" + (runIndex + 1) + " tick=" + stageTicks + " issue=" + lastSnapshotIssue
                );
            }
            if (stageTicks > RUN_TIMEOUT_TICKS) {
                fail(client, "Run " + (runIndex + 1) + " timed out before server snapshot stabilized. issue=" + lastSnapshotIssue);
            }
            return;
        }

        boolean pass = sawSendPhase
            && sawWaitPhase
            && sawThrowPhase
            && sawPutBackPhase
            && sawWalkingPhase
            && snapshot.lootChestItemCount > 0
            && snapshot.pearlChestCount >= INITIAL_PEARL_COUNT
            && "none".equals(module.lastPearlFailureReasonValue());

        if (pass) {
            if (module.isActive()) module.toggle();
            StrictRuntimeLogger.logHarness(
                "STASHMOVER",
                "RUN PASS index=" + (runIndex + 1)
                    + " lootItems=" + snapshot.lootChestItemCount
                    + " pearlChest=" + snapshot.pearlChestCount
                    + " playerPos=" + snapshot.playerPos
            );
            runIndex++;
            if (runIndex >= REQUIRED_RUNS) {
                StrictRuntimeLogger.logHarness("STASHMOVER", "RESULT PASS runs=" + REQUIRED_RUNS + " pearlTarget=" + CHAMBER_POS);
                finish(client, true);
            } else {
                advance(Stage.PREPARE_RUN);
            }
            return;
        }

        if (stageTicks > RUN_TIMEOUT_TICKS) {
            fail(
                client,
                "Run " + (runIndex + 1) + " timed out. "
                    + "send=" + sawSendPhase
                    + " wait=" + sawWaitPhase
                    + " throw=" + sawThrowPhase
                    + " putBack=" + sawPutBackPhase
                    + " walking=" + sawWalkingPhase
                    + " lootItems=" + snapshot.lootChestItemCount
                    + " pearlChest=" + snapshot.pearlChestCount
                    + " failure=" + module.lastPearlFailureReasonValue()
                    + " snapshotIssue=" + lastSnapshotIssue
            );
        }
    }

    private static void prepareScenario(MinecraftServer server, MinecraftClient client) {
        ServerWorld world = server.getOverworld();
        ServerPlayerEntity player = server.getPlayerManager().getPlayer(client.player.getUuid());
        if (player == null) return;

        for (int x = -3; x <= 6; x++) {
            for (int z = -2; z <= 3; z++) {
                world.setBlockState(new BlockPos(x, 63, z), Blocks.STONE.getDefaultState());
                world.setBlockState(new BlockPos(x, 64, z), Blocks.AIR.getDefaultState());
                world.setBlockState(new BlockPos(x, 65, z), Blocks.AIR.getDefaultState());
                world.setBlockState(new BlockPos(x, 66, z), Blocks.AIR.getDefaultState());
            }
        }

        world.setBlockState(WATER_POS, Blocks.WATER.getDefaultState());
        world.setBlockState(BlockPos.ofFloored(CHAMBER_POS), Blocks.OAK_TRAPDOOR.getDefaultState().with(TrapdoorBlock.OPEN, true));
        world.setBlockState(PEARL_CHEST_POS, Blocks.CHEST.getDefaultState());
        world.setBlockState(LOOT_CHEST_POS, Blocks.CHEST.getDefaultState());
        world.setBlockState(LOOT_CHEST_OTHER_POS, Blocks.CHEST.getDefaultState());
        world.setBlockState(SOURCE_CHEST_POS, Blocks.CHEST.getDefaultState());

        for (EnderPearlEntity pearl : world.getEntitiesByClass(EnderPearlEntity.class, player.getBoundingBox().expand(32.0), entity -> true)) {
            pearl.discard();
        }

        fillChest(world.getBlockEntity(PEARL_CHEST_POS), new ItemStack(Items.ENDER_PEARL, INITIAL_PEARL_COUNT));
        clearChest(world.getBlockEntity(LOOT_CHEST_POS));
        clearChest(world.getBlockEntity(LOOT_CHEST_OTHER_POS));
        fillChest(world.getBlockEntity(SOURCE_CHEST_POS), new ItemStack(Items.STONE, 64));

        player.changeGameMode(net.minecraft.world.GameMode.SURVIVAL);
        player.getInventory().clear();
        for (int slot = 0; slot < 36; slot++) {
            player.getInventory().setStack(slot, new ItemStack(Items.COBBLESTONE, 64));
        }
        player.getInventory().setSelectedSlot(8);
        player.requestTeleport(PLAYER_POS.x, PLAYER_POS.y, PLAYER_POS.z);
        player.setVelocity(0.0, 0.0, 0.0);
        player.playerScreenHandler.sendContentUpdates();
        player.closeHandledScreen();
    }

    private static void fillChest(BlockEntity blockEntity, ItemStack firstStack) {
        if (!(blockEntity instanceof ChestBlockEntity chest)) return;
        chest.clear();
        chest.setStack(0, firstStack.copy());
        chest.markDirty();
    }

    private static void clearChest(BlockEntity blockEntity) {
        if (!(blockEntity instanceof ChestBlockEntity chest)) return;
        chest.clear();
        chest.markDirty();
    }

    private static RunSnapshot captureSnapshot(MinecraftServer server) {
        try {
            return server.submit(() -> captureSnapshotOnServer(server)).join();
        } catch (Throwable t) {
            lastSnapshotIssue = "snapshot-submit-failed:" + t.getClass().getSimpleName();
            return null;
        }
    }

    private static RunSnapshot captureSnapshotOnServer(MinecraftServer server) {
        ServerWorld world = server.getOverworld();
        if (world == null) {
            lastSnapshotIssue = "overworld-null";
            return null;
        }

        ChestBlockEntity pearlChest = world.getBlockEntity(PEARL_CHEST_POS) instanceof ChestBlockEntity chest ? chest : null;
        ChestBlockEntity lootChest = world.getBlockEntity(LOOT_CHEST_POS) instanceof ChestBlockEntity chest ? chest : null;
        ServerPlayerEntity player = server.getPlayerManager().getPlayerList().isEmpty() ? null : server.getPlayerManager().getPlayerList().getFirst();
        if (pearlChest == null || lootChest == null || player == null) {
            lastSnapshotIssue = "pearlChest=" + (pearlChest != null)
                + " lootChest=" + (lootChest != null)
                + " player=" + (player != null);
            return null;
        }

        lastSnapshotIssue = "none";
        int pearlCount = chestItemCount(pearlChest);
        int lootItemCount = chestItemCount(lootChest);
        return new RunSnapshot(pearlCount, lootItemCount, formatVec(player.getEntityPos()));
    }

    private static int chestItemCount(ChestBlockEntity chest) {
        int count = 0;
        for (int slot = 0; slot < chest.size(); slot++) {
            if (!chest.getStack(slot).isEmpty()) count += chest.getStack(slot).getCount();
        }
        return count;
    }

    private static void resetRunFlags() {
        ackInjected = false;
        sawSendPhase = false;
        sawWaitPhase = false;
        sawThrowPhase = false;
        sawPutBackPhase = false;
        sawWalkingPhase = false;
        lastSnapshotIssue = "none";
    }

    private static void cleanup(MinecraftClient client) {
        RuntimeClientPlayerHelper.removeFakePlayer(client, PARTNER_ENTITY_ID);
        StashMover module = Modules.get().get(StashMover.class);
        if (module != null && module.isActive()) module.toggle();
    }

    private static void advance(Stage next) {
        stage = next;
        stageTicks = 0;
    }

    private static void fail(MinecraftClient client, String detail) {
        StrictRuntimeLogger.logHarness("STASHMOVER", "RESULT FAIL " + detail);
        finish(client, false);
    }

    private static void finish(MinecraftClient client, boolean success) {
        cleanup(client);
        completed = true;
        stage = success ? Stage.FINISHED : Stage.FAILED;
        StrictRuntimeLogger.logHarness("STASHMOVER", "SUMMARY stashmover-runtime finished=" + Instant.now());
        try {
            client.scheduleStop();
        } catch (Throwable ignored) {
        }
    }

    private static String formatVec(Vec3d pos) {
        return String.format(java.util.Locale.ROOT, "%.2f,%.2f,%.2f", pos.x, pos.y, pos.z);
    }

    private enum Stage {
        STARTUP_DELAY,
        WAIT_FOR_WORLD,
        PREPARE_RUN,
        RUN_SCENARIO,
        FINISHED,
        FAILED
    }

    private record RunSnapshot(int pearlChestCount, int lootChestItemCount, String playerPos) {
    }
}
