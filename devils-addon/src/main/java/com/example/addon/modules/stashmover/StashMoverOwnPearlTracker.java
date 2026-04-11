package com.example.addon.modules.stashmover;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.util.math.BlockPos;

public final class StashMoverOwnPearlTracker {
    private boolean awaitingSpawn;
    private int spawnTimeoutTicks;
    private int trackedEntityId = -1;

    public void reset() {
        awaitingSpawn = false;
        spawnTimeoutTicks = 0;
        trackedEntityId = -1;
    }

    public void beginAwaitingSpawn(int timeoutTicks) {
        awaitingSpawn = true;
        spawnTimeoutTicks = Math.max(1, timeoutTicks);
        trackedEntityId = -1;
    }

    public boolean isAwaitingSpawn() {
        return awaitingSpawn;
    }

    public boolean hasTrackedPearl() {
        return trackedEntityId >= 0;
    }

    public int trackedEntityId() {
        return trackedEntityId;
    }

    public String debugState() {
        return "awaiting=" + awaitingSpawn + " tracked=" + trackedEntityId + " timeout=" + spawnTimeoutTicks;
    }

    public CaptureOutcome onPearlAdded(int entityId, boolean ownerMatches) {
        if (!awaitingSpawn || !ownerMatches) return CaptureOutcome.IGNORED;

        trackedEntityId = entityId;
        awaitingSpawn = false;
        spawnTimeoutTicks = 0;
        return CaptureOutcome.TRACKED;
    }

    public RemovalOutcome onEntityRemoved(int entityId) {
        if (trackedEntityId != entityId) return RemovalOutcome.IGNORED;

        trackedEntityId = -1;
        awaitingSpawn = false;
        spawnTimeoutTicks = 0;
        return RemovalOutcome.TRACKED_REMOVED;
    }

    public AwaitOutcome tickAwaitingSpawn() {
        if (!awaitingSpawn) return AwaitOutcome.IDLE;

        spawnTimeoutTicks--;
        if (spawnTimeoutTicks > 0) return AwaitOutcome.WAITING;

        awaitingSpawn = false;
        spawnTimeoutTicks = 0;
        trackedEntityId = -1;
        return AwaitOutcome.TIMED_OUT;
    }

    public enum CaptureOutcome {
        IGNORED,
        TRACKED
    }

    public enum RemovalOutcome {
        IGNORED,
        TRACKED_REMOVED
    }

    public enum AwaitOutcome {
        IDLE,
        WAITING,
        TIMED_OUT
    }
}

final class StashMoverBaritoneBridge {
    private boolean available;
    private Object baritoneInstance;
    private Method getCustomGoalProcessMethod;
    private Method setGoalAndPathMethod;
    private Method setGoalMethod;
    private Method cancelEverythingMethod;
    private Method isPathingMethod;
    private Constructor<?> goalGetToBlockConstructor;
    private Constructor<?> goalBlockConstructor;
    private Object settingsInstance;
    private Field allowBreakSettingField;
    private Field allowBreakAnywaySettingField;
    private boolean noBreakGuardApplied;
    private Boolean previousAllowBreak;
    private List<?> previousAllowBreakAnyway;
    private BlockPos activeGoal;

    StashMoverBaritoneBridge() {
        initReflection();
    }

    boolean isAvailable() {
        return available;
    }

    boolean isPathing() {
        if (!available || baritoneInstance == null || isPathingMethod == null) return false;

        try {
            Object pathingBehavior = baritoneInstance.getClass().getMethod("getPathingBehavior").invoke(baritoneInstance);
            Object result = isPathingMethod.invoke(pathingBehavior);
            return result instanceof Boolean value && value;
        } catch (Exception ignored) {
            return false;
        }
    }

    BlockPos activeGoal() {
        return activeGoal;
    }

    boolean goTo(BlockPos pos) {
        return goTo(pos, false);
    }

    boolean goToExact(BlockPos pos) {
        return goTo(pos, true);
    }

    private boolean goTo(BlockPos pos, boolean exact) {
        if (!available || pos == null) return false;
        boolean guardAppliedForThisRequest = applyNoBreakGuard();
        if (pos.equals(activeGoal) && isPathing()) return true;

        try {
            Object process = getCustomGoalProcessMethod.invoke(baritoneInstance);
            Object goal = (exact && goalBlockConstructor != null ? goalBlockConstructor : goalGetToBlockConstructor).newInstance(pos);
            setGoalAndPathMethod.invoke(process, goal);
            activeGoal = pos.toImmutable();
            return true;
        } catch (Exception ignored) {
            if (guardAppliedForThisRequest) restoreNoBreakGuard();
            return false;
        }
    }

    void cancel() {
        if (!available) {
            activeGoal = null;
            return;
        }

        try {
            Object pathingBehavior = baritoneInstance.getClass().getMethod("getPathingBehavior").invoke(baritoneInstance);
            if (cancelEverythingMethod != null) cancelEverythingMethod.invoke(pathingBehavior);
            Object process = getCustomGoalProcessMethod.invoke(baritoneInstance);
            setGoalMethod.invoke(process, new Object[] { null });
        } catch (Exception ignored) {
        } finally {
            activeGoal = null;
            restoreNoBreakGuard();
        }
    }

