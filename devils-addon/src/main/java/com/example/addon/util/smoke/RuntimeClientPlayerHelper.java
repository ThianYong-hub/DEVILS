package com.example.addon.util.smoke;

import com.mojang.authlib.GameProfile;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.OtherClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.Vec3d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class RuntimeClientPlayerHelper {
    private static final Logger LOG = LoggerFactory.getLogger("Devils/RuntimeClientPlayerHelper");

    private RuntimeClientPlayerHelper() {
    }

    static OtherClientPlayerEntity ensureFakePlayer(MinecraftClient client, int entityId, UUID uuid, String name, Vec3d pos) {
        if (client == null || !(client.world instanceof ClientWorld world) || uuid == null || name == null || pos == null) return null;

        removeFakePlayer(client, entityId);
        OtherClientPlayerEntity player = instantiate(world, new GameProfile(uuid, name));
        if (player == null) return null;

        player.setId(entityId);
        player.refreshPositionAndAngles(pos.x, pos.y, pos.z, 0.0f, 0.0f);
        player.setVelocity(0.0, 0.0, 0.0);
        player.setHealth(20.0f);
        addEntity(world, entityId, player);
        return player;
    }

    static void removeFakePlayer(MinecraftClient client, int entityId) {
        if (client == null || !(client.world instanceof ClientWorld world)) return;

        Entity existing = world.getEntityById(entityId);
        if (existing == null) return;
        invokeRemoval(world, existing, entityId);
    }

    private static OtherClientPlayerEntity instantiate(ClientWorld world, GameProfile profile) {
        try {
            for (Constructor<?> constructor : OtherClientPlayerEntity.class.getDeclaredConstructors()) {
                Class<?>[] parameterTypes = constructor.getParameterTypes();
                if (parameterTypes.length != 2) continue;
                if (!parameterTypes[0].isAssignableFrom(world.getClass()) && !parameterTypes[0].isAssignableFrom(ClientWorld.class)) continue;
                if (!parameterTypes[1].isAssignableFrom(GameProfile.class) && !GameProfile.class.isAssignableFrom(parameterTypes[1])) continue;
                constructor.setAccessible(true);
                Object created = constructor.newInstance(world, profile);
                if (created instanceof OtherClientPlayerEntity player) return player;
            }
        } catch (Throwable t) {
            LOG.warn("Failed to instantiate fake client-side player {}", profile.name(), t);
        }

        return null;
    }

    private static void addEntity(ClientWorld world, int entityId, OtherClientPlayerEntity player) {
        try {
            for (Method method : world.getClass().getMethods()) {
                Class<?>[] parameterTypes = method.getParameterTypes();
                if (!Objects.equals(method.getName(), "addEntity")) continue;
                if (parameterTypes.length == 2 && parameterTypes[0] == int.class && Entity.class.isAssignableFrom(parameterTypes[1])) {
                    method.invoke(world, entityId, player);
                    return;
                }
                if (parameterTypes.length == 1 && Entity.class.isAssignableFrom(parameterTypes[0])) {
                    method.invoke(world, player);
                    return;
                }
            }

            for (Method method : world.getClass().getMethods()) {
                Class<?>[] parameterTypes = method.getParameterTypes();
                if (!Objects.equals(method.getName(), "addPlayer")) continue;
                if (parameterTypes.length == 2 && parameterTypes[0] == int.class && parameterTypes[1].isAssignableFrom(player.getClass())) {
                    method.invoke(world, entityId, player);
                    return;
                }
            }
        } catch (Throwable t) {
            LOG.warn("Failed to add fake client-side player {}", player.getGameProfile().name(), t);
        }
    }

    private static void invokeRemoval(ClientWorld world, Entity entity, int entityId) {
        try {
            for (Method method : world.getClass().getMethods()) {
                Class<?>[] parameterTypes = method.getParameterTypes();
                if (!Objects.equals(method.getName(), "removeEntity")) continue;
                if (parameterTypes.length == 1 && parameterTypes[0] == int.class) {
                    method.invoke(world, entityId);
                    return;
                }
                if (parameterTypes.length == 2 && parameterTypes[0] == int.class) {
                    Object removalReason = parameterTypes[1].getEnumConstants() != null && parameterTypes[1].getEnumConstants().length > 0
                        ? parameterTypes[1].getEnumConstants()[0]
                        : null;
                    method.invoke(world, entityId, removalReason);
                    return;
                }
            }
        } catch (Throwable ignored) {
        }

        entity.discard();
    }
}
