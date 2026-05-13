package com.example.addon.util.smoke;

import com.example.addon.modules.chesttracker.ChestTrackerKeybindScreen;
import com.example.addon.util.runtime.StrictRuntimeLogger;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Instant;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.input.KeyInput;
import net.minecraft.client.input.MouseInput;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import red.jackf.chesttracker.impl.ChestTracker;

public final class InputRuntimeValidation {
    private static final String ENABLE_PROPERTY = "devils.input.runtime";
    private static final int START_DELAY_TICKS = 40;
    private static final int STEP_TIMEOUT_TICKS = 120;

    private static boolean installed;
    private static boolean completed;
    private static Stage stage = Stage.STARTUP_DELAY;
    private static int stageTicks;

    private InputRuntimeValidation() {
    }

    public static void install() {
        if (installed || !Boolean.getBoolean(ENABLE_PROPERTY)) return;
        installed = true;

        ClientLifecycleEvents.CLIENT_STARTED.register(client -> {
            completed = false;
            stage = Stage.STARTUP_DELAY;
            stageTicks = 0;
            StrictRuntimeLogger.logHarness("INPUT", "SUMMARY input-runtime started=" + Instant.now());
        });

        ClientTickEvents.END_CLIENT_TICK.register(InputRuntimeValidation::tick);
    }

    private static void tick(MinecraftClient client) {
        if (!installed || completed) return;
        stageTicks++;

        switch (stage) {
            case STARTUP_DELAY -> tickStartupDelay(client);
            case OPEN_SCREEN -> tickOpenScreen(client);
            case BIND_MOUSE3 -> tickBindMouse(client, 3, "Mouse3");
            case RELOAD_VERIFY_MOUSE3 -> tickReloadVerify(client, 3, "Mouse3");
            case BIND_MOUSE4 -> tickBindMouse(client, 4, "Mouse4");
            case RELOAD_VERIFY_MOUSE4 -> tickReloadVerify(client, 4, "Mouse4");
            case FINISHED, FAILED -> {
            }
        }
    }

    private static void tickStartupDelay(MinecraftClient client) {
        if (stageTicks < START_DELAY_TICKS) return;
        advance(Stage.OPEN_SCREEN);
    }

    private static void tickOpenScreen(MinecraftClient client) {
        if (!(client.currentScreen instanceof ChestTrackerKeybindScreen)) {
            client.execute(() -> client.setScreen(new ChestTrackerKeybindScreen(null, () -> {
            })));
            StrictRuntimeLogger.logHarness("INPUT", "STAGE open-keybind-screen");
            if (stageTicks > STEP_TIMEOUT_TICKS) fail(client, "ChestTrackerKeybindScreen did not open.");
            return;
        }

        StrictRuntimeLogger.logHarness("INPUT", "STAGE keybind-screen-ready current=" + client.currentScreen.getClass().getSimpleName());
        advance(Stage.BIND_MOUSE3);
    }

    private static void tickBindMouse(MinecraftClient client, int button, String label) {
        if (!(client.currentScreen instanceof ChestTrackerKeybindScreen screen)) {
            fail(client, "Keybind screen disappeared before binding " + label + '.');
            return;
        }

        if (stageTicks == 1) {
            if (!clickCaptureButton(screen)) {
                fail(client, "Failed to click capture button for " + label + '.');
                return;
            }
            StrictRuntimeLogger.logInput("bind-capture", "button=" + button + " label=" + label + " localized=" + mouseLabel(button));
        }

        Click click = new Click(0.0, 0.0, new MouseInput(button, 0));
        if (!screen.mouseClicked(click, false)) {
            fail(client, "Mouse binding click was rejected for " + label + '.');
            return;
        }

        boolean matches = ChestTracker.OPEN_GUI.matchesMouse(click);
        StrictRuntimeLogger.logInput("bind-trigger", "button=" + button + " label=" + label + " triggerAccepted=" + matches);
        if (!matches) {
            fail(client, label + " did not match ChestTracker.OPEN_GUI after capture.");
            return;
        }

        StrictRuntimeLogger.logHarness("INPUT", "TRACE bound " + label + " localized=" + ChestTracker.OPEN_GUI.getBoundKeyLocalizedText().getString());
        advance(button == 3 ? Stage.RELOAD_VERIFY_MOUSE3 : Stage.RELOAD_VERIFY_MOUSE4);
    }

