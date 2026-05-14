# Devils Addon

Аддон для [Meteor Client](https://github.com/MeteorDevelopment/meteor-client): PvP, utility та автоматизація побудови магістралей у Незері.

## Завантажити

- Поточна збірка (`v0.0.54`): [Завантажити jar](https://github.com/ThianYong-hub/DEVILS/releases/download/v0.0.56/devils-addon-0.0.56.jar)
- Останній реліз: [Відкрити](https://github.com/ThianYong-hub/DEVILS/releases/latest)
- Усі релізи: [Відкрити](https://github.com/ThianYong-hub/DEVILS/releases)

## Вимоги

- Minecraft `1.21.11`
- Fabric Loader `0.16.14+`
- Java `21`
- Meteor Client для `1.21.11`

## Встановлення

1. Встанови Meteor Client.
2. Завантаж jar за посиланням вище.
3. Перемісти файл у `.minecraft/mods`.
4. Запусти гру та відкрий категорію `Devils` у Meteor.

## Основні модулі

- Combat: `auto-cev`, `auto-pearl`, `tnt-bomber`, `lava-bucket`, `mace-spoof`
- Movement: `auto-wasp`, `anti-wasp`, `h-clip`, `v-clip`
- World: `highway-builder-plus`
- Utility/Player: `auto-anvil-rename`, `tracker-player`, `discord-rpc`

## Команди

- `.autoraname setname <text>`
- `.autoraname clearitems`
- `.example`

## Для розробників

```bash
git clone https://github.com/ThianYong-hub/DEVILS.git
cd DEVILS
./gradlew build
```

Windows:

```bat
gradlew.bat build
```

Тести:

```bash
./gradlew test
```


