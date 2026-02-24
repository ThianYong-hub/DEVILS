/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.gui.screens.settings;

import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.screens.settings.base.CollectionListSettingScreen;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.utils.misc.Names;
import net.minecraft.class_2394;
import net.minecraft.class_2396;
import net.minecraft.class_7923;
import java.util.List;

public class ParticleTypeListSettingScreen extends CollectionListSettingScreen<class_2396<?>> {
    public ParticleTypeListSettingScreen(GuiTheme theme, Setting<List<class_2396<?>>> setting) {
        super(theme, "Select Particles", setting, setting.get(), class_7923.field_41180);
    }

    @Override
    protected boolean includeValue(class_2396<?> value) {
        return value instanceof class_2394;
    }

    @Override
    protected WWidget getValueWidget(class_2396<?> value) {
        return theme.label(Names.get(value));
    }

    @Override
    protected String[] getValueNames(class_2396<?> value) {
        return new String[]{
            Names.get(value),
            class_7923.field_41180.method_10221(value).toString()
        };
    }
}
