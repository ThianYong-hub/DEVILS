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
    private static final double COLLECTION_RADIUS = 6.0;
    private static final double ACTION_TOLERANCE = 0.18;
    private static final double STAND_EDGE_PADDING = 0.04;
    private static final double STAND_BIAS_AWAY = 0.08;
    private static final double MANUAL_CENTER_RANGE = 3.5;
    private static final int POSITION_STUCK_TICKS = 60;
    private static final double MOVE_EPSILON_SQ = 1.0e-4;

    private Vec3d lastEnsurePos = null;
    private int ensureNoMoveTicks = 0;

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

        // Never run EChest mining while shulker restock cycle is active/open.
        if (module.containerHandler != null) {
            BlockTask containerTask = module.containerHandler.containerTask;
            if (containerTask != null && containerTask.taskState != TaskState.DONE) {
                if (state != State.IDLE) reset();
                return;
            }
        }
        if (mc.player.currentScreenHandler != null && mc.player.currentScreenHandler.syncId != 0) {
            if (state != State.IDLE) reset();
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

        double distToBuild = mc.player.getPos().distanceTo(
            Vec3d.ofCenter(module.pathfinder.currentBlockPos));
        if (distToBuild > 1.5) return;

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
        int availableToMine = echest.count() - MIN_ECHEST_RESERVE;
        chestsRemaining = Math.min(chestsNeeded, Math.max(0, availableToMine));
        if (chestsRemaining <= 0) {
            if (module.storageManagement.get()
                && module.containerHandler.containerTask.taskState == TaskState.DONE
                && module.containerHandler.findShulkerWithItem(Items.ENDER_CHEST) != -1) {
                module.containerHandler.handleRestock(Items.ENDER_CHEST);
            }
            return;
        }

        actionPos = findAdjacentPlacePos();
        if (actionPos == null) return;
        standPos = findStandPos(actionPos);

        // Cache pickaxe slot for the whole cycle
        pickSlot = findBestPickSlot();
        if (pickSlot == -1) return;

        stuckTicks = 0;
        echestPlaced = false;

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
        if (!ensureActionPosition()) return;

        if (mc.world != null && mc.world.getBlockState(actionPos).getBlock() == Blocks.ENDER_CHEST) {
            echestPlaced = true;
            stuckTicks = 0;
            if (isSilent()) state = State.MINE_HIT;
            else state = State.SWAP_TO_PICK;
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
        if (!ensureActionPosition()) return;

        // Already placed?
        if (mc.world.getBlockState(actionPos).getBlock() == Blocks.ENDER_CHEST) {
            echestPlaced = true;
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
            if (stuckTicks > 20) {
                actionPos = findAdjacentPlacePos();
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

        // Do not advance until placement is actually confirmed.
        if (mc.world.getBlockState(actionPos).getBlock() == Blocks.ENDER_CHEST) {
            echestPlaced = true;
            if (isSilent()) {
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
            actionPos = findAdjacentPlacePos();
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
        if (!ensureActionPosition()) return;

        Block currentBlock = mc.world.getBlockState(actionPos).getBlock();

        if (currentBlock != Blocks.ENDER_CHEST) {
            if (!echestPlaced) {
                state = State.PLACE_ECHEST;
                stuckTicks++;
                if (stuckTicks > 30) {
                    actionPos = findAdjacentPlacePos();
                    standPos = actionPos != null ? findStandPos(actionPos) : null;
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
        if (echestPlaced && chestsRemaining > 0) chestsRemaining--;
        echestPlaced = false;
        if (!isInsta()) hitSent = false;
        stuckTicks = 0;
        collectionCenter = actionPos;

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

        if (countFreeObsidianSpace() <= 0) {
            module.pathfinder.clearMinerGoal();
            reset();
            return;
        }

        ItemEntity closest = findClosestObsidian();

        // No obsidian left on ground — done collecting
        if (closest == null) {
            module.pathfinder.clearMinerGoal();
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
        Vec3d center = collectionCenter != null ? Vec3d.ofCenter(collectionCenter) : mc.player.getPos();
        double radius = collectionCenter != null ? COLLECTION_RADIUS : 16.0;
        Box searchBox = new Box(center, center).expand(radius);
        ItemEntity closest = null;
        double closestDist = Double.MAX_VALUE;
        for (var entity : mc.world.getEntities()) {
            if (!(entity instanceof ItemEntity itemEntity)) continue;
            if (itemEntity.getStack().getItem() != Items.OBSIDIAN) continue;
            if (!searchBox.contains(itemEntity.getPos())) continue;
            double d = itemEntity.getPos().squaredDistanceTo(center);
            if (d < closestDist) {
                closestDist = d;
                closest = itemEntity;
            }
        }
        return closest;
    }

    private int countGroundObsidian() {
        if (mc.player == null || mc.world == null) return 0;
        Vec3d center = collectionCenter != null ? Vec3d.ofCenter(collectionCenter) : mc.player.getPos();
        double radius = collectionCenter != null ? COLLECTION_RADIUS : 16.0;
        Box searchBox = new Box(center, center).expand(radius);
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
        BlockPos currentPos = module.pathfinder.currentBlockPos;
        double maxReach = module.maxReach.get();

        // Reuse current action position if still valid.
        if (actionPos != null && isValidPlacePos(actionPos, maxReach)) return actionPos;

        HWDirection hwDir = module.pathfinder.startingDirection;
        if (hwDir != null) {
            BlockPos behind = playerPos.add(
                -hwDir.directionVec.getX(), 0, -hwDir.directionVec.getZ());
            if (isValidPlacePos(behind, maxReach)) return behind;

            BlockPos behindCurrent = currentPos.add(
                -hwDir.directionVec.getX(), 0, -hwDir.directionVec.getZ());
            if (isValidPlacePos(behindCurrent, maxReach)) return behindCurrent;
        }
        for (Direction dir : Direction.Type.HORIZONTAL) {
            BlockPos pos = playerPos.offset(dir);
            if (isValidPlacePos(pos, maxReach)) return pos;

            BlockPos posCurrent = currentPos.offset(dir);
            if (isValidPlacePos(posCurrent, maxReach)) return posCurrent;
        }

        // Fallback one block above (uneven floor cases).
        for (Direction dir : Direction.Type.HORIZONTAL) {
            BlockPos pos = playerPos.offset(dir).up();
            if (isValidPlacePos(pos, maxReach)) return pos;
            BlockPos posCurrent = currentPos.offset(dir).up();
            if (isValidPlacePos(posCurrent, maxReach)) return posCurrent;
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
        // Never select a place position intersecting player hitbox.
        if (mc.player.getBoundingBox().intersects(new Box(pos))) return false;
        if (findSupportSide(pos) == null) return false;
        double dist = mc.player.getEyePos().distanceTo(Vec3d.ofCenter(pos));
        return dist <= maxReach;
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
            reselectActionPosition();
            if (actionPos == null) {
                module.pathfinder.clearMinerGoal();
                return false;
            }
        }

        if (standPos == null || !isValidStandPos(standPos, actionPos)) {
            standPos = findStandPos(actionPos);
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
                reselectActionPosition();
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
            reselectActionPosition();
            ensureNoMoveTicks = 0;
        }

        return false;
    }

    private boolean isUsableActionPos(BlockPos pos) {
        if (mc.world == null || pos == null) return false;
        Block current = mc.world.getBlockState(pos).getBlock();
        if (current != Blocks.ENDER_CHEST
            && !mc.world.getBlockState(pos).isAir()
            && !mc.world.getBlockState(pos).isReplaceable()) return false;
        return findSupportSide(pos) != null;
    }

    private void reselectActionPosition() {
        actionPos = findAdjacentPlacePos();
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

        double speed = Math.min(0.20, module.moveSpeed.get());
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
        standPos = null;
        chestsRemaining = 0;
        tickDelay = 0;
        stuckTicks = 0;
        hitSent = false;
        echestPlaced = false;
        sneakingForPlace = false;
        pickSlot = -1;
        collectionCenter = null;
        lastEnsurePos = null;
        ensureNoMoveTicks = 0;
        if (module.pathfinder != null) {
            module.pathfinder.clearMinerGoal();
            if (module.pathfinder.isPickupActive()) {
                module.pathfinder.stopPickup();
            }
        }
    }
}
