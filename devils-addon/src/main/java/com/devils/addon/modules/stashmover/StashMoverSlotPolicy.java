package com.devils.addon.modules.stashmover;

import java.util.List;
import java.util.Locale;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

public final class StashMoverSlotPolicy {
    private StashMoverSlotPolicy() {
    }

    public static Selection selectPearlHotbarSlot(List<ItemStack> hotbar) {
        if (hotbar == null || hotbar.size() < 9) return new Selection(-1, Status.NO_SAFE_SLOT);
        return selectPearlHotbarSlotKinds(hotbar.stream().map(StashMoverSlotPolicy::classify).toList());
    }

    public static Selection selectPearlHotbarSlotKinds(List<SlotKind> hotbar) {
        if (hotbar == null || hotbar.size() < 9) return new Selection(-1, Status.NO_SAFE_SLOT);

        for (int i = 0; i < 9; i++) {
            if (hotbar.get(i) == SlotKind.ENDER_PEARL) return new Selection(i, Status.ALREADY_PRESENT);
        }
        for (int i = 0; i < 9; i++) {
            if (hotbar.get(i) == SlotKind.EMPTY) return new Selection(i, Status.EMPTY_SLOT);
        }
        for (int i = 0; i < 9; i++) {
            if (hotbar.get(i) == SlotKind.REPLACEABLE) return new Selection(i, Status.REPLACEABLE_SLOT);
        }

        return new Selection(-1, Status.NO_SAFE_SLOT);
    }

    public static SlotKind classify(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return SlotKind.EMPTY;
        if (stack.isOf(Items.ENDER_PEARL)) return SlotKind.ENDER_PEARL;
        if (isProtectedHotbarStack(stack)) return SlotKind.PROTECTED;
        return SlotKind.REPLACEABLE;
    }

    public static boolean isProtectedHotbarStack(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;

        return isSword(stack)
            || isPickaxe(stack)
            || stack.isOf(Items.ENCHANTED_GOLDEN_APPLE)
            || stack.isOf(Items.END_CRYSTAL)
            || stack.isOf(Items.ENDER_PEARL)
            || stack.isOf(Items.EXPERIENCE_BOTTLE)
            || stack.isOf(Items.OBSIDIAN)
            || stack.isOf(Items.TOTEM_OF_UNDYING);
    }

    private static boolean isSword(ItemStack stack) {
        return stack.isOf(Items.WOODEN_SWORD)
            || stack.isOf(Items.STONE_SWORD)
            || stack.isOf(Items.IRON_SWORD)
            || stack.isOf(Items.GOLDEN_SWORD)
            || stack.isOf(Items.DIAMOND_SWORD)
            || stack.isOf(Items.NETHERITE_SWORD);
    }

    private static boolean isPickaxe(ItemStack stack) {
        return stack.isOf(Items.WOODEN_PICKAXE)
            || stack.isOf(Items.STONE_PICKAXE)
            || stack.isOf(Items.IRON_PICKAXE)
            || stack.isOf(Items.GOLDEN_PICKAXE)
            || stack.isOf(Items.DIAMOND_PICKAXE)
            || stack.isOf(Items.NETHERITE_PICKAXE);
    }

    public record Selection(int slot, Status status) {
        public boolean success() {
            return slot >= 0 && status != Status.NO_SAFE_SLOT;
        }
    }

    public enum SlotKind {
        EMPTY,
        ENDER_PEARL,
        PROTECTED,
        REPLACEABLE
    }

    public enum Status {
        ALREADY_PRESENT,
        EMPTY_SLOT,
        REPLACEABLE_SLOT,
        NO_SAFE_SLOT
    }
}

final class StashMoverConfigCodec {
    private StashMoverConfigCodec() {
    }

    static String encodeBlockPos(BlockPos pos) {
        if (pos == null) return "";
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    static BlockPos decodeBlockPos(String value) {
        if (value == null || value.isBlank()) return null;

        String[] parts = value.trim().split("\\s*,\\s*");
        if (parts.length != 3) return null;

        try {
            int x = Integer.parseInt(parts[0]);
            int y = Integer.parseInt(parts[1]);
            int z = Integer.parseInt(parts[2]);
            return new BlockPos(x, y, z);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    static String encodeVec3d(Vec3d pos) {
        if (pos == null) return "";
        return String.format(Locale.ROOT, "%.3f,%.3f,%.3f", pos.x, pos.y, pos.z);
    }

    static Vec3d decodeVec3d(String value) {
        if (value == null || value.isBlank()) return null;

        String[] parts = value.trim().split("\\s*,\\s*");
        if (parts.length != 3) return null;

        try {
            double x = Double.parseDouble(parts[0]);
            double y = Double.parseDouble(parts[1]);
            double z = Double.parseDouble(parts[2]);
            return new Vec3d(x, y, z);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
