# Devils Addon

Addon for [Meteor Client](https://github.com/MeteorDevelopment/meteor-client): PvP, movement, utility, and advanced Nether highway automation.

## Download

- Current build (`v0.0.15`): [Download jar](https://github.com/ThianYong-hub/DEVILS/releases/download/v0.0.15/devils-addon-0.0.15.jar)
- Latest release page: [Open](https://github.com/ThianYong-hub/DEVILS/releases/latest)
- All releases: [Open](https://github.com/ThianYong-hub/DEVILS/releases)

## Requirements

- Minecraft `1.21.8`
- Fabric Loader `0.16.14+`
- Java `21`
- Meteor Client for `1.21.8`

## Install

1. Install Meteor Client.
2. Download the addon jar from the link above.
3. Move it to `.minecraft/mods`.
4. Launch the game and open the `Devils` category in Meteor.

## Main Modules

- Combat: `auto-cev`, `auto-pearl`, `tnt-bomber`, `lava-bucket`, `mace-spoof`
- Movement: `auto-wasp`, `anti-wasp`, `h-clip`, `v-clip`
- World: `highway-builder-plus`
- Utility/Player: `auto-anvil-rename`, `tracker-player`, `discord-rpc`

## Commands

- `.autoraname setname <text>`
- `.autoraname clearitems`
- `.example`

## For Developers

```bash
git clone https://github.com/ThianYong-hub/DEVILS.git
cd DEVILS
./gradlew build
```

Windows:

```bat
gradlew.bat build
```

Tests:

```bash
./gradlew test
```
