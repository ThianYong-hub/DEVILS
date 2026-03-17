package com.example.addon.modules.highwaybuilder;

import java.util.ArrayList;
import java.util.List;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
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
 * EChest Miner places and breaks ender chests to farm obsidian.
 */
public class EChestMiner {
    private static final MinecraftClient mc = MinecraftClient.getInstance();

    final HighwayBuilder module;
    final EChestMinerPositioning positioning;
    final EChestMinerResourceManager resources;
    final EChestMinerCollectionController collection;

    EChestMinerSupport.State state = EChestMinerSupport.State.IDLE;
    BlockPos actionPos = null;
    int chestsRemaining = 0;
    int tickDelay = 0;
    int stuckTicks = 0;
    boolean hitSent = false;
    boolean echestPlaced = false;
    boolean sneakingForPlace = false;
    int pickSlot = -1;
    BlockPos collectionCenter = null;
    BlockPos standPos = null;
    boolean refillToCapacity = false;
    final List<BlockPos> farmCenters = new ArrayList<>();

    Vec3d lastEnsurePos = null;
    int ensureNoMoveTicks = 0;
    int miningAccessStuckTicks = 0;
    int selfBlockTicks = 0;

    public EChestMiner(HighwayBuilder module) {
        this.module = module;
        this.positioning = new EChestMinerPositioning(this);
        this.resources = new EChestMinerResourceManager(this);
        this.collection = new EChestMinerCollectionController(this);
    }

    boolean isInsta() {
        return EChestMinerSupport.isInsta(module);
    }

    boolean isContainerSilent() {
        return EChestMinerSupport.isContainerSilent(module);
    }

    boolean isPickSilent() {
        return EChestMinerSupport.isPickSilent(module);
    }

    public boolean isActive() {
        return state != EChestMinerSupport.State.IDLE;
    }

    public EChestMinerSupport.State getState() {
        return state;
    }

    public void tick() {
        if (mc.player == null || mc.world == null) return;
        if (EChestMinerSupport.shouldPauseForExternalState(module)) {
            EChestMinerSupport.stopForExternalState(this);
            return;
        }

        if (tickDelay > 0) {
            tickDelay--;
            return;
        }

        int maxChain = isInsta() ? 10 : 1;
        for (int i = 0; i < maxChain; i++) {
            EChestMinerSupport.State before = state;
            switch (state) {
                case IDLE -> checkShouldStart();
                case SWAP_TO_ECHEST -> doSwapToEchest();
                case PLACE_ECHEST -> doPlaceEchest();
                case SWAP_TO_PICK -> doSwapToPick();
                case MINE_HIT -> doMineHit();
                case WAIT_BREAK -> doWaitBreak();
                case COLLECTING -> collection.doCollecting();
            }
            if (EChestMinerSupport.shouldStopTickChain(before, state, tickDelay)) break;
        }
    }

    private void checkShouldStart() {
        if (mc.player == null) return;
        if (module.getMaterial() != Blocks.OBSIDIAN) return;
        if (EChestMinerSupport.shouldPauseForExternalState(module)) return;

        int missingObsidian = resources.countMissingObsidianForRefill();
        boolean refillContinuation = EChestMinerSupport.isRefillWorthContinuing(refillToCapacity, missingObsidian);

        double distToBuild = mc.player.getEntityPos().distanceTo(Vec3d.ofCenter(module.pathfinder.currentBlockPos));
        if (!refillContinuation && distToBuild > 1.5) return;
        if (!resources.ensurePickaxeReadyForMining()) return;

        if (missingObsidian < 8) {
            refillToCapacity = false;
            return;
        }

        int currentObsidian = resources.countItem(Items.OBSIDIAN);
        if (!refillToCapacity) {
            if (currentObsidian > module.saveMaterial.get()) return;
            refillToCapacity = true;
        }

        FindItemResult echest = InvUtils.find(Items.ENDER_CHEST);
        if (!echest.found()) {
            if (resources.tryRequestEchestRestock()) return;
            refillToCapacity = false;
            return;
        }

        int chestsNeeded = (int) Math.ceil(missingObsidian / 8.0);
        int availableToMine = echest.count() - EChestMinerSupport.MIN_ECHEST_RESERVE;
        chestsRemaining = Math.min(chestsNeeded, Math.max(0, availableToMine));
        if (chestsRemaining <= 0) {
            if (resources.tryRequestEchestRestock()) return;
            refillToCapacity = false;
            return;
        }

        actionPos = positioning.findAdjacentPlacePos();
        if (actionPos == null) return;
        standPos = positioning.findStandPos(actionPos);

        stuckTicks = 0;
        echestPlaced = false;
        state = isContainerSilent()
            ? EChestMinerSupport.State.PLACE_ECHEST
            : EChestMinerSupport.State.SWAP_TO_ECHEST;
    }

