/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules;

import net.minecraft.class_1799;
import net.minecraft.class_1802;

public class Category {
    public final String name;
    public final class_1799 icon;
    private final int nameHash;

    public Category(String name, class_1799 icon) {
        this.name = name;
        this.nameHash = name.hashCode();
        this.icon = icon == null ? class_1802.field_8162.method_7854() : icon;
    }
    public Category(String name) {
        this(name, null);
    }

    @Override
    public String toString() {
        return name;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Category category = (Category) o;
        return nameHash == category.nameHash;
    }

    @Override
    public int hashCode() {
        return nameHash;
    }
}
