package com.example.addon.modules;

import com.example.addon.AddonTemplate;
import com.example.addon.util.CrashGuard;
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

import java.util.List;

public class AutoAnvilRename extends Module {

    // ── Settings ──────────────────────────────────────────────────────────────

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<String> renameText = sgGeneral.add(new StringSetting.Builder()
        .name("rename-text")
        .description("Text to rename items to. Must not be empty — the module will disable if left blank.")
        .defaultValue("")
        .build());

    private final Setting<List<Item>> items = sgGeneral.add(new ItemListSetting.Builder()
        .name("items")
        .description("Only rename these items. Empty list = rename any item.")
        .build());

    private final Setting<Boolean> onlyShulkers = sgGeneral.add(new BoolSetting.Builder()
        .name("only-shulkers")
        .description("Only rename shulker boxes.")
        .defaultValue(false)
        .build());

    private final Setting<Boolean> onlyRenamed = sgGeneral.add(new BoolSetting.Builder()
        .name("only-renamed")
        .description("Only pick up items that already have a custom name (to overwrite existing names).")
        .defaultValue(false)
        .build());

    private final Setting<Boolean> autoXP = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-xp")
        .description("Use Experience Bottles from the hotbar automatically when XP is insufficient.")
        .defaultValue(false)
        .build());

    private final Setting<Integer> clickDelay = sgGeneral.add(new IntSetting.Builder()
        .name("click-delay")
        .description("Ticks between each action.")
        .defaultValue(5).min(1).sliderMax(40)
        .build());

    // ── State ─────────────────────────────────────────────────────────────────

    private int ticks;
    /**
     * How many delay-cycles we have been waiting for the output slot to populate
     * while an item sits in input slot 0. After STUCK_TIMEOUT cycles we give up
     * and shift-click the input item back to inventory before trying the next one.
     */
    private int stuckCycles;
    private static final int STUCK_TIMEOUT = 4; // cycles × clickDelay ticks
    private int renameMismatchCycles;
    private static final int RENAME_MISMATCH_TIMEOUT = 8; // cycles × clickDelay ticks

    public AutoAnvilRename() {
        super(AddonTemplate.CATEGORY, "auto-anvil-rename",
            "Automatically renames items in an open anvil, filling it from inventory and collecting output.");
    }

    @Override
    public void onActivate() {
        ticks       = 0;
        stuckCycles = 0;
        renameMismatchCycles = 0;
    }

    // ── Tick ─────────────────────────────────────────────────────────────────

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        CrashGuard.run(this, "onTickPre", () -> onTickSafe(event));
    }

    private void onTickSafe(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;

        // ── Guard: rename-text must be set ────────────────────────────────────
        String targetName = renameText.get() == null ? "" : renameText.get().trim();
        if (targetName.isEmpty()) {
            warning("Set rename-text before enabling!");
            toggle();
            return;
        }

        ScreenHandler handler = mc.player.currentScreenHandler;
        if (!(handler instanceof AnvilScreenHandler anvil)) return;

        ticks++;
        if (ticks < clickDelay.get()) return;
        ticks = 0;

        // ── Safety: if cursor holds an item (interrupted pickup), place it down ─
        if (!anvil.getCursorStack().isEmpty()) {
            // Find an empty slot in player inventory (slots 3-38) and click to deposit
            for (int i = 3; i < 39; i++) {
                if (anvil.getSlot(i).getStack().isEmpty()) {
                    mc.interactionManager.clickSlot(anvil.syncId, i, 0, SlotActionType.PICKUP, mc.player);
                    return;
                }
            }
            return; // inventory full, can't place cursor item
        }

        ItemStack output = anvil.getSlot(2).getStack();
        ItemStack input0 = anvil.getSlot(0).getStack();

        // ── Step 1: output slot ready — check XP and take the item ───────────
        if (!output.isEmpty()) {
            stuckCycles = 0; // output appeared, so we're not stuck

            int     cost      = anvil.getLevelCost();
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
                return; // wait until we can afford the rename
            }

            // Shift-click output directly into inventory (QUICK_MOVE).
            // Using PICKUP would leave the item on the cursor, freezing the module.
            String outputName = output.getName().getString();
            if (outputName.equals(targetName)) {
                renameMismatchCycles = 0;
                mc.interactionManager.clickSlot(anvil.syncId, 2, 0, SlotActionType.QUICK_MOVE, mc.player);
            } else {
                renameMismatchCycles++;
                if (renameMismatchCycles >= RENAME_MISMATCH_TIMEOUT) {
                    // Avoid stalling forever when the server keeps forcing another name.
                    renameMismatchCycles = 0;
                    mc.interactionManager.clickSlot(anvil.syncId, 2, 0, SlotActionType.QUICK_MOVE, mc.player);
                } else {
                    mc.player.networkHandler.sendPacket(new RenameItemC2SPacket(targetName));
                }
            }
            return;
        }

        // ── Step 2: item is in input slot — send rename packet ────────────────
        if (!input0.isEmpty()) {
            renameMismatchCycles = 0;
            stuckCycles++;

            if (stuckCycles >= STUCK_TIMEOUT) {
                // Output never appeared — item might be incompatible or cost > 39 levels
                // (the "too expensive!" cap). Shift-click slot 0 to return item to inventory.
                stuckCycles = 0;
                mc.interactionManager.clickSlot(anvil.syncId, 0, 0, SlotActionType.QUICK_MOVE, mc.player);
                return;
            }

            // Keep sending the rename packet every cycle — server may need more than one.
            mc.player.networkHandler.sendPacket(new RenameItemC2SPacket(targetName));
            return;
        }

        // ── Step 3: both input slots empty — move next valid item into anvil ──
        stuckCycles = 0;
        renameMismatchCycles = 0;

        // AnvilScreenHandler slots: 0-2 = anvil, 3-29 = player inventory, 30-38 = hotbar
        for (int i = 3; i < 39; i++) {
            ItemStack stack = anvil.getSlot(i).getStack();
            if (stack.isEmpty()) continue;
            if (!passesFilters(stack, targetName)) continue;

            // Shift-click → Minecraft moves to the first available input slot (0 when empty)
            mc.interactionManager.clickSlot(anvil.syncId, i, 0, SlotActionType.QUICK_MOVE, mc.player);
            return;
        }
        // No more eligible items — nothing left to do
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Returns true if the stack should be moved into the anvil and renamed.
     * Also rejects items that already display the target name (no rename needed).
     */
    private boolean passesFilters(ItemStack stack, String targetName) {
        // Already has the target name — nothing to rename
        if (stack.getName().getString().equals(targetName)) return false;

        Item item = stack.getItem();

        if (onlyShulkers.get()) {
            if (!(item instanceof BlockItem bi && bi.getBlock() instanceof ShulkerBoxBlock)) return false;
        }

        List<Item> allowed = items.get();
        if (allowed != null && !allowed.isEmpty()) {
            if (!allowed.contains(item)) return false;
        }

        // onlyRenamed = true → only pick items that already have a custom name
        if (onlyRenamed.get() && !stack.contains(DataComponentTypes.CUSTOM_NAME)) return false;

        return true;
    }

    /** Returns hotbar slot of the given item, or -1 if not found. */
    private int findHotbarItem(Item item) {
        if (mc.player == null) return -1;
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getStack(i).getItem() == item) return i;
        }
        return -1;
    }

    // ── Command API ───────────────────────────────────────────────────────────

    public void setRenameText(String text) {
        renameText.set(text == null ? "" : text);
    }

    public Setting<List<Item>> getItemsSetting() {
        return items;
    }
}


