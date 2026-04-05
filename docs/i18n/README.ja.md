# Devils Addon

[Meteor Client](https://github.com/MeteorDevelopment/meteor-client) 向けアドオン: PvP、ユーティリティ、ネザー高速道路の自動化。

## ダウンロード

- 現在のビルド（`v0.0.44`）: [jar をダウンロード](https://github.com/ThianYong-hub/DEVILS/releases/download/v0.0.45/devils-addon-0.0.45.jar)
- 最新リリース: [開く](https://github.com/ThianYong-hub/DEVILS/releases/latest)
- すべてのリリース: [開く](https://github.com/ThianYong-hub/DEVILS/releases)

## 要件

- Minecraft `1.21.11`
- Fabric Loader `0.16.14+`
- Java `21`
- `1.21.11` 向け Meteor Client

## インストール

1. Meteor Client をインストールします。
2. 上のリンクから jar をダウンロードします。
3. ファイルを `.minecraft/mods` に入れます。
4. ゲームを起動し、Meteor の `Devils` カテゴリを開きます。

## 主なモジュール

- Combat: `auto-cev`, `auto-pearl`, `tnt-bomber`, `lava-bucket`, `mace-spoof`
- Movement: `auto-wasp`, `anti-wasp`, `h-clip`, `v-clip`
- World: `highway-builder-plus`
- Utility/Player: `auto-anvil-rename`, `tracker-player`, `discord-rpc`

## コマンド

- `.autoraname setname <text>`
- `.autoraname clearitems`
- `.example`

## 開発者向け

```bash
git clone https://github.com/ThianYong-hub/DEVILS.git
cd DEVILS
./gradlew build
```

Windows:

```bat
gradlew.bat build
```

テスト:

```bash
./gradlew test
```