    private void initReflection() {
        try {
            Class<?> apiClass = Class.forName("baritone.api.BaritoneAPI");
            Object provider = apiClass.getMethod("getProvider").invoke(null);
            baritoneInstance = provider.getClass().getMethod("getPrimaryBaritone").invoke(provider);
            settingsInstance = apiClass.getMethod("getSettings").invoke(null);

            getCustomGoalProcessMethod = baritoneInstance.getClass().getMethod("getCustomGoalProcess");
            Object process = getCustomGoalProcessMethod.invoke(baritoneInstance);

            Class<?> goalClass = Class.forName("baritone.api.pathing.goals.Goal");
            Class<?> goalGetToBlockClass = Class.forName("baritone.api.pathing.goals.GoalGetToBlock");
            Class<?> goalBlockClass = Class.forName("baritone.api.pathing.goals.GoalBlock");
            goalGetToBlockConstructor = goalGetToBlockClass.getConstructor(BlockPos.class);
            goalBlockConstructor = goalBlockClass.getConstructor(BlockPos.class);
            setGoalAndPathMethod = process.getClass().getMethod("setGoalAndPath", goalClass);
            setGoalMethod = process.getClass().getMethod("setGoal", goalClass);

            Object pathingBehavior = baritoneInstance.getClass().getMethod("getPathingBehavior").invoke(baritoneInstance);
            isPathingMethod = pathingBehavior.getClass().getMethod("isPathing");
            cancelEverythingMethod = pathingBehavior.getClass().getMethod("cancelEverything");
            allowBreakSettingField = settingsInstance.getClass().getField("allowBreak");
            allowBreakAnywaySettingField = settingsInstance.getClass().getField("allowBreakAnyway");
            available = true;
        } catch (Exception ignored) {
            available = false;
            baritoneInstance = null;
            getCustomGoalProcessMethod = null;
            setGoalAndPathMethod = null;
            setGoalMethod = null;
            cancelEverythingMethod = null;
            isPathingMethod = null;
            goalGetToBlockConstructor = null;
            goalBlockConstructor = null;
            settingsInstance = null;
            allowBreakSettingField = null;
            allowBreakAnywaySettingField = null;
            noBreakGuardApplied = false;
            previousAllowBreak = null;
            previousAllowBreakAnyway = null;
        }
    }

    private boolean applyNoBreakGuard() {
        if (noBreakGuardApplied || settingsInstance == null || allowBreakSettingField == null || allowBreakAnywaySettingField == null) {
            return false;
        }

        try {
            previousAllowBreak = readBooleanSetting(allowBreakSettingField);
            previousAllowBreakAnyway = readListSettingCopy(allowBreakAnywaySettingField);
            writeBooleanSetting(allowBreakSettingField, false);
            clearListSetting(allowBreakAnywaySettingField);
            noBreakGuardApplied = true;
            return true;
        } catch (Exception ignored) {
            previousAllowBreak = null;
            previousAllowBreakAnyway = null;
            return false;
        }
    }

    private void restoreNoBreakGuard() {
        if (!noBreakGuardApplied || settingsInstance == null) return;

        try {
            if (allowBreakSettingField != null && previousAllowBreak != null) {
                writeBooleanSetting(allowBreakSettingField, previousAllowBreak);
            }
            if (allowBreakAnywaySettingField != null && previousAllowBreakAnyway != null) {
                restoreListSetting(allowBreakAnywaySettingField, previousAllowBreakAnyway);
            }
        } catch (Exception ignored) {
        } finally {
            noBreakGuardApplied = false;
            previousAllowBreak = null;
            previousAllowBreakAnyway = null;
        }
    }

    private Boolean readBooleanSetting(Field settingField) throws ReflectiveOperationException {
        Object setting = settingField.get(settingsInstance);
        Object value = setting.getClass().getField("value").get(setting);
        return value instanceof Boolean bool ? bool : null;
    }

    private List<?> readListSettingCopy(Field settingField) throws ReflectiveOperationException {
        Object setting = settingField.get(settingsInstance);
        Object value = setting.getClass().getField("value").get(setting);
        if (value instanceof List<?> list) return new ArrayList<>(list);
        return List.of();
    }

    private void writeBooleanSetting(Field settingField, boolean value) throws ReflectiveOperationException {
        Object setting = settingField.get(settingsInstance);
        setting.getClass().getField("value").set(setting, value);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void clearListSetting(Field settingField) throws ReflectiveOperationException {
        Object setting = settingField.get(settingsInstance);
        Field valueField = setting.getClass().getField("value");
        Object currentValue = valueField.get(setting);
        if (currentValue instanceof List list) {
            list.clear();
            return;
        }
        valueField.set(setting, new ArrayList<>());
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void restoreListSetting(Field settingField, List<?> snapshot) throws ReflectiveOperationException {
        Object setting = settingField.get(settingsInstance);
        Field valueField = setting.getClass().getField("value");
        Object currentValue = valueField.get(setting);
        if (currentValue instanceof List list) {
            list.clear();
            list.addAll(snapshot);
            return;
        }
        valueField.set(setting, new ArrayList<>(snapshot));
    }
}
