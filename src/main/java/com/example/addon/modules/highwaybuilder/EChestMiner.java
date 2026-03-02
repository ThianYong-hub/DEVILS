package com.example.addon.modules.highwaybuilder;

import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

/**
 * EChest Miner — places and breaks ender chests to farm obsidian.
 *
 * Silent mode: swap+action+swapback in one tick, no separate SWAP states.
 * Normal mode: separate SWAP_TO_ECHEST / SWAP_TO_PICK states.
 * Insta mode: one attackBlock, no re-hits. interactBlock for instant placement.
 */
public class EChestMiner {
    private static final MinecraftClient mc = MinecraftClient.getInstance();

    private final HighwayBuilder module;

    public enum State {
        IDLE,
        SWAP_TO_ECHEST,
        PLACE_ECHEST,
        SWAP_TO_PICK,
        MINE_HIT,
        WAIT_BREAK,
        COLLECTING,
    }

    private State state = State.IDLE;
    private BlockPos actionPos = null;
    private int chestsRemaining = 0;
    private int tickDelay = 0;
    private int stuckTicks = 0;

    private boolean hitSent = false;
    private boolean sneakingForPlace = false;
    private int pickSlot = -1; // cached pickaxe slot for the cycle

    public EChestMiner(HighwayBuilder module) {
        this.module = module;
    }

    private boolean isInsta() {
        return module.echestMineMode.get() == EChestMineMode.Insta;
    }

    private boolean isSilent() {
        return module.echestSwapMode.get() == EChestSwapMode.Silent;
    }

    public boolean isActive() {
        return state != State.IDLE;
    }

    public State getState() {
        return state;
    }

    public void tick() {
        if (mc.player == null || mc.world == null) return;

        if (tickDelay > 0) {
            tickDelay--;
            return;
        }

        int maxChain = isInsta() ? 10 : 1;
        for (int i = 0; i < maxChain; i++) {
            State before = state;
            switch (state) {
                case IDLE -> checkShouldStart();
                case SWAP_TO_ECHEST -> doSwapToEchest();
                case PLACE_ECHEST -> doPlaceEchest();
                case SWAP_TO_PICK -> doSwapToPick();
                case MINE_HIT -> doMineHit();
                case WAIT_BREAK -> doWaitBreak();
                case COLLECTING -> doCollecting();
            }
            if (state == before || tickDelay > 0
                || state == State.IDLE || state == State.WAIT_BREAK
                || state == State.COLLECTING) break;
        }
    }

    // ── IDLE ────────────────────────────────────────────────────────────

    private void checkShouldStart() {
        if (mc.player == null) return;
        if (module.getMaterial() != Blocks.OBSIDIAN) return;

        double distToBuild = mc.player.getPos().distanceTo(
            Vec3d.ofCenter(module.pathfinder.currentBlockPos));
        if (distToBuild > 1.5) return;

        if (hasNearbyObsidian()) {
            goToCollecting();
            return;
        }

        FindItemResult echest = InvUtils.find(Items.ENDER_CHEST);
        if (!echest.found()) {
            if (module.storageManagement.get()
                && module.containerHandler.containerTask.taskState == TaskState.DONE
                && module.containerHandler.findShulkerWithItem(Items.ENDER_CHEST) != -1) {
                module.containerHandler.handleRestock(Items.ENDER_CHEST);
            }
            return;
        }

        int currentObsidian = countItem(Items.OBSIDIAN);
        if (currentObsidian > module.saveMaterial.get()) return;

        int freeSpace = countFreeObsidianSpace();
        if (freeSpace < 8) return;

        int chestsNeeded = (int) Math.ceil(freeSpace / 8.0);
        chestsRemaining = Math.min(chestsNeeded, echest.count());
        if (chestsRemaining <= 0) return;

        actionPos = findAdjacentPlacePos();
        if (actionPos == null) return;

        // Cache pickaxe slot for the whole cycle
        pickSlot = findBestPickSlot();
        if (pickSlot == -1) return;

        stuckTicks = 0;

        if (isSilent()) {
            // Silent: skip SWAP_TO_ECHEST, go directly to PLACE_ECHEST
            state = State.PLACE_ECHEST;
        } else {
            state = State.SWAP_TO_ECHEST;
        }
    }

    // ── SWAP_TO_ECHEST (Normal mode only) ────────────────────────────────

