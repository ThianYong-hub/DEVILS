package com.example.addon.modules.autologin;

import com.example.addon.gui.screens.settings.AutoLoginEditScreen;
import com.example.addon.modules.AutoLogin;
import com.example.addon.modules.autologin.AutoLoginProfile.SyncProfileData;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.renderer.GuiRenderer;
import meteordevelopment.meteorclient.gui.renderer.packer.GuiTexture;
import meteordevelopment.meteorclient.gui.themes.meteor.MeteorGuiTheme;
import meteordevelopment.meteorclient.gui.themes.meteor.widgets.pressable.WMeteorButton;
import meteordevelopment.meteorclient.gui.themes.meteor.widgets.pressable.WMeteorMinus;
import meteordevelopment.meteorclient.gui.widgets.WLabel;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.gui.widgets.containers.WContainer;
import meteordevelopment.meteorclient.gui.widgets.containers.WHorizontalList;
import meteordevelopment.meteorclient.gui.widgets.containers.WVerticalList;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.gui.widgets.pressable.WMinus;
import meteordevelopment.meteorclient.utils.render.color.Color;
import net.minecraft.client.MinecraftClient;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

import static meteordevelopment.meteorclient.gui.renderer.GuiRenderer.COPY;

public final class AutoLoginProfileStore {
    private static final Color ACTIVE_PRIMARY_TEXT_COLOR = new Color(255, 104, 112);
    private static final Color ACTIVE_SECONDARY_TEXT_COLOR = new Color(255, 168, 172, 235);
    private static final Color ACTIVE_ROW_OUTLINE_COLOR = new Color(214, 78, 92, 120);
    private static final Color ACTIVE_ROW_OUTLINE_HOVER_COLOR = new Color(236, 98, 114, 150);
    private static final Color ACTIVE_ROW_BACKGROUND_COLOR = new Color(120, 18, 32, 52);
    private static final Color ACTIVE_ROW_BACKGROUND_HOVER_COLOR = new Color(142, 24, 40, 72);
    private static final Color ACTIVE_BUTTON_OUTLINE_COLOR = new Color(204, 82, 96, 130);
    private static final Color ACTIVE_BUTTON_OUTLINE_HOVER_COLOR = new Color(228, 102, 116, 165);
    private static final Color ACTIVE_BUTTON_OUTLINE_PRESSED_COLOR = new Color(248, 122, 132, 190);
    private static final Color ACTIVE_BUTTON_BACKGROUND_COLOR = new Color(110, 18, 28, 96);
    private static final Color ACTIVE_BUTTON_BACKGROUND_HOVER_COLOR = new Color(132, 24, 36, 120);
    private static final Color ACTIVE_BUTTON_BACKGROUND_PRESSED_COLOR = new Color(162, 34, 48, 146);
    private static final Color ACTIVE_BUTTON_TEXT_COLOR = new Color(255, 226, 226);
    private static final double PROFILE_ROW_PAD_X = 4;
    private static final double PROFILE_ROW_PAD_Y = 3;
    private static final double PROFILE_USERNAME_MIN_WIDTH = 220;
    private static final double PROFILE_SERVER_MIN_WIDTH = 110;
    private final List<AutoLoginProfile> profiles = new ArrayList<>();

    public NbtCompound writeToTag(NbtCompound tag) {
        NbtList list = new NbtList();
        for (AutoLoginProfile profile : profiles) {
            NbtCompound entryTag = new NbtCompound();
            entryTag.put("profile", profile.toTag());
            list.add(entryTag);
        }
        tag.put("profiles", list);
        return tag;
    }

    public void loadFromTag(NbtCompound tag) {
        profiles.clear();
        NbtList list = tag.getListOrEmpty("profiles");
        for (NbtElement element : list) {
            if (!(element instanceof NbtCompound entryTag)) continue;
            if (!(entryTag.get("profile") instanceof NbtCompound profileTag)) continue;
            AutoLoginProfile profile = new AutoLoginProfile();
            profile.fromTag(profileTag);
            profiles.add(profile);
        }
    }

