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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * EChest Miner — places and breaks ender chests to farm obsidian.
 *
 * Silent mode: swap+action+swapback in one tick, no separate SWAP states.
 * Normal mode: separate SWAP_TO_ECHEST / SWAP_TO_PICK states.
 * Insta mode: one attackBlock, no re-hits. interactBlock for instant placement.
 */
public class EChestMiner {
    private static final MinecraftClient mc = MinecraftClient.getInstance();
    private static final int MIN_ECHEST_RESERVE = 16;

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
    private boolean echestPlaced = false;
    private boolean sneakingForPlace = false;
    private int pickSlot = -1; // cached pickaxe slot for the cycle
    private BlockPos collectionCenter = null;
    private BlockPos standPos = null;
    private boolean refillToCapacity = false;
    private final List<BlockPos> farmCenters = new ArrayList<>();
    private static final double COLLECTION_RADIUS = 6.0;
    private static final double FALLBACK_COLLECTION_RADIUS = 24.0;
    private static final double ACTION_TOLERANCE = 0.10;
    private static final double STAND_EDGE_PADDING = 0.04;
    private static final double STAND_BIAS_AWAY = 0.08;
    private static final double STAND_ACTION_CLEARANCE = 0.03;
    private static final double MANUAL_CENTER_RANGE = 3.5;
    private static final int POSITION_STUCK_TICKS = 20;
    private static final int SELF_BLOCK_RELOCATE_TICKS = 6;
    private static final int FAR_PLACEMENT_DISTANCE = 2;
    private static final double MOVE_EPSILON_SQ = 1.0e-4;
    private static final int MINING_POSITION_RECOVER_TICKS = 12;

    private Vec3d lastEnsurePos = null;
    private int ensureNoMoveTicks = 0;
    private int miningAccessStuckTicks = 0;
    private int selfBlockTicks = 0;

    public EChestMiner(HighwayBuilder module) {
        this.module = module;
    }

    private boolean isInsta() {
        return module.echestMineMode.get() == EChestMineMode.Insta;
    }

    private boolean isContainerSilent() {
        return module.swapMode.get() == EChestSwapMode.Silent;
    }

    private boolean isPickSilent() {
        return module.pickaxeSwapMode.get() == ToolSwapMode.Silent;
    }

    public boolean isActive() {
        return state != State.IDLE;
    }

    public State getState() {
        return state;
    }

    public void tick() {
        if (mc.player == null || mc.world == null) return;

        // Never run EChest mining while shulker restock cycle is active/open.
        if (module.containerHandler != null) {
            BlockTask containerTask = module.containerHandler.containerTask;
            if (containerTask != null && containerTask.taskState != TaskState.DONE) {
                if (state != State.IDLE) reset(false);
                return;
            }
        }
        if (mc.player.currentScreenHandler != null && mc.player.currentScreenHandler.syncId != 0) {
            if (state != State.IDLE) reset(false);
            return;
        }

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

        if (module.containerHandler != null) {
            BlockTask containerTask = module.containerHandler.containerTask;
            if (containerTask != null && containerTask.taskState != TaskState.DONE) return;
        }
        if (mc.player.currentScreenHandler != null && mc.player.currentScreenHandler.syncId != 0) return;

        int missingObsidian = countMissingObsidianForRefill();
        boolean refillContinuation = refillToCapacity && missingObsidian >= 8;

        double distToBuild = mc.player.getPos().distanceTo(
            Vec3d.ofCenter(module.pathfinder.currentBlockPos));
        // During ongoing refill continuation (after taking extra ender chests),
        // start immediately from current position instead of detouring into
        // normal build tick loop first.
        if (!refillContinuation && distToBuild > 1.5) return;

        // Hard priority: never start or request ender-chest cycle without a pickaxe.
        // If none is available, this call triggers Fortune pickaxe restock first.
        if (!ensurePickaxeReadyForMining()) return;

        if (missingObsidian < 8) {
            refillToCapacity = false;
            return;
        }

        int currentObsidian = countItem(Items.OBSIDIAN);
        if (!refillToCapacity) {
            if (currentObsidian > module.saveMaterial.get()) return;
            refillToCapacity = true;
        }

        FindItemResult echest = InvUtils.find(Items.ENDER_CHEST);
        if (!echest.found()) {
            if (tryRequestEchestRestock()) return;
            refillToCapacity = false;
            return;
        }

        int chestsNeeded = (int) Math.ceil(missingObsidian / 8.0);
        int availableToMine = echest.count() - MIN_ECHEST_RESERVE;
        chestsRemaining = Math.min(chestsNeeded, Math.max(0, availableToMine));
        if (chestsRemaining <= 0) {
            if (tryRequestEchestRestock()) return;
            refillToCapacity = false;
            return;
        }

        actionPos = findAdjacentPlacePos();
        if (actionPos == null) return;
        standPos = findStandPos(actionPos);

        stuckTicks = 0;
        echestPlaced = false;

        if (isContainerSilent()) {
            // Silent: skip SWAP_TO_ECHEST, go directly to PLACE_ECHEST
            state = State.PLACE_ECHEST;
        } else {
            state = State.SWAP_TO_ECHEST;
        }
    }

