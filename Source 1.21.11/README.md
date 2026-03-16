# 1.21.11 Test Port Bundle

This directory contains the branch-ready test port of Devils Addon for Minecraft `1.21.11`.

## Included in This Branch

- `devils-addon-1.21.11-test.jar`
  - built test jar for quick launcher checks and GitHub prerelease asset upload
- `devils-addon-1.21.11-test.zip`
  - compact source snapshot of the `1.21.11` test port
- `devils-addon-1.21.11-branch/`
  - unpacked source snapshot without local `build/.gradle` artifacts
- `PORTING-STATUS-1.21.11.md`
  - current status and validation notes
- `LATEST-TEST-PORT.txt`
  - path pointer to the tracked source snapshot

## GitHub Release

- Branch: `DevilsAddon-1.21.11-test`
- Release tag: `v0.0.32-test.1.21.11`
- Release type: prerelease
- Main asset: `devils-addon-1.21.11-test.jar`
- Release page: `https://github.com/ThianYong-hub/DEVILS/releases/tag/v0.0.32-test.1.21.11`

## Snapshot Contents

The tracked source snapshot includes:

- updated addon sources for `1.21.11`
- embedded `ChestTracker` migration in `chesttracker-port-1.21.11`
- Gradle wrapper and build files needed to rebuild the test port
- project docs and local `SyncHub` sources used by the addon

The tracked source snapshot intentionally excludes:

- `.gradle/`
- `build/`
- `embedded-libs/`
- the obsolete `chesttracker-port/`
- local reverse-engineering/reference folders such as `git-sources/`, `decompiled/`, `jars/`, and `tools/`

## Build

From `Source 1.21.11/devils-addon-1.21.11-branch`:

```powershell
./gradlew.bat clean build
```

## Notes

- This is a dedicated `1.21.11` test branch, separated from the stable `1.21.8` release line.
- The port is build-tested, not runtime-verified in Minecraft.
