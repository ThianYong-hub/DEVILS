#!/usr/bin/env python3
import base64
import hashlib
import hmac
import json
import os
import re
import ssl
import sys
import threading
import time
from copy import deepcopy
from dataclasses import dataclass
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any
from urllib.parse import parse_qs, urlparse

PROFILE_DEFAULT_DELAY = 40
STATE_VERSION = 3
EVENTS_MAX_DEFAULT = 5000
PULL_WAIT_MAX_MS_DEFAULT = 25_000
MAX_BODY_BYTES_DEFAULT = 1_048_576
SIGN_WINDOW_SEC_DEFAULT = 30
NONCE_TTL_SEC_DEFAULT = 120
NONCE_CACHE_MAX_DEFAULT = 200_000

MODULE_ALIASES = {
    "autologin": "auto-login",
    "auto_login": "auto-login",
    "chesttracker": "chest-tracker",
    "chest_tracker": "chest-tracker",
}

ENCRYPTED_PROFILE_USERNAME = "__devils_e2e__"
ENCRYPTED_PROFILE_PASSWORD_PREFIXES = ("devils-e2e:v1:", "devils-e2e:v2:")
ENCRYPTED_MODULES = {"auto-login", "ping", "chest-tracker", "xaero-world-map"}
PROTECTED_EMPTY_OVERWRITE_MODULES = {"auto-login", "chest-tracker", "xaero-world-map"}
XAERO_ENCRYPTED_SLOTS_MAX = 128
XAERO_ENCRYPTED_SLOTS_TTL_MS = 180_000

IGNORE_TRACEBACK_ERRORS = (BrokenPipeError, ConnectionResetError, ConnectionAbortedError, TimeoutError)
IGNORE_TRACEBACK_ERRNOS = {32, 54, 104}
NONCE_PATTERN = re.compile(r"^[a-fA-F0-9]{16,128}$")
ENVELOPE_NONCE_BYTES = 12
ENVELOPE_SALT_BYTES_MIN = 8
ENVELOPE_MIN_CIPHERTEXT_BYTES = 16


def now_ts() -> int:
    return int(time.time())


def now_ms() -> int:
    return int(time.time() * 1000)


def bool_value(value: Any, fallback: bool) -> bool:
    if isinstance(value, bool):
        return value
    if value is None:
        return fallback
    return str(value).strip().lower() in ("1", "true", "yes", "on")


def int_value(value: Any, fallback: int) -> int:
    try:
        return int(value)
    except Exception:
        return fallback


def string_value(value: Any, fallback: str = "") -> str:
    if value is None:
        return fallback
    try:
        return str(value)
    except Exception:
        return fallback


def normalize_mode(value: Any) -> str:
    raw = string_value(value, "LOGIN").strip().upper()
    if raw == "REG":
        return "REGISTER"
    return "REGISTER" if raw == "REGISTER" else "LOGIN"


def normalize_module_optional(value: Any) -> str:
    module = string_value(value).strip().lower()
    module = MODULE_ALIASES.get(module, module)
    return module


def normalize_module(value: str) -> str:
    module = normalize_module_optional(value)
    if not module:
        module = "default"
    return module


def split_namespace(raw_namespace: str) -> tuple[str, str, str]:
    namespace = string_value(raw_namespace, "default").strip().lower()
    if not namespace:
        namespace = "default"

    if ":" in namespace:
        module_raw, remainder = namespace.split(":", 1)
        module = normalize_module(module_raw)
        tail = remainder.strip() or "default"
        canonical = f"{module}:{tail}"
        local_key = tail if module == "chest-tracker" else canonical
        return canonical, module, local_key

    module = normalize_module(namespace)
    canonical = module
    local_key = "default" if module == "chest-tracker" else canonical
    return canonical, module, local_key


def namespace_from_payload(payload: dict[str, Any]) -> str:
    candidate = string_value(payload.get("namespace")).strip()
    if not candidate:
        candidate = string_value(payload.get("module")).strip()
    if not candidate:
        candidate = "default"
    canonical, _, _ = split_namespace(candidate)
    return canonical


def clamp_stream_wait_ms(wait_ms_raw: int, wait_cap: int) -> int:
    cap = max(0, int_value(wait_cap, PULL_WAIT_MAX_MS_DEFAULT))
    if cap <= 0:
        return 0

    requested = int_value(wait_ms_raw, 0)
    if requested <= 0:
        requested = cap

    # Keep stream loops from becoming a busy-spin, but never exceed configured cap.
    floor = 2_500 if cap >= 2_500 else 1
    return min(cap, max(floor, requested))


def slugify(value: str) -> str:
    lowered = string_value(value).strip().lower()
    slug = re.sub(r"[^a-z0-9._-]+", "_", lowered)
    slug = slug.strip("._-")
    if not slug:
        slug = "default"
    if len(slug) > 120:
        digest = hashlib.sha1(lowered.encode("utf-8")).hexdigest()[:10]
        slug = f"{slug[:100]}-{digest}"
    return slug


def normalize_profile(item: dict[str, Any]) -> dict[str, Any]:
    return {
        "enabled": bool_value(item.get("enabled"), True),
        "username": string_value(item.get("username")).strip(),
        "server": string_value(item.get("server")).strip(),
        "mode": normalize_mode(item.get("mode")),
        "password": string_value(item.get("password")),
        "delay": max(0, int_value(item.get("delay"), PROFILE_DEFAULT_DELAY)),
    }


def _ping_created_at(profile: dict[str, Any]) -> int:
    payload = string_value(profile.get("password")).strip()
    if not payload:
        return 0
    try:
        parsed = json.loads(payload)
        if not isinstance(parsed, dict):
            return 0
        return max(0, int_value(parsed.get("createdAt"), 0))
    except Exception:
        return 0


def _xaero_presence_meta(profile: dict[str, Any]) -> tuple[str, int, int]:
    username = string_value(profile.get("username")).strip().lower()
    server = string_value(profile.get("server")).strip().lower()
    identity = username
    seq = 0
    updated_at = 0

    payload = string_value(profile.get("password")).strip()
    if payload:
        try:
            parsed = json.loads(payload)
            if isinstance(parsed, dict):
                seq = max(0, int_value(parsed.get("seq"), 0))
                updated_at = max(0, int_value(parsed.get("updatedAt"), 0))
                uuid = string_value(parsed.get("uuid")).strip().lower()
                sender = string_value(parsed.get("sender")).strip().lower()
                if uuid:
                    identity = uuid
                elif sender:
                    identity = sender
        except Exception:
            pass

    key = identity + "|" + server
    return key, seq, updated_at


def is_encrypted_profile_row(profile: Any) -> bool:
    if not isinstance(profile, dict):
        return False
    username = string_value(profile.get("username")).strip()
    password = string_value(profile.get("password")).strip()
    return username == ENCRYPTED_PROFILE_USERNAME and any(password.startswith(prefix) for prefix in ENCRYPTED_PROFILE_PASSWORD_PREFIXES)


def b64url_decode(raw: str) -> bytes:
    value = string_value(raw).strip()
    if not value:
        raise ValueError("empty-b64")
    padding = "=" * ((4 - (len(value) % 4)) % 4)
    return base64.urlsafe_b64decode(value + padding)


def parse_encrypted_envelope(profile: Any) -> dict[str, Any] | None:
    if not is_encrypted_profile_row(profile):
        return None
    password = string_value(profile.get("password")).strip()
    prefix = next((value for value in ENCRYPTED_PROFILE_PASSWORD_PREFIXES if password.startswith(value)), "")
    if not prefix:
        return None
    encoded = password[len(prefix) :]
    try:
        packed = b64url_decode(encoded)
        parsed = json.loads(packed.decode("utf-8"))
    except Exception:
        return None
    if not isinstance(parsed, dict):
        return None

    version = int_value(parsed.get("v"), 0)
    if version not in (1, 2):
        return None

    nonce_raw = string_value(parsed.get("n")).strip()
    ciphertext_raw = string_value(parsed.get("ct")).strip()
    if not nonce_raw or not ciphertext_raw:
        return None

    try:
        nonce = b64url_decode(nonce_raw)
        ciphertext = b64url_decode(ciphertext_raw)
    except Exception:
        return None
    if len(nonce) != ENVELOPE_NONCE_BYTES:
        return None
    if len(ciphertext) < ENVELOPE_MIN_CIPHERTEXT_BYTES:
        return None

    if version == 2:
        salt_raw = string_value(parsed.get("s")).strip()
        if not salt_raw:
            return None
        try:
            salt = b64url_decode(salt_raw)
        except Exception:
            return None
        if len(salt) < ENVELOPE_SALT_BYTES_MIN:
            return None
    return parsed


