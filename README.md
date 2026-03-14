# Devils Addon

Devils Addon is a [Meteor Client](https://github.com/MeteorDevelopment/meteor-client) addon for combat automation, movement tools, world automation, and shared cross-client sync features.

## Download

- Current build (`v0.0.28`): [Download jar](https://github.com/ThianYong-hub/DEVILS/releases/download/v0.0.28/devils-addon-0.0.28.jar)
- Latest release page: [Open](https://github.com/ThianYong-hub/DEVILS/releases/latest)
- All releases: [Open](https://github.com/ThianYong-hub/DEVILS/releases)

## Requirements

| Component | Required Version |
| --- | --- |
| Minecraft | `1.21.8` |
| Fabric Loader | `0.16.14+` |
| Java | `21` |
| Meteor Client | build for `1.21.8` |

## Installation

1. Install Meteor Client.
2. Download the latest `devils-addon-*.jar`.
3. Put the jar into `.minecraft/mods`.
4. Start the game and open category `Devils` in Meteor.

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
- ChestTracker multiplayer banks: `.minecraft/devils-addon/multiplayer/<server>.nbt`
- ChestTracker singleplayer banks: `.minecraft/devils-addon/singleplayer/<world>.nbt`
- Metadata files are stored next to each bank as `.nbt.meta`.
- When SyncHub is enabled for ChestTracker, bank data is synchronized per server namespace.

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

## Release Notes (`v0.0.28`)

- Embedded Xaero stack wired for Devils integration (`Xaero Minimap`, `Xaero World Map`, `XaeroPlus`) with safer loading hooks.
- Reworked Ping/Xaero sync loop with aggressive stream+pull cadence and conflict-safe merge handling.
- Improved managed waypoint rendering pipeline for Devils icons and map labels.
- Added `xaero-sync` module controls and expanded SyncHub feature wiring.

## License

Project license: [CC0](/LICENSE).
