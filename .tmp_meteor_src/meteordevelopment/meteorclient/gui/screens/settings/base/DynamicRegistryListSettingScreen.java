/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.gui.screens.settings.base;

import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.widgets.containers.WHorizontalList;
import meteordevelopment.meteorclient.gui.widgets.containers.WTable;
import meteordevelopment.meteorclient.gui.widgets.input.WTextBox;
import meteordevelopment.meteorclient.settings.Setting;
import net.minecraft.class_151;
import net.minecraft.class_2378;
import net.minecraft.class_2960;
import net.minecraft.class_310;
import net.minecraft.class_5321;
import net.minecraft.class_7225;
import net.minecraft.class_7887;
import net.minecraft.registry.*;
import java.util.Collection;
import java.util.Optional;
import java.util.Set;

public abstract class DynamicRegistryListSettingScreen<T> extends CollectionListSettingScreen<class_5321<T>> {
    protected final class_5321<class_2378<T>> registryKey;

    public DynamicRegistryListSettingScreen(GuiTheme theme, String title, Setting<?> setting, Collection<class_5321<T>> collection, class_5321<class_2378<T>> registryKey) {
        super(theme, title, setting, collection, createUniverse(collection, registryKey));

        this.registryKey = registryKey;
    }

    private static <T> Iterable<class_5321<T>> createUniverse(Collection<class_5321<T>> collection, class_5321<class_2378<T>> registryKey) {
        Set<class_5321<T>> set = new ReferenceOpenHashSet<>(collection);

        Optional.ofNullable(class_310.method_1551().method_1562())
            .map(networkHandler -> (class_7225.class_7874) networkHandler.method_29091())
            .orElseGet(class_7887::method_46817)
            .method_46759(registryKey)
            .ifPresent(registry -> registry.method_46754().forEach(set::add));

        return set;
    }

    @Override
    protected void postWidgets(WTable left, WTable right) {
        if (!left.cells.isEmpty()) {
            left.add(theme.horizontalSeparator()).expandX();
            left.row();
        }

        WHorizontalList manualEntry = left.add(theme.horizontalList()).expandX().widget();
        WTextBox textBox = manualEntry.add(theme.textBox("minecraft:")).expandX().minWidth(120d).widget();
        manualEntry.add(theme.plus()).expandCellX().right().widget().action = () -> {
            String entry = textBox.get().trim();
            try {
                class_2960 id = entry.contains(":") ? class_2960.method_60654(entry) : class_2960.method_60656(entry);
                addValue(class_5321.method_29179(registryKey, id));
            } catch (class_151 ignored) {}
        };
    }
}
