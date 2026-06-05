package com.devils.addon.util.smoke;

import com.devils.addon.modules.AutoWasp;
import com.devils.addon.util.runtime.StrictRuntimeLogger;
import java.time.Instant;
import java.util.UUID;
import meteordevelopment.meteorclient.systems.friends.Friend;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.world.CreateWorldScreen;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.Vec3d;

public final class AutoWaspRuntimeValidation {
    private static final String ENABLE_PROPERTY = "devils.autowasp.runtime";
    private static final int START_DELAY_TICKS = 60;
    private static final int WORLD_LOAD_TIMEOUT_TICKS = 500;
    private static final int ASSERT_TIMEOUT_TICKS = 80;
    private static final int FRIEND_ENTITY_ID = 46_101;
    private static final int ENEMY_ENTITY_ID = 46_102;
    private static final UUID FRIEND_UUID = UUID.fromString("00000000-0000-0000-0000-00000000a001");
    private static final UUID ENEMY_UUID = UUID.fromString("00000000-0000-0000-0000-00000000a002");
    private static final String FRIEND_NAME = "RuntimeFriend";
    private static final String ENEMY_NAME = "RuntimeEnemy";
    private static final Vec3d PLAYER_POS = new Vec3d(0.5, 64.0, 0.5);
    private static final Vec3d FRIEND_POS = new Vec3d(3.5, 64.0, 0.5);
    private static final Vec3d ENEMY_POS = new Vec3d(5.5, 64.0, 0.5);

    private static boolean installed;
    private static boolean completed;
    private static boolean worldRequested;
    private static boolean worldCreationSubmitted;
    private static Stage stage = Stage.STARTUP_DELAY;
    private static int stageTicks;

