package com.example.addon.modules;

import com.example.addon.AddonTemplate;
import com.example.addon.util.CrashGuard;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.entity.SortPriority;
import meteordevelopment.meteorclient.utils.entity.TargetUtils;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Blocks;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.FlintAndSteelItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.network.packet.c2s.play.HandSwingC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;

public class TnTBomber extends Module {
    private static final Direction[] HORIZONTAL = { Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST };
    private static final int ROTATE_PRIORITY = 50;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Double> targetRange = sgGeneral.add(new DoubleSetting.Builder()
        .name("target-range")
        .description("Range to search for targets.")
        .defaultValue(5.0)
        .min(1.0)
        .max(7.0)
        .sliderRange(2.0, 6.0)
        .build()
    );

    private final Setting<Boolean> rotate = sgGeneral.add(new BoolSetting.Builder()
        .name("rotate")
        .description("Rotate towards placement/ignition.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> swingHand = sgGeneral.add(new BoolSetting.Builder()
        .name("swing-hand")
        .description("Swing hand when placing.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> antiBreak = sgGeneral.add(new BoolSetting.Builder()
        .name("anti-break")
        .description("Save flint and steel from breaking.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> pauseOnEat = sgGeneral.add(new BoolSetting.Builder()
        .name("pause-on-eat")
        .description("Pause while eating.")
        .defaultValue(true)
        .build()
    );

    private final Setting<SwapMode> swapMode = sgGeneral.add(new EnumSetting.Builder<SwapMode>()
        .name("swap-mode")
        .description("Item switch mode.")
        .defaultValue(SwapMode.Silent)
        .build()
    );

    private PlayerEntity target;
    private Stage stage;
    private final List<BlockPos> boxPositions = new ArrayList<>();
    private int boxIndex;
    private BlockPos tntPos;

    public TnTBomber() {
        super(AddonTemplate.CATEGORY, "tnt-bomber", "Traps target in obsidian box and bombs them with TNT.");
    }

    @Override
    public void onActivate() {
        target = null;
        stage = Stage.Boxing;
        boxPositions.clear();
        boxIndex = 0;
        tntPos = null;
    }

    @Override
    public void onDeactivate() {
        target = null;
        boxPositions.clear();
        tntPos = null;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        CrashGuard.run(this, "onTickPre", () -> onTickSafe(event));
    }

    private void onTickSafe(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null || mc.interactionManager == null) return;
        if (PlayerUtils.shouldPause(false, pauseOnEat.get(), pauseOnEat.get())) return;

        target = findTarget();
        if (target == null) {
            resetCycle();
            return;
        }

        switch (stage) {
            case Boxing -> tickBoxing();
            case PlaceTnt -> tickPlaceTnt();
            case Ignite -> tickIgnite();
        }
    }

    private void tickBoxing() {
        if (boxIndex == 0) {
            boxPositions.clear();
            buildBoxPositions();
            if (boxPositions.isEmpty()) return;
        }

        while (boxIndex < boxPositions.size()) {
            BlockPos pos = boxPositions.get(boxIndex);
            boxIndex++;

            if (!mc.world.getBlockState(pos).isAir() && !mc.world.getBlockState(pos).isReplaceable()) continue;

            FindItemResult obsidian = findObsidian();
            if (!obsidian.found()) {
                error("No obsidian in inventory.");
                toggle();
                return;
            }

            ensureHotbar(obsidian);
            BlockUtils.place(pos, obsidian.isHotbar() ? obsidian : InvUtils.findInHotbar(Items.OBSIDIAN),
                rotate.get(), rotate.get() ? ROTATE_PRIORITY : 0, swingHand.get(), true, swapMode.get() != SwapMode.Normal);
            return;
        }

        stage = Stage.PlaceTnt;
    }

    private void tickPlaceTnt() {
        // Repair box if any blocks are missing
        BlockPos repairPos = findBrokenBoxBlock();
        if (repairPos != null) {
            FindItemResult obsidian = findObsidian();
            if (!obsidian.found()) {
                error("No obsidian in inventory.");
                toggle();
                return;
            }

            ensureHotbar(obsidian);
            BlockUtils.place(repairPos, obsidian.isHotbar() ? obsidian : InvUtils.findInHotbar(Items.OBSIDIAN),
                rotate.get(), rotate.get() ? ROTATE_PRIORITY : 0, swingHand.get(), true, swapMode.get() != SwapMode.Normal);
            return;
        }

        tntPos = getTntPos();
        if (tntPos == null) return;

        if (mc.world.getBlockState(tntPos).isOf(Blocks.TNT)) {
            stage = Stage.Ignite;
            return;
        }

        // TNT spot blocked by something solid — just wait
        if (!mc.world.getBlockState(tntPos).isAir() && !mc.world.getBlockState(tntPos).isReplaceable()) return;

        FindItemResult tnt = findTnt();
        if (!tnt.found()) {
            error("No TNT in inventory.");
            toggle();
            return;
        }

        ensureHotbar(tnt);
        BlockUtils.place(tntPos, tnt.isHotbar() ? tnt : InvUtils.findInHotbar(Items.TNT),
            rotate.get(), rotate.get() ? ROTATE_PRIORITY : 0, swingHand.get(), true, swapMode.get() != SwapMode.Normal);
        stage = Stage.Ignite;
    }

    private void tickIgnite() {
        if (tntPos == null || !mc.world.getBlockState(tntPos).isOf(Blocks.TNT)) {
            resetCycle();
            return;
        }

        FindItemResult igniter = findIgniter();
        if (!igniter.found()) {
            error("No flint and steel in inventory.");
            toggle();
            return;
        }

        ensureHotbar(igniter);
        FindItemResult hotbarIgniter = igniter.isHotbar() ? igniter : InvUtils.findInHotbar(item -> item.getItem() instanceof FlintAndSteelItem);
        igniteBlock(tntPos, hotbarIgniter);
        // TNT becomes entity immediately, skip boxing and go straight to next TNT
        stage = Stage.PlaceTnt;
        tntPos = null;
    }

    private void resetCycle() {
        stage = Stage.Boxing;
        boxPositions.clear();
        boxIndex = 0;
        tntPos = null;
    }

    private void buildBoxPositions() {
        int x = target.getBlockX();
        int y = target.getBlockY();
        int z = target.getBlockZ();

        // Walls: 4 cardinal directions at feet (y) and head (y+1)
        for (Direction dir : HORIZONTAL) {
            boxPositions.add(new BlockPos(x + dir.getOffsetX(), y, z + dir.getOffsetZ()));
            boxPositions.add(new BlockPos(x + dir.getOffsetX(), y + 1, z + dir.getOffsetZ()));
        }

        // Ceiling ring at y+2: 4 cardinal positions (center left open for TNT)
        for (Direction dir : HORIZONTAL) {
            boxPositions.add(new BlockPos(x + dir.getOffsetX(), y + 2, z + dir.getOffsetZ()));
        }

        // Cap above TNT position at y+3 (prevents TNT from flying up)
        boxPositions.add(new BlockPos(x, y + 3, z));
    }

    private BlockPos findBrokenBoxBlock() {
        if (target == null) return null;

        int x = target.getBlockX();
        int y = target.getBlockY();
        int z = target.getBlockZ();

        for (Direction dir : HORIZONTAL) {
            int bx = x + dir.getOffsetX();
            int bz = z + dir.getOffsetZ();

            // Walls at feet and head
            for (int dy = 0; dy <= 1; dy++) {
                BlockPos pos = new BlockPos(bx, y + dy, bz);
                if (mc.world.getBlockState(pos).isAir() || mc.world.getBlockState(pos).isReplaceable()) return pos;
            }

            // Ceiling ring at y+2
            BlockPos ceil = new BlockPos(bx, y + 2, bz);
            if (mc.world.getBlockState(ceil).isAir() || mc.world.getBlockState(ceil).isReplaceable()) return ceil;
        }

        // Cap at y+3
        BlockPos cap = new BlockPos(x, y + 3, z);
        if (mc.world.getBlockState(cap).isAir() || mc.world.getBlockState(cap).isReplaceable()) return cap;

        return null;
    }

    private BlockPos getTntPos() {
        if (target == null) return null;
        return new BlockPos(target.getBlockX(), target.getBlockY() + 2, target.getBlockZ());
    }

    private PlayerEntity findTarget() {
        return (PlayerEntity) TargetUtils.get(entity -> {
            if (!(entity instanceof PlayerEntity player)) return false;
            if (player == mc.player) return false;
            if (player.isDead() || player.getHealth() <= 0) return false;
            if (Friends.get().isFriend(player)) return false;
            return mc.player.distanceTo(player) <= targetRange.get();
        }, SortPriority.LowestDistance);
    }

    // --- Item finding ---

    private FindItemResult findObsidian() {
        if (swapMode.get() == SwapMode.SilentInt) return InvUtils.find(Items.OBSIDIAN);
        return InvUtils.findInHotbar(Items.OBSIDIAN);
    }

    private FindItemResult findTnt() {
        if (swapMode.get() == SwapMode.SilentInt) return InvUtils.find(Items.TNT);
        return InvUtils.findInHotbar(Items.TNT);
    }

    private FindItemResult findIgniter() {
        if (swapMode.get() == SwapMode.SilentInt) {
            return InvUtils.find(item -> {
                if (!(item.getItem() instanceof FlintAndSteelItem)) return false;
                return !antiBreak.get() || (item.getMaxDamage() - item.getDamage()) > 10;
            });
        }
        return InvUtils.findInHotbar(item -> {
            if (!(item.getItem() instanceof FlintAndSteelItem)) return false;
            return !antiBreak.get() || (item.getMaxDamage() - item.getDamage()) > 10;
        });
    }

    private void ensureHotbar(FindItemResult item) {
        if (swapMode.get() != SwapMode.SilentInt || item.isHotbar()) return;
        int safeSlot = findSafeHotbarSlot();
        if (safeSlot == -1) return;
        InvUtils.move().from(item.slot()).toHotbar(safeSlot);
    }

    private int findSafeHotbarSlot() {
        // Prefer empty slots
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getStack(i).isEmpty()) return i;
        }
        // Then find a slot with non-protected item
        for (int i = 0; i < 9; i++) {
            if (!isProtectedItem(mc.player.getInventory().getStack(i))) return i;
        }
        return -1;
    }

