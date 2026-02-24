/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.gui.screens.settings;

import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.screens.settings.base.CollectionListSettingScreen;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.settings.ItemListSetting;
import meteordevelopment.meteorclient.utils.misc.Names;
import net.minecraft.class_1792;
import net.minecraft.class_1802;
import net.minecraft.class_7923;
import java.util.function.Predicate;

public class ItemListSettingScreen extends CollectionListSettingScreen<class_1792> {
    public ItemListSettingScreen(GuiTheme theme, ItemListSetting setting) {
        super(theme, "Select Items", setting, setting.get(), class_7923.field_41178);
    }

    @Override
    protected boolean includeValue(class_1792 value) {
        Predicate<class_1792> filter = ((ItemListSetting) setting).filter;
        if (filter != null && !filter.test(value)) return false;

        return value != class_1802.field_8162;
    }

    @Override
    protected WWidget getValueWidget(class_1792 value) {
        return theme.itemWithLabel(value.method_7854());
    }

    @Override
    protected String[] getValueNames(class_1792 value) {
        return new String[]{
            Names.get(value),
            class_7923.field_41178.method_10221(value).toString()
        };
    }
}
