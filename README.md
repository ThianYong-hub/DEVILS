<p align="center">
  <img src="src/main/resources/assets/devils-addon/icon.png" alt="Devils Addon" width="220" height="220">
</p>

<h1 align="center">Devils Addon</h1>

<p align="center">
  Addon for <a href="https://github.com/MeteorDevelopment/meteor-client">Meteor Client</a>: PvP, movement, utility, and advanced Nether highway automation.
</p>

---

## Language

- [English](#english)
- [Русский](#русский)
- [Українська](#українська)

---

## English

### Quick Download

- Latest build (v0.0.15): [Download](https://github.com/ThianYong-hub/DEVILS/releases/download/latest/devils-addon-0.0.15.jar)
- Latest release: [Open](https://github.com/ThianYong-hub/DEVILS/releases/latest)
- All releases: [Open](https://github.com/ThianYong-hub/DEVILS/releases)

### Requirements

- Minecraft: `1.21.8`
- Fabric Loader: `0.16.14+`
- Java: `21`
- Meteor Client for `1.21.8`

### Install

1. Install Meteor Client.
2. Download `devils-addon-latest.jar` from the direct link above.
3. Put it in `.minecraft/mods`.
4. Launch game and open Meteor category `Devils`.

### Modules

- Combat: `auto-cev`, `auto-pearl`, `tnt-bomber`, `lava-bucket`, `mace-spoof`
- Movement: `auto-wasp`, `anti-wasp`, `h-clip`, `v-clip`
- World: `highway-builder-plus`
- Utility/Player: `auto-anvil-rename`, `tracker-player`, `discord-rpc`

### Commands

- `.autoraname setname <text>`
- `.autoraname clearitems`
- `.example`

### For Developers

Build:

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

Artifact:

- `build/libs/devils-addon-<version>.jar`

Versioning behavior:

- Release builds use tag version (`vX.Y.Z` -> `X.Y.Z`).
- Local builds auto-resolve version from git tags.
- Stable fallback comes from `gradle.properties` (`mod_version`).

---

## Русский

### Быстрое скачивание

- Latest build (v0.0.15): [Скачать](https://github.com/ThianYong-hub/DEVILS/releases/download/latest/devils-addon-0.0.15.jar)
- Последний релиз: [Открыть](https://github.com/ThianYong-hub/DEVILS/releases/latest)
- Все релизы: [Открыть](https://github.com/ThianYong-hub/DEVILS/releases)

### Требования

- Minecraft: `1.21.8`
- Fabric Loader: `0.16.14+`
- Java: `21`
- Meteor Client для `1.21.8`

### Установка

1. Установи Meteor Client.
2. Скачай `devils-addon-latest.jar` по прямой ссылке выше.
3. Помести файл в `.minecraft/mods`.
4. Запусти игру и открой категорию `Devils` в Meteor.

### Модули

- Combat: `auto-cev`, `auto-pearl`, `tnt-bomber`, `lava-bucket`, `mace-spoof`
- Movement: `auto-wasp`, `anti-wasp`, `h-clip`, `v-clip`
- World: `highway-builder-plus`
- Utility/Player: `auto-anvil-rename`, `tracker-player`, `discord-rpc`

### Команды

- `.autoraname setname <text>`
- `.autoraname clearitems`
- `.example`

### Для разработчиков

Сборка:

```bash
./gradlew build
```

Тесты:

```bash
./gradlew test
```

Артефакт:

- `build/libs/devils-addon-<version>.jar`

Версионирование:

- В релизах версия берется из тега `vX.Y.Z`.
- Локально версия берется из git-тегов автоматически.
- Резервная версия хранится в `gradle.properties` (`mod_version`).

---

## Українська

### Швидке завантаження

- Latest build (v0.0.15): [Завантажити](https://github.com/ThianYong-hub/DEVILS/releases/download/latest/devils-addon-0.0.15.jar)
- Останній реліз: [Відкрити](https://github.com/ThianYong-hub/DEVILS/releases/latest)
- Усі релізи: [Відкрити](https://github.com/ThianYong-hub/DEVILS/releases)

### Вимоги

- Minecraft: `1.21.8`
- Fabric Loader: `0.16.14+`
- Java: `21`
- Meteor Client для `1.21.8`

### Встановлення

1. Встанови Meteor Client.
2. Завантаж `devils-addon-latest.jar` за прямим посиланням вище.
3. Поклади файл у `.minecraft/mods`.
4. Запусти гру та відкрий категорію `Devils` у Meteor.

### Модулі

- Combat: `auto-cev`, `auto-pearl`, `tnt-bomber`, `lava-bucket`, `mace-spoof`
- Movement: `auto-wasp`, `anti-wasp`, `h-clip`, `v-clip`
- World: `highway-builder-plus`
- Utility/Player: `auto-anvil-rename`, `tracker-player`, `discord-rpc`

### Команди

- `.autoraname setname <text>`
- `.autoraname clearitems`
- `.example`

### Для розробників

Збірка:

```bash
./gradlew build
```

Тести:

```bash
./gradlew test
```

Артефакт:

- `build/libs/devils-addon-<version>.jar`

Версіонування:

- У релізах версія береться з тегу `vX.Y.Z`.
- Локально версія автоматично береться з git-тегів.
- Резервна версія зберігається в `gradle.properties` (`mod_version`).

---

## License

Project license: [CC0](LICENSE).
