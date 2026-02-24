/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.gui.screens.settings;

import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.screens.settings.base.CollectionListSettingScreen;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.settings.Setting;
import net.minecraft.class_1074;
import net.minecraft.class_3414;
import net.minecraft.class_7923;
import java.util.List;

public class SoundEventListSettingScreen extends CollectionListSettingScreen<class_3414> {
    public SoundEventListSettingScreen(GuiTheme theme, Setting<List<class_3414>> setting) {
        super(theme, "Select Sounds", setting, setting.get(), class_7923.field_41172);
    }

    @Override
    protected WWidget getValueWidget(class_3414 value) {
        return theme.label(value.comp_3319().method_12832());
    }

    @Override
    protected String[] getValueNames(class_3414 value) {
        return new String[]{
            value.comp_3319().toString(),
            class_1074.method_4662("subtitles." + value.comp_3319().method_12832())
        };
    }
}
