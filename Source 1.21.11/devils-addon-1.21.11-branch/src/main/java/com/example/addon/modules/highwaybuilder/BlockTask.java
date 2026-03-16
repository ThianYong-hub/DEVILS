package com.example.addon.modules.highwaybuilder;

import net.minecraft.block.Block;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class BlockTask {
    public final BlockPos blockPos;
    public TaskState taskState;
    public Block targetBlock;
    public boolean isSupport = false;
    public boolean isFiller = false;
    public Item item = Items.AIR;

    private int ranTicks = 0;
    private int stuckTicks = 0;
    private int shuffle = 0;
    private double startDistance = 0.0;
    private double eyeDistance = 0.0;

    public List<PlaceInfo> sequence = new ArrayList<>();
    public boolean isLiquidSource = false;

    // Container fields
    public boolean isOpen = false;
    public boolean stopPull = false;
    public int stacksPulled = 0;
    public boolean isLoaded = false;
    public int itemID = 0;
    public boolean destroy = false;
    public boolean collect = true;
    public long lastSequenceUpdate = 0L;

    // Timing/state
    public long timestamp = System.currentTimeMillis();
    public Box aabb = new Box(1, 1, 1, 1, 1, 1);
    public boolean toRemove = false;
    public int ticksMined = 1;
    public ItemStack toolToUse = ItemStack.EMPTY;
    public Direction miningSide = null;

    private static final Random random = new Random();

    public BlockTask(BlockPos blockPos, TaskState taskState, Block targetBlock) {
        this.blockPos = blockPos;
        this.taskState = taskState;
        this.targetBlock = targetBlock;
    }

    public void updateState(TaskState state) {
        if (state != taskState) {
            timestamp = System.currentTimeMillis();
            stuckTicks = 0;
            ranTicks = 0;
            if (state != TaskState.BREAK
                && state != TaskState.BREAKING
                && state != TaskState.PENDING_BREAK) {
                miningSide = null;
            }
            if (state == TaskState.BREAK) {
                ticksMined = 1;
            }
            taskState = state;
        }
    }

    public void onTick() {
        ranTicks++;
        if (ranTicks > taskState.stuckThreshold) {
            stuckTicks++;
        }
    }

    public void onStuck(int weight) {
        stuckTicks += weight;
    }

    public void onStuck() {
        onStuck(1);
    }

    public void resetStuck() {
        stuckTicks = 0;
    }

    public int getStuckTicks() {
        return stuckTicks;
    }

    public int getShuffle() {
        return shuffle;
    }

    public double getStartDistance() {
        return startDistance;
    }

    public double getEyeDistance() {
        return eyeDistance;
    }

    public void setStartDistance(double d) {
        this.startDistance = d;
    }

    public void setEyeDistance(double d) {
        this.eyeDistance = d;
    }

    public void shuffle() {
        shuffle = random.nextInt(1000);
    }

    public boolean isShulker() {
        return targetBlock.getTranslationKey().contains("shulker_box");
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (other instanceof BlockTask bt) {
            return blockPos.equals(bt.blockPos);
        }
        return false;
    }

    @Override
    public int hashCode() {
        return blockPos.hashCode();
    }

    @Override
    public String toString() {
        return "BlockTask{" + targetBlock + " @ " + blockPos.toShortString() + " state=" + taskState + "}";
    }
}