    private void doSwapToEchest() {
        if (mc.player == null) {
            reset();
            return;
        }
        if (!positioning.ensureActionPosition()) return;

        if (mc.world != null && mc.world.getBlockState(actionPos).getBlock() == Blocks.ENDER_CHEST) {
            echestPlaced = true;
            stuckTicks = 0;
            state = isPickSilent()
                ? EChestMinerSupport.State.MINE_HIT
                : EChestMinerSupport.State.SWAP_TO_PICK;
            return;
        }

        FindItemResult echest = InvUtils.findInHotbar(Items.ENDER_CHEST);
        if (!echest.found()) {
            FindItemResult invEchest = InvUtils.find(Items.ENDER_CHEST);
            if (!invEchest.found()) {
                collection.goToCollecting();
                return;
            }

            int safeSlot = resources.findSafeHotbarSlot();
            if (safeSlot == -1) {
                collection.goToCollecting();
                return;
            }

            InvUtils.move().from(invEchest.slot()).toHotbar(safeSlot);
            tickDelay = isInsta() ? 0 : 1;
            return;
        }

        InvUtils.swap(echest.slot(), false);
        state = EChestMinerSupport.State.PLACE_ECHEST;
        stuckTicks = 0;
    }

    private void doPlaceEchest() {
        if (mc.player == null || mc.world == null || mc.interactionManager == null) {
            reset();
            return;
        }
        if (!positioning.ensureActionPosition()) return;

        if (mc.player.getBoundingBox().intersects(new Box(actionPos))) {
            if (!positioning.tryRelocateActionPos()) {
                positioning.nudgeAwayFromActionPos();
                stuckTicks++;
                if (EChestMinerSupport.shouldRetryPlacement(stuckTicks)) {
                    actionPos = positioning.findAdjacentPlacePos(actionPos);
                    standPos = actionPos != null ? positioning.findStandPos(actionPos) : null;
                    stuckTicks = 0;
                }
                tickDelay = 1;
            }
            return;
        }

        if (mc.world.getBlockState(actionPos).getBlock() == Blocks.ENDER_CHEST) {
            echestPlaced = true;
            state = isPickSilent()
                ? EChestMinerSupport.State.MINE_HIT
                : EChestMinerSupport.State.SWAP_TO_PICK;
            return;
        }

        if (!mc.world.getBlockState(actionPos).isAir() && !mc.world.getBlockState(actionPos).isReplaceable()) {
            if (positioning.tryRelocateActionPos()) return;
            stuckTicks++;
            if (stuckTicks > 20) {
                actionPos = positioning.findAdjacentPlacePos(actionPos);
                standPos = actionPos != null ? positioning.findStandPos(actionPos) : null;
                stuckTicks = 0;
                if (actionPos == null) collection.goToCollecting();
            }
            return;
        }

        Direction supportDir = positioning.findSupportSide(actionPos);
        if (supportDir == null) {
            collection.goToCollecting();
            return;
        }

        BlockPos supportPos = actionPos.offset(supportDir);
        Vec3d hitVec = Vec3d.ofCenter(supportPos).add(
            supportDir.getOpposite().getOffsetX() * 0.5,
            supportDir.getOpposite().getOffsetY() * 0.5,
            supportDir.getOpposite().getOffsetZ() * 0.5
        );

        if (module.rotate.get()) Rotations.rotate(Rotations.getYaw(hitVec), Rotations.getPitch(hitVec));

        mc.player.setSneaking(true);
        if (!isInsta() && !isContainerSilent() && !sneakingForPlace) {
            sneakingForPlace = true;
            tickDelay = 1;
            return;
        }

        if (isContainerSilent()) {
            FindItemResult echest = InvUtils.find(Items.ENDER_CHEST);
            if (!echest.found()) {
                mc.player.setSneaking(false);
                collection.goToCollecting();
                return;
            }

            int prevSlot = mc.player.getInventory().getSelectedSlot();
            if (echest.isHotbar()) {
                InvUtils.swap(echest.slot(), false);
            } else {
                int safeSlot = resources.findSafeHotbarSlot();
                if (safeSlot == -1) {
                    mc.player.setSneaking(false);
                    collection.goToCollecting();
                    return;
                }
                InvUtils.move().from(echest.slot()).toHotbar(safeSlot);
                InvUtils.swap(safeSlot, false);
            }

            BlockHitResult hitResult = new BlockHitResult(hitVec, supportDir.getOpposite(), supportPos, false);
            mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hitResult);
            mc.player.swingHand(Hand.MAIN_HAND);
            InvUtils.swap(prevSlot, false);
        } else {
            BlockHitResult hitResult = new BlockHitResult(hitVec, supportDir.getOpposite(), supportPos, false);
            ActionResult result = mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hitResult);
            if (result.isAccepted()) mc.player.swingHand(Hand.MAIN_HAND);
        }

        mc.player.setSneaking(false);
        sneakingForPlace = false;

        if (mc.world.getBlockState(actionPos).getBlock() == Blocks.ENDER_CHEST) {
            echestPlaced = true;
            state = isPickSilent()
                ? EChestMinerSupport.State.MINE_HIT
                : EChestMinerSupport.State.SWAP_TO_PICK;
            stuckTicks = 0;
            return;
        }

        stuckTicks++;
        if (stuckTicks > 40) {
            if (positioning.tryRelocateActionPos()) return;
            actionPos = positioning.findAdjacentPlacePos(actionPos);
            standPos = actionPos != null ? positioning.findStandPos(actionPos) : null;
            stuckTicks = 0;
            if (actionPos == null) collection.goToCollecting();
        }
        tickDelay = 1;
    }

    private void doSwapToPick() {
        if (mc.player == null) {
            reset();
            return;
        }
        if (!positioning.ensureActionPosition()) return;

        if (mc.world != null && EChestMinerSupport.isBrokenEchest(mc.world.getBlockState(actionPos).getBlock())) {
            stuckTicks++;
            if (stuckTicks > 20) state = EChestMinerSupport.State.PLACE_ECHEST;
            return;
        }
        echestPlaced = true;

        if (!resources.ensurePickEquippedForMining()) return;
        if (tickDelay == 0 && !isInsta()) tickDelay = 1;

        state = EChestMinerSupport.State.MINE_HIT;
        stuckTicks = 0;
    }

    private void doMineHit() {
        if (mc.player == null || mc.world == null || mc.interactionManager == null) {
            reset();
            return;
        }
        if (!positioning.ensureMiningAccess()) return;

        Block currentBlock = mc.world.getBlockState(actionPos).getBlock();
        if (EChestMinerSupport.isBrokenEchest(currentBlock)) {
            if (!echestPlaced) {
                state = EChestMinerSupport.State.PLACE_ECHEST;
                stuckTicks++;
                if (stuckTicks > 30) {
                    if (!positioning.tryRelocateActionPos()) {
                        actionPos = positioning.findAdjacentPlacePos(actionPos);
                        standPos = actionPos != null ? positioning.findStandPos(actionPos) : null;
                    }
                    stuckTicks = 0;
                    if (actionPos == null) collection.goToCollecting();
                }
                return;
            }
            collection.onEchestBroken();
            return;
        }

        Direction side = HWUtils.getMiningSide(actionPos);
        if (side == null) side = Direction.UP;

        Vec3d hitVec = Vec3d.ofCenter(actionPos);
        if (module.rotate.get()) Rotations.rotate(Rotations.getYaw(hitVec), Rotations.getPitch(hitVec));

        if (isInsta()) {
            if (!hitSent) {
                if (isPickSilent()) {
                    int prevSlot = mc.player.getInventory().getSelectedSlot();
                    if (!resources.swapToPickSilent()) return;
                    mc.interactionManager.attackBlock(actionPos, side);
                    mc.player.swingHand(Hand.MAIN_HAND);
                    InvUtils.swap(prevSlot, false);
                } else {
                    if (!resources.ensurePickEquippedForMining()) return;
                    mc.interactionManager.attackBlock(actionPos, side);
                    mc.player.swingHand(Hand.MAIN_HAND);
                }
                hitSent = true;
            }
            state = EChestMinerSupport.State.WAIT_BREAK;
            stuckTicks = 0;
            return;
        }

        if (isPickSilent()) {
            int prevSlot = mc.player.getInventory().getSelectedSlot();
            if (!resources.swapToPickSilent()) return;
            mc.interactionManager.attackBlock(actionPos, side);
            mc.player.swingHand(Hand.MAIN_HAND);
            InvUtils.swap(prevSlot, false);
        } else {
            if (!resources.ensurePickEquippedForMining()) return;
            mc.interactionManager.attackBlock(actionPos, side);
            mc.player.swingHand(Hand.MAIN_HAND);
        }

        stuckTicks++;
        if (stuckTicks > 200) {
            collection.goToCollecting();
            return;
        }

        if (EChestMinerSupport.isBrokenEchest(mc.world.getBlockState(actionPos).getBlock())) {
            collection.onEchestBroken();
        }
    }

    private void doWaitBreak() {
        if (mc.world == null) {
            reset();
            return;
        }
        if (!positioning.ensureMiningAccess()) return;

        if (EChestMinerSupport.isBrokenEchest(mc.world.getBlockState(actionPos).getBlock())) {
            collection.onEchestBroken();
            return;
        }

        stuckTicks++;
        if (stuckTicks > 100) {
            state = EChestMinerSupport.State.MINE_HIT;
            hitSent = false;
            stuckTicks = 0;
        }
    }

    public void reset() {
        reset(true);
    }

    void reset(boolean clearRefillToCapacity) {
        state = EChestMinerSupport.State.IDLE;
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
        if (clearRefillToCapacity) farmCenters.clear();
        EChestMinerSupport.resetMovementTracking(this);
        if (clearRefillToCapacity) refillToCapacity = false;
        EChestMinerSupport.clearPathfinderGoal(module);
    }
}