def envelope_module_name(envelope: dict[str, Any] | None) -> str:
    if not isinstance(envelope, dict):
        return ""
    return normalize_module_optional(envelope.get("m"))


def is_valid_encrypted_profile_row(profile: Any, expected_module: str = "") -> bool:
    envelope = parse_encrypted_envelope(profile)
    if envelope is None:
        return False
    expected = normalize_module_optional(expected_module)
    if not expected:
        return True
    envelope_module = envelope_module_name(envelope)
    # Legacy envelopes may not include "m".
    if envelope_module and envelope_module != expected:
        return False
    return True


def extract_envelope_kid(profile: dict[str, Any]) -> str:
    envelope = parse_encrypted_envelope(profile)
    if envelope is None:
        return ""
    return string_value(envelope.get("kid")).strip()


def is_xaero_encrypted_batch(profiles: list[dict[str, Any]]) -> bool:
    return bool(profiles) and all(is_valid_encrypted_profile_row(profile, "xaero-world-map") for profile in profiles)


def load_xaero_encrypted_slots(
    ns: dict[str, Any], current_profiles: list[dict[str, Any]], fallback_writer: str
) -> dict[str, dict[str, Any]]:
    out: dict[str, dict[str, Any]] = {}
    raw = ns.get("xaeroEncryptedSlots")
    if isinstance(raw, dict):
        for key, value in raw.items():
            device_id = string_value(key).strip()
            if not device_id or not isinstance(value, dict):
                continue
            profile_raw = value.get("profile")
            if isinstance(profile_raw, dict):
                profile = normalize_profile(profile_raw)
            else:
                profile = normalize_profile(value)
            if not is_valid_encrypted_profile_row(profile, "xaero-world-map"):
                continue
            kid = string_value(value.get("kid")).strip() or extract_envelope_kid(profile)
            out[device_id] = {
                "profile": profile,
                "updatedAtMs": max(0, int_value(value.get("updatedAtMs"), 0)),
                "lastRequestId": string_value(value.get("lastRequestId")).strip(),
                "kid": kid,
            }
    if out:
        return out

    if not is_xaero_encrypted_batch(current_profiles):
        return out

    if len(current_profiles) == 1 and fallback_writer:
        out[fallback_writer] = {
            "profile": deepcopy(current_profiles[0]),
            "updatedAtMs": now_ms(),
            "lastRequestId": "",
            "kid": extract_envelope_kid(current_profiles[0]),
        }
        return out

    for index, profile in enumerate(current_profiles):
        out[f"_legacy_{index + 1}"] = {
            "profile": deepcopy(profile),
            "updatedAtMs": now_ms(),
            "lastRequestId": "",
            "kid": extract_envelope_kid(profile),
        }
    return out


def prune_xaero_encrypted_slots(slots: dict[str, dict[str, Any]], reference_ms: int) -> dict[str, dict[str, Any]]:
    cutoff_ms = max(0, int_value(reference_ms, 0) - XAERO_ENCRYPTED_SLOTS_TTL_MS)
    filtered: dict[str, dict[str, Any]] = {}
    for device_id, value in slots.items():
        if not isinstance(value, dict):
            continue
        profile = value.get("profile")
        if not is_valid_encrypted_profile_row(profile, "xaero-world-map"):
            continue
        updated_at = max(0, int_value(value.get("updatedAtMs"), 0))
        if updated_at and updated_at < cutoff_ms:
            continue
        filtered[device_id] = {
            "profile": deepcopy(profile),
            "updatedAtMs": updated_at,
            "lastRequestId": string_value(value.get("lastRequestId")).strip(),
            "kid": string_value(value.get("kid")).strip() or extract_envelope_kid(profile),
        }

    ordered = sorted(
        filtered.items(),
        key=lambda item: (
            -max(0, int_value(item[1].get("updatedAtMs"), 0)),
            string_value(item[0]).strip().lower(),
        ),
    )
    if len(ordered) > XAERO_ENCRYPTED_SLOTS_MAX:
        ordered = ordered[:XAERO_ENCRYPTED_SLOTS_MAX]
    return {device_id: entry for device_id, entry in ordered}


def xaero_profiles_from_slots(slots: dict[str, dict[str, Any]], active_kid: str = "") -> list[dict[str, Any]]:
    if not isinstance(slots, dict) or not slots:
        return []
    ordered = sorted(
        slots.items(),
        key=lambda item: (
            -max(0, int_value(item[1].get("updatedAtMs"), 0)),
            string_value(item[0]).strip().lower(),
        ),
    )
    out: list[dict[str, Any]] = []
    for _, value in ordered:
        if not isinstance(value, dict):
            continue
        slot_kid = string_value(value.get("kid")).strip()
        if active_kid and slot_kid != active_kid:
            continue
        profile = value.get("profile")
        if not is_valid_encrypted_profile_row(profile, "xaero-world-map"):
            continue
        out.append(deepcopy(profile))
    if out or not active_kid:
        return out

    # Fallback for legacy rows without kid metadata.
    for _, value in ordered:
        if not isinstance(value, dict):
            continue
        profile = value.get("profile")
        if not is_valid_encrypted_profile_row(profile, "xaero-world-map"):
            continue
        out.append(deepcopy(profile))
    return out


def normalize_profiles(items: Any, namespace: str = "default") -> list[dict[str, Any]]:
    if not isinstance(items, list):
        return []

    canonical_ns, module, _ = split_namespace(namespace)
    out: list[dict[str, Any]] = []
    for item in items:
        if isinstance(item, dict):
            out.append(normalize_profile(item))

    if module == "xaero-world-map":
        if is_xaero_encrypted_batch(out):
            dedup_encrypted: dict[str, dict[str, Any]] = {}
            for profile in out:
                dedup_encrypted[string_value(profile.get("password"))] = profile
            ordered_passwords = sorted(dedup_encrypted.keys())
            return [dedup_encrypted[key] for key in ordered_passwords]

        dedup_xaero: dict[str, tuple[dict[str, Any], int, int]] = {}
        for profile in out:
            key, seq, updated_at = _xaero_presence_meta(profile)
            prev = dedup_xaero.get(key)
            if prev is None or seq > prev[1] or (seq == prev[1] and updated_at >= prev[2]):
                dedup_xaero[key] = (profile, seq, updated_at)

        merged_xaero = [entry for entry in dedup_xaero.values()]
        merged_xaero.sort(
            key=lambda entry: (
                -entry[1],
                -entry[2],
                string_value(entry[0].get("username")).strip().lower(),
                string_value(entry[0].get("server")).strip().lower(),
            )
        )
        return [entry[0] for entry in merged_xaero]

    if module != "ping":
        return out

    dedup: dict[str, tuple[dict[str, Any], int]] = {}
    for profile in out:
        key = (
            string_value(profile.get("username")).strip().lower()
            + "|"
            + string_value(profile.get("server")).strip().lower()
        )
        created_at = _ping_created_at(profile)
        prev = dedup.get(key)
        if prev is None or created_at >= prev[1]:
            dedup[key] = (profile, created_at)

    merged = [entry[0] for entry in dedup.values()]
    merged.sort(
        key=lambda profile: (
            -_ping_created_at(profile),
            string_value(profile.get("username")).strip().lower(),
            string_value(profile.get("server")).strip().lower(),
        )
    )
    return merged


def is_encrypted_profiles_payload(items: Any, expected_module: str = "") -> bool:
    if not isinstance(items, list) or len(items) != 1:
        return False
    return is_valid_encrypted_profile_row(items[0], expected_module)