    public WWidget createWidget(
        GuiTheme theme,
        Supplier<String> currentUsernameSupplier,
        Supplier<String> currentServerKeySupplier,
        IntSupplier newEntryDelaySupplier,
        Consumer<AutoLoginProfile> loginAction
    ) {
        WVerticalList list = theme.verticalList();
        fillWidget(theme, list, currentUsernameSupplier, currentServerKeySupplier, newEntryDelaySupplier, loginAction);
        return list;
    }

    public AutoLoginProfile findMatchingProfile(String username, String server) {
        return findProfile(username, server, true);
    }

    public void upsertProfile(String username, String server, AutoLogin.ParsedCommand parsed, int defaultDelay) {
        if (parsed == null) return;
        upsertProfile(username, server, parsed.mode(), parsed.password(), defaultDelay);
    }

    public void upsertProfile(String username, String server, AutoLogin.LoginMode mode, String password, int defaultDelay) {
        if (mode == null) return;

        String normalizedUsername = username == null ? "" : username.trim();
        String normalizedServer = server == null ? "" : server.trim();
        String normalizedPassword = password == null ? "" : password.trim();
        if (normalizedUsername.isBlank() || normalizedServer.isBlank() || normalizedPassword.isBlank()) return;

        AutoLoginProfile existing = findProfile(normalizedUsername, normalizedServer, false);
        if (existing == null) {
            AutoLoginProfile profile = new AutoLoginProfile();
            profile.enabled.set(true);
            profile.username.set(normalizedUsername);
            profile.server.set(normalizedServer);
            profile.mode.set(mode);
            profile.password.set(normalizedPassword);
            profile.delay.set(defaultDelay);
            profiles.add(profile);
            return;
        }

        existing.username.set(normalizedUsername);
        existing.server.set(normalizedServer);
        existing.mode.set(mode);
        existing.password.set(normalizedPassword);
        existing.enabled.set(true);
    }

    public List<SyncProfileData> snapshotProfiles() {
        ArrayList<SyncProfileData> snapshot = new ArrayList<>();
        for (AutoLoginProfile profile : profiles) {
            snapshot.add(new SyncProfileData(
                profile.enabled.get(),
                profile.username.get(),
                profile.server.get(),
                profile.mode.get(),
                profile.password.get(),
                profile.delay.get()
            ));
        }
        return snapshot;
    }

    public void replaceProfiles(List<SyncProfileData> remoteProfiles) {
        profiles.clear();
        for (SyncProfileData data : remoteProfiles) {
            AutoLoginProfile profile = new AutoLoginProfile();
            profile.enabled.set(data.enabled());
            profile.username.set(data.username());
            profile.server.set(data.server());
            profile.mode.set(data.mode());
            profile.password.set(data.password());
            profile.delay.set(Math.max(0, data.delay()));
            profiles.add(profile);
        }
    }

    public List<SyncProfileData> mergeProfilesPreferLocal(List<SyncProfileData> remoteProfiles, List<SyncProfileData> localProfiles) {
        LinkedHashMap<String, SyncProfileData> merged = new LinkedHashMap<>();
        for (SyncProfileData data : remoteProfiles) merged.put(profileIdentityKey(data), data);
        for (SyncProfileData data : localProfiles) merged.put(profileIdentityKey(data), data);
        return new ArrayList<>(merged.values());
    }

    public boolean isEmpty() {
        return profiles.isEmpty();
    }

