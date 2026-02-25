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

### TrackerPlayer
Tracks players with fully per-player rules. Each rule has player name, event mode (`Join`, `Leave`, `Both`, `Death`), sound toggle, send toggle, command text, per-player chat send delay (ms), and sound source mode (`Local folder`, `Game sound`, `Manual ID`).
`Both` means only `Join + Leave` (it does not include `Death`).

Sound workflow:
- Put `.ogg` files inside `<gameDir>/devils-addon/sounds` (subfolders supported)
- Use `Refresh Sounds` and select from dropdown in the rule
- Or pick an existing game sound from selector
- Or use manual sound id for custom packs/mod integrations

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