def canonical_profiles_checksum(profiles: list[dict[str, Any]]) -> str:
    rows: list[str] = []
    for p in profiles:
        rows.append(
            "|".join(
                [
                    string_value(p.get("username")).strip().lower(),
                    string_value(p.get("server")).strip().lower(),
                    normalize_mode(p.get("mode")),
                    string_value(p.get("password")),
                    str(max(0, int_value(p.get("delay"), PROFILE_DEFAULT_DELAY))),
                    "1" if bool_value(p.get("enabled"), True) else "0",
                ]
            )
        )
    rows.sort()
    return hashlib.sha256("\n".join(rows).encode("utf-8")).hexdigest()


def sha256_hex_bytes(payload: bytes) -> str:
    return hashlib.sha256(payload if payload is not None else b"").hexdigest()


def hmac_sha256_hex(secret: str, payload: str) -> str:
    key = string_value(secret).encode("utf-8")
    data = string_value(payload).encode("utf-8")
    return hmac.new(key, data, hashlib.sha256).hexdigest()


def signature_canonical_payload(method: str, target: str, timestamp: int, nonce: str, body_hash: str) -> str:
    return "\n".join(
        [
            string_value(method).strip().upper(),
            string_value(target).strip(),
            str(int_value(timestamp, 0)),
            string_value(nonce).strip(),
            string_value(body_hash).strip().lower(),
        ]
    )


class NonceReplayGuard:
    def __init__(self, ttl_sec: int, max_entries: int):
        self.ttl_sec = max(10, int_value(ttl_sec, NONCE_TTL_SEC_DEFAULT))
        self.max_entries = max(1_000, int_value(max_entries, NONCE_CACHE_MAX_DEFAULT))
        self.lock = threading.Lock()
        self.nonces: dict[str, int] = {}

    def _cleanup(self, now: int) -> None:
        if not self.nonces:
            return
        expired = [nonce for nonce, expiry in self.nonces.items() if expiry <= now]
        for nonce in expired:
            self.nonces.pop(nonce, None)
        if len(self.nonces) <= self.max_entries:
            return
        # Drop the oldest-expiring entries when storage is full.
        over = len(self.nonces) - self.max_entries
        for nonce, _ in sorted(self.nonces.items(), key=lambda item: item[1])[:over]:
            self.nonces.pop(nonce, None)

    def mark(self, nonce: str, now: int) -> bool:
        nonce_key = string_value(nonce).strip()
        if not nonce_key:
            return False
        with self.lock:
            self._cleanup(now)
            if nonce_key in self.nonces:
                return False
            self.nonces[nonce_key] = now + self.ttl_sec
            return True


def is_empty_overwrite_protected(
    namespace: str,
    current_profiles: list[dict[str, Any]],
    incoming_profiles: list[dict[str, Any]],
    allow_empty_overwrite: bool,
) -> bool:
    if allow_empty_overwrite:
        return False
    _, module, _ = split_namespace(namespace)
    if module not in PROTECTED_EMPTY_OVERWRITE_MODULES:
        return False
    return bool(current_profiles) and not incoming_profiles


def make_namespace_state(namespace: str) -> dict[str, Any]:
    canonical, module, _ = split_namespace(namespace)
    profiles: list[dict[str, Any]] = []
    return {
        "version": 1,
        "module": module,
        "namespace": canonical,
        "revision": 0,
        "profiles": profiles,
        "updatedAt": now_ts(),
        "checksum": canonical_profiles_checksum(profiles),
        "lastWriter": "",
        "lastRequestId": "",
    }


def default_meta_state() -> dict[str, Any]:
    return {
        "version": STATE_VERSION,
        "clients": {},
        "events": [],
        "nextEventId": 1,
        "updatedAt": now_ts(),
    }


@dataclass
class PullResult:
    namespace: str
    revision: int
    profiles: list[dict[str, Any]]
    checksum: str
    changed: bool
    event_id: int
    last_writer: str


@dataclass
class PushResult:
    namespace: str
    revision: int
    profiles: list[dict[str, Any]]
    checksum: str
    applied: bool
    conflict: bool
    error: str
    event_id: int


