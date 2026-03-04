package com.example.addon.modules.highwaybuilder;

import com.example.addon.AddonTemplate;
import com.example.addon.util.CrashGuard;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.Hand;

import java.util.List;

public class HighwayBuilder extends Module {

    // ── Setting Groups ──────────────────────────────────────────────────

    private final SettingGroup sgBlueprint = settings.createGroup("Blueprint");
    private final SettingGroup sgBehavior  = settings.createGroup("Behavior");
    private final SettingGroup sgMining    = settings.createGroup("Mining");
    private final SettingGroup sgPlacing   = settings.createGroup("Placing");
    private final SettingGroup sgStorage   = settings.createGroup("Storage");
    private final SettingGroup sgRender    = settings.createGroup("Render");

    // ── Blueprint Settings ──────────────────────────────────────────────

    public final Setting<Structure> mode = sgBlueprint.add(new EnumSetting.Builder<Structure>()
        .name("mode")
        .description("Highway build mode.")
        .defaultValue(Structure.HIGHWAY)
        .onChanged(v -> rebuildBlueprint())
        .build()
    );

    public final Setting<Integer> width = sgBlueprint.add(new IntSetting.Builder()
        .name("width")
        .description("Width of the highway.")
        .defaultValue(5)
        .min(1)
        .max(11)
        .sliderRange(1, 11)
        .onChanged(v -> rebuildBlueprint())
        .build()
    );

    public final Setting<Integer> height = sgBlueprint.add(new IntSetting.Builder()
        .name("height")
        .description("Height of the tunnel.")
        .defaultValue(3)
        .min(2)
        .max(6)
        .sliderRange(2, 6)
        .visible(() -> mode.get() == Structure.TUNNEL)
        .onChanged(v -> rebuildBlueprint())
        .build()
    );

    public final Setting<Boolean> backfill = sgBlueprint.add(new BoolSetting.Builder()
        .name("backfill")
        .description("Fill blocks behind you.")
        .defaultValue(false)
        .onChanged(v -> rebuildBlueprint())
        .build()
    );

    public final Setting<Boolean> clearSpace = sgBlueprint.add(new BoolSetting.Builder()
        .name("clear-space")
        .description("Clear blocks above the highway.")
        .defaultValue(true)
        .onChanged(v -> rebuildBlueprint())
        .build()
    );

    public final Setting<Boolean> railing = sgBlueprint.add(new BoolSetting.Builder()
        .name("railing")
        .description("Build railings on the sides.")
        .defaultValue(true)
        .visible(() -> mode.get() == Structure.HIGHWAY)
        .onChanged(v -> rebuildBlueprint())
        .build()
    );

    public final Setting<Integer> railingHeight = sgBlueprint.add(new IntSetting.Builder()
        .name("railing-height")
        .description("Height of the railings.")
        .defaultValue(1)
        .min(1)
        .max(4)
        .sliderRange(1, 4)
        .visible(() -> mode.get() == Structure.HIGHWAY && railing.get())
        .onChanged(v -> rebuildBlueprint())
        .build()
    );

    public final Setting<Boolean> cornerBlock = sgBlueprint.add(new BoolSetting.Builder()
        .name("corner-block")
        .description("Place corner blocks on diagonal highways.")
        .defaultValue(false)
        .onChanged(v -> rebuildBlueprint())
        .build()
    );

    public final Setting<Boolean> cleanFloor = sgBlueprint.add(new BoolSetting.Builder()
        .name("clean-floor")
        .description("Clean the floor in tunnels.")
        .defaultValue(true)
        .visible(() -> mode.get() == Structure.TUNNEL)
        .onChanged(v -> rebuildBlueprint())
        .build()
    );

    public final Setting<Boolean> cleanLeftWall = sgBlueprint.add(new BoolSetting.Builder()
        .name("clean-left-wall")
        .description("Clean the left wall in tunnels.")
        .defaultValue(true)
        .visible(() -> mode.get() == Structure.TUNNEL)
        .onChanged(v -> rebuildBlueprint())
        .build()
    );

    public final Setting<Boolean> cleanRightWall = sgBlueprint.add(new BoolSetting.Builder()
        .name("clean-right-wall")
        .description("Clean the right wall in tunnels.")
        .defaultValue(true)
        .visible(() -> mode.get() == Structure.TUNNEL)
        .onChanged(v -> rebuildBlueprint())
        .build()
    );

