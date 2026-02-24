/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.gui.screens;

import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.WindowScreen;
import meteordevelopment.meteorclient.gui.widgets.containers.WTable;
import meteordevelopment.meteorclient.gui.widgets.input.WTextBox;
import net.minecraft.class_1268;
import net.minecraft.class_1799;
import net.minecraft.class_2820;
import net.minecraft.class_3872;
import net.minecraft.class_9262;
import net.minecraft.class_9302;
import net.minecraft.class_9334;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public class EditBookTitleAndAuthorScreen extends WindowScreen {
    private final class_1799 itemStack;
    private final class_1268 hand;

    public EditBookTitleAndAuthorScreen(GuiTheme theme, class_1799 itemStack, class_1268 hand) {
        super(theme, "Edit title & author");
        this.itemStack = itemStack;
        this.hand = hand;
    }

    @Override
    public void initWidgets() {
        WTable t = add(theme.table()).expandX().widget();

        t.add(theme.label("Title"));
        WTextBox title = t.add(theme.textBox(itemStack.method_58694(class_9334.field_49606).comp_2419().method_57140(mc.method_33883()))).minWidth(220).expandX().widget();
        t.row();

        t.add(theme.label("Author"));
        WTextBox author = t.add(theme.textBox(itemStack.method_58694(class_9334.field_49606).comp_2420())).minWidth(220).expandX().widget();
        t.row();

        t.add(theme.button("Done")).expandX().widget().action = () -> {
            class_9302 component = itemStack.method_58694(class_9334.field_49606);
            class_9302 newComponent = new class_9302(class_9262.method_57137(title.get()), author.get(), component.comp_2421(), component.comp_2422(), component.comp_2423());
            itemStack.method_57379(class_9334.field_49606, newComponent);

            class_3872.class_3931 contents = new class_3872.class_3931(itemStack.method_58694(class_9334.field_49606).method_57525(mc.method_33883()));
            List<String> pages = new ArrayList<>(contents.method_17560());
            for (int i = 0; i < contents.method_17560(); i++) pages.add(contents.method_17563(i).getString());

            mc.method_1562().method_52787(new class_2820(hand == class_1268.field_5808 ? mc.field_1724.method_31548().method_67532() : 40, pages, Optional.of(title.get())));

            method_25419();
        };
    }
}