class SyncStore:
    def __init__(self, state_file: Path, namespaces_dir: Path, events_max: int):
        self.state_file = state_file
        self.namespaces_dir = namespaces_dir
        self.events_max = max(100, events_max)
        self.lock = threading.RLock()
        self.cond = threading.Condition(self.lock)

        self.state = self._load_meta_state()
        self.namespaces: dict[str, dict[str, Any]] = {}

    def _namespace_file(self, namespace: str) -> tuple[str, str, Path]:
        canonical, module, local_key = split_namespace(namespace)
        module_dir = self.namespaces_dir / slugify(module)
        return canonical, module, module_dir / f"{slugify(local_key)}.json"

    def _save_json_atomic(self, path: Path, payload: dict[str, Any]) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        tmp = path.with_suffix(path.suffix + ".tmp")
        bak = path.with_suffix(path.suffix + ".bak")
        tmp.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
        if path.exists():
            try:
                path.replace(bak)
            except Exception:
                pass
        tmp.replace(path)

    def _normalize_ns(self, raw: dict[str, Any], namespace: str) -> dict[str, Any]:
        canonical, module, _ = split_namespace(namespace)
        ns = make_namespace_state(canonical)
        ns["module"] = module
        ns["namespace"] = canonical
        ns["revision"] = max(0, int_value(raw.get("revision"), 0))
        ns["profiles"] = normalize_profiles(raw.get("profiles"), canonical)
        ns["updatedAt"] = int_value(raw.get("updatedAt"), now_ts())
        ns["checksum"] = string_value(raw.get("checksum"), canonical_profiles_checksum(ns["profiles"]))
        ns["lastWriter"] = string_value(raw.get("lastWriter"))
        ns["lastRequestId"] = string_value(raw.get("lastRequestId"))
        return ns

    def _load_meta_state(self) -> dict[str, Any]:
        if not self.state_file.exists():
            state = default_meta_state()
            self._save_json_atomic(self.state_file, state)
            return state

        try:
            raw = json.loads(self.state_file.read_text(encoding="utf-8"))
            if not isinstance(raw, dict):
                raise ValueError("bad-root")
        except Exception:
            state = default_meta_state()
            self._save_json_atomic(self.state_file, state)
            return state

        state = default_meta_state()
        if isinstance(raw.get("clients"), dict):
            state["clients"] = raw["clients"]
        if isinstance(raw.get("events"), list):
            state["events"] = [e for e in raw["events"] if isinstance(e, dict)][-self.events_max :]

        state["nextEventId"] = max(1, int_value(raw.get("nextEventId"), len(state["events"]) + 1))
        state["updatedAt"] = int_value(raw.get("updatedAt"), now_ts())
        self._save_json_atomic(self.state_file, state)

        legacy_namespaces = raw.get("namespaces")
        if isinstance(legacy_namespaces, dict):
            migrated = 0
            for key, value in legacy_namespaces.items():
                if not isinstance(value, dict):
                    continue
                canonical, _, file_path = self._namespace_file(string_value(key, "default"))
                if file_path.exists():
                    continue
                self._save_json_atomic(file_path, self._normalize_ns(value, canonical))
                migrated += 1
            if migrated > 0:
                print(f"[{time.strftime('%H:%M:%S')}] migrated legacy namespaces -> file layout: {migrated}")

        return state

    def _save_meta(self) -> None:
        self.state["updatedAt"] = now_ts()
        self._save_json_atomic(self.state_file, self.state)

    def _load_ns(self, namespace: str) -> dict[str, Any] | None:
        canonical, _, file_path = self._namespace_file(namespace)
        if not file_path.exists():
            return None
        try:
            raw = json.loads(file_path.read_text(encoding="utf-8"))
            if not isinstance(raw, dict):
                raise ValueError("bad-ns")
        except Exception:
            ns = make_namespace_state(canonical)
            self._save_json_atomic(file_path, ns)
            return ns

        ns_name = string_value(raw.get("namespace"), canonical)
        ns = self._normalize_ns(raw, ns_name)
        self._save_json_atomic(file_path, ns)
        return ns

    def _save_ns(self, namespace: str, ns: dict[str, Any]) -> None:
        canonical, _, file_path = self._namespace_file(namespace)
        self._save_json_atomic(file_path, self._normalize_ns(ns, canonical))

    def _ensure_ns(self, namespace: str) -> tuple[str, dict[str, Any]]:
        canonical, _, _ = self._namespace_file(namespace)
        current = self.namespaces.get(canonical)
        if isinstance(current, dict):
            return canonical, current
        loaded = self._load_ns(canonical)
        if loaded is None:
            loaded = make_namespace_state(canonical)
        self.namespaces[canonical] = loaded
        return canonical, loaded

    def _append_event(self, namespace: str, event_type: str, device_id: str, payload: dict[str, Any]) -> int:
        canonical, _, _ = split_namespace(namespace)
        event_id = int(self.state.get("nextEventId", 1))
        self.state["nextEventId"] = event_id + 1
        event = {
            "id": event_id,
            "type": event_type,
            "namespace": canonical,
            "deviceId": device_id,
            "time": now_ts(),
            "payload": payload,
        }
        events: list[dict[str, Any]] = self.state["events"]
        events.append(event)
        if len(events) > self.events_max:
            del events[: len(events) - self.events_max]
        return event_id

    def _last_event_id(self) -> int:
        events: list[dict[str, Any]] = self.state["events"]
        if not events:
            return 0
        return int_value(events[-1].get("id"), 0)


    def _namespace_names(self) -> list[str]:
        names = set(self.namespaces.keys())
        if self.namespaces_dir.exists():
            for file_path in self.namespaces_dir.glob('*/*.json'):
                try:
                    raw = json.loads(file_path.read_text(encoding='utf-8'))
                    if not isinstance(raw, dict):
                        continue
                    canonical, _, _ = split_namespace(string_value(raw.get('namespace'), ''))
                    if canonical:
                        names.add(canonical)
                except Exception:
                    continue
        if 'default' not in names:
            names.add('default')
        return sorted(names)

    def _clear_namespace_files(self) -> None:
        if not self.namespaces_dir.exists():
            return

        for file_path in self.namespaces_dir.glob('*/*.json'):
            try:
                file_path.unlink(missing_ok=True)
            except Exception:
                pass
            for suffix in ('.bak', '.tmp'):
                try:
                    file_path.with_suffix(file_path.suffix + suffix).unlink(missing_ok=True)
                except Exception:
                    pass

        for module_dir in self.namespaces_dir.glob('*'):
            if module_dir.is_dir():
                try:
                    module_dir.rmdir()
                except Exception:
                    pass

    def register_client(self, device_id: str, namespace: str, module: str, ip: str, user_agent: str) -> dict[str, Any]:
        with self.lock:
            canonical, _, _ = split_namespace(namespace)
            clients: dict[str, Any] = self.state['clients']
            current = clients.get(device_id)
            if not isinstance(current, dict):
                current = {'createdAt': now_ts(), 'acks': {}}
                clients[device_id] = current

            current['lastSeen'] = now_ts()
            current['lastSeenMs'] = now_ms()
            current['lastNamespace'] = canonical
            current['lastModule'] = normalize_module(module)
            current['lastIp'] = ip
            current['lastUserAgent'] = user_agent
            self._save_meta()
            return deepcopy(current)

    def start_client(self, device_id: str, namespace: str, module: str, ip: str, user_agent: str) -> PullResult:
        self.register_client(device_id, namespace, module, ip, user_agent)
        return self.pull(namespace, -1, 0, device_id)

    def pull(self, namespace: str, known_revision: int, wait_ms: int, device_id: str = "") -> PullResult:
        with self.cond:
            canonical, ns = self._ensure_ns(namespace)
            _, module, _ = split_namespace(canonical)
            if wait_ms > 0 and known_revision == int_value(ns.get('revision'), 0):
                self.cond.wait(timeout=max(1, wait_ms) / 1000.0)
                canonical, ns = self._ensure_ns(canonical)
                _, module, _ = split_namespace(canonical)

            revision = int_value(ns.get('revision'), 0)
            profiles = normalize_profiles(ns.get('profiles'), canonical)
            if module == "xaero-world-map":
                slots = prune_xaero_encrypted_slots(
                    load_xaero_encrypted_slots(ns, profiles, string_value(ns.get("lastWriter")).strip()),
                    now_ms(),
                )
                if slots:
                    preferred_kid = ""
                    if device_id and isinstance(slots.get(device_id), dict):
                        preferred_kid = string_value(slots[device_id].get("kid")).strip()
                    active_kid = preferred_kid or string_value(ns.get("xaeroActiveKid")).strip()
                    profiles = xaero_profiles_from_slots(slots, active_kid)
            checksum = canonical_profiles_checksum(profiles)
            changed = revision != known_revision
            last_writer = string_value(ns.get('lastWriter')).strip()
            return PullResult(canonical, revision, profiles, checksum, changed, self._last_event_id(), last_writer)

    def push(
        self,
        namespace: str,
        base_revision: int,
        profiles: list[dict[str, Any]],
        device_id: str,
        request_id: str,
        allow_empty_overwrite: bool = False,
    ) -> PushResult:
        with self.cond:
            canonical, ns = self._ensure_ns(namespace)
            _, module, _ = split_namespace(canonical)
            current_revision = int_value(ns.get('revision'), 0)
            current_profiles = normalize_profiles(ns.get('profiles'), canonical)
            normalized = normalize_profiles(profiles, canonical)

            if request_id and request_id == string_value(ns.get('lastRequestId')) and device_id == string_value(ns.get('lastWriter')):
                checksum = canonical_profiles_checksum(current_profiles)
                return PushResult(canonical, current_revision, current_profiles, checksum, True, False, '', self._last_event_id())

            if module == "xaero-world-map":
                if is_xaero_encrypted_batch(normalized):
                    slots = prune_xaero_encrypted_slots(
                        load_xaero_encrypted_slots(ns, current_profiles, string_value(ns.get("lastWriter")).strip()),
                        now_ms(),
                    )
                    previous_slot = slots.get(device_id) if isinstance(slots, dict) else None
                    previous_profile = None
                    if isinstance(previous_slot, dict) and isinstance(previous_slot.get("profile"), dict):
                        previous_profile = normalize_profile(previous_slot.get("profile"))
                    previous_request_id = (
                        string_value(previous_slot.get("lastRequestId")).strip() if isinstance(previous_slot, dict) else ""
                    )
                    active_kid = string_value(ns.get("xaeroActiveKid")).strip()

                    if request_id and previous_request_id and request_id == previous_request_id:
                        composed = xaero_profiles_from_slots(slots, active_kid)
                        checksum = canonical_profiles_checksum(composed)
                        return PushResult(canonical, current_revision, composed, checksum, True, False, '', self._last_event_id())

                    incoming_profile = normalized[0]
                    incoming_kid = extract_envelope_kid(incoming_profile)
                    if previous_profile == incoming_profile:
                        if isinstance(previous_slot, dict):
                            previous_slot["updatedAtMs"] = now_ms()
                            previous_slot["lastRequestId"] = request_id
                            if incoming_kid:
                                previous_slot["kid"] = incoming_kid
                            slots[device_id] = previous_slot
                        if incoming_kid:
                            active_kid = incoming_kid
                        composed = xaero_profiles_from_slots(slots, active_kid)
                        checksum = canonical_profiles_checksum(composed)
                        return PushResult(canonical, current_revision, composed, checksum, True, False, '', self._last_event_id())

                    slots[device_id] = {
                        "profile": incoming_profile,
                        "updatedAtMs": now_ms(),
                        "lastRequestId": request_id,
                        "kid": incoming_kid,
                    }
                    slots = prune_xaero_encrypted_slots(slots, now_ms())
                    if incoming_kid:
                        active_kid = incoming_kid
                    merged_profiles = xaero_profiles_from_slots(slots, active_kid)
                    checksum = canonical_profiles_checksum(merged_profiles)
                    ns['xaeroEncryptedSlots'] = slots
                    ns['xaeroActiveKid'] = active_kid
                    ns['profiles'] = merged_profiles
                    ns['revision'] = current_revision + 1
                    ns['updatedAt'] = now_ts()
                    ns['checksum'] = checksum
                    ns['lastWriter'] = device_id
                    ns['lastRequestId'] = request_id
                    self._append_event(
                        canonical,
                        'push',
                        device_id,
                        {
                            'revision': ns['revision'],
                            'profilesCount': len(merged_profiles),
                            'checksum': checksum,
                            'staleBaseAccepted': base_revision != current_revision,
                            'xaeroEncryptedSlots': len(slots),
                            'xaeroActiveKid': active_kid,
                        },
                    )

                    self.namespaces[canonical] = ns
                    self._save_ns(canonical, ns)
                    self._save_meta()
                    self.cond.notify_all()
                    return PushResult(canonical, int_value(ns['revision'], 0), merged_profiles, checksum, True, False, '', self._last_event_id())

                merged_profiles = normalize_profiles(current_profiles + normalized, canonical)
                if is_empty_overwrite_protected(canonical, current_profiles, merged_profiles, allow_empty_overwrite):
                    checksum = canonical_profiles_checksum(current_profiles)
                    return PushResult(canonical, current_revision, current_profiles, checksum, False, True, 'empty-overwrite-protected', self._last_event_id())
                if merged_profiles == current_profiles:
                    checksum = canonical_profiles_checksum(current_profiles)
                    return PushResult(canonical, current_revision, current_profiles, checksum, True, False, '', self._last_event_id())

                checksum = canonical_profiles_checksum(merged_profiles)
                ns.pop('xaeroEncryptedSlots', None)
                ns.pop('xaeroActiveKid', None)
                ns['profiles'] = merged_profiles
                ns['revision'] = current_revision + 1
                ns['updatedAt'] = now_ts()
                ns['checksum'] = checksum
                ns['lastWriter'] = device_id
                ns['lastRequestId'] = request_id
                self._append_event(
                    canonical,
                    'push',
                    device_id,
                    {
                        'revision': ns['revision'],
                        'profilesCount': len(merged_profiles),
                        'checksum': checksum,
                        'staleBaseAccepted': base_revision != current_revision,
                    },
                )

                self.namespaces[canonical] = ns
                self._save_ns(canonical, ns)
                self._save_meta()
                self.cond.notify_all()
                return PushResult(canonical, int_value(ns['revision'], 0), merged_profiles, checksum, True, False, '', self._last_event_id())

            if base_revision != current_revision:
                checksum = canonical_profiles_checksum(current_profiles)
                return PushResult(canonical, current_revision, current_profiles, checksum, False, True, 'revision-mismatch', self._last_event_id())

            if is_empty_overwrite_protected(canonical, current_profiles, normalized, allow_empty_overwrite):
                checksum = canonical_profiles_checksum(current_profiles)
                return PushResult(canonical, current_revision, current_profiles, checksum, False, True, 'empty-overwrite-protected', self._last_event_id())

            checksum = canonical_profiles_checksum(normalized)
            ns['profiles'] = normalized
            ns['revision'] = current_revision + 1
            ns['updatedAt'] = now_ts()
            ns['checksum'] = checksum
            ns['lastWriter'] = device_id
            ns['lastRequestId'] = request_id
            self._append_event(
                canonical,
                'push',
                device_id,
                {
                    'revision': ns['revision'],
                    'profilesCount': len(normalized),
                    'checksum': checksum,
                },
            )

            self.namespaces[canonical] = ns
            self._save_ns(canonical, ns)
            self._save_meta()
            self.cond.notify_all()
            return PushResult(canonical, int_value(ns['revision'], 0), normalized, checksum, True, False, '', self._last_event_id())

    def ack(self, namespace: str, device_id: str, revision: int) -> None:
        with self.lock:
            canonical, _, _ = split_namespace(namespace)
            clients: dict[str, Any] = self.state['clients']
            current = clients.get(device_id)
            if not isinstance(current, dict):
                current = {'createdAt': now_ts(), 'acks': {}}
                clients[device_id] = current

            acks = current.get('acks')
            if not isinstance(acks, dict):
                acks = {}
                current['acks'] = acks

            previous = int_value(acks.get(canonical), -1)
            if revision > previous:
                acks[canonical] = revision

            current['lastSeen'] = now_ts()
            current['lastSeenMs'] = now_ms()
            self._append_event(canonical, 'ack', device_id, {'revision': revision})
            self._save_meta()

    def events_since(self, namespace: str, since_event_id: int, limit: int) -> list[dict[str, Any]]:
        with self.lock:
            canonical, _, _ = split_namespace(namespace)
            out: list[dict[str, Any]] = []
            for event in self.state['events']:
                event_ns = string_value(event.get('namespace'))
                event_id = int_value(event.get('id'), 0)
                if event_ns != canonical:
                    continue
                if event_id <= since_event_id:
                    continue
                out.append(deepcopy(event))
                if len(out) >= limit:
                    break
            return out

    def admin_snapshot(self, namespace: str | None) -> dict[str, Any]:
        with self.lock:
            if namespace:
                canonical, ns = self._ensure_ns(namespace)
                return {
                    'version': self.state.get('version'),
                    'updatedAt': self.state.get('updatedAt'),
                    'namespace': canonical,
                    'state': deepcopy(ns),
                    'clientsCount': len(self.state.get('clients', {})),
                    'eventsCount': len(self.state.get('events', [])),
                    'lastEventId': self._last_event_id(),
                }

            namespaces: dict[str, Any] = {}
            for ns_name in self._namespace_names():
                canonical, ns = self._ensure_ns(ns_name)
                namespaces[canonical] = deepcopy(ns)

            return {
                'version': self.state.get('version'),
                'updatedAt': self.state.get('updatedAt'),
                'namespaces': namespaces,
                'clients': deepcopy(self.state.get('clients', {})),
                'events': deepcopy(self.state.get('events', [])),
                'nextEventId': self.state.get('nextEventId', 1),
            }

    def admin_reset(self, namespace: str | None, preserve_clients: bool) -> dict[str, Any]:
        with self.cond:
            if namespace:
                canonical, module, _ = split_namespace(namespace)
                reset_state = make_namespace_state(canonical)
                self.namespaces[canonical] = reset_state
                self._save_ns(canonical, reset_state)
                self._append_event(canonical, 'admin-reset', 'admin', {'namespaceOnly': True, 'module': module})
            else:
                clients = deepcopy(self.state.get('clients', {})) if preserve_clients else {}
                self.state = default_meta_state()
                if isinstance(clients, dict):
                    self.state['clients'] = clients
                self.namespaces.clear()
                self._clear_namespace_files()
                self._append_event('default', 'admin-reset', 'admin', {'namespaceOnly': False})

            self._save_meta()
            self.cond.notify_all()
            return self.admin_snapshot(namespace)

    def admin_import(self, namespace: str, profiles: list[dict[str, Any]], force_revision: int | None) -> dict[str, Any]:
        with self.cond:
            canonical, ns = self._ensure_ns(namespace)
            ns['profiles'] = normalize_profiles(profiles, canonical)
            ns['checksum'] = canonical_profiles_checksum(ns['profiles'])
            if force_revision is not None and force_revision >= 0:
                ns['revision'] = force_revision
            else:
                ns['revision'] = int_value(ns.get('revision'), 0) + 1

            ns['updatedAt'] = now_ts()
            ns['lastWriter'] = 'admin'
            ns['lastRequestId'] = ''
            self._append_event(
                canonical,
                'admin-import',
                'admin',
                {'revision': ns['revision'], 'profilesCount': len(ns['profiles']), 'checksum': ns['checksum']},
            )

            self.namespaces[canonical] = ns
            self._save_ns(canonical, ns)
            self._save_meta()
            self.cond.notify_all()
            return deepcopy(ns)



