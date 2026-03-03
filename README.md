<p align="center">
  <img src="src/main/resources/assets/devils-addon/icon.png" alt="Devils Addon" width="400" height="400">
</p>

<h1 align="center">Devils Addon</h1>

<p align="center">
  Addon for <a href="https://github.com/MeteorDevelopment/meteor-client">Meteor Client</a> with PvP, movement, automation and highway-building modules.
</p>

## Current State

- Minecraft: `1.21.8`
- Fabric Loader: `0.16.14`
- Java: `21`
- Addon category in Meteor: `Devils`
- Main repository: <https://github.com/ThianYong-hub/DEVILS>
- Releases: <https://github.com/ThianYong-hub/DEVILS/releases>

## Installation

1. Install Meteor Client for Minecraft `1.21.8`.
2. Download the latest `devils-addon-*.jar` from Releases.
3. Put the file into your `.minecraft/mods` folder.
4. Start the game and open Meteor modules category `Devils`.

## Modules

### Combat

| Module | Description |
|--------|-------------|
| `auto-cev` | Automatically places obsidian/end crystals and breaks the base to damage nearby players. |
| `auto-pearl` | Locks onto a target and chases with ender pearls, supports remote chat command `!pearl <nick>`. |
| `tnt-bomber` | Traps a target in obsidian and bombs with TNT. |
| `lava-bucket` | Automatically places and collects lava buckets on nearby players. |
| `mace-spoof` | Spoofs fall distance to amplify mace damage. |

### Movement

| Module | Description |
|--------|-------------|
| `auto-wasp` | Follows a target with elytra using obstacle-aware routing. |
| `anti-wasp` | Elytra evasion patterns (circle/square/triangle) to evade wasp attacks. |
| `h-clip` | Shifts into block corners when surround is mined. |
| `v-clip` | Instantly clips vertically by configured distance. |

### World

| Module | Description |
|--------|-------------|
| `highway-builder-plus` | Builds highways, tunnels and flat paths in the Nether with blueprint/task system, restock from shulkers, EChest miner and Baritone-assisted movement. |

### Utility / Player

| Module | Description |
|--------|-------------|
| `auto-anvil-rename` | Automatically renames items in an open anvil and manages input/output flow. |
| `tracker-player` | Per-player join/leave/death tracker with configurable sound alerts and optional delayed chat send. |
| `discord-rpc` | Shows Devils Addon presence in Discord. |

## Commands

| Command | Description |
|---------|-------------|
| `.autoraname setname <text>` | Set rename text used by `auto-anvil-rename`. |
| `.autoraname clearitems` | Clear item filter list for `auto-anvil-rename`. |
| `.example` | Test command from addon template. |

## Build from Source

```bash
git clone https://github.com/ThianYong-hub/DEVILS.git
cd DEVILS
./gradlew build
```

Windows:

```bat
gradlew.bat build
```

Output artifact:

- `build/libs/devils-addon-<version>.jar`

## License

Project license: [CC0](LICENSE).
