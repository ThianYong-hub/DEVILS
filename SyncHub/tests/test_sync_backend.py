import hashlib
import hmac
import importlib.util
import json
import threading
import time
import unittest
from pathlib import Path
from tempfile import TemporaryDirectory
from urllib.error import HTTPError
from urllib.request import Request, urlopen


def load_backend_module():
    root = Path(__file__).resolve().parents[1]
    backend_path = root / "sync_backend.py"
    spec = importlib.util.spec_from_file_location("devils_sync_backend", backend_path)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"Unable to load backend module from {backend_path}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


BACKEND = load_backend_module()


class BackendHarness:
    def __init__(self):
        self.tmp = TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.state_file = self.root / "sync-hub-state.json"
        self.modules_dir = self.root / "modules"
        self.store = BACKEND.SyncStore(self.state_file, self.modules_dir, 500)
        self.server = BACKEND.QuietThreadingHTTPServer(("127.0.0.1", 0), BACKEND.SyncHandler)
        self.server.sync_store = self.store
        self.server.sync_token = ""
        self.server.admin_token = ""
        self.server.allow_cors = False
        self.server.pull_wait_max_ms = 1500
        self.server.require_encrypted_sync = False
        self.server.require_signed = False
        self.server.signing_key = ""
        self.server.sign_window_sec = 30
        self.server.nonce_replay_guard = BACKEND.NonceReplayGuard(120, 10000)
        self.server.max_body_bytes = 1_048_576
        self.server.config_diagnostics = BACKEND.build_backend_config_diagnostics({}, "", "", False, False, "")
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.base_url = f"http://127.0.0.1:{self.server.server_address[1]}"

    def close(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=3)
        self.tmp.cleanup()

    def post_json(self, path: str, payload: dict):
        body = json.dumps(payload).encode("utf-8")
        req = Request(
            self.base_url + path,
            data=body,
            headers={"Content-Type": "application/json", "Accept": "application/json"},
            method="POST",
        )
        with urlopen(req, timeout=5) as resp:
            raw = resp.read().decode("utf-8")
            return resp.status, json.loads(raw)

    def post_json_signed(self, path: str, payload: dict, signing_key: str):
        body = json.dumps(payload).encode("utf-8")
        ts = str(int(time.time()))
        nonce = hashlib.sha256(f"{time.time_ns()}-{path}".encode("utf-8")).hexdigest()[:32]
        body_hash = hashlib.sha256(body).hexdigest()
        canonical = "\n".join(["POST", path, ts, nonce, body_hash]).encode("utf-8")
        signature = hmac.new(signing_key.encode("utf-8"), canonical, hashlib.sha256).hexdigest()
        req = Request(
            self.base_url + path,
            data=body,
            headers={
                "Content-Type": "application/json",
                "Accept": "application/json",
                "X-Devils-Timestamp": ts,
                "X-Devils-Nonce": nonce,
                "X-Devils-Signature": signature,
                "X-Devils-Signature-Version": "v1",
            },
            method="POST",
        )
        with urlopen(req, timeout=5) as resp:
            raw = resp.read().decode("utf-8")
            return resp.status, json.loads(raw)

    def get_json(self, path: str, headers: dict | None = None):
        req = Request(
            self.base_url + path,
            headers={"Accept": "application/json", **(headers or {})},
            method="GET",
        )
        with urlopen(req, timeout=5) as resp:
            raw = resp.read().decode("utf-8")
            return resp.status, json.loads(raw)

    def get_json_signed(self, path: str, signing_key: str, headers: dict | None = None):
        ts = str(int(time.time()))
        nonce = hashlib.sha256(f"{time.time_ns()}-{path}".encode("utf-8")).hexdigest()[:32]
        body_hash = hashlib.sha256(b"").hexdigest()
        canonical = "\n".join(["GET", path, ts, nonce, body_hash]).encode("utf-8")
        signature = hmac.new(signing_key.encode("utf-8"), canonical, hashlib.sha256).hexdigest()
        req = Request(
            self.base_url + path,
            headers={
                "Accept": "application/json",
                "X-Devils-Timestamp": ts,
                "X-Devils-Nonce": nonce,
                "X-Devils-Signature": signature,
                "X-Devils-Signature-Version": "v1",
                **(headers or {}),
            },
            method="GET",
        )
        with urlopen(req, timeout=5) as resp:
            raw = resp.read().decode("utf-8")
            return resp.status, json.loads(raw)


