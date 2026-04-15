# Devils Addon

Addon pour [Meteor Client](https://github.com/MeteorDevelopment/meteor-client) : PvP, utilitaires et automatisation avancée de highway dans le Nether.

## Téléchargement

- Build actuelle (`v0.0.51`) : [Télécharger le jar](https://github.com/ThianYong-hub/DEVILS/releases/download/v0.0.51/devils-addon-0.0.51.jar)
- Dernière release : [Ouvrir](https://github.com/ThianYong-hub/DEVILS/releases/latest)
- Toutes les releases : [Ouvrir](https://github.com/ThianYong-hub/DEVILS/releases)

## Prérequis

- Minecraft `1.21.11`
- Fabric Loader `0.16.14+`
- Java `21`
- Meteor Client pour `1.21.11`

## Installation

1. Installe Meteor Client.
2. Télécharge le jar via le lien ci-dessus.
3. Place le fichier dans `.minecraft/mods`.
4. Lance le jeu et ouvre la catégorie `Devils` dans Meteor.

## Modules principaux

- Combat: `auto-cev`, `auto-pearl`, `tnt-bomber`, `lava-bucket`, `mace-spoof`
- Movement: `auto-wasp`, `anti-wasp`, `h-clip`, `v-clip`
- World: `highway-builder-plus`
- Utility/Player: `auto-anvil-rename`, `tracker-player`, `discord-rpc`

## Commandes

- `.autoraname setname <text>`
- `.autoraname clearitems`
- `.example`

## Pour les développeurs

```bash
git clone https://github.com/ThianYong-hub/DEVILS.git
cd DEVILS
./gradlew build
```

Windows:

```bat
gradlew.bat build
```

Tests :

```bash
./gradlew test
```