    private void doSwapToEchest() {
        if (mc.player == null) { reset(); return; }

        if (mc.world != null && mc.world.getBlockState(actionPos).getBlock() == Blocks.ENDER_CHEST) {
            stuckTicks++;
            if (stuckTicks > 40) { goToCollecting(); return; }
            return;
        }

        FindItemResult echest = InvUtils.findInHotbar(Items.ENDER_CHEST);
        if (!echest.found()) {
            FindItemResult invEchest = InvUtils.find(Items.ENDER_CHEST);
            if (!invEchest.found()) { goToCollecting(); return; }
            InvUtils.move().from(invEchest.slot()).toHotbar(findSafeHotbarSlot());
            tickDelay = isInsta() ? 0 : 1;
            return;
        }

        InvUtils.swap(echest.slot(), false);
        state = State.PLACE_ECHEST;
        stuckTicks = 0;
    }

    // ── PLACE_ECHEST ────────────────────────────────────────────────────

    private void doPlaceEchest() {
        if (mc.player == null || mc.world == null || mc.interactionManager == null) { reset(); return; }

        // Already placed?
        if (mc.world.getBlockState(actionPos).getBlock() == Blocks.ENDER_CHEST) {
            if (isSilent()) {
                // Go directly to mining, no separate swap state
                state = State.MINE_HIT;
            } else {
                state = State.SWAP_TO_PICK;
            }
            return;
        }

        // Position blocked
        if (!mc.world.getBlockState(actionPos).isAir()
            && !mc.world.getBlockState(actionPos).isReplaceable()) {
            stuckTicks++;
            if (stuckTicks > 20) { goToCollecting(); return; }
            return;
        }

        Direction supportDir = findSupportSide(actionPos);
        if (supportDir == null) { goToCollecting(); return; }

        BlockPos supportPos = actionPos.offset(supportDir);
        Vec3d hitVec = Vec3d.ofCenter(supportPos).add(
            supportDir.getOpposite().getOffsetX() * 0.5,
            supportDir.getOpposite().getOffsetY() * 0.5,
            supportDir.getOpposite().getOffsetZ() * 0.5
        );

        if (module.rotate.get()) {
            Rotations.rotate(Rotations.getYaw(hitVec), Rotations.getPitch(hitVec));
        }

        mc.player.setSneaking(true);

        if (!isInsta() && !isSilent() && !sneakingForPlace) {
            sneakingForPlace = true;
            tickDelay = 1;
            return;
        }

        if (isSilent()) {
            // Silent: find EC, swap → place → swap back, all in one tick
            FindItemResult echest = InvUtils.find(Items.ENDER_CHEST);
            if (!echest.found()) { mc.player.setSneaking(false); goToCollecting(); return; }
            int prevSlot = mc.player.getInventory().getSelectedSlot();
            if (echest.isHotbar()) {
                InvUtils.swap(echest.slot(), false);
            } else {
                InvUtils.move().from(echest.slot()).toHotbar(prevSlot);
            }

            BlockHitResult hitResult = new BlockHitResult(hitVec, supportDir.getOpposite(), supportPos, false);
            mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hitResult);
            mc.player.swingHand(Hand.MAIN_HAND);

            // Swap back
            InvUtils.swap(prevSlot, false);
        } else {
            // Normal: EC is already in hand from SWAP_TO_ECHEST
            BlockHitResult hitResult = new BlockHitResult(hitVec, supportDir.getOpposite(), supportPos, false);
            ActionResult result = mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hitResult);
            if (result.isAccepted()) mc.player.swingHand(Hand.MAIN_HAND);
        }

        mc.player.setSneaking(false);
        sneakingForPlace = false;

        if (isSilent()) {
            // Silent: skip SWAP_TO_PICK, go directly to MINE_HIT
            state = State.MINE_HIT;
        } else {
            state = State.SWAP_TO_PICK;
        }
        stuckTicks = 0;
    }

    // ── SWAP_TO_PICK (Normal mode only) ──────────────────────────────────

    private void doSwapToPick() {
        if (mc.player == null) { reset(); return; }

        // Wait for EC to be placed
        if (mc.world != null && mc.world.getBlockState(actionPos).getBlock() != Blocks.ENDER_CHEST) {
            stuckTicks++;
            if (stuckTicks > 20) {
                state = State.SWAP_TO_ECHEST;
                stuckTicks = 0;
            }
            return;
        }

        if (pickSlot == -1) {
            pickSlot = findBestPickSlot();
            if (pickSlot == -1) {
                module.disableWithError("No pickaxe found for mining ender chests.");
                return;
            }
        }

        if (pickSlot < 9) {
            InvUtils.swap(pickSlot, false);
        } else {
            InvUtils.move().from(pickSlot).toHotbar(mc.player.getInventory().getSelectedSlot());
            tickDelay = isInsta() ? 0 : 1;
        }

        state = State.MINE_HIT;
        stuckTicks = 0;
    }

    // ── MINE_HIT ────────────────────────────────────────────────────────

    private void doMineHit() {
        if (mc.player == null || mc.world == null || mc.interactionManager == null) { reset(); return; }

        Block currentBlock = mc.world.getBlockState(actionPos).getBlock();

        if (currentBlock != Blocks.ENDER_CHEST) {
            onEchestBroken();
            return;
        }

        Direction side = HWUtils.getMiningSide(actionPos);
        if (side == null) side = Direction.UP;

        Vec3d hitVec = Vec3d.ofCenter(actionPos);
        if (module.rotate.get()) {
            Rotations.rotate(Rotations.getYaw(hitVec), Rotations.getPitch(hitVec));
        }

        if (isInsta()) {
            if (!hitSent) {
                if (isSilent()) {
                    // Silent: swap pick → attack → swap back
                    int prevSlot = mc.player.getInventory().getSelectedSlot();
                    swapToPickSilent();
                    mc.interactionManager.attackBlock(actionPos, side);
                    mc.player.swingHand(Hand.MAIN_HAND);
                    InvUtils.swap(prevSlot, false);
                } else {
                    mc.interactionManager.attackBlock(actionPos, side);
                    mc.player.swingHand(Hand.MAIN_HAND);
                }
                hitSent = true;
            }
            state = State.WAIT_BREAK;
            stuckTicks = 0;
        } else {
            if (isSilent()) {
                int prevSlot = mc.player.getInventory().getSelectedSlot();
                swapToPickSilent();
                mc.interactionManager.attackBlock(actionPos, side);
                mc.player.swingHand(Hand.MAIN_HAND);
                InvUtils.swap(prevSlot, false);
            } else {
                mc.interactionManager.attackBlock(actionPos, side);
                mc.player.swingHand(Hand.MAIN_HAND);
            }

            stuckTicks++;
            if (stuckTicks > 200) { goToCollecting(); return; }

            if (mc.world.getBlockState(actionPos).getBlock() != Blocks.ENDER_CHEST) {
                onEchestBroken();
            }
        }
    }

    // ── WAIT_BREAK ──────────────────────────────────────────────────────

    private void doWaitBreak() {
        if (mc.world == null) { reset(); return; }

        if (mc.world.getBlockState(actionPos).getBlock() != Blocks.ENDER_CHEST) {
            onEchestBroken();
            return;
        }

        stuckTicks++;
        if (stuckTicks > 100) {
            state = State.MINE_HIT;
            hitSent = false;
            stuckTicks = 0;
        }
    }

    // ── Called when EC is confirmed broken ───────────────────────────────

    private void onEchestBroken() {
        chestsRemaining--;
        if (!isInsta()) hitSent = false;
        stuckTicks = 0;

        if (chestsRemaining <= 0) {
            goToCollecting();
            return;
        }

        FindItemResult echest = InvUtils.find(Items.ENDER_CHEST);
        if (echest.found()) {
            if (isSilent()) {
                // Silent: skip SWAP_TO_ECHEST, go directly to placement
                state = State.PLACE_ECHEST;
            } else {
                state = State.SWAP_TO_ECHEST;
                tickDelay = isInsta() ? 0 : 1;
            }
            return;
        }

        goToCollecting();
    }

    // ── COLLECTING ──────────────────────────────────────────────────────

    private void doCollecting() {
        if (mc.player == null || mc.world == null) { reset(); return; }

        // No obsidian left on ground — done collecting
        if (!hasNearbyObsidian()) {
            module.pathfinder.stopPickup();
            reset();
            return;
        }

        // Baritone's FollowProcess handles all pathing automatically.
        // Just wait and check for timeout.
        stuckTicks++;
        if (stuckTicks > 200) {
            module.pathfinder.stopPickup();
            reset();
        }
    }

    // ── Transitions ─────────────────────────────────────────────────────

    private void goToCollecting() {
        state = State.COLLECTING;
        tickDelay = 0;
        stuckTicks = 0;
        chestsRemaining = 0;
        hitSent = false;

        // Start Baritone's FollowProcess to pick up obsidian on the ground
        module.pathfinder.startPickup(stack -> stack.getItem() == Items.OBSIDIAN);
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private void swapToPickSilent() {
        if (pickSlot == -1) pickSlot = findBestPickSlot();
        if (pickSlot == -1) return;
        if (pickSlot < 9) {
            InvUtils.swap(pickSlot, false);
        } else {
            InvUtils.move().from(pickSlot).toHotbar(mc.player.getInventory().getSelectedSlot());
        }
    }

    private int findBestPickSlot() {
        if (mc.player == null) return -1;
        int bestSlot = -1;
        float bestSpeed = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (!stack.isIn(ItemTags.PICKAXES)) continue;
            float speed = stack.getMiningSpeedMultiplier(Blocks.ENDER_CHEST.getDefaultState());
            if (speed > bestSpeed) {
                bestSpeed = speed;
                bestSlot = i;
            }
        }
        return bestSlot;
    }

    private boolean hasNearbyObsidian() {
        return findClosestObsidian() != null;
    }

    private ItemEntity findClosestObsidian() {
        if (mc.player == null || mc.world == null) return null;
        Box searchBox = mc.player.getBoundingBox().expand(16.0);
        ItemEntity closest = null;
        double closestDist = Double.MAX_VALUE;
        for (var entity : mc.world.getEntities()) {
            if (!(entity instanceof ItemEntity itemEntity)) continue;
            if (itemEntity.getStack().getItem() != Items.OBSIDIAN) continue;
            if (!searchBox.contains(itemEntity.getPos())) continue;
            double d = mc.player.squaredDistanceTo(itemEntity);
            if (d < closestDist) {
                closestDist = d;
                closest = itemEntity;
            }
        }
        return closest;
    }

    private int countGroundObsidian() {
        if (mc.player == null || mc.world == null) return 0;
        Box searchBox = mc.player.getBoundingBox().expand(16.0);
        int count = 0;
        for (var entity : mc.world.getEntities()) {
            if (!(entity instanceof ItemEntity itemEntity)) continue;
            if (itemEntity.getStack().getItem() != Items.OBSIDIAN) continue;
            if (!searchBox.contains(itemEntity.getPos())) continue;
            count += itemEntity.getStack().getCount();
        }
        return count;
    }

    private BlockPos findAdjacentPlacePos() {
        if (mc.player == null || mc.world == null) return null;
        BlockPos playerPos = mc.player.getBlockPos();
        double maxReach = module.maxReach.get();
        HWDirection hwDir = module.pathfinder.startingDirection;
        if (hwDir != null) {
            BlockPos behind = playerPos.add(
                -hwDir.directionVec.getX(), 0, -hwDir.directionVec.getZ());
            if (isValidPlacePos(behind, maxReach)) return behind;
        }
        for (Direction dir : Direction.Type.HORIZONTAL) {
            BlockPos pos = playerPos.offset(dir);
            if (isValidPlacePos(pos, maxReach)) return pos;
        }
        return null;
    }

    private boolean isValidPlacePos(BlockPos pos, double maxReach) {
        if (mc.world == null || mc.player == null) return false;
        if (!mc.world.getBlockState(pos).isAir()
            && !mc.world.getBlockState(pos).isReplaceable()) return false;
        BlockPos below = pos.down();
        if (mc.world.getBlockState(below).isAir()
            || mc.world.getBlockState(below).isReplaceable()
            || !mc.world.getFluidState(below).isEmpty()) return false;
        if (findSupportSide(pos) == null) return false;
        double dist = mc.player.getEyePos().distanceTo(Vec3d.ofCenter(pos));
        return dist <= maxReach;
    }

    private Direction findSupportSide(BlockPos pos) {
        if (mc.world == null) return null;
        BlockPos below = pos.down();
        if (!mc.world.getBlockState(below).isAir() && !mc.world.getBlockState(below).isReplaceable()) {
            return Direction.DOWN;
        }
        for (Direction dir : Direction.values()) {
            if (dir == Direction.DOWN) continue;
            BlockPos neighbor = pos.offset(dir);
            if (!mc.world.getBlockState(neighbor).isAir()
                && !mc.world.getBlockState(neighbor).isReplaceable()) {
                return dir;
            }
        }
        return null;
    }

    private int countItem(net.minecraft.item.Item item) {
        if (mc.player == null) return 0;
        int count = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.getItem() == item) count += stack.getCount();
        }
        return count;
    }

    private int countFreeObsidianSpace() {
        if (mc.player == null) return 0;
        int space = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.isEmpty()) {
                space += 64;
            } else if (stack.getItem() == Items.OBSIDIAN) {
                space += stack.getMaxCount() - stack.getCount();
            }
        }
        space -= countGroundObsidian();
        return Math.max(0, space);
    }

    private int findSafeHotbarSlot() {
        if (mc.player == null) return 0;
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getStack(i).isEmpty()) return i;
        }
        return 8;
    }

    public void reset() {
        state = State.IDLE;
        actionPos = null;
        chestsRemaining = 0;
        tickDelay = 0;
        stuckTicks = 0;
        hitSent = false;
        sneakingForPlace = false;
        pickSlot = -1;
        module.pathfinder.clearMinerGoal();
        if (module.pathfinder.isPickupActive()) {
            module.pathfinder.stopPickup();
        }
    }
}
