# Devils Addon

Addon untuk [Meteor Client](https://github.com/MeteorDevelopment/meteor-client): PvP, utilitas, dan otomasi highway Nether tingkat lanjut.

## Unduh

- Build saat ini (`v0.0.29`): [Unduh jar](https://github.com/ThianYong-hub/DEVILS/releases/download/v0.0.29/devils-addon-0.0.29.jar)
- Rilis terbaru: [Buka](https://github.com/ThianYong-hub/DEVILS/releases/latest)
- Semua rilis: [Buka](https://github.com/ThianYong-hub/DEVILS/releases)

## Persyaratan

- Minecraft `1.21.8`
- Fabric Loader `0.16.14+`
- Java `21`
- Meteor Client untuk `1.21.8`

## Instalasi

1. Install Meteor Client.
2. Unduh file jar dari tautan di atas.
3. Pindahkan file ke `.minecraft/mods`.
4. Jalankan game dan buka kategori `Devils` di Meteor.

## Modul utama

- Combat: `auto-cev`, `auto-pearl`, `tnt-bomber`, `lava-bucket`, `mace-spoof`
- Movement: `auto-wasp`, `anti-wasp`, `h-clip`, `v-clip`
- World: `highway-builder-plus`
- Utility/Player: `auto-anvil-rename`, `tracker-player`, `discord-rpc`

## Perintah

- `.autoraname setname <text>`
- `.autoraname clearitems`
- `.example`

## Untuk developer

```bash
git clone https://github.com/ThianYong-hub/DEVILS.git
cd DEVILS
./gradlew build
```

Windows:

```bat
gradlew.bat build
```

Tes:

```bash
./gradlew test
```
