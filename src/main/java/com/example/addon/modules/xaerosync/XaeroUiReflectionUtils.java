package com.example.addon.modules.xaerosync;

import meteordevelopment.meteorclient.events.meteor.MouseClickEvent;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;
import net.minecraft.world.World;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

public final class XaeroUiReflectionUtils {
    private XaeroUiReflectionUtils() {
    }

    public static RegistryKey<World> parseDimensionKey(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            String value = raw.contains(":") ? raw : "minecraft:" + raw;
            return RegistryKey.of(RegistryKeys.WORLD, Identifier.of(value));
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static Field findField(Class<?> owner, String name) {
        if (owner == null || name == null || name.isBlank()) return null;
        Class<?> cursor = owner;
        while (cursor != null) {
            try {
                return cursor.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                cursor = cursor.getSuperclass();
            }
        }
        return null;
    }

    public static Method findResizeMethod(Class<?> owner) {
        if (owner == null) return null;
        for (Method method : owner.getMethods()) {
            if (!"method_25423".equals(method.getName())) continue;
            Class<?>[] params = method.getParameterTypes();
            if (params.length != 3) continue;
            if (params[1] != int.class || params[2] != int.class) continue;
            return method;
        }
        return null;
    }

    public static Object invokeNoArg(Object owner, String methodName) {
        if (owner == null || methodName == null || methodName.isBlank()) return null;
        try {
            Method method = owner.getClass().getMethod(methodName);
            method.setAccessible(true);
            return method.invoke(owner);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static void invokeNoArgIfPresent(Object owner, String methodName) {
        if (owner == null || methodName == null || methodName.isBlank()) return;
        try {
            Method method = owner.getClass().getMethod(methodName);
            method.setAccessible(true);
            method.invoke(owner);
        } catch (Throwable ignored) {
        }
    }

    public static void invokeSingleArgIfPresent(Object owner, String methodName, Object arg) {
        if (owner == null || methodName == null || methodName.isBlank()) return;
        Method fallback = null;
        for (Method method : owner.getClass().getMethods()) {
            if (!methodName.equals(method.getName())) continue;
            if (method.getParameterCount() != 1) continue;
            Class<?> parameterType = method.getParameterTypes()[0];
            if (arg == null || parameterType.isInstance(arg)) {
                try {
                    method.setAccessible(true);
                    method.invoke(owner, arg);
                    return;
                } catch (Throwable ignored) {
                }
            }
            fallback = method;
        }
        if (fallback != null) {
            try {
                fallback.setAccessible(true);
                fallback.invoke(owner, arg);
            } catch (Throwable ignored) {
            }
        }
    }

    public static void drawBoxOutline(DrawContext drawContext, int x, int y, int w, int h, int color) {
        drawContext.fill(x, y, x + w, y + 1, color);
        drawContext.fill(x, y + h - 1, x + w, y + h, color);
        drawContext.fill(x, y, x + 1, y + h, color);
        drawContext.fill(x + w - 1, y, x + w, y + h, color);
    }

    public static void cancelInputEvent(Object eventRef) {
        if (eventRef instanceof MouseClickEvent mouseEvent) mouseEvent.setCancelled(true);
    }

    public static Integer readWidgetX(Object owner) {
        return firstNonNullInt(
            tryInvokeInt(owner, "getX"),
            tryInvokeInt(owner, "method_46426"),
            tryReadFieldInt(owner, "field_22760"),
            tryReadFieldInt(owner, "x")
        );
    }

    public static Integer readWidgetY(Object owner) {
        return firstNonNullInt(
            tryInvokeInt(owner, "getY"),
            tryInvokeInt(owner, "method_46427"),
            tryReadFieldInt(owner, "field_22761"),
            tryReadFieldInt(owner, "y")
        );
    }

    public static Integer readWidgetWidth(Object owner) {
        return firstNonNullInt(
            tryInvokeInt(owner, "getWidth"),
            tryReadFieldInt(owner, "field_22758"),
            tryReadFieldInt(owner, "width")
        );
    }

    public static Integer readWidgetHeight(Object owner) {
        return firstNonNullInt(
            tryInvokeInt(owner, "getHeight"),
            tryReadFieldInt(owner, "field_22759"),
            tryReadFieldInt(owner, "height")
        );
    }

    public static Double readFieldDouble(Object owner, String fieldName) {
        Object value = tryReadFieldValue(owner, fieldName);
        if (value instanceof Number number) return number.doubleValue();
        return null;
    }

    public static Object tryReadFieldValue(Object owner, String fieldName) {
        if (owner == null || fieldName == null || fieldName.isBlank()) return null;
        try {
            Field field = findField(owner.getClass(), fieldName);
            if (field == null) return null;
            field.setAccessible(true);
            return field.get(owner);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Integer tryInvokeInt(Object owner, String methodName) {
        if (owner == null || methodName == null || methodName.isBlank()) return null;
        try {
            Method method = owner.getClass().getMethod(methodName);
            Object value = method.invoke(owner);
            if (value instanceof Integer integer) return integer;
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static Integer firstNonNullInt(Integer... values) {
        if (values == null) return null;
        for (Integer value : values) {
            if (value != null) return value;
        }
        return null;
    }

    private static Integer tryReadFieldInt(Object owner, String fieldName) {
        Object value = tryReadFieldValue(owner, fieldName);
        if (value instanceof Number number) return number.intValue();
        return null;
    }
}


