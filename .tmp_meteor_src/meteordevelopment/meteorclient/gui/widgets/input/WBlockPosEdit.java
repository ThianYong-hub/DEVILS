/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.gui.widgets.input;

import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.entity.player.InteractBlockEvent;
import meteordevelopment.meteorclient.events.entity.player.StartBreakingBlockEvent;
import meteordevelopment.meteorclient.gui.widgets.containers.WHorizontalList;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.render.marker.Marker;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.class_2338;
import net.minecraft.class_239;
import net.minecraft.class_437;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public class WBlockPosEdit extends WHorizontalList {
    public Runnable action;
    public Runnable actionOnRelease;

    private WTextBox textBoxX, textBoxY, textBoxZ;

    private class_437 previousScreen;

    private class_2338 value;
    private class_2338 lastValue;

    private boolean clicking;

    public WBlockPosEdit(class_2338 value) {
        this.value = value;
    }

    @Override
    public void init() {
        addTextBox();

        if (Utils.canUpdate()) {
            WButton click = add(theme.button("Click")).expandX().widget();
            click.action = () -> {
                String sb = "Click!\nRight click to pick a new position.\nLeft click to cancel.";
                Modules.get().get(Marker.class).info(sb);

                clicking = true;
                MeteorClient.EVENT_BUS.subscribe(this);
                previousScreen = mc.field_1755;
                mc.method_1507(null);
            };

            WButton here = add(theme.button("Set Here")).expandX().widget();
            here.action = () -> {
                lastValue = value;
                set(new class_2338(mc.field_1724.method_24515()));
                newValueCheck();

                clear();
                init();
            };
        }
    }

    @EventHandler
    private void onStartBreakingBlock(StartBreakingBlockEvent event) {
        if (clicking) {
            clicking = false;
            event.cancel();
            MeteorClient.EVENT_BUS.unsubscribe(this);
            mc.method_1507(previousScreen);
        }
    }

    @EventHandler
    private void onInteractBlock(InteractBlockEvent event) {
        if (clicking) {
            if (event.result.method_17783() == class_239.class_240.field_1333) return;
            lastValue = value;
            set(event.result.method_17777());
            newValueCheck();

            clear();
            init();

            clicking = false;
            event.cancel();
            MeteorClient.EVENT_BUS.unsubscribe(this);
            mc.method_1507(previousScreen);
        }
    }

    private boolean filter(String text, char c) {
        boolean good;
        boolean validate = true;

        if (c == '-' && text.isEmpty()) {
            good = true;
            validate = false;
        }
        else good = Character.isDigit(c);

        if (good && validate) {
            try {
                Integer.parseInt(text + c);
            } catch (NumberFormatException ignored) {
                good = false;
            }
        }

        return good;
    }

    public class_2338 get() {
        return value;
    }

    public void set(class_2338 value) {
        this.value = value;
    }

    private void addTextBox() {
        textBoxX = add(theme.textBox(Integer.toString(value.method_10263()), this::filter)).minWidth(75).widget();
        textBoxY = add(theme.textBox(Integer.toString(value.method_10264()), this::filter)).minWidth(75).widget();
        textBoxZ = add(theme.textBox(Integer.toString(value.method_10260()), this::filter)).minWidth(75).widget();

        textBoxX.actionOnUnfocused = () -> {
            lastValue = value;
            if (textBoxX.get().isEmpty()) set(new class_2338(0, 0, 0));
            else {
                try {
                    set(new class_2338(Integer.parseInt(textBoxX.get()), value.method_10264(), value.method_10260()));
                } catch (NumberFormatException ignored) {}
            }
            newValueCheck();
        };

        textBoxY.actionOnUnfocused = () -> {
            lastValue = value;
            if (textBoxY.get().isEmpty()) set(new class_2338(0, 0, 0));
            else {
                try {
                    set(new class_2338(value.method_10263(), Integer.parseInt(textBoxY.get()), value.method_10260()));
                } catch (NumberFormatException ignored) {}
            }
            newValueCheck();
        };

        textBoxZ.actionOnUnfocused = () -> {
            lastValue = value;
            if (textBoxZ.get().isEmpty()) set(new class_2338(0, 0, 0));
            else {
                try {
                    set(new class_2338(value.method_10263(), value.method_10264(), Integer.parseInt(textBoxZ.get())));
                } catch (NumberFormatException ignored) {}
            }
            newValueCheck();
        };
    }

    private void newValueCheck() {
        if (value != lastValue) {
            if (action != null) action.run();
            if (actionOnRelease != null) actionOnRelease.run();
        }
    }
}