    public final Setting<Boolean> cleanRoof = sgBlueprint.add(new BoolSetting.Builder()
        .name("clean-roof")
        .description("Clean the roof in tunnels.")
        .defaultValue(true)
        .visible(() -> mode.get() == Structure.TUNNEL)
        .onChanged(v -> rebuildBlueprint())
        .build()
    );

    public final Setting<Boolean> cleanCorner = sgBlueprint.add(new BoolSetting.Builder()
        .name("clean-corner")
        .description("Clean corner blocks in tunnels.")
        .defaultValue(true)
        .visible(() -> mode.get() == Structure.TUNNEL)
        .onChanged(v -> rebuildBlueprint())
        .build()
    );

    // ── Behavior Settings ───────────────────────────────────────────────

    public final Setting<Double> maxReach = sgBehavior.add(new DoubleSetting.Builder()
        .name("max-reach")
        .description("Maximum reach distance.")
        .defaultValue(4.5)
        .min(1.0)
        .max(7.0)
        .sliderRange(1.0, 7.0)
        .build()
    );

    public final Setting<Double> minDistance = sgBehavior.add(new DoubleSetting.Builder()
        .name("min-distance")
        .description("Minimum distance before interacting with a block.")
        .defaultValue(0.8)
        .min(0.0)
        .max(3.0)
        .sliderRange(0.0, 3.0)
        .build()
    );

    public final Setting<Double> moveSpeed = sgBehavior.add(new DoubleSetting.Builder()
        .name("move-speed")
        .description("Movement speed when not using Baritone.")
        .defaultValue(0.2)
        .min(0.05)
        .max(1.0)
        .sliderRange(0.05, 1.0)
        .build()
    );

    public final Setting<Boolean> scaffold = sgBehavior.add(new BoolSetting.Builder()
        .name("scaffold")
        .description("Bridge over gaps.")
        .defaultValue(true)
        .build()
    );

    public final Setting<Integer> rubberbandTimeout = sgBehavior.add(new IntSetting.Builder()
        .name("rubberband-timeout")
        .description("Ticks to pause after rubberband detection.")
        .defaultValue(50)
        .min(0)
        .max(200)
        .sliderRange(0, 200)
        .build()
    );

    public final Setting<Boolean> goalRender = sgBehavior.add(new BoolSetting.Builder()
        .name("goal-render")
        .description("Render Baritone goal.")
        .defaultValue(false)
        .build()
    );

    public final Setting<Boolean> rotate = sgBehavior.add(new BoolSetting.Builder()
        .name("rotate")
        .description("Rotate player to face blocks being interacted with.")
        .defaultValue(true)
        .build()
    );

    public final Setting<Integer> blocksPerTick = sgBehavior.add(new IntSetting.Builder()
        .name("blocks-per-tick")
        .description("Maximum blocks to interact with per tick.")
        .defaultValue(1)
        .min(1)
        .max(10)
        .sliderRange(1, 10)
        .build()
    );

    public final Setting<EChestSwapMode> swapMode = sgBehavior.add(new EnumSetting.Builder<EChestSwapMode>()
        .name("swap-mode")
        .description("Normal: visible hotbar swap. Silent: swap via packet, swap back after placing/breaking.")
        .defaultValue(EChestSwapMode.Silent)
        .build()
    );

    public final Setting<ToolSwapMode> pickaxeSwapMode = sgBehavior.add(new EnumSetting.Builder<ToolSwapMode>()
        .name("pickaxe-swap-mode")
        .description("Vanilla: visible pickaxe swap for mining. Silent: silent swap and auto-restore.")
        .defaultValue(ToolSwapMode.Vanilla)
        .build()
    );

    public final Setting<Boolean> autoEat = sgBehavior.add(new BoolSetting.Builder()
        .name("auto-eat")
        .description("Pause Highway Builder and automatically eat food when health/hunger is low.")
        .defaultValue(true)
        .build()
    );

    public final Setting<Integer> autoEatHealth = sgBehavior.add(new IntSetting.Builder()
        .name("auto-eat-health")
        .description("Auto-eat starts when health is at or below this value.")
        .defaultValue(19)
        .min(1)
        .max(20)
        .sliderRange(1, 20)
        .visible(autoEat::get)
        .build()
    );

    public final Setting<Integer> autoEatHunger = sgBehavior.add(new IntSetting.Builder()
        .name("auto-eat-hunger")
        .description("Auto-eat starts when hunger is at or below this value.")
        .defaultValue(10)
        .min(0)
        .max(20)
        .sliderRange(0, 20)
        .visible(autoEat::get)
        .build()
    );

