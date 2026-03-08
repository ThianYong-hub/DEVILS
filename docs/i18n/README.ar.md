# Devils Addon

إضافة لـ [Meteor Client](https://github.com/MeteorDevelopment/meteor-client): PvP وأدوات مساعدة وأتمتة بناء الطرق في الـNether.

## التنزيل

- الإصدار الحالي (`v0.0.22`): [تنزيل jar](https://github.com/ThianYong-hub/DEVILS/releases/download/v0.0.22/devils-addon-0.0.22.jar)
- أحدث إصدار: [فتح](https://github.com/ThianYong-hub/DEVILS/releases/latest)
- جميع الإصدارات: [فتح](https://github.com/ThianYong-hub/DEVILS/releases)

## المتطلبات

- Minecraft `1.21.8`
- Fabric Loader `0.16.14+`
- Java `21`
- Meteor Client لإصدار `1.21.8`

## التثبيت

1. ثبّت Meteor Client.
2. نزّل ملف jar من الرابط أعلاه.
3. انقل الملف إلى `.minecraft/mods`.
4. شغّل اللعبة وافتح قسم `Devils` داخل Meteor.

## الوحدات الأساسية

- Combat: `auto-cev`, `auto-pearl`, `tnt-bomber`, `lava-bucket`, `mace-spoof`
- Movement: `auto-wasp`, `anti-wasp`, `h-clip`, `v-clip`
- World: `highway-builder-plus`
- Utility/Player: `auto-anvil-rename`, `tracker-player`, `discord-rpc`

## الأوامر

- `.autoraname setname <text>`
- `.autoraname clearitems`
- `.example`

## للمطورين

```bash
git clone https://github.com/ThianYong-hub/DEVILS.git
cd DEVILS
./gradlew build
```

Windows:

```bat
gradlew.bat build
```

الاختبارات:

```bash
./gradlew test
```