class QuietThreadingHTTPServer(ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True

    def handle_error(self, request: Any, client_address: tuple[str, int]) -> None:
        _, error, _ = sys.exc_info()
        if isinstance(error, IGNORE_TRACEBACK_ERRORS):
            return
        if isinstance(error, OSError) and getattr(error, 'errno', None) in IGNORE_TRACEBACK_ERRNOS:
            return
        super().handle_error(request, client_address)


class SyncHandler(BaseHTTPRequestHandler):
    server_version = 'DevilsSyncHub/3.0'
    protocol_version = 'HTTP/1.1'

    def handle(self) -> None:
        try:
            super().handle()
        except IGNORE_TRACEBACK_ERRORS:
            return
        except OSError as error:
            if getattr(error, 'errno', None) in IGNORE_TRACEBACK_ERRNOS:
                return
            raise

    def _state(self) -> SyncStore:
        return self.server.sync_store  # type: ignore[attr-defined]

    def _server_time(self) -> int:
        return now_ts()

    def _headers(self, status: int, length: int, content_type: str = 'application/json; charset=utf-8') -> None:
        self.send_response(status)
        self.send_header('Content-Type', content_type)
        self.send_header('Content-Length', str(length))
        if self.server.allow_cors:  # type: ignore[attr-defined]
            self.send_header('Access-Control-Allow-Origin', '*')
            self.send_header(
                'Access-Control-Allow-Headers',
                'Authorization, Content-Type, X-Devils-Timestamp, X-Devils-Nonce, X-Devils-Signature, X-Devils-Signature-Version',
            )
            self.send_header('Access-Control-Allow-Methods', 'GET, POST, OPTIONS')
        self.end_headers()

    def _json(self, status: int, payload: dict[str, Any]) -> None:
        body = json.dumps(payload, ensure_ascii=False).encode('utf-8')
        self._headers(status, len(body))
        self.wfile.write(body)

    def _sse_headers(self) -> None:
        self.send_response(200)
        self.send_header('Content-Type', 'text/event-stream; charset=utf-8')
        self.send_header('Cache-Control', 'no-cache')
        self.send_header('Connection', 'keep-alive')
        self.send_header('X-Accel-Buffering', 'no')
        if self.server.allow_cors:  # type: ignore[attr-defined]
            self.send_header('Access-Control-Allow-Origin', '*')
            self.send_header(
                'Access-Control-Allow-Headers',
                'Authorization, Content-Type, X-Devils-Timestamp, X-Devils-Nonce, X-Devils-Signature, X-Devils-Signature-Version',
            )
            self.send_header('Access-Control-Allow-Methods', 'GET, POST, OPTIONS')
        self.end_headers()

    def _sse_event(self, event_name: str, payload: dict[str, Any]) -> None:
        data = json.dumps(payload, ensure_ascii=False)
        lines: list[str] = []
        if event_name:
            lines.append(f'event: {event_name}')
        for row in data.splitlines() or ['{}']:
            lines.append(f'data: {row}')
        lines.append('')
        self.wfile.write(('\n'.join(lines) + '\n').encode('utf-8'))
        self.wfile.flush()

    def _sse_ping(self) -> None:
        self.wfile.write(f': ping {now_ts()}\n\n'.encode('utf-8'))
        self.wfile.flush()

    def _raw_target(self) -> str:
        target = string_value(self.path).strip()
        return target if target else '/'

    def _content_length(self) -> int:
        raw = string_value(self.headers.get('Content-Length')).strip()
        if not raw:
            return 0
        try:
            value = int(raw)
        except Exception:
            return -1
        if value < 0:
            return -1
        return value

    def _read_json(self, length: int) -> tuple[bytes, dict[str, Any] | None]:
        if length <= 0:
            return b"{}", {}

        raw = b""
        try:
            raw = self.rfile.read(length)
            parsed = json.loads(raw.decode('utf-8'))
        except Exception:
            return raw, None
        if not isinstance(parsed, dict):
            return raw, None
        return raw, parsed

    def _is_authorized(self, admin: bool = False) -> bool:
        auth = string_value(self.headers.get('Authorization')).strip()
        token = self.server.sync_token  # type: ignore[attr-defined]
        admin_token = self.server.admin_token  # type: ignore[attr-defined]

        if admin:
            expected = admin_token if admin_token else token
            if not expected:
                return True
            return auth == f'Bearer {expected}'

        if not token:
            return True
        return auth == f'Bearer {token}'

    def _verify_signature(self, body: bytes) -> tuple[bool, str]:
        require_signed = bool(getattr(self.server, 'require_signed', False))
        if not require_signed:
            return True, ''

        signing_key = string_value(getattr(self.server, 'signing_key', '')).strip()
        if not signing_key:
            return False, 'signature-key-missing'

        timestamp_raw = string_value(self.headers.get('X-Devils-Timestamp')).strip()
        nonce = string_value(self.headers.get('X-Devils-Nonce')).strip()
        signature = string_value(self.headers.get('X-Devils-Signature')).strip().lower()
        if not timestamp_raw or not nonce or not signature:
            return False, 'signature-headers-missing'
        if not NONCE_PATTERN.match(nonce):
            return False, 'signature-nonce-invalid'
        if len(signature) != 64 or not re.fullmatch(r"[a-f0-9]{64}", signature):
            return False, 'signature-format-invalid'

        timestamp = int_value(timestamp_raw, 0)
        now = now_ts()
        window_sec = max(5, int_value(getattr(self.server, 'sign_window_sec', SIGN_WINDOW_SEC_DEFAULT), SIGN_WINDOW_SEC_DEFAULT))
        if timestamp <= 0 or abs(now - timestamp) > window_sec:
            return False, 'signature-timestamp-out-of-window'

        target = self._raw_target()
        body_hash = sha256_hex_bytes(body if body is not None else b'')
        canonical = signature_canonical_payload(self.command, target, timestamp, nonce, body_hash)
        expected = hmac_sha256_hex(signing_key, canonical)
        if not hmac.compare_digest(expected, signature):
            return False, 'signature-mismatch'

        replay_guard = getattr(self.server, 'nonce_replay_guard', None)
        if replay_guard is not None and not replay_guard.mark(nonce, now):
            return False, 'signature-replay'
        return True, ''

    def _require_auth(self, admin: bool = False, body: bytes = b'') -> bool:
        if not self._is_authorized(admin=admin):
            self._json(401, {'ok': False, 'error': 'unauthorized', 'serverTime': self._server_time()})
            return False
        signature_ok, signature_error = self._verify_signature(body)
        if signature_ok:
            return True
        self._json(401, {'ok': False, 'error': signature_error or 'unauthorized-signature', 'serverTime': self._server_time()})
        return False

    def _path(self) -> str:
        path = urlparse(self.path).path
        if path.endswith('/') and path != '/':
            path = path.rstrip('/')
        return path

    def _query(self) -> dict[str, list[str]]:
        return parse_qs(urlparse(self.path).query)

    def do_OPTIONS(self) -> None:
        self._headers(204, 0, content_type='text/plain')

    def do_GET(self) -> None:
        path = self._path()
        store = self._state()

        if path == '/health':
            snap = store.admin_snapshot(None)
            self._json(
                200,
                {
                    'ok': True,
                    'serverTime': self._server_time(),
                    'version': snap.get('version'),
                    'namespaces': len(snap.get('namespaces', {})),
                    'clients': len(snap.get('clients', {})),
                    'events': len(snap.get('events', [])),
                },
            )
            return

        if path == '/v1/admin/state':
            if not self._require_auth(admin=True, body=b''):
                return
            namespace = string_value(self._query().get('namespace', [''])[0]).strip().lower() or None
            self._json(200, {'ok': True, 'serverTime': self._server_time(), 'data': store.admin_snapshot(namespace)})
            return

        if path == '/v1/admin/clients':
            if not self._require_auth(admin=True, body=b''):
                return
            snap = store.admin_snapshot(None)
            self._json(200, {'ok': True, 'serverTime': self._server_time(), 'clients': snap.get('clients', {})})
            return

        if path == '/v1/sync/events':
            if not self._require_auth(admin=False, body=b''):
                return
            query = self._query()
            namespace = string_value(query.get('namespace', ['default'])[0]).strip().lower() or 'default'
            since = int_value(query.get('since', ['0'])[0], 0)
            limit = max(1, min(500, int_value(query.get('limit', ['100'])[0], 100)))
            events = store.events_since(namespace, since, limit)
            self._json(200, {'ok': True, 'namespace': namespace_from_payload({'namespace': namespace}), 'events': events, 'serverTime': self._server_time()})
            return


        if path in ('/stream', '/v1/sync/stream'):
            if not self._require_auth(admin=False, body=b''):
                return

            query = self._query()
            module_raw = string_value(query.get('module', [''])[0]).strip()
            namespace_raw = string_value(query.get('namespace', [''])[0]).strip().lower()
            if not namespace_raw:
                namespace_raw = module_raw.lower() if module_raw else 'default'
            namespace = namespace_from_payload({'namespace': namespace_raw})
            namespace_module = split_namespace(namespace)[1]
            module = normalize_module(module_raw) if module_raw else namespace_module
            if module_raw and module != namespace_module:
                self._json(
                    400,
                    {
                        'ok': False,
                        'error': 'module-namespace-mismatch',
                        'module': module,
                        'namespace': namespace,
                        'serverTime': self._server_time(),
                    },
                )
                return

            device_id = string_value(query.get('deviceId', ['unknown-device'])[0], 'unknown-device').strip() or 'unknown-device'
            known_revision = int_value(query.get('knownRevision', ['-1'])[0], -1)
            wait_ms_raw = int_value(query.get('waitMs', ['0'])[0], 0)
            wait_cap = int(self.server.pull_wait_max_ms)  # type: ignore[attr-defined]
            wait_ms = clamp_stream_wait_ms(wait_ms_raw, wait_cap)
            ip = string_value(self.client_address[0], '')
            user_agent = string_value(self.headers.get('User-Agent'), '')
            store.register_client(device_id, namespace, module, ip, user_agent)

            try:
                self._sse_headers()
                initial = store.pull(namespace, known_revision, 0, device_id)
                self._sse_event(
                    'ready',
                    {
                        'ok': True,
                        'serverTime': self._server_time(),
                        'namespace': initial.namespace,
                        'revision': initial.revision,
                        'eventId': initial.event_id,
                        'lastWriter': initial.last_writer,
                    },
                )
                known_revision = initial.revision

                while True:
                    result = store.pull(namespace, known_revision, wait_ms, device_id)
                    if result.revision != known_revision:
                        self._sse_event(
                            'sync',
                            {
                                'ok': True,
                                'serverTime': self._server_time(),
                                'namespace': result.namespace,
                                'revision': result.revision,
                                'eventId': result.event_id,
                                'lastWriter': result.last_writer,
                            },
                        )
                        known_revision = result.revision
                    else:
                        self._sse_ping()
            except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError, TimeoutError):
                return
            except Exception as exc:
                print(f"[{time.strftime('%H:%M:%S')}] stream-error {self.client_address[0]}: {exc}")
                return

        self._json(404, {'ok': False, 'error': 'not-found', 'serverTime': self._server_time()})

    def do_POST(self) -> None:
        path = self._path()
        content_length = self._content_length()
        if content_length < 0:
            self._json(400, {'ok': False, 'error': 'bad-content-length', 'serverTime': self._server_time()})
            return
        max_body_bytes = max(1_024, int_value(getattr(self.server, 'max_body_bytes', MAX_BODY_BYTES_DEFAULT), MAX_BODY_BYTES_DEFAULT))
        if content_length > max_body_bytes:
            self._json(
                413,
                {
                    'ok': False,
                    'error': 'payload-too-large',
                    'maxBodyBytes': max_body_bytes,
                    'serverTime': self._server_time(),
                },
            )
            return

        raw_body, payload = self._read_json(content_length)
        if payload is None:
            self._json(400, {'ok': False, 'error': 'bad-json', 'serverTime': self._server_time()})
            return
        store = self._state()

        if path in ('/pull', '/push', '/v1/client/start', '/v1/sync/pull', '/v1/sync/push', '/v1/sync/ack', '/v1/sync/events'):
            if not self._require_auth(admin=False, body=raw_body):
                return
        elif path.startswith('/v1/admin/'):
            if not self._require_auth(admin=True, body=raw_body):
                return

        device_id = string_value(payload.get('deviceId'), 'unknown-device').strip() or 'unknown-device'
        module_raw = string_value(payload.get('module')).strip()
        namespace = namespace_from_payload(payload)
        namespace_module = split_namespace(namespace)[1]
        module = normalize_module(module_raw) if module_raw else namespace_module
        if module_raw and module != namespace_module:
            self._json(
                400,
                {
                    'ok': False,
                    'error': 'module-namespace-mismatch',
                    'module': module,
                    'namespace': namespace,
                    'serverTime': self._server_time(),
                },
            )
            return
        ip = string_value(self.client_address[0], '')
        user_agent = string_value(self.headers.get('User-Agent'), '')

        if path == '/v1/client/start':
            result = store.start_client(device_id, namespace, module, ip, user_agent)
            self._json(
                200,
                {
                    'ok': True,
                    'serverTime': self._server_time(),
                    'namespace': result.namespace,
                    'revision': result.revision,
                    'profiles': result.profiles,
                    'checksum': result.checksum,
                    'changed': result.changed,
                    'eventId': result.event_id,
                    'lastWriter': result.last_writer,
                    'capabilities': {
                        'longPoll': True,
                        'events': True,
                        'stream': True,
                        'ack': True,
                        'apiVersion': 3,
                    },
                },
            )
            return

        if path in ('/pull', '/v1/sync/pull'):
            store.register_client(device_id, namespace, module, ip, user_agent)
            known_revision = int_value(payload.get('knownRevision'), int_value(payload.get('known_revision'), -1))
            wait_ms = int_value(payload.get('waitMs'), 0)
            wait_cap = int(self.server.pull_wait_max_ms)  # type: ignore[attr-defined]
            wait_ms = max(0, min(wait_cap, wait_ms))
            result = store.pull(namespace, known_revision, wait_ms, device_id)
            self._json(
                200,
                {
                    'ok': True,
                    'serverTime': self._server_time(),
                    'namespace': result.namespace,
                    'revision': result.revision,
                    'profiles': result.profiles,
                    'checksum': result.checksum,
                    'changed': result.changed,
                    'eventId': result.event_id,
                    'lastWriter': result.last_writer,
                },
            )
            return

        if path in ('/push', '/v1/sync/push'):
            store.register_client(device_id, namespace, module, ip, user_agent)
            base_revision = int_value(payload.get('baseRevision'), int_value(payload.get('base_revision'), -1))
            request_id = string_value(payload.get('requestId'), '').strip()
            allow_empty_overwrite = bool_value(payload.get('allowEmptyOverwrite'), False) or bool_value(payload.get('allow_empty_overwrite'), False)
            if bool(getattr(self.server, 'require_encrypted_sync', False)):
                normalized_module = namespace_module
                if normalized_module in ENCRYPTED_MODULES and not is_encrypted_profiles_payload(payload.get('profiles'), normalized_module):
                    self._json(
                        400,
                        {
                            'ok': False,
                            'error': 'unencrypted-profiles-rejected',
                            'module': normalized_module,
                            'serverTime': self._server_time(),
                        },
                    )
                    return
            profiles = normalize_profiles(payload.get('profiles'), namespace)
            result = store.push(namespace, base_revision, profiles, device_id, request_id, allow_empty_overwrite)
            self._json(
                200,
                {
                    'ok': True,
                    'serverTime': self._server_time(),
                    'namespace': result.namespace,
                    'revision': result.revision,
                    'profiles': result.profiles,
                    'checksum': result.checksum,
                    'applied': result.applied,
                    'conflict': result.conflict,
                    'error': result.error,
                    'eventId': result.event_id,
                },
            )
            return

        if path == '/v1/sync/ack':
            revision = int_value(payload.get('revision'), -1)
            if revision >= 0:
                store.ack(namespace, device_id, revision)
            self._json(200, {'ok': True, 'serverTime': self._server_time(), 'namespace': namespace, 'revision': revision})
            return

        if path == '/v1/sync/events':
            since = int_value(payload.get('sinceEventId'), int_value(payload.get('since'), 0))
            limit = max(1, min(500, int_value(payload.get('limit'), 100)))
            events = store.events_since(namespace, since, limit)
            self._json(200, {'ok': True, 'serverTime': self._server_time(), 'namespace': namespace, 'events': events})
            return

        if path == '/v1/admin/reset':
            ns = string_value(payload.get('namespace')).strip().lower() or None
            preserve_clients = bool_value(payload.get('preserveClients'), True)
            snapshot = store.admin_reset(ns, preserve_clients)
            self._json(200, {'ok': True, 'serverTime': self._server_time(), 'data': snapshot})
            return

        if path == '/v1/admin/import':
            ns = namespace_from_payload(payload)
            force_revision_raw = payload.get('forceRevision')
            force_revision = int_value(force_revision_raw, -1) if force_revision_raw is not None else None
            if force_revision is not None and force_revision < 0:
                force_revision = None
            profiles = normalize_profiles(payload.get('profiles'), ns)
            snapshot = store.admin_import(ns, profiles, force_revision)
            self._json(200, {'ok': True, 'serverTime': self._server_time(), 'namespace': ns, 'state': snapshot})
            return

        self._json(404, {'ok': False, 'error': 'not-found', 'serverTime': self._server_time()})

    def log_message(self, fmt: str, *args: Any) -> None:
        path = self._path()
        ip = string_value(self.client_address[0], '')
        status_code = None
        if len(args) >= 2:
            try:
                status_code = int(str(args[1]))
            except Exception:
                status_code = None
        if path == '/health' and ip in {'127.0.0.1', '::1', 'localhost'}:
            return
        if ip in {'127.0.0.1', '::1', 'localhost'} and status_code is not None and 200 <= status_code < 300:
            return
        if path in {'/push', '/v1/sync/push'}:
            log_push_success = bool(getattr(self.server, 'log_push_success', False))
            if not log_push_success and status_code is not None and 200 <= status_code < 300:
                return
        if path in {'/pull', '/v1/sync/pull'}:
            log_pull_success = bool(getattr(self.server, 'log_pull_success', False))
            if not log_pull_success and status_code is not None and 200 <= status_code < 300:
                return
        print(f"[{time.strftime('%H:%M:%S')}] {ip} {fmt % args}")


