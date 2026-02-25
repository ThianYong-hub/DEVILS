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
Each rule stores separate sound values for `Join`, `Leave`, and `Death` under the same source mode.
`Both` means only `Join + Leave` (it does not include `Death`).

Sound workflow:
- Put `.ogg` files inside `<gameDir>/devils-addon/sounds` (subfolders supported)
- Use `Refresh Sounds` and select per-event sounds (`J/L/D`) in the rule
- Or pick an existing game sound from selector
- Or use manual sound id for custom packs/mod integrations
- If event-specific sound value is empty/invalid, the module falls back to `default-sound`, then to built-in fallback sound

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

## Release Process

Manual-only release flow:
- Feature/PR pipelines run tests and build artifacts only (no tag and no GitHub Release).
- Merge to `main` does not create tags and does not publish releases automatically.
- Stable release is created manually through `Actions -> Manual Release Tag -> Run workflow`.
- Leave `version` empty to auto-increment PATCH (`Z`) from the latest `vX.Y.Z`.
- Set `version` manually to `vX.Y.Z` when you want explicit MINOR/MAJOR.

Stable version source:
- Stable builds use Git tag as source of truth (`APP_VERSION` from tag).
- `mod_version` in `gradle.properties` is fallback for local builds only.

Workflow chaining note:
- Manual tag workflow triggers `release-on-tag` explicitly via `workflow_dispatch`.
- No additional PAT secret is required; built-in `GITHUB_TOKEN` is used.
- `release-auto-patch` remains in repository in disabled/manual mode for optional future use.
- Plain external tag push does not auto-publish release unless `release-on-tag` is dispatched.

## Authors

- **23XT**
- **SOCKETLOST**

## License

This project is available under the CC0 license.