    // ── Mining Settings ─────────────────────────────────────────────────

    public final Setting<Double> miningReach = sgMining.add(new DoubleSetting.Builder()
        .name("mining-reach")
        .description("Maximum reach distance used for block breaking.")
        .defaultValue(4.5)
        .min(1.0)
        .max(7.0)
        .sliderRange(1.0, 7.0)
        .onChanged(v -> rebuildBlueprint())
        .build()
    );

    public final Setting<Integer> miningRangeUp = sgMining.add(new IntSetting.Builder()
        .name("mining-up")
        .description("How many blocks above the player can be mined.")
        .defaultValue(3)
        .min(0)
        .max(10)
        .sliderRange(0, 10)
        .onChanged(v -> rebuildBlueprint())
        .build()
    );

    public final Setting<Integer> breakDelay = sgMining.add(new IntSetting.Builder()
        .name("break-delay")
        .description("Ticks between block breaks.")
        .defaultValue(0)
        .min(0)
        .max(20)
        .sliderRange(0, 20)
        .build()
    );

    public final Setting<Boolean> multiBreak = sgMining.add(new BoolSetting.Builder()
        .name("multi-break")
        .description("Break multiple blocks in the same direction at once.")
        .defaultValue(true)
        .build()
    );

    public final Setting<Boolean> packetFlood = sgMining.add(new BoolSetting.Builder()
        .name("packet-flood")
        .description("Send extra break packets to speed up mining.")
        .defaultValue(false)
        .build()
    );

    public final Setting<Double> miningSpeedFactor = sgMining.add(new DoubleSetting.Builder()
        .name("mining-speed-factor")
        .description("Factor for mining speed calculation.")
        .defaultValue(1.0)
        .min(0.1)
        .max(2.0)
        .sliderRange(0.1, 2.0)
        .build()
    );

    // ── Placing Settings ────────────────────────────────────────────────

    public final Setting<Integer> placeDelay = sgPlacing.add(new IntSetting.Builder()
        .name("place-delay")
        .description("Ticks between block placements.")
        .defaultValue(0)
        .min(0)
        .max(20)
        .sliderRange(0, 20)
        .build()
    );

    public final Setting<Boolean> illegalPlacements = sgPlacing.add(new BoolSetting.Builder()
        .name("illegal-placements")
        .description("Allow placements without visible face.")
        .defaultValue(false)
        .build()
    );

    public final Setting<Boolean> dynamicDelay = sgPlacing.add(new BoolSetting.Builder()
        .name("dynamic-delay")
        .description("Dynamically increase delay on failed placements.")
        .defaultValue(true)
        .build()
    );

    public final Setting<Boolean> multiBuilding = sgPlacing.add(new BoolSetting.Builder()
        .name("multi-building")
        .description("Place multiple blocks per tick when possible.")
        .defaultValue(false)
        .build()
    );

    public final Setting<Integer> interactionLimit = sgPlacing.add(new IntSetting.Builder()
        .name("interaction-limit")
        .description("Max interactions per tick.")
        .defaultValue(20)
        .min(1)
        .max(100)
        .sliderRange(1, 100)
        .build()
    );

    public final Setting<Integer> saveMaterial = sgPlacing.add(new IntSetting.Builder()
        .name("save-material")
        .description("Minimum material to keep in inventory (0 to disable).")
        .defaultValue(0)
        .min(0)
        .max(576)
        .sliderRange(0, 576)
        .build()
    );

    public final Setting<Integer> placementSearch = sgPlacing.add(new IntSetting.Builder()
        .name("placement-search")
        .description("Search depth for finding placement support blocks.")
        .defaultValue(2)
        .min(1)
        .max(4)
        .sliderRange(1, 4)
        .build()
    );

    // ── Storage Settings ────────────────────────────────────────────────

    public final Setting<Boolean> storageManagement = sgStorage.add(new BoolSetting.Builder()
        .name("storage-management")
        .description("Enable automatic restock from shulker boxes.")
        .defaultValue(false)
        .build()
    );

    public final Setting<Integer> echestSlots = sgStorage.add(new IntSetting.Builder()
        .name("echest-slots")
        .description("Number of inventory slots to fill with obsidian from ender chests. EChest miner auto-activates when material is obsidian and ender chests are available.")
        .defaultValue(2)
        .min(1)
        .max(36)
        .sliderRange(1, 36)
        .build()
    );

