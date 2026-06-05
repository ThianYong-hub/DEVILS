package com.devils.addon.shared.sync;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.Settings;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IVisible;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;
import sun.reflect.ReflectionFactory;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Constructor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SyncConfigMigrationRuntimeTest {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final String GENERAL_GROUP = "General";

    @Test
    void migrationScenariosProduceStableBeforeAfterArtifacts() throws IOException {
        ScenarioResult legacyOnly = runScenario(
            "legacy-only",
            orderedMap(
                "base-url", "https://sync.example.com",
                "device-id", "device-legacy",
                "token", "legacy-auth",
                "request-signing-key", "legacy-signing",
                "encryption-key", "legacy-e2e"
            )
        );
        ScenarioResult duplicate = runScenario(
            "preferred-and-legacy-same",
            orderedMap(
                "base-url", "https://sync.example.com",
                "device-id", "device-duplicate",
                "auth-token", "shared-auth",
                "token", "shared-auth",
                "transport-signing-key", "shared-signing",
                "request-signing-key", "shared-signing",
                "e2e-secret", "shared-e2e",
                "encryption-key", "shared-e2e"
            )
        );
        ScenarioResult conflict = runScenario(
            "preferred-and-legacy-conflict",
            orderedMap(
                "base-url", "https://sync.example.com",
                "device-id", "device-conflict",
                "auth-token", "preferred-auth",
                "token", "legacy-auth",
                "transport-signing-key", "preferred-signing",
                "e2e-secret", "preferred-e2e"
            )
        );
        ScenarioResult preferredOnly = runScenario(
            "preferred-only",
            orderedMap(
                "base-url", "https://sync.example.com",
                "device-id", "device-preferred",
                "auth-token", "preferred-auth",
                "transport-signing-key", "preferred-signing",
                "e2e-secret", "preferred-e2e"
            )
        );

        assertEquals(SyncConfigDiagnostics.Mode.PREFERRED_ONLY.name(), legacyOnly.firstAuditMode);
        assertTrue(legacyOnly.firstIssueCodes.contains("sync-auth-token-legacy-migrated"));
        assertTrue(legacyOnly.firstIssueCodes.contains("sync-transport-signing-key-legacy-migrated"));
        assertTrue(legacyOnly.firstIssueCodes.contains("sync-e2e-secret-legacy-migrated"));
        assertFalse(legacyOnly.savedSettings.containsKey("token"));
        assertFalse(legacyOnly.savedSettings.containsKey("request-signing-key"));
        assertFalse(legacyOnly.savedSettings.containsKey("encryption-key"));
        assertEquals("legacy-auth", legacyOnly.savedSettings.get("auth-token"));
        assertEquals(SyncConfigDiagnostics.Mode.PREFERRED_ONLY.name(), legacyOnly.secondAuditMode);
        assertFalse(legacyOnly.secondIssueCodes.stream().anyMatch(code -> code.contains("legacy")));

        assertEquals(SyncConfigDiagnostics.Mode.PREFERRED_ONLY.name(), duplicate.firstAuditMode);
        assertTrue(duplicate.firstIssueCodes.contains("sync-auth-token-legacy-duplicate-cleared"));
        assertTrue(duplicate.firstIssueCodes.contains("sync-transport-signing-key-legacy-duplicate-cleared"));
        assertTrue(duplicate.firstIssueCodes.contains("sync-e2e-secret-legacy-duplicate-cleared"));
        assertFalse(duplicate.savedSettings.containsKey("token"));
        assertFalse(duplicate.savedSettings.containsKey("request-signing-key"));
        assertFalse(duplicate.savedSettings.containsKey("encryption-key"));
        assertEquals(SyncConfigDiagnostics.Mode.PREFERRED_ONLY.name(), duplicate.secondAuditMode);
        assertFalse(duplicate.secondIssueCodes.stream().anyMatch(code -> code.contains("legacy")));

        assertEquals("preferred-auth", conflict.firstResolvedToken);
        assertEquals(SyncConfigDiagnostics.Mode.CONFLICTING.name(), conflict.firstAuditMode);
        assertTrue(conflict.firstIssueCodes.contains("sync-config-conflicting-mode"));
        assertTrue(conflict.firstIssueCodes.contains("sync-auth-token-legacy-conflict"));
        assertEquals("preferred-auth", conflict.savedSettings.get("auth-token"));
        assertEquals("legacy-auth", conflict.savedSettings.get("token"));
        assertEquals(SyncConfigDiagnostics.Mode.CONFLICTING.name(), conflict.secondAuditMode);
        assertTrue(conflict.secondIssueCodes.contains("sync-auth-token-legacy-conflict"));

        assertEquals(SyncConfigDiagnostics.Mode.PREFERRED_ONLY.name(), preferredOnly.firstAuditMode);
        assertTrue(preferredOnly.firstIssueCodes.isEmpty());
        assertEquals(SyncConfigDiagnostics.Mode.PREFERRED_ONLY.name(), preferredOnly.secondAuditMode);
        assertTrue(preferredOnly.secondIssueCodes.isEmpty());

        Path reportPath = Path.of("build", "test-artifacts", "sync-config-migration-runtime.json").normalize();
        Files.createDirectories(reportPath.getParent());
        JsonObject root = new JsonObject();
        root.add("scenarios", asScenarioJsonArray(List.of(legacyOnly, duplicate, conflict, preferredOnly)));
        Files.writeString(reportPath, GSON.toJson(root));
    }

    @Test
    void repeatedDiagnosticsDoNotSpamUntilSignatureChanges() {
        TestSyncModule module = TestSyncModule.create();
        SyncConfigDiagnostics.Audit legacyAudit = module.inspectSyncConfig(true, false);
        module.emitSyncConfigDiagnostics("smoke", legacyAudit);
        module.emitSyncConfigDiagnostics("smoke", legacyAudit);

        int firstWarningCount = module.warnings.size();
        int firstErrorCount = module.errors.size();
        assertEquals(firstWarningCount, module.warnings.size());
        assertEquals(firstErrorCount, module.errors.size());

        module.settings.get("device-id", String.class).set("device-a");
        module.settings.get("base-url", String.class).set("https://sync.example.com");

        SyncConfigDiagnostics.Audit changedAudit = module.inspectSyncConfig(true, false);
        module.emitSyncConfigDiagnostics("smoke", changedAudit);

        assertNotEquals(legacyAudit.signature(), changedAudit.signature());
        assertTrue(module.errors.size() > firstErrorCount);
    }

    private static ScenarioResult runScenario(String name, Map<String, String> initialSettings) {
        TestSyncModule firstModule = TestSyncModule.create();
        firstModule.fromTag(createModuleTag(firstModule, initialSettings));
        SyncConfigDiagnostics.Audit firstAudit = firstModule.inspectSyncConfig(true, false);
        firstModule.emitSyncConfigDiagnostics("runtime-smoke", firstAudit);
        NbtCompound savedTag = firstModule.toTag();
        Map<String, String> savedSettings = extractSettings(savedTag);

        TestSyncModule secondModule = TestSyncModule.create();
        secondModule.fromTag(savedTag);
        SyncConfigDiagnostics.Audit secondAudit = secondModule.inspectSyncConfig(true, false);
        secondModule.emitSyncConfigDiagnostics("runtime-smoke", secondAudit);

        return new ScenarioResult(
            name,
            new LinkedHashMap<>(initialSettings),
            firstAudit.overallMode().name(),
            issueCodes(firstAudit),
            List.copyOf(firstModule.warnings),
            List.copyOf(firstModule.errors),
            firstModule.getToken(),
            new LinkedHashMap<>(savedSettings),
            secondAudit.overallMode().name(),
            issueCodes(secondAudit),
            List.copyOf(secondModule.warnings),
            List.copyOf(secondModule.errors),
            secondModule.getToken()
        );
    }

    private static JsonArray asScenarioJsonArray(List<ScenarioResult> results) {
        JsonArray array = new JsonArray();
        for (ScenarioResult result : results) {
            JsonObject object = new JsonObject();
            object.addProperty("name", result.name);
            object.add("initialSettings", asJsonObject(result.initialSettings));
            object.addProperty("firstAuditMode", result.firstAuditMode);
            object.add("firstIssueCodes", asStringJsonArray(result.firstIssueCodes));
            object.add("firstWarnings", asStringJsonArray(result.firstWarnings));
            object.add("firstErrors", asStringJsonArray(result.firstErrors));
            object.addProperty("firstResolvedToken", result.firstResolvedToken);
            object.add("savedSettings", asJsonObject(result.savedSettings));
            object.addProperty("secondAuditMode", result.secondAuditMode);
            object.add("secondIssueCodes", asStringJsonArray(result.secondIssueCodes));
            object.add("secondWarnings", asStringJsonArray(result.secondWarnings));
            object.add("secondErrors", asStringJsonArray(result.secondErrors));
            object.addProperty("secondResolvedToken", result.secondResolvedToken);
            array.add(object);
        }
        return array;
    }

    private static JsonObject asJsonObject(Map<String, String> values) {
        JsonObject object = new JsonObject();
        for (Map.Entry<String, String> entry : values.entrySet()) object.addProperty(entry.getKey(), entry.getValue());
        return object;
    }

    private static JsonArray asStringJsonArray(List<String> values) {
        JsonArray array = new JsonArray();
        for (String value : values) array.add(value);
        return array;
    }

    private static List<String> issueCodes(SyncConfigDiagnostics.Audit audit) {
        List<String> codes = new ArrayList<>();
        for (SyncConfigDiagnostics.Issue issue : audit.issues()) codes.add(issue.code());
        return codes;
    }

    private static NbtCompound createModuleTag(TestSyncModule module, Map<String, String> settingsValues) {
        NbtCompound tag = new NbtCompound();
        tag.putString("name", module.name);
        tag.put("keybind", module.keybind.toTag());
        tag.putBoolean("toggleOnKeyRelease", false);
        tag.putBoolean("chatFeedback", true);
        tag.putBoolean("favorite", false);
        tag.putBoolean("active", false);
        tag.put("settings", createSettingsTag(settingsValues));
        return tag;
    }

    private static NbtCompound createSettingsTag(Map<String, String> settingsValues) {
        NbtCompound settingsTag = new NbtCompound();
        NbtList groups = new NbtList();
        NbtCompound general = new NbtCompound();
        general.putString("name", GENERAL_GROUP);
        general.putBoolean("sectionExpanded", true);

        NbtList settings = new NbtList();
        for (Map.Entry<String, String> entry : settingsValues.entrySet()) {
            NbtCompound setting = new NbtCompound();
            setting.putString("name", entry.getKey());
            setting.putString("value", entry.getValue());
            settings.add(setting);
        }

        general.put("settings", settings);
        groups.add(general);
        settingsTag.put("groups", groups);
        return settingsTag;
    }

    private static Map<String, String> extractSettings(NbtCompound tag) {
        Map<String, String> values = new LinkedHashMap<>();
        if (!(tag.get("settings") instanceof NbtCompound settingsTag)) return values;
        NbtList groups = settingsTag.getListOrEmpty("groups");
        for (NbtElement groupElement : groups) {
            if (!(groupElement instanceof NbtCompound groupTag)) continue;
            NbtList settings = groupTag.getListOrEmpty("settings");
            for (NbtElement settingElement : settings) {
                if (!(settingElement instanceof NbtCompound settingTag)) continue;
                String name = settingTag.getString("name", "");
                String value = settingTag.getString("value", "");
                if (!name.isBlank()) values.put(name, value);
            }
        }
        return values;
    }

    private static Map<String, String> orderedMap(String... values) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) map.put(values[i], values[i + 1]);
        return map;
    }

    private static void setField(Class<?> owner, Object target, String fieldName, Object value) {
        try {
            Field field = owner.getDeclaredField(fieldName);
            field.setAccessible(true);
            unsafe().putObject(target, unsafe().objectFieldOffset(field), value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to set field " + owner.getSimpleName() + "." + fieldName, e);
        }
    }

    private static void setBooleanField(Class<?> owner, Object target, String fieldName, boolean value) {
        try {
            Field field = owner.getDeclaredField(fieldName);
            field.setAccessible(true);
            unsafe().putBoolean(target, unsafe().objectFieldOffset(field), value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to set boolean field " + owner.getSimpleName() + "." + fieldName, e);
        }
    }

    private static Unsafe unsafe() {
        try {
            Field field = Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            return (Unsafe) field.get(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Unable to access Unsafe for test-only module allocation.", e);
        }
    }

    private static final class TestSyncModule extends AbstractSyncConfigModule {
        private final List<String> infos = new ArrayList<>();
        private final List<String> warnings = new ArrayList<>();
        private final List<String> errors = new ArrayList<>();

        private TestSyncModule() {
            super(null, "sync-test", "Sync test harness.");
        }

        private static TestSyncModule create() {
            try {
                Constructor<Object> objectCtor = Object.class.getDeclaredConstructor();
                Constructor<?> syntheticCtor = ReflectionFactory.getReflectionFactory()
                    .newConstructorForSerialization(TestSyncModule.class, objectCtor);
                syntheticCtor.setAccessible(true);
                TestSyncModule module = (TestSyncModule) syntheticCtor.newInstance();

                Settings settings = new Settings();
                SettingGroup general = settings.getDefaultGroup();

                Setting<String> baseUrl = general.add(newStringSetting(
                    "base-url",
                    "Sync API base URL, e.g. http://127.0.0.1:7878 or https://sync.example.com.",
                    "",
                    null
                ));
                Setting<String> authToken = general.add(newStringSetting(
                    "auth-token",
                    "Bearer credential for sync transport authentication. Preferred backend env: SYNC_AUTH_TOKEN.",
                    "",
                    null
                ));
                Setting<String> transportSigningKey = general.add(newStringSetting(
                    "transport-signing-key",
                    "HMAC key for signed sync requests. Keep this separate from the E2E secret. Preferred backend env: SYNC_REQUEST_SIGNING_KEY.",
                    "",
                    null
                ));
                Setting<String> e2eSecret = general.add(newStringSetting(
                    "e2e-secret",
                    "Client-only secret for E2E payload encryption. The backend never needs this value. Preferred client env: SYNC_E2E_SECRET.",
                    "",
                    null
                ));
                Setting<String> deviceId = general.add(newStringSetting(
                    "device-id",
                    "Stable ID for this client in sync hub. Leave it alone unless you intentionally want a new client identity.",
                    "",
                    null
                ));
                Setting<Boolean> allowHttp = general.add(newBoolSetting(
                    "allow-http",
                    "Allow plain HTTP endpoints (unsafe on public networks).",
                    false,
                    null
                ));
                Setting<Boolean> useStream = general.add(newBoolSetting(
                    "use-stream",
                    "Use server stream channel for sync updates when supported.",
                    true,
                    null
                ));
                Setting<String> legacyToken = general.add(newStringSetting(
                    "token",
                    "Legacy compatibility field for auth-token.",
                    "",
                    () -> false
                ));
                Setting<String> legacyEncryptionKey = general.add(newStringSetting(
                    "encryption-key",
                    "Legacy compatibility field for e2e-secret.",
                    "",
                    () -> false
                ));
                Setting<String> legacyRequestSigningKey = general.add(newStringSetting(
                    "request-signing-key",
                    "Legacy compatibility field for transport-signing-key.",
                    "",
                    () -> false
                ));

                setField(meteordevelopment.meteorclient.systems.modules.Module.class, module, "mc", null);
                setField(meteordevelopment.meteorclient.systems.modules.Module.class, module, "category", null);
                setField(meteordevelopment.meteorclient.systems.modules.Module.class, module, "name", "sync-test");
                setField(meteordevelopment.meteorclient.systems.modules.Module.class, module, "title", "Sync Test");
                setField(meteordevelopment.meteorclient.systems.modules.Module.class, module, "description", "Sync test harness.");
                setField(meteordevelopment.meteorclient.systems.modules.Module.class, module, "aliases", new String[0]);
                setField(meteordevelopment.meteorclient.systems.modules.Module.class, module, "color", null);
                setField(meteordevelopment.meteorclient.systems.modules.Module.class, module, "addon", null);
                setField(meteordevelopment.meteorclient.systems.modules.Module.class, module, "settings", settings);
                setField(meteordevelopment.meteorclient.systems.modules.Module.class, module, "keybind", Keybind.none());
                setBooleanField(meteordevelopment.meteorclient.systems.modules.Module.class, module, "active", false);
                setBooleanField(meteordevelopment.meteorclient.systems.modules.Module.class, module, "serialize", true);
                setBooleanField(meteordevelopment.meteorclient.systems.modules.Module.class, module, "runInMainMenu", false);
                setBooleanField(meteordevelopment.meteorclient.systems.modules.Module.class, module, "autoSubscribe", true);
                setBooleanField(meteordevelopment.meteorclient.systems.modules.Module.class, module, "toggleOnBindRelease", false);
                setBooleanField(meteordevelopment.meteorclient.systems.modules.Module.class, module, "chatFeedback", true);
                setBooleanField(meteordevelopment.meteorclient.systems.modules.Module.class, module, "favorite", false);

                setField(AbstractSyncConfigModule.class, module, "sgGeneral", general);
                setField(AbstractSyncConfigModule.class, module, "baseUrl", baseUrl);
                setField(AbstractSyncConfigModule.class, module, "authToken", authToken);
                setField(AbstractSyncConfigModule.class, module, "transportSigningKey", transportSigningKey);
                setField(AbstractSyncConfigModule.class, module, "e2eSecret", e2eSecret);
                setField(AbstractSyncConfigModule.class, module, "deviceId", deviceId);
                setField(AbstractSyncConfigModule.class, module, "allowHttp", allowHttp);
                setField(AbstractSyncConfigModule.class, module, "useStream", useStream);
                setField(AbstractSyncConfigModule.class, module, "legacyToken", legacyToken);
                setField(AbstractSyncConfigModule.class, module, "legacyEncryptionKey", legacyEncryptionKey);
                setField(AbstractSyncConfigModule.class, module, "legacyRequestSigningKey", legacyRequestSigningKey);
                setBooleanField(AbstractSyncConfigModule.class, module, "authTokenMigratedFromLegacy", false);
                setBooleanField(AbstractSyncConfigModule.class, module, "transportSigningKeyMigratedFromLegacy", false);
                setBooleanField(AbstractSyncConfigModule.class, module, "e2eSecretMigratedFromLegacy", false);
                setBooleanField(AbstractSyncConfigModule.class, module, "authTokenDuplicateLegacyCleared", false);
                setBooleanField(AbstractSyncConfigModule.class, module, "transportSigningKeyDuplicateLegacyCleared", false);
                setBooleanField(AbstractSyncConfigModule.class, module, "e2eSecretDuplicateLegacyCleared", false);
                setField(AbstractSyncConfigModule.class, module, "lastDiagnosticSignatureByConsumer", new HashMap<>());
                setField(AbstractSyncConfigModule.class, module, "lastDiagnosticLogMsByConsumer", new HashMap<>());

                setField(TestSyncModule.class, module, "infos", new ArrayList<>());
                setField(TestSyncModule.class, module, "warnings", new ArrayList<>());
                setField(TestSyncModule.class, module, "errors", new ArrayList<>());

                return module;
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Failed to allocate test sync module.", e);
            }
        }

        @Override
        public void info(String message, Object... args) {
            infos.add(format(message, args));
        }

        @Override
        public void warning(String message, Object... args) {
            warnings.add(format(message, args));
        }

        @Override
        public void error(String message, Object... args) {
            errors.add(format(message, args));
        }

        private static String format(String message, Object... args) {
            if (args == null || args.length == 0) return message;
            return String.format(Locale.ROOT, message, args);
        }
    }

    private static StringSetting newStringSetting(String name, String description, String defaultValue, IVisible visible) {
        try {
            StringSetting setting = (StringSetting) unsafe().allocateInstance(StringSetting.class);
            initializeSettingFields(setting, name, description, defaultValue, visible);
            setField(StringSetting.class, setting, "placeholder", null);
            setField(StringSetting.class, setting, "renderer", null);
            setField(StringSetting.class, setting, "filter", null);
            setBooleanField(StringSetting.class, setting, "wide", false);
            return setting;
        } catch (InstantiationException e) {
            throw new IllegalStateException("Failed to allocate StringSetting for test harness.", e);
        }
    }

    private static BoolSetting newBoolSetting(String name, String description, boolean defaultValue, IVisible visible) {
        try {
            BoolSetting setting = (BoolSetting) unsafe().allocateInstance(BoolSetting.class);
            initializeSettingFields(setting, name, description, defaultValue, visible);
            return setting;
        } catch (InstantiationException e) {
            throw new IllegalStateException("Failed to allocate BoolSetting for test harness.", e);
        }
    }

    private static void initializeSettingFields(Setting<?> setting, String name, String description, Object defaultValue, IVisible visible) {
        setField(meteordevelopment.meteorclient.settings.Setting.class, setting, "name", name);
        setField(meteordevelopment.meteorclient.settings.Setting.class, setting, "title", name);
        setField(meteordevelopment.meteorclient.settings.Setting.class, setting, "description", description);
        setField(meteordevelopment.meteorclient.settings.Setting.class, setting, "visible", visible);
        setField(meteordevelopment.meteorclient.settings.Setting.class, setting, "defaultValue", defaultValue);
        setField(meteordevelopment.meteorclient.settings.Setting.class, setting, "value", defaultValue);
        setField(meteordevelopment.meteorclient.settings.Setting.class, setting, "onModuleActivated", null);
        setField(meteordevelopment.meteorclient.settings.Setting.class, setting, "onChanged", null);
        setField(meteordevelopment.meteorclient.settings.Setting.class, setting, "module", null);
        setBooleanField(meteordevelopment.meteorclient.settings.Setting.class, setting, "lastWasVisible", false);
    }

    private record ScenarioResult(
        String name,
        Map<String, String> initialSettings,
        String firstAuditMode,
        List<String> firstIssueCodes,
        List<String> firstWarnings,
        List<String> firstErrors,
        String firstResolvedToken,
        Map<String, String> savedSettings,
        String secondAuditMode,
        List<String> secondIssueCodes,
        List<String> secondWarnings,
        List<String> secondErrors,
        String secondResolvedToken
    ) {
    }
}
