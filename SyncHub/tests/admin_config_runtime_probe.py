#!/usr/bin/env python3
import argparse
import hashlib
import hmac
import json
import os
import re
import socket
import subprocess
import sys
import tempfile
import threading
import time
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen


REPO_ROOT = Path(__file__).resolve().parents[2]
BACKEND_PATH = REPO_ROOT / "SyncHub" / "sync_backend.py"
ARTIFACT_DIR = REPO_ROOT / "codex log"
NONCE_PATTERN = re.compile(r"^[a-fA-F0-9]{16,128}$")


def find_free_port() -> int:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
        sock.bind(("127.0.0.1", 0))
        sock.listen(1)
        return int(sock.getsockname()[1])


def wait_for_health(base_url: str, timeout_sec: float) -> None:
    deadline = time.time() + timeout_sec
    last_error = None
    while time.time() < deadline:
        try:
            status, _ = get_json(base_url + "/health")
            if status == 200:
                return
        except Exception as error:  # noqa: BLE001
            last_error = error
        time.sleep(0.15)
    raise RuntimeError(f"backend did not become healthy within {timeout_sec:.1f}s: {last_error}")


def get_json(url: str, headers: dict | None = None) -> tuple[int, dict]:
    request = Request(url, headers={"Accept": "application/json", **(headers or {})}, method="GET")
    try:
        with urlopen(request, timeout=5) as response:
            return response.status, json.loads(response.read().decode("utf-8"))
    except HTTPError as error:
        body = error.read().decode("utf-8") if error.fp else ""
        payload = json.loads(body) if body.strip() else {}
        return error.code, payload


def signed_headers(path: str, signing_key: str) -> dict[str, str]:
    timestamp = str(int(time.time()))
    nonce = hashlib.sha256(f"{time.time_ns()}:{path}".encode("utf-8")).hexdigest()[:32]
    if not NONCE_PATTERN.fullmatch(nonce):
        raise RuntimeError(f"generated nonce does not satisfy backend pattern: {nonce}")
    body_hash = hashlib.sha256(b"").hexdigest()
    canonical = "\n".join(["GET", path, timestamp, nonce, body_hash]).encode("utf-8")
    signature = hmac.new(signing_key.encode("utf-8"), canonical, hashlib.sha256).hexdigest()
    return {
        "X-Devils-Timestamp": timestamp,
        "X-Devils-Nonce": nonce,
        "X-Devils-Signature": signature,
        "X-Devils-Signature-Version": "v1",
    }


def capture_output(pipe, sink: list[str]) -> None:
    try:
        for line in iter(pipe.readline, ""):
            if not line:
                break
            sink.append(line.rstrip())
    finally:
        pipe.close()


def filtered_log(lines: list[str]) -> list[str]:
    interesting = []
    for line in lines:
        if (
            "Devils sync hub backend started" in line
            or "listen         :" in line
            or "auth-token     :" in line
            or "admin-auth     :" in line
            or "require-e2e    :" in line
            or "require-signed :" in line
            or "config-mode    :" in line
            or "deprecation    :" in line
            or "/v1/admin/config" in line
        ):
            interesting.append(line)
    return interesting


def scenario_env(name: str) -> tuple[dict[str, str], str, str]:
    if name == "preferred-only":
        return (
            {
                "SYNC_AUTH_TOKEN": "preferred-auth-token",
                "SYNC_ADMIN_AUTH_TOKEN": "preferred-admin-token",
                "SYNC_REQUEST_SIGNING_KEY": "preferred-signing-key",
                "SYNC_REQUIRE_REQUEST_SIGNING": "true",
                "SYNC_REQUIRE_E2E": "true",
            },
            "preferred-admin-token",
            "preferred-signing-key",
        )
    if name == "legacy-only":
        return (
            {
                "SYNC_TOKEN": "legacy-auth-token",
                "SYNC_ADMIN_TOKEN": "legacy-admin-token",
                "SYNC_SIGNING_KEY": "legacy-signing-key",
                "SYNC_REQUIRE_SIGNED": "true",
                "SYNC_REQUIRE_ENCRYPTED": "true",
            },
            "legacy-admin-token",
            "legacy-signing-key",
        )
    if name == "mixed":
        return (
            {
                "SYNC_AUTH_TOKEN": "mixed-auth-token",
                "SYNC_ADMIN_AUTH_TOKEN": "mixed-admin-token",
                "SYNC_SIGNING_KEY": "legacy-signing-key",
                "SYNC_REQUIRE_REQUEST_SIGNING": "true",
                "SYNC_REQUIRE_ENCRYPTED": "true",
            },
            "mixed-admin-token",
            "legacy-signing-key",
        )
    if name == "conflicting":
        return (
            {
                "SYNC_AUTH_TOKEN": "preferred-auth-token",
                "SYNC_TOKEN": "legacy-auth-token",
                "SYNC_ADMIN_AUTH_TOKEN": "conflict-admin-token",
                "SYNC_REQUEST_SIGNING_KEY": "preferred-signing-key",
                "SYNC_SIGNING_KEY": "legacy-signing-key",
                "SYNC_REQUIRE_REQUEST_SIGNING": "true",
                "SYNC_REQUIRE_SIGNED": "false",
                "SYNC_REQUIRE_E2E": "true",
            },
            "conflict-admin-token",
            "preferred-signing-key",
        )
    raise ValueError(f"unknown scenario: {name}")


