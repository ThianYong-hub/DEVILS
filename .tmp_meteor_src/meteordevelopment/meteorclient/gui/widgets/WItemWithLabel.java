/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.gui.widgets;

import meteordevelopment.meteorclient.gui.widgets.containers.WHorizontalList;
import meteordevelopment.meteorclient.utils.misc.Names;
import net.minecraft.class_1292;
import net.minecraft.class_1293;
import net.minecraft.class_1799;
import net.minecraft.class_1802;
import net.minecraft.class_9334;
import java.util.Iterator;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public class WItemWithLabel extends WHorizontalList {
    private class_1799 itemStack;
    private String name;

    private WItem item;
    private WLabel label;

    public WItemWithLabel(class_1799 itemStack, String name) {
        this.itemStack = itemStack;
        this.name = name;
    }

    @Override
    public void init() {
        item = add(theme.item(itemStack)).widget();
        label = add(theme.label(name + getStringToAppend())).widget();
    }

    private String getStringToAppend() {
        String str = "";

        if (itemStack.method_7909() == class_1802.field_8574) {
            Iterator<class_1293> effects = itemStack.method_7909().method_57347().method_58694(class_9334.field_49651).method_57397().iterator();
            if (!effects.hasNext()) return str;

            str += " ";

            class_1293 effect = effects.next();
            if (effect.method_5578() > 0) str += "%d ".formatted(effect.method_5578() + 1);

            str += "(%s)".formatted(class_1292.method_5577(effect, 1, mc.field_1687 != null ? mc.field_1687.method_54719().method_54748() : 20.0F).getString());
        }

        return str;
    }

    public void set(class_1799 itemStack) {
        this.itemStack = itemStack;
        item.itemStack = itemStack;

        name = Names.get(itemStack);
        label.set(name + getStringToAppend());
    }

    public String getLabelText() {
        return label == null ? name : label.get();
    }
}
