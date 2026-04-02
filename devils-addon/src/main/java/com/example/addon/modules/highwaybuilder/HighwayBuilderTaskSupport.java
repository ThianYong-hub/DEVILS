package com.example.addon.modules.highwaybuilder;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import meteordevelopment.meteorclient.utils.player.Rotations;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

final class InventoryJunkDropper {
    private static final MinecraftClient mc = MinecraftClient.getInstance();

    private final HighwayBuilder module;
    private int junkDropDirectionIndex;

    InventoryJunkDropper(HighwayBuilder module) {
        this.module = module;
    }

    int[] getCleanupScanOrder() {
        int[] order = new int[36];
        int p = 0;
        for (int i = 9; i < 36; i++) order[p++] = i;
        for (int i = 0; i < 9; i++) order[p++] = i;
        return order;
    }

    int findNetherrackCleanupSlot(int excess) {
        if (mc.player == null || excess <= 0) return -1;

        int bestSlot = -1;
        int bestCount = Integer.MAX_VALUE;

        for (int slot : getCleanupScanOrder()) {
            ItemStack stack = mc.player.getInventory().getStack(slot);
            if (stack.getItem() != Items.NETHERRACK) continue;

            int count = stack.getCount();
            if (count <= excess) {
                if (count < bestCount) {
                    bestCount = count;
                    bestSlot = slot;
                }
            } else if (bestSlot == -1) {
                bestSlot = slot;
            }
        }

        return bestSlot;
    }

    void dropInventorySlot(int inventorySlot, boolean fullStack) {
        if (mc.player == null || mc.interactionManager == null || mc.player.currentScreenHandler == null) return;
        int screenSlot = inventorySlotToScreenSlot(inventorySlot);
        if (screenSlot < 0) return;

        Runnable throwAction = () -> {
            if (mc.player == null || mc.interactionManager == null || mc.player.currentScreenHandler == null) return;
            mc.interactionManager.clickSlot(
                mc.player.currentScreenHandler.syncId,
                screenSlot,
                fullStack ? 1 : 0,
                SlotActionType.THROW,
                mc.player
            );
        };

        float throwYaw = getNextJunkDropYaw();
        float throwPitch = -10.0f;
        Rotations.rotate(throwYaw, throwPitch, 30, throwAction);
    }

    private float getNextJunkDropYaw() {
        if (mc.player == null) return 0.0f;

        int mode = Math.floorMod(junkDropDirectionIndex++, 3);
        HWDirection buildDir = module.pathfinder != null ? module.pathfinder.startingDirection : null;
        if (buildDir != null) {
            return switch (mode) {
                case 0 -> buildDir.counterClockwise(2).yaw;
                case 1 -> buildDir.clockwise(2).yaw;
                default -> buildDir.clockwise(4).yaw;
            };
        }

        float yaw = mc.player.getYaw();
        return switch (mode) {
            case 0 -> yaw - 90.0f;
            case 1 -> yaw + 90.0f;
            default -> yaw + 180.0f;
        };
    }

    private int inventorySlotToScreenSlot(int inventorySlot) {
        if (inventorySlot < 0 || inventorySlot >= 36) return -1;
        return inventorySlot < 9 ? 36 + inventorySlot : inventorySlot;
    }
}

final class InventoryJunkRules {
    private static final Set<Item> JUNK_ITEMS = Set.of(
        Items.ANCIENT_DEBRIS, Items.NETHER_GOLD_ORE, Items.NETHER_QUARTZ_ORE, Items.QUARTZ,
        Items.GLOWSTONE, Items.BLACKSTONE, Items.BASALT, Items.SMOOTH_BASALT, Items.MAGMA_BLOCK,
        Items.SOUL_SAND, Items.SOUL_SOIL, Items.GRAVEL, Items.FLINT, Items.CRIMSON_NYLIUM,
        Items.WARPED_NYLIUM, Items.CRIMSON_STEM, Items.WARPED_STEM, Items.NETHER_WART_BLOCK,
        Items.WARPED_WART_BLOCK, Items.BONE_BLOCK, Items.NETHER_BRICKS, Items.RED_NETHER_BRICKS,
        Items.CRYING_OBSIDIAN, Items.WEEPING_VINES, Items.TWISTING_VINES, Items.CRIMSON_ROOTS,
        Items.WARPED_ROOTS, Items.NETHER_SPROUTS, Items.CRIMSON_FUNGUS, Items.WARPED_FUNGUS,
        Items.NETHER_WART, Items.BLAZE_ROD, Items.MAGMA_CREAM, Items.GHAST_TEAR, Items.BONE,
        Items.ROTTEN_FLESH, Items.GOLD_NUGGET, Items.GOLDEN_SWORD, Items.GOLDEN_HELMET,
        Items.GOLDEN_CHESTPLATE, Items.GOLDEN_LEGGINGS, Items.GOLDEN_BOOTS
    );

    private final InventoryHandler inventoryHandler;

