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
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

/**
 * EChest Miner — places and breaks ender chests to farm obsidian.
 *
 * Flow: IDLE → SWAP_TO_ECHEST → PLACE_ECHEST → SWAP_TO_PICK → MINE_HIT → WAIT_BREAK
 *       → (loop until chestsRemaining == 0) → COLLECTING → IDLE
 *
 * No centering — places at the nearest valid adjacent block from current position.
 * Pre-calculates how many ECs to break, breaks ALL, THEN collects all dropped obsidian.
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
    private int savedSlot = -1;
    private boolean hitSent = false;
    private boolean sneakingForPlace = false;

    public EChestMiner(HighwayBuilder module) {
        this.module = module;
    }

    private boolean isInsta() {
        return module.echestMineMode.get() == EChestMineMode.Insta;
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

        // In Insta mode: chain multiple states per tick
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

        // Don't start until player is AT the build area
        double distToBuild = mc.player.getPos().distanceTo(
            Vec3d.ofCenter(module.pathfinder.currentBlockPos));
        if (distToBuild > 1.5) return;

        // Check if there are dropped obsidian items nearby — collect them first
        if (hasNearbyObsidian()) {
            state = State.COLLECTING;
            stuckTicks = 0;
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

        int freeSlots = countEmptySlots();
        if (freeSlots <= 0) return; // Inventory full — nothing to fill

        // Pre-calculate: each EC gives 8 obsidian, each slot holds 64
        // We need enough ECs to fill free slots: freeSlots * 8 ECs (64/8 = 8 ECs per slot)
        int chestsNeeded = freeSlots * 8;
        chestsRemaining = Math.min(chestsNeeded, echest.count());
        if (chestsRemaining <= 0) return;

        // Find place position from current spot — no centering needed
        actionPos = findAdjacentPlacePos();
        if (actionPos == null) return;

        state = State.SWAP_TO_ECHEST;
        stuckTicks = 0;
        savedSlot = mc.player.getInventory().getSelectedSlot();
    }

    // ── SWAP_TO_ECHEST ──────────────────────────────────────────────────

    private void doSwapToEchest() {
        if (mc.player == null) { reset(); return; }

        // If the block is still there from previous cycle, wait
        if (mc.world != null && mc.world.getBlockState(actionPos).getBlock() == Blocks.ENDER_CHEST) {
            stuckTicks++;
            if (stuckTicks > 40) { goToCollecting(); return; }
            return;
        }

        FindItemResult echest = InvUtils.findInHotbar(Items.ENDER_CHEST);
        if (!echest.found()) {
            FindItemResult invEchest = InvUtils.find(Items.ENDER_CHEST);
            if (!invEchest.found()) {
                // No more ECs — collect dropped obsidian
                goToCollecting();
                return;
            }
            InvUtils.move().from(invEchest.slot()).toHotbar(findSafeHotbarSlot());
            tickDelay = isInsta() ? 0 : 1;
            return;
        }

        savedSlot = mc.player.getInventory().getSelectedSlot();
        InvUtils.swap(echest.slot(), false);

        state = State.PLACE_ECHEST;
        stuckTicks = 0;
    }

    // ── PLACE_ECHEST ────────────────────────────────────────────────────

    private void doPlaceEchest() {
        if (mc.player == null || mc.world == null || mc.getNetworkHandler() == null) { reset(); return; }

        // Already placed?
        if (mc.world.getBlockState(actionPos).getBlock() == Blocks.ENDER_CHEST) {
            swapBackIfSilent();
            state = State.SWAP_TO_PICK;
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

        // Sneak to prevent opening GUI
        mc.player.setSneaking(true);

        if (!isInsta() && !sneakingForPlace) {
            sneakingForPlace = true;
            tickDelay = 1;
            return;
        }

        // Send place packet
        BlockHitResult hitResult = new BlockHitResult(hitVec, supportDir.getOpposite(), supportPos, false);
        mc.getNetworkHandler().sendPacket(new PlayerInteractBlockC2SPacket(Hand.MAIN_HAND, hitResult, 0));
        mc.player.swingHand(Hand.MAIN_HAND);

        mc.player.setSneaking(false);
        sneakingForPlace = false;
        swapBackIfSilent();

        // Skip WAIT_ECHEST_PLACED — go directly to mining, check block in SWAP_TO_PICK/MINE_HIT
        state = State.SWAP_TO_PICK;
        tickDelay = isInsta() ? 0 : 2;
        stuckTicks = 0;
    }

    // ── SWAP_TO_PICK ────────────────────────────────────────────────────

    private void doSwapToPick() {
        if (mc.player == null) { reset(); return; }

        // Wait for EC to actually be placed before mining
        if (mc.world != null && mc.world.getBlockState(actionPos).getBlock() != Blocks.ENDER_CHEST) {
            stuckTicks++;
            if (stuckTicks > 30) {
                // Placement failed — retry
                state = State.SWAP_TO_ECHEST;
                stuckTicks = 0;
            }
            return;
        }

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

        if (bestSlot == -1) {
            module.disableWithError("No pickaxe found for mining ender chests.");
            return;
        }

        savedSlot = mc.player.getInventory().getSelectedSlot();

        if (bestSlot < 9) {
            InvUtils.swap(bestSlot, false);
        } else {
            InvUtils.move().from(bestSlot).toHotbar(mc.player.getInventory().getSelectedSlot());
            tickDelay = isInsta() ? 0 : 1;
        }

        state = State.MINE_HIT;
        hitSent = false;
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
                mc.interactionManager.attackBlock(actionPos, side);
                mc.player.swingHand(Hand.MAIN_HAND);
                hitSent = true;
            }
            state = State.WAIT_BREAK;
            stuckTicks = 0;
        } else {
            mc.interactionManager.attackBlock(actionPos, side);
            mc.player.swingHand(Hand.MAIN_HAND);

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
        swapBackIfSilent();
        chestsRemaining--;
        hitSent = false;
        stuckTicks = 0;

        // Don't check inventory space here — obsidian is on the ground
        // Just keep breaking until chestsRemaining reaches 0
        if (chestsRemaining > 0) {
            FindItemResult echest = InvUtils.find(Items.ENDER_CHEST);
            if (echest.found()) {
                // Place next EC at the SAME position
                state = State.SWAP_TO_ECHEST;
                tickDelay = isInsta() ? 0 : 1;
                return;
            }
        }

        // All ECs broken (or ran out) — now collect all dropped obsidian
        goToCollecting();
    }

    // ── COLLECTING ──────────────────────────────────────────────────────
    // After all ECs are broken, walk to pick up all dropped obsidian.

    private void doCollecting() {
        if (mc.player == null || mc.world == null) { reset(); return; }

        ItemEntity closest = findClosestObsidian();

        if (closest == null) {
            // No more obsidian on ground — done
            module.pathfinder.clearMinerGoal();
            reset();
            return;
        }

        double dist = mc.player.squaredDistanceTo(closest);

        if (dist < 2.0 * 2.0) {
            // Within pickup range — wait for auto-pickup
            module.pathfinder.clearMinerGoal();
            stuckTicks++;
            if (stuckTicks > 60) {
                module.pathfinder.clearMinerGoal();
                reset();
                return;
            }
            return;
        }

        // Walk toward the obsidian via Baritone
        BlockPos itemBlockPos = closest.getBlockPos();
        module.pathfinder.setMinerGoal(itemBlockPos);

        stuckTicks++;
        if (stuckTicks > 120) {
            module.pathfinder.clearMinerGoal();
            reset();
        }
    }

    // ── Helper: transition to COLLECTING ─────────────────────────────────

    private void goToCollecting() {
        state = State.COLLECTING;
        tickDelay = 0;
        stuckTicks = 0;
        chestsRemaining = 0;
        hitSent = false;
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private boolean hasNearbyObsidian() {
        return findClosestObsidian() != null;
    }

    private ItemEntity findClosestObsidian() {
        if (mc.player == null || mc.world == null) return null;

        Box searchBox = mc.player.getBoundingBox().expand(8.0);
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

    private BlockPos findAdjacentPlacePos() {
        if (mc.player == null || mc.world == null) return null;

        BlockPos playerPos = mc.player.getBlockPos();
        double maxReach = module.maxReach.get();

        HWDirection hwDir = module.pathfinder.startingDirection;

        // 1) Directly behind player (opposite of build direction)
        if (hwDir != null) {
            BlockPos behind = playerPos.add(
                -hwDir.directionVec.getX(), 0, -hwDir.directionVec.getZ());
            if (isValidPlacePos(behind, maxReach)) return behind;
        }

        // 2) Any adjacent horizontal
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

    private void swapBackIfSilent() {
        if (module.echestSwapMode.get() == EChestSwapMode.Silent && savedSlot >= 0) {
            InvUtils.swap(savedSlot, false);
        }
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

    private int countEmptySlots() {
        if (mc.player == null) return 0;
        int count = 0;
        for (int i = 0; i < 36; i++) {
            if (mc.player.getInventory().getStack(i).isEmpty()) count++;
        }
        return count;
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
        savedSlot = -1;
        hitSent = false;
        sneakingForPlace = false;
        module.pathfinder.clearMinerGoal();
    }
}
