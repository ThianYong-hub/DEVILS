package com.example.addon.shared.sync;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Module;
import net.minecraft.nbt.NbtCompound;

public abstract class AbstractSyncConfigModule extends Module {
    private static final int REQUEST_TIMEOUT_SEC = 3;
    private static final int STREAM_WAIT_MS = 25;
    private static final long DIAGNOSTIC_LOG_COOLDOWN_MS = 45_000L;

    protected final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<String> baseUrl = sgGeneral.add(new StringSetting.Builder()
        .name("base-url")
        .description("Sync API base URL, e.g. http://127.0.0.1:7878 or https://sync.example.com.")
        .defaultValue("")
        .build()
    );

    private final Setting<String> authToken = sgGeneral.add(new StringSetting.Builder()
        .name("auth-token")
        .description("Bearer credential for sync transport authentication. Preferred backend env: SYNC_AUTH_TOKEN.")
        .defaultValue("")
        .build()
    );

    private final Setting<String> transportSigningKey = sgGeneral.add(new StringSetting.Builder()
        .name("transport-signing-key")
        .description("HMAC key for signed sync requests. Keep this separate from the E2E secret. Preferred backend env: SYNC_REQUEST_SIGNING_KEY.")
        .defaultValue("")
        .build()
    );

    private final Setting<String> e2eSecret = sgGeneral.add(new StringSetting.Builder()
        .name("e2e-secret")
        .description("Client-only secret for E2E payload encryption. The backend never needs this value. Preferred client env: SYNC_E2E_SECRET.")
        .defaultValue("")
        .build()
    );

