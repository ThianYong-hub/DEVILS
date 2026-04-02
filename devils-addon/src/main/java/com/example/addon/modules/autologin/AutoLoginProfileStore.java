package com.example.addon.modules.autologin;

import com.example.addon.gui.screens.settings.AutoLoginEditScreen;
import com.example.addon.modules.AutoLogin;
import com.example.addon.modules.autologin.AutoLoginProfile.SyncProfileData;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.gui.widgets.containers.WContainer;
import meteordevelopment.meteorclient.gui.widgets.containers.WTable;
import meteordevelopment.meteorclient.gui.widgets.containers.WVerticalList;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.gui.widgets.pressable.WMinus;
import net.minecraft.client.MinecraftClient;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

import static meteordevelopment.meteorclient.gui.renderer.GuiRenderer.COPY;

public final class AutoLoginProfileStore {
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
        IntSupplier newEntryDelaySupplier
    ) {
        WVerticalList list = theme.verticalList();
        fillWidget(theme, list, currentUsernameSupplier, currentServerKeySupplier, newEntryDelaySupplier);
        return list;
    }

    public AutoLoginProfile findMatchingProfile(String username, String server) {
        for (AutoLoginProfile profile : profiles) {
            if (!profile.enabled.get()) continue;
            if (AutoLoginTextRules.matchesKey(profile.username.get(), profile.server.get(), username, server)) return profile;
        }
        return null;
    }

    public void upsertProfile(String username, String server, AutoLogin.ParsedCommand parsed, int defaultDelay) {
        AutoLoginProfile existing = findMatchingProfile(username, server);
        if (existing == null) {
            AutoLoginProfile profile = new AutoLoginProfile();
            profile.enabled.set(true);
            profile.username.set(username);
            profile.server.set(server);
            profile.mode.set(parsed.mode());
            profile.password.set(parsed.password());
            profile.delay.set(defaultDelay);
            profiles.add(profile);
            return;
        }
        existing.mode.set(parsed.mode());
        existing.password.set(parsed.password());
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
        IntSupplier newEntryDelaySupplier
    ) {
        list.clear();
        WTable table = list.add(theme.table()).expandX().widget();
        for (AutoLoginProfile profile : profiles) {
            String username = profile.username.get().isBlank() ? "(username not set)" : profile.username.get();
            String server = profile.server.get().isBlank() ? "(server not set)" : profile.server.get();
            String status = profile.enabled.get() ? "[on]" : "[off]";
            table.add(theme.label(status + " " + username)).expandX();
            table.add(theme.label(server)).expandX();
            WButton edit = table.add(theme.button("Edit")).widget();
            edit.action = () -> {
                AutoLoginEditScreen screen = new AutoLoginEditScreen(theme, profile, currentUsernameSupplier, currentServerKeySupplier);
                screen.onClosed(() -> fillWidget(theme, list, currentUsernameSupplier, currentServerKeySupplier, newEntryDelaySupplier));
                MinecraftClient.getInstance().setScreen(screen);
            };
            WButton copy = table.add(theme.button(COPY)).widget();
            copy.action = () -> {
                AutoLoginProfile duplicate = profile.copy();
                int index = profiles.indexOf(profile);
                if (index < 0 || index > profiles.size()) profiles.add(duplicate);
                else profiles.add(index, duplicate);
                fillWidget(theme, list, currentUsernameSupplier, currentServerKeySupplier, newEntryDelaySupplier);
            };
            WMinus remove = table.add(theme.minus()).widget();
            remove.action = () -> {
                profiles.remove(profile);
                fillWidget(theme, list, currentUsernameSupplier, currentServerKeySupplier, newEntryDelaySupplier);
            };
            table.row();
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
            fillWidget(theme, list, currentUsernameSupplier, currentServerKeySupplier, newEntryDelaySupplier);
        };
        WButton removeAll = controls.add(theme.button("Remove All")).expandX().widget();
        removeAll.action = () -> {
            profiles.clear();
            fillWidget(theme, list, currentUsernameSupplier, currentServerKeySupplier, newEntryDelaySupplier);
        };
    }

    private static String profileIdentityKey(SyncProfileData data) {
        return AutoLoginTextRules.normalizeKey(data.username()) + "|" + AutoLoginTextRules.normalizeServerKey(data.server());
    }
}