def main() -> None:
    host = string_value(os.getenv('SYNC_HOST'), '127.0.0.1')
    port = int_value(os.getenv('SYNC_PORT'), 7878)

    default_state_file = Path(__file__).with_name('sync_state.json')
    state_file = Path(string_value(os.getenv('SYNC_STATE_FILE'), str(default_state_file)))

    default_modules_dir = state_file.parent / 'modules'
    namespaces_dir = Path(string_value(os.getenv('SYNC_NAMESPACES_DIR'), str(default_modules_dir)))

    sync_token = string_value(os.getenv('SYNC_TOKEN'), '').strip()
    admin_token = string_value(os.getenv('SYNC_ADMIN_TOKEN'), '').strip()
    require_encrypted_sync = bool_value(os.getenv('SYNC_REQUIRE_ENCRYPTED'), True)
    require_signed = bool_value(os.getenv('SYNC_REQUIRE_SIGNED'), True)
    signing_key = string_value(os.getenv('SYNC_SIGNING_KEY'), '').strip()
    sign_window_sec = max(5, int_value(os.getenv('SYNC_SIGN_WINDOW_SEC'), SIGN_WINDOW_SEC_DEFAULT))
    nonce_ttl_sec = max(sign_window_sec + 5, int_value(os.getenv('SYNC_NONCE_TTL_SEC'), NONCE_TTL_SEC_DEFAULT))
    nonce_cache_max = max(1_000, int_value(os.getenv('SYNC_NONCE_CACHE_MAX'), NONCE_CACHE_MAX_DEFAULT))
    tls_cert_file = string_value(os.getenv('SYNC_TLS_CERT_FILE'), '').strip()
    tls_key_file = string_value(os.getenv('SYNC_TLS_KEY_FILE'), '').strip()
    tls_min_version = string_value(os.getenv('SYNC_TLS_MIN_VERSION'), 'TLSv1_3').strip().upper()
    allow_cors = bool_value(os.getenv('SYNC_ALLOW_CORS'), False)
    events_max = max(100, int_value(os.getenv('SYNC_EVENTS_MAX'), EVENTS_MAX_DEFAULT))
    pull_wait_max_ms = max(0, int_value(os.getenv('SYNC_PULL_WAIT_MAX_MS'), PULL_WAIT_MAX_MS_DEFAULT))
    max_body_bytes = max(1_024, int_value(os.getenv('SYNC_MAX_BODY_BYTES'), MAX_BODY_BYTES_DEFAULT))
    log_push_success = bool_value(os.getenv('SYNC_LOG_PUSH_SUCCESS'), False)
    log_pull_success = bool_value(os.getenv('SYNC_LOG_PULL_SUCCESS'), False)

    if require_signed and not signing_key:
        print('FATAL: SYNC_REQUIRE_SIGNED=true but SYNC_SIGNING_KEY is empty.')
        raise SystemExit(2)

    store = SyncStore(state_file, namespaces_dir, events_max)

    server = QuietThreadingHTTPServer((host, port), SyncHandler)
    server.sync_store = store  # type: ignore[attr-defined]
    server.sync_token = sync_token  # type: ignore[attr-defined]
    server.admin_token = admin_token  # type: ignore[attr-defined]
    server.require_encrypted_sync = require_encrypted_sync  # type: ignore[attr-defined]
    server.allow_cors = allow_cors  # type: ignore[attr-defined]
    server.pull_wait_max_ms = pull_wait_max_ms  # type: ignore[attr-defined]
    server.max_body_bytes = max_body_bytes  # type: ignore[attr-defined]
    server.log_push_success = log_push_success  # type: ignore[attr-defined]
    server.log_pull_success = log_pull_success  # type: ignore[attr-defined]
    server.require_signed = require_signed  # type: ignore[attr-defined]
    server.signing_key = signing_key  # type: ignore[attr-defined]
    server.sign_window_sec = sign_window_sec  # type: ignore[attr-defined]
    server.nonce_replay_guard = NonceReplayGuard(nonce_ttl_sec, nonce_cache_max)  # type: ignore[attr-defined]

    listen_scheme = 'http'
    if tls_cert_file or tls_key_file:
        if not tls_cert_file or not tls_key_file:
            print('FATAL: both SYNC_TLS_CERT_FILE and SYNC_TLS_KEY_FILE are required for HTTPS.')
            raise SystemExit(2)
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        context.options |= ssl.OP_NO_COMPRESSION
        if tls_min_version == 'TLSV1_3':
            context.minimum_version = ssl.TLSVersion.TLSv1_3
        elif tls_min_version == 'TLSV1_2':
            context.minimum_version = ssl.TLSVersion.TLSv1_2
        else:
            print(f'FATAL: unsupported SYNC_TLS_MIN_VERSION={tls_min_version} (expected TLSv1_2 or TLSv1_3).')
            raise SystemExit(2)
        context.load_cert_chain(certfile=tls_cert_file, keyfile=tls_key_file)
        server.socket = context.wrap_socket(server.socket, server_side=True)
        listen_scheme = 'https'

    print('Devils sync hub backend started')
    print(f'  listen         : {listen_scheme}://{host}:{port}')
    print(f'  meta-state     : {state_file}')
    print(f'  namespaces-dir : {namespaces_dir}')
    print(f"  token          : {'enabled' if sync_token else 'disabled'}")
    print(f"  admin-token    : {'enabled' if admin_token else 'disabled (fallback to user token)'}")
    print(f'  require-e2e    : {require_encrypted_sync}')
    print(f'  require-signed : {require_signed}')
    print(f'  sign-window    : {sign_window_sec} sec')
    print(f'  nonce-ttl      : {nonce_ttl_sec} sec')
    print(f'  nonce-max      : {nonce_cache_max}')
    if listen_scheme == 'https':
        print(f'  tls-cert-file  : {tls_cert_file}')
        print(f'  tls-key-file   : {tls_key_file}')
        print(f'  tls-min-ver    : {tls_min_version}')
    print(f'  allow-cors     : {allow_cors}')
    print(f'  events-max     : {events_max}')
    print(f'  pull-wait-max  : {pull_wait_max_ms} ms')
    print(f'  max-body-bytes : {max_body_bytes}')
    print(f'  log-pull-ok    : {log_pull_success}')
    print(f'  log-push-2xx   : {log_push_success}')
    print('  routes         : /pull, /push, /health')
    print('  api-v3         : /v1/client/start, /v1/sync/*, /v1/admin/*')
    print('  stream         : /stream, /v1/sync/stream')
    server.serve_forever()


if __name__ == '__main__':
    main()
