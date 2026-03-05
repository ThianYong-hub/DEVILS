# Devils Addon

Addon para [Meteor Client](https://github.com/MeteorDevelopment/meteor-client): PvP, utilidade e automação avançada de highway no Nether.

## Download

- Build atual (`v0.0.16`): [Baixar jar](https://github.com/ThianYong-hub/DEVILS/releases/download/v0.0.16/devils-addon-0.0.16.jar)
- Último release: [Abrir](https://github.com/ThianYong-hub/DEVILS/releases/latest)
- Todos os releases: [Abrir](https://github.com/ThianYong-hub/DEVILS/releases)

## Requisitos

- Minecraft `1.21.8`
- Fabric Loader `0.16.14+`
- Java `21`
- Meteor Client para `1.21.8`

## Instalação

1. Instale o Meteor Client.
2. Baixe o jar no link acima.
3. Coloque o arquivo em `.minecraft/mods`.
4. Inicie o jogo e abra a categoria `Devils` no Meteor.

## Módulos principais

- Combat: `auto-cev`, `auto-pearl`, `tnt-bomber`, `lava-bucket`, `mace-spoof`
- Movement: `auto-wasp`, `anti-wasp`, `h-clip`, `v-clip`
- World: `highway-builder-plus`
- Utility/Player: `auto-anvil-rename`, `tracker-player`, `discord-rpc`

## Comandos

- `.autoraname setname <text>`
- `.autoraname clearitems`
- `.example`

## Para desenvolvedores

```bash
git clone https://github.com/ThianYong-hub/DEVILS.git
cd DEVILS
./gradlew build
```

Windows:

```bat
gradlew.bat build
```

Testes:

```bash
./gradlew test
```