    InventoryJunkRules(InventoryHandler inventoryHandler) {
        this.inventoryHandler = inventoryHandler;
    }

    boolean shouldDropJunkStack(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;

        Item item = stack.getItem();
        if (item instanceof BlockItem bi && bi.getBlock() instanceof ShulkerBoxBlock) return false;

        // Never trash useful tools used by builder/combat logic.
        if (inventoryHandler.isFortuneThreePickaxeNoSilk(stack)) return false;
        if (stack.isIn(ItemTags.PICKAXES)) return false;
        if (stack.isIn(ItemTags.SHOVELS)) return false;
        if (stack.isIn(ItemTags.AXES) && item != Items.GOLDEN_AXE) return false;
        if (stack.isIn(ItemTags.SWORDS) && item != Items.GOLDEN_SWORD) return false;

        if (item == Items.NETHERRACK) return false;
        if (JUNK_ITEMS.contains(item)) return true;
        return isFireResistancePotion(stack);
    }

    private boolean isFireResistancePotion(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        if (stack.getItem() != Items.POTION
            && stack.getItem() != Items.SPLASH_POTION
            && stack.getItem() != Items.LINGERING_POTION) {
            return false;
        }

        var contents = stack.get(DataComponentTypes.POTION_CONTENTS);
        if (contents == null || contents.potion().isEmpty()) return false;
        var potion = contents.potion().get().value();
        var id = Registries.POTION.getId(potion);
        return id != null && id.getPath().contains("fire_resistance");
    }
}

final class InventoryRoleSlotGuard {
    private static final MinecraftClient mc = MinecraftClient.getInstance();
    private static final int ROLE_SWORD = 0;
    private static final int ROLE_PICKAXE = 1;
    private static final int ROLE_APPLE = 2;
    private static final int ROLE_OBSIDIAN = 3;
    private static final int ROLE_ENDER_CHEST = 4;
    private static final int ROLE_COUNT = 5;

    private final int[] protectedRoleSlots = new int[ROLE_COUNT];
    private boolean protectedSlotsCaptured;

    InventoryRoleSlotGuard() {
        Arrays.fill(protectedRoleSlots, -1);
    }

    void captureInitialPreferredHotbarSlots() {
        if (mc.player == null) return;
        recaptureProtectedRoleSlots();
        protectedSlotsCaptured = true;
    }

    void refreshProtectedHotbarSlotsDynamically(int swapBackSlot) {
        if (mc.player == null) return;
        if (swapBackSlot >= 0) return;
        if (mc.player.currentScreenHandler == null || mc.player.currentScreenHandler.syncId != 0) return;
        ensureProtectedSlotsCaptured();
        recaptureProtectedRoleSlots();
    }

    boolean canUseHotbarSlot(int slot, Item incomingItem) {
        if (slot < 0 || slot >= 9) return false;
        ensureProtectedSlotsCaptured();

        int lockedRole = getLockedRoleForSlot(slot);
        if (lockedRole == -1) return true;

        int incomingRole = roleOfItem(incomingItem);
        return incomingRole != -1 && incomingRole == lockedRole;
    }

    boolean isProtectedHotbarSlot(int slot) {
        if (slot < 0 || slot >= 9) return false;
        ensureProtectedSlotsCaptured();
        return getLockedRoleForSlot(slot) != -1;
    }

    boolean isAppleReservedHotbarSlot(int slot) {
        if (slot < 0 || slot >= 9) return false;
        ensureProtectedSlotsCaptured();
        return protectedRoleSlots[ROLE_APPLE] == slot;
    }

    private void ensureProtectedSlotsCaptured() {
        if (!protectedSlotsCaptured) captureInitialPreferredHotbarSlots();
    }

    private void recaptureProtectedRoleSlots() {
        Arrays.fill(protectedRoleSlots, -1);
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            int role = roleOfStack(stack);
            if (role == -1) continue;
            if (protectedRoleSlots[role] == -1) protectedRoleSlots[role] = i;
        }
    }

    private int getLockedRoleForSlot(int slot) {
        for (int role = 0; role < ROLE_COUNT; role++) {
            if (protectedRoleSlots[role] == slot) return role;
        }
        return -1;
    }

    private int roleOfItem(Item item) {
        if (item == null) return -1;
        return roleOfStack(item.getDefaultStack());
    }

    private int roleOfStack(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return -1;
        if (stack.isIn(ItemTags.SWORDS)) return ROLE_SWORD;
        if (stack.isIn(ItemTags.PICKAXES)) return ROLE_PICKAXE;
        if (stack.getItem() == Items.ENCHANTED_GOLDEN_APPLE
            || stack.getItem() == Items.GOLDEN_APPLE
            || stack.getItem() == Items.APPLE) return ROLE_APPLE;
        if (stack.getItem() == Items.OBSIDIAN) return ROLE_OBSIDIAN;
        if (stack.getItem() == Items.ENDER_CHEST) return ROLE_ENDER_CHEST;
        return -1;
    }
}

