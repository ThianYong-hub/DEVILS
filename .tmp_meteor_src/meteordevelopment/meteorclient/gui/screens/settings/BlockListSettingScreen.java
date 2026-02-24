/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.gui.screens.settings;

import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.screens.settings.base.CollectionListSettingScreen;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.settings.BlockListSetting;
import meteordevelopment.meteorclient.utils.misc.Names;
import net.minecraft.class_2246;
import net.minecraft.class_2248;
import net.minecraft.class_2960;
import net.minecraft.class_7923;
import java.util.function.Predicate;

public class BlockListSettingScreen extends CollectionListSettingScreen<class_2248> {
    public BlockListSettingScreen(GuiTheme theme, BlockListSetting setting) {
        super(theme, "Select Blocks", setting, setting.get(), class_7923.field_41175);
    }

    @Override
    protected boolean includeValue(class_2248 value) {
        if (class_7923.field_41175.method_10221(value).method_12832().endsWith("_wall_banner")) {
            return false;
        }

        Predicate<class_2248> filter = ((BlockListSetting) setting).filter;

        if (filter == null) return value != class_2246.field_10124;
        return filter.test(value);
    }

    @Override
    protected WWidget getValueWidget(class_2248 value) {
        return theme.itemWithLabel(value.method_8389().method_7854(), Names.get(value));
    }

    @Override
    protected String[] getValueNames(class_2248 value) {
        return new String[]{
            Names.get(value),
            class_7923.field_41175.method_10221(value).toString()
        };
    }

    @Override
    protected class_2248 getAdditionalValue(class_2248 value) {
        String path = class_7923.field_41175.method_10221(value).method_12832();
        if (!path.endsWith("_banner")) return null;

        return class_7923.field_41175.method_63535(class_2960.method_60656(path.substring(0, path.length() - 6) + "wall_banner"));
    }
}
