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
- Current build (`v0.0.41`): <https://github.com/ThianYong-hub/DEVILS/releases/download/v0.0.41/devils-addon-0.0.41.jar>

## Verified Runtime Matrix

| Item | Value |
| --- | --- |
| Addon Version | `0.0.41` |
| Minecraft | `1.21.11` |
| Fabric Loader | `0.18.4+` |
| Java | `21` |
| Meteor Client | build for `1.21.11` |
| Embedded Integrations | `Xaero MiniMap`, `Xaero World Map`, `XaeroPlus`, `ChestTracker Port` |

## Why This Addon

- Combat and movement modules are bundled with sync-aware team tooling.
- SyncHub supports signed requests and encrypted payload flow.
- Xaero integration is not cosmetic: live player markers and managed waypoint pipeline are integrated into addon runtime.
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
| `mod-auto-updater` | One-click migration helper from `1.21.8` to `1.21.11` (Modrinth + GitHub lookups). |
| `xaero-sync` | Internal runtime integration for Xaero World Map overlay and tracked players. Auto-started by `sync-hub`. |

## Commands

| Command | Purpose |
| --- | --- |
| `.autoraname setname <text>` | Set target rename text for `auto-anvil-rename`. |
| `.autoraname clearitems` | Clear item filter list for `auto-anvil-rename`. |
| `.example` | Internal example command. |

## SyncHub Setup (Exact Values)

### 1) Generate Secrets

Generate three independent values (`token`, `request-signing-key`, `encryption-key`):

```bash
python -c "import secrets; print('SYNC_TOKEN=' + secrets.token_urlsafe(32)); print('SYNC_SIGNING_KEY=' + secrets.token_urlsafe(48)); print('SYNC_ENCRYPTION_KEY=' + secrets.token_urlsafe(48))"
```

### 2) Backend `.env` (SyncHub)

```env
SYNC_TOKEN=replace_me
SYNC_SIGNING_KEY=replace_me
SYNC_REQUIRE_SIGNED=true
SYNC_REQUIRE_ENCRYPTED=true
SYNC_HOST=0.0.0.0
SYNC_PORT=7878
SYNC_STATE_FILE=/data/sync-hub-state.json
SYNC_NAMESPACES_DIR=/data/modules
# Optional HTTPS
SYNC_TLS_CERT_FILE=
SYNC_TLS_KEY_FILE=
SYNC_TLS_MIN_VERSION=TLSv1_3
```

### 3) Client `sync-hub` Settings (Meteor)

| Setting | Value |
| --- | --- |
| `base-url` | `https://your-domain` or `http://IP:PORT` |
| `token` | `SYNC_TOKEN` |
| `request-signing-key` | `SYNC_SIGNING_KEY` |
| `encryption-key` | `SYNC_ENCRYPTION_KEY` |
| `allow-http` | `false` for HTTPS, `true` only for plain HTTP |
| `use-stream` | `true` (recommended) |

Important:
- Do not use bare `144.31.167.88:25570` as `base-url`.
- Correct form is `http://144.31.167.88:25570` (or `https://...` when TLS is configured).

### 4) Run Backend via Docker

```bash
cd SyncHub
docker compose up -d --build
```

Health endpoint:

```text
GET /health
```

## SyncHub Stress Tester (2 Modes)

Script: `SyncHub/tests/sync_stress_tester.py`

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
  --token "$SYNC_TOKEN" \
  --signing-key "$SYNC_SIGNING_KEY" \
  --encryption-key "$SYNC_ENCRYPTION_KEY"
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
  --token "$SYNC_TOKEN" \
  --signing-key "$SYNC_SIGNING_KEY" \
  --encryption-key "$SYNC_ENCRYPTION_KEY"
```

Optional report output:

```bash
--output-json SyncHub/tests/last-summary.json
```

## Installation

1. Install Minecraft + Fabric + Fabric API for `1.21.11`.
2. Install Meteor Client for `1.21.11`.
3. Download latest `devils-addon-*.jar` from releases.
4. Put jar into `.minecraft/mods`.
5. Launch game and open `Devils` category in Meteor.

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

## Release Notes (`v0.0.41`)

- shipped the new slot-driven `AutoCraft` with final-goal chain planning and live runtime validation
- confirmed `2x2` and `3x3` crafting flows, `Auto Open`, intermediate reuse, and policy behavior in Minecraft runtime
- added planner, policy, and source regression coverage for the AutoCraft pipeline
- fixed remaining `craft-all=false` session handling before release packaging

## Repository Structure

- `src/main/java/com/example/addon/modules` - public module entrypoints.
- `src/main/java/com/example/addon/modules/*` - feature-local logic (sync, render, planners, controllers).
- `src/main/java/com/example/addon/util/xaerosync` - waypoint and tracked-player integration helpers.
- `SyncHub/` - standalone sync backend (`sync_backend.py`, Docker files, tests).
- `SyncHub/tests/sync_stress_tester.py` - load and propagation test harness.

## Credits

- [Meteor Client](https://github.com/MeteorDevelopment/meteor-client)
- [Xaero's Minimap](https://www.curseforge.com/minecraft/mc-mods/xaeros-minimap)
- [Xaero's World Map](https://www.curseforge.com/minecraft/mc-mods/xaeros-world-map)
- [XaeroPlus](https://github.com/rfresh2/XaeroPlus)
- [ChestTracker](https://modrinth.com/mod/chest-tracker)

## License

Project license: [CC0](LICENSE).
