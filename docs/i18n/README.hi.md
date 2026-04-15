# Devils Addon

[Meteor Client](https://github.com/MeteorDevelopment/meteor-client) के लिए ऐडऑन: PvP, utility और Nether highway automation।

## डाउनलोड

- वर्तमान बिल्ड (`v0.0.50`): [jar डाउनलोड करें](https://github.com/ThianYong-hub/DEVILS/releases/download/v0.0.50/devils-addon-0.0.50.jar)
- नवीनतम रिलीज़ पेज: [खोलें](https://github.com/ThianYong-hub/DEVILS/releases/latest)
- सभी रिलीज़: [खोलें](https://github.com/ThianYong-hub/DEVILS/releases)

## आवश्यकताएँ

- Minecraft `1.21.11`
- Fabric Loader `0.16.14+`
- Java `21`
- `1.21.11` के लिए Meteor Client

## इंस्टॉल

1. Meteor Client इंस्टॉल करें।
2. ऊपर दिए गए लिंक से jar डाउनलोड करें।
3. फाइल को `.minecraft/mods` में रखें।
4. गेम चालू करें और Meteor में `Devils` कैटेगरी खोलें।

## मुख्य मॉड्यूल

- Combat: `auto-cev`, `auto-pearl`, `tnt-bomber`, `lava-bucket`, `mace-spoof`
- Movement: `auto-wasp`, `anti-wasp`, `h-clip`, `v-clip`
- World: `highway-builder-plus`
- Utility/Player: `auto-anvil-rename`, `tracker-player`, `discord-rpc`

## कमांड

- `.autoraname setname <text>`
- `.autoraname clearitems`
- `.example`

## डेवलपर्स के लिए

```bash
git clone https://github.com/ThianYong-hub/DEVILS.git
cd DEVILS
./gradlew build
```

Windows:

```bat
gradlew.bat build
```

टेस्ट:

```bash
./gradlew test
```


