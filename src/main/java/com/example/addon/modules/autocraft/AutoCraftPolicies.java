package com.example.addon.modules.autocraft;

public final class AutoCraftPolicies {
    public static final long DEFAULT_AUTO_OPEN_RETRY_TICKS = 20;
    public static final long DEFAULT_MANUAL_CLOSE_SUPPRESSION_TICKS = 60;
    public static final int UNLIMITED_LIMIT = 0;

    private AutoCraftPolicies() {}

    public static int remainingLimit(int limit, int craftedCount) {
        if (limit <= 0) return Integer.MAX_VALUE;
        return Math.max(0, limit - Math.max(0, craftedCount));
    }

    public static int maxBatchesForLimit(int limit, int craftedCount, int outputPerBatch) {
        if (outputPerBatch <= 0) return 0;
        int remaining = remainingLimit(limit, craftedCount);
        if (remaining == Integer.MAX_VALUE) return Integer.MAX_VALUE;
        return remaining / outputPerBatch;
    }

    public static boolean shouldDropFinalOutput(boolean dropSetting, boolean finalStep) {
        return dropSetting && finalStep;
    }

    public static boolean shouldFastClose(boolean fastCloseSetting, boolean autoOpenedScreen, boolean executorIdle) {
        return fastCloseSetting && autoOpenedScreen && executorIdle;
    }

    public static boolean shouldPlanNextCycle(boolean craftAllSetting, int craftedFinalItems) {
        return craftAllSetting || craftedFinalItems <= 0;
    }

    public static boolean shouldAttemptAutoOpen(long currentTick, long suppressedUntilTick, long lastAttemptTick, long retryCooldownTicks) {
        if (currentTick < suppressedUntilTick) return false;
        if (lastAttemptTick < 0) return true;
        return currentTick - lastAttemptTick >= Math.max(0, retryCooldownTicks);
    }

    public static long suppressAutoOpenUntil(long currentTick, long suppressionTicks) {
        return currentTick + Math.max(0, suppressionTicks);
    }
}
