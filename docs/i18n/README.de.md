# Devils Addon

Addon für [Meteor Client](https://github.com/MeteorDevelopment/meteor-client): PvP, Utility und fortgeschrittene Nether-Highway-Automatisierung.

## Download

- Aktueller Build (`v0.0.21`): [Jar herunterladen](https://github.com/ThianYong-hub/DEVILS/releases/download/v0.0.21/devils-addon-0.0.21.jar)
- Letztes Release: [Öffnen](https://github.com/ThianYong-hub/DEVILS/releases/latest)
- Alle Releases: [Öffnen](https://github.com/ThianYong-hub/DEVILS/releases)

## Anforderungen

- Minecraft `1.21.8`
- Fabric Loader `0.16.14+`
- Java `21`
- Meteor Client für `1.21.8`

## Installation

1. Meteor Client installieren.
2. Jar über den obigen Link herunterladen.
3. Datei nach `.minecraft/mods` verschieben.
4. Spiel starten und in Meteor die Kategorie `Devils` öffnen.

## Hauptmodule

- Combat: `auto-cev`, `auto-pearl`, `tnt-bomber`, `lava-bucket`, `mace-spoof`
- Movement: `auto-wasp`, `anti-wasp`, `h-clip`, `v-clip`
- World: `highway-builder-plus`
- Utility/Player: `auto-anvil-rename`, `tracker-player`, `discord-rpc`

## Befehle

- `.autoraname setname <text>`
- `.autoraname clearitems`
- `.example`

## Für Entwickler

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
