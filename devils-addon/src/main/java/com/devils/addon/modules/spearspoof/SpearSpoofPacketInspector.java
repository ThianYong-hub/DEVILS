package com.devils.addon.modules.spearspoof;

import meteordevelopment.meteorclient.mixininterface.IPlayerInteractEntityC2SPacket;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractEntityC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.network.packet.s2c.play.DamageTiltS2CPacket;
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
            || packet instanceof PlayerMoveC2SPacket;
    }

    static boolean isInterestingReceivePacket(Object packet) {
        return packet instanceof EntityStatusS2CPacket
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

        Integer entityId = extractEntityId(packet);
        if (entityId != null) {
            detail.append(" entityId=").append(entityId);
        }

        Object status = invokeNoArg(packet, "getStatus");
        if (status != null) detail.append(" status=").append(status);

        if (packet instanceof PlayerPositionLookS2CPacket) {
            detail.append(" rubberband=true");
        }

        return detail.toString();
    }

    private static Integer extractEntityId(Object packet) {
        Object value = invokeNoArg(packet, "getEntityId");
        if (value instanceof Integer i) return i;
        value = invokeNoArg(packet, "getId");
        if (value instanceof Integer i) return i;
        return null;
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
