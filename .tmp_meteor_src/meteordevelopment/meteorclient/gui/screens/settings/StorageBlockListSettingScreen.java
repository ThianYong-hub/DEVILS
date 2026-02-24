/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.gui.screens.settings;

import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.screens.settings.base.CollectionListSettingScreen;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.StorageBlockListSetting;
import net.minecraft.class_1792;
import net.minecraft.class_1802;
import net.minecraft.class_2591;
import net.minecraft.class_7923;
import java.util.List;
import java.util.Map;

public class StorageBlockListSettingScreen extends CollectionListSettingScreen<class_2591<?>> {
    private static final Map<class_2591<?>, BlockEntityTypeInfo> BLOCK_ENTITY_TYPE_INFO_MAP = new Object2ObjectOpenHashMap<>();
    private static final BlockEntityTypeInfo UNKNOWN = new BlockEntityTypeInfo(class_1802.field_8077, "Unknown");

    static {
        // Map of storage blocks
        BLOCK_ENTITY_TYPE_INFO_MAP.put(class_2591.field_16411, new BlockEntityTypeInfo(class_1802.field_16307, "Barrel"));
        BLOCK_ENTITY_TYPE_INFO_MAP.put(class_2591.field_16415, new BlockEntityTypeInfo(class_1802.field_16306, "Blast Furnace"));
        BLOCK_ENTITY_TYPE_INFO_MAP.put(class_2591.field_11894, new BlockEntityTypeInfo(class_1802.field_8740, "Brewing Stand"));
        BLOCK_ENTITY_TYPE_INFO_MAP.put(class_2591.field_17380, new BlockEntityTypeInfo(class_1802.field_17346, "Campfire"));
        BLOCK_ENTITY_TYPE_INFO_MAP.put(class_2591.field_11914, new BlockEntityTypeInfo(class_1802.field_8106, "Chest"));
        BLOCK_ENTITY_TYPE_INFO_MAP.put(class_2591.field_40329, new BlockEntityTypeInfo(class_1802.field_40215, "Chiseled Bookshelf"));
        BLOCK_ENTITY_TYPE_INFO_MAP.put(class_2591.field_46808, new BlockEntityTypeInfo(class_1802.field_46791, "Crafter"));
        BLOCK_ENTITY_TYPE_INFO_MAP.put(class_2591.field_11887, new BlockEntityTypeInfo(class_1802.field_8357, "Dispenser"));
        BLOCK_ENTITY_TYPE_INFO_MAP.put(class_2591.field_42781, new BlockEntityTypeInfo(class_1802.field_42699, "Decorated Pot"));
        BLOCK_ENTITY_TYPE_INFO_MAP.put(class_2591.field_11899, new BlockEntityTypeInfo(class_1802.field_8878, "Dropper"));
        BLOCK_ENTITY_TYPE_INFO_MAP.put(class_2591.field_11901, new BlockEntityTypeInfo(class_1802.field_8466, "Ender Chest"));
        BLOCK_ENTITY_TYPE_INFO_MAP.put(class_2591.field_11903, new BlockEntityTypeInfo(class_1802.field_8732, "Furnace"));
        BLOCK_ENTITY_TYPE_INFO_MAP.put(class_2591.field_11888, new BlockEntityTypeInfo(class_1802.field_8239, "Hopper"));
        BLOCK_ENTITY_TYPE_INFO_MAP.put(class_2591.field_11896, new BlockEntityTypeInfo(class_1802.field_8545, "Shulker Box"));
        BLOCK_ENTITY_TYPE_INFO_MAP.put(class_2591.field_16414, new BlockEntityTypeInfo(class_1802.field_16309, "Smoker"));
        BLOCK_ENTITY_TYPE_INFO_MAP.put(class_2591.field_11891, new BlockEntityTypeInfo(class_1802.field_8247, "Trapped Chest"));
    }

    public StorageBlockListSettingScreen(GuiTheme theme, Setting<List<class_2591<?>>> setting) {
        super(theme, "Select Storage Blocks", setting, setting.get(), StorageBlockListSetting.REGISTRY);
    }

    @Override
    protected WWidget getValueWidget(class_2591<?> value) {
        BlockEntityTypeInfo info = BLOCK_ENTITY_TYPE_INFO_MAP.getOrDefault(value, UNKNOWN);
        return theme.itemWithLabel(info.item().method_7854(), info.name());
    }

    @Override
    protected String[] getValueNames(class_2591<?> value) {
        return new String[]{
            BLOCK_ENTITY_TYPE_INFO_MAP.getOrDefault(value, UNKNOWN).name(),
            class_7923.field_41181.method_10221(value).toString()
        };
    }

    private record BlockEntityTypeInfo(class_1792 item, String name) {}
}