    private final Setting<String> deviceId = sgGeneral.add(new StringSetting.Builder()
        .name("device-id")
        .description("Stable ID for this client in sync hub. Leave it alone unless you intentionally want a new client identity.")
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

    private final Setting<String> legacyToken = sgGeneral.add(new StringSetting.Builder()
        .name("token")
        .description("Legacy compatibility field for auth-token.")
        .defaultValue("")
        .visible(() -> false)
        .build()
    );

    private final Setting<String> legacyEncryptionKey = sgGeneral.add(new StringSetting.Builder()
        .name("encryption-key")
        .description("Legacy compatibility field for e2e-secret.")
        .defaultValue("")
        .visible(() -> false)
        .build()
    );

    private final Setting<String> legacyRequestSigningKey = sgGeneral.add(new StringSetting.Builder()
        .name("request-signing-key")
        .description("Legacy compatibility field for transport-signing-key.")
        .defaultValue("")
        .visible(() -> false)
        .build()
    );

    private boolean authTokenMigratedFromLegacy;
    private boolean transportSigningKeyMigratedFromLegacy;
    private boolean e2eSecretMigratedFromLegacy;
    private boolean authTokenDuplicateLegacyCleared;
    private boolean transportSigningKeyDuplicateLegacyCleared;
    private boolean e2eSecretDuplicateLegacyCleared;
    private final Map<String, String> lastDiagnosticSignatureByConsumer = new HashMap<>();
    private final Map<String, Long> lastDiagnosticLogMsByConsumer = new HashMap<>();

    protected AbstractSyncConfigModule(Category category, String name, String description) {
        super(category, name, description);
        refreshLegacyMigration();
    }

    @Override
    public void onActivate() {
        lastDiagnosticSignatureByConsumer.clear();
        lastDiagnosticLogMsByConsumer.clear();
    }

    @Override
    public NbtCompound toTag() {
        refreshLegacyMigration();
        return super.toTag();
    }

    @Override
    public Module fromTag(NbtCompound tag) {
        super.fromTag(tag);
        refreshLegacyMigration();
        return this;
    }

    public String getBaseUrl() {
        refreshLegacyMigration();
        return normalize(baseUrl.get());
    }

    public String getToken() {
        refreshLegacyMigration();
        return firstNonBlank(authToken.get(), legacyToken.get());
    }

    public String getEncryptionKeyMaterial() {
        refreshLegacyMigration();
        return firstNonBlank(e2eSecret.get(), legacyEncryptionKey.get());
    }

    public String getRequestSigningKey() {
        refreshLegacyMigration();
        return firstNonBlank(transportSigningKey.get(), legacyRequestSigningKey.get());
    }

    public String getOrCreateDeviceId() {
        refreshLegacyMigration();
        String value = normalize(deviceId.get());
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

    public SyncConfigDiagnostics.Audit inspectSyncConfig(boolean requireE2e, boolean allowUnsignedRequests) {
        refreshLegacyMigration();

        return SyncConfigDiagnostics.inspect(
            normalize(baseUrl.get()),
            allowHttp(),
            normalize(deviceId.get()),
            SyncConfigDiagnostics.resolveField(
                "auth-token",
                "token",
                "SYNC_AUTH_TOKEN",
                "SYNC_TOKEN",
                authToken.get(),
                legacyToken.get(),
                authTokenMigratedFromLegacy,
                authTokenDuplicateLegacyCleared
            ),
            SyncConfigDiagnostics.resolveField(
                "transport-signing-key",
                "request-signing-key",
                "SYNC_REQUEST_SIGNING_KEY",
                "SYNC_SIGNING_KEY",
                transportSigningKey.get(),
                legacyRequestSigningKey.get(),
                transportSigningKeyMigratedFromLegacy,
                transportSigningKeyDuplicateLegacyCleared
            ),
            SyncConfigDiagnostics.resolveField(
                "e2e-secret",
                "encryption-key",
                "SYNC_E2E_SECRET",
                "SYNC_ENCRYPTION_KEY",
                e2eSecret.get(),
                legacyEncryptionKey.get(),
                e2eSecretMigratedFromLegacy,
                e2eSecretDuplicateLegacyCleared
            ),
            requireE2e,
            allowUnsignedRequests
        );
    }

    public void emitSyncConfigDiagnostics(String consumerName, SyncConfigDiagnostics.Audit audit) {
        if (audit == null || audit.issues().isEmpty()) return;

        String consumerKey = normalize(consumerName).toLowerCase();
        String signature = audit.signature();
        long now = System.currentTimeMillis();
        String lastSignature = lastDiagnosticSignatureByConsumer.getOrDefault(consumerKey, "");
        long lastLogMs = lastDiagnosticLogMsByConsumer.getOrDefault(consumerKey, 0L);
        if (signature.equals(lastSignature) && (now - lastLogMs) < DIAGNOSTIC_LOG_COOLDOWN_MS) return;

        lastDiagnosticSignatureByConsumer.put(consumerKey, signature);
        lastDiagnosticLogMsByConsumer.put(consumerKey, now);

        String prefix = consumerKey.isBlank() ? "Sync config" : consumerName.trim() + " sync config";
        for (SyncConfigDiagnostics.Issue issue : audit.issues()) {
            String message = prefix + ": " + issue.message();
            switch (issue.severity()) {
                case ERROR -> error(message);
                case WARNING -> warning(message);
                case INFO -> info(message);
            }
        }
    }

    private void refreshLegacyMigration() {
        authTokenMigratedFromLegacy |= migrateLegacyValue(authToken, legacyToken);
        transportSigningKeyMigratedFromLegacy |= migrateLegacyValue(transportSigningKey, legacyRequestSigningKey);
        e2eSecretMigratedFromLegacy |= migrateLegacyValue(e2eSecret, legacyEncryptionKey);

        authTokenDuplicateLegacyCleared |= clearDuplicateLegacyValue(authToken, legacyToken);
        transportSigningKeyDuplicateLegacyCleared |= clearDuplicateLegacyValue(transportSigningKey, legacyRequestSigningKey);
        e2eSecretDuplicateLegacyCleared |= clearDuplicateLegacyValue(e2eSecret, legacyEncryptionKey);
    }

    private static boolean migrateLegacyValue(Setting<String> preferred, Setting<String> legacy) {
        if (!normalize(preferred.get()).isBlank()) return false;

        String legacyValue = normalize(legacy.get());
        if (legacyValue.isBlank()) return false;

        preferred.set(legacyValue);
        legacy.set("");
        return true;
    }

    private static boolean clearDuplicateLegacyValue(Setting<String> preferred, Setting<String> legacy) {
        String preferredValue = normalize(preferred.get());
        String legacyValue = normalize(legacy.get());
        if (preferredValue.isBlank() || legacyValue.isBlank()) return false;
        if (!preferredValue.equals(legacyValue)) return false;

        legacy.set("");
        return true;
    }

    private static String firstNonBlank(String preferred, String legacy) {
        String preferredValue = normalize(preferred);
        if (!preferredValue.isBlank()) return preferredValue;
        return normalize(legacy);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
