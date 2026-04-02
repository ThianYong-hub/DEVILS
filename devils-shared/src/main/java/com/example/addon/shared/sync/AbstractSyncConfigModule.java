package com.example.addon.shared.sync;

import java.util.UUID;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Module;

public abstract class AbstractSyncConfigModule extends Module {
    private static final int REQUEST_TIMEOUT_SEC = 3;
    private static final int STREAM_WAIT_MS = 25;

    protected final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<String> baseUrl = sgGeneral.add(new StringSetting.Builder()
        .name("base-url")
        .description("Sync API base URL, e.g. http://127.0.0.1:7878 or https://sync.example.com.")
        .defaultValue("")
        .build()
    );

    private final Setting<String> token = sgGeneral.add(new StringSetting.Builder()
        .name("token")
        .description("Bearer token used for sync hub authentication.")
        .defaultValue("")
        .build()
    );

    private final Setting<String> encryptionKey = sgGeneral.add(new StringSetting.Builder()
        .name("encryption-key")
        .description("E2E encryption key for synced payloads.")
        .defaultValue("")
        .build()
    );

    private final Setting<String> requestSigningKey = sgGeneral.add(new StringSetting.Builder()
        .name("request-signing-key")
        .description("HMAC key for signed sync requests. Token alone is not enough when backend requires signatures.")
        .defaultValue("")
        .build()
    );

    private final Setting<String> deviceId = sgGeneral.add(new StringSetting.Builder()
        .name("device-id")
        .description("Stable ID for this client in sync hub.")
        .defaultValue(UUID.randomUUID().toString())
        .build()
    );

    private final Setting<Boolean> allowHttp = sgGeneral.add(new BoolSetting.Builder()
        .name("allow-http")
        .description("Allow plain HTTP endpoints (unsafe on public networks).")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> useStream = sgGeneral.add(new BoolSetting.Builder()
        .name("use-stream")
        .description("Use server stream channel for sync updates when supported.")
        .defaultValue(true)
        .build()
    );

    protected AbstractSyncConfigModule(Category category, String name, String description) {
        super(category, name, description);
    }

    public String getBaseUrl() {
        return baseUrl.get() == null ? "" : baseUrl.get().trim();
    }

    public String getToken() {
        return token.get() == null ? "" : token.get().trim();
    }

    public String getEncryptionKeyMaterial() {
        return encryptionKey.get() == null ? "" : encryptionKey.get().trim();
    }

    public String getRequestSigningKey() {
        return requestSigningKey.get() == null ? "" : requestSigningKey.get().trim();
    }

    public String getOrCreateDeviceId() {
        String value = deviceId.get() == null ? "" : deviceId.get().trim();
        if (!value.isBlank()) return value;

        value = UUID.randomUUID().toString();
        deviceId.set(value);
        return value;
    }

    public boolean allowHttp() {
        return allowHttp.get();
    }

    public boolean useStream() {
        return useStream.get();
    }

    public int requestTimeoutSec() {
        return REQUEST_TIMEOUT_SEC;
    }

    public int streamWaitMs() {
        return STREAM_WAIT_MS;
    }
}