final class TaskContainerRecovery {
    private static final MinecraftClient mc = MinecraftClient.getInstance();

    private TaskContainerRecovery() {}

    static void recoverContainerTask(HighwayBuilder module, BlockTask containerTask) {
        if (mc.world == null) return;

        containerTask.resetStuck();
        BlockState state = mc.world.getBlockState(containerTask.blockPos);
        boolean isAirLike = state.isAir() || state.isReplaceable();
        boolean isShulker = state.getBlock() instanceof ShulkerBoxBlock;

        switch (containerTask.taskState) {
            case BREAK, BREAKING, PENDING_BREAK -> {
                if (isAirLike) {
                    containerTask.updateState(TaskState.PICKUP);
                    module.pathfinder.moveState = MovementState.PICKUP;
                } else {
                    containerTask.updateState(TaskState.BREAK);
                    module.pathfinder.moveState = MovementState.RESTOCK;
                }
            }
            case PICKUP -> module.pathfinder.moveState = MovementState.PICKUP;
            case OPEN_CONTAINER, RESTOCK -> {
                if (isShulker) {
                    containerTask.updateState(TaskState.OPEN_CONTAINER);
                    module.pathfinder.moveState = MovementState.RESTOCK;
                } else {
                    if (module.containerHandler.tryRelocateContainerPlacement()) {
                        module.pathfinder.moveState = MovementState.RESTOCK;
                    } else {
                        containerTask.updateState(TaskState.PICKUP);
                        module.pathfinder.moveState = MovementState.PICKUP;
                    }
                }
            }
            case PLACE, PENDING_PLACE, IMPOSSIBLE_PLACE, PLACED -> {
                if (!module.containerHandler.tryRelocateContainerPlacement()) {
                    containerTask.updateState(TaskState.PLACE);
                }
                module.pathfinder.moveState = MovementState.RESTOCK;
            }
            default -> {
                // No DONE transition here: container cycle must finish explicitly.
            }
        }
    }
}

final class TaskPriorityPlanner {
    private static final MinecraftClient mc = MinecraftClient.getInstance();

    private final HighwayBuilder module;
    private final TaskStateRules stateRules;

    TaskPriorityPlanner(HighwayBuilder module, TaskStateRules stateRules) {
        this.module = module;
        this.stateRules = stateRules;
    }

    BlockTask findTopPriorityLiquidTask(List<BlockTask> sorted) {
        if (mc.player == null) return null;

        Vec3d eyePos = mc.player.getEyePos();
        double maxReach = module.maxReach.get() + 0.8;
        double maxForward = module.miningReach.get() + 1.5;

        for (BlockTask task : sorted) {
            if (task.taskState != TaskState.LIQUID) continue;
            if (eyePos.distanceTo(Vec3d.ofCenter(task.blockPos)) > maxReach) continue;
            if (getForwardPriority(task.blockPos) > maxForward) continue;
            return task;
        }

        return null;
    }

    Comparator<BlockTask> comparator() {
        return Comparator.comparingInt((BlockTask task) -> task.taskState.ordinal())
            .thenComparingInt(BlockTask::getStuckTicks)
            .thenComparingInt(task -> task.isLiquidSource ? 0 : 1)
            .thenComparingDouble(task -> {
                if (module.pathfinder.moveState == MovementState.BRIDGE) {
                    return task.sequence.isEmpty() ? 69 : task.sequence.size();
                }
                if (stateRules.isBreakPhaseState(task) || isClosingPlacementState(task.taskState)) {
                    return getForwardPriority(task.blockPos);
                }
                return module.multiBuilding.get() ? task.getShuffle() : task.getStartDistance();
            })
            .thenComparingDouble(task -> stateRules.isBreakPhaseState(task)
                ? getLateralPriority(task.blockPos)
                : 0.0)
            .thenComparingDouble(task -> isClosingPlacementState(task.taskState)
                ? getLateralPriority(task.blockPos)
                : 0.0)
            .thenComparingDouble(BlockTask::getEyeDistance)
            .thenComparingDouble(BlockTask::getStartDistance);
    }

    private boolean isClosingPlacementState(TaskState state) {
        return switch (state) {
            case PLACE, LIQUID, PENDING_PLACE, IMPOSSIBLE_PLACE -> true;
            default -> false;
        };
    }

    private double getForwardPriority(BlockPos pos) {
        HWDirection dir = module.pathfinder.startingDirection;
        BlockPos origin = module.pathfinder.currentBlockPos;
        double progress = dir.forwardProgress(origin, pos);
        if (progress < 0.0) return 10_000.0 + Math.abs(progress);
        return progress;
    }

    private double getLateralPriority(BlockPos pos) {
        HWDirection dir = module.pathfinder.startingDirection;
        BlockPos origin = module.pathfinder.currentBlockPos;
        return Math.abs(dir.lateralOffset(origin, pos));
    }
}