    // ── SWAP_TO_ECHEST (Normal mode only) ────────────────────────────────

    private void doSwapToEchest() {
        if (mc.player == null) { reset(); return; }
        if (!ensureActionPosition()) return;

        if (mc.world != null && mc.world.getBlockState(actionPos).getBlock() == Blocks.ENDER_CHEST) {
            echestPlaced = true;
            stuckTicks = 0;
            if (isPickSilent()) state = State.MINE_HIT;
            else state = State.SWAP_TO_PICK;
            return;
        }

        FindItemResult echest = InvUtils.findInHotbar(Items.ENDER_CHEST);
        if (!echest.found()) {
            FindItemResult invEchest = InvUtils.find(Items.ENDER_CHEST);
            if (!invEchest.found()) { goToCollecting(); return; }
            int safeSlot = findSafeHotbarSlot();
            if (safeSlot == -1) { goToCollecting(); return; }
            InvUtils.move().from(invEchest.slot()).toHotbar(safeSlot);
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
        if (!ensureActionPosition()) return;

        if (mc.player.getBoundingBox().intersects(new Box(actionPos))) {
            if (!tryRelocateActionPos()) {
                nudgeAwayFromActionPos();
                stuckTicks++;
                if (stuckTicks > SELF_BLOCK_RELOCATE_TICKS) {
                    reselectActionPosition(actionPos);
                    stuckTicks = 0;
                }
                tickDelay = 1;
            }
            return;
        }

        // Already placed?
        if (mc.world.getBlockState(actionPos).getBlock() == Blocks.ENDER_CHEST) {
            echestPlaced = true;
            if (isPickSilent()) {
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
            if (tryRelocateActionPos()) return;
            stuckTicks++;
            if (stuckTicks > 20) {
                actionPos = findAdjacentPlacePos(actionPos);
                standPos = actionPos != null ? findStandPos(actionPos) : null;
                stuckTicks = 0;
                if (actionPos == null) { goToCollecting(); return; }
            }
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

        if (!isInsta() && !isContainerSilent() && !sneakingForPlace) {
            sneakingForPlace = true;
            tickDelay = 1;
            return;
        }

        if (isContainerSilent()) {
            // Silent: find EC, swap → place → swap back, all in one tick
            FindItemResult echest = InvUtils.find(Items.ENDER_CHEST);
            if (!echest.found()) { mc.player.setSneaking(false); goToCollecting(); return; }
            int prevSlot = mc.player.getInventory().getSelectedSlot();
            if (echest.isHotbar()) {
                InvUtils.swap(echest.slot(), false);
            } else {
                int safeSlot = findSafeHotbarSlot();
                if (safeSlot == -1) { mc.player.setSneaking(false); goToCollecting(); return; }
                InvUtils.move().from(echest.slot()).toHotbar(safeSlot);
                InvUtils.swap(safeSlot, false);
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

        // Do not advance until placement is actually confirmed.
        if (mc.world.getBlockState(actionPos).getBlock() == Blocks.ENDER_CHEST) {
            echestPlaced = true;
            if (isPickSilent()) {
                // Silent: skip SWAP_TO_PICK, go directly to MINE_HIT
                state = State.MINE_HIT;
            } else {
                state = State.SWAP_TO_PICK;
            }
            stuckTicks = 0;
            return;
        }

        stuckTicks++;
        if (stuckTicks > 40) {
            if (tryRelocateActionPos()) return;
            actionPos = findAdjacentPlacePos(actionPos);
            standPos = actionPos != null ? findStandPos(actionPos) : null;
            stuckTicks = 0;
            if (actionPos == null) { goToCollecting(); return; }
        }
        tickDelay = 1;
    }

    // ── SWAP_TO_PICK (Normal mode only) ──────────────────────────────────

    private void doSwapToPick() {
        if (mc.player == null) { reset(); return; }
        if (!ensureActionPosition()) return;

        // Wait for EC to be placed
        if (mc.world != null && mc.world.getBlockState(actionPos).getBlock() != Blocks.ENDER_CHEST) {
            stuckTicks++;
            if (stuckTicks > 20) state = State.PLACE_ECHEST;
            return;
        }
        echestPlaced = true;

        if (!ensurePickEquippedForMining()) return;
        if (tickDelay == 0 && !isInsta()) tickDelay = 1;

        state = State.MINE_HIT;
        stuckTicks = 0;
    }

    // ── MINE_HIT ────────────────────────────────────────────────────────

    private void doMineHit() {
        if (mc.player == null || mc.world == null || mc.interactionManager == null) { reset(); return; }
        if (!ensureMiningAccess()) return;

        Block currentBlock = mc.world.getBlockState(actionPos).getBlock();

        if (currentBlock != Blocks.ENDER_CHEST) {
            if (!echestPlaced) {
                state = State.PLACE_ECHEST;
                stuckTicks++;
                if (stuckTicks > 30) {
                    if (!tryRelocateActionPos()) {
                        actionPos = findAdjacentPlacePos(actionPos);
                        standPos = actionPos != null ? findStandPos(actionPos) : null;
                    }
                    stuckTicks = 0;
                    if (actionPos == null) goToCollecting();
                }
                return;
            }
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
                if (isPickSilent()) {
                    // Silent: swap pick → attack → swap back
                    int prevSlot = mc.player.getInventory().getSelectedSlot();
                    if (!swapToPickSilent()) return;
                    mc.interactionManager.attackBlock(actionPos, side);
                    mc.player.swingHand(Hand.MAIN_HAND);
                    InvUtils.swap(prevSlot, false);
                } else {
                    if (!ensurePickEquippedForMining()) return;
                    mc.interactionManager.attackBlock(actionPos, side);
                    mc.player.swingHand(Hand.MAIN_HAND);
                }
                hitSent = true;
            }
            state = State.WAIT_BREAK;
            stuckTicks = 0;
        } else {
            if (isPickSilent()) {
                int prevSlot = mc.player.getInventory().getSelectedSlot();
                if (!swapToPickSilent()) return;
                mc.interactionManager.attackBlock(actionPos, side);
                mc.player.swingHand(Hand.MAIN_HAND);
                InvUtils.swap(prevSlot, false);
            } else {
                if (!ensurePickEquippedForMining()) return;
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
        if (!ensureMiningAccess()) return;

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
        if (echestPlaced && chestsRemaining > 0) chestsRemaining--;
        echestPlaced = false;
        if (!isInsta()) hitSent = false;
        stuckTicks = 0;
        collectionCenter = actionPos;
        trackFarmCenter(actionPos);

        // If pickaxe broke during mining, switch to pickaxe restock immediately
        // instead of continuing with chest/place phases for a few extra ticks.
        if (!ensurePickaxeReadyForMining()) return;

        if (chestsRemaining <= 0) {
            goToCollecting();
            return;
        }

        FindItemResult echest = InvUtils.find(Items.ENDER_CHEST);
        if (echest.found()) {
            if (isContainerSilent()) {
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

        int freeSpace = countFreeObsidianInventorySpace();
        if (freeSpace <= 0) {
            module.pathfinder.clearMinerGoal();
            reset();
            return;
        }

        ItemEntity closest = findClosestObsidian();

        // No obsidian left on ground — done collecting
        if (closest == null) {
            module.pathfinder.clearMinerGoal();
            int missingObsidian = countMissingObsidianForRefill();
            if (refillToCapacity && missingObsidian >= 8) {
                // Keep refilling in the same session until all convertible space is filled.
                if (countItem(Items.ENDER_CHEST) > MIN_ECHEST_RESERVE || tryRequestEchestRestock()) {
                    reset(false);
                    return;
                }
            }
            refillToCapacity = false;
            reset();
            return;
        }

        module.pathfinder.setMinerGoal(closest.getBlockPos());

        // Wait while moving/collecting.
        stuckTicks++;
        if (stuckTicks > 200) {
            module.pathfinder.clearMinerGoal();
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
        collectionCenter = actionPos != null ? actionPos : mc.player != null ? mc.player.getBlockPos() : null;
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private boolean swapToPickSilent() {
        if (mc.player == null) return false;
        if (!ensurePickaxeReadyForMining()) return false;
        int selected = mc.player.getInventory().getSelectedSlot();
        if (pickSlot < 9) {
            InvUtils.swap(pickSlot, false);
        } else {
            InvUtils.move().from(pickSlot).toHotbar(selected);
            pickSlot = selected;
        }
        return mc.player.getMainHandStack().isIn(ItemTags.PICKAXES);
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

    private boolean ensurePickaxeReadyForMining() {
        if (mc.player == null) return false;

        if (pickSlot >= 0 && pickSlot < 36) {
            ItemStack cached = mc.player.getInventory().getStack(pickSlot);
            if (cached.isIn(ItemTags.PICKAXES)) return true;
            pickSlot = -1;
        }

        int bestSlot = findBestPickSlot();
        if (bestSlot != -1) {
            pickSlot = bestSlot;
            return true;
        }

        if (tryStartFortunePickaxeRestock()) return false;

        module.disableWithError("No pickaxe found for mining ender chests.");
        return false;
    }

    private boolean ensurePickEquippedForMining() {
        if (mc.player == null) return false;
        if (mc.player.getMainHandStack().isIn(ItemTags.PICKAXES)) return true;
        if (!ensurePickaxeReadyForMining()) return false;

        int selected = mc.player.getInventory().getSelectedSlot();
        if (pickSlot < 9) {
            InvUtils.swap(pickSlot, false);
        } else {
            InvUtils.move().from(pickSlot).toHotbar(selected);
            pickSlot = selected;
        }

        return mc.player.getMainHandStack().isIn(ItemTags.PICKAXES);
    }

    private boolean tryStartFortunePickaxeRestock() {
        if (!module.storageManagement.get() || module.containerHandler == null) return false;
        if (module.containerHandler.containerTask.taskState != TaskState.DONE) return false;
        if (module.containerHandler.findShulkerWithFortunePickaxe() == -1) return false;

        module.containerHandler.handleFortunePickaxeRestock();
        // Keep refill intent so after pickaxe restock the miner continues
        // filling empty slots with obsidian instead of stopping early.
        reset(false);
        return true;
    }

    private ItemEntity findClosestObsidian() {
        if (mc.player == null || mc.world == null) return null;
        Box searchBox = getObsidianSearchBox();
        ItemEntity closest = null;
        double closestDist = Double.MAX_VALUE;
        for (var entity : mc.world.getEntities()) {
            if (!(entity instanceof ItemEntity itemEntity)) continue;
            if (itemEntity.getStack().getItem() != Items.OBSIDIAN) continue;
            if (!searchBox.contains(itemEntity.getPos())) continue;
            if (!isInsideTrackedFarmArea(itemEntity.getPos())) continue;
            double d = itemEntity.getPos().squaredDistanceTo(mc.player.getPos());
            if (d < closestDist) {
                closestDist = d;
                closest = itemEntity;
            }
        }

        if (closest == null && !farmCenters.isEmpty()) {
            Vec3d playerPos = mc.player.getPos();
            Box fallback = new Box(playerPos, playerPos).expand(FALLBACK_COLLECTION_RADIUS);
            for (var entity : mc.world.getEntities()) {
                if (!(entity instanceof ItemEntity itemEntity)) continue;
                if (itemEntity.getStack().getItem() != Items.OBSIDIAN) continue;
                if (!fallback.contains(itemEntity.getPos())) continue;
                double d = itemEntity.getPos().squaredDistanceTo(playerPos);
                if (d < closestDist) {
                    closestDist = d;
                    closest = itemEntity;
                }
            }
        }
        return closest;
    }

    private int countGroundObsidian() {
        if (mc.player == null || mc.world == null) return 0;
        Box searchBox = getObsidianSearchBox();
        int count = 0;
        for (var entity : mc.world.getEntities()) {
            if (!(entity instanceof ItemEntity itemEntity)) continue;
            if (itemEntity.getStack().getItem() != Items.OBSIDIAN) continue;
            if (!searchBox.contains(itemEntity.getPos())) continue;
            if (!isInsideTrackedFarmArea(itemEntity.getPos())) continue;
            count += itemEntity.getStack().getCount();
        }
        return count;
    }

    private Box getObsidianSearchBox() {
        if (mc.player == null) return new Box(0, 0, 0, 0, 0, 0);

        if (!farmCenters.isEmpty()) {
            Box union = null;
            for (BlockPos centerPos : farmCenters) {
                Vec3d center = Vec3d.ofCenter(centerPos);
                Box around = new Box(center, center).expand(COLLECTION_RADIUS);
                union = union == null ? around : union.union(around);
            }
            if (union != null) return union.expand(1.0);
        }

        Vec3d center = collectionCenter != null ? Vec3d.ofCenter(collectionCenter) : mc.player.getPos();
        double radius = collectionCenter != null ? COLLECTION_RADIUS : FALLBACK_COLLECTION_RADIUS;
        return new Box(center, center).expand(radius);
    }

    private boolean isInsideTrackedFarmArea(Vec3d pos) {
        if (mc.player == null || pos == null) return false;

        if (farmCenters.isEmpty()) {
            Vec3d center = collectionCenter != null ? Vec3d.ofCenter(collectionCenter) : mc.player.getPos();
            double radius = collectionCenter != null ? COLLECTION_RADIUS : FALLBACK_COLLECTION_RADIUS;
            return pos.squaredDistanceTo(center) <= radius * radius;
        }

        double sq = COLLECTION_RADIUS * COLLECTION_RADIUS;
        for (BlockPos centerPos : farmCenters) {
            if (pos.squaredDistanceTo(Vec3d.ofCenter(centerPos)) <= sq) return true;
        }
        return false;
    }

    private void trackFarmCenter(BlockPos pos) {
        if (pos == null) return;
        if (farmCenters.stream().anyMatch(existing -> existing.equals(pos))) return;
        farmCenters.add(pos);
        while (farmCenters.size() > 16) {
            farmCenters.remove(0);
        }
    }

    private boolean tryRequestEchestRestock() {
        if (!module.storageManagement.get() || module.containerHandler == null) return false;
        // Never request EChest shulker while pickaxe is missing: restock pick first.
        if (findBestPickSlot() == -1) {
            if (module.containerHandler.containerTask.taskState != TaskState.DONE) return true;
            if (module.containerHandler.findShulkerWithFortunePickaxe() == -1) return false;
            module.containerHandler.handleFortunePickaxeRestock();
            return true;
        }
        if (module.containerHandler.containerTask.taskState != TaskState.DONE) return true;
        if (module.containerHandler.findShulkerWithItem(Items.ENDER_CHEST) == -1) return false;
        module.containerHandler.handleRestock(Items.ENDER_CHEST);
        return true;
    }

    private BlockPos findAdjacentPlacePos() {
        return findAdjacentPlacePos(null);
    }

    private BlockPos findAdjacentPlacePos(BlockPos avoidPos) {
        if (mc.player == null || mc.world == null) return null;
        BlockPos playerPos = mc.player.getBlockPos();
        BlockPos currentPos = module.pathfinder.currentBlockPos;
        double maxReach = module.maxReach.get();

        // Reuse current action position if still valid.
        if (actionPos != null
            && (avoidPos == null || !actionPos.equals(avoidPos))
            && isValidPlacePos(actionPos, maxReach)) return actionPos;

        HWDirection hwDir = module.pathfinder != null ? module.pathfinder.startingDirection : null;
        List<BlockPos> closeCandidates = new ArrayList<>();
        Set<BlockPos> closeUnique = new HashSet<>();
        addPlacementCandidates(closeCandidates, closeUnique, playerPos, hwDir, 1);
        if (!currentPos.equals(playerPos)) addPlacementCandidates(closeCandidates, closeUnique, currentPos, hwDir, 1);

        for (BlockPos pos : closeCandidates) {
            if (avoidPos != null && pos.equals(avoidPos)) continue;
            if (isValidPlacePos(pos, maxReach)) return pos;
        }

        // Fallback one block above (uneven floor cases) for close positions.
        for (BlockPos base : closeCandidates) {
            BlockPos pos = base.up();
            if (avoidPos != null && pos.equals(avoidPos)) continue;
            if (isValidPlacePos(pos, maxReach)) return pos;
        }

        // Secondary ring: try placement through one block gap if all close cells fail.
        List<BlockPos> farCandidates = new ArrayList<>();
        Set<BlockPos> farUnique = new HashSet<>();
        addPlacementCandidates(farCandidates, farUnique, playerPos, hwDir, FAR_PLACEMENT_DISTANCE);
        if (!currentPos.equals(playerPos)) addPlacementCandidates(farCandidates, farUnique, currentPos, hwDir, FAR_PLACEMENT_DISTANCE);

        for (BlockPos pos : farCandidates) {
            if (avoidPos != null && pos.equals(avoidPos)) continue;
            if (isValidPlacePos(pos, maxReach)) return pos;
        }

        for (BlockPos base : farCandidates) {
            BlockPos pos = base.up();
            if (avoidPos != null && pos.equals(avoidPos)) continue;
            if (isValidPlacePos(pos, maxReach)) return pos;
        }

        return null;
    }

    private void addPlacementCandidates(List<BlockPos> candidates, Set<BlockPos> unique, BlockPos anchor, HWDirection hwDir, int distance) {
        if (anchor == null || distance < 1) return;

        if (hwDir != null) {
            int fx = Integer.signum(hwDir.directionVec.getX());
            int fz = Integer.signum(hwDir.directionVec.getZ());
            int bx = -fx * distance;
            int bz = -fz * distance;

            HWDirection lateral = hwDir.lateralDirection();
            int sx = Integer.signum(lateral.directionVec.getX());
            int sz = Integer.signum(lateral.directionVec.getZ());
            int lx = sx * distance;
            int lz = sz * distance;

            if (bx != 0 || bz != 0) addPlacementCandidate(candidates, unique, anchor.add(bx, 0, bz));
            if (lx != 0 || lz != 0) {
                addPlacementCandidate(candidates, unique, anchor.add(lx, 0, lz));
                addPlacementCandidate(candidates, unique, anchor.add(-lx, 0, -lz));
            }
            if (lx != 0 || lz != 0) {
                addPlacementCandidate(candidates, unique, anchor.add(bx + lx, 0, bz + lz));
                addPlacementCandidate(candidates, unique, anchor.add(bx - lx, 0, bz - lz));
            }
        }

        for (Direction dir : Direction.Type.HORIZONTAL) {
            addPlacementCandidate(candidates, unique, anchor.offset(dir, distance));
        }
        addPlacementCandidate(candidates, unique, anchor.add(distance, 0, distance));
        addPlacementCandidate(candidates, unique, anchor.add(distance, 0, -distance));
        addPlacementCandidate(candidates, unique, anchor.add(-distance, 0, distance));
        addPlacementCandidate(candidates, unique, anchor.add(-distance, 0, -distance));
    }

    private void addPlacementCandidate(List<BlockPos> list, Set<BlockPos> unique, BlockPos pos) {
        if (pos == null) return;
        if (unique.add(pos)) list.add(pos);
    }

    private boolean tryRelocateActionPos() {
        BlockPos old = actionPos;
        BlockPos relocated = findAdjacentPlacePos(old);
        if (relocated == null || relocated.equals(old)) return false;
        actionPos = relocated;
        standPos = findStandPos(relocated);
        stuckTicks = 0;
        return true;
    }

    private void nudgeAwayFromActionPos() {
        if (mc.player == null || actionPos == null) return;

        Vec3d player = mc.player.getPos();
        Vec3d center = Vec3d.ofCenter(actionPos);
        double dx = player.x - center.x;
        double dz = player.z - center.z;
        if (dx * dx + dz * dz < 1.0e-4) {
            if (module.pathfinder != null) {
                dx = -module.pathfinder.startingDirection.directionVec.getX();
                dz = -module.pathfinder.startingDirection.directionVec.getZ();
            }
        }
        if (dx * dx + dz * dz < 1.0e-4) {
            dx = 1.0;
            dz = 0.0;
        }

        // Pull back to center of the current stand block to avoid getting stuck
        // on block seams where the hitbox clips multiple neighboring blocks.
        Vec3d selfCenter = Vec3d.ofCenter(mc.player.getBlockPos());
        dx += (selfCenter.x - player.x) * 0.90;
        dz += (selfCenter.z - player.z) * 0.90;

        if (standPos != null) {
            Vec3d standTarget = getSafeStandPoint(standPos, actionPos);
            dx += (standTarget.x - player.x) * 0.65;
            dz += (standTarget.z - player.z) * 0.65;
        }

        double len = Math.sqrt(dx * dx + dz * dz);
        dx /= len;
        dz /= len;

        double speed = Math.max(0.26, Math.min(0.46, module.moveSpeed.get() + 0.14));
        mc.player.setVelocity(dx * speed, mc.player.getVelocity().y, dz * speed);
    }

    private boolean isValidPlacePos(BlockPos pos, double maxReach) {
        if (mc.world == null || mc.player == null) return false;
        // Never attempt to place ender chest into player's current block.
        if (pos.equals(mc.player.getBlockPos())) return false;
        if (!mc.world.getBlockState(pos).isAir()
            && !mc.world.getBlockState(pos).isReplaceable()) return false;
        BlockPos below = pos.down();
        if (mc.world.getBlockState(below).isAir()
            || mc.world.getBlockState(below).isReplaceable()
            || !mc.world.getFluidState(below).isEmpty()) return false;
        // Ignore current self-overlap: miner will reposition to stand pos before place.
        if (!mc.world.getOtherEntities(
            null,
            new Box(pos),
            entity -> entity != mc.player && !(entity instanceof ItemEntity)
        ).isEmpty()) return false;
        if (findSupportSide(pos) == null) return false;
        double dist = mc.player.getEyePos().distanceTo(Vec3d.ofCenter(pos));
        if (dist > maxReach + 1.0) return false;
        return canOperateFromCurrentPos(pos) || hasAnyValidStandPos(pos);
    }

    private BlockPos findStandPos(BlockPos placePos) {
        if (mc.world == null || mc.player == null || placePos == null) return null;

        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (Direction dir : Direction.Type.HORIZONTAL) {
            BlockPos candidate = placePos.offset(dir);
            if (!isValidStandPos(candidate, placePos)) continue;

            double d = mc.player.getPos().squaredDistanceTo(Vec3d.ofCenter(candidate));
            if (d < bestDist) {
                bestDist = d;
                best = candidate;
            }
        }

        if (best != null) return best;
        BlockPos playerPos = mc.player.getBlockPos();
        return isValidStandPos(playerPos, placePos) ? playerPos : null;
    }

    private boolean hasAnyValidStandPos(BlockPos placePos) {
        if (placePos == null || mc.player == null) return false;
        for (Direction dir : Direction.Type.HORIZONTAL) {
            if (isValidStandPos(placePos.offset(dir), placePos)) return true;
        }
        return isValidStandPos(mc.player.getBlockPos(), placePos);
    }

    private boolean isValidStandPos(BlockPos standPos, BlockPos placePos) {
        if (mc.world == null || mc.player == null || standPos == null || placePos == null) return false;
        if (standPos.equals(placePos)) return false;

        if (!mc.world.getBlockState(standPos).isAir()
            && !mc.world.getBlockState(standPos).isReplaceable()) return false;
        if (!mc.world.getBlockState(standPos.up()).isAir()
            && !mc.world.getBlockState(standPos.up()).isReplaceable()) return false;

        BlockPos below = standPos.down();
        if (mc.world.getBlockState(below).isAir()
            || mc.world.getBlockState(below).isReplaceable()
            || !mc.world.getFluidState(below).isEmpty()) return false;

        Vec3d center = Vec3d.ofCenter(standPos);
        double eyeOffset = mc.player.getEyeY() - mc.player.getY();
        Vec3d standEye = new Vec3d(center.x, standPos.getY() + eyeOffset, center.z);
        if (standEye.distanceTo(Vec3d.ofCenter(placePos)) > module.maxReach.get() + 0.25) return false;

        double halfWidth = mc.player.getWidth() / 2.0;
        Box playerBox = new Box(
            center.x - halfWidth,
            standPos.getY(),
            center.z - halfWidth,
            center.x + halfWidth,
            standPos.getY() + mc.player.getHeight(),
            center.z + halfWidth
        );
        return !playerBox.intersects(new Box(placePos));
    }

    private boolean canOperateFromCurrentPos(BlockPos placePos) {
        if (mc.player == null || placePos == null) return false;
        if (mc.player.getBoundingBox().intersects(new Box(placePos))) return false;
        if (mc.player.getEyePos().distanceTo(Vec3d.ofCenter(placePos)) > module.maxReach.get() + 0.25) return false;
        return findSupportSide(placePos) != null;
    }

    private boolean ensureActionPosition() {
        if (mc.player == null || mc.world == null) return false;
        if (actionPos == null || !isUsableActionPos(actionPos)) {
            reselectActionPosition(actionPos);
            if (actionPos == null) {
                module.pathfinder.clearMinerGoal();
                return false;
            }
        }

        if (standPos == null || !isValidStandPos(standPos, actionPos)) {
            standPos = findStandPos(actionPos);
        }

        boolean selfBlocking = mc.player.getBoundingBox().intersects(new Box(actionPos));
        if (selfBlocking) {
            selfBlockTicks++;
            nudgeAwayFromActionPos();
            if (standPos != null) {
                Vec3d standTarget = getSafeStandPoint(standPos, actionPos);
                if (horizontalDistanceSq(mc.player.getPos(), standTarget)
                    <= MANUAL_CENTER_RANGE * MANUAL_CENTER_RANGE) {
                    module.pathfinder.clearMinerGoal();
                    moveTowardStandPoint(standTarget);
                } else {
                    module.pathfinder.setMinerGoal(standPos);
                }
            }

            if (selfBlockTicks >= SELF_BLOCK_RELOCATE_TICKS) {
                if (!tryRelocateActionPos()) reselectActionPosition(actionPos);
                selfBlockTicks = 0;
            }
        } else {
            selfBlockTicks = 0;
        }

        if (canOperateFromCurrentPos(actionPos)) {
            module.pathfinder.clearMinerGoal();
            ensureNoMoveTicks = 0;
            lastEnsurePos = mc.player.getPos();
            return true;
        }

        if (standPos == null) {
            ensureNoMoveTicks++;
            if (ensureNoMoveTicks > POSITION_STUCK_TICKS) {
                if (!tryRelocateActionPos()) reselectActionPosition(actionPos);
                ensureNoMoveTicks = 0;
            }
            return false;
        }

        Vec3d standTarget = getSafeStandPoint(standPos, actionPos);
        double distSq = horizontalDistanceSq(mc.player.getPos(), standTarget);

        if (distSq <= MANUAL_CENTER_RANGE * MANUAL_CENTER_RANGE) {
            module.pathfinder.clearMinerGoal();
            moveTowardStandPoint(standTarget);
        } else {
            module.pathfinder.setMinerGoal(standPos);
        }

        Vec3d currentPos = mc.player.getPos();
        if (lastEnsurePos != null && currentPos.squaredDistanceTo(lastEnsurePos) <= MOVE_EPSILON_SQ) {
            ensureNoMoveTicks++;
        } else {
            ensureNoMoveTicks = 0;
        }
        lastEnsurePos = currentPos;

        if (ensureNoMoveTicks > POSITION_STUCK_TICKS) {
            if (!tryRelocateActionPos()) reselectActionPosition(actionPos);
            ensureNoMoveTicks = 0;
        }

        return false;
    }

    private boolean ensureMiningAccess() {
        if (mc.player == null || mc.world == null) return false;
        if (!ensureActionPosition()) {
            miningAccessStuckTicks++;
            if (miningAccessStuckTicks >= MINING_POSITION_RECOVER_TICKS) {
                recoverMiningPosition();
                miningAccessStuckTicks = 0;
            }
            return false;
        }

        miningAccessStuckTicks = 0;
        return true;
    }

    private void recoverMiningPosition() {
        if (mc.player == null || mc.world == null || actionPos == null) return;

        // If chest is already placed, keep mining target and only fix stand access.
        if (mc.world.getBlockState(actionPos).getBlock() == Blocks.ENDER_CHEST) {
            standPos = findStandPos(actionPos);
            if (standPos != null) {
                Vec3d standTarget = getSafeStandPoint(standPos, actionPos);
                if (horizontalDistanceSq(mc.player.getPos(), standTarget)
                    <= MANUAL_CENTER_RANGE * MANUAL_CENTER_RANGE) {
                    module.pathfinder.clearMinerGoal();
                    moveTowardStandPoint(standTarget);
                } else {
                    module.pathfinder.setMinerGoal(standPos);
                }
            } else {
                nudgeAwayFromActionPos();
            }
            tickDelay = 1;
            return;
        }

        // Before chest placement, relocation is safe and preferred.
        if (!tryRelocateActionPos()) {
            reselectActionPosition(actionPos);
            if (actionPos == null) {
                goToCollecting();
                return;
            }
        }

        tickDelay = 1;
    }

    private boolean isUsableActionPos(BlockPos pos) {
        if (mc.world == null || pos == null) return false;
        Block current = mc.world.getBlockState(pos).getBlock();
        if (current != Blocks.ENDER_CHEST
            && !mc.world.getBlockState(pos).isAir()
            && !mc.world.getBlockState(pos).isReplaceable()) return false;
        return findSupportSide(pos) != null;
    }

    private void reselectActionPosition(BlockPos avoidPos) {
        actionPos = findAdjacentPlacePos(avoidPos);
        if (actionPos == null && avoidPos != null) actionPos = findAdjacentPlacePos();
        standPos = actionPos != null ? findStandPos(actionPos) : null;
        lastEnsurePos = null;
    }

    private Vec3d getSafeStandPoint(BlockPos standBlock, BlockPos placePos) {
        if (mc.player == null || standBlock == null) return Vec3d.ZERO;

        Vec3d center = Vec3d.ofCenter(standBlock);
        double halfWidth = mc.player.getWidth() / 2.0;
        double margin = halfWidth + STAND_EDGE_PADDING;
        double minX = standBlock.getX() + margin;
        double maxX = standBlock.getX() + 1.0 - margin;
        double minZ = standBlock.getZ() + margin;
        double maxZ = standBlock.getZ() + 1.0 - margin;

        if (minX > maxX || minZ > maxZ) return center;

        double tx = center.x;
        double tz = center.z;
        if (placePos != null) {
            int dx = placePos.getX() - standBlock.getX();
            int dz = placePos.getZ() - standBlock.getZ();
            tx -= Math.signum(dx) * STAND_BIAS_AWAY;
            tz -= Math.signum(dz) * STAND_BIAS_AWAY;

            // Keep explicit clearance from the shared border with action block so
            // the player hitbox cannot clip into the ender-chest placement block.
            double clear = halfWidth + STAND_ACTION_CLEARANCE;
            if (dx < 0) tx = Math.max(tx, standBlock.getX() + clear);
            else if (dx > 0) tx = Math.min(tx, standBlock.getX() + 1.0 - clear);
            if (dz < 0) tz = Math.max(tz, standBlock.getZ() + clear);
            else if (dz > 0) tz = Math.min(tz, standBlock.getZ() + 1.0 - clear);
        }

        tx = clamp(tx, minX, maxX);
        tz = clamp(tz, minZ, maxZ);
        return new Vec3d(tx, center.y, tz);
    }

    private void moveTowardStandPoint(Vec3d target) {
        if (mc.player == null || target == null) return;

        double dx = target.x - mc.player.getX();
        double dz = target.z - mc.player.getZ();
        if (dx * dx + dz * dz <= ACTION_TOLERANCE * ACTION_TOLERANCE) {
            mc.player.setVelocity(0.0, mc.player.getVelocity().y, 0.0);
            return;
        }

        double speed = Math.max(0.18, Math.min(0.32, module.moveSpeed.get() + 0.08));
        mc.player.setVelocity(
            clamp(dx, -speed, speed),
            mc.player.getVelocity().y,
            clamp(dz, -speed, speed)
        );
    }

    private double horizontalDistanceSq(Vec3d a, Vec3d b) {
        double dx = a.x - b.x;
        double dz = a.z - b.z;
        return dx * dx + dz * dz;
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
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

    private int countFreeObsidianInventorySpace() {
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
        return space;
    }

    private int countMissingObsidianForRefill() {
        int inventorySpace = countFreeObsidianInventorySpace();
        if (inventorySpace <= 0) return 0;
        return Math.max(0, inventorySpace - countGroundObsidian());
    }

    private int findSafeHotbarSlot() {
        if (mc.player == null) return -1;
        for (int i = 0; i < 9; i++) {
            if (module.inventoryHandler != null && !module.inventoryHandler.canUseHotbarSlot(i, Items.ENDER_CHEST)) continue;
            if (mc.player.getInventory().getStack(i).isEmpty()) return i;
        }

        for (int i = 0; i < 9; i++) {
            if (module.inventoryHandler != null && !module.inventoryHandler.canUseHotbarSlot(i, Items.ENDER_CHEST)) continue;
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.isEmpty() || stack.getItem() == Items.ENDER_CHEST || stack.getItem() == Items.OBSIDIAN) {
                return i;
            }
        }

        return -1;
    }

    public void reset() {
        reset(true);
    }

    private void reset(boolean clearRefillToCapacity) {
        state = State.IDLE;
        actionPos = null;
        standPos = null;
        chestsRemaining = 0;
        tickDelay = 0;
        stuckTicks = 0;
        hitSent = false;
        echestPlaced = false;
        sneakingForPlace = false;
        pickSlot = -1;
        collectionCenter = null;
        if (clearRefillToCapacity) {
            farmCenters.clear();
        }
        lastEnsurePos = null;
        ensureNoMoveTicks = 0;
        miningAccessStuckTicks = 0;
        selfBlockTicks = 0;
        if (clearRefillToCapacity) refillToCapacity = false;
        if (module.pathfinder != null) {
            module.pathfinder.clearMinerGoal();
            if (module.pathfinder.isPickupActive()) {
                module.pathfinder.stopPickup();
            }
        }
    }
}