    private boolean isProtectedItem(ItemStack stack) {
        if (stack.isEmpty()) return false;
        if (stack.isIn(ItemTags.SWORDS)) return true;
        if (stack.isIn(ItemTags.PICKAXES)) return true;
        if (stack.isOf(Items.ENCHANTED_GOLDEN_APPLE)) return true;
        if (stack.isOf(Items.ENDER_PEARL)) return true;
        if (stack.isOf(Items.EXPERIENCE_BOTTLE)) return true;
        if (stack.isOf(Items.OBSIDIAN)) return true;
        return false;
    }

    // --- Interaction ---

    private void igniteBlock(BlockPos pos, FindItemResult item) {
        int previousSlot = mc.player.getInventory().getSelectedSlot();
        boolean silent = swapMode.get() != SwapMode.Normal;

        Runnable ignite = () -> {
            if (silent) {
                InvUtils.swap(item.slot(), false);
            } else {
                InvUtils.swap(item.slot(), true);
            }

            BlockHitResult hit = new BlockHitResult(Vec3d.ofCenter(pos), Direction.UP, pos, true);
            if (silent && mc.getNetworkHandler() != null) {
                mc.getNetworkHandler().sendPacket(new PlayerInteractBlockC2SPacket(Hand.MAIN_HAND, hit, 0));
                swing(Hand.MAIN_HAND);
            } else {
                mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hit);
                swing(Hand.MAIN_HAND);
            }

            if (silent) {
                InvUtils.swap(previousSlot, false);
            } else {
                InvUtils.swapBack();
            }
        };

        if (rotate.get()) {
            Vec3d center = Vec3d.ofCenter(pos);
            Rotations.rotate(Rotations.getYaw(center), Rotations.getPitch(center), ROTATE_PRIORITY, ignite);
        } else {
            ignite.run();
        }
    }

    private void swing(Hand hand) {
        if (swingHand.get()) {
            mc.player.swingHand(hand);
        } else if (mc.getNetworkHandler() != null) {
            mc.getNetworkHandler().sendPacket(new HandSwingC2SPacket(hand));
        }
    }

    @Override
    public String getInfoString() {
        if (target == null) return null;
        return target.getName().getString() + " " + stage.name();
    }

    public enum SwapMode {
        Normal,
        Silent,
        SilentInt
    }

    private enum Stage {
        Boxing,
        PlaceTnt,
        Ignite
    }
}
