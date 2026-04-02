package com.example.addon.util.xaerosync;

import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;
import net.minecraft.world.World;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public final class XaeroWaypointReflection {
    private XaeroWaypointReflection() {
    }

    public static RegistryKey<World> currentDimensionKey() {
        if (mc.world == null) return World.OVERWORLD;
        return mc.world.getRegistryKey();
    }

    public static RegistryKey<World> parseDimensionKey(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            Identifier id = raw.contains(":") ? Identifier.of(raw) : Identifier.of("minecraft", raw);
            return RegistryKey.of(RegistryKeys.WORLD, id);
        } catch (Throwable ignored) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    public static List<Object> extractWaypointList(Object set) {
        if (set == null) return null;

        for (String methodName : List.of("getWaypoints", "getList")) {
            try {
                Object result = set.getClass().getMethod(methodName).invoke(set);
                if (result instanceof List<?> list) return (List<Object>) list;
            } catch (Throwable ignored) {
            }
        }

        for (Field field : set.getClass().getDeclaredFields()) {
            if (!List.class.isAssignableFrom(field.getType())) continue;
            try {
                field.setAccessible(true);
                Object value = field.get(set);
                if (value instanceof List<?> list) return (List<Object>) list;
            } catch (Throwable ignored) {
            }
        }

        return null;
    }

    public static String readWaypointName(Object waypoint) {
        if (waypoint == null) return null;
        try {
            Object value = waypoint.getClass().getMethod("getName").invoke(waypoint);
            return value instanceof String s ? s : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static String readWaypointSymbol(Object waypoint) {
        if (waypoint == null) return null;
        try {
            Object value = waypoint.getClass().getMethod("getSymbol").invoke(waypoint);
            return value instanceof String s ? s : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static void trySaveWorld(Object session, Object world) {
        if (session == null || world == null) return;
        try {
            Object worldIo = session.getClass().getMethod("getWorldManagerIO").invoke(session);
            if (worldIo == null) return;

            for (Method method : worldIo.getClass().getMethods()) {
                if (!method.getName().equals("saveWorld")) continue;
                Class<?>[] params = method.getParameterTypes();
                if (params.length == 1 && params[0].isAssignableFrom(world.getClass())) {
                    method.invoke(worldIo, world);
                    return;
                }
                if (params.length == 2 && params[0].isAssignableFrom(world.getClass()) && params[1] == boolean.class) {
                    method.invoke(worldIo, world, false);
                    return;
                }
            }
        } catch (Throwable ignored) {
        }
    }

    public static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    public static Object readFieldValue(Object target, String fieldName) {
        if (target == null || fieldName == null || fieldName.isBlank()) return null;
        Class<?> cursor = target.getClass();
        while (cursor != null) {
            try {
                Field field = cursor.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException ignored) {
                cursor = cursor.getSuperclass();
            } catch (Throwable ignored) {
                return null;
            }
        }
        return null;
    }

    public static Object readStaticField(String className, String fieldName) {
        if (className == null || className.isBlank() || fieldName == null || fieldName.isBlank()) return null;
        try {
            Class<?> klass = Class.forName(className);
            Field field = klass.getField(fieldName);
            field.setAccessible(true);
            return field.get(null);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static boolean writeFieldValue(Object target, String fieldName, Object value) {
        if (target == null || fieldName == null || fieldName.isBlank()) return false;
        Class<?> cursor = target.getClass();
        while (cursor != null) {
            try {
                Field field = cursor.getDeclaredField(fieldName);
                field.setAccessible(true);
                field.set(target, value);
                return true;
            } catch (NoSuchFieldException ignored) {
                cursor = cursor.getSuperclass();
            } catch (Throwable ignored) {
                return false;
            }
        }
        return false;
    }

    public static Object invokeNoArg(Object target, String methodName) {
        if (target == null || methodName == null || methodName.isBlank()) return null;
        try {
            Method method = target.getClass().getMethod(methodName);
            return method.invoke(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static Object invokeSingleArg(Object target, String methodName, Object arg) {
        if (target == null || methodName == null || methodName.isBlank()) return null;
        Method fallback = null;
        for (Method method : target.getClass().getMethods()) {
            if (!method.getName().equals(methodName) || method.getParameterCount() != 1) continue;
            if (!isParameterCompatible(method.getParameterTypes()[0], arg)) {
                if (fallback == null) fallback = method;
                continue;
            }
            try {
                method.setAccessible(true);
                return method.invoke(target, arg);
            } catch (Throwable ignored) {
                return null;
            }
        }

        if (fallback != null) {
            try {
                fallback.setAccessible(true);
                return fallback.invoke(target, arg);
            } catch (Throwable ignored) {
                return null;
            }
        }

        return null;
    }

    public static Boolean invokeBooleanNoArg(Object target, String methodName) {
        if (target == null || methodName == null || methodName.isBlank()) return null;
        try {
            Method method = target.getClass().getMethod(methodName);
            method.setAccessible(true);
            Object value = method.invoke(target);
            return value instanceof Boolean b ? b : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static void invokeTwoArgs(Object target, String methodName, Object arg1, Object arg2) {
        if (target == null || methodName == null || methodName.isBlank()) return;
        Method fallback = null;
        for (Method method : target.getClass().getMethods()) {
            if (!method.getName().equals(methodName) || method.getParameterCount() != 2) continue;
            Class<?>[] params = method.getParameterTypes();
            if (!isParameterCompatible(params[0], arg1) || !isParameterCompatible(params[1], arg2)) {
                if (fallback == null) fallback = method;
                continue;
            }
            try {
                method.setAccessible(true);
                method.invoke(target, arg1, arg2);
                return;
            } catch (Throwable ignored) {
                return;
            }
        }

        if (fallback != null) {
            try {
                fallback.setAccessible(true);
                fallback.invoke(target, arg1, arg2);
            } catch (Throwable ignored) {
            }
        }
    }

    private static boolean isParameterCompatible(Class<?> parameterType, Object value) {
        if (parameterType == null) return false;
        if (value == null) return !parameterType.isPrimitive();
        if (parameterType.isAssignableFrom(value.getClass())) return true;
        if (!parameterType.isPrimitive()) return false;
        return (parameterType == boolean.class && value instanceof Boolean)
            || (parameterType == int.class && value instanceof Integer)
            || (parameterType == long.class && value instanceof Long)
            || (parameterType == float.class && value instanceof Float)
            || (parameterType == double.class && value instanceof Double)
            || (parameterType == short.class && value instanceof Short)
            || (parameterType == byte.class && value instanceof Byte)
            || (parameterType == char.class && value instanceof Character);
    }
}


