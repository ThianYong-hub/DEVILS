package com.devils.addon.modules.nukerplus;

import net.minecraft.util.math.BlockPos;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Reflection bridge to Baritone's #sel selection API plus the immutable selection-area
 * geometry NukerPlus uses to constrain its Baritone Area mode. Extracted verbatim from
 * NukerPlus; behaviour is unchanged.
 */
public final class NukerPlusBaritoneSelection {
    private final boolean available;
    private final Object primaryBaritone;
    private final Method getSelectionManagerMethod;
    private final Method getSelectionsMethod;
    private final Method minMethod;
    private final Method maxMethod;

    public NukerPlusBaritoneSelection() {
        Object resolvedBaritone = null;
        Method resolvedSelectionManager = null;
        Method resolvedSelections = null;
        Method resolvedMin = null;
        Method resolvedMax = null;
        boolean resolvedAvailable = false;

        try {
            Class<?> apiClass = Class.forName("baritone.api.BaritoneAPI");
            Object provider = apiClass.getMethod("getProvider").invoke(null);
            resolvedBaritone = provider.getClass().getMethod("getPrimaryBaritone").invoke(provider);
            resolvedSelectionManager = resolvedBaritone.getClass().getMethod("getSelectionManager");
            Object manager = resolvedSelectionManager.invoke(resolvedBaritone);
            resolvedSelections = manager.getClass().getMethod("getSelections");

            Class<?> selectionClass = Class.forName("baritone.api.selection.ISelection");
            resolvedMin = selectionClass.getMethod("min");
            resolvedMax = selectionClass.getMethod("max");
            resolvedAvailable = true;
        } catch (Throwable ignored) {
        }

        available = resolvedAvailable;
        primaryBaritone = resolvedBaritone;
        getSelectionManagerMethod = resolvedSelectionManager;
        getSelectionsMethod = resolvedSelections;
        minMethod = resolvedMin;
        maxMethod = resolvedMax;
    }

    public Snapshot snapshot() {
        if (!available || primaryBaritone == null) return Snapshot.unavailable();

        try {
            Object manager = getSelectionManagerMethod.invoke(primaryBaritone);
            Object rawSelections = getSelectionsMethod.invoke(manager);
            if (!(rawSelections instanceof Object[] selections) || selections.length == 0) {
                return Snapshot.empty();
            }

            List<BlockBounds> bounds = new ArrayList<>(selections.length);
            for (Object selection : selections) {
                if (selection == null) continue;

                Object min = minMethod.invoke(selection);
                Object max = maxMethod.invoke(selection);
                BlockBounds bound = BlockBounds.of(min, max);
                if (bound != null) bounds.add(bound);
            }

            if (bounds.isEmpty()) return Snapshot.empty();
            return Snapshot.of(bounds);
        } catch (Throwable ignored) {
            return Snapshot.unavailable();
        }
    }

    public record Snapshot(boolean available, List<BlockBounds> bounds) {
        private static final Snapshot DISABLED = new Snapshot(true, Collections.emptyList());
        private static final Snapshot UNAVAILABLE = new Snapshot(false, Collections.emptyList());

        public static Snapshot disabled() {
            return DISABLED;
        }

        private static Snapshot unavailable() {
            return UNAVAILABLE;
        }

        private static Snapshot empty() {
            return new Snapshot(true, Collections.emptyList());
        }

        private static Snapshot of(List<BlockBounds> bounds) {
            return new Snapshot(true, List.copyOf(bounds));
        }

        public boolean hasSelections() {
            return !bounds.isEmpty();
        }

        public boolean allows(BlockPos pos) {
            if (this == DISABLED) return true;
            if (!available || bounds.isEmpty()) return false;

            for (BlockBounds bound : bounds) {
                if (bound.contains(pos)) return true;
            }

            return false;
        }
    }

    record BlockBounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        private static BlockBounds of(Object min, Object max) {
            if (!(min instanceof BlockPos minPos) || !(max instanceof BlockPos maxPos)) return null;
            return new BlockBounds(
                Math.min(minPos.getX(), maxPos.getX()),
                Math.min(minPos.getY(), maxPos.getY()),
                Math.min(minPos.getZ(), maxPos.getZ()),
                Math.max(minPos.getX(), maxPos.getX()),
                Math.max(minPos.getY(), maxPos.getY()),
                Math.max(minPos.getZ(), maxPos.getZ())
            );
        }

        private boolean contains(BlockPos pos) {
            return pos.getX() >= minX && pos.getX() <= maxX
                && pos.getY() >= minY && pos.getY() <= maxY
                && pos.getZ() >= minZ && pos.getZ() <= maxZ;
        }
    }
}
