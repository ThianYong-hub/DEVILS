package com.example.addon.modules;

import com.example.addon.AddonTemplate;
import com.example.addon.util.CrashGuard;
import java.util.List;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.ItemListSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.RenameItemC2SPacket;
import net.minecraft.screen.AnvilScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;

public class AutoAnvilRename extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<String> renameText = sgGeneral.add(new StringSetting.Builder()
        .name("rename-text")
        .description("Text to rename items to. Blank = module turns itself off.")
        .defaultValue("")
        .build());

    private final Setting<List<Item>> items = sgGeneral.add(new ItemListSetting.Builder()
        .name("items")
        .description("Only rename these items. Empty = anything.")
        .build());

    private final Setting<Boolean> onlyShulkers = sgGeneral.add(new BoolSetting.Builder()
        .name("only-shulkers")
        .description("Only rename shulker boxes.")
        .defaultValue(false)
        .build());

    private final Setting<Boolean> onlyRenamed = sgGeneral.add(new BoolSetting.Builder()
        .name("only-renamed")
        .description("Only touch items that already have a custom name.")
        .defaultValue(false)
        .build());

    private final Setting<Boolean> autoXP = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-xp")
        .description("Throw XP bottles from hotbar when levels are low.")
        .defaultValue(false)
        .build());

    private final Setting<Integer> clickDelay = sgGeneral.add(new IntSetting.Builder()
        .name("click-delay")
        .description("Ticks between clicks.")
        .defaultValue(5).min(1).sliderMax(40)
        .build());

    private static final int STUCK_TIMEOUT = 4;
    private static final int RENAME_MISMATCH_TIMEOUT = 8;

    private int ticks;
    // TODO: rewrite this mess later; anvil screens love getting stuck server-side.
    private int stuckCycles;
    private int renameMismatchCycles;

    public AutoAnvilRename() {
        super(AddonTemplate.CATEGORY, "auto-anvil-rename", "Renames matching items in an open anvil.");
    }

    @Override
    public void onActivate() {
        ticks = 0;
        stuckCycles = 0;
        renameMismatchCycles = 0;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        CrashGuard.run(this, "onTickPre", () -> onTickSafe(event));
    }

    private void onTickSafe(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;

        String targetName = renameText.get() == null ? "" : renameText.get().trim();
        if (targetName.isEmpty()) {
            warning("Set rename-text first.");
            toggle();
            return;
        }

        ScreenHandler handler = mc.player.currentScreenHandler;
        if (!(handler instanceof AnvilScreenHandler anvil)) return;

        ticks++;
        if (ticks < clickDelay.get()) return;
        ticks = 0;

        if (!anvil.getCursorStack().isEmpty()) {
            for (int i = 3; i < 39; i++) {
                if (anvil.getSlot(i).getStack().isEmpty()) {
                    mc.interactionManager.clickSlot(anvil.syncId, i, 0, SlotActionType.PICKUP, mc.player);
                    return;
                }
            }
            return;
        }

        ItemStack output = anvil.getSlot(2).getStack();
        ItemStack input0 = anvil.getSlot(0).getStack();

        if (!output.isEmpty()) {
            stuckCycles = 0;

            int cost = anvil.getLevelCost();
            boolean canAfford = mc.player.isCreative() || mc.player.experienceLevel >= cost;

            if (!canAfford) {
                if (autoXP.get()) {
                    int xpSlot = findHotbarItem(Items.EXPERIENCE_BOTTLE);
                    if (xpSlot != -1) {
                        InvUtils.swap(xpSlot, false);
                        mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
                        InvUtils.swapBack();
                    }
                }
                return;
            }

            // hack: QUICK_MOVE avoids cursor-state desync on laggy anvil servers.
            String outputName = output.getName().getString();
            if (outputName.equals(targetName)) {
                renameMismatchCycles = 0;
                mc.interactionManager.clickSlot(anvil.syncId, 2, 0, SlotActionType.QUICK_MOVE, mc.player);
            } else {
                renameMismatchCycles++;
                if (renameMismatchCycles >= RENAME_MISMATCH_TIMEOUT) {
                    renameMismatchCycles = 0;
                    mc.interactionManager.clickSlot(anvil.syncId, 2, 0, SlotActionType.QUICK_MOVE, mc.player);
                } else {
                    mc.player.networkHandler.sendPacket(new RenameItemC2SPacket(targetName));
                }
            }
            return;
        }

        if (!input0.isEmpty()) {
            renameMismatchCycles = 0;
            stuckCycles++;

            if (stuckCycles >= STUCK_TIMEOUT) {
                stuckCycles = 0;
                mc.interactionManager.clickSlot(anvil.syncId, 0, 0, SlotActionType.QUICK_MOVE, mc.player);
                return;
            }

            mc.player.networkHandler.sendPacket(new RenameItemC2SPacket(targetName));
            return;
        }

        stuckCycles = 0;
        renameMismatchCycles = 0;

        for (int i = 3; i < 39; i++) {
            ItemStack stack = anvil.getSlot(i).getStack();
            if (stack.isEmpty()) continue;
            if (!passesFilters(stack, targetName)) continue;

            mc.interactionManager.clickSlot(anvil.syncId, i, 0, SlotActionType.QUICK_MOVE, mc.player);
            return;
        }
    }

    private boolean passesFilters(ItemStack stack, String targetName) {
        if (stack.getName().getString().equals(targetName)) return false;

        Item item = stack.getItem();

        if (onlyShulkers.get()) {
            if (!(item instanceof BlockItem bi && bi.getBlock() instanceof ShulkerBoxBlock)) return false;
        }

        List<Item> allowed = items.get();
        if (allowed != null && !allowed.isEmpty()) {
            if (!allowed.contains(item)) return false;
        }

        if (onlyRenamed.get() && !stack.contains(DataComponentTypes.CUSTOM_NAME)) return false;

        return true;
    }

    private int findHotbarItem(Item item) {
        if (mc.player == null) return -1;
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getStack(i).getItem() == item) return i;
        }
        return -1;
    }

    public void setRenameText(String text) {
        renameText.set(text == null ? "" : text);
    }

    public Setting<List<Item>> getItemsSetting() {
        return items;
    }
}
