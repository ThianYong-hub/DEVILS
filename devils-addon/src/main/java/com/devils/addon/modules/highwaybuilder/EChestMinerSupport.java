package com.devils.addon.modules.highwaybuilder;

import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;

final class EChestMinerSupport {
    static final int MIN_ECHEST_RESERVE = 16;
    static final double COLLECTION_RADIUS = 6.0;
    static final double FALLBACK_COLLECTION_RADIUS = 24.0;
    static final double ACTION_TOLERANCE = 0.10;
    static final double STAND_EDGE_PADDING = 0.04;
    static final double STAND_BIAS_AWAY = 0.08;
    static final double STAND_ACTION_CLEARANCE = 0.03;
    static final double MANUAL_CENTER_RANGE = 3.5;
    static final int POSITION_STUCK_TICKS = 20;
    static final int SELF_BLOCK_RELOCATE_TICKS = 6;
    static final int FAR_PLACEMENT_DISTANCE = 2;
    static final double MOVE_EPSILON_SQ = 1.0e-4;
    static final int MINING_POSITION_RECOVER_TICKS = 12;

    private static final MinecraftClient mc = MinecraftClient.getInstance();

    private EChestMinerSupport() {
    }

    enum State {
        IDLE,
        SWAP_TO_ECHEST,
        PLACE_ECHEST,
        SWAP_TO_PICK,
        MINE_HIT,
        WAIT_BREAK,
        COLLECTING
    }

    static boolean isInsta(HighwayBuilder module) {
        return module.echestMineMode.get() == EChestMineMode.Insta;
    }

    static boolean isContainerSilent(HighwayBuilder module) {
        return module.swapMode.get() == EChestSwapMode.Silent;
    }

    static boolean isPickSilent(HighwayBuilder module) {
        return module.pickaxeSwapMode.get() == ToolSwapMode.Silent;
    }

    static boolean shouldPauseForExternalState(HighwayBuilder module) {
        if (mc.player == null) return true;
        if (hasActiveContainerTask(module)) return true;
        return hasBusyScreen();
    }

    static boolean hasActiveContainerTask(HighwayBuilder module) {
        if (module.containerHandler == null) return false;
        BlockTask containerTask = module.containerHandler.containerTask;
        return containerTask != null && containerTask.taskState != TaskState.DONE;
    }

    static boolean hasBusyScreen() {
        return mc.player != null
            && mc.player.currentScreenHandler != null
            && mc.player.currentScreenHandler.syncId != 0;
    }

    static boolean shouldStopTickChain(State before, State after, int tickDelay) {
        if (tickDelay > 0) return true;
        if (after == before) return true;
        return isWaitingState(after) || after == State.IDLE;
    }

    static boolean isWaitingState(State state) {
        return state == State.WAIT_BREAK || state == State.COLLECTING;
    }

    static boolean isPlacementState(State state) {
        return state == State.SWAP_TO_ECHEST || state == State.PLACE_ECHEST;
    }

    static boolean isMiningState(State state) {
        return state == State.SWAP_TO_PICK || state == State.MINE_HIT || state == State.WAIT_BREAK;
    }

    static boolean isBrokenEchest(Block block) {
        return block != Blocks.ENDER_CHEST;
    }

    static BlockPos nextCollectionCenter(BlockPos actionPos) {
        if (actionPos != null) return actionPos;
        return mc.player != null ? mc.player.getBlockPos() : null;
    }

    static void resetMovementTracking(EChestMiner miner) {
        miner.lastEnsurePos = null;
        miner.ensureNoMoveTicks = 0;
        miner.miningAccessStuckTicks = 0;
        miner.selfBlockTicks = 0;
    }

    static void clearPathfinderGoal(HighwayBuilder module) {
        if (module.pathfinder == null) return;
        module.pathfinder.clearMinerGoal();
        if (module.pathfinder.isPickupActive()) module.pathfinder.stopPickup();
    }

    static void stopForExternalState(EChestMiner miner) {
        if (miner.state == State.IDLE) return;
        miner.reset(false);
    }

    static boolean isRefillWorthContinuing(boolean refillToCapacity, int missingObsidian) {
        return refillToCapacity && missingObsidian >= 8;
    }

    static boolean shouldRetryPlacement(int stuckTicks) {
        return stuckTicks > SELF_BLOCK_RELOCATE_TICKS;
    }

    static boolean shouldRecoverMiningAccess(int stuckTicks) {
        return stuckTicks >= MINING_POSITION_RECOVER_TICKS;
    }
}