class TestSyncBackend(unittest.TestCase):
    def setUp(self):
        self.harness = BackendHarness()

    def tearDown(self):
        self.harness.close()

    def test_stream_routes_support_v1_legacy_and_trailing_slash(self):
        for path in (
            "/stream?deviceId=test-device&module=ping&knownRevision=-1&waitMs=1000",
            "/v1/sync/stream?deviceId=test-device&module=ping&knownRevision=-1&waitMs=1000",
            "/v1/sync/stream/?deviceId=test-device&module=ping&knownRevision=-1&waitMs=1000",
        ):
            req = Request(
                self.harness.base_url + path,
                headers={"Accept": "text/event-stream"},
                method="GET",
            )
            with urlopen(req, timeout=5) as resp:
                self.assertEqual(200, resp.status)
                lines = []
                for _ in range(8):
                    line = resp.readline().decode("utf-8")
                    if not line:
                        break
                    lines.append(line)
                    if line == "\n":
                        break
                first_event = "".join(lines)
                self.assertIn("event: ready", first_event)

    def test_module_and_namespace_storage_are_isolated(self):
        status, push_ping = self.harness.post_json(
            "/v1/core/sync/push",
            {
                "deviceId": "device-a",
                "module": "ping",
                "baseRevision": 0,
                "profiles": [
                    {
                        "enabled": True,
                        "username": "ping-user",
                        "server": "2k2f.ru",
                        "mode": "LOGIN",
                        "password": "{\"schema\":\"devils-ping-marker-v1\"}",
                        "delay": 0,
                    }
                ],
            },
        )
        self.assertEqual(200, status)
        self.assertTrue(push_ping.get("ok"))

        status, push_ct = self.harness.post_json(
            "/v1/core/sync/push",
            {
                "deviceId": "device-b",
                "module": "chest-tracker",
                "namespace": "chest-tracker:2k2f.ru",
                "baseRevision": 0,
                "profiles": [
                    {
                        "enabled": True,
                        "username": "bank:multiplayer/2k2f.ru",
                        "server": "2k2f.ru",
                        "mode": "LOGIN",
                        "password": "{\"schema\":\"devils-ct-file-v1\",\"nbt\":\"AAA\"}",
                        "delay": 0,
                    }
                ],
            },
        )
        self.assertEqual(200, status)
        self.assertTrue(push_ct.get("ok"))

        ping_file = self.harness.modules_dir / "core-sensitive" / "ping" / "ping.json"
        ct_file = self.harness.modules_dir / "core-sensitive" / "chest-tracker" / "2k2f.ru.json"
        self.assertTrue(ping_file.exists(), "ping namespace file is missing")
        self.assertTrue(ct_file.exists(), "chest-tracker namespace file is missing")

        status, pull_ping = self.harness.post_json(
            "/v1/core/sync/pull",
            {"deviceId": "device-a", "module": "ping", "knownRevision": -1},
        )
        self.assertEqual(200, status)
        self.assertTrue(pull_ping.get("ok"))
        self.assertEqual("ping", pull_ping.get("namespace"))
        self.assertEqual(1, len(pull_ping.get("profiles", [])))

        status, pull_ct = self.harness.post_json(
            "/v1/core/sync/pull",
            {
                "deviceId": "device-b",
                "module": "chest-tracker",
                "namespace": "chest-tracker:2k2f.ru",
                "knownRevision": -1,
            },
        )
        self.assertEqual(200, status)
        self.assertTrue(pull_ct.get("ok"))
        self.assertEqual("chest-tracker:2k2f.ru", pull_ct.get("namespace"))
        self.assertEqual(1, len(pull_ct.get("profiles", [])))

    def test_game_routes_use_dedicated_storage_domain(self):
        payload = {
            "deviceId": "games-a",
            "module": "mini-games",
            "baseRevision": 0,
            "profiles": [
                {
                    "enabled": True,
                    "username": "games-a",
                    "server": "2k2f.ru",
                    "mode": "LOGIN",
                    "password": "{\"v\":1,\"deviceId\":\"games-a\",\"name\":\"Games A\",\"server\":\"2k2f.ru\",\"lastSeen\":1}",
                    "delay": 0,
                }
            ],
        }

        status, push = self.harness.post_json("/v1/games/sync/push", payload)
        self.assertEqual(200, status)
        self.assertTrue(push.get("ok"))

        game_file = self.harness.modules_dir / "games" / "mini-games" / "mini-games.json"
        self.assertTrue(game_file.exists(), "mini-games namespace file is missing")

    def test_route_domain_mismatch_is_rejected(self):
        with self.assertRaises(HTTPError) as ctx:
            self.harness.post_json(
                "/v1/games/sync/push",
                {
                    "deviceId": "ping-on-games-route",
                    "module": "ping",
                    "baseRevision": 0,
                    "profiles": [],
                },
            )
        self.assertEqual(400, ctx.exception.code)
        body = json.loads(ctx.exception.read().decode("utf-8"))
        self.assertEqual("route-domain-mismatch", body.get("error"))

    def test_chest_tracker_empty_push_is_protected_by_default(self):
        status, first_push = self.harness.post_json(
            "/push",
            {
                "deviceId": "ct-device",
                "module": "chest-tracker",
                "namespace": "chest-tracker:2k2f.ru",
                "baseRevision": 0,
                "profiles": [
                    {
                        "enabled": True,
                        "username": "bank:multiplayer/2k2f.ru",
                        "server": "2k2f.ru",
                        "mode": "LOGIN",
                        "password": "{\"schema\":\"devils-ct-file-v1\",\"nbt\":\"AAA\"}",
                        "delay": 0,
                    }
                ],
            },
        )
        self.assertEqual(200, status)
        self.assertTrue(first_push.get("applied"))

        status, empty_push = self.harness.post_json(
            "/push",
            {
                "deviceId": "ct-device",
                "module": "chest-tracker",
                "namespace": "chest-tracker:2k2f.ru",
                "baseRevision": first_push.get("revision"),
                "profiles": [],
            },
        )
        self.assertEqual(200, status)
        self.assertFalse(empty_push.get("applied"))
        self.assertTrue(empty_push.get("conflict"))
        self.assertEqual("empty-overwrite-protected", empty_push.get("error"))
        self.assertEqual(1, len(empty_push.get("profiles", [])))

        status, pull = self.harness.post_json(
            "/pull",
            {
                "deviceId": "ct-device",
                "module": "chest-tracker",
                "namespace": "chest-tracker:2k2f.ru",
                "knownRevision": -1,
            },
        )
        self.assertEqual(200, status)
        self.assertEqual(1, len(pull.get("profiles", [])))

    def test_chest_tracker_empty_push_can_be_forced_explicitly(self):
        status, first_push = self.harness.post_json(
            "/push",
            {
                "deviceId": "ct-force",
                "module": "chest-tracker",
                "namespace": "chest-tracker:2k2f.ru",
                "baseRevision": 0,
                "profiles": [
                    {
                        "enabled": True,
                        "username": "bank:multiplayer/2k2f.ru",
                        "server": "2k2f.ru",
                        "mode": "LOGIN",
                        "password": "{\"schema\":\"devils-ct-file-v1\",\"nbt\":\"AAA\"}",
                        "delay": 0,
                    }
                ],
            },
        )
        self.assertEqual(200, status)
        self.assertTrue(first_push.get("applied"))

        status, forced_push = self.harness.post_json(
            "/push",
            {
                "deviceId": "ct-force",
                "module": "chest-tracker",
                "namespace": "chest-tracker:2k2f.ru",
                "baseRevision": first_push.get("revision"),
                "allowEmptyOverwrite": True,
                "profiles": [],
            },
        )
        self.assertEqual(200, status)
        self.assertTrue(forced_push.get("applied"))
        self.assertEqual(0, len(forced_push.get("profiles", [])))

        status, pull = self.harness.post_json(
            "/pull",
            {
                "deviceId": "ct-force",
                "module": "chest-tracker",
                "namespace": "chest-tracker:2k2f.ru",
                "knownRevision": -1,
            },
        )
        self.assertEqual(200, status)
        self.assertEqual(0, len(pull.get("profiles", [])))

    def test_auto_login_empty_push_is_protected_but_ping_can_be_cleared(self):
        status, auto_push = self.harness.post_json(
            "/push",
            {
                "deviceId": "auto-a",
                "module": "auto-login",
                "baseRevision": 0,
                "profiles": [
                    {
                        "enabled": True,
                        "username": "socketlost",
                        "server": "2k2f.ru",
                        "mode": "LOGIN",
                        "password": "/login 12345",
                        "delay": 20,
                    }
                ],
            },
        )
        self.assertEqual(200, status)
        self.assertTrue(auto_push.get("applied"))

        status, auto_empty = self.harness.post_json(
            "/push",
            {
                "deviceId": "auto-a",
                "module": "auto-login",
                "baseRevision": auto_push.get("revision"),
                "profiles": [],
            },
        )
        self.assertEqual(200, status)
        self.assertFalse(auto_empty.get("applied"))
        self.assertEqual("empty-overwrite-protected", auto_empty.get("error"))
        self.assertEqual(1, len(auto_empty.get("profiles", [])))

        status, ping_push = self.harness.post_json(
            "/push",
            {
                "deviceId": "ping-a",
                "module": "ping",
                "baseRevision": 0,
                "profiles": [
                    {
                        "enabled": True,
                        "username": "marker-id",
                        "server": "2k2f.ru",
                        "mode": "LOGIN",
                        "password": "{\"schema\":\"devils-ping-marker-v1\",\"createdAt\":1}",
                        "delay": 0,
                    }
                ],
            },
        )
        self.assertEqual(200, status)
        self.assertTrue(ping_push.get("applied"))

        status, ping_empty = self.harness.post_json(
            "/push",
            {
                "deviceId": "ping-a",
                "module": "ping",
                "baseRevision": ping_push.get("revision"),
                "profiles": [],
            },
        )
        self.assertEqual(200, status)
        self.assertTrue(ping_empty.get("applied"))
        self.assertEqual(0, len(ping_empty.get("profiles", [])))

    def test_signed_mode_rejects_unsigned_requests(self):
        self.harness.server.require_signed = True
        self.harness.server.signing_key = "unit-test-signing-key"
        with self.assertRaises(HTTPError) as ctx:
            self.harness.post_json(
                "/push",
                {
                    "deviceId": "sig-a",
                    "module": "ping",
                    "baseRevision": 0,
                    "profiles": [],
                },
            )
        self.assertEqual(401, ctx.exception.code)
        body = json.loads(ctx.exception.read().decode("utf-8"))
        self.assertIn("signature", body.get("error", ""))

    def test_signed_mode_accepts_valid_signature(self):
        self.harness.server.require_signed = True
        self.harness.server.signing_key = "unit-test-signing-key"
        status, push = self.harness.post_json_signed(
            "/push",
            {
                "deviceId": "sig-b",
                "module": "ping",
                "baseRevision": 0,
                "profiles": [
                    {
                        "enabled": True,
                        "username": "marker-id",
                        "server": "2k2f.ru",
                        "mode": "LOGIN",
                        "password": "{\"schema\":\"devils-ping-marker-v1\",\"createdAt\":1}",
                        "delay": 0,
                    }
                ],
            },
            "unit-test-signing-key",
        )
        self.assertEqual(200, status)
        self.assertTrue(push.get("ok"))
        self.assertTrue(push.get("applied"))

    def test_push_rejects_module_namespace_mismatch(self):
        self.harness.server.require_encrypted_sync = True
        with self.assertRaises(HTTPError) as ctx:
            self.harness.post_json(
                "/push",
                {
                    "deviceId": "mismatch-a",
                    "module": "default",
                    "namespace": "auto-login",
                    "baseRevision": 0,
                    "profiles": [
                        {
                            "enabled": True,
                            "username": "u",
                            "server": "s",
                            "mode": "LOGIN",
                            "password": "/login 123",
                            "delay": 0,
                        }
                    ],
                },
            )
        self.assertEqual(400, ctx.exception.code)
        body = json.loads(ctx.exception.read().decode("utf-8"))
        self.assertEqual("module-namespace-mismatch", body.get("error"))

    def test_malformed_encrypted_envelope_is_rejected(self):
        self.harness.server.require_encrypted_sync = True
        with self.assertRaises(HTTPError) as ctx:
            self.harness.post_json(
                "/push",
                {
                    "deviceId": "malformed-envelope",
                    "module": "auto-login",
                    "baseRevision": 0,
                    "profiles": [
                        {
                            "enabled": True,
                            "username": "__devils_e2e__",
                            "server": "*",
                            "mode": "LOGIN",
                            "password": "devils-e2e:v2:not-base64",
                            "delay": 0,
                        }
                    ],
                },
            )
        self.assertEqual(400, ctx.exception.code)
        body = json.loads(ctx.exception.read().decode("utf-8"))
        self.assertEqual("unencrypted-profiles-rejected", body.get("error"))

    def test_payload_too_large_is_rejected(self):
        self.harness.server.max_body_bytes = 256
        with self.assertRaises(HTTPError) as ctx:
            self.harness.post_json(
                "/push",
                {
                    "deviceId": "big-body",
                    "module": "ping",
                    "baseRevision": 0,
                    "profiles": [],
                    "padding": "x" * 2_048,
                },
            )
        self.assertEqual(413, ctx.exception.code)
        body = json.loads(ctx.exception.read().decode("utf-8"))
        self.assertEqual("payload-too-large", body.get("error"))

    def test_stream_wait_clamp_never_exceeds_cap(self):
        self.assertEqual(100, BACKEND.clamp_stream_wait_ms(100, 1000))
        self.assertEqual(1000, BACKEND.clamp_stream_wait_ms(5000, 1000))
        self.assertEqual(2500, BACKEND.clamp_stream_wait_ms(100, 25000))
        self.assertEqual(25000, BACKEND.clamp_stream_wait_ms(60000, 25000))

    def test_runtime_config_prefers_clear_env_aliases(self):
        cfg = BACKEND.resolve_runtime_config(
            {
                "SYNC_HOST": "0.0.0.0",
                "SYNC_PORT": "8787",
                "SYNC_AUTH_TOKEN": "preferred-auth",
                "SYNC_TOKEN": "legacy-auth",
                "SYNC_ADMIN_AUTH_TOKEN": "preferred-admin",
                "SYNC_ADMIN_TOKEN": "legacy-admin",
                "SYNC_REQUIRE_E2E": "false",
                "SYNC_REQUIRE_ENCRYPTED": "true",
                "SYNC_REQUIRE_REQUEST_SIGNING": "true",
                "SYNC_REQUIRE_SIGNED": "false",
                "SYNC_REQUEST_SIGNING_KEY": "preferred-signing",
                "SYNC_SIGNING_KEY": "legacy-signing",
            },
            Path("SyncHub") / "sync_backend.py",
        )

        self.assertEqual("0.0.0.0", cfg.host)
        self.assertEqual(8787, cfg.port)
        self.assertEqual("preferred-auth", cfg.sync_token)
        self.assertEqual("preferred-admin", cfg.admin_token)
        self.assertFalse(cfg.require_encrypted_sync)
        self.assertTrue(cfg.require_signed)
        self.assertEqual("preferred-signing", cfg.signing_key)

    def test_runtime_config_falls_back_to_legacy_env_names(self):
        cfg = BACKEND.resolve_runtime_config(
            {
                "SYNC_TOKEN": "legacy-auth",
                "SYNC_ADMIN_TOKEN": "legacy-admin",
                "SYNC_REQUIRE_ENCRYPTED": "false",
                "SYNC_REQUIRE_SIGNED": "false",
                "SYNC_SIGNING_KEY": "legacy-signing",
            },
            Path("SyncHub") / "sync_backend.py",
        )

        self.assertEqual("legacy-auth", cfg.sync_token)
        self.assertEqual("legacy-admin", cfg.admin_token)
        self.assertFalse(cfg.require_encrypted_sync)
        self.assertFalse(cfg.require_signed)
        self.assertEqual("legacy-signing", cfg.signing_key)

    def test_backend_config_diagnostics_detects_mixed_and_conflicting_modes(self):
        diagnostics = BACKEND.build_backend_config_diagnostics(
            {
                "SYNC_AUTH_TOKEN": "preferred-auth",
                "SYNC_TOKEN": "legacy-auth",
                "SYNC_REQUEST_SIGNING_KEY": "preferred-signing",
                "SYNC_SIGNING_KEY": "preferred-signing",
                "SYNC_REQUIRE_REQUEST_SIGNING": "true",
                "SYNC_REQUIRE_SIGNED": "true",
                "SYNC_REQUIRE_E2E": "true",
            },
            "preferred-auth",
            "",
            True,
            True,
            "preferred-signing",
        )

        self.assertEqual("conflicting", diagnostics.overall_mode)
        self.assertEqual("conflict", diagnostics.auth_token.mode)
        self.assertEqual("mixed", diagnostics.request_signing_key.mode)
        self.assertTrue(any("SYNC_TOKEN" in warning for warning in diagnostics.warnings))

    def test_admin_config_endpoint_is_safe_and_does_not_leak_secret_values(self):
        self.harness.server.admin_token = "admin-secret"
        self.harness.server.config_diagnostics = BACKEND.build_backend_config_diagnostics(
            {
                "SYNC_TOKEN": "legacy-auth-value",
                "SYNC_SIGNING_KEY": "legacy-signing-value",
                "SYNC_REQUIRE_SIGNED": "true",
            },
            "legacy-auth-value",
            "admin-secret",
            True,
            True,
            "legacy-signing-value",
        )

        status, body = self.harness.get_json(
            "/v1/admin/config",
            headers={"Authorization": "Bearer admin-secret"},
        )

        self.assertEqual(200, status)
        self.assertTrue(body.get("ok"))
        config = body.get("config", {})
        encoded = json.dumps(config)
        self.assertEqual("legacy-only", config.get("overallMode"))
        self.assertNotIn("legacy-auth-value", encoded)
        self.assertNotIn("legacy-signing-value", encoded)
        self.assertIn("warnings", config)
        self.assertEqual("SYNC_TOKEN", config.get("authToken", {}).get("activeSource"))

    def test_admin_config_endpoint_requires_admin_auth(self):
        self.harness.server.admin_token = "admin-secret"

        with self.assertRaises(HTTPError) as ctx:
            self.harness.get_json("/v1/admin/config")

        self.assertEqual(401, ctx.exception.code)

    def test_admin_config_endpoint_requires_signature_when_backend_requires_signed_requests(self):
        self.harness.server.admin_token = "admin-secret"
        self.harness.server.require_signed = True
        self.harness.server.signing_key = "admin-signing-secret"
        self.harness.server.config_diagnostics = BACKEND.build_backend_config_diagnostics(
            {
                "SYNC_AUTH_TOKEN": "auth-secret",
                "SYNC_ADMIN_AUTH_TOKEN": "admin-secret",
                "SYNC_REQUEST_SIGNING_KEY": "admin-signing-secret",
                "SYNC_REQUIRE_REQUEST_SIGNING": "true",
            },
            "auth-secret",
            "admin-secret",
            False,
            True,
            "admin-signing-secret",
        )

        with self.assertRaises(HTTPError) as ctx:
            self.harness.get_json(
                "/v1/admin/config",
                headers={"Authorization": "Bearer admin-secret"},
            )

        self.assertEqual(401, ctx.exception.code)
        body = json.loads(ctx.exception.read().decode("utf-8"))
        self.assertEqual("signature-headers-missing", body.get("error"))

        status, signed_body = self.harness.get_json_signed(
            "/v1/admin/config",
            "admin-signing-secret",
            headers={"Authorization": "Bearer admin-secret"},
        )

        self.assertEqual(200, status)
        self.assertTrue(signed_body.get("ok"))
        self.assertEqual("preferred-only", signed_body.get("config", {}).get("overallMode"))


if __name__ == "__main__":
    unittest.main()
