/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.gui.screens.settings;

import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.screens.settings.base.CollectionListSettingScreen;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.settings.Setting;
import net.minecraft.class_3917;
import net.minecraft.class_7923;
import java.util.List;

public class ScreenHandlerSettingScreen extends CollectionListSettingScreen<class_3917<?>> {
    public ScreenHandlerSettingScreen(GuiTheme theme, Setting<List<class_3917<?>>> setting) {
        super(theme, "Select Screen Handlers", setting, setting.get(), class_7923.field_41187);
    }

    @Override
    protected WWidget getValueWidget(class_3917<?> value) {
        return theme.label(getName(value));
    }

    @Override
    protected String[] getValueNames(class_3917<?> type) {
        return new String[]{
            getName(type)
        };
    }

    private static String getName(class_3917<?> type) {
        return class_7923.field_41187.method_10221(type).toString();
    }
}
