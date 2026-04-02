package com.example.addon.shared.sync;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SyncConfigDiagnosticsTest {
    @Test
    void inspectFlagsMissingRequiredFieldsAndHttpPolicy() {
        SyncConfigDiagnostics.Audit audit = SyncConfigDiagnostics.inspect(
            "http://127.0.0.1:7878",
            false,
            "",
            SyncConfigDiagnostics.resolveField("auth-token", "token", "SYNC_AUTH_TOKEN", "SYNC_TOKEN", "", "", false, false),
            SyncConfigDiagnostics.resolveField("transport-signing-key", "request-signing-key", "SYNC_REQUEST_SIGNING_KEY", "SYNC_SIGNING_KEY", "", "", false, false),
            SyncConfigDiagnostics.resolveField("e2e-secret", "encryption-key", "SYNC_E2E_SECRET", "SYNC_ENCRYPTION_KEY", "", "", false, false),
            true,
            true
        );

        assertTrue(audit.hasErrors());
        assertEquals("sync-http-disabled", audit.firstErrorCode());
        assertEquals(SyncConfigDiagnostics.Mode.DEFAULT, audit.overallMode());
        assertTrue(audit.issues().stream().anyMatch(issue -> issue.code().equals("sync-device-id-empty")));
        assertTrue(audit.issues().stream().anyMatch(issue -> issue.code().equals("sync-e2e-secret-empty")));
        assertTrue(audit.issues().stream().anyMatch(issue -> issue.code().equals("sync-auth-token-empty")));
        assertTrue(audit.issues().stream().anyMatch(issue -> issue.code().equals("sync-transport-signing-key-empty")));
    }

    @Test
    void resolveFieldTracksLegacyMigrationAndAliasConflict() {
        SyncConfigDiagnostics.FieldResolution migrated = SyncConfigDiagnostics.resolveField(
            "auth-token",
            "token",
            "SYNC_AUTH_TOKEN",
            "SYNC_TOKEN",
            "preferred-value",
            "",
            true,
            false
        );
        SyncConfigDiagnostics.FieldResolution conflict = SyncConfigDiagnostics.resolveField(
            "transport-signing-key",
            "request-signing-key",
            "SYNC_REQUEST_SIGNING_KEY",
            "SYNC_SIGNING_KEY",
            "new-signing",
            "old-signing",
            false,
            false
        );

        assertTrue(migrated.migratedFromLegacy());
        assertEquals(SyncConfigDiagnostics.Mode.PREFERRED_ONLY, migrated.mode());
        assertFalse(migrated.conflictingAliases());
        assertTrue(conflict.conflictingAliases());
        assertEquals(SyncConfigDiagnostics.Mode.CONFLICTING, conflict.mode());
        assertEquals("new-signing", conflict.resolvedValue());
    }

    @Test
    void inspectAllowsUnsignedModeWhenSigningKeyIsBlank() {
        SyncConfigDiagnostics.Audit audit = SyncConfigDiagnostics.inspect(
            "https://sync.example.com",
            false,
            "device-a",
            SyncConfigDiagnostics.resolveField("auth-token", "token", "SYNC_AUTH_TOKEN", "SYNC_TOKEN", "auth", "", false, false),
            SyncConfigDiagnostics.resolveField("transport-signing-key", "request-signing-key", "SYNC_REQUEST_SIGNING_KEY", "SYNC_SIGNING_KEY", "", "", false, false),
            SyncConfigDiagnostics.resolveField("e2e-secret", "encryption-key", "SYNC_E2E_SECRET", "SYNC_ENCRYPTION_KEY", "secret", "", false, false),
            true,
            true
        );

        assertFalse(audit.hasErrors());
        assertEquals(SyncConfigDiagnostics.Mode.PREFERRED_ONLY, audit.overallMode());
        assertTrue(audit.issues().stream().anyMatch(issue -> issue.code().equals("sync-transport-signing-key-empty")));
    }

    @Test
    void inspectReportsLegacyMixedAndConflictingModesExplicitly() {
        SyncConfigDiagnostics.Audit legacyOnly = SyncConfigDiagnostics.inspect(
            "https://sync.example.com",
            false,
            "device-a",
            SyncConfigDiagnostics.resolveField("auth-token", "token", "SYNC_AUTH_TOKEN", "SYNC_TOKEN", "", "legacy-auth", false, false),
            SyncConfigDiagnostics.resolveField("transport-signing-key", "request-signing-key", "SYNC_REQUEST_SIGNING_KEY", "SYNC_SIGNING_KEY", "", "legacy-signing", false, false),
            SyncConfigDiagnostics.resolveField("e2e-secret", "encryption-key", "SYNC_E2E_SECRET", "SYNC_ENCRYPTION_KEY", "", "legacy-e2e", false, false),
            true,
            false
        );
        SyncConfigDiagnostics.Audit mixed = SyncConfigDiagnostics.inspect(
            "https://sync.example.com",
            false,
            "device-a",
            SyncConfigDiagnostics.resolveField("auth-token", "token", "SYNC_AUTH_TOKEN", "SYNC_TOKEN", "preferred-auth", "preferred-auth", false, false),
            SyncConfigDiagnostics.resolveField("transport-signing-key", "request-signing-key", "SYNC_REQUEST_SIGNING_KEY", "SYNC_SIGNING_KEY", "preferred-signing", "", false, false),
            SyncConfigDiagnostics.resolveField("e2e-secret", "encryption-key", "SYNC_E2E_SECRET", "SYNC_ENCRYPTION_KEY", "preferred-e2e", "", false, false),
            true,
            false
        );
        SyncConfigDiagnostics.Audit conflicting = SyncConfigDiagnostics.inspect(
            "https://sync.example.com",
            false,
            "device-a",
            SyncConfigDiagnostics.resolveField("auth-token", "token", "SYNC_AUTH_TOKEN", "SYNC_TOKEN", "preferred-auth", "legacy-auth", false, false),
            SyncConfigDiagnostics.resolveField("transport-signing-key", "request-signing-key", "SYNC_REQUEST_SIGNING_KEY", "SYNC_SIGNING_KEY", "preferred-signing", "", false, false),
            SyncConfigDiagnostics.resolveField("e2e-secret", "encryption-key", "SYNC_E2E_SECRET", "SYNC_ENCRYPTION_KEY", "preferred-e2e", "", false, false),
            true,
            false
        );

        assertEquals(SyncConfigDiagnostics.Mode.LEGACY_ONLY, legacyOnly.overallMode());
        assertTrue(legacyOnly.issues().stream().anyMatch(issue -> issue.code().equals("sync-config-legacy-mode")));
        assertEquals(SyncConfigDiagnostics.Mode.MIXED, mixed.overallMode());
        assertTrue(mixed.issues().stream().anyMatch(issue -> issue.code().equals("sync-config-mixed-mode")));
        assertEquals(SyncConfigDiagnostics.Mode.CONFLICTING, conflicting.overallMode());
        assertTrue(conflicting.issues().stream().anyMatch(issue -> issue.code().equals("sync-config-conflicting-mode")));
    }

    @Test
    void inspectEmitsFieldLevelMigrationAndConflictWarnings() {
        SyncConfigDiagnostics.Audit audit = SyncConfigDiagnostics.inspect(
            "https://sync.example.com",
            false,
            "device-a",
            SyncConfigDiagnostics.resolveField(
                "auth-token",
                "token",
                "SYNC_AUTH_TOKEN",
                "SYNC_TOKEN",
                "preferred-auth",
                "",
                true,
                false
            ),
            SyncConfigDiagnostics.resolveField(
                "transport-signing-key",
                "request-signing-key",
                "SYNC_REQUEST_SIGNING_KEY",
                "SYNC_SIGNING_KEY",
                "preferred-signing",
                "preferred-signing",
                false,
                true
            ),
            SyncConfigDiagnostics.resolveField(
                "e2e-secret",
                "encryption-key",
                "SYNC_E2E_SECRET",
                "SYNC_ENCRYPTION_KEY",
                "preferred-e2e",
                "legacy-e2e",
                false,
                false
            ),
            true,
            false
        );

        assertTrue(audit.issues().stream().anyMatch(issue -> issue.code().equals("sync-auth-token-legacy-migrated")));
        assertTrue(audit.issues().stream().anyMatch(issue -> issue.code().equals("sync-transport-signing-key-legacy-duplicate-cleared")));
        assertTrue(audit.issues().stream().anyMatch(issue -> issue.code().equals("sync-e2e-secret-legacy-conflict")));
    }
}
