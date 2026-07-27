package com.devils.addon.modules.spearspoof;

import meteordevelopment.meteorclient.mixininterface.IPlayerInteractEntityC2SPacket;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractEntityC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.network.packet.s2c.play.DamageTiltS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityDamageS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityStatusS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityTrackerUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityVelocityUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.HealthUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerPositionLookS2CPacket;

import java.lang.reflect.Method;

final class SpearSpoofPacketInspector {
    private SpearSpoofPacketInspector() {
    }

    static boolean isInterestingSendPacket(Object packet) {
        return packet instanceof IPlayerInteractEntityC2SPacket
            || packet instanceof ClientCommandC2SPacket
            || packet instanceof PlayerActionC2SPacket
            || packet instanceof PlayerMoveC2SPacket;
    }

    static boolean isInterestingReceivePacket(Object packet) {
        return packet instanceof EntityStatusS2CPacket
            || packet instanceof EntityDamageS2CPacket
            || packet instanceof EntityTrackerUpdateS2CPacket
            || packet instanceof EntityVelocityUpdateS2CPacket
            || packet instanceof PlayerPositionLookS2CPacket
            || packet instanceof HealthUpdateS2CPacket
            || packet instanceof DamageTiltS2CPacket
            || packet instanceof EntityS2CPacket;
    }

    static String describeSendPacket(Object packet) {
        StringBuilder detail = new StringBuilder();
        detail.append("name=").append(packet.getClass().getSimpleName());

        if (packet instanceof IPlayerInteractEntityC2SPacket interact) {
            detail.append(" type=").append(interact.meteor$getType());
            if (interact.meteor$getType() == PlayerInteractEntityC2SPacket.InteractType.ATTACK) detail.append(" attack=true");
            if (interact.meteor$getEntity() != null) {
                detail.append(" entityId=").append(interact.meteor$getEntity().getId());
                detail.append(" entity=").append(interact.meteor$getEntity().getName().getString());
            }
        }

        if (packet instanceof PlayerActionC2SPacket action) {
            detail.append(" action=").append(action.getAction());
            if (action.getAction() == PlayerActionC2SPacket.Action.STAB) detail.append(" stab=true");
        }

        if (packet instanceof ClientCommandC2SPacket) {
            Object mode = invokeNoArg(packet, "getMode");
            if (mode == null) mode = invokeNoArg(packet, "mode");
            if (mode != null) detail.append(" mode=").append(mode);
        }

        Object onGround = invokeNoArg(packet, "isOnGround");
        if (onGround instanceof Boolean value) detail.append(" onGround=").append(value);

        return detail.toString();
    }

    static String describeReceivePacket(Object packet) {
        StringBuilder detail = new StringBuilder();
        String name = packet.getClass().getSimpleName();
        detail.append("name=").append(name);

        // Typed access only. Reflection by Yarn name resolves to nothing in a remapped production jar,
        // so the old entityId/status lookups silently logged nothing exactly where it mattered most.
        if (packet instanceof EntityDamageS2CPacket damage) {
            detail.append(" entityId=").append(damage.entityId());
            detail.append(" sourceCauseId=").append(damage.sourceCauseId());
        }

        if (packet instanceof EntityStatusS2CPacket status) {
            detail.append(" status=").append(status.getStatus());
            MinecraftClient mc = MinecraftClient.getInstance();
            Entity entity = mc.world != null ? status.getEntity(mc.world) : null;
            if (entity != null) detail.append(" entityId=").append(entity.getId());
        }

        if (packet instanceof EntityVelocityUpdateS2CPacket velocity) {
            detail.append(" entityId=").append(velocity.getEntityId());
        }

        if (packet instanceof PlayerPositionLookS2CPacket) {
            detail.append(" rubberband=true");
        }

        return detail.toString();
    }

    private static Object invokeNoArg(Object target, String methodName) {
        if (target == null || methodName == null || methodName.isBlank()) return null;
        try {
            Method method = target.getClass().getMethod(methodName);
            method.setAccessible(true);
            return method.invoke(target);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
