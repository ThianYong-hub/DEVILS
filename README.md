# Devils Addon

Addon for [Meteor Client](https://github.com/MeteorDevelopment/meteor-client): PvP, movement, utility, and advanced Nether highway automation.

## Language

- English (current page)
- [中文（普通话）](/docs/i18n/README.zh-CN.md)
- [हिन्दी](/docs/i18n/README.hi.md)
- [Español](/docs/i18n/README.es.md)
- [العربية](/docs/i18n/README.ar.md)
- [Français](/docs/i18n/README.fr.md)
- [বাংলা](/docs/i18n/README.bn.md)
- [Português](/docs/i18n/README.pt.md)
- [Русский](/docs/i18n/README.ru.md)
- [Bahasa Indonesia](/docs/i18n/README.id.md)
- [اردو](/docs/i18n/README.ur.md)
- [Deutsch](/docs/i18n/README.de.md)
- [日本語](/docs/i18n/README.ja.md)
- [Nigerian Pidgin](/docs/i18n/README.pcm.md)
- [Українська](/docs/i18n/README.uk.md)

## Download

- Current build (`v0.0.23`): [Download jar](https://github.com/ThianYong-hub/DEVILS/releases/download/v0.0.23/devils-addon-0.0.23.jar)
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

## License

Project license: [CC0](/LICENSE).