    private static void tickReloadVerify(MinecraftClient client, int expectedButton, String label) {
        if (stageTicks == 1) {
            boolean reloaded = reloadOptions(client);
            StrictRuntimeLogger.logInput("options-reload", "label=" + label + " success=" + reloaded);
        }

        Click click = new Click(0.0, 0.0, new MouseInput(expectedButton, 0));
        boolean matches = ChestTracker.OPEN_GUI.matchesMouse(click);
        StrictRuntimeLogger.logInput(
            "bind-persisted",
            "label=" + label
                + " button=" + expectedButton
                + " localized=" + ChestTracker.OPEN_GUI.getBoundKeyLocalizedText().getString()
                + " persisted=" + matches
        );
        if (!matches) {
            fail(client, label + " binding did not survive options reload.");
            return;
        }

        if (expectedButton == 3) {
            if (!(client.currentScreen instanceof ChestTrackerKeybindScreen)) client.execute(() -> client.setScreen(new ChestTrackerKeybindScreen(null, () -> {
            })));
            advance(Stage.BIND_MOUSE4);
            return;
        }

        StrictRuntimeLogger.logHarness("INPUT", "RESULT PASS mouse3=true mouse4=true");
        finish(client, true);
    }

    private static boolean clickCaptureButton(ChestTrackerKeybindScreen screen) {
        try {
            Field field = ChestTrackerKeybindScreen.class.getDeclaredField("captureButton");
            field.setAccessible(true);
            Object value = field.get(screen);
            if (!(value instanceof ButtonWidget button)) return false;
            button.onClick(new Click(button.getX() + 1.0, button.getY() + 1.0, new MouseInput(0, 0)), false);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean reloadOptions(MinecraftClient client) {
        if (client == null || client.options == null) return false;

        client.options.write();
        try {
            for (Method method : client.options.getClass().getMethods()) {
                if (!method.getName().equals("load") || method.getParameterCount() != 0) continue;
                method.invoke(client.options);
                KeyBinding.updateKeysByCode();
                return true;
            }

            for (Method method : client.options.getClass().getDeclaredMethods()) {
                if (!method.getName().equals("load") || method.getParameterCount() != 0) continue;
                method.setAccessible(true);
                method.invoke(client.options);
                KeyBinding.updateKeysByCode();
                return true;
            }
        } catch (Throwable ignored) {
        }

        return false;
    }

    private static String mouseLabel(int button) {
        return InputUtil.Type.MOUSE.createFromCode(button).getLocalizedText().getString();
    }

    private static void advance(Stage next) {
        stage = next;
        stageTicks = 0;
    }

    private static void fail(MinecraftClient client, String detail) {
        StrictRuntimeLogger.logHarness("INPUT", "RESULT FAIL " + detail);
        finish(client, false);
    }

    private static void finish(MinecraftClient client, boolean success) {
        completed = true;
        stage = success ? Stage.FINISHED : Stage.FAILED;
        StrictRuntimeLogger.logHarness("INPUT", "SUMMARY input-runtime finished=" + Instant.now());
        try {
            client.scheduleStop();
        } catch (Throwable ignored) {
        }
    }

    private enum Stage {
        STARTUP_DELAY,
        OPEN_SCREEN,
        BIND_MOUSE3,
        RELOAD_VERIFY_MOUSE3,
        BIND_MOUSE4,
        RELOAD_VERIFY_MOUSE4,
        FINISHED,
        FAILED
    }
}
