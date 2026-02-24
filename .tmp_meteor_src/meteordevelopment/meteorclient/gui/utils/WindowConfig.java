/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.gui.utils;

import meteordevelopment.meteorclient.utils.misc.ISerializable;
import net.minecraft.class_2487;

public class WindowConfig implements ISerializable<WindowConfig> {
    public boolean expanded = true;
    public double x = -1;
    public double y = -1;

    // Saving

    @Override
    public class_2487 toTag() {
        class_2487 tag = new class_2487();

        tag.method_10556("expanded", expanded);
        tag.method_10549("x", x);
        tag.method_10549("y", y);

        return tag;
    }

    @Override
    public WindowConfig fromTag(class_2487 tag) {
        tag.method_10577("expanded").ifPresent(bool -> expanded = bool);
        tag.method_10574("x").ifPresent(x1 -> x = x1);
        tag.method_10574("y").ifPresent(y1 -> y = y1);

        return this;
    }
}