def run_scenario(name: str) -> dict:
    scenario_vars, admin_token, signing_key = scenario_env(name)
    port = find_free_port()

    with tempfile.TemporaryDirectory(prefix=f"devils-sync-{name}-") as temp_dir:
        temp_path = Path(temp_dir)
        env = os.environ.copy()
        env.update(
            {
                "PYTHONUNBUFFERED": "1",
                "SYNC_HOST": "127.0.0.1",
                "SYNC_PORT": str(port),
                "SYNC_STATE_FILE": str(temp_path / "state.json"),
                "SYNC_NAMESPACES_DIR": str(temp_path / "namespaces"),
            }
        )
        env.update(scenario_vars)

        process = subprocess.Popen(
            [sys.executable, str(BACKEND_PATH)],
            cwd=str(REPO_ROOT),
            env=env,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            bufsize=1,
        )
        output: list[str] = []
        reader = threading.Thread(target=capture_output, args=(process.stdout, output), daemon=True)
        reader.start()

        base_url = f"http://127.0.0.1:{port}"
        try:
            wait_for_health(base_url, 10.0)
            unauthorized_status, unauthorized_body = get_json(base_url + "/v1/admin/config")
            authorized_status, authorized_body = get_json(
                base_url + "/v1/admin/config",
                headers={
                    "Authorization": f"Bearer {admin_token}",
                    **signed_headers("/v1/admin/config", signing_key),
                },
            )
        finally:
            process.terminate()
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=5)
            reader.join(timeout=2)

        config = authorized_body.get("config", {})
        encoded = json.dumps(config, ensure_ascii=False)
        secret_values = [
            scenario_vars.get("SYNC_AUTH_TOKEN"),
            scenario_vars.get("SYNC_TOKEN"),
            scenario_vars.get("SYNC_ADMIN_AUTH_TOKEN"),
            scenario_vars.get("SYNC_ADMIN_TOKEN"),
            scenario_vars.get("SYNC_REQUEST_SIGNING_KEY"),
            scenario_vars.get("SYNC_SIGNING_KEY"),
        ]
        secret_leakage_detected = any(value and value in encoded for value in secret_values)

        return {
            "scenario": name,
            "startupLog": filtered_log(output),
            "unauthorizedStatus": unauthorized_status,
            "unauthorizedBody": unauthorized_body,
            "authorizedStatus": authorized_status,
            "authorizedBody": authorized_body,
            "reportedMode": config.get("overallMode"),
            "reportedWarnings": config.get("warnings", []),
            "authActiveSource": config.get("authToken", {}).get("activeSource"),
            "requestSigningActiveSource": config.get("requestSigningKey", {}).get("activeSource"),
            "policy": config.get("activePolicy", {}),
            "secretLeakageDetected": secret_leakage_detected,
        }


def main() -> int:
    parser = argparse.ArgumentParser(description="Run operator-facing runtime probes against sync_backend.py")
    parser.add_argument(
        "--json-output",
        default=str(ARTIFACT_DIR / "admin-config-runtime-probe.json"),
        help="Optional JSON output file",
    )
    args = parser.parse_args()

    scenarios = ["preferred-only", "legacy-only", "mixed", "conflicting"]
    results = [run_scenario(name) for name in scenarios]
    payload = {"scenarios": results}

    output_path = Path(args.json_output)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(payload, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