    private void fillWidget(
        GuiTheme theme,
        WVerticalList list,
        Supplier<String> currentUsernameSupplier,
        Supplier<String> currentServerKeySupplier,
        IntSupplier newEntryDelaySupplier,
        Consumer<AutoLoginProfile> loginAction
    ) {
        list.clear();
        String activeUsername = currentUsernameSupplier.get();
        String activeServer = currentServerKeySupplier.get();
        for (AutoLoginProfile profile : profiles) {
            String username = profile.username.get().isBlank() ? "(username not set)" : profile.username.get();
            String server = profile.server.get().isBlank() ? "(server not set)" : profile.server.get();
            String status = profile.enabled.get() ? "[on]" : "[off]";
            boolean currentSessionProfile = AutoLoginTextRules.matchesKey(
                profile.username.get(),
                profile.server.get(),
                activeUsername,
                activeServer
            );
            boolean useActiveButtons = currentSessionProfile && theme instanceof MeteorGuiTheme;

            ProfileRow row = list.add(new ProfileRow(currentSessionProfile)).expandX().padVertical(1).widget();
            row.spacing = theme.scale(4);

            WLabel usernameLabel = row.add(theme.label(status + " " + username))
                .minWidth(theme.scale(PROFILE_USERNAME_MIN_WIDTH))
                .expandX()
                .padLeft(theme.scale(PROFILE_ROW_PAD_X))
                .padRight(theme.scale(6))
                .widget();
            if (currentSessionProfile) usernameLabel.color(new Color(ACTIVE_PRIMARY_TEXT_COLOR));

            WLabel serverLabel = row.add(theme.label(server))
                .minWidth(theme.scale(PROFILE_SERVER_MIN_WIDTH))
                .padRight(theme.scale(6))
                .widget();
            if (currentSessionProfile) serverLabel.color(new Color(ACTIVE_SECONDARY_TEXT_COLOR));

            WButton edit = useActiveButtons ? new ActiveProfileButton("Edit", null) : theme.button("Edit");
            row.add(edit);
            edit.action = () -> {
                AutoLoginEditScreen screen = new AutoLoginEditScreen(theme, profile, currentUsernameSupplier, currentServerKeySupplier);
                screen.onClosed(() -> fillWidget(theme, list, currentUsernameSupplier, currentServerKeySupplier, newEntryDelaySupplier, loginAction));
                MinecraftClient.getInstance().setScreen(screen);
            };

            WButton login = useActiveButtons ? new ActiveProfileButton("Login", null) : theme.button("Login");
            row.add(login);
            login.action = () -> {
                loginAction.accept(profile);
                fillWidget(theme, list, currentUsernameSupplier, currentServerKeySupplier, newEntryDelaySupplier, loginAction);
            };

            WButton copy = useActiveButtons ? new ActiveProfileButton(null, COPY) : theme.button(COPY);
            row.add(copy);
            copy.action = () -> {
                AutoLoginProfile duplicate = profile.copy();
                int index = profiles.indexOf(profile);
                if (index < 0 || index > profiles.size()) profiles.add(duplicate);
                else profiles.add(index, duplicate);
                fillWidget(theme, list, currentUsernameSupplier, currentServerKeySupplier, newEntryDelaySupplier, loginAction);
            };

            WMinus remove = useActiveButtons ? new ActiveProfileMinus() : theme.minus();
            row.add(remove).padRight(theme.scale(PROFILE_ROW_PAD_X));
            remove.action = () -> {
                profiles.remove(profile);
                fillWidget(theme, list, currentUsernameSupplier, currentServerKeySupplier, newEntryDelaySupplier, loginAction);
            };
        }
        if (!profiles.isEmpty()) list.add(theme.horizontalSeparator()).expandX();
        WContainer controls = list.add(theme.horizontalList()).expandX().widget();
        WButton add = controls.add(theme.button("New Entry")).expandX().widget();
        add.action = () -> {
            AutoLoginProfile profile = new AutoLoginProfile();
            profile.delay.set(newEntryDelaySupplier.getAsInt());
            String username = currentUsernameSupplier.get();
            String server = currentServerKeySupplier.get();
            if (!username.isBlank()) profile.username.set(username);
            if (!server.isBlank()) profile.server.set(server);
            profiles.add(profile);
            fillWidget(theme, list, currentUsernameSupplier, currentServerKeySupplier, newEntryDelaySupplier, loginAction);
        };
        WButton removeAll = controls.add(theme.button("Remove All")).expandX().widget();
        removeAll.action = () -> {
            profiles.clear();
            fillWidget(theme, list, currentUsernameSupplier, currentServerKeySupplier, newEntryDelaySupplier, loginAction);
        };
    }

