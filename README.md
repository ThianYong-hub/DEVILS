<p align="center">
  <img src="https://raw.githubusercontent.com/ThianYong-hub/DEVILS/main/src/main/resources/assets/devils-addon/icon.png" alt="Devils Addon" width="128" height="128">
</p>

<h1 align="center">Devils Addon</h1>

<p align="center">
  A feature-rich addon for <a href="https://github.com/MeteorDevelopment/meteor-client">Meteor Client</a> focused on combat, automation, and utility modules for Minecraft 1.21.6–1.21.8.
</p>

<p align="center">
  <a href="https://github.com/ThianYong-hub/DEVILS/releases"><img src="https://img.shields.io/github/v/release/ThianYong-hub/DEVILS?style=flat-square&color=blue" alt="Release"></a>
  <a href="https://github.com/ThianYong-hub/DEVILS/actions"><img src="https://img.shields.io/github/actions/workflow/status/ThianYong-hub/DEVILS/dev_build.yml?style=flat-square" alt="Build"></a>
  <a href="https://github.com/ThianYong-hub/DEVILS/blob/main/LICENSE"><img src="https://img.shields.io/github/license/ThianYong-hub/DEVILS?style=flat-square" alt="License"></a>
</p>

---

## Installation

1. Install [Meteor Client](https://meteorclient.com/) for your Minecraft version.
2. Download the latest JAR from [Releases](https://github.com/ThianYong-hub/DEVILS/releases).
3. Place the JAR in your `mods` folder.
4. Launch the game — modules appear under the **Devils** category.

## Modules

### Combat

| Module | Description |
|--------|-------------|
| **AutoCev** | Automatically places obsidian, end crystals and breaks the base to damage nearby players. |
| **AutoPearl** | Locks onto a target and chases with ender pearls. Supports multi-bot orbit formations and remote chat commands (`!pearl <nick>`, `!pearl on/off`). |
| **TnTBomber** | Traps target in an obsidian box and bombs them with TNT. |
| **LavaBucket** | Automatically places and collects lava buckets on nearby players. |

### Movement

| Module | Description |
|--------|-------------|
| **AutoWasp** | Follows a target with elytra using obstacle-aware routing, movement prediction, and friend filtering. |
| **AntiWasp** | Evasive elytra flight along geometric figures (circle, square, triangle) to dodge pursuers. Includes obstacle avoidance with raycasting and firework boost support. |
| **HClip** | Shifts into block corners when surround is mined to block crystal placement. |
| **VClip** | Instantly clips you vertically by a configured distance. |

### World

| Module | Description |
|--------|-------------|
| **HighwayBuilder** | Automatically builds highways, tunnels, and flat paths in the Nether. Features EChest mining for obsidian farming, Baritone integration, multi-block placement, blueprint system, and auto-liquid handling. |

### Player

| Module | Description |
|--------|-------------|
| **AutoAnvilRename** | Automatically renames items in an open anvil GUI. Supports item/shulker box filters and auto XP bottle usage. |
| **TrackerPlayer** | Universal per-player tracker with join/leave/death rules, custom sound playback (local `.ogg` files, game sounds, or manual IDs), and optional chat command execution. |
| **DiscordRPC** | Shows Devils Addon branding in Discord status. |

## Commands

| Command | Usage | Description |
|---------|-------|-------------|
| `.autoraname setname <text>` | Sets the rename text for AutoAnvilRename | |
| `.autoraname clearitems` | Clears the item filter list | |

## Building from Source

```bash
git clone https://github.com/ThianYong-hub/DEVILS.git
cd DEVILS
./gradlew build
```

The built JAR will be in `build/libs/`.

## Authors

- **23XT**
- **SOCKETLOST**

## License

This project is available under the [CC0](LICENSE) license.