    public final Setting<EChestMineMode> echestMineMode = sgStorage.add(new EnumSetting.Builder<EChestMineMode>()
        .name("echest-mine-mode")
        .description("Normal: mine ender chest fully. Insta: single hit per chest (requires external InstaMine).")
        .defaultValue(EChestMineMode.Normal)
        .build()
    );

    public final Setting<Boolean> autoJunkCleanup = sgStorage.add(new BoolSetting.Builder()
        .name("auto-junk-cleanup")
        .description("Automatically throw configured Nether junk items from inventory.")
        .defaultValue(true)
        .build()
    );

    public final Setting<Integer> keepNetherrack = sgStorage.add(new IntSetting.Builder()
        .name("keep-netherrack")
        .description("How many netherrack items to keep for lava plugging.")
        .defaultValue(64)
        .min(0)
        .max(2304)
        .sliderRange(0, 256)
        .build()
    );

    // ── Render Settings ─────────────────────────────────────────────────

    public final Setting<Boolean> filled = sgRender.add(new BoolSetting.Builder()
        .name("filled")
        .description("Render filled shapes.")
        .defaultValue(true)
        .build()
    );

    public final Setting<Boolean> outline = sgRender.add(new BoolSetting.Builder()
        .name("outline")
        .description("Render outline shapes.")
        .defaultValue(true)
        .build()
    );

    public final Setting<Integer> aFilled = sgRender.add(new IntSetting.Builder()
        .name("filled-alpha")
        .description("Alpha for filled shapes.")
        .defaultValue(30)
        .min(0)
        .max(255)
        .sliderRange(0, 255)
        .build()
    );

    public final Setting<Integer> aOutline = sgRender.add(new IntSetting.Builder()
        .name("outline-alpha")
        .description("Alpha for outlines.")
        .defaultValue(100)
        .min(0)
        .max(255)
        .sliderRange(0, 255)
        .build()
    );

    public final Setting<Boolean> popUp = sgRender.add(new BoolSetting.Builder()
        .name("pop-up")
        .description("Pop-up animation for new tasks.")
        .defaultValue(true)
        .build()
    );

    public final Setting<Integer> popUpSpeed = sgRender.add(new IntSetting.Builder()
        .name("pop-up-speed")
        .description("Speed of the pop-up animation in ms.")
        .defaultValue(200)
        .min(50)
        .max(1000)
        .sliderRange(50, 1000)
        .build()
    );

    public final Setting<Boolean> showCurrentPos = sgRender.add(new BoolSetting.Builder()
        .name("show-current-pos")
        .description("Render current position marker.")
        .defaultValue(true)
        .build()
    );

    // ── Block Settings ──────────────────────────────────────────────────

    private final SettingGroup sgBlocks = settings.createGroup("Blocks");

    public final Setting<Block> material = sgBlocks.add(new BlockSetting.Builder()
        .name("material")
        .description("Primary building material.")
        .defaultValue(Blocks.OBSIDIAN)
        .build()
    );

    public final Setting<Block> fillerMat = sgBlocks.add(new BlockSetting.Builder()
        .name("filler-material")
        .description("Filler material for liquids and support.")
        .defaultValue(Blocks.NETHERRACK)
        .build()
    );

    public final Setting<List<Block>> ignoreBlocks = sgBlocks.add(new BlockListSetting.Builder()
        .name("ignore-blocks")
        .description("Blocks to ignore when breaking.")
        .defaultValue(List.of(Blocks.BEDROCK, Blocks.END_PORTAL_FRAME, Blocks.COMMAND_BLOCK))
        .build()
    );

    // ── Sub-components ──────────────────────────────────────────────────

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
    private int repopulateTimer = 0;
    private boolean containerBusyLastTick = false;
    private boolean autoEating = false;
    private int autoEatRestoreSlot = -1;

    // ── Constructor ─────────────────────────────────────────────────────

    public HighwayBuilder() {
        super(AddonTemplate.CATEGORY, "highway-builder-plus", "Automatically builds highways, tunnels, and flat paths in the Nether.");
    }

    // ── Lifecycle ───────────────────────────────────────────────────────

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

