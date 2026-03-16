# Devils Addon

Devils Addon is a [Meteor Client](https://github.com/MeteorDevelopment/meteor-client) addon for combat automation, movement tools, world automation, and shared cross-client sync features.

## Download

- 1.21.11 test prerelease (`v0.0.32-test.1.21.11`): [Download jar](https://github.com/ThianYong-hub/DEVILS/releases/download/v0.0.32-test.1.21.11/devils-addon-1.21.11-test.jar)
- Source snapshot zip: [Download zip](https://github.com/ThianYong-hub/DEVILS/releases/download/v0.0.32-test.1.21.11/devils-addon-1.21.11-test.zip)
- This branch: [DevilsAddon-1.21.11-test](https://github.com/ThianYong-hub/DEVILS/tree/DevilsAddon-1.21.11-test)
- 1.21.11 test release page: [Open](https://github.com/ThianYong-hub/DEVILS/releases/tag/v0.0.32-test.1.21.11)
- All releases: [Open](https://github.com/ThianYong-hub/DEVILS/releases)

## Requirements

| Component | Required Version |
| --- | --- |
| Minecraft | `1.21.11` |
| Fabric Loader | `0.18.2+` |
| Java | `21` |
| Meteor Client | build for `1.21.11` |

## Installation

1. Install Meteor Client.
2. Download `devils-addon-1.21.11-test.jar` from the `v0.0.32-test.1.21.11` prerelease.
3. Put the jar into `.minecraft/mods`.
4. Start the game and open category `Devils` in Meteor.

## Branch Status

- Branch purpose: isolated `1.21.11` test port
- Stability level: build-tested
- Runtime level: not yet verified in a live Minecraft client session
- Embedded dependency line:
  - Fabric API `0.140.0+1.21.11`
  - Xaero Minimap `fabric-1.21.11-25.3.10`
  - Xaero World Map `fabric-1.21.11-1.40.11`
  - XaeroPlus `2.30.9+fabric-1.21.11`

## Project Layout

- `src/main/java/com/example/addon/modules`: public module entrypoints and compact facades.
- `src/main/java/com/example/addon/modules/<feature>`: feature-local planners, sync controllers, render helpers, and storage logic.
- `src/main/java/com/example/addon/util/xaerosync`: Xaero waypoint and tracked-player support code.
- `src/main/java/com/example/addon/settings`: tracker rule models and UI helpers.
- `src/test/java`: larger regression suites for project structure, config, and module-specific behavior.
- Current codebase rule: Java source files are kept in the `100-500` line range to avoid both monoliths and meaningless tiny wrappers.

## Module Table

| Module | Category | What It Does |
| --- | --- | --- |
| `chest-tracker` | Utility / Storage | Fully integrated ChestTracker module inside Devils Addon. Includes custom Devils UI theme, persistent NBT memory banks, and SyncHub synchronization. |
| `sync-hub` | Core Sync | Shared sync configuration for Devils modules (`AutoLogin`, `Ping`, `ChestTracker`), including stream/pull/push behavior. |
| `xaero-sync` | Utility / Team Map | Standalone sync pipeline for Xaero World Map tracked players and managed waypoints with Devils overlay integration. |
| `auto-login` | Utility / Auth | Sends saved `/login` or `/reg` commands automatically per username/server profile. Supports SyncHub profile sync. |
| `ping` | Utility / Team | Team ping markers with optional sound/text/icon and SyncHub data sync across clients. |
| `tracker-player` (`join-watcher`) | Utility / Alerts | Per-player join/leave tracking with custom rules, sounds, and optional chat actions. |
| `auto-anvil-rename` | Utility / Inventory | Automatically renames matching items in anvils with configurable filters and delay. |
| `discord-rpc` | Utility | Shows Devils Addon status/presence in Discord Rich Presence. |
| `auto-pearl` | Combat / Mobility | Automated pearl throws with target logic, safety filters, and multi-bot coordination options. |
| `auto-cev` | Combat | Automates aggressive Cev-style obsidian/crystal cycles with combat safeguards. |
| `tnt-bomber` | Combat | Builds target trap flow and executes TNT bombing sequence around enemy players. |
| `lava-bucket` | Combat / Utility | Automates lava bucket placement/collection logic against nearby targets. |
| `mace-spoof` | Combat | Spoofs fall distance packets to improve mace damage conditions. |
| `auto-wasp` | Movement / Combat | Elytra chase module with routing/targeting logic and optional armor swap behavior. |
| `anti-wasp` | Movement / Evasion | Elytra evasion patterns (circle/square/triangle) and obstacle-aware avoidance. |
| `h-clip` | Movement | Horizontal clip helper to reposition into safer corners quickly. |
| `v-clip` | Movement | Vertical clip by configurable distance (up/down). |
| `highway-builder-plus` | World / Builder | Nether highway/tunnel/path automation with mining, placing, restock, and render controls. |

## ChestTracker Storage and Sync

- Base addon directory: `.minecraft/devils-addon`
- ChestTracker multiplayer banks: `.minecraft/devils-addon/chesttracker/multiplayer/<server>.nbt`
- ChestTracker singleplayer banks: `.minecraft/devils-addon/chesttracker/singleplayer/<world>.nbt`
- Metadata files are stored next to each bank as `.nbt.meta`.
- When SyncHub is enabled for ChestTracker, bank data is synchronized per server namespace.

## Secure SyncHub Setup

- `sync-hub` now expects two independent secrets:
  - `token` (`SYNC_TOKEN`) for bearer auth.
  - `request-signing-key` (`SYNC_SIGNING_KEY`) for HMAC request signatures.
- `encryption-key` is used for end-to-end encrypted profile payloads and is no longer auto-derived from token.
- Backend can run in HTTPS mode by setting:
  - `SYNC_TLS_CERT_FILE`
  - `SYNC_TLS_KEY_FILE`
  - optional `SYNC_TLS_MIN_VERSION` (`TLSv1_3` by default)
- Signed request checks are controlled by:
  - `SYNC_REQUIRE_SIGNED=true`
  - `SYNC_SIGN_WINDOW_SEC` (default `30`)
  - `SYNC_NONCE_TTL_SEC` (default `120`)

## Commands

| Command | Purpose |
| --- | --- |
| `.autoraname setname <text>` | Sets target rename text for `auto-anvil-rename`. |
| `.autoraname clearitems` | Clears item filter list for `auto-anvil-rename`. |
| `.example` | Test/example command. |

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

## Release Notes (`v0.0.32-test.1.21.11`)

- Ported the addon test build from the stable `1.21.8` line to `1.21.11`.
- Updated Fabric, Yarn, Meteor, Xaero Minimap, Xaero World Map, and XaeroPlus dependency targets for `1.21.11`.
- Repacked ChestTracker into a dedicated `chesttracker-port-1.21.11` module and embedded it into the test jar.
- Migrated affected Meteor input APIs, `GameProfile` accessors, camera access, and entity-position compatibility helpers.
- Revalidated the `1.21.11` branch with green `clean build` runs, including the embedded ChestTracker rebuild path.

## License

Project license: [CC0](/LICENSE).
