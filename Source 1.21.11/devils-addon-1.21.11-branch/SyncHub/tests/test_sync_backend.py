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
            "/push",
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
            "/push",
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

        ping_file = self.harness.modules_dir / "ping" / "ping.json"
        ct_file = self.harness.modules_dir / "chest-tracker" / "2k2f.ru.json"
        self.assertTrue(ping_file.exists(), "ping namespace file is missing")
        self.assertTrue(ct_file.exists(), "chest-tracker namespace file is missing")

        status, pull_ping = self.harness.post_json(
            "/pull",
            {"deviceId": "device-a", "module": "ping", "knownRevision": -1},
        )
        self.assertEqual(200, status)
        self.assertTrue(pull_ping.get("ok"))
        self.assertEqual("ping", pull_ping.get("namespace"))
        self.assertEqual(1, len(pull_ping.get("profiles", [])))

        status, pull_ct = self.harness.post_json(
            "/pull",
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


if __name__ == "__main__":
    unittest.main()
