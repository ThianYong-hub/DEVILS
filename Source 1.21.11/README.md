# 1.21.11 Test Port Bundle

This directory contains the branch-ready test port of Devils Addon for Minecraft `1.21.11`.

## Included in This Branch

- `devils-addon-1.21.11-test.jar`
  - built test jar for quick launcher checks
- `devils-addon-1.21.11-test.zip`
  - compact source snapshot of the `1.21.11` test port
- `devils-addon-1.21.11-branch/`
  - unpacked source snapshot without local `build/.gradle` artifacts
- `PORTING-STATUS-1.21.11.md`
  - current status and validation notes
- `LATEST-TEST-PORT.txt`
  - path pointer to the tracked source snapshot

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

- This is a test port, not a release branch for production use.
- The port is build-tested, not runtime-verified in Minecraft.
