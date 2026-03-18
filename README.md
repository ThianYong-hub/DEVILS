# Devils Addon

Devils Addon is a [Meteor Client](https://github.com/MeteorDevelopment/meteor-client) addon for combat automation, movement tools, world automation, and shared cross-client sync features.

## Download

- Current build (`v0.0.34`): [Download jar](https://github.com/ThianYong-hub/DEVILS/releases/download/v0.0.34/devils-addon-0.0.34.jar)
- Latest release page: [Open](https://github.com/ThianYong-hub/DEVILS/releases/latest)
- All releases: [Open](https://github.com/ThianYong-hub/DEVILS/releases)

## Requirements

| Component | Required Version |
| --- | --- |
| Minecraft | `1.21.11` |
| Fabric Loader | `0.18.4+` |
| Java | `21` |
| Meteor Client | build for `1.21.11` |

## Installation

1. Install Meteor Client.
2. Download the latest `devils-addon-*.jar`.
3. Put the jar into `.minecraft/mods`.
4. Start the game and open category `Devils` in Meteor.

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
| `mod-auto-updater` | Utility / Migration | One-click modpack migration helper from `1.21.8` to `1.21.11`. Scans local jars, resolves updates via Modrinth/GitHub, and replaces jars with optional backups. |
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

## Mod Auto Updater

- Module: `mod-auto-updater` in category `Devils`.
- Button `Run Update Now` performs one-shot scan/update for `mods` folder.
- Built-in source folder picker:
  - `Select Source Folder` opens a directory chooser and lets you pick old pack `mods` folder (for example from `%APPDATA%` instances).
  - Updater scans that selected source and migrates into the currently running instance `mods`.
- Built-in mod selection:
  - `Select Mods` opens a list of mods detected in source folder with checkboxes.
  - Enable `use-selection-filter` to process only selected mods.
- Runtime defaults are automatic:
  - Minecraft target version is taken from the currently running client.
  - Loader is fixed to `fabric`.
  - Target mods directory is current game instance (`<gameDir>/mods`).
- Source support:
  - Auto-detect from each mod's `fabric.mod.json` URLs (`Modrinth` and `GitHub`).
  - Manual override file: `.minecraft/devils-addon/mod-updater/sources.json`.
  - Resolver priority: `Modrinth` first, then `GitHub` fallback.
  - Built-in resolver overrides for known problematic ids (including `modernfix`, `forgeconfigapiport`, `placeholder-api`, `sspb`, `yacl`, `worldtools`, `jefffmod`).
- Compatibility validation:
  - Update candidates are checked against target runtime (`Minecraft 1.21.11`, `Fabric`) before replacement.
  - Jars with incompatible `fabric.mod.json` constraints are rejected to prevent boot-time crashes.
- Startup rerun:
  - Enable setting `update-rerun` to run one automatic check each client launch.
- Exclusions:
  - By default skips already embedded/ported mods (`devils-addon`, `xaerominimap`, `xaeroworldmap`, `chesttracker`).
- Safety:
  - Optional backup of replaced jars to `.minecraft/devils-addon/mod-updater/backups/<timestamp>/`.
  - Optional `copy-fallback-mods` can copy unresolved mods from source folder directly to target mods folder.
  - `dry-run` mode reports available updates without modifying files.

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

## Release Notes (`v0.0.34`)

- Completed full migration workflow for moving packs from `1.21.8` to `1.21.11` with one-click UI flow (`Select Source Folder` -> `Select Mods` -> `Run Update Now`).
- Reworked updater provider resolution:
  - `Modrinth` is now primary.
  - `GitHub` is fallback.
  - Added robust built-in overrides for known outliers, including `WorldTools` via `SKevo18/VibedWorldTools`.
- Hardened version compatibility validation so updater chooses only jars compatible with the running target (`1.21.11 Fabric`) and blocks incompatible replacements.
- Added detailed progress telemetry and clearer UI labels (`Processed` + `Updated/Up-to-date/Excluded/Unresolved`) for transparent migration status.
- Added persistent update logs (`loge-update.txt` + history logs) with per-mod reasoning for unresolved/skipped entries.
- Stabilized edge cases:
  - Better handling of locked files while client is running.
  - Stronger no-downgrade checks.
  - Correct handling of mods that are already compatible/up-to-date.

## License

Project license: [CC0](/LICENSE).
