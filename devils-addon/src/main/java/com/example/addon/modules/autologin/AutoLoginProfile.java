package com.example.addon.modules.autologin;

import com.example.addon.modules.AutoLogin;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.Settings;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.utils.misc.ISerializable;
import net.minecraft.nbt.NbtCompound;

import java.util.List;

public final class AutoLoginProfile implements ISerializable<AutoLoginProfile> {
    private final Settings settings = new Settings();
    private final SettingGroup sgProfile = settings.getDefaultGroup();

    public final Setting<Boolean> enabled = sgProfile.add(new BoolSetting.Builder().name("enabled").description("Whether this entry can be used.").defaultValue(true).build());
    public final Setting<String> username = sgProfile.add(new StringSetting.Builder().name("username").description("Minecraft username this entry is bound to.").defaultValue("").build());
    public final Setting<String> server = sgProfile.add(new StringSetting.Builder().name("server").description("Server address this entry is bound to.").defaultValue("").build());
    public final Setting<AutoLogin.LoginMode> mode = sgProfile.add(new EnumSetting.Builder<AutoLogin.LoginMode>().name("mode").description("Which command should be sent.").defaultValue(AutoLogin.LoginMode.LOGIN).build());
    public final Setting<String> password = sgProfile.add(new StringSetting.Builder().name("password").description("Password used for /login or /reg.").defaultValue("").build());
    public final Setting<Integer> delay = sgProfile.add(new IntSetting.Builder().name("delay").description("Delay in ticks after join before sending the command.").defaultValue(40).min(0).sliderRange(0, 200).build());

    @Override
    public NbtCompound toTag() {
        NbtCompound tag = new NbtCompound();
        tag.put("settings", settings.toTag());
        return tag;
    }

    @Override
    public AutoLoginProfile fromTag(NbtCompound tag) {
        NbtCompound settingsTag = (NbtCompound) tag.get("settings");
        if (settingsTag != null) settings.fromTag(settingsTag);
        return this;
    }

    public AutoLoginProfile copy() {
        return new AutoLoginProfile().fromTag(toTag());
    }

    public void copyFrom(AutoLoginProfile other) {
        enabled.set(other.enabled.get());
        username.set(other.username.get());
        server.set(other.server.get());
        mode.set(other.mode.get());
        password.set(other.password.get());
        delay.set(other.delay.get());
    }

    public record DebugChatPacketSnapshot(
        String packetType,
        String message,
        String sender,
        Boolean senderInTab,
        Boolean overlay,
        boolean trustedAuthOrigin,
        String extra
    ) {
    }

    public record SyncProfileData(
        boolean enabled,
        String username,
        String server,
        AutoLogin.LoginMode mode,
        String password,
        int delay
    ) {
    }

    public record SyncPullResult(
        boolean ok,
        long revision,
        List<SyncProfileData> profiles,
        String error,
        String lastWriter
    ) {
    }

    public record SyncPushResult(
        boolean ok,
        boolean applied,
        boolean conflict,
        long revision,
        List<SyncProfileData> profiles,
        String error,
        String lastWriter
    ) {
    }

    public record SyncCycleResult(
        SyncPullResult pullResult,
        SyncPushResult pushResult,
        boolean remoteApplied,
        boolean localChanged,
        String localFingerprint,
        String error
    ) {
    }

    public record SyncRuntimeConfig(
        String baseUrl,
        String token,
        String deviceId,
        boolean useStream,
        boolean allowHttp,
        int timeoutSec,
        int streamWaitMs,
        String encryptionKey,
        String signingKey
    ) {
    }
}


