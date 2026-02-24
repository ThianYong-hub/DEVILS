# Devils Addon

A Meteor Client addon for Minecraft 1.21.6–1.21.8.

## Modules

### AutoWasp
Automatically flies toward a target player using elytra. Features movement prediction, landing avoidance, friend filtering, and configurable actions on target loss (toggle, find new target, or disconnect).

### AntiWasp
Evasive elytra flight along geometric figures (circle, square, triangle) to dodge pursuers. Includes obstacle avoidance with raycasting, automatic figure cycling, firework boost support, smooth camera rotation, and automatic elytra recovery if flight is interrupted.

### AutoPearl
Locks onto a target and chases them with ender pearls. Simulates pearl trajectories to calculate optimal throw angles. Supports multi-bot orbit formations (up to 4 bots approaching from different sides), platform descent, stuck detection with auto-breakout, and remote chat commands (`!pearl <nick>`, `!pearl auto`, `!pearl on/off`).

### AutoAnvilRename
Automatically renames items in an open anvil GUI. Pulls items from inventory, sends rename packets, and collects output. Supports item/shulker box filters, auto XP bottle usage, and stuck detection.

## Commands

| Command | Usage | Description |
|---------|-------|-------------|
| `autoraname` | `.autoraname setname <text>` | Sets the rename text for AutoAnvilRename |
| `autoraname` | `.autoraname clearitems` | Clears the item filter list |

## Installation

1. Download the latest JAR from [Releases](https://github.com/ThianYong-hub/DEVILS/releases)
2. Place it in your `mods` folder alongside Meteor Client
3. Launch the game

## Building from source

```bash
git clone https://github.com/ThianYong-hub/DEVILS
cd DEVILS
./gradlew build
```

The built JAR will be in `build/libs/`.

## Authors

- **23XT**
- **SOCKETLOST**

## License

This project is available under the CC0 license.