    private AutoWaspRuntimeValidation() {
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
            StrictRuntimeLogger.logHarness("AUTOWASP", "SUMMARY autowasp-runtime started=" + Instant.now());
        });

        ClientTickEvents.END_CLIENT_TICK.register(AutoWaspRuntimeValidation::tick);
    }

    private static void tick(MinecraftClient client) {
        if (!installed || completed) return;
        stageTicks++;

        switch (stage) {
            case STARTUP_DELAY -> tickStartupDelay(client);
            case WAIT_FOR_WORLD -> tickWaitForWorld(client);
            case PREPARE_SCENARIO -> tickPrepareScenario(client);
            case ASSERT_ENEMY_TARGET -> tickAssertTarget(client, false, ENEMY_NAME);
            case ASSERT_FRIEND_TARGET -> tickAssertTarget(client, true, FRIEND_NAME);
            case FINISHED, FAILED -> {
            }
        }
    }

    private static void tickStartupDelay(MinecraftClient client) {
        if (stageTicks < START_DELAY_TICKS) return;

        if (!worldRequested) {
            worldRequested = true;
            StrictRuntimeLogger.logHarness("AUTOWASP", "STAGE opening-test-world");
            client.execute(() -> CreateWorldScreen.showTestWorld(client, () -> {
            }));
        }

        advance(Stage.WAIT_FOR_WORLD);
    }

    private static void tickWaitForWorld(MinecraftClient client) {
        if (client.world != null && client.player != null && client.getServer() != null) {
            client.setScreen(null);
            StrictRuntimeLogger.logHarness("AUTOWASP", "STAGE world-loaded");
            advance(Stage.PREPARE_SCENARIO);
            return;
        }

        if (!worldCreationSubmitted && SmokeCreateWorldHelper.submitCreateWorldIfPresent(client)) {
            worldCreationSubmitted = true;
            StrictRuntimeLogger.logHarness("AUTOWASP", "STAGE create-world-submitted");
        }

        if (stageTicks > WORLD_LOAD_TIMEOUT_TICKS) {
            fail(client, "Timed out while waiting for AutoWasp runtime world.");
        }
    }

    private static void tickPrepareScenario(MinecraftClient client) {
        MinecraftServer server = client.getServer();
        AutoWasp module = Modules.get().get(AutoWasp.class);
        if (server == null || client.player == null || module == null) {
            fail(client, "AutoWasp runtime prerequisites were unavailable.");
            return;
        }

        server.executeSync(() -> prepareScenario(server, client));
        prepareFriends();
        RuntimeClientPlayerHelper.ensureFakePlayer(client, FRIEND_ENTITY_ID, FRIEND_UUID, FRIEND_NAME, FRIEND_POS);
        RuntimeClientPlayerHelper.ensureFakePlayer(client, ENEMY_ENTITY_ID, ENEMY_UUID, ENEMY_NAME, ENEMY_POS);
        module.debugConfigureForRuntime(true, false, false);
        if (module.isActive()) module.toggle();
        module.toggle();
        StrictRuntimeLogger.logHarness("AUTOWASP", "STAGE scenario-ready friendFilter=true onlyFriends=false");
        advance(Stage.ASSERT_ENEMY_TARGET);
    }

    private static void tickAssertTarget(MinecraftClient client, boolean onlyFriends, String expectedTarget) {
        AutoWasp module = Modules.get().get(AutoWasp.class);
        if (module == null) {
            fail(client, "AutoWasp module disappeared during runtime validation.");
            return;
        }

        if (stageTicks == 1) {
            module.debugConfigureForRuntime(true, onlyFriends, false);
            if (module.isActive()) module.toggle();
            module.toggle();
            StrictRuntimeLogger.logHarness("AUTOWASP", "TRACE target-assert expected=" + expectedTarget + " onlyFriends=" + onlyFriends);
        }

        String actualTarget = module.debugTargetName();
        boolean actualFriend = module.debugTargetIsFriend();
        if (!actualTarget.isBlank()) {
            StrictRuntimeLogger.logAutoWasp(
                "runtime-assert",
                "expected=" + expectedTarget + " actual=" + actualTarget + " friend=" + actualFriend + " onlyFriends=" + onlyFriends
            );
        }

        if (expectedTarget.equals(actualTarget)) {
            module.toggle();
            if (onlyFriends) {
                StrictRuntimeLogger.logHarness("AUTOWASP", "RESULT PASS enemyFilter=true friendOnly=true");
                finish(client, true);
            } else {
                advance(Stage.ASSERT_FRIEND_TARGET);
            }
            return;
        }

        if (stageTicks > ASSERT_TIMEOUT_TICKS) {
            fail(client, "Expected target " + expectedTarget + " but got " + (actualTarget.isBlank() ? "<none>" : actualTarget) + '.');
        }
    }

    private static void prepareScenario(MinecraftServer server, MinecraftClient client) {
        ServerPlayerEntity player = server.getPlayerManager().getPlayer(client.player.getUuid());
        if (player == null) return;
        player.requestTeleport(PLAYER_POS.x, PLAYER_POS.y, PLAYER_POS.z);
        player.setVelocity(0.0, 0.0, 0.0);
        player.equipStack(EquipmentSlot.CHEST, new ItemStack(Items.ELYTRA));
        player.playerScreenHandler.sendContentUpdates();
    }

    private static void prepareFriends() {
        Friends friends = Friends.get();
        Friend existingFriend = friends.get(FRIEND_NAME);
        if (existingFriend == null) friends.add(new Friend(FRIEND_NAME));
        Friend existingEnemy = friends.get(ENEMY_NAME);
        if (existingEnemy != null) friends.remove(existingEnemy);
    }

    private static void cleanup(MinecraftClient client) {
        Friends friends = Friends.get();
        Friend friend = friends.get(FRIEND_NAME);
        if (friend != null) friends.remove(friend);
        RuntimeClientPlayerHelper.removeFakePlayer(client, FRIEND_ENTITY_ID);
        RuntimeClientPlayerHelper.removeFakePlayer(client, ENEMY_ENTITY_ID);
    }

    private static void advance(Stage next) {
        stage = next;
        stageTicks = 0;
    }

    private static void fail(MinecraftClient client, String detail) {
        StrictRuntimeLogger.logHarness("AUTOWASP", "RESULT FAIL " + detail);
        finish(client, false);
    }

    private static void finish(MinecraftClient client, boolean success) {
        cleanup(client);
        completed = true;
        stage = success ? Stage.FINISHED : Stage.FAILED;
        StrictRuntimeLogger.logHarness("AUTOWASP", "SUMMARY autowasp-runtime finished=" + Instant.now());
        try {
            client.scheduleStop();
        } catch (Throwable ignored) {
        }
    }

    private enum Stage {
        STARTUP_DELAY,
        WAIT_FOR_WORLD,
        PREPARE_SCENARIO,
        ASSERT_ENEMY_TARGET,
        ASSERT_FRIEND_TARGET,
        FINISHED,
        FAILED
    }
}
