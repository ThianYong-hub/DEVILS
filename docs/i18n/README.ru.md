# Devils Addon

Аддон для [Meteor Client](https://github.com/MeteorDevelopment/meteor-client): PvP, utility и автоматизация строительства магистралей в Незере.

## Скачать

- Текущая сборка (`v0.0.24`): [Скачать jar](https://github.com/ThianYong-hub/DEVILS/releases/download/v0.0.24/devils-addon-0.0.24.jar)
- Последний релиз: [Открыть](https://github.com/ThianYong-hub/DEVILS/releases/latest)
- Все релизы: [Открыть](https://github.com/ThianYong-hub/DEVILS/releases)

## Требования

- Minecraft `1.21.8`
- Fabric Loader `0.16.14+`
- Java `21`
- Meteor Client для `1.21.8`

## Установка

1. Установи Meteor Client.
2. Скачай jar по ссылке выше.
3. Перемести файл в `.minecraft/mods`.
4. Запусти игру и открой категорию `Devils` в Meteor.

## Основные модули

- Combat: `auto-cev`, `auto-pearl`, `tnt-bomber`, `lava-bucket`, `mace-spoof`
- Movement: `auto-wasp`, `anti-wasp`, `h-clip`, `v-clip`
- World: `highway-builder-plus`
- Utility/Player: `auto-anvil-rename`, `tracker-player`, `discord-rpc`

## Команды

- `.autoraname setname <text>`
- `.autoraname clearitems`
- `.example`

## Для разработчиков

```bash
git clone https://github.com/ThianYong-hub/DEVILS.git
cd DEVILS
./gradlew build
```

Windows:

```bat
gradlew.bat build
```

Тесты:

```bash
./gradlew test
```
