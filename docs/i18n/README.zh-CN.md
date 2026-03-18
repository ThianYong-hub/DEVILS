# Devils Addon

适用于 [Meteor Client](https://github.com/MeteorDevelopment/meteor-client) 的附加模组：PvP、实用功能与地狱高速自动化。

## 下载

- 当前构建（`v0.0.15`）：[下载 jar](https://github.com/ThianYong-hub/DEVILS/releases/download/v0.0.34/devils-addon-0.0.34.jar)
- 最新发布页：[打开](https://github.com/ThianYong-hub/DEVILS/releases/latest)
- 全部发布：[打开](https://github.com/ThianYong-hub/DEVILS/releases)

## 需求

- Minecraft `1.21.8`
- Fabric Loader `0.16.14+`
- Java `21`
- 适配 `1.21.8` 的 Meteor Client

## 安装

1. 安装 Meteor Client。
2. 从上方链接下载 jar。
3. 将文件放入 `.minecraft/mods`。
4. 启动游戏，在 Meteor 中打开 `Devils` 分类。

## 主要模块

- Combat: `auto-cev`, `auto-pearl`, `tnt-bomber`, `lava-bucket`, `mace-spoof`
- Movement: `auto-wasp`, `anti-wasp`, `h-clip`, `v-clip`
- World: `highway-builder-plus`
- Utility/Player: `auto-anvil-rename`, `tracker-player`, `discord-rpc`

## 命令

- `.autoraname setname <text>`
- `.autoraname clearitems`
- `.example`

## 开发者

```bash
git clone https://github.com/ThianYong-hub/DEVILS.git
cd DEVILS
./gradlew build
```

Windows:

```bat
gradlew.bat build
```

测试：

```bash
./gradlew test
```
