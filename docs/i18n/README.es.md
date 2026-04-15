# Devils Addon

Addon para [Meteor Client](https://github.com/MeteorDevelopment/meteor-client): PvP, utilidades y automatización avanzada de highways en el Nether.

## Descarga

- Build actual (`v0.0.51`): [Descargar jar](https://github.com/ThianYong-hub/DEVILS/releases/download/v0.0.51/devils-addon-0.0.51.jar)
- Último release: [Abrir](https://github.com/ThianYong-hub/DEVILS/releases/latest)
- Todos los releases: [Abrir](https://github.com/ThianYong-hub/DEVILS/releases)

## Requisitos

- Minecraft `1.21.11`
- Fabric Loader `0.16.14+`
- Java `21`
- Meteor Client para `1.21.11`

## Instalación

1. Instala Meteor Client.
2. Descarga el jar desde el enlace de arriba.
3. Muévelo a `.minecraft/mods`.
4. Inicia el juego y abre la categoría `Devils` en Meteor.

## Módulos principales

- Combat: `auto-cev`, `auto-pearl`, `tnt-bomber`, `lava-bucket`, `mace-spoof`
- Movement: `auto-wasp`, `anti-wasp`, `h-clip`, `v-clip`
- World: `highway-builder-plus`
- Utility/Player: `auto-anvil-rename`, `tracker-player`, `discord-rpc`

## Comandos

- `.autoraname setname <text>`
- `.autoraname clearitems`
- `.example`

## Para desarrolladores

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


