package com.example.addon.modules.highwaybuilder;

import com.example.addon.util.CrashGuard;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

import java.util.List;
import java.util.Set;

public class HighwayBuilder extends HighwayBuilderConfig {
    public BlueprintGenerator blueprintGenerator;
    public TaskManager taskManager;
    public TaskExecutor taskExecutor;
    public BlockBreaker blockBreaker;
    public BlockPlacer blockPlacer;
    public InventoryHandler inventoryHandler;
    public ContainerHandler containerHandler;
    public LiquidHandler liquidHandler;
    public PacketHandler packetHandler;
    public PathfinderHandler pathfinder;
    public HighwayRenderer renderer;
    public HighwayStatistics statistics;
    public EChestMiner echestMiner;

    private final HighwayAutoEatController autoEatController = new HighwayAutoEatController(this);
    private int repopulateTimer;
    private boolean containerBusyLastTick;

    public HighwayBuilder() {}
    @Override
    public void onActivate() {
        if (mc.player == null || mc.world == null) {
            toggle();
            return;
        }

        // Check for building material, ender chests, or ender chests inside shulker boxes
        boolean hasMaterial = InvUtils.find(material.get().asItem()).count() > 0;
        boolean hasEchests = material.get() == Blocks.OBSIDIAN && InvUtils.find(Items.ENDER_CHEST).found();
        boolean hasEchestsInShulker = !hasMaterial && !hasEchests
            && material.get() == Blocks.OBSIDIAN && hasEnderChestsInShulkers();
        if (!hasMaterial && !hasEchests && !hasEchestsInShulker) {
            error("No building material (%s) or ender chests found in inventory.", material.get().getName().getString());
            toggle();
            return;
        }

        blueprintGenerator = new BlueprintGenerator(this);
        taskManager = new TaskManager(this);
        taskExecutor = new TaskExecutor(this);
        blockBreaker = new BlockBreaker(this);
        blockPlacer = new BlockPlacer(this);
        inventoryHandler = new InventoryHandler(this);
        containerHandler = new ContainerHandler(this);
        liquidHandler = new LiquidHandler(this);
        packetHandler = new PacketHandler(this);
        pathfinder = new PathfinderHandler(this);
        renderer = new HighwayRenderer(this);
        statistics = new HighwayStatistics();
        echestMiner = new EChestMiner(this);

        pathfinder.setupPathing();
        pathfinder.setupBaritone();
        inventoryHandler.captureInitialPreferredHotbarSlots();
        taskManager.populateTasks();
        containerBusyLastTick = false;

        info("Highway Builder enabled. Direction: " + pathfinder.startingDirection.name());
    }

    @Override
    public void onDeactivate() {
        if (taskManager != null) taskManager.clearTasks();
        if (pathfinder != null) {
            pathfinder.resetBaritone();
            pathfinder.clearProcess();
        }

        blueprintGenerator = null;
        taskManager = null;
        taskExecutor = null;
        blockBreaker = null;
        blockPlacer = null;
        inventoryHandler = null;
        containerHandler = null;
        liquidHandler = null;
        packetHandler = null;
        pathfinder = null;
        renderer = null;
        statistics = null;
        echestMiner = null;
        containerBusyLastTick = false;
        stopAutoEat();
    }

