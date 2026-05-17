#!/usr/bin/env python3
"""
2-minute SyncHub stress tester for Devils sync transport.

Focus:
- signed /pull + /push load
- optional /v1/sync/stream watcher
- xaero-world-map encrypted payload path (same envelope format as client)
- conflict/retry behavior and propagation delay between simulated clients
- optional elytra48 mode with drift measurement (48 blocks/sec trajectory)
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import hmac
import json
import math
import os
import random
import threading
import time
from collections import Counter, defaultdict
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any
from urllib.parse import urlsplit

import requests
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

ENVELOPE_USERNAME = "__devils_e2e__"
ENVELOPE_SERVER = "*"
ENVELOPE_MODE = "LOGIN"
ENVELOPE_PREFIX_V1 = "devils-e2e:v1:"
ENVELOPE_PREFIX_V2 = "devils-e2e:v2:"

PBKDF2_ITERATIONS = 310_000
KEY_BYTES = 32
NONCE_BYTES = 12
SALT_BYTES = 16

PRESENCE_SCHEMA = "devils-xaero-presence-v1"


def b64url_no_pad(raw: bytes) -> str:
    return base64.urlsafe_b64encode(raw).decode("ascii").rstrip("=")


def b64url_decode(raw: str) -> bytes:
    value = (raw or "").strip()
    if not value:
        raise ValueError("empty-b64")
    padding = "=" * ((4 - (len(value) % 4)) % 4)
    return base64.urlsafe_b64decode(value + padding)


def sha256_hex_bytes(raw: bytes) -> str:
    return hashlib.sha256(raw).hexdigest()


def raw_target(url: str) -> str:
    split = urlsplit(url)
    path = split.path or "/"
    return f"{path}?{split.query}" if split.query else path


def make_signature_headers(method: str, url: str, body: bytes, signing_key: str) -> dict[str, str]:
    if not signing_key:
        return {}
    ts = str(int(time.time()))
    nonce = os.urandom(16).hex()
    canonical = "\n".join(
        [
            method.upper(),
            raw_target(url),
            ts,
            nonce,
            sha256_hex_bytes(body or b""),
        ]
    )
    sig = hmac.new(signing_key.encode("utf-8"), canonical.encode("utf-8"), hashlib.sha256).hexdigest()
    return {
        "X-Devils-Timestamp": ts,
        "X-Devils-Nonce": nonce,
        "X-Devils-Signature": sig,
        "X-Devils-Signature-Version": "v1",
    }


def derive_key_v1(key_material: str) -> bytes:
    digest = hashlib.sha256()
    digest.update(b"devils-sync-e2e-key|")
    digest.update(key_material.encode("utf-8"))
    return digest.digest()


def derive_key_v2(key_material: str, salt: bytes) -> bytes:
    return hashlib.pbkdf2_hmac(
        "sha256",
        key_material.encode("utf-8"),
        salt,
        PBKDF2_ITERATIONS,
        dklen=KEY_BYTES,
    )


def aad(module: str, version: int) -> bytes:
    normalized = (module or "").strip().lower()
    return f"devils-sync-e2e:aad:v{version}|{normalized}".encode("utf-8")


def key_id(key_material: str, version: int) -> str:
    digest = hashlib.sha256()
    digest.update(f"devils-sync-e2e-kid:v{version}|".encode("utf-8"))
    digest.update(key_material.encode("utf-8"))
    return b64url_no_pad(digest.digest()[:6])


def is_encrypted_envelope_row(row: dict[str, Any]) -> bool:
    if not isinstance(row, dict):
        return False
    username = str(row.get("username", "")).strip()
    password = str(row.get("password", "")).strip()
    return username == ENVELOPE_USERNAME and (
        password.startswith(ENVELOPE_PREFIX_V1) or password.startswith(ENVELOPE_PREFIX_V2)
    )


def is_encrypted_envelope(rows: list[dict[str, Any]]) -> bool:
    return isinstance(rows, list) and bool(rows) and all(is_encrypted_envelope_row(row) for row in rows)


def decrypt_envelope_row(row: dict[str, Any], key_material: str, module: str) -> list[dict[str, Any]]:
    password = str(row.get("password", "")).strip()
    version = 2 if password.startswith(ENVELOPE_PREFIX_V2) else 1
    prefix = ENVELOPE_PREFIX_V2 if version == 2 else ENVELOPE_PREFIX_V1
    packed = b64url_decode(password[len(prefix) :])
    envelope = json.loads(packed.decode("utf-8"))

    nonce = b64url_decode(str(envelope.get("n", "")))
    ciphertext = b64url_decode(str(envelope.get("ct", "")))

    if version == 2:
        salt = b64url_decode(str(envelope.get("s", "")))
        key = derive_key_v2(key_material, salt)
    else:
        key = derive_key_v1(key_material)
    plain = AESGCM(key).decrypt(nonce, ciphertext, aad(module, version))
    data = json.loads(plain.decode("utf-8"))
    return data if isinstance(data, list) else []


def encrypt_profiles_v2(plain_profiles: list[dict[str, Any]], key_material: str, module: str) -> list[dict[str, Any]]:
    plain_json = json.dumps(plain_profiles or [], ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    salt = os.urandom(SALT_BYTES)
    nonce = os.urandom(NONCE_BYTES)
    key = derive_key_v2(key_material, salt)
    ct = AESGCM(key).encrypt(nonce, plain_json, aad(module, 2))
    envelope = {
        "v": 2,
        "m": (module or "").strip().lower(),
        "s": b64url_no_pad(salt),
        "n": b64url_no_pad(nonce),
        "ct": b64url_no_pad(ct),
        "kid": key_id(key_material, 2),
    }
    packed = b64url_no_pad(json.dumps(envelope, ensure_ascii=False, separators=(",", ":")).encode("utf-8"))
    return [
        {
            "enabled": True,
            "username": ENVELOPE_USERNAME,
            "server": ENVELOPE_SERVER,
            "mode": ENVELOPE_MODE,
            "password": ENVELOPE_PREFIX_V2 + packed,
            "delay": 0,
        }
    ]


def decrypt_profiles(rows: list[dict[str, Any]], key_material: str, module: str) -> list[dict[str, Any]]:
    if not is_encrypted_envelope(rows):
        return rows or []

    merged: list[dict[str, Any]] = []
    failures = 0
    for row in rows:
        try:
            plain_rows = decrypt_envelope_row(row, key_material, module)
            for plain_row in plain_rows:
                if isinstance(plain_row, dict):
                    merged.append(plain_row)
        except Exception:
            failures += 1
            continue
    if merged or failures == 0:
        return merged
    raise ValueError("all-envelopes-decrypt-failed")


def percentile(values: list[float], p: float) -> float:
    if not values:
        return 0.0
    ordered = sorted(values)
    if len(ordered) == 1:
        return ordered[0]
    rank = (p / 100.0) * (len(ordered) - 1)
    lo = int(rank)
    hi = min(lo + 1, len(ordered) - 1)
    frac = rank - lo
    return ordered[lo] * (1.0 - frac) + ordered[hi] * frac


def summary_stats(values: list[float]) -> dict[str, float]:
    return {
        "count": len(values),
        "p50": round(percentile(values, 50), 3),
        "p95": round(percentile(values, 95), 3),
        "max": round(max(values) if values else 0.0, 3),
    }


@dataclass
class Metrics:
    lock: threading.Lock = field(default_factory=threading.Lock)
    latencies_ms: dict[str, list[float]] = field(default_factory=lambda: defaultdict(list))
    status_counts: Counter[str] = field(default_factory=Counter)
    error_counts: Counter[str] = field(default_factory=Counter)
    event_counts: Counter[str] = field(default_factory=Counter)
    propagation_lags_ms: list[float] = field(default_factory=list)
    stream_lags_ms: list[float] = field(default_factory=list)
    elytra_drift_blocks: list[float] = field(default_factory=list)
    elytra_payload_age_ms: list[float] = field(default_factory=list)

    def add_latency(self, key: str, ms: float) -> None:
        with self.lock:
            self.latencies_ms[key].append(ms)

    def inc_status(self, key: str, status_code: int) -> None:
        with self.lock:
            self.status_counts[f"{key}:{status_code}"] += 1

    def inc_error(self, key: str) -> None:
        with self.lock:
            self.error_counts[key] += 1

    def inc_event(self, key: str, amount: int = 1) -> None:
        with self.lock:
            self.event_counts[key] += amount

    def add_propagation_lag(self, ms: float) -> None:
        with self.lock:
            self.propagation_lags_ms.append(ms)

    def add_stream_lag(self, ms: float) -> None:
        with self.lock:
            self.stream_lags_ms.append(ms)

    def add_elytra_observation(self, drift_blocks: float, payload_age_ms: float) -> None:
        with self.lock:
            self.elytra_drift_blocks.append(max(0.0, drift_blocks))
            self.elytra_payload_age_ms.append(max(0.0, payload_age_ms))

    def snapshot(self) -> dict[str, Any]:
        with self.lock:
            lat = {
                key: {
                    "count": len(values),
                    "p50_ms": round(percentile(values, 50), 2),
                    "p95_ms": round(percentile(values, 95), 2),
                    "max_ms": round(max(values) if values else 0.0, 2),
                }
                for key, values in self.latencies_ms.items()
            }
            propagation = {
                "count": len(self.propagation_lags_ms),
                "p50_ms": round(percentile(self.propagation_lags_ms, 50), 2),
                "p95_ms": round(percentile(self.propagation_lags_ms, 95), 2),
                "max_ms": round(max(self.propagation_lags_ms) if self.propagation_lags_ms else 0.0, 2),
            }
            stream = {
                "count": len(self.stream_lags_ms),
                "p50_ms": round(percentile(self.stream_lags_ms, 50), 2),
                "p95_ms": round(percentile(self.stream_lags_ms, 95), 2),
                "max_ms": round(max(self.stream_lags_ms) if self.stream_lags_ms else 0.0, 2),
            }
            elytra = {
                "drift_blocks": summary_stats(self.elytra_drift_blocks),
                "payload_age_ms": summary_stats(self.elytra_payload_age_ms),
            }
            return {
                "latencies": lat,
                "statuses": dict(self.status_counts),
                "errors": dict(self.error_counts),
                "events": dict(self.event_counts),
                "propagation": propagation,
                "stream": stream,
                "elytra": elytra,
            }


@dataclass
class SharedState:
    lock: threading.Lock = field(default_factory=threading.Lock)
    sent_seq_ts: dict[tuple[str, int], float] = field(default_factory=dict)
    seen_seq: dict[tuple[str, str], int] = field(default_factory=dict)  # (observer, sender_device) -> seq
    revision_ts: dict[int, float] = field(default_factory=dict)
    elytra_simulators: dict[str, "ElytraSimulator"] = field(default_factory=dict)


@dataclass(frozen=True)
class ElytraSimulator:
    start_mono: float
    center_x: float
    center_z: float
    base_y: float
    radius: float
    phase: float
    angular_speed: float

    def sample(self, now_mono: float) -> tuple[float, float, float, float, float, float]:
        dt = max(0.0, now_mono - self.start_mono)
        theta = self.phase + (self.angular_speed * dt)
        x = self.center_x + (self.radius * math.cos(theta))
        z = self.center_z + (self.radius * math.sin(theta))
        y = self.base_y + (6.0 * math.sin(theta * 0.2))
        speed = abs(self.angular_speed) * self.radius
        vx = -speed * math.sin(theta)
        vz = speed * math.cos(theta)
        vy = 1.2 * self.angular_speed * math.cos(theta * 0.2)
        return x, y, z, vx, vy, vz


def build_elytra_simulator(client_index: int, speed_bps: float) -> ElytraSimulator:
    radius = 96.0 + (client_index * 11.0)
    center_x = random.uniform(-2_000_000.0, 2_000_000.0)
    center_z = random.uniform(-2_000_000.0, 2_000_000.0)
    base_y = random.uniform(96.0, 168.0)
    phase = random.uniform(0.0, math.tau)
    angular_speed = max(1.0, speed_bps) / max(48.0, radius)
    return ElytraSimulator(
        start_mono=time.monotonic(),
        center_x=center_x,
        center_z=center_z,
        base_y=base_y,
        radius=radius,
        phase=phase,
        angular_speed=angular_speed,
    )


def load_env_file(path: Path) -> dict[str, str]:
    if not path.exists():
        return {}
    out: dict[str, str] = {}
    for line in path.read_text(encoding="utf-8", errors="ignore").splitlines():
        row = line.strip()
        if not row or row.startswith("#") or "=" not in row:
            continue
        k, v = row.split("=", 1)
        out[k.strip()] = v.strip()
    return out


def resolve_input_value(
    preferred_arg: str,
    legacy_arg: str,
    preferred_env_name: str,
    legacy_env_name: str,
    env_values: dict[str, str],
) -> tuple[str, str, list[str]]:
    preferred_arg_value = (preferred_arg or "").strip()
    legacy_arg_value = (legacy_arg or "").strip()
    preferred_env_value = (env_values.get(preferred_env_name, "") or os.getenv(preferred_env_name, "") or "").strip()
    legacy_env_value = (env_values.get(legacy_env_name, "") or os.getenv(legacy_env_name, "") or "").strip()

    if preferred_arg_value and legacy_arg_value and preferred_arg_value != legacy_arg_value:
        warnings = [f"Conflict: preferred CLI value wins over deprecated CLI alias for {preferred_env_name}."]
        return preferred_arg_value, "conflicting", warnings
    if preferred_arg_value and legacy_arg_value:
        warnings = [f"Deprecated CLI alias is redundant for {preferred_env_name}; keep only the preferred flag."]
        return preferred_arg_value, "mixed", warnings
    if preferred_arg_value:
        return preferred_arg_value, "preferred-only", []
    if legacy_arg_value:
        return legacy_arg_value, "legacy-only", [f"Deprecated CLI alias is active for {preferred_env_name}; use the preferred flag instead."]

    if preferred_env_value and legacy_env_value and preferred_env_value != legacy_env_value:
        warnings = [f"Conflict: preferred env {preferred_env_name} wins over deprecated {legacy_env_name}."]
        return preferred_env_value, "conflicting", warnings
    if preferred_env_value and legacy_env_value:
        warnings = [f"Deprecated env {legacy_env_name} is redundant because {preferred_env_name} is already set."]
        return preferred_env_value, "mixed", warnings
    if preferred_env_value:
        return preferred_env_value, "preferred-only", []
    if legacy_env_value:
        return legacy_env_value, "legacy-only", [f"Deprecated env {legacy_env_name} is active; use {preferred_env_name} instead."]
    return "", "default", []


def request_json(
    session: requests.Session,
    method: str,
    url: str,
    payload: dict[str, Any] | None,
    token: str,
    signing_key: str,
    timeout: float,
    verify_tls: bool,
) -> tuple[int, dict[str, Any], float]:
    body = b""
    headers = {"Accept": "application/json"}
    if payload is not None:
        body = json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        headers["Content-Type"] = "application/json"
    if token:
        headers["Authorization"] = f"Bearer {token}"
    headers.update(make_signature_headers(method, url, body, signing_key))

    start = time.monotonic()
    resp = session.request(method=method, url=url, data=body or None, headers=headers, timeout=timeout, verify=verify_tls)
    elapsed_ms = (time.monotonic() - start) * 1000.0
    try:
        data = resp.json()
        if not isinstance(data, dict):
            data = {"raw": data}
    except Exception:
        data = {"raw": resp.text[:200]}
    return resp.status_code, data, elapsed_ms


def make_presence_profile(
    sender: str,
    sender_uuid: str,
    sender_device: str,
    server_key: str,
    seq: int,
    mode: str,
    simulator: ElytraSimulator | None,
    sample_mono: float,
) -> dict[str, Any]:
    now_ms = int(time.time() * 1000)
    if mode == "elytra48" and simulator is not None:
        x, y, z, vx, vy, vz = simulator.sample(sample_mono)
    else:
        x = random.uniform(-3_000_000.0, 3_000_000.0)
        y = random.uniform(60.0, 120.0)
        z = random.uniform(-3_000_000.0, 3_000_000.0)
        vx = random.uniform(-55.0, 55.0)
        vy = random.uniform(-8.0, 8.0)
        vz = random.uniform(-55.0, 55.0)
    payload = {
        "schema": PRESENCE_SCHEMA,
        "sender": sender,
        "uuid": sender_uuid,
        "senderDevice": sender_device,
        "dim": "minecraft:overworld",
        "x": x,
        "y": y,
        "z": z,
        "vx": vx,
        "vy": vy,
        "vz": vz,
        "seq": seq,
        "updatedAt": now_ms,
        "motionMode": mode,
    }
    return {
        "enabled": True,
        "username": f"__presence__:{sender}",
        "server": server_key,
        "mode": "LOGIN",
        "password": json.dumps(payload, ensure_ascii=False, separators=(",", ":")),
        "delay": 0,
    }


def client_worker(
    client_name: str,
    sender_uuid: str,
    client_index: int,
    base_url: str,
    module: str,
    server_key: str,
    token: str,
    signing_key: str,
    encryption_key: str,
    mode: str,
    elytra_speed_bps: float,
    duration_sec: float,
    push_interval_ms: int,
    pull_interval_ms: int,
    metrics: Metrics,
    shared: SharedState,
    stop: threading.Event,
) -> None:
    session = requests.Session()
    known_revision = -1
    seq = 0
    end_time = time.monotonic() + duration_sec
    next_push = time.monotonic()
    next_pull = time.monotonic()
    simulator = build_elytra_simulator(client_index, elytra_speed_bps) if mode == "elytra48" else None

    if simulator is not None:
        with shared.lock:
            shared.elytra_simulators[client_name] = simulator

    while not stop.is_set() and time.monotonic() < end_time:
        now = time.monotonic()

        if now >= next_push:
            seq += 1
            sample_mono = time.monotonic()
            profile = make_presence_profile(
                client_name,
                sender_uuid,
                client_name,
                server_key,
                seq,
                mode,
                simulator,
                sample_mono,
            )
            wire_profiles = encrypt_profiles_v2([profile], encryption_key, module)
            push_payload = {
                "deviceId": client_name,
                "module": module,
                "baseRevision": known_revision,
                "profiles": wire_profiles,
            }

            try:
                status, body, elapsed = request_json(
                    session,
                    "POST",
                    f"{base_url}/push",
                    push_payload,
                    token,
                    signing_key,
                    timeout=5.0,
                    verify_tls=True,
                )
                metrics.add_latency("push", elapsed)
                metrics.inc_status("push", status)
                if status != 200:
                    metrics.inc_error("push-http")
                elif body.get("ok") and body.get("applied"):
                    rev = int(body.get("revision", -1))
                    if rev >= 0:
                        known_revision = max(known_revision, rev)
                        with shared.lock:
                            shared.revision_ts[rev] = time.monotonic()
                    with shared.lock:
                        shared.sent_seq_ts[(client_name, seq)] = sample_mono
                    metrics.inc_event("push_applied")
                elif body.get("ok") and body.get("conflict"):
                    metrics.inc_event("push_conflict")
                    known_revision = max(known_revision, int(body.get("revision", -1)))
                    retry_payload = dict(push_payload)
                    retry_payload["baseRevision"] = known_revision
                    status2, body2, elapsed2 = request_json(
                        session,
                        "POST",
                        f"{base_url}/push",
                        retry_payload,
                        token,
                        signing_key,
                        timeout=5.0,
                        verify_tls=True,
                    )
                    metrics.add_latency("push_retry", elapsed2)
                    metrics.inc_status("push_retry", status2)
                    if status2 == 200 and body2.get("ok") and body2.get("applied"):
                        rev2 = int(body2.get("revision", -1))
                        if rev2 >= 0:
                            known_revision = max(known_revision, rev2)
                            with shared.lock:
                                shared.revision_ts[rev2] = time.monotonic()
                        with shared.lock:
                            shared.sent_seq_ts[(client_name, seq)] = sample_mono
                        metrics.inc_event("push_retry_applied")
                    else:
                        metrics.inc_error("push-retry-failed")
                else:
                    metrics.inc_error("push-rejected")
            except Exception:
                metrics.inc_error("push-exception")

            next_push += max(1, push_interval_ms) / 1000.0

        if now >= next_pull:
            pull_payload = {"deviceId": client_name, "module": module, "knownRevision": known_revision, "waitMs": 0}
            try:
                status, body, elapsed = request_json(
                    session,
                    "POST",
                    f"{base_url}/pull",
                    pull_payload,
                    token,
                    signing_key,
                    timeout=5.0,
                    verify_tls=True,
                )
                metrics.add_latency("pull", elapsed)
                metrics.inc_status("pull", status)
                if status != 200 or not body.get("ok"):
                    metrics.inc_error("pull-http")
                else:
                    known_revision = max(known_revision, int(body.get("revision", -1)))
                    rows = body.get("profiles", [])
                    if isinstance(rows, list):
                        try:
                            plain_rows = decrypt_profiles(rows, encryption_key, module)
                            metrics.inc_event("pull_decrypt_ok")
                        except Exception:
                            plain_rows = []
                            metrics.inc_error("pull-decrypt-failed")

                        now_ts = time.monotonic()
                        for row in plain_rows:
                            if not isinstance(row, dict):
                                continue
                            payload_raw = row.get("password")
                            if not isinstance(payload_raw, str):
                                continue
                            try:
                                payload = json.loads(payload_raw)
                            except Exception:
                                continue
                            if not isinstance(payload, dict) or payload.get("schema") != PRESENCE_SCHEMA:
                                continue
                            sender_device = str(payload.get("senderDevice", "")).strip()
                            sender_seq = int(payload.get("seq", 0))
                            if not sender_device or sender_device == client_name:
                                continue

                            key = (client_name, sender_device)
                            with shared.lock:
                                previous = shared.seen_seq.get(key, 0)
                                shared.seen_seq[key] = max(previous, sender_seq)
                                sent_at = shared.sent_seq_ts.get((sender_device, sender_seq))
                                sender_simulator = shared.elytra_simulators.get(sender_device)
                            if sender_seq > previous and sent_at is not None:
                                metrics.add_propagation_lag((now_ts - sent_at) * 1000.0)

                            if mode == "elytra48" and sender_simulator is not None:
                                try:
                                    observed_x = float(payload.get("x", 0.0))
                                    observed_y = float(payload.get("y", 0.0))
                                    observed_z = float(payload.get("z", 0.0))
                                    true_x, true_y, true_z, _, _, _ = sender_simulator.sample(now_ts)
                                    drift = math.sqrt(
                                        ((observed_x - true_x) ** 2)
                                        + ((observed_y - true_y) ** 2)
                                        + ((observed_z - true_z) ** 2)
                                    )
                                    updated_at = int(payload.get("updatedAt", 0))
                                    payload_age_ms = max(0.0, (time.time() * 1000.0) - float(updated_at)) if updated_at > 0 else 0.0
                                    metrics.add_elytra_observation(drift, payload_age_ms)
                                except Exception:
                                    metrics.inc_error("elytra-metric-parse")
            except Exception:
                metrics.inc_error("pull-exception")

            next_pull += max(1, pull_interval_ms) / 1000.0

        time.sleep(0.001)

    if simulator is not None:
        with shared.lock:
            shared.elytra_simulators.pop(client_name, None)


def stream_worker(
    base_url: str,
    module: str,
    token: str,
    signing_key: str,
    wait_ms: int,
    duration_sec: float,
    shared: SharedState,
    metrics: Metrics,
    stop: threading.Event,
) -> None:
    end_time = time.monotonic() + duration_sec
    known_revision = -1
    session = requests.Session()

    while not stop.is_set() and time.monotonic() < end_time:
        stream_url = f"{base_url}/v1/sync/stream?deviceId=stress-stream&module={module}&knownRevision={known_revision}&waitMs={wait_ms}"
        headers = {"Accept": "text/event-stream"}
        if token:
            headers["Authorization"] = f"Bearer {token}"
        headers.update(make_signature_headers("GET", stream_url, b"", signing_key))

        try:
            start = time.monotonic()
            with session.get(stream_url, headers=headers, stream=True, timeout=(5, 40), verify=True) as resp:
                metrics.add_latency("stream_connect", (time.monotonic() - start) * 1000.0)
                metrics.inc_status("stream", resp.status_code)
                if resp.status_code != 200:
                    metrics.inc_error("stream-http")
                    time.sleep(0.25)
                    continue

                data_lines: list[str] = []
                for line in resp.iter_lines(decode_unicode=True):
                    if stop.is_set() or time.monotonic() >= end_time:
                        return
                    if line is None:
                        continue
                    if line.startswith("data:"):
                        data_lines.append(line[5:].lstrip())
                        continue
                    if line != "":
                        continue
                    if not data_lines:
                        continue

                    raw = "\n".join(data_lines)
                    data_lines.clear()
                    try:
                        obj = json.loads(raw)
                    except Exception:
                        metrics.inc_error("stream-json")
                        continue
                    rev = int(obj.get("revision", -1))
                    if rev <= known_revision:
                        continue
                    known_revision = rev
                    metrics.inc_event("stream_revision")
                    with shared.lock:
                        pushed_at = shared.revision_ts.get(rev)
                    if pushed_at is not None:
                        metrics.add_stream_lag((time.monotonic() - pushed_at) * 1000.0)
        except Exception:
            metrics.inc_error("stream-exception")
            time.sleep(0.2)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Devils SyncHub stress tester")
    parser.add_argument("--base-url", default="http://127.0.0.1:7878", help="SyncHub base URL")
    parser.add_argument("--module", default="xaero-world-map", help="Namespace module")
    parser.add_argument("--server-key", default="example.test:25565", help="Server key used in presence profile")
    parser.add_argument("--mode", choices=["random", "elytra48"], default="random", help="Traffic profile mode")
    parser.add_argument("--elytra-speed-bps", type=float, default=48.0, help="Elytra speed in blocks/sec for elytra48 mode")
    parser.add_argument("--duration-sec", type=float, default=120.0, help="Test duration in seconds")
    parser.add_argument("--clients", type=int, default=2, help="Simulated pushing clients")
    parser.add_argument("--push-interval-ms", type=int, default=50, help="Per-client push interval")
    parser.add_argument("--pull-interval-ms", type=int, default=50, help="Per-client pull interval")
    parser.add_argument("--stream", action="store_true", default=True, help="Enable stream listener")
    parser.add_argument("--no-stream", action="store_true", help="Disable stream listener")
    parser.add_argument("--stream-wait-ms", type=int, default=1000, help="waitMs for stream endpoint")
    parser.add_argument("--auth-token", default="", help="Preferred bearer auth token")
    parser.add_argument("--token", default="", help="Deprecated legacy alias for --auth-token")
    parser.add_argument("--request-signing-key", default="", help="Preferred request signing key used for HMAC headers")
    parser.add_argument("--signing-key", default="", help="Deprecated legacy alias for --request-signing-key")
    parser.add_argument("--e2e-secret", default="", help="Preferred client-only E2E secret for payload envelopes")
    parser.add_argument("--encryption-key", default="", help="Deprecated legacy alias for --e2e-secret")
    parser.add_argument("--env-file", default=str(Path(__file__).resolve().parents[1] / ".env"), help="Optional .env file")
    parser.add_argument("--output-json", default="", help="Optional output file path")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    env_values = load_env_file(Path(args.env_file)) if args.env_file else {}

    token, token_mode, token_warnings = resolve_input_value(
        args.auth_token,
        args.token,
        "SYNC_AUTH_TOKEN",
        "SYNC_TOKEN",
        env_values,
    )
    signing_key, signing_mode, signing_warnings = resolve_input_value(
        args.request_signing_key,
        args.signing_key,
        "SYNC_REQUEST_SIGNING_KEY",
        "SYNC_SIGNING_KEY",
        env_values,
    )
    encryption_key, e2e_mode, e2e_warnings = resolve_input_value(
        args.e2e_secret,
        args.encryption_key,
        "SYNC_E2E_SECRET",
        "SYNC_ENCRYPTION_KEY",
        env_values,
    )
    if not encryption_key:
        encryption_key = b64url_no_pad(os.urandom(48))
        if e2e_mode == "default":
            e2e_mode = "generated"

    if not signing_key:
        print("FATAL: signing key is required (SYNC_REQUEST_SIGNING_KEY/SYNC_SIGNING_KEY or --request-signing-key/--signing-key).")
        return 2

    base_url = args.base_url.rstrip("/")
    metrics = Metrics()
    shared = SharedState()
    stop = threading.Event()

    print("Starting SyncHub stress test")
    print(f"  base-url      : {base_url}")
    print(f"  module        : {args.module}")
    print(f"  mode          : {args.mode}")
    print(f"  duration      : {args.duration_sec:.1f}s")
    print(f"  clients       : {args.clients}")
    print(f"  push interval : {args.push_interval_ms}ms")
    print(f"  pull interval : {args.pull_interval_ms}ms")
    print(f"  auth-mode     : {token_mode}")
    print(f"  signing-mode  : {signing_mode}")
    print(f"  e2e-mode      : {e2e_mode}")
    if args.mode == "elytra48":
        print(f"  elytra speed  : {args.elytra_speed_bps:.1f} blocks/sec")
    print(f"  stream        : {not args.no_stream and args.stream}")
    for warning in [*token_warnings, *signing_warnings, *e2e_warnings]:
        print(f"  deprecation   : {warning}")

    threads: list[threading.Thread] = []
    for i in range(max(1, args.clients)):
        client_name = f"stress-client-{i+1}"
        client_uuid = f"00000000-0000-0000-0000-{(i+1):012d}"
        thread = threading.Thread(
            target=client_worker,
            args=(
                client_name,
                client_uuid,
                i + 1,
                base_url,
                args.module,
                args.server_key,
                token,
                signing_key,
                encryption_key,
                args.mode,
                args.elytra_speed_bps,
                args.duration_sec,
                args.push_interval_ms,
                args.pull_interval_ms,
                metrics,
                shared,
                stop,
            ),
            daemon=True,
        )
        threads.append(thread)

    if not args.no_stream and args.stream:
        stream_thread = threading.Thread(
            target=stream_worker,
            args=(
                base_url,
                args.module,
                token,
                signing_key,
                args.stream_wait_ms,
                args.duration_sec,
                shared,
                metrics,
                stop,
            ),
            daemon=True,
        )
        threads.append(stream_thread)

    start = time.monotonic()
    for thread in threads:
        thread.start()

    while (time.monotonic() - start) < args.duration_sec:
        time.sleep(0.5)

    stop.set()
    for thread in threads:
        thread.join(timeout=5)

    summary = metrics.snapshot()
    summary["duration_sec"] = round(time.monotonic() - start, 2)
    summary["clients"] = max(1, args.clients)
    summary["module"] = args.module
    summary["mode"] = args.mode
    if args.mode == "elytra48":
        summary["elytra_speed_bps"] = args.elytra_speed_bps

    print(json.dumps(summary, ensure_ascii=False, indent=2))
    if args.output_json:
        out_path = Path(args.output_json)
        out_path.parent.mkdir(parents=True, exist_ok=True)
        out_path.write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
        print(f"Saved report: {out_path}")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
