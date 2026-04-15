# Devils Addon

[Meteor Client](https://github.com/MeteorDevelopment/meteor-client)-এর জন্য অ্যাডঅন: PvP, utility এবং Nether highway automation।

## ডাউনলোড

- বর্তমান বিল্ড (`v0.0.51`): [jar ডাউনলোড](https://github.com/ThianYong-hub/DEVILS/releases/download/v0.0.51/devils-addon-0.0.51.jar)
- সর্বশেষ রিলিজ পেজ: [খুলুন](https://github.com/ThianYong-hub/DEVILS/releases/latest)
- সব রিলিজ: [খুলুন](https://github.com/ThianYong-hub/DEVILS/releases)

## প্রয়োজনীয়তা

- Minecraft `1.21.11`
- Fabric Loader `0.16.14+`
- Java `21`
- `1.21.11` এর জন্য Meteor Client

## ইনস্টল

1. Meteor Client ইনস্টল করুন।
2. উপরের লিংক থেকে jar ডাউনলোড করুন।
3. ফাইলটি `.minecraft/mods` এ রাখুন।
4. গেম চালু করে Meteor-এ `Devils` ক্যাটাগরি খুলুন।

## প্রধান মডিউল

- Combat: `auto-cev`, `auto-pearl`, `tnt-bomber`, `lava-bucket`, `mace-spoof`
- Movement: `auto-wasp`, `anti-wasp`, `h-clip`, `v-clip`
- World: `highway-builder-plus`
- Utility/Player: `auto-anvil-rename`, `tracker-player`, `discord-rpc`

## কমান্ড

- `.autoraname setname <text>`
- `.autoraname clearitems`
- `.example`

## ডেভেলপারদের জন্য

```bash
git clone https://github.com/ThianYong-hub/DEVILS.git
cd DEVILS
./gradlew build
```

Windows:

```bat
gradlew.bat build
```

টেস্ট:

```bash
./gradlew test
```