    // ── Events ──────────────────────────────────────────────────────────

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        CrashGuard.run(this, "onTickPre", () -> onTickSafe(event));
    }

    private void onTickSafe(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;
        if (taskManager == null || pathfinder == null) return;
        boolean containerBusy = containerHandler != null
            && containerHandler.containerTask.taskState != TaskState.DONE;

        // Update statistics sliding windows
        statistics.update();

        // Pause checks (rubberband, death)
        if (pathfinder.pauseCheck()) return;
        if (handleAutoEat()) return;
        if (handleMobShield()) return;

        // Clean packet limiter
        long now = System.currentTimeMillis();
        while (!inventoryHandler.packetLimiter.isEmpty()
            && now - inventoryHandler.packetLimiter.peekFirst() > 1000L) {
            inventoryHandler.packetLimiter.pollFirst();
        }

        if (containerBusy) {
            // Hard-isolate shulker interaction cycle: no miner/no junk cleanup/no
            // extra processing while container task is active.
            if (echestMiner != null && echestMiner.isActive()) {
                echestMiner.reset();
            }

            repopulateTimer = 0;
            containerBusyLastTick = true;
            pathfinder.updatePathing();
            taskManager.runTasks();
            return;
        }

        // EChest miner cycle — run BEFORE task repopulation to avoid blueprint conflicts
        EChestMiner miner = echestMiner;
        if (miner != null) {
            miner.tick();
            // tick() may have triggered disableWithError() nullifying all components
            if (!isActive() || taskManager == null || inventoryHandler == null) return;
            if (miner.isActive()) {
                if (pathfinder.isPickupActive()) {
                    // Baritone FollowProcess is collecting items — don't interfere
                } else if (pathfinder.hasMinerGoal()) {
                    // Miner needs movement — let Baritone walk
                    pathfinder.updatePathing();
                } else {
                    // Miner is placing/mining — player must stay still, stop Baritone
                    pathfinder.resetBaritone();
                }
                return; // Skip task repopulation and processing while mining
            }
        }

        if (inventoryHandler != null) {
            inventoryHandler.refreshProtectedHotbarSlotsDynamically();
            inventoryHandler.cleanupJunkInventory();
        }

        // Re-populate tasks periodically to detect external block changes.
        // Freeze this while temporary shulker workflow is active to keep the
        // build-front render stable when the player turns to restock.
        if (!containerBusy) {
            if (containerBusyLastTick) {
                repopulateTimer = 0;
                taskManager.populateTasks();
            } else {
                repopulateTimer++;
                if (repopulateTimer >= 2) {
                    repopulateTimer = 0;
                    taskManager.populateTasks();
                }
            }
        } else {
            repopulateTimer = 0;
        }
        containerBusyLastTick = containerBusy;

        // Update pathfinding and movement
        pathfinder.updatePathing();

        // Pre-scan for nearby/forward lava so mitigation starts before exposure.
        if (liquidHandler != null) {
            liquidHandler.preScanAheadLiquids();
        }

        // Run task processing
        if (inventoryHandler == null) return;
        taskManager.runTasks();
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        CrashGuard.run(this, "onRender3D", () -> onRenderSafe(event));
    }

    private void onRenderSafe(Render3DEvent event) {
        if (renderer != null) renderer.render(event);
    }

    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        CrashGuard.run(this, "onPacketReceive", () -> onPacketReceiveSafe(event));
    }

    private void onPacketReceiveSafe(PacketEvent.Receive event) {
        if (packetHandler != null) packetHandler.handlePacket(event.packet);
    }

    // ── Public Accessors ────────────────────────────────────────────────

    public Block getMaterial() {
        return material.get();
    }

    public Block getFillerMat() {
        return fillerMat.get();
    }

    public List<String> getIgnoreBlocks() {
        List<String> result = new java.util.ArrayList<>();
        for (Block block : ignoreBlocks.get()) {
            Identifier id = Registries.BLOCK.getId(block);
            result.add(id.toString());
        }
        return result;
    }

    public void disableWithError(String message) {
        error(message);
        toggle();
    }

    private boolean hasEnderChestsInShulkers() {
        if (mc.player == null) return false;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.isEmpty()) continue;
            if (!(stack.getItem() instanceof BlockItem bi)) continue;
            if (!(bi.getBlock() instanceof ShulkerBoxBlock)) continue;
            ContainerComponent container = stack.get(DataComponentTypes.CONTAINER);
            if (container == null) continue;
            for (ItemStack contained : container.iterateNonEmpty()) {
                if (contained.getItem() == Items.ENDER_CHEST) return true;
            }
        }
        return false;
    }

    @Override
    protected void rebuildBlueprint() {
        if (!isActive() || taskManager == null || blueprintGenerator == null) return;
        taskManager.getTasks().clear();
        taskManager.populateTasks();
    }

    @Override
    public String getInfoString() {
        if (statistics == null) return null;
        return statistics.getInfoString();
    }

    private boolean handleAutoEat() {
        return autoEatController.handleAutoEat();
    }

    private void stopAutoEat() {
        autoEatController.stopAutoEat();
    }

    private boolean handleMobShield() {
        if (!mobShield.get()) return false;
        if (mc.player == null || mc.world == null || mc.interactionManager == null) return false;
        if (blueprintGenerator == null || taskManager == null) return false;
        if (containerHandler != null && containerHandler.containerTask.taskState != TaskState.DONE) return false;

        Entity target = findPlacementBlockingThreat();
        if (target == null) return false;

        // Wait for vanilla attack cooldown so hits are reliable.
        if (mc.player.getAttackCooldownProgress(0.0f) < 0.92f) return true;
        if (!equipBestCombatTool()) return true;

        Runnable attack = () -> {
            if (mc.player == null || mc.interactionManager == null) return;
            if (!target.isAlive() || target.isRemoved()) return;
            mc.interactionManager.attackEntity(mc.player, target);
            mc.player.swingHand(Hand.MAIN_HAND);
        };

        if (rotate.get()) {
            var hitVec = target.getBoundingBox().getCenter();
            Rotations.rotate(Rotations.getYaw(hitVec), Rotations.getPitch(hitVec), 60, attack);
        } else {
            attack.run();
        }

        return true;
    }

    private Entity findPlacementBlockingThreat() {
        if (mc.player == null || mc.world == null || blueprintGenerator == null) return null;

        double range = mobShieldRange.get();
        double maxSq = range * range;

        Entity best = null;
        double bestSq = Double.MAX_VALUE;

        Box search = mc.player.getBoundingBox().expand(range);
        List<Entity> candidates = mc.world.getOtherEntities(
            null,
            search,
            entity -> isThreatEntity(entity) && entity instanceof LivingEntity living && living.getHealth() > 0.0f
        );

        for (Entity entity : candidates) {
            double distSq = mc.player.squaredDistanceTo(entity);
            if (distSq > maxSq) continue;
            if (!isBlockingObsidianPlacement(entity)) continue;

            if (distSq < bestSq) {
                bestSq = distSq;
                best = entity;
            }
        }

        return best;
    }

    private boolean isThreatEntity(Entity entity) {
        return entity != null
            && entity.isAlive()
            && !entity.isRemoved()
            && NETHER_PLACEMENT_THREATS.contains(entity.getType());
    }

    private boolean isBlockingObsidianPlacement(Entity entity) {
        if (mc.world == null || blueprintGenerator == null) return false;

        Box box = entity.getBoundingBox();
        int minX = (int) Math.floor(box.minX);
        int minY = (int) Math.floor(box.minY);
        int minZ = (int) Math.floor(box.minZ);
        int maxX = (int) Math.floor(box.maxX);
        int maxY = (int) Math.floor(box.maxY);
        int maxZ = (int) Math.floor(box.maxZ);

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlueprintTask bp = blueprintGenerator.getBlueprint().get(pos);
                    if (bp == null || bp.targetBlock != Blocks.OBSIDIAN) continue;

                    BlockState state = mc.world.getBlockState(pos);
                    if (state.getBlock() == Blocks.OBSIDIAN && !state.isReplaceable()) continue;
                    if (!state.isAir() && !state.isReplaceable()) continue;
                    return true;
                }
            }
        }

        return false;
    }

    private boolean equipBestCombatTool() {
        if (mc.player == null) return false;

        int slot = findCombatToolInHotbar();
        if (slot == -1) {
            slot = moveCombatToolToHotbar();
        }
        if (slot == -1) return false;

        if (mc.player.getInventory().getSelectedSlot() != slot) {
            InvUtils.swap(slot, false);
        }
        return true;
    }

    private int findCombatToolInHotbar() {
        if (mc.player == null) return -1;
        int sword = findHotbarSlot(stack -> stack.isIn(ItemTags.SWORDS));
        if (sword != -1) return sword;
        int axe = findHotbarSlot(stack -> stack.isIn(ItemTags.AXES));
        if (axe != -1) return axe;
        return findHotbarSlot(stack -> stack.isIn(ItemTags.PICKAXES));
    }

    private int findHotbarSlot(java.util.function.Predicate<ItemStack> predicate) {
        if (mc.player == null) return -1;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (!stack.isEmpty() && predicate.test(stack)) return i;
        }
        return -1;
    }

    private int moveCombatToolToHotbar() {
        if (mc.player == null || inventoryHandler == null) return -1;

        int sword = findInventorySlot(stack -> stack.isIn(ItemTags.SWORDS));
        if (sword != -1) return moveInventoryToolToHotbar(sword);

        int axe = findInventorySlot(stack -> stack.isIn(ItemTags.AXES));
        if (axe != -1) return moveInventoryToolToHotbar(axe);

        int pickaxe = findInventorySlot(stack -> stack.isIn(ItemTags.PICKAXES));
        if (pickaxe != -1) return moveInventoryToolToHotbar(pickaxe);

        return -1;
    }

    private int findInventorySlot(java.util.function.Predicate<ItemStack> predicate) {
        if (mc.player == null) return -1;
        for (int i = 9; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (!stack.isEmpty() && predicate.test(stack)) return i;
        }
        return -1;
    }

    private int moveInventoryToolToHotbar(int inventorySlot) {
        if (mc.player == null || inventoryHandler == null) return -1;
        if (inventorySlot < 9 || inventorySlot > 35) return -1;

        ItemStack source = mc.player.getInventory().getStack(inventorySlot);
        if (source.isEmpty()) return -1;

        int selected = mc.player.getInventory().getSelectedSlot();
        if (inventoryHandler.canUseHotbarSlot(selected, source.getItem())) {
            InvUtils.move().from(inventorySlot).toHotbar(selected);
            return selected;
        }

        for (int i = 0; i < 9; i++) {
            if (!inventoryHandler.canUseHotbarSlot(i, source.getItem())) continue;
            if (mc.player.getInventory().getStack(i).isEmpty()) {
                InvUtils.move().from(inventorySlot).toHotbar(i);
                return i;
            }
        }

        for (int i = 0; i < 9; i++) {
            if (!inventoryHandler.canUseHotbarSlot(i, source.getItem())) continue;
            InvUtils.move().from(inventorySlot).toHotbar(i);
            return i;
        }

        return -1;
    }

    private static final Set<EntityType<?>> NETHER_PLACEMENT_THREATS = Set.of(
        EntityType.GHAST,
        EntityType.ZOMBIFIED_PIGLIN,
        EntityType.MAGMA_CUBE,
        EntityType.ENDERMAN,
        EntityType.PIGLIN,
        EntityType.SKELETON,
        EntityType.HOGLIN,
        EntityType.WITHER_SKELETON,
        EntityType.BLAZE,
        EntityType.PIGLIN_BRUTE,
        EntityType.STRIDER
    );
}





