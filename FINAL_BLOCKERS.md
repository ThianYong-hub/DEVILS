# Final Public Release Blockers

## Security Blockers

- No real secrets were identified in the checked tracked files during this pass.
- `SyncHub/.env` remains a local ignored file and must not be committed.

## Reproducible Build Status

- A clean temporary checkout was built without the external local source dump.
- The external source dump was not copied into the repository.
- The build produced both root release jars from the clean checkout:
  - `build/libs/devils-addon-0.0.57.jar`
  - `build/libs/devils-game-0.0.4.jar`

## License / Redistribution Blockers

- `devils-addon` currently bundles classes/resources from the Xaero integration family into the release jar.
- The generated third-party notice for the addon identifies `XaeroLib`, `Xaero's Minimap`, and `Xaero's World Map` as `All Rights Reserved`.
- `devils-addon/src/main/resources/fabric.mod.json` also lists `All Rights Reserved` in the addon license metadata because those bundled integration components are present.
- The release jar contains `xaero/` and `xaeroplus/` class trees, so this is not only documentation wording; redistributed artifacts include upstream integration payload.

## Evidence

- `devils-addon/build.gradle.kts` extracts source-native runtime classes from remapped dependency jars and the vendored XaeroLib helper jar.
- `devils-addon/build.gradle.kts` generates `META-INF/licenses/THIRD_PARTY_NOTICES.txt` with an explicit `All Rights Reserved` notice for the Xaero family.
- `devils-addon/src/main/resources/fabric.mod.json` provides and initializes Xaero-related entrypoints.
- `build/libs/devils-addon-0.0.57.jar` contains `xaero/`, `xaeroplus/`, and `META-INF/licenses/THIRD_PARTY_NOTICES.txt`.

## Required Manual Actions

- Confirm whether redistributing the bundled XaeroLib, Xaero Minimap, and Xaero World Map classes/resources inside `devils-addon` is permitted.
- If redistribution is not permitted, replace bundled Xaero payload with legal runtime dependencies or a thin integration layer that requires users to install upstream mods separately.
- If redistribution is permitted, keep explicit permission/license evidence in the repository and update third-party notices accordingly.
- Do not open the repository as public and do not publish a public release with the current bundled Xaero payload until this is resolved.
