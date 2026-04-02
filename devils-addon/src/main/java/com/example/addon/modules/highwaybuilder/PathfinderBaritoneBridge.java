package com.example.addon.modules.highwaybuilder;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.lang.reflect.Method;
import java.util.function.Consumer;
import java.util.function.Predicate;

final class PathfinderBaritoneBridge {
    private final HighwayBuilder module;

    private boolean baritoneActive;
    private boolean baritoneAvailable;

    private Object baritoneInstance;
    private Method setGoalAndPathMethod;
    private Method setGoalMethod;
    private java.lang.reflect.Constructor<?> goalBlockConstructor;

    private Object followProcess;
    private Method pickupMethod;
    private Method followCancelMethod;
    private boolean pickupActive;

    PathfinderBaritoneBridge(HighwayBuilder module) {
        this.module = module;
        initReflection();
    }

    boolean isPickupActive() {
        return pickupActive;
    }

    void startPickup(Predicate<net.minecraft.item.ItemStack> filter) {
        pickupActive = true;
        if (!baritoneAvailable || pickupMethod == null || followProcess == null) return;

        try {
            Object process = baritoneInstance.getClass().getMethod("getCustomGoalProcess")
                .invoke(baritoneInstance);
            setGoalMethod.invoke(process, (Object) null);
            baritoneActive = false;

            pickupMethod.invoke(followProcess, (Predicate<?>) filter);
        } catch (Exception ignored) {
        }
    }

    void stopPickup() {
        pickupActive = false;
        if (!baritoneAvailable || followCancelMethod == null) return;

        try {
            followCancelMethod.invoke(followProcess);
        } catch (Exception ignored) {
        }
    }

    void updateGoal(BlockPos goal, Consumer<Vec3d> fallbackMove) {
        if (!baritoneAvailable) {
            if (goal != null) fallbackMove.accept(Vec3d.ofCenter(goal));
            return;
        }

        try {
            Object process = baritoneInstance.getClass().getMethod("getCustomGoalProcess")
                .invoke(baritoneInstance);

            if (goal != null) {
                Object goalBlock = goalBlockConstructor.newInstance(goal);
                setGoalAndPathMethod.invoke(process, goalBlock);
                baritoneActive = true;
            } else if (baritoneActive) {
                setGoalMethod.invoke(process, (Object) null);
                baritoneActive = false;
            }
        } catch (Exception ignored) {
            if (goal != null) fallbackMove.accept(Vec3d.ofCenter(goal));
        }
    }

    void setupSettings() {
        if (!baritoneAvailable) return;

        try {
            Class<?> apiClass = Class.forName("baritone.api.BaritoneAPI");
            Method getSettings = apiClass.getMethod("getSettings");
            Object settings = getSettings.invoke(null);

            setBaritoneField(settings, "allowPlace", false);
            setBaritoneField(settings, "allowBreak", false);
            setBaritoneField(settings, "renderGoal", module.goalRender.get());
            setBaritoneField(settings, "allowInventory", false);
        } catch (Exception ignored) {
        }
    }

    void reset() {
        if (baritoneAvailable) {
            try {
                Object process = baritoneInstance.getClass().getMethod("getCustomGoalProcess")
                    .invoke(baritoneInstance);
                setGoalMethod.invoke(process, (Object) null);
                baritoneActive = false;
            } catch (Exception ignored) {
            }
        }

        if (pickupActive) stopPickup();
    }

    void clearProcess() {
        baritoneActive = false;
        if (pickupActive) stopPickup();
    }

    private void initReflection() {
        try {
            Class<?> apiClass = Class.forName("baritone.api.BaritoneAPI");
            Method getProvider = apiClass.getMethod("getProvider");
            Object provider = getProvider.invoke(null);
            Method getPrimary = provider.getClass().getMethod("getPrimaryBaritone");
            baritoneInstance = getPrimary.invoke(provider);

            Method getCustomGoalProcess = baritoneInstance.getClass().getMethod("getCustomGoalProcess");
            Object process = getCustomGoalProcess.invoke(baritoneInstance);

            Class<?> goalClass = Class.forName("baritone.api.pathing.goals.Goal");
            Class<?> goalBlockClass = Class.forName("baritone.api.pathing.goals.GoalBlock");
            goalBlockConstructor = goalBlockClass.getConstructor(BlockPos.class);

            setGoalAndPathMethod = process.getClass().getMethod("setGoalAndPath", goalClass);
            setGoalMethod = process.getClass().getMethod("setGoal", goalClass);

            baritoneAvailable = true;
        } catch (Exception ignored) {
            baritoneAvailable = false;
        }

        if (!baritoneAvailable) return;
        try {
            Method getFollowProcess = baritoneInstance.getClass().getMethod("getFollowProcess");
            followProcess = getFollowProcess.invoke(baritoneInstance);

            pickupMethod = followProcess.getClass().getMethod("pickup", Predicate.class);
            followCancelMethod = followProcess.getClass().getMethod("cancel");
        } catch (Exception ignored) {
            followProcess = null;
            pickupMethod = null;
            followCancelMethod = null;
        }
    }

    private void setBaritoneField(Object settings, String fieldName, Object value) {
        try {
            var field = settings.getClass().getField(fieldName);
            var setting = field.get(settings);
            var valueField = setting.getClass().getField("value");
            valueField.set(setting, value);
        } catch (Exception ignored) {
        }
    }
}


