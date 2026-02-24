/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.gui.screens.settings;

import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.screens.settings.base.DynamicRegistryListSettingScreen;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.utils.misc.Names;
import net.minecraft.class_1887;
import net.minecraft.class_5321;
import net.minecraft.class_7924;
import java.util.Set;

public class EnchantmentListSettingScreen extends DynamicRegistryListSettingScreen<class_1887> {
    public EnchantmentListSettingScreen(GuiTheme theme, Setting<Set<class_5321<class_1887>>> setting) {
        super(theme, "Select Enchantments", setting, setting.get(), class_7924.field_41265);
    }

    @Override
    protected WWidget getValueWidget(class_5321<class_1887> value) {
        return theme.label(Names.get(value));
    }

    @Override
    protected String[] getValueNames(class_5321<class_1887> value) {
        return new String[]{
            Names.get(value),
            value.method_29177().toString()
        };
    }
}
