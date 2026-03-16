# 1.21.11 Test Port

Sandbox path: `Source 1.21.11/devils-addon-1.21.11-branch`
Built jar: `Source 1.21.11/devils-addon-1.21.11-test.jar`
Source zip: `Source 1.21.11/devils-addon-1.21.11-test.zip`
Date: 2026-03-16

## Main addon status
- Main addon test port builds on Minecraft `1.21.11`.
- Verified commands:
  - `./gradlew.bat compileJava -x test`
  - `./gradlew.bat clean build`
- Embedded dependency versions updated to:
  - Fabric Loader `0.18.2`
  - Fabric API `0.140.0+1.21.11`
  - Yarn `1.21.11+build.3`
  - Xaero Minimap `fabric-1.21.11-25.3.10`
  - Xaero World Map `fabric-1.21.11-1.40.11`
  - XaeroPlus `2.30.9+fabric-1.21.11`

## Code changes applied
- Meteor input migration:
  - `MouseButtonEvent` -> `MouseClickEvent`
  - `event.button` -> `event.button()`
  - `KeyEvent` uses `event.key()` and `event.modifiers()`
- Mojang authlib profile migration:
  - `GameProfile#getName()` -> `name()`
  - `GameProfile#getId()` -> `id()`
- Camera access migration:
  - `MinecraftClient.cameraEntity` -> `MinecraftClient#getCameraEntity()`
- Entity position compatibility:
  - added `com.example.addon.util.EntityPositionCompat`
  - replaced broken `entity.getPos()` calls with compatibility helper in the test port
- Reflection/mixin adjustments:
  - updated affected `GameProfile` target descriptor and reflection checks
- Embedded ChestTracker migration:
  - replaced the broken legacy `chesttracker-port` bundle with a dedicated `chesttracker-port-1.21.11`
  - repackaged the upstream `ChestTracker` `1.21.11` client source into the Devils namespace
  - restored Devils runtime gating, storage layout migration, GUI theme hooks, and optional compat mixins
  - sandbox `jar` build now rebuilds and embeds the new ChestTracker jar automatically

## Known limitations
- This is a build-tested port, not a runtime-verified release.
- I did not launch Minecraft with this sandbox jar.
- Jar version naming still comes from the repo git-version plugin, so the produced filename is not the `mod_version` string.

## ChestTracker status
- Active bundled module: `chesttracker-port-1.21.11`
- Verified commands:
  - `./gradlew.bat -p chesttracker-port-1.21.11 clean build`
  - `./gradlew.bat clean build`
- Included custom Devils behavior:
  - runtime on/off control through `ChestTrackerRuntimeState`
  - Devils-styled GUI overlays and config toggles
  - locked NBT storage layout under `.minecraft/devils-addon/...`
  - optional compat entrypoints and mixins for Where Is It, Jade, WTHIT, ModMenu, and Shulker Box Tooltip
- Legacy `chesttracker-port` remains only as reference material inside the sandbox and is not used by the final `1.21.11` test jar.
