package com.devils.addon.modules.highwaybuilder;

import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

final class EChestMinerCollectionController {
    private static final MinecraftClient mc = MinecraftClient.getInstance();

    private final EChestMiner miner;
    private final HighwayBuilder module;

    EChestMinerCollectionController(EChestMiner miner) {
        this.miner = miner;
        this.module = miner.module;
    }

    void onEchestBroken() {
        if (miner.echestPlaced && miner.chestsRemaining > 0) miner.chestsRemaining--;
        miner.echestPlaced = false;
        if (!miner.isInsta()) miner.hitSent = false;
        miner.stuckTicks = 0;
        miner.collectionCenter = miner.actionPos;
        trackFarmCenter(miner.actionPos);

        if (!miner.resources.ensurePickaxeReadyForMining()) return;

        if (miner.chestsRemaining <= 0) {
            goToCollecting();
            return;
        }

        FindItemResult echest = InvUtils.find(Items.ENDER_CHEST);
        if (echest.found()) {
            if (miner.isContainerSilent()) {
                miner.state = EChestMinerSupport.State.PLACE_ECHEST;
            } else {
                miner.state = EChestMinerSupport.State.SWAP_TO_ECHEST;
                miner.tickDelay = miner.isInsta() ? 0 : 1;
            }
            return;
        }

        goToCollecting();
    }

    void doCollecting() {
        if (mc.player == null || mc.world == null) {
            miner.reset();
            return;
        }

        int freeSpace = miner.resources.countFreeObsidianInventorySpace();
        if (freeSpace <= 0) {
            module.pathfinder.clearMinerGoal();
            miner.reset();
            return;
        }

        ItemEntity closest = findClosestObsidian();
        if (closest == null) {
            module.pathfinder.clearMinerGoal();
            int missingObsidian = miner.resources.countMissingObsidianForRefill();
            if (miner.refillToCapacity && missingObsidian >= 8) {
                if (miner.resources.countItem(Items.ENDER_CHEST) > EChestMinerSupport.MIN_ECHEST_RESERVE
                    || miner.resources.tryRequestEchestRestock()) {
                    miner.reset(false);
                    return;
                }
            }
            miner.refillToCapacity = false;
            miner.reset();
            return;
        }

        module.pathfinder.setMinerGoal(closest.getBlockPos());
        miner.stuckTicks++;
        if (miner.stuckTicks > 200) {
            module.pathfinder.clearMinerGoal();
            miner.reset();
        }
    }

    void goToCollecting() {
        miner.state = EChestMinerSupport.State.COLLECTING;
        miner.tickDelay = 0;
        miner.stuckTicks = 0;
        miner.chestsRemaining = 0;
        miner.hitSent = false;
        miner.collectionCenter = miner.actionPos != null
            ? miner.actionPos
            : mc.player != null ? mc.player.getBlockPos() : null;
    }

    int countGroundObsidian() {
        if (mc.player == null || mc.world == null) return 0;

        Box searchBox = getObsidianSearchBox();
        int count = 0;
        for (var entity : mc.world.getEntities()) {
            if (!(entity instanceof ItemEntity itemEntity)) continue;
            if (itemEntity.getStack().getItem() != Items.OBSIDIAN) continue;
            if (!searchBox.contains(itemEntity.getEntityPos())) continue;
            if (!isInsideTrackedFarmArea(itemEntity.getEntityPos())) continue;
            count += itemEntity.getStack().getCount();
        }
        return count;
    }

    private ItemEntity findClosestObsidian() {
        if (mc.player == null || mc.world == null) return null;

        Box searchBox = getObsidianSearchBox();
        ItemEntity closest = null;
        double closestDist = Double.MAX_VALUE;
        for (var entity : mc.world.getEntities()) {
            if (!(entity instanceof ItemEntity itemEntity)) continue;
            if (itemEntity.getStack().getItem() != Items.OBSIDIAN) continue;
            if (!searchBox.contains(itemEntity.getEntityPos())) continue;
            if (!isInsideTrackedFarmArea(itemEntity.getEntityPos())) continue;
            double distance = itemEntity.getEntityPos().squaredDistanceTo(mc.player.getEntityPos());
            if (distance < closestDist) {
                closestDist = distance;
                closest = itemEntity;
            }
        }

        if (closest == null && !miner.farmCenters.isEmpty()) {
            Vec3d playerPos = mc.player.getEntityPos();
            Box fallback = new Box(playerPos, playerPos).expand(EChestMinerSupport.FALLBACK_COLLECTION_RADIUS);
            for (var entity : mc.world.getEntities()) {
                if (!(entity instanceof ItemEntity itemEntity)) continue;
                if (itemEntity.getStack().getItem() != Items.OBSIDIAN) continue;
                if (!fallback.contains(itemEntity.getEntityPos())) continue;
                double distance = itemEntity.getEntityPos().squaredDistanceTo(playerPos);
                if (distance < closestDist) {
                    closestDist = distance;
                    closest = itemEntity;
                }
            }
        }

        return closest;
    }

    private Box getObsidianSearchBox() {
        if (mc.player == null) return new Box(0, 0, 0, 0, 0, 0);

        if (!miner.farmCenters.isEmpty()) {
            Box union = null;
            for (BlockPos centerPos : miner.farmCenters) {
                Vec3d center = Vec3d.ofCenter(centerPos);
                Box around = new Box(center, center).expand(EChestMinerSupport.COLLECTION_RADIUS);
                union = union == null ? around : union.union(around);
            }
            if (union != null) return union.expand(1.0);
        }

        Vec3d center = miner.collectionCenter != null ? Vec3d.ofCenter(miner.collectionCenter) : mc.player.getEntityPos();
        double radius = miner.collectionCenter != null
            ? EChestMinerSupport.COLLECTION_RADIUS
            : EChestMinerSupport.FALLBACK_COLLECTION_RADIUS;
        return new Box(center, center).expand(radius);
    }

    private boolean isInsideTrackedFarmArea(Vec3d pos) {
        if (mc.player == null || pos == null) return false;

        if (miner.farmCenters.isEmpty()) {
            Vec3d center = miner.collectionCenter != null ? Vec3d.ofCenter(miner.collectionCenter) : mc.player.getEntityPos();
            double radius = miner.collectionCenter != null
                ? EChestMinerSupport.COLLECTION_RADIUS
                : EChestMinerSupport.FALLBACK_COLLECTION_RADIUS;
            return pos.squaredDistanceTo(center) <= radius * radius;
        }

        double sq = EChestMinerSupport.COLLECTION_RADIUS * EChestMinerSupport.COLLECTION_RADIUS;
        for (BlockPos centerPos : miner.farmCenters) {
            if (pos.squaredDistanceTo(Vec3d.ofCenter(centerPos)) <= sq) return true;
        }
        return false;
    }

    private void trackFarmCenter(BlockPos pos) {
        if (pos == null) return;
        if (miner.farmCenters.stream().anyMatch(existing -> existing.equals(pos))) return;

        miner.farmCenters.add(pos);
        while (miner.farmCenters.size() > 16) {
            miner.farmCenters.remove(0);
        }
    }
}