    private static String profileIdentityKey(SyncProfileData data) {
        return AutoLoginTextRules.normalizeKey(data.username()) + "|" + AutoLoginTextRules.normalizeServerKey(data.server());
    }

    private AutoLoginProfile findProfile(String username, String server, boolean enabledOnly) {
        for (AutoLoginProfile profile : profiles) {
            if (enabledOnly && !profile.enabled.get()) continue;
            if (AutoLoginTextRules.matchesKey(profile.username.get(), profile.server.get(), username, server)) return profile;
        }
        return null;
    }

    private static final class ProfileRow extends WHorizontalList {
        private final boolean highlighted;

        private ProfileRow(boolean highlighted) {
            this.highlighted = highlighted;
        }

        @Override
        protected void onRender(GuiRenderer renderer, double mouseX, double mouseY, double delta) {
            if (!highlighted) return;

            double outline = Math.max(1, theme.scale(1));
            Color outlineColor = mouseOver ? ACTIVE_ROW_OUTLINE_HOVER_COLOR : ACTIVE_ROW_OUTLINE_COLOR;
            Color backgroundColor = mouseOver ? ACTIVE_ROW_BACKGROUND_HOVER_COLOR : ACTIVE_ROW_BACKGROUND_COLOR;

            renderer.quad(x + outline, y + outline, width - outline * 2, height - outline * 2, backgroundColor);
            renderer.quad(x, y, width, outline, outlineColor);
            renderer.quad(x, y + height - outline, width, outline, outlineColor);
            renderer.quad(x, y + outline, outline, height - outline * 2, outlineColor);
            renderer.quad(x + width - outline, y + outline, outline, height - outline * 2, outlineColor);
        }
    }

    private static final class ActiveProfileButton extends WMeteorButton {
        private ActiveProfileButton(String text, GuiTexture texture) {
            super(text, texture);
        }

        @Override
        protected void onRender(GuiRenderer renderer, double mouseX, double mouseY, double delta) {
            MeteorGuiTheme theme = theme();
            double pad = pad();

            renderBackground(
                renderer,
                this,
                pressed ? ACTIVE_BUTTON_OUTLINE_PRESSED_COLOR : mouseOver ? ACTIVE_BUTTON_OUTLINE_HOVER_COLOR : ACTIVE_BUTTON_OUTLINE_COLOR,
                pressed ? ACTIVE_BUTTON_BACKGROUND_PRESSED_COLOR : mouseOver ? ACTIVE_BUTTON_BACKGROUND_HOVER_COLOR : ACTIVE_BUTTON_BACKGROUND_COLOR
            );

            if (text != null) {
                renderer.text(text, x + width / 2 - textWidth / 2, y + pad, ACTIVE_BUTTON_TEXT_COLOR, false);
            } else {
                double ts = theme.textHeight();
                renderer.quad(x + width / 2 - ts / 2, y + pad, ts, ts, texture, ACTIVE_BUTTON_TEXT_COLOR);
            }
        }
    }

    private static final class ActiveProfileMinus extends WMeteorMinus {
        @Override
        protected void onRender(GuiRenderer renderer, double mouseX, double mouseY, double delta) {
            double pad = pad();
            double s = theme.scale(3);

            renderBackground(
                renderer,
                this,
                pressed ? ACTIVE_BUTTON_OUTLINE_PRESSED_COLOR : mouseOver ? ACTIVE_BUTTON_OUTLINE_HOVER_COLOR : ACTIVE_BUTTON_OUTLINE_COLOR,
                pressed ? ACTIVE_BUTTON_BACKGROUND_PRESSED_COLOR : mouseOver ? ACTIVE_BUTTON_BACKGROUND_HOVER_COLOR : ACTIVE_BUTTON_BACKGROUND_COLOR
            );

            renderer.quad(x + pad, y + height / 2 - s / 2, width - pad * 2, s, ACTIVE_BUTTON_TEXT_COLOR);
        }
    }
}


