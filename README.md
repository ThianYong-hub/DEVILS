# Devils Addon

An addon for [Meteor Client](https://github.com/MeteorDevelopment/meteor-client) focused on combat automation, movement control, SyncHub networking, and deep Xaero map integration.

[![Release](https://img.shields.io/github/v/release/ThianYong-hub/DEVILS?label=Release)](https://github.com/ThianYong-hub/DEVILS/releases/latest)
[![Minecraft](https://img.shields.io/badge/Minecraft-1.21.11-31a24c)](https://github.com/ThianYong-hub/DEVILS)
[![Java](https://img.shields.io/badge/Java-21-orange)](https://adoptium.net/)
[![Downloads](https://img.shields.io/github/downloads/ThianYong-hub/DEVILS/total)](https://github.com/ThianYong-hub/DEVILS/releases)
[![Stars](https://img.shields.io/github/stars/ThianYong-hub/DEVILS?style=social)](https://github.com/ThianYong-hub/DEVILS)
[![Last Commit](https://img.shields.io/github/last-commit/ThianYong-hub/DEVILS)](https://github.com/ThianYong-hub/DEVILS/commits)
[![Build](https://github.com/ThianYong-hub/DEVILS/actions/workflows/dev_build.yml/badge.svg)](https://github.com/ThianYong-hub/DEVILS/actions/workflows/dev_build.yml)

## Download

- Latest release: <https://github.com/ThianYong-hub/DEVILS/releases/latest>
- Current addon build (`0.0.48`): `build/libs/devils-addon-0.0.46.jar`
- Current game build (`0.0.4`): `build/libs/devils-game-0.0.4.jar`
- GitHub release links should be treated as latest published release, not as the current local workspace build.

## Artifact Model

- `devils-addon` is the main addon jar with combat, utility, world, SyncHub, AutoCraft, and Xaero integrations.
- `devils-game` is a separate addon jar that contains checkers, chess, blackjack, slot machine, Doom, and dedicated game sync.
- `devils-addon` and `devils-game` can be installed separately or together. Shared sync/config primitives live in an internal shared layer.

## Verified Runtime Matrix

| Item | Value |
| --- | --- |
| Addon Version | `0.0.48` |
| Game Version | `0.0.4` |
| Minecraft | `1.21.11` |
| Fabric Loader | `0.18.4+` |
| Java | `21` |
| Meteor Client | build for `1.21.11` |
| Source-Native Assimilations | `Xaero MiniMap`, `Xaero World Map`, `XaeroPlus`, `ChestTracker Port` |

## Why This Addon

- Combat and movement modules are bundled with sync-aware team tooling.
- SyncHub supports signed requests and encrypted payload flow.
- Xaero integration is not cosmetic: live player markers and managed waypoint pipeline are integrated into addon runtime as source-native addon code, not nested mod jars.
- ChestTracker / WhereIsIt / Searchables / YACL are assimilated into the addon build instead of shipping as jar-in-jar payload.
- Includes migration tooling (`mod-auto-updater`) and dedicated backend stress tests (`SyncHub/tests/sync_stress_tester.py`).

## Modules

### Combat Modules

| Module | Description |
| --- | --- |
| `auto-cev` | Places obsidian and crystals for an aggressive Cev cycle. |
| `auto-pearl` | Tracks target and chases with pearls. Supports chat trigger `!pearl <nick>`. |
| `tnt-bomber` | Traps target with obsidian and executes TNT bombing sequence. |
| `lava-bucket` | Automatically places and collects lava around nearby players. |
| `mace-spoof` | Spoofs fall-distance conditions to amplify mace damage. |
| `spear-spoof` | Full spear FSM: targeting, movement controller, attack contour, debug pipeline. |

### Movement and World Modules

| Module | Description |
| --- | --- |
| `auto-wasp` | Elytra follow/chase with obstacle-aware routing. |
| `anti-wasp` | Elytra evasion patterns (circle/square/triangle) with obstacle checks. |
| `h-clip` | Fast horizontal corner clip helper. |
| `v-clip` | Instant vertical clip by configured distance. |
| `highway-builder-plus` | Automated Nether highway/tunnel/flat path builder with mining, placing, and task pipeline. |

### Utility and Team Modules

| Module | Description |
| --- | --- |
| `ping` | Synchronized ping markers with 2D/3D render, sound, custom icon and SyncHub bridge. |
| `tracker-player` (`join-watcher`) | Per-player join/leave/death rules with sounds and optional delayed chat actions. |
| `auto-login` | Auto `/login` and `/reg` by username + server profile. |
| `auto-anvil-rename` | Auto-renames matching items in open anvil with filters and XP assist. |
| `discord-rpc` | Shows Devils Addon presence in Discord Rich Presence. |

### Core and Integrations

| Module | Description |
| --- | --- |
| `sync-hub` | Shared sync settings for `auto-login`, `ping`, `chest-tracker`, `xaero-world-map`. |
| `chest-tracker` | Integrated ChestTracker module with Devils theme, local storage and SyncHub sync. |
| `mod-auto-updater` | One-click migration helper for `1.21.11` updates (Modrinth + GitHub lookups). |
| `xaero-sync` | Internal runtime integration for Xaero World Map overlay and tracked players. Auto-started by `sync-hub`. |

### Devils Game Companion Modules

| Module | Description |
| --- | --- |
| `games` | Launcher module for the companion games pack. |
| `chess-overlay` | Floating chess window with script and SyncHub play modes. |
| `devils-game-overlay` | Floating checkers window with script and SyncHub play modes. |
| `blackjack-overlay` | Blackjack overlay window and session runtime. |
| `slot-machine` | Slot machine overlay with bundled assets. |
| `russian-roulette` | Russian roulette overlay and death-sequence runtime. |
| `devilsdoom-overlay` | Embedded Doom runtime with bundled Freedoom assets. |

## Commands

| Command | Purpose |
| --- | --- |
| `.autoraname setname <text>` | Set target rename text for `auto-anvil-rename`. |
| `.autoraname clearitems` | Clear item filter list for `auto-anvil-rename`. |
| `.example` | Internal example command. |

## SyncHub Backend Quick Start (For Regular Users)

This is the shortest path to run your own SyncHub backend on a home PC, VPS, or dedicated server.

### 1) Requirements

- Docker + Docker Compose plugin installed
- Open TCP port (default `7878`) on the host firewall/provider firewall

### 2) Prepare Environment File

```bash
cd SyncHub
cp .env.example .env
```

Generate secrets and paste values into `.env`:

```bash
python -c "import secrets; print('SYNC_AUTH_TOKEN=' + secrets.token_urlsafe(32)); print('SYNC_REQUEST_SIGNING_KEY=' + secrets.token_urlsafe(48)); print('SYNC_E2E_SECRET=' + secrets.token_urlsafe(48))"
```

Important:
- `SYNC_E2E_SECRET` is client-side only. Do not add it to backend `.env`.
- Keep `SYNC_REQUIRE_REQUEST_SIGNING=true` and `SYNC_REQUIRE_E2E=true` unless this is a throwaway local test server.

### 3) Start Backend

```bash
cd SyncHub
docker compose up -d --build
docker compose ps
```

Health check:

```text
GET http://<SERVER_IP>:7878/health
```

### 4) Configure Meteor `sync-hub` Module

| Setting | Value |
| --- | --- |
| `base-url` | `http://<SERVER_IP>:7878` or `https://<DOMAIN>` |
| `auth-token` | `SYNC_AUTH_TOKEN` |
| `transport-signing-key` | `SYNC_REQUEST_SIGNING_KEY` |
| `e2e-secret` | your generated `SYNC_E2E_SECRET` |
| `allow-http` | `true` only for plain HTTP, `false` for HTTPS |

### 5) Deploy On Your Own Server (VPS / Dedicated)

```bash
git clone https://github.com/ThianYong-hub/DEVILS.git
cd DEVILS/SyncHub
cp .env.example .env
# edit .env with your real tokens
docker compose up -d --build
```

After deploy:
- allow inbound TCP `7878` or put service behind reverse proxy on `443`
- keep `.env` private
- rotate tokens immediately if they were exposed

## SyncHub Setup (Exact Values)

### 1) Generate Secrets

Generate three role-specific values:
- `SYNC_AUTH_TOKEN`: bearer auth credential
- `SYNC_REQUEST_SIGNING_KEY`: HMAC key for signed transport requests
- `SYNC_E2E_SECRET`: client-only E2E payload secret

```bash
python -c "import secrets; print('SYNC_AUTH_TOKEN=' + secrets.token_urlsafe(32)); print('SYNC_REQUEST_SIGNING_KEY=' + secrets.token_urlsafe(48)); print('SYNC_E2E_SECRET=' + secrets.token_urlsafe(48))"
```

### 2) Backend `.env` (SyncHub)

```env
# Required
SYNC_AUTH_TOKEN=replace_me
SYNC_REQUEST_SIGNING_KEY=replace_me
SYNC_REQUIRE_REQUEST_SIGNING=true
SYNC_REQUIRE_E2E=true

# Transport / storage
SYNC_HOST=0.0.0.0
SYNC_PORT=7878
SYNC_STATE_FILE=/data/sync-hub-state.json
SYNC_NAMESPACES_DIR=/data/modules

# Optional HTTP behavior / limits
SYNC_ALLOW_CORS=false
SYNC_PULL_WAIT_MAX_MS=25000
SYNC_MAX_BODY_BYTES=1048576

# Optional admin token
SYNC_ADMIN_AUTH_TOKEN=

# Optional HTTPS
SYNC_TLS_CERT_FILE=
SYNC_TLS_KEY_FILE=
SYNC_TLS_MIN_VERSION=TLSv1_3
```

Compatibility:
- backend still accepts legacy `SYNC_TOKEN`, `SYNC_ADMIN_TOKEN`, `SYNC_SIGNING_KEY`, `SYNC_REQUIRE_SIGNED`, `SYNC_REQUIRE_ENCRYPTED`
- backend does not use `SYNC_E2E_SECRET`; that secret stays client-side
- legacy backend aliases are deprecated-but-supported during the migration window; preferred names stay authoritative

### 3) Client `sync-hub` Settings (Meteor)

| Setting | Value |
| --- | --- |
| `base-url` | `https://your-domain` or `http://IP:PORT` |
| `auth-token` | `SYNC_AUTH_TOKEN` or legacy `SYNC_TOKEN` |
| `transport-signing-key` | `SYNC_REQUEST_SIGNING_KEY` or legacy `SYNC_SIGNING_KEY` |
| `e2e-secret` | `SYNC_E2E_SECRET` or legacy `SYNC_ENCRYPTION_KEY` |
| `allow-http` | `false` for HTTPS, `true` only for plain HTTP |
| `use-stream` | `true` (recommended) |

Important:
- Do not use bare `144.31.167.88:25570` as `base-url`.
- Correct form is `http://144.31.167.88:25570` (or `https://...` when TLS is configured).
- `auth-token` is auth only; do not reuse it as crypto material.
- `transport-signing-key` protects request integrity and replay resistance.
- `e2e-secret` encrypts payloads before they leave the client; backend never needs it.

Resolve order and migration behavior:
- preferred names win over legacy aliases
- if only a legacy client field exists, Devils migrates it to the preferred name for the current session and warns once
- if preferred and legacy client fields both exist with different values, the preferred value wins and Devils warns once
- if `auth-token` is empty, sync only works against a backend that allows anonymous requests
- if `transport-signing-key` is empty, sync only works against a backend with request signing disabled
- if `e2e-secret` is empty, encrypted sync modules stay blocked until it is filled

### 4) Run Backend via Docker

```bash
cd SyncHub
docker compose up -d --build
```

Health endpoint:

```text
GET /health
```

Admin-only config diagnostics:

```text
GET /v1/admin/config
```

Use `Authorization: Bearer <SYNC_ADMIN_AUTH_TOKEN>` or, if no dedicated admin token is configured, `Authorization: Bearer <SYNC_AUTH_TOKEN>`.
If `SYNC_REQUIRE_REQUEST_SIGNING=true`, the request also needs the normal `X-Devils-*` signing headers built from `SYNC_REQUEST_SIGNING_KEY`.

This endpoint reports config mode, deprecated alias usage, and active auth/signing/E2E policy without exposing secret values.

## SyncHub Stress Tester (2 Modes)

Script: `SyncHub/tests/sync_stress_tester.py`

Preferred flags:
- `--auth-token`
- `--request-signing-key`
- `--e2e-secret`

Deprecated compatibility flags:
- `--token`
- `--signing-key`
- `--encryption-key`

### Mode 1: Random Traffic (2 minutes)

```bash
python SyncHub/tests/sync_stress_tester.py \
  --base-url http://127.0.0.1:7878 \
  --module xaero-world-map \
  --duration-sec 120 \
  --mode random \
  --clients 2 \
  --push-interval-ms 50 \
  --pull-interval-ms 50 \
  --auth-token "$SYNC_AUTH_TOKEN" \
  --request-signing-key "$SYNC_REQUEST_SIGNING_KEY" \
  --e2e-secret "$SYNC_E2E_SECRET"
```

### Mode 2: Elytra48 (48 blocks/sec)

```bash
python SyncHub/tests/sync_stress_tester.py \
  --base-url http://127.0.0.1:7878 \
  --module xaero-world-map \
  --duration-sec 120 \
  --mode elytra48 \
  --elytra-speed-bps 48 \
  --clients 2 \
  --push-interval-ms 50 \
  --pull-interval-ms 50 \
  --auth-token "$SYNC_AUTH_TOKEN" \
  --request-signing-key "$SYNC_REQUEST_SIGNING_KEY" \
  --e2e-secret "$SYNC_E2E_SECRET"
```

Optional report output:

```bash
--output-json SyncHub/tests/last-summary.json
```

## Installation

1. Install Minecraft + Fabric + Fabric API for `1.21.11`.
2. Install Meteor Client for `1.21.11`.
3. Download `devils-addon-*.jar` from releases.
4. Optional: download `devils-game-*.jar` if you want the games companion pack.
5. Put the selected jars into `.minecraft/mods`.
6. Launch game and open `Devils` and, when installed, `Devils-Game` categories in Meteor.

## Build From Source

```bash
git clone https://github.com/ThianYong-hub/DEVILS.git
cd DEVILS
./gradlew build
```

Windows:

```bat
gradlew.bat build
```

Run tests:

```bash
./gradlew test
```

Build only the main addon:

```bash
./gradlew :devils-addon:build
```

Build only the game companion:

```bash
./gradlew :devils-game:build
```

## Release Notes (`v0.0.46`)

- bumped addon artifact version from `0.0.45` to `0.0.46`
- fixed the broken packaged runtime by bundling the required non-mod runtime libraries into the final addon jar
- preserved the de-jarred source-native addon layout while keeping compile, test, remap, runtime smoke, and full build validation green
- kept the cleaned final jar structure introduced in the previous pass

## Repository Structure

- `devils-addon/` - main addon sources, tests, fabric metadata, and source-native assimilated integrations.
- `devils-game/` - companion games addon sources, assets, and fabric metadata.
- `devils-addon/src/main/java/com/example/addon/modules` - public module entrypoints for the main addon.
- `devils-game/src/main/java/com/example/addon/modules/games` - game overlays, sessions, windows, and launcher runtime.
- `devils-addon/src/main/java/com/example/addon/util/xaerosync` - waypoint and tracked-player integration helpers.
- `SyncHub/` - standalone sync backend (`sync_backend.py`, Docker files, tests).
- `SyncHub/tests/sync_stress_tester.py` - load and propagation test harness.

## Credits

- [Meteor Client](https://github.com/MeteorDevelopment/meteor-client)
- [Xaero's Minimap](https://www.curseforge.com/minecraft/mc-mods/xaeros-minimap)
- [Xaero's World Map](https://www.curseforge.com/minecraft/mc-mods/xaeros-world-map)
- [XaeroPlus](https://github.com/rfresh2/XaeroPlus)
- [ChestTracker](https://modrinth.com/mod/chest-tracker)

## License

Project license: [GPL-3.0](LICENSE).