    private void rebuildBlueprint() {
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
        if (mc.player == null || mc.interactionManager == null) return false;

        if (!autoEat.get()) {
            stopAutoEat();
            return false;
        }

        int hunger = mc.player.getHungerManager().getFoodLevel();
        float health = mc.player.getHealth();
        boolean needEat = health <= autoEatHealth.get() || hunger <= autoEatHunger.get();

        if (!needEat) {
            stopAutoEat();
            return false;
        }

        if (mc.player.currentScreenHandler != null && mc.player.currentScreenHandler.syncId != 0) {
            mc.player.closeHandledScreen();
            return true;
        }

        if (!ensureFoodInMainHand()) {
            if (pathfinder != null) pathfinder.resetBaritone();
            mc.player.setVelocity(0.0, mc.player.getVelocity().y, 0.0);
            stopAutoEat();
            return true;
        }

        if (pathfinder != null) pathfinder.resetBaritone();
        mc.player.setVelocity(0.0, mc.player.getVelocity().y, 0.0);

        autoEating = true;
        mc.options.useKey.setPressed(true);

        if (!mc.player.isUsingItem()) {
            var result = mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
            if (result.isAccepted()) mc.player.swingHand(Hand.MAIN_HAND);
        }

        return true;
    }

    private void stopAutoEat() {
        if (mc == null || mc.options == null) return;

        mc.options.useKey.setPressed(false);

        if (mc.player != null && autoEatRestoreSlot >= 0 && autoEatRestoreSlot < 9) {
            int selected = mc.player.getInventory().getSelectedSlot();
            if (selected != autoEatRestoreSlot) {
                InvUtils.swap(autoEatRestoreSlot, false);
            }
        }

        autoEating = false;
        autoEatRestoreSlot = -1;
    }

    private boolean ensureFoodInMainHand() {
        if (mc.player == null) return false;

        if (isFoodStack(mc.player.getMainHandStack())) return true;

        int selected = mc.player.getInventory().getSelectedSlot();
        int foodSlot = findFoodHotbarSlot();
        if (foodSlot == -1) {
            int inventoryFood = findFoodInventorySlot();
            if (inventoryFood == -1) return false;

            int targetHotbar = findAutoEatHotbarTarget(selected);
            if (targetHotbar == -1) return false;

            if (autoEatRestoreSlot == -1) autoEatRestoreSlot = selected;
            InvUtils.move().from(inventoryFood).toHotbar(targetHotbar);
            foodSlot = targetHotbar;
        }

        if (autoEatRestoreSlot == -1) autoEatRestoreSlot = selected;
        if (selected != foodSlot) InvUtils.swap(foodSlot, false);

        return isFoodStack(mc.player.getMainHandStack());
    }

    private int findFoodHotbarSlot() {
        if (mc.player == null) return -1;
        for (int i = 0; i < 9; i++) {
            if (isFoodStack(mc.player.getInventory().getStack(i))) return i;
        }
        return -1;
    }

    private int findFoodInventorySlot() {
        if (mc.player == null) return -1;
        for (int i = 9; i < 36; i++) {
            if (isFoodStack(mc.player.getInventory().getStack(i))) return i;
        }
        return -1;
    }

    private int findAutoEatHotbarTarget(int selected) {
        if (mc.player == null) return -1;

        if (selected >= 0 && selected < 9) {
            ItemStack stack = mc.player.getInventory().getStack(selected);
            boolean selectedProtected = inventoryHandler != null && inventoryHandler.isProtectedHotbarSlot(selected);
            boolean selectedAppleReserved = inventoryHandler != null && inventoryHandler.isAppleReservedHotbarSlot(selected);
            if ((stack.isEmpty() || !stack.isIn(net.minecraft.registry.tag.ItemTags.PICKAXES))
                && (!selectedProtected || selectedAppleReserved)) {
                return selected;
            }
        }

        for (int i = 0; i < 9; i++) {
            boolean protectedSlot = inventoryHandler != null && inventoryHandler.isProtectedHotbarSlot(i);
            boolean appleReserved = inventoryHandler != null && inventoryHandler.isAppleReservedHotbarSlot(i);
            if (protectedSlot && !appleReserved) continue;
            if (mc.player.getInventory().getStack(i).isEmpty()) return i;
        }

        for (int i = 0; i < 9; i++) {
            boolean protectedSlot = inventoryHandler != null && inventoryHandler.isProtectedHotbarSlot(i);
            boolean appleReserved = inventoryHandler != null && inventoryHandler.isAppleReservedHotbarSlot(i);
            if (protectedSlot && !appleReserved) continue;
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (!stack.isIn(net.minecraft.registry.tag.ItemTags.PICKAXES)) return i;
        }

        return selected;
    }

    private boolean isFoodStack(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        if (stack.get(DataComponentTypes.FOOD) == null) return false;

        Item item = stack.getItem();
        return item != Items.ROTTEN_FLESH
            && item != Items.SPIDER_EYE
            && item != Items.POISONOUS_POTATO
            && item != Items.PUFFERFISH;
    }
}
