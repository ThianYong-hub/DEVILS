package com.example.addon.modules;

import com.example.addon.AddonTemplate;
import com.example.addon.modules.autocraft.AutoCraftSessionController;
import java.util.List;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.ItemListSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.item.Item;

public class AutoCraft extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<List<Item>> items = sgGeneral.add(new ItemListSetting.Builder()
        .name("items")
        .description("Targets to craft. The planner tries them from top to bottom and picks the first reachable item.")
        .build());

    private final Setting<List<Item>> recipeBlacklist = sgGeneral.add(new ItemListSetting.Builder()
        .name("recipe-blacklist")
        .description("Blacklist by recipe output item. Existing matching inventory items can still be consumed as inputs.")
        .build());

    private final Setting<Integer> delay = sgGeneral.add(new IntSetting.Builder()
        .name("delay")
        .description("Ticks between low-level click, place, and take actions.")
        .defaultValue(2)
        .min(1)
        .sliderRange(1, 10)
        .build());

    private final Setting<Integer> frequency = sgGeneral.add(new IntSetting.Builder()
        .name("frequency")
        .description("Ticks between logical craft cycles and step hand-offs.")
        .defaultValue(4)
        .min(1)
        .sliderRange(1, 40)
        .build());

    private final Setting<Boolean> drop = sgGeneral.add(new BoolSetting.Builder()
        .name("drop")
        .description("Drop only the final crafted output instead of storing it in inventory.")
        .defaultValue(false)
        .build());

    private final Setting<Boolean> craftAll = sgGeneral.add(new BoolSetting.Builder()
        .name("craft-all")
        .description("Craft as many final batches as possible within the limit and current materials.")
        .defaultValue(false)
        .build());

    private final Setting<Boolean> fastClose = sgGeneral.add(new BoolSetting.Builder()
        .name("fast-close")
        .description("Close only module-opened crafting table screens after the session becomes idle.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> autoOpen = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-open")
        .description("Open a reachable crafting table when a 3x3 step is required.")
        .defaultValue(true)
        .build());

    private final Setting<Integer> limit = sgGeneral.add(new IntSetting.Builder()
        .name("limit")
        .description("Maximum number of final output items to craft this activation. 0 = unlimited.")
        .defaultValue(0)
        .min(0)
        .sliderRange(0, 512)
        .build());

    private final AutoCraftSessionController session = new AutoCraftSessionController(this);

    public AutoCraft() {
        super(
            AddonTemplate.CATEGORY,
            "auto-craft",
            "Slot-driven chain crafting without recipe-book placement."
        );
    }

    @Override
    public void onActivate() {
        session.onActivate();
    }

    @Override
    public void onDeactivate() {
        session.onDeactivate();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        session.onTick();
    }

    @Override
    public String getInfoString() {
        return session.infoString();
    }

    public List<Item> items() {
        return items.get();
    }

    public List<Item> recipeBlacklist() {
        return recipeBlacklist.get();
    }

    public int delay() {
        return delay.get();
    }

    public int frequency() {
        return frequency.get();
    }

    public boolean drop() {
        return drop.get();
    }

    public boolean craftAll() {
        return craftAll.get();
    }

    public boolean fastClose() {
        return fastClose.get();
    }

    public boolean autoOpen() {
        return autoOpen.get();
    }

    public int limit() {
        return limit.get();
    }
}
