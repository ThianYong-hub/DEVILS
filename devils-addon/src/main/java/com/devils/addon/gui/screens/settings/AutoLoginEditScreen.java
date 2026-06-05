package com.devils.addon.gui.screens.settings;

import com.devils.addon.modules.AutoLogin;
import com.devils.addon.modules.autologin.AutoLoginProfile;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.WindowScreen;
import meteordevelopment.meteorclient.gui.widgets.containers.WHorizontalList;
import meteordevelopment.meteorclient.gui.widgets.containers.WTable;
import meteordevelopment.meteorclient.gui.widgets.input.WTextBox;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.gui.widgets.pressable.WCheckbox;

import java.util.Arrays;
import java.util.Locale;
import java.util.Random;
import java.util.function.Supplier;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public class AutoLoginEditScreen extends WindowScreen {
    private static final char[] MASK_CHARS = new char[] { '#', '*', '$', '^', '@', '&', '(', ')' };
    private static final int WINDOW_MIN_WIDTH = 520;
    private static final int FIELD_MIN_WIDTH = 260;

    private final AutoLoginProfile target;
    private final AutoLoginProfile working;
    private final Supplier<String> currentUsernameSupplier;
    private final Supplier<String> currentServerSupplier;

    private boolean showPassword;
    private String passwordMask;

    public AutoLoginEditScreen(
        GuiTheme theme,
        AutoLoginProfile target,
        Supplier<String> currentUsernameSupplier,
        Supplier<String> currentServerSupplier
    ) {
        super(theme, "Edit Auto Login");
        this.target = target;
        this.working = target.copy();
        this.currentUsernameSupplier = currentUsernameSupplier;
        this.currentServerSupplier = currentServerSupplier;
        this.passwordMask = generateMask(working.password.get());
    }

    @Override
    public void initWidgets() {
        WTable table = add(theme.table()).minWidth(WINDOW_MIN_WIDTH).expandX().widget();

        table.add(theme.label("Enabled"));
        WHorizontalList enabledRow = table.add(theme.horizontalList()).expandX().widget();
        WCheckbox enabled = enabledRow.add(theme.checkbox(working.enabled.get())).widget();
        enabled.action = () -> working.enabled.set(enabled.checked);
        table.row();

        table.add(theme.label("Account"));
        WHorizontalList accountRow = table.add(theme.horizontalList()).expandX().widget();
        WTextBox account = accountRow.add(theme.textBox(working.username.get(), "Player name")).minWidth(FIELD_MIN_WIDTH).expandX().widget();
        account.action = () -> updateAccountValue(account.get());
        account.actionOnUnfocused = () -> updateAccountValue(account.get());

        WButton useCurrent = accountRow.add(theme.button("Current")).widget();
        useCurrent.tooltip = "Replace with current session username.";
        useCurrent.action = () -> {
            updateAccountValue(currentUsernameSupplier.get().trim());
            reload();
        };

        WButton selectAccount = accountRow.add(theme.button("Accounts")).widget();
        selectAccount.action = () -> mc.setScreen(new SelectionScreens.StringSelectScreen(theme, "Select Account", Arrays.asList(AutoLogin.getAccountOptions()), value -> {
            updateAccountValue(value);
            reload();
        }));
        table.row();

        table.add(theme.label("Server"));
        WHorizontalList serverRow = table.add(theme.horizontalList()).expandX().widget();
        WTextBox server = serverRow.add(theme.textBox(working.server.get(), "Server address")).minWidth(FIELD_MIN_WIDTH).expandX().widget();
        server.action = () -> working.server.set(server.get().trim());
        server.actionOnUnfocused = () -> working.server.set(server.get().trim());

        WButton selectServer = serverRow.add(theme.button("Servers")).widget();
        selectServer.action = () -> mc.setScreen(new SelectionScreens.StringSelectScreen(
            theme,
            "Select Server",
            Arrays.asList(AutoLogin.getServerOptions(currentServerSupplier.get().trim(), working.server.get())),
            value -> {
                working.server.set(value.trim());
                reload();
            }
        ));
        table.row();

        table.add(theme.label("Mode"));
        WHorizontalList modeRow = table.add(theme.horizontalList()).expandX().widget();
        modeRow.add(theme.label(working.mode.get().name())).expandX();

        WButton switchMode = modeRow.add(theme.button("Switch")).widget();
        switchMode.action = () -> {
            working.mode.set(working.mode.get() == AutoLogin.LoginMode.LOGIN ? AutoLogin.LoginMode.REGISTER : AutoLogin.LoginMode.LOGIN);
            reload();
        };
        table.row();

        table.add(theme.label("Delay"));
        var delay = table.add(theme.intEdit(working.delay.get(), 0, 20_000, true)).expandX().widget();
        delay.actionOnRelease = () -> working.delay.set(delay.get());
        table.row();

        table.add(theme.label("Password"));
        WHorizontalList passwordRow = table.add(theme.horizontalList()).expandX().widget();
        if (showPassword) {
            WTextBox password = passwordRow.add(theme.textBox(working.password.get(), "Password")).minWidth(FIELD_MIN_WIDTH).expandX().widget();
            password.action = () -> working.password.set(password.get());
            password.actionOnUnfocused = () -> working.password.set(password.get());

            WButton hide = passwordRow.add(theme.button("Hide")).widget();
            hide.action = () -> {
                showPassword = false;
                passwordMask = generateMask(working.password.get());
                reload();
            };
        } else {
            passwordRow.add(theme.label(passwordMask)).minWidth(FIELD_MIN_WIDTH).expandX();

            WButton show = passwordRow.add(theme.button("Show")).widget();
            show.action = () -> {
                showPassword = true;
                reload();
            };
        }
        table.row();

        add(theme.horizontalSeparator()).expandX();

        WHorizontalList actions = add(theme.horizontalList()).expandX().widget();

        WButton save = actions.add(theme.button("Save")).expandX().widget();
        save.action = () -> {
            target.copyFrom(working);
            close();
        };

        WButton cancel = actions.add(theme.button("Cancel")).expandX().widget();
        cancel.action = this::close;
    }

    private void updateAccountValue(String value) {
        String trimmed = value == null ? "" : value.trim();
        working.username.set(trimmed);
    }

    private String generateMask(String password) {
        int sourceLength = password == null ? 0 : password.trim().length();
        int length = Math.max(8, Math.min(20, sourceLength + 3));
        Random random = new Random();
        StringBuilder builder = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            builder.append(MASK_CHARS[random.nextInt(MASK_CHARS.length)]);
        }
        return builder.toString().toLowerCase(Locale.ROOT);
    }
}



